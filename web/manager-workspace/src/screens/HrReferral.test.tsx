import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import {
  buildHrReferral,
  CLINICAL_CONTENT_MESSAGE,
  emptyHrForm,
  HR_FIELDS,
  sentQuestionRow,
  submitReferral,
  validateHrReferral,
  type HrReferralForm,
} from '../api/referrals';
import { ApiError } from '../auth/AuthClient';
import { canHrRefer, canManagerRefer, referralNav, resolveReferralRoute, workspaceNav } from '../auth/roles';
import { findAll } from '../test/tree';
import { AdviceQuestionsEditor, HrReferralView } from './HrReferral';

const form: HrReferralForm = {
  employee: { employeeId: 'E1005', displayName: 'Olivia Office' },
  absenceSummary: '3 episodes since June, currently absent 4 weeks',
  policyStage: 'STAGE_1',
  absenceDaysLast12Months: '34',
  adviceQuestions: ['Is a phased return advisable?', '', 'Any adjustments?'],
};

describe('HR referral role gating', () => {
  it('offers the HR form to HR only', () => {
    expect(canHrRefer(['HR'])).toBe(true);
    expect(canHrRefer(['ROLE_hr'])).toBe(true);
    expect(canHrRefer(['MANAGER'])).toBe(false);
    expect(canHrRefer(['OH_CLINICIAN', 'CASE_MANAGER'])).toBe(false);
    expect(canHrRefer(undefined)).toBe(false);
    expect(canManagerRefer(['MANAGER'])).toBe(true);
    expect(canManagerRefer(['HR'])).toBe(false);
  });

  it('shows navigation entries per role', () => {
    expect(referralNav(['HR']).map((n) => n.path)).toEqual(['/refer/hr']);
    expect(referralNav(['MANAGER']).map((n) => n.path)).toEqual(['/refer']);
    expect(referralNav(['MANAGER', 'HR']).map((n) => n.path)).toEqual(['/refer', '/refer/hr']);
    expect(referralNav(['OH_CLINICIAN'])).toEqual([]);
    expect(workspaceNav(['HR']).map((n) => n.label)).toEqual(['Team cases', 'HR referral', 'Team restrictions', 'Return to work', 'Documents', 'Analytics']);
  });

  it('blocks direct navigation to a referral route without the role', () => {
    expect(resolveReferralRoute('/refer/hr', ['HR'])).toBe('hr');
    expect(resolveReferralRoute('/refer/hr', ['MANAGER'])).toBe('forbidden');
    expect(resolveReferralRoute('/refer', ['HR'])).toBe('forbidden');
    expect(resolveReferralRoute('/refer', ['MANAGER'])).toBe('manager');
    expect(resolveReferralRoute('/analytics', ['HR'])).toBeUndefined();
  });
});

describe('HR referral form', () => {
  it('renders HrReferral fields with guidance', () => {
    const html = renderToStaticMarkup(
      <HrReferralView form={form} errors={{}} submitting={false} onChange={vi.fn()} onSubmit={vi.fn()} employeeSlot={null} />,
    );
    for (const id of ['hr-absenceSummary', 'hr-policyStage', 'hr-absenceDaysLast12Months', 'hr-adviceQuestions-0', 'hr-adviceQuestions-2']) {
      expect(html).toContain(`id="${id}"`);
    }
    expect(html).toContain('<option value="STAGE_2">Stage 2</option>');
    expect(html).toContain('Do not include diagnoses or medical opinions');
  });

  it('edits the advice question list (add, change, remove)', () => {
    const onChange = vi.fn();
    const tree = AdviceQuestionsEditor({ questions: ['A', 'B'], errors: {}, onChange });
    const buttons = findAll(tree, (e) => e.type === 'button');
    buttons.find((b) => b.props.children === 'Add question')!.props.onClick();
    expect(onChange).toHaveBeenLastCalledWith(['A', 'B', '']);
    buttons.find((b) => b.props['aria-label'] === 'Remove question 1')!.props.onClick();
    expect(onChange).toHaveBeenLastCalledWith(['B']);
    findAll(tree, (e) => e.type === 'input')[1]!.props.onChange({ target: { value: 'B2' } });
    expect(onChange).toHaveBeenLastCalledWith(['A', 'B2']);
    // a single question cannot be removed; at most 10 questions
    const single = findAll(AdviceQuestionsEditor({ questions: ['A'], errors: {}, onChange }), (e) => e.type === 'button');
    expect(single.find((b) => b.props.children === 'Remove')!.props.disabled).toBe(true);
    const full = findAll(AdviceQuestionsEditor({ questions: Array(10).fill('q'), errors: {}, onChange }), (e) => e.type === 'button');
    expect(full.find((b) => b.props.children === 'Add question')!.props.disabled).toBe(true);
  });

  it('validates and builds the HrReferral body (blank questions dropped)', () => {
    expect(Object.keys(validateHrReferral(emptyHrForm())).sort()).toEqual(
      ['absenceDaysLast12Months', 'absenceSummary', 'adviceQuestions', 'employeeId', 'policyStage'].sort(),
    );
    expect(validateHrReferral({ ...form, absenceDaysLast12Months: '400' })).toHaveProperty('absenceDaysLast12Months');
    expect(validateHrReferral({ ...form, absenceDaysLast12Months: '3.5' })).toHaveProperty('absenceDaysLast12Months');
    expect(validateHrReferral(form)).toEqual({});
    expect(buildHrReferral(form)).toEqual({
      employeeId: 'E1005',
      absenceSummary: '3 episodes since June, currently absent 4 weeks',
      policyStage: 'STAGE_1',
      absenceDaysLast12Months: 34,
      adviceQuestions: ['Is a phased return advisable?', 'Any adjustments?'],
    });
  });

  it('highlights the offending advice question from a 422 (index of the sent list)', async () => {
    const api = vi.fn().mockRejectedValue(new ApiError(422, { code: 'clinical_content_not_allowed', field: 'adviceQuestions[1]' }));
    const r = await submitReferral({ api }, '/intake/referrals/hr', buildHrReferral(form), HR_FIELDS, sentQuestionRow(form.adviceQuestions));
    expect(r).toMatchObject({ ok: false, fieldErrors: { 'adviceQuestions.2': CLINICAL_CONTENT_MESSAGE } });
    const html = renderToStaticMarkup(
      <AdviceQuestionsEditor questions={form.adviceQuestions} errors={r.ok ? {} : r.fieldErrors} onChange={vi.fn()} />,
    );
    expect(html).toMatch(/<input id="hr-adviceQuestions-2"[^>]*aria-invalid="true"/);
    expect(html).not.toMatch(/<input id="hr-adviceQuestions-0"[^>]*aria-invalid/);
  });
});
