// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it } from 'vitest';
import type { AuthClient, Session } from '../../auth/AuthClient';
import { mockApi, reply, sessionFor } from '../../testing/mockApi';
import { ConnectorSecrets, SecurityAlerts, SecurityAssurance, SecurityEndpointDetail, SecurityEndpoints, SecurityEvents, SecurityZones, SigningKeys } from './DeviceSecurity';

afterEach(cleanup);
const noop = () => {};
const props = (api: ReturnType<typeof mockApi>, ...roles: string[]) => ({ client: api.client, session: sessionFor(...roles), onSessionExpired: noop });

const endpoint = (over = {}) => ({
  endpointId: 'EP-LHR-GW-01', kind: 'GATEWAY', siteCode: 'LHR', zone: 'GATEWAY_ZONE', tenantCode: 'GROUP', status: 'ACTIVE', deviceInstanceIds: ['DEV-000003', 'DEV-000006'],
  certificate: { sha256: 'f'.repeat(64), subject: 'CN=EP-LHR-GW-01', issuer: 'CN=WZ Dev CA', notAfter: '2027-10-31T00:00:00Z' }, certificateExpiresInDays: 364,
  tokenExpiresAt: '2027-01-29T00:00:00Z', recertificationRequired: false, hardeningStatus: 'OK', hardeningBaseline: 'CIS L1', ...over,
});
const event = (over = {}) => ({ id: 41, eventId: 'e41', type: 'ENDPOINT_ISOLATED', severity: 'HIGH', endpointId: 'EP-LHR-GW-01', actor: 'secops1', sourceModule: 'E34', reasonCode: 'SUSPECTED_COMPROMISE', occurredAt: '2026-10-31T10:00:00Z', hash: 'd'.repeat(64), ...over });

describe('E34 screens – smoke (initial render)', () => {
  const client: AuthClient = { mode: 'bff', init: async () => session, login: async () => undefined, logout: async () => undefined, api: () => new Promise(() => {}) };
  const session: Session = { authenticated: true, name: 'Sam Security-Ops', username: 'secops1', roles: ['SECURITY_OPS'] };
  const base = { client, session, onSessionExpired: noop };
  const screens: [string, ReactElement][] = [
    ['SecurityEndpoints', <SecurityEndpoints {...base} />],
    ['SecurityEndpointDetail', <SecurityEndpointDetail {...base} endpointId="EP-LHR-GW-01" />],
    ['SecurityAlerts', <SecurityAlerts {...base} />],
    ['SecurityEvents', <SecurityEvents {...base} />],
    ['SecurityZones', <SecurityZones {...base} />],
    ['SigningKeys', <SigningKeys {...base} />],
    ['ConnectorSecrets', <ConnectorSecrets {...base} />],
    ['SecurityAssurance', <SecurityAssurance {...base} />],
  ];
  it.each(screens)('%s renders', (_, el) => {
    expect(() => renderToStaticMarkup(el)).not.toThrow();
  });
});

describe('endpoints', () => {
  it('inventory with certificate expiry; creating an endpoint shows the enrolment token once', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /device-security/endpoints': [endpoint(), endpoint({ endpointId: 'EP-DXB-GW-01', siteCode: 'DXB', certificateExpiresInDays: 19 }), endpoint({ endpointId: 'EP-LHR-GW-02', status: 'PENDING_ENROLMENT', certificate: null, certificateExpiresInDays: null })],
      'POST /device-security/endpoints': { endpointId: 'EP-LHR-KSK-02', status: 'PENDING_ENROLMENT', enrolmentToken: 'wz-enrol-one-time-token', enrolmentExpiresAt: '2026-11-03T10:00:00Z' },
    });
    render(<SecurityEndpoints {...props(api, 'SECURITY_OPS')} />);
    expect(await screen.findByRole('link', { name: 'EP-LHR-GW-01' })).toBeTruthy();
    expect(screen.getByText('19 days left')).toBeTruthy();
    expect(screen.getByText('Not enrolled')).toBeTruthy();
    await user.click(screen.getByText('Create an endpoint and issue an enrolment token'));
    const form = screen.getByRole('form', { name: 'Create endpoint' });
    await user.click(within(form).getByRole('button', { name: 'Create and issue token' }));
    expect(within(form).getByRole('link', { name: 'Choose the endpoint kind' })).toBeTruthy();
    await user.selectOptions(within(form).getByLabelText(/Kind/), 'KIOSK');
    await user.type(within(form).getByLabelText(/^Site/), 'lhr');
    await user.selectOptions(within(form).getByLabelText(/Zone/), 'CLINIC_LAN');
    await user.type(within(form).getByLabelText(/Device instances/), 'dev-000010');
    await user.click(within(form).getByRole('button', { name: 'Create and issue token' }));
    await waitFor(() => expect(api.find('POST', '/device-security/endpoints')[0]?.body).toEqual({ kind: 'KIOSK', siteCode: 'LHR', zone: 'CLINIC_LAN', deviceInstanceIds: ['DEV-000010'] }));
    const box = await screen.findByRole('region', { name: 'Enrolment token for EP-LHR-KSK-02' });
    expect(within(box).getByText('wz-enrol-one-time-token')).toBeTruthy();
    await user.click(within(box).getByRole('button', { name: /I have copied it/ }));
    expect(screen.queryByText('wz-enrol-one-time-token')).toBeNull();
  });

  it('detail: rotate token shows the new token with grace; isolate needs a reason', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /device-security/endpoints/EP-LHR-GW-01': endpoint(),
      'GET /device-security/endpoints/EP-LHR-GW-01/events?limit=100': [event({ type: 'TOKEN_ROTATED', severity: 'INFO' })],
      'POST /device-security/endpoints/EP-LHR-GW-01/rotate-token': { endpointId: 'EP-LHR-GW-01', endpointToken: 'wz-ep-new-token', tokenExpiresAt: '2027-01-30T00:00:00Z', previousTokenValidUntil: '2026-11-01T10:15:00Z' },
      'POST /device-security/endpoints/EP-LHR-GW-01/isolate': { endpointId: 'EP-LHR-GW-01', status: 'ISOLATED', minutesToIsolate: 0.4, incidentRef: 'DSI-2026-000002' },
    });
    render(<SecurityEndpointDetail {...props(api, 'SECURITY_OPS')} endpointId="EP-LHR-GW-01" />);
    expect(await screen.findByText('CN=WZ Dev CA')).toBeTruthy();
    expect(screen.getByText('Token rotated')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Rotate endpoint token' }));
    expect(await screen.findByText('wz-ep-new-token')).toBeTruthy();
    expect(screen.getByText(/previous token stays valid until/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Isolate' }));
    const dlg = screen.getByRole('dialog', { name: 'Isolate EP-LHR-GW-01' });
    await user.click(within(dlg).getByRole('button', { name: 'Isolate now' }));
    expect(within(dlg).getByText('Choose the reason')).toBeTruthy();
    await user.selectOptions(within(dlg).getByLabelText(/Reason/), 'SUSPECTED_COMPROMISE');
    await user.click(within(dlg).getByRole('button', { name: 'Isolate now' }));
    await waitFor(() => expect(api.find('POST', '/device-security/endpoints/EP-LHR-GW-01/isolate')[0]?.body).toEqual({ reasonCode: 'SUSPECTED_COMPROMISE', note: '' }));
    expect(await screen.findByText(/Isolated in 0.4 minutes – incident DSI-2026-000002/)).toBeTruthy();
  });

  it('release from isolation shows that re-certification is required and explains a 409 refusal', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /device-security/endpoints/EP-LHR-GW-01': endpoint({ status: 'ISOLATED', recertificationRequired: true }),
      'GET /device-security/endpoints/EP-LHR-GW-01/events?limit=100': [],
      'POST /device-security/endpoints/EP-LHR-GW-01/release': reply(409, { status: 409, code: 'recertification_required', detail: 'Every device instance of the endpoint must be re-certified in the console before release' }),
    });
    render(<SecurityEndpointDetail {...props(api, 'SECURITY_OPS')} endpointId="EP-LHR-GW-01" />);
    expect(await screen.findByText('Re-certification required.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Rotate endpoint token' })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Release from isolation' }));
    const dlg = screen.getByRole('dialog', { name: 'Release EP-LHR-GW-01' });
    expect((within(dlg).getByLabelText(/Reason/) as HTMLSelectElement).value).toBe('RECERTIFIED');
    await user.click(within(dlg).getByRole('button', { name: 'Release' }));
    expect(await screen.findByText(/Release refused: at least one device instance has not been re-certified/)).toBeTruthy();
  });
});

describe('detection, log and controls', () => {
  it('alerts: acknowledge, open an incident with the drill record', async () => {
    const user = userEvent.setup();
    const alert = { id: 'a1', ruleCode: 'FAILED_AUTH_BURST', severity: 'HIGH', endpointId: 'EP-LHR-GW-01', status: 'OPEN', detectedAt: '2026-10-31T10:00:00Z', occurrences: 31, autoAction: 'ISOLATE', isolatedAt: '2026-10-31T10:04:00Z' };
    const api = mockApi({
      'GET /device-security/alerts?status=OPEN': [alert],
      'GET /device-security/incidents': [{ incidentRef: 'DSI-2026-000001', endpointId: 'EP-LHR-GW-01', status: 'OPEN', minutesToIsolate: 4, withinSla: true, slaMinutes: 15, evidence: [{ type: 'RAW_MESSAGES', sha256: 'e'.repeat(64) }] }],
      'GET /device-security/anomaly-rules': [{ code: 'FAILED_AUTH_BURST', severity: 'HIGH', enabled: true, threshold: 10, windowMinutes: 5, action: 'ISOLATE', isolateThreshold: 30 }],
      'POST /device-security/alerts/a1/acknowledge': { ...alert, status: 'ACKNOWLEDGED' },
      'POST /device-security/incidents': { incidentRef: 'DSI-2026-000002' },
    });
    render(<SecurityAlerts {...props(api, 'SECURITY_OPS')} />);
    expect(within(await screen.findByRole('table', { name: '1 alert' })).getByRole('rowheader', { name: 'Failed auth burst' })).toBeTruthy();
    expect(screen.getByText('4.0 min (SLA 15)')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Acknowledge' }));
    expect(await screen.findByText('Alert acknowledged.')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Open incident' }));
    const dlg = screen.getByRole('dialog');
    await user.type(within(dlg).getByLabelText(/Summary/), 'Credential stuffing from the clinic LAN');
    await user.click(within(dlg).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(api.find('POST', '/device-security/incidents')[0]?.body).toEqual({ endpointId: 'EP-LHR-GW-01', alertIds: ['a1'], summary: 'Credential stuffing from the clinic LAN' }));
  });

  it('event log: filters and verifies the hash chain (intact / broken shown with text)', async () => {
    const user = userEvent.setup();
    let call = 0;
    const api = mockApi({
      'GET /device-security/events?limit=200': [event()],
      'GET /device-security/events?type=ENDPOINT_ISOLATED&limit=200': [event()],
      'GET /device-security/events/verify': () => (++call === 1 ? { intact: true, checked: 412 } : { intact: false, checked: 412, firstBrokenId: 77 }),
    });
    render(<SecurityEvents {...props(api, 'AUDITOR')} />);
    expect(await screen.findByText('Endpoint isolated')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Verify hash chain' }));
    expect(await screen.findByText('Chain intact – 412 events verified')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Verify hash chain' }));
    expect(await screen.findByText('Chain broken at event #77 (412 checked)')).toBeTruthy();
    await user.selectOptions(screen.getByLabelText('Type'), 'ENDPOINT_ISOLATED');
    await user.click(screen.getByRole('button', { name: 'Filter' }));
    await waitFor(() => expect(api.find('GET', '/device-security/events?type=ENDPOINT_ISOLATED')).toHaveLength(1));
  });

  it('zones and conduits with the firewall template export', async () => {
    const api = mockApi({
      'GET /device-security/zones': [{ code: 'DEVICE_VLAN', name: 'Medical device VLAN', defaultPolicy: 'DENY' }],
      'GET /device-security/conduits': [{ id: 'c1', fromZone: 'DEVICE_VLAN', toZone: 'GATEWAY_ZONE', protocol: 'MLLP_TLS', ports: '2575', direction: 'INBOUND', description: 'HL7 from monitors' }],
      'GET /device-security/zone-cidrs': [],
    });
    render(<SecurityZones {...props(api, 'SECURITY_OPS')} />);
    expect(await screen.findByText('HL7 from monitors')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Export firewall template (CSV)' }).getAttribute('href')).toBe('/bff/api/device-security/conduits/export?format=csv');
  });

  it('signing keys: rotate and revoke with a confirmation', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /device-security/signing-keys': [{ keyId: 'wz-k2', algorithm: 'Ed25519', status: 'ACTIVE', publicKey: 'MCowBQYDK2VwAyEA' }, { keyId: 'wz-k1', algorithm: 'Ed25519', status: 'RETIRED' }],
      'POST /device-security/signing-keys': { keyId: 'wz-k3', algorithm: 'Ed25519', status: 'ACTIVE' },
      'POST /device-security/signing-keys/wz-k2/revoke': { keyId: 'wz-k2', status: 'REVOKED' },
    });
    render(<SigningKeys {...props(api, 'SECURITY_OPS')} />);
    expect(await screen.findByText('wz-k2')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Rotate key' }));
    expect(await screen.findByText(/New active key wz-k3/)).toBeTruthy();
    expect(api.find('POST', '/device-security/signing-keys')[0]?.body).toEqual({ algorithm: 'Ed25519' });
    await user.click(screen.getAllByRole('button', { name: 'Revoke' })[0]!);
    const dlg = screen.getByRole('dialog', { name: 'Revoke key wz-k2' });
    await user.click(within(dlg).getByRole('button', { name: 'Revoke key' }));
    await waitFor(() => expect(api.find('POST', '/device-security/signing-keys/wz-k2/revoke')).toHaveLength(1));
  });

  it('secrets: metadata only – the value is never displayed; rotation is write-only', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /device-security/secrets': [{ reference: 'devsec:11111111-2222-3333-4444-555555555555', scope: 'device-configuration:CFG-000008', name: 'oauth2.clientSecretRef', createdAt: '2026-10-01T00:00:00Z', boundInstances: ['DEV-000008'], destroyed: false }],
      'POST /device-security/secrets/devsec%3A11111111-2222-3333-4444-555555555555/rotate': { reference: 'devsec:11111111-2222-3333-4444-555555555555' },
    });
    render(<ConnectorSecrets {...props(api, 'SECURITY_OPS')} />);
    expect(await screen.findByText('Set')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Rotate oauth2.clientSecretRef' }));
    const input = within(screen.getByRole('dialog')).getByLabelText(/New value/) as HTMLInputElement;
    expect(input.type).toBe('password');
    await user.type(input, 'n3w-s3cret');
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Rotate' }));
    await waitFor(() => expect(api.find('POST', '/device-security/secrets/devsec%3A11111111-2222-3333-4444-555555555555/rotate')[0]?.body).toEqual({ value: 'n3w-s3cret' }));
    expect(screen.queryByText('n3w-s3cret')).toBeNull();
  });

  it('assurance: go-live gate, test-pack runs (record a run), pen-test and SDLC records', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      'GET /device-security/go-live-readiness': { ready: false, checks: [{ code: 'NO_OPEN_CRITICAL_PENTEST', passed: false, detail: '1 open critical finding' }, { code: 'TEST_PACK_PASSED', passed: true }] },
      'GET /device-security/test-pack/runs': [{ id: 'r1', version: '1.0.0', environment: 'uat', runBy: 'secops1', runAt: '2026-10-30T10:00:00Z', passed: 13, failed: 1, notRun: 0, results: [{ script: '02_tls_mtls_check.sh', control: 'SC-01', outcome: 'FAIL' }] }],
      'GET /device-security/sdlc': { threatModels: [{ id: 't', adapterOrOption: 'P4', version: '1', reviewedOn: '2026-09-01' }], sbom: { release: '6.0.0', documentId: 'doc', format: 'CycloneDX' }, vulnerabilities: [{ id: 'CVE-2026-1', component: 'x', severity: 'HIGH', dueOn: '2026-10-01', status: 'OPEN', overdue: true }] },
      'GET /device-security/pentest/findings': [{ ref: 'PT-1', severity: 'CRITICAL', title: 'MLLP listener accepts plaintext', status: 'OPEN' }],
      'GET /device-security/responsibility-agreement': { version: '1.0', signedOn: '2026-09-15' },
      'POST /device-security/test-pack/runs': { passed: 1, failed: 0, notRun: 13 },
    });
    render(<SecurityAssurance {...props(api, 'SECURITY_OPS')} />);
    expect(await screen.findByText('Not ready for go-live')).toBeTruthy();
    expect(screen.getByText(/1 open critical finding/)).toBeTruthy();
    expect(screen.getByText('SC-01 (02_tls_mtls_check.sh)')).toBeTruthy();
    expect(await screen.findByText('MLLP listener accepts plaintext')).toBeTruthy();
    expect(screen.getByText('Overdue')).toBeTruthy();
    await user.click(screen.getByText('Record a test-pack run'));
    const form = screen.getByRole('form', { name: 'Record a test-pack run' });
    await user.click(within(form).getByRole('button', { name: 'Record run' }));
    expect(within(form).getByRole('link', { name: 'Enter the test-pack version' })).toBeTruthy();
    await user.type(within(form).getByLabelText(/Test-pack version/), '1.1.0');
    await user.selectOptions(within(form).getByLabelText('Outcome of 02_tls_mtls_check.sh'), 'PASS');
    await user.click(within(form).getByRole('button', { name: 'Record run' }));
    await waitFor(() => expect(api.find('POST', '/device-security/test-pack/runs')).toHaveLength(1));
    const body = api.find('POST', '/device-security/test-pack/runs')[0]!.body as { version: string; results: { script: string; outcome: string }[] };
    expect(body.version).toBe('1.1.0');
    expect(body.results.find((r) => r.script === '02_tls_mtls_check.sh')?.outcome).toBe('PASS');
    expect(await screen.findByText('Run recorded: 1 passed, 0 failed, 13 not run.')).toBeTruthy();
  });
});
