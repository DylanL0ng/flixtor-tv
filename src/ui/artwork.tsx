import { createEffect, createSignal, on, onCleanup, Show, untrack } from 'solid-js';
import { useApp } from '../app/context';
import { palette, motion, reducedMotion } from './theme';

export function useArtwork(url: () => string | undefined, preserve = false) {
  const app = useApp(); const [src, setSrc] = createSignal('');
  createEffect(() => {
    const value = url(), abort = new AbortController(); if (!preserve || !value) setSrc('');
    if (value) void app.platform.call<string>('art', { url: value }, abort.signal).then(async image => {
      if (image) { const decoded = new Image(); decoded.src = image; await decoded.decode(); }
      if (!abort.signal.aborted) setSrc(image || '');
    }).catch(() => { if (!abort.signal.aborted) setSrc(''); });
    onCleanup(() => abort.abort());
  });
  return src;
}
export function Backdrop(props: { url?: string; width?: number; height?: number; poster?: boolean }) {
  const src = useArtwork(() => props.url, true);
  const [current, setCurrent] = createSignal(''), [previous, setPrevious] = createSignal(''), [alpha, setAlpha] = createSignal(1);
  createEffect(on(src, source => {
    const old = untrack(current); setPrevious(reducedMotion() || !source ? '' : old); setCurrent(source); setAlpha(reducedMotion() ? 0.38 : 0.1);
    const frame = requestAnimationFrame(() => setAlpha(0.38));
    const timer = setTimeout(() => setPrevious(''), 400);
    onCleanup(() => { cancelAnimationFrame(frame); clearTimeout(timer); });
  }));
  return <view skipFocus width={props.width || 1920} height={props.height || 1080} clipping zIndex={-1}>
    <view width={props.width || 1920} height={props.height || 1080} color={palette.ground} />
    <Show when={previous()} keyed>{url => <view src={url} x={props.poster ? (props.width || 1920) * 0.5 : 0} width={props.poster ? (props.width || 1920) * 0.5 : props.width || 1920} height={props.height || 1080} imageType="regular" textureOptions={{ resizeMode: { type: 'cover' } }} alpha={0.38} />}</Show>
    <Show when={current()} keyed>{url => <view src={url} x={props.poster ? (props.width || 1920) * 0.5 : 0} width={props.poster ? (props.width || 1920) * 0.5 : props.width || 1920} height={props.height || 1080} imageType="regular" textureOptions={{ resizeMode: { type: 'cover' } }} alpha={alpha()} transition={{ alpha: motion.fade }} />}</Show>
    <view width={props.width || 1920} height={props.height || 1080} color={0x080c1480} />
    <view width={props.width || 1920} height={props.height || 1080} color={0x00000000} linearGradient={{ colors: [palette.ground, 0x080c14dd, 0x080c1480], stops: [0, 0.55, 1], angle: -Math.PI / 2 }} />
    <view width={props.width || 1920} height={props.height || 1080} color={0x00000000} linearGradient={{ colors: [0x080c1400, 0x080c14dd], stops: [0.3, 1], angle: 0 }} />
  </view>;
}
