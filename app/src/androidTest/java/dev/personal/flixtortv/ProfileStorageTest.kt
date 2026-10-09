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
