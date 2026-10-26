import { createSignal, onCleanup, onMount, type ParentProps } from 'solid-js';
import { useApp } from '../app/context';
import { FocusScope, enterScope, announce } from './focus';
import { Glass } from './components';
import { motion, reducedMotion } from './theme';
import { Portal } from '@solidtv/solid/primitives';

let sequence = 0;
export function Overlay(props: ParentProps<{ title: string; close(): void; width?: number; height?: number; drawer?: boolean }>) {
  const app = useApp(), id = `overlay-${++sequence}`, back = Symbol(id);
  const [entered, setEntered] = createSignal(false);
  const width = () => props.width || (props.drawer ? 720 : 960), height = () => props.height || (props.drawer ? 900 : 650);
  const scale = () => Math.min(1, (1920 * (1 - 2 * app.data.fit.horizontal / 100)) / width(), (1080 * (1 - 2 * app.data.fit.vertical / 100)) / height());
  const leave = enterScope(id);
  app.backHandlers.set(back, () => { props.close(); return true; });
  let role: HTMLDivElement | undefined;
  onMount(() => {
    role = document.createElement('div'); role.setAttribute('role', 'dialog'); role.setAttribute('aria-modal', 'true'); role.setAttribute('aria-label', props.title);
    role.dataset.overlay = id; document.getElementById('semantics')!.appendChild(role);
    document.querySelectorAll(`[data-scope="${id}"]`).forEach(control => role!.appendChild(control));
    announce(props.title); queueMicrotask(() => setEntered(true));
  });
  onCleanup(() => { app.backHandlers.delete(back); role?.remove(); leave(); });
  return <Portal><FocusScope.Provider value={id}>
    <view width={1920} height={1080} zIndex={20 + sequence} onUp={() => true} onDown={() => true} onLeft={() => true} onRight={() => true}>
      <view skipFocus width={1920} height={1080} zIndex={1} color={props.drawer ? 0x02070d88 : 0x02070dc9} />
      <view x={props.drawer ? 1920 - 1920 * app.data.fit.horizontal / 100 - width() * scale() + (entered() || reducedMotion() ? 0 : 32) : (1920 - width() * scale()) / 2}
        y={(1080 - height() * scale()) / 2} width={width()} height={height()} alpha={entered() ? 1 : 0.1} pivot={0}
        zIndex={2} scale={scale() * (entered() || reducedMotion() ? 1 : 0.97)} transition={{ alpha: motion.focus, scale: motion.focus, x: motion.slide }}>
        <Glass width={width()} height={height()} radius={32} solid />
        <view x={40} y={36} width={width() - 80} height={height() - 72}>{props.children}</view>
      </view>
    </view>
  </FocusScope.Provider></Portal>;
}
