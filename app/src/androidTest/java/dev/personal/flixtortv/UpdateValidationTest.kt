package dev.personal.flixtortv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class UpdateValidationTest {
    @Test fun checksumPackageVersionAndSignerAreValidatedOnAndroid() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val assets = instrument.context.assets
        assumeTrue("Generate candidate APKs locally before running this device test", assets.list("")!!.contains("candidate.apk"))
        val context = instrument.targetContext
        val service = UpdateService(context)
        fun candidate(name: String): Pair<File, ReleaseInfo> {
            val file = File(context.cacheDir, "test_$name")
            assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
            val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val version = context.packageManager.getPackageArchiveInfo(file.path, 0)!!.longVersionCode.toInt()
            return file to ReleaseInfo(version, "test", 29, sha, "https://github.com/DylanL0ng/flixtor-tv/releases/download/test/app.apk")
        }
        val correct = candidate("candidate.apk")
        val wrongSigner = candidate("wrong-signer.apk")
        try {
            service.validate(correct.first, correct.second)
            for ((file, metadata) in listOf(correct.first to correct.second.copy(sha256 = "0".repeat(64)),
                correct.first to correct.second.copy(versionCode = 1), wrongSigner)) {
                try { service.validate(file, metadata); fail("Expected invalid APK rejection") } catch (_: IllegalStateException) { }
            }
        } finally { correct.first.delete(); wrongSigner.first.delete(); service.close() }
    }
}
