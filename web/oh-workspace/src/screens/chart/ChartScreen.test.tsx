// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { chartSummary } from '../../testing/fixtures';
import { mockApi, sequence, sessionFor } from '../../testing/mockApi';
import { ChartScreen } from './ChartScreen';

afterEach(cleanup);

const base = {
  'GET /employees/E1004/problems': [{ id: 'p1', employeeId: 'E1004', code: { system: 'ICD_10', code: 'S63.5', display: 'Sprain of wrist' }, status: 'ACTIVE', workRelated: 'YES', recordedBy: 'nurse1' }],
};

describe('ChartScreen', () => {
  it('renders the summary panel: alerts, fitness, restrictions, open cases, surveillance, immunisations, counts', async () => {
    const api = mockApi({ ...base, 'GET /employees/E1004/chart': chartSummary() });
    render(<ChartScreen client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={() => {}} employeeId="E1004" />);
    const panel = await screen.findByRole('region', { name: 'Chart summary' });
    expect(within(panel).getByText(/Penicillin – anaphylaxis/)).toBeTruthy();
    expect(within(panel).getByText('Fit with restrictions')).toBeTruthy();
    expect(within(panel).getByText(/No lifting above 10 kg/)).toBeTruthy();
    expect(within(panel).getByRole('link', { name: 'GND-2026-000001' }).getAttribute('href')).toBe('#/cases/c1');
    expect(within(panel).getByText('Overdue')).toBeTruthy();
    expect(within(panel).getByText(/Hepatitis B/)).toBeTruthy();
    expect(within(panel).getByText(/1 unacknowledged abnormal/)).toBeTruthy();
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Rami Ramp (E1004)');
    // default tab is Problems
    expect(await screen.findByText(/Sprain of wrist/)).toBeTruthy();
    // FHIR / ODH export downloads go through the BFF
    expect(screen.getByRole('link', { name: 'Download FHIR record' }).getAttribute('href')).toBe('/bff/api/employees/E1004/fhir');
    expect(screen.getByRole('link', { name: 'Download ODH (jobs & exposures)' }).getAttribute('href')).toBe('/bff/api/employees/E1004/odh');
    expect(screen.queryByRole('button', { name: /break-glass/i })).toBeNull();
  });

  it('shows "N restricted items hidden" and runs the break-glass flow (≥ 20 chars, DPO warning, reload)', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      ...base,
      'GET /employees/E1004/chart': sequence(chartSummary({ restrictedItemsHidden: 2 }), chartSummary({ restrictedItemsHidden: 0 })),
      'POST /break-glass': { id: 'bg1', employeeId: 'E1004', grantedTo: 'tester', expiresAt: '2026-10-29T16:00:00Z', reason: 'x' },
    });
    render(<ChartScreen client={api.client} session={sessionFor('OH_PHYSICIAN')} onSessionExpired={() => {}} employeeId="E1004" />);
    expect(await screen.findByText('2 restricted items hidden')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Emergency access (break-glass)' }));
    const dialog = screen.getByRole('dialog', { name: 'Emergency access (break-glass)' });
    expect(within(dialog).getByText(/Data Protection Officer is alerted immediately/)).toBeTruthy();
    // focus moves into the dialog
    expect(document.activeElement).toBe(within(dialog).getByLabelText(/Justification/));

    await user.type(within(dialog).getByLabelText(/Justification/), 'too short');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm emergency access' }));
    expect(within(dialog).getByRole('alert').textContent).toMatch(/at least 20 characters/);
    expect(api.find('POST', '/break-glass')).toHaveLength(0);

    await user.clear(within(dialog).getByLabelText(/Justification/));
    await user.type(within(dialog).getByLabelText(/Justification/), 'Unconscious in clinic, need medication history');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm emergency access' }));
    await waitFor(() => expect(api.find('POST', '/break-glass')).toHaveLength(1));
    expect(api.find('POST', '/break-glass')[0]?.body).toEqual({ employeeId: 'E1004', reason: 'Unconscious in clinic, need medication history' });
    expect(await screen.findByText(/Emergency access granted until/)).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
    await waitFor(() => expect(api.find('GET', '/employees/E1004/chart')).toHaveLength(2));
  });

  it('a case manager (not a clinician) sees the hidden count but no break-glass button', async () => {
    const api = mockApi({ ...base, 'GET /employees/E1004/chart': chartSummary({ restrictedItemsHidden: 1 }) });
    render(<ChartScreen client={api.client} session={sessionFor('CASE_MANAGER')} onSessionExpired={() => {}} employeeId="E1004" />);
    expect(await screen.findByText('1 restricted item hidden')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /break-glass/i })).toBeNull();
    // read-only: no "Add problem"
    await screen.findByText(/Sprain of wrist/);
    expect(screen.queryByRole('button', { name: 'Add problem' })).toBeNull();
  });

  it('shows the server error (404 = no access) with its correlation id', async () => {
    const api = mockApi({ ...base });
    render(<ChartScreen client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={() => {}} employeeId="E1004" />);
    expect((await screen.findAllByRole('alert'))[0]?.textContent).toMatch(/No mock for GET \/employees\/E1004\/chart/);
  });

  it('switches tabs via links and renders the requested tab', async () => {
    const api = mockApi({
      'GET /employees/E1004/chart': chartSummary(),
      'GET /employees/E1004/record-timeline': [{ at: '2026-10-01T10:00:00Z', kind: 'ENCOUNTER', ref: 'enc9', title: 'Injury consultation' }],
    });
    render(<ChartScreen client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={() => {}} employeeId="E1004" tab="timeline" />);
    expect((await screen.findByRole('link', { name: 'Injury consultation' })).getAttribute('href')).toBe('#/encounters/enc9');
    const nav = screen.getByRole('navigation', { name: 'Chart sections' });
    expect(within(nav).getByRole('link', { name: 'Timeline' }).getAttribute('aria-current')).toBe('page');
    expect(within(nav).getAllByRole('link').map((a) => a.textContent)).toEqual(['Problems', 'Medications', 'Allergies', 'Immunisations', 'Jobs & exposures', 'Results', 'Documents', 'Encounters', 'Timeline', 'Absence', 'Surveillance']);
  });
});
