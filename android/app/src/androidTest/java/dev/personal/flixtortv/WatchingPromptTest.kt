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
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Opt-in emulator only; isolated household storage, offline media, no live service requests. */
@RunWith(AndroidJUnit4::class)
class WatchingPromptTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrument.targetContext
    private data class Node(val label: String, val bounds: Rect, val focused: Boolean)
    private fun nodes(): List<Node> {
        fun read(node: AccessibilityNodeInfo?): List<Node> {
            if (node == null) return emptyList()
            node.refresh()
            val own = Node(node.contentDescription?.toString() ?: node.text?.toString().orEmpty(),
                Rect().also(node::getBoundsInScreen), node.isFocused)
            val children = (0 until node.childCount).flatMap { read(node.getChild(it)) }
            @Suppress("DEPRECATION")
            node.recycle()
            return listOf(own) + children
        }
        val raw = read(instrument.uiAutomation.rootInActiveWindow)
        return raw.map { node ->
            if (node.focused && node.label.isBlank()) node.copy(label = raw.firstOrNull {
                node.bounds.contains(it.bounds) && it.label.isNotBlank()
            }?.label.orEmpty()) else node
        }
    }
    private fun text(id: Int) = target.getString(id)
    private fun visible(id: Int) = nodes().any { it.label == text(id) }
    private fun focused() = nodes().firstOrNull { it.focused && it.label.isNotBlank() }?.label
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("$message; focus=${focused()}; nodes=${nodes()}", condition())
    }
    private fun key(code: Int) { instrument.sendKeyDownUpSync(code); Thread.sleep(100) }

    @Test fun confirmationGatesAutoplayAndOwnsFocusTimeoutAndLifecycle() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("watchingQA") == "true")
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        instrument.setInTouchMode(false)
        instrument.uiAutomation
        val activity = instrument.startActivitySync(Intent(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val prefix = "watching_test_${UUID.randomUUID()}_"
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
        val store = ViewModelStore()
        val requests = CopyOnWriteArrayList<String>()
        val time = AtomicLong()
        lateinit var vm: TvViewModel
        lateinit var mutable: MutableStateFlow<TvState>
        fun main(block: () -> Unit) = instrument.runOnMainSync(block)
        main {
            vm = TvViewModel(app, FlixtorClient(SessionStore(app)) { path ->
                if (path == "/home") "<meta name=csrf-token content=fixture>"
                else { requests.add(path); throw SiteException(Failure.MEDIA) }
            })
            store.put("watching", vm)
            @Suppress("UNCHECKED_CAST")
            mutable = TvViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }
                .get(vm) as MutableStateFlow<TvState>
        }
        listOf("qa-player.m3u8", "qa-player-0.ts").forEach { name ->
            instrument.context.assets.open(name).use { input ->
                File(folder, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        val stream = Stream(File(folder, "qa-player.m3u8").toURI().toString(), emptyList())
        val profile = vm.state.value.profiles.first()
        val episodes = (1..6).map { Episode(1, it, "Watching fixture $it") }
        val title = Title("9844", "/watch/tv/9844/fixture", "Still watching fixture")
        val base = TvState(screen = Screen.PLAYER, profile = profile, profiles = listOf(profile),
            stream = stream, detail = Detail(title, episodes), episode = episodes[2], resumePosition = 57_000,
            playbackIdentity = PlaybackIdentity(profile.id, title, episodes[2]))

        fun render() {
            main {
                activity.setContent {
                    MaterialTheme {
                        BoxWithConstraints(Modifier.fillMaxSize().background(Midnight)) {
                            CompositionLocalProvider(LocalSafePadding provides PaddingValues(maxWidth * .05f, maxHeight * .05f)) {
                                val state by vm.state.collectAsState()
                                val media = state.stream
                                if (state.screen == Screen.PLAYER && media != null) PlaybackScreen(media, state, vm)
                                else Text("Fixture ${state.screen}")
                            }
                        }
                    }
                }
            }
            instrument.waitForIdleSync()
        }
        fun start(autoplay: Boolean = true, eligible: Boolean = true, movie: Boolean = false, finale: Boolean = false) {
            main { activity.setContent { }; vm.back() }
            instrument.waitForIdleSync()
            Thread.sleep(250) // setContent schedules a frame after the main queue first becomes idle.
            instrument.waitForIdleSync()
            requests.clear(); time.set(0)
            main {
                vm.watchingSession = WatchingSession(time::get, minimumWatchMs = 1)
                if (eligible) repeat(2) {
                    val token = vm.watchingSession.start(automatic = true)
                    vm.watchingSession.playback(token, true, true); time.incrementAndGet()
                    vm.watchingSession.complete(token, episode = true)
                }
                vm.watchingSession.start(automatic = true)
                mutable.value = base.copy(profile = profile.copy(autoplay = autoplay),
                    detail = if (movie) Detail(Title("9844", "/watch/movie/9844/fixture", "Movie fixture"), emptyList()) else base.detail,
                    episode = if (movie) null else if (finale) episodes.last() else episodes[2])
            }
            render()
        }
        fun prompt() {
            start()
            await("Current media plays before any attendance prompt") { visible(R.string.pause) }
            assertFalse(visible(R.string.still_watching_title))
            await("Episode end raises confirmation with Continue focused") {
                visible(R.string.still_watching_title) && focused() == text(R.string.continue_watching)
            }
            assertTrue("Next stream must not be fetched before confirmation", requests.isEmpty())
            assertTrue("Completion saved before confirmation", LibraryStore(app, profile.id).viewing().first().finished)
        }
        fun assertStopped() {
            await("Stop returns to title page and releases screen-awake flag") {
                vm.state.value.screen == Screen.DETAIL &&
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0
            }
            assertNull(vm.state.value.stillWatchingSeconds)
            assertFalse(vm.watchingSession.pending)
            assertTrue(requests.isEmpty())
        }
        fun advance(ms: Long) { main { time.addAndGet(ms) } }
        try {
            prompt()
            val size = InstrumentationRegistry.getArguments().getString("captureSize", "1080")
            val bitmap = instrument.uiAutomation.takeScreenshot()
            File(target.getExternalFilesDir(null), "still-watching-$size.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            main { vm.autoplayNext(vm.watchingSession.token, episodes[3]); vm.play(episodes[3]) }
            assertTrue("Stale countdown and manual transport cannot bypass latched prompt", requests.isEmpty())
            key(KeyEvent.KEYCODE_MEDIA_PLAY); key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            assertTrue(vm.watchingSession.pending); assertTrue(requests.isEmpty())
            advance(15_000)
            await("Response window ticks") { vm.state.value.stillWatchingSeconds == 45 }
            val body = nodes().first { it.label == text(R.string.still_watching_help) }.bounds
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    body.exactCenterX(), body.exactCenterY(), 0)
                instrument.sendPointerSync(event); event.recycle()
            }
            await("Pointer input restarts countdown without confirming") { vm.state.value.stillWatchingSeconds == 60 }
            assertTrue(vm.watchingSession.pending); assertTrue(requests.isEmpty())
            advance(15_000)
            await("Countdown ticks again") { vm.state.value.stillWatchingSeconds == 45 }
            key(KeyEvent.KEYCODE_DPAD_LEFT) // Android consumes the first key to restore focus after touch input.
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Navigation resets response window and reaches Stop") {
                vm.state.value.stillWatchingSeconds == 60 && focused() == text(R.string.stop_watching)
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Continue fetches next episode with one bounded recovery") {
                requests.count { it.startsWith("/ajax/v4/e/") } == 2 && !vm.state.value.loading
            }
            assertEquals(episodes[3], vm.state.value.episode)
            assertFalse(vm.watchingSession.pending)

            prompt(); key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_CENTER); assertStopped()
            prompt(); key(KeyEvent.KEYCODE_BACK); assertStopped()
            prompt()
            advance(10_000)
            await("Timeout elapsed before backgrounding") { vm.state.value.stillWatchingSeconds == 50 }
            key(KeyEvent.KEYCODE_HOME)
            await("Activity stops") { !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
            val remaining = vm.state.value.stillWatchingSeconds
            advance(1_000_000); Thread.sleep(600)
            assertEquals("Background does not consume response window", remaining, vm.state.value.stillWatchingSeconds)
            main { activity.startActivity(Intent(target, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            await("Return preserves pending prompt") { visible(R.string.still_watching_title) }
            assertEquals(remaining, vm.state.value.stillWatchingSeconds)
            val completed = LibraryStore(app, profile.id).viewing().first()
            main { activity.setContent { } }
            instrument.waitForIdleSync()
            Thread.sleep(250)
            instrument.waitForIdleSync()
            render()
            await("Replacing composition restores pending prompt and focus") {
                visible(R.string.still_watching_title) && focused() == text(R.string.continue_watching)
            }
            assertEquals("Recreated prompt preserves completed position", completed.position,
                LibraryStore(app, profile.id).viewing().first().position)
            assertEquals(completed.duration, LibraryStore(app, profile.id).viewing().first().duration)
            advance(60_000); assertStopped()

            start(eligible = false)
            await("Ordinary episode end keeps Next episode card") { visible(R.string.play_next) }
            assertFalse(visible(R.string.still_watching_title)); assertTrue(requests.isEmpty())
            await("Ordinary autoplay fetches next episode after countdown") { requests.any { it.startsWith("/ajax/v4/e/") } }
            start(autoplay = false)
            await("Autoplay off keeps manual Play next") { visible(R.string.play_next) }
            Thread.sleep(5500)
            assertFalse(visible(R.string.still_watching_title)); assertTrue(requests.isEmpty())
            for (movie in listOf(true, false)) {
                start(movie = movie, finale = !movie)
                await("Movie/finale finishes without confirmation") { vm.watchingSession.finished }
                assertFalse(visible(R.string.still_watching_title)); assertTrue(requests.isEmpty())
            }
        } finally {
            main { activity.setContent { }; store.clear(); activity.finish() }
            folder.deleteRecursively()
            prefs.forEach { target.deleteSharedPreferences(it) }
        }
    }
}
