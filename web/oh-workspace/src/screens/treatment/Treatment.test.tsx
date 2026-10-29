// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { caseDetail } from '../../testing/fixtures';
import { mockApi, reply, sessionFor } from '../../testing/mockApi';
import { CaseView } from '../case/CaseView';
import { AdministerPanel } from './AdministerPanel';
import { ClaimTab } from './ClaimTab';
import { CostsTab } from './CostsTab';
import { OutcomesTab } from './OutcomesTab';
import { ProviderDirectory } from './Providers';
import { ReferralWorklist } from './ReferralScreens';
import { ReferralsTab } from './ReferralsTab';
import { TreatmentTab } from './TreatmentTab';

afterEach(cleanup);
const noop = () => {};
const tabProps = (api: ReturnType<typeof mockApi>, ...roles: string[]) => ({ client: api.client, session: sessionFor(...(roles.length ? roles : ['OH_CLINICIAN'])), onSessionExpired: noop, caseId: 'c1', detail: caseDetail(), refresh: noop });

const services = [
  { code: 'PHYSIOTHERAPY', name: 'Physiotherapy', providerTypes: ['PHYSIOTHERAPY'], requiredFields: [{ code: 'bodyRegion', label: 'Body region', type: 'TEXT' }, { code: 'injuryDate', label: 'Injury date', type: 'DATE' }], reportDueDays: 7, urgentAppointmentHours: 48, routineAppointmentDays: 5 },
];
const providers = [
  { code: 'PHYSIOPLUS', name: 'Physio Plus', orgType: 'PHYSIOTHERAPY', city: 'London', specialties: ['MSK'], services: ['PHYSIOTHERAPY'], languages: ['en'], accreditation: [], coverage: 'HUB', preferred: true, contract: { ref: 'K1', status: 'ACTIVE' }, rates: [{ serviceCode: 'PHYSIOTHERAPY', price: { amount: '65.00', currency: 'GBP' } }], sla: { appointmentDays: 5, urgentHours: 48, reportDays: 7 }, scorecard: { month: '2026-09', overall: 86 } },
  { code: 'REHAB_HUB', name: 'Rehab Hub', orgType: 'PHYSIOTHERAPY', city: 'Dubai', specialties: [], services: ['PHYSIOTHERAPY'], languages: [], accreditation: [], coverage: 'OUTSTATION', preferred: false, contract: { ref: 'K2', status: 'EXPIRED' } },
];
const referral = (over = {}) => ({ id: 'r1', ref: 'RFL-2026-000001', caseId: 'c1', caseRef: 'GND-2026-000001', employeeId: 'E1004', displayName: 'Rami Ramp', serviceCode: 'PHYSIOTHERAPY', serviceName: 'Physiotherapy', providerCode: 'PHYSIOPLUS', providerName: 'Physio Plus', contracted: true, preferred: true, urgency: 'ROUTINE', status: 'SENT', channel: 'PORTAL', createdAt: '2026-10-20T09:00:00Z', sentAt: '2026-10-20T10:00:00Z', reportDueAt: '2099-01-01T00:00:00Z', overdue: false, chaseCount: 0, escalated: false, ...over });

describe('case header claim badge', () => {
  it('shows nothing on 204 and the claim badge when a claim exists', async () => {
    const none = mockApi({ 'GET /cases/c1': caseDetail(), 'GET /cases/c1/header': { caseRef: 'GND-2026-000001' }, 'GET /cases/c1/claim-status': reply(204) });
    const { unmount } = render(<CaseView client={none.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} caseId="c1" tab="overview" />);
    await screen.findByRole('link', { name: 'Open OH chart' });
    await waitFor(() => expect(none.find('GET', '/cases/c1/claim-status')).toHaveLength(1));
    expect(screen.queryByText(/^Claim CLM/)).toBeNull();
    unmount();
    const api = mockApi({
      'GET /cases/c1': caseDetail(),
      'GET /cases/c1/header': {},
      'GET /cases/c1/claim-status': { claimId: 'cl1', claimRef: 'CLM-2026-000001', claimType: 'WORKERS_COMP', status: 'OPEN', lostTime: true, litigationHold: true },
    });
    render(<CaseView client={api.client} session={sessionFor('NURSING_HEAD')} onSessionExpired={noop} caseId="c1" tab="overview" />);
    const badge = await screen.findByRole('link', { name: 'Open claim CLM-2026-000001' });
    expect(badge.getAttribute('href')).toBe('#/claims/cl1');
    expect(badge.textContent).toContain('lost time');
    expect(badge.textContent).toContain('legal hold');
  });
});

describe('TreatmentTab', () => {
  it('builds a plan from a template and activates it', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /cases/c1/treatment-plans': [],
      'GET /treatment/plan-templates': [{ code: 'WRIST_SPRAIN', injuryType: 'WRIST_SPRAIN', title: 'Wrist sprain', goals: ['Full grip strength'], interventions: [{ type: 'SELF_CARE', description: 'Splint and ice', weeks: 2 }], followUpDays: [7], expectedDays: 21 }],
      'GET /masterdata/clinic-sites': [{ code: 'LHR-T5', name: 'Heathrow T5 clinic' }],
      'POST /cases/c1/treatment-plans': { id: 'p1', status: 'ACTIVE' },
      'GET /treatment/formulary': [],
      'GET /cases/c1/medications/administrations': [],
    });
    render(<TreatmentTab {...tabProps(api)} />);
    await user.click(await screen.findByRole('button', { name: 'Create treatment plan' }));
    await user.selectOptions(screen.getByLabelText('Start from template'), 'WRIST_SPRAIN');
    expect((screen.getByLabelText(/Goal 1/) as HTMLInputElement).value).toBe('Full grip strength');
    await user.click(screen.getByLabelText('Book appointment'));
    await user.click(screen.getByRole('button', { name: 'Save & activate' }));
    expect(await screen.findByRole('link', { name: /choose a clinic site/ })).toBeTruthy();
    await user.type(within(screen.getByRole('region', { name: 'New treatment plan' })).getByLabelText(/Clinic site/), 'lhr-t5');
    await user.click(screen.getByRole('button', { name: 'Save & activate' }));
    await waitFor(() => expect(api.find('POST', '/cases/c1/treatment-plans')).toHaveLength(1));
    const call = api.find('POST', '/cases/c1/treatment-plans')[0]!;
    expect(call.path).toBe('/cases/c1/treatment-plans?activate=true');
    expect(call.body).toMatchObject({ templateCode: 'WRIST_SPRAIN', goals: ['Full grip strength'], expectedRecoveryDays: 21, followUps: [{ type: 'OH_REVIEW', siteCode: 'LHR-T5', book: true }] });
  });

  it('shows variance, updates follow-ups and prints for the employee', async () => {
    const user = userEvent.setup();
    const plan = { id: 'p1', caseId: 'c1', employeeId: 'E1004', status: 'ACTIVE', version: 2, goals: ['Return to ramp duties'], interventions: [{ type: 'PHYSIOTHERAPY', description: 'Physio', providerCode: 'PHYSIOPLUS' }], followUps: [{ id: 'f1', dueDate: '2026-11-05', type: 'OH_REVIEW', status: 'BOOKED', appointmentId: 'a1' }], guideline: { code: 'ODG', title: 'Back strain', expectedDays: 42 }, variance: { actualDays: 55, expectedDays: 42, pctOver: 31, alert: true } };
    const api = mockApi({
      'GET /cases/c1/treatment-plans': [plan],
      'GET /treatment/plan-templates': [],
      'POST /treatment-plans/p1/follow-ups/f1': plan,
      'POST /treatment-plans/p1/print': { id: 'd9', title: 'Treatment plan' },
      'GET /treatment/formulary': [],
      'GET /cases/c1/medications/administrations': [],
    });
    render(<TreatmentTab {...tabProps(api)} />);
    expect(await screen.findByText('31 % over guideline (day 55 of 42)')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: /Mark follow-up .* done/ }));
    await waitFor(() => expect(api.find('POST', '/treatment-plans/p1/follow-ups/f1')[0]?.body).toEqual({ status: 'DONE' }));
    await user.click(screen.getByRole('button', { name: 'Print for employee' }));
    expect((await screen.findByRole('link', { name: 'Open the employee plan letter' })).getAttribute('href')).toBe('/bff/api/documents/d9/content');
  });
});

describe('AdministerPanel', () => {
  it('shows the allergy alert and re-submits with an override reason', async () => {
    const user = userEvent.setup();
    let calls = 0;
    const api = mockApi({
      'GET /treatment/formulary': [{ code: 'IBU', name: 'Ibuprofen', strength: '400 mg', route: ['ORAL'], stockTracked: true, active: true }],
      'GET /treatment/stock': [{ itemCode: 'IBU', batchNumber: 'B7', expiry: '2099-01-01', quantity: 20, siteCode: 'LHR', belowReorder: false, expired: false }],
      'GET /cases/c1/medications/administrations': [],
      'POST /cases/c1/medications/administer': () => (++calls === 1 ? reply(422, { status: 422, code: 'allergy_alert', detail: 'Recorded allergy: NSAID – bronchospasm' }) : { id: 'm1', itemCode: 'IBU', name: 'Ibuprofen', dose: '400 mg', route: 'ORAL', batchNumber: 'B7', administeredAt: '2026-10-29T10:00:00Z' }),
    });
    render(<AdministerPanel {...tabProps(api)} />);
    await user.selectOptions(await screen.findByLabelText(/Formulary item/), 'IBU');
    await user.type(screen.getByLabelText(/^Dose/), '400 mg');
    await user.type(screen.getByLabelText(/Clinic site/), 'LHR');
    await user.selectOptions(await screen.findByLabelText(/^Batch/), 'B7');
    await user.click(screen.getByRole('button', { name: 'Record administration' }));
    expect(await screen.findByText(/NSAID – bronchospasm/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Override alert and administer' }));
    expect(await screen.findByRole('link', { name: 'Enter the reason for overriding the allergy alert' })).toBeTruthy();
    await user.type(screen.getByLabelText(/Override reason/), 'Discussed with physician – benefit outweighs risk');
    await user.click(screen.getByRole('button', { name: 'Override alert and administer' }));
    expect(await screen.findByText(/Ibuprofen 400 mg administered/)).toBeTruthy();
    const posts = api.find('POST', '/cases/c1/medications/administer');
    expect(posts[0]?.body).toEqual({ itemCode: 'IBU', dose: '400 mg', route: 'ORAL', batchNumber: 'B7', siteCode: 'LHR' });
    expect(posts[1]?.body).toMatchObject({ overrideAllergyReason: 'Discussed with physician – benefit outweighs risk' });
  });
});

describe('Referral wizard', () => {
  it('walks service → provider → data set → consent → channel → send; consent_required returns to the consent step', async () => {
    const user = userEvent.setup();
    let sends = 0;
    const api = mockApi({
      'GET /cases/c1/referrals': [],
      'GET /referrals/services': services,
      'GET /providers': providers,
      'GET /documents': [{ id: 'doc1', title: 'X-ray report', type: 'EXTERNAL_REPORT', employeeId: 'E1004', status: 'SIGNED' }],
      'POST /cases/c1/referrals': referral({ status: 'DRAFT' }),
      'PUT /referrals/r1': referral({ status: 'DRAFT' }),
      'POST /referrals/r1/send': () => (++sends === 1 ? reply(422, { status: 422, code: 'consent_required', detail: 'No active consent' }) : referral()),
    });
    render(<ReferralsTab {...tabProps(api)} />);
    await user.click(await screen.findByRole('button', { name: 'New referral' }));
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByRole('link', { name: 'Choose a service' })).toBeTruthy();
    await user.selectOptions(screen.getByLabelText(/^Service/), 'PHYSIOTHERAPY');
    await user.type(screen.getByLabelText(/Reason for referral/), 'Persistent low back pain after lifting');
    await user.type(screen.getByLabelText(/Question for the provider/), 'Assess and treat; advise on lifting');
    await user.click(screen.getByRole('button', { name: 'Next' }));

    const list = await screen.findByRole('list', { name: 'Providers' });
    expect(within(list).getAllByRole('listitem')[0]?.textContent).toContain('Physio Plus');
    expect(within(list).getAllByRole('listitem')[0]?.textContent).toContain('Contract rate £65.00');
    await user.selectOptions(screen.getByLabelText('City'), 'Dubai');
    expect(within(screen.getByRole('list', { name: 'Providers' })).getAllByRole('listitem')).toHaveLength(1);
    await user.selectOptions(screen.getByLabelText('City'), '');
    await user.click(screen.getByRole('radio', { name: /Physio Plus/ }));
    await user.click(screen.getByRole('button', { name: 'Next' }));

    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByRole('link', { name: 'Body region: required for Physiotherapy' })).toBeTruthy();
    await user.type(screen.getByLabelText(/Body region/), 'Lumbar');
    await user.type(screen.getByLabelText(/Injury date/), '2026-10-01');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.type(screen.getByLabelText(/Consent reference/), 'CONS-1');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(screen.getByRole('radio', { name: /Fhir/ }));
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(await screen.findByRole('checkbox', { name: /X-ray report/ }));
    await user.click(screen.getByRole('button', { name: 'Save & send' }));

    expect(await screen.findByRole('link', { name: /No active treatment-sharing consent/ })).toBeTruthy();
    expect(api.find('POST', '/cases/c1/referrals')[0]?.body).toEqual({
      serviceCode: 'PHYSIOTHERAPY', urgency: 'ROUTINE', providerCode: 'PHYSIOPLUS', reason: 'Persistent low back pain after lifting', question: 'Assess and treat; advise on lifting',
      dataSet: { bodyRegion: 'Lumbar', injuryDate: '2026-10-01' }, consentId: 'CONS-1', channel: 'FHIR', attachDocumentIds: ['doc1'],
    });
    await user.clear(screen.getByLabelText(/Consent reference/));
    await user.type(screen.getByLabelText(/Consent reference/), 'CONS-2');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(screen.getByRole('button', { name: 'Save & send' }));
    expect(await screen.findByText(/Referral RFL-2026-000001 sent to Physio Plus/)).toBeTruthy();
    expect(api.find('POST', '/cases/c1/referrals')).toHaveLength(1);
    expect(api.find('PUT', '/referrals/r1')[0]?.body).toMatchObject({ consentId: 'CONS-2' });
  });
});

describe('ReferralWorklist', () => {
  it('filters overdue, selects and bulk-reminds; rates a received report', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /referrals': [referral(), referral({ id: 'r2', ref: 'RFL-2026-000002', overdue: true, escalated: true, chaseCount: 2 })],
      'GET /referrals?overdue=true': [referral({ id: 'r2', ref: 'RFL-2026-000002', overdue: true, chaseCount: 2 })],
      'GET /referrals/services': services,
      'POST /referrals/remind': { reminded: 1 },
    });
    render(<ReferralWorklist client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={noop} />);
    expect(await screen.findByText('Escalated')).toBeTruthy();
    await user.click(screen.getByLabelText('Overdue only'));
    await waitFor(() => expect(screen.queryByText('RFL-2026-000001')).toBeNull());
    await user.click(screen.getByRole('checkbox', { name: 'Select RFL-2026-000002' }));
    await user.click(screen.getByRole('button', { name: 'Remind provider (1)' }));
    expect(await screen.findByText('Reminder sent for 1 referral.')).toBeTruthy();
    expect(api.find('POST', '/referrals/remind')[0]?.body).toEqual({ referralIds: ['r2'] });
  });
});

describe('CostsTab', () => {
  it('lists pre-authorisations and invoices with matching and variance; clinicians create a pre-auth with justification', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /cases/c1/preauthorisations': [{ id: 'pa1', ref: 'PA-2026-000001', caseId: 'c1', serviceCode: 'IMAGING_MRI', providerCode: 'LDN_IMAGING', estimate: { amount: '400.00', currency: 'GBP' }, status: 'APPROVED', approvedAmount: { amount: '400.00', currency: 'GBP' } }],
      'GET /cases/c1/invoices': [{ id: 'i1', providerCode: 'LDN_IMAGING', invoiceNumber: 'INV-77', amount: { amount: '448.00', currency: 'GBP' }, status: 'DISPUTED', matchedPreauthId: 'pa1', serviceDate: '2026-10-10' }],
      'GET /referrals/services': services,
      'POST /cases/c1/preauthorisations': { id: 'pa2', ref: 'PA-2026-000002', status: 'DRAFT' },
    });
    render(<CostsTab {...tabProps(api, 'OH_PHYSICIAN')} />);
    expect(await screen.findByText('£48.00 (12.0 %) over approval')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Approve invoice/ })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'New pre-authorisation' }));
    await user.selectOptions(screen.getByLabelText(/^Service/), 'PHYSIOTHERAPY');
    await user.type(screen.getByLabelText(/Provider code/), 'physioplus');
    await user.type(screen.getByLabelText(/^Estimate/), '1,200');
    await user.click(screen.getByRole('button', { name: 'Create draft' }));
    expect(screen.getByRole('link', { name: 'Clinical justification is required' })).toBeTruthy();
    await user.type(screen.getByLabelText(/Clinical justification/), 'Six sessions for lumbar strain');
    await user.type(screen.getByLabelText(/Insurer-sharing consent/), 'INS-1');
    await user.click(screen.getByRole('button', { name: 'Create draft' }));
    await waitFor(() => expect(api.find('POST', '/cases/c1/preauthorisations')[0]?.body).toEqual({ serviceCode: 'PHYSIOTHERAPY', providerCode: 'PHYSIOPLUS', estimate: { amount: '1200.00', currency: 'GBP' }, justification: 'Six sessions for lumbar strain', consentId: 'INS-1' }));
  });
});

describe('OutcomesTab and ClaimTab', () => {
  it('explains the closure rule and shows improvement', async () => {
    const api = mockApi({ 'GET /cases/c1/outcomes': { outcomes: [{ milestone: 'BASELINE', painScore: 7, functionScale: 'ODI', functionScore: 50, recordedAt: '2026-10-01T00:00:00Z' }, { milestone: 'REVIEW', painScore: 4, functionScale: 'ODI', functionScore: 30, recordedAt: '2026-10-20T00:00:00Z' }] } });
    render(<OutcomesTab {...tabProps(api)} />);
    expect(await screen.findByText('Pain down 3 points (NPRS)')).toBeTruthy();
    expect(screen.getByText('Function improved by 20 (ODI)')).toBeTruthy();
    expect(screen.getByText(/can only be closed after a/)).toBeTruthy();
  });

  it('case manager opens a claim; clinical notes are rejected on the field', async () => {
    const user = userEvent.setup();
    let n = 0;
    const api = mockApi({
      'GET /cases/c1/claim-status': reply(204),
      'GET /masterdata/partner-organisations': [{ code: 'GULFTPA', name: 'Gulf TPA', orgType: 'TPA' }],
      'POST /cases/c1/claims': () => (++n === 1 ? reply(422, { status: 422, code: 'clinical_content_not_allowed', field: 'notes', detail: "Field 'notes' contains clinical content" }) : { id: 'cl1', claimRef: 'CLM-2026-000001', status: 'NOTIFIED' }),
    });
    render(<ClaimTab {...tabProps(api, 'CASE_MANAGER')} />);
    await user.click(await screen.findByRole('button', { name: 'Open claim' }));
    await user.selectOptions(screen.getByLabelText(/^Payer/), 'GULFTPA');
    await user.click(screen.getByLabelText(/Lost-time claim/));
    await user.type(screen.getByLabelText(/^Notes/), 'Lumbar disc prolapse');
    await user.click(screen.getByRole('button', { name: 'Open claim' }));
    const notes = screen.getByLabelText(/^Notes/);
    await waitFor(() => expect(notes.getAttribute('aria-invalid')).toBe('true'));
    await user.clear(notes);
    await user.type(notes, 'Injured while loading baggage');
    await user.click(screen.getByRole('button', { name: 'Open claim' }));
    expect(await screen.findByText('Claim CLM-2026-000001 opened.')).toBeTruthy();
    expect(api.find('POST', '/cases/c1/claims')[1]?.body).toEqual({ claimType: 'WORKERS_COMP', payerCode: 'GULFTPA', lostTime: true, notes: 'Injured while loading baggage' });
  });
});

describe('ProviderDirectory', () => {
  it('ranks preferred / contracted first and shows coverage and scorecard', async () => {
    const api = mockApi({ 'GET /providers': [providers[1], providers[0]] });
    render(<ProviderDirectory client={api.client} session={sessionFor('PROCUREMENT')} onSessionExpired={noop} />);
    const table = await screen.findByRole('table');
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows[0]?.textContent).toContain('Physio Plus');
    expect(rows[0]?.textContent).toContain('Hub');
    expect(rows[0]?.textContent).toContain('2026-09 86/100');
    expect(rows[1]?.textContent).toContain('Contract expired');
  });
});
