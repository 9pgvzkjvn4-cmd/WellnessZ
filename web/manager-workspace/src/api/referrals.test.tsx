import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionExpiredError } from '../auth/AuthClient';
import { EmployeeResults } from '../components/EmployeeSearch';
import { ManagerReferralView, type ManagerReferralFormProps } from '../screens/ManagerReferral';
import { findAll, findOne } from '../test/tree';
import {
  buildManagerReferral,
  CLINICAL_CONTENT_MESSAGE,
  emptyManagerForm,
  MANAGER_FIELDS,
  problemFieldKey,
  submitReferral,
  toEmployeeLookups,
  validateManagerReferral,
  type ManagerReferralForm,
} from './referrals';

const erin = { employeeId: 'E1001', displayName: 'Erin Employee', departmentCode: 'CATERING', jobCode: 'CATERING_ASSOC' };
const complete: ManagerReferralForm = {
  employee: erin,
  dutiesAffected: ' Cannot lift trolleys ',
  startDate: '2026-10-20',
  safetyConcern: 'yes',
  safetyConcernDetail: 'Drives airside vehicles',
  adjustmentsTried: '',
  questionsForOh: 'Can she return to full duties?',
};
const clinical422 = (field?: string) =>
  new ApiError(422, { title: 'Unprocessable Entity', code: 'clinical_content_not_allowed', field, detail: 'echoed text' });

const view = (over: Partial<ManagerReferralFormProps> = {}) =>
  renderToStaticMarkup(
    <ManagerReferralView
      form={complete}
      errors={{}}
      submitting={false}
      onChange={vi.fn()}
      onSubmit={vi.fn()}
      employeeSlot={<div id="slot" />}
      {...over}
    />,
  );

describe('manager referral – 422 clinical content', () => {
  it('maps code=clinical_content_not_allowed + field to a highlighted field', async () => {
    const api = vi.fn().mockRejectedValue(clinical422('dutiesAffected'));
    const r = await submitReferral({ api }, '/intake/referrals/manager', buildManagerReferral(complete), MANAGER_FIELDS);
    expect(r).toMatchObject({ ok: false, fieldErrors: { dutiesAffected: CLINICAL_CONTENT_MESSAGE } });
    expect(r.ok === false && r.message).toMatch(/medical information/);
    expect(JSON.stringify(r)).not.toContain('echoed text'); // problem detail is never shown

    const html = view({ errors: r.ok ? {} : r.fieldErrors, message: r.ok ? undefined : r.message });
    expect(html).toMatch(/<textarea id="mr-dutiesAffected"[^>]*aria-invalid="true" aria-describedby="mr-dutiesAffected-hint mr-dutiesAffected-error"/);
    expect(html).toContain('<p id="mr-dutiesAffected-error" class="field-error">');
    expect(html).toMatch(/<div class="field field-invalid"><label for="mr-dutiesAffected">/);
    expect(html).not.toMatch(/<textarea id="mr-questionsForOh"[^>]*aria-invalid/);
    expect(html).toContain('role="alert"');
  });

  it('accepts body./[n] field spellings and ignores unknown fields', () => {
    expect(problemFieldKey('body.questionsForOh', MANAGER_FIELDS)).toBe('questionsForOh');
    expect(problemFieldKey('adviceQuestions[1]', ['adviceQuestions'])).toBe('adviceQuestions.1');
    expect(problemFieldKey('adviceQuestions.0', ['adviceQuestions'], () => 3)).toBe('adviceQuestions.3');
    expect(problemFieldKey('diagnosis', MANAGER_FIELDS)).toBeUndefined();
    expect(problemFieldKey(undefined, MANAGER_FIELDS)).toBeUndefined();
  });

  it('shows a general message when the problem names no (known) field', async () => {
    const r = await submitReferral({ api: vi.fn().mockRejectedValue(clinical422()) }, '/intake/referrals/manager', buildManagerReferral(complete), MANAGER_FIELDS);
    expect(r).toMatchObject({ ok: false, fieldErrors: {} });
    expect(r.ok === false && r.message).toMatch(/remove diagnoses or medical opinions/);
  });

  it('returns only the case reference on success and flags lost sessions', async () => {
    const api = vi.fn().mockResolvedValue({ caseRef: 'CAT-2026-000042', diagnosis: 'never' });
    const r = await submitReferral({ api }, '/intake/referrals/manager', buildManagerReferral(complete), MANAGER_FIELDS);
    expect(r).toEqual({ ok: true, caseRef: 'CAT-2026-000042' });
    expect(api).toHaveBeenCalledWith('/intake/referrals/manager', { method: 'POST', body: expect.objectContaining({ employeeId: 'E1001' }) });
    const lost = await submitReferral({ api: vi.fn().mockRejectedValue(new SessionExpiredError()) }, '/intake/referrals/manager', buildManagerReferral(complete), MANAGER_FIELDS);
    expect(lost).toMatchObject({ ok: false, sessionExpired: true });
  });
});

describe('manager referral form', () => {
  it('shows the inline guidance and only work-impact fields', () => {
    const html = view();
    expect(html).toContain('Do not include diagnoses or medical opinions');
    for (const id of ['mr-dutiesAffected', 'mr-startDate', 'mr-safetyConcern', 'mr-safetyConcernDetail', 'mr-adjustmentsTried', 'mr-questionsForOh']) {
      expect(html).toContain(`id="${id}"`);
    }
    expect(html).not.toMatch(/id="mr-(diagnosis|symptoms|clinical)/);
  });

  it('hides the safety detail unless a safety concern is reported', () => {
    expect(view({ form: { ...complete, safetyConcern: 'no' } })).not.toContain('id="mr-safetyConcernDetail"');
  });

  it('validates required fields and the 1000-character limit', () => {
    expect(Object.keys(validateManagerReferral(emptyManagerForm())).sort()).toEqual(['dutiesAffected', 'employeeId', 'safetyConcern', 'startDate']);
    expect(validateManagerReferral({ ...complete, safetyConcernDetail: ' ' })).toEqual({ safetyConcernDetail: 'Please describe the safety concern.' });
    expect(validateManagerReferral({ ...complete, adjustmentsTried: 'x'.repeat(1001) })).toEqual({ adjustmentsTried: 'Please use at most 1000 characters.' });
    expect(validateManagerReferral(complete)).toEqual({});
  });

  it('builds the ManagerReferral body', () => {
    expect(buildManagerReferral(complete)).toEqual({
      employeeId: 'E1001',
      dutiesAffected: 'Cannot lift trolleys',
      startDate: '2026-10-20',
      safetyConcern: true,
      safetyConcernDetail: 'Drives airside vehicles',
      questionsForOh: 'Can she return to full duties?',
    });
    expect(buildManagerReferral({ ...complete, safetyConcern: 'no' })).not.toHaveProperty('safetyConcernDetail');
  });

  it('dispatches the yes/no safety answer', () => {
    const onChange = vi.fn();
    const p = { form: emptyManagerForm(), errors: {}, submitting: false, onChange, onSubmit: vi.fn(), employeeSlot: null };
    const radios = findAll(ManagerReferralView(p), (e) => e.type === 'input' && e.props.name === 'mr-safetyConcern');
    radios[0]!.props.onChange();
    expect(onChange).toHaveBeenCalledWith('safetyConcern', 'yes');
  });
});

describe('employee lookup', () => {
  it('keeps only lookup fields and at most 20 rows', () => {
    const rows = toEmployeeLookups([{ ...erin, diagnosis: 'x', dateOfBirth: '1990-01-01' }, { displayName: 'no id' }, ...Array.from({ length: 30 }, (_, i) => ({ employeeId: `E${i}` }))]);
    expect(rows).toHaveLength(20);
    expect(Object.keys(rows[0]!).sort()).toEqual(['departmentCode', 'displayName', 'employeeId', 'jobCode']);
    expect(rows[1]).toMatchObject({ employeeId: 'E0', displayName: 'E0' });
  });

  it('selects an employee from the results', () => {
    const onSelect = vi.fn();
    findOne(EmployeeResults({ results: [erin], onSelect, listId: 'l' }), (e) => e.type === 'button').props.onClick();
    expect(onSelect).toHaveBeenCalledWith(erin);
  });
});
