import { describe, expect, it } from 'vitest';
import { editValue, pinDigit } from '../src/ui/input-state';
import { catalogueKey, distinctTitles, nextCataloguePage, resumeEpisode, resumeViewing } from '../src/features/catalog/catalog-state';
import type { Detail, Title, Viewing } from '../src/domain/models';

const movie: Title = { id: '1', path: '/watch/movie/1/one', name: 'One' };
describe('TV input', () => {
  it('edits selections and keeps the caret valid when deleting or clearing', () => {
    expect(editValue('Northbound', 'South', 0, 5)).toEqual({ value: 'Southbound', caret: 5 });
    expect(editValue('AB', 'Delete', 0)).toEqual({ value: 'AB', caret: 0 });
    expect(editValue('ABCD', 'Delete', 1, 3)).toEqual({ value: 'AD', caret: 1 });
    expect(editValue('A', 'Space')).toEqual({ value: 'A ', caret: 2 });
    expect(editValue('AB', 'Clear')).toEqual({ value: '', caret: 0 });
  });
  it('accepts exactly four digits and ignores held-key repetition', () => {
    let value = pinDigit('', '1'); value = pinDigit(value, '1', true);
    for (const key of ['2', '3', '4', '5', 'x']) value = pinDigit(value, key);
    expect(value).toBe('1234'); expect(pinDigit(value, 'Backspace')).toBe('123');
  });
});
describe('catalogue continuity', () => {
  it('scopes filters to destination, query, sort, profile and account', () => {
    const base = catalogueKey('movies', '', 'latest', 'alice', 0);
    expect(catalogueKey('movies', '', 'name', 'alice', 0)).not.toEqual(base);
    expect(catalogueKey('movies', '', 'latest', 'bob', 0)).not.toEqual(base);
    expect(catalogueKey('movies', '', 'latest', 'alice', 1)).not.toEqual(base);
  });
  it('deduplicates overlapping pages and stops a repeating upstream page', () => {
    const two = { ...movie, id: '2' };
    expect(distinctTitles([[movie, two], [two]])).toEqual([movie, two]);
    expect(nextCataloguePage([two], [[movie], [two]], 2)).toBe(3);
    expect(nextCataloguePage([movie], [[movie], [movie]], 2)).toBeUndefined();
    expect(nextCataloguePage([], [[]], 1)).toBeUndefined();
  });
  it('resumes the current episode and falls back when that episode disappears', () => {
    const detail: Detail = { title: movie, episodes: [{ season: 1, number: 1, name: 'First' }, { season: 2, number: 1, name: 'Later' }] };
    const viewing: Viewing = { title: movie, episode: detail.episodes[1], position: 120_000, duration: 900_000 };
    expect(resumeViewing([viewing], movie)).toBe(viewing);
    expect(resumeEpisode(detail, viewing)).toEqual(detail.episodes[1]);
    expect(resumeEpisode({ ...detail, episodes: detail.episodes.slice(0, 1) }, viewing)).toEqual(detail.episodes[0]);
    expect(resumeViewing([{ ...viewing, position: 900_000 }], movie)).toBeUndefined();
  });
});
