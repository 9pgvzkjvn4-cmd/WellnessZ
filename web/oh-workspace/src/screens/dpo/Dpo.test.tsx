// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { mockApi, sessionFor } from '../../testing/mockApi';
import { BreakGlassReport, BreakGlassReviews, DisclosureLog, RetentionScreen } from './DpoScreens';

afterEach(cleanup);

const event = { id: 'bg1', employeeId: 'E1004', grantedTo: 'physician1', reason: 'Unconscious in clinic, need history', grantedAt: '2026-10-27T08:00:00Z', expiresAt: '2026-10-27T12:00:00Z', review: 'PENDING', reviewDueAt: '2026-10-28T08:00:00Z' };

describe('BreakGlassReviews', () => {
  it('lists pending events (overdue review flagged) and records a review via the dialog', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /break-glass?status=PENDING': [event], 'GET /break-glass': [event, { ...event, id: 'bg0', review: 'APPROPRIATE', reviewedBy: 'dpo1' }], 'POST /break-glass/bg1/review': { ...event, review: 'APPROPRIATE' } });
    render(<BreakGlassReviews client={api.client} session={sessionFor('DPO')} onSessionExpired={() => {}} />);
    expect(await screen.findByText('Overdue')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Review access by physician1' }));
    const dialog = screen.getByRole('dialog', { name: 'Review emergency access by physician1' });
    expect(within(dialog).getByText(event.reason)).toBeTruthy();
    expect((within(dialog).getByRole('button', { name: 'Record review' }) as HTMLButtonElement).disabled).toBe(true);
    await user.click(within(dialog).getByRole('radio', { name: 'Appropriate' }));
    await user.type(within(dialog).getByLabelText(/Review note/), 'Clinical emergency confirmed with ward');
    await user.click(within(dialog).getByRole('button', { name: 'Record review' }));
    await waitFor(() => expect(api.find('POST', '/break-glass/bg1/review')[0]?.body).toEqual({ review: 'APPROPRIATE', note: 'Clinical emergency confirmed with ward' }));
    expect(await screen.findByText('Access by physician1 reviewed as appropriate.')).toBeTruthy();
    await user.click(screen.getByRole('checkbox', { name: 'Pending reviews only' }));
    expect(await screen.findByText('dpo1', { exact: false })).toBeTruthy();
  });
});

describe('BreakGlassReport', () => {
  it('shows the monthly report', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /break-glass/report': { month: '2026-10', total: 4, appropriate: 2, inappropriate: 1, pending: 1, overdueReviews: 1, byUser: [{ username: 'physician1', count: 3 }] } });
    render(<BreakGlassReport client={api.client} session={sessionFor('CMO')} onSessionExpired={() => {}} />);
    expect(await screen.findByText('physician1')).toBeTruthy();
    expect(api.calls[0]?.path).toMatch(/^\/break-glass\/report\?month=\d{4}-\d{2}$/);
    const month = screen.getByLabelText('Month');
    await user.clear(month);
    await user.type(month, '2026-09');
    await user.click(screen.getByRole('button', { name: 'Show' }));
    await waitFor(() => expect(api.calls.some((c) => c.path === '/break-glass/report?month=2026-09')).toBe(true));
  });
});

describe('DisclosureLog', () => {
  it('shows disclosures by employee', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /disclosures?employeeId=E1004': [{ at: '2026-10-28T11:00:00Z', recipientType: 'PARTNER', recipientName: 'Physio Ltd', documentTitle: 'Referral letter', purpose: 'Treatment', consentId: 'CONS-77', status: 'REVOKED', disclosedBy: 'nurse1' }] });
    render(<DisclosureLog client={api.client} session={sessionFor('DPO')} onSessionExpired={() => {}} />);
    expect(api.calls).toHaveLength(0);
    await user.type(screen.getByLabelText('Employee ID'), 'E1004');
    await user.click(screen.getByRole('button', { name: 'Show disclosures' }));
    const table = await screen.findByRole('table', { name: 'Disclosures for E1004' });
    expect(within(table).getByText('Partner – Physio Ltd')).toBeTruthy();
    expect(within(table).getByText('Revoked')).toBeTruthy();
  });
});

describe('RetentionScreen', () => {
  const schedule = [{ recordClass: 'OH_RECORD', retentionYears: 40, trigger: 'LAST_ENCOUNTER', legalBasis: 'Local law', approvedBy: 'dpo1', approvedAt: '2026-01-01T00:00:00Z' }];

  it('DPO edits the schedule, sees the due list and places a legal hold', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /documents/retention': schedule,
      'PUT /documents/retention/OH_RECORD': schedule[0],
      'GET /documents/retention/due': [{ documentId: 'd7', documentNumber: 'DOC-2001-000007', recordClass: 'OH_RECORD', employeeId: 'E0001', retentionEndsAt: '2026-01-01' }],
      'POST /documents/d7/legal-hold': {},
      'DELETE /documents/d7/legal-hold': {},
    });
    render(<RetentionScreen client={api.client} session={sessionFor('DPO')} onSessionExpired={() => {}} />);
    await user.click(await screen.findByRole('button', { name: 'Edit' }));
    const years = screen.getByLabelText(/Retention \(years\)/);
    await user.clear(years);
    await user.type(years, '50');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(api.find('PUT', '/documents/retention/OH_RECORD')[0]?.body).toMatchObject({ recordClass: 'OH_RECORD', retentionYears: 50 }));

    await user.click(await screen.findByRole('button', { name: 'Legal hold…' }));
    expect((screen.getByLabelText(/Document id/) as HTMLInputElement).value).toBe('d7');
    await user.type(screen.getByLabelText(/^Reason/), 'Litigation 2026-17');
    await user.click(screen.getByRole('button', { name: 'Place legal hold' }));
    await waitFor(() => expect(api.find('POST', '/documents/d7/legal-hold')[0]?.body).toEqual({ reason: 'Litigation 2026-17' }));
    await user.type(screen.getByLabelText(/^Reason/), 'Settled');
    await user.click(screen.getByRole('button', { name: 'Release legal hold' }));
    await waitFor(() => expect(api.find('DELETE', '/documents/d7/legal-hold')[0]?.body).toEqual({ reason: 'Settled' }));
  });

  it('OH_ADMIN reads the schedule only (no edit, no due list, no legal hold)', async () => {
    const api = mockApi({ 'GET /documents/retention': schedule });
    render(<RetentionScreen client={api.client} session={sessionFor('OH_ADMIN')} onSessionExpired={() => {}} />);
    expect(await screen.findByText('Local law')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Legal hold' })).toBeNull();
    expect(api.calls.map((c) => c.path)).toEqual(['/documents/retention']);
  });
});
