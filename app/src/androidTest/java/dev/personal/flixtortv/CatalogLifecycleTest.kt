package dev.personal.flixtortv

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class CatalogLifecycleTest {
    @Test fun appendRetrySwitchAndLatePlaybackRemainIsolated() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val target = instrument.targetContext
        val prefix = "catalog_test_${UUID.randomUUID()}_"
        val folder = File(target.cacheDir, prefix).apply { mkdirs() }
        val app = object : Application() {
            init { attachBaseContext(object : ContextWrapper(target) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
                override fun getFilesDir() = folder
            }) }
        }
        fun cards(vararg ids: Int) = "<div class=contentListM>" + ids.joinToString("") { id ->
            "<div class=cflip data-href='/watch/movie/$id/test'><div class=front><img src='https://media.example/$id.jpg'><div class=card-footer>Title $id</div></div></div>"
        } + "</div>"
        val delayed = CompletableDeferred<String>()
        val delayedSearch = CompletableDeferred<String>()
        val searchCalls = AtomicInteger()
        val appendCalls = AtomicInteger()
        var failAppend = true
        var loggedIn = false
        val client = FlixtorClient(SessionStore(app)) { path -> when {
            path == "/home" -> "<meta name=csrf-token content=fixture>" + cards(1) +
                if (loggedIn) "<a class=logout>Sign out</a>" else ""
            path == "/account" -> "<a class=logout>Sign out</a><span class=acct-status>VIP Active</span>"
            path == "/ajax/viplogin" -> { loggedIn = true; "{}" }
            path.startsWith("/search/slow") -> { searchCalls.incrementAndGet(); withContext(NonCancellable) { delayedSearch.await() } }
            path.startsWith("/search/fast") -> cards(9)
            path.startsWith("/search/empty") -> "<div class=contentList>No results</div>"
            path.contains("page=2") -> {
                appendCalls.incrementAndGet()
                if (failAppend) throw SiteException(Failure.NETWORK)
                withContext(NonCancellable) { delayed.await() }
            }
            path.startsWith("/watch/") -> "<meta property='og:type' content='video.movie'>"
            path.startsWith("/ajax/v4/") -> """{"file":"https://media.example/qa.m3u8"}"""
            else -> cards(1, 2)
        } }
        lateinit var vm: TvViewModel
        val store = ViewModelStore()
        fun main(block: () -> Unit) = instrument.runOnMainSync(block)
        fun waitFor(condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 5000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue("State transition timed out: ${vm.state.value.screen}", condition())
        }
        main { vm = TvViewModel(app, client); store.put("test", vm); vm.selectProfile("default"); vm.navigate(Screen.MOVIES) }
        try {
            waitFor { !vm.state.value.loading && vm.state.value.titles.size == 2 }
            main { vm.browse(true) }
            waitFor { vm.state.value.appendFailure == Failure.NETWORK }
            assertEquals(2, vm.state.value.titles.size)
            failAppend = false
            main { vm.browse(true); vm.browse(true); vm.browse(true) }
            waitFor { appendCalls.get() == 2 }
            assertTrue(vm.state.value.appending)
            main { vm.saveProfile(null, "Second", 1) }
            val second = vm.state.value.profiles.last().id
            main { vm.selectProfile(second) }
            delayed.complete(cards(2, 3))
            waitFor { !vm.state.value.loading }
            assertEquals(second, vm.state.value.profile?.id)
            assertTrue(vm.state.value.titles.isEmpty())
            main { vm.selectProfile("default") }
            waitFor { !vm.state.value.loading }
            main { vm.navigate(Screen.SEARCH); vm.search("slow") }
            waitFor { searchCalls.get() == 1 }
            main { vm.search("fast") }
            waitFor { !vm.state.value.loading && vm.state.value.titles.firstOrNull()?.id == "9" }
            delayedSearch.complete(cards(7))
            Thread.sleep(100)
            assertEquals("9", vm.state.value.titles.single().id)
            main { vm.search("empty") }
            waitFor { !vm.state.value.loading }
            assertTrue(vm.state.value.titles.isEmpty()); assertFalse(vm.state.value.hasMore); assertNull(vm.state.value.failure)
            main { vm.open(Title("1", "/watch/movie/1/test", "Title 1")) }
            waitFor { !vm.state.value.loading }
            main { vm.play(null) }
            waitFor { vm.state.value.stream != null }
            val captured = vm.state.value.playbackIdentity!!
            main { vm.signInForPlayback() }
            assertEquals(Screen.ACCOUNT, vm.state.value.screen)
            main { vm.login("fixture@example.invalid", "fixture-password") }
            waitFor { !vm.state.value.loading && vm.state.value.screen == Screen.PLAYER }
            assertEquals(AccountStatus.VIP, vm.state.value.account.status)
            assertEquals(captured, vm.state.value.playbackIdentity)
            main { vm.selectProfile(second); vm.saveProgress(captured, 10000, 90000) }
            assertEquals(10000L, LibraryStore(app, "default").viewing().single().position)
            assertTrue(LibraryStore(app, second).viewing().isEmpty())
        } finally { delayed.complete(cards(3)); delayedSearch.complete(cards(7)); main { store.clear() }; folder.deleteRecursively() }
    }
}
