package dev.personal.flixtortv

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in, isolated local-media test. Never reads or modifies household marks. */
@RunWith(AndroidJUnit4::class)
class PlayerRegionsTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val target = instrument.targetContext
    private data class Node(val label: String, val bounds: Rect, val focused: Boolean, val clickable: Boolean)
    private fun nodes(): List<Node> {
        fun read(node: AccessibilityNodeInfo?): List<Node> {
            if (node == null) return emptyList()
            node.refresh()
            val own = Node(node.contentDescription?.toString() ?: node.text?.toString().orEmpty(),
                Rect().also(node::getBoundsInScreen), node.isFocused, node.isClickable)
            val children = (0 until node.childCount).flatMap { read(node.getChild(it)) }
            @Suppress("DEPRECATION")
            node.recycle()
            return listOf(own) + children
        }
        val raw = read(instrument.uiAutomation.rootInActiveWindow)
        return raw.map { node ->
            if (node.label.isBlank() && (node.clickable || node.focused)) node.copy(label = raw.firstOrNull {
                node.bounds.contains(it.bounds) && it.label.isNotBlank() && it.bounds.width() >= node.bounds.width() / 2
            }?.label.orEmpty()) else node
        }
    }
    private fun text(id: Int) = target.getString(id)
    private fun visible(id: Int) = nodes().any { it.label == text(id) }
    private fun focused() = nodes().firstOrNull { it.focused && it.label.isNotBlank() }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!condition() && SystemClock.uptimeMillis() < deadline) Thread.sleep(80)
        assertTrue("$message; focus=${focused()}; nodes=${nodes()}", condition())
    }
    private fun key(code: Int) { instrument.sendKeyDownUpSync(code); Thread.sleep(100) }
    private fun select(id: Int, direction: Int = KeyEvent.KEYCODE_DPAD_RIGHT) {
        repeat(25) {
            if (focused()?.label?.startsWith(text(id)) == true) { key(KeyEvent.KEYCODE_DPAD_CENTER); return }
            key(direction)
        }
        fail("Remote cannot reach ${text(id)}; focus=${focused()}")
    }
    private fun capture(name: String) {
        val bitmap = instrument.uiAutomation.takeScreenshot()
        val size = InstrumentationRegistry.getArguments().getString("captureSize", "1080")
        File(target.getExternalFilesDir(null), "$name-$size.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun regionsCreditsDrawerAndBufferUseRemoteAndKeepSessionState() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("playerRegionsQA") == "true")
        instrument.setInTouchMode(false)
        instrument.uiAutomation
        val activity = instrument.startActivitySync(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val prefix = "regions_test_${UUID.randomUUID()}_"
        val folder = File(target.cacheDir, prefix).apply { mkdirs() }
        val names = mutableSetOf<String>()
        val app = object : Application() {
            init { attachBaseContext(object : ContextWrapper(target) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    names.add(prefix + name); return super.getSharedPreferences(prefix + name, mode)
                }
                override fun getFilesDir() = folder
            }) }
        }
        val store = ViewModelStore()
        lateinit var vm: TvViewModel
        lateinit var mutable: MutableStateFlow<TvState>
        instrument.runOnMainSync {
            vm = TvViewModel(app, FlixtorClient(SessionStore(app)) { throw SiteException(Failure.MEDIA) })
            store.put("regions", vm)
            @Suppress("UNCHECKED_CAST")
            mutable = TvViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(vm) as MutableStateFlow<TvState>
        }
        listOf("qa-player.m3u8", "qa-player-0.ts").forEach { name ->
            instrument.context.assets.open(name).use { input -> File(folder, name).outputStream().use { input.copyTo(it) } }
        }
        val stream = Stream(File(folder, "qa-player.m3u8").toURI().toString(), emptyList())
        val episodes = (1..2).flatMap { season -> (1..25).map { Episode(season, it, "Season $season episode $it") } }
        val current = episodes[42]
        val title = Title("regions", "/watch/tv/regions", "Timeline and credits fixture")
        val profile = vm.state.value.profiles.first()
        val base = TvState(screen = Screen.PLAYER, profile = profile, profiles = listOf(profile), detail = Detail(title, episodes),
            episode = current, stream = stream, playbackIdentity = PlaybackIdentity(profile.id, title, current))
        fun render(s: TvState) {
            instrument.runOnMainSync {
                activity.setContent { }
                vm.watchingSession.start()
                mutable.value = s
            }
            instrument.waitForIdleSync()
            instrument.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        BoxWithConstraints(Modifier.fillMaxSize().background(Midnight)) {
                            CompositionLocalProvider(LocalSafePadding provides PaddingValues(maxWidth * .05f, maxHeight * .05f)) {
                                val state by vm.state.collectAsState()
                                PlaybackScreen(stream, state, vm)
                            }
                        }
                    }
                }
            }
            await("Offline player ready") { visible(R.string.pause) }
        }
        try {
            render(base)
            key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            select(R.string.episodes)
            await("Drawer opens on current offscreen episode") { focused()?.label?.contains(current.name) == true }
            assertTrue(focused()?.label?.contains(text(R.string.now_playing)) == true)
            capture("regions-current-episode")
            key(KeyEvent.KEYCODE_BACK)
            await("Closing restores Episodes") { focused()?.label == text(R.string.episodes) }
            select(R.string.edit_regions, KeyEvent.KEYCODE_DPAD_LEFT)
            capture("regions-editor-open")
            await("Editor starts on Add region") { focused()?.label == text(R.string.region_add) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Start handle receives focus") { focused()?.label?.startsWith("Start") == true }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Start moved one second") { nodes().any { it.label == target.getString(R.string.region_start, clock(1000)) } }
            capture("regions-editor")
            select(R.string.save, KeyEvent.KEYCODE_DPAD_DOWN)
            await("Save persists range and restores opener") { vm.markedSegments().size == 1 && focused()?.label == text(R.string.edit_regions) }
            assertTrue(vm.markedSegments().single().startMs in 1000L..1999L)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Editor can reopen") { focused()?.label == text(R.string.region_add) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("New draft ready") { focused()?.label?.startsWith("Start") == true }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_BACK)
            await("Cancel restores opener") { focused()?.label == text(R.string.edit_regions) }
            assertEquals(1, vm.markedSegments().size)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("End-of-video draft opens") { focused()?.label == text(R.string.region_add) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Draft start receives focus") { focused()?.label?.startsWith("Start") == true }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("End handle receives focus") { focused()?.label?.startsWith("End") == true }
            val now = SystemClock.uptimeMillis()
            instrument.sendKeySync(KeyEvent(now - 6000, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 10))
            instrument.sendKeySync(KeyEvent(now - 6000, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
            await("Final-frame preview can be saved") { nodes().any { it.label == text(R.string.save) && it.clickable } }
            assertFalse("Previewing the end must not finish the episode", vm.watchingSession.finished)
            key(KeyEvent.KEYCODE_BACK)
            await("End preview cancels safely") { focused()?.label == text(R.string.edit_regions) }
            select(R.string.settings)
            select(R.string.buffering_mode, KeyEvent.KEYCODE_DPAD_DOWN)
            select(R.string.buffer_ahead, KeyEvent.KEYCODE_DPAD_DOWN)
            await("Buffer ready without playing") { visible(R.string.buffer_play_now) && nodes().any { it.label.startsWith("Buffer ready") } }
            capture("regions-buffer")
            key(KeyEvent.KEYCODE_BACK)
            await("Buffer cancel retains pause") { !visible(R.string.buffer_play_now) && visible(R.string.play) }
            val credits = SkipSegment(SegmentType.CREDITS, 0, 30000)
            render(base.copy(profile = profile.copy(autoplay = false), segments = Segments("", listOf(credits))))
            await("Credits prompt appears before end") { visible(R.string.play_next) && visible(R.string.timeline) }
            assertFalse(nodes().any { it.label == target.getString(R.string.next_episode_in, 5) })
            capture("regions-credits-manual")
            render(base.copy(segments = Segments("", listOf(credits))))
            await("Credits countdown starts while video plays") { nodes().any { it.label == target.getString(R.string.next_episode_in, 5) } && visible(R.string.pause) }
            capture("regions-credits-countdown")
            key(KeyEvent.KEYCODE_BACK)
            await("Cancel dismisses credits prompt") { !visible(R.string.play_next) }
            Thread.sleep(5500)
            assertEquals(current.key, mutable.value.episode?.key)
            render(base.copy(segments = Segments("", listOf(credits))))
            await("Credits automatically advance before video ends") { mutable.value.episode?.key == episodes[43].key }
            assertTrue("Disposed old player cannot overwrite completed credits progress",
                LibraryStore(app, profile.id).viewing().first { it.episode?.key == current.key }.finished)
        } finally {
            instrument.runOnMainSync { activity.setContent { }; store.clear(); activity.finish() }
            names.forEach { target.deleteSharedPreferences(it) }
            folder.deleteRecursively()
        }
    }
}
