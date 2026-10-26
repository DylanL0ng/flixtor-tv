package dev.personal.flixtortv

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Isolated service fixtures and intercepted offline HLS; no account or live media required. */
@RunWith(AndroidJUnit4::class)
class PlaybackRecoveryTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(message, condition())
    }

    private inner class Fixture : AutoCloseable {
        val prefix = "recovery_${UUID.randomUUID()}_"
        val folder = File(instrument.targetContext.cacheDir, prefix).apply { mkdirs() }
        private val prefs = mutableSetOf<String>()
        val app = object : Application() {
            init { attachBaseContext(object : ContextWrapper(instrument.targetContext) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    prefs.add(prefix + name)
                    return super.getSharedPreferences(prefix + name, mode)
                }
                override fun getFilesDir() = folder
                override fun getCacheDir() = folder
            }) }
        }
        val requests = CopyOnWriteArrayList<String>()
        val streamFailures = AtomicInteger()
        @Volatile var streamFailure = Failure.SIGNED_OUT
        @Volatile var homeFailure: Failure? = null
        @Volatile var homeGate: CompletableDeferred<Unit>? = null
        @Volatile var guest = false
        val badMediaRequests = AtomicInteger()
        val mediaFailure = AtomicInteger()
        val title = Title("1", "/watch/tv/1/fixture", "Fixture show")
        val episodes = (1..3).map { Episode(1, it, "Episode $it") }
        val media = OkHttpClient.Builder().addInterceptor { chain ->
            if (mediaFailure.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
                badMediaRequests.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(403).message("Forbidden").body("".toResponseBody()).build()
            } else {
                val name = chain.request().url.pathSegments.last()
                val bytes = instrument.context.assets.open(name).use { it.readBytes() }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(bytes.toResponseBody()).build()
            }
        }.build()
        val client = FlixtorClient(SessionStore(app), httpClient = media) { path ->
            requests.add(path)
            when {
                path == "/home" -> {
                    homeGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
                    homeFailure?.let { throw SiteException(it) }
                    "<meta name=csrf-token content=fixture><div class=contentListM><div class=cflip " +
                        "data-href='/watch/movie/2/fixture'><div class=front><div class=card-footer>Fixture movie</div></div></div></div>" +
                        if (guest) "" else "<a class=logout>Sign out</a>"
                }
                path == "/account" -> "<a class=logout>Sign out</a><span class=acct-status>VIP Active</span>"
                path.startsWith("/watch/") -> "<meta property='og:type' content='video.movie'>"
                path.startsWith("/ajax/v4/") -> {
                    if (streamFailures.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) throw SiteException(streamFailure)
                    """{"file":"https://media.example/qa-player.m3u8"}"""
                }
                else -> error("Unexpected fixture request: $path")
            }
        }
        val store = ViewModelStore()
        lateinit var vm: TvViewModel
        lateinit var mutable: MutableStateFlow<TvState>
        init {
            runBlocking { client.bootstrap() }
            requests.clear()
            main {
                vm = TvViewModel(app, client)
                store.put("recovery", vm)
                @Suppress("UNCHECKED_CAST")
                mutable = TvViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }
                    .get(vm) as MutableStateFlow<TvState>
                val profile = vm.state.value.profiles.first()
                mutable.value = TvState(screen = Screen.DETAIL, profile = profile, profiles = listOf(profile),
                    detail = Detail(title, episodes), account = Account(AccountStatus.VIP), accountChecked = true)
            }
        }
        fun start() { main { vm.play(episodes.first()) }; ready() }
        fun ready() = await("Playback request finishes: ${requests.toList()}") { !vm.state.value.loading }
        fun fail(position: Long = 58_000, play: Boolean = false, failure: Failure = Failure.ACCESS) = main {
            vm.playerFailure(failure, vm.watchingSession.token, vm.state.value.playerGeneration, position, 60_000, play)
        }
        override fun close() {
            homeGate?.complete(Unit)
            main { store.clear() }
            prefs.forEach { instrument.targetContext.deleteSharedPreferences(it) }
            folder.deleteRecursively()
        }
    }

    @Test fun startsAutoplayMoviesAndGuestPlaybackRefreshOnlyAfterFailure() {
        Fixture().use { f ->
            f.start()
            assertEquals(listOf("/ajax/v4/e/1/1/1"), f.requests.toList())
            for (failure in Failure.values()) {
                f.requests.clear()
                f.streamFailure = failure
                f.streamFailures.set(1)
                main { f.vm.play(f.episodes.first()) }
                f.ready()
                assertNull(f.vm.state.value.failure)
                assertNotNull(f.vm.state.value.stream)
                assertEquals(listOf("/ajax/v4/e/1/1/1", "/home", "/account", "/ajax/v4/e/1/1/1"), f.requests.toList())
            }
            f.requests.clear()
            f.streamFailures.set(1)
            main {
                val token = f.vm.watchingSession.token
                f.vm.playbackEnded(token)
                assertTrue(f.vm.autoplayNext(token, f.episodes[1]))
            }
            f.ready()
            assertEquals(f.episodes[1], f.vm.state.value.episode)
            assertEquals(listOf("/ajax/v4/e/1/1/2", "/home", "/account", "/ajax/v4/e/1/1/2"), f.requests.toList())

            f.requests.clear()
            f.guest = true
            f.streamFailures.set(1)
            main { f.vm.playTitle(Title("2", "/watch/movie/2/fixture", "Fixture movie")) }
            f.ready()
            assertEquals(listOf("/watch/movie/2/fixture", "/ajax/v4/m/2", "/home", "/ajax/v4/m/2"), f.requests.toList())
            assertEquals(AccountStatus.GUEST, f.vm.state.value.account.status)
            assertNull(f.vm.state.value.failure)
        }
    }

    @Test fun recoveryBudgetIsSharedAndExplicitRetryRestoresIt() {
        Fixture().use { f ->
            f.streamFailures.set(1)
            f.start()
            f.requests.clear()
            f.fail()
            assertEquals(Failure.ACCESS, f.vm.state.value.failure)
            assertTrue(f.requests.isEmpty())
            main { f.vm.refresh() }
            f.ready()
            f.requests.clear()
            val stream = f.vm.state.value.stream
            val token = f.vm.watchingSession.token
            val generation = f.vm.state.value.playerGeneration
            val gate = CompletableDeferred<Unit>()
            f.homeGate = gate
            f.fail()
            await("Silent refresh begins") { "/home" in f.requests }
            assertTrue(f.vm.state.value.loading)
            assertNull(f.vm.state.value.failure)
            main { f.vm.playerFailure(Failure.MEDIA, token, generation, 0, 0, true) }
            gate.complete(Unit)
            f.ready()
            assertEquals(listOf("/home", "/account", "/ajax/v4/e/1/1/1"), f.requests.toList())
            assertEquals(stream, f.vm.state.value.stream)
            assertEquals(token, f.vm.watchingSession.token)
            assertEquals(generation + 1, f.vm.state.value.playerGeneration)
            assertEquals(58_000L, f.vm.state.value.resumePosition)
            assertFalse(f.vm.state.value.resumePlayWhenReady)
            main { f.vm.playerFailure(Failure.MEDIA, token, generation, 0, 0, true) }
            assertNull(f.vm.state.value.failure)
            f.requests.clear()
            f.fail(failure = Failure.UNAVAILABLE)
            assertEquals(Failure.UNAVAILABLE, f.vm.state.value.failure)
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun refreshAndRetryFailuresRemainActionableAndDoNotLoop() {
        Fixture().use { f ->
            f.homeFailure = Failure.NETWORK
            f.streamFailures.set(1)
            main { f.vm.play(f.episodes.first()) }
            f.ready()
            assertEquals(Failure.NETWORK, f.vm.state.value.failure)
            assertEquals(listOf("/ajax/v4/e/1/1/1", "/home"), f.requests.toList())
            f.homeFailure = null
            f.requests.clear()
            f.streamFailure = Failure.VIP
            f.streamFailures.set(2)
            main { f.vm.refresh() }
            f.ready()
            assertEquals(Failure.VIP, f.vm.state.value.failure)
            assertEquals(4, f.requests.size)
        }
    }

    @Test fun cancelledRecoveryCannotRevivePlaybackOrOverwriteAccount() {
        Fixture().use { f ->
            for (action in listOf("back", "episode", "profile", "signout")) {
                main {
                    val profile = f.vm.state.value.profiles.first()
                    f.mutable.value = TvState(screen = Screen.DETAIL, detail = Detail(f.title, f.episodes),
                        profile = profile, profiles = listOf(profile), account = Account(false), accountChecked = true)
                }
                f.start()
                val gate = CompletableDeferred<Unit>()
                f.homeGate = gate
                f.requests.clear()
                f.fail()
                await("Refresh blocked before $action") { "/home" in f.requests }
                f.homeGate = null
                main {
                    when (action) {
                        "back" -> f.vm.back()
                        "episode" -> f.vm.play(f.episodes[1])
                        "profile" -> f.vm.selectProfile(f.vm.state.value.profiles.first().id)
                        "signout" -> f.vm.clearSession()
                    }
                }
                f.ready()
                val expected = f.vm.state.value
                gate.complete(Unit)
                Thread.sleep(100)
                instrument.waitForIdleSync()
                assertEquals("Late recovery after $action", expected, f.vm.state.value)
                assertNull(f.vm.state.value.failure)
            }
        }
    }

    @Test fun recoveryPreservesAttendanceAndPendingConfirmationAndSkipsAnime() {
        Fixture().use { f ->
            val time = AtomicLong()
            main { f.vm.watchingSession = WatchingSession(time::get, minimumWatchMs = 1000, minimumEpisodes = 1) }
            f.start()
            val token = f.vm.watchingSession.token
            main { f.vm.watchPlayback(token, true, true) }
            time.set(1500)
            f.fail(play = true)
            f.ready()
            assertTrue(f.vm.state.value.resumePlayWhenReady)
            main {
                f.vm.playbackEnded(token)
                assertTrue(f.vm.watchingSession.pending)
            }
            // A later attempt can recover while confirmation is pending without clearing it.
            main { f.vm.refresh() }
            f.ready()
            val pendingToken = f.vm.watchingSession.token
            main {
                f.vm.watchPlayback(pendingToken, true, true)
                time.addAndGet(1500)
                f.vm.playbackEnded(pendingToken)
            }
            val seconds = f.vm.state.value.stillWatchingSeconds
            f.fail()
            f.ready()
            assertTrue(f.vm.watchingSession.pending)
            assertTrue(f.vm.watchingSession.finished)
            assertEquals(seconds, f.vm.state.value.stillWatchingSeconds)
            f.requests.clear()
            main { f.mutable.value = f.vm.state.value.copy(failure = null,
                detail = Detail(f.title.copy(source = ContentSource.HIANIME), f.episodes)) }
            f.fail(failure = Failure.MEDIA)
            assertEquals(Failure.MEDIA, f.vm.state.value.failure)
            assertTrue(f.requests.isEmpty())
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun nativeMediaErrorRecoversSameUrlAndHonorsPauseAndBackground() {
        Fixture().use { f ->
            val activity = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            fun findPlayer(view: View): Player? {
                if (view is PlayerView) return view.player
                if (view is ViewGroup) repeat(view.childCount) { findPlayer(view.getChildAt(it))?.let { return it } }
                return null
            }
            fun player(): Player? { var result: Player? = null; main { result = findPlayer(activity.window.decorView) }; return result }
            fun readyPlayer(): Player {
                try {
                    await("Offline player ready") { var ready = false; main { ready = findPlayer(activity.window.decorView)?.playbackState == Player.STATE_READY }; ready }
                } catch (error: AssertionError) {
                    var status = "missing"
                    main { findPlayer(activity.window.decorView)?.let {
                        status = "${it.playbackState}/${it.playerError}/${it.currentPosition}; buffered=${it.bufferedPosition}; loading=${it.isLoading}; duration=${it.duration}"
                    } }
                    throw AssertionError("Offline player $status; state=${f.vm.state.value}; requests=${f.requests}", error)
                }
                return player()!!
            }
            try {
                main {
                    activity.setContent {
                        MaterialTheme { TvApp(f.vm) }
                    }
                    f.vm.play(f.episodes.first())
                }
                val first = readyPlayer()
                main { first.pause(); first.seekTo(45_000) }
                val gate = CompletableDeferred<Unit>()
                f.homeGate = gate
                f.mediaFailure.set(100)
                // Force a native HTTP failure on the existing item at its paused position.
                main { first.stop(); first.setMediaItem(first.currentMediaItem!!, 45_000); first.prepare() }
                await("Native media error starts account refresh") { f.vm.state.value.loading && "/account" !in f.requests && f.badMediaRequests.get() > 0 }
                assertNull(f.vm.state.value.failure)
                val token = f.vm.watchingSession.token
                assertEquals(45_000L, f.vm.state.value.resumePosition)
                f.mediaFailure.set(0)
                gate.complete(Unit)
                f.ready()
                await("Same URL creates a replacement player") { player()?.let { it !== first } == true }
                val second = player()!!
                assertNotSame(first, second)
                assertEquals(token, f.vm.watchingSession.token)
                main {
                    assertEquals(45_000L, second.currentPosition)
                    assertFalse(second.playWhenReady)
                    second.play()
                }
                readyPlayer()

                // Explicit Retry starts a new budget; a recovered item must stay paused in background.
                main { f.vm.refresh() }
                f.ready()
                val third = readyPlayer()
                val backgroundGate = CompletableDeferred<Unit>()
                f.homeGate = backgroundGate
                f.fail(position = 10_000, play = true)
                await("Background recovery waits") { f.vm.state.value.loading }
                instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_HOME)
                await("Activity enters background") { !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                backgroundGate.complete(Unit)
                f.ready()
                assertFalse(f.vm.state.value.resumePlayWhenReady)
                main {
                    activity.startActivity(Intent(instrument.targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                await("Activity resumes") { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                await("Background recovery creates replacement player") { player()?.let { it !== third } == true }
                val fourth = player()!!
                main { assertFalse(fourth.playWhenReady); fourth.play() }
                readyPlayer()
            } finally { main { activity.setContent { }; activity.finish() } }
        }
    }
}
