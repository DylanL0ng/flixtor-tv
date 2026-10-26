import { browserPlayer } from './browser-player';
import { ServiceError } from '../domain/models';
import { inputLabels } from '../ui/copy';
import type { Platform, PlatformEvent } from './platform';

/** Real browser capabilities through the loopback Vite bridge. Fixtures are opt-in and separate. */
export function browserPlatform(): Platform {
  const listeners = new Set<(event: PlatformEvent) => void>(), pending = new Set<AbortController>();
  const prefix = 'flixtor-live:';
  let input: HTMLDialogElement | undefined;
  function prompt(args: Record<string, unknown>) {
    if (input) return Promise.reject(new Error('Input already open'));
    return new Promise<string | null>(resolve => {
      const previous = document.activeElement as HTMLElement | null;
      const dialog = document.createElement('dialog'), form = document.createElement('form'), label = document.createElement('label'), field = document.createElement('input');
      input = dialog; form.method = 'dialog';
      dialog.style.cssText = 'box-sizing:border-box;width:min(480px,90vw);max-height:calc(100dvh - 32px);overflow:auto;padding:24px;border:1px solid #34383b;border-radius:6px;background:#1d2023;color:#f1efe8;font:18px sans-serif';
      label.textContent = String(args.label); label.style.cssText = 'display:flex;flex-direction:column;gap:12px;margin-bottom:20px';
      field.type = args.secret ? 'password' : 'text'; field.value = String(args.value || ''); field.autocomplete = args.secret ? 'current-password' : 'off';
      field.style.cssText = 'box-sizing:border-box;min-width:0;padding:10px;font:inherit'; label.append(field); form.append(label);
      for (const [value, text] of [['ok', inputLabels.done], ['cancel', inputLabels.cancel]]) {
        const button = document.createElement('button'); button.type = 'submit'; button.value = value; button.textContent = text;
        button.style.cssText = 'padding:10px 20px;margin-right:12px;font:inherit'; form.append(button);
      }
      dialog.append(form); dialog.addEventListener('keydown', event => event.stopPropagation());
      for (const type of ['click', 'mousedown', 'wheel']) dialog.addEventListener(type, event => event.stopPropagation());
      dialog.addEventListener('close', () => {
        const value = dialog.returnValue === 'ok' ? field.value : null;
        field.value = ''; dialog.remove(); input = undefined; previous?.focus(); resolve(value);
      }, { once: true });
      document.body.append(dialog); dialog.showModal(); field.focus();
    });
  }
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
      else if (method === 'input') value = await prompt(args);
      else if (method === 'player.load') await player.load(args, signal);
      else if (method === 'player.command') player.command(args);
      else if (method === 'exit') window.close();
      else throw new Error(`Unsupported browser capability: ${method}`);
      return value as T;
    },
    onEvent(fn) { listeners.add(fn); return () => listeners.delete(fn); },
    dispose() { input?.close('cancel'); for (const call of pending) call.abort(); pending.clear(); player.dispose(); listeners.clear(); },
  };
}
