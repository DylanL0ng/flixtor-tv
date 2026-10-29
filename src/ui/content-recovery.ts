import { failureOf, type Failure } from '../domain/models';

export interface ContentOperation {
  id: string; error?: unknown; fetching?: boolean; retry(): unknown | Promise<unknown>;
}
export const signInFailures: Failure[] = ['CAPACITY', 'SIGNED_OUT', 'EXPIRED', 'VIP'];
export function failedOperations(operations: ContentOperation[]) {
  return [...new Map(operations.filter(operation => operation.error && (operation.error as { name?: string }).name !== 'AbortError')
    .map(operation => [operation.id, operation])).values()];
}
export interface RecoveryState { key: string; open: boolean; dismissed: boolean; failure: Failure }
export function recoveryState(previous: RecoveryState, key: string, operations: ContentOperation[], busy: boolean): RecoveryState {
  const state = previous.key === key ? previous : { key, open: false, dismissed: false, failure: 'NETWORK' as const };
  const failures = failedOperations(operations);
  const failure = failures.find(operation => signInFailures.includes(failureOf(operation.error))) || failures[0];
  return { ...state, failure: failure ? failureOf(failure.error) : state.failure,
    open: !state.dismissed && (!!failures.length || busy && state.open) };
}
