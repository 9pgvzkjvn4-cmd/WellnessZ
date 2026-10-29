import type { CdsAlert } from '../api/record';
import { overrideFieldId, sortAlerts } from '../domain/cds';
import { humanise } from './format';
import { IdField, type FieldError } from './forms';
import { errorFor } from '../domain/validation';

const SYMBOL: Record<string, string> = { CRITICAL: '!!', WARNING: '!', INFO: 'i' };

/** Severity tag with a shape symbol and text (never colour alone). */
export function CdsSeverity({ severity }: { severity?: string }) {
  const s = severity ?? 'INFO';
  return (
    <span className={`badge sev sev-${s.toLowerCase()}`}>
      <span aria-hidden="true">{SYMBOL[s] ?? '•'} </span>
      {humanise(s)}
    </span>
  );
}

/** Read-only list of CDS alerts / prompts (encounter panel). */
export function CdsAlertList({ alerts, label = 'Clinical decision support' }: { alerts: readonly CdsAlert[]; label?: string }) {
  if (alerts.length === 0) return <p className="small muted">No alerts.</p>;
  return (
    <ul className="plain-list cds-list" aria-label={label}>
      {sortAlerts(alerts).map((a, i) => (
        <li key={`${a.code}-${i}`} className={`cds-alert cds-${(a.severity ?? 'info').toLowerCase()}`}>
          <CdsSeverity severity={a.severity} /> <strong>{humanise(a.code)}</strong> – {a.message}
          {a.reference && <div className="small muted">Reference: {a.reference}</div>}
        </li>
      ))}
    </ul>
  );
}

/**
 * `409 cds_alert` panel: every alert needs its own override reason before the request is resubmitted
 * (contract E03 §4.5 – a reason is mandatory, so "override without reason" is always 0).
 */
export function CdsOverridePanel({ alerts, reasons, onChange, errors }: { alerts: readonly CdsAlert[]; reasons: Record<string, string>; onChange: (r: Record<string, string>) => void; errors: readonly FieldError[] }) {
  return (
    <div className="cds-panel" role="region" aria-label="Clinical decision support alerts">
      <h3>
        <span aria-hidden="true">⚠ </span>
        {alerts.length} clinical alert{alerts.length === 1 ? '' : 's'} – review before saving
      </h3>
      <p className="small">Change the entry, or give a reason for overriding each alert. Overrides are recorded and reported.</p>
      <ul className="plain-list">
        {sortAlerts(alerts).map((a) => {
          const id = overrideFieldId(a.code);
          return (
            <li key={a.code} className={`cds-alert cds-${(a.severity ?? 'info').toLowerCase()}`}>
              <CdsSeverity severity={a.severity} /> <strong>{humanise(a.code)}</strong> – {a.message}
              {a.reference && <div className="small muted">Reference: {a.reference}</div>}
              <IdField id={id} label={`Override reason for ${humanise(a.code)}`} required error={errorFor(errors, id)}>
                {(d) => <textarea id={id} rows={2} maxLength={500} value={reasons[a.code] ?? ''} aria-describedby={d} aria-invalid={!!errorFor(errors, id)} onChange={(e) => onChange({ ...reasons, [a.code]: e.target.value })} />}
              </IdField>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
