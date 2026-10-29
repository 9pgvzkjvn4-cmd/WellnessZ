/**
 * E06 client-side helpers: trigger-rule parameter forms, milestone / SLA flags, Bradford bands, KPI targets and the
 * 422 clinical-content field mapping. Pure functions only (unit-tested in increment3.test.ts).
 */
import type { Episode, Milestone, RuleAction, RuleBody, RuleParams, RuleType } from '../api/absence';
import { ApiError } from '../auth/AuthClient';
import type { FieldError } from './devices';

// ---------------------------------------------------------------- rule builder
export interface ParamSpec {
  key: string;
  label: string;
  kind: 'int' | 'pattern';
  min: number;
  max: number;
  hint?: string;
}

const MIN_DAYS = (hint: string): ParamSpec => ({ key: 'minDays', label: 'Minimum days', kind: 'int', min: 1, max: 365, hint });
const EPISODES: ParamSpec = { key: 'episodes', label: 'Episodes', kind: 'int', min: 1, max: 50, hint: 'Number of episodes within the window' };
const WINDOW: ParamSpec = { key: 'windowDays', label: 'Window (days)', kind: 'int', min: 1, max: 730 };

/** Parameters per rule type – contract §3.2 `POST /absence/rules`. */
export const RULE_PARAM_SPEC: Record<RuleType, ParamSpec[]> = {
  DURATION: [MIN_DAYS('Trigger when an episode exceeds this many days')],
  FREQUENCY: [EPISODES, WINDOW],
  PATTERN: [
    { key: 'pattern', label: 'Pattern', kind: 'pattern', min: 0, max: 0 },
    { key: 'minOccurrences', label: 'Minimum occurrences', kind: 'int', min: 1, max: 50 },
    WINDOW,
  ],
  POST_INCIDENT: [{ key: 'withinDays', label: 'Within days of an incident', kind: 'int', min: 1, max: 90 }],
  SAFETY_CRITICAL: [MIN_DAYS('Safety-critical job: trigger when an episode exceeds this many days')],
  CREW_SICKNESS: [MIN_DAYS('Crew sickness longer than this many days'), EPISODES, WINDOW],
};

/** Supported absence patterns for PATTERN rules. */
export const RULE_PATTERNS = ['MONDAY_FRIDAY'] as const;

const DEFAULTS: Record<RuleType, RuleParams> = {
  DURATION: { minDays: 10 },
  FREQUENCY: { episodes: 3, windowDays: 90 },
  PATTERN: { pattern: 'MONDAY_FRIDAY', minOccurrences: 3, windowDays: 180 },
  POST_INCIDENT: { withinDays: 7 },
  SAFETY_CRITICAL: { minDays: 3 },
  CREW_SICKNESS: { minDays: 7, episodes: 3, windowDays: 90 },
};

export const defaultParams = (t: RuleType): RuleParams => ({ ...DEFAULTS[t] });

export interface RuleForm {
  code: string;
  name: string;
  subsidiaryCode: string;
  ruleType: RuleType;
  action: RuleAction;
  active: boolean;
  params: Record<string, string>;
}

export function paramsToForm(t: RuleType, params: RuleParams | undefined): Record<string, string> {
  const src = { ...DEFAULTS[t], ...(params ?? {}) };
  return Object.fromEntries(RULE_PARAM_SPEC[t].map((s) => [s.key, src[s.key] === undefined ? '' : String(src[s.key])]));
}

export function emptyRuleForm(t: RuleType = 'DURATION'): RuleForm {
  return { code: '', name: '', subsidiaryCode: '', ruleType: t, action: t === 'CREW_SICKNESS' ? 'AME_REVIEW' : 'EARLY_INTERVENTION', active: true, params: paramsToForm(t, undefined) };
}

/** Switching type resets the parameter set to that type's defaults (keeps values with the same key). */
export function changeRuleType(f: RuleForm, t: RuleType): RuleForm {
  const kept = Object.fromEntries(Object.entries(f.params).filter(([k, v]) => v !== '' && RULE_PARAM_SPEC[t].some((s) => s.key === k)));
  return { ...f, ruleType: t, params: { ...paramsToForm(t, undefined), ...kept } };
}

export const ruleFieldId = (key: string) => `rule-${key}`;

export function validateRuleForm(f: RuleForm): FieldError[] {
  const errors: FieldError[] = [];
  if (!/^[A-Z0-9_]{2,40}$/.test(f.code.trim())) errors.push({ field: ruleFieldId('code'), message: 'Code: 2–40 upper-case letters, digits or underscores' });
  if (!f.name.trim()) errors.push({ field: ruleFieldId('name'), message: 'Enter a rule name' });
  for (const s of RULE_PARAM_SPEC[f.ruleType]) {
    const v = (f.params[s.key] ?? '').trim();
    if (s.kind === 'pattern') {
      if (!(RULE_PATTERNS as readonly string[]).includes(v)) errors.push({ field: ruleFieldId(s.key), message: `${s.label}: choose a pattern` });
      continue;
    }
    const n = Number(v);
    if (!/^\d+$/.test(v) || n < s.min || n > s.max) errors.push({ field: ruleFieldId(s.key), message: `${s.label}: whole number ${s.min}–${s.max}` });
  }
  return errors;
}

/** Form → request body (`params` with numbers; only the keys of the selected type). Throws if invalid. */
export function toRuleBody(f: RuleForm): RuleBody {
  if (validateRuleForm(f).length > 0) throw new Error('invalid rule');
  const params: RuleParams = {};
  for (const s of RULE_PARAM_SPEC[f.ruleType]) {
    const v = (f.params[s.key] ?? '').trim();
    params[s.key] = s.kind === 'int' ? Number(v) : v;
  }
  return { code: f.code.trim(), name: f.name.trim(), ruleType: f.ruleType, action: f.action, active: f.active, params, ...(f.subsidiaryCode.trim() ? { subsidiaryCode: f.subsidiaryCode.trim().toUpperCase() } : {}) };
}

/** Human summary of a rule's parameters, e.g. "3 episodes in 90 days". */
export function describeParams(t: RuleType, p: RuleParams): string {
  const n = (k: string) => p[k] ?? '?';
  switch (t) {
    case 'DURATION': return `> ${n('minDays')} days`;
    case 'FREQUENCY': return `${n('episodes')} episodes in ${n('windowDays')} days`;
    case 'PATTERN': return `≥ ${n('minOccurrences')} × ${String(n('pattern')).replace(/_/g, '/').toLowerCase()} in ${n('windowDays')} days`;
    case 'POST_INCIDENT': return `starts ≤ ${n('withinDays')} days after an incident`;
    case 'SAFETY_CRITICAL': return `safety-critical job, > ${n('minDays')} days`;
    case 'CREW_SICKNESS': return `crew > ${n('minDays')} days or ${n('episodes')} in ${n('windowDays')} days`;
    default: return '';
  }
}

// ---------------------------------------------------------------- dates, milestones, SLA
const DAY = 86_400_000;
export const todayIso = (d: Date = new Date()): string => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

/** Whole days from `today` to `date` (negative = in the past). Both YYYY-MM-DD. */
export function daysUntil(date: string, today: string = todayIso()): number {
  return Math.round((Date.parse(`${date.slice(0, 10)}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / DAY);
}

/** Overdue = server status OVERDUE, or still PENDING past its due date (nightly job not run yet). */
export function isMilestoneOverdue(m: Pick<Milestone, 'status' | 'dueDate'>, today: string = todayIso()): boolean {
  return m.status === 'OVERDUE' || (m.status === 'PENDING' && daysUntil(m.dueDate, today) < 0);
}

export type Tone = 'red' | 'amber' | 'green' | 'muted';

export function milestoneTone(m: Pick<Milestone, 'status' | 'dueDate'>, today: string = todayIso()): Tone {
  if (m.status === 'DONE') return 'green';
  if (m.status === 'SKIPPED') return 'muted';
  if (isMilestoneOverdue(m, today)) return 'red';
  return daysUntil(m.dueDate, today) <= 7 ? 'amber' : 'muted';
}

/** Queue SLA (`slaDueAt` timestamp): breached / due within 24 h / ok. */
export function slaTone(slaDueAt: string | undefined | null, now: number = Date.now()): { tone: Tone; label: string } {
  if (!slaDueAt) return { tone: 'muted', label: 'No SLA' };
  const ms = Date.parse(slaDueAt) - now;
  if (Number.isNaN(ms)) return { tone: 'muted', label: 'No SLA' };
  const hours = Math.round(Math.abs(ms) / 3_600_000);
  const span = hours >= 48 ? `${Math.round(hours / 24)} d` : `${hours} h`;
  if (ms < 0) return { tone: 'red', label: `Overdue by ${span}` };
  if (ms <= 24 * 3_600_000) return { tone: 'amber', label: `Due in ${span}` };
  return { tone: 'green', label: `Due in ${span}` };
}

// ---------------------------------------------------------------- Bradford & KPIs
/** Common Bradford-factor bands (S² × D): < 50 no concern, 50–124 monitor, 125–399 review, ≥ 400 formal action. */
export function bradfordBand(score: number): { band: 'LOW' | 'MONITOR' | 'REVIEW' | 'HIGH'; tone: Tone; label: string } {
  if (score >= 400) return { band: 'HIGH', tone: 'red', label: 'High (≥ 400)' };
  if (score >= 125) return { band: 'REVIEW', tone: 'amber', label: 'Review (125–399)' };
  if (score >= 50) return { band: 'MONITOR', tone: 'amber', label: 'Monitor (50–124)' };
  return { band: 'LOW', tone: 'green', label: 'Low (< 50)' };
}

/** Target check: `atLeast` (e.g. LTA with case ≥ 95 %) or `atMost` (median days trigger→contact ≤ 5). */
export function kpiStatus(value: number | null | undefined, target: number, direction: 'atLeast' | 'atMost'): 'MET' | 'MISSED' | 'UNKNOWN' {
  if (value === null || value === undefined || Number.isNaN(value)) return 'UNKNOWN';
  return (direction === 'atLeast' ? value >= target : value <= target) ? 'MET' : 'MISSED';
}

export function formatPct(v: number | null | undefined, digits = 1): string {
  return typeof v === 'number' && Number.isFinite(v) ? `${v.toFixed(digits)} %` : '–';
}

/** `YYYY-MM` of the month before `today` (default export month). */
export function previousMonth(today: string = todayIso()): string {
  const y = Number(today.slice(0, 4));
  const m = Number(today.slice(5, 7));
  return m === 1 ? `${y - 1}-12` : `${y}-${String(m - 1).padStart(2, '0')}`;
}

// ---------------------------------------------------------------- episodes
export interface EpisodeFilter {
  types: readonly string[];
  openOnly: boolean;
  from?: string;
  to?: string;
}

/** Filter + newest-first sort for the chart absence timeline. */
export function filterEpisodes(list: readonly Episode[], f: EpisodeFilter): Episode[] {
  return list
    .filter((e) => (f.types.length === 0 || f.types.includes(e.absenceType)) && (!f.openOnly || e.open))
    .filter((e) => (!f.from || (e.endDate ?? '9999-12-31') >= f.from) && (!f.to || e.startDate <= f.to))
    .sort((a, b) => b.startDate.localeCompare(a.startDate));
}

// ---------------------------------------------------------------- 422 clinical content
/** True for the contract's `clinical_content` and the platform's `clinical_content_not_allowed` codes. */
export function isClinicalContentError(e: unknown): e is ApiError {
  return e instanceof ApiError && e.status === 422 && !!e.problem?.code?.startsWith('clinical_content');
}

/**
 * The offending field of a 422 clinical-content error: the problem's `field` property, or "Field 'x'" in the detail.
 * Returns e.g. `adjustments` or `answers.FIT_FOR_ROLE` (normalised from `answers[FIT_FOR_ROLE]`).
 */
export function clinicalContentField(e: unknown): string | undefined {
  if (!isClinicalContentError(e)) return undefined;
  const raw = e.problem?.field ?? /Field '([^']+)'/.exec(e.problem?.detail ?? '')?.[1];
  if (!raw) return undefined;
  return raw.replace(/\[([^\]]+)\]/g, '.$1').replace(/^report\./, '');
}
