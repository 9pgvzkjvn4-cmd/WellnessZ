import { BFF_API_PREFIX } from '../auth/bffAuthClient';
import type { QueueCode, QueueFilters } from './types';

export type QueryValue = string | number | boolean | null | undefined;

/**
 * Build `?a=1&b=x` from a params object. Empty strings, null and undefined are omitted;
 * keys keep their insertion order; values are URI-encoded. Returns '' when nothing is left.
 */
export function buildQuery(params: Record<string, QueryValue>): string {
  const parts: string[] = [];
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

/** Encode a single path segment (ids, codes, usernames). */
export const seg = (v: string | number): string => encodeURIComponent(String(v));

/** Direct browser link through the BFF (cookie auth, same origin) – used for downloads. */
export function bffLink(path: string): string {
  return `${BFF_API_PREFIX}${path}`;
}

/** E01-BL-004 CSV export: `GET /intake/reconciliation/export?from&to` (text/csv). */
export function reconciliationExportUrl(range: { from?: string; to?: string }): string {
  return bffLink(`/intake/reconciliation/export${buildQuery({ from: range.from, to: range.to })}`);
}

/** Audited binary download `GET /cases/{id}/attachments/{attId}`. */
export function attachmentUrl(caseId: string, attachmentId: string): string {
  return bffLink(`/cases/${seg(caseId)}/attachments/${seg(attachmentId)}`);
}

/** Only YYYY-MM-DD dates are accepted for report ranges. */
export function isIsoDate(v: string | undefined): boolean {
  return !!v && /^\d{4}-\d{2}-\d{2}$/.test(v) && !Number.isNaN(new Date(`${v}T00:00:00Z`).getTime());
}

export const PAGE_SIZE = 50;

/** `GET /queues/{queue}?page&size&urgency&subsidiary&caseType&safetyCritical&q` (empty filters omitted). */
export function queuePath(queue: QueueCode, page: number, f: QueueFilters, size: number = PAGE_SIZE): string {
  return `/queues/${seg(queue)}${buildQuery({ page, size, urgency: f.urgency, subsidiary: f.subsidiary, caseType: f.caseType, safetyCritical: f.safetyCritical, q: f.q })}`;
}
