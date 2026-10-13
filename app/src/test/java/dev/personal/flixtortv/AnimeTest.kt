package dev.personal.flixtortv

import java.util.Base64
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnimeTest {
    private val title = Title("1335", "/naruto-1335", "Naruto", source = ContentSource.HIANIME, type = ContentType.SERIES)
    private val episode = Episode(1, 1, "Start", "22676", "1", 0)
    private val card = """<div class="film_list-wrap"><div class="flw-item">
        <img class="film-poster-img" src="https://images.example/poster.webp">
        <h3 class="film-name"><a href="https://hianime.at/naruto-1335" title="Naruto">Naruto</a></h3>
        <span class="fdi-item">TV</span></div></div>"""
    private fun fragment(html: String) = JSONObject().put("status", true).put("html", html).toString()
    private fun servers(vararg modes: String) = fragment(modes.joinToString("") { mode ->
        val hash = Base64.getEncoder().encodeToString("https://embed.example/mal/20/1/$mode".toByteArray())
        """<div class="server-item" data-type="$mode" data-server-name="ZokoAnime" data-hash="$hash"></div>"""
    })
    private fun embed(): String {
        val json = """{"src":"https://media.example/master.m3u8?token=fixture","subtitles":[
            {"src":"/english.vtt","label":"English","default":true},
            {"src":"http://unsafe.example/caption.vtt"}]}"""
        val key = "otaku-embed-v1".toByteArray()
        val blob = json.toByteArray().mapIndexed { i, b -> (b.toInt() xor key[i % key.size].toInt()).toByte() }.toByteArray()
        return "window.__P=\"${Base64.getEncoder().encodeToString(blob)}\";"
    }
    @Test fun catalogUsesSourceIdentityAndRecognizesMoviesAndEmptyResults() {
        assertEquals(title.key, AnimeParser.cards(card).single().key)
        assertNotEquals(Title("1335", "/watch/tv/1335/a", "Other").key, title.key)
        assertFalse(AnimeParser.cards(card.replace(">TV<", ">Movie<")).single().isSeries)
        assertTrue(AnimeParser.cards("<div class=film_list-wrap></div>").isEmpty())
        assertEquals(1, AnimeParser.cards(card + "<div id=main-sidebar>$card</div>").size)
        try { AnimeParser.cards(card.replace("https://hianime.at/naruto-1335", "https://evil.example/naruto-1335")); fail() }
        catch (e: SiteException) { assertEquals(Failure.CHANGED, e.failure) }
        try { AnimeParser.cards("<title>Just a moment</title><div class=film_list-wrap></div>"); fail() }
        catch (e: SiteException) { assertEquals(Failure.ACCESS, e.failure) }
        try { AnimeParser.cards("<html>Verify your browser</html>"); fail() }
        catch (e: SiteException) { assertEquals(Failure.CHANGED, e.failure) }
    }
    @Test fun specialsKeepDistinctProviderIdentityAndSourceOrder() {
        val eps = AnimeParser.episodes(fragment("""<a class="ep-item" data-id="1" data-number="1" title="Start"></a>
            <a class="ep-item" data-id="2" data-number="1.5" title="Special"></a>
            <a class="ep-item" data-id="3" data-number="2" title="Return"></a>"""))
        assertEquals(listOf("1", "1.5", "2"), eps.map { it.displayNumber })
        assertEquals(3, eps.map { it.key }.distinct().size)
        assertEquals(eps, Detail(title, eps.reversed()).ordered)
        assertEquals(eps[1], Detail(title, eps).next(eps[0]))
    }
    @Test fun versionsRequireSupportedServerAndDoNotFallback() {
        assertEquals(setOf(AnimeVersion.SUB, AnimeVersion.DUB), AnimeParser.servers(servers("sub", "dub")).keys)
        assertEquals(setOf(AnimeVersion.SUB), AnimeParser.servers(servers("sub")).keys)
        assertTrue(AnimeParser.servers(servers("dub").replace("ZokoAnime", "Unsupported")).isEmpty())
        try { AnimeParser.servers("""{"status":false,"html":""}"""); fail() }
        catch (e: SiteException) { assertEquals(Failure.UNAVAILABLE, e.failure) }
    }
    @Test fun streamPreservesHeaderAndCaptionRequirements() {
        val stream = AnimeParser.stream(embed(), "https://embed.example/mal/20/1/dub")
        assertEquals("https://embed.example/", stream.headers["Referer"])
        assertTrue(stream.uri.endsWith("token=fixture"))
        assertEquals("application/x-mpegURL", stream.mime)
        assertEquals("https://embed.example/english.vtt", stream.captions.single().uri)
        assertTrue(stream.captions.single().default)
        for (url in listOf("http://media.example/a", "https://127.0.0.1/a", "https://u:p@media.example/a", "https://media.example:8080/a")) {
            try { AnimeParser.safeUrl(url); fail("Unsafe URL accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun codecsKeepLegacyKeysAndVersionSpecificProgress() {
        val legacy = ContentJson.title(JSONObject("""{"id":"1335","path":"/watch/tv/1335/a","name":"Legacy"}"""))
        assertEquals("t:1335", legacy.key)
        assertEquals(ContentSource.FLIXTOR, legacy.source)
        assertEquals(title, ContentJson.title(ContentJson.title(title)))
        val dub = Viewing(title, episode, 15000, 120000, AnimeVersion.DUB)
        val sub = dub.copy(version = AnimeVersion.SUB)
        assertNotEquals(dub.key, sub.key)
        assertEquals(dub, ContentJson.viewing(ContentJson.viewing(dub)))
        val old = ContentJson.viewing(JSONObject("""{"title":{"id":"1","path":"/watch/movie/1/a","name":"Old"},"position":10000,"duration":90000}"""))
        assertNull(old.version)
        assertEquals("m:1", old.key)
    }
    @Test fun evictedPagesRetainAnimeRoutingAndExplicitType() {
        val root = Files.createTempDirectory("anime-pages").toFile()
        val pages = CatalogPages(root)
        try {
            var snapshot = pages.append(listOf(title))
            repeat(10) { snapshot = pages.append(listOf(title.copy(id = "other$it"))) }
            assertEquals(title, snapshot.first())
            assertEquals(ContentSource.HIANIME, snapshot.first().source)
        } finally { pages.close(); root.deleteRecursively() }
    }
    @Test fun clientResolvesFreshRequestedVersionAndCancelsPendingWork() = runBlocking {
        val requests = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<String>()
        var delay = false
        val client = AnimeClient { url ->
            requests.add(url)
            if (delay) { entered.complete(Unit); blocked.await() }
            else if (url.contains("/servers?")) servers("sub") else embed()
        }
        try {
            try { client.playback(episode, AnimeVersion.DUB); fail() }
            catch (e: SiteException) { assertEquals(Failure.UNAVAILABLE, e.failure) }
            assertEquals(1, requests.size)
            assertEquals("https://media.example/master.m3u8?token=fixture", client.playback(episode, AnimeVersion.SUB).uri)
            client.playback(episode, AnimeVersion.SUB)
            assertEquals(3, requests.count { it.contains("/servers?") })
            delay = true
            var completed = false
            val job = launch { client.servers(episode); completed = true }
            entered.await(); job.cancelAndJoin(); blocked.complete(servers("dub"))
            assertFalse(completed)
        } finally { client.close() }
    }
}
