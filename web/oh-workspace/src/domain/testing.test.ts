import { describe, expect, it } from 'vitest';
import type { TestingPolicy } from '../api/testing';
import { toLocalInput } from '../components/format';
import {
  currentPeriod,
  emptyCollectionForm,
  emptyMroForm,
  emptyRtdForm,
  expectedSelectionCount,
  nonNegative,
  normaliseSpecimenId,
  rateCell,
  requirementCountdown,
  rowIsSuppressed,
  shortHash,
  statCell,
  toCollectionBody,
  toMroBody,
  validateCollection,
  validateMro,
  validatePolicy,
  validateRefusal,
  validateRtd,
  validateSelection,
  windowKind,
} from './testing';

const NOW = Date.parse('2026-10-30T12:00:00Z');
const at = (min: number) => new Date(NOW + min * 60_000).toISOString();

describe('collection windows', () => {
  it('alcohol vs drug window by panel', () => {
    expect(windowKind(['ALCOHOL_BREATH'])).toBe('ALCOHOL');
    expect(windowKind(['DRUGS_5', 'ALCOHOL_BREATH'])).toBe('ALCOHOL');
    expect(windowKind(['DRUGS_10'])).toBe('DRUGS');
  });

  it('alcohol: amber inside 2 h, red inside 1 h; drugs: amber inside 8 h', () => {
    expect(requirementCountdown({ dueAt: at(150), panels: ['ALCOHOL_BREATH'] }, NOW)).toMatchObject({ kind: 'ALCOHOL', tone: 'green', label: '2 h 30 min left' });
    expect(requirementCountdown({ dueAt: at(90), panels: ['ALCOHOL_BREATH'] }, NOW).tone).toBe('amber');
    expect(requirementCountdown({ dueAt: at(45), panels: ['ALCOHOL_BREATH'] }, NOW).tone).toBe('red');
    expect(requirementCountdown({ dueAt: at(7 * 60), panels: ['DRUGS_5'] }, NOW)).toMatchObject({ kind: 'DRUGS', tone: 'amber' });
    expect(requirementCountdown({ dueAt: at(20 * 60), panels: ['DRUGS_5'] }, NOW).tone).toBe('green');
    expect(requirementCountdown({ dueAt: at(-5), panels: ['DRUGS_5'] }, NOW)).toMatchObject({ overdue: true, tone: 'red' });
  });

  it('collected / missed requirements stop counting', () => {
    expect(requirementCountdown({ dueAt: at(-5), panels: ['DRUGS_5'], status: 'COLLECTED' }, NOW)).toMatchObject({ tone: 'muted', label: 'Window closed' });
    expect(requirementCountdown({ dueAt: at(-5), panels: ['DRUGS_5'], status: 'WINDOW_MISSED' }, NOW)).toMatchObject({ tone: 'red', label: 'Window missed' });
  });
});

describe('random selection', () => {
  it('n = ceil(poolSize × rate / 12)', () => {
    expect(expectedSelectionCount(120, 25)).toBe(3); // 120 × 0.25 / 12 = 2.5 → 3
    expect(expectedSelectionCount(48, 25)).toBe(1); // exactly 1
    expect(expectedSelectionCount(10, 10)).toBe(1); // 0.083 → 1
    expect(expectedSelectionCount(0, 25)).toBe(0);
    expect(expectedSelectionCount(100, 0)).toBe(0);
  });

  it('validates role group, YYYY-MM not in the past, test type and duplicates', () => {
    const today = '2026-10-30';
    expect(currentPeriod(today)).toBe('2026-10');
    expect(validateSelection({ roleGroup: 'FLIGHT_CREW', period: '2026-11', testType: 'DRUGS' }, [], today)).toEqual([]);
    expect(validateSelection({ roleGroup: '', period: '2026-13', testType: '' }, [], today).map((e) => e.field)).toEqual(['sel-role-group', 'sel-period', 'sel-test-type']);
    expect(validateSelection({ roleGroup: 'FLIGHT_CREW', period: '2026-09', testType: 'DRUGS' }, [], today)[0]?.message).toMatch(/past month/);
    const existing = [{ roleGroup: 'FLIGHT_CREW' as const, period: '2026-10', testType: 'DRUGS' as const }];
    expect(validateSelection({ roleGroup: 'FLIGHT_CREW', period: '2026-10', testType: 'DRUGS' }, existing, today)[0]?.message).toMatch(/already exists/);
    expect(validateSelection({ roleGroup: 'FLIGHT_CREW', period: '2026-10', testType: 'ALCOHOL' }, existing, today)).toEqual([]);
  });

  it('shortens hashes for display', () => {
    expect(shortHash('a'.repeat(8) + 'b'.repeat(48) + 'c'.repeat(8))).toBe('aaaaaaaa…cccccccc');
    expect(shortHash('abc')).toBe('abc');
    expect(shortHash(undefined)).toBe('–');
  });

  it('policy validation', () => {
    const p: TestingPolicy = { roleGroup: 'FLIGHT_CREW', jobCodes: ['FO'], panels: ['DRUGS_10'], randomDrugRatePct: 25, randomAlcoholRatePct: 10, alcoholWindowHours: 8, drugWindowHours: 32, leadTimeMinutes: 60, jurisdiction: 'UK', consentRequired: true, approvalStatus: 'PENDING', version: 1 };
    expect(validatePolicy(p)).toEqual([]);
    expect(validatePolicy({ ...p, randomDrugRatePct: 120, panels: [], jurisdiction: ' ' }).map((e) => e.field)).toEqual(['pol-drug-rate', 'pol-panels', 'pol-jurisdiction']);
  });
});

describe('e-CCF (collection) validation', () => {
  const now = Date.now();
  const urine = { ...emptyCollectionForm('LHR-T5', toLocalInput(new Date(now - 60_000))), specimenType: 'URINE' as const, specimenId: 'SP-123456', temperatureInRange: 'yes' as const, sealIntact: true, donorIdentityChecked: true, splitSpecimen: true };

  it('normalises keyboard-wedge scans', () => {
    expect(normaliseSpecimenId(' sp-123456\r\n')).toBe('SP-123456');
    expect(normaliseSpecimenId('ab 12\tcd')).toBe('AB12CD');
  });

  it('accepts a complete urine collection and builds the body', () => {
    expect(validateCollection(urine, now)).toEqual([]);
    const body = toCollectionBody({ ...urine, specimenId: 'sp-123456 ', remarks: ' ' });
    expect(body).toMatchObject({ specimenId: 'SP-123456', specimenType: 'URINE', siteCode: 'LHR-T5', temperatureInRange: true, sealIntact: true, donorIdentityChecked: true, splitSpecimen: true });
    expect(body).not.toHaveProperty('breathScreenResult');
    expect(body).not.toHaveProperty('remarks');
  });

  it('urine needs temperature and an intact seal; identity always', () => {
    const errs = validateCollection({ ...urine, temperatureInRange: '', sealIntact: false, donorIdentityChecked: false }, now).map((e) => e.field);
    expect(errs).toEqual(['ccf-identity', 'ccf-temperature', 'ccf-seal']);
  });

  it('rejects malformed specimen IDs and future collection times', () => {
    expect(validateCollection({ ...urine, specimenId: 'AB1' }, now).map((e) => e.field)).toEqual(['ccf-specimen']);
    expect(validateCollection({ ...urine, specimenId: '' }, now)[0]?.message).toMatch(/Scan or type/);
    expect(validateCollection({ ...urine, collectedAt: toLocalInput(new Date(now + 60 * 60_000)) }, now).map((e) => e.field)).toEqual(['ccf-collected']);
  });

  it('breath: numeric result 0–1 g/210 L, no seal / split / temperature', () => {
    const breath = { ...urine, specimenType: 'BREATH' as const, sealIntact: false, temperatureInRange: '' as const, breathScreenResult: '' };
    expect(validateCollection(breath, now).map((e) => e.field)).toEqual(['ccf-breath']);
    expect(validateCollection({ ...breath, breathScreenResult: '0.042' }, now)).toEqual([]);
    const body = toCollectionBody({ ...breath, breathScreenResult: '0.042' });
    expect(body).toMatchObject({ specimenType: 'BREATH', breathScreenResult: 0.042, splitSpecimen: false, sealIntact: true });
    expect(body).not.toHaveProperty('temperatureInRange');
  });

  it('refusal needs reason and observations', () => {
    expect(validateRefusal({ reason: '', observations: '' }).map((e) => e.field)).toEqual(['ref-reason', 'ref-observations']);
    expect(validateRefusal({ reason: 'NO_SHOW', observations: 'Did not attend by 14:00' })).toEqual([]);
  });
});

describe('MRO and RTD', () => {
  it('legitimate prescription needs verification and an interview note', () => {
    expect(validateMro(emptyMroForm()).map((e) => e.field)).toEqual(['mro-verdict']);
    expect(validateMro({ ...emptyMroForm(), verdict: 'NEGATIVE_LEGITIMATE_PRESCRIPTION' }).map((e) => e.field)).toEqual(['mro-prescription', 'mro-note']);
    expect(validateMro({ ...emptyMroForm(), verdict: 'NEGATIVE' })).toEqual([]);
    expect(toMroBody({ verdict: 'NEGATIVE_LEGITIMATE_PRESCRIPTION', prescriptionVerified: true, interviewNote: ' Codeine Rx ', splitSpecimenRequested: false })).toEqual({ verdict: 'NEGATIVE_LEGITIMATE_PRESCRIPTION', prescriptionVerified: true, interviewNote: 'Codeine Rx' });
    expect(toMroBody({ ...emptyMroForm(), verdict: 'NEGATIVE' })).toEqual({ verdict: 'NEGATIVE' });
  });
  it('lists non-negative analytes', () => {
    expect(nonNegative([{ analyte: 'THC', outcome: 'NON_NEGATIVE' }, { analyte: 'COC', outcome: 'NEGATIVE' }])).toEqual(['THC']);
  });
  it('RTD plan validation', () => {
    expect(validateRtd({ ...emptyRtdForm(), employeeId: 'E1', caseId: 'c1', evaluator: 'Dr Sap' })).toEqual([]);
    expect(validateRtd({ ...emptyRtdForm(), followUpTests: '0' }).map((e) => e.field)).toEqual(['rtd-employee', 'rtd-case', 'rtd-evaluator', 'rtd-tests']);
  });
});

describe('statistics suppression', () => {
  it('suppressed rows never reveal counts or rates', () => {
    expect(rowIsSuppressed({ suppressed: true, tests: 40 }, 5)).toBe(true);
    expect(rowIsSuppressed({ suppressed: false, tests: 3 }, 5)).toBe(true); // defence in depth
    expect(rowIsSuppressed({ suppressed: false, tests: 0 }, 5)).toBe(false);
    expect(rowIsSuppressed({ suppressed: false, tests: 12 }, 5)).toBe(false);
    expect(statCell(2, true, 5)).toBe('<5');
    expect(statCell(12, false, 5)).toBe('12');
    expect(rateCell(8.333, false)).toBe('8.3 %');
    expect(rateCell(8.333, true)).toBe('suppressed');
    expect(rateCell(null, false)).toBe('–');
  });
});
