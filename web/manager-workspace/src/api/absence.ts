/**
 * Increment 3 – E06 absence & sickness for managers / HR (docs/increment-03/API-Contract-E06-E07-E14.md §3.1, §3.3,
 * §3.4, §3.6).
 *
 * Confidentiality: managers and HR see status, dates and counts only – never absence reasons (`reasonCategory`),
 * certificate content or analytics categories. As elsewhere in the workspace every projection copies ONLY
 * whitelisted fields, and enumerations outside the HR view (e.g. certificate status SUSPICIOUS) are dropped.
 */
import { BFF_API_PREFIX } from '../auth/bffAuthClient';
import { list, n, s } from './fitness';
import type { FieldErrors } from './referrals';

const obj = (v: unknown): Record<string, unknown> | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined);
const defined = <T>(x: T | undefined): x is T => x !== undefined;
const oneOf = <T extends string>(values: readonly T[], v: unknown): T | undefined =>
  typeof v === 'string' && (values as readonly string[]).includes(v) ? (v as T) : undefined;

/* ---------------- certificate validity (HR view) ---------------- */

/** `CertificateValidity` – the only certificate states HR / managers may see. */
export const CERTIFICATE_VALIDITIES = ['PENDING', 'VALID', 'INVALID'] as const;
export type CertificateValidity = (typeof CERTIFICATE_VALIDITIES)[number];
export const CERTIFICATE_VALIDITY_LABELS: Record<CertificateValidity, string> = {
  PENDING: 'Pending check',
  VALID: 'Valid',
  INVALID: 'Not valid',
};
export const toCertificateValidity = (v: unknown) => oneOf(CERTIFICATE_VALIDITIES, v);

/* ---------------- team absence status (§3.1 GET /workspace/absence/team) ---------------- */

export interface TeamAbsenceRow {
  employeeId: string;
  displayName: string;
  openEpisode?: { startDate?: string; days?: number };
  episodes12m?: number;
  ohEngaged: boolean;
  certificateValidity?: CertificateValidity;
}

export function toTeamAbsence(body: unknown): TeamAbsenceRow[] {
  return list(body)
    .map((raw): TeamAbsenceRow | undefined => {
      const r = obj(raw);
      const employeeId = s(r?.employeeId);
      if (!r || !employeeId) return undefined;
      const ep = obj(r.openEpisode);
      return {
        employeeId,
        displayName: s(r.displayName) ?? employeeId,
        openEpisode: ep ? { startDate: s(ep.startDate), days: n(ep.days) } : undefined,
        episodes12m: n(r.episodes12m),
        ohEngaged: r.ohEngaged === true,
        certificateValidity: toCertificateValidity(r.certificateValidity),
      };
    })
    .filter(defined);
}

export interface TeamAbsenceFilter {
  q: string;
  /** '' all · 'OPEN' currently absent · 'OH' OH engaged. */
  show: '' | 'OPEN' | 'OH';
}
export const emptyTeamAbsenceFilter = (): TeamAbsenceFilter => ({ q: '', show: '' });

/** Filter and sort: currently absent first (longest first), then by name. */
export function filterTeamAbsence(rows: readonly TeamAbsenceRow[], f: TeamAbsenceFilter): TeamAbsenceRow[] {
  const q = f.q.trim().toLowerCase();
  return rows
    .filter((r) => {
      if (q && !r.displayName.toLowerCase().includes(q) && !r.employeeId.toLowerCase().includes(q)) return false;
      if (f.show === 'OPEN') return !!r.openEpisode;
      if (f.show === 'OH') return r.ohEngaged;
      return true;
    })
    .sort((a, b) => {
      const da = a.openEpisode ? (a.openEpisode.days ?? 0) : -1;
      const db = b.openEpisode ? (b.openEpisode.days ?? 0) : -1;
      return db - da || a.displayName.localeCompare(b.displayName);
    });
}

export function summariseTeamAbsence(rows: readonly TeamAbsenceRow[]) {
  return {
    total: rows.length,
    absentNow: rows.filter((r) => r.openEpisode).length,
    ohEngaged: rows.filter((r) => r.ohEngaged).length,
  };
}

/* ---------------- certificates (§3.3 GET /workspace/absence/certificates) ---------------- */

export interface WorkspaceCertificate {
  id: string;
  employeeId?: string;
  displayName: string;
  fromDate?: string;
  toDate?: string;
  validity?: CertificateValidity;
  escalated: boolean;
}

/** Validity only – issuer, OCR fields, checklist, flags and call logs are never copied. */
export function toWorkspaceCertificates(body: unknown): WorkspaceCertificate[] {
  return list(body)
    .map((raw): WorkspaceCertificate | undefined => {
      const r = obj(raw);
      const id = s(r?.id);
      if (!r || !id) return undefined;
      return {
        id,
        employeeId: s(r.employeeId),
        displayName: s(r.displayName) ?? s(r.employeeId) ?? '–',
        fromDate: s(r.fromDate),
        toDate: s(r.toDate),
        validity: toCertificateValidity(r.validity),
        escalated: r.escalated === true,
      };
    })
    .filter(defined)
    .sort((a, b) => Number(b.escalated) - Number(a.escalated) || (b.fromDate ?? '').localeCompare(a.fromDate ?? ''));
}

/* ---------------- long-term absence (§3.4) ---------------- */

export const HR_STAGES = ['NONE', 'INFORMAL_REVIEW', 'FORMAL_REVIEW_1', 'FORMAL_REVIEW_2', 'CAPABILITY_HEARING', 'CLOSED'] as const;
export type HrStage = (typeof HR_STAGES)[number];
/** Stages HR can move a pathway to (NONE is the initial state only). */
export const SETTABLE_HR_STAGES = HR_STAGES.filter((x): x is Exclude<HrStage, 'NONE'> => x !== 'NONE');
export const HR_STAGE_LABELS: Record<HrStage, string> = {
  NONE: 'Not started',
  INFORMAL_REVIEW: 'Informal review',
  FORMAL_REVIEW_1: 'Formal review 1',
  FORMAL_REVIEW_2: 'Formal review 2',
  CAPABILITY_HEARING: 'Capability hearing',
  CLOSED: 'Closed',
};

export const MILESTONE_TYPES = ['CONTACT', 'ADVICE_REPORT', 'CASE_CONFERENCE', 'HR_REVIEW', 'RTW_REVIEW', 'IHR_CONSIDERATION'] as const;
export const MILESTONE_LABELS: Record<(typeof MILESTONE_TYPES)[number], string> = {
  CONTACT: 'OH contact',
  ADVICE_REPORT: 'OH advice report',
  CASE_CONFERENCE: 'Case conference',
  HR_REVIEW: 'HR review',
  RTW_REVIEW: 'Return-to-work review',
  IHR_CONSIDERATION: 'Ill-health retirement consideration',
};
export const milestoneLabel = (type: string) => MILESTONE_LABELS[type as keyof typeof MILESTONE_LABELS] ?? type;

export interface LtaRow {
  caseId: string;
  caseRef?: string;
  employeeId?: string;
  displayName: string;
  absenceStart?: string;
  weeksAbsent?: number;
  hrStage: HrStage;
  nextMilestone?: { week?: number; type: string; dueDate?: string };
  adviceReleased: number;
}

export function toLtaRows(body: unknown): LtaRow[] {
  return list(body)
    .map((raw): LtaRow | undefined => {
      const r = obj(raw);
      const caseId = s(r?.caseId);
      if (!r || !caseId) return undefined;
      const m = obj(r.nextMilestone);
      const mType = s(m?.type);
      return {
        caseId,
        caseRef: s(r.caseRef),
        employeeId: s(r.employeeId),
        displayName: s(r.displayName) ?? s(r.employeeId) ?? '–',
        absenceStart: s(r.absenceStart),
        weeksAbsent: n(r.weeksAbsent),
        hrStage: oneOf(HR_STAGES, r.hrStage) ?? 'NONE',
        nextMilestone: m && mType ? { week: n(m.week), type: mType, dueDate: s(m.dueDate) } : undefined,
        adviceReleased: n(r.adviceReleased) ?? 0,
      };
    })
    .filter(defined)
    .sort((a, b) => (b.weeksAbsent ?? 0) - (a.weeksAbsent ?? 0) || a.displayName.localeCompare(b.displayName));
}

/** Not pinned by the contract – client-side limit for the (screened) HR stage note. */
export const HR_STAGE_NOTE_MAX = 1000;
export const HR_STAGE_FIELDS = ['stage', 'note'] as const;

export interface HrStageDraft {
  stage: HrStage | '';
  note: string;
}
export const hrStageDraftFor = (row: Pick<LtaRow, 'hrStage'>): HrStageDraft => ({ stage: row.hrStage === 'NONE' ? '' : row.hrStage, note: '' });

export function validateHrStage(d: HrStageDraft, current: HrStage): FieldErrors {
  const e: FieldErrors = {};
  if (!d.stage || d.stage === 'NONE') e.stage = 'Please choose the HR stage.';
  else if (d.stage === current && !d.note.trim()) e.stage = 'This is already the current stage. Choose another stage or add a note.';
  if (d.note.length > HR_STAGE_NOTE_MAX) e.note = `Please use at most ${HR_STAGE_NOTE_MAX} characters.`;
  return e;
}

/** `POST /workspace/absence/lta/{caseId}/hr-stage` body. */
export function buildHrStage(d: HrStageDraft): { stage: HrStage; note?: string } {
  const body: { stage: HrStage; note?: string } = { stage: d.stage as HrStage };
  if (d.note.trim()) body.note = d.note.trim();
  return body;
}

export const hrStagePath = (caseId: string) => `/workspace/absence/lta/${encodeURIComponent(caseId)}/hr-stage`;

/* ---------------- analytics (§3.6) ---------------- */

export const ANALYTICS_GROUP_BY = ['SUBSIDIARY', 'DEPARTMENT', 'JOB_FAMILY'] as const;
export type AnalyticsGroupBy = (typeof ANALYTICS_GROUP_BY)[number];
export const ANALYTICS_GROUP_LABELS: Record<AnalyticsGroupBy, string> = {
  SUBSIDIARY: 'Subsidiary',
  DEPARTMENT: 'Department',
  JOB_FAMILY: 'Job family',
};
/** `analytics.min-group-size` default; the response does not carry it (a page object may – then it wins). */
export const DEFAULT_MIN_GROUP_SIZE = 5;

export interface UnitAnalyticsRow {
  key: string;
  label: string;
  suppressed: boolean;
  headcount?: number;
  episodes?: number;
  daysLost?: number;
  absenceRatePct?: number;
  frequencyRate?: number;
  avgDurationDays?: number;
  bradfordMean?: number;
}

export interface UnitAnalytics {
  minGroupSize: number;
  rows: UnitAnalyticsRow[];
}

/**
 * `GET /absence/analytics/units` – reason `categories` are never copied (OH roles only). As defence in depth a row
 * whose headcount is below the minimum group size is treated as suppressed and loses all its counts.
 */
export function toUnitAnalytics(body: unknown): UnitAnalytics {
  const o = obj(body);
  const k = n(o?.minGroupSize) ?? DEFAULT_MIN_GROUP_SIZE;
  const rawRows = Array.isArray(body) ? body : Array.isArray(o?.rows) ? (o.rows as unknown[]) : list(body);
  const rows = rawRows
    .map((raw): UnitAnalyticsRow | undefined => {
      const r = obj(raw);
      const key = s(r?.key);
      if (!r || !key) return undefined;
      const label = s(r.label) ?? key;
      const headcount = n(r.headcount);
      if (r.suppressed === true || headcount === undefined || headcount < k) return { key, label, suppressed: true };
      return {
        key,
        label,
        suppressed: false,
        headcount,
        episodes: n(r.episodes),
        daysLost: n(r.daysLost),
        absenceRatePct: n(r.absenceRatePct),
        frequencyRate: n(r.frequencyRate),
        avgDurationDays: n(r.avgDurationDays),
        bradfordMean: n(r.bradfordMean),
      };
    })
    .filter(defined)
    .sort((a, b) => a.label.localeCompare(b.label));
  return { minGroupSize: k, rows };
}

export const suppressedText = (k: number) => `fewer than ${k} – suppressed`;

/** Numbers for the analytics table (one decimal for rates). */
export function formatMetric(v: number | undefined, decimals = 0): string {
  if (v === undefined) return '–';
  return v.toLocaleString(undefined, { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

export interface AnalyticsQuery {
  groupBy: AnalyticsGroupBy;
  from: string;
  to: string;
}

export function validateAnalyticsQuery(q: AnalyticsQuery): FieldErrors {
  const e: FieldErrors = {};
  if (q.from && !DATE.test(q.from)) e.from = 'Please enter a valid start date.';
  if (q.to && !DATE.test(q.to)) e.to = 'Please enter a valid end date.';
  if (!e.from && !e.to && q.from && q.to && q.to < q.from) e.to = 'The end date must be on or after the start date.';
  return e;
}

export function analyticsUnitsPath(q: AnalyticsQuery): string {
  const p = new URLSearchParams({ groupBy: q.groupBy });
  if (q.from) p.set('from', q.from);
  if (q.to) p.set('to', q.to);
  return `/absence/analytics/units?${p.toString()}`;
}

/** Previous calendar month `YYYY-MM` (the latest complete monthly pack). */
export function previousMonth(now = new Date()): string {
  const d = new Date(now.getFullYear(), now.getMonth() - 1, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

export const isValidMonth = (m: string) => MONTH.test(m);

/** Browser URL of the monthly CSV pack (`text/csv`, suppression applied server-side) – downloaded via a link. */
export const analyticsExportUrl = (month: string) => `${BFF_API_PREFIX}/absence/analytics/export?month=${encodeURIComponent(month)}`;
