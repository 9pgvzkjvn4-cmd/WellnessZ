/**
 * E04 client-side helpers: plan-from-template expansion, plan / referral data-set / outcome / administration / telehealth
 * form checks, provider filtering and ranking, referral timeline, variance and stock flags. Pure functions only
 * (unit-tested in increment4.test.ts). The server re-validates everything.
 */
import type {
  FollowUpType,
  FunctionScale,
  InterventionType,
  Outcome,
  OutcomesResponse,
  PlanBody,
  PlanTemplate,
  PlanVariance,
  Provider,
  Referral,
  ReferralDetail,
  ReferralService,
  ReferralStatus,
  StockRow,
  TelehealthSession,
} from '../api/treatment';
import { ApiError } from '../auth/AuthClient';
import { daysUntil, todayIso, type Tone } from './absence';
import type { FieldError } from './devices';

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
export const isIsoDay = (v: string): boolean => ISO_DATE.test(v) && !Number.isNaN(Date.parse(`${v}T00:00:00Z`));

export function addDays(day: string, n: number): string {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

// ---------------------------------------------------------------- treatment plan form
export interface InterventionRow {
  type: InterventionType;
  description: string;
  frequency: string;
  startDate: string;
  endDate: string;
  providerCode: string;
}
export interface FollowUpRow {
  dueDate: string;
  type: FollowUpType;
  siteCode: string;
  book: boolean;
}
export interface PlanForm {
  templateCode: string;
  goals: string[];
  interventions: InterventionRow[];
  followUps: FollowUpRow[];
  expectedRecoveryDays: string;
  guidelineCode: string;
  varianceThresholdPct: string;
}

export const emptyIntervention = (today: string = todayIso()): InterventionRow => ({ type: 'SELF_CARE', description: '', frequency: '', startDate: today, endDate: '', providerCode: '' });
export const emptyFollowUp = (today: string = todayIso()): FollowUpRow => ({ dueDate: addDays(today, 14), type: 'OH_REVIEW', siteCode: '', book: false });

export function emptyPlanForm(today: string = todayIso()): PlanForm {
  return { templateCode: '', goals: [''], interventions: [emptyIntervention(today)], followUps: [emptyFollowUp(today)], expectedRecoveryDays: '', guidelineCode: '', varianceThresholdPct: '' };
}

const FOLLOW_UP_FOR: Partial<Record<InterventionType, FollowUpType>> = { PHYSIOTHERAPY: 'PHYSIOTHERAPY', SPECIALIST_REVIEW: 'SPECIALIST' };

/**
 * Template → editable plan: goals copied, each intervention starts today and ends after its `weeks`, follow-ups on
 * `today + followUpDays[i]` (OH review, except a physio-only template whose follow-ups are physio sessions).
 */
export function planFromTemplate(t: PlanTemplate, today: string = todayIso()): PlanForm {
  const onlyType = new Set(t.interventions.map((i) => i.type));
  const fuType: FollowUpType = onlyType.size === 1 ? (FOLLOW_UP_FOR[[...onlyType][0]!] ?? 'OH_REVIEW') : 'OH_REVIEW';
  return {
    templateCode: t.code,
    goals: t.goals.length ? [...t.goals] : [''],
    interventions: t.interventions.map((i) => ({
      type: i.type,
      description: i.description,
      frequency: i.frequency ?? '',
      startDate: today,
      endDate: i.weeks ? addDays(today, i.weeks * 7) : '',
      providerCode: '',
    })),
    followUps: [...t.followUpDays].sort((a, b) => a - b).map((d) => ({ dueDate: addDays(today, d), type: fuType, siteCode: '', book: false })),
    expectedRecoveryDays: t.expectedDays !== undefined ? String(t.expectedDays) : '',
    guidelineCode: t.guidelineCode ?? '',
    varianceThresholdPct: '',
  };
}

export const planFieldId = (key: string) => `plan-${key}`;

export function validatePlanForm(f: PlanForm): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.goals.some((g) => g.trim())) errs.push({ field: planFieldId('goal-0'), message: 'Add at least one goal' });
  f.interventions.forEach((i, idx) => {
    if (!i.description.trim()) errs.push({ field: planFieldId(`int-${idx}-description`), message: `Intervention ${idx + 1}: describe the intervention` });
    if (i.startDate && !isIsoDay(i.startDate)) errs.push({ field: planFieldId(`int-${idx}-startDate`), message: `Intervention ${idx + 1}: start date is not a valid date` });
    if (i.endDate && i.startDate && i.endDate < i.startDate) errs.push({ field: planFieldId(`int-${idx}-endDate`), message: `Intervention ${idx + 1}: end date is before the start date` });
  });
  f.followUps.forEach((fu, idx) => {
    if (!isIsoDay(fu.dueDate)) errs.push({ field: planFieldId(`fu-${idx}-dueDate`), message: `Follow-up ${idx + 1}: enter a due date` });
    if (fu.book && !fu.siteCode.trim()) errs.push({ field: planFieldId(`fu-${idx}-siteCode`), message: `Follow-up ${idx + 1}: choose a clinic site to book` });
  });
  if (f.expectedRecoveryDays.trim() && !/^\d{1,3}$/.test(f.expectedRecoveryDays.trim())) errs.push({ field: planFieldId('expectedRecoveryDays'), message: 'Expected recovery: whole number of days (0–999)' });
  if (f.varianceThresholdPct.trim()) {
    const n = Number(f.varianceThresholdPct);
    if (!/^\d+$/.test(f.varianceThresholdPct.trim()) || n < 1 || n > 500) errs.push({ field: planFieldId('varianceThresholdPct'), message: 'Variance threshold: whole number 1–500 %' });
  }
  return errs;
}

export function toPlanBody(f: PlanForm): PlanBody {
  const opt = (v: string) => (v.trim() ? v.trim() : undefined);
  return {
    ...(opt(f.templateCode) ? { templateCode: f.templateCode } : {}),
    goals: f.goals.map((g) => g.trim()).filter(Boolean),
    interventions: f.interventions
      .filter((i) => i.description.trim())
      .map((i) => ({
        type: i.type,
        description: i.description.trim(),
        ...(opt(i.frequency) ? { frequency: i.frequency.trim() } : {}),
        ...(opt(i.startDate) ? { startDate: i.startDate } : {}),
        ...(opt(i.endDate) ? { endDate: i.endDate } : {}),
        ...(opt(i.providerCode) ? { providerCode: i.providerCode } : {}),
      })),
    followUps: f.followUps.map((fu) => ({ dueDate: fu.dueDate, type: fu.type, ...(opt(fu.siteCode) ? { siteCode: fu.siteCode } : {}), ...(fu.book ? { book: true } : {}) })),
    ...(opt(f.expectedRecoveryDays) ? { expectedRecoveryDays: Number(f.expectedRecoveryDays) } : {}),
    ...(opt(f.guidelineCode) ? { guidelineCode: f.guidelineCode.trim() } : {}),
    ...(opt(f.varianceThresholdPct) ? { varianceThresholdPct: Number(f.varianceThresholdPct) } : {}),
  };
}

/** Variance indicator: alert (threshold crossed) red, over guideline amber, within guideline green. */
export function varianceTone(v: PlanVariance | null | undefined): { tone: Tone; label: string } {
  if (!v) return { tone: 'muted', label: 'No guideline' };
  if (v.alert) return { tone: 'red', label: `${v.pctOver.toFixed(0)} % over guideline (day ${v.actualDays} of ${v.expectedDays})` };
  if (v.pctOver > 0) return { tone: 'amber', label: `${v.pctOver.toFixed(0)} % over guideline (day ${v.actualDays} of ${v.expectedDays})` };
  return { tone: 'green', label: `Within guideline (day ${v.actualDays} of ${v.expectedDays})` };
}

// ---------------------------------------------------------------- referrals
export const dataSetFieldId = (code: string) => `ds-${code}`;

/** Required data set per service (contract §3.2 `requiredFields`): every field required; DATE = YYYY-MM-DD; CODE = token. */
export function validateDataSet(service: ReferralService | undefined, dataSet: Record<string, string>): FieldError[] {
  if (!service) return [];
  const errs: FieldError[] = [];
  for (const f of service.requiredFields) {
    const v = (dataSet[f.code] ?? '').trim();
    if (!v) {
      errs.push({ field: dataSetFieldId(f.code), message: `${f.label}: required for ${service.name}` });
      continue;
    }
    if (f.type === 'DATE' && !isIsoDay(v)) errs.push({ field: dataSetFieldId(f.code), message: `${f.label}: enter a valid date` });
    if (f.type === 'CODE' && !/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(v)) errs.push({ field: dataSetFieldId(f.code), message: `${f.label}: letters, digits, dot, dash or underscore only` });
  }
  return errs;
}

/** Only the service's required fields, trimmed (stale keys from a previously chosen service are dropped). */
export function cleanDataSet(service: ReferralService | undefined, dataSet: Record<string, string>): Record<string, string> {
  if (!service) return {};
  return Object.fromEntries(service.requiredFields.map((f) => [f.code, (dataSet[f.code] ?? '').trim()]).filter(([, v]) => v));
}

export const WIZARD_STEPS = ['service', 'provider', 'data', 'consent', 'channel', 'attachments'] as const;
export type WizardStep = (typeof WIZARD_STEPS)[number];
export const WIZARD_LABELS: Record<WizardStep, string> = { service: 'Service', provider: 'Provider', data: 'Data set', consent: 'Consent', channel: 'Channel', attachments: 'Attachments & review' };

export interface ReferralForm {
  serviceCode: string;
  urgency: 'ROUTINE' | 'URGENT';
  providerCode: string;
  reason: string;
  question: string;
  dataSet: Record<string, string>;
  consentId: string;
  channel: 'PORTAL' | 'FHIR' | 'SECURE_EMAIL';
  attachDocumentIds: string[];
}
export const emptyReferralForm = (): ReferralForm => ({ serviceCode: '', urgency: 'ROUTINE', providerCode: '', reason: '', question: '', dataSet: {}, consentId: '', channel: 'PORTAL', attachDocumentIds: [] });

/** Errors that block leaving a wizard step. */
export function validateWizardStep(step: WizardStep, f: ReferralForm, service: ReferralService | undefined): FieldError[] {
  switch (step) {
    case 'service': {
      const e: FieldError[] = [];
      if (!f.serviceCode) e.push({ field: 'rf-service', message: 'Choose a service' });
      if (!f.reason.trim()) e.push({ field: 'rf-reason', message: 'Enter the reason for referral' });
      if (!f.question.trim()) e.push({ field: 'rf-question', message: 'Enter the clinical question for the provider' });
      return e;
    }
    case 'provider':
      return f.providerCode ? [] : [{ field: 'rf-provider', message: 'Choose a provider' }];
    case 'data':
      return validateDataSet(service, f.dataSet);
    case 'consent':
      return f.consentId.trim() ? [] : [{ field: 'rf-consent', message: "Enter the employee's treatment-sharing consent reference – the provider cannot receive the referral without it" }];
    default:
      return [];
  }
}

/** Contracted = ACTIVE or EXPIRING contract. */
export const isContracted = (p: Pick<Provider, 'contract'>): boolean => p.contract?.status === 'ACTIVE' || p.contract?.status === 'EXPIRING';

export interface ProviderQuery {
  serviceCode?: string;
  city?: string;
  contract?: '' | 'CONTRACTED' | 'ACTIVE' | 'EXPIRING' | 'EXPIRED' | 'NONE';
  preferredOnly?: boolean;
  q?: string;
}

/**
 * Client-side filter (the server filters too – this keeps the wizard list responsive while typing) and ranking:
 * preferred first, then contracted, then best scorecard, then name.
 */
export function filterProviders(list: readonly Provider[], f: ProviderQuery): Provider[] {
  const q = f.q?.trim().toLowerCase();
  const city = f.city?.trim().toLowerCase();
  return list
    .filter((p) => !f.serviceCode || p.services.includes(f.serviceCode))
    .filter((p) => !city || (p.city ?? '').toLowerCase() === city)
    .filter((p) => !f.preferredOnly || p.preferred)
    .filter((p) => {
      if (!f.contract) return true;
      if (f.contract === 'CONTRACTED') return isContracted(p);
      return (p.contract?.status ?? 'NONE') === f.contract;
    })
    .filter((p) => !q || [p.name, p.code, p.city ?? '', ...p.specialties].some((s) => s.toLowerCase().includes(q)))
    .sort(
      (a, b) =>
        Number(b.preferred) - Number(a.preferred) ||
        Number(isContracted(b)) - Number(isContracted(a)) ||
        (b.scorecard?.overall ?? -1) - (a.scorecard?.overall ?? -1) ||
        a.name.localeCompare(b.name),
    );
}

export function contractTone(status: string | undefined): Tone {
  switch (status) {
    case 'ACTIVE': return 'green';
    case 'EXPIRING': return 'amber';
    case 'EXPIRED': return 'red';
    default: return 'muted';
  }
}

/** Scorecard 0–100 → tone (≥ 80 green, ≥ 60 amber, else red). */
export function scoreTone(score: number | undefined | null): Tone {
  if (score === undefined || score === null || Number.isNaN(score)) return 'muted';
  return score >= 80 ? 'green' : score >= 60 ? 'amber' : 'red';
}

export function referralTone(r: Pick<Referral, 'status' | 'overdue' | 'escalated'>): Tone {
  if (r.escalated || r.overdue) return 'red';
  switch (r.status) {
    case 'DECLINED':
    case 'CANCELLED': return 'muted';
    case 'REPORT_RECEIVED':
    case 'CLOSED': return 'green';
    case 'DRAFT': return 'muted';
    default: return 'amber';
  }
}

export const REFERRAL_FLOW: readonly ReferralStatus[] = ['DRAFT', 'SENT', 'ACCEPTED', 'SCHEDULED', 'SEEN', 'REPORT_RECEIVED', 'CLOSED'];

export interface TimelineStep {
  status: ReferralStatus;
  at?: string;
  by?: string;
  state: 'done' | 'current' | 'todo' | 'stopped';
}

/**
 * Status timeline: the happy path with timestamps from `history` (falling back to the referral's own timestamps);
 * a DECLINED / CANCELLED referral ends with that status.
 */
export function referralTimeline(r: Pick<ReferralDetail, 'status' | 'history' | 'createdAt' | 'sentAt' | 'acceptedAt' | 'appointmentAt' | 'seenAt' | 'reportReceivedAt'>): TimelineStep[] {
  const fallback: Partial<Record<ReferralStatus, string | undefined>> = { DRAFT: r.createdAt, SENT: r.sentAt, ACCEPTED: r.acceptedAt, SCHEDULED: r.appointmentAt, SEEN: r.seenAt, REPORT_RECEIVED: r.reportReceivedAt };
  const hist = new Map<ReferralStatus, { at: string; by?: string }>();
  for (const h of r.history ?? []) hist.set(h.status, { at: h.at, by: h.by });
  const stopped = r.status === 'DECLINED' || r.status === 'CANCELLED';
  const reachedIdx = stopped
    ? Math.max(...REFERRAL_FLOW.map((s, i) => (hist.has(s) || fallback[s] ? i : -1)))
    : REFERRAL_FLOW.indexOf(r.status);
  const steps: TimelineStep[] = REFERRAL_FLOW.map((s, i) => ({
    status: s,
    at: hist.get(s)?.at ?? (i <= reachedIdx ? fallback[s] : undefined),
    by: hist.get(s)?.by,
    state: i < reachedIdx || (i === reachedIdx && (stopped || s === 'CLOSED')) ? 'done' : i === reachedIdx ? 'current' : 'todo',
  }));
  if (stopped) {
    const cut = steps.slice(0, reachedIdx + 1);
    cut.push({ status: r.status, at: hist.get(r.status)?.at, by: hist.get(r.status)?.by, state: 'stopped' });
    return cut;
  }
  return steps;
}

/** Report due countdown in whole days (negative = overdue) for open referrals. */
export function reportDueLabel(r: Pick<Referral, 'reportDueAt' | 'reportReceivedAt' | 'status' | 'overdue'>, today: string = todayIso()): { tone: Tone; label: string } | undefined {
  if (!r.reportDueAt || r.reportReceivedAt || ['REPORT_RECEIVED', 'CLOSED', 'CANCELLED', 'DECLINED'].includes(r.status)) return undefined;
  const d = daysUntil(r.reportDueAt.slice(0, 10), today);
  if (r.overdue || d < 0) return { tone: 'red', label: d < 0 ? `Report ${-d} d overdue` : 'Report overdue' };
  if (d <= 2) return { tone: 'amber', label: d === 0 ? 'Report due today' : `Report due in ${d} d` };
  return { tone: 'green', label: `Report due in ${d} d` };
}

// ---------------------------------------------------------------- outcomes
/** For ODI and QuickDASH a higher score means more disability; PSFS and LEFS are higher-is-better. */
export const LOWER_IS_BETTER: readonly FunctionScale[] = ['ODI', 'QUICKDASH'];

export function normaliseOutcomes(resp: OutcomesResponse | undefined): Outcome[] {
  if (!resp) return [];
  const list = Array.isArray(resp) ? resp : (resp.outcomes ?? resp.items ?? []);
  return [...list].sort((a, b) => (a.recordedAt ?? '').localeCompare(b.recordedAt ?? ''));
}

export interface Improvement {
  /** Positive = less pain (NPRS points). */
  painImprovement?: number;
  /** Positive = better function (direction-corrected for the scale). */
  functionImprovement?: number;
  scale?: FunctionScale;
  from?: Outcome;
  to?: Outcome;
}

/** Baseline (first BASELINE, else earliest) vs latest outcome. */
export function outcomeImprovement(outcomes: readonly Outcome[]): Improvement {
  if (outcomes.length < 2) return {};
  const from = outcomes.find((o) => o.milestone === 'BASELINE') ?? outcomes[0]!;
  const to = outcomes[outcomes.length - 1]!;
  if (from === to) return {};
  const res: Improvement = { from, to, painImprovement: from.painScore - to.painScore };
  if (from.functionScale === to.functionScale && from.functionScale !== 'NONE' && typeof from.functionScore === 'number' && typeof to.functionScore === 'number') {
    const raw = to.functionScore - from.functionScore;
    res.functionImprovement = LOWER_IS_BETTER.includes(from.functionScale) ? -raw : raw;
    res.scale = from.functionScale;
  }
  return res;
}

export function describeImprovement(i: Improvement): string[] {
  const out: string[] = [];
  if (i.painImprovement !== undefined) {
    out.push(i.painImprovement > 0 ? `Pain down ${i.painImprovement} point${i.painImprovement === 1 ? '' : 's'} (NPRS)` : i.painImprovement < 0 ? `Pain up ${-i.painImprovement} point${i.painImprovement === -1 ? '' : 's'} (NPRS)` : 'Pain unchanged');
  }
  if (i.functionImprovement !== undefined && i.scale) {
    const n = Math.abs(i.functionImprovement);
    out.push(i.functionImprovement > 0 ? `Function improved by ${n} (${i.scale})` : i.functionImprovement < 0 ? `Function worse by ${n} (${i.scale})` : `Function unchanged (${i.scale})`);
  }
  return out;
}

export const hasClosureOutcome = (outcomes: readonly Pick<Outcome, 'milestone'>[]): boolean => outcomes.some((o) => o.milestone === 'CLOSURE');

export interface OutcomeForm {
  milestone: 'BASELINE' | 'REVIEW' | 'CLOSURE';
  painScore: string;
  functionScale: FunctionScale;
  functionScore: string;
  rtwDate: string;
  residualImpairmentPct: string;
  note: string;
}
export const emptyOutcomeForm = (milestone: OutcomeForm['milestone'] = 'BASELINE'): OutcomeForm => ({ milestone, painScore: '', functionScale: 'NONE', functionScore: '', rtwDate: '', residualImpairmentPct: '', note: '' });

const intIn = (v: string, min: number, max: number) => /^\d+$/.test(v.trim()) && Number(v) >= min && Number(v) <= max;

export function validateOutcome(f: OutcomeForm): FieldError[] {
  const errs: FieldError[] = [];
  if (!intIn(f.painScore, 0, 10)) errs.push({ field: 'oc-pain', message: 'Pain (NPRS): whole number 0–10' });
  if (f.functionScale !== 'NONE' && !intIn(f.functionScore, 0, 100)) errs.push({ field: 'oc-function', message: 'Function score: whole number 0–100' });
  if (f.rtwDate && !isIsoDay(f.rtwDate)) errs.push({ field: 'oc-rtw', message: 'Return-to-work date is not a valid date' });
  if (f.residualImpairmentPct.trim() && !intIn(f.residualImpairmentPct, 0, 100)) errs.push({ field: 'oc-impairment', message: 'Residual impairment: whole number 0–100 %' });
  return errs;
}

// ---------------------------------------------------------------- medications
export interface StockFlag {
  tone: Tone;
  label: string;
}

/** Expired (server flag or past expiry), expires within 30 days, below reorder level. */
export function stockFlags(row: Pick<StockRow, 'expired' | 'expiry' | 'belowReorder'>, today: string = todayIso()): StockFlag[] {
  const flags: StockFlag[] = [];
  const left = isIsoDay(row.expiry.slice(0, 10)) ? daysUntil(row.expiry.slice(0, 10), today) : undefined;
  if (row.expired || (left !== undefined && left < 0)) flags.push({ tone: 'red', label: 'Expired – do not use' });
  else if (left !== undefined && left <= 30) flags.push({ tone: 'amber', label: `Expires in ${left} d` });
  if (row.belowReorder) flags.push({ tone: 'amber', label: 'Below reorder level' });
  return flags;
}

/** Usable batches for an item at a site (not expired, quantity > 0), soonest expiry first (FEFO). */
export function usableBatches<T extends Pick<StockRow, 'itemCode' | 'siteCode' | 'expired' | 'expiry' | 'quantity'>>(stock: readonly T[], itemCode: string, siteCode: string, today: string = todayIso()): T[] {
  return stock
    .filter((s) => s.itemCode === itemCode && (!siteCode || s.siteCode === siteCode) && !s.expired && s.quantity > 0 && s.expiry.slice(0, 10) >= today)
    .sort((a, b) => a.expiry.localeCompare(b.expiry));
}

export const isAllergyAlert = (e: unknown): e is ApiError => e instanceof ApiError && e.status === 422 && e.problem?.code === 'allergy_alert';
export const problemCode = (e: unknown): string | undefined => (e instanceof ApiError ? e.problem?.code : undefined);

// ---------------------------------------------------------------- telehealth
const JOIN_LEAD_MS = 15 * 60_000;

/** Join window (contract §3.7): from 15 minutes before the start until the scheduled end. */
export function joinWindow(s: Pick<TelehealthSession, 'scheduledAt' | 'durationMinutes' | 'status'>, now: number = Date.now()): { open: boolean; label: string } {
  if (s.status !== 'SCHEDULED' && s.status !== 'IN_PROGRESS') return { open: false, label: '' };
  const start = Date.parse(s.scheduledAt);
  const end = start + s.durationMinutes * 60_000;
  if (Number.isNaN(start)) return { open: false, label: '' };
  if (now < start - JOIN_LEAD_MS) {
    const mins = Math.ceil((start - JOIN_LEAD_MS - now) / 60_000);
    return { open: false, label: mins > 120 ? `Join opens ${Math.round(mins / 60)} h before…` : `Join opens in ${mins} min` };
  }
  if (now > end) return { open: false, label: 'Session time has passed' };
  return { open: true, label: 'Join now' };
}

export interface SessionForm {
  employeeId: string;
  caseId: string;
  scheduledAt: string;
  durationMinutes: string;
  mode: 'VIDEO' | 'CHAT';
  clinicianUsername: string;
  providerCode: string;
  crewLayover: boolean;
  layoverCity: string;
}

export function validateSessionForm(f: SessionForm, now: number = Date.now()): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.employeeId.trim()) errs.push({ field: 'th-employee', message: 'Enter the employee ID' });
  const at = Date.parse(f.scheduledAt);
  if (!f.scheduledAt || Number.isNaN(at)) errs.push({ field: 'th-at', message: 'Enter the session start' });
  else if (at < now - 5 * 60_000) errs.push({ field: 'th-at', message: 'The session start is in the past' });
  if (!intIn(f.durationMinutes, 10, 90)) errs.push({ field: 'th-duration', message: 'Duration: 10–90 minutes' });
  if (f.crewLayover && !f.layoverCity.trim()) errs.push({ field: 'th-city', message: 'Crew layover: enter the layover city' });
  return errs;
}
