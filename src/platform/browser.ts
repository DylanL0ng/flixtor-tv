import { browserPlayer } from './browser-player';
import { ServiceError } from '../domain/models';
import { deviceInput } from '../ui/device-input';
import type { Platform, PlatformEvent } from './platform';

/** Real browser capabilities through the loopback Vite bridge. Fixtures are opt-in and separate. */
export function browserPlatform(): Platform {
  const listeners = new Set<(event: PlatformEvent) => void>(), pending = new Set<AbortController>();
  const prefix = 'flixtor-live:';
  const input = deviceInput();
  async function remote<T>(method: string, args: Record<string, unknown> = {}, signal?: AbortSignal): Promise<T> {
    signal?.throwIfAborted();
    const abort = new AbortController(), cancel = () => abort.abort(); pending.add(abort);
    signal?.addEventListener('abort', cancel, { once: true });
    try {
      const response = await fetch('/__platform/call', { method: 'POST', credentials: 'same-origin', signal: abort.signal,
        headers: { 'Content-Type': 'application/json', 'X-Flixtor-Request': '1' }, body: JSON.stringify({ method, args }) });
      if (!response.ok) throw new ServiceError('NETWORK');
      const result = await response.json(); if (result.error) throw new ServiceError('NETWORK'); return result.value as T;
    } finally { pending.delete(abort); signal?.removeEventListener('abort', cancel); }
  }
  const player = browserPlayer(event => { for (const fn of listeners) fn(event); }, (args, signal) => remote('media', args, signal));
  async function digest(profile: string, pin: string) {
    return [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${profile}:${pin}`)))].map(b => b.toString(16).padStart(2, '0')).join('');
  }
  return {
    async call<T>(method: string, args: Record<string, unknown> = {}, signal?: AbortSignal): Promise<T> {
      signal?.throwIfAborted(); let value: unknown = null;
      if (method === 'ready') { await remote('session.open', {}, signal); value = { platform: 'browser', version: 'development', versionCode: 0, sdk: 0, commit: 'local', reportUrl: '', contribution: false }; }
      else if (method === 'http' || method === 'art' || method === 'session.clear') value = await remote(method, args, signal);
      else if (method === 'storage.get') value = localStorage.getItem(prefix + args.key);
      else if (method === 'storage.set') localStorage.setItem(prefix + args.key, String(args.value));
      else if (method === 'storage.remove') localStorage.removeItem(prefix + args.key);
      else if (method === 'legacy') value = {};
      else if (method === 'credentials.verify') { const stored = localStorage.getItem(`${prefix}pin:${args.profile}`); value = !stored || stored === await digest(String(args.profile), String(args.pin)); }
      else if (method === 'credentials.commit') { localStorage.setItem(`${prefix}pin:${args.profile}`, args.pin ? await digest(String(args.profile), String(args.pin)) : ''); localStorage.setItem(prefix + 'app', String(args.value)); }
      else if (method === 'input') value = await input.open(args);
      else if (method === 'player.load') await player.load(args, signal);
      else if (method === 'player.command') player.command(args);
      else if (method === 'exit') window.close();
      else throw new Error(`Unsupported browser capability: ${method}`);
      return value as T;
    },
    onEvent(fn) { listeners.add(fn); return () => listeners.delete(fn); },
    dispose() { input.dispose(); for (const call of pending) call.abort(); pending.clear(); player.dispose(); listeners.clear(); },
  };
}
