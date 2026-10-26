import { inputLabels } from './copy';

/** Browser equivalent of the native IME fallback, shared by live and fixture hosts. */
export function deviceInput() {
  let current: HTMLDialogElement | undefined;
  return {
    open(args: Record<string, unknown>) {
      if (current) return Promise.reject(new Error('Input already open'));
      return new Promise<string | null>(resolve => {
        const previous = document.activeElement as HTMLElement | null;
        const dialog = document.createElement('dialog'), form = document.createElement('form'), label = document.createElement('label'), input = document.createElement('input');
        current = dialog; form.method = 'dialog';
        dialog.setAttribute('aria-label', String(args.label));
        dialog.style.cssText = 'box-sizing:border-box;width:min(680px,90vw);max-height:calc(100vh - 32px);max-height:calc(100dvh - 32px);overflow:auto;padding:32px;border:1px solid #768ba466;border-radius:28px;background:#18212ef5;color:#f5f8ff;font:22px Roboto,Arial,sans-serif;box-shadow:0 24px 60px #0008';
        label.textContent = String(args.label); label.style.cssText = 'display:flex;flex-direction:column;gap:20px;margin-bottom:28px';
        input.type = args.secret ? 'password' : 'text'; input.value = String(args.value || ''); input.autocomplete = args.secret ? 'current-password' : 'off';
        input.inputMode = args.mode === 'integer' || args.mode === 'pin' ? 'numeric' : args.mode === 'time' ? 'decimal' : 'text';
        input.style.cssText = 'box-sizing:border-box;width:100%;min-width:0;padding:16px 20px;border:1px solid #768ba4;border-radius:16px;background:#080c14;color:#f5f8ff;font:inherit;caret-color:#b8e9ff';
        label.append(input); form.append(label);
        for (const [value, text] of [['ok', inputLabels.done], ['cancel', inputLabels.cancel]]) {
          const button = document.createElement('button'); button.type = 'submit'; button.value = value; button.textContent = text;
          button.style.cssText = `padding:14px 28px;margin-right:16px;border:0;border-radius:28px;background:${value === 'ok' ? '#f5f8ff' : '#334254'};color:${value === 'ok' ? '#0a1624' : '#f5f8ff'};font:inherit`; form.append(button);
        }
        dialog.append(form); dialog.addEventListener('keydown', event => event.stopPropagation());
        for (const type of ['click', 'mousedown', 'wheel']) dialog.addEventListener(type, event => event.stopPropagation());
        dialog.addEventListener('close', () => {
          const value = dialog.returnValue === 'ok' ? input.value : null;
          input.value = ''; dialog.remove(); current = undefined; previous?.focus(); resolve(value);
        }, { once: true });
        document.body.append(dialog); dialog.showModal(); input.focus();
      });
    },
    dispose() { current?.close('cancel'); },
  };
}
