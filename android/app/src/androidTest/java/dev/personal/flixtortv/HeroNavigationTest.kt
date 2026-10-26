package dev.personal.flixtortv

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in remote regression coverage using isolated storage and service fixtures. */
@RunWith(AndroidJUnit4::class)
class HeroNavigationTest {
    @Test fun playFocusAndWholeHeroSurviveRemoteNavigation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("navigationQA") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.setInTouchMode(false)
        val automation = instrument.uiAutomation
        val target = instrument.targetContext
        val prefix = "navigation_test_${UUID.randomUUID()}_"
        val folder = File(target.cacheDir, prefix).apply { mkdirs() }
        val artworkFile = File(folder, "fixture-art.png")
        Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(Color.rgb(30, 80, 120))
            artworkFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val artwork = artworkFile.toURI().toString()
        val app = object : Application() {
            init { attachBaseContext(object : ContextWrapper(target) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    super.getSharedPreferences(prefix + name, mode)
                override fun getFilesDir() = folder
            }) }
        }
        val show = Title("1", "/watch/tv/1/fixture", "Fixture show", synopsis = "Fixture synopsis")
        val movie = Title("2", "/watch/movie/2/fixture", "Fixture movie")
        val episodes = (1..8).map { Episode(1, it, "Episode $it") }
        val showHtml = "<meta property='og:type' content='video.tv_show'><table>" + episodes.joinToString("") {
            "<tr class=eplist data-pes='${it.season}' data-pep='${it.number}'><td class=epTitle>${it.name}</td></tr>"
        } + "</table>"
        val cards = "<div class=contentListM><div class=cflip data-href='${movie.path}'>" +
            "<div class=front><div class=card-footer>${movie.name}</div></div></div></div>"
        val mediaCalls = AtomicInteger()
        val detailCalls = AtomicInteger()
        var delayed: CompletableDeferred<String>? = null
        var failDetail = false
        val client = FlixtorClient(SessionStore(app)) { path -> when {
            path == "/home" -> "<meta name=csrf-token content=fixture>$cards"
            path == show.path -> {
                detailCalls.incrementAndGet()
                if (failDetail) throw SiteException(Failure.NETWORK)
                delayed?.let { withContext(NonCancellable) { it.await() } } ?: showHtml
            }
            path.startsWith("/watch/") -> "<meta property='og:type' content='video.movie'>"
            path.startsWith("/ajax/v4/") -> {
                mediaCalls.incrementAndGet()
                // End at an actionable playback error; no external video/network is needed.
                throw SiteException(Failure.MEDIA)
            }
            else -> cards
        } }
        val activity = instrument.startActivitySync(Intent(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val store = ViewModelStore()
        lateinit var vm: TvViewModel
        lateinit var mutable: MutableStateFlow<TvState>
        instrument.runOnMainSync {
            vm = TvViewModel(app, client)
            store.put("fixture", vm)
            @Suppress("UNCHECKED_CAST")
            mutable = TvViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }
                .get(vm) as MutableStateFlow<TvState>
            activity.setContent { MaterialTheme { TvApp(vm) } }
        }
        val profile = vm.state.value.profiles.first()
        val base = TvState(profile = profile, profiles = listOf(profile), account = Account(false),
            accountChecked = true, hasMore = false, shelves = listOf(
                Shelf("movies", (1..6).map { movie.copy(id = (it + 10).toString(), name = "Movie $it") }),
                Shelf("shows", (1..6).map { show.copy(id = (it + 20).toString(), name = "Show $it") }),
            ))
        fun labels(node: AccessibilityNodeInfo?): String {
            if (node == null) return ""
            node.refresh()
            return (listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
                (0 until node.childCount).map { labels(node.getChild(it)) }).joinToString(" ")
        }
        fun find(predicate: (AccessibilityNodeInfo) -> Boolean,
            node: AccessibilityNodeInfo? = automation.rootInActiveWindow): AccessibilityNodeInfo? {
            if (node == null) return null
            node.refresh()
            if (predicate(node)) return node
            repeat(node.childCount) { find(predicate, node.getChild(it))?.let { return it } }
            return null
        }
        fun focusedLabel(): String {
            fun focused(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                node.refresh()
                repeat(node.childCount) { focused(node.getChild(it))?.let { return it } }
                return node.takeIf { it.isFocused }
            }
            val focus = focused(automation.rootInActiveWindow) ?: return ""
            val bounds = Rect().also { focus.getBoundsInScreen(it) }
            fun matching(node: AccessibilityNodeInfo?): String {
                if (node == null) return ""
                node.refresh()
                val rect = Rect().also { node.getBoundsInScreen(it) }
                val own = if (rect.contains(bounds.centerX(), bounds.centerY()) &&
                    rect.width() <= bounds.width() * 1.5 && rect.height() <= bounds.height() * 1.5)
                    listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ") else ""
                return (listOf(own) + (0 until node.childCount).map { matching(node.getChild(it)) }).joinToString(" ")
            }
            return (labels(focus) + matching(automation.rootInActiveWindow)).replace(Regex("\\s+"), " ").trim()
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 7000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue("$message; focus=${focusedLabel()}; window=${labels(automation.rootInActiveWindow)}", condition())
        }
        fun render(state: TvState) { instrument.runOnMainSync { mutable.value = state }; instrument.waitForIdleSync(); Thread.sleep(250) }
        fun key(code: Int) { instrument.sendKeyDownUpSync(code); instrument.waitForIdleSync(); Thread.sleep(150) }
        fun activate(id: Int) {
            val label = target.getString(id)
            repeat(6) {
                if (find({ it.isClickable && labels(it) == label }) == null) {
                    // The shorter viewport virtualizes the lower rail destinations.
                    find({ node ->
                        val rect = Rect().also { node.getBoundsInScreen(it) }
                        node.isScrollable && rect.width() < target.resources.displayMetrics.widthPixels / 3
                    })?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    Thread.sleep(250)
                }
            }
            val node = requireNotNull(find({ it.isClickable && labels(it) == label })) { "Missing action: $label" }
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrument.waitForIdleSync()
        }
        fun wholeHero(): Boolean {
            val hero = find({ it.viewIdResourceName == "home_hero" }) ?: return false
            val viewport = find({ it.viewIdResourceName == "home_viewport" }) ?: return false
            val h = Rect().also { hero.getBoundsInScreen(it) }
            val v = Rect().also { viewport.getBoundsInScreen(it) }
            val heightDp = v.height() / target.resources.displayMetrics.density
            val expected = v.height() * if (heightDp < 420) .52f else .62f
            return kotlin.math.abs(h.top - v.top) <= 2 && kotlin.math.abs(h.height() - expected) <= 3
        }
        fun capture(name: String) {
            val suffix = InstrumentationRegistry.getArguments().getString("captureSize", "1080")
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: target.getExternalFilesDir(null)!!
            output.mkdirs()
            val bitmap = automation.takeScreenshot()
            File(output, "$name-$suffix.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        try {
            // Every rail destination must survive activation, including lazy content screens.
            for (screen in listOf(Screen.SEARCH, Screen.MOVIES, Screen.SHOWS, Screen.LIST, Screen.ACCOUNT, Screen.PROFILES)) {
                render(base.copy(screen = Screen.HOME))
                val id = when (screen) {
                    Screen.SEARCH -> R.string.search; Screen.MOVIES -> R.string.movies
                    Screen.SHOWS -> R.string.shows; Screen.LIST -> R.string.my_list
                    Screen.ACCOUNT -> R.string.settings; else -> R.string.profiles
                }
                activate(id)
                await("Rail $screen must navigate") { vm.state.value.screen == screen }
                Thread.sleep(250)
            }

            // Missing artwork during slow loading, then success: loading changes must not steal focus.
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            delayed = CompletableDeferred()
            render(base.copy(screen = Screen.HOME, shelves = listOf(Shelf("shows", listOf(show))), focusedKey = "hero"))
            await("Hero Play focus") { focusedLabel().contains(target.getString(R.string.play)) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Show opens detail while loading") { vm.state.value.screen == Screen.DETAIL && vm.state.value.loading }
            await("Back is focused without artwork") { focusedLabel().contains(target.getString(R.string.back)) }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            val loadingFocus = focusedLabel()
            delayed.complete(showHtml)
            await("Episodes load") { !vm.state.value.loading && vm.state.value.detail?.episodes?.size == 8 }
            assertEquals("Successful load must preserve focus", loadingFocus, focusedLabel())
            assertEquals(0, mediaCalls.get())
            assertEquals("hero", vm.state.value.focusedKey)
            instrument.runOnMainSync { vm.play(episodes[2]) }
            await("Only selecting an episode requests media, with one silent recovery") { mediaCalls.get() == 2 && !vm.state.value.loading }
            assertEquals(episodes[2], vm.state.value.episode)
            assertEquals(Screen.PLAYER, vm.state.value.screen)
            key(KeyEvent.KEYCODE_BACK); key(KeyEvent.KEYCODE_BACK)
            await("Return Home restores whole hero") { vm.state.value.screen == Screen.HOME && wholeHero() }

            // Failure/retry and empty episode results stay navigable with no image.
            delayed = null; failDetail = true
            instrument.runOnMainSync { vm.playTitle(show) }
            await("Detail failure") { vm.state.value.failure == Failure.NETWORK }
            assertEquals(Screen.DETAIL, vm.state.value.screen)
            failDetail = false
            instrument.runOnMainSync { vm.refresh() }
            await("Detail retry") { !vm.state.value.loading && vm.state.value.failure == null }
            render(base.copy(screen = Screen.HOME))
            render(base.copy(screen = Screen.DETAIL, detail = Detail(show, emptyList())))
            await("Empty episodes expose Back") { focusedLabel().contains(target.getString(R.string.back)) }
            key(KeyEvent.KEYCODE_BACK)

            // A cancelled detail response must not reopen detail or playback.
            delayed = CompletableDeferred()
            val calls = detailCalls.get()
            instrument.runOnMainSync { vm.playTitle(show) }
            await("Delayed request started") { detailCalls.get() > calls }
            key(KeyEvent.KEYCODE_BACK)
            delayed.complete(showHtml)
            Thread.sleep(250)
            assertEquals(Screen.HOME, vm.state.value.screen)
            assertEquals(2, mediaCalls.get())
            delayed = null

            // A nonblank artwork model exercises the exact renderer that crashed on the TV.
            for (title in listOf(show, movie)) {
                render(base.copy(screen = Screen.HOME))
                instrument.runOnMainSync { vm.openFrom(title.copy(poster = artwork), "hero") }
                await("Artwork detail loads without crashing") { vm.state.value.screen == Screen.DETAIL && !vm.state.value.loading }
                await("Artwork detail Back target") { focusedLabel().contains(target.getString(R.string.back)) }
                capture("detail-artwork-${title.key.replace(':', '-')}")
                key(KeyEvent.KEYCODE_BACK)
            }

            LibraryStore(app, profile.id).save(Viewing(movie, null, 15000, 90000))
            instrument.runOnMainSync { vm.playTitle(movie) }
            await("Movie Play requests media immediately, with one silent recovery") { mediaCalls.get() == 4 && !vm.state.value.loading }
            assertEquals(Screen.PLAYER, vm.state.value.screen)
            assertNull(vm.state.value.episode)
            assertEquals(15000L, vm.state.value.resumePosition)
            assertEquals("hero", vm.state.value.focusedKey)

            val artBase = base.copy(shelves = base.shelves.map { shelf ->
                shelf.copy(titles = shelf.titles.map { it.copy(poster = artwork, backdrop = artwork) })
            })
            for (fit in listOf(ScreenFit(), ScreenFit(10, 10))) {
                render(artBase.copy(screen = Screen.HOME, focusedKey = "hero", screenFit = fit))
                await("Full hero on entry") { wholeHero() }
                key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_DPAD_UP)
                await("Full hero after scrolling up") { focusedLabel().contains(target.getString(R.string.play)) && wholeHero() }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Details action keeps full hero") { focusedLabel().contains(target.getString(R.string.details)) && wholeHero() }
                capture("home-hero-${fit.horizontal}")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Details opens") { vm.state.value.screen == Screen.DETAIL && !vm.state.value.loading }
                key(KeyEvent.KEYCODE_BACK)
                await("Details return restores Play and full hero") { focusedLabel().contains(target.getString(R.string.play)) && wholeHero() }
            }
        } finally {
            delayed?.complete(showHtml)
            instrument.runOnMainSync { activity.finish(); store.clear() }
            folder.deleteRecursively()
        }
    }
}
