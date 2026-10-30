// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it } from 'vitest';
import type { AuthClient } from '../../auth/AuthClient';
import { addDaysIso } from '../../domain/promotion';
import { mockApi, sessionFor } from '../../testing/mockApi';
import { PromotionCampaigns } from './Campaigns';
import { Ergonomics } from './Ergonomics';
import { PromotionInsights } from './Insights';
import { PromotionReports } from './Reports';
import { ScreeningEvents } from './Screening';

afterEach(() => {
  cleanup();
  window.location.hash = '';
});
const noop = () => {};

describe('E12 screens smoke (initial render, no effects)', () => {
  const client: AuthClient = { mode: 'bff', init: async () => sessionFor(), login: async () => undefined, logout: async () => undefined, api: () => new Promise(() => {}) };
  const base = { client, session: sessionFor('HEALTH_PROMOTION_LEAD', 'ERGONOMIST'), onSessionExpired: noop };
  const screens: [string, ReactElement][] = [
    ['PromotionInsights', <PromotionInsights {...base} />],
    ['PromotionCampaigns', <PromotionCampaigns {...base} />],
    ['ScreeningEvents', <ScreeningEvents {...base} />],
    ['Ergonomics', <Ergonomics {...base} />],
    ['PromotionReports', <PromotionReports {...base} />],
  ];
  it.each(screens)('%s renders', (_, el) => {
    expect(renderToStaticMarkup(el)).toContain('<h1');
  });
});

describe('PromotionInsights', () => {
  it('shows anonymised hotspots; suppressed units never show a number; plan-campaign link carries the insight', async () => {
    const api = mockApi({
      'GET /promotion/insights': [
        { unitCode: 'RAMP', risk: 'MSK', indicator: 'MSK cases per 100 FTE', value: 8.4, period: '2026-Q3', suppressed: false },
        { unitCode: 'CARGO', risk: 'MSK', indicator: 'MSK cases per 100 FTE', value: 3, period: '2026-Q3', suppressed: true },
      ],
    });
    render(<PromotionInsights client={api.client} session={sessionFor('HEALTH_PROMOTION_LEAD')} onSessionExpired={noop} />);
    const table = await screen.findByRole('table', { name: /Hotspots/ });
    expect(within(table).getByText('8.4')).toBeTruthy();
    const cargo = within(table).getByText('CARGO').closest('tr')!;
    expect(cargo.className).toBe('row-suppressed');
    expect(cargo.textContent).not.toContain('3');
    expect(within(table).getByRole('link', { name: 'Plan campaign' }).getAttribute('href')).toBe('#/promotion/campaigns?risk=MSK&unit=RAMP&insight=RAMP%3AMSK%3A2026-Q3');
    cleanup();
    render(<PromotionInsights client={api.client} session={sessionFor('CMO')} onSessionExpired={noop} />);
    await screen.findByRole('table', { name: /Hotspots/ });
    expect(screen.queryByRole('link', { name: 'Plan campaign' })).toBeNull();
  });
});

describe('PromotionCampaigns', () => {
  const campaign = { id: 'pc1', title: 'Back care', risk: 'MSK', objective: 'Fewer MSK complaints', targetUnits: ['RAMP'], budget: { amount: '2500.00', currency: 'GBP' }, startDate: '2026-11-01', endDate: '2027-01-31', owners: ['promo1'], kpis: [{ metric: 'participation_pct', target: 60 }], status: 'PLANNED' };

  it('plans from an insight (pre-filled), validates, saves and publishes', async () => {
    const user = userEvent.setup();
    window.location.hash = '#/promotion/campaigns?risk=MSK&unit=RAMP&insight=RAMP%3AMSK%3A2026-Q3';
    const api = mockApi({ 'GET /promotion/campaigns': [campaign], 'POST /promotion/campaigns': { ...campaign, id: 'pc2', title: 'Lift smart' }, 'POST /promotion/campaigns/pc1/publish': { ...campaign, publishedAt: '2026-10-30T10:00:00Z' } });
    render(<PromotionCampaigns client={api.client} session={sessionFor('HEALTH_PROMOTION_LEAD')} onSessionExpired={noop} />);
    expect((screen.getByLabelText(/^Target units/) as HTMLInputElement).value).toBe('RAMP');
    expect((screen.getByLabelText(/^Insight reference/) as HTMLInputElement).value).toBe('RAMP:MSK:2026-Q3');
    await user.click(screen.getByRole('button', { name: 'Save campaign' }));
    const summary = screen.getByRole('alert');
    for (const m of ['Enter a title.', 'Describe the objective.', 'Name at least one owner.']) expect(within(summary).getByText(m)).toBeTruthy();
    await user.type(screen.getByLabelText(/^Title/), 'Lift smart');
    await user.type(screen.getByLabelText(/^Objective/), 'Safer lifting on the ramp');
    await user.type(screen.getByLabelText(/^Budget/), '1200');
    await user.type(screen.getByLabelText(/^End/), '2027-02-28');
    await user.type(screen.getByLabelText(/^Owners/), 'promo1, ergo1');
    await user.type(screen.getByLabelText(/^Target$/), '50');
    await user.click(screen.getByRole('button', { name: 'Save campaign' }));
    await waitFor(() =>
      expect(api.find('POST', '/promotion/campaigns')[0]?.body).toMatchObject({
        title: 'Lift smart', risk: 'MSK', targetUnits: ['RAMP'], insightRef: 'RAMP:MSK:2026-Q3', budget: { amount: '1200.00', currency: 'GBP' }, owners: ['promo1', 'ergo1'], kpis: [{ metric: 'participation_pct', target: 50 }], status: 'PLANNED',
      }),
    );
    await user.click(await screen.findByRole('button', { name: 'Back care' }));
    await user.click(screen.getByRole('button', { name: 'Publish Back care' }));
    await waitFor(() => expect(api.find('POST', '/promotion/campaigns/pc1/publish')).toHaveLength(1));
    expect(await screen.findByText(/published – invitations sent/)).toBeTruthy();
  });
});

describe('ScreeningEvents', () => {
  const event = { id: 'se1', type: 'BIOMETRIC', siteCode: 'LHR-T5', date: '2026-11-03', participantCount: 2, participants: [{ employeeId: 'E1001', displayName: 'Ann Able' }, { employeeId: 'E1004', displayName: 'Rami Ramp', resultRecorded: true, abnormal: true }] };

  it('promotion lead: create event, add participants – no result entry and no participant list', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /promotion/screening-events': [event], 'GET /promotion/screening-events/se1': { ...event, participants: undefined }, 'GET /promotion/campaigns': [], 'POST /promotion/screening-events/se1/participants': {} });
    render(<ScreeningEvents client={api.client} session={sessionFor('HEALTH_PROMOTION_LEAD')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'Biometric screening' }));
    expect(await screen.findByText('2 participants')).toBeTruthy();
    expect(screen.queryByText('Enter results')).toBeNull();
    expect(screen.queryByText('Rami Ramp', { exact: false })).toBeNull();
    expect(screen.getByText('Results are entered by clinicians and are not shown here.')).toBeTruthy();
    await user.type(screen.getByLabelText(/Add participants/), 'e1009, bob');
    await user.click(screen.getByRole('button', { name: 'Add participants' }));
    expect(screen.getByText('Not employee IDs: BOB')).toBeTruthy();
    await user.clear(screen.getByLabelText(/Add participants/));
    await user.type(screen.getByLabelText(/Add participants/), 'e1009 E1010');
    await user.click(screen.getByRole('button', { name: 'Add participants' }));
    await waitFor(() => expect(api.find('POST', '/promotion/screening-events/se1/participants')[0]?.body).toEqual({ employeeIds: ['E1009', 'E1010'] }));
  });

  it('clinician: result entry with live threshold flags → abnormal → follow-up case notice', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /promotion/screening-events': [event],
      'GET /promotion/screening-events/se1': event,
      'POST /promotion/screening-events/se1/results': { abnormal: true, flags: [{ measure: 'systolic', flag: 'HIGH' }], followUpCaseId: 'c77', followUpCaseRef: 'OH-2026-000077', letterDocumentId: 'd5' },
    });
    render(<ScreeningEvents client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    expect(screen.queryByText('New screening event')).not.toBeNull();
    await user.click(await screen.findByRole('button', { name: 'Biometric screening' }));
    expect(await screen.findByText('Abnormal – follow-up')).toBeTruthy();
    const participant = screen.getByRole('combobox', { name: /Participant/ });
    expect(within(participant).queryByText('Rami Ramp (E1004)')).toBeNull(); // already recorded
    await user.click(screen.getByRole('button', { name: 'Save result' }));
    expect(within(screen.getByRole('alert')).getByText('Choose the participant.')).toBeTruthy();
    await user.selectOptions(participant, 'E1001');
    await user.type(screen.getByLabelText(/^Systolic BP/), '152');
    await user.type(screen.getByLabelText(/^Diastolic BP/), '84');
    expect(screen.getByText('High')).toBeTruthy();
    expect(screen.getByText('In range')).toBeTruthy();
    expect(screen.getByText(/saving opens a follow-up case \(within 48 h\)/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Save result' }));
    await waitFor(() => expect(api.find('POST', '/promotion/screening-events/se1/results')[0]?.body).toEqual({ employeeId: 'E1001', measures: { systolic: 152, diastolic: 84 } }));
    expect((await screen.findByRole('link', { name: 'OH-2026-000077' })).getAttribute('href')).toBe('#/cases/c77');
    expect(screen.getByText(/contact within 48 h/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Result letter' }).getAttribute('href')).toBe('/bff/api/documents/d5/content');
  });
});

describe('Ergonomics', () => {
  const assessment = {
    id: 'ea1', ref: 'ERG-2026-000001', type: 'MANUAL_HANDLING', unitCode: 'RAMP', jobCode: 'RAMP_AGENT', location: 'LHR stand 5', checklist: { LOAD_WEIGHT: 3 }, riskScore: 17, rag: 'RED', createdAt: '2026-10-20T10:00:00Z',
    jobDemandProfile: { jobCode: 'RAMP_AGENT', title: 'Ramp agent', unit: 'GROUND', demands: { LIFT_KG: 25, WORK_AT_HEIGHT: false } },
    photos: [{ id: 'p1', fileName: 'belt-loader.jpg' }],
    actions: [
      { id: 'a1', title: 'Install roller bed', owner: 'hse1', dueDate: addDaysIso(-3), status: 'OPEN' },
      { id: 'a2', title: 'Team lift training', owner: 'ergo1', dueDate: addDaysIso(20), status: 'OPEN' },
    ],
  };

  it('assessment form: checklist scoring with live RAG preview, validation and save', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /ergonomics/assessments': [], 'POST /ergonomics/assessments': { ...assessment, id: 'ea2', actions: [] }, 'GET /ergonomics/assessments/ea2': { ...assessment, id: 'ea2', actions: [] } });
    render(<Ergonomics client={api.client} session={sessionFor('HSE_OFFICER')} onSessionExpired={noop} />);
    await user.click(screen.getByText('New ergonomic assessment'));
    await user.selectOptions(screen.getByLabelText(/^Type/), 'TASK');
    await user.click(screen.getByRole('button', { name: 'Save assessment' }));
    expect(within(screen.getByRole('alert')).getByText('Score every checklist item (7 missing).')).toBeTruthy();
    await user.type(screen.getByLabelText(/^Unit/), 'ramp');
    await user.type(screen.getByLabelText(/^Job code/), 'ramp_agent');
    await user.type(screen.getByLabelText(/^Location/), 'Stand 5');
    for (const item of ['Awkward posture', 'Force', 'Repetition', 'Duration without recovery', 'Vibration', 'Environment (cold, heat, lighting)']) await user.click(screen.getByLabelText(`${item}: 1 – low`));
    await user.click(screen.getByLabelText('Work organisation / pace: 3 – high'));
    expect(screen.getByText(/Score 9 \/ 21 \(43 %, 7 of 7 scored\)/)).toBeTruthy();
    expect(screen.getByText('Amber – medium risk')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Save assessment' }));
    await waitFor(() =>
      expect(api.find('POST', '/ergonomics/assessments')[0]?.body).toEqual({
        type: 'TASK', unitCode: 'RAMP', jobCode: 'RAMP_AGENT', location: 'Stand 5',
        checklist: { POSTURE: 1, FORCE: 1, REPETITION: 1, DURATION: 1, VIBRATION: 1, ENVIRONMENT: 1, WORK_ORGANISATION: 3 },
      }),
    );
  });

  it('detail: job-demand profile, photos (ergonomist only), actions with overdue highlighting, close action', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /ergonomics/assessments': [assessment], 'GET /ergonomics/assessments/ea1': assessment, 'PUT /ergonomics/assessments/ea1/actions/a1': {}, 'POST /ergonomics/assessments/ea1/photos': {} });
    render(<Ergonomics client={api.client} session={sessionFor('ERGONOMIST')} onSessionExpired={noop} />);
    const open = await screen.findByRole('button', { name: 'ERG-2026-000001' });
    expect(within(screen.getByRole('group', { name: 'Ergonomics KPIs' })).getByText('Overdue actions').previousSibling?.textContent).toBe('1');
    expect(open.closest('tr')?.className).toBe('row-breached');
    await user.click(open);
    expect(await screen.findByText('Ramp agent (RAMP_AGENT) · GROUND')).toBeTruthy();
    expect(screen.getByText('25')).toBeTruthy();
    expect(screen.getByText('belt-loader.jpg')).toBeTruthy();
    const actions = screen.getByRole('table', { name: 'Actions' });
    const overdue = within(actions).getByText('Overdue 3 d').closest('tr')!;
    expect(overdue.className).toBe('row-breached');
    expect(within(actions).getByText('Due in 20 d')).toBeTruthy();
    const input = screen.getByLabelText(/Upload a workstation/) as HTMLInputElement;
    await userEvent.setup({ applyAccept: false }).upload(input, new File(['x'], 'notes.txt', { type: 'text/plain' }));
    expect(screen.getByText('Choose a JPEG, PNG, WebP or HEIC image.')).toBeTruthy();
    await user.upload(input, new File(['jpg'], 'desk.jpg', { type: 'image/jpeg' }));
    await waitFor(() => expect(api.find('POST', '/ergonomics/assessments/ea1/photos')[0]?.body).toBeInstanceOf(FormData));
    await user.click(within(overdue).getByRole('button', { name: 'Close Install roller bed' }));
    await waitFor(() => expect(api.find('PUT', '/ergonomics/assessments/ea1/actions/a1')[0]?.body).toMatchObject({ title: 'Install roller bed', owner: 'hse1', status: 'DONE' }));
    cleanup();
    render(<Ergonomics client={api.client} session={sessionFor('HSE_OFFICER')} onSessionExpired={noop} />);
    await user.click(await screen.findByRole('button', { name: 'ERG-2026-000001' }));
    await screen.findByText('belt-loader.jpg');
    expect(screen.queryByLabelText(/Upload a workstation/)).toBeNull();
  });
});

describe('PromotionReports', () => {
  const report = {
    participation: [
      { key: 'RAMP', invited: 60, participated: 33, pct: 55, suppressed: false },
      { key: 'CARGO', invited: 3, participated: 3, pct: 100, suppressed: false },
      { key: 'MRO', suppressed: true },
    ],
    outcomes: [
      { metric: 'mean_systolic', baseline: 138, current: 134.6, delta: -3.4 },
      { metric: 'wellbeing_score', baseline: 50, current: 48, delta: -2 },
    ],
  };

  it('participation with suppressed rows, outcome deltas, CSV link (lead picks from the portfolio)', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /promotion/campaigns': [{ id: 'pc1', title: 'Back care' }], 'GET /promotion/campaigns/pc1/report': report });
    render(<PromotionReports client={api.client} session={sessionFor('HEALTH_PROMOTION_LEAD')} onSessionExpired={noop} />);
    await user.selectOptions(await screen.findByLabelText('Campaign'), 'pc1');
    const table = await screen.findByRole('table', { name: /Participation – 2 groups suppressed/ });
    expect(within(table).getByText('55.0 %')).toBeTruthy();
    expect(within(table).getAllByText('Suppressed – group too small to report')).toHaveLength(2);
    expect(within(table).getByText('CARGO').closest('tr')!.textContent).not.toMatch(/\d/);
    const outcomes = screen.getByRole('table', { name: /Outcome change/ });
    expect(within(outcomes).getByText('−3.4').closest('.badge')?.className).toContain('badge-complete');
    expect(within(outcomes).getByText('−2').closest('.badge')?.className).toContain('badge-overdue');
    expect(screen.getByRole('link', { name: 'Download CSV' }).getAttribute('href')).toBe('/bff/api/promotion/campaigns/pc1/report?groupBy=DEPARTMENT&format=csv');
    expect(api.find('GET', '/promotion/campaigns/pc1/report?groupBy=DEPARTMENT')).toHaveLength(1);
  });

  it('CMO (no portfolio access) enters the campaign id', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /promotion/campaigns/pc1/report': report });
    render(<PromotionReports client={api.client} session={sessionFor('CMO')} onSessionExpired={noop} />);
    await user.type(screen.getByLabelText('Campaign id'), 'pc1');
    await screen.findByRole('table', { name: /Participation/ });
    expect(api.find('GET', '/promotion/campaigns?')).toHaveLength(0);
    expect(api.calls.some((c) => c.path === '/promotion/campaigns')).toBe(false);
  });
});
