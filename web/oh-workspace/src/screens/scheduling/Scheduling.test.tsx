// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderToStaticMarkup } from 'react-dom/server';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import type { AuthClient, Session } from '../../auth/AuthClient';
import { mockApi, reply, sequence, sessionFor } from '../../testing/mockApi';
import { CampaignDetail, Campaigns } from './Campaigns';
import { Capacity } from './Capacity';
import { Catalogue } from './Catalogue';
import { QueueBoardScreen, ReceptionKiosk } from './ClinicFlow';
import { Diary } from './Diary';
import { EquipmentScreen } from './Equipment';

afterEach(cleanup);

const sites = [{ code: 'HQ-MC', name: 'HQ Medical Centre', siteType: 'HQ_MEDICAL_CENTRE', locationCode: 'HQ', timeZone: 'UTC', active: true }];
const appt = {
  id: 'a1', employeeId: 'E1004', displayName: 'Rami Ramp', appointmentType: 'AUDIOMETRY', appointmentTypeName: 'Audiometry', siteCode: 'HQ-MC',
  startsAt: '2026-10-29T09:00:00Z', endsAt: '2026-10-29T09:20:00Z', status: 'BOOKED', clinicianUsername: 'nurse1', roomCode: 'AUD-1', version: 3, telehealth: false,
};
const diary = { siteCode: 'HQ-MC', date: '2026-10-29', sessions: [{ clinicianUsername: 'nurse1', clinicianName: 'Nina Nurse', roomCode: 'AUD-1', start: '08:00', end: '16:00', appointmentTypes: ['AUDIOMETRY'], telehealth: false }], appointments: [appt] };
const resources = [
  { id: 'r1', siteCode: 'HQ-MC', type: 'CLINICIAN', code: 'nurse1', name: 'Nina Nurse', username: 'nurse1', active: true },
  { id: 'r2', siteCode: 'HQ-MC', type: 'ROOM', code: 'AUD-1', name: 'Audio booth 1', roomType: 'AUDIO_BOOTH', active: true },
  { id: 'r3', siteCode: 'HQ-MC', type: 'CLINICIAN', code: 'nurse2', name: 'Noel Nurse', username: 'nurse2', active: true },
];
const types = [
  { code: 'AUDIOMETRY', name: 'Audiometry', durationMinutes: 20, roomType: 'AUDIO_BOOTH', deviceCategory: 'AUDIOMETER', telehealth: false, selfBookable: false, chargeCode: 'OH-AUD', internalPrice: 35, active: true },
  { code: 'TELEHEALTH_CONSULT', name: 'Telehealth consult', durationMinutes: 20, telehealth: true, selfBookable: false, active: true },
];
const base = { 'GET /masterdata/clinic-sites': sites, 'GET /scheduling/sites/HQ-MC/resources': resources, 'GET /scheduling/appointment-types': types };
const props = (client: AuthClient, ...roles: string[]) => ({ client, session: sessionFor(...roles), onSessionExpired: () => {} });

describe('Diary', () => {
  it('shows sessions and appointments and books with type / clinician / room from a free slot', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      ...base,
      'GET /scheduling/diary': diary,
      'GET /scheduling/slots': [{ startsAt: '2026-10-30T10:00:00Z', endsAt: '2026-10-30T10:20:00Z', clinicianUsername: 'nurse2', roomCode: 'AUD-1', deviceId: 'AUD-LHR-01' }],
      'POST /scheduling/appointments': { ...appt, id: 'a2', employeeId: 'E1007', displayName: 'Sam Screener', startsAt: '2026-10-30T10:00:00Z' },
    });
    render(<Diary {...props(api.client, 'CLINIC_SCHEDULER')} />);
    expect(await screen.findByRole('button', { name: /09:00 Rami Ramp, Audiometry, Booked/ })).toBeTruthy();
    expect(screen.getAllByText('Nina Nurse').length).toBeGreaterThan(0);

    await user.click(screen.getByRole('button', { name: 'Book appointment' }));
    const dlg = screen.getByRole('dialog', { name: 'Book appointment' });
    await user.type(within(dlg).getByLabelText(/Employee ID/), 'e1007');
    await user.selectOptions(within(dlg).getByLabelText(/Appointment type/), 'AUDIOMETRY');
    await user.click(within(dlg).getByRole('button', { name: /Find free slots/ }));
    await user.click(await within(dlg).findByRole('button', { name: /2026-10-30 10:00 · nurse2 · AUD-1/ }));
    await user.click(within(dlg).getByRole('button', { name: 'Book' }));
    await waitFor(() => expect(api.find('POST', '/scheduling/appointments')).toHaveLength(1));
    expect(api.find('GET', '/scheduling/slots')[0]?.path).toMatch(/type=AUDIOMETRY&siteCode=HQ-MC&from=\d{4}-\d{2}-\d{2}&to=/);
    expect(api.find('POST', '/scheduling/appointments')[0]?.body).toEqual({
      employeeId: 'E1007', appointmentType: 'AUDIOMETRY', siteCode: 'HQ-MC', startsAt: '2026-10-30T10:00:00.000Z', clinicianUsername: 'nurse2', roomCode: 'AUD-1', deviceId: 'AUD-LHR-01', sourceType: 'MANUAL',
    });
    expect(await screen.findByText(/Booked Audiometry for Sam Screener at 10:00/)).toBeTruthy();
  });

  it('moves with Alt+Arrow keys and reports a stale (409) move, then refreshes', async () => {
    const api = mockApi({ ...base, 'GET /scheduling/diary': diary, 'PUT /scheduling/appointments/a1/move': sequence({ ...appt, startsAt: '2026-10-29T09:15:00Z' }, reply(409, { code: 'stale', detail: 'Version mismatch' })) });
    render(<Diary {...props(api.client, 'CLINIC_SCHEDULER')} />);
    const card = await screen.findByRole('button', { name: /09:00 Rami Ramp/ });
    fireEvent.keyDown(card, { key: 'ArrowDown', altKey: true });
    await waitFor(() => expect(api.find('PUT', '/scheduling/appointments/a1/move')[0]?.body).toEqual({ startsAt: '2026-10-29T09:15:00.000Z', clinicianUsername: 'nurse1', version: 3 }));
    expect(await screen.findByText(/Moved Rami Ramp to 09:15/)).toBeTruthy();
    const card2 = await screen.findByRole('button', { name: /09:00 Rami Ramp/ });
    fireEvent.keyDown(card2, { key: 'ArrowRight', altKey: true }); // only one clinician column → ignored
    expect(api.find('PUT', '/scheduling/appointments/a1/move')).toHaveLength(1);
    fireEvent.keyDown(card2, { key: 'ArrowUp', altKey: true });
    expect(await screen.findByText(/Someone else changed this appointment/)).toBeTruthy();
    await waitFor(() => expect(api.find('GET', '/scheduling/diary').length).toBeGreaterThanOrEqual(3));
  });

  it('drag-and-drop onto a clinician column moves; conflicts are reported', async () => {
    const api = mockApi({ ...base, 'GET /scheduling/diary': diary, 'PUT /scheduling/appointments/a1/move': reply(409, { code: 'resource_conflict', detail: 'Room AUD-1 is already booked' }) });
    render(<Diary {...props(api.client, 'CLINIC_SCHEDULER')} />);
    const card = await screen.findByRole('button', { name: /09:00 Rami Ramp/ });
    const store: Record<string, string> = {};
    const dataTransfer = { setData: (k: string, v: string) => (store[k] = v), getData: (k: string) => store[k] ?? '', get types() { return Object.keys(store); }, effectAllowed: '', dropEffect: '' };
    fireEvent.dragStart(card, { dataTransfer });
    const col = screen.getByRole('list', { name: /Nina Nurse/ });
    fireEvent.dragOver(col, { dataTransfer });
    fireEvent.drop(col, { dataTransfer, clientY: 0 });
    await waitFor(() => expect(api.find('PUT', '/scheduling/appointments/a1/move')).toHaveLength(1));
    expect((api.find('PUT', '/scheduling/appointments/a1/move')[0]?.body as { clinicianUsername: string; version: number })).toMatchObject({ clinicianUsername: 'nurse1', version: 3 });
    expect(await screen.findByText(/clashes with another booking: Room AUD-1 is already booked/)).toBeTruthy();
  });

  it('opens an appointment: cancel with reason, attendance, telehealth launch for clinicians', async () => {
    const user = userEvent.setup();
    const tele = { ...appt, id: 'a9', appointmentType: 'TELEHEALTH_CONSULT', appointmentTypeName: 'Telehealth consult', telehealth: true, startsAt: '2026-10-29T11:00:00Z', endsAt: '2026-10-29T11:20:00Z' };
    const api = mockApi({
      ...base,
      'GET /scheduling/diary': { ...diary, appointments: [appt, tele] },
      'POST /scheduling/appointments/a1/cancel': { ...appt, status: 'CANCELLED' },
      'POST /scheduling/appointments/a9/telehealth': { joinUrl: 'https://video.example/room/xyz', documentationLink: 'javascript:alert(1)' },
    });
    render(<Diary {...props(api.client, 'OH_CLINICIAN')} />);
    await user.click(await screen.findByRole('button', { name: /11:00 Rami Ramp, Telehealth consult/ }));
    let dlg = screen.getByRole('dialog');
    await user.click(within(dlg).getByRole('button', { name: 'Launch telehealth' }));
    const join = await within(dlg).findByRole('link', { name: /Join video consultation/ });
    expect(join.getAttribute('href')).toBe('https://video.example/room/xyz');
    expect(within(dlg).queryByRole('link', { name: 'Documentation' })).toBeNull();
    await user.click(within(dlg).getByRole('button', { name: 'Close dialog' }));

    await user.click(screen.getByRole('button', { name: /09:00 Rami Ramp, Audiometry/ }));
    dlg = screen.getByRole('dialog');
    await user.click(within(dlg).getByRole('button', { name: 'Cancel appointment…' }));
    await user.type(within(dlg).getByLabelText(/Cancellation reason/), 'Clinic closed');
    await user.click(within(dlg).getByRole('button', { name: 'Cancel appointment' }));
    await waitFor(() => expect(api.find('POST', '/scheduling/appointments/a1/cancel')[0]?.body).toEqual({ reason: 'Clinic closed' }));
  });

  it('waitlist panel lists and adds entries', async () => {
    const user = userEvent.setup();
    const api = mockApi({ ...base, 'GET /scheduling/diary': diary, 'GET /scheduling/waitlist': [{ id: 'w1', employeeId: 'E1009', displayName: 'Cole Cleaner', appointmentType: 'AUDIOMETRY', siteCode: 'HQ-MC', earliest: '2026-11-01', latest: '2026-11-15', priority: 2, status: 'WAITING' }], 'POST /scheduling/waitlist': {} });
    render(<Diary {...props(api.client, 'CLINIC_SCHEDULER')} />);
    await user.click(await screen.findByRole('button', { name: 'Waitlist' }));
    expect(await screen.findByText('Cole Cleaner')).toBeTruthy();
    const panel = screen.getByRole('complementary');
    await user.type(within(panel).getByLabelText(/Employee ID/), 'e1010');
    await user.selectOptions(within(panel).getByLabelText(/Appointment type/), 'AUDIOMETRY');
    await user.click(within(panel).getByRole('button', { name: 'Add to waitlist' }));
    await waitFor(() => expect(api.find('POST', '/scheduling/waitlist')[0]?.body).toMatchObject({ employeeId: 'E1010', appointmentType: 'AUDIOMETRY', siteCode: 'HQ-MC', priority: 3 }));
  });
});

describe('Reception kiosk', () => {
  it('checks in by employee id and shows only the ticket', async () => {
    const user = userEvent.setup();
    const api = mockApi({ ...base, 'POST /scheduling/check-in': { appointmentId: 'a1', ticket: 'A012', appointmentTypeName: 'Audiometry', position: 3, estimatedWaitMinutes: 12 } });
    render(<ReceptionKiosk {...props(api.client, 'CLINIC_RECEPTION')} />);
    await screen.findByRole('option', { name: /HQ Medical Centre/ });
    await user.click(screen.getByRole('radio', { name: 'Use employee ID' }));
    await user.type(screen.getByLabelText('Employee ID'), 'e1004{Enter}');
    expect(await screen.findByText('A012')).toBeTruthy();
    expect(api.find('POST', '/scheduling/check-in')[0]?.body).toEqual({ siteCode: 'HQ-MC', employeeId: 'E1004' });
    expect(screen.getByText(/12 min/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Next person' }));
    expect(screen.getByLabelText('Employee ID')).toBeTruthy();
  });

  it('shows the server problem when no appointment is found', async () => {
    const user = userEvent.setup();
    const api = mockApi({ ...base, 'POST /scheduling/check-in': reply(404, { detail: 'No appointment today at this site', correlationId: 'c-1' }) });
    render(<ReceptionKiosk {...props(api.client, 'CLINIC_RECEPTION')} />);
    await screen.findByRole('option', { name: /HQ Medical Centre/ });
    await user.type(screen.getByLabelText('Badge number'), '000123{Enter}');
    expect(await screen.findByText('No appointment today at this site')).toBeTruthy();
    expect(api.find('POST', '/scheduling/check-in')[0]?.body).toEqual({ siteCode: 'HQ-MC', badgeId: '000123' });
  });
});

describe('Queue board', () => {
  const board = {
    siteCode: 'HQ-MC',
    averageWaitMinutesToday: 14,
    entries: [
      { ticket: 'A010', appointmentTypeName: 'Audiometry', status: 'CALLED', checkedInAt: '2026-10-29T08:50:00Z', waitMinutes: 10, roomCode: 'AUD-1', displayName: 'Should Not Show' },
      { ticket: 'A011', appointmentTypeName: 'Vision', status: 'WAITING', checkedInAt: '2026-10-29T08:55:00Z', waitMinutes: 5 },
    ],
  };

  it('shows tickets and average wait, never names', async () => {
    const api = mockApi({ ...base, 'GET /scheduling/queue': board });
    render(<QueueBoardScreen {...props(api.client, 'CLINIC_RECEPTION')} />);
    expect(await screen.findByText('A010')).toBeTruthy();
    expect(screen.getByText('A011')).toBeTruthy();
    expect(screen.getByText('14 min')).toBeTruthy();
    expect(screen.getByText('AUD-1')).toBeTruthy();
    expect(screen.queryByText(/Should Not Show/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Call next patient' })).toBeNull();
    expect(api.find('GET', '/scheduling/queue')[0]?.path).toBe('/scheduling/queue?siteCode=HQ-MC');
  });

  it('clinician calls next and hands off to the chart encounters', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      ...base,
      'GET /scheduling/queue': board,
      'POST /scheduling/queue/call-next': { appointmentId: 'a1', ticket: 'A011', employeeId: 'E1004', displayName: 'Rami Ramp', appointmentType: 'VISION', encounterLink: '#/employees/E1004/chart/encounters' },
      'POST /scheduling/appointments/a1/start': { ...appt, status: 'IN_PROGRESS' },
    });
    render(<QueueBoardScreen {...props(api.client, 'OH_CLINICIAN')} />);
    await screen.findByText('A010');
    await user.type(screen.getByLabelText(/My room/), 'C2');
    await user.click(screen.getByRole('button', { name: 'Call next patient' }));
    const link = await screen.findByRole('link', { name: 'Open chart encounters' });
    expect(link.getAttribute('href')).toBe('#/employees/E1004/chart/encounters');
    expect(api.find('POST', '/scheduling/queue/call-next')[0]?.body).toEqual({ siteCode: 'HQ-MC', roomCode: 'C2' });
    await user.click(screen.getByRole('button', { name: 'Start consultation' }));
    await waitFor(() => expect(api.find('POST', '/scheduling/appointments/a1/start')).toHaveLength(1));
  });
});

const equipment = [
  { id: 'eq1', assetTag: 'AUD-LHR-01', deviceId: 'AUD-LHR-01', category: 'AUDIOMETER', model: 'AD629', siteCode: 'HQ-MC', status: 'IN_SERVICE', calibrationIntervalMonths: 12, calibrationDueOn: '2027-06-01', calibrationState: 'VALID', openExcursions: 0 },
  { id: 'eq2', assetTag: 'AUD-LHR-02', deviceId: 'AUD-LHR-02', category: 'AUDIOMETER', siteCode: 'HQ-MC', status: 'IN_SERVICE', calibrationIntervalMonths: 12, calibrationDueOn: '2026-01-01', calibrationState: 'OVERDUE', openExcursions: 0 },
  { id: 'eq3', assetTag: 'FRIDGE-HQ-01', category: 'VACCINE_FRIDGE', siteCode: 'HQ-MC', status: 'IN_SERVICE', calibrationIntervalMonths: 12, calibrationDueOn: '2026-11-10', calibrationState: 'DUE_SOON', minTempC: 2, maxTempC: 8, lastReading: { temperatureC: 9.2, recordedAt: '2026-10-29T06:00:00Z' }, openExcursions: 1 },
];

describe('Equipment', () => {
  it('BIOMEDICAL sees calibration badges and records a failed calibration', async () => {
    const user = userEvent.setup();
    const api = mockApi({ ...base, 'GET /scheduling/equipment': equipment, 'POST /scheduling/equipment/eq2/calibrations': {} });
    render(<EquipmentScreen {...props(api.client, 'BIOMEDICAL')} />);
    expect((await screen.findAllByText('AUD-LHR-02')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('Overdue').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Due soon').length).toBeGreaterThan(0);
    expect(screen.getByText('1 open excursion')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Record calibration for AUD-LHR-02' }));
    const dlg = screen.getByRole('dialog');
    await user.type(within(dlg).getByLabelText(/Vendor/), 'CalLab');
    await user.type(within(dlg).getByLabelText(/Certificate number/), 'CL-77');
    await user.click(within(dlg).getByRole('radio', { name: /Fail/ }));
    await user.click(within(dlg).getByRole('button', { name: 'Record calibration' }));
    await waitFor(() => expect(api.find('POST', '/scheduling/equipment/eq2/calibrations')[0]?.body).toMatchObject({ vendor: 'CalLab', certificateNumber: 'CL-77', result: 'FAIL' }));
    expect(await screen.findByText(/now out of service/)).toBeTruthy();
  });

  it('clinicians land on cold chain, enter a reading and acknowledge an excursion', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      ...base,
      'GET /scheduling/equipment': (_b: unknown, c: { path: string }) => (c.path.includes('VACCINE_FRIDGE') ? [equipment[2]] : equipment),
      'GET /scheduling/equipment/eq3/temperatures': [
        { recordedAt: '2026-10-28T06:00:00Z', temperatureC: 4.1, source: 'IOT', excursion: false },
        { recordedAt: '2026-10-29T06:00:00Z', temperatureC: 9.2, source: 'IOT', excursion: true },
      ],
      'POST /scheduling/equipment/eq3/temperatures': { recordedAt: '2026-10-29T08:00:00Z', temperatureC: 5, source: 'MANUAL', excursion: false },
      'GET /scheduling/excursions': [{ id: 'x1', equipmentId: 'eq3', assetTag: 'FRIDGE-HQ-01', siteCode: 'HQ-MC', startedAt: '2026-10-29T06:00:00Z', minC: 9.2, maxC: 9.2, status: 'OPEN' }],
      'POST /scheduling/excursions/x1/acknowledge': {},
    });
    render(<EquipmentScreen {...props(api.client, 'OH_CLINICIAN')} />);
    expect(await screen.findByRole('img', { name: /Temperature of FRIDGE-HQ-01: 2 readings, 1 outside 2–8 °C/ })).toBeTruthy();
    await user.type(screen.getByLabelText(/Temperature \(°C\)/), '5');
    await user.click(screen.getByRole('button', { name: 'Record reading' }));
    await waitFor(() => expect((api.find('POST', '/scheduling/equipment/eq3/temperatures')[0]?.body as { temperatureC: number }).temperatureC).toBe(5));
    await user.click(screen.getByRole('button', { name: 'Excursions' }));
    await user.click(await screen.findByRole('button', { name: 'Acknowledge excursion on FRIDGE-HQ-01' }));
    await user.type(screen.getByLabelText(/Action taken/), 'Stock quarantined');
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Acknowledge' }));
    await waitFor(() => expect(api.find('POST', '/scheduling/excursions/x1/acknowledge')[0]?.body).toEqual({ action: 'Stock quarantined' }));
    expect(screen.queryByRole('button', { name: /Record calibration/ })).toBeNull();
  });
});

const campaign = {
  id: 'cmp1', name: 'LHR audiometry day', campaignType: 'SURVEILLANCE', siteCode: 'HQ-MC', appointmentType: 'AUDIOMETRY', days: ['2026-10-29'], dayStart: '08:00', dayEnd: '16:00', slotMinutes: 20,
  stations: 4, deviceKit: ['eq1'], staff: ['nurse1'], targetCount: 96, status: 'OPEN', performance: { slots: 96, booked: 80, attended: 40, noShow: 2, utilisationPct: 83.3, attendancePct: 95.2 },
};

describe('Campaigns', () => {
  it('lists campaigns and creates one with expected slot preview', async () => {
    const user = userEvent.setup();
    const api = mockApi({ ...base, 'GET /scheduling/campaigns': [campaign], 'GET /scheduling/equipment': equipment, 'POST /scheduling/campaigns': { ...campaign, id: 'cmp2', name: 'Flu jabs' } });
    render(<Campaigns {...props(api.client, 'CLINIC_SCHEDULER')} />);
    expect((await screen.findByRole('link', { name: 'LHR audiometry day' })).getAttribute('href')).toBe('#/scheduling/campaigns/cmp1');
    await user.click(screen.getByRole('button', { name: 'New campaign' }));
    await user.click(screen.getByRole('button', { name: 'Create campaign' }));
    expect(await screen.findByText('Enter a campaign name.', { selector: 'a' })).toBeTruthy();
    await user.type(screen.getByLabelText(/^Name/), 'Flu jabs');
    await user.selectOptions(screen.getByLabelText(/Appointment type/), 'AUDIOMETRY');
    await user.click(screen.getByRole('checkbox', { name: /AUD-LHR-02/ }));
    expect(screen.getByText(/out of service or due for calibration/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Create campaign' }));
    await waitFor(() => expect(api.find('POST', '/scheduling/campaigns')).toHaveLength(1));
    const body = api.find('POST', '/scheduling/campaigns')[0]?.body as Record<string, unknown>;
    expect(body).toMatchObject({ name: 'Flu jabs', campaignType: 'SURVEILLANCE', siteCode: 'HQ-MC', appointmentType: 'AUDIOMETRY', slotMinutes: 20, stations: 4, deviceKit: ['eq2'], targetCount: 0 });
    expect((body.days as string[]).length).toBe(1);
  });

  it('detail: performance tiles, bulk booking from pasted ids, attendance grid', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      ...base,
      'GET /scheduling/campaigns/cmp1': campaign,
      'POST /scheduling/campaigns/cmp1/book': { booked: 2, waitlisted: 1 },
      'POST /scheduling/campaigns/cmp1/generate-slots': { slots: 96 },
      'GET /scheduling/appointments': [{ ...appt, campaignId: 'cmp1' }, { ...appt, id: 'other', campaignId: 'x', displayName: 'Not In Campaign' }],
      'POST /scheduling/campaigns/cmp1/attendance': {},
    });
    render(<CampaignDetail {...props(api.client, 'CLINIC_SCHEDULER')} campaignId="cmp1" />);
    expect(await screen.findByText('83%')).toBeTruthy();
    expect(screen.getByText('95%')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Generate slots' }));
    expect(await screen.findByText('96 slots generated.')).toBeTruthy();
    await user.type(screen.getByLabelText(/Employee IDs/), 'E1001, e1002\nE1001 bad!');
    expect(screen.getByText(/2 unique ids, 1 duplicate ignored/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Book 2 employees' }));
    await waitFor(() => expect(api.find('POST', '/scheduling/campaigns/cmp1/book')[0]?.body).toEqual({ employeeIds: ['E1001', 'E1002'] }));
    expect(await screen.findByText('2 booked, 1 waitlisted.')).toBeTruthy();

    expect(await screen.findByRole('radio', { name: 'Rami Ramp attended' })).toBeTruthy();
    expect(screen.queryByText('Not In Campaign')).toBeNull();
    await user.click(screen.getByRole('radio', { name: 'Rami Ramp did not attend' }));
    await user.click(screen.getByRole('button', { name: 'Save attendance (1)' }));
    await waitFor(() => expect(api.find('POST', '/scheduling/campaigns/cmp1/attendance')[0]?.body).toEqual([{ appointmentId: 'a1', status: 'NO_SHOW' }]));
  });
});

describe('Capacity', () => {
  it('colours gaps, shows utilisation and runs a what-if', async () => {
    const user = userEvent.setup();
    const rows = [
      { siteCode: 'HQ-MC', siteName: 'HQ Medical Centre', month: '2026-11', capacityAppointments: 100, bookedAppointments: 60, forecastDemand: 130, demandBySource: { E07: 90, E06: 10, RUN_RATE: 30 }, gap: -30, utilisationPct: 60 },
      { siteCode: 'HQ-MC', siteName: 'HQ Medical Centre', month: '2026-12', capacityAppointments: 100, bookedAppointments: 20, forecastDemand: 50, gap: 50, utilisationPct: 20 },
    ];
    const api = mockApi({
      ...base,
      'GET /scheduling/capacity': rows,
      'GET /scheduling/utilisation': [{ key: 'HQ-MC', label: 'HQ Medical Centre', sessionsMinutes: 6000, bookedMinutes: 4800, utilisationPct: 80, noShowRatePct: 4, averageWaitMinutes: 12 }],
      'POST /scheduling/capacity/what-if': [{ ...rows[0], capacityAppointments: 140 }],
    });
    render(<Capacity {...props(api.client, 'NURSING_HEAD')} />);
    expect(await screen.findByText('Short by 30')).toBeTruthy();
    expect(screen.getByText('50 spare')).toBeTruthy();
    expect(screen.getByText('E07 90 · E06 10 · Run rate 30')).toBeTruthy();
    expect(await screen.findByText('80%')).toBeTruthy();
    expect(api.find('GET', '/scheduling/capacity')[0]?.path).toMatch(/^\/scheduling\/capacity\?from=\d{4}-\d{2}-01&to=\d{4}-\d{2}-\d{2}$/);
    await user.click(screen.getByRole('button', { name: 'Recalculate' }));
    await waitFor(() => expect(api.find('POST', '/scheduling/capacity/what-if')[0]?.body).toMatchObject({ siteCode: 'HQ-MC', extraClinicianSessionsPerWeek: 2, extraCampaignDays: 0 }));
    expect(await screen.findByText('Tight (10 spare)')).toBeTruthy();
    expect(screen.getByText('(was 100)')).toBeTruthy();
  });
});

describe('Catalogue', () => {
  it('shows charge codes, finance export link, and saves validated site templates', async () => {
    const user = userEvent.setup();
    const tpl = [{ weekday: 1, start: '08:00', end: '12:00', clinicianUsername: 'nurse1', roomCode: 'AUD-1', appointmentTypes: ['AUDIOMETRY'], telehealth: false }];
    const api = mockApi({ ...base, 'GET /scheduling/service-catalogue': types, 'GET /scheduling/charging/usage': [{ subsidiaryCode: 'GND', chargeCode: 'OH-AUD', service: 'Audiometry', appointments: 10, amount: 350 }], 'GET /scheduling/sites/HQ-MC/templates': tpl, 'PUT /scheduling/sites/HQ-MC/templates': tpl });
    render(<Catalogue {...props(api.client, 'OH_ADMIN')} />);
    expect(await screen.findByText('OH-AUD')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Monthly charging' }));
    expect(await screen.findByText('Audiometry')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Export CSV for finance' }).getAttribute('href')).toMatch(/^\/bff\/api\/scheduling\/charging\/export\?month=\d{4}-\d{2}$/);
    await user.click(screen.getByRole('button', { name: 'Site templates' }));
    await screen.findByDisplayValue('12:00');
    await user.click(screen.getByRole('button', { name: 'Add session' }));
    await user.selectOptions(screen.getByLabelText('Clinician, row 2'), 'nurse1');
    expect(await screen.findByText(/nurse1 has overlapping sessions on Monday/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Save templates' }) as HTMLButtonElement).disabled).toBe(true);
    await user.selectOptions(screen.getByLabelText('Weekday, row 2'), '2');
    await user.click(screen.getByRole('button', { name: 'Save templates' }));
    await waitFor(() => expect((api.find('PUT', '/scheduling/sites/HQ-MC/templates')[0]?.body as unknown[]).length).toBe(2));
  });

  it('scheduler reads types without editing or charging', async () => {
    const api = mockApi({ ...base, 'GET /scheduling/service-catalogue': types });
    render(<Catalogue {...props(api.client, 'CLINIC_SCHEDULER')} />);
    expect(await screen.findByText('OH-AUD')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Monthly charging' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Edit/ })).toBeNull();
  });
});

describe('scheduling screens render (SSR smoke)', () => {
  const client: AuthClient = { mode: 'bff', init: async () => session, login: async () => undefined, logout: async () => undefined, api: () => new Promise(() => {}) };
  const session: Session = { authenticated: true, name: 'T', roles: ['NURSING_HEAD', 'CLINIC_SCHEDULER', 'BIOMEDICAL', 'CLINIC_RECEPTION'] };
  const p = { client, session, onSessionExpired: () => {} };
  const screens: [string, ReactElement][] = [
    ['Diary', <Diary {...p} />],
    ['Campaigns', <Campaigns {...p} />],
    ['CampaignDetail', <CampaignDetail {...p} campaignId="c1" />],
    ['ReceptionKiosk', <ReceptionKiosk {...p} />],
    ['QueueBoard', <QueueBoardScreen {...p} />],
    ['Equipment', <EquipmentScreen {...p} />],
    ['Capacity', <Capacity {...p} />],
    ['Catalogue', <Catalogue {...p} />],
  ];
  it.each(screens)('%s renders', (_n, el) => {
    expect(renderToStaticMarkup(el).length).toBeGreaterThan(0);
  });
});
