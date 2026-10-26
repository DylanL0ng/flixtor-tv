import { createStore } from 'solid-js/store';
import type { HostInfo, HttpResponse, Platform } from '../../platform/platform';
import type { Release } from '../../domain/models';
const REPO = 'https://api.github.com/repos/DylanL0ng/flixtor-tv/releases/latest';
export function createUpdates(platform: Platform, host: HostInfo) {
  const [state, set] = createStore<{ phase: string; version: string; progress: number; incident?: boolean; report: string; error?: string }>({ phase: 'idle', version: '', progress: 0, report: host.reportUrl ? 'idle' : 'unconfigured' });
  let working = false, generation = 0;
  async function json(url: string, signal?: AbortSignal, depth = 0): Promise<Record<string, any>> {
    if (depth > 5 || !url.startsWith('https://')) throw new Error('Invalid release response');
    const r = await platform.call<HttpResponse>('http', { client: 'updates', url }, signal);
    if ([301, 302, 303, 307, 308].includes(r.status) && r.location) return json(new URL(r.location, url).href, signal, depth + 1);
    if (r.status !== 200) throw new Error('Update service unavailable'); return JSON.parse(r.body);
  }
  async function check(fingerprint?: string) {
    if (working) return; working = true; const request = ++generation; set({ phase: 'checking', error: undefined });
    try {
      const latest = await json(REPO); const asset = latest.assets?.find((a: { name: string }) => a.name === 'release.json');
      if (!asset || latest.prerelease || latest.draft) throw new Error('No stable release metadata');
      const release = await json(asset.browser_download_url) as Release;
      if (!Number.isInteger(release.versionCode) || release.versionCode < 1 || !Number.isInteger(release.minSdk) || release.minSdk < 29 || !release.versionName || release.versionName.length > 64 || !/^[a-f\d]{64}$/i.test(release.sha256) || !release.apkUrl?.startsWith('https://github.com/DylanL0ng/flixtor-tv/releases/download/')) throw new Error('Invalid release metadata');
      if (request !== generation) return;
      if (release.versionCode > host.versionCode) {
        if (release.minSdk > host.sdk) { set({ phase: 'incompatible', version: release.versionName }); return; }
        set({ phase: 'downloading', version: release.versionName });
        await platform.call('installer.prepare', release as unknown as Record<string, unknown>);
        if (request === generation) set({ phase: 'ready', progress: 100 });
      } else set({ phase: 'current', version: release.versionName });
      if (fingerprint) {
        try {
          const feed = await json('https://raw.githubusercontent.com/DylanL0ng/flixtor-tv/main/incidents.json');
          set('incident', feed.incidents?.some((i: { fingerprint: string; status: string; minVersionCode: number; maxVersionCode: number }) => i.fingerprint === fingerprint && i.status === 'pending' && host.versionCode >= i.minVersionCode && host.versionCode <= i.maxVersionCode) ?? false);
        } catch { set('incident', undefined); }
      }
    } catch { if (request === generation) set({ phase: 'failed', error: 'Update status unavailable. Try again.' }); }
    finally { working = false; }
  }
  async function report(operation: string) {
    if (!host.reportUrl || state.report === 'sending' || state.report === 'sent') return;
    set('report', 'sending');
    try {
      const r = await platform.call<HttpResponse>('http', { client: 'report', url: host.reportUrl, method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ versionCode: host.versionCode, commit: host.commit, operation, fingerprint: operation.toLowerCase() + '.response-v1', category: 'CHANGED' }) });
      if (r.status < 200 || r.status >= 300) throw new Error(); set('report', 'sent');
    } catch { set('report', 'failed'); }
  }
  const timer = setInterval(() => void check(), 6 * 60 * 60_000);
  const unsubscribe = platform.onEvent(event => { if (event.type === 'installer') set('progress', (event.value as { progress: number }).progress); });
  return { state, check, report, install: () => platform.call<{ permission: boolean }>('installer.install'), dispose() { generation++; clearInterval(timer); unsubscribe(); } };
}
