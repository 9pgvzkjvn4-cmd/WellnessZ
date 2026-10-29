// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { App } from '../../App';
import { mockApi } from '../../testing/mockApi';

afterEach(() => {
  cleanup();
  window.location.hash = '';
});

const navLinks = () => within(screen.getByRole('navigation', { name: 'Main' })).getAllByRole('link').map((a) => a.textContent);

describe('App shell – increment 3 absence / surveillance', () => {
  it('AUDITOR lands on compliance and sees nothing else', async () => {
    window.location.hash = '#/';
    const api = mockApi({
      'GET /bff/session': { authenticated: true, roles: ['AUDITOR'] },
      'GET /surveillance/compliance?groupBy=PROGRAMME': [],
      'GET /surveillance/compliance/trend': [],
      'GET /surveillance/programmes': [],
      'GET /surveillance/audit-packs/templates': [],
    });
    render(<App client={api.client} />);
    await waitFor(() => expect(window.location.hash).toBe('#/surveillance/compliance'));
    expect(await screen.findByRole('heading', { level: 1, name: 'Surveillance compliance' })).toBeTruthy();
    expect(navLinks()).toEqual(['Compliance & audit']);
  });

  it('CREW_LICENSING opening an absence URL is restricted and no request is made', async () => {
    window.location.hash = '#/absence/lta/c1';
    const api = mockApi({ 'GET /bff/session': { authenticated: true, roles: ['CREW_LICENSING'] } });
    render(<App client={api.client} />);
    expect(await screen.findByRole('heading', { name: 'Access restricted' })).toBeTruthy();
    expect(api.calls.some((c) => c.path.startsWith('/absence'))).toBe(false);
  });

  it('LTA detail keeps "Long-term absence" highlighted in the nav', async () => {
    window.location.hash = '#/absence/lta/c1';
    const api = mockApi({
      'GET /bff/session': { authenticated: true, roles: ['OH_CLINICIAN'] },
      'GET /absence/lta/c1': { caseId: 'c1', caseRef: 'ABS-1', employeeId: 'E1', displayName: 'Rami Ramp', absenceStart: '2026-08-01', weeksAbsent: 12, status: 'ACTIVE', hrStage: 'NONE', overdueCount: 0, milestones: [], contacts: [], adviceReports: [] },
    });
    render(<App client={api.client} />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Rami Ramp – ABS-1' })).toBeTruthy();
    const nav = screen.getByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'Long-term absence' }).getAttribute('aria-current')).toBe('page');
  });
});
