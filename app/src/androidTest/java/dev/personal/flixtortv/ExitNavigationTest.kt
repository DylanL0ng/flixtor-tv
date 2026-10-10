package dev.personal.flixtortv

import android.content.Intent
import android.graphics.Bitmap
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

/** Opt-in fixtures on the disposable QA emulator. Never modify household storage. */
@RunWith(AndroidJUnit4::class)
class ExitNavigationTest {
    @Test fun rootBackRequiresDeliberateExitAndNestedBackStillNavigates() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("exitQA") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        // Attach accessibility before creating windows; otherwise the first dialog snapshot can be stale.
        val automation = instrument.uiAutomation
        val context = instrument.targetContext
        val suffix = InstrumentationRegistry.getArguments().getString("captureSize", "1080")
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var mutable: MutableStateFlow<TvState>
        instrument.runOnMainSync {
            val vm = ViewModelProvider(activity)[TvViewModel::class.java]
            @Suppress("UNCHECKED_CAST")
            mutable = TvViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }
                .get(vm) as MutableStateFlow<TvState>
        }
        val profile = mutable.value.profiles.first().copy(name = "Household")
        val base = TvState(profile = profile, profiles = listOf(profile), account = Account(AccountStatus.VIP),
            accountChecked = true, screenFit = ScreenFit(10, 10), hasMore = false)
        fun render(screen: Screen) { instrument.runOnMainSync { mutable.value = base.copy(screen = screen) }; Thread.sleep(1200) }
        fun key(code: Int) { instrument.sendKeyDownUpSync(code); Thread.sleep(900) }
        fun labels(node: AccessibilityNodeInfo?): String {
            if (node == null) return ""
            node.refresh()
            return (listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
                (0 until node.childCount).map { labels(node.getChild(it)) }).joinToString(" ")
        }
        fun visible(id: Int) = labels(automation.rootInActiveWindow).contains(context.getString(id))
        fun expectVisible(id: Int) {
            val deadline = System.currentTimeMillis() + 5000
            while (!visible(id) && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("Expected ${context.getString(id)}; window: ${labels(automation.rootInActiveWindow)}", visible(id))
        }
        fun activate(id: Int) {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                node.refresh()
                if (node.isClickable && labels(node).contains(context.getString(id))) return node
                repeat(node.childCount) { find(node.getChild(it))?.let { target -> return target } }
                return null
            }
            assertTrue(requireNotNull(find(instrument.uiAutomation.rootInActiveWindow)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            Thread.sleep(900)
        }
        fun capture(name: String) {
            val bitmap = instrument.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        render(Screen.PROFILES)
        expectVisible(R.string.who_watching)
        key(KeyEvent.KEYCODE_BACK)
        expectVisible(R.string.stay_in_app); expectVisible(R.string.exit_app); capture("exit-confirmation")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertFalse("Select defaults to Stay", visible(R.string.stay_in_app))
        assertFalse(activity.isFinishing)
        key(KeyEvent.KEYCODE_BACK); key(KeyEvent.KEYCODE_BACK)
        assertFalse("Back dismisses confirmation", visible(R.string.stay_in_app))
        assertFalse(activity.isFinishing)
        activate(R.string.manage_profiles)
        expectVisible(R.string.done); key(KeyEvent.KEYCODE_BACK)
        assertFalse("Back closes profile management first", visible(R.string.done))
        activate(R.string.add_profile)
        expectVisible(R.string.profile_name); key(KeyEvent.KEYCODE_BACK)
        assertFalse("Back closes profile editing first", visible(R.string.profile_name))
        assertFalse(visible(R.string.stay_in_app))
        render(Screen.ACCOUNT); capture("account-vip")
        key(KeyEvent.KEYCODE_BACK)
        assertEquals(Screen.HOME, mutable.value.screen)
        assertFalse(visible(R.string.stay_in_app))
        key(KeyEvent.KEYCODE_BACK)
        expectVisible(R.string.stay_in_app)
        key(KeyEvent.KEYCODE_BACK)
        instrument.runOnMainSync { mutable.value = base.copy(screen = Screen.ACCOUNT, account = Account(AccountStatus.SIGNED_IN)) }
        Thread.sleep(1200); capture("account-unknown")
        assertTrue(visible(R.string.refresh_account))
        render(Screen.PROFILES)
        key(KeyEvent.KEYCODE_BACK); key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER)
        Thread.sleep(500)
        assertTrue("Only explicit Exit finishes the activity", activity.isFinishing || activity.isDestroyed)
    }
}
