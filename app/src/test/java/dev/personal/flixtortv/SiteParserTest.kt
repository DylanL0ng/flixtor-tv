package dev.personal.flixtortv

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SiteParserTest {
    @Test fun fullGuestServersAreNotAStructuralFailure() {
        // Sanitized shape observed on the live service; omit navigation, tokens and scripts.
        val html = """<meta name="csrf-token" content="fixture"><div id="content">
            <div>...all our servers are full right now.</div>
            <div>Except for VIPs, we cannot accept additional users at the moment.</div></div>"""
        assertEquals(Failure.CAPACITY, SiteParser.responseFailure(html))
        assertEquals(AccountStatus.GUEST, SiteParser.account(html).status)
        try { SiteParser.home(html); fail("Expected capacity outcome") }
        catch (error: SiteException) { assertEquals(Failure.CAPACITY, error.failure) }
    }
    private val card = """
        <div class="cflip" data-href="/watch/movie/123/example">
          <div class="front"><img src="https://example.org/poster.jpg" alt="Example">
            <div class="card-footer">Example</div>
            <div class="card-badge top"><span class="badge">2026</span></div>
            <div class="card-badge bottom"><span class="badge">8.1</span></div>
          </div><div class="genres">Drama</div>
        </div>
    """.trimIndent()
    @Test fun cardsUseDomainIdentityAndIgnoreUnrecognizedPaths() {
        val parsed = SiteParser.cards(card + card + card.replace("/watch/movie/123/example", "https://malicious.example/"))
        assertEquals(1, parsed.size)
        assertEquals("m:123", parsed.single().key)
        assertEquals("Example", parsed.single().name)
        assertEquals("2026", parsed.single().year)
    }
    @Test fun changedHomeNeverLooksLikeAnEmptyCatalogue() {
        try { SiteParser.home("<html>Access denied</html>"); fail("Expected response-shape failure") }
        catch (e: SiteException) { assertEquals(Failure.ACCESS, e.failure) }
    }
    @Test fun onlyRecognizedZeroResultsBecomeAnEmptyCatalogue() {
        assertTrue(SiteParser.cards("<div class=contentList>No results</div>").isEmpty())
        try { SiteParser.cards("<html>Please verify your browser</html>"); fail("Expected response-shape failure") }
        catch (e: SiteException) { assertEquals(Failure.ACCESS, e.failure) }
    }
    @Test fun episodeIdentityIncludesSeasonAndNumber() {
        val original = Title("123", "/watch/tv/123/example", "Example")
        val html = """<meta property="og:type" content="video.tv_show">
            <meta name="description" content="Example synopsis">
            <table><tr class="eplist" data-pes="2" data-pep="1"><td class="epTitle">Return</td></tr>
            <tr class="eplist" data-pes="1" data-pep="1"><td class="epTitle">Start</td></tr></table>"""
        val detail = SiteParser.detail(html, original)
        assertEquals(listOf("2:1", "1:1"), detail.episodes.map { it.key })
        assertEquals("Example synopsis", detail.title.synopsis)
    }
    @Test fun transportEncodingRoundTripsUtf8AndSignedUrls() {
        val json = """{"file":"https://media.example/playlist.m3u8?token=abc%2B123","tracks":[{"file":"https://media.example/sub.vtt","label":"日本語","kind":"captions"}]}"""
        val shifted = json.map { c -> if (c.code in 33..126) (33 + (c.code - 33 + 47) % 94).toChar() else c }.joinToString("")
        val encoded = Base64.getEncoder().encodeToString(shifted.toByteArray(Charsets.UTF_8)).map { c ->
            when(c) {
                in 'a'..'z' -> ('a'.code + (c.code - 'a'.code + 13) % 26).toChar()
                in 'A'..'Z' -> ('A'.code + (c.code - 'A'.code + 13) % 26).toChar()
                else -> c
            }
        }.joinToString("")
        assertEquals(json, SiteParser.decodePlayback(encoded))
        assertEquals("日本語", SiteParser.stream(encoded).captions.single().label)
        assertTrue(SiteParser.stream(encoded).uri.endsWith("token=abc%2B123"))
    }
    @Test fun plainPlaybackJsonAndRelativeCaptionsAreSupported() {
        val stream = SiteParser.stream("""{"file":"https://media.example/a.m3u8","tracks":[{"file":"/captions/en.srt","label":"English"},{"file":"/thumbs.vtt","kind":"thumbnails"}]}""")
        assertEquals(1, stream.captions.size)
        assertEquals("https://flixtor.to/captions/en.srt", stream.captions.single().uri)
        assertEquals("application/x-subrip", stream.captions.single().mime)
    }
    @Test fun unsafePlaybackAndMalformedResponsesAreRejected() {
        for (raw in listOf("""{"file":"http://media.example/a.m3u8"}""", "<html>Sign in</html>", "{}")) {
            try { SiteParser.stream(raw); fail("Expected rejection") }
            catch (e: SiteException) { assertEquals(Failure.CHANGED, e.failure) }
        }
    }
    @Test fun firstPartyPathsCannotEscapeTheOrigin() {
        for (path in listOf("//evil.example/path", "https://evil.example/path", "relative")) {
            try { SiteParser.safePath(path); fail("Expected path rejection") } catch (_: IllegalArgumentException) { }
        }
        assertEquals("/show/movies?ajax=1", SiteParser.safePath("/show/movies?ajax=1"))
    }
    @Test fun recognizedRestrictionsPreserveActionableFailures() {
        val fixtures = mapOf(
            "{\"error\":\"Please sign in\"}" to Failure.SIGNED_OUT,
            "{\"error\":\"VIP required\"}" to Failure.VIP,
            "<html><body>Session expired</body></html>" to Failure.EXPIRED,
            "{\"message\":\"Account is blocked\"}" to Failure.BLOCKED,
            "<html>Access denied</html>" to Failure.ACCESS,
        )
        fixtures.forEach { (body, expected) ->
            try { SiteParser.stream(body); fail("Expected restriction") }
            catch (error: SiteException) { assertEquals(expected, error.failure) }
        }
        assertEquals(Failure.UNAVAILABLE, SiteParser.responseFailure("", 429))
        assertEquals(Failure.UNAVAILABLE, SiteParser.responseFailure("", 503))
        assertEquals(Failure.SIGNED_OUT, SiteParser.responseFailure("", 401))
    }
    @Test fun normalPagesAndStreamsDoNotTreatVipMarketingAsADenial() {
        assertEquals(1, SiteParser.cards(card + "<p>VIP members: please sign in</p>").size)
        assertNotNull(SiteParser.stream("""{"file":"https://media.example/a.m3u8","message":"VIP members"}"""))
    }
    @Test fun invalidOptionalCaptionsDoNotBlockAnAuthorizedStream() {
        val stream = SiteParser.stream("""{"file":"https://media.example/a.m3u8","tracks":[null,{"file":"http://unsafe.example/a.vtt"},{"file":"https://media.example/valid.vtt"}]}""")
        assertEquals(1, stream.captions.size)
    }
    @Test fun emptyFileCannotHideAccessErrors() {
        val fixtures = listOf(
            """{"file":"","message":"VIP required"}""" to Failure.VIP,
            """{"file":null,"error":"Please log in"}""" to Failure.SIGNED_OUT,
            """{"file":"","reason":"Session expired"}""" to Failure.EXPIRED,
            """{"error":"Only available to VIP members"}""" to Failure.VIP,
            """<section>Outside the free access limit</section>""" to Failure.VIP,
        )
        fixtures.forEach { (body, expected) ->
            try { SiteParser.stream(body); fail("Expected access restriction") }
            catch (error: SiteException) { assertEquals(expected, error.failure) }
        }
        assertEquals(Failure.VIP, SiteParser.responseFailure("", 443))
        assertEquals(Failure.SIGNED_OUT, SiteParser.responseFailure("""{"file":"https://media.example/a.m3u8"}""", 401))
    }
    @Test fun accountDoesNotInferVipFromMarketing() {
        assertEquals(AccountStatus.GUEST, SiteParser.account("<p>VIP active</p>").status)
        assertEquals(AccountStatus.SIGNED_IN, SiteParser.account("""<a class="logout">Logout</a><p>VIP members</p>""").status)
        assertEquals(AccountStatus.VIP, SiteParser.account("""<a class="logout">Logout</a><span data-vip-status="active"></span>""").status)
        assertEquals(AccountStatus.EXPIRED, SiteParser.account("""<a class="logout">Logout</a><span data-vip-status="expired"></span>""").status)
    }
    @Test fun encodedDenialRemainsAnAccessFailure() {
        val json = """{"file":"","error":"VIP required"}"""
        val shifted = json.map { c -> if (c.code in 33..126) (33 + (c.code - 33 + 47) % 94).toChar() else c }.joinToString("")
        val encoded = Base64.getEncoder().encodeToString(shifted.toByteArray()).map { c ->
            when(c) {
                in 'a'..'z' -> ('a'.code + (c.code - 'a'.code + 13) % 26).toChar()
                in 'A'..'Z' -> ('A'.code + (c.code - 'A'.code + 13) % 26).toChar()
                else -> c
            }
        }.joinToString("")
        try { SiteParser.stream(encoded); fail("Expected VIP restriction") }
        catch (error: SiteException) { assertEquals(Failure.VIP, error.failure) }
    }

}
