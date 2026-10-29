/**
 * HR-only increment-2 features (docs/increment-02/API-Contract-E03-E05.md §3.1, §5.3, §5.7):
 * electronic alternative-duty job offers (E05-BL-008) and ill-health retirement / redeployment status + appeals
 * (E05-F07). HR sees IHR `{type, status, decisionText, dates}` only – findings, recommendation and notes are dropped.
 */
import { list, n, s, toFunctionalRestrictions, type FunctionalRestriction } from './fitness';
import type { FieldErrors } from './referrals';

const obj = (v: unknown): Record<string, unknown> | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined);
const defined = <T>(x: T | undefined): x is T => x !== undefined;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const tooLong = (max: number) => `Please use at most ${max} characters.`;

/* ---------------- master data ---------------- */

/** `GET /masterdata/restrictions` – functional catalogue (inactive entries are dropped). */
export interface CatalogueRestriction {
  code: string;
  label: string;
  valueRequired: boolean;
  unit?: string;
}

export function toRestrictionCatalogue(body: unknown): CatalogueRestriction[] {
  return list(body)
    .map((raw) => {
      const r = obj(raw);
      const code = s(r?.code);
      if (!r || !code || r.active === false) return undefined;
      return { code, label: s(r.label) ?? code, valueRequired: r.valueRequired === true, unit: s(r.unit) };
    })
    .filter(defined);
}

/** `GET /masterdata/job-demands` – only code, title and unit are needed for an offer. */
export interface JobProfile {
  jobCode: string;
  title: string;
  unit?: string;
}

export function toJobProfiles(body: unknown): JobProfile[] {
  return list(body)
    .map((raw) => {
      const r = obj(raw);
      const jobCode = s(r?.jobCode);
      return r && jobCode ? { jobCode, title: s(r.title) ?? jobCode, unit: s(r.unit) } : undefined;
    })
    .filter(defined)
    .sort((a, b) => a.title.localeCompare(b.title));
}

/* ---------------- job offers (E05-BL-008) ---------------- */

export const PAY_BASES = ['SAME_PAY', 'PROTECTED_PAY', 'ADJUSTED_PAY'] as const;
export type PayBasis = (typeof PAY_BASES)[number];
export const PAY_BASIS_LABELS: Record<PayBasis, string> = {
  SAME_PAY: 'Same pay',
  PROTECTED_PAY: 'Protected pay',
  ADJUSTED_PAY: 'Adjusted pay',
};

export const OFFER_STATUSES = ['ISSUED', 'ACCEPTED', 'DECLINED', 'WITHDRAWN', 'EXPIRED'] as const;

/** Request fields of JobOfferRequest – a 422 `field` outside this list is shown as a general error only. */
export const JOB_OFFER_FIELDS = [
  'jobCode',
  'jobTitle',
  'duties',
  'location',
  'shift',
  'hoursPerWeek',
  'payBasis',
  'payNote',
  'restrictions',
  'startDate',
  'endDate',
  'contactName',
  'contactEmail',
  'respondBy',
] as const;
export type JobOfferField = (typeof JOB_OFFER_FIELDS)[number];

/** Not pinned by the contract except `duties` (≤ 1000, as for duty proposals) – client-side limits. */
export const OFFER_LIMITS = { jobTitle: 200, duties: 1000, location: 200, shift: 200, payNote: 500, contactName: 200, contactEmail: 254 } as const;
export const MAX_HOURS_PER_WEEK = 60;

export interface JobOfferRequest {
  jobCode: string;
  jobTitle: string;
  duties: string;
  location: string;
  shift: string;
  hoursPerWeek: number;
  payBasis: PayBasis;
  payNote?: string;
  restrictions: { code: string; value?: number }[];
  startDate: string;
  endDate?: string;
  contactName: string;
  contactEmail: string;
  respondBy: string;
}

export interface JobOfferForm {
  jobCode: string;
  jobTitle: string;
  duties: string;
  location: string;
  shift: string;
  hoursPerWeek: string;
  payBasis: PayBasis | '';
  payNote: string;
  /** Selected catalogue restrictions with their value (as typed). */
  restrictions: { code: string; value: string }[];
  startDate: string;
  endDate: string;
  contactName: string;
  contactEmail: string;
  respondBy: string;
}

export const emptyJobOfferForm = (): JobOfferForm => ({
  jobCode: '',
  jobTitle: '',
  duties: '',
  location: '',
  shift: '',
  hoursPerWeek: '',
  payBasis: '',
  payNote: '',
  restrictions: [],
  startDate: '',
  endDate: '',
  contactName: '',
  contactEmail: '',
  respondBy: '',
});

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const POSITIVE = /^\d+(\.\d+)?$/;

/** `today` is `YYYY-MM-DD` (injected for tests). */
export function validateJobOffer(f: JobOfferForm, catalogue: readonly CatalogueRestriction[], today: string): FieldErrors {
  const e: FieldErrors = {};
  if (!f.jobCode) e.jobCode = 'Please choose the job from the job-demand profiles.';
  if (!f.jobTitle.trim()) e.jobTitle = 'Please enter the job title.';
  if (!f.duties.trim()) e.duties = 'Please describe the duties offered.';
  if (!f.location.trim()) e.location = 'Please enter the work location.';
  if (!f.shift.trim()) e.shift = 'Please describe the shift pattern.';
  const hours = f.hoursPerWeek.trim();
  if (!POSITIVE.test(hours) || Number(hours) <= 0 || Number(hours) > MAX_HOURS_PER_WEEK) e.hoursPerWeek = `Please enter hours per week between 1 and ${MAX_HOURS_PER_WEEK}.`;
  if (!f.payBasis) e.payBasis = 'Please choose the pay basis.';
  if (f.payBasis === 'ADJUSTED_PAY' && !f.payNote.trim()) e.payNote = 'Please explain the adjusted pay.';
  for (const r of f.restrictions) {
    const c = catalogue.find((x) => x.code === r.code);
    if (!c) e.restrictions = 'Please choose restrictions from the catalogue only.';
    else if (c.valueRequired && (!POSITIVE.test(r.value.trim()) || Number(r.value) <= 0)) e.restrictions = `Please enter a value for "${c.label.replace(/\{value\}/g, '…')}".`;
    if (e.restrictions) break;
  }
  if (!DATE.test(f.startDate)) e.startDate = 'Please enter the start date.';
  if (f.endDate && !DATE.test(f.endDate)) e.endDate = 'Please enter a valid end date.';
  else if (f.endDate && DATE.test(f.startDate) && f.endDate < f.startDate) e.endDate = 'The end date must be on or after the start date.';
  if (!f.contactName.trim()) e.contactName = 'Please enter a contact name.';
  if (!EMAIL.test(f.contactEmail.trim())) e.contactEmail = 'Please enter a valid e-mail address.';
  if (!DATE.test(f.respondBy)) e.respondBy = 'Please enter the date by which the employee should respond.';
  else if (f.respondBy < today) e.respondBy = 'The response date cannot be in the past.';
  else if (DATE.test(f.startDate) && f.respondBy > f.startDate) e.respondBy = 'The response date must be on or before the start date.';
  for (const k of Object.keys(OFFER_LIMITS) as (keyof typeof OFFER_LIMITS)[]) {
    if (!e[k] && f[k].length > OFFER_LIMITS[k]) e[k] = tooLong(OFFER_LIMITS[k]);
  }
  return e;
}

export function buildJobOffer(f: JobOfferForm, catalogue: readonly CatalogueRestriction[]): JobOfferRequest {
  const body: JobOfferRequest = {
    jobCode: f.jobCode,
    jobTitle: f.jobTitle.trim(),
    duties: f.duties.trim(),
    location: f.location.trim(),
    shift: f.shift.trim(),
    hoursPerWeek: Number(f.hoursPerWeek.trim()),
    payBasis: f.payBasis as PayBasis,
    restrictions: f.restrictions.map((r) =>
      catalogue.find((c) => c.code === r.code)?.valueRequired ? { code: r.code, value: Number(r.value.trim()) } : { code: r.code },
    ),
    startDate: f.startDate,
    contactName: f.contactName.trim(),
    contactEmail: f.contactEmail.trim(),
    respondBy: f.respondBy,
  };
  if (f.payNote.trim()) body.payNote = f.payNote.trim();
  if (f.endDate) body.endDate = f.endDate;
  return body;
}

export interface JobOffer {
  id: string;
  status: string;
  employeeName?: string;
  jobCode?: string;
  jobTitle?: string;
  duties?: string;
  location?: string;
  shift?: string;
  hoursPerWeek?: number;
  payBasis?: string;
  payNote?: string;
  restrictions: FunctionalRestriction[];
  startDate?: string;
  endDate?: string;
  contactName?: string;
  contactEmail?: string;
  respondBy?: string;
  issuedAt?: string;
  respondedAt?: string;
  documentId?: string;
}

/** The employee's free-text response `comment` is not shown in the workspace (status only). */
export function toJobOffers(body: unknown): JobOffer[] {
  return list(body)
    .map((raw): JobOffer | undefined => {
      const r = obj(raw);
      const id = s(r?.id);
      const status = s(r?.status);
      if (!r || !id || !status) return undefined;
      return {
        id,
        status,
        employeeName: s(r.employeeName),
        jobCode: s(r.jobCode),
        jobTitle: s(r.jobTitle),
        duties: s(r.duties),
        location: s(r.location),
        shift: s(r.shift),
        hoursPerWeek: n(r.hoursPerWeek),
        payBasis: s(r.payBasis),
        payNote: s(r.payNote),
        restrictions: toFunctionalRestrictions(r.restrictions),
        startDate: s(r.startDate),
        endDate: s(r.endDate),
        contactName: s(r.contactName),
        contactEmail: s(r.contactEmail),
        respondBy: s(r.respondBy),
        issuedAt: s(r.issuedAt),
        respondedAt: s(r.respondedAt),
        documentId: s(r.documentId),
      };
    })
    .filter(defined);
}

export const canWithdrawOffer = (o: JobOffer) => o.status === 'ISSUED';

/* ---------------- ill-health retirement / redeployment (E05-F07) ---------------- */

export const IHR_TYPES = ['ILL_HEALTH_RETIREMENT', 'REDEPLOYMENT'] as const;
export const IHR_STATUSES = ['DRAFT', 'AWAITING_CMO', 'DECIDED', 'APPEALED', 'APPEAL_DECIDED', 'CLOSED'] as const;
export const APPEAL_WINDOW_DAYS = 28;
export const APPEAL_DECISION_DAYS = 30;

/** HR projection: `{type, status, decisionText, dates}` only. */
export interface IhrAssessment {
  id: string;
  type: string;
  status: string;
  decisionText?: string;
  createdAt?: string;
  decidedAt?: string;
  /** Last day to appeal (server value, else decidedAt + 28 days). */
  appealDeadline?: string;
  appealedAt?: string;
  /** Appeal decision due (server value, else appealedAt + 30 days). */
  appealDecisionDueAt?: string;
  appealDecidedAt?: string;
}

/** `YYYY-MM-DD` of an ISO date/timestamp plus `days` (UTC). */
export function addDays(iso: string, days: number): string | undefined {
  const d = new Date(DATE.test(iso) ? `${iso}T00:00:00Z` : iso);
  if (Number.isNaN(d.getTime())) return undefined;
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

export function toIhrAssessments(body: unknown): IhrAssessment[] {
  return list(body)
    .map((raw): IhrAssessment | undefined => {
      const r = obj(raw);
      const id = s(r?.id);
      const type = s(r?.type);
      const status = s(r?.status);
      if (!r || !id || !type || !status) return undefined;
      const decidedAt = s(r.decidedAt);
      const appealedAt = s(r.appealedAt);
      return {
        id,
        type,
        status,
        decisionText: s(r.decisionText),
        createdAt: s(r.createdAt),
        decidedAt,
        appealDeadline: s(r.appealDeadline) ?? s(r.appealWindowEndsAt) ?? s(r.appealDueBy) ?? (decidedAt ? addDays(decidedAt, APPEAL_WINDOW_DAYS) : undefined),
        appealedAt,
        appealDecisionDueAt: s(r.appealDecisionDueAt) ?? s(r.appealDecisionDue) ?? (appealedAt ? addDays(appealedAt, APPEAL_DECISION_DAYS) : undefined),
        appealDecidedAt: s(r.appealDecidedAt),
      };
    })
    .filter(defined);
}

/** An appeal can be lodged against a DECIDED assessment until the end of the appeal window. */
export const canAppeal = (a: IhrAssessment, today: string) => a.status === 'DECIDED' && (!a.appealDeadline || today <= a.appealDeadline.slice(0, 10));

export const APPEAL_FIELDS = ['grounds', 'secondOpinionBy'] as const;
export const APPEAL_LIMITS = { grounds: 2000, secondOpinionBy: 200 } as const;

export interface AppealForm {
  grounds: string;
  secondOpinionBy: string;
}
export const emptyAppealForm = (): AppealForm => ({ grounds: '', secondOpinionBy: '' });

export function validateAppeal(f: AppealForm): FieldErrors {
  const e: FieldErrors = {};
  if (!f.grounds.trim()) e.grounds = 'Please state the grounds for the appeal.';
  else if (f.grounds.length > APPEAL_LIMITS.grounds) e.grounds = tooLong(APPEAL_LIMITS.grounds);
  if (f.secondOpinionBy.length > APPEAL_LIMITS.secondOpinionBy) e.secondOpinionBy = tooLong(APPEAL_LIMITS.secondOpinionBy);
  return e;
}

export function buildAppeal(f: AppealForm): { grounds: string; secondOpinionBy?: string } {
  return f.secondOpinionBy.trim() ? { grounds: f.grounds.trim(), secondOpinionBy: f.secondOpinionBy.trim() } : { grounds: f.grounds.trim() };
}

/** Local calendar date as `YYYY-MM-DD`. */
export function todayIso(now = new Date()): string {
  const p = (x: number) => String(x).padStart(2, '0');
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`;
}
