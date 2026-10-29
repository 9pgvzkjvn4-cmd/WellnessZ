// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { App } from './App';
import { chartSummary } from './testing/fixtures';
import { mockApi } from './testing/mockApi';

afterEach(() => {
  cleanup();
  window.location.hash = '';
});

const navLinks = () => within(screen.getByRole('navigation', { name: 'Main' })).getAllByRole('link').map((a) => a.textContent);
const navGroups = () => within(screen.getByRole('navigation', { name: 'Main' })).getAllByRole('heading').map((h) => h.textContent);

describe('App shell – increment 2 navigation and gating', () => {
  it('DPO lands on break-glass reviews and sees only the privacy area', async () => {
    window.location.hash = '#/';
    const api = mockApi({ 'GET /bff/session': { authenticated: true, name: 'Dana DPO', roles: ['ROLE_DPO'] }, 'GET /break-glass?status=PENDING': [] });
    render(<App client={api.client} />);
    await waitFor(() => expect(window.location.hash).toBe('#/dpo/break-glass'));
    expect(await screen.findByRole('heading', { level: 1, name: 'Break-glass reviews' })).toBeTruthy();
    expect(navGroups()).toEqual(['Privacy']);
    expect(navLinks()).toEqual(['Break-glass reviews', 'Break-glass report', 'Disclosure log', 'Retention & legal hold']);
    for (const label of ['Work queues', 'Employee chart', 'Results worklist', 'Unsigned notes', 'Active restrictions']) expect(navLinks()).not.toContain(label);
  });

  it('DPO opening a clinical URL gets "Access restricted" and no chart request is made', async () => {
    window.location.hash = '#/employees/E1004/chart';
    const api = mockApi({ 'GET /bff/session': { authenticated: true, roles: ['DPO'] } });
    render(<App client={api.client} />);
    expect(await screen.findByRole('heading', { name: 'Access restricted' })).toBeTruthy();
    expect(api.calls.some((c) => c.path.includes('/chart'))).toBe(false);
  });

  it.each([['HR'], ['MANAGER']])('%s gets the full-page access restriction', async (role) => {
    window.location.hash = '#/employees/E1004/chart';
    const api = mockApi({ 'GET /bff/session': { authenticated: true, roles: [role] } });
    render(<App client={api.client} />);
    expect(await screen.findByText(/sign out/i)).toBeTruthy();
    expect(screen.queryByRole('navigation', { name: 'Main' })).toBeNull();
    expect(api.calls.some((c) => c.path.includes('/chart'))).toBe(false);
  });

  it('an OH physician gets the Record and Fitness groups and can open a chart (nav highlights Employee chart)', async () => {
    window.location.hash = '#/employees/E1004/chart';
    const api = mockApi({ 'GET /bff/session': { authenticated: true, roles: ['OH_PHYSICIAN'] }, 'GET /employees/E1004/chart': chartSummary(), 'GET /employees/E1004/problems': [] });
    render(<App client={api.client} />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Rami Ramp (E1004)' })).toBeTruthy();
    expect(navGroups()).toEqual(expect.arrayContaining(['Work', 'Record', 'Fitness']));
    expect(navGroups()).not.toContain('Privacy');
    expect(navLinks()).toEqual(expect.arrayContaining(['Employee chart', 'Unsigned notes', 'Results worklist', 'Active restrictions', 'RTW overdue reviews']));
    expect(navLinks()).not.toContain('Fitness sign-off');
    const nav = screen.getByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'Employee chart' }).getAttribute('aria-current')).toBe('page');
  });
});
