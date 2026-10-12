package dev.personal.flixtortv

import org.junit.Assert.*
import org.junit.Test

class ProfilePinTest {
    @Test fun digestIsStableAndNeverContainsTheDigits() {
        val first = ProfilePin.digest("default", "1234")
        assertEquals(first, ProfilePin.digest("default", "1234"))
        assertEquals(64, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
        assertFalse(first.contains("1234"))
    }

    @Test fun digestIsScopedToTheProfile() {
        // The same digits on another profile must not unlock it.
        assertNotEquals(ProfilePin.digest("default", "1234"), ProfilePin.digest("other", "1234"))
    }

    @Test fun distinctPinsProduceDistinctDigests() {
        assertNotEquals(ProfilePin.digest("default", "1234"), ProfilePin.digest("default", "4321"))
    }

    @Test fun lengthMatchesTheKeypad() {
        assertEquals(4, ProfilePin.LENGTH)
    }
}
