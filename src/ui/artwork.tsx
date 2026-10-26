import { createEffect, createSignal, on, onCleanup, Show } from 'solid-js';
import { createBlurredImage } from '@solidtv/solid/primitives';
import { useApp } from '../app/context';
import { palette, reducedMotion } from './theme';

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

const SCREEN_WIDTH = 1920;
const SCREEN_HEIGHT = 1080;

// Baseline alpha applied to backdrop textures so high-vibrancy artwork is dimmed
// in dark living rooms and stays legible behind UI text.
const BACKDROP_ALPHA = 0.45;
const CROSSFADE_MS = 400;
const SWAP_MS = 420;
const BLUR_RADIUS = 40;
const BLUR_RESOLUTION = 0.3;

export function Backdrop(props: { url?: string; width?: number; height?: number }) {
  const src = useArtwork(() => props.url, true);
  const width = () => props.width || SCREEN_WIDTH;
  const height = () => props.height || SCREEN_HEIGHT;

  // Dual-slot buffer tracking for textures.
  const [currentSrc, setCurrentSrc] = createSignal(src());
  const [nextSrc, setNextSrc] = createSignal('');
  // Independent opacity control for the smooth hardware cross-blend.
  const [currentAlpha, setCurrentAlpha] = createSignal(BACKDROP_ALPHA);
  const [nextAlpha, setNextAlpha] = createSignal(0.0);

  // Keep animations on the GPU via the framework transition config so the
  // main thread isn't blocked while panning across options.
  const crossfade = { alpha: { duration: CROSSFADE_MS, easing: 'ease-in-out' as const } };

  // Blurred ambient background layers (soft glow behind sharp textures).
  const blurredCurrent = createBlurredImage(() => currentSrc() || undefined, { radius: BLUR_RADIUS, resolution: BLUR_RESOLUTION });
  const blurredNext = createBlurredImage(() => nextSrc() || undefined, { radius: BLUR_RADIUS, resolution: BLUR_RESOLUTION });

  createEffect(on(src, (source) => {
    if (!source) { setCurrentSrc(''); setNextSrc(''); setCurrentAlpha(0.0); setNextAlpha(0.0); return; }
    if (source === currentSrc()) return;

    if (reducedMotion()) { setNextSrc(''); setCurrentSrc(source); setCurrentAlpha(BACKDROP_ALPHA); setNextAlpha(0.0); return; }

    // 1. Stage the incoming buffer hidden under zero alpha.
    setNextSrc(source);
    setNextAlpha(0.0);

    let frame: ReturnType<typeof requestAnimationFrame>;
    let timer: ReturnType<typeof setTimeout>;
    // 2. Schedule the asymmetric cross-blend across render frames.
    frame = requestAnimationFrame(() => {
      setCurrentAlpha(0.0);
      setNextAlpha(BACKDROP_ALPHA);
      // 3. Clear memory and shift states once the transition window resolves.
      timer = setTimeout(() => {
        setCurrentSrc(source);
        setCurrentAlpha(BACKDROP_ALPHA); // elevate next state to current baseline
        setNextSrc('');                   // free texture maps off the GPU
        setNextAlpha(0.0);
      }, SWAP_MS);
    });
    onCleanup(() => { cancelAnimationFrame(frame); clearTimeout(timer); });
  }, { defer: true }));

  const renderSharp = (url: string, alpha: () => number) => (
    <view src={url} width={width()} height={height()}
      imageType="regular" textureOptions={{ resizeMode: { type: 'cover' } }}
      alpha={alpha()} transition={crossfade} />
  );
  const renderBlur = (data: string | null | undefined, alpha: () => number) => (
    <Show when={data} keyed>
      {(url) => <view src={url} x={0} y={0} width={width()} height={height()}
        imageType="regular" textureOptions={{ resizeMode: { type: 'cover' } }}
        alpha={alpha()} transition={crossfade} />}
    </Show>
  );

  return <view skipFocus width={width()} height={height()} clipping zIndex={-1}>
    {/* Baseline: Solid Dark Canvas */}
    <view width={width()} height={height()} color={palette.ground} />
    {/* Blurred ambient background layer A */}
    {renderBlur(blurredCurrent.error ? undefined : blurredCurrent(), currentAlpha)}
    {/* Blurred ambient background layer B */}
    {renderBlur(blurredNext.error ? undefined : blurredNext(), nextAlpha)}
    {/* Texture Buffer A: Active background asset */}
    <Show when={currentSrc()} keyed>{renderSharp(currentSrc(), currentAlpha)}</Show>
    {/* Texture Buffer B: Staged incoming background asset */}
    <Show when={nextSrc()} keyed>{renderSharp(nextSrc(), nextAlpha)}</Show>
    {/* Hardware-Accelerated Static Burn Overlay Layer (Top -> Bottom) */}
    <view width={width()} height={height()} color={0x00000000}
      linearGradient={{ colors: [0x0a0a0c00, palette.ground], stops: [0, 1], angle: -Math.PI / 2 }} />
  </view>;
}
