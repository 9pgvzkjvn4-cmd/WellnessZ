/**
 * Money helpers (contract convention: `{amount: "1234.50", currency: "GBP"}`, decimal string, ISO 4217).
 * Arithmetic is done in integer minor units (2 decimals) so sums never pick up floating-point noise.
 */
import type { Tone } from './absence';

export interface Money {
  amount: string;
  currency: string;
}

export const DEFAULT_CURRENCY = 'GBP';
export const CURRENCIES = ['GBP', 'EUR', 'USD', 'AED', 'SGD', 'HKD'] as const;

const DECIMAL = /^-?\d+(\.\d+)?$/;

/** Accept a Money object, a decimal string or a number (roll-up cells may be either); anything else → undefined. */
export function asMoney(v: unknown, fallbackCurrency: string = DEFAULT_CURRENCY): Money | undefined {
  if (v === null || v === undefined) return undefined;
  if (typeof v === 'number') return Number.isFinite(v) ? { amount: v.toFixed(2), currency: fallbackCurrency } : undefined;
  if (typeof v === 'string') return DECIMAL.test(v.trim()) ? { amount: v.trim(), currency: fallbackCurrency } : undefined;
  if (typeof v === 'object') {
    const o = v as Record<string, unknown>;
    const amount = typeof o.amount === 'number' ? o.amount.toFixed(2) : typeof o.amount === 'string' ? o.amount.trim() : undefined;
    if (!amount || !DECIMAL.test(amount)) return undefined;
    return { amount, currency: typeof o.currency === 'string' && o.currency ? o.currency : fallbackCurrency };
  }
  return undefined;
}

/** Decimal string → integer minor units (rounded half away from zero at 2 decimals). */
export function toMinor(amount: string): number {
  const neg = amount.startsWith('-');
  const [int = '0', frac = ''] = amount.replace(/^-/, '').split('.');
  const padded = (frac + '000').slice(0, 3);
  let minor = Number(int) * 100 + Number(padded.slice(0, 2));
  if (Number(padded[2]) >= 5) minor += 1;
  return neg ? -minor : minor;
}

/** Integer minor units → decimal string with 2 decimals. */
export function fromMinor(minor: number): string {
  const neg = minor < 0;
  const abs = Math.abs(Math.round(minor));
  return `${neg ? '-' : ''}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

export const moneyNumber = (m: Money): number => toMinor(m.amount) / 100;

/**
 * Display, e.g. `£1,234.50`. Fixed en-GB grouping so screens and tests agree; unknown currency codes fall back to
 * `1,234.50 XYZ`. Non-money → '–'.
 */
export function formatMoney(v: unknown, fallbackCurrency: string = DEFAULT_CURRENCY): string {
  const m = asMoney(v, fallbackCurrency);
  if (!m) return '–';
  const n = moneyNumber(m);
  try {
    return new Intl.NumberFormat('en-GB', { style: 'currency', currency: m.currency, minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n);
  } catch {
    return `${new Intl.NumberFormat('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n)} ${m.currency}`;
  }
}

/**
 * User input → normalised decimal string ("1,234.5" → "1234.50", "£ 80" → "80.00"). Currency symbols, spaces and
 * thousands separators are ignored; at most 2 decimals; negative or empty → undefined.
 */
export function parseAmount(input: string): string | undefined {
  const s = input.replace(/[£$€\s]/g, '').replace(/,(?=\d{3}(\D|$))/g, '');
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return undefined;
  return fromMinor(toMinor(s));
}

/** Sum of same-currency amounts (undefined when the list is empty or currencies differ). */
export function sumMoney(list: readonly Money[]): Money | undefined {
  if (list.length === 0) return undefined;
  const currency = list[0]!.currency;
  if (list.some((m) => m.currency !== currency)) return undefined;
  return { amount: fromMinor(list.reduce((s, m) => s + toMinor(m.amount), 0)), currency };
}

export interface InvoiceVariance {
  diff: Money;
  pct: number;
  tone: Tone;
  label: string;
  withinTolerance: boolean;
}

/**
 * Invoice vs approved pre-authorisation (contract §3.4: tolerance `treatment.invoice.tolerance-pct`, default 5 %).
 * Over tolerance → red "12.0 % over approval"; within → green; under approval → muted. Different currencies → undefined.
 */
export function invoiceVariance(invoice: unknown, approved: unknown, tolerancePct = 5): InvoiceVariance | undefined {
  const inv = asMoney(invoice);
  const ap = asMoney(approved);
  if (!inv || !ap || inv.currency !== ap.currency) return undefined;
  const apMinor = toMinor(ap.amount);
  if (apMinor <= 0) return undefined;
  const diffMinor = toMinor(inv.amount) - apMinor;
  const pct = Math.round((diffMinor / apMinor) * 1000) / 10;
  const diff = { amount: fromMinor(diffMinor), currency: inv.currency };
  const withinTolerance = Math.abs(pct) <= tolerancePct;
  if (diffMinor === 0) return { diff, pct: 0, tone: 'green', label: 'Matches approval', withinTolerance: true };
  if (diffMinor > 0) {
    return { diff, pct, tone: withinTolerance ? 'green' : 'red', label: `${formatMoney(diff)} (${pct.toFixed(1)} %) over approval${withinTolerance ? ' – within tolerance' : ''}`, withinTolerance };
  }
  return { diff, pct, tone: 'muted', label: `${formatMoney({ amount: fromMinor(-diffMinor), currency: inv.currency })} (${Math.abs(pct).toFixed(1)} %) under approval`, withinTolerance };
}

/** Server-supplied variance (Money, decimal string or percentage number) → short label. */
export function describeServerVariance(v: unknown, currency?: string): string | undefined {
  if (v === null || v === undefined || v === '') return undefined;
  if (typeof v === 'number') return `${v > 0 ? '+' : ''}${v.toFixed(1)} %`;
  const m = asMoney(v, currency);
  if (!m) return undefined;
  const minor = toMinor(m.amount);
  return `${minor > 0 ? '+' : ''}${formatMoney(m)}`;
}
