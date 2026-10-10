package dev.personal.flixtortv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live account check. Credentials and response bodies never leave the device. */
@RunWith(AndroidJUnit4::class)
class AccountEvidenceTest {
    @Test fun authenticatedAccountStatusMatchesExplicitExpectation() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("accountEvidence") == "true")
        val expected = AccountStatus.valueOf(requireNotNull(arguments.getString("expectedAccountStatus")))
        val client = FlixtorClient(SessionStore(InstrumentationRegistry.getInstrumentation().targetContext))
        try {
            val actual = client.account().status
            println("ACCOUNT_STATUS category=$actual")
            assertEquals(expected, actual)
        } finally { client.close() }
    }
}
