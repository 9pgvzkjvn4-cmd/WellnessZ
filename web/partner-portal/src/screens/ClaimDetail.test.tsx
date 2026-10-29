// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { toMe } from '../auth/me';
import { claimDetail, meAdjuster } from '../test/fixtures';
import { mockApi, reply } from '../test/mockApi';
import { ClaimDetail } from './ClaimDetail';
import { Claims } from './Claims';

afterEach(cleanup);

const me = toMe(meAdjuster);
const session = { authenticated: true, roles: ['TPA_ADJUSTER'] };

describe('adjuster claims', () => {
  it('lists claims with lost time, open tasks and unread messages, filtered by status on the server', async () => {
    const api = mockApi({
      'GET /partner/claims': [{ id: 'cl1', claimRef: 'CLM-2026-000001', claimType: 'WORKERS_COMP', status: 'OPEN', displayName: 'Rami Ramp', incidentDate: '2026-10-01', lostTime: true, openTasks: 3, unreadMessages: 2 }],
      'GET /partner/claims?status=SETTLED': [],
    });
    render(<Claims client={api.client} session={session} me={me} onSessionExpired={vi.fn()} />);
    const link = await screen.findByRole('link', { name: 'Open claim CLM-2026-000001' });
    expect(link.getAttribute('href')).toBe('#/claims/cl1');
    expect(screen.getByText('2 unread')).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'SETTLED');
    expect(await screen.findByText('No claims match the filter.')).toBeTruthy();
    expect(api.calls.map((c) => c.path)).toContain('/partner/claims?status=SETTLED');
  });

  it('shows overview, RTW status and milestones, documents – no clinical content', async () => {
    const api = mockApi({ 'GET /partner/claims/cl1': claimDetail(), 'GET /claims/cl1/messages': [] });
    render(<ClaimDetail client={api.client} session={session} me={me} onSessionExpired={vi.fn()} claimId="cl1" />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Claim CLM-2026-000001' })).toBeTruthy();
    expect(screen.getByText('Fit with restrictions')).toBeTruthy();
    expect(screen.getByText('Office-based duties, no lifting')).toBeTruthy();
    expect(screen.getByText(/4 h\/day/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Download Fitness certificate' }).getAttribute('href')).toBe('/bff/api/documents/d1/content');
    expect(document.body.textContent).not.toMatch(/SECRET|1,000/);
  });

  it('completes an adjuster task and creates a new one', async () => {
    let tasks = claimDetail().tasks;
    const api = mockApi({
      'GET /partner/claims/cl1': claimDetail(),
      'GET /claims/cl1/messages': [],
      'GET /claims/cl1/tasks': () => tasks,
      'POST /claims/tasks/tk1/complete': () => {
        tasks = tasks.map((t) => (t.id === 'tk1' ? { ...t, status: 'DONE' } : t));
        return {};
      },
      'POST /claims/cl1/tasks': reply(422, { status: 422, code: 'clinical_content_not_allowed', field: 'title' }),
    });
    render(<ClaimDetail client={api.client} session={session} me={me} onSessionExpired={vi.fn()} claimId="cl1" />);
    const completeButtons = await screen.findAllByRole('button', { name: /^Mark as done/ });
    expect(completeButtons).toHaveLength(1);
    await userEvent.click(completeButtons[0]!);
    expect(await screen.findByText('Task completed.')).toBeTruthy();
    expect(screen.queryAllByRole('button', { name: /^Mark as done/ })).toHaveLength(0);

    await userEvent.type(screen.getByLabelText(/^Task/), 'Check fracture healing');
    await userEvent.type(screen.getByLabelText(/Due date/), '2099-01-10');
    await userEvent.click(screen.getByRole('button', { name: 'Create task' }));
    expect(await screen.findByText('Remove medical information from this field.')).toBeTruthy();
    expect(screen.getByLabelText(/^Task/).getAttribute('aria-invalid')).toBe('true');
    expect(api.find('POST', '/claims/cl1/tasks')[0]!.body).toEqual({ title: 'Check fracture healing', dueDate: '2099-01-10', assigneeRole: 'CLAIMS' });
  });

  it('shows "not found or not shared" for a claim of another payer', async () => {
    const api = mockApi({ 'GET /partner/claims/other': reply(404, { status: 404 }) });
    render(<ClaimDetail client={api.client} session={session} me={me} onSessionExpired={vi.fn()} claimId="other" />);
    expect(await screen.findByText('Not found or not shared with your organisation.')).toBeTruthy();
  });
});
