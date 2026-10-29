// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { toMe } from '../auth/me';
import { meProvider, referral } from '../test/fixtures';
import { mockApi, reply } from '../test/mockApi';
import { ReferralDetail } from './ReferralDetail';

afterEach(cleanup);

const me = toMe(meProvider);
const session = { authenticated: true, roles: ['PARTNER_PROVIDER'] };
const renderDetail = (routes: Record<string, unknown>, id = 'r1') => {
  const api = mockApi(routes);
  const onSessionExpired = vi.fn();
  render(<ReferralDetail client={api.client} session={session} me={me} onSessionExpired={onSessionExpired} referralId={id} />);
  return { api, onSessionExpired };
};

describe('referral detail', () => {
  it('shows patient, request, data set, downloads and confidentiality hint', async () => {
    renderDetail({ 'GET /partner/referrals': [referral()] });
    expect(await screen.findByRole('heading', { level: 1, name: 'Referral RFL-2026-000001' })).toBeTruthy();
    expect(screen.getByText('Rami Ramp')).toBeTruthy();
    expect(screen.getByText('Lower back strain after lifting baggage')).toBeTruthy();
    expect(screen.getByText('Lumbar spine')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Download Referral letter (PDF)' }).getAttribute('href')).toBe('/bff/api/documents/doc-letter/content');
    expect(screen.getByRole('link', { name: 'Download Physio intake form' }).getAttribute('href')).toBe('/bff/api/documents/doc-att/content');
    expect(screen.getByLabelText('Confidentiality').textContent).toContain('CITYHOSP');
    expect(screen.getByText('Urgent')).toBeTruthy();
  });

  it('says "not found or not shared" for a referral of another organisation', async () => {
    renderDetail({ 'GET /partner/referrals': [referral()] }, 'foreign');
    expect(await screen.findByText('Not found or not shared with your organisation.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Accept referral' })).toBeNull();
  });

  it('accepts a new referral', async () => {
    let status = 'SENT';
    const { api } = renderDetail({
      'GET /partner/referrals': () => [referral({ status })],
      'POST /partner/referrals/r1/accept': () => {
        status = 'ACCEPTED';
        return { id: 'r1', status };
      },
    });
    await userEvent.click(await screen.findByRole('button', { name: 'Accept referral' }));
    expect(await screen.findByText('Referral accepted.')).toBeTruthy();
    expect(api.find('POST', '/partner/referrals/r1/accept')).toHaveLength(1);
    expect(await screen.findByRole('heading', { name: 'Set appointment' })).toBeTruthy();
  });

  it('requires a reason to decline and posts it', async () => {
    const { api } = renderDetail({ 'GET /partner/referrals': [referral()], 'POST /partner/referrals/r1/decline': { id: 'r1' } });
    await userEvent.click(await screen.findByRole('button', { name: 'Decline referral' }));
    await userEvent.click(screen.getByRole('button', { name: 'Decline' }));
    const reason = screen.getByLabelText(/Reason for declining/);
    expect(reason.getAttribute('aria-invalid')).toBe('true');
    expect(api.find('POST', '/partner/referrals/r1/decline')).toHaveLength(0);
    await userEvent.type(reason, 'No capacity this month');
    await userEvent.click(screen.getByRole('button', { name: 'Decline' }));
    expect(await screen.findByText('Referral declined.')).toBeTruthy();
    expect(api.find('POST', '/partner/referrals/r1/decline')[0]!.body).toEqual({ reason: 'No capacity this month' });
  });

  it('highlights the field named by 422 clinical_content_not_allowed on the appointment', async () => {
    const { api } = renderDetail({
      'GET /partner/referrals': [referral({ status: 'ACCEPTED' })],
      'POST /partner/referrals/r1/appointment': reply(422, { status: 422, code: 'clinical_content_not_allowed', field: 'instructions', detail: 'ECHO' }),
    });
    const card = (await screen.findByRole('heading', { name: 'Set appointment' })).closest('section')!;
    await userEvent.type(within(card as HTMLElement).getByLabelText(/Date and time/), '2099-11-03T09:30');
    await userEvent.type(within(card as HTMLElement).getByLabelText(/Instructions for the patient/), 'Bring your MRI for the herniated disc');
    await userEvent.click(within(card as HTMLElement).getByRole('button', { name: 'Save appointment' }));
    expect(await within(card as HTMLElement).findByText('Remove medical information from this field.')).toBeTruthy();
    expect(within(card as HTMLElement).getByLabelText(/Instructions for the patient/).getAttribute('aria-invalid')).toBe('true');
    expect(screen.queryByText(/ECHO/)).toBeNull();
    expect(api.find('POST', '/partner/referrals/r1/appointment')[0]!.body).toMatchObject({ appointmentAt: new Date(2099, 10, 3, 9, 30).toISOString() });
  });

  it('rejects a wrong file type before uploading the report', async () => {
    const { api } = renderDetail({ 'GET /partner/referrals': [referral({ status: 'SEEN' })], 'POST /partner/referrals/r1/report': { id: 'doc' } });
    const card = (await screen.findByRole('heading', { name: 'Upload report' })).closest('section') as HTMLElement;
    const input = within(card).getByLabelText(/Report file/) as HTMLInputElement;
    await userEvent.upload(input, new File(['x'], 'report.docx', { type: 'application/msword' }), { applyAccept: false });
    await userEvent.click(within(card).getByRole('button', { name: 'Upload report' }));
    expect(within(card).getByText('Only PDF, JPEG or PNG files can be uploaded.')).toBeTruthy();
    expect(api.find('POST', '/partner/referrals/r1/report')).toHaveLength(0);

    await userEvent.upload(input, new File(['%PDF-1.7'], 'report.pdf', { type: 'application/pdf' }));
    await userEvent.type(within(card).getByLabelText(/Summary/), 'Assessment completed');
    await userEvent.click(within(card).getByRole('button', { name: 'Upload report' }));
    expect(await screen.findByText(/Report uploaded/)).toBeTruthy();
    const body = api.find('POST', '/partner/referrals/r1/report')[0]!.body as FormData;
    expect((body.get('file') as File).name).toBe('report.pdf');
    expect(body.get('summary')).toBe('Assessment completed');
  });

  it('uploads an invoice with amount, currency and service date', async () => {
    const { api } = renderDetail({ 'GET /partner/referrals': [referral({ status: 'REPORT_RECEIVED' })], 'POST /partner/referrals/r1/invoice': { id: 'inv1' } });
    const card = (await screen.findByRole('heading', { name: 'Upload invoice' })).closest('section') as HTMLElement;
    await userEvent.upload(within(card).getByLabelText(/Invoice file/), new File(['png'], 'invoice.png', { type: 'image/png' }));
    await userEvent.type(within(card).getByLabelText(/Invoice number/), 'INV-2026-77');
    await userEvent.type(within(card).getByLabelText(/^Amount/), '420.5');
    await userEvent.selectOptions(within(card).getByLabelText(/Currency/), 'AED');
    await userEvent.type(within(card).getByLabelText(/Service date/), '2026-10-20');
    await userEvent.click(within(card).getByRole('button', { name: 'Upload invoice' }));
    expect(await screen.findByText('Invoice uploaded.')).toBeTruthy();
    const body = api.find('POST', '/partner/referrals/r1/invoice')[0]!.body as FormData;
    expect([body.get('invoiceNumber'), body.get('amount'), body.get('currency'), body.get('serviceDate')]).toEqual(['INV-2026-77', '420.50', 'AED', '2026-10-20']);
  });
});
