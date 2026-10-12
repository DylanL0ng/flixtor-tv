package dev.personal.flixtortv

import org.junit.Assert.*
import org.junit.Test

class IntroDbTest {
    // Verbatim response shapes captured from https://api.introdb.app/segments.
    private val outroOnly = """{"imdb_id":"tt0903747","media_type":"tv","is_movie":false,"season":1,"episode":1,
        "intro":null,"recap":null,"outro":{"start_sec":3431,"end_sec":3500,"start_ms":3431000,"end_ms":3500000,
        "confidence":1,"submission_count":1,"updated_at":"2026-04-14T12:21:01.935Z"},"post_credits":null}"""
    private val introAndOutro = """{"imdb_id":"tt0944947","media_type":"tv","is_movie":false,"season":1,"episode":1,
        "intro":{"start_sec":437,"end_sec":531,"start_ms":437000,"end_ms":531000,"confidence":1,"submission_count":2,
        "updated_at":"2026-06-16T02:49:39.823Z"},"recap":null,
        "outro":{"start_sec":3631.5,"end_sec":3699.5,"start_ms":3631500,"end_ms":3699500,"confidence":1,
        "submission_count":2,"updated_at":"2026-04-08T18:48:22.562Z"},"post_credits":null}"""
    private val empty = """{"imdb_id":"tt0000001","media_type":"tv","is_movie":false,"season":9,"episode":9,
        "intro":null,"recap":null,"outro":null,"post_credits":null}"""

    @Test fun parsesEverySegmentFromALiveResponse() {
        val segments = IntroDbClient.parseSegments(introAndOutro, "tt0944947")
        assertEquals(listOf(SegmentType.INTRO, SegmentType.CREDITS), segments.items.map { it.type })
        assertEquals(437000L, segments.items[0].startMs)
        assertEquals(531000L, segments.items[0].endMs)
        assertEquals(3631500L, segments.items[1].startMs)
        assertEquals(3699500L, segments.items[1].endMs)
    }

    @Test fun anEpisodeWithOnlyAnOutroYieldsOneSegment() {
        val segments = IntroDbClient.parseSegments(outroOnly, "tt0903747")
        assertEquals(listOf(SegmentType.CREDITS), segments.items.map { it.type })
    }

    @Test fun noDataIsEmptyRatherThanAFailure() {
        val segments = IntroDbClient.parseSegments(empty, "tt0000001")
        assertTrue(segments.none)
        assertTrue(segments.items.isEmpty())
    }

    @Test fun secondsAreUsedWhenMillisecondsAreAbsent() {
        val raw = """{"intro":{"start_sec":30,"end_sec":135},"recap":{"start_sec":10.5,"end_sec":20.25}}"""
        val segments = IntroDbClient.parseSegments(raw, "tt1234567")
        assertEquals(2, segments.items.size)
        // Sorted by start, so the recap precedes the intro.
        assertEquals(SegmentType.RECAP, segments.items[0].type)
        assertEquals(10500L, segments.items[0].startMs)
        assertEquals(20250L, segments.items[0].endMs)
        assertEquals(30000L, segments.items[1].startMs)
        assertEquals(135000L, segments.items[1].endMs)
    }

    @Test fun malformedAndUnknownPayloadsYieldNoSegments() {
        assertTrue(IntroDbClient.parseSegments("not json", "tt1").none)
        assertTrue(IntroDbClient.parseSegments("""{"error":"Invalid query params."}""", "tt1").none)
        // A segment that ends before it starts must never cause a backwards seek.
        assertTrue(IntroDbClient.parseSegments("""{"intro":{"start_ms":5000,"end_ms":1000}}""", "tt1").none)
        assertTrue(IntroDbClient.parseSegments("""{"intro":{"start_ms":1000}}""", "tt1").none)
        assertTrue(IntroDbClient.parseSegments("""{"intro":{"start_ms":1000,"end_ms":1000}}""", "tt1").none)
        // An unknown type is ignored rather than mislabelled.
        assertTrue(IntroDbClient.parseSegments("""{"preview":{"start_ms":1000,"end_ms":2000}}""", "tt1").none)
    }

    @Test fun atReportsTheSegmentCoveringAPosition() {
        val segments = IntroDbClient.parseSegments(introAndOutro, "tt0944947")
        assertNull(segments.at(0))
        assertEquals(SegmentType.INTRO, segments.at(437000)?.type)
        // The final millisecond still belongs to the segment; the next one does not.
        assertEquals(SegmentType.INTRO, segments.at(531000)?.type)
        assertNull(segments.at(531001))
        assertEquals(SegmentType.CREDITS, segments.at(3699500)?.type)
        assertNull(segments.at(4000000))
    }

    @Test fun markersAreFractionsOfTheMedia() {
        val segments = Segments("tt1", listOf(SkipSegment(SegmentType.INTRO, 1000, 2000)))
        val markers = segments.markers(10000)
        assertEquals(1, markers.size)
        assertEquals(SegmentType.INTRO, markers[0].first)
        assertEquals(0.1f, markers[0].second.first, 0.0001f)
        assertEquals(0.2f, markers[0].second.second, 0.0001f)
        // Without a duration there is nothing to scale against.
        assertTrue(segments.markers(0).isEmpty())
    }

    @Test fun segmentRequiresAForwardRange() {
        val segment = SkipSegment(SegmentType.INTRO, 1000, 3000)
        assertEquals(2000L, segment.durationMs)
        assertTrue(segment.contains(2000))
        assertFalse(segment.contains(3001))
        assertFalse(segment.contains(999))
        for (bad in listOf(-1L to 0L, 500L to 100L, 500L to 500L)) {
            try { SkipSegment(SegmentType.INTRO, bad.first, bad.second); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun submitBodiesUseTheDocumentedWireFormat() {
        val segment = SkipSegment(SegmentType.POST_CREDITS, 125000, 355000)
        val episode = IntroDbClient.submitBody(segment, "tt0903747", 1, 2)
        assertEquals("tt0903747", episode.getString("imdb_id"))
        // The submit wire value differs from the read key for post-credits.
        assertEquals("post-credits", episode.getString("segment_type"))
        assertEquals(1, episode.getInt("season"))
        assertEquals(2, episode.getInt("episode"))
        assertEquals(125, episode.getInt("start_sec"))
        assertEquals(355, episode.getInt("end_sec"))
        assertFalse(episode.has("is_movie"))

        val movie = IntroDbClient.submitBody(SkipSegment(SegmentType.CREDITS, 7190000, 7560000), "tt0944947")
        assertEquals("outro", movie.getString("segment_type"))
        assertTrue(movie.getBoolean("is_movie"))
        assertEquals(7190, movie.getInt("start_sec"))
        assertFalse(movie.has("season"))
        assertFalse(movie.has("episode"))
    }

    @Test fun everySegmentTypeHasDistinctWireNames() {
        val reads = SegmentType.entries.map { it.read }
        val submits = SegmentType.entries.map { it.submit }
        assertEquals(reads.size, reads.distinct().size)
        assertEquals(submits.size, submits.distinct().size)
        // The only type whose read key and submit value differ.
        assertEquals("post_credits", SegmentType.POST_CREDITS.read)
        assertEquals("post-credits", SegmentType.POST_CREDITS.submit)
    }

    @Test fun imdbIdsAreExtractedFromWatchPageMarkup() {
        assertEquals("tt0903747", SiteParser.imdb("""<a href="https://www.imdb.com/title/tt0903747/">IMDb</a>"""))
        assertEquals("tt0944947", SiteParser.imdb("""<div data-imdb="tt0944947"></div>"""))
        // Only imdb.com markup counts; an unrelated site must not be mistaken for one.
        assertEquals("", SiteParser.imdb("""<a href="https://example.com/title/tt1234567/">no</a>"""))
        assertEquals("", SiteParser.imdb("no identifiers here"))
    }
}
