import { describe, expect, it } from 'vitest';
import { Countdown, endingEligible, nearEpisodeEnd } from '../src/features/playback/timing';
const marker = { id: 'ending', type: 'outro', start: 1_350_000, end: 1_440_000 };
const event = (position: number, duration = 1_440_000, ended = false) => ({ position, duration, ended });
describe('end-of-episode guard', () => {
  it('requires both the time and percentage boundaries', () => {
    expect(nearEpisodeEnd(1_350_000, 1_440_000)).toBe(true);
    expect(nearEpisodeEnd(1_349_999, 1_440_000)).toBe(false);
    expect(nearEpisodeEnd(809_999, 900_000)).toBe(false);
    expect(nearEpisodeEnd(810_000, 900_000)).toBe(true);
    expect(nearEpisodeEnd(90_000, 100_000)).toBe(true);
    expect(nearEpisodeEnd(89_999, 100_000)).toBe(false);
    for (const duration of [0, -1, Infinity, NaN]) expect(nearEpisodeEnd(0, duration)).toBe(false);
    expect(nearEpisodeEnd(101, 100)).toBe(false);
    expect(nearEpisodeEnd(3_500_000, 3_600_000)).toBe(false);
  });
  it('rejects early or malformed credits and missing markers', () => {
    expect(endingEligible(event(300_000), [{ ...marker, start: 290_000, end: 380_000 }])).toBe(false);
    expect(endingEligible(event(1_350_000), [])).toBe(false);
    expect(endingEligible(event(1_350_000), [{ ...marker, start: -1 }])).toBe(false);
    expect(endingEligible(event(1_350_000), [{ ...marker, end: Infinity }])).toBe(false);
    expect(endingEligible(event(1_350_000), [marker])).toBe(true);
  });
  it('preserves future and currently playing post-credits scenes', () => {
    const post = { id: 'scene', type: 'post_credits', start: 1_400_000, end: 1_430_000 };
    expect(endingEligible(event(1_350_000), [marker, post])).toBe(false);
    expect(endingEligible(event(1_410_000), [marker, post])).toBe(false);
    expect(endingEligible(event(1_430_000), [marker, post])).toBe(true);
  });
  it('permits actual completion without duration or markers; seeking back revokes eligibility', () => {
    expect(endingEligible(event(0, 0, true), [])).toBe(true);
    expect(endingEligible(event(300_000), [marker], new Set(['ending']))).toBe(false);
    expect(endingEligible(event(1_440_000), [marker], new Set(['ending']))).toBe(true);
  });
  it('resets elapsed countdown after seek/duration changes without overriding cancellation', () => {
    const c = new Countdown(); c.tick(0, true, true); c.tick(4000, true, true); c.reset();
    expect(c.remaining).toBe(5000); expect(c.tick(5000, true, true)).toBe(false);
    c.cancel(); c.reset(); expect(c.tick(20_000, true, true)).toBe(false);
  });
});
