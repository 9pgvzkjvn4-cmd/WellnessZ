// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it } from 'vitest';
import type { AuthClient, Session } from '../../auth/AuthClient';
import { mockApi, reply, sessionFor } from '../../testing/mockApi';
import { SupportCases, SupportCaseScreen } from './Cases';
import { CismEvents, CismEventScreen } from './Cism';
import { SupportFitness } from './Fitness';
import { PeerSupport } from './PeerSupport';
import { Psychosocial } from './Psychosocial';
import { RestrictedCasePlaceholder } from './RestrictedCasePlaceholder';
import { EapReferrals, UrgentQueue } from './Urgent';

afterEach(cleanup);
const noop = () => {};
const props = (api: ReturnType<typeof mockApi>, ...roles: string[]) => ({ client: api.client, session: sessionFor(...roles), onSessionExpired: noop });
const inMinutes = (m: number) => new Date(Date.now() + m * 60_000).toISOString();

const supportCase = (over = {}) => ({
  id: 's1', ref: 'SUP-2026-000001', employeeId: 'E1002', displayName: 'Cara Crew', status: 'ACTIVE', risk: 'MODERATE', concern: 'LOW_MOOD', source: 'SELF',
  openedAt: '2026-10-20T09:00:00Z', lastContactAt: '2026-10-28T09:00:00Z', sharing: 'FITNESS_ONLY', ...over,
});

describe('E09 screens – smoke (initial render)', () => {
  const client: AuthClient = { mode: 'bff', init: async () => session, login: async () => undefined, logout: async () => undefined, api: () => new Promise(() => {}) };
  const session: Session = { authenticated: true, name: 'Pat Psych', roles: ['PSYCH_CLINICIAN', 'AME', 'PEER_SUPPORT_COORDINATOR', 'PEER_SUPPORT_VOLUNTEER', 'SAFETY_MANAGER', 'HSE_OFFICER'] };
  const base = { client, session, onSessionExpired: noop };
  const screens: [string, ReactElement][] = [
    ['SupportCases', <SupportCases {...base} />],
    ['SupportCaseScreen', <SupportCaseScreen {...base} caseId="s1" />],
    ['UrgentQueue', <UrgentQueue {...base} />],
    ['EapReferrals', <EapReferrals {...base} />],
    ['SupportFitness', <SupportFitness {...base} />],
    ['PeerSupport', <PeerSupport {...base} />],
    ['CismEvents', <CismEvents {...base} />],
    ['CismEventScreen', <CismEventScreen {...base} eventId="ev1" />],
    ['Psychosocial', <Psychosocial {...base} />],
    ['RestrictedCasePlaceholder', <RestrictedCasePlaceholder client={client} roles={['OH_CLINICIAN']} employeeId="E1" onSessionExpired={noop} />],
  ];
  it.each(screens)('%s renders', (_, el) => {
    expect(() => renderToStaticMarkup(el)).not.toThrow();
  });
});

describe('support cases (named clinician)', () => {
  it('lists cases with risk, sharing choice and EAP / relief markers and validates a new case', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /support/cases': [supportCase({ risk: 'URGENT', eapReferral: { id: 'r1', ref: 'EAP-2026-000001', status: 'SENT' } })] });
    render(<SupportCases {...props(api, 'PSYCH_CLINICIAN')} />);
    const row = (await screen.findByRole('link', { name: 'SUP-2026-000001' })).closest('tr')!;
    expect(row.textContent).toContain('Urgent risk');
    expect(row.textContent).toContain('EAP EAP-2026-000001');
    expect(row.textContent).toContain('Manager sees fitness status only');
    await user.click(screen.getByRole('button', { name: 'Open support case' }));
    await user.click(screen.getByRole('button', { name: 'Open case' }));
    expect(screen.getByRole('link', { name: 'Enter the employee ID.' })).toBeTruthy();
    expect(api.find('POST', '/support/cases')).toHaveLength(0);
  });

  it('administers a PHQ-9 with live band, urgent warning and posts the answers', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /support/cases/s1': supportCase({ screens: [{ instrument: 'GAD7', score: 12, band: 'MODERATE', urgent: false, completedAt: '2026-10-21T10:00:00Z', source: 'SELF' }] }),
      'POST /support/cases/s1/screens': { instrument: 'PHQ9', score: 9, band: 'MILD', urgent: true, nextSteps: [] },
    });
    render(<SupportCaseScreen {...props(api, 'PSYCH_CLINICIAN')} caseId="s1" />);
    expect(await screen.findByText('Moderate (10–14)')).toBeTruthy();
    expect(screen.getByText('Manager sees fitness status only')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Administer screen' }));
    const form = screen.getByRole('form', { name: 'Administer screen' });
    await user.click(within(form).getByRole('button', { name: 'Record screen' }));
    expect(within(form).getAllByRole('link', { name: /Answer item/ })).toHaveLength(9);
    for (let i = 0; i < 8; i++) await user.selectOptions(within(form).getByLabelText(new RegExp(`^${i + 1}\\. `)), '1');
    await user.selectOptions(within(form).getByLabelText(/^9\. /), '1');
    expect(within(form).getByText('Mild (5–9)')).toBeTruthy();
    expect(within(form).getByText(/Urgent rule met \(item 9 above zero\)/)).toBeTruthy();
    await user.click(within(form).getByRole('button', { name: 'Record screen' }));
    await waitFor(() => expect(api.find('POST', '/support/cases/s1/screens')).toHaveLength(1));
    expect(api.find('POST', '/support/cases/s1/screens')[0]!.body).toEqual({ instrument: 'PHQ9', answers: [1, 1, 1, 1, 1, 1, 1, 1, 1] });
    expect(await screen.findByText(/URGENT: same-day call-back task created/)).toBeTruthy();
  });

  it('EAP referral requires a consent from the picker and shows a 422 field error next to it', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /support/cases/s1': supportCase({ caseId: 'k1' }),
      'GET /cases/k1/consents?purpose=TREATMENT_SHARING_PARTNER': [{ id: 'con1', purpose: 'TREATMENT_SHARING_PARTNER', grantedAt: '2026-10-01T00:00:00Z' }],
      'POST /support/cases/s1/eap-referrals': reply(422, { code: 'consent_inactive', field: 'consentId', detail: 'The consent has been withdrawn' }),
    });
    render(<SupportCaseScreen {...props(api, 'PSYCH_CLINICIAN')} caseId="s1" />);
    await user.click(await screen.findByRole('button', { name: 'Refer to EAP' }));
    const form = screen.getByRole('form', { name: 'EAP referral' });
    await user.click(within(form).getByRole('button', { name: 'Send referral' }));
    expect(within(form).getByRole('link', { name: /partner-sharing consent/ })).toBeTruthy();
    await user.click(await within(form).findByRole('radio', { name: /Treatment sharing partner/ }));
    await user.clear(within(form).getByLabelText(/Sessions authorised/));
    await user.type(within(form).getByLabelText(/Sessions authorised/), '8');
    await user.click(within(form).getByRole('button', { name: 'Send referral' }));
    await waitFor(() => expect(api.find('POST', '/support/cases/s1/eap-referrals')).toHaveLength(1));
    expect(api.find('POST', '/support/cases/s1/eap-referrals')[0]!.body).toEqual({ partnerCode: 'EAP_WELLBEING', sessionsAuthorised: 8, urgency: 'ROUTINE', consentId: 'con1' });
    expect(await within(form).findByRole('link', { name: 'The consent has been withdrawn' })).toBeTruthy();
  });

  it('records an urgent contact outcome and closes the case', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /support/cases/s1': supportCase(), 'POST /support/cases/s1/urgent-contact': {}, 'POST /support/cases/s1/close': {} });
    render(<SupportCaseScreen {...props(api, 'PSYCH_CLINICIAN')} caseId="s1" />);
    const form = await screen.findByRole('form', { name: 'Record urgent contact' });
    await user.selectOptions(within(form).getByLabelText(/Outcome/), 'REACHED_SAFE');
    await user.type(within(form).getByLabelText(/Note/), 'Safety plan agreed');
    await user.click(within(form).getByRole('button', { name: 'Record outcome' }));
    await waitFor(() => expect(api.find('POST', '/support/cases/s1/urgent-contact')[0]?.body).toEqual({ outcome: 'REACHED_SAFE', note: 'Safety plan agreed' }));
    await user.type(screen.getByLabelText(/Reason/), 'Recovered');
    await user.click(screen.getByRole('button', { name: 'Close case' }));
    await waitFor(() => expect(api.find('POST', '/support/cases/s1/close')[0]?.body).toEqual({ reason: 'Recovered' }));
  });
});

describe('urgent queue', () => {
  it('sorts by call-back deadline with countdown and shows the protocol script', async () => {
    const api = mockApi({
      'GET /support/urgent': [
        { caseId: 's2', ref: 'SUP-2026-000002', displayName: 'Later', callBackBy: inMinutes(300), band: 'SEVERE' },
        { caseId: 's1', ref: 'SUP-2026-000001', displayName: 'Sooner', callBackBy: inMinutes(-20), reason: 'PHQ9_ITEM9' },
      ],
      'GET /support/protocols/urgent-risk': { version: 3, steps: ['Confirm identity and location', { title: 'Ask directly', text: 'about thoughts of self-harm' }] },
      'GET /support/governance': { protocolVersion: 3, signOff: { status: 'PENDING', pendingWith: ['CMO'] } },
    });
    render(<UrgentQueue {...props(api, 'PSYCH_CLINICIAN')} />);
    const links = await screen.findAllByRole('link', { name: /^SUP-/ });
    expect(links.map((l) => l.textContent)).toEqual(['SUP-2026-000001', 'SUP-2026-000002']);
    expect(links[0]!.closest('tr')!.textContent).toMatch(/Overdue by 2\d min/);
    expect(await screen.findByText('Ask directly – about thoughts of self-harm')).toBeTruthy();
    expect(screen.getByText('Pending – waiting for CMO')).toBeTruthy();
  });
});

describe('peer support', () => {
  it('volunteer: routed requests, pseudonymous contact log (rejects employee IDs) and referral', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /peer-support/my-requests': [{ contactCode: 'PS-7K3Q', alias: 'Sky', callbackPhone: '+44 7700 900123', language: 'en', requestedAt: '2026-10-29T08:00:00Z' }],
      'POST /peer-support/contacts': { id: 'pc1', contactCode: 'PS-7K3Q', date: '2026-10-30', durationMinutes: 30, category: 'FATIGUE', outcome: 'SUPPORTED' },
      'POST /peer-support/contacts/pc1/refer': { id: 'pc1', referredAt: '2026-10-30T10:00:00Z' },
    });
    render(<PeerSupport {...props(api, 'PEER_SUPPORT_VOLUNTEER')} />);
    expect(screen.queryByRole('heading', { name: /Volunteers, training/ })).toBeNull();
    expect(screen.queryByRole('heading', { name: /Quarterly report/ })).toBeNull();
    await user.click(await screen.findByRole('button', { name: 'Log contact PS-7K3Q' }));
    const code = screen.getByLabelText(/Contact code/) as HTMLInputElement;
    expect(code.value).toBe('PS-7K3Q');
    await user.clear(code);
    await user.type(code, 'E1004');
    await user.click(screen.getByRole('button', { name: 'Log contact' }));
    expect(screen.getByRole('link', { name: /never an employee ID/ })).toBeTruthy();
    await user.clear(code);
    await user.type(code, 'PS-7K3Q');
    await user.type(screen.getByLabelText(/Duration/), '30');
    await user.selectOptions(screen.getByLabelText(/Category/), 'FATIGUE');
    await user.selectOptions(screen.getByLabelText(/Outcome/), 'SUPPORTED');
    await user.click(screen.getByRole('button', { name: 'Log contact' }));
    await waitFor(() => expect(api.find('POST', '/peer-support/contacts')).toHaveLength(1));
    const body = api.find('POST', '/peer-support/contacts')[0]!.body as Record<string, unknown>;
    expect(Object.keys(body).sort()).toEqual(['category', 'contactCode', 'date', 'durationMinutes', 'outcome']);
    await user.click(await screen.findByRole('button', { name: 'Refer PS-7K3Q to the trusted professional' }));
    await waitFor(() => expect(api.find('POST', '/peer-support/contacts/pc1/refer')).toHaveLength(1));
    expect(await screen.findByText('Referred')).toBeTruthy();
  });

  it('coordinator: register with training expiry, quarterly report with suppressed rows and send', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /peer-support/volunteers': [{ id: 'v1', username: 'peer1', displayName: 'Pip', base: 'LHR', languages: ['en'], available: true, trainingRecords: [{ course: 'CISM peer', completedOn: '2024-01-01', expiresOn: '2025-01-01' }] }],
      'PUT /peer-support/volunteers/v1': {},
      'GET /peer-support/reports/quarterly': { quarter: '2026-Q3', rows: [{ base: 'LHR', category: 'FATIGUE', contacts: 12, suppressed: false }, { base: 'DXB', category: 'TRAUMA', suppressed: true }], totals: { contacts: 15 } },
      'POST /peer-support/reports/quarterly/send': { quarter: '2026-Q3', rows: [], reportRef: 'SMS-77' },
    });
    render(<PeerSupport {...props(api, 'PEER_SUPPORT_COORDINATOR')} />);
    expect(await screen.findByText(/expires/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Mark Pip unavailable' }));
    await waitFor(() => expect(api.find('PUT', '/peer-support/volunteers/v1')[0]?.body).toMatchObject({ available: false, username: 'peer1' }));
    const dxb = (await screen.findByRole('rowheader', { name: 'DXB' })).closest('tr')!;
    expect(dxb.textContent).toContain('<5');
    expect(dxb.textContent).not.toMatch(/\d{2}/);
    expect(screen.getByRole('rowheader', { name: 'LHR' }).closest('tr')!.textContent).toContain('12');
    await user.click(screen.getByRole('button', { name: 'Send to SMS' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Send' }));
    await waitFor(() => expect(api.find('POST', '/peer-support/reports/quarterly/send')).toHaveLength(1));
    expect(await screen.findByText(/SMS-77/)).toBeTruthy();
  });
});

describe('CISM', () => {
  const ev = { id: 'ev1', ref: 'CISM-2026-000001', title: 'Emergency landing WZ101', category: 'EMERGENCY_LANDING', occurredAt: '2026-10-29T10:00:00Z', affected: 6, offered: 2, accepted: 1, offerDueBy: inMinutes(600) };
  it('SAFETY_MANAGER sees counts only (no drill-down link)', async () => {
    const api = mockApi({ 'GET /cism/events': [ev] });
    render(<CismEvents {...props(api, 'SAFETY_MANAGER')} />);
    expect(await screen.findByText('CISM-2026-000001')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'CISM-2026-000001' })).toBeNull();
    expect(screen.getByText(/Aggregated counts only/)).toBeTruthy();
  });
  it('clinician: affected list, offers to the not-yet-offered and follow-up update', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /cism/events/ev1': { ...ev, affected: [
        { employeeId: 'E1002', displayName: 'Cara Crew', dutyRole: 'CABIN', offerSentAt: '2026-10-29T12:00:00Z', response: 'ACCEPTED', followUp1w: { dueOn: '2026-11-05', done: false }, followUp4w: { dueOn: '2026-11-26', done: false } },
        { employeeId: 'E1003', displayName: 'Finn Flight', dutyRole: 'FO' },
      ] },
      'POST /cism/events/ev1/offers': {},
      'PUT /cism/events/ev1/affected/E1002': {},
    });
    render(<CismEventScreen {...props(api, 'OH_CLINICIAN')} eventId="ev1" />);
    await user.click(await screen.findByRole('button', { name: 'Send offers to 1 not yet offered' }));
    await waitFor(() => expect(api.find('POST', '/cism/events/ev1/offers')).toHaveLength(1));
    await user.click(screen.getByRole('button', { name: 'Update Cara Crew' }));
    const dlg = screen.getByRole('dialog');
    await user.click(within(dlg).getByLabelText('1-week follow-up done'));
    await user.click(within(dlg).getByLabelText('Refer to a confidential support case'));
    await user.click(within(dlg).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(api.find('PUT', '/cism/events/ev1/affected/E1002')[0]?.body).toEqual({ response: 'ACCEPTED', followUp1wDone: true, followUp4wDone: false, referToSupport: true }));
  });
});

describe('psychosocial register', () => {
  it('scores factors live and shows suppressed support-case rates', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /psychosocial/dashboard': [{ unitCode: 'CABIN', rag: 'AMBER', overallScore: 12, reviewDate: '2026-01-01', overdueReview: true, openActions: 2, overdueActions: 1, suppressed: true }],
    });
    render(<Psychosocial {...props(api, 'HSE_OFFICER')} />);
    const row = (await screen.findByRole('button', { name: 'Show assessments for CABIN' })).closest('tr')!;
    expect(row.textContent).toContain('suppressed');
    expect(row.textContent).toContain('Overdue');
    await user.click(screen.getByRole('button', { name: 'New assessment' }));
    await user.selectOptions(screen.getByLabelText('Workload likelihood'), '4');
    await user.selectOptions(screen.getByLabelText('Workload severity'), '4');
    expect(screen.getByText(/Overall \(highest factor\)/).textContent).toContain('Red (16)');
    await user.click(screen.getByRole('button', { name: 'Save assessment' }));
    expect(screen.getByRole('link', { name: 'Enter the unit code.' })).toBeTruthy();
  });
});

describe('mental-health fitness (AME)', () => {
  it('forces the authority report for referral and posts a structured assessment', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /support/fitness-assessments': [], 'POST /support/fitness-assessments': { id: 'f1', employeeId: 'E1002', standardCode: 'CLASS_1', medications: [], decision: 'REFER_TO_AUTHORITY', reviewDate: '2027-01-15', authorityReportRequired: true, documentId: 'd1' } });
    render(<SupportFitness {...props(api, 'AME')} />);
    await user.type(screen.getAllByLabelText(/Employee ID/)[0]!, 'E1002');
    await user.click(screen.getByRole('button', { name: 'Show assessments' }));
    expect(await screen.findByText('No mental-health fitness assessments.')).toBeTruthy();
    await user.type(screen.getByLabelText(/Fitness standard/), 'CLASS_1');
    await user.click(screen.getByRole('button', { name: 'Add medication' }));
    await user.type(screen.getByLabelText(/^Name/), 'Sertraline');
    await user.type(screen.getByLabelText(/Drug class/), 'SSRI');
    await user.type(screen.getByLabelText(/^Dose/), '50 mg');
    await user.type(screen.getByLabelText(/Start date/), '2026-09-01');
    await user.type(screen.getByLabelText(/Findings/), 'Stable mood; no side effects');
    await user.click(screen.getByLabelText('Refer to authority'));
    const auth = screen.getByLabelText(/Authority report required/) as HTMLInputElement;
    expect(auth.checked).toBe(true);
    expect(auth.disabled).toBe(true);
    await user.type(screen.getByLabelText(/Review date/), '2027-01-15');
    await user.click(screen.getByRole('button', { name: 'Save assessment' }));
    await waitFor(() => expect(api.find('POST', '/support/fitness-assessments')).toHaveLength(1));
    expect(api.find('POST', '/support/fitness-assessments')[0]!.body).toEqual({
      employeeId: 'E1002', standardCode: 'CLASS_1', medications: [{ name: 'Sertraline', drugClass: 'SSRI', dose: '50 mg', startDate: '2026-09-01' }],
      findings: 'Stable mood; no side effects', decision: 'REFER_TO_AUTHORITY', reviewDate: '2027-01-15', authorityReportRequired: true,
    });
    expect(await screen.findByText(/Authority fitness report generated/)).toBeTruthy();
  });

  it('PSYCH_CLINICIAN reads history only (no assessment form)', () => {
    const api = mockApi({});
    render(<SupportFitness {...props(api, 'PSYCH_CLINICIAN')} />);
    expect(screen.queryByRole('heading', { name: 'New structured assessment' })).toBeNull();
    expect(screen.getByRole('heading', { name: 'Temporary relief from duties' })).toBeTruthy();
    expect(screen.queryByRole('form', { name: 'Return decision' })).toBeNull();
  });
});

describe('restricted case placeholder (general OH roles)', () => {
  it('shows only the count and no support link for an OH clinician', async () => {
    const api = mockApi({ 'GET /support/placeholder?employeeId=E1002': { restrictedCases: 2 } });
    render(<RestrictedCasePlaceholder client={api.client} roles={['OH_CLINICIAN']} employeeId="E1002" onSessionExpired={noop} />);
    expect(await screen.findByText('2 restricted cases')).toBeTruthy();
    expect(screen.queryByRole('link')).toBeNull();
  });
  it('renders nothing for zero, on 404, or for roles outside OH', async () => {
    const zero = mockApi({ 'GET /support/placeholder': { restrictedCases: 0 } });
    const { container, unmount } = render(<RestrictedCasePlaceholder client={zero.client} roles={['NURSING_HEAD']} employeeId="E1" onSessionExpired={noop} />);
    await waitFor(() => expect(zero.calls).toHaveLength(1));
    expect(container.textContent).toBe('');
    unmount();
    const denied = mockApi({});
    const r2 = render(<RestrictedCasePlaceholder client={denied.client} roles={['OH_CLINICIAN']} employeeId="E1" onSessionExpired={noop} />);
    await waitFor(() => expect(denied.calls).toHaveLength(1));
    expect(r2.container.textContent).toBe('');
    r2.unmount();
    const outside = mockApi({ 'GET /support/placeholder': { restrictedCases: 3 } });
    const r3 = render(<RestrictedCasePlaceholder client={outside.client} roles={['COLLECTOR']} employeeId="E1" onSessionExpired={noop} />);
    expect(r3.container.textContent).toBe('');
    expect(outside.calls).toHaveLength(0);
  });
});
