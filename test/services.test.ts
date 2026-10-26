import { describe, expect, it } from 'vitest';
import fixtures from './fixtures/http.json';
import { decodePlayback, parseAccount, parseCards, parseDetail, parseHome, parseStream, responseFailure, safePath } from '../src/services/flixtor';
import { parseAnimeEpisodes, parseAnimeServers, animeMapping } from '../src/services/anime';
import { parseAniSkip, parseIntroDb } from '../src/services/segments';
import { episodeKey, orderedEpisodes, titleKey } from '../src/domain/models';
describe('service contracts migrated from Kotlin', () => {
  it('parses catalogue and preserves domain identity', () => { expect(parseCards(fixtures.catalog)).toHaveLength(12); expect(titleKey(parseCards(fixtures.catalog)[0])).toBe('t:1'); expect(parseHome(fixtures['/home'])[0].titles).toHaveLength(12); });
  it('deduplicates cards and rejects paths escaping origin', () => { expect(parseCards(fixtures.catalog + fixtures.catalog)).toHaveLength(12); for (const path of ['//evil.example/x', 'https://evil.example/', 'relative', '/\\evil.example']) expect(() => safePath(path)).toThrow(); });
  it('accepts explicit empty results only', () => { expect(parseCards('<div class=contentList>No results</div>')).toEqual([]); expect(() => parseCards('<html>unrecognized</html>')).toThrow('CHANGED'); });
  it.each([
    ['{"error":"Please sign in"}', 200, 'SIGNED_OUT'], ['{"error":"VIP required"}', 200, 'VIP'],
    ['<p>Session expired</p>', 200, 'EXPIRED'], ['{"message":"Account is blocked"}', 200, 'BLOCKED'],
    ['<p>Access denied</p>', 200, 'ACCESS'], ['', 429, 'UNAVAILABLE'], ['', 503, 'UNAVAILABLE'], ['', 401, 'SIGNED_OUT'], ['', 443, 'VIP'],
  ])('classifies restriction %s', (raw, status, expected) => expect(responseFailure(raw, status)).toBe(expected));
  it('keeps free capacity separate from parser failure', () => expect(responseFailure('<div id=content>All our servers are full. We cannot accept additional users.</div>')).toBe('CAPACITY'));
  it('does not infer entitlement from marketing', () => { expect(parseAccount('<p>VIP Active</p>')).toBe('GUEST'); expect(parseAccount('<a class=logout>Logout</a><p>VIP active</p>')).toBe('SIGNED_IN'); expect(parseAccount('<a class=logout>Logout</a><span class=acct-status>VIP Active</span>')).toBe('VIP'); expect(parseAccount('<a class=logout>Logout</a><span data-vip-status=expired></span>')).toBe('EXPIRED'); });
  it('orders episodes canonically', () => { const d = parseDetail(fixtures.detail, parseCards(fixtures.catalog)[0]); expect(orderedEpisodes(d.episodes.reverse()).map(episodeKey)).toEqual(['1:1', '1:2', '1:3', '1:4']); });
  it('roundtrips encoded UTF-8 streams and signed URLs', () => {
    const json = JSON.stringify({ file: 'https://media.example/a.m3u8?token=abc%2B123', tracks: [{ file: '/ja.vtt', label: '日本語' }] });
    const shifted = [...json].map(c => c.charCodeAt(0) >= 33 && c.charCodeAt(0) <= 126 ? String.fromCharCode(33 + (c.charCodeAt(0) - 33 + 47) % 94) : c).join('');
    const encoded = Buffer.from(shifted).toString('base64').replace(/[a-zA-Z]/g, c => String.fromCharCode(c.charCodeAt(0) + (c.toLowerCase() <= 'm' ? 13 : -13)));
    expect(decodePlayback(encoded)).toBe(json); expect(parseStream(encoded).captions[0].label).toBe('日本語');
  });
  it('ignores invalid optional captions without hiding denials', () => {
    expect(parseStream('{"file":"https://media.example/a.m3u8","tracks":[null,{"file":"http://bad.example/a.vtt"},{"file":"/ok.vtt"}]}').captions).toHaveLength(1);
    expect(() => parseStream('{"file":"","error":"VIP required"}')).toThrow('VIP'); expect(() => parseStream('{"file":"http://bad.example/a.m3u8"}')).toThrow('CHANGED');
  });
  it('preserves anime specials and explicit mappings', () => {
    const episodes = parseAnimeEpisodes(JSON.stringify({ html: '<a class=ep-item data-id=11 data-number=SP1 title=Special></a><a class=ep-item data-id=12 data-number=2 title=Second></a>' }));
    expect(episodes[0].displayNumber).toBe('SP1'); expect(episodeKey(episodes[0])).toBe('anime:11'); expect(animeMapping('https://embed.example/mal/123/4')).toEqual({ malId: 123, episode: 4 }); expect(animeMapping('https://embed.example/123/4')).toBeUndefined();
  });
  it('never silently substitutes anime audio versions', () => { const servers = parseAnimeServers(JSON.stringify({ html: `<div data-server-name=ZokoAnime data-type=sub data-hash="${btoa('https://embed.example/1')}"></div>` })); expect(servers.SUB).toBe('https://embed.example/1'); expect(servers.DUB).toBeUndefined(); });
  it('validates optional segment precision and bounds', () => {
    expect(parseIntroDb('{"intro":{"start_ms":0,"end_sec":10.5},"recap":{"start_ms":2.5,"end_ms":20}}')).toHaveLength(1);
    expect(parseAniSkip('{"found":true,"results":[{"skipType":"op","skipId":"1","interval":{"startTime":0,"endTime":12}}]}', 10_000)).toEqual([]);
  });
});
