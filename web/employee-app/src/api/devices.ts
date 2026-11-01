/**
 * E33 employee-facing device API (docs/increment-06/API-Contract-E31-E34.md §14.6, §14.8;
 * `modules/wz-e33-device-capture/…/web/MeDeviceController.java`, `KioskController.java`). Paths relative to `/api/v1`.
 * Summaries are plain-language texts in the preferred language (blood-pressure category, BMI band) – never a diagnosis.
 */
import type { AuthClient } from '../auth/AuthClient';

export const WEARABLE_SCOPES = ['STEPS', 'SLEEP', 'HEART_RATE', 'ACTIVITY'] as const;
export type WearableScope = (typeof WEARABLE_SCOPES)[number];

export interface DeviceSummary {
  summaryId: string;
  category: string;
  measuredAt: string;
  /** Language of `plainLanguage` (the employee's preferred language). */
  language?: string | null;
  plainLanguage: string;
  educationLink?: string | null;
}

export interface DeviceConsent {
  id: string;
  consentId?: string | null;
  purpose: 'WEARABLE_DATA' | string;
  scopes: string[];
  campaignId?: string | null;
  status: string;
  grantedAt?: string | null;
  revokedAt?: string | null;
}

const enc = encodeURIComponent;

export function fetchDeviceSummaries(client: AuthClient): Promise<DeviceSummary[]> {
  return client.api<DeviceSummary[]>('/me/device-summaries');
}
export function fetchDeviceConsents(client: AuthClient): Promise<DeviceConsent[]> {
  return client.api<DeviceConsent[]>('/me/device-consents');
}
export function grantDeviceConsent(client: AuthClient, scopes: WearableScope[], campaignId?: string): Promise<DeviceConsent> {
  return client.api<DeviceConsent>('/me/device-consents', { method: 'POST', body: { purpose: 'WEARABLE_DATA', scopes, ...(campaignId ? { campaignId } : {}) } });
}
export function revokeDeviceConsent(client: AuthClient, id: string): Promise<DeviceConsent> {
  return client.api<DeviceConsent>(`/me/device-consents/${enc(id)}`, { method: 'DELETE' });
}

/** True for a consent that still lets data flow. */
export const isActiveConsent = (c: DeviceConsent): boolean => c.status === 'GRANTED' || c.status === 'ACTIVE';

// ---------------------------------------------------------------- kiosk (§14.6)

export interface KioskIdentity {
  kioskSessionId: string;
  firstName: string;
  language: string;
  consentRequired: boolean;
  consentNoticeVersion?: string | null;
}
export interface KioskConsentResult {
  kioskSessionId: string;
  state: string;
  consented: boolean;
}
export interface KioskSummary {
  kioskSessionId: string;
  language: string;
  items: { category: string; measuredAt: string; plainLanguage: string; educationLink?: string | null }[];
  state: string;
}

/**
 * The kiosk API (`/device-ingest/kiosk/**`) is machine traffic: it needs the kiosk's enrolled endpoint identity (mutual TLS +
 * endpoint token) and is never proxied by the BFF. The kiosk shell that runs this page on an enrolled KIOSK endpoint injects
 * a bridge as `window.wellnesszKiosk` that makes these calls with the kiosk's credentials. Without the bridge the page shows
 * that it only works on an enrolled kiosk. No personal data beyond the first name ever reaches the page. The bridge rejects
 * with an `ApiError` carrying the HTTP status (404 / 422 = badge not recognised).
 */
export interface KioskBridge {
  identify(badgeId: string): Promise<KioskIdentity>;
  consent(kioskSessionId: string, accepted: boolean, noticeVersion?: string | null): Promise<KioskConsentResult>;
  summary(kioskSessionId: string): Promise<KioskSummary>;
}

declare global {
  interface Window {
    wellnesszKiosk?: KioskBridge;
  }
}

export function kioskBridge(): KioskBridge | undefined {
  return typeof window === 'undefined' ? undefined : window.wellnesszKiosk;
}

/** Badge ids as the kiosk API accepts them (letters, digits, hyphen; ≤ 64). */
export function normaliseBadge(raw: string): string | undefined {
  const v = raw.trim().toUpperCase();
  return /^[A-Z0-9-]{1,64}$/.test(v) ? v : undefined;
}
