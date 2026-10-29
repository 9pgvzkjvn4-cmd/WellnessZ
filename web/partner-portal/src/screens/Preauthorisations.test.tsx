// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { toMe } from '../auth/me';
import { meAdjuster, preauth } from '../test/fixtures';
import { mockApi } from '../test/mockApi';
import { Preauthorisations } from './Preauthorisations';

afterEach(cleanup);

describe('pre-authorisation decisions', () => {
  it('lists pending requests and records a partial approval', async () => {
    const api = mockApi({ 'GET /partner/preauthorisations?status=SUBMITTED': [preauth()], 'POST /partner/preauthorisations/pa1/decision': { id: 'pa1' } });
    render(<Preauthorisations client={api.client} session={{ authenticated: true, roles: ['TPA_ADJUSTER'] }} me={toMe(meAdjuster)} onSessionExpired={vi.fn()} />);
    expect(await screen.findByText(/PA-2026-000001/)).toBeTruthy();
    expect(screen.getByText('£850.00')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Request document (PDF)' }).getAttribute('href')).toBe('/bff/api/documents/doc-pa/content');
    expect(document.body.textContent).not.toContain('SECRET');

    await userEvent.click(screen.getByRole('radio', { name: 'Partially approve' }));
    await userEvent.type(screen.getByLabelText(/Approved amount/), '900');
    await userEvent.click(screen.getByRole('button', { name: 'Record decision' }));
    expect(screen.getByText('The partially approved amount must be lower than the estimate.')).toBeTruthy();

    await userEvent.clear(screen.getByLabelText(/Approved amount/));
    await userEvent.type(screen.getByLabelText(/Approved amount/), '600');
    await userEvent.type(screen.getByLabelText(/Approval reference/), 'GT-2026-55');
    await userEvent.type(screen.getByLabelText(/Valid until/), '2099-12-31');
    await userEvent.click(screen.getByRole('button', { name: 'Record decision' }));
    expect(await screen.findByText(/Decision recorded/)).toBeTruthy();
    expect(api.find('POST', '/partner/preauthorisations/pa1/decision')[0]!.body).toEqual({
      status: 'PARTIALLY_APPROVED',
      approvalRef: 'GT-2026-55',
      approvedAmount: { amount: '600.00', currency: 'GBP' },
      validUntil: '2099-12-31',
    });
  });
});
