import { createContext, createEffect, createSignal, onCleanup, onMount, useContext } from 'solid-js';
import { activeElement, setActiveElementCore, type ElementNode } from '@solidtv/solid';
import type { NavigableElement } from '@solidtv/solid/primitives';
export { activeElement } from '@solidtv/solid';

export const FocusScope = createContext('screen');
const [scopes, setScopes] = createSignal<string[]>([]);
export const activeScope = () => scopes().at(-1) || 'screen';
const registry = new Map<string, ElementNode>();
const updates = new Set<() => void>();
let scheduled = false;

export function syncSemantics() {
  if (scheduled) return;
  scheduled = true;
  queueMicrotask(() => { scheduled = false; for (const update of updates) update(); });
}
export function trackFrame(update: () => void) {
  updates.add(update); onMount(syncSemantics);
  onCleanup(() => updates.delete(update));
}
export function focusElement(node?: ElementNode) {
  if (!node?.rendered || node.skipFocus) return;
  let child = node;
  for (let parent = child.parent; parent; child = parent, parent = parent.parent) {
    if (typeof parent.selected === 'number') {
      const previous = parent.selected, next = parent.children.indexOf(child); parent.selected = next;
      const navigable = parent as NavigableElement;
      if (next !== previous) navigable.onSelectedChanged?.call(navigable, next, navigable, child, previous);
    }
  }
  if (activeElement() !== node) setActiveElementCore(node);
}
export function restoreFocus(id: string) { const node = registry.get(id); if (node) focusElement(node); return !!node; }
export function registerFocus(id: () => string | undefined, node: () => ElementNode | undefined, autofocus?: () => boolean) {
  createEffect(() => {
    const key = id(), target = node();
    if (!key || !target) return;
    registry.set(key, target);
    onCleanup(() => { if (registry.get(key) === target) registry.delete(key); });
  });
  onMount(() => { if (autofocus?.()) queueMicrotask(() => focusElement(node())); });
}
export function enterScope(id: string) {
  const previous = activeElement();
  setScopes(s => [...s, id]);
  return () => {
    setScopes(s => s.filter(value => value !== id));
    queueMicrotask(() => { if (previous?.rendered && !previous.skipFocus) focusElement(previous); });
  };
}

/** Renderer transforms and DOM targets share one frame update, including clipping. */
export function nodeRect(node?: ElementNode, clip = true) {
  if (!node) return { x: 0, y: 0, width: 0, height: 0, visible: false };
  let x = 0, y = 0, width = node.lng?.w || node.width || 0, height = node.lng?.h || node.height || 0, visible = true;
  for (let p: ElementNode | undefined = node; p; p = p.parent) {
    const n = p.lng;
    const sx = n.scaleX ?? 1, sy = n.scaleY ?? 1, pw = n.w || 0, ph = n.h || 0;
    if (clip && n.clipping) {
      const margins = Array.isArray(n.clipping) ? n.clipping : [0, 0, 0, 0];
      const right = Math.min(pw + margins[1], x + width), bottom = Math.min(ph + margins[2], y + height);
      x = Math.max(-margins[3], x); y = Math.max(-margins[0], y); width = Math.max(0, right - x); height = Math.max(0, bottom - y);
      if (!width || !height) visible = false;
    }
    x = (n.x || 0) - pw * (n.mountX || 0) + pw * (n.pivotX ?? 0.5) * (1 - sx) + x * sx;
    y = (n.y || 0) - ph * (n.mountY || 0) + ph * (n.pivotY ?? 0.5) * (1 - sy) + y * sy;
    width *= sx; height *= sy;
    if (n.alpha === 0 || !p.rendered) visible = false;
  }
  const canvas = document.querySelector('canvas')?.getBoundingClientRect();
  const sx = (canvas?.width || innerWidth) / 1920, sy = (canvas?.height || innerHeight) / 1080;
  return { x: (canvas?.left || 0) + x * sx, y: (canvas?.top || 0) + y * sy, width: width * sx, height: height * sy,
    visible: visible && x + width > 0 && y + height > 0 && x < 1920 && y < 1080 };
}
export function semantic(node: () => ElementNode | undefined, label: () => string, action: () => void,
  disabled = () => false, selected = () => activeElement() === node(), focus = () => focusElement(node()), checked?: () => boolean, escapeClip = () => false) {
  const scope = useContext(FocusScope);
  let button: HTMLButtonElement | undefined;
  const off = () => disabled() || activeScope() !== scope;
  onMount(() => {
    button = document.createElement('button'); button.type = 'button';
    button.dataset.scope = scope;
    button.style.cssText = 'position:fixed;opacity:0;pointer-events:auto;z-index:5;cursor:pointer;border:0;padding:0';
    button.onclick = event => { event.stopPropagation(); if (!off()) { focus(); action(); } };
    button.onfocus = () => { if (!off()) focus(); };
    (document.querySelector(`[data-overlay="${scope}"]`) || document.getElementById('semantics'))!.appendChild(button);
  });
  createEffect(() => {
    const text = label(), unavailable = off(), focused = selected(), chosen = checked?.();
    if (!button) return;
    button.textContent = text; button.disabled = unavailable; button.tabIndex = unavailable ? -1 : 0;
    button.dataset.focused = String(focused && !unavailable);
    if (chosen !== undefined) button.setAttribute('aria-pressed', String(chosen));
    if (focused && !unavailable && document.activeElement !== button) button.focus({ preventScroll: true });
  });
  trackFrame(() => {
    if (!button) return;
    const rect = nodeRect(node(), !escapeClip());
    Object.assign(button.style, { left: `${rect.x}px`, top: `${rect.y}px`, width: `${rect.width}px`, height: `${rect.height}px` });
    button.hidden = !rect.visible;
    button.tabIndex = !rect.visible || off() ? -1 : 0;
  });
  onCleanup(() => button?.remove());
  return off;
}

export function announce(message: string) { const status = document.getElementById('status'); if (status) status.textContent = message; }
