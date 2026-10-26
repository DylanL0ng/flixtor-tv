package dev.personal.flixtortv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Run only against disposable same-key legacy install before replacing its APK. */
@RunWith(AndroidJUnit4::class)
class LegacyUpgradeSeedTest {
    @Test fun seedLegacyFormats() {
        check(InstrumentationRegistry.getArguments().getString("upgradeQA") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "solidtv-upgrade-qa"
        val pin = MessageDigest.getInstance("SHA-256").digest("$id:1234".toByteArray()).joinToString("") { "%02x".format(it) }
        val profile = JSONObject().put("id", id).put("name", "Signed upgrade QA").put("avatar", 2).put("quality", 720)
            .put("autoplay", false).put("largerBuffer", true).put("pin", pin)
        check(context.getSharedPreferences("profiles", 0).edit().putString("items", JSONArray().put(profile).toString()).putBoolean("migrated", true).commit())
        val title = JSONObject().put("id", "1").put("path", "/watch/movie/1/fixture").put("name", "Signed upgrade fixture")
        val viewing = JSONObject().put("title", title).put("position", 12000).put("duration", 90000)
        check(context.getSharedPreferences("library_$id", 0).edit().putString("favorites", JSONArray().put(title).toString()).putString("viewing", JSONArray().put(viewing).toString()).commit())
        check(context.getSharedPreferences("skip", 0).edit().putString("mark:m:1:", "[{\"type\":\"intro\",\"start\":1000,\"end\":10000}]")
            .putString("action:$id::intro", "AUTO").putString("imdb:m:1", "tt1234567").commit())
        check(context.getSharedPreferences("screen_fit", 0).edit().putInt("horizontal", 8).putInt("vertical", 6).commit())
        val url = "https://upgrade-fixture.invalid/".toHttpUrl()
        SessionStore(context).saveFromResponse(url, listOf(Cookie.Builder().name("qa").value("fixture").hostOnlyDomain(url.host).path("/").secure().expiresAt(System.currentTimeMillis() + 86400000).build()))
        Thread.sleep(300)
    }
}
