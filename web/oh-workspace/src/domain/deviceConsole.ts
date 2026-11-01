/** Presentation helpers shared by the device console screens (E32 / E34). */
import type { Tone } from './absence';

/** Status → tone for device console badges (always rendered with a symbol and text, never colour alone). */
const TONES: Record<string, Tone> = {
  ACCEPTABLE: 'green', EXCEPTION_APPROVED: 'amber', BLOCKED: 'red', NOT_ASSESSED: 'muted',
  CANDIDATE: 'muted', CERTIFIED: 'green', DEPRECATED: 'amber', RETIRED: 'red',
  REGISTERED: 'amber', REVOKED: 'red',
  DRAFT: 'muted', AWAITING_APPROVAL: 'amber', PUBLISHED: 'green', SUSPENDED: 'amber', QUARANTINED: 'red', SUPERSEDED: 'muted', DECOMMISSIONED: 'muted',
  PENDING_ENROLMENT: 'amber', ACTIVE: 'green', ISOLATED: 'red',
  HEALTHY: 'green', OK: 'green', DEGRADED: 'amber', SILENT: 'red', STALE: 'amber', UNKNOWN: 'muted',
  PASS: 'green', PASSED: 'green', VALID: 'green', DONE: 'green', FAIL: 'red', FAILED: 'red', WARN: 'amber', NOT_RUN: 'muted', DUE: 'amber', OVERDUE: 'red', DUE_SOON: 'amber',
  OUT_OF_CALIBRATION: 'red', OUT_OF_SERVICE: 'red', OPEN: 'amber', ACKNOWLEDGED: 'muted', CLOSED: 'green', FIXED: 'green', ACCEPTED: 'green', REJECTED: 'red',
  NOT_APPLICABLE: 'muted', PENDING: 'amber', APPROVED: 'green', SUBMITTED: 'amber', SCOPING: 'amber', SCOPED: 'green', IN_DEVELOPMENT: 'amber',
  INFO: 'muted', LOW: 'muted', MEDIUM: 'amber', HIGH: 'red', CRITICAL: 'red', RETIRED_KEY: 'muted',
};

export function toneFor(code: string | null | undefined): Tone {
  return (code && TONES[code]) || 'muted';
}

/** Small "n items" caption helper. */
export const plural = (n: number, one: string, many = `${one}s`): string => `${n} ${n === 1 ? one : many}`;
