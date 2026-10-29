import { createEffect, createSignal, For, onCleanup, onMount } from 'solid-js';
import { activeElement, type ElementNode } from '@solidtv/solid';
import { useApp } from '../app/context';
import { activeScope, focusElement, nodeRect, registerFocus, trackFrame } from './focus';
import { palette, time } from './theme';
import { timelineStep } from '../features/playback/timing';

export function Timeline(props: { width: number }) {
  const player = useApp().playback!;
  let node: ElementNode | undefined, input: HTMLInputElement | undefined, held = 0;
  const [focused, setFocused] = createSignal(false);
  const position = () => player.state.preview ?? player.state.event?.position ?? 0;
  const duration = () => player.state.event?.duration || 1;
  registerFocus(() => 'player-timeline', () => node);
  onMount(() => {
    input = document.createElement('input'); input.type = 'range'; input.min = '0'; input.step = '1000'; input.dataset.scope = 'screen';
    input.setAttribute('aria-label', 'Seek'); input.style.cssText = 'position:fixed;z-index:6;opacity:0;margin:0;cursor:pointer';
    input.onfocus = () => { focusElement(node); setFocused(true); }; input.onblur = () => setFocused(false);
    input.oninput = () => { player.interact(); player.preview(Number(input!.value)); };
    input.onchange = () => { void player.commitSeek(); };
    input.onkeydown = event => {
      if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
        event.preventDefault(); event.stopPropagation(); if (!event.repeat) held = performance.now();
        const step = timelineStep(10_000, performance.now() - held, event.repeat ? 1 : 0, duration());
        player.preview(position() + (event.key === 'ArrowLeft' ? -step : step));
      } else if (event.key === 'Enter') { event.preventDefault(); event.stopPropagation(); void player.commitSeek(); }
    };
    document.getElementById('semantics')!.appendChild(input);
  });
  createEffect(() => {
    const value = position(), max = duration(), isFocused = activeElement() === node, disabled = activeScope() !== 'screen';
    if (input) { input.max = String(max); input.value = String(value); input.disabled = disabled; input.setAttribute('aria-valuetext', `${time(value)} of ${time(max)}`); if (isFocused && !disabled && document.activeElement !== input) input.focus({ preventScroll: true }); }
  });
  trackFrame(() => { if (input) { const rect = nodeRect(node); Object.assign(input.style, { left: `${rect.x}px`, top: `${rect.y}px`, width: `${rect.width}px`, height: `${rect.height}px` }); input.hidden = !rect.visible; } });
  onCleanup(() => input?.remove());
  return <view ref={node} width={props.width} height={84} onFocus={() => setFocused(true)} onBlur={() => setFocused(false)}
    onLeft={() => { player.preview(position() - 10_000); return true; }} onRight={() => { player.preview(position() + 10_000); return true; }} onEnter={() => void player.commitSeek()}>
    <view skipFocus y={18} width={props.width} height={focused() ? 8 : 5} borderRadius={4} color={0xffffff33} />
    <view skipFocus y={18} width={props.width * Math.min(1, (player.state.event?.buffered || 0) / duration())} height={focused() ? 8 : 5} borderRadius={4} color={0xffffff66} />
    <For each={player.state.regions}>{region => <view skipFocus x={props.width * region.start / duration()} y={16} width={props.width * (region.end - region.start) / duration()} height={10} color={0xffffff44} borderRadius={3} />}</For>
    <view skipFocus y={18} width={props.width * Math.min(1, position() / duration())} height={focused() ? 8 : 5} borderRadius={4} color={focused() ? palette.accent : palette.white} />
    <view skipFocus x={Math.min(props.width - 8, props.width * position() / duration())} y={focused() ? 13 : 17} width={focused() ? 18 : 6} height={focused() ? 18 : 6} borderRadius={9} color={palette.white} />
    <text skipFocus y={45} width={props.width * 0.5} height={32} fontSize={23} color={palette.muted}>{time(position())}{player.state.preview !== undefined ? '   ·   OK to seek · Back to cancel' : ''}</text>
    <text skipFocus x={props.width * 0.5} y={45} width={props.width * 0.5} height={32} fontSize={23} textAlign="right" color={palette.muted}>{`−${time(Math.max(0, duration() - position()))}`}</text>
  </view>;
}
