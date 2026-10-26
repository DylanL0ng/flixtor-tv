import type { Platform } from '../platform/platform';
import { playbackKey, titleKey, validSegment, type Profile, type Segment, type Title, type Viewing } from './models';
export interface Library { favorites: Title[]; viewing: Viewing[] }
export interface AppData { schema: 1; profiles: Profile[]; libraries: Record<string, Library>; skip: Record<string, string>; fit: { horizontal: number; vertical: number } }
const defaultProfile = (): Profile => ({ id: 'default', name: 'My profile', avatar: 0, quality: 0, autoplay: true, largerBuffer: false, locked: false });
function parse<T>(raw: unknown, fallback: T): T { try { return typeof raw === 'string' ? JSON.parse(raw) : fallback; } catch { return fallback; } }
export function importLegacy(legacy: Record<string, unknown>): AppData {
  const old = Array.isArray(legacy.profiles) && legacy.profiles.length ? legacy.profiles as Profile[] : [defaultProfile()];
  const profiles = old.slice(0, 7).map(p => ({ ...defaultProfile(), id: p.id, name: p.name, avatar: p.avatar ?? 0, quality: p.quality ?? 0, autoplay: p.autoplay ?? true, largerBuffer: p.largerBuffer ?? false, locked: p.locked ?? false }));
  const libraries: Record<string, Library> = {};
  for (const p of profiles) {
    const value = legacy[`library:${p.id}`] as Library | undefined;
    const original = legacy.library as Record<string, string> | undefined;
    libraries[p.id] = { favorites: (value?.favorites ?? (p.id === 'default' ? parse<Title[]>(original?.favorites, []) : [])).slice(0, 200),
      viewing: (value?.viewing ?? (p.id === 'default' ? parse<Viewing[]>(original?.viewing, []) : [])).slice(0, 100) };
  }
  const fit = legacy.screen_fit as { horizontal?: number; vertical?: number } | undefined;
  return { schema: 1, profiles, libraries, skip: (legacy.skip || {}) as Record<string, string>, fit: { horizontal: Math.min(10, Math.max(0, fit?.horizontal ?? 5)), vertical: Math.min(10, Math.max(0, fit?.vertical ?? 5)) } };
}
export class Repository {
  private data!: AppData; private queue: Promise<void> = Promise.resolve();
  private listeners = new Set<(data: AppData) => void>();
  constructor(private platform: Platform) {}
  get current() { return this.data; }
  async initialize() {
    const raw = await this.platform.call<string | null>('storage.get', { key: 'app' });
    if (raw) {
      const value = JSON.parse(raw) as AppData;
      if (value.schema !== 1 || !Array.isArray(value.profiles) || !value.profiles.length || !value.libraries || !value.skip) throw new Error('Stored data needs recovery. Original data retained.');
      this.data = value;
    } else {
      const legacy = await this.platform.call<Record<string, unknown>>('legacy');
      const value = importLegacy(legacy);
      await this.platform.call('storage.set', { key: 'app', value: JSON.stringify(value) });
      // Atomic single-record commit is also migration-complete marker.
      this.data = value;
    }
    const checkpoint = await this.platform.call<string | null>('storage.get', { key: 'checkpoint' });
    if (checkpoint) {
      const value = JSON.parse(checkpoint) as Viewing & { profileId: string };
      if (value.title && this.data.profiles.some(p => p.id === value.profileId)) await this.progress(value.profileId, value);
      await this.platform.call('storage.remove', { key: 'checkpoint' });
    }
    return this.data;
  }
  subscribe(fn: (data: AppData) => void) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  change(transform: (data: AppData) => void, credentials?: { profile: string; pin: string }): Promise<void> {
    const job = this.queue.then(async () => {
      const next = structuredClone(this.data); transform(next);
      await this.platform.call(credentials ? 'credentials.commit' : 'storage.set', { key: 'app', value: JSON.stringify(next), ...credentials });
      this.data = next; this.listeners.forEach(fn => fn(next));
    });
    this.queue = job.catch(() => {}); return job;
  }
  saveProfile(id: string | undefined, name: string, avatar: number) {
    name = name.trim(); if (!name || name.length > 30 || !Number.isInteger(avatar) || avatar < 0 || avatar > 6) return Promise.reject(new Error('Name is required. Maximum 30 characters.'));
    return this.change(d => {
      if (id) { const p = d.profiles.find(p => p.id === id); if (!p) throw new Error('Profile unavailable'); Object.assign(p, { name, avatar }); }
      else { if (d.profiles.length >= 7) throw new Error('Maximum seven profiles'); const p = { ...defaultProfile(), id: crypto.randomUUID(), name, avatar }; d.profiles.push(p); d.libraries[p.id] = { favorites: [], viewing: [] }; }
    });
  }
  updateProfile(id: string, value: Partial<Pick<Profile, 'quality' | 'autoplay' | 'largerBuffer' | 'locked'>>) {
    return this.change(d => { const p = d.profiles.find(p => p.id === id); if (!p) throw new Error('Profile unavailable'); Object.assign(p, value); });
  }
  async setPin(id: string, pin: string) { if (pin && !/^\d{4}$/.test(pin)) throw new Error('PIN must contain four digits.'); await this.change(d => { const p = d.profiles.find(p => p.id === id); if (!p) throw new Error('Profile unavailable'); p.locked = !!pin; }, { profile: id, pin }); }
  deleteProfile(id: string) { return this.change(d => { if (d.profiles.length === 1) throw new Error('Keep at least one profile.'); d.profiles = d.profiles.filter(p => p.id !== id); delete d.libraries[id]; for (const key of Object.keys(d.skip)) if (key.startsWith(`action:${id}:`)) delete d.skip[key]; }); }
  toggle(id: string, title: Title) { return this.change(d => { const library = d.libraries[id]; if (!library) throw new Error('Profile unavailable'); const key = titleKey(title); library.favorites = library.favorites.some(t => titleKey(t) === key) ? library.favorites.filter(t => titleKey(t) !== key) : [title, ...library.favorites].slice(0, 200); }); }
  progress(id: string, viewing: Viewing) { if (viewing.position < 1000) return Promise.resolve(); return this.change(d => { const library = d.libraries[id]; if (!library) return; const key = playbackKey(viewing.title, viewing.episode, viewing.version); library.viewing = [viewing, ...library.viewing.filter(v => playbackKey(v.title, v.episode, v.version) !== key)].slice(0, 100); }); }
  markKey(title: Title, episode?: Viewing['episode'], version?: Viewing['version']) { return `mark:${titleKey(title)}:${episode ? (episode.providerId ? `anime:${episode.providerId}` : `${episode.season}:${episode.number}`) : ''}${version ? ':' + version.toLowerCase() : ''}`; }
  regions(key: string): Segment[] { return parse<Segment[]>(this.data.skip[key], []).map(s => ({ ...s, typeId: s.typeId || s.type, id: s.id || `local:${s.type}:${s.start}:${s.end}`, source: 'LOCAL' as const })).filter(s => validSegment(s)); }
  saveRegion(key: string, segment: Segment, duration: number) { if (!validSegment(segment, duration)) return Promise.reject(new Error('End must follow start and remain inside video.')); return this.change(d => { const current = parse<Segment[]>(d.skip[key], []); d.skip[key] = JSON.stringify([...current.filter(s => s.id !== segment.id), segment].sort((a, b) => a.start - b.start)); }); }
  deleteRegion(key: string, id: string) { return this.change(d => { d.skip[key] = JSON.stringify(parse<Segment[]>(d.skip[key], []).filter(s => s.id !== id)); }); }
  customTypes(): { id: string; label: string }[] { return parse(this.data.skip.customTypes, []); }
  customType(label: string) { label = label.trim(); if (!label || label.length > 30) return Promise.reject(new Error('Name is required.')); return this.change(d => { const types = parse<{ id: string; label: string }[]>(d.skip.customTypes, []); if (types.length >= 50) throw new Error('Maximum 50 custom types'); if (!types.some(t => t.label.toLowerCase() === label.toLowerCase())) types.push({ id: crypto.randomUUID(), label }); d.skip.customTypes = JSON.stringify(types); }); }
  setSkip(key: string, value?: string) { return this.change(d => { if (value === undefined) delete d.skip[key]; else d.skip[key] = value; }); }
  clearLibrary(id: string) { return this.change(d => { d.libraries[id] = { favorites: [], viewing: [] }; }); }
}
