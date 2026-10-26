export type InputMode = 'text' | 'password' | 'pin' | 'integer' | 'time';
export interface InputRequest {
  label: string; value: string; mode: InputMode; maxLength?: number; autoSubmit?: boolean;
  validate?(value: string): string | undefined | Promise<string | undefined>;
  resolve(value: string | null): void;
}
export function editValue(value: string, key: string, start = value.length, end = start) {
  if (key === 'Clear') return { value: '', caret: 0 };
  const from = key === 'Delete' && start === end ? Math.max(0, start - 1) : start;
  const insert = key === 'Delete' ? '' : key === 'Space' ? ' ' : key;
  return { value: value.slice(0, from) + insert + value.slice(end), caret: from + insert.length };
}
export function pinDigit(value: string, key: string, repeat = false) {
  if (repeat) return value;
  if (key === 'Backspace' || key === 'Delete') return value.slice(0, -1);
  return /^\d$/.test(key) && value.length < 4 ? value + key : value;
}
