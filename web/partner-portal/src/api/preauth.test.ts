import { describe, expect, it } from 'vitest';
import { preauth } from '../test/fixtures';
import { buildDecision, isSlaOverdue, toPreauths, type DecisionForm } from './preauth';

const NOW = new Date('2026-10-29T12:00:00Z');
const p = toPreauths([preauth()])[0]!;
const form = (over: Partial<DecisionForm>): DecisionForm => ({ status: '', approvalRef: '', amount: '', validUntil: '', note: '', ...over });

describe('pre-authorisation decisions', () => {
  it('maps requests without the clinical justification', () => {
    expect(p.estimate).toEqual({ amount: '850.00', currency: 'GBP' });
    expect(JSON.stringify(p)).not.toContain('SECRET');
    expect(isSlaOverdue(p, NOW)).toBe(false);
    expect(isSlaOverdue(p, new Date('2026-10-31T00:00:00Z'))).toBe(true);
  });

  it('requires a decision', () => {
    expect(buildDecision(p, form({}), NOW).errors).toEqual({ status: 'This field is required.' });
  });

  it('approves with reference and validity', () => {
    expect(buildDecision(p, form({ status: 'APPROVED', approvalRef: ' GT-77 ', validUntil: '2026-12-31', amount: '1' }), NOW).body).toEqual({
      status: 'APPROVED',
      approvalRef: 'GT-77',
      validUntil: '2026-12-31',
    });
  });

  it('partially approves with an amount below the estimate in its currency', () => {
    expect(buildDecision(p, form({ status: 'PARTIALLY_APPROVED', amount: '600', note: 'Max 4 sessions' }), NOW).body).toEqual({
      status: 'PARTIALLY_APPROVED',
      approvedAmount: { amount: '600.00', currency: 'GBP' },
      note: 'Max 4 sessions',
    });
    expect(buildDecision(p, form({ status: 'PARTIALLY_APPROVED', amount: '850.00' }), NOW).errors).toEqual({ approvedAmount: 'The partially approved amount must be lower than the estimate.' });
    expect(buildDecision(p, form({ status: 'PARTIALLY_APPROVED', amount: '' }), NOW).errors.approvedAmount).toMatch(/greater than zero/);
  });

  it('declines without approval fields', () => {
    expect(buildDecision(p, form({ status: 'DECLINED', approvalRef: 'X', validUntil: '2020-01-01', note: 'Not covered' }), NOW).body).toEqual({ status: 'DECLINED', note: 'Not covered' });
  });

  it('rejects a validity date in the past', () => {
    expect(buildDecision(p, form({ status: 'APPROVED', validUntil: '2026-10-01' }), NOW).errors).toEqual({ validUntil: 'The date cannot be in the past.' });
  });
});
