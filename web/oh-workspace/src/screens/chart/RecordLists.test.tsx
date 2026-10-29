// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { TerminologyPicker } from '../../components/TerminologyPicker';
import { mockApi, reply, sequence, sessionFor } from '../../testing/mockApi';
import type { ChartTabProps } from './chartTabProps';
import { MedicationsTab } from './MedicationsTab';
import { AllergiesTab, ImmunisationsTab, JobsTab, ProblemsTab } from './RecordLists';

afterEach(cleanup);

const props = (client: ChartTabProps['client'], canWrite = true): ChartTabProps => ({ client, session: sessionFor('OH_CLINICIAN'), onSessionExpired: () => {}, employeeId: 'E1004', canWrite, refreshSummary: () => {} });
const CDS_409 = reply(409, {
  status: 409,
  title: 'Conflict',
  code: 'cds_alert',
  detail: 'Clinical decision support alerts',
  alerts: [
    { code: 'ALLERGY_CLASS', severity: 'CRITICAL', message: 'Amoxicillin is a penicillin – recorded allergy: penicillin (anaphylaxis)', reference: 'Drug-class map', extra: 'dropped' },
    { code: 'INTERACTION', severity: 'WARNING', message: 'Interaction with methotrexate' },
  ],
});

describe('MedicationsTab – clinical decision support', () => {
  it('shows the alerts on 409 cds_alert and requires an override reason per alert before resubmitting', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /employees/E1004/medications': [],
      'POST /employees/E1004/medications': sequence(CDS_409, { id: 'm1' }),
    });
    render(<MedicationsTab {...props(api.client)} />);
    await user.click(await screen.findByRole('button', { name: 'Add medication' }));
    await user.type(screen.getByLabelText(/^Medication/), 'Amoxicillin');
    await user.type(screen.getByLabelText('Dose'), '500 mg');
    await user.selectOptions(screen.getByLabelText(/^Source/), 'ADMINISTERED');
    await user.click(screen.getByRole('button', { name: 'Save medication' }));

    const panel = await screen.findByRole('region', { name: 'Clinical decision support alerts' });
    expect(within(panel).getByText(/2 clinical alerts/)).toBeTruthy();
    expect(within(panel).getByText(/Amoxicillin is a penicillin/)).toBeTruthy();
    expect(within(panel).getByText('Critical')).toBeTruthy();

    // submitting without reasons is blocked client-side with an error summary
    await user.click(screen.getByRole('button', { name: 'Save with overrides' }));
    const summary = screen.getByRole('alert', { name: 'There is a problem' });
    expect(within(summary).getAllByRole('link').map((l) => l.textContent)).toEqual(['Give an override reason for Allergy class.', 'Give an override reason for Interaction.']);
    expect(api.find('POST', '/employees/E1004/medications')).toHaveLength(1);

    await user.type(within(panel).getByLabelText(/Override reason for Allergy class/), 'Tolerated amoxicillin in 2024 under supervision');
    await user.click(screen.getByRole('button', { name: 'Save with overrides' }));
    expect(api.find('POST', '/employees/E1004/medications')).toHaveLength(1);
    await user.type(within(panel).getByLabelText(/Override reason for Interaction/), 'Methotrexate stopped');
    await user.click(screen.getByRole('button', { name: 'Save with overrides' }));

    await waitFor(() => expect(api.find('POST', '/employees/E1004/medications')).toHaveLength(2));
    const body = api.find('POST', '/employees/E1004/medications')[1]?.body as Record<string, unknown>;
    expect(body).toMatchObject({ name: 'Amoxicillin', dose: '500 mg', source: 'ADMINISTERED', status: 'ACTIVE' });
    expect(body.overrides).toEqual([
      { alertCode: 'ALLERGY_CLASS', reason: 'Tolerated amoxicillin in 2024 under supervision' },
      { alertCode: 'INTERACTION', reason: 'Methotrexate stopped' },
    ]);
    expect(await screen.findByText('Medication recorded with documented overrides.')).toBeTruthy();
  });

  it('changing the entry after an alert clears the alerts (the server re-checks)', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /employees/E1004/medications': [], 'POST /employees/E1004/medications': CDS_409 });
    render(<MedicationsTab {...props(api.client)} />);
    await user.click(await screen.findByRole('button', { name: 'Add medication' }));
    await user.type(screen.getByLabelText(/^Medication/), 'Amoxicillin');
    await user.click(screen.getByRole('button', { name: 'Save medication' }));
    await screen.findByRole('region', { name: 'Clinical decision support alerts' });
    await user.clear(screen.getByLabelText(/^Medication/));
    await user.type(screen.getByLabelText(/^Medication/), 'Clarithromycin');
    expect(screen.queryByRole('region', { name: 'Clinical decision support alerts' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Save medication' })).toBeTruthy();
  });

  it('lists medications with overridden alerts', async () => {
    const api = mockApi({
      'GET /employees/E1004/medications': [{ id: 'm1', employeeId: 'E1004', name: 'Ibuprofen', dose: '400 mg', route: 'ORAL', frequency: 'TDS', source: 'REPORTED', status: 'ACTIVE', overrides: [{ alertCode: 'INTERACTION', reason: 'x' }] }],
    });
    render(<MedicationsTab {...props(api.client, false)} />);
    expect(await screen.findByText('Ibuprofen')).toBeTruthy();
    expect(screen.getByText(/Alerts overridden: Interaction/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Add medication' })).toBeNull();
  });
});

describe('ProblemsTab', () => {
  it('requires a coded problem, then saves via the terminology picker', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /employees/E1004/problems': [],
      'GET /terminology/favourites': [],
      'GET /terminology/search': [{ system: 'ICD_10', code: 'S93.4', display: 'Sprain of ankle' }],
      'POST /employees/E1004/problems': { id: 'p1' },
    });
    render(<ProblemsTab {...props(api.client)} />);
    await user.click(await screen.findByRole('button', { name: 'Add problem' }));
    await user.click(screen.getByRole('button', { name: 'Save problem' }));
    expect(screen.getByRole('alert', { name: 'There is a problem' }).textContent).toMatch(/Choose a coded problem/);

    await user.type(screen.getByLabelText('Search codes'), 'ankle{Enter}');
    await waitFor(() => expect(api.find('GET', '/terminology/search')[0]?.path).toBe('/terminology/search?system=ICD_10&q=ankle&limit=20'));
    await user.click(await screen.findByRole('button', { name: /S93.4 Sprain of ankle/ }));
    expect(screen.getByText(/Selected:/).textContent).toMatch(/Sprain of ankle/);
    await user.selectOptions(screen.getByLabelText('Work-related'), 'YES');
    await user.type(screen.getByLabelText('Onset date'), '2026-10-20');
    await user.click(screen.getByRole('button', { name: 'Save problem' }));
    await waitFor(() => expect(api.find('POST', '/employees/E1004/problems')).toHaveLength(1));
    expect(api.find('POST', '/employees/E1004/problems')[0]?.body).toEqual({
      code: { system: 'ICD_10', code: 'S93.4', display: 'Sprain of ankle' }, status: 'ACTIVE', onsetDate: '2026-10-20', resolvedDate: null, workRelated: 'YES', note: null, caseId: null,
    });
  });

  it('edits an existing problem with PUT /problems/{id}', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /employees/E1004/problems': [{ id: 'p9', employeeId: 'E1004', code: { system: 'ICD_10', code: 'M54.5', display: 'Low back pain' }, status: 'ACTIVE', workRelated: 'UNDER_REVIEW' }],
      'GET /terminology/favourites': [],
      'PUT /problems/p9': { id: 'p9' },
    });
    render(<ProblemsTab {...props(api.client)} />);
    await user.click(await screen.findByRole('button', { name: 'Edit Low back pain' }));
    await user.selectOptions(screen.getByLabelText(/^Status/), 'RESOLVED');
    await user.click(screen.getByRole('button', { name: 'Save problem' }));
    expect(screen.getByRole('alert', { name: 'There is a problem' }).textContent).toMatch(/resolved date/);
    await user.type(screen.getByLabelText('Resolved date'), '2026-10-28');
    await user.click(screen.getByRole('button', { name: 'Save problem' }));
    await waitFor(() => expect(api.find('PUT', '/problems/p9')).toHaveLength(1));
    expect(api.find('PUT', '/problems/p9')[0]?.body).toMatchObject({ status: 'RESOLVED', resolvedDate: '2026-10-28' });
  });
});

describe('AllergiesTab / ImmunisationsTab / JobsTab', () => {
  it('adds an allergy with a SNOMED substance', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /employees/E1004/allergies': [],
      'GET /terminology/favourites': [{ system: 'SNOMED_CT', code: '764146007', display: 'Penicillin' }],
      'POST /employees/E1004/allergies': { id: 'a1' },
    });
    render(<AllergiesTab {...props(api.client)} />);
    await user.click(await screen.findByRole('button', { name: 'Add allergy' }));
    await user.click(screen.getByRole('button', { name: 'Favourites' }));
    await user.click(await screen.findByRole('button', { name: /764146007 Penicillin/ }));
    await user.type(screen.getByLabelText('Reaction'), 'Anaphylaxis');
    await user.click(screen.getByRole('button', { name: 'Save allergy' }));
    await waitFor(() => expect(api.find('POST', '/employees/E1004/allergies')).toHaveLength(1));
    expect(api.find('POST', '/employees/E1004/allergies')[0]?.body).toEqual({ substance: { system: 'SNOMED_CT', code: '764146007', display: 'Penicillin' }, category: 'MEDICATION', criticality: 'HIGH', reaction: 'Anaphylaxis' });
  });

  it('validates and records an immunisation', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /employees/E1004/immunisations': [], 'POST /employees/E1004/immunisations': { id: 'i1' } });
    render(<ImmunisationsTab {...props(api.client)} />);
    await user.click(await screen.findByRole('button', { name: 'Record immunisation' }));
    const summary = screen.getByRole('alert', { name: 'There is a problem' });
    expect(within(summary).getAllByRole('link')).toHaveLength(2);
    // error-summary links move focus to the field without changing the hash route
    await user.click(within(summary).getByRole('link', { name: 'Enter the vaccine.' }));
    expect(document.activeElement).toBe(screen.getByLabelText(/^Vaccine/));
    await user.type(screen.getByLabelText(/^Vaccine/), 'Hepatitis B');
    await user.type(screen.getByLabelText(/^Date given/), '2026-10-01');
    await user.type(screen.getByLabelText('Dose number'), '2');
    await user.click(screen.getByRole('button', { name: 'Record immunisation' }));
    await waitFor(() => expect(api.find('POST', '/employees/E1004/immunisations')).toHaveLength(1));
    expect(api.find('POST', '/employees/E1004/immunisations')[0]?.body).toEqual({ vaccine: 'Hepatitis B', date: '2026-10-01', doseNumber: 2, lotNumber: null, status: 'COMPLETE', nextDueDate: null });
  });

  it('shows HRIS jobs with exposures and adds a manual job with hazards', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /employees/E1004/jobs': [{ id: 'j1', employeeId: 'E1004', jobCode: 'RAMP_AGENT', jobTitle: 'Ramp agent', employer: 'Group / GND', current: true, source: 'HRIS', hazards: [{ code: 'NOISE', name: 'Noise', category: 'PHYSICAL' }], startDate: '2020-01-01' }],
      'POST /employees/E1004/jobs': { id: 'j2' },
    });
    render(<JobsTab {...props(api.client)} />);
    expect(await screen.findByText('From HRIS')).toBeTruthy();
    expect(within(screen.getByRole('list', { name: 'Exposures for Ramp agent' })).getByText('Noise')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Add job' }));
    await user.type(screen.getByLabelText(/^Job title/), 'Baker');
    await user.type(screen.getByLabelText(/^Start date/), '2015-01-01');
    await user.type(screen.getByLabelText(/^End date/), '2019-12-31');
    await user.type(screen.getByLabelText(/^Exposures \/ hazards/), 'FLOUR_DUST:Flour dust, heat');
    await user.click(screen.getByRole('button', { name: 'Save job' }));
    await waitFor(() => expect(api.find('POST', '/employees/E1004/jobs')).toHaveLength(1));
    expect(api.find('POST', '/employees/E1004/jobs')[0]?.body).toMatchObject({ jobTitle: 'Baker', startDate: '2015-01-01', endDate: '2019-12-31', current: false, hazards: [{ code: 'FLOUR_DUST', name: 'Flour dust' }, { code: 'HEAT', name: 'heat' }] });
  });
});

describe('TerminologyPicker', () => {
  it('searches, selects, toggles favourites and shows recent codes', async () => {
    const user = userEvent.setup();
    const picked: unknown[] = [];
    const api = mockApi({
      'GET /terminology/favourites': sequence([], [{ system: 'LOINC', code: '2345-7', display: 'Glucose' }]),
      'GET /terminology/search': [{ system: 'LOINC', code: '2345-7', display: 'Glucose' }],
      'POST /terminology/favourites': undefined,
      'GET /terminology/recent': [{ system: 'LOINC', code: '718-7', display: 'Haemoglobin' }],
    });
    render(<TerminologyPicker client={api.client} onSessionExpired={() => {}} label="Test" system="LOINC" onSelect={(c) => picked.push(c)} />);
    expect(screen.queryByLabelText('Code system')).toBeNull(); // fixed system
    await user.type(screen.getByLabelText('Search Loinc'), 'gl');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await user.click(await screen.findByRole('button', { name: 'Add 2345-7 to favourites' }));
    await waitFor(() => expect(api.find('POST', '/terminology/favourites')[0]?.body).toEqual({ system: 'LOINC', code: '2345-7' }));
    expect(await screen.findByRole('button', { name: 'Remove 2345-7 from favourites' })).toBeTruthy();
    await user.click(screen.getByRole('button', { name: /2345-7 Glucose/ }));
    expect(picked).toEqual([{ system: 'LOINC', code: '2345-7', display: 'Glucose' }]);
    await user.click(screen.getByRole('button', { name: 'Recent' }));
    expect(await screen.findByRole('button', { name: /718-7 Haemoglobin/ })).toBeTruthy();
  });
});
