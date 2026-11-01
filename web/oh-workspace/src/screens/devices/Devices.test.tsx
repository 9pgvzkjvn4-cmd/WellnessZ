// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it } from 'vitest';
import type { Configuration, WizardStep } from '../../api/devices';
import type { AuthClient, Session } from '../../auth/AuthClient';
import { mockApi, reply, sessionFor } from '../../testing/mockApi';
import { DeviceApprovals, DeviceTestReview } from './Approvals';
import { DeviceCatalogue, DeviceModelDetail } from './Catalogue';
import { DeviceCalibration, DeviceFleet, DeviceInstance } from './Operations';
import { DeviceGovernance, DriverRegistry, ModelRequests } from './Registry';
import { DeviceWizard, DeviceWizardList } from './Wizard';

afterEach(cleanup);
const noop = () => {};
const props = (api: ReturnType<typeof mockApi>, ...roles: string[]) => ({ client: api.client, session: sessionFor(...roles), onSessionExpired: noop });
const ID = 'CFG-000012';
const C = `/device-configurations/${ID}`;

const step = (n: number, over: Partial<WizardStep> = {}): WizardStep => ({ step: n, name: `Step ${n}`, status: 'NOT_STARTED', ...over });
const valid = (upTo: number, extra: Partial<Record<number, Partial<WizardStep>>> = {}) =>
  [1, 2, 3, 4, 5, 6, 7, 8].map((n) => step(n, { status: n <= upTo ? 'VALID' : 'NOT_STARTED', updatedAt: `2026-10-31T10:0${n}:00Z`, ...(extra[n] ?? {}) }));
const cfg = (over: Partial<Configuration> = {}): Configuration => ({
  configurationId: ID, version: 1, deviceInstanceId: 'DEV-000012', status: 'DRAFT', paused: false, currentStep: 1, modelCode: 'MD-027',
  steps: valid(0), tests: [], ...over,
});
const model = {
  modelCode: 'MD-027', family: 'VITALS', familyLabel: 'Vital signs & body composition', manufacturer: 'Welch Allyn', model: 'Connex Spot Monitor', ohUse: 'Vital signs',
  options: [{ option: 'P4', supported: true, notes: 'HL7 over LAN' }, { option: 'P2', supported: true }, { option: 'P1', supported: false }],
  regulatory: [{ market: 'US', regime: 'FDA', status: 'CLEARED', productCodes: ['DXN'] }, { market: 'EU', regime: 'MDR', status: 'UNKNOWN', productCodes: [] }],
  security: { posture: 'ACCEPTABLE', mds2: { received: true }, sbom: { available: false }, findings: [{ rule: 'MDS2_RECEIVED', outcome: 'PASS', message: 'MDS2 on file' }] },
  drivers: [{ packageId: 'vitals.welchallyn.csm-pcd01', version: '1.0.0', status: 'CERTIFIED', options: ['P4'] }],
  lifecycle: 'CANDIDATE', confidence: 'High',
};
const comparison = [
  { option: 'P4', name: 'LAN / Wi-Fi – HL7 v2 over MLLP', prerequisites: ['NETWORK_ZONE', 'CERTIFICATES'], reliability: 'HIGH', securityLevel: 'HIGH', effort: 'MEDIUM', driverAvailable: true, driver: { packageId: 'vitals.welchallyn.csm-pcd01', version: '1.0.0', status: 'CERTIFIED' } },
  { option: 'P2', name: 'Vendor software database / file export watcher', prerequisites: ['VENDOR_SOFTWARE_VERSION'], reliability: 'MEDIUM', securityLevel: 'MEDIUM', effort: 'LOW', driverAvailable: false },
];
const manifest = {
  capabilities: { worklistPush: 'HL7_ADT' },
  bindings: [{ formCode: 'E03.DEVICE_RESULT.VITALS', target: 'E03.RESULT', category: 'VITALS', verifyBeforePost: true, fields: { systolic: 'bpSystolic', diastolic: 'bpDiastolic' } }],
  mappings: [{ key: 'systolic', display: 'Systolic', ucum: 'mm[Hg]' }, { key: 'diastolic', display: 'Diastolic', ucum: 'mm[Hg]' }],
  testVectors: [{ name: 'normal' }, { name: 'malformed' }],
};
const stations = [
  { stationCode: 'LHR-OH-ROOM-1', siteCode: 'LHR', name: 'LHR OH centre – examination room 1', testSite: false, active: true },
  { stationCode: 'TST-BENCH-1', siteCode: 'TEST', name: 'Integration test bench 1', testSite: true, active: true },
];
const p4Schema = {
  type: 'object', required: ['mllp', 'ackMode'],
  properties: {
    mllp: { type: 'object', required: ['listenerEndpointId', 'port'], properties: { listenerEndpointId: { type: 'string', pattern: '^[A-Z0-9][A-Z0-9\\-]{2,60}$' }, port: { type: 'integer', minimum: 1024, maximum: 65535 }, tls: { type: 'string', enum: ['REQUIRED', 'EXCEPTION_SEGMENTED_VLAN'] } } },
    ackMode: { type: 'string', enum: ['ORIGINAL'] },
    oauth2: { type: 'object', properties: { clientId: { type: 'string' }, clientSecretRef: { type: 'string', format: 'secret' } } },
  },
};
const defaults = {
  option: 'P4', parameters: { mllp: { port: 2575, tls: 'REQUIRED' }, ackMode: 'ORIGINAL' }, common: { timeSource: 'NTP_GATEWAY', verifyBeforePost: true, driverPackageId: 'vitals.welchallyn.csm-pcd01', driverVersion: '1.0.0' },
  parameterSchema: p4Schema, commonSchema: { type: 'object', required: ['timeSource'], properties: { timeSource: { type: 'string', enum: ['NTP_GATEWAY', 'DEVICE_RTC'] }, verifyBeforePost: { type: 'boolean' } } },
  secretFields: ['oauth2.clientSecretRef'],
};
const wizardRoutes = (c: Configuration, extra: Record<string, unknown> = {}) => ({
  [`GET ${C}`]: c,
  'GET /device-catalogue/models/MD-027': model,
  [`GET ${C}/options`]: comparison,
  'GET /device-catalogue/options': [{ option: 'P4', name: 'LAN / Wi-Fi – HL7 v2 over MLLP', howItWorks: 'Device sends ORU^R01 over MLLP with TLS.', stepGuide: [{ step: 1, text: 'Give the device a static address.' }] }],
  [`GET ${C}/steps/4/defaults`]: defaults,
  'GET /device-catalogue/drivers/vitals.welchallyn.csm-pcd01/1.0.0': { packageId: 'vitals.welchallyn.csm-pcd01', version: '1.0.0', models: ['MD-027'], options: ['P4'], status: 'CERTIFIED', manifest },
  'GET /device-catalogue/stations': stations,
  ...extra,
});
const withOption = { option: 'P4', driverPackageId: 'vitals.welchallyn.csm-pcd01', driverVersion: '1.0.0' };

describe('device console screens – smoke (initial render)', () => {
  const client: AuthClient = { mode: 'bff', init: async () => session, login: async () => undefined, logout: async () => undefined, api: () => new Promise(() => {}) };
  const session: Session = { authenticated: true, name: 'Dana Device-Admin', username: 'deviceadmin1', roles: ['DEVICE_ADMIN', 'DEVICE_APPROVER', 'INTEGRATION_TESTER', 'SECURITY_OPS'] };
  const base = { client, session, onSessionExpired: noop };
  const screens: [string, ReactElement][] = [
    ['DeviceCatalogue', <DeviceCatalogue {...base} />],
    ['DeviceModelDetail', <DeviceModelDetail {...base} modelCode="MD-027" />],
    ['DeviceWizardList', <DeviceWizardList {...base} />],
    ['DeviceWizard', <DeviceWizard {...base} configurationId={ID} />],
    ['DeviceApprovals', <DeviceApprovals {...base} />],
    ['DeviceTestReview', <DeviceTestReview {...base} />],
    ['DriverRegistry', <DriverRegistry {...base} />],
    ['ModelRequests', <ModelRequests {...base} />],
    ['DeviceGovernance', <DeviceGovernance {...base} />],
    ['DeviceCalibration', <DeviceCalibration {...base} />],
    ['DeviceFleet', <DeviceFleet {...base} />],
    ['DeviceInstance', <DeviceInstance {...base} deviceInstanceId="DEV-000001" />],
  ];
  it.each(screens)('%s renders', (_, el) => {
    expect(() => renderToStaticMarkup(el)).not.toThrow();
  });
});

describe('catalogue', () => {
  it('searches by brand / family and compares models side by side', async () => {
    const user = userEvent.setup();
    const summary = { modelCode: 'MD-027', family: 'VITALS', manufacturer: 'Welch Allyn', model: 'Connex Spot Monitor', options: ['P4', 'P2'], lifecycle: 'CANDIDATE', posture: 'ACCEPTABLE', certifiedDrivers: 1, confidence: 'High' };
    const api = mockApi({
      'GET /device-catalogue/families': [{ family: 'VITALS', label: 'Vital signs & body composition', models: 18, certifiedModels: 2 }],
      'GET /device-catalogue/models': [summary, { ...summary, modelCode: 'MD-028', model: 'Connex Vital Signs Monitor', posture: 'BLOCKED' }],
      'GET /device-catalogue/models?q=welch': [summary],
      'GET /device-catalogue/compare?models=MD-027%2CMD-028': [model, { ...model, modelCode: 'MD-028', model: 'Connex VSM' }],
    });
    render(<DeviceCatalogue {...props(api, 'DEVICE_ADMIN')} />);
    expect(await screen.findByRole('link', { name: 'Welch Allyn Connex Spot Monitor' })).toBeTruthy();
    expect(screen.getByText('Blocked', { selector: '.badge' })).toBeTruthy();
    await user.click(screen.getByRole('checkbox', { name: 'Compare Welch Allyn Connex Spot Monitor' }));
    await user.click(screen.getByRole('checkbox', { name: 'Compare Welch Allyn Connex Vital Signs Monitor' }));
    await user.click(screen.getByRole('button', { name: 'Compare selected' }));
    expect(await screen.findByRole('columnheader', { name: 'Welch Allyn Connex VSM' })).toBeTruthy();
    await user.type(screen.getByLabelText('Brand, model or code'), 'welch');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(api.find('GET', '/device-catalogue/models?q=welch')).toHaveLength(1));
    expect(screen.getByRole('link', { name: 'Request a new model' }).getAttribute('href')).toBe('#/devices/model-requests');
  });

  it('model detail: regulatory per market, supported options, drivers, posture; intake evaluates posture', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /device-catalogue/models/MD-027': model, 'PUT /device-catalogue/models/MD-027/posture': { modelCode: 'MD-027', posture: 'BLOCKED', evaluatedPosture: 'BLOCKED', findings: [] } });
    render(<DeviceModelDetail {...props(api, 'SECURITY_OPS')} modelCode="MD-027" />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Welch Allyn Connex Spot Monitor' })).toBeTruthy();
    const reg = screen.getByRole('table', { name: 'Regulatory status' });
    expect(within(reg).getByText('Cleared')).toBeTruthy();
    expect(screen.getByText('1 option not supported by this model (hidden in the wizard).')).toBeTruthy();
    expect(screen.getByText('vitals.welchallyn.csm-pcd01@1.0.0')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Provision this model' })).toBeNull();
    await user.click(screen.getByText('Record vendor security intake'));
    const form = screen.getByRole('form', { name: 'Vendor security intake' });
    await user.click(within(form).getByRole('button', { name: 'Evaluate posture' }));
    expect(within(form).getByRole('link', { name: 'Choose the patch path' })).toBeTruthy();
    await user.selectOptions(within(form).getByLabelText(/Patch path/), 'NONE');
    await user.click(within(form).getByRole('button', { name: 'Evaluate posture' }));
    await waitFor(() => expect(api.find('PUT', '/device-catalogue/models/MD-027/posture')[0]?.body).toMatchObject({ mds2: { received: true }, patchPath: 'NONE', remoteSupport: 'NONE' }));
    expect(await screen.findByText('Posture evaluated: Blocked.')).toBeTruthy();
  });
});

describe('provisioning wizard', () => {
  it('lists configurations and starts a draft (testers must pick a test site)', async () => {
    const user = userEvent.setup();
    window.location.hash = '#/devices/wizard?model=MD-027';
    const api = mockApi({
      'GET /device-configurations': [{ configurationId: ID, version: 1, deviceInstanceId: 'DEV-000012', status: 'DRAFT', paused: true, currentStep: 4, modelCode: 'MD-027', option: 'P4' }],
      'GET /device-catalogue/stations': stations,
      'POST /device-configurations': cfg(),
    });
    render(<DeviceWizardList {...props(api, 'INTEGRATION_TESTER')} />);
    expect(await screen.findByRole('link', { name: ID })).toBeTruthy();
    expect(screen.getByText('4 of 8 – Configure parameters')).toBeTruthy();
    expect(screen.getByText('Paused')).toBeTruthy();
    expect((screen.getByLabelText(/Model code/) as HTMLInputElement).value).toBe('MD-027');
    await user.click(screen.getByRole('button', { name: 'Create draft' }));
    expect(screen.getByRole('link', { name: /test site/ })).toBeTruthy();
    await user.selectOptions(screen.getByLabelText(/^Site/, { selector: '#start-site' }), 'TEST');
    await user.click(screen.getByRole('button', { name: 'Create draft' }));
    await waitFor(() => expect(api.find('POST', '/device-configurations')[0]?.body).toEqual({ modelCode: 'MD-027', siteCode: 'TEST' }));
    await waitFor(() => expect(window.location.hash).toBe(`#/devices/wizard/${ID}`));
  });

  it('stepper: eight steps with status text, later steps locked, current step marked', async () => {
    const api = mockApi(wizardRoutes(cfg({ currentStep: 2, steps: valid(1), ...withOption })));
    render(<DeviceWizard {...props(api, 'DEVICE_ADMIN')} configurationId={ID} />);
    const nav = await screen.findByRole('navigation', { name: 'Wizard steps' });
    const buttons = within(nav).getAllByRole('button');
    expect(buttons).toHaveLength(8);
    expect(buttons[0]!.textContent).toContain('Complete');
    expect(buttons[1]!.getAttribute('aria-current')).toBe('step');
    expect((buttons[2] as HTMLButtonElement).disabled).toBe(true);
    expect(buttons[2]!.textContent).toContain('(locked)');
    expect(screen.getByRole('heading', { level: 2, name: /Step 2 of 8: Choose connectivity option/ })).toBeTruthy();
  });

  it('step 1 saves the selected model and moves to step 2', async () => {
    const user = userEvent.setup();
    const api = mockApi(wizardRoutes(cfg(), { [`PUT ${C}/steps/1`]: cfg({ currentStep: 2, steps: valid(1), warnings: ['model_deprecated'] }) }));
    render(<DeviceWizard {...props(api, 'DEVICE_ADMIN')} configurationId={ID} />);
    expect(await screen.findByDisplayValue('Welch Allyn Connex Spot Monitor (MD-027)')).toBeTruthy();
    await user.type(screen.getByLabelText('Serial number'), 'CSM-100123');
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    await waitFor(() => expect(api.find('PUT', `${C}/steps/1`)[0]?.body).toEqual({ modelCode: 'MD-027', serial: 'CSM-100123' }));
    expect(await screen.findByRole('heading', { level: 2, name: /Step 2 of 8/ })).toBeTruthy();
    expect(screen.getByText(/Warnings: Model deprecated/)).toBeTruthy();
  });

  it('step 2 compares the supported options only, needs a choice and a driver', async () => {
    const user = userEvent.setup();
    const api = mockApi(wizardRoutes(cfg({ currentStep: 2, steps: valid(1) }), { [`PUT ${C}/steps/2`]: cfg({ currentStep: 3, steps: valid(2), ...withOption }) }));
    render(<DeviceWizard {...props(api, 'DEVICE_ADMIN')} configurationId={ID} />);
    const table = await screen.findByRole('table', { name: 'Comparison of the supported connectivity options' });
    expect(within(table).getAllByRole('radio')).toHaveLength(2);
    expect(within(table).queryByText(/P1/)).toBeNull();
    expect((within(table).getByRole('radio', { name: /P2/ }) as HTMLInputElement).disabled).toBe(true);
    expect(within(table).getByText('No driver package')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    expect(screen.getByRole('link', { name: 'Choose a connectivity option' })).toBeTruthy();
    await user.click(within(table).getByRole('radio', { name: /P4/ }));
    expect(await screen.findByText('Device sends ORU^R01 over MLLP with TLS.')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    await waitFor(() => expect(api.find('PUT', `${C}/steps/2`)[0]?.body).toEqual({ option: 'P4' }));
    expect(await screen.findByRole('heading', { level: 2, name: /Step 3 of 8/ })).toBeTruthy();
  });

  it('step 3 generates the checklist, shows auto-check results and needs evidence for manual items marked done', async () => {
    const user = userEvent.setup();
    const items = [
      { code: 'NETWORK_ZONE', label: 'Network zone and firewall change', kind: 'MANUAL', required: true, status: 'OPEN' },
      { code: 'VENDOR_POSTURE', label: 'Vendor security posture', kind: 'AUTO', required: true, status: 'DONE', autoResult: 'Posture ACCEPTABLE' },
    ];
    const withItems = cfg({ currentStep: 3, steps: valid(2, { 3: { status: 'IN_PROGRESS', data: { items }, errors: [{ field: 'items.NETWORK_ZONE', code: 'prerequisite_open', message: 'Network zone and firewall change is not complete' }] } }), ...withOption });
    const api = mockApi(wizardRoutes(cfg({ currentStep: 3, steps: valid(2), ...withOption }), {
      [`POST ${C}/steps/3/auto-check`]: withItems,
      [`PUT ${C}/steps/3`]: cfg({ currentStep: 4, steps: valid(3), ...withOption }),
    }));
    render(<DeviceWizard {...props(api, 'DEVICE_ADMIN')} configurationId={ID} />);
    await user.click(await screen.findByRole('button', { name: /Generate the checklist/ }));
    expect(await screen.findByText('Posture ACCEPTABLE')).toBeTruthy();
    expect(screen.getAllByText('Network zone and firewall change is not complete').length).toBeGreaterThan(0);
    await user.selectOptions(screen.getByLabelText('Status'), 'DONE');
    await user.click(screen.getByRole('button', { name: 'Save checklist' }));
    expect(screen.getByRole('link', { name: /Network zone and firewall change: record the evidence/ })).toBeTruthy();
    expect(api.find('PUT', `${C}/steps/3`)).toHaveLength(0);
    await user.type(screen.getByLabelText(/Zone \(e.g. DEVICE_VLAN\)/), 'DEVICE_VLAN');
    await user.click(screen.getByRole('button', { name: 'Save checklist' }));
    await waitFor(() => expect(api.find('PUT', `${C}/steps/3`)[0]?.body).toEqual({ items: [{ code: 'NETWORK_ZONE', status: 'DONE', evidence: { reference: 'DEVICE_VLAN' } }, { code: 'VENDOR_POSTURE', status: 'DONE' }] }));
    expect(await screen.findByRole('heading', { level: 2, name: /Step 4 of 8/ })).toBeTruthy();
  });

  it('step 4 renders the option-pack schema with validated defaults; secrets are write-only and shown as set', async () => {
    const user = userEvent.setup();
    const saved = { parameters: { mllp: { port: 2575, tls: 'REQUIRED' }, ackMode: 'ORIGINAL', oauth2: { clientId: 'wz', clientSecretRef: { set: true } } }, common: { timeSource: 'NTP_GATEWAY' }, secrets: { 'oauth2.clientSecretRef': { set: true } } };
    const api = mockApi(wizardRoutes(cfg({ currentStep: 4, steps: valid(3, { 4: { status: 'INVALID', data: saved } }), ...withOption }), {
      [`PUT ${C}/steps/4`]: reply(422, { status: 422, code: 'step_invalid', detail: 'Step 4 is not valid', errors: [{ field: 'parameters.mllp.listenerEndpointId', code: 'endpoint_not_usable', message: 'The gateway endpoint is not enrolled' }] }),
    }));
    render(<DeviceWizard {...props(api, 'DEVICE_ADMIN')} configurationId={ID} />);
    const port = (await screen.findByLabelText(/^Port/)) as HTMLInputElement;
    expect(port.value).toBe('2575');
    expect(screen.getByRole('heading', { level: 3, name: 'MLLP' })).toBeTruthy();
    const secret = screen.getByLabelText(/Client secret ref/) as HTMLInputElement;
    expect(secret.type).toBe('password');
    expect(secret.value).toBe('');
    expect(screen.getByText('Set')).toBeTruthy();
    await user.clear(port);
    await user.type(port, '80');
    await user.click(screen.getByRole('button', { name: 'Validate and save' }));
    expect(screen.getByRole('link', { name: 'Port must be between 1024 and 65535' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Listener endpoint ID is required' })).toBeTruthy();
    expect(api.find('PUT', `${C}/steps/4`)).toHaveLength(0);
    await user.clear(port);
    await user.type(port, '2575');
    await user.type(screen.getByLabelText(/Listener endpoint ID/), 'EP-LHR-GW-09');
    await user.type(secret, 'rotated-secret');
    await user.click(screen.getByRole('button', { name: 'Validate and save' }));
    await waitFor(() => expect(api.find('PUT', `${C}/steps/4`)).toHaveLength(1));
    expect(api.find('PUT', `${C}/steps/4`)[0]!.body).toEqual({
      parameters: { mllp: { port: 2575, tls: 'REQUIRED', listenerEndpointId: 'EP-LHR-GW-09' }, ackMode: 'ORIGINAL', oauth2: { clientId: 'wz' } },
      common: { timeSource: 'NTP_GATEWAY', verifyBeforePost: true },
      secrets: { 'oauth2.clientSecretRef': 'rotated-secret' },
    });
    const link = await screen.findByRole('link', { name: 'The gateway endpoint is not enrolled' });
    expect(link.getAttribute('href')).toBe('#cfg-p-mllp-listenerEndpointId');
  });

  it('step 5 validates association, station and bindings, then saves only included bindings; preview shows the bound fields', async () => {
    const user = userEvent.setup();
    const api = mockApi(wizardRoutes(cfg({ currentStep: 5, steps: valid(4), ...withOption, siteCode: 'LHR' }), {
      [`PUT ${C}/steps/5`]: cfg({ currentStep: 6, steps: valid(5), ...withOption }),
      [`POST ${C}/steps/5/preview`]: { datasetId: 'normal', forms: [{ formCode: 'E03.DEVICE_RESULT.VITALS', verifyBeforePost: true, fields: [{ key: 'systolic', formField: 'bpSystolic', value: 128, unit: 'mm[Hg]', display: 'Systolic', filled: true }, { key: 'diastolic', formField: 'bpDiastolic', filled: false }] }] },
    }));
    render(<DeviceWizard {...props(api, 'DEVICE_ADMIN')} configurationId={ID} />);
    await user.click(await screen.findByRole('button', { name: 'Save and continue' }));
    expect(screen.getByRole('link', { name: 'Choose at least one association method' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Choose the station' })).toBeTruthy();
    expect((screen.getByRole('checkbox', { name: /Clinician verifies before posting/ }) as HTMLInputElement).disabled).toBe(true);
    await user.click(screen.getByRole('checkbox', { name: 'Session' }));
    await user.selectOptions(screen.getByLabelText(/^Station/), 'LHR-OH-ROOM-1');
    await user.clear(screen.getByLabelText('Form field for diastolic in E03.DEVICE_RESULT.VITALS'));
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    await waitFor(() => expect(api.find('PUT', `${C}/steps/5`)[0]?.body).toEqual({
      associationMethods: ['SESSION'], siteCode: 'LHR', stationCode: 'LHR-OH-ROOM-1', services: [], formCodes: ['E03.DEVICE_RESULT.VITALS'],
      bindings: [{ formCode: 'E03.DEVICE_RESULT.VITALS', target: 'E03.RESULT', category: 'VITALS', verifyBeforePost: true, fields: { systolic: 'bpSystolic' } }],
    }));
  });

  it('step 5 preview against a test vector', async () => {
    const user = userEvent.setup();
    const api = mockApi(wizardRoutes(cfg({ currentStep: 5, steps: valid(4), ...withOption }), {
      [`POST ${C}/steps/5/preview`]: { datasetId: 'malformed', expectedError: 'parse_failed', forms: [{ formCode: 'E03.DEVICE_RESULT.VITALS', fields: [{ key: 'systolic', formField: 'bpSystolic', value: 128, unit: 'mm[Hg]', display: 'Systolic', filled: true }] }] },
    }));
    render(<DeviceWizard {...props(api, 'DEVICE_ADMIN')} configurationId={ID} />);
    await user.selectOptions(await screen.findByLabelText('Test vector'), 'malformed');
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    expect(await screen.findByText(/negative case/)).toBeTruthy();
    expect(screen.getByRole('cell', { name: '128' })).toBeTruthy();
    expect(api.find('POST', `${C}/steps/5/preview`)[0]?.body).toEqual({ datasetId: 'malformed' });
  });

  it('step 6 shows the requirements and handles 503 module_disabled from the diagnostics runner gracefully', async () => {
    const user = userEvent.setup();
    const api = mockApi(wizardRoutes(cfg({ currentStep: 6, steps: valid(5), ...withOption }), {
      [`POST ${C}/steps/6/tests`]: reply(503, { status: 503, code: 'module_disabled', detail: 'Module E31 is disabled' }),
    }));
    render(<DeviceWizard {...props(api, 'DEVICE_ADMIN')} configurationId={ID} />);
    expect(await screen.findByText('Passing simulator run including the negative test vectors')).toBeTruthy();
    expect(screen.getByText('No test has been run for this version yet.')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Run diagnostics test' }));
    expect(await screen.findByText(/is not available in this environment yet/)).toBeTruthy();
    expect(screen.getByText(/module_disabled/)).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(api.find('POST', `${C}/steps/6/tests`)[0]?.body).toEqual({ mode: 'SIMULATOR' });
  });

  it('step 6 lists test runs with evidence and records a simulator-only justification', async () => {
    const user = userEvent.setup();
    const tests = [{ testRunId: 't1', mode: 'SIMULATOR' as const, status: 'PASSED', finishedAt: '2026-10-31T10:00:00Z', by: 'deviceadmin1', evidenceSha256: 'a'.repeat(64), steps: [{ name: 'Negative vectors rejected', outcome: 'PASS' }] }];
    const api = mockApi(wizardRoutes(cfg({ currentStep: 6, steps: valid(5), tests, ...withOption }), { [`PUT ${C}/steps/6`]: cfg({ currentStep: 6, steps: valid(5), tests, simulatorOnlyJustification: 'Bench only', ...withOption }) }));
    render(<DeviceWizard {...props(api, 'DEVICE_ADMIN')} configurationId={ID} />);
    expect(await screen.findByText('Negative vectors rejected')).toBeTruthy();
    expect(screen.getByText('Passed')).toBeTruthy();
    await user.type(screen.getByRole('textbox', { name: /Simulator-only justification/ }), 'Bench only');
    await user.click(screen.getByRole('button', { name: 'Save justification' }));
    await waitFor(() => expect(api.find('PUT', `${C}/steps/6`)[0]?.body).toEqual({ simulatorOnlyJustification: 'Bench only' }));
  });

  it('step 7 four-eyes: approve is disabled for the submitter', async () => {
    const awaiting = cfg({ status: 'AWAITING_APPROVAL', currentStep: 7, submittedBy: 'tester', steps: valid(6, { 7: { status: 'IN_PROGRESS', data: { submittedAt: '2026-10-31T11:00:00Z' } } }), ...withOption, nurseReview: { outcome: 'ACCEPTED', by: 'nurse1' } });
    const api = mockApi(wizardRoutes(awaiting));
    render(<DeviceWizard {...props(api, 'DEVICE_APPROVER', 'DEVICE_ADMIN')} configurationId={ID} />);
    const approve = (await screen.findByRole('button', { name: 'Approve and certify' })) as HTMLButtonElement;
    expect(approve.disabled).toBe(true);
    expect(screen.getAllByText(/You submitted this configuration – another approver must approve it \(four-eyes\)/).length).toBeGreaterThan(0);
    expect(approve.getAttribute('aria-describedby')).toBe('four-eyes-reason');
  });

  it('step 7 four-eyes: another approver approves with a note; the submitter-only view explains the wait', async () => {
    const user = userEvent.setup();
    const awaiting = cfg({ status: 'AWAITING_APPROVAL', currentStep: 7, submittedBy: 'deviceadmin1', steps: valid(6, { 4: { status: 'VALID', updatedBy: 'deviceadmin1' } }), ...withOption, tests: [{ testRunId: 't', mode: 'SIMULATOR', status: 'PASSED', by: 'deviceadmin1' }] });
    const certified = cfg({ status: 'CERTIFIED', currentStep: 8, approvedBy: 'tester', steps: valid(7), ...withOption });
    const api = mockApi(wizardRoutes(awaiting, { [`POST ${C}/approve`]: certified, [`GET ${C}/certification`]: { certificationId: 'DCR-2026-000004', configurationId: ID, version: 1, deviceInstanceId: 'DEV-000012', approvedBy: 'tester', snapshotSha256: 'b'.repeat(64) } }));
    render(<DeviceWizard {...props(api, 'DEVICE_APPROVER')} configurationId={ID} />);
    const approve = (await screen.findByRole('button', { name: 'Approve and certify' })) as HTMLButtonElement;
    expect(approve.disabled).toBe(false);
    await user.type(screen.getByLabelText('Approval note'), 'Evidence reviewed');
    await user.click(approve);
    await waitFor(() => expect(api.find('POST', `${C}/approve`)[0]?.body).toEqual({ note: 'Evidence reviewed' }));
    expect(await screen.findByText('DCR-2026-000004')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Download certificate (PDF)' }).getAttribute('href')).toBe(`/bff/api${C}/certificate.pdf`);
    cleanup();
    const admin = mockApi(wizardRoutes(awaiting));
    render(<DeviceWizard {...props(admin, 'DEVICE_ADMIN')} configurationId={ID} />);
    expect(await screen.findByText(/Awaiting four-eyes approval by a device approver/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Approve and certify' })).toBeNull();
  });

  it('step 7 reject needs a reason; a 409 four_eyes_violation from the server is shown', async () => {
    const user = userEvent.setup();
    const awaiting = cfg({ status: 'AWAITING_APPROVAL', currentStep: 7, submittedBy: 'deviceadmin1', steps: valid(6), ...withOption });
    const api = mockApi(wizardRoutes(awaiting, { [`POST ${C}/approve`]: reply(409, { status: 409, code: 'four_eyes_violation', detail: 'You submitted or changed this configuration – another approver must approve it' }) }));
    render(<DeviceWizard {...props(api, 'DEVICE_APPROVER')} configurationId={ID} />);
    await user.click(await screen.findByRole('button', { name: 'Reject' }));
    expect(screen.getByText('Enter the reason')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Approve and certify' }));
    expect((await screen.findByRole('alert')).textContent).toContain('another approver must approve it');
  });

  it('step 7 submit is offered only when steps 1–6 are complete', async () => {
    const user = userEvent.setup();
    const api = mockApi(wizardRoutes(cfg({ currentStep: 7, steps: valid(6), ...withOption }), { [`POST ${C}/submit`]: cfg({ status: 'AWAITING_APPROVAL', currentStep: 7, submittedBy: 'tester', steps: valid(6), ...withOption }) }));
    render(<DeviceWizard {...props(api, 'DEVICE_ADMIN')} configurationId={ID} />);
    await user.click(await screen.findByRole('button', { name: 'Submit for approval' }));
    await waitFor(() => expect(api.find('POST', `${C}/submit`)).toHaveLength(1));
    expect(await screen.findByText(/Awaiting four-eyes approval/)).toBeTruthy();
    cleanup();
    const notReady = mockApi(wizardRoutes(cfg({ currentStep: 6, steps: valid(5, { 7: { status: 'IN_PROGRESS' } }), ...withOption })));
    render(<DeviceWizard {...props(notReady, 'DEVICE_ADMIN')} configurationId={ID} />);
    await user.click(within(await screen.findByRole('navigation', { name: 'Wizard steps' })).getByRole('button', { name: /7\. Certify/ }));
    expect((screen.getByRole('button', { name: 'Submit for approval' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('step 8 publishes and shows the one-time enrolment token once', async () => {
    const user = userEvent.setup();
    const certified = cfg({ status: 'CERTIFIED', currentStep: 8, approvedBy: 'deviceapprover1', steps: valid(7), ...withOption, siteCode: 'LHR', stationCode: 'LHR-OH-ROOM-1' });
    const api = mockApi(wizardRoutes(certified, { [`POST ${C}/publish`]: { configurationId: ID, deviceInstanceId: 'DEV-000012', configVersion: 1, status: 'PUBLISHED', endpointId: 'EP-LHR-GW-03', enrolmentToken: 'wz-enrol-0123456789abcdef', enrolmentExpiresAt: '2026-11-03T10:00:00Z' } }));
    render(<DeviceWizard {...props(api, 'DEVICE_ADMIN')} configurationId={ID} />);
    await user.click(await screen.findByRole('button', { name: 'Publish to LHR-OH-ROOM-1' }));
    const box = await screen.findByRole('region', { name: 'Enrolment token for EP-LHR-GW-03' });
    expect(within(box).getByText('wz-enrol-0123456789abcdef')).toBeTruthy();
    expect(within(box).getByText(/Shown once/)).toBeTruthy();
    await user.click(within(box).getByRole('button', { name: /I have copied it/ }));
    expect(screen.queryByText('wz-enrol-0123456789abcdef')).toBeNull();
    expect(screen.getByText(/cannot be shown again/)).toBeTruthy();
  });

  it('lifecycle: pause, quarantine banner with re-certification path, decommission request validation', async () => {
    const user = userEvent.setup();
    const quarantined = cfg({ status: 'QUARANTINED', currentStep: 8, steps: valid(8), ...withOption, statusReason: 'ENDPOINT_ISOLATED' });
    const api = mockApi(wizardRoutes(quarantined, { [`POST ${C}/pause`]: { ...quarantined, paused: true }, [`POST ${C}/decommission`]: { ...quarantined, decommissionRequest: { reason: 'Broken', disposal: 'DISPOSED', requestedBy: 'tester', requestedAt: 'x' } } }));
    render(<DeviceWizard {...props(api, 'DEVICE_ADMIN')} configurationId={ID} />);
    expect(await screen.findByText(/Re-certification is required: revise → test → four-eyes approval → publish/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Revise' })).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Pause' }));
    await waitFor(() => expect(api.find('POST', `${C}/pause`)).toHaveLength(1));
    expect(await screen.findByRole('button', { name: 'Resume' })).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Decommission' }));
    const dlg = screen.getByRole('dialog', { name: 'Decommission device' });
    await user.click(within(dlg).getByRole('button', { name: 'Request decommissioning' }));
    expect(within(dlg).getByRole('link', { name: 'Choose the disposal' })).toBeTruthy();
    await user.type(within(dlg).getByLabelText(/Reason/), 'Broken');
    await user.selectOptions(within(dlg).getByLabelText(/Disposal/), 'DISPOSED');
    await user.click(within(dlg).getByRole('button', { name: 'Request decommissioning' }));
    await waitFor(() => expect(api.find('POST', `${C}/decommission`)[0]?.body).toEqual({ reason: 'Broken', disposal: 'DISPOSED' }));
    expect(await screen.findByText(/Decommissioning requested by tester/)).toBeTruthy();
  });

  it('read-only for a paused draft', async () => {
    const api = mockApi(wizardRoutes(cfg({ paused: true })));
    render(<DeviceWizard {...props(api, 'DEVICE_ADMIN')} configurationId={ID} />);
    expect(await screen.findByText('The configuration is paused – resume it to make changes.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Save and continue' })).toBeNull();
  });
});

describe('approvals, test review and registry', () => {
  it('approval queue: configuration opens the approver view; requester cannot approve their own decommissioning', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /device-governance/approvals?status=PENDING': [
        { approvalId: 'a1', kind: 'CONFIGURATION', reference: ID, version: 1, status: 'PENDING', requestedBy: 'deviceadmin1', requestedAt: '2026-10-31T10:00:00Z', detail: { siteCode: 'LHR' } },
        { approvalId: 'a2', kind: 'DECOMMISSION', reference: 'CFG-000003', status: 'PENDING', requestedBy: 'tester', requestedAt: '2026-10-31T10:00:00Z', detail: { disposal: 'DISPOSED' } },
      ],
      'POST /device-governance/approvals/a2/reject': {},
    });
    render(<DeviceApprovals {...props(api, 'DEVICE_APPROVER')} />);
    expect((await screen.findByRole('link', { name: 'Open approver view' })).getAttribute('href')).toBe(`#/devices/wizard/${ID}`);
    expect(screen.getByText(/You requested this – another approver must decide/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Decide Decommission CFG-000003' }));
    const dlg = screen.getByRole('dialog');
    expect((within(dlg).getByRole('button', { name: 'Approve' }) as HTMLButtonElement).disabled).toBe(true);
    await user.click(within(dlg).getByRole('button', { name: 'Reject' }));
    expect(within(dlg).getByText('Enter the reason for rejecting')).toBeTruthy();
    await user.type(within(dlg).getByLabelText(/Note/), 'Still in use');
    await user.click(within(dlg).getByRole('button', { name: 'Reject' }));
    await waitFor(() => expect(api.find('POST', '/device-governance/approvals/a2/reject')[0]?.body).toEqual({ note: 'Still in use' }));
  });

  it('nurse records the device test review', async () => {
    const user = userEvent.setup();
    const api = mockApi({ [`POST ${C}/steps/6/nurse-review`]: {} });
    render(<DeviceTestReview {...props(api, 'OH_CLINICIAN')} />);
    await user.click(screen.getByRole('button', { name: 'Record review' }));
    expect(screen.getByRole('link', { name: /configuration reference/ })).toBeTruthy();
    await user.type(screen.getByLabelText(/Configuration reference/), 'cfg-000012');
    await user.click(screen.getByLabelText(/Accepted/));
    await user.click(screen.getByRole('button', { name: 'Record review' }));
    await waitFor(() => expect(api.find('POST', `${C}/steps/6/nurse-review`)[0]?.body).toEqual({ outcome: 'ACCEPTED', note: '' }));
    expect(await screen.findByText(`Review recorded for ${ID}.`)).toBeTruthy();
  });

  it('driver registry: approver cannot certify own registration; security revokes with a reason', async () => {
    const user = userEvent.setup();
    const d = { packageId: 'vitals.and.ua651ble-blp', version: '1.0.0', models: ['MD-037'], options: ['P3'], status: 'REGISTERED', registeredBy: 'tester', signatureKeyId: 'wz-k1' };
    const api = mockApi({ 'GET /device-catalogue/drivers': [d, { ...d, packageId: 'ecg.ge.mac-hl7', registeredBy: 'inttester1' }], 'POST /device-catalogue/drivers/vitals.and.ua651ble-blp/1.0.0/revoke': {} });
    render(<DriverRegistry {...props(api, 'DEVICE_APPROVER', 'SECURITY_OPS')} />);
    const certify = await screen.findAllByRole('button', { name: 'Certify' });
    expect((certify[0] as HTMLButtonElement).disabled).toBe(true);
    expect((certify[1] as HTMLButtonElement).disabled).toBe(false);
    await user.click(screen.getAllByRole('button', { name: 'Revoke' })[0]!);
    const dlg = screen.getByRole('dialog');
    expect(within(dlg).getByText(/suspended/)).toBeTruthy();
    await user.type(within(dlg).getByLabelText(/Reason/), 'Key compromise');
    await user.click(within(dlg).getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(api.find('POST', '/device-catalogue/drivers/vitals.and.ua651ble-blp/1.0.0/revoke')[0]?.body).toEqual({ reason: 'Key compromise' }));
  });

  it('model requests: KPI and validated request form', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /device-model-requests': [],
      'GET /device-model-requests/kpi': { scopedRequests: 2, awaitingScoping: 1, medianScopingWorkingDays: 3, targetWorkingDays: 5, withinTargetPct: 100 },
      'POST /device-model-requests': { id: 'r1', requestRef: 'DMR-2026-000001', status: 'SUBMITTED' },
    });
    render(<ModelRequests {...props(api, 'DEVICE_ADMIN')} />);
    expect(await screen.findByText('on target')).toBeTruthy();
    await user.click(screen.getByText('Request a new model'));
    await user.click(screen.getByRole('button', { name: 'Submit request' }));
    expect(screen.getByRole('link', { name: 'Choose the family' })).toBeTruthy();
    await user.type(screen.getByLabelText(/Manufacturer/), 'Seca');
    await user.type(screen.getByLabelText(/^Model/), 'mBCA 555');
    await user.selectOptions(screen.getByLabelText(/Family/), 'VITALS');
    await user.type(screen.getByLabelText(/Connectivity/), 'Wi-Fi, seca connect FHIR');
    await user.type(screen.getByLabelText(/^Sites/), 'lhr, dxb');
    await user.click(screen.getByRole('button', { name: 'Submit request' }));
    await waitFor(() => expect(api.find('POST', '/device-model-requests')[0]?.body).toEqual({ manufacturer: 'Seca', model: 'mBCA 555', family: 'VITALS', connectivity: 'Wi-Fi, seca connect FHIR', interfaceDocumentIds: [], sites: ['LHR', 'DXB'], urgency: 'NORMAL' }));
    expect(await screen.findByText(/DMR-2026-000001 submitted/)).toBeTruthy();
  });

  it('governance: export then stage an import and show the diff', async () => {
    const user = userEvent.setup();
    const payload = { exportId: 'e1e1e1e1-0000', sourceEnvironment: 'uat', createdAt: 'x', sha256: 'c'.repeat(64), items: [{ configurationId: 'CFG-000001', version: 1, status: 'PUBLISHED', snapshot: {} }] };
    const api = mockApi({
      'GET /device-governance/changes': [{ entityType: 'CONFIGURATION', entityId: 'CFG-000001', version: 1, actor: 'deviceadmin1', action: 'STEP', step: 4, diff: [{ path: 'parameters.mllp.port' }], at: '2026-10-31T10:00:00Z' }],
      'GET /device-governance/export?configurationIds=CFG-000001': payload,
      'POST /device-governance/imports': { importId: 'i1', status: 'PENDING', targetEnvironment: 'prod', items: [{ configurationId: 'CFG-000001', targetConfigurationId: 'CFG-000001', action: 'UPDATE', diff: [{ path: 'parameters.mllp.port', from: 2575, to: 2576 }], secretsToRebind: ['oauth2.clientSecretRef'], problems: [] }] },
    });
    render(<DeviceGovernance {...props(api, 'DEVICE_ADMIN')} />);
    expect(await screen.findByText('parameters.mllp.port')).toBeTruthy();
    await user.type(screen.getByLabelText(/^Configurations/), 'cfg-000001');
    await user.click(screen.getByRole('button', { name: 'Export' }));
    expect(await screen.findByText(/secret values are not included/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Save export file' })).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Stage import' }));
    expect(await screen.findByText('2575')).toBeTruthy();
    expect(screen.getByText('2576')).toBeTruthy();
    expect(screen.getByText(/oauth2.clientSecretRef/)).toBeTruthy();
    expect(api.find('POST', '/device-governance/imports')[0]?.body).toMatchObject({ exportId: 'e1e1e1e1-0000', sha256: 'c'.repeat(64) });
  });
});

describe('calibration and fleet', () => {
  const status = [{ deviceInstanceId: 'DEV-000001', modelCode: 'MD-001', model: 'ndd EasyOne Air', siteCode: 'LHR', stationCode: 'LHR-OH-ROOM-1', status: 'PUBLISHED', checks: [{ checkType: 'SPIRO_SYRINGE_3L', verdict: 'OVERDUE', dueAt: '2026-10-30T08:00:00Z' }], calibration: { verdict: 'VALID' }, lockedOut: true, lockoutReason: 'QC' as const }];

  it('locked-out device: record a failed / passing check and release (no override)', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /device-calibration/status': status, 'GET /device-calibration/schedules': [], 'POST /device-calibration/checks': {}, 'POST /device-calibration/instances/DEV-000001/release': reply(409, { status: 409, code: 'qc_due', detail: 'Record a passing SPIRO_SYRINGE_3L check before releasing the device' }) });
    render(<DeviceCalibration {...props(api, 'DEVICE_ADMIN')} />);
    expect(await screen.findByText('Locked out (Qc)')).toBeTruthy();
    expect(screen.getByText('Overdue')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Release' }));
    expect((await screen.findByRole('alert')).textContent).toContain('Record a passing');
    await user.click(screen.getByRole('button', { name: 'Record a check for DEV-000001' }));
    const dlg = screen.getByRole('dialog');
    await user.click(within(dlg).getByRole('button', { name: 'Record check' }));
    expect(within(dlg).getByRole('link', { name: 'Record pass or fail' })).toBeTruthy();
    await user.click(within(dlg).getByLabelText('Pass'));
    await user.click(within(dlg).getByRole('button', { name: 'Record check' }));
    await waitFor(() => expect(api.find('POST', '/device-calibration/checks')[0]?.body).toMatchObject({ deviceInstanceId: 'DEV-000001', checkType: 'SPIRO_SYRINGE_3L', outcome: 'PASS' }));
  });

  it('fleet: site map, health without person data, acknowledge and ITSM ticket', async () => {
    const user = userEvent.setup();
    const alert = { alertId: 'al1', deviceInstanceId: 'DEV-000002', siteCode: 'LHR', type: 'SILENT', level: 'WARNING', status: 'OPEN', raisedAt: '2026-10-31T10:00:00Z' };
    const api = mockApi({
      'GET /device-fleet/sites': [{ siteCode: 'LHR', devices: 7, healthy: 5, degraded: 1, silent: 1, quarantined: 0, calibrationDue: 1, lockedOut: 1 }],
      'GET /device-fleet': [{ deviceInstanceId: 'DEV-000002', configurationId: 'CFG-000002', configVersion: 1, modelCode: 'MD-015', model: 'Benson CCA-200', siteCode: 'LHR', stationCode: 'LHR-OH-BOOTH-1', status: 'PUBLISHED', health: { state: 'UNKNOWN', reason: 'engine diagnostics unavailable' }, driverPackageId: 'audio.benson.cca200-db', driverVersion: '1.0.0', endpointId: 'EP-LHR-AGT-01', endpointStatus: 'ACTIVE', alerts: [{ alertId: 'al1', type: 'SILENT', level: 'WARNING', raisedAt: 'x', status: 'OPEN' }] }],
      'GET /device-fleet/alerts': [alert],
      'POST /device-fleet/alerts/al1/ticket': { ...alert, ticketRef: 'ITSM-AL1' },
    });
    render(<DeviceFleet {...props(api, 'DEVICE_ADMIN')} />);
    expect(await screen.findByRole('link', { name: 'Benson CCA-200' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'LHR' })).toBeTruthy();
    expect(screen.getByText('1 alert')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Raise ITSM ticket' }));
    expect(await screen.findByText('ITSM ticket ITSM-AL1 raised.')).toBeTruthy();
  });

  it('instance: masked trace handles module_disabled; versions are recorded', async () => {
    const user = userEvent.setup();
    const row = { deviceInstanceId: 'DEV-000001', configurationId: 'CFG-000001', configVersion: 1, modelCode: 'MD-001', model: 'ndd EasyOne Air', siteCode: 'LHR', stationCode: 'LHR-OH-ROOM-1', status: 'PUBLISHED', health: { state: 'UNKNOWN' }, driverPackageId: 'spiro.ndd.easyone-connect-gdt', driverVersion: '1.2.0', alerts: [] };
    const api = mockApi({
      'GET /device-fleet': [row],
      'GET /device-fleet/instances/DEV-000001/consumables': [{ item: 'Mouthpieces', lot: 'L1', expiry: '2026-12-01', quantity: 50 }],
      'GET /device-diagnostics/DEV-000001/trace?limit=50': reply(503, { status: 503, code: 'module_disabled', detail: 'Module E31 is disabled' }),
      'PUT /device-fleet/instances/DEV-000001/versions': { compatible: false },
    });
    render(<DeviceInstance {...props(api, 'DEVICE_ADMIN')} deviceInstanceId="DEV-000001" />);
    expect(await screen.findByText('Mouthpieces')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Load trace' }));
    expect(await screen.findByText(/integration engine diagnostics is not available/)).toBeTruthy();
    await user.type(screen.getByLabelText('Firmware'), '2.1');
    await user.click(screen.getByRole('button', { name: 'Record versions' }));
    expect(await screen.findByText(/NOT compatible with the pinned driver/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Certificate (PDF)' }).getAttribute('href')).toBe('/bff/api/device-configurations/CFG-000001/certificate.pdf');
  });
});
