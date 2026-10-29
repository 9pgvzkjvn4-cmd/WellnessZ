// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import type { CaseDetail } from '../../api/types';
import { catalogue, caseDetail, decision, standards } from '../../testing/fixtures';
import { mockApi, sessionFor } from '../../testing/mockApi';
import type { CaseTabProps } from './caseTabProps';
import { CaseView } from './CaseView';
import { FitnessDecisionTab } from './FitnessDecisionTab';
import { SummaryTab } from './RecordTabs';
import { RestrictionsDutiesTab } from './RestrictionsDutiesTab';
import { RtwPlanTab } from './RtwPlanTab';
import { CrewTab, FoodHandlerTab, IhrTab } from './SpecialistTabs';

afterEach(cleanup);

const tabProps = (api: ReturnType<typeof mockApi>, roles: string[], detail: CaseDetail = caseDetail()): CaseTabProps => ({ client: api.client, session: sessionFor(...roles), onSessionExpired: () => {}, caseId: 'c1', detail, refresh: () => {} });
const fitnessBase = {
  'GET /masterdata/fitness-standards': standards,
  'GET /masterdata/restrictions': catalogue,
  'GET /cases/c1/fitness-decisions': [decision({ id: 'fd0', status: 'AWAITING_SIGNOFF', outcome: 'PERMANENTLY_UNFIT', certificateDocumentId: null })],
  'GET /cases/c1/duration-comparison': { icdCode: 'S63.5', guidelineTitle: 'Wrist sprain', expectedDays: 14, actualDays: 30, varianceDays: 16, exceeded: true, source: 'MDGuidelines' },
};

describe('FitnessDecisionTab', () => {
  it('records a decision: limitations need a restriction, values required, duration override reason required', async () => {
    const user = userEvent.setup();
    const api = mockApi({ ...fitnessBase, 'POST /cases/c1/fitness-decisions': decision({ certificateDocumentId: 'doc1' }) });
    render(<FitnessDecisionTab {...tabProps(api, ['OH_CLINICIAN'])} />);
    expect(await screen.findByText(/Guideline exceeded/)).toBeTruthy();
    expect(screen.getByText('+16 days')).toBeTruthy();
    expect(screen.getByText(/1 decision is awaiting sign-off/)).toBeTruthy();
    await user.selectOptions(await screen.findByLabelText(/^Fitness standard/), 'AIRSIDE_DRIVING');
    expect((screen.getByLabelText(/^Valid until/) as HTMLInputElement).value).not.toBe(''); // default from 36-month validity
    await user.selectOptions(screen.getByLabelText(/^Outcome/), 'FIT_WITH_LIMITATIONS');
    await user.click(screen.getByRole('button', { name: 'Record decision' }));
    const summary = screen.getByRole('alert', { name: 'There is a problem' });
    expect(within(summary).getAllByRole('link').map((a) => a.textContent)).toEqual([
      'Fit with limitations needs at least one restriction or limitation code.',
      'Enter the clinical rationale (OH only).',
      'The absence exceeds the duration guideline – give a reason for the override.',
    ]);
    await user.selectOptions(screen.getByLabelText('Add restriction'), 'LIFT_MAX_KG');
    await user.click(screen.getByRole('button', { name: 'Add' }));
    await user.click(screen.getByRole('button', { name: 'Record decision' }));
    expect(screen.getByRole('alert', { name: 'There is a problem' }).textContent).toMatch(/Enter a value for “No lifting above … kg”/);
    await user.type(screen.getByLabelText(/Value \(kg\) for No lifting above/), '10');
    await user.type(screen.getByLabelText(/Clinical rationale/), 'Wrist sprain healing');
    await user.type(screen.getByLabelText(/Duration override reason/), 'Delayed physiotherapy');
    await user.click(screen.getByRole('button', { name: 'Record decision' }));
    await waitFor(() => expect(api.find('POST', '/cases/c1/fitness-decisions')).toHaveLength(1));
    expect(api.find('POST', '/cases/c1/fitness-decisions')[0]?.body).toMatchObject({
      standardCode: 'AIRSIDE_DRIVING', outcome: 'FIT_WITH_LIMITATIONS', limitationCodes: [], restrictions: [{ code: 'LIFT_MAX_KG', value: 10 }],
      rationale: 'Wrist sprain healing', durationOverrideReason: 'Delayed physiotherapy', generateCertificate: true,
    });
    expect(await screen.findByText(/draft fitness certificate \(no diagnosis\) was generated/)).toBeTruthy();
  });

  it('a nurse cannot decide a licensed (AME) standard; the AME can and sees limitation codes', async () => {
    const user = userEvent.setup();
    const api = mockApi(fitnessBase);
    const { unmount } = render(<FitnessDecisionTab {...tabProps(api, ['OH_CLINICIAN'])} />);
    await user.selectOptions(await screen.findByLabelText(/^Fitness standard/), 'CAA_CLASS1');
    expect(screen.getByText(/only an AME or the CMO can record the decision/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Record decision' }) as HTMLButtonElement).disabled).toBe(true);
    unmount();
    render(<FitnessDecisionTab {...tabProps(api, ['AME'])} />);
    await user.selectOptions(await screen.findByLabelText(/^Fitness standard/), 'CAA_CLASS1');
    expect(screen.getByRole('checkbox', { name: 'VDL' })).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Record decision' }) as HTMLButtonElement).disabled).toBe(false);
    expect(screen.getByRole('link', { name: 'Open standard' }).getAttribute('href')).toBe('https://example.invalid/partmed');
  });

  it('PERMANENTLY_UNFIT sign-off is CMO only; the CMO approves from the history', async () => {
    const user = userEvent.setup();
    const api = mockApi({ ...fitnessBase, 'POST /fitness-decisions/fd0/signoff': decision() });
    const { unmount } = render(<FitnessDecisionTab {...tabProps(api, ['AME'])} />);
    await screen.findByRole('table', { name: 'Decision history' });
    expect(screen.queryByRole('button', { name: 'Sign off' })).toBeNull();
    unmount();
    render(<FitnessDecisionTab {...tabProps(api, ['CMO'])} />);
    await user.click(await screen.findByRole('button', { name: 'Sign off' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^Note/), 'Agreed');
    await user.click(within(dialog).getByRole('button', { name: 'Approve decision' }));
    await waitFor(() => expect(api.find('POST', '/fitness-decisions/fd0/signoff')[0]?.body).toEqual({ approve: true, note: 'Agreed' }));
  });

  it('keeps the increment-1 quick status form', async () => {
    const api = mockApi(fitnessBase);
    render(<FitnessDecisionTab {...tabProps(api, ['OH_CLINICIAN'])} />);
    expect(await screen.findByText(/Quick status update without a standard/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Record fitness' })).toBeTruthy();
  });
});

describe('RestrictionsDutiesTab', () => {
  it('matches job demands from the latest decision and proposes a modified duty', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /masterdata/restrictions': catalogue,
      'GET /cases/c1/fitness-decisions': [decision()],
      'GET /cases/c1/duty-proposals': [{ id: 'dp0', caseId: 'c1', jobCode: 'CHECKIN', jobTitle: 'Check-in agent', duties: 'Desk', startDate: '2026-10-01', status: 'DECLINED', decidedBy: 'mgr1', decidedAt: '2026-10-02T10:00:00Z', reasonCode: 'NO_SUITABLE_WORK' }],
      'POST /fitness/match': [
        { jobCode: 'RAMP_COORD', title: 'Ramp coordinator', unit: 'GND', compatible: true, conflicts: [], score: 92, sameDepartment: true },
        { jobCode: 'BAG_HANDLER', title: 'Baggage handler', unit: 'GND', compatible: false, conflicts: [{ dimension: 'LIFT_KG', demand: 23, restriction: 10 }], score: 20 },
      ],
      'POST /cases/c1/duty-proposals': { id: 'dp1' },
    });
    render(<RestrictionsDutiesTab {...tabProps(api, ['OH_CLINICIAN'])} />);
    expect(await screen.findByText(/No suitable work/)).toBeTruthy();
    expect(await screen.findByText('No lifting above 10 kg')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Match job demands' }));
    await waitFor(() => expect(api.find('POST', '/fitness/match')[0]?.body).toEqual({ employeeId: 'E1004', restrictions: [{ code: 'LIFT_MAX_KG', value: 10 }], unit: 'GND' }));
    expect(await screen.findByText('Lift kg: demand 23 vs restriction 10')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Propose Ramp coordinator as modified duty' }));
    await user.click(screen.getByRole('button', { name: 'Send proposal to manager' }));
    expect(screen.getByRole('alert', { name: 'There is a problem' }).textContent).toMatch(/Describe the modified duties/);
    await user.type(screen.getByLabelText(/^Duties/), 'Coordinate stand allocation from the office');
    await user.type(screen.getByLabelText(/Hours per day/), '6');
    await user.click(screen.getByRole('button', { name: 'Send proposal to manager' }));
    await waitFor(() => expect(api.find('POST', '/cases/c1/duty-proposals')).toHaveLength(1));
    expect(api.find('POST', '/cases/c1/duty-proposals')[0]?.body).toMatchObject({ jobCode: 'RAMP_COORD', duties: 'Coordinate stand allocation from the office', hoursPerDay: 6 });
  });
});

describe('RtwPlanTab', () => {
  const plan = {
    id: 'rp1', caseId: 'c1', status: 'DRAFT', startDate: '2026-11-02', goal: 'Full duties by week 4',
    phases: [{ fromWeek: 1, toWeek: 2, hoursPerDay: 4, duties: 'Office tasks', restrictions: [{ code: 'NO_NIGHT', label: 'No night shifts' }] }],
    reviews: [{ id: 'rv1', dueDate: '2026-11-09' }], employeeAck: null, managerAck: null,
  };

  it('builds a phased plan with validation and saves it as a draft', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /masterdata/restrictions': catalogue, 'GET /cases/c1/rtw-plans': [], 'GET /cases/c1/job-offers': [], 'POST /cases/c1/rtw-plans': plan });
    render(<RtwPlanTab {...tabProps(api, ['OH_CLINICIAN'])} />);
    await user.click(await screen.findByRole('button', { name: 'New RTW plan' }));
    await user.click(screen.getByRole('button', { name: 'Save draft plan' }));
    const summary = screen.getByRole('alert', { name: 'There is a problem' });
    expect(within(summary).getAllByRole('link').map((a) => a.textContent)).toEqual(['Enter the plan goal (functional wording).', 'Phase 1: describe the duties.', 'Add at least one review date.']);
    await user.type(screen.getByLabelText(/^Goal/), 'Full duties by week 4');
    await user.type(screen.getByLabelText(/^Duties/), 'Office tasks');
    await user.click(screen.getByRole('button', { name: 'Add phase' }));
    const phase2 = screen.getByRole('group', { name: 'Phase 2' });
    expect((within(phase2).getByLabelText(/^From week/) as HTMLInputElement).value).toBe('3');
    await user.clear(within(phase2).getByLabelText(/^From week/));
    await user.type(within(phase2).getByLabelText(/^From week/), '2');
    await user.type(within(phase2).getByLabelText(/^Duties/), 'Half shifts');
    await user.clear(screen.getByLabelText(/^Start date/));
    await user.type(screen.getByLabelText(/^Start date/), '2026-11-02');
    await user.type(screen.getByLabelText('Review 1'), '2026-11-09');
    await user.click(screen.getByRole('button', { name: 'Save draft plan' }));
    expect(screen.getByRole('alert', { name: 'There is a problem' }).textContent).toMatch(/Phase 2 overlaps the previous phase/);
    await user.clear(within(phase2).getByLabelText(/^From week/));
    await user.type(within(phase2).getByLabelText(/^From week/), '3');
    await user.clear(within(phase2).getByLabelText(/^To week/));
    await user.type(within(phase2).getByLabelText(/^To week/), '4');
    await user.click(screen.getByRole('button', { name: 'Save draft plan' }));
    await waitFor(() => expect(api.find('POST', '/cases/c1/rtw-plans')).toHaveLength(1));
    expect(api.find('POST', '/cases/c1/rtw-plans')[0]?.body).toEqual({
      startDate: '2026-11-02', goal: 'Full duties by week 4', reviewDates: ['2026-11-09'],
      phases: [{ fromWeek: 1, toWeek: 2, hoursPerDay: 4, duties: 'Office tasks', restrictions: [] }, { fromWeek: 3, toWeek: 4, hoursPerDay: 4, duties: 'Half shifts', restrictions: [] }],
    });
  });

  it('activates a draft plan and completes a review of an active plan; shows acknowledgements and job offers', async () => {
    const user = userEvent.setup();
    const active = { ...plan, id: 'rp2', status: 'ACTIVE', documentId: 'doc7', employeeAck: { at: '2026-11-03T08:00:00Z', agree: true }, reviews: [{ id: 'rv2', dueDate: '2026-01-01' }] };
    const api = mockApi({
      'GET /masterdata/restrictions': catalogue,
      'GET /cases/c1/rtw-plans': [plan, active],
      'GET /cases/c1/job-offers': [{ id: 'jo1', caseId: 'c1', jobTitle: 'Ramp coordinator', status: 'ISSUED', issuedAt: '2026-10-29T09:00:00Z' }],
      'POST /rtw-plans/rp1/activate': { ...plan, status: 'ACTIVE' },
      'POST /rtw-plans/rp2/reviews/rv2/complete': active,
    });
    render(<RtwPlanTab {...tabProps(api, ['OH_CLINICIAN'])} />);
    await user.click(await screen.findByRole('button', { name: 'Activate plan' }));
    await waitFor(() => expect(api.find('POST', '/rtw-plans/rp1/activate')).toHaveLength(1));
    expect(screen.getByText(/Employee: agreed/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Printable plan (PDF)' }).getAttribute('href')).toBe('/bff/api/documents/doc7/content');
    expect(screen.getByText('Overdue')).toBeTruthy();
    expect(screen.getByText('Ramp coordinator')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Complete review' }));
    await user.selectOptions(screen.getByLabelText('Outcome'), 'ADJUST');
    await user.type(screen.getByLabelText('Note (functional)'), 'Extend phase 1');
    await user.click(screen.getByRole('button', { name: 'Complete review' }));
    await waitFor(() => expect(api.find('POST', '/rtw-plans/rp2/reviews/rv2/complete')[0]?.body).toEqual({ outcome: 'ADJUST', note: 'Extend phase 1' }));
  });
});

describe('FoodHandlerTab', () => {
  it('excludes with the checklist, requests return and clears (symptom-free 48 h required)', async () => {
    const user = userEvent.setup();
    let status = 'FIT_TO_HANDLE_FOOD';
    let exclusions = 0;
    const history = [{ id: 'ep1', status: 'FIT_TO_HANDLE_FOOD', reasonCategory: 'SKIN_INFECTION', excludedFrom: '2026-01-10', clearedAt: '2026-01-20T10:00:00Z' }];
    const api = mockApi({
      'GET /cases/c1/food-handler': () => [...history, ...(exclusions > 0 ? [{ id: 'ep2', status, reasonCategory: 'GI_ILLNESS', excludedFrom: '2026-10-29' }] : [])],
      'POST /cases/c1/food-handler/exclude': () => ((status = 'EXCLUDED'), (exclusions = 1), { status }),
      'POST /cases/c1/food-handler/return-request': () => ((status = 'RETURN_PENDING_CLEARANCE'), { status }),
      'POST /cases/c1/food-handler/clear': () => ((status = 'FIT_TO_HANDLE_FOOD'), { status, documentId: 'd1' }),
    });
    render(<FoodHandlerTab {...tabProps(api, ['OH_CLINICIAN'], caseDetail({ safetyCategory: 'FOOD_HANDLER' }))} />);
    expect(await screen.findByText('Fit to handle food')).toBeTruthy();
    expect(screen.queryByRole('table', { name: 'Episodes on this case' })).toBeNull();
    await user.click(screen.getByRole('checkbox', { name: /Diarrhoea or vomiting/ }));
    await user.click(screen.getByRole('button', { name: 'Exclude from food handling' }));
    expect(screen.getByRole('alert', { name: 'There is a problem' }).textContent).toMatch(/exclusion reason/);
    await user.selectOptions(screen.getByLabelText(/Reason category/), 'GI_ILLNESS');
    await user.click(screen.getByRole('button', { name: 'Exclude from food handling' }));
    await waitFor(() => expect(api.find('POST', '/cases/c1/food-handler/exclude')).toHaveLength(1));
    expect(api.find('POST', '/cases/c1/food-handler/exclude')[0]?.body).toMatchObject({ reasonCategory: 'GI_ILLNESS', checklistAnswers: { diarrhoeaOrVomiting48h: true, feverOrJaundice: false } });
    expect((await screen.findAllByText('Excluded')).length).toBe(2); // current status + episodes table
    expect(within(screen.getByRole('table', { name: 'Episodes on this case' })).getByText('Skin infection')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: /Request return/ }));
    expect((await screen.findAllByText('Return pending clearance')).length).toBeGreaterThan(0);
    await user.type(screen.getByLabelText(/^Note/), 'Clear');
    await user.click(screen.getByRole('button', { name: 'Clear to handle food' }));
    expect(screen.getByRole('alert', { name: 'There is a problem' }).textContent).toMatch(/symptom-free for 48 hours/);
    await user.click(screen.getByRole('checkbox', { name: /Symptom-free/ }));
    await user.click(screen.getByRole('button', { name: 'Clear to handle food' }));
    await waitFor(() => expect(api.find('POST', '/cases/c1/food-handler/clear')[0]?.body).toEqual({ criteria: { symptomFree48h: true }, note: 'Clear' }));
    expect(await screen.findByText(/food-handler clearance document was generated/)).toBeTruthy();
  });

  it('case managers can exclude but not clear', async () => {
    const api = mockApi({ 'GET /cases/c1/food-handler': [] });
    render(<FoodHandlerTab {...tabProps(api, ['CASE_MANAGER'])} />);
    expect(await screen.findByText(/Clearance is given by an OH nurse or physician/)).toBeTruthy();
  });
});

describe('CrewTab', () => {
  it('AME records a crew assessment with EASA limitation codes and gets the crew medical report', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /crew/limitation-codes': [{ code: 'OML', label: 'Valid only as or with qualified co-pilot', appliesTo: ['CAA_CLASS1'], operational: true }, { code: 'CCL', label: 'Correction by contact lenses', appliesTo: ['CAA_CLASS1', 'CABIN_CREW'], operational: false }],
      'GET /cases/c1/fitness-decisions': [decision({ standardCode: 'CAA_CLASS1', standardTitle: 'Class 1' })],
      'POST /cases/c1/crew-assessment': decision({ standardCode: 'CAA_CLASS1', certificateDocumentId: 'rep1' }),
    });
    render(<CrewTab {...tabProps(api, ['AME'], caseDetail({ safetyCategory: 'FLIGHT_CREW' }))} />);
    await user.click(await screen.findByRole('checkbox', { name: /OML/ }));
    expect(screen.getByText('Operational')).toBeTruthy();
    await user.selectOptions(screen.getByLabelText(/^Outcome/), 'FIT_WITH_LIMITATIONS');
    await user.type(screen.getByLabelText(/^Rationale/), 'Monocular');
    await user.click(screen.getByRole('button', { name: 'Record crew assessment' }));
    await waitFor(() => expect(api.find('POST', '/cases/c1/crew-assessment')).toHaveLength(1));
    expect(api.find('POST', '/cases/c1/crew-assessment')[0]?.body).toMatchObject({ standardCode: 'CAA_CLASS1', outcome: 'FIT_WITH_LIMITATIONS', limitationCodes: ['OML'], rationale: 'Monocular' });
    expect((await screen.findByRole('link', { name: /crew medical report/ })).getAttribute('href')).toBe('/bff/api/documents/rep1/content');
    // cabin crew standard shows only its codes
    await user.selectOptions(screen.getByLabelText(/^Standard/), 'CABIN_CREW');
    expect(screen.queryByRole('checkbox', { name: /OML/ })).toBeNull();
    expect(screen.getByRole('checkbox', { name: /CCL/ })).toBeTruthy();
  });

  it('is read-only for an OH physician', async () => {
    const api = mockApi({ 'GET /crew/limitation-codes': [], 'GET /cases/c1/fitness-decisions': [] });
    render(<CrewTab {...tabProps(api, ['OH_PHYSICIAN'])} />);
    expect(await screen.findByText(/recorded by an AME or the CMO/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Record crew assessment' }) as HTMLButtonElement).closest('fieldset')?.disabled).toBe(true);
  });
});

describe('IhrTab', () => {
  it('physician assesses; CMO decides; appeal; a different CMO decides the appeal', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /cases/c1/ihr-assessments': [
        { id: 'i1', caseId: 'c1', type: 'ILL_HEALTH_RETIREMENT', status: 'AWAITING_CMO', findings: 'Severe OA', recommendation: 'Retire', policyReference: 'POL-7', assessedBy: 'physician1' },
        { id: 'i2', caseId: 'c1', type: 'REDEPLOYMENT', status: 'DECIDED', decisionText: 'Redeploy to office', appealDeadline: '2026-11-26', assessedBy: 'physician1' },
        { id: 'i3', caseId: 'c1', type: 'REDEPLOYMENT', status: 'APPEALED', appealGrounds: 'New evidence', assessedBy: 'tester' },
      ],
      'POST /cases/c1/ihr-assessments': { id: 'i4' },
      'POST /ihr-assessments/i1/cmo-decision': { id: 'i1' },
      'POST /ihr-assessments/i2/appeal': { id: 'i2' },
    });
    render(<IhrTab {...tabProps(api, ['CMO'])} />);
    await user.type(await screen.findByLabelText(/Decision text \(functional – shared/), 'Retirement on ill-health grounds approved');
    await user.click(screen.getByRole('button', { name: 'Record CMO decision' }));
    await waitFor(() => expect(api.find('POST', '/ihr-assessments/i1/cmo-decision')[0]?.body).toEqual({ approve: true, decisionText: 'Retirement on ill-health grounds approved', note: '' }));
    await user.type(screen.getByLabelText('Grounds'), 'Employee disputes');
    await user.click(screen.getByRole('button', { name: 'Lodge appeal' }));
    await waitFor(() => expect(api.find('POST', '/ihr-assessments/i2/appeal')[0]?.body).toEqual({ grounds: 'Employee disputes' }));
    // i3 was assessed by the current user → must be decided by another CMO
    expect(screen.getByText(/must be decided by a CMO other than the assessing physician/)).toBeTruthy();
    // CMO is a physician → can create
    await user.type(screen.getByLabelText(/^Findings/), 'f');
    await user.type(screen.getByLabelText(/^Recommendation/), 'r');
    await user.type(screen.getByLabelText(/^Policy reference/), 'p');
    await user.click(screen.getByRole('button', { name: 'Send to CMO' }));
    await waitFor(() => expect(api.find('POST', '/cases/c1/ihr-assessments')[0]?.body).toEqual({ type: 'REDEPLOYMENT', findings: 'f', recommendation: 'r', policyReference: 'p' }));
  });

  it('nurses see the list but cannot create assessments or decide', async () => {
    const api = mockApi({ 'GET /cases/c1/ihr-assessments': [{ id: 'i1', caseId: 'c1', type: 'REDEPLOYMENT', status: 'AWAITING_CMO' }] });
    render(<IhrTab {...tabProps(api, ['OH_CLINICIAN'])} />);
    expect(await screen.findByText('Awaiting cmo')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Send to CMO' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Record CMO decision' })).toBeNull();
  });
});

describe('SummaryTab and CaseView', () => {
  it('renders the rule-based handover summary', async () => {
    const api = mockApi({
      'GET /cases/c1/summary': {
        keyDates: { injuryDate: '2026-10-20', firstContact: '2026-10-21' }, diagnoses: [{ system: 'ICD_10', code: 'S63.5', display: 'Sprain of wrist' }],
        fitness: { status: 'FIT_WITH_RESTRICTIONS', validUntil: '2026-11-30' }, restrictions: [{ label: 'No lifting above 10 kg', validUntil: '2026-11-30' }],
        openTasks: [{ title: 'Call back' }], lastEncounter: { id: 'enc1', type: 'INJURY', title: 'Injury consultation', signedAt: '2026-10-21T10:00:00Z' },
      },
    });
    render(<SummaryTab {...tabProps(api, ['CASE_MANAGER'])} />);
    expect(await screen.findByText('Injury date')).toBeTruthy();
    expect(screen.getByText(/Sprain of wrist/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Injury consultation' }).getAttribute('href')).toBe('#/encounters/enc1');
    expect(screen.getByText(/no AI involved/)).toBeTruthy();
  });

  it('case view links to the OH chart and shows the increment-2 tabs (crew/food-handler only when relevant)', async () => {
    const api = mockApi({ 'GET /cases/c1': caseDetail(), 'GET /cases/c1/header': { caseRef: 'GND-2026-000001' }, 'GET /cases/c1/summary': {} });
    const { unmount } = render(<CaseView client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={() => {}} caseId="c1" tab="summary" />);
    expect((await screen.findByRole('link', { name: 'Open OH chart' })).getAttribute('href')).toBe('#/employees/E1004/chart');
    const labels = within(screen.getByRole('navigation', { name: 'Case sections' })).getAllByRole('link').map((a) => a.textContent);
    expect(labels).toEqual(['Overview', 'Triage', 'Recordability', 'Checklists', 'Assignment', 'Escalation', 'Complexity', 'Duplicates', 'Tasks', 'Timeline', 'Encounters', 'Fitness', 'Restrictions & duties', 'RTW plan', 'IHR', 'Treatment', 'Referrals', 'Costs', 'Outcomes', 'Claim', 'Documents', 'Summary', 'Status']);
    unmount();
    const crewApi = mockApi({ 'GET /cases/c1': caseDetail({ safetyCategory: 'CABIN_CREW' }), 'GET /cases/c1/header': {}, 'GET /crew/limitation-codes': [], 'GET /cases/c1/fitness-decisions': [] });
    render(<CaseView client={crewApi.client} session={sessionFor('AME')} onSessionExpired={() => {}} caseId="c1" tab="crew" />);
    expect(await screen.findByRole('heading', { name: 'Crew aeromedical assessment' })).toBeTruthy();
    expect(within(screen.getByRole('navigation', { name: 'Case sections' })).queryByRole('link', { name: 'Food handler' })).toBeNull();
  });
});
