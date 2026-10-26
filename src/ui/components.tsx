import { createEffect, createMemo, createSignal, For, onCleanup, Show, useContext, type ParentProps } from 'solid-js';
import { type ElementNode } from '@solidtv/solid';
import { useArtwork } from './artwork';
import { fraction, type Title, type Viewing } from '../domain/models';
import { icon, avatar, avatarColors, type IconName } from './icons';
import { activeScope, FocusScope, semantic, registerFocus, focusElement, announce } from './focus';
import { palette, motion, reducedMotion, glassColor, time } from './theme';
export { palette, motion, time } from './theme';
export { restoreFocus, focusElement, semantic } from './focus';

export function Glass(props: ParentProps<{ width: number; height: number; x?: number; y?: number; radius?: number; solid?: boolean; focused?: boolean }>) {
  return <view skipFocus x={props.x || 0} y={props.y || 0} width={props.width} height={props.height}>
    <view width={props.width} height={props.height} color={props.solid ? palette.panelSolid : glassColor()} borderRadius={props.radius || 28}
      border={{ width: 1, color: props.focused ? 0xf5f8ff99 : 0xd8e8ff29 }} shadow={{ color: 0x00000066, x: 0, y: 12, blur: 24, spread: 0, projection: 0 }} />
    <view x={props.radius || 28} y={1} width={Math.max(0, props.width - 2 * (props.radius || 28))} height={1} color={0xffffff38} />
    {props.children}
  </view>;
}
export interface ButtonProps {
  label: string; action(): unknown; width?: number; height?: number; autofocus?: boolean; id?: string;
  disabled?: boolean; selected?: boolean; selectionFill?: boolean; onFocus?(): void; icon?: IconName; primary?: boolean; quiet?: boolean; iconOnly?: boolean;
}
export function Button(props: ButtonProps) {
  let node: ElementNode | undefined; const [focused, setFocused] = createSignal(false), [pressed, setPressed] = createSignal(false);
  let release: ReturnType<typeof setTimeout> | undefined;
  const scope = useContext(FocusScope);
  const off = () => !!props.disabled || activeScope() !== scope;
  const activate = () => {
    if (off()) return;
    setPressed(true); clearTimeout(release); release = setTimeout(() => setPressed(false), 100); props.action();
  };
  semantic(() => node, () => props.label, activate, () => !!props.disabled, undefined, undefined, () => !!props.selected);
  registerFocus(() => props.id, () => node, () => !!props.autofocus);
  onCleanup(() => clearTimeout(release));
  const fill = () => focused() ? palette.accent : props.primary ? palette.foreground : props.selected && props.selectionFill !== false ? 0x71859b66 : props.quiet ? 0x00000000 : glassColor();
  const dark = () => focused() || !!props.primary;
  return <view ref={node} width={props.width || 280} height={props.height || 68} skipFocus={off()} autofocus={props.autofocus}
    color={fill()} borderRadius={(props.height || 68) / 2}
    border={{ width: props.quiet && !focused() ? 0 : 1, color: focused() ? palette.white : 0xe6f2ff33 }}
    alpha={props.disabled ? 0.4 : 1} scale={reducedMotion() ? 1 : pressed() ? 0.97 : focused() ? 1.035 : 1}
    transition={{ color: motion.focus, scale: motion.focus }}
    onFocus={() => { setFocused(true); props.onFocus?.(); }} onBlur={() => setFocused(false)} onEnter={() => { activate(); return true; }}>
    <Show when={props.icon}><view skipFocus x={props.iconOnly ? ((props.width || 68) - 28) / 2 : 23} y={((props.height || 68) - 28) / 2} width={28} height={28} src={icon(props.icon!, dark())} imageType="svg" /></Show>
    <Show when={!props.iconOnly}><text skipFocus x={props.icon ? 64 : 22} y={((props.height || 68) - 34) / 2} width={(props.width || 280) - (props.icon ? 84 : 44)} height={38} fontSize={27} maxLines={1} color={dark() ? palette.ink : palette.foreground}>{props.label}</text></Show>
  </view>;
}
export interface Option { id: string; label: string; value?: string; action(): unknown; disabled?: boolean; selected?: boolean }
export function Menu(props: { options: Option[]; title?: string; width?: number; height?: number; initial?: string; back?(): void; id?: string }) {
  const options = createMemo(() => props.options);
  const [index, setIndex] = createSignal(Math.max(0, options().findIndex(o => o.id === props.initial || o.selected)));
  const [focused, setFocused] = createSignal(false);
  const scope = useContext(FocusScope);
  let node: ElementNode | undefined;
  const rows = () => Math.max(1, Math.floor(((props.height || 630) - (props.title ? 82 : 0) - 28) / 82));
  const start = () => Math.max(0, Math.min(index() - Math.floor(rows() / 2), options().length - rows()));
  createEffect(() => {
    const list = options();
    if (index() >= list.length) setIndex(Math.max(0, list.length - 1));
    if (list[index()]?.disabled) { const first = list.findIndex(o => !o.disabled); if (first >= 0) setIndex(first); }
  });
  const move = (delta: number) => {
    let next = index() + delta;
    while (options()[next]?.disabled) next += delta;
    if (next < 0 || next >= options().length) return false;
    setIndex(next); focusElement(node); return true;
  };
  registerFocus(() => props.id, () => node, () => true);
  return <view ref={node} width={props.width || 1200} height={props.height || 630} skipFocus={activeScope() !== scope}
    autofocus onFocus={() => setFocused(true)} onBlur={() => setFocused(false)} onUp={() => move(-1)} onDown={() => move(1)}
    onBack={() => { props.back?.(); return true; }} onEnter={() => { const option = options()[index()]; if (!option?.disabled && activeScope() === scope) option?.action(); return true; }}>
    <Show when={props.title}><text skipFocus width={props.width || 1200} height={66} fontSize={38} fontFamily="Roboto700" maxLines={2}>{props.title}</text></Show>
    <For each={options().slice(start(), start() + rows())}>{(option, offset) => {
      let row: ElementNode | undefined;
      const position = () => start() + offset(), highlighted = () => position() === index() && focused();
      const focus = () => { setIndex(position()); focusElement(node); };
      const activate = () => { if (!option.disabled) { focus(); option.action(); } };
      semantic(() => row, () => [props.title, option.label, option.value].filter(Boolean).join(', '), activate,
        () => !!option.disabled, highlighted, focus, () => !!option.selected);
      return <view ref={row} skipFocus y={(props.title ? 82 : 0) + offset() * 82} width={props.width || 1200} height={72}
        color={highlighted() ? palette.foreground : 0xffffff06} alpha={option.disabled ? 0.4 : 1} borderRadius={18}
        transition={{ color: motion.focus }}>
        <text skipFocus x={24} y={19} width={(props.width || 1200) * 0.68 - 40} height={36} fontSize={27} maxLines={1} color={highlighted() ? palette.ink : palette.foreground}>{option.label}</text>
        <Show when={option.selected}><view skipFocus x={(props.width || 1200) - 54} y={23} width={26} height={26} src={icon('check', highlighted())} imageType="svg" /></Show>
        <Show when={!option.selected && option.value}><text skipFocus x={(props.width || 1200) * 0.68} y={20} width={(props.width || 1200) * 0.32 - 25} height={34} fontSize={24} textAlign="right" maxLines={1} color={highlighted() ? palette.ink : palette.muted}>{option.value}</text></Show>
      </view>;
    }}</For>
    <Show when={options().length > rows()}><text skipFocus y={(props.height || 630) - 26} width={props.width || 1200} height={28} fontSize={21} textAlign="right" color={palette.muted}>{`${index() + 1} / ${options().length}`}</text></Show>
  </view>;
}
export function Poster(props: { title: Title; action(): unknown; autofocus?: boolean; onFocus?(): void; id: string; width?: number; landscape?: boolean; viewing?: Viewing }) {
  const src = useArtwork(() => props.landscape ? props.title.backdrop || props.title.poster : props.title.poster);
  let node: ElementNode | undefined; const [focused, setFocused] = createSignal(false);
  const width = () => props.width || 240, imageHeight = () => props.landscape ? width() * 0.5625 : width() * 1.5;
  const off = semantic(() => node, () => props.title.name, props.action);
  registerFocus(() => props.id, () => node, () => !!props.autofocus);
  return <view ref={node} width={width()} height={imageHeight() + 76} skipFocus={off()} onEnter={() => { if (!off()) props.action(); return true; }}
    zIndex={focused() ? 5 : 1} scale={reducedMotion() ? 1 : focused() ? 1.045 : 1} pivotY={1}
    transition={{ scale: motion.lift }} onFocus={() => { setFocused(true); props.onFocus?.(); }} onBlur={() => setFocused(false)}>
    <view width={width()} height={imageHeight()} color={palette.panelSolid} borderRadius={18}>
      <Show when={src()} fallback={<view skipFocus width={width()} height={imageHeight()}>
        <text skipFocus x={24} y={24} width={width() - 48} height={28} fontSize={18} color={palette.muted}>FLIXTOR</text>
        <text skipFocus x={24} y={props.landscape ? 76 : 115} width={width() - 48} height={props.landscape ? 100 : 200} fontFamily="Roboto700" fontSize={props.landscape ? 31 : 32} maxLines={4} color={palette.foreground}>{props.title.name}</text>
        <text skipFocus x={24} y={imageHeight() - 48} width={width() - 48} height={30} fontSize={21} color={palette.muted}>{props.title.year || (props.title.source === 'HIANIME' ? 'Anime' : 'FlixTor')}</text>
      </view>}><view skipFocus src={src()} width={width()} height={imageHeight()} borderRadius={18} imageType="regular" textureOptions={{ resizeMode: { type: 'cover' } }} /></Show>
      <view skipFocus width={width()} height={imageHeight()} borderRadius={18} color={0x00000000} border={{ width: focused() ? 3 : 0, color: palette.foreground }} />
    </view>
    <Show when={props.viewing}><view skipFocus x={8} y={imageHeight() - 10} width={width() - 16} height={4} color={0xffffff44} borderRadius={2}><view width={(width() - 16) * fraction(props.viewing!)} height={4} color={palette.accent} borderRadius={2} /></view></Show>
    <Show when={src()}><text skipFocus y={imageHeight() + 16} width={width()} height={34} fontSize={26} maxLines={1} color={focused() ? palette.white : palette.foreground}>{props.title.name}</text></Show>
    <text skipFocus y={imageHeight() + (src() ? 50 : 18)} width={width()} height={30} fontSize={22} maxLines={1} color={palette.muted}>{props.viewing ? props.viewing.episode ? `Episode ${props.viewing.episode.displayNumber || props.viewing.episode.number} · ${time(Math.max(0, props.viewing.duration - props.viewing.position))} left` : `${time(Math.max(0, props.viewing.duration - props.viewing.position))} left` : props.title.year || ''}</text>
  </view>;
}
export function Avatar(props: { index: number; size?: number; locked?: boolean }) {
  const size = () => props.size || 160;
  return <view skipFocus width={size()} height={size()} color={avatarColors[props.index % 7]} borderRadius={size() * 0.28}>
    <view src={avatar(props.index)} imageType="svg" x={size() * 0.15} y={size() * 0.08} width={size() * 0.7} height={size() * 0.84} />
    <Show when={props.locked}><view x={size() - 38} y={size() - 38} width={44} height={44} color={palette.panelSolid} borderRadius={22}><view x={11} y={10} width={22} height={22} src={icon('lock')} imageType="svg" /></view></Show>
  </view>;
}
export function Status(props: { message: string; error?: boolean; width?: number }) {
  createEffect(() => announce(props.message));
  return <text skipFocus width={props.width || 1300} height={85} fontSize={28} maxLines={2} color={props.error ? palette.danger : palette.muted}>{props.message}</text>;
}
