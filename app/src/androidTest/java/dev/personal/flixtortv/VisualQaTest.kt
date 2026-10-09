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
        instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
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
        val poster = "https://storage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg"
        val title = Title("1", "/watch/movie/1/qa", "Big Buck Bunny", poster, "2008", "", "Animation",
            "Public sample media used to verify TV controls and screen-safe layout.", poster)
        val base = TvState(profile = profile, profiles = mutable.value.profiles, account = Account(false), accountChecked = true,
            detail = Detail(title, emptyList()), shelves = listOf(Shelf("movies", listOf(title))), hasMore = false)
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
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER)
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
        key(KeyEvent.KEYCODE_DPAD_DOWN, 2); key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(720, vm.state.value.profile?.quality)
        assertEquals(720, ProfileStore(context).all().first { it.id == profile.id }.quality)
        key(KeyEvent.KEYCODE_BACK)
        awaitCondition("Drawer must close after Back") { !visible(context.getString(R.string.quality)) }
        awaitCondition("Settings focus must restore: ${focusedLabel()}") { focusedLabel().contains(context.getString(R.string.settings)) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitCondition("Drawer must reopen") { visible(context.getString(R.string.quality)) }
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
