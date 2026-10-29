/**
 * Formatting and parsing helpers: codes, dates / date-times (ISO-8601 UTC on the wire, `YYYY-MM-DD` dates) and money
 * (`{amount: "1234.50", currency: "GBP"}` – decimal string, ISO 4217).
 */
import { S } from '../strings';

export function humanise(code: string): string {
  const s = code.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/* ------------------------------------------------------------------ dates */

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/** Timestamp in the viewer's locale and time zone, e.g. "29 Oct 2026, 14:30". */
export function formatDateTime(iso?: string | null): string {
  if (!iso) return S.app.noValue;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? S.app.noValue : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

/** Calendar date (`YYYY-MM-DD` or timestamp) without time; date-only values are not shifted by the time zone. */
export function formatDay(iso?: string | null): string {
  if (!iso) return S.app.noValue;
  const dateOnly = DATE_ONLY.test(iso);
  const d = new Date(dateOnly ? `${iso}T00:00:00Z` : iso);
  return Number.isNaN(d.getTime()) ? S.app.noValue : d.toLocaleDateString(undefined, { dateStyle: 'medium', ...(dateOnly ? { timeZone: 'UTC' } : {}) });
}

const pad = (n: number) => String(n).padStart(2, '0');

/** Local calendar date `YYYY-MM-DD` of `now` (for `<input type="date">` min / max). */
export function localDate(now: Date = new Date()): string {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** Is `s` a real calendar date in `YYYY-MM-DD` form (rejects 2026-02-30)? */
export function isValidDate(s: string): boolean {
  if (!DATE_ONLY.test(s)) return false;
  const d = new Date(`${s}T00:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === s;
}

const LOCAL_DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/;

/**
 * `<input type="datetime-local">` value (viewer's local time, `YYYY-MM-DDTHH:mm`) → ISO-8601 UTC instant
 * (`2026-11-03T09:30:00.000Z`). Undefined when the value is not a real local date-time.
 */
export function localInputToIso(value: string): string | undefined {
  const m = LOCAL_DATE_TIME.exec(value.trim());
  if (!m) return undefined;
  const [y, mo, d, h, mi, s] = m.slice(1).map((x) => (x === undefined ? 0 : Number(x))) as [number, number, number, number, number, number];
  const date = new Date(y, mo - 1, d, h, mi, s);
  // reject overflow (2026-02-30 → 2 March) and invalid hours
  if (Number.isNaN(date.getTime()) || date.getFullYear() !== y || date.getMonth() !== mo - 1 || date.getDate() !== d || date.getHours() !== h || date.getMinutes() !== mi) {
    return undefined;
  }
  return date.toISOString();
}

/** ISO instant → `<input type="datetime-local">` value in the viewer's local time ('' when invalid / missing). */
export function isoToLocalInput(iso?: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return `${localDate(d)}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/* ------------------------------------------------------------------ money */

export interface Money {
  /** Decimal string, e.g. "1234.50". */
  amount: string;
  /** ISO 4217, e.g. "GBP". */
  currency: string;
}

/** Currencies offered in forms (WellnessZ hubs: UK, UAE, US, Singapore, Hong Kong, EU). Any ISO code from the server is displayed. */
export const CURRENCIES = ['GBP', 'EUR', 'USD', 'AED', 'SGD', 'HKD'] as const;

const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

/** Tolerant reader for a money value from the API (amount may arrive as a string or number). */
export function toMoney(v: unknown): Money | undefined {
  if (!v || typeof v !== 'object') return undefined;
  const r = v as Record<string, unknown>;
  const amount = typeof r.amount === 'string' ? r.amount.trim() : typeof r.amount === 'number' && Number.isFinite(r.amount) ? String(r.amount) : undefined;
  const currency = typeof r.currency === 'string' && /^[A-Z]{3}$/.test(r.currency) ? r.currency : undefined;
  if (!amount || !currency || !/^-?\d+(\.\d+)?$/.test(amount)) return undefined;
  return { amount, currency };
}

/** "£1,234.50" style formatting in the viewer's locale; falls back to "GBP 1234.50" for unknown currencies. */
export function formatMoney(m?: Money | null, locale?: string): string {
  if (!m) return S.app.noValue;
  const n = Number(m.amount);
  if (!Number.isFinite(n)) return S.app.noValue;
  try {
    return new Intl.NumberFormat(locale, { style: 'currency', currency: m.currency }).format(n);
  } catch {
    return `${m.currency} ${m.amount}`;
  }
}

/**
 * Form input → normalised decimal string with two places ("1250" → "1250.00", "12.5" → "12.50"); accepts a
 * thousands separator comma ("1,250.00"). Undefined unless it is a positive amount with at most two decimals.
 */
export function parseAmount(input: string): string | undefined {
  const s = input.trim().replace(/,(?=\d{3}(\D|$))/g, '');
  if (!AMOUNT.test(s)) return undefined;
  const [whole = '0', frac = ''] = s.split('.');
  const normalised = `${String(BigInt(whole))}.${frac.padEnd(2, '0')}`;
  return toCents(normalised) > 0n ? normalised : undefined;
}

/** Exact comparison of two decimal strings (no floating point): negative, zero or positive. */
export function compareAmounts(a: string, b: string): number {
  const d = toCents(a) - toCents(b);
  return d < 0n ? -1 : d > 0n ? 1 : 0;
}

function toCents(s: string): bigint {
  const neg = s.startsWith('-');
  const [whole = '0', frac = ''] = s.replace(/^-/, '').split('.');
  const cents = BigInt(whole) * 100n + BigInt((frac + '00').slice(0, 2));
  return neg ? -cents : cents;
}

/* ------------------------------------------------------------------ misc */

/** Safe DOM id fragment for a server id. */
export const idPart = (id: string) => id.replace(/[^A-Za-z0-9_-]/g, '-');

/** File name for a document download (`DOC-2026-000123.pdf`). */
export const downloadName = (base: string, ext = 'pdf') => `${base.replace(/[^A-Za-z0-9_-]/g, '_')}.${ext}`;
