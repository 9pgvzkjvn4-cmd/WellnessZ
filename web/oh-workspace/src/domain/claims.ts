/**
 * E08 client-side helpers: report deadline countdown, claim status workflow, disclosure-set document rules, redaction
 * phrase parsing, budget variance and claim form checks. Pure functions only (unit-tested in increment4.test.ts).
 */
import type { ClaimStatus, FirstReport } from '../api/claims';
import type { Tone } from './absence';
import type { FieldError } from './devices';

const HOUR = 3_600_000;

function span(ms: number): string {
  const totalMin = Math.round(Math.abs(ms) / 60_000);
  const d = Math.floor(totalMin / 1440);
  const h = Math.floor((totalMin % 1440) / 60);
  const m = totalMin % 60;
  if (d > 0) return `${d} d ${h} h`;
  if (h > 0) return `${h} h ${m} min`;
  return `${m} min`;
}

export interface Countdown {
  tone: Tone;
  label: string;
  /** Hours left (negative = overdue); undefined once submitted. */
  hoursLeft?: number;
}

/**
 * Statutory / insurer report deadline (contract §4.2): submitted or acknowledged → done; LATE or past due → red
 * "Overdue by …"; due within 24 h → amber; otherwise green "… left".
 */
export function deadlineCountdown(r: Pick<FirstReport, 'dueAt' | 'status' | 'submittedAt'>, now: number = Date.now()): Countdown {
  if (r.status === 'ACKNOWLEDGED') return { tone: 'green', label: 'Acknowledged' };
  if (r.status === 'SUBMITTED') return { tone: 'green', label: 'Submitted' };
  if (r.status === 'REJECTED') return { tone: 'red', label: 'Rejected – resubmit' };
  const due = Date.parse(r.dueAt);
  if (Number.isNaN(due)) return { tone: 'muted', label: 'No deadline' };
  const ms = due - now;
  const hoursLeft = Math.floor(ms / HOUR);
  if (ms < 0 || r.status === 'LATE') return { tone: 'red', label: ms < 0 ? `Overdue by ${span(ms)}` : 'Late', hoursLeft };
  if (ms <= 24 * HOUR) return { tone: 'amber', label: `${span(ms)} left`, hoursLeft };
  return { tone: 'green', label: `${span(ms)} left`, hoursLeft };
}

/** Claim lifecycle (contract §4 `ClaimStatus`): NOTIFIED → OPEN → UNDER_REVIEW → SETTLED → CLOSED; LITIGATED from OPEN / UNDER_REVIEW; CLOSED → OPEN. */
const TRANSITIONS: Record<ClaimStatus, ClaimStatus[]> = {
  NOTIFIED: ['OPEN'],
  OPEN: ['UNDER_REVIEW', 'LITIGATED'],
  UNDER_REVIEW: ['SETTLED', 'LITIGATED'],
  LITIGATED: ['SETTLED'],
  SETTLED: ['CLOSED'],
  CLOSED: ['OPEN'],
};

/** Next statuses the user may choose: claims coordinators get all; LEGAL_COUNSEL only LITIGATED. */
export function nextClaimStatuses(status: ClaimStatus, opts: { coordinator: boolean; legal: boolean }): ClaimStatus[] {
  const all = TRANSITIONS[status] ?? [];
  if (opts.coordinator) return all;
  if (opts.legal) return all.filter((s) => s === 'LITIGATED');
  return [];
}

/** Reopening a closed claim needs a reason; so does litigation. */
export const statusReasonRequired = (from: ClaimStatus, to: ClaimStatus): boolean => (from === 'CLOSED' && to === 'OPEN') || to === 'LITIGATED';

export function claimTone(status: string | undefined): Tone {
  switch (status) {
    case 'LITIGATED': return 'red';
    case 'NOTIFIED':
    case 'UNDER_REVIEW': return 'amber';
    case 'OPEN': return 'green';
    default: return 'muted';
  }
}

// ---------------------------------------------------------------- disclosure sets (§4.3)
export const DISCLOSABLE_TYPES = ['FITNESS_CERTIFICATE', 'RESTRICTION_NOTE', 'RTW_PLAN', 'PREAUTH_REQUEST', 'FIRST_REPORT_OF_INJURY', 'PROVIDER_INVOICE', 'REDACTED_COPY'] as const;
export const OH_ONLY_DISCLOSABLE_TYPES = ['PROVIDER_REPORT', 'EXTERNAL_REPORT'] as const;
export const NEVER_DISCLOSABLE_TYPES = ['MEDICO_LEGAL_REPORT', 'PHOTO'] as const;

/** Whether a document type may be added to a disclosure set by this user (the server re-checks restricted class and privilege). */
export function isDisclosable(type: string | undefined, ohRole: boolean): boolean {
  if (!type) return false;
  if ((DISCLOSABLE_TYPES as readonly string[]).includes(type)) return true;
  return ohRole && (OH_ONLY_DISCLOSABLE_TYPES as readonly string[]).includes(type);
}

export const MAX_REDACTION_PHRASES = 50;

/** One phrase per line; trimmed, de-duplicated (case-insensitive), ≥ 2 characters, ≤ 50 phrases. */
export function parseRedactionPhrases(text: string): { phrases: string[]; error?: string } {
  const seen = new Set<string>();
  const phrases: string[] = [];
  for (const raw of text.split(/\r?\n/)) {
    const p = raw.trim();
    if (!p) continue;
    if (p.length < 2) return { phrases, error: `"${p}" is too short to redact safely – use at least 2 characters` };
    if (p.length > 200) return { phrases, error: 'A phrase may be at most 200 characters' };
    const k = p.toLowerCase();
    if (seen.has(k)) continue;
    seen.add(k);
    phrases.push(p);
  }
  if (phrases.length === 0) return { phrases, error: 'Enter at least one phrase to mask' };
  if (phrases.length > MAX_REDACTION_PHRASES) return { phrases, error: `At most ${MAX_REDACTION_PHRASES} phrases per redaction` };
  return { phrases };
}

// ---------------------------------------------------------------- finance
/** Budget variance: actual over budget → red (> 10 %) / amber (0–10 %); under → green. */
export function budgetTone(variancePct: number | undefined | null): { tone: Tone; label: string } {
  if (variancePct === undefined || variancePct === null || Number.isNaN(variancePct)) return { tone: 'muted', label: '–' };
  const s = `${variancePct > 0 ? '+' : ''}${variancePct.toFixed(1)} %`;
  if (variancePct > 10) return { tone: 'red', label: `${s} over budget` };
  if (variancePct > 0) return { tone: 'amber', label: `${s} over budget` };
  return { tone: 'green', label: variancePct === 0 ? 'On budget' : `${s} under budget` };
}

export const isMonth = (v: string): boolean => /^\d{4}-(0[1-9]|1[0-2])$/.test(v);

// ---------------------------------------------------------------- claim form
export interface ClaimForm {
  claimType: string;
  payerCode: string;
  policyNumber: string;
  adjusterName: string;
  adjusterEmail: string;
  adjusterUsername: string;
  lostTime: boolean;
  incidentDate: string;
  notes: string;
}
export const emptyClaimForm = (): ClaimForm => ({ claimType: 'WORKERS_COMP', payerCode: '', policyNumber: '', adjusterName: '', adjusterEmail: '', adjusterUsername: '', lostTime: false, incidentDate: '', notes: '' });

export function validateClaimForm(f: ClaimForm): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.claimType) errs.push({ field: 'claim-claimType', message: 'Choose the claim type' });
  if (!f.payerCode.trim()) errs.push({ field: 'claim-payerCode', message: 'Choose the payer (insurer / TPA)' });
  if (f.adjusterEmail.trim() && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(f.adjusterEmail.trim())) errs.push({ field: 'claim-adjusterEmail', message: 'Adjuster e-mail is not valid' });
  if (f.incidentDate && !/^\d{4}-\d{2}-\d{2}$/.test(f.incidentDate)) errs.push({ field: 'claim-incidentDate', message: 'Incident date is not a valid date' });
  return errs;
}
