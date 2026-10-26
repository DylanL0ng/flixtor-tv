/** Playing wall time, never seek position. Clock injected for deterministic tests. */
export class WatchingSession {
  token = 0; finished = false; pending = false;
  private watched = 0; private episodes = 0; private playing = false; private foreground = false; private remaining: number; private last: number;
  constructor(private now: () => number, private minimumWatch = 90 * 60_000, private minimumEpisodes = 3, private timeout = 60_000) { this.remaining = timeout; this.last = now(); }
  private settle() { const time = this.now(), elapsed = Math.max(0, time - this.last); if (this.foreground) { if (this.pending) this.remaining = Math.max(0, this.remaining - elapsed); else if (this.playing) this.watched += elapsed; } this.last = time; }
  reset() { this.token++; this.watched = 0; this.episodes = 0; this.playing = false; this.foreground = false; this.finished = false; this.pending = false; this.remaining = this.timeout; this.last = this.now(); }
  start(automatic = false) { if (!automatic) this.reset(); else { this.settle(); this.token++; this.playing = false; this.finished = false; } return this.token; }
  playback(token: number, playing: boolean, foreground: boolean) { if (token !== this.token) return; this.settle(); this.playing = playing; this.foreground = foreground; }
  interact() { this.settle(); this.watched = 0; this.episodes = 0; this.remaining = this.timeout; }
  complete(token: number, episode: boolean) { if (token !== this.token || this.finished) return; this.settle(); this.playing = false; this.finished = true; if (episode) this.episodes++; }
  requireConfirmation(token: number) { if (token !== this.token || !this.finished) return false; this.settle(); if (!this.pending && this.episodes >= this.minimumEpisodes && this.watched >= this.minimumWatch) { this.pending = true; this.remaining = this.timeout; } return this.pending; }
  dueAtCredits(token: number) { this.settle(); return token !== this.token || this.pending || this.episodes + (this.finished ? 0 : 1) >= this.minimumEpisodes && this.watched >= this.minimumWatch; }
  get seconds() { this.settle(); return this.pending ? Math.ceil(this.remaining / 1000) : undefined; }
}
export class Countdown {
  remaining = 5000; cancelled = false; private last = 0; private running = false; private fired = false;
  cancel() { this.cancelled = true; this.running = false; }
  tick(now: number, eligible: boolean, canRun: boolean) {
    if (!eligible) { this.remaining = 5000; this.running = false; this.fired = false; this.last = now; return false; }
    if (this.running && canRun && !this.cancelled) this.remaining = Math.max(0, this.remaining - Math.max(0, now - this.last));
    this.last = now; this.running = canRun && !this.cancelled;
    if (!this.running || this.fired || this.remaining > 0) return false; this.fired = true; return true;
  }
}
export function timelineStep(base: number, held: number, repeat: number, duration: number) { if (!repeat) return base; return held >= 5000 ? Math.max(base * 30, Math.min(duration / 20, 600_000)) : held >= 3000 ? base * 18 : held >= 1500 ? base * 6 : held >= 500 ? base * 3 : base; }
