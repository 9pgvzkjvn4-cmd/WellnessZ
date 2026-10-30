import { describe, expect, it } from 'vitest';
import { ApiError } from '../auth/AuthClient';
import {
  bandFor,
  bandLabel,
  bandTone,
  cismSla,
  countdown,
  emptyAssessmentForm,
  emptyContactForm,
  emptyFitnessForm,
  followUpTone,
  isQuarter,
  isUrgentScreen,
  previewAssessment,
  previousQuarter,
  quarterOf,
  ragFor,
  screenScore,
  serverFieldError,
  sharingLabel,
  smsTotal,
  trainingTone,
  validateAssessment,
  validateContact,
  validateEap,
  validateFitness,
  validateScreen,
} from './support';

const NOW = Date.parse('2026-10-30T12:00:00Z');
const at = (min: number) => new Date(NOW + min * 60_000).toISOString();

describe('screening scoring and bands (PHQ-9 / GAD-7)', () => {
  it('sums answers and maps PHQ-9 bands at the published cut-offs', () => {
    expect(screenScore([1, 2, 3, undefined])).toBe(6);
    expect([0, 4, 5, 9, 10, 14, 15, 19, 20, 27].map((s) => bandFor('PHQ9', s))).toEqual([
      'MINIMAL', 'MINIMAL', 'MILD', 'MILD', 'MODERATE', 'MODERATE', 'MODERATELY_SEVERE', 'MODERATELY_SEVERE', 'SEVERE', 'SEVERE',
    ]);
  });

  it('GAD-7 has no moderately-severe band (15+ is severe)', () => {
    expect(bandFor('GAD7', 14)).toBe('MODERATE');
    expect(bandFor('GAD7', 15)).toBe('SEVERE');
    expect(bandFor('GAD7', 21)).toBe('SEVERE');
  });

  it('labels and tones bands for display', () => {
    expect(bandLabel('PHQ9', 'MODERATELY_SEVERE')).toBe('Moderately severe (15–19)');
    expect(bandLabel('GAD7', 'SEVERE')).toBe('Severe (15–21)');
    expect(bandLabel('PHQ9', 'MINIMAL')).toBe('Minimal (0–4)');
    expect(bandTone('MINIMAL')).toBe('green');
    expect(bandTone('MILD')).toBe('muted');
    expect(bandTone('MODERATE')).toBe('amber');
    expect(bandTone('SEVERE')).toBe('red');
  });

  it('urgent rule: PHQ-9 item 9 above zero or a severe band', () => {
    expect(isUrgentScreen('PHQ9', [0, 0, 0, 0, 0, 0, 0, 0, 1])).toBe(true);
    expect(isUrgentScreen('PHQ9', [2, 2, 2, 2, 2, 2, 2, 2, 0])).toBe(false); // 16 = moderately severe, item 9 = 0
    expect(isUrgentScreen('PHQ9', [3, 3, 3, 3, 3, 3, 2, 0, 0])).toBe(true); // 20 = severe
    expect(isUrgentScreen('GAD7', [3, 3, 3, 3, 3, 0, 0])).toBe(true);
    expect(isUrgentScreen('GAD7', [1, 1, 1, 1, 1, 1, 1])).toBe(false);
  });

  it('requires every item to be answered', () => {
    expect(validateScreen('GAD7', [0, 1, 2, 3, 0, 1])).toEqual([{ field: 'screen-item-6', message: 'Answer item 7.' }]);
    expect(validateScreen('PHQ9', Array(9).fill(0))).toEqual([]);
  });

  it('describes the employee’s sharing choice', () => {
    expect(sharingLabel('NOTHING')).toBe('Nothing shared with the manager');
    expect(sharingLabel('FITNESS_ONLY')).toMatch(/fitness status only/);
    expect(sharingLabel('FITNESS_AND_ADJUSTMENTS')).toMatch(/adjustments/);
  });
});

describe('countdowns', () => {
  it('green / amber / red / overdue with readable spans', () => {
    expect(countdown(at(300), NOW)).toMatchObject({ tone: 'green', label: '5 h 00 min left', overdue: false });
    expect(countdown(at(90), NOW)).toMatchObject({ tone: 'amber', label: '1 h 30 min left' });
    expect(countdown(at(20), NOW)).toMatchObject({ tone: 'red', label: '20 min left' });
    expect(countdown(at(-75), NOW)).toMatchObject({ tone: 'red', label: 'Overdue by 1 h 15 min', overdue: true });
    expect(countdown(at(3 * 24 * 60 + 60), NOW).label).toBe('3 d 1 h left');
    expect(countdown(undefined, NOW)).toMatchObject({ tone: 'muted', label: 'No deadline' });
    expect(countdown('nonsense', NOW).tone).toBe('muted');
  });

  it('CISM 72 h offer SLA: server verdict first, then live countdown', () => {
    expect(cismSla({ offerDueBy: at(-10), slaMet: true, affected: 3, offered: 3 }, NOW)).toEqual({ tone: 'green', label: 'Offers sent within 72 h' });
    expect(cismSla({ offerDueBy: at(-10), slaMet: false, affected: 3, offered: 1 }, NOW).tone).toBe('red');
    expect(cismSla({ offerDueBy: at(60 * 48), affected: 3, offered: 0 }, NOW)).toMatchObject({ tone: 'green' });
    expect(cismSla({ offerDueBy: at(60 * 10), affected: 3, offered: 0 }, NOW).tone).toBe('amber');
    expect(cismSla({ offerDueBy: at(-60), affected: 3, offered: 1 }, NOW).label).toMatch(/^Offers overdue by/);
    expect(cismSla({ offerDueBy: at(-60), affected: 2, offered: 2 }, NOW).label).toBe('All offers sent');
  });

  it('follow-ups at 1 / 4 weeks', () => {
    expect(followUpTone({ dueOn: '2026-10-01', done: true }, '2026-10-30').tone).toBe('green');
    expect(followUpTone({ dueOn: '2026-10-29', done: false }, '2026-10-30').tone).toBe('red');
    expect(followUpTone({ dueOn: '2026-11-01', done: false }, '2026-10-30').tone).toBe('amber');
    expect(followUpTone({ dueOn: '2026-11-20', done: false }, '2026-10-30').tone).toBe('muted');
    expect(followUpTone(undefined).label).toBe('–');
  });
});

describe('EAP, fitness and contact-log validation', () => {
  it('EAP: 1–12 sessions and a consent', () => {
    expect(validateEap({ partnerCode: 'EAP_WELLBEING', sessionsAuthorised: '6', urgency: 'ROUTINE', consentId: 'c1' })).toEqual([]);
    expect(validateEap({ partnerCode: 'EAP_WELLBEING', sessionsAuthorised: '13', urgency: 'ROUTINE', consentId: '' }).map((e) => e.field)).toEqual(['eap-sessions', 'eap-consent']);
    expect(validateEap({ partnerCode: '', sessionsAuthorised: '2.5', urgency: 'PRIORITY', consentId: 'c' }).map((e) => e.field)).toEqual(['eap-partner', 'eap-sessions']);
  });

  it('fitness: limitations only for fit-with-limitations, future review date, complete medication rows', () => {
    const base = { ...emptyFitnessForm('E1002'), standardCode: 'CLASS_1', findings: 'Stable on treatment', decision: 'FIT' as const, reviewDate: '2027-04-30' };
    expect(validateFitness(base, '2026-10-30')).toEqual([]);
    expect(validateFitness({ ...base, decision: 'FIT_WITH_LIMITATIONS' }, '2026-10-30').map((e) => e.field)).toEqual(['fit-limitations']);
    expect(validateFitness({ ...base, reviewDate: '2026-10-30' }, '2026-10-30').map((e) => e.field)).toEqual(['fit-review']);
    expect(validateFitness({ ...base, medications: [{ name: 'Sertraline', drugClass: '', dose: '50 mg', startDate: '2027-01-01' }] }, '2026-10-30').map((e) => e.field)).toEqual(['fit-med-0-class', 'fit-med-0-start']);
    expect(validateFitness(emptyFitnessForm(), '2026-10-30').map((e) => e.field)).toEqual(['fit-employee', 'fit-standard', 'fit-findings', 'fit-decision', 'fit-review']);
  });

  it('peer contact log refuses employee IDs, names (spaces) and future dates', () => {
    const ok = { ...emptyContactForm('PS-7K3Q', '2026-10-30'), durationMinutes: '25', category: 'FATIGUE' as const, outcome: 'SUPPORTED' as const };
    expect(validateContact(ok, '2026-10-30')).toEqual([]);
    expect(validateContact({ ...ok, contactCode: 'E1004' }, '2026-10-30')[0]?.message).toMatch(/never an employee ID/);
    expect(validateContact({ ...ok, contactCode: 'Jane Doe' }, '2026-10-30')[0]?.field).toBe('contact-code');
    expect(validateContact({ ...ok, date: '2026-11-01', durationMinutes: '0' }, '2026-10-30').map((e) => e.field)).toEqual(['contact-date', 'contact-duration']);
  });

  it('training expiry tones', () => {
    expect(trainingTone('2026-10-01', '2026-10-30')).toBe('red');
    expect(trainingTone('2026-12-01', '2026-10-30')).toBe('amber');
    expect(trainingTone('2027-06-01', '2026-10-30')).toBe('green');
    expect(trainingTone(undefined)).toBe('muted');
  });
});

describe('quarterly SMS report', () => {
  it('quarters', () => {
    expect(quarterOf('2026-10-30')).toBe('2026-Q4');
    expect(previousQuarter('2026-10-30')).toBe('2026-Q3');
    expect(previousQuarter('2026-02-01')).toBe('2025-Q4');
    expect(isQuarter('2026-Q3')).toBe(true);
    expect(isQuarter('2026-Q5')).toBe(false);
  });
  it('totals as number or object; suppressed totals are hidden', () => {
    expect(smsTotal({ quarter: 'q', rows: [], totals: 12 })).toBe(12);
    expect(smsTotal({ quarter: 'q', rows: [], totals: { contacts: 9 } })).toBe(9);
    expect(smsTotal({ quarter: 'q', rows: [], totals: { contacts: 3, suppressed: true } })).toBeUndefined();
    expect(smsTotal({ quarter: 'q', rows: [] })).toBeUndefined();
  });
});

describe('psychosocial factor scoring (1–5 × 1–5)', () => {
  it('RAG thresholds', () => {
    expect([1, 7, 8, 14, 15, 25].map(ragFor)).toEqual(['GREEN', 'GREEN', 'AMBER', 'AMBER', 'RED', 'RED']);
  });
  it('previews per-factor score and the overall (highest) score', () => {
    const f = emptyAssessmentForm('2026-10-30');
    f.factors[0] = { ...f.factors[0]!, likelihood: '4', severity: '4' };
    f.factors[1] = { ...f.factors[1]!, likelihood: '2', severity: '3' };
    const p = previewAssessment(f);
    expect(p.factors[0]).toMatchObject({ factor: 'WORKLOAD', score: 16, rag: 'RED' });
    expect(p.factors[1]).toMatchObject({ score: 6, rag: 'GREEN' });
    expect(p.factors[2]?.score).toBeUndefined();
    expect(p).toMatchObject({ overall: 16, rag: 'RED' });
  });
  it('validates unit, review after assessment and ratings 1–5 for every factor', () => {
    const f = { ...emptyAssessmentForm('2026-10-30'), unitCode: 'CABIN', reviewDate: '2027-04-30' };
    f.factors = f.factors.map((x) => ({ ...x, likelihood: '3', severity: '2' }));
    expect(validateAssessment(f)).toEqual([]);
    f.factors[2] = { ...f.factors[2]!, severity: '6' };
    expect(validateAssessment({ ...f, reviewDate: '2026-10-01' }).map((e) => e.field)).toEqual(['psy-review', 'psy-2-severity']);
  });
});

describe('server field errors (422)', () => {
  it('maps the problem field to the form field id', () => {
    const e = new ApiError(422, { code: 'consent_required', field: 'consentId', detail: 'An active partner-sharing consent is required' });
    expect(serverFieldError(e, { consentId: 'eap-consent' })).toEqual({ field: 'eap-consent', message: 'An active partner-sharing consent is required' });
    expect(serverFieldError(new ApiError(422, { field: 'factors[2].likelihood', detail: 'x' }), { 'factors.2.likelihood': 'psy-2-likelihood' })?.field).toBe('psy-2-likelihood');
    expect(serverFieldError(new ApiError(409, { field: 'consentId' }), { consentId: 'x' })).toBeUndefined();
    expect(serverFieldError(new Error('x'), {})).toBeUndefined();
  });
});
