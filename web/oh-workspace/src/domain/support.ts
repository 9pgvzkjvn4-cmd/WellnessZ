/**
 * E09 display / validation helpers (pure functions, unit-tested in support.test.ts). The server is authoritative for
 * scores, bands, urgency and RAG – these helpers drive previews, badges, countdowns and client-side form checks.
 */
import type {
  Band,
  CismEvent,
  EapUrgency,
  FollowUpState,
  Instrument,
  Medication,
  MhFitnessDecision,
  PeerCategory,
  PeerContactBody,
  PeerOutcome,
  PsychosocialFactor,
  Rag,
  Risk,
  Sharing,
  SmsReport,
} from '../api/support';
import { PSYCHOSOCIAL_FACTORS } from '../api/support';
import { ApiError } from '../auth/AuthClient';
import { todayIso, type Tone } from './absence';
import type { FieldError } from './devices';

// ---------------------------------------------------------------- screening instruments (public domain)
export const PHQ9_ITEMS = [
  'Little interest or pleasure in doing things',
  'Feeling down, depressed or hopeless',
  'Trouble falling or staying asleep, or sleeping too much',
  'Feeling tired or having little energy',
  'Poor appetite or overeating',
  'Feeling bad about yourself – or that you are a failure or have let yourself or your family down',
  'Trouble concentrating on things, such as reading or watching television',
  'Moving or speaking so slowly that other people could have noticed – or the opposite, being fidgety or restless',
  'Thoughts that you would be better off dead, or of hurting yourself',
] as const;
export const GAD7_ITEMS = [
  'Feeling nervous, anxious or on edge',
  'Not being able to stop or control worrying',
  'Worrying too much about different things',
  'Trouble relaxing',
  'Being so restless that it is hard to sit still',
  'Becoming easily annoyed or irritable',
  'Feeling afraid as if something awful might happen',
] as const;
export const SCREEN_OPTIONS = [
  { value: 0, label: 'Not at all' },
  { value: 1, label: 'Several days' },
  { value: 2, label: 'More than half the days' },
  { value: 3, label: 'Nearly every day' },
] as const;

export const itemsFor = (instrument: Instrument): readonly string[] => (instrument === 'PHQ9' ? PHQ9_ITEMS : GAD7_ITEMS);
export const instrumentTitle = (instrument: Instrument): string => (instrument === 'PHQ9' ? 'PHQ-9 (depression)' : 'GAD-7 (anxiety)');

export function screenScore(answers: readonly (number | undefined)[]): number {
  return answers.reduce<number>((s, a) => s + (typeof a === 'number' ? a : 0), 0);
}

/** PHQ-9: 0–4 / 5–9 / 10–14 / 15–19 / 20–27. GAD-7: 0–4 / 5–9 / 10–14 / 15–21 (no "moderately severe" band). */
export function bandFor(instrument: Instrument, score: number): Band {
  if (score <= 4) return 'MINIMAL';
  if (score <= 9) return 'MILD';
  if (score <= 14) return 'MODERATE';
  if (instrument === 'GAD7') return 'SEVERE';
  return score <= 19 ? 'MODERATELY_SEVERE' : 'SEVERE';
}

const BAND_RANGES: Record<Instrument, Partial<Record<Band, string>>> = {
  PHQ9: { MINIMAL: '0–4', MILD: '5–9', MODERATE: '10–14', MODERATELY_SEVERE: '15–19', SEVERE: '20–27' },
  GAD7: { MINIMAL: '0–4', MILD: '5–9', MODERATE: '10–14', SEVERE: '15–21' },
};

export function bandLabel(instrument: Instrument, band: Band): string {
  const name = band === 'MODERATELY_SEVERE' ? 'Moderately severe' : band.charAt(0) + band.slice(1).toLowerCase();
  const range = BAND_RANGES[instrument]?.[band];
  return range ? `${name} (${range})` : name;
}

export function bandTone(band: Band): Tone {
  switch (band) {
    case 'MINIMAL': return 'green';
    case 'MILD': return 'muted';
    case 'MODERATE': return 'amber';
    default: return 'red';
  }
}

/** Contract §3.2: PHQ-9 item 9 > 0 or band SEVERE → URGENT (same-day call-back). */
export function isUrgentScreen(instrument: Instrument, answers: readonly (number | undefined)[]): boolean {
  if (instrument === 'PHQ9' && (answers[8] ?? 0) > 0) return true;
  return bandFor(instrument, screenScore(answers)) === 'SEVERE';
}

export function validateScreen(instrument: Instrument, answers: readonly (number | undefined)[]): FieldError[] {
  const errs: FieldError[] = [];
  itemsFor(instrument).forEach((_, i) => {
    const a = answers[i];
    if (typeof a !== 'number' || a < 0 || a > 3) errs.push({ field: `screen-item-${i}`, message: `Answer item ${i + 1}.` });
  });
  return errs;
}

export function riskTone(risk: Risk): Tone {
  return risk === 'URGENT' || risk === 'HIGH' ? 'red' : risk === 'MODERATE' ? 'amber' : 'green';
}

/** What the employee chose to share with the manager (§3.1 rule 2) – shown read-only to the clinician. */
export function sharingLabel(s: Sharing | undefined): string {
  switch (s) {
    case 'FITNESS_ONLY': return 'Manager sees fitness status only';
    case 'FITNESS_AND_ADJUSTMENTS': return 'Manager sees fitness status and adjustments';
    default: return 'Nothing shared with the manager';
  }
}

// ---------------------------------------------------------------- countdowns
export interface Countdown {
  tone: Tone;
  label: string;
  overdue: boolean;
  minutesLeft: number;
}

function span(totalMinutes: number): string {
  const m = Math.abs(Math.trunc(totalMinutes));
  if (m >= 48 * 60) return `${Math.floor(m / 1440)} d ${Math.floor((m % 1440) / 60)} h`;
  const h = Math.floor(m / 60);
  const mm = m % 60;
  return h > 0 ? `${h} h ${String(mm).padStart(2, '0')} min` : `${mm} min`;
}

/**
 * Countdown to a deadline (call-back-by, offer SLA, collection window). Red when overdue or within `redWithinMin`,
 * amber within `amberWithinMin`, otherwise green. Invalid / missing → muted "No deadline".
 */
export function countdown(dueAt: string | undefined | null, now: number = Date.now(), amberWithinMin = 120, redWithinMin = 30): Countdown {
  const t = dueAt ? Date.parse(dueAt) : NaN;
  if (Number.isNaN(t)) return { tone: 'muted', label: 'No deadline', overdue: false, minutesLeft: Number.POSITIVE_INFINITY };
  const minutesLeft = Math.floor((t - now) / 60_000);
  if (t - now < 0) return { tone: 'red', label: `Overdue by ${span(Math.ceil((now - t) / 60_000))}`, overdue: true, minutesLeft };
  const tone: Tone = minutesLeft <= redWithinMin ? 'red' : minutesLeft <= amberWithinMin ? 'amber' : 'green';
  return { tone, label: `${span(minutesLeft)} left`, overdue: false, minutesLeft };
}

// ---------------------------------------------------------------- EAP referral
export interface EapForm {
  partnerCode: string;
  sessionsAuthorised: string;
  urgency: EapUrgency;
  consentId: string;
}
export const emptyEapForm = (): EapForm => ({ partnerCode: 'EAP_WELLBEING', sessionsAuthorised: '6', urgency: 'ROUTINE', consentId: '' });

export function validateEap(f: EapForm): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.partnerCode) errs.push({ field: 'eap-partner', message: 'Choose the EAP partner.' });
  const n = Number(f.sessionsAuthorised);
  if (!Number.isInteger(n) || n < 1 || n > 12) errs.push({ field: 'eap-sessions', message: 'Sessions authorised must be a whole number from 1 to 12.' });
  if (!f.consentId) errs.push({ field: 'eap-consent', message: 'Choose the employee’s active partner-sharing consent.' });
  return errs;
}

// ---------------------------------------------------------------- safety-critical fitness (F06)
export interface FitnessForm {
  employeeId: string;
  supportCaseId: string;
  standardCode: string;
  medications: Medication[];
  findings: string;
  decision: MhFitnessDecision | '';
  limitations: string;
  reviewDate: string;
  authorityReportRequired: boolean;
}
export const emptyMedication = (): Medication => ({ name: '', drugClass: '', dose: '', startDate: '' });
export const emptyFitnessForm = (employeeId = ''): FitnessForm => ({
  employeeId, supportCaseId: '', standardCode: '', medications: [], findings: '', decision: '', limitations: '', reviewDate: '', authorityReportRequired: false,
});

export function validateFitness(f: FitnessForm, today: string = todayIso()): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.employeeId.trim()) errs.push({ field: 'fit-employee', message: 'Enter the employee ID.' });
  if (!f.standardCode.trim()) errs.push({ field: 'fit-standard', message: 'Enter the fitness standard (e.g. the licensing medical standard).' });
  f.medications.forEach((m, i) => {
    if (!m.name.trim()) errs.push({ field: `fit-med-${i}-name`, message: `Medication ${i + 1}: enter the name.` });
    if (!m.drugClass.trim()) errs.push({ field: `fit-med-${i}-class`, message: `Medication ${i + 1}: enter the drug class.` });
    if (!m.dose.trim()) errs.push({ field: `fit-med-${i}-dose`, message: `Medication ${i + 1}: enter the dose.` });
    if (!m.startDate) errs.push({ field: `fit-med-${i}-start`, message: `Medication ${i + 1}: enter the start date.` });
    else if (m.startDate > today) errs.push({ field: `fit-med-${i}-start`, message: `Medication ${i + 1}: the start date cannot be in the future.` });
  });
  if (!f.findings.trim()) errs.push({ field: 'fit-findings', message: 'Record the findings.' });
  if (!f.decision) errs.push({ field: 'fit-decision', message: 'Choose a decision.' });
  if (f.decision === 'FIT_WITH_LIMITATIONS' && !f.limitations.trim()) errs.push({ field: 'fit-limitations', message: 'Describe the limitations for a fit-with-limitations decision.' });
  if (!f.reviewDate) errs.push({ field: 'fit-review', message: 'Set the review date.' });
  else if (f.reviewDate <= today) errs.push({ field: 'fit-review', message: 'The review date must be in the future.' });
  return errs;
}

/** Referral to the authority always needs the authority report; the flag is forced on for that decision. */
export const authorityReportForced = (d: FitnessForm['decision']): boolean => d === 'REFER_TO_AUTHORITY';

// ---------------------------------------------------------------- peer-support contact log (F03, rule 3)
export interface ContactForm {
  contactCode: string;
  date: string;
  durationMinutes: string;
  category: PeerCategory | '';
  outcome: PeerOutcome | '';
}
export const emptyContactForm = (contactCode = '', date: string = todayIso()): ContactForm => ({ contactCode, date, durationMinutes: '', category: '', outcome: '' });

/** Employee-ID shaped values (E1004, S8801, M2001, X8809) are never allowed in the pseudonymous log. */
const EMPLOYEE_ID_LIKE = /^[A-Z]\d{4,}$/i;

export function validateContact(f: ContactForm, today: string = todayIso()): FieldError[] {
  const errs: FieldError[] = [];
  const code = f.contactCode.trim();
  if (!code) errs.push({ field: 'contact-code', message: 'Enter the pseudonymous contact code from the request.' });
  else if (EMPLOYEE_ID_LIKE.test(code)) errs.push({ field: 'contact-code', message: 'Use the pseudonymous contact code – never an employee ID.' });
  else if (/\s/.test(code)) errs.push({ field: 'contact-code', message: 'The contact code has no spaces – do not enter names.' });
  if (!f.date) errs.push({ field: 'contact-date', message: 'Enter the contact date.' });
  else if (f.date > today) errs.push({ field: 'contact-date', message: 'The contact date cannot be in the future.' });
  const d = Number(f.durationMinutes);
  if (!Number.isInteger(d) || d < 1 || d > 600) errs.push({ field: 'contact-duration', message: 'Duration must be 1–600 minutes.' });
  if (!f.category) errs.push({ field: 'contact-category', message: 'Choose a category.' });
  if (!f.outcome) errs.push({ field: 'contact-outcome', message: 'Choose an outcome.' });
  return errs;
}

export const toContactBody = (f: ContactForm): PeerContactBody => ({
  contactCode: f.contactCode.trim(),
  date: f.date,
  durationMinutes: Number(f.durationMinutes),
  category: f.category as PeerCategory,
  outcome: f.outcome as PeerOutcome,
});

/** Training record expired / expiring within 60 days. */
export function trainingTone(expiresOn: string | undefined, today: string = todayIso()): Tone {
  if (!expiresOn) return 'muted';
  if (expiresOn < today) return 'red';
  const days = (Date.parse(`${expiresOn}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000;
  return days <= 60 ? 'amber' : 'green';
}

// ---------------------------------------------------------------- quarterly SMS report (rule 4)
export function quarterOf(date: string = todayIso()): string {
  return `${date.slice(0, 4)}-Q${Math.floor((Number(date.slice(5, 7)) - 1) / 3) + 1}`;
}
export function previousQuarter(date: string = todayIso()): string {
  const y = Number(date.slice(0, 4));
  const q = Math.floor((Number(date.slice(5, 7)) - 1) / 3) + 1;
  return q === 1 ? `${y - 1}-Q4` : `${y}-Q${q - 1}`;
}
export const isQuarter = (v: string): boolean => /^\d{4}-Q[1-4]$/.test(v);

/** Total contacts of the report (`totals` may be a number or `{contacts}`); undefined when suppressed / absent. */
export function smsTotal(r: SmsReport): number | undefined {
  const t = r.totals;
  if (typeof t === 'number') return t;
  if (t && typeof t === 'object' && !t.suppressed && typeof t.contacts === 'number') return t.contacts;
  return undefined;
}

// ---------------------------------------------------------------- CISM (F04)
/** 72-h offer SLA: server verdict when known, otherwise a live countdown (amber inside 24 h). */
export function cismSla(e: Pick<CismEvent, 'offerDueBy' | 'slaMet' | 'affected' | 'offered'>, now: number = Date.now()): { tone: Tone; label: string } {
  if (e.slaMet === true) return { tone: 'green', label: 'Offers sent within 72 h' };
  if (e.slaMet === false) return { tone: 'red', label: '72 h offer SLA missed' };
  if (e.affected > 0 && e.offered >= e.affected) return { tone: 'green', label: 'All offers sent' };
  const c = countdown(e.offerDueBy, now, 24 * 60, 4 * 60);
  return { tone: c.tone, label: c.overdue ? `Offers ${c.label.toLowerCase()}` : `Offers due – ${c.label}` };
}

export function followUpTone(f: FollowUpState | undefined, today: string = todayIso()): { tone: Tone; label: string } {
  if (!f) return { tone: 'muted', label: '–' };
  if (f.done) return { tone: 'green', label: 'Done' };
  if (f.dueOn < today) return { tone: 'red', label: `Overdue (${f.dueOn})` };
  const days = (Date.parse(`${f.dueOn}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000;
  return { tone: days <= 3 ? 'amber' : 'muted', label: `Due ${f.dueOn}` };
}

// ---------------------------------------------------------------- psychosocial risk (F05)
/** Factor score = likelihood × severity (1–25). */
export const factorScore = (likelihood: number, severity: number): number => likelihood * severity;

/** 5 × 5 matrix: ≥ 15 red, 8–14 amber, ≤ 7 green. */
export function ragFor(score: number): Rag {
  if (score >= 15) return 'RED';
  if (score >= 8) return 'AMBER';
  return 'GREEN';
}
export const ragTone = (r: Rag | undefined): Tone => (r === 'RED' ? 'red' : r === 'AMBER' ? 'amber' : r === 'GREEN' ? 'green' : 'muted');

export interface FactorInput {
  factor: PsychosocialFactor;
  likelihood: string;
  severity: string;
  controls: string;
}
export interface AssessmentForm {
  unitCode: string;
  assessedOn: string;
  reviewDate: string;
  factors: FactorInput[];
}
export const emptyAssessmentForm = (today: string = todayIso()): AssessmentForm => ({
  unitCode: '', assessedOn: today, reviewDate: '', factors: PSYCHOSOCIAL_FACTORS.map((factor) => ({ factor, likelihood: '', severity: '', controls: '' })),
});

const rating = (v: string): number | undefined => {
  const n = Number(v);
  return v !== '' && Number.isInteger(n) && n >= 1 && n <= 5 ? n : undefined;
};

/** Live preview: per-factor score + RAG and the overall (highest factor) score. */
export function previewAssessment(f: AssessmentForm): { factors: { factor: PsychosocialFactor; score?: number; rag?: Rag }[]; overall?: number; rag?: Rag } {
  const factors = f.factors.map((x) => {
    const l = rating(x.likelihood);
    const s = rating(x.severity);
    const score = l && s ? factorScore(l, s) : undefined;
    return { factor: x.factor, score, rag: score === undefined ? undefined : ragFor(score) };
  });
  const scores = factors.map((x) => x.score).filter((x): x is number => x !== undefined);
  const overall = scores.length ? Math.max(...scores) : undefined;
  return { factors, overall, rag: overall === undefined ? undefined : ragFor(overall) };
}

export function validateAssessment(f: AssessmentForm): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.unitCode.trim()) errs.push({ field: 'psy-unit', message: 'Enter the unit code.' });
  if (!f.assessedOn) errs.push({ field: 'psy-assessed', message: 'Enter the assessment date.' });
  if (!f.reviewDate) errs.push({ field: 'psy-review', message: 'Set the review date.' });
  else if (f.assessedOn && f.reviewDate <= f.assessedOn) errs.push({ field: 'psy-review', message: 'The review date must be after the assessment date.' });
  f.factors.forEach((x, i) => {
    if (rating(x.likelihood) === undefined) errs.push({ field: `psy-${i}-likelihood`, message: `${x.factor.replace(/_/g, ' ').toLowerCase()}: likelihood must be 1–5.` });
    if (rating(x.severity) === undefined) errs.push({ field: `psy-${i}-severity`, message: `${x.factor.replace(/_/g, ' ').toLowerCase()}: severity must be 1–5.` });
  });
  return errs;
}

// ---------------------------------------------------------------- 422 field errors
/**
 * Map a 400 / 422 problem that names a `field` (e.g. `consentId`, `sessionsAuthorised`, `factors[2].likelihood`) to the form
 * field id used by the error summary. Unknown fields fall back to `fallbackField` with the server's detail.
 */
export function serverFieldError(e: unknown, map: Record<string, string>, fallbackField?: string): FieldError | undefined {
  if (!(e instanceof ApiError) || (e.status !== 422 && e.status !== 400)) return undefined;
  const raw = e.problem?.field;
  const message = e.problem?.detail ?? e.problem?.title ?? 'Check this field.';
  if (raw) {
    const key = raw.replace(/\[(\d+)\]/g, '.$1');
    const id = map[key] ?? map[key.split('.')[0] ?? key];
    if (id) return { field: id, message };
  }
  return fallbackField && raw ? { field: fallbackField, message } : undefined;
}
