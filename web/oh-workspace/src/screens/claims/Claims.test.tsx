// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { mockApi, reply, sessionFor } from '../../testing/mockApi';
import { ClaimCostsScreen } from './ClaimCosts';
import { ClaimDetailScreen } from './ClaimDetail';
import { ClaimDisclosureReport, ClaimList, ClaimReportsDashboard, NurseAssignment } from './ClaimScreens';
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
      // the claim detail carries a summary only – the tab loads the full reports
      'GET /claims/cl1': claim({ reports: [{ id: 'rep1', ref: 'FROI-2026-000001', formCode: 'INSURER_FNOL', recipientCode: 'GULFTPA', status: 'DRAFT', dueAt: '2099-01-01T00:00:00Z' }] }),
      'GET /claims/cl1/reports': [report],
      'GET /claims/report-forms': [{ code: 'INSURER_FNOL', jurisdiction: 'GB', recipientType: 'INSURER', recipientCode: 'GULFTPA', title: 'Insurer first notice of loss', deadlineHours: 72, trigger: 'ANY_CLAIM', fields: [{ code: 'incidentDate', label: 'Incident date', source: 'INCIDENT' }, { code: 'description', label: 'Description of injury', source: 'CASE' }] }],
      'PUT /claims/reports/rep1': () => (++puts === 1 ? reply(422, { status: 422, code: 'clinical_content_not_allowed', field: 'fields.description' }) : report),
      'POST /claims/reports/rep1/submit': { ...report, status: 'SUBMITTED' },
    });
    render(<ClaimDetailScreen client={api.client} session={sessionFor('CLAIMS_COORDINATOR')} onSessionExpired={noop} claimId="cl1" tab="reports" />);
    expect(await screen.findByRole('heading', { name: /FROI-2026-000001 – Insurer first notice of loss/ })).toBeTruthy();
    expect(screen.getByText(/ left$/)).toBeTruthy();
    const desc = screen.getByLabelText(/Description of injury/);
    expect((desc as HTMLTextAreaElement).value).toBe('Strain');
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

  it('disclosures: OH role builds a set from the server candidates, redacts and sends under a picked consent', async () => {
    const user = userEvent.setup();
    const set = { id: 's1', claimId: 'cl1', recipientCode: 'GULFTPA', recipientName: 'Gulf TPA', purpose: 'CLAIM', status: 'DRAFT', documents: [{ documentId: 'd2', title: 'Physio report', type: 'PROVIDER_REPORT' }] };
    const api = mockApi({
      'GET /claims/cl1': claim(),
      'GET /claims/cl1/disclosure-sets': [set],
      'GET /claims/cl1/documents': [
        { documentId: 'd1', documentNumber: 'DOC-1', title: 'Fitness certificate', type: 'FITNESS_CERTIFICATE', createdAt: '2026-10-03T09:00:00Z', disclosable: true },
        { documentId: 'd2', title: 'Physio report', type: 'PROVIDER_REPORT', createdAt: '2026-10-04T09:00:00Z', disclosable: true },
        { documentId: 'd3', title: 'Wound photo', type: 'PHOTO', createdAt: '2026-10-05T09:00:00Z', disclosable: false, reason: 'photos are never disclosed' },
      ],
      'GET /claims/cl1/consents': [{ id: 'INS-77', purpose: 'INSURER_SHARING', grantedAt: '2026-10-02T08:00:00Z', scope: 'GULFTPA' }],
      'POST /claims/cl1/disclosure-sets': set,
      'POST /claims/disclosure-sets/s1/redactions': { ...set, documents: [{ documentId: 'd9', title: 'Physio report (redacted)', type: 'REDACTED_COPY', redactedFromId: 'd2' }] },
      'POST /claims/disclosure-sets/s1/send': { ...set, status: 'SENT' },
    });
    render(<ClaimDetailScreen client={api.client} session={sessionFor('NURSING_HEAD')} onSessionExpired={noop} claimId="cl1" tab="disclosures" />);
    await user.click(await screen.findByRole('button', { name: 'New disclosure set' }));
    expect(await screen.findByRole('checkbox', { name: /Physio report/ })).toBeTruthy();
    const photo = screen.getByRole('checkbox', { name: /Wound photo/ }) as HTMLInputElement;
    expect(photo.disabled).toBe(true);
    expect(screen.getByText(/not disclosable: photos are never disclosed/)).toBeTruthy();
    expect(screen.queryByLabelText(/Other document ids/)).toBeNull();
    await user.click(screen.getByRole('checkbox', { name: /Fitness certificate/ }));
    await user.click(screen.getByRole('button', { name: 'Create draft set' }));
    await waitFor(() => expect(api.find('POST', '/claims/cl1/disclosure-sets')[0]?.body).toEqual({ recipientCode: 'GULFTPA', purpose: 'CLAIM', documentIds: ['d1'] }));

    await user.click(screen.getByRole('button', { name: 'Redact Physio report' }));
    await user.type(screen.getByLabelText(/Phrases to mask/), 'sertraline\nSertraline\nanxiety');
    await user.click(screen.getByRole('button', { name: 'Create redacted copy' }));
    await waitFor(() => expect(api.find('POST', '/claims/disclosure-sets/s1/redactions')[0]?.body).toEqual({ documentId: 'd2', phrases: ['sertraline', 'anxiety'] }));

    await user.click(screen.getByRole('button', { name: 'Send set to GULFTPA' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByRole('textbox')).toBeNull();
    expect((within(dialog).getByRole('button', { name: 'Send' }) as HTMLButtonElement).disabled).toBe(true);
    const radio = await within(dialog).findByRole('radio', { name: /Insurer sharing/ });
    expect(radio.closest('label')?.textContent).toContain('GULFTPA');
    await user.click(radio);
    await user.click(within(dialog).getByRole('button', { name: 'Send' }));
    await waitFor(() => expect(api.find('POST', '/claims/disclosure-sets/s1/send')[0]?.body).toEqual({ consentId: 'INS-77' }));
    expect(await screen.findByText('Disclosure set sent to Gulf TPA and logged.')).toBeTruthy();
  });

  it('disclosures: claims coordinator chooser uses the claim documents; no consent → empty state, no free text', async () => {
    const user = userEvent.setup();
    const set = { id: 's1', recipientCode: 'GULFTPA', purpose: 'CLAIM', status: 'DRAFT', documents: [{ documentId: 'd1', title: 'Fitness certificate', type: 'FITNESS_CERTIFICATE' }] };
    const api = mockApi({
      'GET /claims/cl1': claim(),
      'GET /claims/cl1/disclosure-sets': [set],
      'GET /claims/cl1/documents': [
        { id: 'd1', title: 'Fitness certificate', type: 'FITNESS_CERTIFICATE', disclosable: true },
        { id: 'd2', title: 'Physio report', type: 'PROVIDER_REPORT', disclosable: false, reason: 'provider reports are disclosed by OH only' },
      ],
      'GET /claims/cl1/consents': [],
    });
    render(<ClaimDetailScreen client={api.client} session={sessionFor('CLAIMS_COORDINATOR')} onSessionExpired={noop} claimId="cl1" tab="disclosures" />);
    await user.click(await screen.findByRole('button', { name: 'New disclosure set' }));
    expect(((await screen.findByRole('checkbox', { name: /Physio report/ })) as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText(/provider reports are disclosed by OH only/)).toBeTruthy();
    expect((screen.getByRole('checkbox', { name: /Fitness certificate/ }) as HTMLInputElement).disabled).toBe(false);
    expect(api.find('GET', '/documents')).toHaveLength(0);
    await user.click(screen.getByRole('button', { name: 'Send set to GULFTPA' }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('No active consent – ask the employee to grant it in the employee app')).toBeTruthy();
    expect(within(dialog).queryByRole('textbox')).toBeNull();
    expect((within(dialog).getByRole('button', { name: 'Send' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('legal tab: claims coordinators see the request summaries from the claim', async () => {
    const api = mockApi({ 'GET /claims/cl1': claim({ legalRequests: [{ id: 'l1', ref: 'LEG-2026-000001', type: 'IME', status: 'OPEN', dueDate: '2099-01-01' }] }) });
    render(<ClaimDetailScreen client={api.client} session={sessionFor('CLAIMS_COORDINATOR')} onSessionExpired={noop} claimId="cl1" tab="legal" />);
    expect(await screen.findByRole('heading', { name: /LEG-2026-000001 – Ime/ })).toBeTruthy();
    expect(api.find('GET', '/legal-requests')).toHaveLength(0);
  });

  it('legal counsel: hold and privilege, no reports / costs tabs', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /claims/cl1': claim({ status: 'LITIGATED', legalRequests: [{ id: 'l1', ref: 'LEG-2026-000001', type: 'MEDICO_LEGAL_REPORT', status: 'REPORT_READY', dueDate: '2099-01-01' }] }),
      'GET /legal-requests': [
        { id: 'l1', ref: 'LEG-2026-000001', claimId: 'cl1', type: 'MEDICO_LEGAL_REPORT', status: 'REPORT_READY', questions: 'Is the injury work-related?', dueDate: '2099-01-01', reports: [{ version: 1, documentId: 'd5', createdAt: '2026-10-20T10:00:00Z' }] },
        { id: 'l2', ref: 'LEG-2026-000002', claimId: 'other', type: 'IME', status: 'OPEN', questions: 'Other claim', dueDate: '2099-01-01', reports: [] },
      ],
      'POST /claims/cl1/legal-hold': { claimId: 'cl1', claimRef: 'CLM-2026-000001', litigationHold: true, documentsAffected: 4, privileged: false },
    });
    render(<ClaimDetailScreen client={api.client} session={sessionFor('LEGAL_COUNSEL')} onSessionExpired={noop} claimId="cl1" tab="legal" />);
    const tabs = within(await screen.findByRole('navigation', { name: 'Claim sections' })).getAllByRole('link').map((a) => a.textContent);
    expect(tabs).toEqual(['Overview', 'Legal']);
    await user.type(await screen.findByLabelText(/^Reason/), 'Pre-action letter received');
    await user.click(screen.getByRole('button', { name: 'Place hold' }));
    await waitFor(() => expect(api.find('POST', '/claims/cl1/legal-hold')[0]?.body).toEqual({ reason: 'Pre-action letter received' }));
    expect(screen.queryByText('£5,000.00')).toBeNull();
    expect(await screen.findByText('Is the injury work-related?')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Report v1' })).toBeTruthy();
    expect(screen.queryByText('Other claim')).toBeNull();
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

  it('DPO disclosure report renders the disclosure-log rows', async () => {
    const api = mockApi({
      'GET /claims/disclosure-report': [
        { id: 'x1', at: '2026-10-20T10:00:00Z', employeeId: 'E1004', recipientType: 'INSURER', recipientName: 'Gulf TPA', resourceType: 'DOCUMENT', resourceId: 'd1', document: 'Fitness certificate', purpose: 'CLAIM', consentId: 'INS-77', by: 'claims1', status: 'ACTIVE' },
        { id: 'x2', at: '2026-10-19T10:00:00Z', employeeId: 'E1004', recipientType: 'AUTHORITY', recipientName: 'HSE', resourceType: 'DOCUMENT', resourceId: 'd2', document: 'F2508', purpose: 'LEGAL_OBLIGATION', by: 'claims1', status: 'REVOKED', revokedAt: '2026-10-21T10:00:00Z' },
      ],
    });
    render(<ClaimDisclosureReport client={api.client} session={sessionFor('DPO')} onSessionExpired={noop} />);
    const rows = within(await screen.findByRole('table')).getAllByRole('row').slice(1);
    expect(rows[0]?.textContent).toContain('Gulf TPA');
    expect(rows[0]?.textContent).toContain('Fitness certificate');
    expect(rows[0]?.textContent).toContain('INS-77');
    expect(rows[1]?.textContent).toContain('Legal obligation');
    expect(rows[1]?.textContent).toContain('Revoked');
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
