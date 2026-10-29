import { createEffect, createSignal, onCleanup, Show } from 'solid-js';
import { useNavigate } from '@solidjs/router';
import { Column, Row } from '@solidtv/solid/primitives';
import { Button, palette, Status } from './components';
import { Overlay } from './Overlay';
import { failureMessages } from './copy';
import { failedOperations, recoveryState, signInFailures, type ContentOperation, type RecoveryState } from './content-recovery';

/** One recovery surface per active content screen; retries remain owned by their queries. */
export function ContentError(props: { screenKey: string; operations: ContentOperation[] }) {
  const navigate = useNavigate();
  const [state, setState] = createSignal<RecoveryState>({ key: props.screenKey, open: false, dismissed: false, failure: 'NETWORK' });
  const [retrying, setRetrying] = createSignal(false);
  let generation = 0;
  onCleanup(() => generation++);
  createEffect(() => {
    const key = props.screenKey;
    if (state().key !== key) { generation++; setRetrying(false); }
    const operations = props.operations, busy = retrying() || operations.some(operation => operation.fetching);
    setState(previous => {
      const next = recoveryState(previous, key, operations, busy);
      return JSON.stringify(next) === JSON.stringify(previous) ? previous : next;
    });
  });
  const close = () => setState(previous => ({ ...previous, open: false, dismissed: true }));
  const busy = () => retrying() || props.operations.some(operation => operation.error && operation.fetching);
  async function retry() {
    if (busy()) return;
    const token = generation, operations = failedOperations(props.operations);
    setRetrying(true);
    await Promise.allSettled(operations.map(operation => Promise.resolve().then(() => operation.retry())));
    if (generation === token) setRetrying(false);
  }
  return <>
    <Show when={state().dismissed && failedOperations(props.operations).length}>
      <Button label="Retry loading" width={270} action={() => setState(previous => ({ ...previous, dismissed: false, open: true }))} />
    </Show>
    <Show when={state().open}><Overlay title="Unable to load content" close={close} width={1080} height={470}>
      <Column width={1000} height={398} gap={30} scroll="none">
        <text skipFocus width={1000} height={64} fontSize={42} fontFamily="Roboto700">Unable to load content</text>
        <text skipFocus width={1000} height={120} maxLines={3} fontSize={28} color={palette.muted}>{failureMessages[state().failure]}</text>
        <Show when={busy()}><Status width={1000} message="Retrying…" /></Show>
        <Row width={1000} height={68} gap={20}>
          <Show when={signInFailures.includes(state().failure)}><Button label="Sign in" primary autofocus width={260} disabled={busy()} action={() => { close(); navigate('/settings/account'); }} /></Show>
          <Button label="Try again" primary={!signInFailures.includes(state().failure)} autofocus={!signInFailures.includes(state().failure)} width={260} disabled={busy()} action={() => void retry()} />
          <Button label="Close" width={220} action={close} />
        </Row>
      </Column>
    </Overlay></Show>
  </>;
}
