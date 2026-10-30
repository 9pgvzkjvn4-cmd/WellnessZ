/**
 * E12 client-side helpers (pure, unit-tested in promotion.test.ts): screening threshold flags, ergonomic checklist
 * scoring and RAG, action due / overdue state, participation and outcome deltas with suppression, insight rendering
 * and form validation. The server's thresholds (configuration) and risk score are authoritative – the UI previews them.
 */
import type { ErgoAction, Insight, Measures, MeasureKey, OutcomeRow, ParticipationRow, Rag, ScreeningResultResponse } from '../api/promotion';
import { daysUntil, todayIso, type Tone } from './absence';
import type { FieldError } from './devices';
import { DEFAULT_K } from './suppression';

// ---------------------------------------------------------------- screening thresholds
export interface Threshold {
  label: string;
  unit: string;
  /** Value ≥ high → HIGH. */
  high?: number;
  /** Value < low → LOW. */
  low?: number;
  min: number;
  max: number;
  step: number;
}

/**
 * Default thresholds mirroring the seeded E12 configuration (adult screening cut-offs: BP ≥ 140/90 mmHg, BMI ≥ 30 or
 * < 18.5, random glucose ≥ 7.8 mmol/L, total cholesterol ≥ 6.5 mmol/L, waist ≥ 102 cm, MSK score ≥ 7 / 10, WEMWBS
 * wellbeing < 41). Used for the live preview only; the result response decides.
 */
export const DEFAULT_THRESHOLDS: Record<MeasureKey, Threshold> = {
  systolic: { label: 'Systolic BP', unit: 'mmHg', high: 140, low: 90, min: 50, max: 260, step: 1 },
  diastolic: { label: 'Diastolic BP', unit: 'mmHg', high: 90, low: 50, min: 30, max: 160, step: 1 },
  bmi: { label: 'BMI', unit: 'kg/m²', high: 30, low: 18.5, min: 10, max: 80, step: 0.1 },
  glucoseMmol: { label: 'Glucose', unit: 'mmol/L', high: 7.8, low: 3.5, min: 1, max: 40, step: 0.1 },
  cholesterolMmol: { label: 'Total cholesterol', unit: 'mmol/L', high: 6.5, min: 1, max: 20, step: 0.1 },
  waistCm: { label: 'Waist', unit: 'cm', high: 102, min: 40, max: 200, step: 1 },
  mskScore: { label: 'MSK score', unit: '0–10', high: 7, min: 0, max: 10, step: 1 },
  wellbeingScore: { label: 'Wellbeing (WEMWBS)', unit: '14–70', low: 41, min: 14, max: 70, step: 1 },
};

/** Measures captured per screening type. */
export const MEASURES_FOR_TYPE: Record<string, MeasureKey[]> = {
  BIOMETRIC: ['systolic', 'diastolic', 'bmi', 'glucoseMmol', 'cholesterolMmol', 'waistCm'],
  MSK: ['mskScore'],
  HEARING: [],
  VISION: [],
  MENTAL_WELLBEING: ['wellbeingScore'],
};

export type Flag = 'HIGH' | 'LOW' | 'NORMAL';
export interface MeasureFlag {
  measure: MeasureKey;
  value: number;
  flag: Flag;
  label: string;
}

export function flagMeasure(measure: MeasureKey, value: number, thresholds: Record<MeasureKey, Threshold> = DEFAULT_THRESHOLDS): Flag {
  const t = thresholds[measure];
  if (!t || !Number.isFinite(value)) return 'NORMAL';
  if (t.high !== undefined && value >= t.high) return 'HIGH';
  if (t.low !== undefined && value < t.low) return 'LOW';
  return 'NORMAL';
}

export function flagMeasures(m: Measures, thresholds: Record<MeasureKey, Threshold> = DEFAULT_THRESHOLDS): MeasureFlag[] {
  const out: MeasureFlag[] = [];
  for (const [k, v] of Object.entries(m) as [MeasureKey, number | undefined][]) {
    if (typeof v !== 'number' || !Number.isFinite(v)) continue;
    const flag = flagMeasure(k, v, thresholds);
    const t = thresholds[k];
    out.push({ measure: k, value: v, flag, label: `${t?.label ?? k} ${v} ${t?.unit ?? ''}`.trim() });
  }
  return out;
}

export const isAbnormal = (flags: readonly MeasureFlag[]): boolean => flags.some((f) => f.flag !== 'NORMAL');

export const flagTone = (f: Flag): Tone => (f === 'NORMAL' ? 'green' : f === 'HIGH' ? 'red' : 'amber');

/** Measures form (strings) → numeric measures; empty fields are left out. */
export function parseMeasures(form: Partial<Record<MeasureKey, string>>, thresholds: Record<MeasureKey, Threshold> = DEFAULT_THRESHOLDS): { measures: Measures; errors: FieldError[] } {
  const measures: Measures = {};
  const errors: FieldError[] = [];
  for (const [k, raw] of Object.entries(form) as [MeasureKey, string | undefined][]) {
    if (raw === undefined || raw.trim() === '') continue;
    const v = Number(raw.replace(',', '.'));
    const t = thresholds[k];
    if (!Number.isFinite(v) || (t && (v < t.min || v > t.max))) {
      errors.push({ field: `m-${k}`, message: `${t?.label ?? k}: enter a value between ${t?.min} and ${t?.max}.` });
      continue;
    }
    measures[k] = v;
  }
  if (errors.length === 0 && Object.keys(measures).length === 0) errors.push({ field: 'm-first', message: 'Enter at least one measurement.' });
  if (measures.systolic !== undefined && measures.diastolic !== undefined && measures.diastolic >= measures.systolic)
    errors.push({ field: 'm-diastolic', message: 'Diastolic must be lower than systolic.' });
  return { measures, errors };
}

/** Server response flags (strings or objects) → measure names that were flagged. */
export function responseFlags(r: Pick<ScreeningResultResponse, 'flags'>): string[] {
  return (r.flags ?? []).map((f) => (typeof f === 'string' ? f : `${f.measure}${f.flag ? ` ${f.flag.toLowerCase()}` : ''}`));
}

// ---------------------------------------------------------------- ergonomic checklists, score and RAG
export interface ChecklistItem {
  key: string;
  label: string;
}
export const CHECKLISTS: Record<string, ChecklistItem[]> = {
  DSE_WORKSTATION: [
    { key: 'SCREEN_HEIGHT', label: 'Screen height and position' },
    { key: 'SCREEN_DISTANCE', label: 'Viewing distance' },
    { key: 'CHAIR_ADJUSTMENT', label: 'Chair adjustability' },
    { key: 'LUMBAR_SUPPORT', label: 'Lumbar support' },
    { key: 'DESK_HEIGHT', label: 'Desk height and leg room' },
    { key: 'KEYBOARD_MOUSE', label: 'Keyboard and mouse position' },
    { key: 'LIGHTING_GLARE', label: 'Lighting and glare' },
    { key: 'BREAKS_VARIETY', label: 'Breaks and task variety' },
  ],
  MANUAL_HANDLING: [
    { key: 'LOAD_WEIGHT', label: 'Load weight' },
    { key: 'LOAD_DISTANCE', label: 'Load distance from the trunk' },
    { key: 'TWISTING', label: 'Twisting' },
    { key: 'STOOPING_REACHING', label: 'Stooping or reaching' },
    { key: 'CARRY_DISTANCE', label: 'Carry distance' },
    { key: 'FREQUENCY', label: 'Repetition / frequency' },
    { key: 'ENVIRONMENT', label: 'Floor, space and environment' },
    { key: 'INDIVIDUAL_CAPABILITY', label: 'Individual capability / training' },
  ],
  TASK: [
    { key: 'POSTURE', label: 'Awkward posture' },
    { key: 'FORCE', label: 'Force' },
    { key: 'REPETITION', label: 'Repetition' },
    { key: 'DURATION', label: 'Duration without recovery' },
    { key: 'VIBRATION', label: 'Vibration' },
    { key: 'ENVIRONMENT', label: 'Environment (cold, heat, lighting)' },
    { key: 'WORK_ORGANISATION', label: 'Work organisation / pace' },
  ],
};
export const SCORE_LABELS = ['0 – no risk', '1 – low', '2 – medium', '3 – high'] as const;

/**
 * Checklist score: sum of item scores (0..3) and the percentage of the maximum. RAG: ≥ 67 % red, ≥ 34 % amber, else
 * green; any single item scored 3 raises green to amber (a high-risk item always needs an action).
 */
export function ergonomicScore(checklist: Record<string, number | undefined>, items: readonly ChecklistItem[]): { total: number; max: number; pct: number; scored: number; rag: Rag } {
  let total = 0;
  let scored = 0;
  let anyHigh = false;
  for (const i of items) {
    const v = checklist[i.key];
    if (typeof v !== 'number' || v < 0 || v > 3) continue;
    total += v;
    scored += 1;
    if (v === 3) anyHigh = true;
  }
  const max = items.length * 3;
  const pct = max > 0 ? Math.round((total / max) * 100) : 0;
  const rag: Rag = pct >= 67 ? 'RED' : pct >= 34 || anyHigh ? 'AMBER' : 'GREEN';
  return { total, max, pct, scored, rag };
}

export const ragTone = (rag: Rag | string | undefined): Tone => (rag === 'RED' ? 'red' : rag === 'AMBER' ? 'amber' : rag === 'GREEN' ? 'green' : 'muted');
export const ragLabel = (rag: Rag | string | undefined): string => (rag === 'RED' ? 'Red – high risk' : rag === 'AMBER' ? 'Amber – medium risk' : rag === 'GREEN' ? 'Green – low risk' : 'Not scored');

/** Ergonomic action: done → green; open past due → red "Overdue n d"; due within 7 days → amber; else muted. */
export function actionView(a: Pick<ErgoAction, 'status' | 'dueDate'>, today: string = todayIso()): { tone: Tone; label: string; overdue: boolean } {
  if (a.status === 'DONE') return { tone: 'green', label: 'Done', overdue: false };
  const d = daysUntil(a.dueDate, today);
  if (d < 0) return { tone: 'red', label: `Overdue ${-d} d`, overdue: true };
  if (d <= 7) return { tone: 'amber', label: d === 0 ? 'Due today' : `Due in ${d} d`, overdue: false };
  return { tone: 'muted', label: `Due in ${d} d`, overdue: false };
}

/** Today + n days (local calendar) as YYYY-MM-DD. */
export function addDaysIso(n: number, from: Date = new Date()): string {
  const d = new Date(from);
  d.setDate(d.getDate() + n);
  return todayIso(d);
}

/** KPI: actions closed within 60 days. */
export const ACTION_CLOSE_KPI_DAYS = 60;

export interface AssessmentForm {
  type: string;
  unitCode: string;
  jobCode: string;
  employeeId: string;
  location: string;
  checklist: Record<string, number | undefined>;
  findings: string;
}
export function validateAssessment(f: AssessmentForm): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.type) errs.push({ field: 'ea-type', message: 'Choose the assessment type.' });
  if (!f.unitCode.trim()) errs.push({ field: 'ea-unit', message: 'Enter the unit code.' });
  if (!f.jobCode.trim()) errs.push({ field: 'ea-job', message: 'Enter the job code.' });
  if (!f.location.trim()) errs.push({ field: 'ea-location', message: 'Enter the location.' });
  if (f.employeeId.trim() && !/^[A-Za-z]\d{3,8}$/.test(f.employeeId.trim())) errs.push({ field: 'ea-employee', message: 'Employee ID looks like E1004 (leave empty for a job / workstation assessment).' });
  const items = CHECKLISTS[f.type] ?? [];
  const missing = items.filter((i) => typeof f.checklist[i.key] !== 'number');
  if (f.type && missing.length > 0) errs.push({ field: `ea-item-${missing[0]!.key}`, message: `Score every checklist item (${missing.length} missing).` });
  if (f.findings.length > 4000) errs.push({ field: 'ea-findings', message: 'Findings can be at most 4000 characters.' });
  return errs;
}

// ---------------------------------------------------------------- campaigns
export interface PromotionCampaignForm {
  title: string;
  risk: string;
  objective: string;
  targetUnits: string;
  insightRef: string;
  budgetAmount: string;
  currency: string;
  startDate: string;
  endDate: string;
  owners: string;
  kpis: { metric: string; target: string }[];
  status: string;
}

export function validatePromotionCampaign(f: PromotionCampaignForm): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.title.trim()) errs.push({ field: 'pc-title', message: 'Enter a title.' });
  if (!f.risk) errs.push({ field: 'pc-risk', message: 'Choose the risk area.' });
  if (!f.objective.trim()) errs.push({ field: 'pc-objective', message: 'Describe the objective.' });
  if (list(f.targetUnits).length === 0) errs.push({ field: 'pc-units', message: 'List at least one target unit.' });
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(f.budgetAmount.trim())) errs.push({ field: 'pc-budget', message: 'Budget: an amount like 2500 or 2500.00.' });
  if (!/^[A-Z]{3}$/.test(f.currency.trim().toUpperCase())) errs.push({ field: 'pc-currency', message: 'Currency: three letters, e.g. GBP.' });
  if (!f.startDate) errs.push({ field: 'pc-start', message: 'Enter the start date.' });
  if (!f.endDate) errs.push({ field: 'pc-end', message: 'Enter the end date.' });
  else if (f.startDate && f.endDate < f.startDate) errs.push({ field: 'pc-end', message: 'The end date cannot be before the start date.' });
  if (list(f.owners, false).length === 0) errs.push({ field: 'pc-owners', message: 'Name at least one owner.' });
  const kpis = f.kpis.filter((k) => k.metric.trim() || k.target.trim());
  if (kpis.length === 0) errs.push({ field: 'pc-kpi-0-metric', message: 'Add at least one KPI.' });
  f.kpis.forEach((k, i) => {
    if (!k.metric.trim() && !k.target.trim()) return;
    if (!k.metric.trim()) errs.push({ field: `pc-kpi-${i}-metric`, message: `KPI ${i + 1}: enter the metric.` });
    if (!Number.isFinite(Number(k.target)) || k.target.trim() === '') errs.push({ field: `pc-kpi-${i}-target`, message: `KPI ${i + 1}: the target must be a number.` });
  });
  return errs;
}

export function promotionCampaignBody(f: PromotionCampaignForm) {
  const amount = f.budgetAmount.trim();
  return {
    title: f.title.trim(),
    risk: f.risk as 'MSK',
    objective: f.objective.trim(),
    targetUnits: list(f.targetUnits),
    ...(f.insightRef.trim() ? { insightRef: f.insightRef.trim() } : {}),
    budget: { amount: amount.includes('.') ? amount.padEnd(amount.indexOf('.') + 3, '0') : `${amount}.00`, currency: f.currency.trim().toUpperCase() },
    startDate: f.startDate,
    endDate: f.endDate,
    owners: list(f.owners, false),
    kpis: f.kpis.filter((k) => k.metric.trim()).map((k) => ({ metric: k.metric.trim(), target: Number(k.target) })),
    status: f.status as 'PLANNED',
  };
}

/** Comma / whitespace separated codes (upper-cased) or names (`upper=false`, comma separated only). */
export function list(v: string, upper = true): string[] {
  const parts = upper ? v.split(/[\s,;]+/).map((s) => s.trim().toUpperCase()) : v.split(/[,;\n]+/).map((s) => s.trim());
  return [...new Set(parts.filter(Boolean))];
}

/** Timeline position of a campaign: before start, running (n % elapsed), ended. */
export function timelineView(startDate: string, endDate: string, today: string = todayIso()): { label: string; pct: number } {
  const total = daysUntil(endDate, startDate) + 1;
  const elapsed = daysUntil(today, startDate) + 1;
  if (elapsed <= 0) return { label: `Starts in ${-elapsed + 1} d`, pct: 0 };
  if (elapsed > total) return { label: 'Ended', pct: 100 };
  const pct = total > 0 ? Math.round((elapsed / total) * 100) : 100;
  return { label: `Day ${elapsed} of ${total}`, pct };
}

// ---------------------------------------------------------------- insights & reports (suppressed)
export function insightValue(i: Pick<Insight, 'value' | 'suppressed'>): string {
  if (i.suppressed || typeof i.value !== 'number') return 'Suppressed';
  return Number.isInteger(i.value) ? String(i.value) : i.value.toFixed(1);
}

/** Hotspots: non-suppressed rows, highest value first per risk. */
export function hotspots(rows: readonly Insight[], risk?: string): Insight[] {
  return rows
    .filter((r) => (!risk || r.risk === risk))
    .sort((a, b) => Number(a.suppressed) - Number(b.suppressed) || a.risk.localeCompare(b.risk) || (b.value ?? -Infinity) - (a.value ?? -Infinity));
}

export function participationView(r: ParticipationRow, k: number = DEFAULT_K): { suppressed: boolean; invited: string; participated: string; pct: string } {
  const invited = r.invited ?? undefined;
  if (r.suppressed || typeof invited !== 'number' || (invited > 0 && invited < k)) return { suppressed: true, invited: `<${k}`, participated: '–', pct: 'Suppressed' };
  const participated = r.participated ?? 0;
  const pct = typeof r.pct === 'number' ? r.pct : invited > 0 ? Math.round((participated / invited) * 1000) / 10 : undefined;
  return { suppressed: false, invited: String(invited), participated: String(participated), pct: pct === undefined ? '–' : `${pct.toFixed(1)} %` };
}

/** Metrics where a fall is an improvement (blood pressure, BMI, glucose, cholesterol, waist, MSK score, absence). */
const LOWER_IS_BETTER = /systolic|diastolic|bmi|glucose|cholesterol|waist|msk|absence|incident|sick/i;

export function outcomeDelta(o: OutcomeRow): { text: string; tone: Tone; direction: 'up' | 'down' | 'flat' | 'none' } {
  const delta = typeof o.delta === 'number' ? o.delta : typeof o.current === 'number' && typeof o.baseline === 'number' ? o.current - o.baseline : undefined;
  if (delta === undefined || !Number.isFinite(delta)) return { text: '–', tone: 'muted', direction: 'none' };
  const rounded = Math.round(delta * 10) / 10;
  if (rounded === 0) return { text: '±0', tone: 'muted', direction: 'flat' };
  const better = LOWER_IS_BETTER.test(o.metric) ? rounded < 0 : rounded > 0;
  return { text: `${rounded > 0 ? '+' : '−'}${Math.abs(rounded)}`, tone: better ? 'green' : 'red', direction: rounded > 0 ? 'up' : 'down' };
}

export function validateScreeningEvent(f: { type: string; siteCode: string; date: string }): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.type) errs.push({ field: 'se-type', message: 'Choose the screening type.' });
  if (!/^[A-Z0-9][A-Z0-9_-]{1,39}$/.test(f.siteCode.trim().toUpperCase())) errs.push({ field: 'se-site', message: 'Enter the site code (e.g. LHR-T5).' });
  if (!f.date) errs.push({ field: 'se-date', message: 'Enter the date.' });
  return errs;
}
