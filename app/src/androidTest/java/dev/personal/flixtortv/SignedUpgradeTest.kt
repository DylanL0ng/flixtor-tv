package dev.personal.flixtortv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only on the disposable QA emulator, before/after a same-key signed installation. */
@RunWith(AndroidJUnit4::class)
class SignedUpgradeTest {
    @Test fun signedUpgradePreservesProfilesLibraryAndEncryptedSession() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("signedUpgradeQA") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profiles = ProfileStore(context)
        profiles.initialize()
        val url = "https://upgrade-fixture.invalid/".toHttpUrl()
        val title = Title("1", "/watch/movie/1/fixture", "Signed upgrade fixture")
        if (arguments.getString("phase") == "seed") {
            val profile = profiles.save(null, "Signed upgrade QA", 2).last()
            profiles.quality(profile.id, 720)
            val library = LibraryStore(context, profile.id)
            library.toggle(title)
            library.save(Viewing(title, null, 12000, 90000))
            SessionStore(context).saveFromResponse(url, listOf(Cookie.Builder().name("qa").value("fixture")
                .hostOnlyDomain(url.host).path("/").secure().expiresAt(System.currentTimeMillis() + 86400000).build()))
            Thread.sleep(300) // SharedPreferences.apply must reach disk before replacing the process.
        }
        val profile = profiles.all().single { it.name == "Signed upgrade QA" }
        assertEquals(720, profile.quality)
        assertEquals(title.key, LibraryStore(context, profile.id).favorites().single().key)
        assertEquals(12000L, LibraryStore(context, profile.id).viewing().single().position)
        assertEquals("fixture", SessionStore(context).loadForRequest(url).single { it.name == "qa" }.value)
        arguments.getString("expectedVersionCode")?.let {
            assertEquals(it.toLong(), context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
        }
    }
}
