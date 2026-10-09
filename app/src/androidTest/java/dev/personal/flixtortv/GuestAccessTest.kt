package dev.personal.flixtortv

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opt-in live contract check; never reads or clears the household's shared login. */
@RunWith(AndroidJUnit4::class)
class GuestAccessTest {
    @Test fun restrictedGuestPlaybackHasAnAccessOutcome() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveService") == "true")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "guest_test_${UUID.randomUUID()}_"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
        val client = FlixtorClient(SessionStore(context))
        try {
            assertEquals(AccountStatus.GUEST, client.account().status)
            try {
                val title = client.browse(false, 1, "best").first()
                client.playback(title, null); fail("Expected restricted guest title")
            }
            catch (error: SiteException) {
                assertTrue("Expected explicit access outcome, got ${error.failure}", error.failure in listOf(Failure.VIP, Failure.SIGNED_OUT, Failure.CAPACITY))
            }
        } finally {
            client.close()
            context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
