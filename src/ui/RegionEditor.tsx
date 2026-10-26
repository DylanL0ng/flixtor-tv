import { createEffect, createSignal, For, onCleanup, onMount, useContext } from 'solid-js';
import { activeElement, type ElementNode } from '@solidtv/solid';
import { Column, Row } from '@solidtv/solid/primitives';
import { Button, palette, time } from './components';
import { activeScope, FocusScope, focusElement, nodeRect, registerFocus, trackFrame } from './focus';
import { timelineStep } from '../features/playback/timing';
import type { Segment } from '../domain/models';

export function RegionEditor(props: { segment: Segment; duration: number; change(value: Segment): void; preview(position: number): void }) {
  const [zoom, setZoom] = createSignal(false), [handle, setHandle] = createSignal<'start' | 'end'>('start');
  const min = () => zoom() ? Math.max(0, Math.min(props.duration - 60_000, props.segment[handle()] - 30_000)) : 0;
  const max = () => zoom() ? Math.min(props.duration, min() + 60_000) : props.duration;
  const update = (key: 'start' | 'end', value: number) => {
    setHandle(key);
    const next = Math.max(key === 'end' ? props.segment.start + 1000 : 0, Math.min(key === 'start' ? props.segment.end - 1000 : props.duration, Math.round(value / 1000) * 1000));
    props.change({ ...props.segment, [key]: next }); props.preview(next);
  };
  return <Column width={640} height={780} gap={28} scroll="none">
    <text skipFocus width={640} height={68} fontSize={40} fontFamily="Roboto700">Edit region</text>
    <text skipFocus width={640} height={100} fontSize={27} maxLines={3} color={palette.muted}>Move either boundary to preview the paused video. Arrows adjust one second; hold to move faster.</text>
    <view skipFocus width={640} height={95}>
      <view y={28} width={640} height={8} color={palette.line} borderRadius={4} />
      <view x={640 * Math.max(0, props.segment.start - min()) / (max() - min() || 1)} y={24} width={640 * Math.max(0, Math.min(max(), props.segment.end) - Math.max(min(), props.segment.start)) / (max() - min() || 1)} height={16} color={palette.accent} borderRadius={8} />
      <text skipFocus y={60} width={320} height={30} fontSize={22} color={palette.muted}>{time(min())}</text><text skipFocus x={320} y={60} width={320} height={30} textAlign="right" fontSize={22} color={palette.muted}>{time(max())}</text>
    </view>
    <For each={['start', 'end'] as const}>{key => <Boundary label={key === 'start' ? 'Start' : 'End'} value={props.segment[key]} min={min()} max={max()} change={value => update(key, value)} selected={() => setHandle(key)} />}</For>
    <Button label={zoom() ? 'Show full timeline' : 'Zoom to 60 seconds'} width={360} action={() => setZoom(!zoom())} />
  </Column>;
}
function Boundary(props: { label: string; value: number; min: number; max: number; change(value: number): void; selected(): void }) {
  let node: ElementNode | undefined, input: HTMLInputElement | undefined, held = 0;
  const scope = useContext(FocusScope), [focused, setFocused] = createSignal(false);
  registerFocus(() => `region-${props.label}`, () => node);
  onMount(() => {
    input = document.createElement('input'); input.type = 'range'; input.step = '1000'; input.setAttribute('aria-label', `${props.label} boundary`); input.dataset.scope = scope;
    input.style.cssText = 'position:fixed;opacity:0;z-index:40;margin:0;cursor:ew-resize';
    input.onfocus = () => { focusElement(node); setFocused(true); props.selected(); }; input.onblur = () => setFocused(false);
    input.oninput = () => props.change(Number(input!.value));
    input.onkeydown = event => {
      if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
      event.preventDefault(); event.stopPropagation(); if (!event.repeat) held = performance.now();
      props.change(props.value + (event.key === 'ArrowLeft' ? -1 : 1) * timelineStep(1000, performance.now() - held, event.repeat ? 1 : 0, props.max));
    };
    (document.querySelector(`[data-overlay="${scope}"]`) || document.getElementById('semantics'))!.appendChild(input);
  });
  createEffect(() => {
    const value = props.value, min = props.min, max = props.max, focus = activeElement() === node, disabled = activeScope() !== scope;
    if (input) { input.min = String(min); input.max = String(max); input.value = String(value); input.disabled = disabled; input.setAttribute('aria-valuetext', time(value)); if (focus && !disabled && document.activeElement !== input) input.focus({ preventScroll: true }); }
  });
  trackFrame(() => { if (input) { const rect = nodeRect(node); Object.assign(input.style, { left: `${rect.x}px`, top: `${rect.y}px`, width: `${rect.width}px`, height: `${rect.height}px` }); input.hidden = !rect.visible; } });
  onCleanup(() => input?.remove());
  return <view ref={node} width={640} height={76} borderRadius={20} color={focused() ? palette.white : palette.panel} onFocus={() => { setFocused(true); props.selected(); }} onBlur={() => setFocused(false)}>
    <text skipFocus x={24} y={22} width={300} height={40} fontSize={28} color={focused() ? palette.ink : palette.foreground}>{props.label}</text>
    <text skipFocus x={350} y={22} width={260} height={40} textAlign="right" fontSize={28} color={focused() ? palette.ink : palette.foreground}>{time(props.value)}</text>
  </view>;
}
