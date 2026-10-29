import { expect, it, vi } from 'vitest';
import { ServiceError } from '../src/domain/models';
import { failedOperations, recoveryState, type ContentOperation, type RecoveryState } from '../src/ui/content-recovery';

const initial: RecoveryState = { key: 'movies', open: false, dismissed: false, failure: 'NETWORK' };
const operation = (id: string, failure = 'NETWORK'): ContentOperation => ({ id, error: new ServiceError(failure as ServiceError['failure']), retry: vi.fn() });

it('deduplicates failed operations, ignores cancellation, and prioritizes sign-in recovery', () => {
  const operations = [operation('latest'), operation('latest'), operation('best', 'CAPACITY'), { id: 'cancelled', error: new DOMException('Cancelled', 'AbortError'), retry: vi.fn() }];
  expect(failedOperations(operations).map(operation => operation.id)).toEqual(['latest', 'best']);
  expect(recoveryState(initial, 'movies', operations, false)).toMatchObject({ open: true, failure: 'CAPACITY' });
});

it('suppresses late sibling errors after dismissal and resets for a new screen key', () => {
  const dismissed = { ...initial, dismissed: true };
  expect(recoveryState(dismissed, 'movies', [operation('latest'), operation('best')], false).open).toBe(false);
  expect(recoveryState(dismissed, 'anime', [operation('anime')], false)).toMatchObject({ open: true, dismissed: false, key: 'anime' });
});

it('keeps recovery open while retrying, retains failures, and closes after success', () => {
  const open = recoveryState(initial, 'movies', [operation('latest')], false);
  expect(recoveryState(open, 'movies', [], true).open).toBe(true);
  expect(recoveryState(open, 'movies', [operation('latest', 'UNAVAILABLE')], false)).toMatchObject({ open: true, failure: 'UNAVAILABLE' });
  expect(recoveryState(open, 'movies', [], false).open).toBe(false);
});
