import { describe, expect, it } from 'vitest';
import type { PlanTemplate, Provider, ReferralService } from '../api/treatment';
import { ApiError } from '../auth/AuthClient';
import { budgetTone, deadlineCountdown, isDisclosable, isMonth, nextClaimStatuses, parseRedactionPhrases, statusReasonRequired, validateClaimForm, emptyClaimForm } from './claims';
import { asMoney, describeServerVariance, formatMoney, fromMinor, invoiceVariance, parseAmount, sumMoney, toMinor } from './money';
import {
  cleanDataSet,
  emptyPlanForm,
  emptyReferralForm,
  filterProviders,
  isAllergyAlert,
  joinWindow,
  normaliseOutcomes,
  outcomeImprovement,
  describeImprovement,
  planFromTemplate,
  referralTimeline,
  reportDueLabel,
  stockFlags,
  toPlanBody,
  usableBatches,
  validateDataSet,
  validateOutcome,
  validatePlanForm,
  validateSessionForm,
  validateWizardStep,
  varianceTone,
  emptyOutcomeForm,
} from './treatment';

describe('money', () => {
  it('formats Money, decimal strings and numbers with the currency (en-GB grouping)', () => {
    expect(formatMoney({ amount: '1234.5', currency: 'GBP' })).toBe('£1,234.50');
    expect(formatMoney({ amount: '80', currency: 'EUR' })).toBe('€80.00');
    expect(formatMoney('99.99')).toBe('£99.99');
    expect(formatMoney(12.345, 'USD')).toBe('US$12.35');
    expect(formatMoney({ amount: '-15.00', currency: 'GBP' })).toBe('-£15.00');
    expect(formatMoney({ amount: '10.00', currency: 'AED' })).toMatch(/AED\s?10\.00/);
    expect(formatMoney({ amount: '1.00', currency: 'ZZZ1' })).toBe('1.00 ZZZ1');
    expect(formatMoney(undefined)).toBe('–');
    expect(formatMoney({ amount: 'abc', currency: 'GBP' })).toBe('–');
  });

  it('parses user input into a 2-decimal string and rejects bad input', () => {
    expect(parseAmount('1,234.5')).toBe('1234.50');
    expect(parseAmount('£ 80')).toBe('80.00');
    expect(parseAmount('0.05')).toBe('0.05');
    expect(parseAmount('1,234,567.89')).toBe('1234567.89');
    for (const bad of ['', '-5', '1.234', '12,34', 'abc', '1.2.3']) expect(parseAmount(bad)).toBeUndefined();
  });

  it('converts via minor units without float noise and sums same-currency only', () => {
    expect(toMinor('0.1') + toMinor('0.2')).toBe(30);
    expect(toMinor('1.005')).toBe(101);
    expect(fromMinor(-1050)).toBe('-10.50');
    expect(sumMoney([{ amount: '0.10', currency: 'GBP' }, { amount: '0.20', currency: 'GBP' }])).toEqual({ amount: '0.30', currency: 'GBP' });
    expect(sumMoney([{ amount: '1', currency: 'GBP' }, { amount: '1', currency: 'EUR' }])).toBeUndefined();
    expect(asMoney({ amount: 5, currency: 'USD' })).toEqual({ amount: '5.00', currency: 'USD' });
  });

  it('invoice variance against the approved pre-authorisation (5 % tolerance)', () => {
    const approved = { amount: '400.00', currency: 'GBP' };
    expect(invoiceVariance({ amount: '400.00', currency: 'GBP' }, approved)).toMatchObject({ tone: 'green', label: 'Matches approval', pct: 0 });
    expect(invoiceVariance({ amount: '410.00', currency: 'GBP' }, approved)).toMatchObject({ tone: 'green', pct: 2.5, withinTolerance: true, label: '£10.00 (2.5 %) over approval – within tolerance' });
    expect(invoiceVariance({ amount: '448.00', currency: 'GBP' }, approved)).toMatchObject({ tone: 'red', pct: 12, withinTolerance: false, label: '£48.00 (12.0 %) over approval' });
    expect(invoiceVariance('350.00', approved)).toMatchObject({ tone: 'muted', label: '£50.00 (12.5 %) under approval' });
    expect(invoiceVariance({ amount: '1', currency: 'EUR' }, approved)).toBeUndefined();
    expect(invoiceVariance({ amount: '1', currency: 'GBP' }, { amount: '0', currency: 'GBP' })).toBeUndefined();
    expect(describeServerVariance(7.5)).toBe('+7.5 %');
    expect(describeServerVariance({ amount: '20.00', currency: 'GBP' })).toBe('+£20.00');
    expect(describeServerVariance(null)).toBeUndefined();
  });
});

describe('claims deadline countdown', () => {
  const now = Date.parse('2026-10-29T12:00:00Z');
  it('counts down, turns amber within 24 h and red when overdue or LATE', () => {
    expect(deadlineCountdown({ dueAt: '2026-11-01T14:30:00Z', status: 'DRAFT' }, now)).toEqual({ tone: 'green', label: '3 d 2 h left', hoursLeft: 74 });
    expect(deadlineCountdown({ dueAt: '2026-10-30T08:15:00Z', status: 'DRAFT' }, now)).toEqual({ tone: 'amber', label: '20 h 15 min left', hoursLeft: 20 });
    expect(deadlineCountdown({ dueAt: '2026-10-29T12:40:00Z', status: 'DRAFT' }, now)).toMatchObject({ tone: 'amber', label: '40 min left' });
    expect(deadlineCountdown({ dueAt: '2026-10-29T07:00:00Z', status: 'DRAFT' }, now)).toMatchObject({ tone: 'red', label: 'Overdue by 5 h 0 min' });
    expect(deadlineCountdown({ dueAt: '2026-10-29T07:00:00Z', status: 'LATE' }, now).tone).toBe('red');
    expect(deadlineCountdown({ dueAt: '2026-10-29T07:00:00Z', status: 'SUBMITTED' }, now)).toEqual({ tone: 'green', label: 'Submitted' });
    expect(deadlineCountdown({ dueAt: '2026-10-29T07:00:00Z', status: 'ACKNOWLEDGED' }, now).label).toBe('Acknowledged');
    expect(deadlineCountdown({ dueAt: 'nonsense', status: 'DRAFT' }, now).tone).toBe('muted');
  });
});

describe('claim workflow, disclosure and finance helpers', () => {
  it('offers the contract transitions; legal counsel may only litigate', () => {
    const cc = { coordinator: true, legal: false };
    expect(nextClaimStatuses('NOTIFIED', cc)).toEqual(['OPEN']);
    expect(nextClaimStatuses('OPEN', cc)).toEqual(['UNDER_REVIEW', 'LITIGATED']);
    expect(nextClaimStatuses('CLOSED', cc)).toEqual(['OPEN']);
    expect(nextClaimStatuses('UNDER_REVIEW', { coordinator: false, legal: true })).toEqual(['LITIGATED']);
    expect(nextClaimStatuses('SETTLED', { coordinator: false, legal: true })).toEqual([]);
    expect(nextClaimStatuses('OPEN', { coordinator: false, legal: false })).toEqual([]);
    expect(statusReasonRequired('CLOSED', 'OPEN')).toBe(true);
    expect(statusReasonRequired('OPEN', 'UNDER_REVIEW')).toBe(false);
  });

  it('disclosable document types: OH-only reports, never medico-legal / photos', () => {
    expect(isDisclosable('FITNESS_CERTIFICATE', false)).toBe(true);
    expect(isDisclosable('PROVIDER_REPORT', false)).toBe(false);
    expect(isDisclosable('PROVIDER_REPORT', true)).toBe(true);
    expect(isDisclosable('MEDICO_LEGAL_REPORT', true)).toBe(false);
    expect(isDisclosable('PHOTO', true)).toBe(false);
    expect(isDisclosable(undefined, true)).toBe(false);
  });

  it('redaction phrases: one per line, trimmed, de-duplicated, ≤ 50', () => {
    expect(parseRedactionPhrases(' lumbar disc \nLumbar disc\n\nsertraline\r\n')).toEqual({ phrases: ['lumbar disc', 'sertraline'] });
    expect(parseRedactionPhrases('\n \n').error).toMatch(/at least one/);
    expect(parseRedactionPhrases('a').error).toMatch(/too short/);
    expect(parseRedactionPhrases(Array.from({ length: 51 }, (_, i) => `phrase ${i}`).join('\n')).error).toMatch(/At most 50/);
  });

  it('budget variance tone and month / claim form checks', () => {
    expect(budgetTone(12)).toEqual({ tone: 'red', label: '+12.0 % over budget' });
    expect(budgetTone(3)).toMatchObject({ tone: 'amber' });
    expect(budgetTone(-4.25)).toEqual({ tone: 'green', label: '-4.3 % under budget' });
    expect(budgetTone(0).label).toBe('On budget');
    expect(budgetTone(undefined).tone).toBe('muted');
    expect(isMonth('2026-10')).toBe(true);
    expect(isMonth('2026-13')).toBe(false);
    expect(validateClaimForm(emptyClaimForm()).map((e) => e.field)).toEqual(['claim-payerCode']);
    expect(validateClaimForm({ ...emptyClaimForm(), payerCode: 'GULFTPA', adjusterEmail: 'nope' }).map((e) => e.field)).toEqual(['claim-adjusterEmail']);
  });
});

const service: ReferralService = {
  code: 'IMAGING_MRI', name: 'MRI', providerTypes: ['IMAGING'], reportDueDays: 7, urgentAppointmentHours: 48, routineAppointmentDays: 10,
  requiredFields: [
    { code: 'bodyRegion', label: 'Body region', type: 'TEXT' },
    { code: 'injuryDate', label: 'Injury date', type: 'DATE' },
    { code: 'icd10', label: 'Working diagnosis code', type: 'CODE' },
  ],
};

describe('referral data set and wizard', () => {
  it('requires every field of the service and checks DATE / CODE formats', () => {
    expect(validateDataSet(service, {}).map((e) => e.field)).toEqual(['ds-bodyRegion', 'ds-injuryDate', 'ds-icd10']);
    expect(validateDataSet(service, { bodyRegion: 'Lumbar spine', injuryDate: '2026-13-01', icd10: 'M54 5' }).map((e) => e.message)).toEqual([
      'Injury date: enter a valid date',
      'Working diagnosis code: letters, digits, dot, dash or underscore only',
    ]);
    expect(validateDataSet(service, { bodyRegion: 'Lumbar', injuryDate: '2026-10-01', icd10: 'M54.5' })).toEqual([]);
    expect(validateDataSet(undefined, {})).toEqual([]);
  });

  it('drops stale keys and blanks from the data set', () => {
    expect(cleanDataSet(service, { bodyRegion: ' Knee ', icd10: '', old: 'x' })).toEqual({ bodyRegion: 'Knee' });
  });

  it('blocks wizard steps until service, reason, question, provider and consent are given', () => {
    const f = emptyReferralForm();
    expect(validateWizardStep('service', f, undefined).map((e) => e.field)).toEqual(['rf-service', 'rf-reason', 'rf-question']);
    expect(validateWizardStep('provider', f, service)).toHaveLength(1);
    expect(validateWizardStep('consent', { ...f, consentId: 'C-1' }, service)).toEqual([]);
    expect(validateWizardStep('channel', f, service)).toEqual([]);
  });
});

const prov = (over: Partial<Provider>): Provider => ({ code: 'X', name: 'X', orgType: 'CLINIC', specialties: [], services: [], languages: [], accreditation: [], preferred: false, ...over });

describe('provider filtering', () => {
  const list = [
    prov({ code: 'A', name: 'Alpha Physio', city: 'London', services: ['PHYSIOTHERAPY'], contract: { ref: 'c', status: 'NONE' }, scorecard: { month: '2026-09', overall: 90 } }),
    prov({ code: 'B', name: 'Beta Rehab', city: 'London', services: ['PHYSIOTHERAPY'], contract: { ref: 'c', status: 'ACTIVE' }, scorecard: { month: '2026-09', overall: 70 } }),
    prov({ code: 'C', name: 'Gamma Physio', city: 'Dubai', services: ['PHYSIOTHERAPY', 'IMAGING_MRI'], preferred: true, contract: { ref: 'c', status: 'EXPIRING' }, specialties: ['Sports'] }),
    prov({ code: 'D', name: 'Delta Imaging', city: 'London', services: ['IMAGING_MRI'], contract: { ref: 'c', status: 'EXPIRED' }, scorecard: { month: '2026-09', overall: 95 } }),
  ];
  it('filters by service, city, contract and preference and ranks preferred → contracted → score', () => {
    expect(filterProviders(list, { serviceCode: 'PHYSIOTHERAPY' }).map((p) => p.code)).toEqual(['C', 'B', 'A']);
    expect(filterProviders(list, { serviceCode: 'PHYSIOTHERAPY', city: 'london' }).map((p) => p.code)).toEqual(['B', 'A']);
    expect(filterProviders(list, { contract: 'CONTRACTED' }).map((p) => p.code)).toEqual(['C', 'B']);
    expect(filterProviders(list, { contract: 'EXPIRED' }).map((p) => p.code)).toEqual(['D']);
    expect(filterProviders(list, { preferredOnly: true }).map((p) => p.code)).toEqual(['C']);
    expect(filterProviders(list, { q: 'sports' }).map((p) => p.code)).toEqual(['C']);
    expect(filterProviders(list, {}).map((p) => p.code)).toEqual(['C', 'B', 'D', 'A']);
  });
});

describe('treatment plan', () => {
  const t: PlanTemplate = {
    code: 'BACK_STRAIN', injuryType: 'BACK_STRAIN', title: 'Back strain', goals: ['Pain ≤ 3', 'Return to full duties'],
    interventions: [{ type: 'PHYSIOTHERAPY', description: 'Physio', frequency: '2x weekly', weeks: 4 }, { type: 'SELF_CARE', description: 'Home exercises' }],
    followUpDays: [28, 14], guidelineCode: 'ODG-BACK', expectedDays: 42,
  };
  it('expands a template from today', () => {
    const f = planFromTemplate(t, '2026-10-29');
    expect(f.goals).toEqual(['Pain ≤ 3', 'Return to full duties']);
    expect(f.interventions[0]).toEqual({ type: 'PHYSIOTHERAPY', description: 'Physio', frequency: '2x weekly', startDate: '2026-10-29', endDate: '2026-11-26', providerCode: '' });
    expect(f.interventions[1]?.endDate).toBe('');
    expect(f.followUps.map((x) => x.dueDate)).toEqual(['2026-11-12', '2026-11-26']);
    expect(f.followUps[0]?.type).toBe('OH_REVIEW');
    expect(f.expectedRecoveryDays).toBe('42');
    expect(planFromTemplate({ ...t, interventions: [t.interventions[0]!] }, '2026-10-29').followUps[0]?.type).toBe('PHYSIOTHERAPY');
  });

  it('validates and maps to the request body', () => {
    const empty = emptyPlanForm('2026-10-29');
    expect(validatePlanForm(empty).map((e) => e.field)).toEqual(['plan-goal-0', 'plan-int-0-description']);
    const f = planFromTemplate(t, '2026-10-29');
    f.followUps[0]!.book = true;
    expect(validatePlanForm(f).map((e) => e.field)).toEqual(['plan-fu-0-siteCode']);
    f.followUps[0]!.siteCode = 'LHR-T5';
    f.varianceThresholdPct = '30';
    expect(validatePlanForm(f)).toEqual([]);
    const body = toPlanBody(f);
    expect(body).toMatchObject({ templateCode: 'BACK_STRAIN', expectedRecoveryDays: 42, guidelineCode: 'ODG-BACK', varianceThresholdPct: 30 });
    expect(body.followUps[0]).toEqual({ dueDate: '2026-11-12', type: 'OH_REVIEW', siteCode: 'LHR-T5', book: true });
    expect(body.interventions[1]).toEqual({ type: 'SELF_CARE', description: 'Home exercises', startDate: '2026-10-29' });
  });

  it('variance indicator', () => {
    expect(varianceTone({ actualDays: 30, expectedDays: 42, pctOver: 0, alert: false })).toEqual({ tone: 'green', label: 'Within guideline (day 30 of 42)' });
    expect(varianceTone({ actualDays: 46, expectedDays: 42, pctOver: 9.5, alert: false }).tone).toBe('amber');
    expect(varianceTone({ actualDays: 55, expectedDays: 42, pctOver: 31, alert: true })).toEqual({ tone: 'red', label: '31 % over guideline (day 55 of 42)' });
    expect(varianceTone(undefined).tone).toBe('muted');
  });
});

describe('referral timeline and report due', () => {
  it('marks reached statuses and stops at DECLINED', () => {
    const steps = referralTimeline({ status: 'SCHEDULED', createdAt: '2026-10-01T00:00:00Z', sentAt: '2026-10-02T00:00:00Z', history: [{ status: 'ACCEPTED', at: '2026-10-03T00:00:00Z', by: 'partner1' }] });
    expect(steps.map((s) => s.state)).toEqual(['done', 'done', 'done', 'current', 'todo', 'todo', 'todo']);
    expect(steps[2]).toMatchObject({ status: 'ACCEPTED', by: 'partner1' });
    const declined = referralTimeline({ status: 'DECLINED', createdAt: '2026-10-01T00:00:00Z', sentAt: '2026-10-02T00:00:00Z', history: [{ status: 'DECLINED', at: '2026-10-03T00:00:00Z' }] });
    expect(declined.map((s) => `${s.status}:${s.state}`)).toEqual(['DRAFT:done', 'SENT:done', 'DECLINED:stopped']);
  });

  it('report due label', () => {
    const r = { status: 'SEEN' as const, overdue: false, reportDueAt: '2026-11-02T00:00:00Z' };
    expect(reportDueLabel(r, '2026-10-29')).toEqual({ tone: 'green', label: 'Report due in 4 d' });
    expect(reportDueLabel(r, '2026-11-01')).toEqual({ tone: 'amber', label: 'Report due in 1 d' });
    expect(reportDueLabel(r, '2026-11-05')).toEqual({ tone: 'red', label: 'Report 3 d overdue' });
    expect(reportDueLabel({ ...r, status: 'REPORT_RECEIVED' }, '2026-11-05')).toBeUndefined();
  });
});

describe('outcomes', () => {
  it('computes direction-corrected improvement from baseline to latest', () => {
    const list = normaliseOutcomes({
      outcomes: [
        { milestone: 'REVIEW', painScore: 5, functionScale: 'ODI', functionScore: 30, recordedAt: '2026-10-15T00:00:00Z' },
        { milestone: 'BASELINE', painScore: 8, functionScale: 'ODI', functionScore: 52, recordedAt: '2026-10-01T00:00:00Z' },
      ],
    });
    const i = outcomeImprovement(list);
    expect(i).toMatchObject({ painImprovement: 3, functionImprovement: 22, scale: 'ODI' });
    expect(describeImprovement(i)).toEqual(['Pain down 3 points (NPRS)', 'Function improved by 22 (ODI)']);
    const lefs = outcomeImprovement([
      { milestone: 'BASELINE', painScore: 4, functionScale: 'LEFS', functionScore: 40 },
      { milestone: 'CLOSURE', painScore: 5, functionScale: 'LEFS', functionScore: 35 },
    ]);
    expect(describeImprovement(lefs)).toEqual(['Pain up 1 point (NPRS)', 'Function worse by 5 (LEFS)']);
    expect(outcomeImprovement([{ milestone: 'BASELINE', painScore: 4, functionScale: 'NONE' }])).toEqual({});
  });

  it('validates the outcome form', () => {
    expect(validateOutcome(emptyOutcomeForm()).map((e) => e.field)).toEqual(['oc-pain']);
    expect(validateOutcome({ ...emptyOutcomeForm(), painScore: '11', functionScale: 'ODI', functionScore: '', residualImpairmentPct: '101' }).map((e) => e.field)).toEqual(['oc-pain', 'oc-function', 'oc-impairment']);
    expect(validateOutcome({ ...emptyOutcomeForm('CLOSURE'), painScore: '0', rtwDate: '2026-11-01', residualImpairmentPct: '5' })).toEqual([]);
  });
});

describe('medications and telehealth', () => {
  it('flags expired / expiring / below-reorder stock and offers usable batches FEFO', () => {
    expect(stockFlags({ expired: false, expiry: '2026-10-01', belowReorder: false }, '2026-10-29')).toEqual([{ tone: 'red', label: 'Expired – do not use' }]);
    expect(stockFlags({ expired: false, expiry: '2026-11-10', belowReorder: true }, '2026-10-29')).toEqual([{ tone: 'amber', label: 'Expires in 12 d' }, { tone: 'amber', label: 'Below reorder level' }]);
    expect(stockFlags({ expired: false, expiry: '2027-06-01', belowReorder: false }, '2026-10-29')).toEqual([]);
    const stock = [
      { itemCode: 'PARA', siteCode: 'LHR', batchNumber: 'B2', expiry: '2027-03-01', quantity: 5, expired: false },
      { itemCode: 'PARA', siteCode: 'LHR', batchNumber: 'B1', expiry: '2026-12-01', quantity: 2, expired: false },
      { itemCode: 'PARA', siteCode: 'LHR', batchNumber: 'B0', expiry: '2026-09-01', quantity: 9, expired: true },
      { itemCode: 'PARA', siteCode: 'LHR', batchNumber: 'B3', expiry: '2027-03-01', quantity: 0, expired: false },
      { itemCode: 'PARA', siteCode: 'DXB', batchNumber: 'B4', expiry: '2027-03-01', quantity: 3, expired: false },
    ];
    expect(usableBatches(stock, 'PARA', 'LHR', '2026-10-29').map((b) => b.batchNumber)).toEqual(['B1', 'B2']);
  });

  it('recognises the allergy alert problem', () => {
    expect(isAllergyAlert(new ApiError(422, { code: 'allergy_alert', detail: 'Penicillin allergy' }))).toBe(true);
    expect(isAllergyAlert(new ApiError(422, { code: 'batch_expired' }))).toBe(false);
  });

  it('join window opens 15 minutes before the start and closes at the end', () => {
    const s = { scheduledAt: '2026-10-29T12:00:00Z', durationMinutes: 20, status: 'SCHEDULED' as const };
    expect(joinWindow(s, Date.parse('2026-10-29T11:40:00Z'))).toEqual({ open: false, label: 'Join opens in 5 min' });
    expect(joinWindow(s, Date.parse('2026-10-29T11:46:00Z')).open).toBe(true);
    expect(joinWindow(s, Date.parse('2026-10-29T12:19:00Z')).open).toBe(true);
    expect(joinWindow(s, Date.parse('2026-10-29T12:21:00Z')).label).toBe('Session time has passed');
    expect(joinWindow({ ...s, status: 'COMPLETED' }, Date.parse('2026-10-29T12:00:00Z')).open).toBe(false);
  });

  it('validates the session form incl. crew-layover city', () => {
    const now = Date.parse('2026-10-29T10:00:00Z');
    const base = { employeeId: 'E1', caseId: '', scheduledAt: '2026-10-29T13:00:00Z', durationMinutes: '20', mode: 'VIDEO' as const, clinicianUsername: '', providerCode: '', crewLayover: false, layoverCity: '' };
    expect(validateSessionForm(base, now)).toEqual([]);
    expect(validateSessionForm({ ...base, durationMinutes: '5', crewLayover: true }, now).map((e) => e.field)).toEqual(['th-duration', 'th-city']);
    expect(validateSessionForm({ ...base, scheduledAt: '2026-10-29T08:00:00Z' }, now).map((e) => e.field)).toEqual(['th-at']);
  });
});
