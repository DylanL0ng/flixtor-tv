import { createEffect, createSignal, For, onCleanup, onMount, Show, useContext } from 'solid-js';
import { type ElementNode } from '@solidtv/solid';
import { Column, Row } from '@solidtv/solid/primitives';
import { useApp } from '../app/context';
import { Button, palette, Status } from './components';
import { Overlay } from './Overlay';
import { activeScope, announce, FocusScope, focusElement, nodeRect, registerFocus, restoreFocus, trackFrame } from './focus';
import { editValue, pinDigit, type InputMode, type InputRequest } from './input-state';

export interface FieldHandle { focus(): void; edit(key: string): void }
export function TextField(props: { label: string; value: string; change(value: string): void; width: number; mode?: InputMode; maxLength?: number; id: string; down?(): void; submit?(): void; handle?(handle: FieldHandle): void }) {
  let node: ElementNode | undefined, input: HTMLInputElement | undefined;
  const scope = useContext(FocusScope);
  const [focused, setFocused] = createSignal(false);
  registerFocus(() => props.id, () => node);
  onMount(() => {
    input = document.createElement('input'); input.setAttribute('aria-label', props.label); input.dataset.scope = scope;
    input.type = props.mode === 'password' ? 'password' : 'text'; input.inputMode = props.mode === 'integer' ? 'numeric' : props.mode === 'time' ? 'decimal' : 'text';
    input.autocomplete = 'off'; input.spellcheck = false; input.maxLength = props.maxLength || 256; input.value = props.value;
    input.style.cssText = 'position:fixed;box-sizing:border-box;z-index:10;background:transparent;border:0;outline:0;color:#f5f8ff;padding:0 22px;font-family:Roboto,Arial,sans-serif;caret-color:#b8e9ff;';
    input.onfocus = () => { setFocused(true); focusElement(node); };
    input.onblur = () => setFocused(false);
    input.oninput = event => { if (!(event instanceof InputEvent) || !event.isComposing) props.change(input!.value); };
    input.addEventListener('compositionend', () => props.change(input!.value));
    input.onkeydown = event => {
      if (event.isComposing) return;
      if (event.key === 'ArrowDown') { event.preventDefault(); input!.blur(); props.down?.(); }
      if (event.key === 'Enter') { event.preventDefault(); props.submit?.(); }
      if (!['Escape', 'Tab'].includes(event.key)) event.stopPropagation();
    };
    (document.querySelector(`[data-overlay="${scope}"]`) || document.getElementById('semantics'))!.appendChild(input);
    props.handle?.({ focus: () => input?.focus(), edit(key) {
      const edit = editValue(input!.value, key, input!.selectionStart ?? input!.value.length, input!.selectionEnd ?? input!.value.length);
      const value = edit.value.slice(0, props.maxLength || 256); input!.value = value;
      input!.setSelectionRange(Math.min(edit.caret, value.length), Math.min(edit.caret, value.length)); props.change(value);
    } });
  });
  createEffect(() => { const value = props.value, disabled = activeScope() !== scope; if (input) { if (input.value !== value) input.value = value; input.disabled = disabled; } });
  trackFrame(() => {
    if (!input) return; const rect = nodeRect(node);
    Object.assign(input.style, { left: `${rect.x}px`, top: `${rect.y}px`, width: `${rect.width}px`, height: `${rect.height}px`, fontSize: `${28 * rect.height / 76}px` });
    input.hidden = !rect.visible;
  });
  onCleanup(() => { if (input) input.value = ''; input?.remove(); });
  return <view ref={node} width={props.width} height={76} color={palette.panelSolid} borderRadius={20} border={{ width: focused() ? 2 : 1, color: focused() ? palette.accent : palette.line }}
    onEnter={() => input?.focus()} onDown={() => { props.down?.(); return true; }} onFocus={() => setFocused(true)} onBlur={() => setFocused(false)} />;
}

export function Keyboard(props: { edit(key: string): void; done(): void; width?: number; numeric?: boolean; prefix?: string; autofocus?: boolean; cancel?(): void; right?(): boolean; keyFocus?(id: string): void; pending?: boolean }) {
  const [symbols, setSymbols] = createSignal(!!props.numeric);
  const letters = () => symbols() ? '1234567890@.-_!?&()/+:'.split('') : 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');
  const columns = () => props.numeric ? 3 : 6;
  const width = () => props.width || 600, keyWidth = () => (width() - (columns() - 1) * 10) / columns();
  const prefix = () => props.prefix || 'keyboard';
  const keys = () => props.numeric ? '123456789'.split('').concat(['Delete', '0', props.cancel ? 'Cancel' : '.']) : letters();
  const rows = () => Array.from({ length: Math.ceil(keys().length / columns()) }, (_, i) => keys().slice(i * columns(), (i + 1) * columns()));
  return <Column width={width()} height={props.numeric ? 320 : 540} gap={10} autofocus={props.autofocus} scroll="none">
    <For each={rows()}>{(row, r) => <Row width={width()} height={68} gap={10} onRight={function (this: ElementNode) {
      if (this.selected !== this.children.length - 1) return false;
      return props.right?.() || false;
    }}>
      <For each={row}>{(key, c) => <Button id={`${prefix()}-${r()}-${c()}`} label={key} width={keyWidth()} height={68} autofocus={props.autofocus && r() === 0 && c() === 0}
        onFocus={() => props.keyFocus?.(`${prefix()}-${r()}-${c()}`)} disabled={props.pending && key !== 'Cancel'} action={() => key === 'Cancel' ? props.cancel?.() : props.edit(key)} />}</For>
    </Row>}</For>
    <Show when={!props.numeric}>
      <Row width={width()} height={68} gap={10}>
        <Button label="Space" width={keyWidth() * 2 + 10} action={() => props.edit('Space')} />
        <Button label="Delete" width={keyWidth() * 2 + 10} action={() => props.edit('Delete')} />
        <Button label={symbols() ? 'ABC' : '123'} width={keyWidth() * 2 + 10} action={() => setSymbols(!symbols())} />
      </Row>
      <Row width={width()} height={68} gap={10}>
        <Button label="Clear" width={(width() - 10) / 2} action={() => props.edit('Clear')} />
        <Button label="Done" width={(width() - 10) / 2} primary action={props.done} />
      </Row>
    </Show>
  </Column>;
}

export function InputDialog(props: { request: InputRequest }) {
  const app = useApp(), request = props.request;
  const [value, setValue] = createSignal(request.value), [busy, setBusy] = createSignal(false), [error, setError] = createSignal('');
  let alive = true, field: FieldHandle | undefined;
  const pin = request.mode === 'pin';
  createEffect(() => { if (pin) announce(error() || (busy() ? 'Checking PIN…' : `${value().length} of 4 digits entered.`)); });
  const finish = (result: string | null) => { if (alive) { setValue(''); request.resolve(result); } };
  async function submit() {
    if (busy() || pin && value().length !== 4) return;
    setBusy(true); setError('');
    try {
      const problem = await request.validate?.(value());
      if (!alive) return;
      if (problem) { setError(problem); if (pin) setValue(''); }
      else finish(value());
    } catch { if (alive) setError('Unable to check this value. Try again.'); }
    finally { if (alive) setBusy(false); }
  }
  const edit = (key: string) => {
    if (busy()) return;
    setError('');
    if (pin) { const next = pinDigit(value(), key); setValue(next); if (next.length === 4 && request.autoSubmit) void submit(); }
    else if (field) field.edit(key); else setValue(editValue(value(), key).value);
  };
  const keydown = (event: KeyboardEvent) => {
    if (document.querySelector('dialog[open]') || activeScope() === 'screen' || event.isComposing) return;
    if (pin && (/^\d$/.test(event.key) || event.key === 'Backspace')) {
      event.preventDefault(); event.stopImmediatePropagation(); if (!busy() && !event.repeat) edit(event.key === 'Backspace' ? 'Delete' : event.key);
    } else if (!pin && !(event.target instanceof HTMLInputElement) && !event.ctrlKey && !event.metaKey && (event.key.length === 1 || event.key === 'Backspace')) {
      event.preventDefault(); event.stopImmediatePropagation(); edit(event.key === 'Backspace' ? 'Delete' : event.key);
    }
  };
  window.addEventListener('keydown', keydown, true);
  onCleanup(() => { alive = false; setValue(''); window.removeEventListener('keydown', keydown, true); });
  return <Overlay title={request.label} close={() => finish(null)} width={pin ? 650 : 1150} height={pin ? 860 : 900}>
    <Column width={pin ? 570 : 1070} height={pin ? 788 : 828} gap={24} scroll="none">
      <text skipFocus width={pin ? 570 : 1070} height={66} fontSize={40} fontFamily="Roboto700" maxLines={2}>{request.label}</text>
      <Show when={pin} fallback={<TextField id="input-field" label={request.label} value={value()} change={setValue} mode={request.mode} maxLength={request.maxLength} width={1070} handle={handle => field = handle} down={() => restoreFocus('input-0-0')} submit={() => void submit()} />}>
        <view skipFocus width={570} height={70}>
          <For each={[0, 1, 2, 3]}>{i => <view x={170 + i * 60} y={12} width={22} height={22} borderRadius={11} color={value().length > i ? palette.white : 0xffffff22} />}</For>
        </view>
      </Show>
      <Row width={pin ? 570 : 1070} height={pin ? 320 : 540} gap={40}>
        <Keyboard width={pin ? 570 : 650} numeric={pin || request.mode === 'integer' || request.mode === 'time'} prefix="input" autofocus edit={edit} done={() => void submit()} cancel={pin ? () => finish(null) : undefined} pending={busy()} />
        <Show when={!pin}><Column width={380} height={540} gap={20} scroll="none">
          <Button label="Save" width={340} primary disabled={busy()} action={() => void submit()} />
          <Button label="Cancel" width={340} action={() => finish(null)} />
          <Button label="Device keyboard" width={340} action={() => app.run(async () => {
            const next = await app.platform.call<string | null>('input', { label: request.label, value: value(), secret: request.mode === 'password', mode: request.mode });
            if (alive && next !== null) { setValue(next); field?.focus(); }
          })} />
          <Show when={error() || busy()}><Status width={360} message={busy() ? 'Checking…' : error()} error={!!error()} /></Show>
        </Column></Show>
      </Row>
      <Show when={pin && (error() || busy())}><Status width={570} message={busy() ? 'Checking…' : error()} error={!!error()} /></Show>
      <Show when={pin && !request.autoSubmit}><Button label="Save PIN" primary disabled={busy() || value().length !== 4} action={() => void submit()} /></Show>
    </Column>
  </Overlay>;
}
