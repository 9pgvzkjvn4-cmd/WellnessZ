import type { Result } from '../../api/record';
import { humanise } from '../../components/format';

const FLAG_SYMBOL: Record<string, string> = { NORMAL: '✓', LOW: '▼', HIGH: '▲', ABNORMAL: '!', CRITICAL: '!!' };

/** Result flag: symbol + text + colour (never colour alone). */
export function ResultFlag({ flag }: { flag?: string | null }) {
  if (!flag) return <span className="muted">–</span>;
  return (
    <span className={`badge flag flag-${flag.toLowerCase()}`}>
      <span aria-hidden="true">{FLAG_SYMBOL[flag] ?? '•'} </span>
      {humanise(flag)}
    </span>
  );
}

export function OverdueBadge({ overdue, ageHours }: { overdue?: boolean; ageHours?: number }) {
  if (!overdue) return ageHours != null ? <span className="small muted">{Math.round(ageHours)} h</span> : null;
  return (
    <span className="badge badge-overdue" title="Unacknowledged for more than 72 hours – escalated to the CMO">
      <span aria-hidden="true">■ </span>Overdue{ageHours != null ? ` · ${Math.round(ageHours)} h` : ''}
    </span>
  );
}

/** Value + unit + range, plus the audiometry standard threshold shift detail when present. */
export function ResultValue({ r }: { r: Result }) {
  const sts = (r.detail as { sts?: { left?: boolean; right?: boolean } } | null | undefined)?.sts;
  const value = r.value !== undefined && r.value !== null && typeof r.value === 'object' ? JSON.stringify(r.value) : r.value;
  return (
    <>
      {value ?? '–'} {r.unit ?? ''}
      {r.referenceRange && <div className="small muted">Ref. {r.referenceRange}</div>}
      {sts && (sts.left || sts.right) && (
        <div className="small">
          Standard threshold shift: {[sts.left ? 'left' : '', sts.right ? 'right' : ''].filter(Boolean).join(' and ')} ear
        </div>
      )}
    </>
  );
}
