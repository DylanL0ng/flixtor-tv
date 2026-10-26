export interface HttpRequest {
  client: 'flixtor' | 'anime' | 'introdb' | 'aniskip' | 'updates' | 'report' | 'public';
  url: string; method?: 'GET' | 'POST'; headers?: Record<string, string>; body?: string;
}
export interface HttpResponse { status: number; body: string; location?: string }
export interface HostInfo { platform: string; version: string; versionCode: number; sdk: number; commit: string; reportUrl: string; contribution: boolean }
export interface Track { id: string; type: number; label: string; selected: boolean; supported: boolean }
export interface PlayerEvent {
  id: string; position: number; duration: number; buffered: number; playing: boolean; playRequested: boolean;
  buffering: boolean; ended: boolean; time: number; tracks: Track[]; error?: string; bytes: number; byteLimit: number;
}
export interface PlatformEvent { type: 'player' | 'lifecycle' | 'key' | 'installer'; value: PlayerEvent | { foreground: boolean } | { code: number } | { progress: number } }
export interface Platform {
  call<T = unknown>(method: string, args?: Record<string, unknown>, signal?: AbortSignal): Promise<T>;
  onEvent(listener: (event: PlatformEvent) => void): () => void;
  dispose(): void;
}
declare global {
  interface Window {
    nativeHost?: { postMessage(message: string): void; onmessage?: (event: MessageEvent<string>) => void };
    __platformEvent?: (event: PlatformEvent) => void;
  }
}

export function androidPlatform(): Platform {
  let sequence = 0;
  const pending = new Map<string, { resolve(value: unknown): void; reject(error: unknown): void; cleanup(): void }>();
  const listeners = new Set<(event: PlatformEvent) => void>();
  const host = window.nativeHost!;
  host.onmessage = event => {
    let message: { id: string; value: unknown; error?: string; v: number };
    try { message = JSON.parse(event.data); } catch { return; }
    if (message.v !== 1) return;
    const entry = pending.get(message.id);
    if (!entry) return;
    pending.delete(message.id); entry.cleanup();
    if (message.error) entry.reject(new Error(message.error)); else entry.resolve(message.value);
  };
  window.__platformEvent = event => { for (const listener of listeners) listener(event); };
  return {
    call<T>(method: string, args = {}, signal?: AbortSignal): Promise<T> {
      if (signal?.aborted) return Promise.reject(new DOMException('Cancelled', 'AbortError'));
      if (pending.size >= 64) return Promise.reject(new Error('Too many pending platform requests'));
      const id = String(++sequence);
      return new Promise<T>((resolve, reject) => {
        const abort = () => {
          pending.delete(id); cleanup(); reject(new DOMException('Cancelled', 'AbortError'));
          host.postMessage(JSON.stringify({ v: 1, id: `cancel:${id}`, method: 'cancel', args: { id } }));
        };
        const timer = setTimeout(() => { pending.delete(id); cleanup(); reject(new Error('Platform request timed out')); }, method === 'input' ? 600_000 : 60_000);
        const cleanup = () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); };
        pending.set(id, { resolve: value => resolve(value as T), reject, cleanup });
        signal?.addEventListener('abort', abort, { once: true });
        host.postMessage(JSON.stringify({ v: 1, id, method, args }));
      });
    },
    onEvent(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    dispose() {
      for (const entry of pending.values()) { entry.cleanup(); entry.reject(new Error('Host disposed')); }
      pending.clear(); listeners.clear(); delete window.__platformEvent;
    },
  };
}
