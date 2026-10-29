// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { mockApi, reply, sessionFor } from '../../testing/mockApi';
import { ClaimCostsScreen } from './ClaimCosts';
import { ClaimDetailScreen } from './ClaimDetail';
import { ClaimList, ClaimReportsDashboard, NurseAssignment } from './ClaimScreens';
import { PhysicianLegalRequests } from './LegalRequests';

afterEach(cleanup);
const noop = () => {};

const claim = (over = {}) => ({
  id: 'cl1', claimRef: 'CLM-2026-000001', caseId: 'c1', caseRef: 'GND-2026-000001', employeeId: 'E1004', displayName: 'Rami Ramp', unit: 'GND', claimType: 'WORKERS_COMP', status: 'OPEN',
  payerCode: 'GULFTPA', payerName: 'Gulf TPA', lostTime: true, incidentDate: '2026-10-01', notifiedAt: '2026-10-02T09:00:00Z', litigationHold: false, privileged: false, openTasks: 1,
  reserves: { MEDICAL: { amount: '5000.00', currency: 'GBP' } }, paidTotal: { amount: '1250.00', currency: 'GBP' }, nurseCaseManager: 'nurse1', ...over,
});
const report = { id: 'rep1', ref: 'FROI-2026-000001', claimId: 'cl1', formCode: 'INSURER_FNOL', recipientCode: 'GULFTPA', status: 'DRAFT', dueAt: '2099-01-01T00:00:00Z', fields: { incidentDate: '2026-10-01', description: 'Strain' } };

describe('ClaimDetailScreen', () => {
  it('overview: money, status workflow with reason for litigation', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /claims/cl1': claim({ history: [{ status: 'NOTIFIED', at: '2026-10-02T09:00:00Z', by: 'claims1' }] }), 'POST /claims/cl1/status': claim({ status: 'LITIGATED' }) });
    render(<ClaimDetailScreen client={api.client} session={sessionFor('CLAIMS_COORDINATOR')} onSessionExpired={noop} claimId="cl1" />);
    expect(await screen.findByText('£5,000.00')).toBeTruthy();
    expect(screen.getByText('£1,250.00')).toBeTruthy();
    const tabs = within(screen.getByRole('navigation', { name: 'Claim sections' })).getAllByRole('link').map((a) => a.textContent);
    expect(tabs).toEqual(['Overview', 'Reports', 'Disclosures', 'Tasks & messages', 'Costs', 'Legal']);
    await user.click(screen.getByRole('button', { name: 'Move to litigated' }));
    const dialog = screen.getByRole('dialog');
    expect((within(dialog).getByRole('button', { name: 'Change status' }) as HTMLButtonElement).disabled).toBe(true);
    await user.type(within(dialog).getByLabelText(/Reason/), 'Claim form issued by solicitor');
    await user.click(within(dialog).getByRole('button', { name: 'Change status' }));
    await waitFor(() => expect(api.find('POST', '/claims/cl1/status')[0]?.body).toEqual({ status: 'LITIGATED', reason: 'Claim form issued by solicitor' }));
  });

  it('reports: countdown, edit fields, 422 clinical content on the description, then submit', async () => {
    const user = userEvent.setup();
    let puts = 0;
    const api = mockApi({
      'GET /claims/cl1': claim({ reports: [report] }),
      'GET /claims/report-forms': [{ code: 'INSURER_FNOL', jurisdiction: 'GB', recipientType: 'INSURER', recipientCode: 'GULFTPA', title: 'Insurer first notice of loss', deadlineHours: 72, trigger: 'ANY_CLAIM', fields: [{ code: 'incidentDate', label: 'Incident date', source: 'INCIDENT' }, { code: 'description', label: 'Description of injury', source: 'CASE' }] }],
      'PUT /claims/reports/rep1': () => (++puts === 1 ? reply(422, { status: 422, code: 'clinical_content_not_allowed', field: 'fields.description' }) : report),
      'POST /claims/reports/rep1/submit': { ...report, status: 'SUBMITTED' },
    });
    render(<ClaimDetailScreen client={api.client} session={sessionFor('CLAIMS_COORDINATOR')} onSessionExpired={noop} claimId="cl1" tab="reports" />);
    expect(await screen.findByRole('heading', { name: /FROI-2026-000001 – Insurer first notice of loss/ })).toBeTruthy();
    expect(screen.getByText(/ left$/)).toBeTruthy();
    const desc = screen.getByLabelText(/Description of injury/);
    await user.clear(desc);
    await user.type(desc, 'L4/L5 disc herniation');
    await user.click(screen.getByRole('button', { name: 'Submit report' }));
    await waitFor(() => expect(desc.getAttribute('aria-invalid')).toBe('true'));
    expect(screen.getByText(/nature of the injury only/)).toBeTruthy();
    await user.clear(desc);
    await user.type(desc, 'Back strain while lifting');
    await user.click(screen.getByRole('button', { name: 'Submit report' }));
    await waitFor(() => expect(api.find('POST', '/claims/reports/rep1/submit')[0]?.body).toEqual({ channel: 'API' }));
    expect(api.find('PUT', '/claims/reports/rep1')[1]?.body).toEqual({ fields: { incidentDate: '2026-10-01', description: 'Back strain while lifting' } });
  });

  it('disclosures: OH role builds a set from disclosable documents, redacts and sends with consent', async () => {
    const user = userEvent.setup();
    const set = { id: 's1', recipientCode: 'GULFTPA', purpose: 'CLAIM', status: 'DRAFT', documents: [{ documentId: 'd2', title: 'Physio report', type: 'PROVIDER_REPORT' }] };
    const api = mockApi({
      'GET /claims/cl1': claim(),
      'GET /claims/cl1/disclosure-sets': [set],
      'GET /documents': [
        { id: 'd1', title: 'Fitness certificate', type: 'FITNESS_CERTIFICATE', employeeId: 'E1004', status: 'SIGNED' },
        { id: 'd2', title: 'Physio report', type: 'PROVIDER_REPORT', employeeId: 'E1004', status: 'SIGNED' },
        { id: 'd3', title: 'Wound photo', type: 'PHOTO', employeeId: 'E1004', status: 'SIGNED' },
      ],
      'POST /claims/cl1/disclosure-sets': set,
      'POST /claims/disclosure-sets/s1/redactions': { ...set, documents: [{ documentId: 'd9', title: 'Physio report (redacted)', type: 'REDACTED_COPY', redactedFromId: 'd2' }] },
      'POST /claims/disclosure-sets/s1/send': { ...set, status: 'SENT' },
    });
    render(<ClaimDetailScreen client={api.client} session={sessionFor('NURSING_HEAD')} onSessionExpired={noop} claimId="cl1" tab="disclosures" />);
    await user.click(await screen.findByRole('button', { name: 'New disclosure set' }));
    expect(await screen.findByRole('checkbox', { name: /Physio report/ })).toBeTruthy();
    expect(screen.queryByRole('checkbox', { name: /Wound photo/ })).toBeNull();
    await user.click(screen.getByRole('checkbox', { name: /Fitness certificate/ }));
    await user.click(screen.getByRole('button', { name: 'Create draft set' }));
    await waitFor(() => expect(api.find('POST', '/claims/cl1/disclosure-sets')[0]?.body).toEqual({ recipientCode: 'GULFTPA', purpose: 'CLAIM', documentIds: ['d1'] }));

    await user.click(screen.getByRole('button', { name: 'Redact Physio report' }));
    await user.type(screen.getByLabelText(/Phrases to mask/), 'sertraline\nSertraline\nanxiety');
    await user.click(screen.getByRole('button', { name: 'Create redacted copy' }));
    await waitFor(() => expect(api.find('POST', '/claims/disclosure-sets/s1/redactions')[0]?.body).toEqual({ documentId: 'd2', phrases: ['sertraline', 'anxiety'] }));

    await user.type(screen.getByLabelText(/Insurer-sharing consent reference/), 'INS-77');
    await user.click(screen.getByRole('button', { name: 'Send' }));
    await waitFor(() => expect(api.find('POST', '/claims/disclosure-sets/s1/send')[0]?.body).toEqual({ consentId: 'INS-77' }));
  });

  it('legal counsel: hold and privilege, no reports / costs tabs', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /claims/cl1': claim({ status: 'LITIGATED', legalRequests: [] }), 'POST /claims/cl1/legal-hold': claim({ litigationHold: true }) });
    render(<ClaimDetailScreen client={api.client} session={sessionFor('LEGAL_COUNSEL')} onSessionExpired={noop} claimId="cl1" tab="legal" />);
    const tabs = within(await screen.findByRole('navigation', { name: 'Claim sections' })).getAllByRole('link').map((a) => a.textContent);
    expect(tabs).toEqual(['Overview', 'Legal']);
    await user.type(await screen.findByLabelText(/^Reason/), 'Pre-action letter received');
    await user.click(screen.getByRole('button', { name: 'Place hold' }));
    await waitFor(() => expect(api.find('POST', '/claims/cl1/legal-hold')[0]?.body).toEqual({ reason: 'Pre-action letter received' }));
    expect(screen.queryByText('£5,000.00')).toBeNull();
  });
});

describe('claims lists', () => {
  it('ClaimList filters by lost time', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /claims': [claim()], 'GET /claims?lostTime=true': [claim({ id: 'cl2', claimRef: 'CLM-2026-000002' })] });
    render(<ClaimList client={api.client} session={sessionFor('CLAIMS_COORDINATOR')} onSessionExpired={noop} />);
    expect((await screen.findByRole('link', { name: 'CLM-2026-000001' })).getAttribute('href')).toBe('#/claims/cl1');
    await user.selectOptions(screen.getByLabelText('Lost time'), 'true');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByRole('link', { name: 'CLM-2026-000002' })).toBeTruthy();
  });

  it('report deadline dashboard sorts by due date and shows overdue', async () => {
    const api = mockApi({
      'GET /claims/reports': [
        { id: 'a', ref: 'FROI-B', claimId: 'cl1', claimRef: 'CLM-1', formCode: 'GB_OSH_F2508', recipientCode: 'AUTH_OSH', status: 'DRAFT', dueAt: '2099-01-01T00:00:00Z' },
        { id: 'b', ref: 'FROI-A', claimId: 'cl1', claimRef: 'CLM-1', formCode: 'INSURER_FNOL', recipientCode: 'GULFTPA', status: 'LATE', dueAt: '2020-01-01T00:00:00Z' },
      ],
    });
    render(<ClaimReportsDashboard client={api.client} session={sessionFor('CLAIMS_COORDINATOR')} onSessionExpired={noop} />);
    const table = await screen.findByRole('table');
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows[0]?.textContent).toContain('FROI-A');
    expect(rows[0]?.textContent).toMatch(/Overdue by/);
  });

  it('nurse assignment KPI', async () => {
    const api = mockApi({ 'GET /claims/nurse-assignment': { lostTimeClaims: 10, assignedWithin48h: 9, pct: 90, unassigned: [{ claimRef: 'CLM-9', notifiedAt: '2026-10-28T09:00:00Z' }] } });
    render(<NurseAssignment client={api.client} session={sessionFor('NURSING_HEAD')} onSessionExpired={noop} />);
    expect(await screen.findByText('90.0 %')).toBeTruthy();
    expect(screen.getByText('CLM-9')).toBeTruthy();
  });

  it('physicians write a medico-legal report version', async () => {
    const user = userEvent.setup();
    const req = { id: 'l1', ref: 'LEG-2026-000001', type: 'MEDICO_LEGAL_REPORT', status: 'OPEN', questions: 'Is the injury work-related?', dueDate: '2099-01-01', reports: [] };
    const api = mockApi({ 'GET /legal-requests?status=OPEN': [req], 'POST /legal-requests/l1/report': { ...req, status: 'REPORT_READY', reports: [{ version: 1, documentId: 'd1', createdAt: '2026-10-29T10:00:00Z' }] } });
    render(<PhysicianLegalRequests client={api.client} session={sessionFor('OH_PHYSICIAN')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'Write report' }));
    await user.type(screen.getByLabelText(/Medical opinion/), 'On balance of probabilities, yes.');
    await user.click(screen.getByRole('button', { name: 'Sign report' }));
    expect(await screen.findByText('Medico-legal report v1 signed for LEG-2026-000001.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Close request' })).toBeNull();
  });
});

describe('ClaimCostsScreen (FINANCE)', () => {
  it('shows suppressed rows, budget variance and reconciles an ERP export', async () => {
    const user = userEvent.setup();
    const year = new Date().getFullYear();
    const api = mockApi({
      'GET /claims/costs/rollup': [
        { key: 'GND', label: 'Ground ops', cases: 12, MEDICAL: { amount: '1000.00', currency: 'GBP' }, total: { amount: '1500.00', currency: 'GBP' }, costPerEmployee: '12.50', suppressed: false },
        { key: 'CARGO', label: 'Cargo', suppressed: true },
      ],
      'GET /treatment/costs': [],
      [`GET /claims/costs/budget-vs-actual?year=${year}&groupBy=SUBSIDIARY`]: [{ unitCode: 'GND', label: 'Ground ops', budget: { amount: '10000.00', currency: 'GBP' }, actual: { amount: '11500.00', currency: 'GBP' }, variance: { amount: '1500.00', currency: 'GBP' }, variancePct: 15, trend: [{ month: '2026-09', actual: '900.00' }] }],
      'GET /claims/costs/exports': [{ id: 'x1', month: '2026-09', rows: 42, total: { amount: '10450.00', currency: 'GBP' }, checksum: 'abcdef0123456789', status: 'GENERATED' }],
      'GET /claims/reconciliation': [],
      'POST /claims/costs/exports/x1/reconcile': { id: 'x1', month: '2026-09', rows: 42, total: '10450.00', checksum: 'abc', status: 'RECONCILED' },
    });
    render(<ClaimCostsScreen client={api.client} session={sessionFor('FINANCE')} onSessionExpired={noop} />);
    expect(await screen.findByText('Suppressed – group too small to report')).toBeTruthy();
    expect(screen.getAllByText('£1,500.00').length).toBeGreaterThan(0);
    expect(await screen.findByText('+15.0 % over budget')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'CSV' }).getAttribute('href')).toBe('/bff/api/claims/costs/exports/x1/content');
    await user.click(screen.getByRole('button', { name: 'Reconcile export 2026-09' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText(/ERP total/), '10,450.00');
    await user.type(within(dialog).getByLabelText(/ERP reference/), 'SAP-123');
    await user.click(within(dialog).getByRole('button', { name: 'Reconcile' }));
    expect(await screen.findByText('Export 2026-09 reconciled.')).toBeTruthy();
    expect(api.find('POST', '/claims/costs/exports/x1/reconcile')[0]?.body).toEqual({ erpTotal: { amount: '10450.00', currency: 'GBP' }, erpReference: 'SAP-123' });
  });
});
