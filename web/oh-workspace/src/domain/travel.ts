/**
 * E11 client-side helpers (pure, unit-tested in travel.test.ts): protocol-timer countdowns and overdue state, serology
 * schedule, contact-tracing risk display and the 4 h tracing KPI, vaccination-campaign uptake maths with small-cell
 * suppression, public-health report deadlines, advisory acknowledgement rate, travel recommendations vs the E07
 * immunisation status and form validation.
 */
import type {
  AdvisoryAcks,
  CdCondition,
  Destination,
  ExposureTimer,
  PublicHealthReport,
  RiskLevel,
  SerologyPoint,
  TracedContact,
  TravelRecommendation,
  UptakeRow,
} from '../api/travel';
import type { EmployeeImmunisation } from '../api/surveillance';
import { formatDuration } from './sla';
import { daysUntil, todayIso, type Tone } from './absence';
import type { FieldError } from './devices';
import { DEFAULT_K } from './suppression';
import { lotInput } from './surveillance';

const MIN = 60_000;
const HOUR = 60 * MIN;

// ---------------------------------------------------------------- exposure protocol timers (§5.3)
/** The `exposure-timers` job alerts when a task is due within 30 min or overdue – the UI uses the same band. */
export const TIMER_WARNING_MINUTES = 30;

export interface TimerView {
  tone: Tone;
  label: string;
  overdue: boolean;
  done: boolean;
  /** Whole minutes until due (negative = overdue); undefined when done or no valid due time. */
  minutesLeft?: number;
}

/** "Overdue by 1 h 5 min" / "25 min left" / "Done". Overdue = server OVERDUE, or still PENDING past `dueAt` (job not run yet). */
export function timerView(t: Pick<ExposureTimer, 'dueAt' | 'status' | 'completedAt'>, now: number = Date.now()): TimerView {
  if (t.status === 'DONE') return { tone: 'green', label: 'Done', overdue: false, done: true };
  const due = Date.parse(t.dueAt);
  if (Number.isNaN(due)) return { tone: t.status === 'OVERDUE' ? 'red' : 'muted', label: t.status === 'OVERDUE' ? 'Overdue' : 'No due time', overdue: t.status === 'OVERDUE', done: false };
  const ms = due - now;
  // Whole minutes, truncated towards zero like the hh:mm:ss clock; anything overdue shows at least 1 min.
  const minutesLeft = ms >= 0 ? Math.floor(ms / MIN) : -Math.max(1, Math.floor(-ms / MIN));
  if (ms < 0 || t.status === 'OVERDUE') return { tone: 'red', label: ms < 0 ? `Overdue by ${formatDuration(-minutesLeft)}` : 'Overdue', overdue: true, done: false, minutesLeft };
  if (ms <= TIMER_WARNING_MINUTES * MIN) return { tone: 'amber', label: `${formatDuration(minutesLeft)} left`, overdue: false, done: false, minutesLeft };
  return { tone: 'muted', label: `${formatDuration(minutesLeft)} left`, overdue: false, done: false, minutesLeft };
}

/** Live countdown "hh:mm:ss" (or "-hh:mm:ss" once overdue) for the next open timer. */
export function clock(dueAt: string, now: number = Date.now()): string {
  const due = Date.parse(dueAt);
  if (Number.isNaN(due)) return '–';
  const s = Math.floor(Math.abs(due - now) / 1000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${due - now < 0 ? '−' : ''}${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`;
}

/** Open timers soonest first; done ones last. */
export function sortTimers<T extends Pick<ExposureTimer, 'dueAt' | 'status'>>(timers: readonly T[]): T[] {
  return [...timers].sort((a, b) => Number(a.status === 'DONE') - Number(b.status === 'DONE') || a.dueAt.localeCompare(b.dueAt));
}

export function nextOpenTimer<T extends Pick<ExposureTimer, 'dueAt' | 'status'>>(timers: readonly T[]): T | undefined {
  return sortTimers(timers).find((t) => t.status !== 'DONE');
}

export function overdueTimerCount(timers: readonly Pick<ExposureTimer, 'dueAt' | 'status' | 'completedAt'>[], now: number = Date.now()): number {
  return timers.filter((t) => timerView(t, now).overdue).length;
}

/** Serology follow-up (needlestick: 6 weeks, 3 and 6 months). */
export function serologyView(s: SerologyPoint, today: string = todayIso()): { tone: Tone; label: string } {
  if (s.done) return { tone: 'green', label: 'Done' };
  const d = daysUntil(s.dueOn, today);
  if (d < 0) return { tone: 'red', label: `Overdue ${-d} d` };
  if (d <= 7) return { tone: 'amber', label: d === 0 ? 'Due today' : `Due in ${d} d` };
  return { tone: 'muted', label: `Due in ${d} d` };
}

/** Only http(s) links are rendered for SDS documents (never `javascript:` or `data:`). */
export function safeHttpUrl(url: string | null | undefined): string | undefined {
  if (!url) return undefined;
  try {
    const u = new URL(url);
    return u.protocol === 'https:' || u.protocol === 'http:' ? u.toString() : undefined;
  } catch {
    return undefined;
  }
}

// ---------------------------------------------------------------- contact tracing (§5.2)
const RISK_ORDER: Record<RiskLevel, number> = { HIGH: 0, MEDIUM: 1, LOW: 2 };
const RISK_TONE: Record<RiskLevel, Tone> = { HIGH: 'red', MEDIUM: 'amber', LOW: 'muted' };

/** Risk badge text (+ the contract rule for HIGH: same proximity group ≥ 15 min). */
export function riskView(level: RiskLevel | string): { tone: Tone; label: string; title: string } {
  const l = (level in RISK_ORDER ? level : 'LOW') as RiskLevel;
  const title = l === 'HIGH' ? 'Same proximity group for 15 minutes or more' : l === 'MEDIUM' ? 'Same duty or shorter / indirect proximity' : 'Same facility, limited overlap';
  return { tone: RISK_TONE[l], label: `${l.charAt(0)}${l.slice(1).toLowerCase()} risk`, title };
}

/** HIGH first, then the longest overlap, then name. */
export function sortContacts<T extends Pick<TracedContact, 'riskLevel' | 'overlapMinutes' | 'displayName'>>(contacts: readonly T[]): T[] {
  return [...contacts].sort(
    (a, b) => (RISK_ORDER[a.riskLevel] ?? 3) - (RISK_ORDER[b.riskLevel] ?? 3) || b.overlapMinutes - a.overlapMinutes || a.displayName.localeCompare(b.displayName),
  );
}

export function riskCounts(contacts: readonly Pick<TracedContact, 'riskLevel'>[]): Record<RiskLevel, number> {
  const out: Record<RiskLevel, number> = { HIGH: 0, MEDIUM: 0, LOW: 0 };
  for (const c of contacts) if (c.riskLevel in out) out[c.riskLevel] += 1;
  return out;
}

/** "45 min", "2 h 5 min" overlap. */
export const overlapText = (minutes: number): string => (Number.isFinite(minutes) && minutes > 0 ? formatDuration(minutes) : '–');

/** Contact-tracing KPI: the contact list is generated within 4 h of confirmation. */
export const TRACING_KPI_HOURS = 4;
export function tracingKpi(hoursSinceConfirmation: number | null | undefined): { status: 'MET' | 'MISSED' | 'UNKNOWN'; label: string } {
  if (typeof hoursSinceConfirmation !== 'number' || !Number.isFinite(hoursSinceConfirmation)) return { status: 'UNKNOWN', label: 'Not generated yet' };
  const h = Math.max(0, hoursSinceConfirmation);
  const text = h < 1 ? `${Math.round(h * 60)} min` : `${h.toFixed(1)} h`;
  return { status: h <= TRACING_KPI_HOURS ? 'MET' : 'MISSED', label: `Contact list generated in ${text}` };
}

/** Monitoring / check-in state of a contact. */
export function monitoringView(c: Pick<TracedContact, 'notifiedAt' | 'monitoringUntil' | 'symptomatic' | 'lastCheckIn'>, today: string = todayIso()): { tone: Tone; label: string } {
  if (c.symptomatic) return { tone: 'red', label: 'Symptomatic' };
  if (!c.notifiedAt) return { tone: 'amber', label: 'Not notified' };
  if (c.monitoringUntil && daysUntil(c.monitoringUntil.slice(0, 10), today) < 0) return { tone: 'green', label: 'Monitoring ended' };
  return { tone: 'muted', label: c.lastCheckIn ? 'Monitoring – checked in' : 'Monitoring – no check-in yet' };
}

export function checkInText(v: TracedContact['lastCheckIn']): { at?: string; detail?: string } {
  if (!v) return {};
  if (typeof v === 'string') return { at: v };
  const parts = [...(v.symptoms ?? []).map((s) => s.replace(/_/g, ' ').toLowerCase()), typeof v.temperatureC === 'number' ? `${v.temperatureC.toFixed(1)} °C` : ''].filter(Boolean);
  return { at: v.at, detail: parts.join(', ') || undefined };
}

export interface TraceForm {
  windowFrom: string;
  windowTo: string;
  include: string[];
}
export function validateTrace(f: TraceForm): FieldError[] {
  const errs: FieldError[] = [];
  const from = Date.parse(f.windowFrom);
  const to = Date.parse(f.windowTo);
  if (!f.windowFrom || Number.isNaN(from)) errs.push({ field: 'trace-from', message: 'Enter the start of the infectious window.' });
  if (!f.windowTo || Number.isNaN(to)) errs.push({ field: 'trace-to', message: 'Enter the end of the infectious window.' });
  else if (!Number.isNaN(from) && to <= from) errs.push({ field: 'trace-to', message: 'The window must end after it starts.' });
  else if (!Number.isNaN(from) && to - from > 31 * 24 * HOUR) errs.push({ field: 'trace-to', message: 'The window can be at most 31 days.' });
  if (f.include.length === 0) errs.push({ field: 'trace-include', message: 'Choose at least one contact source.' });
  return errs;
}

export interface ExclusionForm {
  decision: string;
  from: string;
  until: string;
  returnCriteria: string;
}
export function validateExclusion(f: ExclusionForm): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.decision) errs.push({ field: 'excl-decision', message: 'Choose a decision.' });
  if (!f.from) errs.push({ field: 'excl-from', message: 'Enter the start date.' });
  if (f.until && f.from && f.until < f.from) errs.push({ field: 'excl-until', message: 'The end date cannot be before the start date.' });
  if ((f.decision === 'EXCLUDED' || f.decision === 'ISOLATING') && splitLines(f.returnCriteria).length === 0)
    errs.push({ field: 'excl-criteria', message: 'List at least one return-to-work criterion.' });
  return errs;
}

/** Default exclusion end from the condition's `exclusionDays`. */
export function suggestedUntil(from: string, condition: Pick<CdCondition, 'exclusionDays'> | undefined): string {
  if (!from || !condition?.exclusionDays) return '';
  const d = new Date(`${from}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + condition.exclusionDays);
  return d.toISOString().slice(0, 10);
}

export const splitLines = (v: string): string[] => v.split(/\n/).map((s) => s.trim()).filter(Boolean);

// ---------------------------------------------------------------- vaccination campaign uptake (§5.4)
export interface UptakeView {
  suppressed: boolean;
  /** Text for each numeric cell – "<5" when suppressed. */
  eligible: string;
  vaccinated: string;
  declined: string;
  pct: string;
  pctValue?: number;
  tone: Tone;
}

/**
 * One uptake row. Suppressed when the server says so, or (defence in depth) when the eligible group is below k.
 * The percentage is the server's `uptakePct`, else vaccinated ÷ eligible.
 */
export function uptakeView(r: UptakeRow, targetPct?: number, k: number = DEFAULT_K): UptakeView {
  const eligible = r.eligible ?? undefined;
  const suppressed = r.suppressed || typeof eligible !== 'number' || (eligible > 0 && eligible < k);
  if (suppressed) return { suppressed: true, eligible: `<${k}`, vaccinated: '–', declined: '–', pct: 'Suppressed', tone: 'muted' };
  const vaccinated = r.vaccinated ?? 0;
  const pctValue = typeof r.uptakePct === 'number' ? r.uptakePct : uptakePct(vaccinated, eligible);
  return {
    suppressed: false,
    eligible: String(eligible),
    vaccinated: String(vaccinated),
    declined: String(r.declined ?? 0),
    pct: pctValue === undefined ? '–' : `${pctValue.toFixed(1)} %`,
    pctValue,
    tone: pctValue === undefined || targetPct === undefined ? 'muted' : pctValue >= targetPct ? 'green' : pctValue >= targetPct * 0.75 ? 'amber' : 'red',
  };
}

export function uptakePct(vaccinated: number, eligible: number): number | undefined {
  if (!(eligible > 0)) return undefined;
  return Math.round((Math.min(vaccinated, eligible) / eligible) * 1000) / 10;
}

/**
 * Totals over the reported (non-suppressed) rows only – suppressed rows never contribute, so the totals cannot be
 * used to back-calculate a suppressed cell.
 */
export function uptakeTotals(rows: readonly UptakeRow[], k: number = DEFAULT_K): { eligible: number; vaccinated: number; declined: number; pct?: number; suppressedRows: number } {
  let eligible = 0;
  let vaccinated = 0;
  let declined = 0;
  let suppressedRows = 0;
  for (const r of rows) {
    if (uptakeView(r, undefined, k).suppressed) {
      suppressedRows += 1;
      continue;
    }
    eligible += r.eligible ?? 0;
    vaccinated += r.vaccinated ?? 0;
    declined += r.declined ?? 0;
  }
  return { eligible, vaccinated, declined, pct: uptakePct(vaccinated, eligible), suppressedRows };
}

export interface CampaignForm {
  name: string;
  vaccineCode: string;
  eligibilityMode: 'all' | 'jobCodes' | 'subsidiaries';
  eligibilityCodes: string;
  startDate: string;
  endDate: string;
  siteCodes: string;
  targetUptakePct: string;
}
export function validateCampaign(f: CampaignForm): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.name.trim()) errs.push({ field: 'vc-name', message: 'Enter a campaign name.' });
  if (!f.vaccineCode.trim()) errs.push({ field: 'vc-vaccine', message: 'Enter the vaccine code.' });
  if (f.eligibilityMode !== 'all' && codes(f.eligibilityCodes).length === 0) errs.push({ field: 'vc-elig', message: 'List at least one code for the eligibility.' });
  if (!f.startDate) errs.push({ field: 'vc-start', message: 'Enter the start date.' });
  if (!f.endDate) errs.push({ field: 'vc-end', message: 'Enter the end date.' });
  else if (f.startDate && f.endDate < f.startDate) errs.push({ field: 'vc-end', message: 'The end date cannot be before the start date.' });
  if (codes(f.siteCodes).length === 0) errs.push({ field: 'vc-sites', message: 'List at least one site.' });
  const t = Number(f.targetUptakePct);
  if (f.targetUptakePct.trim() === '' || !Number.isFinite(t) || t <= 0 || t > 100) errs.push({ field: 'vc-target', message: 'Target uptake must be between 1 and 100 %.' });
  return errs;
}

export const codes = (v: string): string[] => [...new Set(v.split(/[\s,;]+/).map((s) => s.trim().toUpperCase()).filter(Boolean))];

// ---------------------------------------------------------------- public-health reports (§5.6)
/** Deadline from the server's `hoursLeft` (else `dueAt`): submitted → done; < 0 → red; ≤ 6 h → amber; else green. */
export function reportDeadline(r: Pick<PublicHealthReport, 'hoursLeft' | 'dueAt' | 'submittedAt' | 'status'>, now: number = Date.now()): { tone: Tone; label: string; hoursLeft?: number } {
  if (r.submittedAt || r.status === 'SUBMITTED' || r.status === 'ACKNOWLEDGED' || r.status === 'CLOSED') return { tone: 'green', label: 'Submitted' };
  let hours: number | undefined = typeof r.hoursLeft === 'number' && Number.isFinite(r.hoursLeft) ? r.hoursLeft : undefined;
  if (hours === undefined && r.dueAt) {
    const due = Date.parse(r.dueAt);
    if (!Number.isNaN(due)) hours = (due - now) / HOUR;
  }
  if (hours === undefined) return { tone: 'muted', label: 'No deadline' };
  const span = formatDuration(Math.abs(hours) * 60);
  if (hours < 0) return { tone: 'red', label: `Overdue by ${span}`, hoursLeft: hours };
  if (hours <= 6) return { tone: 'amber', label: `${span} left`, hoursLeft: hours };
  return { tone: 'green', label: `${span} left`, hoursLeft: hours };
}

// ---------------------------------------------------------------- advisories (§5.5)
export function ackRateView(a: Pick<AdvisoryAcks, 'targeted' | 'acknowledged' | 'ratePct'> | undefined): { tone: Tone; label: string } {
  if (!a || !(a.targeted > 0)) return { tone: 'muted', label: 'No one targeted' };
  const pct = typeof a.ratePct === 'number' && Number.isFinite(a.ratePct) ? a.ratePct : (a.acknowledged / a.targeted) * 100;
  return { tone: pct >= 90 ? 'green' : pct >= 50 ? 'amber' : 'red', label: `${pct.toFixed(0)} % (${a.acknowledged} of ${a.targeted})` };
}

export interface AdvisoryForm {
  title: string;
  destinationCode: string;
  baseCode: string;
  roles: string;
  severity: string;
  body: string;
  validFrom: string;
  validUntil: string;
}
export function validateAdvisory(f: AdvisoryForm, max = 4000): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.title.trim()) errs.push({ field: 'adv-title', message: 'Enter a title.' });
  if (!f.destinationCode.trim() && !f.baseCode.trim() && codes(f.roles).length === 0)
    errs.push({ field: 'adv-destination', message: 'Target a destination, a base or at least one role.' });
  if (f.baseCode.trim() && codes(f.roles).length === 0) errs.push({ field: 'adv-roles', message: 'Choose the roles at the base that should receive it.' });
  if (!f.severity) errs.push({ field: 'adv-severity', message: 'Choose a severity.' });
  if (!f.body.trim()) errs.push({ field: 'adv-body', message: 'Enter the advisory text.' });
  else if (f.body.length > max) errs.push({ field: 'adv-body', message: `The text can be at most ${max} characters.` });
  if (!f.validFrom) errs.push({ field: 'adv-from', message: 'Enter the start of validity.' });
  if (!f.validUntil) errs.push({ field: 'adv-until', message: 'Enter the end of validity.' });
  else if (f.validFrom && f.validUntil < f.validFrom) errs.push({ field: 'adv-until', message: 'Validity cannot end before it starts.' });
  return errs;
}

// ---------------------------------------------------------------- travel consultation (§5.1)
const COVERED = new Set(['COMPLETE', 'COVERED', 'IMMUNE']);

/** Recommendation status → badge. Covered (E07 complete) green; due / titre amber; overdue / not recorded red. */
export function recommendationView(r: Pick<TravelRecommendation, 'status' | 'covered'>): { tone: Tone; label: string; action: string } {
  const s = (r.status ?? '').toUpperCase();
  if (r.covered || COVERED.has(s)) return { tone: 'green', label: 'Covered', action: 'No dose needed' };
  if (s === 'DUE' || s === 'TITRE_NEEDED') return { tone: 'amber', label: s === 'DUE' ? 'Booster due' : 'Titre needed', action: s === 'DUE' ? 'Give booster' : 'Check titre' };
  if (s === 'OVERDUE') return { tone: 'red', label: 'Overdue', action: 'Give dose' };
  return { tone: 'red', label: 'Not recorded', action: 'Give dose / check history' };
}

/**
 * Client-side fallback when `/travel/recommendations` is unavailable: the union of recommended vaccines of the chosen
 * destinations matched against the E07 immunisation status.
 */
export function recommendationsFor(destinations: readonly Destination[], chosen: readonly string[], immunisations: readonly EmployeeImmunisation[]): TravelRecommendation[] {
  const byVaccine = new Map<string, string[]>();
  for (const d of destinations) {
    if (!chosen.includes(d.countryCode)) continue;
    for (const v of d.recommendedVaccines) byVaccine.set(v, [...(byVaccine.get(v) ?? []), d.countryCode]);
  }
  return [...byVaccine.entries()]
    .map(([vaccineCode, dests]) => {
      const imm = immunisations.find((i) => i.vaccineCode === vaccineCode);
      return { vaccineCode, vaccineName: imm?.vaccineName, status: imm?.status ?? 'NOT_RECORDED', lastDoseOn: imm?.lastDoseOn, nextDueOn: imm?.nextDueOn, destinations: dests };
    })
    .sort((a, b) => a.vaccineCode.localeCompare(b.vaccineCode));
}

export interface VaccineRow {
  vaccineCode: string;
  doseNumber: string;
  lot: string;
  site: string;
  route: string;
}
export interface ProphylaxisRow {
  drug: string;
  regimen: string;
  startDate: string;
}
export interface ConsultationForm {
  employeeId: string;
  destinations: string[];
  departDate: string;
  returnDate: string;
  purpose: string;
  vaccines: VaccineRow[];
  prophylaxis: ProphylaxisRow[];
  advice: string;
}

export function validateConsultation(f: ConsultationForm, today: string = todayIso()): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.employeeId) errs.push({ field: 'tc-employee', message: 'Choose the employee.' });
  if (f.destinations.length === 0) errs.push({ field: 'tc-destinations', message: 'Choose at least one destination.' });
  if (!f.departDate) errs.push({ field: 'tc-depart', message: 'Enter the departure date.' });
  if (!f.returnDate) errs.push({ field: 'tc-return', message: 'Enter the return date.' });
  else if (f.departDate && f.returnDate < f.departDate) errs.push({ field: 'tc-return', message: 'Return cannot be before departure.' });
  if (!f.purpose) errs.push({ field: 'tc-purpose', message: 'Choose the purpose of the trip.' });
  f.vaccines.forEach((v, i) => {
    if (!v.vaccineCode.trim()) errs.push({ field: `tc-vac-${i}-code`, message: `Vaccine ${i + 1}: enter the vaccine code.` });
    const lot = lotInput(v.lot, today);
    if (lot.error) errs.push({ field: `tc-vac-${i}-lot`, message: `Vaccine ${i + 1}: ${lot.error}.` });
    const dose = Number(v.doseNumber);
    if (!Number.isInteger(dose) || dose < 1 || dose > 10) errs.push({ field: `tc-vac-${i}-dose`, message: `Vaccine ${i + 1}: dose number 1–10.` });
    if (!v.site) errs.push({ field: `tc-vac-${i}-site`, message: `Vaccine ${i + 1}: choose the site.` });
  });
  f.prophylaxis.forEach((p, i) => {
    if (!p.drug.trim()) errs.push({ field: `tc-pro-${i}-drug`, message: `Prophylaxis ${i + 1}: enter the drug.` });
    if (!p.regimen.trim()) errs.push({ field: `tc-pro-${i}-regimen`, message: `Prophylaxis ${i + 1}: enter the regimen.` });
    if (!p.startDate) errs.push({ field: `tc-pro-${i}-start`, message: `Prophylaxis ${i + 1}: enter the start date.` });
  });
  return errs;
}

/** Form → contract body (`vaccinesGiven.lotNumber` is the parsed lot, also when a GS1 barcode was scanned). */
export function consultationBody(f: ConsultationForm) {
  return {
    employeeId: f.employeeId,
    trip: { destinations: f.destinations, departDate: f.departDate, returnDate: f.returnDate, purpose: f.purpose as 'LAYOVER' | 'POSTING' | 'BUSINESS' },
    vaccinesGiven: f.vaccines.map((v) => ({ vaccineCode: v.vaccineCode.trim().toUpperCase(), doseNumber: Number(v.doseNumber), lotNumber: lotInput(v.lot).lotNumber ?? v.lot.trim(), site: v.site, route: v.route })),
    prophylaxis: f.prophylaxis.map((p) => ({ drug: p.drug.trim(), regimen: p.regimen.trim(), startDate: p.startDate })),
    ...(f.advice.trim() ? { advice: f.advice.trim() } : {}),
  };
}
