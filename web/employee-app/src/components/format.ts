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

export function formatDate(iso?: string | null, locale?: string): string {
  if (!iso) return '–';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '–' : d.toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' });
}

/** Calendar date (`YYYY-MM-DD` or ISO timestamp) without time. `YYYY-MM-DD` is shown as-is in local format, no TZ shift. */
export function formatDay(value?: string | null, locale?: string): string {
  if (!value) return '–';
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  const d = m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : new Date(value);
  return Number.isNaN(d.getTime()) ? '–' : d.toLocaleDateString(locale, { dateStyle: 'medium' });
}

/** Date and time in the device's current time zone, with the zone name (crew on layover). */
export function formatDateTimeZone(iso?: string | null, locale?: string): string {
  if (!iso) return '–';
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? '–'
    : d.toLocaleString(locale, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZoneName: 'short' });
}
