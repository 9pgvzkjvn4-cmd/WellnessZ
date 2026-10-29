import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { submitAction } from '../api/submit';
import {
  emptyComplianceFilter,
  emptyPreEmploymentForm,
  normalisePreEmployment,
  PRE_EMPLOYMENT_FIELDS,
  PRE_EMPLOYMENT_PATH,
  toComplianceRows,
  toOnboardingRows,
  validatePreEmployment,
} from '../api/surveillance';
import { ApiError } from '../auth/AuthClient';
import { findOne } from '../test/tree';
import { ComplianceView } from './Compliance';
import { OnboardingTable, PreEmploymentFormView, type PreEmploymentFormViewProps } from './PreEmployment';

describe('team compliance view', () => {
  const rows = toComplianceRows([
    { employeeId: 'E1007', displayName: 'Sam Screener', programmeTitle: 'Night work', status: 'OVERDUE', nextDueOn: '2026-09-01', escalated: true, results: 'SECRET' },
    { employeeId: 'E1001', displayName: 'Alice', programmeTitle: 'Noise', status: 'CURRENT', nextDueOn: '2027-05-01', escalated: false },
  ]);

  it('shows programme, status, next due and escalation with a summary', () => {
    const html = renderToStaticMarkup(<ComplianceView rows={rows} filter={emptyComplianceFilter()} onFilter={vi.fn()} />);
    for (const s of ['Sam Screener', 'Night work', 'Overdue', 'Current', 'Invitations ignored – please follow up', '50%', 'badge-compliance-overdue']) expect(html).toContain(s);
    expect(html).not.toContain('SECRET');
  });

  it('wires the filters', () => {
    const onFilter = vi.fn();
    const tree = ComplianceView({ rows, filter: emptyComplianceFilter(), onFilter });
    findOne(tree, (e) => e.props.id === 'co-status').props.onChange({ target: { value: 'DUE' } });
    expect(onFilter).toHaveBeenLastCalledWith({ q: '', status: 'DUE', escalatedOnly: false });
    findOne(tree, (e) => e.props.id === 'co-escalated').props.onChange({ target: { checked: true } });
    expect(onFilter).toHaveBeenLastCalledWith({ q: '', status: '', escalatedOnly: true });
  });
});

describe('pre-employment (HR)', () => {
  it('lists clearance status and flags pending starts at risk', () => {
    const rows = toOnboardingRows([
      { id: 'p1', offerRef: 'OFF-1', candidateName: 'Dana Doe', jobTitle: 'Cleaner', proposedStart: '2026-11-02', hrClearance: 'PENDING' },
      { id: 'p2', offerRef: 'OFF-2', candidateName: 'Carl', jobTitle: 'Screener', proposedStart: '2026-12-01', hrClearance: 'UNFIT' },
    ]);
    const html = renderToStaticMarkup(<OnboardingTable rows={rows} today="2026-10-29" />);
    for (const s of ['Dana Doe', 'OFF-1', 'Cleaner', 'Pending', 'Start date at risk', 'Not fit for the role', 'badge-clearance-unfit']) expect(html).toContain(s);
    expect(html.match(/Start date at risk/g)).toHaveLength(1);
  });

  const view = (over: Partial<PreEmploymentFormViewProps> = {}): PreEmploymentFormViewProps => ({
    form: emptyPreEmploymentForm(),
    errors: {},
    submitting: false,
    onPatch: vi.fn(),
    onSubmit: vi.fn(),
    ...over,
  });

  it('renders every field of the manual trigger with errors wired', () => {
    const errors = validatePreEmployment(emptyPreEmploymentForm(), '2026-10-29');
    const html = renderToStaticMarkup(<PreEmploymentFormView {...view({ errors, message: 'Please check the highlighted fields.' })} />);
    for (const k of PRE_EMPLOYMENT_FIELDS) expect(html).toMatch(new RegExp(`<input id="pe-${k}"[^>]*aria-invalid="true"`));
    expect(html).toContain('type="email"');
    expect(html).toContain('type="date"');
  });

  it('patches the changed field', () => {
    const onPatch = vi.fn();
    const tree = PreEmploymentFormView(view({ onPatch }));
    findOne(tree, (e) => e.props.id === 'pe-jobCode').props.onChange({ target: { value: 'CLEANER' } });
    expect(onPatch).toHaveBeenLastCalledWith({ jobCode: 'CLEANER' });
  });

  it('posts to /surveillance/pre-employment and maps a field 422', async () => {
    const body = normalisePreEmployment({
      offerRef: 'OFF-9',
      candidateId: 'C9001',
      candidateName: 'Carl',
      email: 'c@example.com',
      jobCode: 'cleaner',
      subsidiaryCode: 'GRP',
      departmentCode: 'FAC',
      locationCode: 'HQ',
      proposedStart: '2026-11-20',
    });
    const ok = vi.fn().mockResolvedValue({ id: 'p9' });
    expect(await submitAction({ api: ok }, PRE_EMPLOYMENT_PATH, body, PRE_EMPLOYMENT_FIELDS, { failed: 'x' })).toMatchObject({ ok: true });
    expect(ok).toHaveBeenCalledWith('/surveillance/pre-employment', { method: 'POST', body: expect.objectContaining({ jobCode: 'CLEANER', offerRef: 'OFF-9' }) });

    const api = vi.fn().mockRejectedValue(new ApiError(422, { code: 'unknown_job', field: 'jobCode', title: 'Unknown job code' }));
    const r = await submitAction({ api }, PRE_EMPLOYMENT_PATH, body, PRE_EMPLOYMENT_FIELDS, { failed: 'Not started' });
    expect(r).toMatchObject({ ok: false, fieldErrors: { jobCode: 'Please check this field.' }, message: 'Unknown job code' });
  });
});
