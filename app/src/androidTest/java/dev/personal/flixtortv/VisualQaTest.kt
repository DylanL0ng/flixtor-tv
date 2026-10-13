package dev.personal.flixtortv

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in visual fixtures on a separate emulator; public sample media, no household login. */
@RunWith(AndroidJUnit4::class)
class VisualQaTest {
    @Test fun verifySingleBottomRightPlayerActions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("visualQA") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val suffix = InstrumentationRegistry.getArguments().getString("captureSize", "1080")
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrument.setInTouchMode(false)
        lateinit var mutable: MutableStateFlow<TvState>
        lateinit var vm: TvViewModel
        instrument.runOnMainSync {
            vm = ViewModelProvider(activity)[TvViewModel::class.java]
            @Suppress("UNCHECKED_CAST")
            mutable = TvViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(vm) as MutableStateFlow<TvState>
        }
        val fixture = File(context.filesDir, "qa-player").apply { mkdirs() }
        listOf("qa-player.m3u8", "qa-player-0.ts").forEach { name ->
            instrument.context.assets.open(name).use { input -> File(fixture, name).outputStream().use { output -> input.copyTo(output) } }
        }
        val subtitles = "WEBVTT\n\n00:00.000 --> 01:00.000\nCaptions stay above player actions.\n"
        val caption = Caption("data:text/vtt;base64," + android.util.Base64.encodeToString(subtitles.toByteArray(), android.util.Base64.NO_WRAP), "QA English", "text/vtt", true)
        val stream = Stream(File(fixture, "qa-player.m3u8").toURI().toString(), listOf(caption))
        val profile = mutable.value.profiles.first().copy(autoplay = false)
        val episode = Episode(1, 1, "Player action fixture")
        val next = Episode(1, 2, "Next episode fixture")
        val title = Title("qa-player-actions", "/watch/tv/qa-player-actions", "Player actions QA")
        val base = TvState(profile = profile, profiles = listOf(profile), account = Account(false), accountChecked = true,
            screen = Screen.PLAYER, stream = stream, detail = Detail(title, listOf(episode, next)), episode = episode)
        data class Node(val label: String, val bounds: Rect, val focused: Boolean, val clickable: Boolean)
        fun nodes(): List<Node> {
            fun read(node: AccessibilityNodeInfo?): List<Node> {
                if (node == null) return emptyList()
                node.refresh()
                val own = Node(node.contentDescription?.toString() ?: node.text?.toString().orEmpty(),
                    Rect().also(node::getBoundsInScreen), node.isFocused, node.isClickable)
                val children = (0 until node.childCount).flatMap { read(node.getChild(it)) }
                @Suppress("DEPRECATION") // Still releases pooled nodes on Android 10–12.
                node.recycle()
                return listOf(own) + children
            }
            val raw = read(instrument.uiAutomation.rootInActiveWindow)
            // Compose exposes action/focus and its label on separate accessibility nodes.
            return raw.map { node ->
                if (node.label.isBlank() && (node.clickable || node.focused))
                    node.copy(label = raw.firstOrNull { node.bounds.contains(it.bounds) && it.label.isNotBlank() && it.bounds.width() >= node.bounds.width() / 2 }?.label.orEmpty())
                else node
            }
        }
        fun label(id: Int) = context.getString(id)
        fun actions(id: Int) = nodes().filter { it.clickable && it.label == label(id) }
        fun visible(id: Int) = nodes().any { it.label == label(id) }
        fun awaitCondition(message: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 8000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("$message; nodes=${nodes()}", condition())
        }
        fun key(code: Int) { instrument.sendKeyDownUpSync(code); Thread.sleep(200) }
        fun capture(name: String) {
            val bitmap = instrument.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun render(state: TvState) {
            instrument.runOnMainSync { vm.back(); mutable.value = state.copy(screen = Screen.DETAIL, stream = null) }
            instrument.waitForIdleSync()
            Thread.sleep(200)
            instrument.runOnMainSync { mutable.value = state }
            awaitCondition("Offline player must start") {
                visible(R.string.play_next) || nodes().any { it.label.endsWith(context.getString(R.string.time_remaining, "")) &&
                    it.label != context.getString(R.string.time_remaining, clock(0)) }
            }
            if (state.resumePosition == 0L) {
                key(KeyEvent.KEYCODE_MEDIA_PAUSE)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                awaitCondition("Player must pause") { visible(R.string.play) }
            }
        }
        fun segment(type: SegmentType, end: Long = 20000) = Segments("", listOf(SkipSegment(type, 0, end)))
        fun safeBounds(fit: ScreenFit): Rect {
            val bitmap = instrument.uiAutomation.takeScreenshot()
            val density = context.resources.displayMetrics.density
            val x = if (fit == ScreenFit()) (14 * density).toInt() else bitmap.width * fit.horizontal / 100
            val y = if (fit == ScreenFit()) (12 * density).toInt() else bitmap.height * fit.vertical / 100
            val bounds = Rect(x, y, bitmap.width - x, bitmap.height - y)
            bitmap.recycle()
            return bounds
        }
        try {
            listOf(ScreenFit(), ScreenFit(10, 10)).forEach { fit ->
                render(base.copy(screenFit = fit, segments = segment(SegmentType.INTRO)))
                capture("player-actions-initial-${fit.horizontal}")
                awaitCondition("Exactly one intro action") { actions(R.string.skip_intro).size == 1 }
                val skip = actions(R.string.skip_intro).single().bounds
                val heading = nodes().first { it.label == title.name }.bounds
                val timeline = nodes().first { it.label == label(R.string.timeline) }.bounds
                assertTrue("Skip must sit above entire control panel", skip.bottom < heading.top && skip.bottom < timeline.top)
                assertTrue("Skip must respect screen fit", safeBounds(fit).contains(skip))
                key(KeyEvent.KEYCODE_DPAD_UP)
                key(KeyEvent.KEYCODE_DPAD_UP)
                awaitCondition("Timeline Up must focus skip") { actions(R.string.skip_intro).singleOrNull()?.focused == true }
                capture("player-skip-focused-${fit.horizontal}")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitCondition("Used skip disappears and restores timeline focus") {
                    actions(R.string.skip_intro).isEmpty() && nodes().any { it.label == label(R.string.timeline) && it.focused }
                }
                key(KeyEvent.KEYCODE_MEDIA_REWIND)
                assertTrue("Used intro must not reappear", actions(R.string.skip_intro).isEmpty())
                instrument.runOnMainSync { mutable.value = mutable.value.copy(segments = segment(SegmentType.RECAP)) }
                awaitCondition("Exactly one recap action") { actions(R.string.skip_recap).size == 1 }
                key(KeyEvent.KEYCODE_BACK)
                awaitCondition("Recap stays visible with transport hidden") { !visible(R.string.timeline) && actions(R.string.skip_recap).size == 1 }
                val hiddenSkip = actions(R.string.skip_recap).single().bounds
                assertTrue("Hidden-controls action moves down", hiddenSkip.top > skip.top)
                assertTrue(safeBounds(fit).contains(hiddenSkip))
                capture("player-skip-hidden-${fit.horizontal}")
                key(KeyEvent.KEYCODE_DPAD_UP)
                awaitCondition("Hidden skip must be remote reachable") { actions(R.string.skip_recap).singleOrNull()?.focused == true }
                key(KeyEvent.KEYCODE_ENTER)
                awaitCondition("Hidden skip activates") { actions(R.string.skip_recap).isEmpty() }
                assertEquals(Screen.PLAYER, mutable.value.screen)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                instrument.runOnMainSync { mutable.value = mutable.value.copy(segments = segment(SegmentType.CREDITS, 60000)) }
                awaitCondition("Exactly one credits action") { actions(R.string.skip_credits).size == 1 }
                // Transport begins at Play: Right traverses rewind, forward, Episodes, Settings.
                repeat(4) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitCondition("Settings suppresses skip") { visible(R.string.quality) && actions(R.string.skip_credits).isEmpty() }
                capture("player-skip-settings-${fit.horizontal}")
                key(KeyEvent.KEYCODE_BACK)
                awaitCondition("Settings restores initiating focus") { nodes().any { it.label == label(R.string.settings) && it.focused } }
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitCondition("Episodes suppresses skip") { nodes().any { it.label == next.name } && actions(R.string.skip_credits).isEmpty() }
                key(KeyEvent.KEYCODE_BACK)
                awaitCondition("Episodes restores initiating focus") { nodes().any { it.label == label(R.string.episodes) && it.focused } }

                instrument.runOnMainSync { mutable.value = mutable.value.copy(segments = segment(SegmentType.POST_CREDITS, 60000)) }
                awaitCondition("Exactly one post-credits action") { actions(R.string.skip_post_credits).size == 1 }
                key(KeyEvent.KEYCODE_DPAD_UP)
                key(KeyEvent.KEYCODE_DPAD_UP)
                awaitCondition("Post-credits action reachable") { actions(R.string.skip_post_credits).singleOrNull()?.focused == true }
                instrument.runOnMainSync { mutable.value = mutable.value.copy(segments = null) }
                awaitCondition("Removed segment restores timeline focus") {
                    actions(R.string.skip_post_credits).isEmpty() && nodes().any { it.label == label(R.string.timeline) && it.focused }
                }

                render(base.copy(screenFit = fit, resumePosition = 59000, segments = segment(SegmentType.CREDITS, 60000)))
                awaitCondition("Next prompt replaces skip and transport") {
                    actions(R.string.play_next).size == 1 && actions(R.string.skip_credits).isEmpty() && !visible(R.string.timeline)
                }
                val playNext = actions(R.string.play_next).single()
                assertTrue("Play next focused by default", playNext.focused)
                assertFalse("Autoplay off must not show countdown", nodes().any { node ->
                    (0..5).any { node.label == context.getString(R.string.next_episode_in, it) }
                })
                assertTrue("Next prompt respects margins", safeBounds(fit).contains(playNext.bounds))
                assertTrue("Next prompt aligned right", playNext.bounds.left > safeBounds(fit).centerX() - 200 * context.resources.displayMetrics.density)
                capture("player-next-${fit.horizontal}")
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitCondition("Cancel dismisses prompt") { actions(R.string.play_next).isEmpty() && visible(R.string.timeline) }
                assertEquals(episode, mutable.value.episode)
            }
            render(base.copy(resumePosition = 59000))
            awaitCondition("Next prompt appears") { actions(R.string.play_next).size == 1 }
            key(KeyEvent.KEYCODE_BACK)
            awaitCondition("Back dismisses prompt without leaving player") { actions(R.string.play_next).isEmpty() && visible(R.string.timeline) }
            assertEquals(Screen.PLAYER, mutable.value.screen)
            render(base.copy(resumePosition = 59000))
            awaitCondition("Play next available") { actions(R.string.play_next).size == 1 }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitCondition("Play next starts immediately") { mutable.value.episode == next }
            render(base.copy(profile = profile.copy(autoplay = true), resumePosition = 59000))
            awaitCondition("Autoplay countdown appears") { nodes().any { it.label == context.getString(R.string.next_episode_in, 5) } }
            capture("player-next-autoplay")
            awaitCondition("Autoplay starts following episode") { mutable.value.episode == next }
            instrument.runOnMainSync {
                vm.back()
                mutable.value = base.copy(stream = Stream(File(fixture, "missing.m3u8").toURI().toString(), emptyList()),
                    segments = segment(SegmentType.INTRO))
            }
            awaitCondition("Media failure suppresses skip") { mutable.value.failure != null && actions(R.string.skip_intro).isEmpty() }
            capture("player-skip-error")
        } finally {
            instrument.runOnMainSync { activity.finish() }
        }
    }

    @Test fun captureUpdateDecisionAtMaximumMargins() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("visualQA") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val suffix = InstrumentationRegistry.getArguments().getString("captureSize", "1080")
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrument.runOnMainSync {
            activity.setContent {
                androidx.tv.material3.MaterialTheme(colorScheme = androidx.tv.material3.darkColorScheme()) {
                    androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize().background(Midnight)) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.tv.material3.LocalContentColor provides androidx.compose.ui.graphics.Color.White,
                            LocalSafePadding provides androidx.compose.foundation.layout.PaddingValues(horizontal = maxWidth * 0.1f, vertical = maxHeight * 0.1f)) {
                            UpdateReadyDialog("QA fixture", { activity.finish() }) { error("QA must never install") }
                        }
                    }
                }
            }
        }
        Thread.sleep(1800)
        fun hasTitle(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            node.refresh()
            return node.text?.toString() == context.getString(R.string.update_available_title, "QA fixture") ||
                (0 until node.childCount).any { hasTitle(node.getChild(it)) }
        }
        val bitmap = instrument.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "update-ready-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        assertTrue("Update decision must be visible", hasTitle(instrument.uiAutomation.rootInActiveWindow))
        instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        Thread.sleep(300)
        instrument.runOnMainSync { activity.finish() }
    }

    @Test fun captureTvLayoutsAndVerifyPlayerRemoteBehavior() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("visualQA") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val suffix = InstrumentationRegistry.getArguments().getString("captureSize", "1080")
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var vm: TvViewModel
        lateinit var mutable: MutableStateFlow<TvState>
        instrument.runOnMainSync {
            vm = ViewModelProvider(activity)[TvViewModel::class.java]
            @Suppress("UNCHECKED_CAST")
            mutable = TvViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(vm) as MutableStateFlow<TvState>
        }
        val profile = mutable.value.profiles.first()
        // Public QA art: Big Buck Bunny poster, CC BY 3.0, Wikimedia Commons.
        val posterFile = File(context.filesDir, "qa-big-buck-bunny.jpg")
        if (!posterFile.exists()) {
            instrument.context.assets.open("qa-big-buck-bunny.jpg").use { input ->
                posterFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val poster = posterFile.toURI().toString()
        val title = Title("1", "/watch/movie/1/qa", "Big Buck Bunny", poster, "2008", "", "Animation",
            "Public sample media used to verify TV controls and screen-safe layout.", poster)
        val base = TvState(profile = profile, profiles = mutable.value.profiles, account = Account(false), accountChecked = true,
            detail = Detail(title, emptyList()), shelves = listOf(Shelf("movies", (1..7).map { id ->
                title.copy(id = id.toString(), name = if (id == 1) title.name else "Sample title $id")
            })), hasMore = false)
        fun render(state: TvState) { instrument.runOnMainSync { mutable.value = state }; Thread.sleep(1800) }
        fun key(code: Int, count: Int = 1) { repeat(count) { instrument.sendKeyDownUpSync(code); Thread.sleep(180) } }
        fun capture(name: String) {
            val bitmap = instrument.uiAutomation.takeScreenshot()
            assertNotNull(bitmap)
            File(context.getExternalFilesDir(null), "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun focused(node: AccessibilityNodeInfo? = instrument.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
            if (node == null) return null
            node.refresh()
            repeat(node.childCount) { index -> focused(node.getChild(index))?.let { return it } }
            return node.takeIf { it.isFocused }
        }
        fun labels(node: AccessibilityNodeInfo?): String {
            if (node == null) return ""
            node.refresh()
            return (listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
                (0 until node.childCount).map { labels(node.getChild(it)) }).joinToString(" ")
        }
        fun visible(text: String) = labels(instrument.uiAutomation.rootInActiveWindow).contains(text)
        fun awaitCondition(message: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 5000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue(message, condition())
        }
        fun focusedLabel(): String {
            val focus = focused() ?: return ""
            val bounds = Rect().also { focus.getBoundsInScreen(it) }
            fun matching(node: AccessibilityNodeInfo?): String {
                if (node == null) return ""
                node.refresh()
                val rect = Rect().also { node.getBoundsInScreen(it) }
                val own = if (rect.contains(bounds.centerX(), bounds.centerY()) && rect.width() <= bounds.width() * 1.5 && rect.height() <= bounds.height() * 1.5)
                    listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ") else ""
                return (listOf(own) + (0 until node.childCount).map { matching(node.getChild(it)) }).joinToString(" ")
            }
            return labels(focus) + matching(instrument.uiAutomation.rootInActiveWindow)
        }
        render(base.copy(screen = Screen.PROFILES)); capture("profiles")
        render(base.copy(screen = Screen.HOME)); capture("home")
        render(base.copy(screen = Screen.MOVIES, titles = (1..36).map { title.copy(id = it.toString(), name = "Sample $it") })); capture("browse")
        render(base.copy(screen = Screen.DETAIL)); capture("detail")
        render(base.copy(screen = Screen.ACCOUNT)); capture("settings")
        var fitFocused = false
        val focusTrace = mutableListOf<String>()
        // Enter through the rail's Settings destination before traversing its content.
        key(KeyEvent.KEYCODE_DPAD_DOWN, 7)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(16) {
            if (!labels(focused()).contains(context.getString(R.string.display))) key(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(16) {
            if (!fitFocused) {
                val focus = focused()
                val label = labels(focus)
                focusTrace.add(label)
                fitFocused = label.contains(context.getString(R.string.screen_fit))
                if (!fitFocused) key(KeyEvent.KEYCODE_DPAD_DOWN)
            }
        }
        capture("settings-scrolled")
        assertTrue("Screen fit must be reachable by remote: $focusTrace", fitFocused)
        key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(500)
        assertTrue(visible(context.getString(R.string.screen_fit_help))); capture("screen-fit")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER, 5)
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER, 5); capture("screen-fit-max")
        key(KeyEvent.KEYCODE_BACK)
        assertEquals(ScreenFit(), vm.state.value.screenFit)
        render(base.copy(screen = Screen.HOME, screenFit = ScreenFit(10, 10))); capture("home-max")
        @Suppress("UNCHECKED_CAST")
        val updateFlow = UpdateService::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            .get(vm.updates) as MutableStateFlow<UpdateState>
        instrument.runOnMainSync { updateFlow.value = UpdateState(UpdatePhase.READY, "QA fixture") }
        awaitCondition("Downloaded update must be announced") { visible(context.getString(R.string.update_available_title, "QA fixture")) }
        capture("update-ready")
        key(KeyEvent.KEYCODE_BACK)
        awaitCondition("Later must dismiss the update prompt") { !visible(context.getString(R.string.update_available_title, "QA fixture")) }
        instrument.runOnMainSync { updateFlow.value = UpdateState() }
        val subtitles = "WEBVTT\n\n00:00.000 --> 09:59.000\nSample captions stay clear of the controls.\n"
        val caption = Caption("data:text/vtt;base64," + android.util.Base64.encodeToString(subtitles.toByteArray(), android.util.Base64.NO_WRAP), "English", "text/vtt", true)
        val stream = Stream("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", listOf(caption))
        render(base.copy(screen = Screen.PLAYER, stream = stream, playbackIdentity = PlaybackIdentity(profile.id, title, null)))
        Thread.sleep(14000)
        assertNull("Public sample must play without a media error", vm.state.value.failure)
        key(KeyEvent.KEYCODE_MEDIA_PAUSE); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, 9); key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_MEDIA_PLAY); Thread.sleep(6000); key(KeyEvent.KEYCODE_MEDIA_PAUSE)
        key(KeyEvent.KEYCODE_DPAD_DOWN); Thread.sleep(500); capture("player")
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_LEFT, 4); key(KeyEvent.KEYCODE_DPAD_RIGHT, 3); key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("Settings drawer must open", visible(context.getString(R.string.quality))); capture("player-settings")
        key(KeyEvent.KEYCODE_DPAD_CENTER) // Quality submenu, initially Auto.
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(720, vm.state.value.profile?.quality)
        assertEquals(720, ProfileStore(context).all().first { it.id == profile.id }.quality)
        key(KeyEvent.KEYCODE_BACK)
        awaitCondition("Drawer must close after Back") { !visible(context.getString(R.string.quality)) }
        awaitCondition("Settings focus must restore: ${focusedLabel()}") { focusedLabel().contains(context.getString(R.string.settings)) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitCondition("Drawer must reopen") { visible(context.getString(R.string.quality)) }
        var subtitlesFocused = false
        repeat(12) {
            if (!subtitlesFocused) {
                subtitlesFocused = labels(focused()).contains(context.getString(R.string.subtitles))
                if (!subtitlesFocused) key(KeyEvent.KEYCODE_DPAD_DOWN)
            }
        }
        assertTrue("Subtitles category must be reachable", subtitlesFocused)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        var englishFocused = false
        repeat(12) {
            if (!englishFocused) {
                englishFocused = labels(focused()).contains("English")
                if (!englishFocused) key(KeyEvent.KEYCODE_DPAD_DOWN)
            }
        }
        assertTrue("English subtitle must be reachable", englishFocused)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_BACK)
        awaitCondition("Caption drawer must close after Back") { !visible(context.getString(R.string.quality)) }
        Thread.sleep(500); capture("player-captions")
        assertTrue("Settings focus must restore: ${focusedLabel()}", focusedLabel().contains(context.getString(R.string.settings)))
        key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_BACK)
        assertEquals(Screen.PLAYER, vm.state.value.screen)
        key(KeyEvent.KEYCODE_BACK); key(KeyEvent.KEYCODE_BACK)
        assertEquals(Screen.DETAIL, vm.state.value.screen)
        render(base.copy(screen = Screen.ACCOUNT, screenFit = ScreenFit(10, 10))); capture("settings-max")
        instrument.runOnMainSync { activity.finish() }
    }
}
