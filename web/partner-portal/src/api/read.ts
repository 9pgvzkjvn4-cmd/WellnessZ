/** Tolerant field readers used by the API mappers (whitelisting projections – unknown fields are dropped). */
import { S } from '../strings';

export type Rec = Record<string, unknown>;

export const rec = (v: unknown): Rec | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Rec) : undefined);
export const str = (v: unknown): string | undefined => (typeof v === 'string' && v !== '' ? v : typeof v === 'number' && Number.isFinite(v) ? String(v) : undefined);
export const num = (v: unknown): number | undefined => (typeof v === 'number' && Number.isFinite(v) ? v : typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v)) ? Number(v) : undefined);
export const bool = (v: unknown): boolean => v === true || v === 'true';
export const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

/** Map a list, dropping entries the mapper rejects. */
export function mapList<T>(v: unknown, fn: (r: Rec) => T | undefined): T[] {
  return list(v)
    .map((x) => {
      const r = rec(x);
      return r ? fn(r) : undefined;
    })
    .filter((x): x is T => x !== undefined);
}

/** Display value of a structured data-set entry: primitives as text, lists joined, nested objects omitted. */
export function displayValue(v: unknown): string | undefined {
  if (typeof v === 'string') return v;
  if (typeof v === 'number') return String(v);
  if (typeof v === 'boolean') return v ? S.claims.yes : S.claims.no;
  if (Array.isArray(v)) {
    const parts = v.map(displayValue).filter((x): x is string => x !== undefined);
    return parts.length ? parts.join(', ') : undefined;
  }
  return undefined;
}
