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
  const width = () => props.width || 1920, height = () => props.height || 1080;
  const posterWidth = () => Math.min(height() * 0.62, width() * 0.36);
  const posterHeight = () => posterWidth() * 1.5;
  const artX = () => props.poster ? width() - posterWidth() - width() * 0.08 : 0;
  const artY = () => props.poster ? (height() - posterHeight()) / 2 : 0;
  const artWidth = () => props.poster ? posterWidth() : width();
  const artHeight = () => props.poster ? posterHeight() : height();
  createEffect(on(src, source => {
    const old = untrack(current); setPrevious(reducedMotion() || !source ? '' : old); setCurrent(source); setAlpha(reducedMotion() ? 0.92 : 0.1);
    const frame = requestAnimationFrame(() => setAlpha(0.92));
    const timer = setTimeout(() => setPrevious(''), 400);
    onCleanup(() => { cancelAnimationFrame(frame); clearTimeout(timer); });
  }));
  return <view skipFocus width={width()} height={height()} clipping zIndex={-1}>
    <view width={width()} height={height()} color={palette.ground} />
    <Show when={props.poster && !!props.url}><view x={artX() - 18} y={artY() - 18} width={artWidth() + 36} height={artHeight() + 36} color={palette.panelSolid} borderRadius={20} /></Show>
    <Show when={previous()} keyed>{url => <view src={url} x={artX()} y={artY()} width={artWidth()} height={artHeight()} imageType="regular" textureOptions={{ resizeMode: { type: props.poster ? 'contain' : 'cover' } }} alpha={0.92} borderRadius={props.poster ? 12 : 0} />}</Show>
    <Show when={current()} keyed>{url => <view src={url} x={artX()} y={artY()} width={artWidth()} height={artHeight()} imageType="regular" textureOptions={{ resizeMode: { type: props.poster ? 'contain' : 'cover' } }} alpha={alpha()} borderRadius={props.poster ? 12 : 0} transition={{ alpha: motion.fade }} />}</Show>
    <view width={width()} height={height()} color={0x00000000} linearGradient={{ colors: [palette.ground, 0x080c14bb, 0x080c1422], stops: [0, 0.52, 1], angle: -Math.PI / 2 }} />
    <view width={width()} height={height()} color={0x00000000} linearGradient={{ colors: [0x080c144d, 0x080c1488], stops: [0, 1], angle: 0 }} />
  </view>;
}
