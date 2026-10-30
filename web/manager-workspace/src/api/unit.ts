/**
 * Increment 5 – the manager's unit views (docs/increment-05/API-Contract-E09-E10-E11-E12.md §3.1, §3.5, §6):
 * - `GET /workspace/psychosocial` – the unit's psychosocial risk assessment and actions (read);
 * - `GET /workspace/psychosocial/shared-outcomes` – only what employees chose to share: fitness and, if chosen, adjustments;
 * - `GET /workspace/campaigns` – unit participation % with small-cell suppression;
 * - `GET /workspace/ergonomics` – unit ergonomic actions; MANAGER may set the status (`PUT …/actions/{actionId}`).
 * Mappers copy only the fields listed here; anything else (support-case data, scores of individuals, clinical text) is dropped.
 */
import { humanise } from '../components/format';
import { list, n, s } from './fitness';

const obj = (v: unknown): Record<string, unknown> | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined);
const defined = <T>(x: T | undefined): x is T => x !== undefined;
const enc = encodeURIComponent;

export const PSYCHOSOCIAL_PATH = '/workspace/psychosocial';
export const SHARED_OUTCOMES_PATH = '/workspace/psychosocial/shared-outcomes';
export const CAMPAIGNS_PATH = '/workspace/campaigns';
export const ERGONOMICS_PATH = '/workspace/ergonomics';
/** Default `wellnessz.analytics.min-group-size` (contract rule 4). */
export const DEFAULT_MIN_GROUP = 5;

/** Local `YYYY-MM-DD`. */
export function today(now: Date = new Date()): string {
  const p = (x: number) => String(x).padStart(2, '0');
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`;
}

/* ---------------------------------------------------------------- actions (shared by psychosocial + ergonomics) */

export type ActionState = 'DONE' | 'OVERDUE' | 'DUE_SOON' | 'OPEN';
export const ACTION_STATE_LABELS: Record<ActionState, string> = { DONE: 'Done', OVERDUE: 'Overdue', DUE_SOON: 'Due within 14 days', OPEN: 'Open' };
export const DUE_SOON_DAYS = 14;

export interface UnitAction {
  id: string;
  assessmentId?: string;
  title: string;
  owner?: string;
  dueDate?: string;
  status: 'OPEN' | 'DONE';
  /** Server says the session may mark this action done. */
  canComplete: boolean;
  context?: string;
}

export function actionState(a: Pick<UnitAction, 'status' | 'dueDate'>, now: Date = new Date()): ActionState {
  if (a.status === 'DONE') return 'DONE';
  if (!a.dueDate) return 'OPEN';
  const t = today(now);
  const due = a.dueDate.slice(0, 10);
  if (due < t) return 'OVERDUE';
  const soon = new Date(now.getFullYear(), now.getMonth(), now.getDate() + DUE_SOON_DAYS);
  return due <= today(soon) ? 'DUE_SOON' : 'OPEN';
}

const STATE_ORDER: Record<ActionState, number> = { OVERDUE: 0, DUE_SOON: 1, OPEN: 2, DONE: 3 };

export function sortActions(items: readonly UnitAction[], now: Date = new Date()): UnitAction[] {
  return [...items].sort((a, b) => STATE_ORDER[actionState(a, now)] - STATE_ORDER[actionState(b, now)] || (a.dueDate ?? '9').localeCompare(b.dueDate ?? '9'));
}

export function toAction(raw: unknown, assessmentId?: string, i = 0): UnitAction | undefined {
  const r = obj(raw);
  const title = s(r?.title);
  if (!r || !title) return undefined;
  const ctx = [s(r.assessmentType) ?? s(r.type), s(r.location), s(r.jobCode)].filter(Boolean).map((x) => (/^[A-Z_]+$/.test(x!) ? humanise(x!) : x)).join(' · ');
  return {
    id: s(r.id) ?? s(r.actionId) ?? `a-${i}`,
    assessmentId: s(r.assessmentId) ?? assessmentId,
    title,
    owner: s(r.owner),
    dueDate: s(r.dueDate),
    status: (s(r.status) ?? '').toUpperCase() === 'DONE' ? 'DONE' : 'OPEN',
    canComplete: r.canComplete === true || r.editable === true,
    context: ctx || undefined,
  };
}

export function summariseActions(items: readonly UnitAction[], now: Date = new Date()): Record<ActionState, number> {
  const c: Record<ActionState, number> = { OVERDUE: 0, DUE_SOON: 0, OPEN: 0, DONE: 0 };
  for (const a of items) c[actionState(a, now)] += 1;
  return c;
}

/* ---------------------------------------------------------------- psychosocial (§3.5) */

export const PSYCHOSOCIAL_FACTORS = ['WORKLOAD', 'SHIFT_FATIGUE', 'VIOLENCE_AGGRESSION', 'ISOLATION', 'CHANGE', 'ROLE_CLARITY', 'SUPPORT', 'RECOGNITION'] as const;
export const FACTOR_LABELS: Record<string, string> = {
  WORKLOAD: 'Workload and demands',
  SHIFT_FATIGUE: 'Shift work and fatigue',
  VIOLENCE_AGGRESSION: 'Violence and aggression',
  ISOLATION: 'Lone or isolated work',
  CHANGE: 'Organisational change',
  ROLE_CLARITY: 'Role clarity',
  SUPPORT: 'Support from managers and colleagues',
  RECOGNITION: 'Recognition and reward',
};
export const RAGS = ['RED', 'AMBER', 'GREEN'] as const;
export type Rag = (typeof RAGS)[number];
export const RAG_LABELS: Record<Rag, string> = { RED: 'High risk', AMBER: 'Medium risk', GREEN: 'Low risk' };

/** Likelihood × severity (1..5 each): ≥ 15 red, ≥ 8 amber (used only when the server sends no RAG). */
export function ragFor(score: number | undefined): Rag | undefined {
  if (score === undefined) return undefined;
  return score >= 15 ? 'RED' : score >= 8 ? 'AMBER' : 'GREEN';
}
const toRag = (v: unknown, score?: number): Rag | undefined => {
  const r = (s(v) ?? '').toUpperCase();
  return (RAGS as readonly string[]).includes(r) ? (r as Rag) : ragFor(score);
};

export interface FactorRow {
  factor: string;
  likelihood?: number;
  severity?: number;
  score?: number;
  rag?: Rag;
  controls?: string;
}

export interface UnitAssessment {
  id?: string;
  unitCode?: string;
  assessedOn?: string;
  reviewDate?: string;
  overallScore?: number;
  rag?: Rag;
  factors: FactorRow[];
  actions: UnitAction[];
}

export function toFactor(raw: unknown): FactorRow | undefined {
  const r = obj(raw);
  const factor = s(r?.factor);
  if (!r || !factor) return undefined;
  const likelihood = n(r.likelihood);
  const severity = n(r.severity);
  const score = n(r.score) ?? (likelihood !== undefined && severity !== undefined ? likelihood * severity : undefined);
  return { factor: factor.toUpperCase(), likelihood, severity, score, rag: toRag(r.rag, score), controls: s(r.controls) };
}

/** `{assessment, actions}`, a bare assessment, or a list (the most recent is used). No assessment → undefined. */
export function toUnitAssessment(body: unknown): UnitAssessment | undefined {
  const o = obj(body);
  const candidates = Array.isArray(body) ? body : o && Array.isArray(o.assessments) ? (o.assessments as unknown[]) : o ? [obj(o.assessment) ?? o] : [];
  const sorted = candidates.map(obj).filter(defined).sort((a, b) => (s(b.assessedOn) ?? '').localeCompare(s(a.assessedOn) ?? ''));
  const a = sorted[0];
  if (!a || (!s(a.assessedOn) && !Array.isArray(a.factors))) return undefined;
  const id = s(a.id);
  const actionsRaw = [...list(a.actions), ...(o && obj(o.assessment) ? list(o.actions) : [])];
  const factors = list(a.factors).map(toFactor).filter(defined);
  const overall = n(a.overallScore) ?? n(a.score);
  return {
    id,
    unitCode: s(a.unitCode),
    assessedOn: s(a.assessedOn),
    reviewDate: s(a.reviewDate),
    overallScore: overall,
    rag: toRag(a.rag ?? a.overallRag, overall),
    factors: factors.sort((x, y) => (y.score ?? 0) - (x.score ?? 0)),
    actions: actionsRaw.map((x, i) => toAction(x, id, i)).filter(defined),
  };
}

/** Review overdue when the review date has passed. */
export const reviewOverdue = (a: Pick<UnitAssessment, 'reviewDate'>, now: Date = new Date()) => !!a.reviewDate && a.reviewDate.slice(0, 10) < today(now);

export interface SharedOutcome {
  employeeId: string;
  displayName: string;
  fitness: string;
  adjustments?: string;
  sharedAt?: string;
}

export const SHARED_OUTCOME_FIELDS = ['employeeId', 'displayName', 'fitness', 'adjustments', 'sharedAt'] as const;

/** Only employee, fitness, adjustments (if shared) and when. Rows without a fitness value are dropped. */
export function toSharedOutcomes(body: unknown): SharedOutcome[] {
  return list(body)
    .map((raw): SharedOutcome | undefined => {
      const r = obj(raw);
      const employeeId = s(r?.employeeId);
      const fitness = s(r?.fitness) ?? s(r?.fitnessStatus);
      if (!r || !employeeId || !fitness) return undefined;
      return { employeeId, displayName: s(r.displayName) ?? employeeId, fitness: fitness.toUpperCase(), adjustments: s(r.adjustments), sharedAt: s(r.sharedAt) };
    })
    .filter(defined)
    .sort((a, b) => a.displayName.localeCompare(b.displayName));
}

export const FITNESS_LABELS: Record<string, string> = {
  FIT: 'Fit for work',
  FIT_WITH_LIMITATIONS: 'Fit with limitations',
  FIT_WITH_RESTRICTIONS: 'Fit with restrictions',
  TEMPORARILY_UNFIT: 'Temporarily unfit',
  UNFIT: 'Unfit',
  UNFIT_PENDING: 'Unfit – pending assessment',
  PENDING: 'Assessment pending',
  UNDER_REVIEW: 'Under review',
};
export const fitnessLabel = (f: string) => FITNESS_LABELS[f] ?? humanise(f);

/* ---------------------------------------------------------------- campaigns (§6) */

export interface ParticipationRow {
  key: string;
  title: string;
  unitCode?: string;
  period?: string;
  suppressed: boolean;
  invited?: number;
  participated?: number;
  pct?: number;
}

export interface UnitParticipation {
  minGroupSize: number;
  rows: ParticipationRow[];
}

/**
 * Plain array or `{minGroupSize, rows|campaigns}`. Suppressed rows keep no figures at all (even if the server sent some),
 * and a row whose invited count is below the minimum group size is treated as suppressed (defence in depth).
 */
export function toParticipation(body: unknown): UnitParticipation {
  const o = obj(body);
  const minGroupSize = n(o?.minGroupSize) ?? DEFAULT_MIN_GROUP;
  const raw = Array.isArray(body) ? body : o ? list(o.rows ?? o.campaigns ?? o.participation ?? o.content) : [];
  const rows = raw
    .map((x, i): ParticipationRow | undefined => {
      const r = obj(x);
      const title = s(r?.title) ?? s(r?.campaignTitle) ?? s(r?.key);
      if (!r || !title) return undefined;
      const invited = n(r.invited) ?? n(r.eligible);
      const suppressed = r.suppressed === true || (invited !== undefined && invited < minGroupSize);
      const base = { key: s(r.campaignId) ?? s(r.id) ?? `${title}-${i}`, title, unitCode: s(r.unitCode), period: [s(r.startDate), s(r.endDate)].filter(Boolean).join(' – ') || undefined, suppressed };
      if (suppressed) return base;
      const participated = n(r.participated);
      const pct = n(r.pct) ?? n(r.participationPct) ?? (invited && participated !== undefined ? Math.round((participated / invited) * 1000) / 10 : undefined);
      return { ...base, invited, participated, pct };
    })
    .filter(defined);
  return { minGroupSize, rows };
}

/* ---------------------------------------------------------------- ergonomics (§6) */

export function toErgonomicActions(body: unknown): UnitAction[] {
  const o = obj(body);
  const raw = Array.isArray(body) ? body : o ? list(o.actions ?? o.content) : [];
  return raw.map((x, i) => toAction(x, undefined, i)).filter(defined);
}

/** `PUT /ergonomics/assessments/{id}/actions/{actionId}` – MANAGER may change the status only. */
export const ergonomicActionPath = (a: Pick<UnitAction, 'assessmentId' | 'id'>) => `/ergonomics/assessments/${enc(a.assessmentId ?? '')}/actions/${enc(a.id)}`;
export const canMarkErgonomicDone = (a: UnitAction) => a.status === 'OPEN' && !!a.assessmentId;
export const psychosocialActionPath = (a: Pick<UnitAction, 'assessmentId' | 'id'>) => `/psychosocial/assessments/${enc(a.assessmentId ?? '')}/actions/${enc(a.id)}`;
/** Psychosocial actions are maintained by HSE / HR; the manager may only mark one done when the server allows it. */
export const canMarkPsychosocialDone = (a: UnitAction) => a.status === 'OPEN' && !!a.assessmentId && a.canComplete;
