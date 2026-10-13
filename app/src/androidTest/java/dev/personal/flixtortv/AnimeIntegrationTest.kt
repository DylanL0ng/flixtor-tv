package dev.personal.flixtortv

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** Opt-in QA emulator only. Network fixtures and storage are isolated from the household. */
@RunWith(AndroidJUnit4::class)
class AnimeIntegrationTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrument.targetContext
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun label(node: AccessibilityNodeInfo) = node.contentDescription?.toString() ?: node.text?.toString().orEmpty()
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            node.refresh()
            if (predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrument.uiAutomation.rootInActiveWindow)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("$message; focus=${focusedLabel()}", condition())
    }
    private fun focused(id: Int): Boolean {
        return focusedLabel()?.substringBefore(" · ") == target.getString(id)
    }
    private fun focusedLabel(): String? {
        data class Node(val label: String, val bounds: Rect, val focused: Boolean)
        fun read(node: AccessibilityNodeInfo?): List<Node> {
            if (node == null) return emptyList()
            node.refresh()
            val own = Node(label(node), Rect().also(node::getBoundsInScreen), node.isFocused)
            val children = (0 until node.childCount).flatMap { read(node.getChild(it)) }
            @Suppress("DEPRECATION")
            node.recycle()
            return listOf(own) + children
        }
        val nodes = read(instrument.uiAutomation.rootInActiveWindow)
        val focused = nodes.firstOrNull { it.focused } ?: return null
        return focused.label.ifBlank {
            nodes.firstOrNull { focused.bounds.contains(it.bounds) && it.label.isNotBlank() &&
                it.bounds.width() >= focused.bounds.width() / 2 }?.label.orEmpty()
        }
    }
    private fun key(code: Int) {
        instrument.sendKeyDownUpSync(code); instrument.waitForIdleSync(); Thread.sleep(100)
    }
    private fun capture(name: String) {
        val bitmap = instrument.uiAutomation.takeScreenshot()
        val folder = target.getExternalFilesDir(null)!!
        File(folder, "$name-${bitmap.width}x${bitmap.height}.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private fun fragment(html: String) = JSONObject().put("status", true).put("html", html).toString()
    private fun embed(version: String): String {
        val data = JSONObject().put("src", "https://media.example/qa-player.m3u8?version=$version")
            .put("subtitles", org.json.JSONArray().put(JSONObject().put("src", "https://media.example/en.vtt")
                .put("label", "English").put("default", true)))
        val key = "otaku-embed-v1".toByteArray()
        val raw = data.toString().toByteArray().mapIndexed { i, b -> (b.toInt() xor key[i % key.size].toInt()).toByte() }.toByteArray()
        return "window.__P=\"${Base64.getEncoder().encodeToString(raw)}\""
    }
    @Test fun animeNavigationVersionsResumeAutoplayAndCancellation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("animeQA") == "true")
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        instrument.setInTouchMode(false)
        val prefix = "anime_test_${UUID.randomUUID()}_"
        val folder = File(target.cacheDir, prefix).apply { mkdirs() }
        val prefs = mutableSetOf<String>()
        val app = object : Application() {
            init { attachBaseContext(object : ContextWrapper(target) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    prefs.add(prefix + name)
                    return super.getSharedPreferences(prefix + name, mode)
                }
                override fun getFilesDir() = folder
            }) }
        }
        val requests = CopyOnWriteArrayList<String>()
        val media = CopyOnWriteArrayList<okhttp3.Request>()
        val playlist = instrument.context.assets.open("qa-player.m3u8").use { it.readBytes() }
        val segment = instrument.context.assets.open("qa-player-0.ts").use { it.readBytes() }
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); media.add(request)
            val body = when (request.url.encodedPath) {
                "/qa-player.m3u8" -> playlist
                "/qa-player-0.ts" -> segment
                "/en.vtt" -> "WEBVTT\n\n00:00.000 --> 00:59.000\nAnime caption fixture\n".toByteArray()
                else -> error("Unexpected media request")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/octet-stream".toMediaType())).build()
        }.build()
        val card = """<div class="film_list-wrap"><div class="flw-item"><h3 class="film-name">
            <a href="/fixture-1335" title="Anime fixture">Anime fixture</a></h3><span class="fdi-item">TV</span></div></div>"""
        val eps = (1..4).joinToString("") { """<a class="ep-item" data-id="${100 + it}" data-number="$it" title="Anime episode $it"></a>""" }
        var missingDub = false
        var serverError = false
        var catalogError = false
        var slowGate: CompletableDeferred<String>? = null
        val anime = AnimeClient(transport) { url ->
            requests.add(url)
            when {
                url.contains("keyword=slow") -> slowGate!!.await()
                url.contains("keyword=empty") -> "<div class=film_list-wrap></div>"
                url.contains("/search") || url.contains("/most-popular") -> {
                    if (catalogError) throw SiteException(Failure.NETWORK)
                    card
                }
                url.contains("/episode/list/") -> fragment(eps)
                url.contains("/episode/servers?") -> {
                    if (serverError) throw SiteException(Failure.NETWORK)
                    val modes = if (missingDub && url.endsWith("=102")) listOf("sub") else listOf("dub", "sub")
                    fragment(modes.joinToString("") { mode ->
                        val hash = Base64.getEncoder().encodeToString("https://embed.example/$mode".toByteArray())
                        """<div class="server-item" data-type="$mode" data-server-name="ZokoAnime" data-hash="$hash"></div>"""
                    })
                }
                url.startsWith("https://embed.example/") -> embed(url.substringAfterLast('/'))
                else -> "<div class=anisc-detail><div class=film-description><div class=text>Anime fixture synopsis</div></div></div>"
            }
        }
        val store = ViewModelStore()
        lateinit var vm: TvViewModel
        lateinit var mutable: MutableStateFlow<TvState>
        main {
            vm = TvViewModel(app, FlixtorClient(SessionStore(app)) { throw SiteException(Failure.NETWORK) }, anime = anime)
            store.put("anime", vm)
            @Suppress("UNCHECKED_CAST")
            mutable = TvViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(vm) as MutableStateFlow<TvState>
            val profile = vm.state.value.profiles.first()
            mutable.value = TvState(screen = Screen.HOME, profile = profile, profiles = listOf(profile))
        }
        val activity = instrument.startActivitySync(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        main { activity.setContent { MaterialTheme { TvApp(vm) } } }
        try {
            await("Home rail has focus") { focused(R.string.home) }
            repeat(8) { if (!focused(R.string.anime)) key(KeyEvent.KEYCODE_DPAD_DOWN) }
            assertTrue("Remote reaches Anime rail", focused(R.string.anime))
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Popular anime loads") { !vm.state.value.loading && vm.state.value.titles.isNotEmpty() }
            await("Catalog title fits the viewport") {
                find { label(it) == "Anime fixture" && it.isVisibleToUser &&
                    Rect().also(it::getBoundsInScreen).height() > 0 } != null
            }
            instrument.waitForIdleSync(); capture("anime-browse")
            val title = vm.state.value.titles.first()
            main { vm.open(title) }
            await("Anime detail episodes") { !vm.state.value.loading && vm.state.value.detail?.episodes?.size == 4 }
            instrument.waitForIdleSync(); capture("anime-detail")
            val first = vm.state.value.detail!!.ordered.first()
            val second = vm.state.value.detail!!.ordered[1]
            main { vm.play(first) }
            await("Manual playback asks version") { vm.state.value.animeChoice?.loading == false }
            await("Dub receives remote focus") { focused(R.string.anime_dub) }
            capture("anime-chooser")
            assertTrue(media.isEmpty())
            key(KeyEvent.KEYCODE_BACK)
            await("Cancel stays in detail") { vm.state.value.animeChoice == null && vm.state.value.screen == Screen.DETAIL }
            val profileId = vm.state.value.profile!!.id
            LibraryStore(app, profileId).save(Viewing(title, first, 15000, 60000, AnimeVersion.DUB))
            LibraryStore(app, profileId).save(Viewing(title, first, 5000, 60000, AnimeVersion.SUB))
            main { vm.play(first) }
            await("Choose dub again") { focused(R.string.anime_dub) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Native HLS playback starts") { vm.state.value.stream != null && media.any { it.url.encodedPath.endsWith(".ts") } }
            assertEquals(AnimeVersion.DUB, vm.state.value.version)
            assertEquals(15000L, vm.state.value.resumePosition)
            await("Captions requested") { media.any { it.url.encodedPath == "/en.vtt" } }
            assertTrue(media.all { it.header("Referer") == "https://embed.example/" })
            assertTrue(media.all { it.header("Cookie") == null })
            Thread.sleep(1000)
            capture("anime-playback")
            repeat(6) { if (!focused(R.string.episodes)) key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            assertTrue("Remote must reach Episodes", focused(R.string.episodes))
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            instrument.waitForIdleSync(); capture("anime-drawer")
            repeat(6) { if (focusedLabel()?.contains(second.name) != true) key(KeyEvent.KEYCODE_DPAD_DOWN) }
            assertTrue("Remote reaches second episode", focusedLabel()?.contains(second.name) == true)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Drawer/manual episode asks again") { vm.state.value.animeChoice?.loading == false }
            main { vm.cancelAnimeChoice() }
            assertEquals(first.key, vm.state.value.episode?.key)
            assertEquals(AnimeVersion.DUB, vm.state.value.version)
            await("Cancel restores episode drawer focus") { focusedLabel()?.contains(second.name) == true }
            key(KeyEvent.KEYCODE_BACK)
            // Simulate completed playback through the actual attendance/autoplay entry points.
            val token = vm.watchingSession.token
            main { vm.playbackEnded(token); vm.autoplayNext(token, second) }
            await("Autoplay retains dub without waiting for selection") { vm.state.value.episode?.key == second.key && vm.state.value.stream != null }
            assertEquals(AnimeVersion.DUB, vm.state.value.version)
            main { vm.back(); vm.play(first) }
            await("Choose first episode") { vm.state.value.animeChoice?.loading == false }
            main { vm.chooseAnimeVersion(AnimeVersion.DUB) }
            await("First episode ready") { vm.state.value.stream != null }
            missingDub = true
            val missingToken = vm.watchingSession.token
            main { vm.playbackEnded(missingToken); vm.autoplayNext(missingToken, second) }
            await("Missing dub stops autoplay") { vm.state.value.animeChoice?.missing == AnimeVersion.DUB }
            assertEquals(first.key, vm.state.value.episode?.key)
            assertEquals(setOf(AnimeVersion.SUB), vm.state.value.animeChoice?.available)
            main { vm.cancelAnimeChoice(); vm.autoplayNext(missingToken, second) }
            assertNull(vm.state.value.animeChoice)
            assertTrue(vm.state.value.animeAutoplayBlocked)
            main { vm.play(second) }
            await("Sub-only selection") { vm.state.value.animeChoice?.loading == false }
            main { vm.chooseAnimeVersion(AnimeVersion.SUB) }
            await("Explicit sub starts") { vm.state.value.stream != null && vm.state.value.version == AnimeVersion.SUB }
            main { vm.markSegment(SegmentType.INTRO, 1000, 3000) }
            assertEquals(1, vm.markedSegments().size)
            assertTrue(SkipStore(app).segments(title, second, AnimeVersion.DUB).isEmpty())
            assertFalse(vm.canSubmit)
            main { vm.back(); vm.back() }
            assertEquals(Screen.ANIME, vm.state.value.screen)
            assertEquals(title.key, vm.state.value.focusedKey)
            // Failed, empty and rapidly superseded searches must not leak stale results.
            catalogError = true
            main { vm.searchAnime("failure") }
            await("Source error") { vm.state.value.failure == Failure.NETWORK }
            assertFalse(vm.state.value.account.signedIn)
            catalogError = false
            main { vm.refresh() }
            await("Source retry") { !vm.state.value.loading && vm.state.value.failure == null }
            main { vm.searchAnime("empty") }
            await("Successful empty") { !vm.state.value.loading && vm.state.value.titles.isEmpty() }
            slowGate = CompletableDeferred()
            main { vm.searchAnime("slow") }
            await("Slow request pending") { requests.any { it.contains("keyword=slow") } }
            assertTrue(vm.state.value.loading)
            assertNull(find { label(it) == target.getString(R.string.empty_results) })
            main { vm.searchAnime("latest") }
            await("Latest request wins") { !vm.state.value.loading && vm.state.value.titles.isNotEmpty() }
            slowGate.complete("<div class=film_list-wrap></div>")
            Thread.sleep(200)
            assertEquals("latest", vm.state.value.animeQuery)
            assertEquals(1, vm.state.value.titles.size)
            // Server retry preserves pending intent; cancellation cannot start playback.
            main { vm.open(title) }
            await("Reopen detail") { !vm.state.value.loading && vm.state.value.detail?.episodes?.size == 4 }
            serverError = true
            main { vm.play(first) }
            await("Chooser error") { vm.state.value.animeChoice?.failure == Failure.NETWORK }
            serverError = false
            main { vm.retryAnimeChoice() }
            await("Chooser retry") { vm.state.value.animeChoice?.loading == false && vm.state.value.animeChoice?.failure == null }
            main { vm.cancelAnimeChoice(); vm.toggle(title) }
            assertEquals(ContentSource.HIANIME, LibraryStore(app, profileId).favorites().single().source)
            assertTrue(LibraryStore(app, "other").viewing().isEmpty())
            // Anime films resolve their provider episode but retain film history semantics.
            main { vm.open(title.copy(type = ContentType.MOVIE)) }
            await("Film detail") { !vm.state.value.loading }
            main { vm.play(null) }
            await("Film chooser") { vm.state.value.animeChoice?.loading == false }
            main { vm.chooseAnimeVersion(AnimeVersion.DUB) }
            await("Film playback") { vm.state.value.stream != null }
            assertNull(vm.state.value.episode)
        } finally {
            main { activity.finish(); store.clear() }
            prefs.forEach { app.getSharedPreferences(it.removePrefix(prefix), Context.MODE_PRIVATE).edit().clear().commit() }
            folder.deleteRecursively()
        }
    }

    /** Separate opt-in live probe. It never reads or changes household accounts/progress. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun liveSourcePlaysBothVersionsWithNativeMedia3() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAnime") == "true")
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val client = AnimeClient()
        var player: ExoPlayer? = null
        var activePlayer by mutableStateOf<ExoPlayer?>(null)
        val activity = instrument.startActivitySync(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        main { activity.setContent {
            AndroidView(factory = { PlayerView(it).apply { useController = false } }, update = { it.player = activePlayer })
        } }
        try {
            val title = client.browse("naruto", 1).first { it.name.equals("Naruto", true) }
            val detail = client.detail(title)
            assertTrue(detail.episodes.isNotEmpty())
            val episode = detail.ordered.first()
            assertTrue(client.servers(episode).keys.containsAll(AnimeVersion.entries))
            for (version in AnimeVersion.entries) {
                val stream = client.playback(episode, version)
                val advanced = AtomicBoolean()
                val error = AtomicInteger()
                main {
                    player?.release()
                    val http = OkHttpDataSource.Factory(client.mediaClient).setDefaultRequestProperties(stream.headers)
                    player = ExoPlayer.Builder(target).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().apply {
                        addListener(object : Player.Listener {
                            override fun onPlayerError(failure: PlaybackException) { error.set(failure.errorCode) }
                        })
                        setMediaItem(MediaItem.Builder().setUri(stream.uri).setMimeType(stream.mime).build())
                        prepare(); play()
                    }
                    activePlayer = player
                }
                val deadline = SystemClock.elapsedRealtime() + 30000
                while (!advanced.get() && error.get() == 0 && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(100)
                    main { advanced.set(player!!.isPlaying && player!!.currentPosition > 3000) }
                }
                // Emit only a category; failures must never print signed provider URLs.
                assertEquals("Native ${version.wire} decoder error category", 0, error.get())
                assertTrue("Native ${version.wire} playback must advance", advanced.get())
            }
        } finally {
            main { activePlayer = null; player?.release(); activity.finish() }
            client.close()
        }
    }
}
