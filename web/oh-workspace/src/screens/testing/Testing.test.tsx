// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it } from 'vitest';
import type { AuthClient, Session } from '../../auth/AuthClient';
import { mockApi, reply, sessionFor } from '../../testing/mockApi';
import { TestingCollection } from './Collection';
import { MroQueue, RtdPlans } from './Mro';
import { TestingPolicies, TestingPools, TestingSelections } from './Programme';
import { TestingReports } from './Reports';
import { TestingRequirements } from './Requirements';

afterEach(cleanup);
const noop = () => {};
const props = (api: ReturnType<typeof mockApi>, ...roles: string[]) => ({ client: api.client, session: sessionFor(...roles), onSessionExpired: noop });
const inMinutes = (m: number) => new Date(Date.now() + m * 60_000).toISOString();
const thisMonth = () => new Date().toISOString().slice(0, 7);

const policy = (over = {}) => ({ roleGroup: 'FLIGHT_CREW', jobCodes: ['CPT', 'FO'], panels: ['DRUGS_10', 'ALCOHOL_BREATH'], randomDrugRatePct: 25, randomAlcoholRatePct: 10, alcoholWindowHours: 8, drugWindowHours: 32, leadTimeMinutes: 60, jurisdiction: 'UK', consentRequired: false, approvalStatus: 'PENDING', version: 1, ...over });
const requirement = (over = {}) => ({ id: 't1', ref: 'DAT-2026-000001', employeeId: 'E1004', displayName: 'Rami Ramp', reason: 'POST_INCIDENT', panels: ['ALCOHOL_BREATH'], status: 'DISPATCHED', dueAt: inMinutes(45), siteCode: 'LHR-T5', ...over });

describe('E10 screens – smoke (initial render)', () => {
  const client: AuthClient = { mode: 'bff', init: async () => session, login: async () => undefined, logout: async () => undefined, api: () => new Promise(() => {}) };
  const session: Session = { authenticated: true, name: 'Dana Manager', roles: ['DA_PROGRAMME_MANAGER', 'COLLECTOR', 'MRO', 'CMO'] };
  const base = { client, session, onSessionExpired: noop };
  const screens: [string, ReactElement][] = [
    ['TestingPolicies', <TestingPolicies {...base} />],
    ['TestingPools', <TestingPools {...base} />],
    ['TestingSelections', <TestingSelections {...base} />],
    ['TestingRequirements', <TestingRequirements {...base} />],
    ['TestingCollection', <TestingCollection {...base} />],
    ['MroQueue', <MroQueue {...base} />],
    ['RtdPlans', <RtdPlans {...base} />],
    ['TestingReports', <TestingReports {...base} />],
  ];
  it.each(screens)('%s renders', (_, el) => {
    expect(() => renderToStaticMarkup(el)).not.toThrow();
  });
});

describe('policies and pools', () => {
  it('shows the pending-approval badge and count; read-only for SAFETY_MANAGER', async () => {
    const api = mockApi({ 'GET /testing/policies': [policy(), policy({ roleGroup: 'FORKLIFT', approvalStatus: 'APPROVED', approvedBy: 'legal1' })] });
    render(<TestingPolicies {...props(api, 'SAFETY_MANAGER')} />);
    expect(await screen.findByText('1 pending approval')).toBeTruthy();
    expect(screen.getByText('Pending Legal / Safety / HR approval')).toBeTruthy();
    expect(screen.getByText('Approved by legal1')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Edit/ })).toBeNull();
  });

  it('programme manager edits a policy (validated, PUT by role group)', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /testing/policies': [policy()], 'PUT /testing/policies/FLIGHT_CREW': policy({ version: 2 }) });
    render(<TestingPolicies {...props(api, 'DA_PROGRAMME_MANAGER')} />);
    await user.click(await screen.findByRole('button', { name: 'Edit Flight crew policy' }));
    const dlg = screen.getByRole('dialog');
    await user.clear(within(dlg).getByLabelText(/Random drug rate/));
    await user.type(within(dlg).getByLabelText(/Random drug rate/), '150');
    await user.click(within(dlg).getByRole('button', { name: 'Save policy' }));
    expect(within(dlg).getByRole('link', { name: /0–100/ })).toBeTruthy();
    await user.clear(within(dlg).getByLabelText(/Random drug rate/));
    await user.type(within(dlg).getByLabelText(/Random drug rate/), '50');
    await user.click(within(dlg).getByRole('button', { name: 'Save policy' }));
    await waitFor(() => expect(api.find('PUT', '/testing/policies/FLIGHT_CREW')).toHaveLength(1));
    expect(api.find('PUT', '/testing/policies/FLIGHT_CREW')[0]!.body).toMatchObject({ roleGroup: 'FLIGHT_CREW', randomDrugRatePct: 50, jobCodes: ['CPT', 'FO'] });
  });

  it('adds a pool exclusion', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /testing/pools': [{ roleGroup: 'FORKLIFT', size: 2, members: [{ employeeId: 'E1007', displayName: 'Fred Fork', jobCode: 'FLT' }, { employeeId: 'E1009', displayName: 'Gail Gate' }], exclusions: [] }],
      'POST /testing/pools/FORKLIFT/exclusions': {},
    });
    render(<TestingPools {...props(api, 'DA_PROGRAMME_MANAGER')} />);
    const form = await screen.findByRole('form', { name: 'Add exclusion to Forklift' });
    await user.selectOptions(within(form).getByLabelText(/Member/), 'E1007');
    await user.selectOptions(within(form).getByLabelText(/Reason/), 'LONG_TERM_ABSENCE');
    await user.type(within(form).getByLabelText(/Until/), '2027-01-31');
    await user.click(within(form).getByRole('button', { name: 'Exclude' }));
    await waitFor(() => expect(api.find('POST', '/testing/pools/FORKLIFT/exclusions')[0]?.body).toEqual({ employeeId: 'E1007', reason: 'LONG_TERM_ABSENCE', until: '2027-01-31' }));
  });
});

describe('random selections', () => {
  const sel = { id: 'sel1', ref: 'SEL-2026-000001', roleGroup: 'FLIGHT_CREW', period: thisMonth(), testType: 'DRUGS', poolSize: 120, poolHash: 'a'.repeat(64), seedCommitment: 'b'.repeat(64), selectedCount: 3, selected: [{ employeeId: 'E1002', displayName: 'Cara Crew' }], createdAt: '2026-10-01T08:00:00Z' };

  it('validates the form, previews n and blocks a duplicate', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /testing/selections': [sel], 'GET /testing/policies': [policy({ approvalStatus: 'APPROVED' })], 'GET /testing/pools': [{ roleGroup: 'FLIGHT_CREW', size: 120, members: [], exclusions: [] }] });
    render(<TestingSelections {...props(api, 'DA_PROGRAMME_MANAGER')} />);
    await screen.findByText(/SEL-2026-000001/);
    await user.click(screen.getByRole('button', { name: 'Draw selection' }));
    expect(screen.getByRole('link', { name: 'Choose the role group.' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Choose drugs or alcohol.' })).toBeTruthy();
    await user.selectOptions(screen.getByLabelText(/Role group/), 'FLIGHT_CREW');
    await user.selectOptions(screen.getByLabelText(/Test type/), 'DRUGS');
    expect(screen.getByText(/Expected:/).textContent).toContain('3 of 120');
    await user.click(screen.getByRole('button', { name: 'Draw selection' }));
    expect(screen.getByRole('link', { name: /already exists/ })).toBeTruthy();
    expect(api.find('POST', '/testing/selections')).toHaveLength(0);
  });

  it('creates, verifies reproducibility and notifies', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /testing/selections': [sel],
      'GET /testing/policies': [policy()],
      'GET /testing/pools': [],
      'POST /testing/selections': { ...sel, id: 'sel2', ref: 'SEL-2026-000002', testType: 'ALCOHOL', selectedCount: 1 },
      'GET /testing/selections/sel1/verify': { reproducible: true },
      'POST /testing/selections/sel1/notify': reply(204),
    });
    render(<TestingSelections {...props(api, 'DA_PROGRAMME_MANAGER')} />);
    await screen.findByText(/SEL-2026-000001/);
    await user.selectOptions(screen.getByLabelText(/Role group/), 'FLIGHT_CREW');
    expect(screen.getByText(/Random testing for this group should wait/)).toBeTruthy();
    expect(screen.getByText('Pending Legal / Safety / HR approval')).toBeTruthy();
    await user.selectOptions(screen.getByLabelText(/Test type/), 'ALCOHOL');
    await user.click(screen.getByRole('button', { name: 'Draw selection' }));
    await waitFor(() => expect(api.find('POST', '/testing/selections')[0]?.body).toEqual({ roleGroup: 'FLIGHT_CREW', period: thisMonth(), testType: 'ALCOHOL' }));
    await user.click(screen.getByRole('button', { name: 'Verify SEL-2026-000001' }));
    expect(await screen.findByText('Reproducible')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Notify SEL-2026-000001' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Notify' }));
    await waitFor(() => expect(api.find('POST', '/testing/selections/sel1/notify')).toHaveLength(1));
  });
});

describe('requirements, collection (COLLECTOR)', () => {
  it('requirements sorted by deadline with window countdown', async () => {
    const api = mockApi({ 'GET /testing/requirements': [requirement({ id: 't2', ref: 'DAT-2026-000002', panels: ['DRUGS_5'], dueAt: inMinutes(20 * 60) }), requirement()] });
    render(<TestingRequirements {...props(api, 'DA_PROGRAMME_MANAGER')} />);
    const rows = await screen.findAllByRole('rowheader');
    expect(rows.map((r) => r.textContent)).toEqual(['DAT-2026-000001', 'DAT-2026-000002']);
    expect(rows[0]!.closest('tr')!.textContent).toMatch(/Alcohol window: 4\d min left/);
    expect(rows[1]!.closest('tr')!.textContent).toMatch(/Drug window: (19|20) h/);
    expect(screen.queryByRole('link', { name: 'DAT-2026-000001' })).toBeNull();
  });

  it('e-CCF: wedge scan + Enter does not submit; validates; posts; then records custody', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /testing/requirements': [requirement({ panels: ['DRUGS_10'], reason: 'RANDOM', dueAt: inMinutes(600) })],
      'POST /testing/tests/t1/collection': {},
      'POST /testing/specimens/SP-998877/custody': {},
    });
    render(<TestingCollection {...props(api, 'COLLECTOR')} />);
    await user.click(await screen.findByRole('button', { name: 'Start collection DAT-2026-000001' }));
    const id = screen.getByLabelText(/Specimen ID/);
    await user.type(id, ' sp-998877{Enter}');
    expect((id as HTMLInputElement).value).toBe('SP-998877');
    expect(api.find('POST', '/testing/tests/t1/collection')).toHaveLength(0);
    expect(document.activeElement).toBe(screen.getByLabelText(/Specimen type/));
    await user.selectOptions(screen.getByLabelText(/Specimen type/), 'URINE');
    await user.click(screen.getByRole('button', { name: 'Sign & submit e-CCF' }));
    const summary = screen.getByRole('alert');
    expect(within(summary).getByRole('link', { name: /photo identity/ })).toBeTruthy();
    expect(within(summary).getByRole('link', { name: /temperature/ })).toBeTruthy();
    expect(within(summary).getByRole('link', { name: /Seal the specimen/ })).toBeTruthy();
    await user.click(screen.getByLabelText('Donor photo identity checked'));
    await user.click(screen.getByLabelText('Yes'));
    await user.click(screen.getByLabelText(/Tamper-evident seal/));
    await user.click(screen.getByLabelText(/Split specimen/));
    await user.click(screen.getByRole('button', { name: 'Sign & submit e-CCF' }));
    await waitFor(() => expect(api.find('POST', '/testing/tests/t1/collection')).toHaveLength(1));
    expect(api.find('POST', '/testing/tests/t1/collection')[0]!.body).toMatchObject({ specimenId: 'SP-998877', specimenType: 'URINE', siteCode: 'LHR-T5', temperatureInRange: true, sealIntact: true, donorIdentityChecked: true, splitSpecimen: true });
    const custody = await screen.findByRole('form', { name: 'Record custody event' });
    await user.click(within(custody).getByRole('button', { name: 'Record custody event' }));
    await waitFor(() => expect(api.find('POST', '/testing/specimens/SP-998877/custody')[0]?.body).toMatchObject({ event: 'SEALED', by: 'tester' }));
  });

  it('records a refusal (no-show)', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /testing/requirements': [requirement()], 'POST /testing/tests/t1/refusal': {} });
    render(<TestingCollection {...props(api, 'COLLECTOR')} />);
    await user.click(await screen.findByRole('button', { name: 'Start collection DAT-2026-000001' }));
    expect((screen.getByLabelText(/Specimen type/) as HTMLSelectElement).value).toBe('BREATH');
    await user.click(screen.getByRole('button', { name: 'Record refusal / no-show' }));
    const dlg = screen.getByRole('dialog');
    await user.selectOptions(within(dlg).getByLabelText(/Reason/), 'NO_SHOW');
    await user.type(within(dlg).getByLabelText(/Observations/), 'Did not attend');
    await user.click(within(dlg).getByRole('button', { name: 'Record refusal' }));
    await waitFor(() => expect(api.find('POST', '/testing/tests/t1/refusal')[0]?.body).toEqual({ reason: 'NO_SHOW', observations: 'Did not attend' }));
  });
});

describe('MRO and RTD', () => {
  const item = { testId: 't9', ref: 'DAT-2026-000009', employeeId: 'E1003', displayName: 'Finn Flight', reason: 'RANDOM', specimenId: 'SP-1', validity: 'VALID', receivedAt: '2026-10-29T10:00:00Z', results: [{ analyte: 'OPIATES', cutoff: 2000, value: 2600, unit: 'ng/mL', outcome: 'NON_NEGATIVE' }, { analyte: 'THC', cutoff: 50, value: 0, unit: 'ng/mL', outcome: 'NEGATIVE' }] };

  it('reviews results; legitimate prescription needs verification + interview; posts the verdict', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /testing/mro/queue': [item], 'POST /testing/tests/t9/mro-decision': {} });
    render(<MroQueue {...props(api, 'MRO')} />);
    expect((await screen.findByRole('rowheader', { name: 'DAT-2026-000009' })).closest('tr')!.textContent).toContain('OPIATES');
    await user.click(screen.getByRole('button', { name: 'Review DAT-2026-000009' }));
    await user.selectOptions(screen.getByLabelText(/Verdict/), 'NEGATIVE_LEGITIMATE_PRESCRIPTION');
    await user.click(screen.getByRole('button', { name: 'Record verdict' }));
    expect(screen.getByRole('link', { name: /Verify the prescription/ })).toBeTruthy();
    await user.click(screen.getByLabelText(/Prescription verified/));
    await user.type(screen.getByLabelText(/Donor interview note/), 'Codeine prescription confirmed');
    await user.click(screen.getByRole('button', { name: 'Record verdict' }));
    await waitFor(() => expect(api.find('POST', '/testing/tests/t9/mro-decision')[0]?.body).toEqual({ verdict: 'NEGATIVE_LEGITIMATE_PRESCRIPTION', prescriptionVerified: true, interviewNote: 'Codeine prescription confirmed' }));
    expect(await screen.findByText(/Negative legitimate prescription recorded/)).toBeTruthy();
  });

  it('RTD: create plan and record evaluation', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /testing/rtd-plans': [{ id: 'p1', employeeId: 'E1003', displayName: 'Finn Flight', caseId: 'k9', evaluator: 'Dr Sap', followUpTests: 6, followUpMonths: 12, status: 'ACTIVE', dutyStatus: 'REMOVED', followUps: [{ scheduledFor: '2026-12-03', status: 'SCHEDULED' }] }],
      'POST /testing/rtd-plans': { id: 'p2' },
      'POST /testing/rtd-plans/p1/evaluation': {},
    });
    render(<RtdPlans {...props(api, 'OH_PHYSICIAN')} />);
    expect(await screen.findByText('Duty: Removed')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'New plan' }));
    await user.click(screen.getByRole('button', { name: 'Create plan' }));
    expect(screen.getByRole('link', { name: 'Enter the employee ID.' })).toBeTruthy();
    await user.type(screen.getByLabelText(/^Employee ID/), 'E1003');
    await user.type(screen.getByLabelText(/Substance case/), 'k9');
    await user.type(screen.getByLabelText(/^Evaluator/), 'Dr Sap');
    await user.click(screen.getByRole('button', { name: 'Create plan' }));
    await waitFor(() => expect(api.find('POST', '/testing/rtd-plans')[0]?.body).toEqual({ employeeId: 'E1003', caseId: 'k9', evaluator: 'Dr Sap', followUpTests: 6, followUpMonths: 12 }));
    const evalForm = screen.getByRole('form', { name: 'Evaluation for Finn Flight' });
    await user.selectOptions(within(evalForm).getByLabelText(/Evaluation/), 'READY');
    await user.click(within(evalForm).getByRole('button', { name: 'Record evaluation' }));
    await waitFor(() => expect(api.find('POST', '/testing/rtd-plans/p1/evaluation')[0]?.body).toEqual({ outcome: 'READY' }));
  });
});

describe('statistics and reports', () => {
  it('suppresses small cells and offers annual report downloads to CMO', async () => {
    const year = new Date().getFullYear();
    const api = mockApi({
      [`GET /testing/statistics?year=${year}`]: [
        { reason: 'RANDOM', roleGroup: 'FLIGHT_CREW', tests: 48, verifiedPositive: 1, refusals: 0, positiveRatePct: 2.083, suppressed: false },
        { reason: 'FOR_CAUSE', roleGroup: 'FORKLIFT', tests: 2, verifiedPositive: 1, refusals: 0, positiveRatePct: 50, suppressed: true },
      ],
      [`GET /testing/coverage?year=${year}`]: [{ roleGroup: 'FLIGHT_CREW', poolSize: 120, targetTests: 30, completed: 20, coveragePct: 66.7, behindPlan: true }],
    });
    render(<TestingReports {...props(api, 'CMO')} />);
    const forCause = (await screen.findByRole('rowheader', { name: 'For cause' })).closest('tr')!;
    expect(forCause.textContent).toContain('<5');
    expect(forCause.textContent).toContain('suppressed');
    expect(forCause.textContent).not.toContain('50');
    expect(screen.getByRole('rowheader', { name: 'Random' }).closest('tr')!.textContent).toContain('2.1 %');
    expect(await screen.findByText(/behind plan/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Annual report (CSV)' }).getAttribute('href')).toBe(`/bff/api/testing/reports/annual?year=${year}&format=csv`);
  });

  it('SAFETY_MANAGER sees statistics without coverage or annual report', async () => {
    const api = mockApi({ 'GET /testing/statistics': [] });
    render(<TestingReports {...props(api, 'SAFETY_MANAGER')} />);
    expect(await screen.findByText(/No tests in/)).toBeTruthy();
    expect(screen.queryByRole('link', { name: /Annual report/ })).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Random-testing coverage' })).toBeNull();
    expect(api.find('GET', '/testing/coverage')).toHaveLength(0);
  });
});
