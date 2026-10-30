// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it } from 'vitest';
import type { AuthClient } from '../../auth/AuthClient';
import { mockApi, sessionFor } from '../../testing/mockApi';
import { Advisories } from './Advisories';
import { CommunicableCase, CommunicableList } from './Communicable';
import { TravelConsultations } from './Consultations';
import { TravelDestinations } from './Destinations';
import { ExposureDetail, ExposureList } from './Exposures';
import { PublicHealthReports } from './PublicHealth';
import { VaccinationCampaigns } from './VaccinationCampaigns';

afterEach(cleanup);
const noop = () => {};
const inMin = (m: number) => new Date(Date.now() + m * 60_000).toISOString();

describe('E11 screens smoke (initial render, no effects)', () => {
  const client: AuthClient = { mode: 'bff', init: async () => sessionFor(), login: async () => undefined, logout: async () => undefined, api: () => new Promise(() => {}) };
  const base = { client, session: sessionFor('OH_PHYSICIAN', 'NURSING_HEAD'), onSessionExpired: noop };
  const screens: [string, ReactElement][] = [
    ['TravelDestinations', <TravelDestinations {...base} />],
    ['TravelConsultations', <TravelConsultations {...base} />],
    ['CommunicableList', <CommunicableList {...base} />],
    ['CommunicableCase', <CommunicableCase {...base} caseId="cd1" />],
    ['ExposureList', <ExposureList {...base} />],
    ['ExposureDetail', <ExposureDetail {...base} exposureId="x1" />],
    ['VaccinationCampaigns', <VaccinationCampaigns {...base} />],
    ['Advisories', <Advisories {...base} />],
    ['PublicHealthReports', <PublicHealthReports {...base} />],
  ];
  it.each(screens)('%s renders', (_, el) => {
    expect(renderToStaticMarkup(el)).toContain('<h1');
  });
});

const destinations = [
  { countryCode: 'SG', name: 'Singapore', risks: ['Dengue'], recommendedVaccines: ['HEPA', 'TYPHOID'], prophylaxis: [], advice: 'Bite avoidance.', source: 'IN_HOUSE', reviewedOn: '2026-06-01' },
  { countryCode: 'KE', name: 'Kenya', risks: ['Malaria'], recommendedVaccines: ['YF'], prophylaxis: ['Atovaquone-proguanil'], advice: 'Malaria prophylaxis.', source: 'IN_HOUSE', reviewedOn: '2024-01-01' },
];

describe('TravelDestinations', () => {
  it('physicians edit destination content; nurses read only', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /travel/destinations': destinations, 'PUT /travel/destinations/SG': destinations[0] });
    render(<TravelDestinations client={api.client} session={sessionFor('OH_PHYSICIAN')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'Kenya (KE)' }));
    expect(screen.getByText(/Reviewed .* – due/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Singapore (SG)' }));
    await user.click(screen.getByRole('button', { name: 'Edit Singapore' }));
    const dialog = screen.getByRole('dialog');
    const vaccines = within(dialog).getByLabelText(/Recommended vaccines/);
    await user.clear(vaccines);
    await user.type(vaccines, 'hepa, typhoid, rabies');
    await user.click(within(dialog).getByRole('button', { name: 'Save destination' }));
    await waitFor(() => expect(api.find('PUT', '/travel/destinations/SG')[0]?.body).toMatchObject({ countryCode: 'SG', recommendedVaccines: ['HEPA', 'TYPHOID', 'RABIES'], risks: ['Dengue'] }));
    cleanup();
    render(<TravelDestinations client={mockApi({ 'GET /travel/destinations': destinations }).client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Singapore (SG)' }));
    expect(screen.queryByRole('button', { name: 'Edit Singapore' })).toBeNull();
  });
});

describe('TravelConsultations', () => {
  it('recommendations vs immunisation status, vaccine with scanned lot, validation, certificate link', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /travel/destinations': destinations,
      'GET /intake/employees/lookup': [{ employeeId: 'E1002', displayName: 'Cara Crew' }],
      'GET /travel/consultations': [],
      'GET /travel/recommendations': [{ vaccineCode: 'HEPA', vaccineName: 'Hepatitis A', status: 'COMPLETE', destinations: ['SG'] }, { vaccineCode: 'TYPHOID', vaccineName: 'Typhoid', status: 'OVERDUE', destinations: ['SG'] }],
      'POST /travel/consultations': { id: 't1', ref: 'TRV-2026-000001', employeeId: 'E1002', trip: { destinations: ['SG'], departDate: '2099-01-10', returnDate: '2099-01-20', purpose: 'LAYOVER' }, certificateDocumentId: 'doc9' },
    });
    render(<TravelConsultations client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    await user.type(screen.getByLabelText(/Find employee/), 'Cara');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await user.click(await screen.findByRole('button', { name: 'Cara Crew (E1002)' }));
    await user.click(await screen.findByLabelText('Singapore'));
    expect(await screen.findByText('Covered')).toBeTruthy();
    expect(screen.getByText('Overdue')).toBeTruthy();
    expect(api.find('GET', '/travel/recommendations?employeeId=E1002&destinations=SG')).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: 'Record TYPHOID dose' }));
    expect((screen.getByLabelText(/^Vaccine code/) as HTMLInputElement).value).toBe('TYPHOID');
    await user.click(screen.getByRole('button', { name: 'Record consultation and issue certificate' }));
    const summary = screen.getByRole('alert');
    expect(within(summary).getByText('Enter the departure date.')).toBeTruthy();
    expect(within(summary).getByText(/Vaccine 1: Scan or type the lot number/)).toBeTruthy();
    expect(api.find('POST', '/travel/consultations')).toHaveLength(0);
    await user.type(screen.getByLabelText(/^Departure/), '2099-01-10');
    await user.type(screen.getByLabelText(/^Return/), '2099-01-20');
    await user.selectOptions(screen.getByLabelText(/^Purpose/), 'LAYOVER');
    await user.type(screen.getByLabelText(/^Lot \(vaccine 1\)/), '(17)991231(10)TY-55');
    await user.click(screen.getByRole('button', { name: 'Record consultation and issue certificate' }));
    await waitFor(() =>
      expect(api.find('POST', '/travel/consultations')[0]?.body).toEqual({
        employeeId: 'E1002',
        trip: { destinations: ['SG'], departDate: '2099-01-10', returnDate: '2099-01-20', purpose: 'LAYOVER' },
        vaccinesGiven: [{ vaccineCode: 'TYPHOID', doseNumber: 1, lotNumber: 'TY-55', site: 'LEFT_DELTOID', route: 'IM' }],
        prophylaxis: [],
      }),
    );
    expect((await screen.findByRole('link', { name: 'Open the travel health certificate' })).getAttribute('href')).toBe('/bff/api/documents/doc9/content');
  });
});

describe('CommunicableCase', () => {
  const cdCase = { id: 'cd1', ref: 'CD-2026-000001', status: 'OPEN', notifiable: true, reportDueAt: inMin(600), employeeId: 'E1003', displayName: 'Nora Norovirus', conditionCode: 'NOROVIRUS', onsetDate: '2026-10-29', confirmedOn: '2026-10-30' };
  const conditions = [{ code: 'NOROVIRUS', name: 'Norovirus', notifiable: true, reportWithinHours: 24, exclusionDays: 2, foodHandlerExclusion: true, monitoringDays: 3 }];
  const contacts = [
    { id: 'k1', employeeId: 'E1007', displayName: 'Liam Low', contactType: 'FACILITY', dutyRef: 'LHR-CREW-ROOM', overlapMinutes: 30, riskLevel: 'LOW' },
    { id: 'k2', employeeId: 'E1002', displayName: 'Hana High', contactType: 'PROXIMITY_GROUP', dutyRef: 'WZ101', overlapMinutes: 780, riskLevel: 'HIGH' },
    { id: 'k3', employeeId: 'E7001', displayName: 'Mo Medium', contactType: 'SAME_DUTY', dutyRef: 'WZ101', overlapMinutes: 780, riskLevel: 'MEDIUM', notifiedAt: '2026-10-30T09:00:00Z', symptomatic: true, lastCheckIn: { at: '2026-10-30T09:30:00Z', symptoms: ['VOMITING'] } },
  ];

  it('exclusion with return criteria, tracing with window + include, risk-sorted contacts, 4 h KPI, notify', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /communicable/cases/cd1': cdCase,
      'GET /communicable/conditions': conditions,
      'GET /communicable/cases/cd1/contacts': contacts,
      'POST /communicable/cases/cd1/exclusion': cdCase,
      'POST /communicable/cases/cd1/trace': { generatedAt: '2026-10-30T10:00:00Z', hoursSinceConfirmation: 2.5, contacts },
      'POST /communicable/cases/cd1/contacts/notify': {},
    });
    render(<CommunicableCase client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} caseId="cd1" />);
    expect(await screen.findByText('Not generated yet')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Draft public-health report' })).toBeNull(); // OH_PHYSICIAN / CMO only

    // exclusion: criteria are required for EXCLUDED; the condition's 2 exclusion days pre-fill "until"
    await user.click(screen.getByRole('button', { name: 'Record decision' }));
    expect(screen.getByText('List at least one return-to-work criterion.', { selector: 'a' })).toBeTruthy();
    await user.type(screen.getByLabelText(/Return criteria/), '48 h symptom-free{Enter}Hygiene briefing');
    await user.click(screen.getByRole('button', { name: 'Record decision' }));
    await waitFor(() => expect(api.find('POST', '/communicable/cases/cd1/exclusion')[0]?.body).toMatchObject({ decision: 'EXCLUDED', returnCriteria: ['48 h symptom-free', 'Hygiene briefing'] }));
    expect((api.find('POST', '/communicable/cases/cd1/exclusion')[0]?.body as { until: string }).until).toMatch(/^\d{4}-\d{2}-\d{2}$/);

    // tracing
    await user.click(screen.getByLabelText('Facility'));
    await user.click(screen.getByRole('button', { name: 'Generate contact list' }));
    await waitFor(() => expect(api.find('POST', '/communicable/cases/cd1/trace')[0]?.body).toMatchObject({ include: ['SAME_DUTY', 'PROXIMITY_GROUP', 'FACILITY'] }));
    const body = api.find('POST', '/communicable/cases/cd1/trace')[0]?.body as { windowFrom: string; windowTo: string };
    expect(Date.parse(body.windowTo)).toBeGreaterThan(Date.parse(body.windowFrom));
    expect((await screen.findAllByText('Contact list generated in 2.5 h')).length).toBeGreaterThan(0);
    expect(screen.getByText('Within target')).toBeTruthy();

    const table = screen.getByRole('table', { name: /Contacts \(3\)/ });
    const names = within(table).getAllByRole('rowheader').map((r) => r.textContent);
    expect(names).toEqual(['Hana High E1002', 'Mo Medium E7001', 'Liam Low E1007']);
    expect(within(table).getByText('High risk').getAttribute('title')).toMatch(/15 minutes/);
    expect(within(table).getAllByText('13 h')).toHaveLength(2);
    expect(within(table).getByText('Symptomatic')).toBeTruthy();
    expect(within(table).getByText(/vomiting/)).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Select all not yet notified' }));
    await user.click(screen.getByRole('button', { name: 'Notify 2 selected' }));
    await waitFor(() => expect(api.find('POST', '/communicable/cases/cd1/contacts/notify')[0]?.body).toEqual({ contactIds: ['k2', 'k1'] }));
  });

  it('flags a late contact list and lets physicians draft the public-health report', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /communicable/cases/cd1': { ...cdCase, hoursSinceConfirmation: 6.2 },
      'GET /communicable/conditions': conditions,
      'GET /communicable/cases/cd1/contacts': [],
      'POST /public-health/reports': { id: 'ph1', ref: 'PH-2026-000001', authorityCode: 'UKHSA_LOCAL', status: 'DRAFT' },
    });
    render(<CommunicableCase client={api.client} session={sessionFor('OH_PHYSICIAN')} onSessionExpired={noop} caseId="cd1" />);
    expect(await screen.findByText('Contact list generated in 6.2 h')).toBeTruthy();
    expect(screen.getByText('off target')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Draft public-health report' }));
    await waitFor(() => expect(api.find('POST', '/public-health/reports')[0]?.body).toEqual({ communicableCaseId: 'cd1', authorityCode: 'UKHSA_LOCAL' }));
  });
});

describe('Exposures', () => {
  const exposure = {
    id: 'x1', ref: 'EXP-2026-000001', caseId: 'c9', employeeId: 'E1004', displayName: 'Rami Ramp', pathway: 'NEEDLESTICK_BBP', occurredAt: inMin(-150), sourceKnown: true,
    timers: [
      { code: 'FIRST_AID', label: 'First aid', dueAt: inMin(-140), status: 'DONE', completedAt: inMin(-145) },
      { code: 'RISK_ASSESSMENT', label: 'Risk assessment', dueAt: inMin(-90), status: 'PENDING' },
      { code: 'PEP_DECISION', label: 'PEP decision', dueAt: inMin(20), status: 'PENDING' },
    ],
    serology: [{ dueOn: '2099-01-01', done: false, label: '6 weeks' }],
  };

  it('detail: live countdown, overdue highlighting, complete a task, serology', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /exposures/x1': exposure, 'GET /exposures/chemicals': [], 'POST /exposures/x1/tasks/RISK_ASSESSMENT/complete': exposure });
    render(<ExposureDetail client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} exposureId="x1" />);
    const table = await screen.findByRole('table', { name: 'Protocol tasks' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.map((r) => within(r).getByRole('rowheader').textContent)).toEqual(['Risk assessment', 'PEP decision', 'First aid']);
    expect(rows[0]!.className).toBe('row-breached');
    expect(within(rows[0]!).getByText(/Overdue by 1 h 30 min/)).toBeTruthy();
    expect(within(rows[0]!).getByText(/^−01:(29|30):\d\d$/)).toBeTruthy();
    expect(within(rows[1]!).getByText(/^(19|20) min left$/)).toBeTruthy();
    expect(screen.getByText('Next: Risk assessment')).toBeTruthy();
    expect(screen.getByText('6 weeks', { exact: false })).toBeTruthy();
    await user.click(within(rows[0]!).getByRole('button', { name: 'Complete Risk assessment' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText(/Note/), 'Low risk – source known');
    await user.click(within(dialog).getByRole('button', { name: 'Mark complete' }));
    await waitFor(() => expect(api.find('POST', '/exposures/x1/tasks/RISK_ASSESSMENT/complete')[0]?.body).toEqual({ note: 'Low risk – source known' }));
  });

  it('chemical exposure shows a safe SDS link; javascript: links are never rendered', async () => {
    const chemicals = [
      { code: 'SKYDROL', name: 'Skydrol LD-4', unNumber: '3082', sdsUrl: 'https://sds.example.com/skydrol.pdf', hazards: ['Eye irritant'] },
      { code: 'BAD', name: 'Bad link', sdsUrl: 'javascript:alert(1)', hazards: 'x' },
    ];
    const api = mockApi({ 'GET /exposures/x2': { ...exposure, id: 'x2', pathway: 'CHEMICAL_DGR', chemicalCode: 'SKYDROL', timers: [], serology: [] }, 'GET /exposures/chemicals': chemicals });
    render(<ExposureDetail client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} exposureId="x2" />);
    const link = await screen.findByRole('link', { name: /Safety data sheet: Skydrol LD-4 \(UN 3082\)/ });
    expect(link.getAttribute('href')).toBe('https://sds.example.com/skydrol.pdf');
    expect(link.getAttribute('rel')).toContain('noopener');
    cleanup();
    render(<ExposureDetail client={mockApi({ 'GET /exposures/x3': { ...exposure, id: 'x3', pathway: 'CHEMICAL_DGR', chemicalCode: 'BAD', timers: [], serology: [] }, 'GET /exposures/chemicals': chemicals }).client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} exposureId="x3" />);
    expect(await screen.findByText('No valid safety data sheet link for Bad link')).toBeTruthy();
  });

  it('list: overdue rows highlighted and counted; chemical pathway requires a chemical', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /exposures': [exposure], 'GET /exposures/chemicals': [] });
    render(<ExposureList client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    const link = await screen.findByRole('link', { name: 'EXP-2026-000001' });
    expect(link.closest('tr')?.className).toBe('row-breached');
    expect(within(screen.getByRole('group', { name: 'Pathway KPIs' })).getByText('Overdue tasks').previousSibling?.textContent).toBe('1');
    await user.selectOptions(screen.getByLabelText(/^Pathway/), 'CHEMICAL_DGR');
    await user.click(screen.getByRole('button', { name: 'Start pathway' }));
    const summary = screen.getByRole('alert');
    expect(within(summary).getByText('Choose the employee.')).toBeTruthy();
    expect(within(summary).getByText('Choose the chemical from the register.')).toBeTruthy();
    expect(api.find('POST', '/exposures')).toHaveLength(0);
  });
});

describe('VaccinationCampaigns', () => {
  const campaign = { id: 'vc1', name: 'Flu 2026', vaccineCode: 'FLU', eligibility: { all: true }, startDate: '2026-10-01', endDate: '2026-12-15', siteCodes: ['LHR'], targetUptakePct: 75 };

  it('fast entry (E07 pattern): scanned lot + badge, consent, Enter → administration with the campaign id path', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /vaccination-campaigns': [campaign], 'POST /vaccination-campaigns/vc1/administrations': {} });
    render(<VaccinationCampaigns client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'Flu 2026' }));
    expect(screen.queryByRole('button', { name: 'Send invitations' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Uptake' })).toBeNull();
    await user.type(screen.getByLabelText(/^Lot/), '(17)991231(10)FL-1{Enter}');
    expect(document.activeElement?.id).toBe('vc-person');
    await user.type(screen.getByLabelText('Badge or employee ID'), '0004417733{Enter}');
    expect(api.find('POST', '/vaccination-campaigns/vc1/administrations')).toHaveLength(0);
    expect(document.activeElement).toBe(screen.getByLabelText('Consent confirmed'));
    await user.click(screen.getByLabelText('Consent confirmed'));
    await user.click(screen.getByRole('button', { name: 'Record dose' }));
    await waitFor(() =>
      expect(api.find('POST', '/vaccination-campaigns/vc1/administrations')[0]?.body).toEqual({ badgeId: '0004417733', lotBarcode: '(17)991231(10)FL-1', site: 'LEFT_DELTOID', route: 'IM', consentConfirmed: true }),
    );
    expect(await screen.findByRole('table', { name: 'Recorded this session (1)' })).toBeTruthy();
  });

  it('nursing head: create (validated), invite, decline, adverse event, uptake with suppression', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /vaccination-campaigns': [campaign],
      'POST /vaccination-campaigns': { ...campaign, id: 'vc2', name: 'Hep B' },
      'POST /vaccination-campaigns/vc1/invite': { invited: 120 },
      'POST /vaccination-campaigns/vc1/declines': {},
      'POST /vaccination-campaigns/vc1/adverse-events': {},
      'GET /vaccination-campaigns/vc1/uptake': [
        { key: 'GND', eligible: 40, vaccinated: 32, declined: 3, uptakePct: 80, suppressed: false },
        { key: 'CAT', eligible: 20, vaccinated: 8, declined: 6, uptakePct: 40, suppressed: false },
        { key: 'MRO', suppressed: true },
      ],
    });
    render(<VaccinationCampaigns client={api.client} session={sessionFor('NURSING_HEAD')} onSessionExpired={noop} />);
    await user.click(await screen.findByText('New vaccination campaign'));
    await user.click(screen.getByRole('button', { name: 'Create campaign' }));
    expect(within(screen.getByRole('alert')).getByText('Enter a campaign name.')).toBeTruthy();
    expect(api.find('POST', '/vaccination-campaigns')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Flu 2026' }));
    await user.click(screen.getByRole('button', { name: 'Send invitations' }));
    expect(await screen.findByText('120 invitations sent.')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Declines' }));
    await user.type(screen.getByLabelText(/^Employee ID/), 'e1004');
    await user.type(screen.getByLabelText(/^Reason/), 'Prefers GP');
    await user.click(screen.getByRole('button', { name: 'Record decline' }));
    await waitFor(() => expect(api.find('POST', '/vaccination-campaigns/vc1/declines')[0]?.body).toEqual({ employeeId: 'E1004', reason: 'Prefers GP' }));

    await user.click(screen.getByRole('button', { name: 'Adverse events' }));
    await user.type(screen.getByLabelText(/^Employee ID/), 'E1009');
    await user.selectOptions(screen.getByLabelText('Event'), 'ANAPHYLAXIS');
    expect(screen.getByText(/anaphylaxis \/ emergency protocol/)).toBeTruthy();
    await user.type(screen.getByLabelText(/^Actions taken/), 'Adrenaline IM, 999');
    await user.click(screen.getByRole('button', { name: 'Record adverse event' }));
    await waitFor(() => expect(api.find('POST', '/vaccination-campaigns/vc1/adverse-events')[0]?.body).toMatchObject({ employeeId: 'E1009', event: 'ANAPHYLAXIS', severity: 'MILD', actions: 'Adrenaline IM, 999' }));

    await user.click(screen.getByRole('button', { name: 'Uptake' }));
    const table = await screen.findByRole('table', { name: /Uptake by subsidiary/ });
    expect(within(table).getByText('80.0 %')).toBeTruthy();
    expect(within(table).getByText('Suppressed – group too small to report')).toBeTruthy();
    expect(table.textContent).not.toMatch(/MRO.*\d/);
    // totals over reported rows only: 40 / 60 = 66.7 % vs target 75 %
    expect(screen.getByText('66.7 %')).toBeTruthy();
    expect(screen.getByText('off target')).toBeTruthy();
  });
});

describe('Advisories and public health', () => {
  it('physicians author advisories with targeting; acknowledgement rate and pending list', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /advisories': [{ id: 'a1', title: 'Measles – Singapore', destinationCode: 'SG', roles: ['CABIN_CREW'], severity: 'WARNING', body: 'Check MMR.', validFrom: '2026-10-30', validUntil: '2026-11-30', targeted: 40, acknowledged: 30, ratePct: 75 }],
      'GET /travel/destinations': destinations,
      'GET /advisories/a1/acknowledgements': { targeted: 40, acknowledged: 30, ratePct: 75, pending: [{ employeeId: 'E1002', displayName: 'Cara Crew' }] },
      'POST /advisories': { id: 'a2', title: 'Dengue – Kenya', roles: [], severity: 'INFO', body: 'x', validFrom: '2026-10-30', validUntil: '2026-12-01', targeted: 12 },
    });
    render(<Advisories client={api.client} session={sessionFor('AME')} onSessionExpired={noop} />);
    expect((await screen.findAllByText('75 % (30 of 40)')).length).toBe(1);
    await user.click(screen.getByRole('button', { name: 'Measles – Singapore' }));
    expect(await screen.findByText('Cara Crew (E1002)')).toBeTruthy();
    await user.click(screen.getByText('Author an advisory'));
    await user.type(screen.getByLabelText(/^Title/), 'Dengue – Kenya');
    await user.click(screen.getByRole('button', { name: 'Publish advisory' }));
    expect(within(screen.getByRole('alert')).getByText('Target a destination, a base or at least one role.')).toBeTruthy();
    await user.selectOptions(await screen.findByLabelText(/^Destination/), 'KE');
    await user.type(screen.getByLabelText(/^Advisory text/), 'Use repellent.');
    await user.type(screen.getByLabelText(/^Valid until/), '2026-12-01');
    await user.click(screen.getByRole('button', { name: 'Publish advisory' }));
    await waitFor(() => expect(api.find('POST', '/advisories')[0]?.body).toMatchObject({ title: 'Dengue – Kenya', destinationCode: 'KE', roles: [], severity: 'INFO', body: 'Use repellent.', validUntil: '2026-12-01' }));
    expect(await screen.findByText('Advisory published to 12 people.')).toBeTruthy();
    cleanup();
    render(<Advisories client={mockApi({ 'GET /advisories': [] }).client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    await screen.findByText('No advisories.');
    expect(screen.queryByText('Author an advisory')).toBeNull();
  });

  it('public-health reports: hours left, overdue first, submit and correspondence log (OH_PHYSICIAN); read-only for nurses', async () => {
    const user = userEvent.setup();
    const reports = [
      { id: 'p1', ref: 'PH-1', communicableCaseId: 'cd1', authorityCode: 'UKHSA_LOCAL', status: 'DRAFT', dueAt: inMin(300), hoursLeft: 5, documentId: 'd1', correspondence: [{ id: 'm1', direction: 'IN', summary: 'Authority asked for contact numbers', at: '2026-10-30T09:00:00Z' }] },
      { id: 'p2', ref: 'PH-2', exposureId: 'x1', authorityCode: 'UKHSA_LOCAL', status: 'DRAFT', hoursLeft: -3 },
      { id: 'p3', ref: 'PH-3', authorityCode: 'UKHSA_LOCAL', status: 'SUBMITTED', submittedAt: '2026-10-29T09:00:00Z' },
    ];
    const api = mockApi({ 'GET /public-health/reports': reports, 'GET /communicable/cases': [], 'GET /exposures': [], 'POST /public-health/reports/p1/submit': reports[0], 'POST /public-health/reports/p1/correspondence': {} });
    render(<PublicHealthReports client={api.client} session={sessionFor('OH_PHYSICIAN')} onSessionExpired={noop} />);
    const table = await screen.findByRole('table', { name: /2 reports/ });
    expect(within(table).getByText('Overdue by 3 h').closest('tr')?.className).toBe('row-breached');
    expect(within(table).getByText('5 h left')).toBeTruthy();
    expect(within(table).queryByText('PH-3')).toBeNull();
    await user.click(within(table).getByRole('button', { name: 'PH-1' }));
    expect(screen.getByRole('link', { name: 'Public-health report (PDF)' }).getAttribute('href')).toBe('/bff/api/documents/d1/content');
    expect(screen.getByText('Authority asked for contact numbers')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Record submission' }));
    expect(within(screen.getByRole('alert')).getByText('Enter the portal reference.')).toBeTruthy();
    await user.type(screen.getByLabelText(/^Reference/), 'UKHSA-77');
    await user.click(screen.getByRole('button', { name: 'Record submission' }));
    await waitFor(() => expect(api.find('POST', '/public-health/reports/p1/submit')[0]?.body).toMatchObject({ channel: 'PORTAL', reference: 'UKHSA-77' }));
    await user.type(screen.getByLabelText(/^Summary/), 'Sent contact list');
    await user.click(screen.getByRole('button', { name: 'Log correspondence' }));
    await waitFor(() => expect(api.find('POST', '/public-health/reports/p1/correspondence')[0]?.body).toEqual({ direction: 'OUT', summary: 'Sent contact list' }));
    cleanup();
    render(<PublicHealthReports client={mockApi({ 'GET /public-health/reports': reports }).client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'PH-1' }));
    expect(screen.queryByRole('button', { name: 'Record submission' })).toBeNull();
    expect(screen.queryByText('Draft a public-health report')).toBeNull();
  });
});
