// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { mockApi, sessionFor } from '../../testing/mockApi';
import { ComplianceScreen } from './Compliance';
import { CrewCertificates } from './Crew';
import { SurveillanceDue } from './Due';
import { ExposureScreen } from './Exposure';
import { ReturnAfterIllness, SurveillanceFollowUps } from './FollowUps';
import { ImmunisationScreen } from './Immunisation';
import { ProgrammeBuilder } from './Programmes';
import { SurveillanceChartTab } from './SurveillanceChartTab';

afterEach(cleanup);
const noop = () => {};

const enrolment = (over = {}) => ({ id: 'en1', employeeId: 'E1004', displayName: 'Rami Ramp', subsidiaryCode: 'GND', departmentCode: 'RAMP', programmeCode: 'HEARING_CONSERVATION', programmeTitle: 'Hearing conservation', reason: 'HAZARD', nextDueOn: '2026-11-10', status: 'DUE', invitations: 0, ...over });

describe('SurveillanceDue', () => {
  it('multi-selects and bulk invites + books; shows the invitation log', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /surveillance/due': [enrolment(), enrolment({ id: 'en2', displayName: 'Erin', status: 'OVERDUE', invitations: 2, escalatedAt: '2026-10-20T00:00:00Z' }), enrolment({ id: 'en3', displayName: 'Sam' })],
      'POST /surveillance/invitations': { invited: 2, booked: 1, notBooked: 1, invitationIds: ['i1', 'i2'] },
      'GET /surveillance/invitations?enrolmentId=en2': [{ id: 'i0', sentAt: '2026-10-01T09:00:00Z', channel: 'EMAIL', status: 'NO_SHOW' }],
    });
    render(<SurveillanceDue client={api.client} session={sessionFor('CLINIC_SCHEDULER')} onSessionExpired={noop} />);
    await user.click(await screen.findByLabelText('Select Rami Ramp – Hearing conservation'));
    await user.click(screen.getByLabelText('Select Erin – Hearing conservation'));
    expect(screen.getByText('2 selected')).toBeTruthy();
    expect(screen.getByText('Escalated')).toBeTruthy();
    // scheduler: no clinical actions
    expect(screen.queryByRole('button', { name: /Record completion/ })).toBeNull();
    await user.type(screen.getByLabelText(/Site/), 'lhr-airside');
    await user.click(screen.getByRole('button', { name: 'Invite and book 2' }));
    await waitFor(() => expect(api.find('POST', '/surveillance/invitations')[0]?.body).toEqual({ enrolmentIds: ['en1', 'en2'], book: true, siteCode: 'LHR-AIRSIDE' }));
    expect(await screen.findByText('2 invited, 1 booked, 1 not booked.')).toBeTruthy();
    expect(screen.getByText('0 selected')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: /2 sent/ }));
    expect(await screen.findByRole('list', { name: 'Invitation log' })).toBeTruthy();
  });

  it('select-all header and clinician completion', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /surveillance/due': [enrolment(), enrolment({ id: 'en2', displayName: 'Erin' })], 'POST /surveillance/enrolments/en1/complete': enrolment() });
    render(<SurveillanceDue client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    await user.click(await screen.findByLabelText('Select all shown'));
    expect(screen.getByText('2 selected')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Exempt/ })).toBeNull(); // physicians only
    await user.click(screen.getByRole('button', { name: 'Record completion for Rami Ramp – Hearing conservation' }));
    const dialog = screen.getByRole('dialog');
    await user.selectOptions(within(dialog).getByLabelText(/Outcome/), 'REFER');
    await user.click(within(dialog).getByRole('button', { name: 'Record completion' }));
    await waitFor(() => expect(api.find('POST', '/surveillance/enrolments/en1/complete')[0]?.body).toMatchObject({ outcome: 'REFER' }));
  });
});

describe('ImmunisationScreen fast entry', () => {
  const reqs = [{ id: 'CATERING_ASSOC-TYPHOID', jobCode: 'CATERING_ASSOC', vaccineCode: 'TYPHOID', vaccineName: 'Typhoid', doses: 1, boosterMonths: 36, titreRequired: false, mandatory: true }];

  it('records a dose from a scanned GS1 lot + badge with Enter, keeps the session settings', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /surveillance/immunisation/requirements': reqs, 'POST /surveillance/immunisation/administrations': {} });
    render(<ImmunisationScreen client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    await screen.findByRole('option', { name: 'Typhoid (TYPHOID)' });
    await user.selectOptions(screen.getByLabelText(/^Vaccine/), 'TYPHOID');
    await user.type(screen.getByLabelText(/^Lot/), '(17)991231(10)TY-55{Enter}');
    expect(screen.getByText(/Lot TY-55 · expires/)).toBeTruthy();
    expect(document.activeElement?.id).toBe('imm-person'); // scanner Enter moves on to the person
    await user.type(screen.getByLabelText('Badge or employee ID'), '0004417733');
    expect(screen.getByText('Badge 0004417733')).toBeTruthy();
    await user.keyboard('{Enter}'); // consent missing → focus moves to consent, nothing sent
    expect(api.find('POST', '/surveillance/immunisation/administrations')).toHaveLength(0);
    expect(document.activeElement).toBe(screen.getByLabelText('Consent confirmed'));
    await user.click(screen.getByLabelText('Consent confirmed'));
    await user.click(screen.getByRole('button', { name: 'Record dose' }));
    await waitFor(() =>
      expect(api.find('POST', '/surveillance/immunisation/administrations')[0]?.body).toEqual({ badgeId: '0004417733', vaccineCode: 'TYPHOID', doseNumber: 1, lotBarcode: '(17)991231(10)TY-55', lotExpiry: '2099-12-31', site: 'LEFT_DELTOID', route: 'IM', consentConfirmed: true }),
    );
    expect(await screen.findByRole('table', { name: 'Recorded this session (1)' })).toBeTruthy();
    expect((screen.getByLabelText('Badge or employee ID') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText(/^Lot/) as HTMLInputElement).value).toBe('(17)991231(10)TY-55');
    expect((screen.getByLabelText('Consent confirmed') as HTMLInputElement).checked).toBe(false);
  });

  it('refuses an expired lot', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /surveillance/immunisation/requirements': reqs });
    render(<ImmunisationScreen client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    await user.type(await screen.findByLabelText(/^Lot/), '(17)200101(10)OLD');
    expect(screen.getByText(/Lot OLD expired on 2020-01-01/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Record dose' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('batch mode needs consent for everyone and posts one batch', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /surveillance/immunisation/requirements': reqs, 'POST /surveillance/immunisation/administrations/batch': { recorded: 1, rejected: [{ ref: 'E1010', reason: 'Not a food handler' }] } });
    render(<ImmunisationScreen client={api.client} session={sessionFor('NURSING_HEAD')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'Campaign batch' }));
    await screen.findByRole('option', { name: 'Typhoid (TYPHOID)' });
    await user.selectOptions(screen.getByLabelText(/^Vaccine/), 'TYPHOID');
    await user.type(screen.getByLabelText(/^Lot/), 'TY-55');
    const scan = screen.getByLabelText(/Scan badge or type employee ID/);
    await user.type(scan, 'E1009{Enter}');
    await user.type(scan, 'E1010{Enter}');
    await user.type(scan, 'e1010{Enter}');
    expect(screen.getByText('e1010 is already in the list')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Record batch' }) as HTMLButtonElement).disabled).toBe(true);
    await user.click(screen.getByRole('button', { name: 'Consent confirmed for all' }));
    await user.click(screen.getByRole('button', { name: 'Record batch' }));
    await waitFor(() =>
      expect(api.find('POST', '/surveillance/immunisation/administrations/batch')[0]?.body).toEqual({ vaccineCode: 'TYPHOID', lotNumber: 'TY-55', site: 'LEFT_DELTOID', route: 'IM', entries: [{ employeeId: 'E1009', consentConfirmed: true }, { employeeId: 'E1010', consentConfirmed: true }] }),
    );
    expect(await screen.findByText('Not a food handler')).toBeTruthy();
    expect(screen.getByRole('table', { name: '1 in this batch' })).toBeTruthy(); // only the rejected one stays
  });

  it('OH_ADMIN sees only the requirement matrix and can edit it', async () => {
    const api = mockApi({ 'GET /surveillance/immunisation/requirements': reqs });
    render(<ImmunisationScreen client={api.client} session={sessionFor('OH_ADMIN')} onSessionExpired={noop} />);
    expect(await screen.findByRole('button', { name: 'Edit CATERING_ASSOC-TYPHOID' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Fast entry' })).toBeNull();
  });
});

describe('ComplianceScreen', () => {
  const routes = {
    'GET /surveillance/compliance?groupBy=PROGRAMME': { rows: [{ key: 'HEARING_CONSERVATION', label: 'Hearing conservation', enrolled: 200, current: 150, due: 20, overdue: 30, compliancePct: 75, suppressed: false }, { key: 'VDU', label: 'VDU', suppressed: true }], refreshedAt: '2026-10-29T02:00:00Z' },
    'GET /surveillance/compliance/trend': [{ date: '2026-08-01', compliancePct: 70 }, { date: '2026-09-01', compliancePct: 75 }],
    'GET /surveillance/programmes': [{ code: 'HEARING_CONSERVATION' }],
    'GET /surveillance/audit-packs/templates': [{ code: 'CAA', title: 'CAA audit', sections: ['Enrolment', 'Overdue'] }],
    'POST /surveillance/audit-packs': { id: 'ap1', generatedAt: '2026-10-29T10:00:00Z', programmeCode: 'HEARING_CONSERVATION', template: 'CAA', anonymised: true, files: [{ format: 'pdf', path: 'x' }, { format: 'csv', path: 'y' }] },
    'GET /surveillance/compliance/overdue?programme=HEARING_CONSERVATION': [enrolment({ status: 'OVERDUE' })],
  };

  it('AUDITOR: suppressed rows, no drill-down, audit pack always anonymised', async () => {
    const user = userEvent.setup();
    const api = mockApi(routes);
    render(<ComplianceScreen client={api.client} session={sessionFor('AUDITOR')} onSessionExpired={noop} />);
    const table = await screen.findByRole('table', { name: 'Compliance by programme' });
    expect(within(table).getByText('75.0 %')).toBeTruthy();
    expect(within(table).getByText('Suppressed – group too small to report')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Show overdue/ })).toBeNull();
    expect(await screen.findByText('(+5.0 pts)')).toBeTruthy();
    const anon = screen.getByLabelText(/Anonymise/) as HTMLInputElement;
    expect(anon.checked && anon.disabled).toBe(true);
    await user.selectOptions(await screen.findByLabelText(/Template/), 'CAA');
    await user.type(screen.getByLabelText(/^Programme \*/), 'hearing_conservation');
    await user.click(screen.getByRole('button', { name: 'Generate audit pack' }));
    await waitFor(() => expect(api.find('POST', '/surveillance/audit-packs')[0]?.body).toMatchObject({ template: 'CAA', programmeCode: 'HEARING_CONSERVATION', anonymise: true }));
    expect((await screen.findByRole('link', { name: 'Download PDF' })).getAttribute('href')).toBe('/bff/api/surveillance/audit-packs/ap1/content?format=pdf');
    expect(api.calls.some((c) => c.path.startsWith('/surveillance/compliance/overdue'))).toBe(false);
  });

  it('Nursing Head drills down to overdue people', async () => {
    const user = userEvent.setup();
    const api = mockApi(routes);
    render(<ComplianceScreen client={api.client} session={sessionFor('NURSING_HEAD')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'Show overdue for Hearing conservation' }));
    expect(await screen.findByRole('link', { name: 'Rami Ramp' })).toBeTruthy();
  });
});

describe('ExposureScreen', () => {
  it('HSE officer pastes IH CSV, sees row errors, then imports', async () => {
    const api = mockApi({
      'GET /surveillance/exposure-groups': [{ code: 'SEG-RAMP', name: 'Ramp noise', locationCode: 'LHR', jobCodes: ['RAMP_AGENT'], hazardCode: 'NOISE', unit: 'dB(A)', actionLevel: 80, limitValue: 87, latestValue: 84, latestMeasuredOn: '2026-10-01', aboveActionLevel: true, enrolledProgrammes: ['HEARING_CONSERVATION'] }],
      'POST /surveillance/ih-measurements/import': { imported: 1, duplicates: 0, segsAboveActionLevel: ['SEG-RAMP'], employeesEnrolled: 12 },
    });
    const user = userEvent.setup();
    render(<ExposureScreen client={api.client} session={sessionFor('HSE_OFFICER')} onSessionExpired={noop} />);
    expect(await screen.findByText('Above action level')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: /Cosmic radiation/ })).toBeNull();
    const box = screen.getByLabelText(/Measurements \(CSV or JSON\)/);
    fireEvent.change(box, { target: { value: 'sourceRef,segCode,hazardCode,measurementType,value,unit,measuredOn\nIH-1,SEG-RAMP,NOISE,NOISE_DOSIMETRY,85,dB(A),2026-10-20\nIH-2,SEG-RAMP,NOISE,BAD,1,x,2026-10-20' } });
    expect(screen.getByText('Row 3: Unknown measurement type BAD')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Import' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(box, { target: { value: 'sourceRef,segCode,hazardCode,measurementType,value,unit,measuredOn\nIH-1,SEG-RAMP,NOISE,NOISE_DOSIMETRY,85,dB(A),2026-10-20' } });
    await user.click(screen.getByRole('button', { name: 'Import' }));
    await waitFor(() => expect(api.find('POST', '/surveillance/ih-measurements/import')[0]?.body).toEqual([{ sourceRef: 'IH-1', segCode: 'SEG-RAMP', hazardCode: 'NOISE', measurementType: 'NOISE_DOSIMETRY', value: 85, unit: 'dB(A)', measuredOn: '2026-10-20' }]));
    expect(await screen.findByText('1 group above action level')).toBeTruthy();
  });
});

describe('CrewCertificates', () => {
  it('CREW_LICENSING sees status / expiry bands only – no exam, import or authority report', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /surveillance/crew/certificates': [{ id: 'cc1', employeeId: 'E2001', displayName: 'Pilot Pat', certificateType: 'CLASS1', validUntil: '2000-01-01', limitationCodes: ['VDL'], status: 'EXPIRED', source: 'INTERNAL', alertsSent: [90, 60, 30] }],
      'GET /surveillance/crew/without-valid-medical': [{ employeeId: 'E2001', displayName: 'Pilot Pat', jobCode: 'PILOT', status: 'EXPIRED' }],
    });
    render(<CrewCertificates client={api.client} session={sessionFor('CREW_LICENSING')} onSessionExpired={noop} />);
    expect(await screen.findByText(/Expired \d+ d ago/)).toBeTruthy();
    expect(screen.getByText('90 d, 60 d, 30 d')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'AME examination' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'External import' })).toBeNull();
    expect(screen.queryByRole('heading', { name: /Authority report/ })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Without valid medical' }));
    expect(await screen.findByRole('table', { name: '1 crew without a valid medical' })).toBeTruthy();
  });

  it('AME fills the CAA exam template', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /surveillance/crew/certificates': [],
      'GET /surveillance/crew/exam-template?type=CLASS1': [{ section: 'Vision', items: [{ code: 'VA_R', label: 'Visual acuity right', type: 'TEXT' }, { code: 'COLOUR_OK', label: 'Colour vision normal', type: 'BOOLEAN' }] }],
      'GET /intake/employees/lookup?q=pat': [{ employeeId: 'E2001', displayName: 'Pilot Pat' }],
      'POST /surveillance/crew/examinations': { id: 'cc2', employeeId: 'E2001', displayName: 'Pilot Pat', certificateType: 'CLASS1', validUntil: '2027-10-29', limitationCodes: [], status: 'VALID', source: 'INTERNAL' },
    });
    render(<CrewCertificates client={api.client} session={sessionFor('AME')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'AME examination' }));
    await user.type(screen.getByLabelText(/Crew member/), 'pat');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await user.click(await screen.findByRole('button', { name: 'Pilot Pat (E2001)' }));
    await user.type(await screen.findByLabelText('Visual acuity right'), '6/6');
    await user.click(screen.getByLabelText('Colour vision normal'));
    await user.type(screen.getByLabelText(/Limitation codes/), 'vdl');
    await user.click(screen.getByRole('button', { name: 'Record examination' }));
    await waitFor(() => expect(api.find('POST', '/surveillance/crew/examinations')[0]?.body).toMatchObject({ employeeId: 'E2001', certificateType: 'CLASS1', answers: { VA_R: '6/6', COLOUR_OK: true }, outcome: 'FIT', limitationCodes: ['VDL'] }));
  });
});

describe('ProgrammeBuilder', () => {
  it('simulates population impact and saves a new draft version', async () => {
    const user = userEvent.setup();
    const p = { code: 'HEARING_CONSERVATION', version: 3, title: 'Hearing conservation', status: 'DRAFT', eligibility: { jobCodes: [], departmentCodes: [], subsidiaryCodes: [], hazardCodes: ['NOISE'], exposureGroups: [], licenceTypes: [] }, panel: [{ test: 'AUDIOMETRY' }], frequencyMonths: 12, responsibleRole: 'OH_CLINICIAN', serviceCode: 'AUDIOMETRY', regulatoryBasis: 'Noise regs', versions: [{ version: 2, status: 'ACTIVE', approvedBy: 'cmo1', approvedAt: '2026-01-01T00:00:00Z' }, { version: 3, status: 'DRAFT' }] };
    const api = mockApi({
      'GET /surveillance/programmes/HEARING_CONSERVATION': p,
      'POST /surveillance/programmes/HEARING_CONSERVATION/simulate': { eligibleHeadcount: 240, byUnit: [{ subsidiaryCode: 'GND', departmentCode: 'RAMP', count: 200 }], newlyEnrolled: 12, wouldBeOverdue: 3 },
      'PUT /surveillance/programmes/HEARING_CONSERVATION': p,
      'POST /surveillance/programmes/HEARING_CONSERVATION/activate': p,
    });
    render(<ProgrammeBuilder client={api.client} session={sessionFor('CMO')} onSessionExpired={noop} code="HEARING_CONSERVATION" />);
    await user.click(await screen.findByRole('button', { name: 'Simulate population impact' }));
    expect(await screen.findByRole('table', { name: 'Eligible headcount by unit' })).toBeTruthy();
    expect(api.find('POST', '/surveillance/programmes/HEARING_CONSERVATION/simulate')[0]?.body).toEqual({ version: 3 });
    await user.selectOptions(screen.getByLabelText('Test'), 'LAB');
    await user.type(screen.getByLabelText(/LOINC code/), '5671-3{Enter}');
    expect(screen.getByText('LAB:5671-3')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Save as new draft version' }));
    await waitFor(() => expect(api.find('PUT', '/surveillance/programmes/HEARING_CONSERVATION')[0]?.body).toMatchObject({ panel: [{ test: 'AUDIOMETRY' }, { test: 'LAB:5671-3' }], eligibility: { hazardCodes: ['NOISE'] } }));
    await user.click(screen.getByRole('button', { name: 'Activate version 3' }));
    await waitFor(() => expect(api.find('POST', '/surveillance/programmes/HEARING_CONSERVATION/activate')).toHaveLength(1));
    expect(within(screen.getByRole('table', { name: 'Versions' })).getByText('cmo1 · ' + new Date('2026-01-01T00:00:00Z').toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }))).toBeTruthy();
  });
});

describe('Follow-ups and RAI', () => {
  it('physician reviews a follow-up', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /surveillance/follow-ups?status=OPEN': [{ id: 'f1', employeeId: 'E1004', displayName: 'Rami Ramp', programmeCode: 'HEARING_CONSERVATION', ruleCode: 'STS', resultId: 'r1', caseId: 'c5', caseRef: 'SUR-1', recheckDueOn: '2000-01-01', regulatoryNotificationRequired: true, status: 'OPEN', createdAt: '2026-10-01T00:00:00Z' }],
      'POST /surveillance/follow-ups/f1/review': {},
    });
    render(<SurveillanceFollowUps client={api.client} session={sessionFor('OH_PHYSICIAN')} onSessionExpired={noop} />);
    expect(await screen.findByText('Notification required')).toBeTruthy();
    expect(screen.getByText('Overdue')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Review follow-up for Rami Ramp' }));
    const d = screen.getByRole('dialog');
    await user.click(within(d).getByRole('radio', { name: 'Work related' }));
    await user.click(within(d).getByRole('button', { name: 'Save review' }));
    await waitFor(() => expect(api.find('POST', '/surveillance/follow-ups/f1/review')[0]?.body).toEqual({ decision: 'WORK_RELATED', regulatoryNotificationRequired: true }));
  });

  it('nurse completes but cannot waive RAI', async () => {
    const api = mockApi({ 'GET /surveillance/return-after-illness?status=REQUIRED': [{ id: 'ra1', employeeId: 'E1006', displayName: 'Ellie Engineer', reason: 'LONG_TERM_ABSENCE', source: 'E06', status: 'REQUIRED', createdAt: '2026-10-01T00:00:00Z' }] });
    render(<ReturnAfterIllness client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    expect(await screen.findByRole('button', { name: 'Complete for Ellie Engineer' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Waive for Ellie Engineer' })).toBeNull();
    expect(screen.getByText('Absence (long-term)')).toBeTruthy();
  });
});

describe('SurveillanceChartTab', () => {
  it('shows only this employee’s programmes, immunisations and exposure history', async () => {
    const api = mockApi({
      'GET /surveillance/due?employeeId=E1004': [enrolment(), enrolment({ id: 'x', employeeId: 'E9999', programmeTitle: 'Someone else' })],
      'GET /surveillance/immunisation/employees/E1004': [{ vaccineCode: 'HEP_B', vaccineName: 'Hepatitis B', status: 'COMPLETE', lastDoseOn: '2025-01-01', titre: { value: 120, protective: true, testedOn: '2025-03-01' } }],
      'GET /surveillance/employees/E1004/exposure-history': { exposures: [{ segCode: 'SEG-RAMP', hazardCode: 'NOISE', from: '2024-01-01', latestValue: 84, unit: 'dB(A)' }], cosmicDose: [] },
    });
    render(<SurveillanceChartTab client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} employeeId="E1004" canWrite refreshSummary={noop} />);
    expect(await screen.findByText('Hearing conservation')).toBeTruthy();
    expect(screen.queryByText('Someone else')).toBeNull();
    expect(await screen.findByText(/120 · protective/)).toBeTruthy();
    expect(await screen.findByText('SEG-RAMP')).toBeTruthy();
  });
});
