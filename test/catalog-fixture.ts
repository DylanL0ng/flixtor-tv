import { getOuterHTML } from 'domutils';
import { all, document, one, text } from '../src/services/html';

/** Sort the complete finite fixture catalogue, not a partial production page. */
export function catalogFixture(html: string, sort: string | null, page: string | null) {
  if (Number(page || 1) > 1) return '<div class="contentList">No results</div>';
  const cards = all(document(html), '.cflip[data-href]');
  if (sort === 'name') cards.sort((a, b) => text(one(a, '.front .card-footer')).localeCompare(text(one(b, '.front .card-footer')), 'en'));
  if (sort === 'best') cards.sort((a, b) => Number(text(one(b, '.front .card-badge.bottom .badge'))) - Number(text(one(a, '.front .card-badge.bottom .badge'))));
  return '<div class="contentList">' + cards.map(card => getOuterHTML(card)).join('') + '</div>';
}
