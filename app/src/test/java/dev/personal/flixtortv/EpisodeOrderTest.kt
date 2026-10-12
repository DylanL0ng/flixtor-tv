package dev.personal.flixtortv

import org.junit.Assert.*
import org.junit.Test

class EpisodeOrderTest {
    private val series = Title("1", "/watch/tv/1/example", "Example")
    private val film = Title("2", "/watch/movie/2/example", "Film")
    private fun episode(season: Int, number: Int) = Episode(season, number, "S${season}E$number")
    /** The service lists newest first; watch order must not inherit that. */
    private val detail = Detail(series, listOf(episode(2, 1), episode(1, 3), episode(1, 1), episode(1, 2), episode(2, 2)))

    @Test fun orderedEpisodesFollowSeasonThenNumber() {
        assertEquals(listOf("1:1", "1:2", "1:3", "2:1", "2:2"), detail.ordered.map { it.key })
    }

    @Test fun nextAdvancesWithinAndAcrossSeasons() {
        assertEquals("1:2", detail.next(episode(1, 1))?.key)
        assertEquals("2:1", detail.next(episode(1, 3))?.key)
    }

    @Test fun finalEpisodeAndUnknownEpisodeHaveNoNext() {
        assertNull(detail.next(episode(2, 2)))
        assertNull(detail.next(episode(9, 9)))
    }

    @Test fun emptyDetailHasNoEpisodesOrNext() {
        val blank = Detail(series, emptyList())
        assertTrue(blank.ordered.isEmpty())
        assertNull(blank.next(null))
    }

    @Test fun finishedAndFractionReflectSavedProgress() {
        val watching = Viewing(series, episode(1, 1), 30_000, 600_000)
        assertFalse(watching.finished)
        assertEquals(0.05f, watching.fraction, 0.0001f)
        // Credits within the last 30 seconds count as finished.
        assertTrue(Viewing(series, episode(1, 1), 599_000, 600_000).finished)
        assertEquals(1f, Viewing(series, episode(1, 1), 599_000, 600_000).fraction, 0.0001f)
        // Unknown duration never claims progress.
        assertEquals(0f, Viewing(series, episode(1, 1), 5_000, 0).fraction, 0.0001f)
    }

    @Test fun viewingKeysSeparateEpisodesOfTheSameShow() {
        val first = Viewing(series, episode(1, 1), 10, 100)
        val second = Viewing(series, episode(1, 2), 10, 100)
        val movie = Viewing(film, null, 10, 100)
        assertEquals("t:1:1:1", first.key)
        assertEquals("t:1:1:2", second.key)
        assertEquals("m:2", movie.key)
    }

    @Test fun startSeasonOpensTheSeasonStillBeingWatched() {
        val viewing = listOf(Viewing(series, episode(1, 1), 500_000, 600_000), Viewing(series, episode(2, 1), 590_000, 600_000))
        assertEquals(1, startSeason(viewing, series))
        assertEquals(null, startSeason(listOf(Viewing(series, episode(2, 1), 590_000, 600_000)), series))
        assertEquals(null, startSeason(emptyList(), series))
    }

    @Test fun profileDefaultsToAutoplayOnAndUnlocked() {
        val profile = Profile("default", "Mine")
        assertTrue(profile.autoplay)
        assertFalse(profile.locked)
        assertTrue(Profile("default", "Mine", autoplay = false, pin = "abc").locked)
    }
}
