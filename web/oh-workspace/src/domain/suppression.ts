/**
 * k-anonymity rendering for intake dashboard / report cells (E01-BL-026: "small cells < k suppressed").
 * A cell is suppressed when the server marks it (null / missing / `{suppressed:true}`) or – as defence in
 * depth – when a positive count is below k. Suppressed cells render as "<k" and never reveal a number.
 */
export const DEFAULT_K = 5;

export function readK(body: unknown): number {
  const b = (body && typeof body === 'object' ? body : {}) as Record<string, unknown>;
  for (const key of ['minGroupSize', 'k', 'kAnonymity']) {
    const v = b[key];
    if (typeof v === 'number' && Number.isFinite(v) && v > 0) return v;
  }
  return DEFAULT_K;
}

export function cellCount(v: unknown): number | undefined {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (v && typeof v === 'object') {
    const o = v as Record<string, unknown>;
    if (o.suppressed === true) return undefined;
    if (typeof o.count === 'number') return o.count;
  }
  return undefined;
}

export function isSuppressed(v: unknown, k: number, rowSuppressed = false): boolean {
  if (rowSuppressed) return true;
  const n = cellCount(v);
  return n === undefined || (n > 0 && n < k);
}

export function cellText(v: unknown, k: number, rowSuppressed = false): string {
  if (isSuppressed(v, k, rowSuppressed)) return `<${k}`;
  return (cellCount(v) ?? 0).toLocaleString();
}
