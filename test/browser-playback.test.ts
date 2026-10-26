import { expect, it, vi } from 'vitest';
import { fixturePlatform } from './fixture-platform';
import type { PlayerEvent } from '../src/platform/platform';

it('browser player reports play intent consistently through load, pause and resume', async () => {
  vi.useFakeTimers();
  const platform = fixturePlatform();
  let player: PlayerEvent | undefined;
  platform.onEvent(event => { if (event.type === 'player') player = event.value as PlayerEvent; });
  try {
    await platform.call('player.load', { id: 'first', play: true });
    vi.advanceTimersByTime(250);
    expect(player).toMatchObject({ playing: true, playRequested: true });
    await platform.call('player.command', { id: 'first', action: 'pause' });
    vi.advanceTimersByTime(250);
    expect(player).toMatchObject({ playing: false, playRequested: false });
    await platform.call('player.load', { id: 'recovered', play: false, position: 99_000 });
    vi.advanceTimersByTime(250);
    expect(player).toMatchObject({ id: 'recovered', position: 99_000, playing: false, playRequested: false });
    await platform.call('player.command', { id: 'recovered', action: 'play' });
    vi.advanceTimersByTime(250);
    expect(player).toMatchObject({ playing: true, playRequested: true });
  } finally { platform.dispose(); vi.useRealTimers(); }
});
