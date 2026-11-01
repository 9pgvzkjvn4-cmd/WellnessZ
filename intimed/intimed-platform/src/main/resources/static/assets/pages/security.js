import { h, clear, get, post, put, del, guard, badge, table, card, kpi, field, input, select, button, modal, pre, fmt, tabs, download } from '../lib.js';
import { has } from '../app.js';

export async function securityPage(main, [sub]) {
  const tab = sub || 'endpoints';
  main.append(h('h1', null, 'Endpoint & integration security'),
    tabs([['endpoints', 'Endpoints & PKI'], ['alerts', 'Anomalies & isolation'], ['audit', 'Audit trail'], ['secrets', 'Secrets'], ['posture', 'Vendor posture'], ['controls', 'Controls SC-01…SC-20']], tab, (t) => { location.hash = '#/security/' + t; }));
  const box = h('div');
  main.append(box);
  await ({ endpoints, alerts, audit, secrets, posture, controls }[tab] || endpoints)(box);
}

async function endpoints(box) {
  const [eps, exp] = await Promise.all([get('/api/security/endpoints'), get('/api/security/endpoints/expiring?days=30')]);
  box.append(h('div', { class: 'kpis' }, kpi('Endpoint identities', eps.length), kpi('Active', eps.filter(e => e.status === 'ACTIVE').length), kpi('Isolated / revoked', eps.filter(e => ['ISOLATED', 'REVOKED'].includes(e.status)).length),
    kpi('Certificates < 30 days', exp.length, 'SC-14')),
    h('div', { class: 'toolbar' }, has('DEVICE_ADMIN', 'SECURITY_ANALYST') ? button('Register endpoint', register) : null,
      button('Download root CA', async () => { const r = await get('/api/security/pki/root'); download('intimed-root-ca.pem', new Blob([r.rootCa], { type: 'application/x-pem-file' })); }, 'ghost')),
    table([{ label: 'Endpoint', key: 'name' }, { label: 'ID', key: 'id' }, { label: 'Type', key: 'type' }, { label: 'Zone', key: 'zone' }, { label: 'Status', render: e => badge(e.status) },
      { label: 'Certificate', render: e => e.certSerial ? h('span', null, h('code', null, e.certSerial.slice(0, 12)), ' until ', fmt(e.notAfter)) : '—' }, { label: 'Last seen', key: 'lastSeen' },
      { label: 'Configs', render: e => (e.configs || []).length }, { label: '', render: e => h('span', null,
        e.status !== 'REVOKED' && e.type !== 'HARNESS' ? button('Rotate', async () => { const r = await guard(() => post('/api/security/endpoints/' + e.id + '/rotation-token')); tokenModal(r.enrolmentToken, e.id); }, 'small ghost') : null,
        e.status === 'ISOLATED' && has('SECURITY_ANALYST') ? button('Release (re-cert gate)', () => guard(() => post('/api/security/endpoints/' + e.id + '/release'), 'Released').then(() => location.reload()), 'small') : null,
        e.status !== 'REVOKED' && e.type !== 'HARNESS' ? button('Revoke', () => { if (confirm('Revoke ' + e.name + '?')) guard(() => post('/api/security/endpoints/' + e.id + '/revoke', { reason: 'manual revocation' }), 'Revoked').then(() => location.reload()); }, 'small danger') : null) }], eps));
}

function register() {
  const name = input({ value: 'Clinic gateway 2' }), type = select(['GATEWAY', 'DEVICE_HOST', 'MOBILE', 'KIOSK', 'VENDOR_CLOUD']), site = input({ value: 'OH clinic' }), zone = input({ value: 'MDZ-OH' });
  modal('Register endpoint identity', h('div', { class: 'form' }, field('Name', name), field('Type', type), field('Site', site), field('Network zone', zone)),
    (close) => [button('Register & issue one-time token', async () => { const r = await guard(() => post('/api/security/endpoints', { name: name.value, type: type.value, site: site.value, zone: zone.value })); close(); tokenModal(r.enrolmentToken, r.endpoint.id); })]);
}

function tokenModal(token, id) {
  modal('One-time enrolment token (shown once, valid 15 minutes)', h('div', null,
    h('p', null, 'On the endpoint (the private key is generated there and never leaves it):'),
    pre('java -jar intimed-platform.jar sdk enrol \\\n  --server https://localhost:9443 \\\n  --token ' + token + ' \\\n  --ca ~/IntiMed-Demo-Data/pki/root-ca.pem --out ./identity-' + id),
    h('p', { class: 'muted small' }, 'Token is stored only as SHA-256, single use; re-use or expiry is refused and audited.')));
}

async function alerts(box) {
  const [al, drill, rules] = await Promise.all([get('/api/security/alerts'), get('/api/security/drill'), get('/api/security/anomaly-rules')]);
  box.append(h('div', { class: 'kpis' }, kpi('Alerts', al.length), kpi('Open', al.filter(a => a.status === 'OPEN').length), kpi('Isolations', drill.isolations, drill.withinTarget + ' within 15 min target')),
    table([{ label: 'Detected', key: 'detectedAt' }, { label: 'Rule', render: a => a.rule + ' ' + a.name }, { label: 'Severity', render: a => badge(a.severity) }, { label: 'Endpoint', key: 'endpoint' },
      { label: 'Scope', render: a => a.scope || 'TENANT' }, { label: 'Status', render: a => badge(a.status) }, { label: 'Time to isolate', render: a => a.timeToIsolateSeconds !== undefined ? a.timeToIsolateSeconds + ' s' : '—' }, { label: 'Detail', key: 'detail' },
      { label: '', render: a => a.status === 'OPEN' && !a.scope && has('SECURITY_ANALYST') ? button('Isolate', () => guard(() => post('/api/security/alerts/' + a.id + '/isolate'), 'Endpoint isolated – connections refused; bound configurations need re-certification').then(() => location.reload()), 'small danger') : '' }], al, { empty: 'No alerts.' }),
    card('Anomaly rules', table([{ label: 'Signal', render: r => r[0] }, { label: 'Rule', render: r => r[1].id }, { label: 'Description', render: r => r[1].name }, { label: 'Threshold', render: r => r[1].threshold + ' / ' + r[1].window },
      { label: 'Severity', render: r => badge(r[1].severity) }, { label: 'Auto-isolate', render: r => r[1].autoIsolate ? 'yes' : 'no' }], Object.entries(rules), { compact: true })));
}

async function audit(box) {
  const [rows, v] = await Promise.all([get('/api/security/audit?limit=200'), get('/api/security/audit/verify').catch(e => ({ error: e.message }))]);
  box.append(h('p', null, 'Hash chain: ', v.error ? v.error : h('span', null, badge(v.valid ? 'INTACT' : 'BROKEN at ' + v.brokenAt, v.valid ? 'ok' : 'bad'), ' ', v.records + ' records')),
    h('p', { class: 'muted small' }, 'Every record is HMAC-chained to its predecessor and forwarded to the SIEM sink (<home>/siem/audit.jsonl). Details are PHI-masked.'),
    table([{ label: '#', key: 'seq' }, { label: 'Time', key: 'ts' }, { label: 'Actor', key: 'actor' }, { label: 'Action', key: 'action' }, { label: 'Target', key: 'target' }, { label: 'Detail', render: r => String(r.detail).slice(0, 140) },
      { label: 'Hash', render: r => h('code', null, r.hash.slice(0, 10)) }], rows, { compact: true }));
}

async function secrets(box) {
  const s = await get('/api/security/secrets');
  const name = input({ placeholder: 'e.g. vendor-webhook' }), value = input({ type: 'password', placeholder: 'value (write-only)' });
  box.append(h('p', null, 'Provider ', badge(s.provider, 'neutral'), ' – values are write-only; configurations hold secret:// references only (SC-05).'),
    h('div', { class: 'toolbar' }, name, value, button('Store', () => guard(() => put('/api/security/secrets/' + encodeURIComponent(name.value), { value: value.value }), 'Stored').then(() => location.reload()))),
    table([{ label: 'Reference', render: n => 'secret://' + n }, { label: '', render: n => has('SECURITY_ANALYST', 'TENANT_ADMIN') ? button('Delete', () => guard(() => del('/api/security/secrets/' + encodeURIComponent(n)), 'Deleted').then(() => location.reload()), 'small ghost') : '' }], s.names));
  const ta = h('textarea', { class: 'in', rows: 4, placeholder: 'Paste a configuration export or text to scan for secrets' });
  box.append(card('Secret scanner', ta, button('Scan', async () => modal('Scan result', pre(await post('/api/security/scan/secrets', ta.value))), 'secondary')));
}

async function posture(box) {
  const rows = await get('/api/security/posture');
  box.append(table([{ label: 'Model', key: 'modelId' }, { label: 'Status', render: p => badge(p.status) }, { label: 'MDS2', render: p => badge(p.mds2) }, { label: 'SBOM', render: p => badge(p.sbom) },
    { label: 'EOL', key: 'eol' }, { label: 'Known vulns', key: 'knownVulnerabilities' }, { label: 'Assessed', render: p => p.assessedBy + ' ' + fmt(p.assessedAt) }], rows, { empty: 'Record posture from the catalogue model view.' }));
}

async function controls(box) {
  const C = [['SC-01', 'Per-endpoint identity + mTLS; plaintext refused', 'LocalPki, DeviceListeners (needClientAuth), XT-13 probes'], ['SC-02', 'Zoning & deny-by-default conduits', 'Endpoint zones; firewall request pre-fill (step 3); reference designs in docs'],
    ['SC-03', 'Hardened gateway / device-host images', 'Deferred – cloud/edge images (see deferred backlog)'], ['SC-04', 'BLE LE Secure Connections; USB allow-list', 'Policy RR-03 blocks Just-Works; P1 usbAllowList'],
    ['SC-05', 'Secrets in vault; none in configs/exports', 'SecretStore (local AES-GCM / HashiCorp Vault), SecretScanner, secret:// refs'], ['SC-06', 'SDLC: SBOM, signed artefacts', 'CycloneDX SBOM per build, Ed25519 signed drivers, CI'],
    ['SC-07', 'Vendor posture gate', 'Posture register; JUSTIFY + security co-approval'], ['SC-08', 'Tamper-evident audit + SIEM, PHI masking', 'HMAC hash chain, SIEM JSONL sink, PhiMasker'],
    ['SC-09', 'Anomaly detection, isolation < 15 min, re-cert gate', 'AnomalyService AN-01…AN-07, isolate/release'], ['SC-10', 'Encryption at rest', 'Per-tenant DEK envelope encryption; H2 AES file cipher'],
    ['SC-11', 'API authN/Z, object-level checks, input validation', 'OAuth2 ES256 JWT scopes, CSRF, CSP, tenant from token'], ['SC-12', 'Robustness against malformed messages', 'Size limits, XXE-safe XML, DLQ, fuzz tests'],
    ['SC-13', 'Rate limiting & quotas', 'Token buckets per tenant/endpoint/login/token'], ['SC-14', 'Time sync & certificate life-cycle', 'Clock-skew correction; < 30 day expiry checks; rotation tokens'],
    ['SC-15', 'Privacy by design', 'Consent for RPM, minimisation, de-identified KB, masking'], ['SC-16', 'Pen test / SOC 2 / ISO 27001', 'Organisational – deferred'],
    ['SC-17', 'Cloud posture', 'Deferred – cloud environments'], ['SC-18', 'Tenant isolation', 'Tenant-partitioned store, per-tenant keys, tests'],
    ['SC-19', 'Recommendation guardrails', 'Never auto-applied in UAT/PROD; provenance; KB de-identified'], ['SC-20', 'Exchange-test publish gate, sealed evidence', 'Publish gate on config hash; Ed25519-sealed packs']];
  box.append(table([{ label: 'Control', render: r => r[0] }, { label: 'Requirement', render: r => r[1] }, { label: 'Implementation (local)', render: r => r[2] }], C),
    h('p', { class: 'muted small' }, 'Run the security test kit: security-test-kit/run-kit.sh (scripts 02, 03, 04, 08, 09, 12, 13, 14, 17, 18, 19 apply locally).'));
}
