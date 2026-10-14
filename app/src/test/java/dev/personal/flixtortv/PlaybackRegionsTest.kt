package dev.personal.flixtortv

import org.junit.Assert.*
import org.junit.Test

class PlaybackRegionsTest {
    @Test fun animeMappingsUseExplicitMalPathsNotProviderIdsOrSpecialFallbacks() {
        assertEquals(AnimeMapping(20, 1.0), AniSkipClient.mapping("https://embed.example/mal/20/1/dub"))
        for (path in listOf("/anime/20/1", "/mal/0/1/sub", "/mal/20/1.5/sub", "/mal/20/0/sub"))
            assertNull(AniSkipClient.mapping("https://embed.example$path"))
    }

    @Test fun animeRangesPreserveFractionalTimesAndRejectUnusableData() {
        val raw = """{"found":true,"results":[
            {"skipType":"op","skipId":"a","interval":{"startTime":0,"endTime":90.125}},
            {"skipType":"recap","skipId":"b","interval":{"startTime":100,"endTime":110}},
            {"skipType":"mixed-ed","skipId":"c","interval":{"startTime":115,"endTime":119}},
            {"skipType":"ed","interval":{"startTime":120,"endTime":130}},
            {"skipType":"op","interval":{"startTime":-2,"endTime":1}},
            {"skipType":"unknown","interval":{"startTime":1,"endTime":2}},
            {"skipType":"op","interval":{"startTime":1,"endTime":1}}]}"""
        val regions = AniSkipClient.parse(raw, 120000)
        assertEquals(listOf(SegmentType.INTRO, SegmentType.RECAP, SegmentType.MIXED_CREDITS), regions.map { it.type })
        assertEquals(90125L, regions.first().endMs)
        assertEquals(SegmentSource.ANISKIP, regions.first().source)
        assertFalse(regions.first().contains(90125))
        assertTrue(AniSkipClient.parse("broken", 120000).isEmpty())
        assertTrue(AniSkipClient.parse(raw, 0).isEmpty())
    }

    @Test fun localOverridesNeverTurnIntoCommunityDataAndKeepMultipleRanges() {
        val remote = listOf(SkipSegment(SegmentType.INTRO, 0, 10000), SkipSegment(SegmentType.CREDITS, 90000, 100000))
        val local = listOf(SkipSegment(SegmentType.INTRO, 1000, 5000, "first", source = SegmentSource.LOCAL),
            SkipSegment(SegmentType.INTRO, 6000, 9000, "second", source = SegmentSource.LOCAL))
        assertEquals(listOf("first", "second", remote.last().id), mergeSegments(remote, local).map { it.id })
        assertEquals(remote, mergeSegments(remote, emptyList()))
    }

    @Test fun actionsResolveRegionThenShowThenProfileThenManual() {
        val region = SkipSegment(SegmentType.CUSTOM, 0, 1000, typeId = "bts")
        assertEquals(SkipAction.ASK, effectiveSkipAction(region, null, null))
        assertEquals(SkipAction.AUTO, effectiveSkipAction(region, null, SkipAction.AUTO))
        assertEquals(SkipAction.OFF, effectiveSkipAction(region, SkipAction.OFF, SkipAction.AUTO))
        assertEquals(SkipAction.ASK, effectiveSkipAction(region.copy(action = SkipAction.ASK), SkipAction.OFF, SkipAction.AUTO))
    }

    @Test fun holdingAcceleratesAndNewPressResetsToFineStep() {
        val duration = 7200000L
        val steps = listOf(0L, 500L, 1500L, 3000L, 5000L).map { timelineStep(10000, it, 1, duration) }
        assertEquals(listOf(10000L, 30000L, 60000L, 180000L, 360000L), steps)
        assertEquals(10000L, timelineStep(10000, 99999, 0, duration))
        assertEquals(1000L, timelineStep(1000, 0, 0, duration))
        assertEquals(3000L, timelineStep(1000, 500, 1, duration))
        assertTrue(timelineStep(10000, 100000, 1, Long.MAX_VALUE) <= 600000)
    }

    @Test fun countdownSurvivesCreditsToEndAndExcludesSuspension() {
        val timer = NextEpisodeCountdown()
        assertFalse(timer.tick(0, true, true))
        assertFalse(timer.tick(2000, true, true))
        assertEquals(3000L, timer.remainingMs)
        assertFalse(timer.tick(2000, true, false))
        assertFalse(timer.tick(90000, true, true))
        assertEquals(3000L, timer.remainingMs)
        // Eligibility remains true across credits → media end.
        assertTrue(timer.tick(93000, true, true))
        assertFalse(timer.tick(94000, true, true))
    }

    @Test fun cancellingSuppressesEndAutoplayAndLeavingCreditsResetsUncancelledTimer() {
        val timer = NextEpisodeCountdown()
        timer.tick(0, true, true); timer.tick(2000, true, true)
        timer.tick(2100, false, false)
        assertEquals(5000L, timer.remainingMs)
        timer.cancel()
        assertFalse(timer.tick(90000, true, true))
        assertFalse(timer.tick(100000, true, true))
        assertTrue(timer.cancelled)
    }

    @Test fun episodeDrawerMatchesStableIdsAndFallsBackForMissingEpisode() {
        val episodes = (1..20).map { Episode(1, it, "First") } + (1..20).map { Episode(2, it, "Second") }
        assertEquals(2 to 16, episodeDrawerTarget(episodes, episodes[36]))
        assertEquals(1 to 0, episodeDrawerTarget(episodes, Episode(9, 9, "Missing")))
        assertNull(episodeDrawerTarget(emptyList(), null))
        val specials = listOf(Episode(1, 1, "Main", "123", "1"), Episode(1, 1, "Special", "124", "1.5"))
        assertEquals(1 to 1, episodeDrawerTarget(specials, specials.last()))
    }

    @Test fun bufferTargetsRespectHeapAndShortRemainingVideo() {
        assertEquals(48 * 1024 * 1024, bufferByteTarget(192L * 1024 * 1024))
        assertEquals(96 * 1024 * 1024, bufferByteTarget(1024L * 1024 * 1024))
        assertFalse(bufferReady(50000, 120000, 10, 100))
        assertTrue(bufferReady(90000, 120000, 10, 100))
        assertTrue(bufferReady(15000, 15000, 10, 100))
        assertTrue(bufferReady(5000, 120000, 100, 100))
    }
}
