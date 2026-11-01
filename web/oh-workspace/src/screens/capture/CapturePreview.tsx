import type { CaptureSession, FlagView, Preview, ProvenanceView } from '../../api/deviceCapture';
import { attachmentHref } from '../../api/deviceCapture';
import { formatBytes, formatDate, humanise } from '../../components/format';
import { blockFlags, displayValue, flagKey } from '../../domain/capture';

const OUTCOME_CLASS: Record<string, string> = { BLOCK: 'badge-overdue', WARN: 'badge-expiring', INFO: 'badge-muted', PASS: 'badge-complete' };
const OUTCOME_SYMBOL: Record<string, string> = { BLOCK: '■', WARN: '▲', INFO: 'ℹ', PASS: '●' };
const OUTCOME_TEXT: Record<string, string> = { BLOCK: 'Blocks posting', WARN: 'Warning', INFO: 'Information', PASS: 'Passed' };

export function FlagBadge({ outcome }: { outcome: string }) {
  return (
    <span className={`badge ${OUTCOME_CLASS[outcome] ?? 'badge-muted'}`}>
      <span aria-hidden="true">{OUTCOME_SYMBOL[outcome] ?? '○'} </span>
      {OUTCOME_TEXT[outcome] ?? humanise(outcome)}
    </span>
  );
}

/** Quality flags: BLOCK flags explain why posting is disabled; WARN flags need an explicit acknowledgement. */
export function QualityFlags({ flags, acknowledged, onToggle, idPrefix, readOnly }: { flags: FlagView[]; acknowledged: ReadonlySet<string>; onToggle: (key: string, on: boolean) => void; idPrefix: string; readOnly?: boolean }) {
  const shown = flags.filter((f) => f.outcome !== 'PASS');
  const blocks = blockFlags(flags);
  if (shown.length === 0) return <p className="small muted">All quality checks passed.</p>;
  return (
    <div className="quality-flags">
      {blocks.length > 0 && (
        <div className="alert alert-error" role="alert">
          <strong>Posting is blocked.</strong> {blocks.map((b) => b.message ?? humanise(b.code)).join(' ')}
        </div>
      )}
      <ul className="plain-list flag-list">
        {shown.map((f, i) => {
          const id = `${idPrefix}-ack-${i}`;
          return (
            <li key={`${f.gate}-${f.code}-${i}`} className={`flag-item flag-${f.outcome.toLowerCase()}`}>
              <FlagBadge outcome={f.outcome} /> <span className="small muted">{humanise(f.gate)}</span>
              <div>{f.message ?? humanise(f.code)}</div>
              {f.outcome === 'WARN' && !readOnly && (
                <label className="checkbox ack" htmlFor={id}>
                  <input id={id} type="checkbox" checked={acknowledged.has(flagKey(f))} onChange={(e) => onToggle(flagKey(f), e.target.checked)} /> I have reviewed this warning
                </label>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

export function ProvenanceDetails({ p, marking }: { p?: ProvenanceView | null; marking?: string | null }) {
  if (!p) return null;
  const rows: [string, string | null | undefined][] = [
    ['Test traffic', p.simulated ? 'Simulated test reading from a simulator run – not measured on this person' : undefined],
    ['Capture method', humanise(p.captureMethod)],
    ['Device', [p.manufacturer, p.model].filter(Boolean).join(' ') || undefined],
    ['Serial', p.serial],
    ['UDI', p.udi],
    ['Firmware', p.firmware],
    ['Driver', p.driver],
    ['Calibration', [p.calibrationId, p.calibrationDate, p.calibrationVerdict && humanise(p.calibrationVerdict)].filter(Boolean).join(' · ') || undefined],
    ['Operator', p.operator],
    ['Linked to the employee by', p.associationMethod ? `${humanise(p.associationMethod)}${p.associationConfidence !== null && p.associationConfidence !== undefined ? ` (confidence ${Number(p.associationConfidence).toFixed(2)})` : ''}` : undefined],
    ['Device clock offset', p.clockSkewMillis ? `${Math.round(p.clockSkewMillis / 1000)} s (corrected)` : undefined],
    ['Verified', p.verifiedBy ? `${p.verifiedBy} ${formatDate(p.verifiedAt)}` : undefined],
    ['Raw payload SHA-256', p.rawPayloadSha256],
  ];
  return (
    <details className="provenance">
      <summary>Provenance{marking ? ` – ${marking}` : ''}</summary>
      <dl className="definitions">
        {rows.filter(([, v]) => !!v).map(([k, v]) => (
          <div key={k}>
            <dt>{k}</dt>
            <dd className={k.includes('SHA') ? 'mono break' : undefined}>{v}</dd>
          </div>
        ))}
      </dl>
    </details>
  );
}

/** Values with units, interpretation and body site; attachments (PDF, waveform, image) open through the engine. */
export function PreviewValues({ preview }: { preview: Preview }) {
  return (
    <>
      <div className="table-wrap">
        <table className="capture-values">
          <caption className="visually-hidden">Captured values</caption>
          <thead>
            <tr>
              <th scope="col">Measurement</th>
              <th scope="col">Value</th>
              <th scope="col">Site / method</th>
              <th scope="col">Interpretation</th>
            </tr>
          </thead>
          <tbody>
            {preview.values.map((v, i) => (
              <tr key={`${v.observationId}-${v.key}-${i}`}>
                <th scope="row">{v.display ?? humanise(v.key)}</th>
                <td className="num">{displayValue(v)}</td>
                <td className="small">{[v.bodySite, v.method].filter(Boolean).map((x) => humanise(x)).join(' · ') || '–'}</td>
                <td>{v.interpretation ? <span className={`flag-${v.interpretation === 'N' ? 'normal' : v.interpretation === 'L' ? 'low' : 'high'}`}>{{ N: 'Normal', L: 'Low', H: 'High', A: 'Abnormal' }[v.interpretation] ?? v.interpretation}</span> : '–'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {preview.attachments.length > 0 && (
        <ul className="plain-list small">
          {preview.attachments.map((a) => (
            <li key={a.archiveId}>
              <a href={attachmentHref(a.link)} target="_blank" rel="noopener">
                {humanise(a.kind)} ({a.mediaType}, {formatBytes(a.sizeBytes)})
              </a>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

/** Previous values / baseline of the same category (§14.7) next to the new reading. */
export function ComparisonBlock({ preview }: { preview: Preview }) {
  const c = preview.comparison;
  if (!c || (c.previous.length === 0 && !c.baseline)) return null;
  const keys = [...new Set(preview.values.filter((v) => v.value !== null && v.value !== undefined).map((v) => v.key))].slice(0, 8);
  const rows = [...(c.baseline ? [{ label: 'Baseline', ...c.baseline }] : []), ...c.previous.slice(0, 3).map((p) => ({ label: formatDate(p.effectiveAt), ...p }))];
  if (keys.length === 0) return null;
  return (
    <div className="table-wrap">
      <table className="comparison">
        <caption>Comparison with earlier results</caption>
        <thead>
          <tr>
            <th scope="col">When</th>
            {keys.map((k) => (
              <th key={k} scope="col">{preview.values.find((v) => v.key === k)?.display ?? humanise(k)}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          <tr className="current-row">
            <th scope="row">This reading</th>
            {keys.map((k) => (
              <td key={k} className="num">{preview.values.find((v) => v.key === k)?.value ?? '–'}</td>
            ))}
          </tr>
          {rows.map((r, i) => (
            <tr key={i}>
              <th scope="row">{r.label}</th>
              {keys.map((k) => (
                <td key={k} className="num">{r.values[k] ?? '–'}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** "device-sourced · model · time" / "photo verified" / "entered manually – attested". */
export function MarkingTag({ s }: { s: CaptureSession }) {
  const m = s.preview?.marking;
  if (!m) return null;
  return <span className={`badge capture-marking marking-${(s.path ?? 'DEVICE').toLowerCase().replace(/_/g, '-')}`}>{m}</span>;
}

