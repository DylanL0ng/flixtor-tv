package dev.personal.flixtortv

import org.junit.Assert.*
import org.junit.Test

class WatchingSessionTest {
    private var time = 0L
    private val session = WatchingSession({ time })
    private val minute = 60_000L

    private fun episode(minutes: Long, automatic: Boolean = true): Long {
        val token = session.start(automatic)
        session.playback(token, true, true)
        time += minutes * minute
        session.complete(token, episode = true)
        return token
    }

    @Test fun requiresBothThreeEpisodesAndNinetyMinutes() {
        assertFalse(session.requireConfirmation(episode(5)))
        assertFalse(session.requireConfirmation(episode(5)))
        assertFalse(session.requireConfirmation(episode(5)))
        assertTrue(session.requireConfirmation(episode(75)))
        session.reset()
        assertFalse(session.requireConfirmation(episode(90)))
        assertFalse(session.requireConfirmation(episode(1)))
        assertTrue(session.requireConfirmation(episode(1)))
    }

    @Test fun waitsForEndEvenAfterBothThresholdsAreReached() {
        episode(30); episode(30)
        val token = session.start(automatic = true)
        session.playback(token, true, true); time += 30 * minute
        assertFalse(session.requireConfirmation(token))
        session.complete(token, episode = true)
        assertTrue(session.requireConfirmation(token))
    }

    @Test fun interactionResetsTimeAndEpisodeCount() {
        episode(30); episode(30)
        session.interact()
        assertFalse(session.requireConfirmation(episode(90)))
        assertFalse(session.requireConfirmation(episode(1)))
        assertTrue(session.requireConfirmation(episode(1)))
    }

    @Test fun manualPlaybackAndExitResetSession() {
        episode(30); episode(30)
        assertFalse(session.requireConfirmation(episode(30, automatic = false)))
        session.reset()
        assertFalse(session.requireConfirmation(episode(90)))
    }

    @Test fun pauseBufferBackgroundAndLoadingDoNotCount() {
        repeat(2) { episode(10) }
        val token = session.start(automatic = true)
        time += 90 * minute // Loading; there is no active player.
        session.playback(token, true, true); time += 10 * minute
        session.playback(token, false, true); time += 90 * minute // Pause/buffer.
        session.playback(token, true, false); time += 90 * minute // Background.
        session.playback(token, true, true)
        session.complete(token, episode = true)
        assertFalse(session.requireConfirmation(token))
        assertTrue(session.requireConfirmation(episode(60)))
    }

    @Test fun seekingAndReplayedEndCallbacksDoNotInventTimeOrCompletions() {
        val token = episode(90)
        repeat(5) { session.complete(token, episode = true) }
        assertFalse(session.requireConfirmation(token))
        episode(0) // A seek to the end contributes no playing time.
        assertTrue(session.requireConfirmation(episode(0)))
        session.reset()
        repeat(3) { assertFalse(session.requireConfirmation(episode(0))) }
    }

    @Test fun oldPlayersCannotAffectReplacementOrExitedSession() {
        val old = episode(90)
        val token = session.start()
        session.playback(old, true, true); time += 90 * minute
        session.complete(old, episode = true)
        session.complete(token, episode = true)
        assertFalse(session.requireConfirmation(token))
        session.reset()
        session.playback(token, true, true); time += 90 * minute
        session.complete(token, episode = true)
        assertFalse(session.requireConfirmation(token))
    }

    @Test fun seekingBackAfterEndCountsActualPlaybackWithoutCountingCompletionTwice() {
        val token = episode(90)
        session.interact()
        session.playback(token, true, true); time += 90 * minute
        assertFalse(session.requireConfirmation(token))
        session.playback(token, false, true)
        session.complete(token, episode = true)
        assertFalse(session.requireConfirmation(episode(0)))
        assertFalse(session.requireConfirmation(episode(0)))
        assertTrue(session.requireConfirmation(episode(0)))
    }

    @Test fun promptIsLatchedAndInputRestartsResponseWindow() {
        episode(30); episode(30)
        val token = episode(30)
        assertTrue(session.requireConfirmation(token))
        time += 59_001
        assertEquals(1, session.responseSeconds)
        session.interact()
        assertTrue(session.pending)
        assertTrue(session.requireConfirmation(token))
        assertEquals(60, session.responseSeconds)
        time += 60_000
        assertEquals(0, session.responseSeconds)
    }

    @Test fun promptCountdownSuspendsInBackgroundAndKeepsRemainingTime() {
        episode(30); episode(30)
        val token = episode(30)
        session.requireConfirmation(token)
        time += 10_000
        session.playback(token, false, false)
        time += 90 * minute
        assertEquals(50, session.responseSeconds)
        session.playback(token, false, true)
        time += 49_000
        assertEquals(1, session.responseSeconds)
        time += 1000
        assertEquals(0, session.responseSeconds)
    }

    @Test fun moviesDoNotCountAsEpisodesAndColdSessionHasNoHistory() {
        val token = session.start()
        session.playback(token, true, true); time += 180 * minute
        session.complete(token, episode = false)
        assertFalse(session.requireConfirmation(token))
        assertFalse(WatchingSession({ time }).pending)
    }
}
