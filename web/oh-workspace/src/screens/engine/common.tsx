import '../capture/capture.css';
import { ApiError } from '../../auth/AuthClient';
import { humanise } from '../../components/format';
import { ToneBadge } from '../../components/Tone';
import { Alert } from '../../components/ui';

const TONES: Record<string, 'green' | 'amber' | 'red' | 'muted'> = {
  HEALTHY: 'green', DEGRADED: 'amber', STALE: 'amber', SILENT: 'red', ISOLATED: 'red',
  OPEN: 'amber', REPLAYED: 'green', DISCARDED: 'muted', RESOLVED: 'green',
  HIGH: 'red', CRITICAL: 'red', MEDIUM: 'amber', LOW: 'muted', INFO: 'muted',
  ROUTED: 'green', PENDING_VERIFICATION: 'amber', BLOCKED: 'red', UNMATCHED: 'amber', REJECTED: 'muted', ROUTE_PENDING: 'amber',
  APPROVED: 'green', PENDING: 'amber', PASS: 'green', PASSED: 'green', FAIL: 'red', FAILED: 'red', ACCEPTED: 'green',
};

export function StateBadge({ code, label }: { code: string | null | undefined; label?: string }) {
  if (!code) return <span className="muted">–</span>;
  return <ToneBadge tone={TONES[code] ?? 'muted'}>{label ?? humanise(code)}</ToneBadge>;
}

/** Long hashes / ids: abbreviated with the full value for assistive technology and on hover. */
export function ShortHash({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="muted">–</span>;
  return (
    <span className="mono" title={value}>
      {value.slice(0, 12)}…<span className="visually-hidden"> full value {value}</span>
    </span>
  );
}

/** A 403 is an expected state for some roles (the server enforces each endpoint separately). */
export function NotPermitted({ error, what }: { error: unknown; what: string }) {
  if (!(error instanceof ApiError) || error.status !== 403) return null;
  return <Alert kind="info">Your role does not include {what}.</Alert>;
}
