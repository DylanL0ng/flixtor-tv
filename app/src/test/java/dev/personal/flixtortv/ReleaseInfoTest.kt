package dev.personal.flixtortv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseInfoTest {
    private fun metadata() = JSONObject().put("versionCode", 3).put("versionName", "0.3.0").put("minSdk", 29)
        .put("sha256", "a".repeat(64)).put("apkUrl", "https://github.com/DylanL0ng/flixtor-tv/releases/download/v0.3.0/app-release.apk")
    @Test fun validReleaseIsAccepted() { assertEquals(3, ReleaseInfo.parse(metadata()).versionCode) }
    @Test fun unsafeReleaseMetadataIsRejected() {
        for (json in listOf(metadata().put("apkUrl", "https://evil.example/a.apk"), metadata().put("sha256", "bad"),
            metadata().put("versionCode", -1), metadata().put("minSdk", 1))) {
            try { ReleaseInfo.parse(json); fail("Expected invalid release") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun timeFormattingBoundsNegativeDurations() {
        assertEquals("0:00:00", clock(-1))
        assertEquals("1:01:01", clock(3661000))
    }
}
