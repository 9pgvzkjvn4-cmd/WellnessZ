/**
 * E10 display / validation helpers (pure functions, unit-tested in testing.test.ts). The server is authoritative
 * (selection maths, windows, MRO state machine); these drive previews, countdowns and client-side form checks.
 */
import type { CollectionBody, MroDecisionBody, Panel, RefusalReason, RoleGroup, Selection, SpecimenType, TestingPolicy, TestStatus, TestType, Verdict } from '../api/testing';
import { localToIso } from '../components/format';
import { todayIso, type Tone } from './absence';
import type { FieldError } from './devices';
import { countdown, type Countdown } from './support';
import { cellText, isSuppressed } from './suppression';

// ---------------------------------------------------------------- windows & countdowns
/** Alcohol windows are short (8 h): amber inside 2 h, red inside 1 h. Drug windows (32 h): amber inside 8 h, red inside 2 h. */
export function windowKind(panels: readonly Panel[]): 'ALCOHOL' | 'DRUGS' {
  return panels.includes('ALCOHOL_BREATH') ? 'ALCOHOL' : 'DRUGS';
}
export function requirementCountdown(r: { dueAt: string; panels: readonly Panel[]; status?: TestStatus }, now: number = Date.now()): Countdown & { kind: 'ALCOHOL' | 'DRUGS' } {
  const kind = windowKind(r.panels);
  if (r.status && ['COLLECTED', 'IN_LAB', 'MRO_REVIEW', 'VERIFIED_NEGATIVE', 'VERIFIED_POSITIVE', 'REFUSAL', 'CANCELLED'].includes(r.status)) {
    return { kind, tone: 'muted', label: 'Window closed', overdue: false, minutesLeft: Number.POSITIVE_INFINITY };
  }
  if (r.status === 'WINDOW_MISSED') return { kind, tone: 'red', label: 'Window missed', overdue: true, minutesLeft: 0 };
  const c = kind === 'ALCOHOL' ? countdown(r.dueAt, now, 120, 60) : countdown(r.dueAt, now, 8 * 60, 120);
  return { ...c, kind };
}

export function testStatusTone(s: TestStatus): Tone {
  switch (s) {
    case 'VERIFIED_NEGATIVE': return 'green';
    case 'VERIFIED_POSITIVE':
    case 'REFUSAL':
    case 'WINDOW_MISSED': return 'red';
    case 'MRO_REVIEW':
    case 'NOTIFIED':
    case 'DISPATCHED': return 'amber';
    default: return 'muted';
  }
}

// ---------------------------------------------------------------- policies
export const policyTone = (p: Pick<TestingPolicy, 'approvalStatus'>): Tone => (p.approvalStatus === 'APPROVED' ? 'green' : 'amber');

export function validatePolicy(p: TestingPolicy): FieldError[] {
  const errs: FieldError[] = [];
  const pct = (v: number) => Number.isFinite(v) && v >= 0 && v <= 100;
  if (!pct(p.randomDrugRatePct)) errs.push({ field: 'pol-drug-rate', message: 'Random drug rate must be 0–100 %.' });
  if (!pct(p.randomAlcoholRatePct)) errs.push({ field: 'pol-alcohol-rate', message: 'Random alcohol rate must be 0–100 %.' });
  if (!Number.isInteger(p.alcoholWindowHours) || p.alcoholWindowHours < 1) errs.push({ field: 'pol-alcohol-window', message: 'Alcohol window must be a whole number of hours.' });
  if (!Number.isInteger(p.drugWindowHours) || p.drugWindowHours < 1) errs.push({ field: 'pol-drug-window', message: 'Drug window must be a whole number of hours.' });
  if (!Number.isInteger(p.leadTimeMinutes) || p.leadTimeMinutes < 0) errs.push({ field: 'pol-lead', message: 'Lead time must be zero or more minutes.' });
  if (p.panels.length === 0) errs.push({ field: 'pol-panels', message: 'Choose at least one panel.' });
  if (!p.jurisdiction.trim()) errs.push({ field: 'pol-jurisdiction', message: 'Enter the jurisdiction.' });
  return errs;
}

// ---------------------------------------------------------------- random selection
/** Contract §4.1: n = ceil(poolSize × rate / 12) (rate in %, monthly selection). */
export function expectedSelectionCount(poolSize: number, ratePct: number): number {
  if (poolSize <= 0 || ratePct <= 0) return 0;
  return Math.ceil((poolSize * ratePct) / 100 / 12 - 1e-9);
}

export const currentPeriod = (today: string = todayIso()): string => today.slice(0, 7);
export const isPeriod = (v: string): boolean => /^\d{4}-(0[1-9]|1[0-2])$/.test(v);

export interface SelectionForm {
  roleGroup: RoleGroup | '';
  period: string;
  testType: TestType | '';
}

export function validateSelection(f: SelectionForm, existing: readonly Pick<Selection, 'roleGroup' | 'period' | 'testType'>[] = [], today: string = todayIso()): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.roleGroup) errs.push({ field: 'sel-role-group', message: 'Choose the role group.' });
  if (!isPeriod(f.period)) errs.push({ field: 'sel-period', message: 'Enter the period as YYYY-MM.' });
  else if (f.period < currentPeriod(today)) errs.push({ field: 'sel-period', message: 'Selections cannot be made for a past month.' });
  if (!f.testType) errs.push({ field: 'sel-test-type', message: 'Choose drugs or alcohol.' });
  if (f.roleGroup && f.testType && isPeriod(f.period) && existing.some((s) => s.roleGroup === f.roleGroup && s.period === f.period && s.testType === f.testType)) {
    errs.push({ field: 'sel-period', message: 'A selection already exists for this role group, period and test type.' });
  }
  return errs;
}

/** Short fingerprint for display (hashes / seed commitments are long hex strings). */
export const shortHash = (h: string | undefined): string => (!h ? '–' : h.length > 16 ? `${h.slice(0, 8)}…${h.slice(-8)}` : h);

// ---------------------------------------------------------------- e-CCF (collection)
/** Barcode scanners in keyboard-wedge mode type the code + Enter; strip whitespace / control characters, upper-case. */
export function normaliseSpecimenId(raw: string): string {
  // eslint-disable-next-line no-control-regex
  return raw.replace(/[\s\u0000-\u001f\u007f]/g, '').toUpperCase();
}
export const SPECIMEN_ID = /^[A-Z0-9][A-Z0-9-]{5,31}$/;

export interface CollectionForm {
  specimenId: string;
  specimenType: SpecimenType | '';
  collectedAt: string;
  siteCode: string;
  temperatureInRange: '' | 'yes' | 'no';
  sealIntact: boolean;
  donorIdentityChecked: boolean;
  splitSpecimen: boolean;
  breathScreenResult: string;
  remarks: string;
}
export const emptyCollectionForm = (siteCode = '', collectedAt = ''): CollectionForm => ({
  specimenId: '', specimenType: '', collectedAt, siteCode, temperatureInRange: '', sealIntact: false, donorIdentityChecked: false, splitSpecimen: false, breathScreenResult: '', remarks: '',
});

export function validateCollection(f: CollectionForm, now: number = Date.now()): FieldError[] {
  const errs: FieldError[] = [];
  const id = normaliseSpecimenId(f.specimenId);
  if (!f.specimenType) errs.push({ field: 'ccf-type', message: 'Choose the specimen type.' });
  if (!id) errs.push({ field: 'ccf-specimen', message: 'Scan or type the specimen ID from the barcode label.' });
  else if (!SPECIMEN_ID.test(id)) errs.push({ field: 'ccf-specimen', message: 'The specimen ID must be 6–32 letters, digits or hyphens.' });
  const at = localToIso(f.collectedAt);
  if (!at) errs.push({ field: 'ccf-collected', message: 'Enter the collection time.' });
  else if (Date.parse(at) > now + 5 * 60_000) errs.push({ field: 'ccf-collected', message: 'The collection time cannot be in the future.' });
  if (!f.siteCode.trim()) errs.push({ field: 'ccf-site', message: 'Enter the collection site.' });
  if (!f.donorIdentityChecked) errs.push({ field: 'ccf-identity', message: 'Confirm the donor’s photo identity was checked.' });
  if (f.specimenType === 'URINE' && !f.temperatureInRange) errs.push({ field: 'ccf-temperature', message: 'Record whether the urine temperature was in range (32–38 °C) within 4 minutes.' });
  if (f.specimenType && f.specimenType !== 'BREATH' && !f.sealIntact) errs.push({ field: 'ccf-seal', message: 'Seal the specimen in front of the donor and confirm the seal is intact – otherwise record a refusal or recollect.' });
  if (f.specimenType === 'BREATH') {
    const v = Number(f.breathScreenResult);
    if (f.breathScreenResult.trim() === '' || !Number.isFinite(v) || v < 0 || v > 1) errs.push({ field: 'ccf-breath', message: 'Enter the breath screen result in g/210 L (0–1.000).' });
  }
  return errs;
}

export function toCollectionBody(f: CollectionForm): CollectionBody {
  const type = f.specimenType as SpecimenType;
  return {
    specimenId: normaliseSpecimenId(f.specimenId),
    specimenType: type,
    collectedAt: localToIso(f.collectedAt) ?? f.collectedAt,
    siteCode: f.siteCode.trim(),
    ...(type === 'URINE' ? { temperatureInRange: f.temperatureInRange === 'yes' } : {}),
    sealIntact: type === 'BREATH' ? true : f.sealIntact,
    donorIdentityChecked: f.donorIdentityChecked,
    splitSpecimen: type === 'URINE' ? f.splitSpecimen : false,
    ...(type === 'BREATH' ? { breathScreenResult: Number(f.breathScreenResult) } : {}),
    ...(f.remarks.trim() ? { remarks: f.remarks.trim() } : {}),
  };
}

export function validateRefusal(f: { reason: RefusalReason | ''; observations: string }): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.reason) errs.push({ field: 'ref-reason', message: 'Choose the refusal reason.' });
  if (!f.observations.trim()) errs.push({ field: 'ref-observations', message: 'Record what you observed.' });
  return errs;
}

// ---------------------------------------------------------------- MRO
export interface MroForm {
  verdict: Verdict | '';
  prescriptionVerified: boolean;
  interviewNote: string;
  splitSpecimenRequested: boolean;
}
export const emptyMroForm = (): MroForm => ({ verdict: '', prescriptionVerified: false, interviewNote: '', splitSpecimenRequested: false });

export function validateMro(f: MroForm): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.verdict) errs.push({ field: 'mro-verdict', message: 'Choose the verdict.' });
  if (f.verdict === 'NEGATIVE_LEGITIMATE_PRESCRIPTION' && !f.prescriptionVerified) errs.push({ field: 'mro-prescription', message: 'Verify the prescription before recording a legitimate-prescription negative.' });
  if ((f.verdict === 'POSITIVE' || f.verdict === 'POSITIVE_DILUTE' || f.verdict === 'NEGATIVE_LEGITIMATE_PRESCRIPTION') && !f.interviewNote.trim()) {
    errs.push({ field: 'mro-note', message: 'Record the donor interview before this verdict.' });
  }
  return errs;
}

export const toMroBody = (f: MroForm): MroDecisionBody => ({
  verdict: f.verdict as Verdict,
  ...(f.verdict === 'NEGATIVE_LEGITIMATE_PRESCRIPTION' ? { prescriptionVerified: f.prescriptionVerified } : {}),
  ...(f.interviewNote.trim() ? { interviewNote: f.interviewNote.trim() } : {}),
  ...(f.splitSpecimenRequested ? { splitSpecimenRequested: true } : {}),
});

/** Non-negative analytes – the reason the item is in the MRO queue. */
export const nonNegative = (results: readonly { outcome: string; analyte: string }[]): string[] => results.filter((r) => r.outcome === 'NON_NEGATIVE').map((r) => r.analyte);

// ---------------------------------------------------------------- RTD
export interface RtdForm {
  employeeId: string;
  caseId: string;
  evaluator: string;
  treatmentProvider: string;
  followUpTests: string;
  followUpMonths: string;
}
export const emptyRtdForm = (): RtdForm => ({ employeeId: '', caseId: '', evaluator: '', treatmentProvider: '', followUpTests: '6', followUpMonths: '12' });

export function validateRtd(f: RtdForm): FieldError[] {
  const errs: FieldError[] = [];
  if (!f.employeeId.trim()) errs.push({ field: 'rtd-employee', message: 'Enter the employee ID.' });
  if (!f.caseId.trim()) errs.push({ field: 'rtd-case', message: 'Enter the restricted substance case.' });
  if (!f.evaluator.trim()) errs.push({ field: 'rtd-evaluator', message: 'Name the substance-abuse evaluator.' });
  const n = Number(f.followUpTests);
  if (!Number.isInteger(n) || n < 1 || n > 60) errs.push({ field: 'rtd-tests', message: 'Follow-up tests must be 1–60.' });
  const m = Number(f.followUpMonths);
  if (!Number.isInteger(m) || m < 1 || m > 60) errs.push({ field: 'rtd-months', message: 'Follow-up period must be 1–60 months.' });
  return errs;
}

// ---------------------------------------------------------------- statistics (suppression, rule 4)
export function statCell(v: number | null | undefined, rowSuppressed: boolean, k: number): string {
  return cellText(v, k, rowSuppressed);
}
export function rateCell(v: number | null | undefined, rowSuppressed: boolean): string {
  if (rowSuppressed || typeof v !== 'number' || !Number.isFinite(v)) return rowSuppressed ? 'suppressed' : '–';
  return `${v.toFixed(1)} %`;
}
export const rowIsSuppressed = (row: { suppressed?: boolean; tests?: number | null }, k: number): boolean => !!row.suppressed || isSuppressed(row.tests, k);
