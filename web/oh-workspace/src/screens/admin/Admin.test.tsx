// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { catalogue, injuryTemplate, standards } from '../../testing/fixtures';
import { mockApi, sessionFor } from '../../testing/mockApi';
import { FitnessStandardsAdmin, JobDemandsAdmin, RestrictionCatalogueAdmin } from './FitnessAdmin';
import { EncounterTemplatesAdmin, TerminologyAdmin } from './RecordAdmin';

afterEach(cleanup);

describe('FitnessStandardsAdmin', () => {
  it('edits a standard with PUT /masterdata/fitness-standards/{code} without the version (new version server-side)', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /masterdata/fitness-standards': standards, 'PUT /masterdata/fitness-standards/CAA_CLASS1': { ...standards[1], version: 4 } });
    render(<FitnessStandardsAdmin client={api.client} session={sessionFor('CMO')} onSessionExpired={() => {}} />);
    expect(await screen.findByText('TML, VDL')).toBeTruthy();
    await user.click(screen.getAllByRole('button', { name: 'Edit' })[1]!);
    const codes = screen.getByLabelText('Limitation codes');
    await user.clear(codes);
    await user.type(codes, 'TML, VDL, OML');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(api.find('PUT', '/masterdata/fitness-standards/CAA_CLASS1')).toHaveLength(1));
    const body = api.find('PUT', '/masterdata/fitness-standards/CAA_CLASS1')[0]?.body as Record<string, unknown>;
    expect(body.limitationCodes).toEqual(['TML', 'VDL', 'OML']);
    expect(body).not.toHaveProperty('version');
    expect(body.requiresSignoffRole).toBe('AME');
  });

  it('is read-only for a nursing head', async () => {
    const api = mockApi({ 'GET /masterdata/fitness-standards': standards });
    render(<FitnessStandardsAdmin client={api.client} session={sessionFor('NURSING_HEAD')} onSessionExpired={() => {}} />);
    await screen.findByText('TML, VDL');
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
  });
});

describe('RestrictionCatalogueAdmin', () => {
  it('creates a restriction by upsert (PUT /masterdata/restrictions/{code})', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /masterdata/restrictions': catalogue, 'PUT /masterdata/restrictions/NO_LADDERS': {} });
    render(<RestrictionCatalogueAdmin client={api.client} session={sessionFor('NURSING_HEAD')} onSessionExpired={() => {}} />);
    await user.click(await screen.findByRole('button', { name: 'Add entry' }));
    await user.type(screen.getByLabelText(/^Code/), 'NO_LADDERS');
    await user.type(screen.getByLabelText(/^Label/), 'No work on ladders');
    await user.selectOptions(screen.getByLabelText(/Job-demand dimension/), 'CLIMB');
    await user.click(screen.getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(api.find('PUT', '/masterdata/restrictions/NO_LADDERS')[0]?.body).toMatchObject({ code: 'NO_LADDERS', label: 'No work on ladders', dimension: 'CLIMB', valueRequired: false, active: true }));
    expect(api.find('POST', '/masterdata/restrictions')).toHaveLength(0);
  });
});

describe('JobDemandsAdmin', () => {
  const profile = { jobCode: 'RAMP_AGENT', title: 'Ramp agent', unit: 'GND', demands: { LIFT_KG: 23, NIGHT: true, KNIFE: false }, sitting: 'LOW', validatedBy: 'mgr1', validatedAt: '2026-09-01T00:00:00Z' };

  it('OH admin imports a JSON array (validated) and edits one profile', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /masterdata/job-demands': [profile], 'POST /masterdata/job-demands/import': { upserted: 2 }, 'PUT /masterdata/job-demands/RAMP_AGENT': profile });
    render(<JobDemandsAdmin client={api.client} session={sessionFor('OH_ADMIN')} onSessionExpired={() => {}} />);
    expect(await screen.findByText('Lift kg 23, Night')).toBeTruthy();
    const ta = screen.getByLabelText('Profiles JSON');
    await user.click(ta);
    await user.paste('[{"jobCode":"X"}]');
    await user.click(screen.getByRole('button', { name: 'Import profiles' }));
    expect(screen.getByRole('alert').textContent).toMatch(/Profile 1: jobCode, title, unit and a demands object are required/);
    await user.clear(ta);
    await user.click(ta);
    await user.paste(JSON.stringify([profile, { ...profile, jobCode: 'CHECKIN', title: 'Check-in' }]));
    await user.click(screen.getByRole('button', { name: 'Import profiles' }));
    await waitFor(() => expect((api.find('POST', '/masterdata/job-demands/import')[0]?.body as unknown[]).length).toBe(2));
    expect(await screen.findByText('2 profiles imported.')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Edit RAMP_AGENT' }));
    const dialog = screen.getByRole('dialog', { name: 'Edit RAMP_AGENT' });
    await user.click(within(dialog).getByRole('button', { name: 'Save profile' }));
    await waitFor(() => expect(api.find('PUT', '/masterdata/job-demands/RAMP_AGENT')[0]?.body).toMatchObject({ jobCode: 'RAMP_AGENT', demands: { LIFT_KG: 23 } }));
  });

  it('Nursing Head edits but cannot bulk import', async () => {
    const api = mockApi({ 'GET /masterdata/job-demands': [profile] });
    render(<JobDemandsAdmin client={api.client} session={sessionFor('NURSING_HEAD')} onSessionExpired={() => {}} />);
    expect(await screen.findByRole('button', { name: 'Edit RAMP_AGENT' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Import profiles' })).toBeNull();
  });
});

describe('EncounterTemplatesAdmin', () => {
  it('CMO approves a DRAFT template; others only view', async () => {
    const user = userEvent.setup();
    const draft = { ...injuryTemplate, status: 'DRAFT' as const, version: 3, approvedBy: null };
    const api = mockApi({ 'GET /encounter-templates': [draft], 'POST /encounter-templates/INJURY/approve': { ...draft, status: 'APPROVED' } });
    const { unmount } = render(<EncounterTemplatesAdmin client={api.client} session={sessionFor('OH_PHYSICIAN')} onSessionExpired={() => {}} />);
    expect(await screen.findByText(/1 sections · 3 fields · 1 mandatory · body map · diagnosis required/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Approve v3' })).toBeNull();
    unmount();
    render(<EncounterTemplatesAdmin client={api.client} session={sessionFor('CMO')} onSessionExpired={() => {}} />);
    await user.click(await screen.findByRole('button', { name: 'Approve v3' }));
    await waitFor(() => expect(api.find('POST', '/encounter-templates/INJURY/approve')).toHaveLength(1));
  });
});

describe('TerminologyAdmin', () => {
  it('lists releases and imports a CSV release after preview', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /terminology/releases': [{ system: 'ICD_10', version: '2026', loadedAt: '2026-01-10T00:00:00Z', loadedBy: 'admin1', licence: 'WHO' }],
      'POST /terminology/concepts/import': { upserted: 2, deactivated: 1 },
    });
    render(<TerminologyAdmin client={api.client} session={sessionFor('OH_ADMIN')} onSessionExpired={() => {}} />);
    expect(await screen.findByText('WHO')).toBeTruthy();
    const ta = screen.getByLabelText('Concepts');
    await user.click(ta);
    await user.paste('system,code,display,active\nICD_10,S93.4,"Sprain of ankle, left",true\nICD_10,X99,Old code,false');
    expect(screen.getByRole('status').textContent).toMatch(/2 concepts ready \(ICD_10; 1 inactive\)/);
    await user.click(screen.getByRole('button', { name: 'Import' }));
    await waitFor(() => expect(api.find('POST', '/terminology/concepts/import')[0]?.body).toEqual([
      { system: 'ICD_10', code: 'S93.4', display: 'Sprain of ankle, left', active: true },
      { system: 'ICD_10', code: 'X99', display: 'Old code', active: false },
    ]));
    expect(await screen.findByText('2 concepts upserted, 1 deactivated.')).toBeTruthy();
  });
});
