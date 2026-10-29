import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ADVICE_REQUEST_FIELDS, buildAdviceRequest, toAdviceQuestions, toAdviceRequests } from '../api/advice';
import { submitAction } from '../api/submit';
import { ApiError } from '../auth/AuthClient';
import { adviceQuestionId } from '../components/ids';
import { findAll, findOne } from '../test/tree';
import { AdviceRequestList, CONTEXT_HINT, QuestionPicker, type AdviceRequestListProps } from './AdviceRequests';

const questions = toAdviceQuestions([
  { code: 'FIT_FOR_ROLE', text: 'Is the employee fit for their role?' },
  { code: 'EXPECTED_RTW', text: 'When is a return to work expected?' },
]);
const base = {
  ref: 'ADV-2026-000001',
  employeeId: 'E1004',
  displayName: 'Rami Ramp',
  questionCodes: ['FIT_FOR_ROLE', 'EXPECTED_RTW'],
  requestedAt: '2026-10-20T09:00:00Z',
  slaDueAt: '2026-10-27T17:00:00Z',
};
const report = {
  fitness: 'FIT_WITH_ADJUSTMENTS',
  adjustments: 'Phased return over 4 weeks',
  restrictions: 'No lifting above 10 kg',
  prognosis: 'RTW_WITHIN_4_WEEKS',
  expectedReturn: '2026-11-16',
  reviewDate: '2026-12-01',
  answers: { FIT_FOR_ROLE: 'Yes, with a phased return', EXPECTED_RTW: 'Mid November' },
  employeeComment: 'Happy with this.',
  documentId: 'doc-9',
  releasedAt: '2026-10-28T10:00:00Z',
};
const list = (over: Partial<AdviceRequestListProps> = {}): AdviceRequestListProps => ({
  items: [],
  questions,
  now: new Date('2026-10-29T12:00:00Z'),
  onWithdraw: vi.fn(),
  onConfirm: vi.fn(),
  onCancel: vi.fn(),
  ...over,
});

describe('question picker', () => {
  it('lists the library and toggles codes', () => {
    const onChange = vi.fn();
    const tree = QuestionPicker({ questions, selected: ['EXPECTED_RTW'], onChange });
    findOne(tree, (e) => e.props.id === adviceQuestionId('FIT_FOR_ROLE')).props.onChange({ target: { checked: true } });
    expect(onChange).toHaveBeenLastCalledWith(['EXPECTED_RTW', 'FIT_FOR_ROLE']);
    findOne(tree, (e) => e.props.id === adviceQuestionId('EXPECTED_RTW')).props.onChange({ target: { checked: false } });
    expect(onChange).toHaveBeenLastCalledWith([]);
  });

  it('marks the group invalid when no question is chosen', () => {
    const html = renderToStaticMarkup(<QuestionPicker questions={questions} selected={[]} error="Please choose at least one question." onChange={vi.fn()} />);
    expect(html).toMatch(/<fieldset id="adv-questionCodes"[^>]*aria-invalid="true"/);
    expect(html).toContain('Please choose at least one question.');
  });

  it('explains that context goes only to OH', () => {
    expect(CONTEXT_HINT).toMatch(/only to Occupational Health/);
  });
});

describe('advice request list', () => {
  it('shows the released report, answers per question, comment and the shared document', () => {
    const items = toAdviceRequests([{ ...base, id: 'a1', status: 'RELEASED', report: { ...report, diagnosis: 'SECRET-DX' } }]);
    const html = renderToStaticMarkup(<AdviceRequestList {...list({ items })} />);
    for (const s of [
      'ADV-2026-000001',
      'Released',
      'Fit with adjustments',
      'Phased return over 4 weeks',
      'No lifting above 10 kg',
      'Return within 4 weeks expected',
      'Is the employee fit for their role?',
      'Yes, with a phased return',
      'Mid November',
      'Happy with this.',
      'href="/bff/api/documents/doc-9/content"',
    ]) {
      expect(html).toContain(s);
    }
    expect(html).toMatch(/Nov[^<]*2026/);
    expect(html).not.toContain('SECRET');
    expect(html).not.toContain('Withdraw request');
    expect(html).not.toContain('Past due date');
  });

  it('shows only the consent message for a WITHHELD request', () => {
    const items = toAdviceRequests([{ ...base, id: 'a2', status: 'WITHHELD', report }]);
    const html = renderToStaticMarkup(<AdviceRequestList {...list({ items })} />);
    expect(html).toContain('The employee has not consented to release – OH will contact you.');
    expect(html).not.toContain('Phased return');
    expect(html).not.toContain('doc-9');
  });

  it('offers a two-step withdraw for open requests and flags a missed SLA', () => {
    const items = toAdviceRequests([{ ...base, id: 'a3', status: 'SUBMITTED' }]);
    const onWithdraw = vi.fn();
    const html = renderToStaticMarkup(<AdviceRequestList {...list({ items })} />);
    expect(html).toContain('Past due date');
    expect(html).toContain('Submitted');
    const tree = AdviceRequestList(list({ items, onWithdraw }));
    findOne(tree, (e) => e.type === 'button' && e.props.children === 'Withdraw request').props.onClick();
    expect(onWithdraw).toHaveBeenCalledWith('a3');

    const onConfirm = vi.fn();
    const confirming = AdviceRequestList(list({ items, confirming: 'a3', onConfirm }));
    findAll(confirming, (e) => e.type === 'button')[0]!.props.onClick();
    expect(onConfirm).toHaveBeenCalledWith('a3');
  });

  it('shows an empty state', () => {
    expect(renderToStaticMarkup(<AdviceRequestList {...list()} />)).toContain('You have not requested OH advice yet.');
  });
});

describe('submitting an advice request', () => {
  it('posts to /workspace/advice-requests and maps a 422 on context', async () => {
    const body = buildAdviceRequest({ employee: { employeeId: 'E1004', displayName: 'Rami' }, questionCodes: ['FIT_FOR_ROLE'], context: 'ctx' }, questions);
    const ok = vi.fn().mockResolvedValue({ id: 'a1', ref: 'ADV-2026-000001', status: 'SUBMITTED' });
    expect(await submitAction({ api: ok }, '/workspace/advice-requests', body, ADVICE_REQUEST_FIELDS, { failed: 'x' })).toMatchObject({ ok: true });
    expect(ok).toHaveBeenCalledWith('/workspace/advice-requests', { method: 'POST', body: { employeeId: 'E1004', questionCodes: ['FIT_FOR_ROLE'], context: 'ctx' } });

    const api = vi.fn().mockRejectedValue(new ApiError(422, { code: 'too_long', field: 'context', title: 'Context too long' }));
    const r = await submitAction({ api }, '/workspace/advice-requests', body, ADVICE_REQUEST_FIELDS, { failed: 'The advice request was not sent' });
    expect(r).toMatchObject({ ok: false, fieldErrors: { context: 'Please check this field.' }, message: 'Context too long' });
  });
});
