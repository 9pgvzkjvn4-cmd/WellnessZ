import { describe, expect, it } from 'vitest';
import {
  adviceWithdrawPath,
  buildAdviceRequest,
  canWithdrawAdvice,
  emptyAdviceRequestForm,
  isSlaOverdue,
  toAdviceQuestions,
  toAdviceRequests,
  validateAdviceRequest,
} from './advice';

const QUESTIONS = toAdviceQuestions([
  { code: 'FIT_FOR_ROLE', text: 'Is the employee fit for their role?' },
  { code: 'ADJUSTMENTS', text: 'What adjustments would help?' },
  { code: 'EXPECTED_RTW', text: 'When is a return to work expected?' },
  { code: 'ADJUSTMENTS', text: 'duplicate' },
  { text: 'no code' },
]);

const REPORT = {
  fitness: 'FIT_WITH_ADJUSTMENTS',
  adjustments: 'Phased return over 4 weeks',
  restrictions: ['No lifting above 10 kg', { label: 'No night shifts' }],
  prognosis: 'RTW_WITHIN_4_WEEKS',
  expectedReturn: '2026-11-16',
  reviewDate: '2026-12-01',
  answers: { EXPECTED_RTW: 'Within four weeks', FIT_FOR_ROLE: 'Yes with adjustments', OTHER: 'Extra' },
  employeeComment: 'I agree with the plan.',
  documentId: 'doc-1',
  releasedAt: '2026-10-28T10:00:00Z',
  diagnosis: 'SECRET-DX',
};

describe('advice question library (E06 §3.5)', () => {
  it('maps codes and texts, dropping duplicates and rows without a code', () => {
    expect(QUESTIONS.map((q) => q.code)).toEqual(['FIT_FOR_ROLE', 'ADJUSTMENTS', 'EXPECTED_RTW']);
  });
});

describe('advice request form', () => {
  const employee = { employeeId: 'E1004', displayName: 'Rami Ramp' };

  it('requires an employee and at least one library question', () => {
    expect(validateAdviceRequest(emptyAdviceRequestForm(), QUESTIONS)).toEqual({
      employeeId: 'Please choose the employee.',
      questionCodes: 'Please choose at least one question for Occupational Health.',
    });
    expect(validateAdviceRequest({ employee, questionCodes: ['MADE_UP'], context: '' }, QUESTIONS)).toHaveProperty('questionCodes');
    expect(validateAdviceRequest({ employee, questionCodes: ['ADJUSTMENTS'], context: 'x'.repeat(2001) }, QUESTIONS)).toHaveProperty('context');
    expect(validateAdviceRequest({ employee, questionCodes: ['ADJUSTMENTS'], context: 'x'.repeat(2000) }, QUESTIONS)).toEqual({});
  });

  it('builds the body with questions in library order and optional context', () => {
    expect(buildAdviceRequest({ employee, questionCodes: ['EXPECTED_RTW', 'FIT_FOR_ROLE'], context: '  ' }, QUESTIONS)).toEqual({
      employeeId: 'E1004',
      questionCodes: ['FIT_FOR_ROLE', 'EXPECTED_RTW'],
    });
    expect(buildAdviceRequest({ employee, questionCodes: ['ADJUSTMENTS'], context: ' Works night shifts on the ramp ' }, QUESTIONS)).toEqual({
      employeeId: 'E1004',
      questionCodes: ['ADJUSTMENTS'],
      context: 'Works night shifts on the ramp',
    });
  });
});

describe('advice requests list', () => {
  const base = {
    ref: 'ADV-2026-000001',
    employeeId: 'E1004',
    displayName: 'Rami Ramp',
    questionCodes: ['FIT_FOR_ROLE', 'EXPECTED_RTW'],
    requestedBy: 'manager1',
    requestedAt: '2026-10-20T09:00:00Z',
    slaDueAt: '2026-10-27T17:00:00Z',
    context: 'SECRET-CONTEXT',
  };

  it('keeps the report only when RELEASED, whitelisted and with answers in question order', () => {
    const [released, withheld] = toAdviceRequests([
      { ...base, id: 'a1', status: 'RELEASED', report: REPORT },
      { ...base, id: 'a2', status: 'WITHHELD', report: REPORT, requestedAt: '2026-10-19T09:00:00Z' },
    ]);
    expect(released!.report).toEqual({
      fitness: 'FIT_WITH_ADJUSTMENTS',
      adjustments: ['Phased return over 4 weeks'],
      restrictions: ['No lifting above 10 kg', 'No night shifts'],
      prognosis: 'RTW_WITHIN_4_WEEKS',
      expectedReturn: '2026-11-16',
      reviewDate: '2026-12-01',
      answers: [
        { code: 'FIT_FOR_ROLE', text: 'Yes with adjustments' },
        { code: 'EXPECTED_RTW', text: 'Within four weeks' },
        { code: 'OTHER', text: 'Extra' },
      ],
      employeeComment: 'I agree with the plan.',
      documentId: 'doc-1',
      releasedAt: '2026-10-28T10:00:00Z',
    });
    expect(withheld!.report).toBeUndefined();
    expect(JSON.stringify([released, withheld])).not.toMatch(/SECRET/);
  });

  it('sorts newest first and drops rows without id or status', () => {
    const rows = toAdviceRequests([
      { ...base, id: 'old', status: 'SUBMITTED', requestedAt: '2026-01-01T00:00:00Z' },
      { ...base, id: 'new', status: 'SUBMITTED' },
      { ...base, status: 'SUBMITTED' },
      { ...base, id: 'x' },
    ]);
    expect(rows.map((r) => r.id)).toEqual(['new', 'old']);
  });

  it('allows withdrawal only before an outcome', () => {
    for (const st of ['SUBMITTED', 'ACCEPTED', 'REPORT_DRAFTED', 'AWAITING_EMPLOYEE']) expect(canWithdrawAdvice({ status: st })).toBe(true);
    for (const st of ['RELEASED', 'WITHHELD', 'WITHDRAWN']) expect(canWithdrawAdvice({ status: st })).toBe(false);
    expect(adviceWithdrawPath('a/1')).toBe('/workspace/advice-requests/a%2F1/withdraw');
  });

  it('flags open requests past their SLA', () => {
    const now = new Date('2026-10-29T12:00:00Z');
    expect(isSlaOverdue({ status: 'SUBMITTED', slaDueAt: '2026-10-27T17:00:00Z' }, now)).toBe(true);
    expect(isSlaOverdue({ status: 'SUBMITTED', slaDueAt: '2026-11-03T17:00:00Z' }, now)).toBe(false);
    expect(isSlaOverdue({ status: 'RELEASED', slaDueAt: '2026-10-27T17:00:00Z' }, now)).toBe(false);
    expect(isSlaOverdue({ status: 'SUBMITTED' }, now)).toBe(false);
  });
});
