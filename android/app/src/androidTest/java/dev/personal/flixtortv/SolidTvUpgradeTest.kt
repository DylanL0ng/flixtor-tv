package dev.personal.flixtortv

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SolidTvUpgradeTest {
    @Test fun importsLegacyDataAndKeepsKeystoreSession() {
        check(InstrumentationRegistry.getArguments().getString("upgradeQA") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, SolidTvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SolidTvActivity
        try {
            val prefs = activity.getSharedPreferences("solidtv", 0)
            val end = System.currentTimeMillis() + 15000
            while (!prefs.contains("app") && System.currentTimeMillis() < end) Thread.sleep(50)
            val app = JSONObject(prefs.getString("app", null) ?: error("Migration did not commit"))
            val profile = app.getJSONArray("profiles").getJSONObject(0)
            assertEquals("Signed upgrade QA", profile.getString("name")); assertEquals(720, profile.getInt("quality")); assertTrue(profile.getBoolean("locked")); assertTrue(profile.getBoolean("largerBuffer")); assertFalse(profile.getBoolean("autoplay"))
            assertFalse(profile.has("pin"))
            val library = app.getJSONObject("libraries").getJSONObject("solidtv-upgrade-qa")
            assertEquals("Signed upgrade fixture", library.getJSONArray("favorites").getJSONObject(0).getString("name"))
            assertEquals(12000, library.getJSONArray("viewing").getJSONObject(0).getInt("position"))
            assertEquals("AUTO", app.getJSONObject("skip").getString("action:solidtv-upgrade-qa::intro"))
            assertTrue(app.getJSONObject("skip").getString("mark:m:1:").contains("10000"))
            assertEquals(8, app.getJSONObject("fit").getInt("horizontal")); assertEquals(6, app.getJSONObject("fit").getInt("vertical"))
            assertEquals("fixture", SessionStore(activity).loadForRequest("https://upgrade-fixture.invalid/".toHttpUrl()).single { it.name == "qa" }.value)
            assertTrue(prefs.getString("pin:solidtv-upgrade-qa", "")!!.isNotEmpty())
            assertEquals(1000L, activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode)
        } finally { instrument.runOnMainSync { activity.finish() } }
    }
}
