import { bulkSummary, type BulkRow } from '../domain/bulk';

/** Per-item result of a bulk action (text + symbol, not colour alone). */
export function BulkResultList({ action, rows }: { action: string; rows: readonly BulkRow[] }) {
  if (rows.length === 0) return null;
  const failed = rows.some((r) => !r.ok);
  return (
    <div className={`bulk-result ${failed ? 'bulk-result-partial' : 'bulk-result-ok'}`} role="status" aria-live="polite">
      <p>
        <strong>{action}:</strong> {bulkSummary(rows)}
      </p>
      <ul>
        {rows.map((r) => (
          <li key={r.caseId} className={r.ok ? 'bulk-ok' : 'bulk-failed'}>
            <span aria-hidden="true">{r.ok ? '✓' : '✕'} </span>
            <span className="mono">{r.caseRef}</span> – {r.ok ? 'Done' : `Failed: ${r.reason ?? 'unknown reason'}`}
          </li>
        ))}
      </ul>
    </div>
  );
}
