package dev.personal.flixtortv

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import android.graphics.Bitmap

/** Real WebGL, WebMessage bridge, Media3 and injected Android remote events. Offline QA package only. */
@RunWith(AndroidJUnit4::class)
class SolidTvIntegrationTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun await(message: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 15000
        while (!predicate() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue(message, predicate())
    }
    private fun web(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { web(view.getChildAt(it)) } else null
    private fun evaluate(web: WebView, code: String): String {
        val latch = CountDownLatch(1); var result = "null"
        instrument.runOnMainSync { web.evaluateJavascript(code) { result = it; latch.countDown() } }
        check(latch.await(5, TimeUnit.SECONDS)); return result
    }
    private fun press(code: Int) = instrument.sendKeyDownUpSync(code)
    private fun capture(name: String) {
        Thread.sleep(450)
        val bitmap = instrument.uiAutomation.takeScreenshot() ?: return
        File(instrument.targetContext.getExternalFilesDir(null), "liquid-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun activate(web: WebView, label: String) {
        await("Control available: $label") { evaluate(web, "Array.from(document.querySelectorAll('button')).some(b=>b.textContent.includes(${org.json.JSONObject.quote(label)}))") == "true" }
        evaluate(web, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent.includes(${org.json.JSONObject.quote(label)}))?.focus()")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    @Test fun profilesCatalogueNativePlaybackAndBack() {
        check(instrument.targetContext.packageName.endsWith(".solidqa"))
        instrument.targetContext.getSharedPreferences("solidtv", 0).edit().clear().commit()
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, SolidTvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("solidQA", true)) as SolidTvActivity
        try {
            val web = web(activity.window.decorView)!!
            activate(web, "My profile")
            await("Home route") { evaluate(web, "location.hash.includes('home')") == "true" }
            capture("home")
            activate(web, "The Test Series")
            capture("detail")
            fun focused(label: String) = await("Remote focus: $label") {
                evaluate(web, "document.querySelector('button[data-focused=\"true\"]')?.textContent===${org.json.JSONObject.quote(label)}") == "true"
            }
            focused("Play")
            press(KeyEvent.KEYCODE_DPAD_DOWN); focused("Season 1")
            press(KeyEvent.KEYCODE_DPAD_DOWN); focused("1. Chapter 1")
            press(KeyEvent.KEYCODE_DPAD_DOWN); focused("2. Chapter 2")
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Player started") { evaluate(web, "Array.from(document.querySelectorAll('button')).some(b=>b.textContent==='Pause')") == "true" }
            activate(web, "Pause")
            await("Paused") { evaluate(web, "Array.from(document.querySelectorAll('button')).some(b=>b.textContent==='Play')") == "true" }
            activate(web, "+10 seconds")
            activate(web, "Settings")
            await("Native tracks and SolidTV settings") { evaluate(web, "Array.from(document.querySelectorAll('button')).some(b=>b.textContent.includes('Video quality'))") == "true" }
            capture("player-settings")
            press(KeyEvent.KEYCODE_BACK); press(KeyEvent.KEYCODE_BACK)
            await("Detail restored") { evaluate(web, "location.hash.includes('detail')") == "true" }
            assertEquals("true", evaluate(web, "!!document.querySelector('canvas')"))
        } finally { instrument.runOnMainSync { activity.finish() } }
    }
    @Test fun bridgeDoesNotExposeProtectedStorageOrRemoteOrigin() {
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, SolidTvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("solidQA", true)) as SolidTvActivity
        try {
            val web = web(activity.window.decorView)!!
            await("Bridge initialized") { evaluate(web, "typeof nativeHost !== 'undefined'") == "true" }
            evaluate(web, "window.qaReply=null; nativeHost.onmessage=e=>{window.qaReply=JSON.parse(e.data)}; nativeHost.postMessage(JSON.stringify({v:1,id:'probe',method:'storage.get',args:{key:'pin:default'}}))")
            await("Protected storage rejected") { evaluate(web, "window.qaReply?.error==='INVALID'") == "true" }
        } finally { instrument.runOnMainSync { activity.finish() } }
    }
}
