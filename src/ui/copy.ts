import type { Failure } from '../domain/models';
export const inputLabels = { done: 'Done', cancel: 'Cancel' };
export const failureMessages: Record<Failure, string> = {
  NETWORK: 'Connection failed. Check your network and try again.',
  ACCESS: 'The service refused this request. Try again later.',
  VIP: 'This title requires an active VIP account. Check your account in Settings.',
  EXPIRED: 'Your session or membership expired. Sign in again.',
  BLOCKED: 'This account is blocked by the service. Check your account with the provider.',
  CHANGED: 'The service response changed. Check for an app update.',
  MEDIA: 'Playback stopped. Try again to request a fresh stream.',
  SIGNED_OUT: 'Sign in to continue watching.',
  UNAVAILABLE: 'The service is temporarily unavailable. Try again later.',
  UNSUPPORTED: 'This source or format is not supported.',
  CAPACITY: 'Free-access servers are full. Sign in or try again later.',
};
