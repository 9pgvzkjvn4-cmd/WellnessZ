// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { mockApi, reply, sessionFor } from '../../testing/mockApi';
import { AbsenceChartTab } from './AbsenceChartTab';
import { AbsenceAdvice } from './Advice';
import { AbsenceAnalytics } from './Analytics';
import { AbsenceCertificates } from './Certificates';
import { LtaDetail } from './Lta';
import { AbsenceQueue } from './Queue';
import { AbsenceRules } from './Rules';

afterEach(cleanup);

const noop = () => {};
const episode = { id: 'ep1', sourceSystem: 'HRIS', startDate: '2026-09-01', endDate: '2026-09-12', days: 12, absenceType: 'SICKNESS', reasonCategory: 'MSK', certified: true, certificateValidity: 'VALID', open: false };
const queueItem = (over = {}) => ({ id: 'q1', employeeId: 'E1004', displayName: 'Rami Ramp', unit: 'GND', ruleCode: 'DURATION_10D', ruleName: 'More than 10 days', action: 'EARLY_INTERVENTION', raisedAt: '2026-10-28T08:00:00Z', slaDueAt: '2099-01-01T00:00:00Z', status: 'NEW', history: [episode], ...over });

describe('AbsenceQueue', () => {
  it('filters the AME queue, accepts, records a contact and shows history', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /absence/queue': [queueItem(), queueItem({ id: 'q2', displayName: 'Erin', status: 'ACCEPTED', caseId: 'c9', caseRef: 'ABS-1' })],
      'GET /absence/queue?action=AME_REVIEW': [queueItem({ id: 'q3', displayName: 'Pilot Pat', action: 'AME_REVIEW' })],
      'POST /absence/queue/q1/accept': queueItem({ status: 'ACCEPTED', caseRef: 'ABS-2' }),
      'POST /absence/queue/q2/contact': queueItem(),
    });
    render(<AbsenceQueue client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'Accept Rami Ramp' }));
    expect(await screen.findByText('Accepted – Rami Ramp assigned to you (case ABS-2).')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Record contact with Erin' }));
    const dialog = screen.getByRole('dialog', { name: 'Record contact' });
    await user.click(within(dialog).getByRole('radio', { name: 'No answer' }));
    await user.type(within(dialog).getByLabelText(/Note/), 'Left voicemail');
    await user.click(within(dialog).getByRole('button', { name: 'Save contact' }));
    await waitFor(() => expect(api.find('POST', '/absence/queue/q2/contact')[0]?.body).toEqual({ channel: 'PHONE', outcome: 'NO_ANSWER', note: 'Left voicemail' }));

    const hist = screen.getAllByRole('button', { name: 'History (1)' })[0]!;
    await user.click(hist);
    expect(hist.getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByRole('table', { name: 'Absence history – Rami Ramp' })).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'AME review' }));
    expect(await screen.findByText('Pilot Pat')).toBeTruthy();
  });
});

describe('AbsenceAdvice', () => {
  const request = { id: 'a1', ref: 'ADV-2026-000001', employeeId: 'E1004', displayName: 'Rami Ramp', status: 'ACCEPTED', questionCodes: ['FIT_FOR_ROLE', 'ADJUSTMENTS'], context: 'Struggling with lifting', requestedBy: 'manager1', requestedAt: '2026-10-27T09:00:00Z', slaDueAt: '2099-01-01T00:00:00Z' };

  it('shows a 422 clinical_content error inline on the offending field', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /absence/advice-requests': [request],
      'GET /absence/advice/questions': [{ code: 'FIT_FOR_ROLE', text: 'Is the employee fit for their role?' }, { code: 'ADJUSTMENTS', text: 'What adjustments are recommended?' }],
      'PUT /absence/advice-requests/a1/report': reply(422, { status: 422, code: 'clinical_content_not_allowed', field: 'answers[ADJUSTMENTS]', detail: "Field 'answers[ADJUSTMENTS]' appears to contain medical information." }),
    });
    render(<AbsenceAdvice client={api.client} session={sessionFor('OH_PHYSICIAN')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'Open report for ADV-2026-000001' }));
    expect(screen.getByText('Struggling with lifting')).toBeTruthy();
    await user.selectOptions(screen.getByLabelText(/Functional fitness/), 'FIT_WITH_ADJUSTMENTS');
    await user.selectOptions(screen.getByLabelText(/Prognosis/), 'RTW_WITHIN_4_WEEKS');
    await user.type(screen.getByLabelText(/Is the employee fit for their role/), 'Yes with adjustments');
    await user.type(screen.getByLabelText(/What adjustments are recommended/), 'Due to lumbar disc prolapse avoid lifting');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    const field = screen.getByLabelText(/What adjustments are recommended/);
    await waitFor(() => expect(field.getAttribute('aria-invalid')).toBe('true'));
    expect(screen.getAllByText(/remove medical information/).length).toBeGreaterThan(0);
    expect(api.find('PUT', '/absence/advice-requests/a1/report')[0]?.body).toMatchObject({ fitness: 'FIT_WITH_ADJUSTMENTS', prognosis: 'RTW_WITHIN_4_WEEKS', answers: { FIT_FOR_ROLE: 'Yes with adjustments' } });
    expect(api.find('POST', '/absence/advice-requests/a1/report/sign')).toHaveLength(0);
  });

  it('requires answers before saving and signs after a successful save', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /absence/advice-requests': [{ ...request, questionCodes: ['FIT_FOR_ROLE'] }],
      'GET /absence/advice/questions': [],
      'PUT /absence/advice-requests/a1/report': request,
      'POST /absence/advice-requests/a1/report/sign': { ...request, status: 'AWAITING_EMPLOYEE' },
    });
    render(<AbsenceAdvice client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'Open report for ADV-2026-000001' }));
    await user.click(screen.getByRole('button', { name: 'Sign report' }));
    expect(screen.getByRole('alert').textContent).toMatch(/Choose the functional fitness/);
    expect(api.find('PUT', '/absence/advice-requests/a1/report')).toHaveLength(0);
    await user.selectOptions(screen.getByLabelText(/Functional fitness/), 'FIT');
    await user.selectOptions(screen.getByLabelText(/Prognosis/), 'RTW_WITHIN_2_WEEKS');
    await user.type(screen.getByLabelText(/Fit for role/), 'Yes');
    await user.click(screen.getByRole('button', { name: 'Sign report' }));
    await waitFor(() => expect(api.find('POST', '/absence/advice-requests/a1/report/sign')).toHaveLength(1));
  });

  it('case managers can read but not edit', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /absence/advice-requests': [request], 'GET /absence/advice/questions': [] });
    render(<AbsenceAdvice client={api.client} session={sessionFor('CASE_MANAGER')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'Open report for ADV-2026-000001' }));
    expect(screen.queryByRole('button', { name: 'Sign report' })).toBeNull();
    expect(screen.getByText('Only clinicians can draft and sign advice reports.')).toBeTruthy();
  });
});

describe('AbsenceRules', () => {
  const rule = { id: 'r1', code: 'FREQ_3_IN_90', name: '3 in 90', ruleType: 'FREQUENCY', params: { episodes: 3, windowDays: 90 }, action: 'EARLY_INTERVENTION', active: true, version: 2, updatedBy: 'ohadmin', updatedAt: '2026-10-01T00:00:00Z' };

  it('OH_ADMIN builds a PATTERN rule, tests it (no cases) and saves it', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /absence/rules': [rule],
      'POST /absence/rules/test': { matches: [{ employeeId: 'E1004', displayName: 'Rami Ramp', subsidiaryCode: 'GND', detail: '3 Monday starts' }], count: 1 },
      'POST /absence/rules': { ...rule, id: 'r2' },
    });
    render(<AbsenceRules client={api.client} session={sessionFor('OH_ADMIN')} onSessionExpired={noop} />);
    expect(await screen.findByText('3 episodes in 90 days')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'New rule' }));
    await user.type(screen.getByLabelText(/^Code/), 'mon_fri_cat');
    await user.type(screen.getByLabelText(/^Name/), 'Mon/Fri catering');
    await user.selectOptions(screen.getByLabelText(/Rule type/), 'PATTERN');
    expect(screen.getByLabelText(/Minimum occurrences/)).toBeTruthy();
    expect(screen.queryByLabelText(/Minimum days/)).toBeNull();
    await user.clear(screen.getByLabelText(/Window \(days\)/));
    await user.type(screen.getByLabelText(/Window \(days\)/), '120');
    await user.click(screen.getByRole('button', { name: 'Test rule' }));
    expect(await screen.findByText(/1 employee would match/)).toBeTruthy();
    expect(api.find('POST', '/absence/rules/test')[0]?.body).toEqual({ rule: { code: 'MON_FRI_CAT', name: 'Mon/Fri catering', ruleType: 'PATTERN', action: 'EARLY_INTERVENTION', active: true, params: { pattern: 'MONDAY_FRIDAY', minOccurrences: 3, windowDays: 120 } } });
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    await waitFor(() => expect(api.find('POST', '/absence/rules').filter((c) => c.path === '/absence/rules')).toHaveLength(1));
  });

  it('clinicians read rules only', async () => {
    const api = mockApi({ 'GET /absence/rules': [rule] });
    render(<AbsenceRules client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    expect(await screen.findByText('FREQ_3_IN_90')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'New rule' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Edit FREQ_3_IN_90' })).toBeNull();
  });
});

describe('AbsenceCertificates', () => {
  const cert = { id: 'ct1', employeeId: 'E1004', displayName: 'Rami Ramp', source: 'EMPLOYEE_UPLOAD', documentId: 'd1', fromDate: '2026-10-01', toDate: '2026-10-14', issuerName: 'Dr Who', extracted: { issuerName: 'Dr Who', registration: 'GMC123' }, checklist: {}, status: 'RECEIVED', duplicateOfId: 'ct0', alterationFlags: ['DATES_DIFFER_FROM_OCR'], calls: [], receivedAt: '2026-10-02T09:00:00Z' };

  it('reception validates and logs calls but never sees the document, OCR content or verify', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /absence/certificates?status=RECEIVED': [cert], 'POST /absence/certificates/ct1/validate': cert, 'POST /absence/certificates/ct1/calls': cert });
    render(<AbsenceCertificates client={api.client} session={sessionFor('CLINIC_RECEPTION')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'Rami Ramp' }));
    expect(screen.getByText('Possible duplicate')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'View certificate' })).toBeNull();
    expect(screen.queryByText('Extracted by OCR')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Record verification' })).toBeNull();
    const save = screen.getByRole('button', { name: 'Save validation' }) as HTMLButtonElement;
    expect(save.disabled).toBe(true); // VALID needs every check
    for (const label of ['Issuer identified', 'Issuer registration valid', 'Dates consistent', 'Signature / stamp present', 'Covers the absence period']) await user.click(screen.getByLabelText(label));
    await user.click(save);
    await waitFor(() => expect(api.find('POST', '/absence/certificates/ct1/validate')[0]?.body).toEqual({ checklist: { issuerIdentified: true, registrationValid: true, datesConsistent: true, signaturePresent: true, coversAbsence: true }, outcome: 'VALID' }));
    await user.type(screen.getByLabelText(/Called party/), 'Surgery reception');
    await user.click(screen.getByRole('button', { name: 'Log call' }));
    await waitFor(() => expect(api.find('POST', '/absence/certificates/ct1/calls')[0]?.body).toEqual({ calledParty: 'Surgery reception', outcome: 'CONFIRMED' }));
  });

  it('OH verifies as fraudulent and escalates to HR', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /absence/certificates?status=RECEIVED': [cert], 'POST /absence/certificates/ct1/verify': cert });
    render(<AbsenceCertificates client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'Rami Ramp' }));
    expect(screen.getByRole('link', { name: 'View certificate' }).getAttribute('href')).toBe('/bff/api/documents/d1/content');
    await user.click(screen.getByRole('radio', { name: 'Verified fraudulent' }));
    await user.click(screen.getByLabelText(/Escalate to HR/));
    await user.click(screen.getByRole('button', { name: 'Record verification' }));
    await waitFor(() => expect(api.find('POST', '/absence/certificates/ct1/verify')[0]?.body).toEqual({ outcome: 'VERIFIED_FRAUDULENT', escalateToHr: true }));
  });
});

describe('LtaDetail', () => {
  it('flags overdue milestones and completes one', async () => {
    const user = userEvent.setup();
    const detail = {
      caseId: 'c1', caseRef: 'ABS-2026-000003', employeeId: 'E1004', displayName: 'Rami Ramp', unit: 'GND', absenceStart: '2026-08-01', weeksAbsent: 12, status: 'ACTIVE', hrStage: 'INFORMAL_REVIEW', overdueCount: 1, rtwPlanId: null,
      milestones: [
        { id: 'm1', week: 2, type: 'CONTACT', dueDate: '2026-08-15', status: 'DONE', completedAt: '2026-08-14T10:00:00Z', completedBy: 'nurse1' },
        { id: 'm2', week: 4, type: 'ADVICE_REPORT', dueDate: '2026-08-29', status: 'OVERDUE' },
        { id: 'm3', week: 26, type: 'IHR_CONSIDERATION', dueDate: '2099-01-30', status: 'PENDING' },
      ],
      contacts: [], adviceReports: [],
    };
    const api = mockApi({ 'GET /absence/lta/c1': detail, 'POST /absence/lta/c1/milestones/m2/complete': detail });
    render(<LtaDetail client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} caseId="c1" />);
    const table = await screen.findByRole('table', { name: 'Milestones' });
    expect(within(table).getAllByText('Overdue')).toHaveLength(1);
    expect(screen.getByRole('link', { name: 'Create RTW plan' }).getAttribute('href')).toBe('#/cases/c1/rtw');
    await user.click(screen.getByRole('button', { name: 'Complete week 4 Advice report' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Complete milestone' }));
    await waitFor(() => expect(api.find('POST', '/absence/lta/c1/milestones/m2/complete')[0]?.body).toEqual({}));
  });
});

describe('AbsenceAnalytics', () => {
  it('suppresses small units, shows categories and KPI targets; export link for CMO', async () => {
    const api = mockApi({
      'GET /absence/analytics/units': [
        { key: 'GND', label: 'Ground', headcount: 120, episodes: 40, daysLost: 300, absenceRatePct: 3.2, frequencyRate: 0.33, avgDurationDays: 7.5, bradfordMean: 88, categories: { MSK: 12, MENTAL_HEALTH: 6 }, suppressed: false },
        { key: 'TINY', label: 'Tiny unit', suppressed: true },
      ],
      'GET /absence/analytics/kpis': { absenceRatePct: 3.1, ltaWithCasePct: 91, medianDaysTriggerToContact: 3, certificatesVerifiedPct: 80, openLta: 7, overdueMilestones: 2, trend: [{ month: '2026-09', absenceRatePct: 3, medianDaysTriggerToContact: 4, ltaWithCasePct: 90 }] },
    });
    render(<AbsenceAnalytics client={api.client} session={sessionFor('CMO')} onSessionExpired={noop} />);
    const table = await screen.findByRole('table', { name: 'Absence by subsidiary' });
    expect(within(table).getByText('Suppressed – group too small to report')).toBeTruthy();
    expect(within(table).getByRole('columnheader', { name: 'Msk' })).toBeTruthy();
    expect(within(table).getByText('3.2 %')).toBeTruthy();
    expect(await screen.findByText('91.0 %')).toBeTruthy();
    expect(screen.getAllByText('off target').length).toBeGreaterThan(0);
    expect(screen.getAllByText('on target').length).toBeGreaterThan(0);
    expect(screen.getByRole('link', { name: 'Download CSV' }).getAttribute('href')).toMatch(/^\/bff\/api\/absence\/analytics\/export\?month=\d{4}-\d{2}$/);
  });

  it('OH_ADMIN gets no export', async () => {
    const api = mockApi({ 'GET /absence/analytics/units': [], 'GET /absence/analytics/kpis': reply(403) });
    render(<AbsenceAnalytics client={api.client} session={sessionFor('OH_ADMIN')} onSessionExpired={noop} />);
    expect(await screen.findByText('No absence data for this period.')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Download CSV' })).toBeNull();
  });
});

describe('AbsenceChartTab', () => {
  it('shows summary with Bradford band, patterns and filters the timeline', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /absence/employees/E1004/summary': { episodes12m: 5, daysLost12m: 20, bradfordFactor: 500, longestDays: 9, patterns: ['MONDAY_FRIDAY'], activeLtaCaseId: 'c1' },
      'GET /absence/employees/E1004/episodes': [episode, { ...episode, id: 'ep2', startDate: '2026-10-01', endDate: null, absenceType: 'INJURY_WORK', open: true }],
    });
    render(<AbsenceChartTab client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} employeeId="E1004" canWrite refreshSummary={noop} />);
    expect(await screen.findByText('High (≥ 400)')).toBeTruthy();
    expect(screen.getByText('Monday friday')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Long-term absence pathway' }).getAttribute('href')).toBe('#/absence/lta/c1');
    const timeline = await screen.findByRole('list', { name: 'Absence episodes, newest first' });
    expect(within(timeline).getAllByRole('listitem')).toHaveLength(2);
    await user.click(screen.getByLabelText('Injury work'));
    expect(within(screen.getByRole('list', { name: 'Absence episodes, newest first' })).getAllByRole('listitem')).toHaveLength(1);
  });
});
