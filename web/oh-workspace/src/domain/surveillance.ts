/**
 * E07 client-side helpers: GS1 vaccine-lot barcode parsing, crew certificate expiry bands, CSV / JSON parsing for IH and
 * cosmic-dose imports, programme form validation, compliance formatting. Pure functions only.
 */
import { MEASUREMENT_TYPES, PANEL_TEST_PREFIXES, PANEL_TESTS, type Eligibility, type IhMeasurement, type PanelItem, type ProgrammeBody } from '../api/surveillance';
import { daysUntil, todayIso, type Tone } from './absence';
import type { FieldError } from './devices';

// ---------------------------------------------------------------- GS1
export interface Gs1Lot {
  gtin?: string;
  /** YYYY-MM-DD (AI 17; day 00 = last day of the month) */
  expiry?: string;
  lot?: string;
  serial?: string;
}

const GS = '\u001d';

function gs1Date(yymmdd: string): string | undefined {
  if (!/^\d{6}$/.test(yymmdd)) return undefined;
  const y = 2000 + Number(yymmdd.slice(0, 2));
  const m = Number(yymmdd.slice(2, 4));
  let d = Number(yymmdd.slice(4, 6));
  if (m < 1 || m > 12) return undefined;
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  if (d === 0) d = last;
  if (d > last) return undefined;
  return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

/**
 * Parse a GS1 vaccine barcode as typed by a keyboard-wedge scanner or by hand: human-readable `(01)…(17)YYMMDD(10)LOT`
 * or raw element strings with FNC1 / GS separators (`]d2`, `]C1`, `]Q3` symbology prefixes are ignored). Supported AIs:
 * 01 (GTIN-14), 17 (expiry), 10 (lot, ≤ 20), 21 (serial, ≤ 20). Returns undefined when nothing usable (no lot) is found.
 */
export function parseGs1(input: string): Gs1Lot | undefined {
  let s = input.trim().replace(/^\][A-Za-z]\d/, '');
  if (!s) return undefined;
  const out: Gs1Lot = {};
  if (s.startsWith('(')) {
    const re = /\((\d{2,4})\)([^(]*)/g;
    let m: RegExpExecArray | null;
    let any = false;
    while ((m = re.exec(s))) {
      any = true;
      const v = (m[2] ?? '').trim();
      if (!assign(out, m[1] ?? '', v)) return undefined;
    }
    return any && out.lot ? out : undefined;
  }
  // raw element string
  s = s.replace(/<GS>|\\x1d|\|/gi, GS);
  while (s.length > 0) {
    if (s.startsWith(GS)) {
      s = s.slice(1);
      continue;
    }
    const ai = s.slice(0, 2);
    if (ai === '01') {
      if (!assign(out, ai, s.slice(2, 16))) return undefined;
      s = s.slice(16);
    } else if (ai === '17') {
      if (!assign(out, ai, s.slice(2, 8))) return undefined;
      s = s.slice(8);
    } else if (ai === '10' || ai === '21') {
      const end = s.indexOf(GS, 2);
      const v = end < 0 ? s.slice(2) : s.slice(2, end);
      if (!assign(out, ai, v)) return undefined;
      s = end < 0 ? '' : s.slice(end + 1);
    } else {
      return undefined;
    }
  }
  return out.lot ? out : undefined;
}

function assign(out: Gs1Lot, ai: string, v: string): boolean {
  switch (ai) {
    case '01':
      if (!/^\d{14}$/.test(v)) return false;
      out.gtin = v;
      return true;
    case '17': {
      const d = gs1Date(v);
      if (!d) return false;
      out.expiry = d;
      return true;
    }
    case '10':
      if (!v || v.length > 20) return false;
      out.lot = v;
      return true;
    case '21':
      if (!v || v.length > 20) return false;
      out.serial = v;
      return true;
    default:
      return true; // unknown bracketed AIs are ignored
  }
}

/** Lot input heuristic: a barcode (GS1) vs a plain typed lot number. */
export function lotInput(raw: string, today: string = todayIso()): { lotNumber?: string; lotBarcode?: string; expiry?: string; expired: boolean; error?: string } {
  const v = raw.trim();
  if (!v) return { expired: false, error: 'Scan or type the lot number' };
  const looksGs1 = v.startsWith('(') || /^\][A-Za-z]\d/.test(v) || (/^(01\d{14}|17\d{6}|10)/.test(v) && v.length > 10);
  if (looksGs1) {
    const p = parseGs1(v);
    if (!p?.lot) return { expired: false, error: 'Barcode not recognised – type the lot number instead' };
    const expired = !!p.expiry && daysUntil(p.expiry, today) < 0;
    return { lotBarcode: v, lotNumber: p.lot, expiry: p.expiry, expired, ...(expired ? { error: `Lot ${p.lot} expired on ${p.expiry}` } : {}) };
  }
  if (!/^[A-Za-z0-9-]{2,20}$/.test(v)) return { expired: false, error: 'Lot number: 2–20 letters, digits or hyphens' };
  return { lotNumber: v, expired: false };
}

// ---------------------------------------------------------------- crew expiry bands
export type ExpiryBand = 'EXPIRED' | 'SUSPENDED' | 'D30' | 'D60' | 'D90' | 'VALID';

/** Alert bands of the nightly crew-certificates job (90 / 60 / 30 days, then EXPIRED). */
export function crewExpiryBand(validUntil: string | null | undefined, today: string = todayIso(), status?: string): { band: ExpiryBand; tone: Tone; label: string; days?: number } {
  if (status === 'SUSPENDED') return { band: 'SUSPENDED', tone: 'red', label: 'Suspended' };
  if (!validUntil) return { band: 'EXPIRED', tone: 'red', label: 'No valid medical' };
  const days = daysUntil(validUntil, today);
  if (days < 0) return { band: 'EXPIRED', tone: 'red', label: `Expired ${-days} d ago`, days };
  if (days <= 30) return { band: 'D30', tone: 'red', label: `≤ 30 days (${days} d)`, days };
  if (days <= 60) return { band: 'D60', tone: 'amber', label: `≤ 60 days (${days} d)`, days };
  if (days <= 90) return { band: 'D90', tone: 'amber', label: `≤ 90 days (${days} d)`, days };
  return { band: 'VALID', tone: 'green', label: `${days} days left`, days };
}

// ---------------------------------------------------------------- CSV / JSON import
/** RFC 4180-ish CSV: quoted fields, escaped quotes, CRLF / LF, comma or semicolon (detected from the header). */
export function parseCsv(text: string): string[][] {
  const src = text.replace(/^\uFEFF/, '');
  const firstLine = src.split(/\r?\n/, 1)[0] ?? '';
  const sep = (firstLine.match(/;/g)?.length ?? 0) > (firstLine.match(/,/g)?.length ?? 0) ? ';' : ',';
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    if (quoted) {
      if (ch === '"' && src[i + 1] === '"') {
        cell += '"';
        i++;
      } else if (ch === '"') quoted = false;
      else cell += ch;
    } else if (ch === '"' && cell === '') quoted = true;
    else if (ch === sep) {
      row.push(cell.trim());
      cell = '';
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && src[i + 1] === '\n') i++;
      row.push(cell.trim());
      if (row.some((c) => c !== '')) rows.push(row);
      row = [];
      cell = '';
    } else cell += ch;
  }
  row.push(cell.trim());
  if (row.some((c) => c !== '')) rows.push(row);
  return rows;
}

export interface ImportError {
  line: number;
  message: string;
}
export interface ParsedImport<T> {
  rows: T[];
  errors: ImportError[];
}

/** Records from pasted JSON (array of objects) or CSV with a header row. `line` = 1-based data row / array index. */
function records(text: string): { list: Record<string, unknown>[]; errors: ImportError[]; firstLine: number } {
  const t = text.trim();
  if (!t) return { list: [], errors: [{ line: 0, message: 'Nothing to import' }], firstLine: 1 };
  if (t.startsWith('[') || t.startsWith('{')) {
    try {
      const j = JSON.parse(t) as unknown;
      const arr = Array.isArray(j) ? j : [j];
      return { list: arr.map((x) => (x && typeof x === 'object' ? (x as Record<string, unknown>) : {})), errors: [], firstLine: 1 };
    } catch {
      return { list: [], errors: [{ line: 0, message: 'Invalid JSON' }], firstLine: 1 };
    }
  }
  const [header, ...data] = parseCsv(t);
  const keys = (header ?? []).map((h) => h.trim());
  return { list: data.map((r) => Object.fromEntries(keys.map((k, i) => [k, r[i] ?? '']))), errors: [], firstLine: 2 };
}

const isDate = (v: string) => /^\d{4}-\d{2}-\d{2}$/.test(v) && !Number.isNaN(Date.parse(`${v}T00:00:00Z`));
const str = (v: unknown) => (v === undefined || v === null ? '' : String(v).trim());

export const IH_COLUMNS = ['sourceRef', 'segCode', 'hazardCode', 'measurementType', 'value', 'unit', 'measuredOn'] as const;

/** IH measurement import (contract §4.6) from pasted CSV (header row with {@link IH_COLUMNS}) or JSON. */
export function parseIhImport(text: string): ParsedImport<IhMeasurement> {
  const { list, errors, firstLine } = records(text);
  const rows: IhMeasurement[] = [];
  list.forEach((r, i) => {
    const line = i + firstLine;
    const missing = IH_COLUMNS.filter((k) => str(r[k]) === '');
    if (missing.length) {
      errors.push({ line, message: `Missing ${missing.join(', ')}` });
      return;
    }
    const type = str(r.measurementType).toUpperCase();
    const value = Number(str(r.value).replace(',', '.'));
    const measuredOn = str(r.measuredOn);
    if (!(MEASUREMENT_TYPES as readonly string[]).includes(type)) errors.push({ line, message: `Unknown measurement type ${type}` });
    else if (!Number.isFinite(value) || value < 0) errors.push({ line, message: 'Value must be a non-negative number' });
    else if (!isDate(measuredOn)) errors.push({ line, message: 'measuredOn must be YYYY-MM-DD' });
    else rows.push({ sourceRef: str(r.sourceRef), segCode: str(r.segCode).toUpperCase(), hazardCode: str(r.hazardCode).toUpperCase(), measurementType: type, value, unit: str(r.unit), measuredOn });
  });
  const refs = new Set<string>();
  rows.forEach((r) => {
    if (refs.has(r.sourceRef)) errors.push({ line: 0, message: `Duplicate sourceRef ${r.sourceRef} in the pasted data` });
    refs.add(r.sourceRef);
  });
  return { rows, errors };
}

export interface CosmicImportRow {
  employeeId: string;
  year: number;
  doseMsv: number;
  source: string;
}

/** Cosmic-dose import (`employeeId, year, doseMsv, source`) from CSV or JSON. */
export function parseCosmicImport(text: string): ParsedImport<CosmicImportRow> {
  const { list, errors, firstLine } = records(text);
  const rows: CosmicImportRow[] = [];
  list.forEach((r, i) => {
    const line = i + firstLine;
    const year = Number(str(r.year));
    const dose = Number(str(r.doseMsv).replace(',', '.'));
    if (!str(r.employeeId)) errors.push({ line, message: 'Missing employeeId' });
    else if (!Number.isInteger(year) || year < 1990 || year > 2100) errors.push({ line, message: 'Year must be a 4-digit year' });
    else if (!Number.isFinite(dose) || dose < 0 || dose > 100) errors.push({ line, message: 'doseMsv must be 0–100' });
    else rows.push({ employeeId: str(r.employeeId).toUpperCase(), year, doseMsv: dose, source: str(r.source) || 'IMPORT' });
  });
  return { rows, errors };
}

// ---------------------------------------------------------------- programmes
export const splitCodes = (v: string): string[] => [...new Set(v.split(/[\s,;]+/).map((s) => s.trim().toUpperCase()).filter(Boolean))];
export const joinCodes = (v: readonly string[] | undefined): string => (v ?? []).join(', ');

export const ELIGIBILITY_KEYS = ['jobCodes', 'departmentCodes', 'subsidiaryCodes', 'hazardCodes', 'exposureGroups', 'licenceTypes'] as const;

/** `AUDIOMETRY`, `LAB:5671-3`, `BIOMONITORING:PB`, `IMMUNISATION:HEP_B`. */
export function isValidPanelTest(t: string): boolean {
  if ((PANEL_TESTS as readonly string[]).includes(t)) return true;
  const [prefix, code, ...rest] = t.split(':');
  return rest.length === 0 && (PANEL_TEST_PREFIXES as readonly string[]).includes(prefix ?? '') && /^[A-Za-z0-9_.-]{1,30}$/.test(code ?? '');
}

export interface ProgrammeForm {
  code: string;
  title: string;
  eligibility: Record<(typeof ELIGIBILITY_KEYS)[number], string> & { minAge: string };
  panel: PanelItem[];
  frequencyMonths: string;
  frequencyMonthsOver40: string;
  standardCode: string;
  responsibleRole: string;
  serviceCode: string;
  regulatoryBasis: string;
}

export function programmeToForm(p?: Partial<ProgrammeBody>): ProgrammeForm {
  const e: Partial<Eligibility> = p?.eligibility ?? {};
  return {
    code: p?.code ?? '',
    title: p?.title ?? '',
    eligibility: {
      jobCodes: joinCodes(e.jobCodes),
      departmentCodes: joinCodes(e.departmentCodes),
      subsidiaryCodes: joinCodes(e.subsidiaryCodes),
      hazardCodes: joinCodes(e.hazardCodes),
      exposureGroups: joinCodes(e.exposureGroups),
      licenceTypes: joinCodes(e.licenceTypes),
      minAge: e.minAge != null ? String(e.minAge) : '',
    },
    panel: p?.panel ? p.panel.map((x) => ({ ...x })) : [],
    frequencyMonths: p?.frequencyMonths != null ? String(p.frequencyMonths) : '12',
    frequencyMonthsOver40: p?.frequencyMonthsOver40 != null ? String(p.frequencyMonthsOver40) : '',
    standardCode: p?.standardCode ?? '',
    responsibleRole: p?.responsibleRole ?? 'OH_CLINICIAN',
    serviceCode: p?.serviceCode ?? 'SURVEILLANCE_PANEL',
    regulatoryBasis: p?.regulatoryBasis ?? '',
  };
}

export const progFieldId = (k: string) => `prog-${k}`;

export function validateProgramme(f: ProgrammeForm): FieldError[] {
  const errors: FieldError[] = [];
  if (!/^[A-Z0-9_]{2,40}$/.test(f.code.trim())) errors.push({ field: progFieldId('code'), message: 'Code: 2–40 upper-case letters, digits or underscores' });
  if (!f.title.trim()) errors.push({ field: progFieldId('title'), message: 'Enter a title' });
  const anyCriterion = ELIGIBILITY_KEYS.some((k) => splitCodes(f.eligibility[k]).length > 0);
  if (!anyCriterion) errors.push({ field: progFieldId('jobCodes'), message: 'Eligibility: enter at least one job, department, subsidiary, hazard, exposure group or licence' });
  if (f.eligibility.minAge && !(/^\d+$/.test(f.eligibility.minAge) && Number(f.eligibility.minAge) >= 16 && Number(f.eligibility.minAge) <= 80))
    errors.push({ field: progFieldId('minAge'), message: 'Minimum age: 16–80' });
  if (f.panel.length === 0) errors.push({ field: progFieldId('panel-new'), message: 'Panel: add at least one test' });
  f.panel.forEach((p, i) => {
    if (!isValidPanelTest(p.test)) errors.push({ field: progFieldId(`panel-${i}`), message: `Panel test ${i + 1}: “${p.test}” is not a valid test (e.g. AUDIOMETRY, LAB:5671-3)` });
  });
  const months = (v: string) => /^\d+$/.test(v) && Number(v) >= 1 && Number(v) <= 120;
  if (!months(f.frequencyMonths)) errors.push({ field: progFieldId('frequencyMonths'), message: 'Frequency: 1–120 months' });
  if (f.frequencyMonthsOver40 && !months(f.frequencyMonthsOver40)) errors.push({ field: progFieldId('frequencyMonthsOver40'), message: 'Frequency over 40: 1–120 months' });
  if (!f.responsibleRole.trim()) errors.push({ field: progFieldId('responsibleRole'), message: 'Choose the responsible role' });
  if (!f.serviceCode.trim()) errors.push({ field: progFieldId('serviceCode'), message: 'Enter the appointment service code' });
  return errors;
}

export function formToProgramme(f: ProgrammeForm): ProgrammeBody {
  return {
    code: f.code.trim(),
    title: f.title.trim(),
    eligibility: {
      jobCodes: splitCodes(f.eligibility.jobCodes),
      departmentCodes: splitCodes(f.eligibility.departmentCodes),
      subsidiaryCodes: splitCodes(f.eligibility.subsidiaryCodes),
      hazardCodes: splitCodes(f.eligibility.hazardCodes),
      exposureGroups: splitCodes(f.eligibility.exposureGroups),
      licenceTypes: splitCodes(f.eligibility.licenceTypes),
      ...(f.eligibility.minAge ? { minAge: Number(f.eligibility.minAge) } : {}),
    },
    panel: f.panel.map((p) => ({ test: p.test.trim(), ...(p.standard?.trim() ? { standard: p.standard.trim() } : {}) })),
    frequencyMonths: Number(f.frequencyMonths),
    ...(f.frequencyMonthsOver40 ? { frequencyMonthsOver40: Number(f.frequencyMonthsOver40) } : {}),
    ...(f.standardCode.trim() ? { standardCode: f.standardCode.trim() } : {}),
    responsibleRole: f.responsibleRole.trim(),
    serviceCode: f.serviceCode.trim().toUpperCase(),
    regulatoryBasis: f.regulatoryBasis.trim(),
  };
}

// ---------------------------------------------------------------- compliance
/** ≥ 95 % green, ≥ 80 % amber, else red. */
export function complianceTone(pct: number | null | undefined): Tone {
  if (typeof pct !== 'number' || !Number.isFinite(pct)) return 'muted';
  if (pct >= 95) return 'green';
  if (pct >= 80) return 'amber';
  return 'red';
}

/** Compliance % for display; suppressed rows never show a number. */
export function complianceText(row: { compliancePct?: number | null; suppressed?: boolean }): string {
  if (row.suppressed) return 'Suppressed';
  return typeof row.compliancePct === 'number' && Number.isFinite(row.compliancePct) ? `${row.compliancePct.toFixed(1)} %` : '–';
}

/** Change between the first and last trend point, e.g. "+2.5 pts". */
export function trendDelta(points: readonly { compliancePct: number }[]): string {
  if (points.length < 2) return '–';
  const d = (points[points.length - 1]?.compliancePct ?? 0) - (points[0]?.compliancePct ?? 0);
  return `${d >= 0 ? '+' : '−'}${Math.abs(d).toFixed(1)} pts`;
}

// ---------------------------------------------------------------- selection
export function toggleId(sel: ReadonlySet<string>, id: string): Set<string> {
  const n = new Set(sel);
  if (n.has(id)) n.delete(id);
  else n.add(id);
  return n;
}

/** Header checkbox: all visible selected → clear them, otherwise add all visible. */
export function toggleAll(sel: ReadonlySet<string>, visible: readonly string[]): Set<string> {
  const all = visible.length > 0 && visible.every((id) => sel.has(id));
  const n = new Set(sel);
  for (const id of visible) {
    if (all) n.delete(id);
    else n.add(id);
  }
  return n;
}

// ---------------------------------------------------------------- fast entry
export type IdMode = 'AUTO' | 'EMPLOYEE' | 'BADGE';

/**
 * Employee ID vs badge for immunisation entry. AUTO treats `E1004`-style ids (one letter + 4–8 digits) as employee ids and
 * anything else (e.g. a scanned badge number) as a badge id.
 */
export function employeeOrBadge(raw: string, mode: IdMode = 'AUTO'): { employeeId: string } | { badgeId: string } | undefined {
  const v = raw.trim();
  if (!v || v.length > 40 || !/^[A-Za-z0-9-]+$/.test(v)) return undefined;
  if (mode === 'EMPLOYEE' || (mode === 'AUTO' && /^[A-Za-z]\d{4,8}$/.test(v))) return { employeeId: v.toUpperCase() };
  return { badgeId: v };
}
