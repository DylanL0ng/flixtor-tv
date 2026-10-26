import { createResource, createSignal, onCleanup, onMount, Show } from 'solid-js';
import { useNavigate } from '@solidjs/router';
import { Column } from '@solidtv/solid/primitives';
import QRCode from 'qrcode';
import { useApp } from '../../app/context';
import { Button, palette } from '../../ui/components';
export function Pairing() {
  const app = useApp(), navigate = useNavigate(), abort = new AbortController();
  const [status, setStatus] = createSignal('Preparing sign-in…'), [qr, setQr] = createSignal('');
  const [pair, { refetch }] = createResource(async () => {
    const code = await app.flixtor.pair(abort.signal); setQr(await QRCode.toDataURL(code.url, { margin: 2, width: 400 })); return code;
  });
  let timer: ReturnType<typeof setTimeout> | undefined; const expires = Date.now() + 180_000;
  async function poll() {
    if (!pair() || abort.signal.aborted) { timer = setTimeout(() => void poll(), 1000); return; }
    if (Date.now() > expires) { setStatus('Pairing expired. Open sign-in again.'); return; }
    try { const state = await app.flixtor.poll(pair()!.code, abort.signal); if (state === 'done') { const account = await app.flixtor.account(abort.signal); if (!['VIP', 'SIGNED_IN'].includes(account)) throw new Error('Sign-in could not be verified.'); app.refreshAccount(); if (app.pendingPlayback) await app.resumePending(); else navigate('/settings/account', { replace: true }); return; } setStatus('Scan with your phone, then approve sign-in.'); }
    catch { if (!abort.signal.aborted) setStatus('Unable to check pairing. Retrying…'); }
    if (!abort.signal.aborted) timer = setTimeout(() => void poll(), 2000);
  }
  onMount(() => { void poll(); }); onCleanup(() => { abort.abort(); clearTimeout(timer); });
  return <Column width={1400} height={900} gap={30} autofocus>
    <text skipFocus width={1300} height={90} fontSize={60}>Sign in with your phone</text>
    <Show when={qr()}><view src={qr()} width={360} height={360} /></Show>
    <text skipFocus width={1300} height={65} fontSize={40}>{pair()?.code || ''}</text>
    <text skipFocus width={1300} height={90} fontSize={28} color={palette.muted}>{pair.error ? 'Pairing unavailable. Try again.' : status()}</text>
    <Show when={pair.error}><Button label="Try again" action={() => void refetch()} /></Show>
    <Button label="Cancel" action={() => navigate('/settings/account')} />
  </Column>;
}
