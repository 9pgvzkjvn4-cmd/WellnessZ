import { describe, expect, it } from 'vitest';
import type { Episode } from '../api/absence';
import { normaliseCompliance, normaliseExposureHistory } from '../api/surveillance';
import { ApiError, toProblem } from '../auth/AuthClient';
import {
  bradfordBand,
  changeRuleType,
  clinicalContentField,
  daysUntil,
  describeParams,
  emptyRuleForm,
  filterEpisodes,
  isClinicalContentError,
  isMilestoneOverdue,
  kpiStatus,
  milestoneTone,
  paramsToForm,
  previousMonth,
  RULE_PARAM_SPEC,
  slaTone,
  toRuleBody,
  validateRuleForm,
} from './absence';
import {
  complianceText,
  complianceTone,
  crewExpiryBand,
  employeeOrBadge,
  formToProgramme,
  isValidPanelTest,
  lotInput,
  parseCosmicImport,
  parseCsv,
  parseGs1,
  parseIhImport,
  programmeToForm,
  splitCodes,
  toggleAll,
  toggleId,
  trendDelta,
  validateProgramme,
} from './surveillance';

describe('E06 rule builder', () => {
  it('has a parameter form for every rule type matching the contract', () => {
    expect(RULE_PARAM_SPEC.DURATION.map((s) => s.key)).toEqual(['minDays']);
    expect(RULE_PARAM_SPEC.FREQUENCY.map((s) => s.key)).toEqual(['episodes', 'windowDays']);
    expect(RULE_PARAM_SPEC.PATTERN.map((s) => s.key)).toEqual(['pattern', 'minOccurrences', 'windowDays']);
    expect(RULE_PARAM_SPEC.POST_INCIDENT.map((s) => s.key)).toEqual(['withinDays']);
    expect(RULE_PARAM_SPEC.SAFETY_CRITICAL.map((s) => s.key)).toEqual(['minDays']);
    expect(RULE_PARAM_SPEC.CREW_SICKNESS.map((s) => s.key)).toEqual(['minDays', 'episodes', 'windowDays']);
  });

  it('builds a typed body with numeric params of the chosen type only', () => {
    const f = { ...changeRuleType({ ...emptyRuleForm(), code: 'FREQ_4_IN_60', name: 'Four in sixty', subsidiaryCode: 'cat' }, 'FREQUENCY') };
    f.params = { ...f.params, episodes: '4', windowDays: '60', minDays: '9' };
    expect(toRuleBody(f)).toEqual({ code: 'FREQ_4_IN_60', name: 'Four in sixty', ruleType: 'FREQUENCY', action: 'EARLY_INTERVENTION', active: true, params: { episodes: 4, windowDays: 60 }, subsidiaryCode: 'CAT' });
  });

  it('switching type keeps shared values and fills defaults', () => {
    const dur = { ...emptyRuleForm('DURATION'), params: { minDays: '12' } };
    const crew = changeRuleType(dur, 'CREW_SICKNESS');
    expect(crew.params).toEqual({ minDays: '12', episodes: '3', windowDays: '90' });
    expect(changeRuleType(dur, 'PATTERN').params).toEqual({ pattern: 'MONDAY_FRIDAY', minOccurrences: '3', windowDays: '180' });
    expect(emptyRuleForm('CREW_SICKNESS').action).toBe('AME_REVIEW');
  });

  it('validates code, name and parameter ranges', () => {
    const f = { ...emptyRuleForm('FREQUENCY'), code: 'bad code', name: '', params: { episodes: '0', windowDays: '2.5' } };
    const fields = validateRuleForm(f).map((e) => e.field);
    expect(fields).toEqual(['rule-code', 'rule-name', 'rule-episodes', 'rule-windowDays']);
    expect(() => toRuleBody(f)).toThrow();
    const pattern = { ...emptyRuleForm('PATTERN'), code: 'P', name: 'x', params: { pattern: '', minOccurrences: '3', windowDays: '180' } };
    expect(validateRuleForm(pattern).map((e) => e.field)).toContain('rule-pattern');
  });

  it('describes parameters and reads existing params into the form', () => {
    expect(describeParams('FREQUENCY', { episodes: 3, windowDays: 90 })).toBe('3 episodes in 90 days');
    expect(describeParams('DURATION', { minDays: 10 })).toBe('> 10 days');
    expect(paramsToForm('DURATION', { minDays: 28 })).toEqual({ minDays: '28' });
  });
});

describe('E06 milestones, SLA, Bradford, KPIs', () => {
  const today = '2026-10-29';
  it('flags overdue milestones (server OVERDUE or pending past due)', () => {
    expect(daysUntil('2026-11-05', today)).toBe(7);
    expect(isMilestoneOverdue({ status: 'OVERDUE', dueDate: '2026-12-01' }, today)).toBe(true);
    expect(isMilestoneOverdue({ status: 'PENDING', dueDate: '2026-10-28' }, today)).toBe(true);
    expect(isMilestoneOverdue({ status: 'PENDING', dueDate: '2026-10-29' }, today)).toBe(false);
    expect(isMilestoneOverdue({ status: 'DONE', dueDate: '2026-10-01' }, today)).toBe(false);
    expect(milestoneTone({ status: 'PENDING', dueDate: '2026-11-03' }, today)).toBe('amber');
    expect(milestoneTone({ status: 'PENDING', dueDate: '2026-12-03' }, today)).toBe('muted');
    expect(milestoneTone({ status: 'SKIPPED', dueDate: '2026-10-01' }, today)).toBe('muted');
    expect(milestoneTone({ status: 'DONE', dueDate: '2026-10-01' }, today)).toBe('green');
  });

  it('computes SLA tone', () => {
    const now = Date.parse('2026-10-29T12:00:00Z');
    expect(slaTone('2026-10-29T10:00:00Z', now)).toEqual({ tone: 'red', label: 'Overdue by 2 h' });
    expect(slaTone('2026-10-30T06:00:00Z', now)).toEqual({ tone: 'amber', label: 'Due in 18 h' });
    expect(slaTone('2026-11-02T12:00:00Z', now)).toEqual({ tone: 'green', label: 'Due in 4 d' });
    expect(slaTone(undefined, now).tone).toBe('muted');
  });

  it('bands the Bradford factor', () => {
    expect(bradfordBand(0).band).toBe('LOW');
    expect(bradfordBand(50).band).toBe('MONITOR');
    expect(bradfordBand(125).band).toBe('REVIEW');
    expect(bradfordBand(400).tone).toBe('red');
  });

  it('checks KPIs against their targets', () => {
    expect(kpiStatus(96, 95, 'atLeast')).toBe('MET');
    expect(kpiStatus(94.9, 95, 'atLeast')).toBe('MISSED');
    expect(kpiStatus(5, 5, 'atMost')).toBe('MET');
    expect(kpiStatus(6.5, 5, 'atMost')).toBe('MISSED');
    expect(kpiStatus(undefined, 5, 'atMost')).toBe('UNKNOWN');
    expect(previousMonth('2026-01-15')).toBe('2025-12');
    expect(previousMonth('2026-10-29')).toBe('2026-09');
  });

  it('filters and sorts episodes for the chart timeline', () => {
    const ep = (id: string, startDate: string, endDate: string | null, absenceType: Episode['absenceType'], open = false): Episode => ({ id, sourceSystem: 'HRIS', startDate, endDate, days: 1, absenceType, certified: false, open });
    const list = [ep('a', '2026-01-05', '2026-01-09', 'SICKNESS'), ep('b', '2026-09-01', null, 'INJURY_WORK', true), ep('c', '2026-05-01', '2026-05-02', 'SICKNESS')];
    expect(filterEpisodes(list, { types: [], openOnly: false }).map((e) => e.id)).toEqual(['b', 'c', 'a']);
    expect(filterEpisodes(list, { types: ['SICKNESS'], openOnly: false }).map((e) => e.id)).toEqual(['c', 'a']);
    expect(filterEpisodes(list, { types: [], openOnly: true }).map((e) => e.id)).toEqual(['b']);
    expect(filterEpisodes(list, { types: [], openOnly: false, from: '2026-05-02', to: '2026-12-31' }).map((e) => e.id)).toEqual(['b', 'c']);
  });
});

describe('422 clinical content', () => {
  it('reads the field from the problem property or the detail text', () => {
    const withField = new ApiError(422, toProblem({ code: 'clinical_content_not_allowed', field: 'answers[FIT_FOR_ROLE]', detail: 'x' }));
    expect(isClinicalContentError(withField)).toBe(true);
    expect(clinicalContentField(withField)).toBe('answers.FIT_FOR_ROLE');
    const fromDetail = new ApiError(422, { code: 'clinical_content', detail: "Field 'adjustments' appears to contain medical information." });
    expect(clinicalContentField(fromDetail)).toBe('adjustments');
    expect(clinicalContentField(new ApiError(422, { code: 'other' }))).toBeUndefined();
    expect(isClinicalContentError(new ApiError(409, { code: 'clinical_content' }))).toBe(false);
  });
});

describe('E07 GS1 lot barcodes', () => {
  it('parses human-readable AIs', () => {
    expect(parseGs1('(17)270331(10)AB12C')).toEqual({ expiry: '2027-03-31', lot: 'AB12C' });
    expect(parseGs1('(01)05012345678900(17)261200(10)LOT-9(21)SER1')).toEqual({ gtin: '05012345678900', expiry: '2026-12-31', lot: 'LOT-9', serial: 'SER1' });
  });

  it('parses raw scanner strings with FNC1 / GS separators and symbology prefix', () => {
    expect(parseGs1(']d201050123456789001727033110AB12C')).toEqual({ gtin: '05012345678900', expiry: '2027-03-31', lot: 'AB12C' });
    expect(parseGs1('10LOT77\u001d17270101')).toEqual({ lot: 'LOT77', expiry: '2027-01-01' });
    expect(parseGs1('10LOT77<GS>17270101')).toEqual({ lot: 'LOT77', expiry: '2027-01-01' });
  });

  it('rejects invalid dates, missing lots and unknown raw AIs', () => {
    expect(parseGs1('(17)271332(10)A')).toBeUndefined();
    expect(parseGs1('(17)270331')).toBeUndefined();
    expect(parseGs1('99XYZ')).toBeUndefined();
    expect(parseGs1('')).toBeUndefined();
  });

  it('classifies lot input and blocks expired lots', () => {
    expect(lotInput('AB12C', '2026-10-29')).toEqual({ lotNumber: 'AB12C', expired: false });
    expect(lotInput('(17)270331(10)AB12C', '2026-10-29')).toEqual({ lotBarcode: '(17)270331(10)AB12C', lotNumber: 'AB12C', expiry: '2027-03-31', expired: false });
    const expired = lotInput('(17)260930(10)OLD1', '2026-10-29');
    expect(expired.expired).toBe(true);
    expect(expired.error).toMatch(/expired/);
    expect(lotInput('', '2026-10-29').error).toBeTruthy();
    expect(lotInput('bad lot!', '2026-10-29').error).toBeTruthy();
  });

  it('tells employee ids from badges', () => {
    expect(employeeOrBadge('e1004')).toEqual({ employeeId: 'E1004' });
    expect(employeeOrBadge('0004417733')).toEqual({ badgeId: '0004417733' });
    expect(employeeOrBadge('0004417733', 'EMPLOYEE')).toEqual({ employeeId: '0004417733' });
    expect(employeeOrBadge('E1004', 'BADGE')).toEqual({ badgeId: 'E1004' });
    expect(employeeOrBadge('  ')).toBeUndefined();
    expect(employeeOrBadge('a b')).toBeUndefined();
  });
});

describe('E07 crew expiry bands', () => {
  const t = '2026-10-29';
  it.each([
    ['2026-10-28', 'EXPIRED'],
    ['2026-11-15', 'D30'],
    ['2026-12-20', 'D60'],
    ['2027-01-20', 'D90'],
    ['2027-06-01', 'VALID'],
  ])('%s → %s', (until, band) => {
    expect(crewExpiryBand(until, t).band).toBe(band);
  });
  it('suspended and missing certificates are red', () => {
    expect(crewExpiryBand('2027-06-01', t, 'SUSPENDED').band).toBe('SUSPENDED');
    expect(crewExpiryBand(undefined, t).tone).toBe('red');
  });
});

describe('E07 imports', () => {
  it('parses CSV with quotes, CRLF and semicolons', () => {
    expect(parseCsv('a,b\r\n"x, y","say ""hi"""\r\n\r\n')).toEqual([['a', 'b'], ['x, y', 'say "hi"']]);
    expect(parseCsv('a;b\n1;2')).toEqual([['a', 'b'], ['1', '2']]);
  });

  it('parses IH measurements from CSV and reports row errors', () => {
    const csv = 'sourceRef,segCode,hazardCode,measurementType,value,unit,measuredOn\nIH-1,seg-ramp,noise,noise_dosimetry,84.2,dB(A),2026-10-01\nIH-2,SEG,NOISE,LASER,1,x,2026-10-01\nIH-3,SEG,NOISE,VIBRATION,abc,m/s2,2026-10-01\nIH-4,SEG,NOISE,VIBRATION,1,m/s2,01/10/2026\nIH-5,SEG,,VIBRATION,1,m/s2,2026-10-01';
    const r = parseIhImport(csv);
    expect(r.rows).toEqual([{ sourceRef: 'IH-1', segCode: 'SEG-RAMP', hazardCode: 'NOISE', measurementType: 'NOISE_DOSIMETRY', value: 84.2, unit: 'dB(A)', measuredOn: '2026-10-01' }]);
    expect(r.errors.map((e) => e.line)).toEqual([3, 4, 5, 6]);
    expect(r.errors[3]?.message).toMatch(/hazardCode/);
  });

  it('parses IH measurements from JSON and flags duplicates / bad JSON', () => {
    const row = { sourceRef: 'A', segCode: 'S', hazardCode: 'DUST_RESP', measurementType: 'AIR_SAMPLING', value: 0.5, unit: 'mg/m3', measuredOn: '2026-09-30' };
    expect(parseIhImport(JSON.stringify([row])).rows).toHaveLength(1);
    expect(parseIhImport(JSON.stringify([row, row])).errors[0]?.message).toMatch(/Duplicate/);
    expect(parseIhImport('[{').errors[0]?.message).toBe('Invalid JSON');
    expect(parseIhImport('  ').errors[0]?.message).toBe('Nothing to import');
  });

  it('parses cosmic dose imports', () => {
    const r = parseCosmicImport('employeeId,year,doseMsv,source\ne2001,2026,3.4,SIEVERT\nE2002,26,1,X\nE2003,2026,"4,5",');
    expect(r.rows).toEqual([{ employeeId: 'E2001', year: 2026, doseMsv: 3.4, source: 'SIEVERT' }, { employeeId: 'E2003', year: 2026, doseMsv: 4.5, source: 'IMPORT' }]);
    expect(r.errors).toEqual([{ line: 3, message: 'Year must be a 4-digit year' }]);
  });
});

describe('E07 programme builder', () => {
  it('validates panel tests', () => {
    expect(isValidPanelTest('AUDIOMETRY')).toBe(true);
    expect(isValidPanelTest('LAB:5671-3')).toBe(true);
    expect(isValidPanelTest('IMMUNISATION:HEP_B')).toBe(true);
    expect(isValidPanelTest('LAB:')).toBe(false);
    expect(isValidPanelTest('XRAY')).toBe(false);
    expect(isValidPanelTest('LAB:1:2')).toBe(false);
  });

  it('round-trips a programme through the form and validates it', () => {
    const f = programmeToForm({ code: 'HEARING_CONSERVATION', title: 'Hearing', eligibility: { jobCodes: [], departmentCodes: [], subsidiaryCodes: [], hazardCodes: ['NOISE'], exposureGroups: [], licenceTypes: [] }, panel: [{ test: 'AUDIOMETRY' }], frequencyMonths: 12, responsibleRole: 'OH_CLINICIAN', serviceCode: 'AUDIOMETRY', regulatoryBasis: 'Noise at Work' });
    expect(validateProgramme(f)).toEqual([]);
    const body = formToProgramme({ ...f, eligibility: { ...f.eligibility, jobCodes: 'ramp_agent, , FORKLIFT_OP;ramp_agent', minAge: '40' } });
    expect(body.eligibility).toEqual({ jobCodes: ['RAMP_AGENT', 'FORKLIFT_OP'], departmentCodes: [], subsidiaryCodes: [], hazardCodes: ['NOISE'], exposureGroups: [], licenceTypes: [], minAge: 40 });
    expect(body.frequencyMonths).toBe(12);
    expect(body).not.toHaveProperty('frequencyMonthsOver40');
  });

  it('requires eligibility, a panel and sane frequencies', () => {
    const f = programmeToForm();
    const fields = validateProgramme({ ...f, frequencyMonths: '0', frequencyMonthsOver40: 'x' }).map((e) => e.field);
    expect(fields).toEqual(['prog-code', 'prog-title', 'prog-jobCodes', 'prog-panel-new', 'prog-frequencyMonths', 'prog-frequencyMonthsOver40']);
    expect(splitCodes(' a,b ;c  a')).toEqual(['A', 'B', 'C']);
  });
});

describe('E07 compliance & selection', () => {
  it('formats compliance and hides suppressed rows', () => {
    expect(complianceText({ compliancePct: 92.345 })).toBe('92.3 %');
    expect(complianceText({ compliancePct: 50, suppressed: true })).toBe('Suppressed');
    expect(complianceText({})).toBe('–');
    expect(complianceTone(96)).toBe('green');
    expect(complianceTone(85)).toBe('amber');
    expect(complianceTone(40)).toBe('red');
    expect(complianceTone(undefined)).toBe('muted');
    expect(trendDelta([{ compliancePct: 80 }, { compliancePct: 82.5 }])).toBe('+2.5 pts');
    expect(trendDelta([{ compliancePct: 90 }, { compliancePct: 88 }])).toBe('−2.0 pts');
  });

  it('accepts compliance as an array or {rows, refreshedAt}', () => {
    expect(normaliseCompliance([{ key: 'A', label: 'A', suppressed: false }]).rows).toHaveLength(1);
    expect(normaliseCompliance({ rows: [], refreshedAt: '2026-10-29T02:00:00Z' }).refreshedAt).toBe('2026-10-29T02:00:00Z');
    expect(normaliseCompliance(null).rows).toEqual([]);
    expect(normaliseExposureHistory([{ segCode: 'S', hazardCode: 'NOISE' }]).exposures).toHaveLength(1);
    expect(normaliseExposureHistory({ exposures: [], cosmicDose: [{ employeeId: 'E', year: 2026, doseMsv: 1 }] }).cosmicDose).toHaveLength(1);
  });

  it('toggles selection', () => {
    const s = toggleId(new Set(['a']), 'b');
    expect([...s]).toEqual(['a', 'b']);
    expect([...toggleId(s, 'a')]).toEqual(['b']);
    expect([...toggleAll(new Set(['a']), ['a', 'b'])].sort()).toEqual(['a', 'b']);
    expect([...toggleAll(new Set(['a', 'b', 'z']), ['a', 'b'])]).toEqual(['z']);
  });
});
