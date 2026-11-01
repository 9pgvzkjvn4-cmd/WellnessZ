import { h, clear, get, post, put, del, guard, badge, table, card, kpi, field, input, select, button, modal, pre, fmt, tabs } from '../lib.js';
import { has } from '../app.js';

export async function integrationPage(main, [sub]) {
  const tab = sub || 'clients';
  main.append(h('h1', null, 'Interoperability APIs & SDKs'), tabs([['clients', 'API clients (OAuth2)'], ['webhooks', 'Webhooks & events'], ['fhir', 'FHIR explorer'], ['sdk', 'SDK & integration patterns']], tab, t => { location.hash = '#/integration/' + t; }));
  const box = h('div'); main.append(box);
  await ({ clients, webhooks, fhir, sdk }[tab] || clients)(box);
}

async function clients(box) {
  const [rows, scopes] = await Promise.all([get('/api/integration/clients'), get('/api/integration/scopes')]);
  const name = input({ value: 'host-ehr' });
  const checks = scopes.map(s => h('label', { class: 'chk' }, h('input', { type: 'checkbox', value: s, checked: s.endsWith('.read') && s.startsWith('system/Observation') }), ' ', s));
  box.append(card('Create client (client credentials, ES256 JWT, 15 min)', field('Name', name), h('div', { class: 'checks' }, checks),
    button('Create', async () => { const r = await guard(() => post('/api/integration/clients', { name: name.value, scopes: checks.map(c => c.querySelector('input')).filter(i => i.checked).map(i => i.value) })); modal('Client created – secret shown once', pre(r)); })),
    table([{ label: 'Client ID', key: 'clientId' }, { label: 'Name', key: 'name' }, { label: 'Scopes', render: c => c.scopes.join(' ') }, { label: 'Status', render: c => badge(c.status) }, { label: 'Created', key: 'createdAt' },
      { label: '', render: c => c.status === 'ACTIVE' ? button('Revoke', () => guard(() => del('/api/integration/clients/' + c.clientId), 'Revoked').then(() => location.reload()), 'small ghost') : '' }], rows));
}

async function webhooks(box) {
  const [subs, deliveries, types, inbox, events] = await Promise.all([get('/api/integration/webhooks'), get('/api/integration/deliveries'), get('/api/integration/event-types'), get('/api/integration/host-inbox'), get('/api/integration/events')]);
  const url = input({ value: location.origin + '/api/webhooks/_host/' + (await get('/api/auth/me')).tenant });
  const ev = types.map(t => h('label', { class: 'chk' }, h('input', { type: 'checkbox', value: t, checked: t === 'observation.posted' || t === 'run.completed' }), ' ', t));
  box.append(card('Subscribe (HMAC-SHA256 signed, retries 1/4/16 min, HTTPS + egress allow-list)', field('Endpoint URL', url), h('div', { class: 'checks' }, ev),
    button('Subscribe', async () => { const r = await guard(() => post('/api/integration/webhooks', { url: url.value, events: ev.map(c => c.querySelector('input')).filter(i => i.checked).map(i => i.value) })); modal('Subscription – signing secret shown once', pre(r)); })),
    table([{ label: 'Subscription', key: 'id' }, { label: 'URL', key: 'url' }, { label: 'Events', render: s => s.events.join(' ') }, { label: '', render: s => button('Delete', () => guard(() => del('/api/integration/webhooks/' + s.id)).then(() => location.reload()), 'small ghost') }], subs, { compact: true }),
    card('Deliveries', table([{ label: 'Type', key: 'type' }, { label: 'Status', render: d => badge(d.status) }, { label: 'Attempts', key: 'attempts' }, { label: 'HTTP', key: 'lastStatus' }, { label: 'At', key: 'lastAttemptAt' },
      { label: '', render: d => button('Replay', () => guard(() => post('/api/integration/deliveries/' + d.id + '/replay'), 'Replayed').then(() => location.reload()), 'small ghost') }], deliveries.slice(0, 50), { compact: true })),
    card('Reference host inbox (signature verified by the host)', table([{ label: 'At', key: 'at' }, { label: 'Signature', render: r => badge(r.signatureValid ? 'VALID' : 'INVALID') }, { label: 'Event', render: r => r.event.type + ' ' + JSON.stringify(r.event.data).slice(0, 120) }], inbox.slice(0, 30), { compact: true })),
    card('Event stream (no PHI – ids and statuses only)', table([{ label: 'Time', key: 'time' }, { label: 'Type', key: 'type' }, { label: 'Data', render: e => JSON.stringify(e.data).slice(0, 160) }], events.slice(0, 40), { compact: true })));
}

async function fhir(box) {
  const id = input({ placeholder: 'client id' }), sec = input({ type: 'password', placeholder: 'client secret' }), q = input({ value: '/fhir/r4/Observation?_count=10' });
  const out = h('div');
  box.append(card('Call the FHIR R4 API with your client credentials', h('div', { class: 'form cols' }, field('Client ID', id), field('Client secret', sec), field('Request', q)),
    button('Get token & call', async () => {
      const t = await fetch('/oauth2/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', Authorization: 'Basic ' + btoa(id.value + ':' + sec.value) }, body: 'grant_type=client_credentials' });
      const tok = await t.json();
      if (!t.ok) { clear(out).append(pre(tok)); return; }
      const r = await fetch(q.value, { headers: { Authorization: 'Bearer ' + tok.access_token, Accept: 'application/fhir+json' } });
      clear(out).append(h('p', null, 'HTTP ', badge(String(r.status), r.ok ? 'ok' : 'bad'), ' scope ', tok.scope), pre(await r.text()));
    }), out),
    button('CapabilityStatement', async () => modal('CapabilityStatement', pre(await (await fetch('/fhir/r4/metadata')).json())), 'ghost'));
}

async function sdk(box) {
  box.append(card('Seven integration patterns', table([{ label: 'Pattern', render: r => r[0] }, { label: 'How', render: r => r[1] }, { label: 'In this kit', render: r => r[2] }], [
    ['EP-1 Embedded capture component', '<intimed-capture> web component posts back through the template adapter', '/host-demo/ and Capture page'],
    ['EP-2 FHIR API & Subscriptions', 'Observation/Device search, rest-hook Subscription', '/fhir/r4 (OAuth2 scopes)'],
    ['EP-3 HL7 v2 / IHE DEV', 'ORU^R01 out (adapter), ADT/ORM in over MLLP-TLS', 'MLLP-TLS :2575, HL7V2 adapter'],
    ['EP-4 Webhooks & events', 'HMAC-signed webhooks with retries/replay', 'Webhooks tab'],
    ['EP-5 Template adapter post-back', 'REST JSON / PDF to the host schema', 'REST and PDF adapters'],
    ['EP-6 Sidecar / OEM', 'Engine packaged in a vendor product', 'Same jar, white-label deferred (IM-E09-BL-006)'],
    ['EP-7 Gateway-only edge', 'Gateway CLI with encrypted store-and-forward', 'java -jar … sdk gateway']])),
    card('mdi-sdk CLI', pre(['java -jar intimed-platform.jar sdk keygen --out ./keys', 'java -jar intimed-platform.jar sdk validate my-driver.json', 'java -jar intimed-platform.jar sdk test my-driver.json',
      'java -jar intimed-platform.jar sdk sign --key ./keys/publisher-ed25519.key my-driver.json', 'java -jar intimed-platform.jar sdk enrol --server https://localhost:9443 --token enr_… --ca root-ca.pem --out ./identity',
      'java -jar intimed-platform.jar sdk gateway --identity ./identity --config cfg-… --driver driver.json --interval 10'].join('\n'))));
}

export async function tenantPage(main) {
  const [t, metering, users, analytics, acceptance] = await Promise.all([get('/api/tenant'), get('/api/tenant/metering').catch(() => []), get('/api/tenant/users').catch(() => []),
    get('/api/tenant/analytics').catch(() => ({})), get('/api/catalogue/acceptance').catch(() => ({}))]);
  const settings = h('textarea', { class: 'in', rows: 12 }, JSON.stringify({ networkProfile: t.settings.networkProfile, securityPolicy: t.settings.securityPolicy, unitPolicy: t.settings.unitPolicy, knowledgeBaseOptIn: t.settings.knowledgeBaseOptIn }, null, 2));
  main.append(h('h1', null, 'Tenant & analytics'), h('p', null, h('b', null, t.name), ' · residency ', badge(t.residency, 'neutral'), ' · plan ', t.plan, ' · status ', badge(t.status)),
    h('div', { class: 'kpis' }, kpi('Readings', analytics.observations ?? '—'), kpi('Capture rate', (analytics.captureRatePct ?? '—') + ' %'), kpi('Clinician-verified', analytics.verifiedByClinician ?? '—'),
      kpi('Est. minutes saved', analytics.estimatedMinutesSaved ?? '—'), kpi('Calibration compliance', (analytics.calibrationCompliancePct ?? '—') + ' %')),
    h('div', { class: 'two' },
      card('Tenant context used by configuration intelligence', settings, has('TENANT_ADMIN') ? button('Save settings', () => guard(() => put('/api/tenant/settings', JSON.parse(settings.value)), 'Saved')) : null),
      card('Entitlements & quotas (platform-managed)', pre({ entitlements: t.settings.entitlements, quotas: t.settings.quotas }), h('h4', null, 'Catalogue acceptance policy'), pre(acceptance))),
    card('Metering', table([{ label: 'Day', key: 'day' }, { label: 'Meter', key: 'meter' }, { label: 'Quantity', key: 'qty' }], metering, { compact: true })),
    card('Users', table([{ label: 'Username', key: 'username' }, { label: 'Name', key: 'displayName' }, { label: 'Roles', render: u => u.roles.join(', ') }, { label: 'Locked', render: u => u.locked ? badge('LOCKED', 'bad') : '' }], users, { compact: true })));
}
