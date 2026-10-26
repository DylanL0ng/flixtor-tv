import { createSignal, onCleanup, onMount, Show } from 'solid-js';
import { useNavigate } from '@solidjs/router';
import { Column, Row } from '@solidtv/solid/primitives';
import QRCode from 'qrcode';
import { useApp } from '../../app/context';
import { Button, Glass, palette, Status } from '../../ui/components';
import { narratePage } from '../../ui/narration';

export function Pairing() {
  const app = useApp(), navigate = useNavigate();
  const [status, setStatus] = createSignal('Preparing sign-in…'), [qr, setQr] = createSignal(''), [code, setCode] = createSignal(''), [failed, setFailed] = createSignal(false);
  narratePage(() => 'Sign in with your phone', () => `${code()}. ${status()}`);
  let abort = new AbortController(), timer: ReturnType<typeof setTimeout> | undefined, generation = 0, alive = true;
  async function begin() {
    abort.abort(); abort = new AbortController(); clearTimeout(timer); const signal = abort.signal, token = ++generation;
    setQr(''); setCode(''); setFailed(false); setStatus('Preparing sign-in…');
    try {
      const pair = await app.flixtor.pair(signal);
      const image = await QRCode.toDataURL(pair.url, { margin: 3, width: 500 });
      if (signal.aborted || !alive || token !== generation) return;
      setQr(image); setCode(pair.code); setStatus('Scan with your phone, then approve sign-in.');
      const expires = Date.now() + 180_000;
      async function poll() {
        if (signal.aborted || !alive || token !== generation) return;
        if (Date.now() >= expires) { setStatus('This code expired. Generate a new code to continue.'); setFailed(true); setQr(''); return; }
        try {
          const state = await app.flixtor.poll(pair.code, signal);
          if (signal.aborted || !alive || token !== generation) return;
          if (state === 'done') {
            const account = await app.flixtor.account(signal);
            if (signal.aborted || !alive || token !== generation) return;
            if (!['VIP', 'SIGNED_IN'].includes(account)) throw new Error('unverified');
            app.refreshAccount(); if (app.pendingPlayback) await app.resumePending(); else navigate('/settings/account', { replace: true }); return;
          }
        } catch { if (!signal.aborted && alive) setStatus('Unable to check pairing. Retrying…'); }
        if (!signal.aborted && alive && token === generation) timer = setTimeout(() => void poll(), 2000);
      }
      timer = setTimeout(() => void poll(), 1000);
    } catch { if (!signal.aborted && alive) { setFailed(true); setStatus('Pairing unavailable. Try again.'); } }
  }
  const unsubscribe = app.platform.onEvent(event => {
    if (event.type === 'lifecycle' && !(event.value as { foreground: boolean }).foreground) {
      abort.abort(); clearTimeout(timer); setQr(''); setFailed(true); setStatus('Pairing paused. Generate a new code when you’re ready.');
    }
  });
  onMount(() => void begin());
  onCleanup(() => { alive = false; generation++; abort.abort(); clearTimeout(timer); unsubscribe(); });
  return <Column width={1728} height={972} gap={36} scroll="none">
    <Button label="Back" icon="back" quiet width={170} action={() => navigate('/settings/account')} />
    <text skipFocus width={1500} height={100} fontSize={66} fontFamily="Roboto700">Sign in with your phone</text>
    <Row width={1728} height={560} gap={70}>
      <view skipFocus width={500} height={500} color={palette.white} borderRadius={26}><Show when={qr()}><view x={10} y={10} src={qr()} width={480} height={480} /></Show></view>
      <Column width={1050} height={560} gap={28} scroll="none">
        <text skipFocus width={1000} height={90} fontSize={58} fontFamily="Roboto700">{code()}</text>
        <Status width={1000} message={status()} error={failed()} />
        <text skipFocus width={950} height={100} fontSize={28} maxLines={3} color={palette.muted}>Use your existing service account. One sign-in connects this TV; each profile keeps its own list and viewing progress.</text>
        <Button label={failed() ? 'Generate new code' : 'Refresh code'} autofocus action={() => void begin()} />
        <Button label="Cancel" quiet action={() => navigate('/settings/account')} />
      </Column>
    </Row>
  </Column>;
}
