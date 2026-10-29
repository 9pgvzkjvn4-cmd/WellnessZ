import type { BulkResult } from '../api/types';

export interface BulkRow {
  caseId: string;
  caseRef: string;
  ok: boolean;
  reason?: string;
}

/**
 * Per-item outcome of `POST /queues/bulk`, in the order the user selected the cases.
 * Ids the server reported neither as succeeded nor failed are shown as failed ("No result returned").
 */
export function bulkRows(requested: readonly string[], result: BulkResult, refOf: (id: string) => string | undefined): BulkRow[] {
  const ok = new Set(result.succeeded ?? []);
  const failed = new Map((result.failed ?? []).map((f) => [f.caseId, f.reason]));
  return requested.map((caseId) => {
    const caseRef = refOf(caseId) ?? caseId;
    if (ok.has(caseId)) return { caseId, caseRef, ok: true };
    return { caseId, caseRef, ok: false, reason: failed.get(caseId) ?? 'No result returned' };
  });
}

export function bulkSummary(rows: readonly BulkRow[]): string {
  const ok = rows.filter((r) => r.ok).length;
  const failed = rows.length - ok;
  return failed === 0 ? `${ok} of ${rows.length} succeeded` : `${ok} of ${rows.length} succeeded, ${failed} failed`;
}
