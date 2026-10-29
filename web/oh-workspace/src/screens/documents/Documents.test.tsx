// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { mockApi, sessionFor } from '../../testing/mockApi';
import { DocumentsPanel } from './DocumentsPanel';
import { ScanDocuments } from './ScanDocuments';

afterEach(cleanup);

const templates = [
  { code: 'FITNESS_CERTIFICATE', type: 'FITNESS_CERTIFICATE', title: 'Fitness certificate', mergeFields: ['outcome', 'validUntil'], audience: 'EMPLOYEE_MANAGER', containsClinical: false },
  { code: 'REFERRAL_LETTER', type: 'REFERRAL_LETTER', title: 'Referral letter', mergeFields: [{ key: 'recipient', label: 'Recipient' }, { key: 'clinicalSummary', label: 'Clinical summary' }], containsClinical: true },
];
const docs = [
  { id: 'd1', documentNumber: 'DOC-2026-000001', type: 'FITNESS_CERTIFICATE', templateCode: 'FITNESS_CERTIFICATE', title: 'Fitness certificate', employeeId: 'E1004', version: 1, status: 'DRAFT', createdBy: 'nurse1', shares: [] },
  { id: 'd2', documentNumber: 'DOC-2026-000002', type: 'REFERRAL_LETTER', templateCode: 'REFERRAL_LETTER', title: 'Referral letter', employeeId: 'E1004', version: 2, status: 'SIGNED', sha256: 'feed', signedBy: 'physician1', signedAt: '2026-10-28T10:00:00Z',
    shares: [{ id: 's1', target: 'PARTNER', partnerOrg: 'Physio Ltd', sharedAt: '2026-10-28T11:00:00Z' }] },
];
const base = () => ({ 'GET /documents': docs, 'GET /documents/templates': templates });
const renderPanel = (api: ReturnType<typeof mockApi>, roles = ['OH_CLINICIAN'], caseId?: string) =>
  render(<DocumentsPanel client={api.client} session={sessionFor(...roles)} onSessionExpired={() => {}} employeeId="E1004" caseId={caseId} canWrite refreshSummary={() => {}} />);

describe('DocumentsPanel', () => {
  it('lists documents (employee scope) with download links, sign, shares and revoke', async () => {
    const user = userEvent.setup();
    const api = mockApi({ ...base(), 'POST /documents/d1/sign': { ...docs[0], status: 'SIGNED', sha256: 'abc123' }, 'DELETE /documents/d2/shares/s1': undefined });
    renderPanel(api);
    expect(await screen.findByText('DOC-2026-000001')).toBeTruthy();
    expect(api.calls[0]?.path).toBe('/documents?employeeId=E1004');
    expect(screen.getAllByRole('link', { name: 'Download PDF' })[0]?.getAttribute('href')).toBe('/bff/api/documents/d1/content');
    await user.click(screen.getByRole('button', { name: 'Sign' }));
    expect(await screen.findByText(/Fitness certificate signed. SHA-256 abc123/)).toBeTruthy();
    await user.click(within(screen.getByRole('list', { name: 'Shares of Referral letter' })).getByRole('button', { name: 'Revoke' }));
    await waitFor(() => expect(api.find('DELETE', '/documents/d2/shares/s1')).toHaveLength(1));
  });

  it('case scope lists by caseId; case managers cannot sign', async () => {
    const api = mockApi(base());
    renderPanel(api, ['CASE_MANAGER'], 'c1');
    await screen.findByText('DOC-2026-000001');
    expect(api.calls[0]?.path).toBe('/documents?caseId=c1&employeeId=E1004');
    expect(screen.queryByRole('button', { name: 'Sign' })).toBeNull();
  });

  it('generates from a template with a dynamic merge-field form (required fields validated)', async () => {
    const user = userEvent.setup();
    const api = mockApi({ ...base(), 'POST /documents/generate': { ...docs[0], id: 'd3' } });
    renderPanel(api, ['OH_CLINICIAN'], 'c1');
    await user.click(await screen.findByRole('button', { name: 'Generate document' }));
    await user.selectOptions(await screen.findByLabelText(/^Template/), 'FITNESS_CERTIFICATE');
    expect(screen.getByText(/No clinical content – shareable/)).toBeTruthy();
    await user.click(screen.getAllByRole('button', { name: 'Generate document' })[1]!);
    const summary = screen.getByRole('alert', { name: 'There is a problem' });
    expect(within(summary).getAllByRole('link').map((a) => a.textContent)).toEqual(['Enter outcome.', 'Enter valid until.']);
    await user.type(screen.getByLabelText(/^Outcome/), 'Fit with restrictions');
    await user.type(screen.getByLabelText(/^Valid until/), '2026-12-01');
    await user.click(screen.getAllByRole('button', { name: 'Generate document' })[1]!);
    await waitFor(() => expect(api.find('POST', '/documents/generate')).toHaveLength(1));
    expect(api.find('POST', '/documents/generate')[0]?.body).toEqual({ templateCode: 'FITNESS_CERTIFICATE', employeeId: 'E1004', caseId: 'c1', fields: { outcome: 'Fit with restrictions', validUntil: '2026-12-01' } });
  });

  it('share: clinical documents cannot go to MANAGER/HR; PARTNER requires a consent id', async () => {
    const user = userEvent.setup();
    const api = mockApi({ ...base(), 'POST /documents/d2/share': { id: 's2', target: 'PARTNER' } });
    renderPanel(api);
    await user.click(await screen.findByRole('button', { name: 'Share' }));
    const dialog = screen.getByRole('dialog', { name: 'Share Referral letter' });
    const target = within(dialog).getByLabelText(/Share with/);
    expect(within(target).getAllByRole('option').map((o) => o.textContent)).toEqual(['Employee', 'Partner']);
    await user.selectOptions(target, 'PARTNER');
    await user.type(within(dialog).getByLabelText(/Partner organisation/), 'Physio Ltd');
    await user.click(within(dialog).getByRole('button', { name: 'Share document' }));
    expect(within(dialog).getByRole('alert', { name: 'There is a problem' }).textContent).toMatch(/consent ID/);
    await user.type(within(dialog).getByLabelText(/Consent ID/), 'CONS-77');
    await user.click(within(dialog).getByRole('button', { name: 'Share document' }));
    await waitFor(() => expect(api.find('POST', '/documents/d2/share')[0]?.body).toEqual({ target: 'PARTNER', partnerOrg: 'Physio Ltd', consentId: 'CONS-77' }));
  });

  it('creates a new version with the template merge fields', async () => {
    const user = userEvent.setup();
    const api = mockApi({ ...base(), 'POST /documents/d2/new-version': { ...docs[1], version: 3, status: 'DRAFT' } });
    renderPanel(api);
    await user.click((await screen.findAllByRole('button', { name: 'New version' }))[1]!);
    const dialog = screen.getByRole('dialog', { name: 'New version of Referral letter' });
    await user.type(within(dialog).getByLabelText(/^Recipient/), 'Dr Hand');
    await user.type(within(dialog).getByLabelText(/^Clinical summary/), 'Updated summary');
    await user.click(within(dialog).getByRole('button', { name: 'Create new version' }));
    await waitFor(() => expect(api.find('POST', '/documents/d2/new-version')[0]?.body).toEqual({ fields: { recipient: 'Dr Hand', clinicalSummary: 'Updated summary' } }));
    expect(await screen.findByText('Version 3 created as a draft.')).toBeTruthy();
  });

  it('full-text search shows snippets', async () => {
    const user = userEvent.setup();
    const api = mockApi({ ...base(), 'GET /documents/search': [{ document: docs[1], snippet: 'MRI of the wrist' }] });
    renderPanel(api);
    await user.type(await screen.findByLabelText('Search document text'), 'wrist');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText(/MRI of the wrist/)).toBeTruthy();
    expect(api.find('GET', '/documents/search')[0]?.path).toBe('/documents/search?employeeId=E1004&q=wrist');
  });

  it('scan upload rejects other file types and sends multipart', async () => {
    const user = userEvent.setup({ applyAccept: false });
    const api = mockApi({ ...base(), 'POST /documents/scan': { ...docs[0], id: 'd9', type: 'EXTERNAL_REPORT', documentNumber: 'DOC-9' } });
    renderPanel(api, ['OH_CLINICIAN'], 'c1');
    await user.click(await screen.findByRole('button', { name: 'Scan / upload' }));
    await user.upload(screen.getByLabelText(/^File \(PDF/), new File(['x'], 'notes.docx', { type: 'application/msword' }));
    await user.click(screen.getByRole('button', { name: 'Upload' }));
    expect(screen.getByRole('alert', { name: 'There is a problem' }).textContent).toMatch(/PDF, JPEG or PNG/);
    await user.upload(screen.getByLabelText(/^File \(PDF/), new File(['%PDF'], 'mri.pdf', { type: 'application/pdf' }));
    await user.click(screen.getByRole('button', { name: 'Upload' }));
    await waitFor(() => expect(api.find('POST', '/documents/scan')).toHaveLength(1));
    const fd = api.find('POST', '/documents/scan')[0]?.body as FormData;
    expect(fd.get('employeeId')).toBe('E1004');
    expect(fd.get('caseId')).toBe('c1');
    expect(fd.get('documentType')).toBeNull();
    expect(await screen.findByText(/Uploaded as External report DOC-9/)).toBeTruthy();
  });
});

describe('ScanDocuments (reception)', () => {
  it('finds the employee then uploads', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /intake/employees/lookup': [{ employeeId: 'E1004', displayName: 'Rami Ramp' }], 'POST /documents/scan': { ...docs[0], id: 'd9', type: 'EXTERNAL_REPORT' } });
    render(<ScanDocuments client={api.client} session={sessionFor('CLINIC_RECEPTION')} onSessionExpired={() => {}} />);
    await user.type(screen.getByLabelText(/Employee \(name or employee ID\)/), 'Rami');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await user.click(await screen.findByRole('button', { name: 'Rami Ramp (E1004)' }));
    await user.upload(screen.getByLabelText(/^File \(PDF/), new File(['%PDF'], 'report.pdf', { type: 'application/pdf' }));
    await user.click(screen.getByRole('button', { name: 'Upload' }));
    await waitFor(() => expect((api.find('POST', '/documents/scan')[0]?.body as FormData).get('employeeId')).toBe('E1004'));
  });
});
