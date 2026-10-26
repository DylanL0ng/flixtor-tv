import { createEffect, createSignal, For, onCleanup, onMount, Show } from 'solid-js';
import { activeElement, setActiveElementCore, type ElementNode } from '@solidtv/solid';
import { useApp } from '../app/context';
export const palette = {
  midnight: 0x06152eff,
  panel: 0x1b3457e6,
  panelSolid: 0x142844ff,
  accent: 0x9cebffff,
  muted: 0xbdcee7ff,
  foreground: 0xf4f6fcff,
  white: 0xffffffff,
  black: 0x000000ff,
  ink: 0x06152eff,
  danger: 0xffafa5ff,
  ground: 0x06152eff,
  surface: 0x1b3457e6,
  line: 0x2a4a6dff,
  text: 0xf4f6fcff,
  focus: 0x9cebffff,
};

export const motion = {
  focus: { duration: 180, easing: 'cubic-bezier(0.2, 0.9, 0.3, 1.1)' },
  lift:  { duration: 220, easing: 'cubic-bezier(0.2, 0.9, 0.3, 1.1)' },
  enter: { duration: 320, easing: 'cubic-bezier(0.16, 1, 0.3, 1)' },
  slide: { duration: 280, easing: 'cubic-bezier(0.16, 1, 0.3, 1)' },
  fade:  { duration: 200, easing: 'ease-in-out' },
} as const;

const focusRegistry = new Map<string, ElementNode>();
function focusElement(node: ElementNode) {
  let child = node;
  for (let parent = child.parent; parent; child = parent, parent = parent.parent) {
    if (typeof parent.selected === 'number') parent.selected = parent.children.indexOf(child);
  }
  setActiveElementCore(node);
}
export function restoreFocus(key: string) { const node = focusRegistry.get(key); if (node) focusElement(node); }

/** DOM hit targets share the canvas controls' focus and activation paths. */
function semantic(node: () => ElementNode | undefined, label: () => string, action: () => void, disabled: () => boolean,
  focus = () => { const target = node(); if (target) focusElement(target); }, selected = () => activeElement() === node()) {
  let button: HTMLButtonElement;
  onMount(() => {
    button = document.createElement('button'); button.tabIndex = 0;
    button.style.cssText = 'position:fixed;opacity:0;pointer-events:auto;z-index:5;cursor:pointer;border:0;padding:0';
    button.type = 'button'; button.onclick = event => { event.stopPropagation(); focus(); action(); }; button.onfocus = focus;
    button.onpointerenter = () => { if (!disabled()) button.focus({ preventScroll: true }); };
    document.getElementById('semantics')!.appendChild(button);
    button.textContent = label(); button.disabled = disabled();
    update();
  });
  createEffect(() => { const text = label(), off = disabled(); if (button) { button.textContent = text; button.disabled = off; } });
  createEffect(() => { const focused = selected(); if (button) button.dataset.focused = String(focused); });
  function update() {
    const n = node(); if (!n || !button) return;
    const canvas = document.querySelector('canvas')?.getBoundingClientRect();
    const csx = (canvas?.width || innerHeight / 1080 * 1920) / 1920, csy = (canvas?.height || innerHeight) / 1080;
    let x = 0, y = 0, width = (n.lng?.w || n.width || 0), height = (n.lng?.h || n.height || 0), visible = true;
    for (let p: any = n; p; p = p.parent) {
      const lng = p.lng || p;
      const sx = lng.scaleX ?? p.scaleX ?? (p as any).scale ?? 1, sy = lng.scaleY ?? p.scaleY ?? (p as any).scale ?? 1;
      const mx = lng.mountX ?? p.mountX ?? (p as any).mount ?? 0, my = lng.mountY ?? p.mountY ?? (p as any).mount ?? 0;
      const px = lng.pivotX ?? p.pivotX ?? (p as any).pivot ?? 0.5, py = lng.pivotY ?? p.pivotY ?? (p as any).pivot ?? 0.5;
      const pw = lng.w || p.width || 0, ph = lng.h || p.height || 0;
      const lx = lng.x ?? p.x ?? 0, ly = lng.y ?? p.y ?? 0;
      x = lx - pw * mx + pw * px * (1 - sx) + x * sx;
      y = ly - ph * my + ph * py * (1 - sy) + y * sy;
      width *= sx; height *= sy;
      if ((lng.alpha ?? p.alpha ?? 1) === 0) visible = false;
    }
    Object.assign(button.style, { left: `${(canvas?.left || 0) + x * csx}px`, top: `${(canvas?.top || 0) + y * csy}px`, width: `${width * csx}px`, height: `${height * csy}px` });
    button.hidden = !visible || width <= 0 || height <= 0;
  }
  const timer = setInterval(update, 100);
  onCleanup(() => { clearInterval(timer); button?.remove(); });
}
export function Button(props: { label: string; action(): unknown; width?: number; height?: number; autofocus?: boolean; id?: string; disabled?: boolean; selected?: boolean; onFocus?(): void }) {
  let node: ElementNode | undefined; const [focused, setFocused] = createSignal(false);
  const activate = () => { if (!props.disabled) props.action(); };
  semantic(() => node, () => props.label, activate, () => !!props.disabled);
  onMount(() => { if (props.id && node) focusRegistry.set(props.id, node); });
  onCleanup(() => { if (props.id) focusRegistry.delete(props.id); });
  return <view ref={node} width={props.width || 300} height={props.height || 68} autofocus={props.autofocus} skipFocus={props.disabled}
    color={focused() ? palette.accent : props.selected ? palette.line : palette.panel}
    alpha={props.disabled ? 0.45 : 1} borderRadius={10}
    scaleX={focused() ? 1.04 : 1} scaleY={focused() ? 1.04 : 1}
    borderLeft={props.selected && !focused() ? { width: 4, color: palette.accent } : undefined}
    transition={{ color: motion.focus, scaleX: motion.focus, scaleY: motion.focus }}
    onFocus={() => { setFocused(true); props.onFocus?.(); }} onBlur={() => { setFocused(false); }} onEnter={() => { activate(); }}>
    <text x={22} y={17} width={(props.width || 300) - 44} height={40} fontSize={28} maxLines={1} color={focused() ? palette.ink : palette.foreground}>{props.label}</text>
  </view>;
}
export interface Option { id: string; label: string; value?: string; action(): unknown; disabled?: boolean; selected?: boolean }
export function Menu(props: { options: Option[]; title?: string; width?: number; height?: number; initial?: string; back?(): void }) {
  const [index, setIndex] = createSignal(Math.max(0, props.options.findIndex(o => o.id === props.initial || o.selected)));
  const [hovered, setHovered] = createSignal<number>();
  let node: ElementNode | undefined;
  const maxRows = () => Math.max(1, Math.floor(((props.height || 650) - (props.title ? 80 : 0)) / 76));
  const start = () => Math.max(0, Math.min(index() - Math.floor(maxRows() / 2), props.options.length - maxRows()));
  const active = () => props.options[index()];
  createEffect(() => { if (index() >= props.options.length) setIndex(Math.max(0, props.options.length - 1)); });
  function move(delta: number) {
    let next = (hovered() ?? index()) + delta;
    while (next >= 0 && next < props.options.length && props.options[next].disabled) next += delta;
    if (next < 0 || next >= props.options.length) return false;
    setHovered(undefined); setIndex(next); if (node) focusElement(node); return true;
  }
  return <view ref={node} width={props.width || 1200} height={props.height || 650} autofocus onUp={() => move(-1)} onDown={() => move(1)}
    onBack={() => { props.back?.(); return true; }} onEnter={() => { if (!active()?.disabled) active()?.action(); }}>
    <Show when={props.title}><text skipFocus width={props.width || 1200} height={65} fontSize={42} color={palette.foreground}>{props.title}</text></Show>
    <For each={props.options.slice(start(), start() + maxRows())}>{(option, offset) => {
      let row: ElementNode | undefined;
      const position = () => start() + offset(), highlighted = () => position() === (hovered() ?? index());
      const focus = () => { setHovered(undefined); setIndex(position()); if (node) focusElement(node); };
      const activate = () => { if (!option.disabled) { focus(); option.action(); } };
      semantic(() => row, () => [props.title, option.label, option.value].filter(Boolean).join(', '), activate,
        () => !!option.disabled, focus, () => highlighted() && (activeElement() === node || activeElement() === row));
      return <view ref={row} y={(props.title ? 80 : 0) + offset() * 76} width={props.width || 1200} height={68} skipFocus={option.disabled}
        onMouseClick={activate} onEnter={activate} onFocus={() => { if (!option.disabled) setHovered(position()); }} onBlur={() => setHovered(undefined)}
        color={highlighted() ? palette.accent : 0x00000000} alpha={option.disabled ? 0.45 : 1} borderRadius={8}
        transition={{ color: motion.focus }}>
        <text x={22} y={17} width={(props.width || 1200) * 0.65 - 22} height={40} fontSize={28} color={highlighted() ? palette.ink : palette.foreground}>{option.label}</text>
        <text x={(props.width || 1200) * 0.65} y={17} width={(props.width || 1200) * 0.35 - 22} height={40} fontSize={26} textAlign="right" color={highlighted() ? palette.ink : palette.muted}>{option.selected ? '✓' : option.value || ''}</text>
      </view>;
    }}</For>
    <Show when={props.options.length > maxRows()}><text y={(props.height || 650) - 20} width={props.width || 1200} height={28} fontSize={20} color={palette.muted}>{`${index() + 1} / ${props.options.length}`}</text></Show>
  </view>;
}
export function Poster(props: { title: import('../domain/models').Title; action(): unknown; autofocus?: boolean; onFocus?(): void; id: string }) {
  const app = useApp(); const [src, setSrc] = createSignal(''); let alive = true;
  let node: ElementNode | undefined; const [focused, setFocused] = createSignal(false);
  semantic(() => node, () => props.title.name, props.action, () => false);
  onMount(() => { if (node) { focusRegistry.set(props.id, node); if (app.lastFocus === props.id) focusElement(node); } });
  onCleanup(() => { focusRegistry.delete(props.id); });
  createEffect(() => { const url = props.title.poster; if (url) void app.platform.call<string>('art', { url }).then(value => { if (alive) setSrc(value); }).catch(() => {}); else setSrc(''); });
  onCleanup(() => { alive = false; });
  return <view ref={node} width={260} height={370} autofocus={props.autofocus} onEnter={() => { props.action(); }}
    zIndex={focused() ? 10 : 1}
    y={focused() ? -8 : 0}
    scaleX={focused() ? 1.08 : 1} scaleY={focused() ? 1.08 : 1}
    borderRadius={12}
    border={focused() ? { width: 3, color: palette.accent } : { width: 1, color: palette.line }}
    transition={{ scaleX: motion.lift, scaleY: motion.lift, y: motion.lift }}
    onFocus={() => { setFocused(true); props.onFocus?.(); }} onBlur={() => setFocused(false)}>
    <Show when={src()}><view src={src()} width={260} height={285} borderRadius={[12, 12, 0, 0]} imageType="regular" /></Show>
    <Show when={!src()}><view width={260} height={285} color={palette.panel} borderRadius={[12, 12, 0, 0]}><text x={18} y={70} width={224} height={150} fontSize={34} maxLines={3} color={palette.foreground}>{props.title.name}</text></view></Show>
    <view y={285} width={260} height={85} color={focused() ? palette.accent : palette.panel} borderRadius={[0, 0, 12, 12]} transition={{ color: motion.focus }}>
      <text x={16} y={16} width={228} height={52} fontSize={26} maxLines={2} color={focused() ? palette.ink : palette.foreground}>{props.title.name}</text>
    </view>
  </view>;
}
export const time = (milliseconds: number) => { const seconds = Math.max(0, Math.floor(milliseconds / 1000)); return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`; };
