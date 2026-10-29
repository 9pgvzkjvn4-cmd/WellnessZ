import { ApiError, SessionExpiredError, type ProblemDetail } from '../auth/AuthClient';

export interface ErrorInfo {
  message: string;
  correlationId?: string;
  code?: string;
  status?: number;
}

/**
 * User-facing error: the problem `detail` (server-authored, safe for display to the authenticated user –
 * e.g. "Recordability must be classified before leaving INTAKE") plus the correlationId for support.
 */
export function errorInfo(e: unknown): ErrorInfo {
  if (e instanceof SessionExpiredError) return { message: 'Your session has ended. Please sign in again.' };
  if (e instanceof ApiError) {
    const p: ProblemDetail = e.problem ?? {};
    const fallback =
      e.status === 403
        ? 'You do not have permission to do this.'
        : e.status === 404
          ? 'Not found.'
          : e.status === 409
            ? 'This change conflicts with the current state of the record.'
            : e.status === 422
              ? 'The request was rejected by a business rule.'
              : e.status >= 500
                ? 'The service is temporarily unavailable. Please try again later.'
                : 'The request could not be completed.';
    return { message: p.detail ?? p.title ?? fallback, correlationId: p.correlationId, code: p.code, status: e.status };
  }
  return { message: 'Something went wrong. Please check your connection and try again.' };
}

export function describeError(e: unknown): string {
  const i = errorInfo(e);
  return i.correlationId ? `${i.message} (reference ${i.correlationId})` : i.message;
}

export function humanise(code: string | null | undefined): string {
  if (!code) return '–';
  const s = code.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function formatDate(iso?: string | null): string {
  if (!iso) return '–';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '–' : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

export function formatDay(iso?: string | null): string {
  if (!iso) return '–';
  const d = new Date(iso.length === 10 ? `${iso}T00:00:00` : iso);
  return Number.isNaN(d.getTime()) ? '–' : d.toLocaleDateString(undefined, { dateStyle: 'medium' });
}

/** `<input type="datetime-local">` value → ISO-8601 UTC (or undefined). */
export function localToIso(v: string): string | undefined {
  if (!v) return undefined;
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

/** Date → `<input type="datetime-local">` value in local time. */
export function toLocalInput(d: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function isoDay(d: Date): string {
  return d.toISOString().slice(0, 10);
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}
