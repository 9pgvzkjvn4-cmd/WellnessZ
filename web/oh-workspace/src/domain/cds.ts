import type { CdsAlert, Override } from '../api/record';
import { ApiError } from '../auth/AuthClient';

/**
 * Clinical decision support (contract E03 §4.5): a `409 code=cds_alert` carries `{alerts:[CdsAlert]}`; the request is
 * accepted once it carries `overrides:[{alertCode, reason}]` for **every** alert (a reason is mandatory).
 */
export function cdsAlertsOf(e: unknown): CdsAlert[] | undefined {
  if (!(e instanceof ApiError) || e.status !== 409 || e.problem?.code !== 'cds_alert') return undefined;
  return e.problem.alerts ?? [];
}

/** Alert codes that still lack a non-blank override reason. */
export function missingOverrideReasons(alerts: readonly CdsAlert[], reasons: Readonly<Record<string, string>>): string[] {
  return alerts.filter((a) => !(reasons[a.code] ?? '').trim()).map((a) => a.code);
}

/** One override per alert, reasons trimmed. Throws if any reason is missing (callers validate first). */
export function toOverrides(alerts: readonly CdsAlert[], reasons: Readonly<Record<string, string>>): Override[] {
  const missing = missingOverrideReasons(alerts, reasons);
  if (missing.length) throw new Error(`Override reason missing for ${missing.join(', ')}`);
  return alerts.map((a) => ({ alertCode: a.code, reason: (reasons[a.code] ?? '').trim() }));
}

const SEVERITY_ORDER: Record<string, number> = { CRITICAL: 0, WARNING: 1, INFO: 2 };
export function sortAlerts<T extends { severity?: string }>(alerts: readonly T[]): T[] {
  return [...alerts].sort((a, b) => (SEVERITY_ORDER[a.severity ?? ''] ?? 3) - (SEVERITY_ORDER[b.severity ?? ''] ?? 3));
}
