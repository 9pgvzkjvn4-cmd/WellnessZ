import { ApiError, SessionExpiredError } from '../auth/AuthClient';

/** User-facing error text; never echoes server payloads beyond RFC 9457 title. */
export function describeError(e: unknown): string {
  if (e instanceof SessionExpiredError) return 'Your session has ended. Please sign in again.';
  if (e instanceof ApiError) {
    if (e.status === 403) return 'You do not have permission to do this.';
    if (e.status === 404) return 'Not found.';
    if (e.status >= 500) return 'The service is temporarily unavailable. Please try again later.';
    return e.problem?.title ?? 'The request could not be completed.';
  }
  return 'Something went wrong. Please check your connection and try again.';
}

export function humanise(code: string): string {
  const s = code.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function formatDate(iso?: string | null): string {
  if (!iso) return '–';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '–' : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

/** Calendar date (`YYYY-MM-DD` or timestamp) without time; date-only values are not shifted by the time zone. */
export function formatDay(iso?: string | null): string {
  if (!iso) return '–';
  const dateOnly = /^\d{4}-\d{2}-\d{2}$/.test(iso);
  const d = new Date(dateOnly ? `${iso}T00:00:00Z` : iso);
  return Number.isNaN(d.getTime()) ? '–' : d.toLocaleDateString(undefined, { dateStyle: 'medium', ...(dateOnly ? { timeZone: 'UTC' } : {}) });
}

/** Safe DOM id fragment for a server id. */
export const idPart = (id: string) => id.replace(/[^A-Za-z0-9_-]/g, '-');
