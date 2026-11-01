// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { mockApi, reply, sessionFor } from '../../testing/mockApi';
import { ObservationDetail, ObservationsBrowser, UnmatchedQueue } from './Clinical';
import { DeadLetters, EngineHealth } from './Operations';
import { EngineTooling, TerminologyViewer } from './Tooling';

afterEach(cleanup);
const noop = () => {};
const props = (api: ReturnType<typeof mockApi>, ...roles: string[]) => ({ client: api.client, session: sessionFor(...roles), onSessionExpired: noop });
const forbidden = reply(403, { status: 403, title: 'Forbidden', detail: 'Access denied' });

describe('Engine health', () => {
  const routes = {
    'GET /device-engine/ops/connectors': [
      { deviceInstanceId: 'DEV-000003', endpointId: 'EP-LHR-GW-01', siteCode: 'LHR', option: 'P4', state: 'HEALTHY', lastSeenAt: '2026-11-01T08:59:00Z', messages24h: 120, errors24h: 0, p95LatencyMillis: 850, unmatchedOpen: 0, deadLetters: 0 },
      { deviceInstanceId: 'DEV-000001', endpointId: 'EP-LHR-AGT-01', siteCode: 'LHR', option: 'P2', state: 'STALE', lastSeenAt: '2026-11-01T07:00:00Z', messages24h: 4, errors24h: 2, p95LatencyMillis: 6200, unmatchedOpen: 1, deadLetters: 1 },
    ],
    'GET /device-engine/ops/metrics': { window: 'PT1H', messages: 124, perSecondPeak: 3, latencyP50Ms: 400, latencyP95Ms: 1800, parseErrorRate: 0.008, validationBlockRate: 0.02, unmatchedRate: 0.008, unmatchedOpen: 1, slaBreaches: 0, deadLettersOpen: 1 },
    'GET /device-engine/ops/alerts': [{ type: 'STALE', deviceInstanceId: 'DEV-000001', siteCode: 'LHR', since: '2026-11-01T07:05:00Z', level: 'MEDIUM' }],
    'GET /device-engine/unmatched/count': { open: 1, overdue: 0 },
  };
  it('shows throughput, latency against the 5 s target, rates, alerts and connector health (counts only)', async () => {
    const user = userEvent.setup();
    const api = mockApi(routes);
    render(<EngineHealth {...props(api, 'DEVICE_ADMIN')} />);
    expect(await screen.findByText('400 / 1800 ms')).toBeTruthy();
    expect(screen.getByText('0.80 %')).toBeTruthy(); // unmatched > 0.5 % target
    expect(screen.getByText('Stale', { selector: '.badge, .badge *' })).toBeTruthy();
    const table = screen.getByRole('table');
    expect(within(table).getByText('6200 ms').className).toContain('text-amber');
    expect(screen.getByText('1 / 0')).toBeTruthy();
    await user.selectOptions(screen.getByLabelText('Connector state'), 'STALE');
    await waitFor(() => expect(api.find('GET', '/device-engine/ops/connectors?state=STALE')).toHaveLength(1));
    // DEVICE_ADMIN may replay a range
    expect(screen.getByText('Replay a time range from the stored raw payloads')).toBeTruthy();
  });
  it('NURSING_HEAD sees health without range replay; 403 is explained', async () => {
    const api = mockApi({ ...routes, 'GET /device-engine/ops/metrics': forbidden, 'GET /device-engine/ops/connectors': forbidden });
    render(<EngineHealth {...props(api, 'INTEGRATION_TESTER')} />);
    expect(await screen.findByText('Your role does not include the engine operations views.')).toBeTruthy();
    expect(screen.queryByText('Replay a time range from the stored raw payloads')).toBeNull();
  });
});

describe('Dead letters', () => {
  const dl = { id: 'd1', messageId: 'm1000000-0000-4000-8000-000000000001', deviceInstanceId: 'DEV-000003', stage: 'PARSE', code: 'parse_failed', detailMasked: 'PID-5 «masked» unexpected segment', attempts: 1, firstFailedAt: '2026-11-01T08:00:00Z', lastFailedAt: '2026-11-01T08:00:00Z', status: 'OPEN' };
  it('replays with an optional pinned driver version and discards with a reason', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /device-engine/dead-letters': [dl],
      'POST /device-engine/dead-letters/d1/replay': { deadLetterId: 'd1', messageId: dl.messageId, status: 'ROUTED', observationIds: ['o1'] },
      'POST /device-engine/dead-letters/d1/discard': { id: 'd1', status: 'DISCARDED' },
    });
    render(<DeadLetters {...props(api, 'INTEGRATION_TESTER')} />);
    expect(await screen.findByText('PID-5 «masked» unexpected segment')).toBeTruthy();
    expect(api.calls[0]!.path).toBe('/device-engine/dead-letters?status=OPEN');
    await user.type(screen.getByLabelText('Driver version (optional)'), '1.0.1');
    await user.click(screen.getByRole('button', { name: 'Replay' }));
    await waitFor(() => expect(api.find('POST', '/device-engine/dead-letters/d1/replay')[0]?.body).toEqual({ driverVersion: '1.0.1' }));
    expect(await screen.findByText(/Routed – 1 observation/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Discard…' }));
    const discard = screen.getByRole('button', { name: 'Discard' }) as HTMLButtonElement;
    expect(discard.disabled).toBe(true);
    await user.type(screen.getByLabelText(/Reason for discarding/), 'Test traffic from the bench');
    await user.click(discard);
    await waitFor(() => expect(api.find('POST', '/device-engine/dead-letters/d1/discard')[0]?.body).toEqual({ reason: 'Test traffic from the bench' }));
  });
  it('PLATFORM_ADMIN reads but cannot replay', async () => {
    const api = mockApi({ 'GET /device-engine/dead-letters': [dl] });
    render(<DeadLetters {...props(api, 'PLATFORM_ADMIN')} />);
    await screen.findByText('PID-5 «masked» unexpected segment');
    expect(screen.queryByRole('button', { name: 'Replay' })).toBeNull();
  });
});

describe('Unmatched queue', () => {
  const item = { id: 'u1', observationId: 'o9', category: 'VITALS', receivedAt: '2026-11-01T06:00:00Z', ageHours: 3, slaDueAt: '2026-11-01T14:00:00Z', deviceInstanceId: 'DEV-000003', reasonCode: 'below_threshold', restricted: false, suggestions: [{ employeeId: 'E1002', displayName: 'Sam Sample', method: 'VENDOR_ID_MATCH', confidence: 0.85 }] };
  it('resolves to a suggested employee with a reason code, or discards', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /device-engine/unmatched': [item, { ...item, id: 'u2', category: 'BREATH_ALCOHOL', restricted: true, suggestions: [] }], 'POST /device-engine/unmatched/u1/resolve': { id: 'u1', status: 'RESOLVED', observationStatus: 'PENDING_VERIFICATION' } });
    render(<UnmatchedQueue {...props(api, 'OH_CLINICIAN')} />);
    expect(await screen.findByText(/Restricted reading – suggestions are shown to the MRO only/)).toBeTruthy();
    const card = screen.getByText('Sam Sample').closest('li.card') as HTMLElement;
    expect(within(card).getByText(/confidence 0.85/)).toBeTruthy();
    const link = within(card).getByRole('button', { name: 'Choose an employee' }) as HTMLButtonElement;
    expect(link.disabled).toBe(true);
    await user.click(within(card).getByRole('radio'));
    await user.click(within(card).getByRole('button', { name: 'Link to Sam Sample' }));
    await waitFor(() => expect(api.find('POST', '/device-engine/unmatched/u1/resolve')[0]?.body).toEqual({ employeeId: 'E1002', reasonCode: 'CONFIRMED_IDENTITY' }));
    expect(await screen.findByText(/Linked to Sam Sample; reading re-validated \(Pending verification\)/)).toBeTruthy();
  });
});

describe('Observations and provenance', () => {
  it('lists an employee\'s observations; restricted rows carry no values', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /intake/employees/lookup': [{ employeeId: 'E1001', displayName: 'Erin Example' }],
      'GET /device-engine/observations': [
        { observationId: 'o1', category: 'VITALS', status: 'ROUTED', effectiveAt: '2026-11-01T09:02:00Z', deviceInstanceId: 'DEV-000003', model: 'Connex Spot', captureMethod: 'DEVICE', flags: [], restricted: false },
        { observationId: 'o2', category: 'BREATH_ALCOHOL', status: 'ROUTED', effectiveAt: '2026-11-01T09:10:00Z', captureMethod: 'DEVICE', flags: [], restricted: true, restrictedNote: 'restricted reading' },
      ],
    });
    render(<ObservationsBrowser {...props(api, 'OH_CLINICIAN')} />);
    expect(screen.queryByText('Archive integrity check')).toBeNull();
    await user.type(screen.getByLabelText(/Find employee/), 'Erin');
    await user.keyboard('{Enter}');
    await user.click(await screen.findByRole('button', { name: 'Erin Example (E1001)' }));
    expect(await screen.findByText('restricted reading')).toBeTruthy();
    expect(screen.getAllByRole('link')[0]!.getAttribute('href')).toBe('#/devices/observations/o1');
    expect(api.find('GET', '/device-engine/observations')[0]!.path).toBe('/device-engine/observations?employeeId=E1001');
  });
  it('detail shows provenance with the raw-payload hash; a restricted reading shows no values', async () => {
    const api = mockApi({
      'GET /device-engine/observations/o1': { observationId: 'o1', category: 'VITALS', status: 'ROUTED', effectiveAt: '2026-11-01T09:02:00Z', deviceInstanceId: 'DEV-000003', captureMethod: 'DEVICE', components: [{ key: 'systolic', display: 'Systolic blood pressure', value: 128, ucum: 'mm[Hg]', loincCode: '8480-6', mdcCode: 'MDC_PRESS_BLD_NONINV_SYS' }], flags: [{ gate: 'CLOCK_SKEW', outcome: 'WARN', code: 'clock_skew_corrected', message: 'Device clock corrected by 3 minutes.' }], provenance: { model: 'Connex Spot', serial: 'SN-0003', driverPackageId: 'vitals.synthetic.pcd01', driverVersion: '1.0.0', associationMethod: 'SESSION', associationConfidence: 1, clockSkewMillis: 180000, rawPayloadSha256: 'e'.repeat(64) }, attachments: [], routedRecordType: 'RESULT', routedRecordId: 'R-77', restricted: false },
      'GET /device-engine/observations/o2': { observationId: 'o2', category: 'BREATH_ALCOHOL', status: 'ROUTED', captureMethod: 'DEVICE', components: null, flags: [], attachments: [], restricted: true, restrictedNote: 'Restricted reading – MRO only' },
    });
    const { unmount } = render(<ObservationDetail {...props(api, 'OH_CLINICIAN')} observationId="o1" />);
    expect(await screen.findByText('128 mmHg')).toBeTruthy();
    expect(screen.getByText('MDC_PRESS_BLD_NONINV_SYS')).toBeTruthy();
    expect(screen.getByText('e'.repeat(64))).toBeTruthy();
    expect(screen.getByText('180 s (corrected)')).toBeTruthy();
    expect(screen.getByText('vitals.synthetic.pcd01@1.0.0')).toBeTruthy();
    unmount();
    render(<ObservationDetail {...props(api, 'OH_CLINICIAN')} observationId="o2" />);
    expect(await screen.findByText('Restricted reading – MRO only')).toBeTruthy();
    expect(screen.queryByText(/FHIR resources/)).toBeNull();
  });
  it('DEVICE_ADMIN gets the archive integrity check only', async () => {
    const user = userEvent.setup();
    const id = '33333333-3333-4333-8333-333333333333';
    const api = mockApi({ [`GET /device-engine/archive/${id}/verify`]: { sha256Stored: 'f'.repeat(64), sha256Computed: 'f'.repeat(64), intact: true } });
    render(<ObservationsBrowser {...props(api, 'DEVICE_ADMIN')} />);
    expect(screen.queryByLabelText(/Find employee/)).toBeNull();
    await user.type(screen.getByLabelText(/Archive id/), id);
    await user.click(screen.getByRole('button', { name: 'Verify hash' }));
    expect(await screen.findByText(/Intact – the stored and recomputed SHA-256 match/)).toBeTruthy();
  });
});

describe('Simulators, golden datasets and driver checks', () => {
  it('runs a golden-dataset sample through the simulator', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /device-engine/golden-datasets': [{ datasetId: 'vitals/vitals.synthetic.pcd01/normal', family: 'VITALS', adapter: 'HL7V2', packageId: 'vitals.synthetic.pcd01', kind: 'NORMAL', description: 'Synthetic PCD-01 vitals' }],
      'POST /device-engine/simulators/HL7V2/run': { runId: 'r1', adapter: 'HL7V2', datasetId: 'vitals/vitals.synthetic.pcd01/normal', messages: 3, accepted: 3, duplicates: 0, failed: 0, messageIds: [], observationIds: [], statuses: ['UNMATCHED', 'UNMATCHED', 'UNMATCHED'], evidenceSha256: '9'.repeat(64) },
    });
    render(<EngineTooling {...props(api, 'INTEGRATION_TESTER')} />);
    await user.clear(await screen.findByLabelText('Messages for vitals/vitals.synthetic.pcd01/normal'));
    await user.type(screen.getByLabelText('Messages for vitals/vitals.synthetic.pcd01/normal'), '3');
    await user.click(screen.getByRole('button', { name: 'Run sample' }));
    await waitFor(() => expect(api.find('POST', '/device-engine/simulators/HL7V2/run')[0]?.body).toEqual({ datasetId: 'vitals/vitals.synthetic.pcd01/normal', count: 3 }));
    expect(await screen.findByText(/3 message\(s\), 3 accepted, 0 duplicate\(s\), 0 refused/)).toBeTruthy();
    expect(screen.getByText(/outcomes Unmatched/)).toBeTruthy();
  });
  it('validates a driver package and runs conformance (integration tester only)', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /device-engine/drivers': [{ packageId: 'vitals.synthetic.pcd01', version: '1.0.0', family: 'VITALS', models: ['MD-027'], adapter: 'HL7V2', options: ['P4'], sha256: '1'.repeat(64), status: 'SAMPLE' }],
      'GET /device-engine/drivers/vitals.synthetic.pcd01/1.0.0': { schema: 'wellnessz.device-driver/1', packageId: 'vitals.synthetic.pcd01', version: '1.0.0' },
      'POST /device-engine/drivers/validate': { valid: false, packageId: 'vitals.synthetic.pcd01', version: '1.0.0', sha256: '2'.repeat(64), errors: ['mappings[3].loinc not in release'], warnings: [], coverage: { fields: 6, mapped: 5, coded: 5, quantities: 5, quantitiesWithUcum: 5 }, secretFindings: [] },
      'POST /device-engine/drivers/conformance': { passed: true, packageId: 'vitals.synthetic.pcd01', version: '1.0.0', results: [{ name: 'normal', outcome: 'PASS', diff: [] }], evidenceSha256: '3'.repeat(64) },
    });
    render(<EngineTooling {...props(api, 'INTEGRATION_TESTER')} tab="drivers" />);
    await user.click(await screen.findByRole('button', { name: 'Load into the editor' }));
    await waitFor(() => expect((screen.getByLabelText(/Driver package/) as HTMLTextAreaElement).value).toContain('wellnessz.device-driver/1'));
    await user.click(screen.getByRole('button', { name: 'Validate' }));
    expect(await screen.findByText('mappings[3].loinc not in release')).toBeTruthy();
    expect(screen.getByText(/5\/6 fields mapped/)).toBeTruthy();
    expect(api.find('POST', '/device-engine/drivers/validate')[0]!.body).toEqual({ schema: 'wellnessz.device-driver/1', packageId: 'vitals.synthetic.pcd01', version: '1.0.0' });
    await user.click(screen.getByRole('button', { name: 'Run conformance' }));
    await waitFor(() => expect(api.find('POST', '/device-engine/drivers/conformance')).toHaveLength(1));
    expect(api.find('POST', '/device-engine/drivers/conformance')[0]!.body).toMatchObject({ packageJson: { packageId: 'vitals.synthetic.pcd01' } });
    expect(await screen.findByText('Passed', { selector: '.badge, .badge *' })).toBeTruthy();
  });
  it('rejects invalid JSON and hides the run buttons for DEVICE_ADMIN', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /device-engine/drivers': [] });
    render(<EngineTooling {...props(api, 'DEVICE_ADMIN')} tab="drivers" />);
    expect(await screen.findByText('Validation and conformance runs are performed by integration testers.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Validate' })).toBeNull();
    await user.type(screen.getByLabelText(/Driver package/), 'not json');
    expect(api.find('POST', '/device-engine/drivers/validate')).toHaveLength(0);
  });
});

describe('Terminology viewer', () => {
  it('searches the maps, shows releases, GLI-2012 predicted values and the gate catalogue', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /device-engine/terminology/maps': [{ system: 'MDC', code: 'MDC_PRESS_BLD_NONINV_SYS', mdc: 'MDC_PRESS_BLD_NONINV_SYS', loinc: '8480-6', display: 'Systolic blood pressure', ucum: 'mm[Hg]' }],
      'GET /device-engine/terminology/versions': [{ version: 'WZ-TERM-2026.10', active: true, loadedAt: '2026-10-20T00:00:00Z' }],
      'GET /device-engine/gates': [{ code: 'CALIBRATION', description: 'Device calibration in date', families: ['ALL'], signOff: { status: 'PENDING' } }],
      'GET /device-engine/terminology/predicted': { equation: 'GLI_2012', FEV1: { predicted: 3.12, lln: 2.45, l: 1, s: 0.1 }, FVC: { predicted: 3.8, lln: 3.0, l: 1, s: 0.1 }, FEV1FVC: { predicted: 0.82, lln: 0.7, l: 1, s: 0.1 }, release: 'WZ-TERM-2026.10' },
    });
    render(<TerminologyViewer {...props(api, 'OH_CLINICIAN')} />);
    expect(await screen.findByText('8480-6')).toBeTruthy();
    expect(screen.getByText(/WZ-TERM-2026.10 \(active\)/)).toBeTruthy();
    expect(screen.getByText('Clinical sign-off pending')).toBeTruthy();
    await user.selectOptions(screen.getByLabelText('System'), 'MDC');
    await user.type(screen.getByLabelText('Code'), 'MDC_PRESS');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(api.find('GET', '/device-engine/terminology/maps?system=MDC&code=MDC_PRESS')).toHaveLength(1));
    await user.click(screen.getByRole('button', { name: 'Calculate' }));
    expect(await screen.findByText('3.12 L')).toBeTruthy();
    expect(api.find('GET', '/device-engine/terminology/predicted')[0]!.path).toBe('/device-engine/terminology/predicted?equation=GLI_2012&age=40&sex=F&heightCm=168');
  });
});
