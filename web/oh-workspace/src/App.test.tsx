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
    expect(navLinks()).toEqual(['Break-glass reviews', 'Break-glass report', 'Disclosure log', 'Retention & legal hold', 'Claim disclosures']);
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

describe('App shell – increment 4 routing', () => {
  it('FINANCE lands on claim costs and is restricted from a case', async () => {
    window.location.hash = '#/';
    const api = mockApi({ 'GET /bff/session': { authenticated: true, roles: ['FINANCE'] }, 'GET /claims/costs/rollup': [], 'GET /treatment/costs': [], 'GET /claims/costs/exports': [] });
    render(<App client={api.client} />);
    await waitFor(() => expect(window.location.hash).toBe('#/claims/costs'));
    expect(await screen.findByRole('heading', { level: 1, name: 'Claim & treatment costs' })).toBeTruthy();
    expect(navLinks()).toEqual(['Claim & treatment costs']);
    window.location.hash = '#/cases/c1/treatment';
    expect(await screen.findByRole('heading', { name: 'Access restricted' })).toBeTruthy();
    expect(api.calls.some((c) => c.path.startsWith('/cases/'))).toBe(false);
  });

  it('claims coordinator opens a claim detail tab; /claims/reports stays the dashboard', async () => {
    window.location.hash = '#/claims/cl1/collaboration';
    const api = mockApi({
      'GET /bff/session': { authenticated: true, roles: ['CLAIMS_COORDINATOR'] },
      'GET /claims/cl1': { id: 'cl1', claimRef: 'CLM-2026-000001', caseId: 'c1', employeeId: 'E1', displayName: 'Rami Ramp', claimType: 'WORKERS_COMP', status: 'OPEN', lostTime: false, litigationHold: false, privileged: false },
      'GET /claims/cl1/tasks': [],
      'GET /claims/cl1/messages': [],
      'GET /claims/reports': [],
    });
    render(<App client={api.client} />);
    expect(await screen.findByRole('heading', { level: 1, name: 'CLM-2026-000001' })).toBeTruthy();
    expect(await screen.findByRole('heading', { name: 'Shared tasks' })).toBeTruthy();
    expect(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Claims' }).getAttribute('aria-current')).toBe('page');
    window.location.hash = '#/claims/reports';
    expect(await screen.findByRole('heading', { level: 1, name: 'Report deadlines' })).toBeTruthy();
  });
});
