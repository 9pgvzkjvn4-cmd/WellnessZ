// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { decision } from '../../testing/fixtures';
import { mockApi, sessionFor } from '../../testing/mockApi';
import { CdsStatistics } from '../CdsStatistics';
import { ActiveRestrictions, CrewDashboard, RosterReconciliation, RtwOverdue, SignoffQueue } from './WorkScreens';

afterEach(cleanup);

const restriction = (over = {}) => ({ id: 'ri1', employeeId: 'E1004', employeeName: 'Rami Ramp', caseId: 'c1', caseRef: 'GND-2026-000001', code: 'LIFT_MAX_KG', label: 'No lifting above 10 kg', validFrom: '2026-10-01', validUntil: '2026-11-02', status: 'EXPIRING', ...over });

describe('SignoffQueue', () => {
  it('AME signs off licensed decisions; PERMANENTLY_UNFIT shows "CMO only"', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /fitness-decisions/awaiting-signoff': [decision({ id: 'a1', status: 'AWAITING_SIGNOFF', standardCode: 'CAA_CLASS1', standardTitle: 'Class 1', caseRef: 'FLT-1' }), decision({ id: 'a2', status: 'AWAITING_SIGNOFF', outcome: 'PERMANENTLY_UNFIT', caseRef: 'GND-2' })],
      'POST /fitness-decisions/a1/signoff': decision(),
    });
    render(<SignoffQueue client={api.client} session={sessionFor('AME')} onSessionExpired={() => {}} />);
    expect(await screen.findByText('CMO only')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Sign off FLT-1' }));
    const dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getByRole('radio', { name: 'Reject' }));
    expect((within(dialog).getByRole('button', { name: 'Reject decision' }) as HTMLButtonElement).disabled).toBe(true);
    await user.type(within(dialog).getByLabelText(/Note/), 'Needs specialist report');
    await user.click(within(dialog).getByRole('button', { name: 'Reject decision' }));
    await waitFor(() => expect(api.find('POST', '/fitness-decisions/a1/signoff')[0]?.body).toEqual({ approve: false, note: 'Needs specialist report' }));
    expect(await screen.findByText('FLT-1 rejected.')).toBeTruthy();
  });
});

describe('ActiveRestrictions', () => {
  it('filters EXPIRING / LAPSED and extends or closes with a reason; dashboard for Nursing Head', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /restrictions/active': [restriction(), restriction({ id: 'ri2', status: 'LAPSED', employeeName: 'Erin' })],
      'GET /restrictions/active?status=LAPSED': [restriction({ id: 'ri2', status: 'LAPSED', employeeName: 'Erin' })],
      'GET /restrictions/dashboard': { byUnit: [{ unit: 'GND', active: 12, expiring: 3, lapsed: 1 }] },
      'POST /restrictions/ri1/extend': restriction(),
      'POST /restrictions/ri2/close': restriction(),
    });
    render(<ActiveRestrictions client={api.client} session={sessionFor('NURSING_HEAD')} onSessionExpired={() => {}} />);
    expect(await screen.findByRole('heading', { name: 'Restrictions by unit' })).toBeTruthy();
    expect(await screen.findByText('12')).toBeTruthy();
    await user.click(await screen.findByRole('button', { name: 'Extend No lifting above 10 kg for Rami Ramp' }));
    let dialog = screen.getByRole('dialog', { name: 'Extend restriction' });
    await user.type(within(dialog).getByLabelText(/New end date/), '2026-12-15');
    await user.type(within(dialog).getByLabelText(/Reason/), 'Still healing');
    await user.click(within(dialog).getByRole('button', { name: 'Extend restriction' }));
    await waitFor(() => expect(api.find('POST', '/restrictions/ri1/extend')[0]?.body).toEqual({ validUntil: '2026-12-15', reason: 'Still healing' }));

    await user.click(screen.getByRole('button', { name: 'Lapsed' }));
    await waitFor(() => expect(api.calls.some((c) => c.path === '/restrictions/active?status=LAPSED')).toBe(true));
    await user.click(await screen.findByRole('button', { name: 'Close No lifting above 10 kg for Erin' }));
    dialog = screen.getByRole('dialog', { name: 'Close restriction' });
    await user.type(within(dialog).getByLabelText(/Reason/), 'Recovered');
    await user.click(within(dialog).getByRole('button', { name: 'Close restriction' }));
    await waitFor(() => expect(api.find('POST', '/restrictions/ri2/close')[0]?.body).toEqual({ reason: 'Recovered' }));
  });

  it('a nurse sees no dashboard', async () => {
    const api = mockApi({ 'GET /restrictions/active': [] });
    render(<ActiveRestrictions client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={() => {}} />);
    expect(await screen.findByText('No restrictions match.')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Restrictions by unit' })).toBeNull();
  });
});

describe('RtwOverdue / CrewDashboard / RosterReconciliation / CdsStatistics', () => {
  it('lists overdue RTW reviews with escalation after 2 days', async () => {
    const api = mockApi({ 'GET /rtw/reviews/overdue': [{ planId: 'p1', caseId: 'c1', caseRef: 'GND-1', employeeName: 'Rami Ramp', reviewDate: '2026-10-20', daysOverdue: 9, owner: 'nurse1' }] });
    render(<RtwOverdue client={api.client} session={sessionFor('NURSING_HEAD')} onSessionExpired={() => {}} />);
    expect((await screen.findByRole('link', { name: 'GND-1' })).getAttribute('href')).toBe('#/cases/c1/rtw');
    expect(screen.getByText('Escalated')).toBeTruthy();
  });

  it('crew dashboard suppresses small cells as "<5" and never shows identifiers', async () => {
    const api = mockApi({
      'GET /crew/dashboard': {
        unfitByBase: [{ base: 'LHR', count: 12, avgDays: 21.5 }, { base: 'MAN', count: { suppressed: true } }, { base: 'EDI', count: 3, avgDays: 4 }],
        limitedByBase: [{ base: 'LHR', count: null }],
        byFleet: [{ fleet: 'A320', unfit: 7, limited: 2 }],
        trend: [{ week: '2026-W43', unfit: 9, limited: { suppressed: true } }],
      },
    });
    render(<CrewDashboard client={api.client} session={sessionFor('CMO')} onSessionExpired={() => {}} />);
    const unfit = await screen.findByRole('table', { name: 'Unfit by base' });
    expect(within(unfit).getByText('12')).toBeTruthy();
    expect(within(unfit).getByText('21.5')).toBeTruthy();
    expect(within(unfit).getAllByText('<5')).toHaveLength(2);
    expect(within(unfit).queryByText('4.0')).toBeNull(); // avg of a suppressed cell is hidden too
    expect(within(screen.getByRole('table', { name: 'Limited by base' })).getByText('<5')).toBeTruthy();
    expect(within(screen.getByRole('table', { name: 'By fleet' })).getByText('<5')).toBeTruthy();
    expect(within(screen.getByRole('table', { name: 'Weekly trend' })).getByText('9')).toBeTruthy();
  });

  it('roster-feed reconciliation shows counts, unacknowledged > 15 min and p95', async () => {
    const api = mockApi({
      'GET /crew/roster-feed': [{ messageId: 'm-10', employeeId: 'E2002', rosterStatus: 'LIMITED', validFrom: '2026-10-29', operationalLimitations: 'OML', sentAt: '2026-10-29T09:00:00Z', ackAt: '2026-10-29T09:01:00Z' }],
      'GET /crew/roster-feed/reconciliation': { sent: 40, acknowledged: 38, unacknowledgedOver15Min: [{ messageId: 'm-9', employeeId: 'E2001', rosterStatus: 'UNFIT', sentAt: '2026-10-29T08:00:00Z' }], p95LatencySeconds: 312.4 },
    });
    render(<RosterReconciliation client={api.client} session={sessionFor('CMO')} onSessionExpired={() => {}} />);
    expect(await screen.findByText('312 s')).toBeTruthy();
    expect(within(screen.getByRole('table', { name: 'Unacknowledged after 15 minutes' })).getByText('m-9')).toBeTruthy();
    expect(within(await screen.findByRole('table', { name: 'Recent status messages' })).getByText('OML')).toBeTruthy();
    expect(api.calls.some((c) => c.path.startsWith('/integration/'))).toBe(false);
  });

  it('CDS statistics show override rate as a percentage', async () => {
    const api = mockApi({ 'GET /cds/statistics': { fired: 50, overridden: 4, overriddenWithoutReason: 0, overrideRate: 0.08, byCode: [{ code: 'ALLERGY_CLASS', fired: 10, overridden: 1 }] } });
    render(<CdsStatistics client={api.client} session={sessionFor('CMO')} onSessionExpired={() => {}} />);
    expect(await screen.findByText('8.0 %')).toBeTruthy();
    expect(screen.getByText('Allergy class')).toBeTruthy();
    expect(api.calls[0]?.path).toMatch(/^\/cds\/statistics\?from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}$/);
  });
});
