import { ApiError } from '../auth/AuthClient';
import type { KioskIdentity } from '../api/devices';

/** Kiosk visit steps (contract §14.6): badge → (consent) → measure → summary → privacy screen; declining ends the visit. */
export type KioskStep = 'badge' | 'consent' | 'measure' | 'summary' | 'declined' | 'privacy';

export const KIOSK_IDLE_MS = 180_000;

/** After the badge is recognised: consent first when the kiosk consent is required, otherwise straight to measuring. */
export const afterIdentify = (who: KioskIdentity): KioskStep => (who.consentRequired ? 'consent' : 'measure');

/** Declining ends the visit without measuring or saving anything. */
export const afterConsent = (accepted: boolean): KioskStep => (accepted ? 'measure' : 'declined');

/** Steps that hold a person's data and therefore run the privacy timeout. */
export const holdsPersonData = (step: KioskStep): boolean => step === 'consent' || step === 'measure' || step === 'summary';

/**
 * Idle check: expired after `idleMs` without interaction; during the last `warnMs` the remaining whole seconds are returned
 * so the kiosk can show a countdown.
 */
export function idleCheck(lastActivity: number, now: number, idleMs = KIOSK_IDLE_MS, warnMs = 20_000): { expired: boolean; remaining?: number } {
  const left = idleMs - (now - lastActivity);
  if (left <= 0) return { expired: true };
  return { expired: false, remaining: left <= warnMs ? Math.ceil(left / 1000) : undefined };
}

/** An unknown badge (404 / 422) gets the "not recognised" message; anything else the generic one. */
export const identifyFailure = (e: unknown): 'notFound' | 'error' => (e instanceof ApiError && (e.status === 404 || e.status === 422) ? 'notFound' : 'error');
