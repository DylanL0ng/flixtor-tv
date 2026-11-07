/** Small deterministic metadata catalogue, never included in production builds. */
const north = { id: 2, title: 'Northbound', release_date: '2026-01-01', imdb_id: 'tt1234567', overview: 'A fixture film.', vote_average: 7.8, vote_count: 900, genres: [{ id: 12, name: 'Adventure' }] };
const quiet = { id: 3, title: 'Quiet Harbour', release_date: '2025-01-01', imdb_id: 'tt1234568', overview: 'A fixture drama.', vote_average: 8.1, vote_count: 1000 };
export function tmdbFixture(path: string, search: URLSearchParams) {
  if (path.startsWith('/3/find/')) return { movie_results: [path.includes('tt1234568') ? quiet : north] };
  if (path === '/3/search/movie') return { results: [search.get('query') === 'Quiet Harbour' ? quiet : north] };
  if (path === '/3/search/person') return { results: [{ id: 101, name: 'Alex Example' }], total_pages: 1 };
  if (path === '/3/search/company') return { results: [{ id: 201, name: 'Example Pictures' }], total_pages: 1 };
  if (path.includes('/person/')) return { cast: [north, quiet], crew: [{ ...north, job: 'Director' }, { ...quiet, job: 'Writer' }] };
  if (/\/movie\/\d+$/.test(path)) return { ...(path.includes('/3/movie/3') ? quiet : north), credits: {
    cast: [{ id: 101, name: 'Alex Example', character: 'The traveller' }], crew: [{ id: 102, name: 'Sam Director', job: 'Director' }, { id: 103, name: 'Writer Only', job: 'Writer' }],
  }, production_companies: [{ id: 201, name: 'Example Pictures' }], recommendations: { results: [quiet] } };
  return { results: path.includes('recommendations') ? [quiet] : search.get('sort_by') === 'vote_average.desc' ? [quiet, north] : [north, quiet], total_pages: 1 };
}
