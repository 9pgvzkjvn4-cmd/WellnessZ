/**
 * Increment 3 – E07 medical surveillance for managers / HR (docs/increment-03/API-Contract-E06-E07-E14.md §4.2, §4.3).
 *
 * Team compliance and onboarding clearance are status only: no results, exposures, questionnaire answers, panels,
 * appointments or documents are copied, even if a misconfigured backend returned them.
 */
import { list, s } from './fitness';
import type { FieldErrors } from './referrals';

const obj = (v: unknown): Record<string, unknown> | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined);
const defined = <T>(x: T | undefined): x is T => x !== undefined;
const oneOf = <T extends string>(values: readonly T[], v: unknown): T | undefined =>
  typeof v === 'string' && (values as readonly string[]).includes(v) ? (v as T) : undefined;

/* ---------------- team surveillance compliance (§4.2 GET /workspace/surveillance/compliance) ---------------- */

export const TEAM_COMPLIANCE_STATUSES = ['CURRENT', 'DUE', 'OVERDUE'] as const;
export type TeamComplianceStatus = (typeof TEAM_COMPLIANCE_STATUSES)[number];
export const COMPLIANCE_LABELS: Record<TeamComplianceStatus, string> = {
  CURRENT: 'Current',
  DUE: 'Due within 30 days',
  OVERDUE: 'Overdue',
};

export interface ComplianceRow {
  employeeId: string;
  displayName: string;
  programmeTitle: string;
  status: TeamComplianceStatus;
  nextDueOn?: string;
  escalated: boolean;
}

const RANK: Record<TeamComplianceStatus, number> = { OVERDUE: 0, DUE: 1, CURRENT: 2 };

/** Rows with a status outside CURRENT / DUE / OVERDUE are dropped (not part of the manager view). Overdue first. */
export function toComplianceRows(body: unknown): ComplianceRow[] {
  return list(body)
    .map((raw): ComplianceRow | undefined => {
      const r = obj(raw);
      const employeeId = s(r?.employeeId);
      const status = oneOf(TEAM_COMPLIANCE_STATUSES, r?.status);
      if (!r || !employeeId || !status) return undefined;
      return {
        employeeId,
        displayName: s(r.displayName) ?? employeeId,
        programmeTitle: s(r.programmeTitle) ?? '–',
        status,
        nextDueOn: s(r.nextDueOn),
        escalated: r.escalated === true,
      };
    })
    .filter(defined)
    .sort(
      (a, b) =>
        RANK[a.status] - RANK[b.status] ||
        Number(b.escalated) - Number(a.escalated) ||
        (a.nextDueOn ?? '9999').localeCompare(b.nextDueOn ?? '9999') ||
        a.displayName.localeCompare(b.displayName),
    );
}

export interface ComplianceFilter {
  q: string;
  status: '' | TeamComplianceStatus;
  escalatedOnly: boolean;
}
export const emptyComplianceFilter = (): ComplianceFilter => ({ q: '', status: '', escalatedOnly: false });

export function filterCompliance(rows: readonly ComplianceRow[], f: ComplianceFilter): ComplianceRow[] {
  const q = f.q.trim().toLowerCase();
  return rows.filter((r) => {
    if (q && ![r.displayName, r.employeeId, r.programmeTitle].some((x) => x.toLowerCase().includes(q))) return false;
    if (f.status && r.status !== f.status) return false;
    if (f.escalatedOnly && !r.escalated) return false;
    return true;
  });
}

export function summariseCompliance(rows: readonly ComplianceRow[]) {
  const count = (st: TeamComplianceStatus) => rows.filter((r) => r.status === st).length;
  const current = count('CURRENT');
  return {
    total: rows.length,
    current,
    due: count('DUE'),
    overdue: count('OVERDUE'),
    escalated: rows.filter((r) => r.escalated).length,
    /** Share of programme enrolments that are current (null when there are none). */
    compliancePct: rows.length ? Math.round((current / rows.length) * 100) : null,
  };
}

/* ---------------- pre-employment (§4.3) ---------------- */

export const HR_CLEARANCES = ['PENDING', 'FIT', 'UNFIT'] as const;
export type HrClearance = (typeof HR_CLEARANCES)[number];
export const HR_CLEARANCE_LABELS: Record<HrClearance, string> = {
  PENDING: 'Pending',
  FIT: 'Fit – cleared to start',
  UNFIT: 'Not fit for the role',
};

export interface OnboardingRow {
  id: string;
  offerRef?: string;
  candidateName: string;
  jobTitle?: string;
  proposedStart?: string;
  hrClearance: HrClearance;
  updatedAt?: string;
}

/** `GET /workspace/pre-employment` – unknown clearance values are shown as PENDING (never as a decision). */
export function toOnboardingRows(body: unknown): OnboardingRow[] {
  return list(body)
    .map((raw): OnboardingRow | undefined => {
      const r = obj(raw);
      const id = s(r?.id);
      if (!r || !id) return undefined;
      return {
        id,
        offerRef: s(r.offerRef),
        candidateName: s(r.candidateName) ?? s(r.offerRef) ?? '–',
        jobTitle: s(r.jobTitle),
        proposedStart: s(r.proposedStart),
        hrClearance: oneOf(HR_CLEARANCES, r.hrClearance) ?? 'PENDING',
        updatedAt: s(r.updatedAt),
      };
    })
    .filter(defined)
    .sort((a, b) => (a.proposedStart ?? '9999').localeCompare(b.proposedStart ?? '9999') || a.candidateName.localeCompare(b.candidateName));
}

/** Pending clearances whose proposed start is within `days` days (or already past) of `today` (`YYYY-MM-DD`). */
export function isStartAtRisk(r: Pick<OnboardingRow, 'hrClearance' | 'proposedStart'>, today: string, days = 7): boolean {
  if (r.hrClearance !== 'PENDING' || !r.proposedStart) return false;
  const t = new Date(`${today}T00:00:00Z`);
  t.setUTCDate(t.getUTCDate() + days);
  return r.proposedStart <= t.toISOString().slice(0, 10);
}

export const PRE_EMPLOYMENT_FIELDS = [
  'offerRef',
  'candidateId',
  'candidateName',
  'email',
  'jobCode',
  'subsidiaryCode',
  'departmentCode',
  'locationCode',
  'proposedStart',
] as const;
export type PreEmploymentField = (typeof PRE_EMPLOYMENT_FIELDS)[number];

export type PreEmploymentForm = Record<PreEmploymentField, string>;
export type PreEmploymentRequest = PreEmploymentForm;

export const emptyPreEmploymentForm = (): PreEmploymentForm => ({
  offerRef: '',
  candidateId: '',
  candidateName: '',
  email: '',
  jobCode: '',
  subsidiaryCode: '',
  departmentCode: '',
  locationCode: '',
  proposedStart: '',
});

/** Not pinned by the contract – client-side limits. */
export const PRE_EMPLOYMENT_LIMITS: Record<PreEmploymentField, number> = {
  offerRef: 64,
  candidateId: 64,
  candidateName: 200,
  email: 254,
  jobCode: 64,
  subsidiaryCode: 32,
  departmentCode: 32,
  locationCode: 32,
  proposedStart: 10,
};

/** Master-data codes (job, subsidiary, department, location): upper-case letters, digits, `_` and `-`. */
const CODE = /^[A-Z0-9][A-Z0-9_-]*$/;
const REF = /^[A-Za-z0-9][A-Za-z0-9_./-]*$/;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const CODE_FIELDS = ['jobCode', 'subsidiaryCode', 'departmentCode', 'locationCode'] as const;

/** Normalise as the request will be sent: trimmed, codes upper-cased. */
export function normalisePreEmployment(f: PreEmploymentForm): PreEmploymentRequest {
  const out = Object.fromEntries(PRE_EMPLOYMENT_FIELDS.map((k) => [k, f[k].trim()])) as PreEmploymentRequest;
  for (const k of CODE_FIELDS) out[k] = out[k].toUpperCase();
  return out;
}

/** `today` is `YYYY-MM-DD` (injected for tests). */
export function validatePreEmployment(form: PreEmploymentForm, today: string): FieldErrors {
  const f = normalisePreEmployment(form);
  const e: FieldErrors = {};
  if (!f.offerRef) e.offerRef = 'Please enter the offer reference.';
  else if (!REF.test(f.offerRef)) e.offerRef = 'Please use letters, digits and - _ . / only.';
  if (!f.candidateId) e.candidateId = 'Please enter the candidate id.';
  else if (!REF.test(f.candidateId)) e.candidateId = 'Please use letters, digits and - _ . / only.';
  if (!f.candidateName) e.candidateName = "Please enter the candidate's name.";
  if (!EMAIL.test(f.email)) e.email = 'Please enter a valid e-mail address.';
  const codeLabels: Record<(typeof CODE_FIELDS)[number], string> = {
    jobCode: 'job code',
    subsidiaryCode: 'subsidiary code',
    departmentCode: 'department code',
    locationCode: 'location code',
  };
  for (const k of CODE_FIELDS) {
    if (!f[k]) e[k] = `Please enter the ${codeLabels[k]}.`;
    else if (!CODE.test(f[k])) e[k] = `Please enter a valid ${codeLabels[k]} (letters, digits, _ and -).`;
  }
  if (!DATE.test(f.proposedStart)) e.proposedStart = 'Please enter the proposed start date.';
  else if (f.proposedStart < today) e.proposedStart = 'The proposed start date cannot be in the past.';
  for (const k of PRE_EMPLOYMENT_FIELDS) {
    if (!e[k] && f[k].length > PRE_EMPLOYMENT_LIMITS[k]) e[k] = `Please use at most ${PRE_EMPLOYMENT_LIMITS[k]} characters.`;
  }
  return e;
}

export const PRE_EMPLOYMENT_PATH = '/surveillance/pre-employment';
