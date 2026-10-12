package dev.personal.flixtortv

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProfileStorageTest {
    @Test fun screenFitIsDeviceWideAndSurvivesRestart() {
        val context = isolatedContext()
        assertEquals(ScreenFit(), ScreenFitStore(context).read())
        ScreenFitStore(context).save(ScreenFit(10, 0))
        assertEquals(ScreenFit(10, 0), ScreenFitStore(context).read())
        val profiles = ProfileStore(context)
        profiles.initialize()
        profiles.save(null, "Another", 1)
        assertEquals(ScreenFit(10, 0), ScreenFitStore(context).read())
        try { ScreenFit(-1, 5); fail("Expected bounded margins") } catch (_: IllegalArgumentException) { }
    }
    private fun isolatedContext(): Context {
        val prefix = "test_${UUID.randomUUID()}_"
        return object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
    }
    @Test fun migrationIsIdempotentAndProfilesStayIsolated() {
        val context = isolatedContext()
        val old = context.getSharedPreferences("library", Context.MODE_PRIVATE)
        val json = """[{"id":"1","path":"/watch/movie/1/test","name":"Example"}]"""
        old.edit().putString("favorites", json).commit()
        val profiles = ProfileStore(context)
        profiles.initialize()
        val first = LibraryStore(context, "default")
        assertEquals(1, first.favorites().size)
        first.clear()
        profiles.initialize()
        assertTrue(first.favorites().isEmpty())
        val second = profiles.save(null, "Second", 1).last()
        first.toggle(Title("2", "/watch/movie/2/test", "Only first"))
        assertTrue(LibraryStore(context, second.id).favorites().isEmpty())
        assertEquals(1, first.favorites().size)
        assertEquals(2, ProfileStore(context).all().size)
    }
    @Test fun qualityDeletionAndLimitsSurviveRestart() {
        val context = isolatedContext()
        val profiles = ProfileStore(context)
        profiles.initialize()
        repeat(6) { profiles.save(null, "Viewer $it", it) }
        try { profiles.save(null, "Overflow", 0); fail("Expected profile limit") } catch (_: IllegalArgumentException) { }
        profiles.quality("default", 1080)
        assertEquals(1080, ProfileStore(context).all().first().quality)
        val deleted = profiles.all().last()
        LibraryStore(context, deleted.id).toggle(Title("1", "/watch/movie/1/test", "Private"))
        profiles.delete(deleted.id)
        assertTrue(LibraryStore(context, deleted.id).favorites().isEmpty())
        profiles.all().drop(1).forEach { profiles.delete(it.id) }
        try { profiles.delete("default"); fail("Cannot delete final profile") } catch (_: IllegalArgumentException) { }
    }
    @Test fun pinsAndAutoplaySurviveRestartAndStayPrivate() {
        val context = isolatedContext()
        val profiles = ProfileStore(context)
        profiles.initialize()
        val locked = profiles.save(null, "Locked", 2).last()
        val open = profiles.save(null, "Open", 3).last()
        assertFalse(ProfileStore(context).all().first { it.id == locked.id }.locked)
        // The stored value is a digest, never the entered digits.
        ProfileStore(context).pin(locked.id, "2468")
        val stored = ProfileStore(context).all().first { it.id == locked.id }
        assertTrue(stored.locked)
        assertFalse(stored.pin.contains("2468"))
        assertFalse(ProfileStore(context).all().first { it.id == open.id }.locked)
        // A new store instance reads the same lock back, as after a restart.
        assertTrue(ProfileStore(context).unlock(locked.id, "2468"))
        assertFalse(ProfileStore(context).unlock(locked.id, "1357"))
        assertFalse(ProfileStore(context).unlock(locked.id, "24"))
        assertTrue(ProfileStore(context).unlock(open.id, ""))
        // Bounded input only.
        try { ProfileStore(context).pin(locked.id, "12"); fail("Expected a four digit PIN") } catch (_: IllegalArgumentException) { }
        try { ProfileStore(context).pin(locked.id, "12ab"); fail("Expected digits only") } catch (_: IllegalArgumentException) { }
        // Removing the PIN unlocks the profile again.
        ProfileStore(context).pin(locked.id, null)
        assertFalse(ProfileStore(context).all().first { it.id == locked.id }.locked)
    }

    @Test fun autoplayDefaultsOnAndIsPerProfile() {
        val context = isolatedContext()
        val profiles = ProfileStore(context)
        profiles.initialize()
        val other = profiles.save(null, "Other", 1).last()
        assertTrue(ProfileStore(context).all().all { it.autoplay })
        ProfileStore(context).autoplay("default", false)
        val stored = ProfileStore(context).all()
        assertFalse(stored.first { it.id == "default" }.autoplay)
        assertTrue(stored.first { it.id == other.id }.autoplay)
    }

    @Test fun markedSkipSegmentsAndImdbIdsSurviveRestart() {
        val context = isolatedContext()
        val show = Title("1", "/watch/tv/1/example", "Example")
        val episode = Episode(2, 3, "Return")
        val film = Title("2", "/watch/movie/2/film", "Film")
        assertEquals(null, SkipStore(context).imdbId(show))
        // IMDb ids are per title and never guessed.
        SkipStore(context).setImdbId(show, "tt0903747")
        assertEquals("tt0903747", SkipStore(context).imdbId(show))
        assertEquals(null, SkipStore(context).imdbId(film))
        SkipStore(context).setImdbId(show, "")
        assertEquals(null, SkipStore(context).imdbId(show))

        SkipStore(context).setSegment(show, episode, SkipSegment(SegmentType.INTRO, 437000, 531000))
        SkipStore(context).setSegment(show, episode, SkipSegment(SegmentType.CREDITS, 3431000, 3500000))
        val stored = SkipStore(context).segments(show, episode)
        assertEquals(listOf(SegmentType.INTRO, SegmentType.CREDITS), stored.map { it.type })
        assertEquals(437000L, stored.first().startMs)
        // Marks are scoped to the episode and to the title.
        assertTrue(SkipStore(context).segments(show, Episode(2, 4, "Other")).isEmpty())
        assertTrue(SkipStore(context).segments(show, null).isEmpty())
        assertTrue(SkipStore(context).segments(film, null).isEmpty())
        // Marking the same type replaces it rather than duplicating.
        SkipStore(context).setSegment(show, episode, SkipSegment(SegmentType.INTRO, 400000, 500000))
        val replaced = SkipStore(context).segments(show, episode)
        assertEquals(2, replaced.size)
        assertEquals(400000L, replaced.first { it.type == SegmentType.INTRO }.startMs)
        SkipStore(context).setSegments(show, episode, replaced.filterNot { it.type == SegmentType.INTRO })
        assertEquals(listOf(SegmentType.CREDITS), SkipStore(context).segments(show, episode).map { it.type })
        SkipStore(context).setSegments(show, episode, emptyList())
        assertTrue(SkipStore(context).segments(show, episode).isEmpty())
    }

    @Test fun capturedPlaybackWritesOnlyToItsOriginalProfile() {
        val context = isolatedContext()
        val profiles = ProfileStore(context)
        profiles.initialize()
        val second = profiles.save(null, "Other viewer", 2).last()
        val title = Title("1", "/watch/movie/1/test", "Example")
        val captured = PlaybackIdentity("default", title, null)
        LibraryStore(context, captured.profileId).save(Viewing(captured.title, captured.episode, 10000, 100000))
        assertEquals(10000L, LibraryStore(context, "default").viewing().single().position)
        assertTrue(LibraryStore(context, second.id).viewing().isEmpty())
    }
}
