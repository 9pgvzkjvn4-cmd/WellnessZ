// End-to-end for increment 6 (E31 device integration engine, E32 device console, E33 module-embedded capture, E34 endpoint &
// integration security) against the running local stack. People log in through Keycloak and the BFF in the four apps (OH
// workspace, manager workspace, employee app, partner portal); devices call the core directly as gateways / agents / tablets
// with the documented demo endpoint credentials (token wzdemo-<endpoint id lower case>-not-a-secret) and the client-certificate
// thumbprint header that the mTLS ingress sets in a real deployment (the dev stack has no mTLS ingress – contract §20).
// Checks: console roles without person data, catalogue of 133 models, the full eight-step wizard with four-eyes certification,
// nurse review and certification PDF, one-time enrolment token, zero-touch gateway enrolment with the dev CA, HL7 ORU + ACK,
// BLE SFLOAT, POCT1-A with the QC-lot gate, GDT export blocked by the lockout then released, unmatched queue, dead letter and
// replay without duplicates, capture from device in the OH form (SSE preview through the BFF, accept, E03 result with
// provenance), OCR / manual attested fallback, restricted breath alcohol (g/210L) for collector and MRO, store-and-forward batch,
// employee summary and wearable consent, manager fleet counts, signed driver packages (tampered rejected), P6 vendor-cloud poll
// against a mock cloud, outbound order with device ACK, historic retrieval de-duplication, decommissioning, isolation →
// quarantine → release refused until re-certification, security event chain, tenant isolation and 401 / 403 semantics.
//
// Usage: npm i -D playwright && node tools/e2e/e2e-increment6.js  (fresh stack – docs/setup/01-Local-MacBook-Pro.md §14).
// Enrolment and the P6 mock cloud need the throw-away dev CA: tools/device/dev-ca.sh init, then start the core with
// WZ_DEVICE_TRUST_ANCHORS=$PWD/tools/device/dev-ca/ca.pem, DEVICE_CLOUD_ALLOWED_HOSTS=localhost and a JVM truststore that holds
// the dev CA (-Djavax.net.ssl.trustStore=…) so that the core accepts the mock cloud on https://localhost.
const { chromium } = require('playwright');
const crypto = require('crypto');
const fs = require('fs');
const https = require('https');
const path = require('path');
const { execFileSync } = require('child_process');

const EMP = process.env.WZ_E2E_EMPLOYEE_URL || 'http://localhost:5173';
const MGR = process.env.WZ_E2E_WORKSPACE_URL || 'http://localhost:5174';
const OH = process.env.WZ_E2E_OH_URL || 'http://localhost:5175';
const PARTNER = process.env.WZ_E2E_PARTNER_URL || 'http://localhost:5176';
const IDP_URL = process.env.WZ_E2E_IDP_URL || 'http://localhost:8180';
const CORE = process.env.WZ_E2E_CORE_URL || 'http://localhost:8080';
const OUT = process.env.WZ_E2E_OUT || __dirname + '/shots';
// the throw-away dev CA of tools/device/dev-ca.sh (git-ignored); the core must trust it (WZ_DEVICE_TRUST_ANCHORS)
const DEV_CA = process.env.WZ_E2E_DEVICE_CA_DIR || path.resolve(__dirname, '../device/dev-ca');
const GOLDEN = process.env.WZ_E2E_GOLDEN_DIR || path.resolve(__dirname, '../../modules/wz-e31-device-engine/src/main/resources/e31/golden');
fs.mkdirSync(OUT, { recursive: true });
const NEWPW = process.env.WZ_E2E_NEW_PASSWORD || 'Wz-Local-Demo-2026!';
const log = [];
const check = (name, ok, detail = '') => log.push(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' – ' + detail : ''}`);
const brief = (r) => `HTTP ${r.status} ${typeof r.json === 'string' ? r.json.slice(0, 160) : JSON.stringify(r.json).slice(0, 160)}`;
const arr = (j) => (Array.isArray(j) ? j : (j && (j.rows || j.items || j.content)) || []);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// 1×1 PNG – stands in for a photo of the device display (OCR fallback)
const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

// --------------------------------------------------------------------------------------------- people (Keycloak + BFF)

async function login(page, user) {
  const app = new URL(page.url()).origin;
  await page.getByRole('button', { name: /Sign in with (your company account|multi-factor authentication)/ }).click();
  await page.waitForURL(new RegExp(new URL(IDP_URL).host));
  for (const pw of ['ChangeMe!123', NEWPW]) {
    await page.fill('#username', user);
    await page.fill('#password', pw);
    await page.click('#kc-login');
    await page.waitForLoadState('networkidle');
    if (await page.locator('#password-new').count()) {
      await page.fill('#password-new', NEWPW);
      await page.fill('#password-confirm', NEWPW);
      await page.click('[type=submit]');
    }
    if (page.url().startsWith(app)) break;
  }
  await page.waitForURL((u) => u.href.startsWith(app), { timeout: 15000 });
}

async function api(page, method, path, body) {
  return page.evaluate(async ([method, path, body]) => {
    const xsrf = document.cookie.split(';').map((c) => c.trim().split('=')).find(([k]) => k === 'XSRF-TOKEN');
    const headers = { Accept: 'application/json' };
    if (xsrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(xsrf[1]);
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const r = await fetch('/bff/api' + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await r.text();
    let json; try { json = JSON.parse(text); } catch { json = text; }
    return { status: r.status, json };
  }, [method, path, body]);
}

/** A binary download through the BFF: status, content type and the first bytes. */
async function download(page, path) {
  return page.evaluate(async (path) => {
    const r = await fetch('/bff/api' + path, { headers: { Accept: 'application/pdf, */*' } });
    const buf = new Uint8Array(await r.arrayBuffer());
    return { status: r.status, type: r.headers.get('content-type') || '', size: buf.length, head: String.fromCharCode(...buf.slice(0, 5)) };
  }, path);
}

/** Multipart upload through the BFF (fields + one file under the given part name). */
async function upload(page, path, fields, base64, fileName, type, part = 'file') {
  return page.evaluate(async ([path, fields, base64, fileName, type, part]) => {
    const xsrf = document.cookie.split(';').map((c) => c.trim().split('=')).find(([k]) => k === 'XSRF-TOKEN');
    const fd = new FormData();
    for (const [k, v] of Object.entries(fields)) fd.append(k, v);
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
    fd.append(part, new Blob([bytes], { type }), fileName);
    const headers = { Accept: 'application/json' };
    if (xsrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(xsrf[1]);
    const r = await fetch('/bff/api' + path, { method: 'POST', headers, body: fd });
    const text = await r.text();
    let json; try { json = JSON.parse(text); } catch { json = text; }
    return { status: r.status, json };
  }, [path, fields, base64, fileName, type, part]);
}

async function session(browser, url, user, viewport = { width: 1440, height: 900 }) {
  const page = await (await browser.newContext({ viewport })).newPage();
  await page.goto(url + '/');
  await login(page, user);
  return page;
}

async function shot(page, name, route, wait = 1800) {
  if (route) {
    await page.goto(route);
    await page.reload(); // a hash-only navigation may keep a mounted screen; reload so it shows fresh data
  }
  await page.waitForTimeout(wait);
  await page.screenshot({ path: `${OUT}/${name}.png` });
}

// --------------------------------------------------------------------------------------------- machines (direct to the core)

// Demo endpoint credentials of R__demo_e34_device_security.sql / E34DemoSeeder – local / test only, documented non-secrets.
const demoToken = (ep) => `wzdemo-${ep.toLowerCase()}-not-a-secret`;
const demoThumb = (ep) => crypto.createHash('sha256').update('wellnessz-demo-certificate:' + ep).digest('hex');
const demo = (ep) => ({ id: ep, token: demoToken(ep), thumbprint: demoThumb(ep) });

/** A machine call: endpoint id, DeviceToken and the thumbprint header the mTLS ingress would forward (null = header omitted). */
async function machine(ep, method, path, body, contentType = 'application/json', extra = {}) {
  const headers = { 'X-Device-Endpoint': ep.id, Authorization: 'DeviceToken ' + ep.token, 'Idempotency-Key': 'E2E6:' + crypto.randomUUID(), ...extra };
  if (ep.thumbprint) headers['X-Client-Cert-Sha256'] = ep.thumbprint;
  if (body !== undefined) headers['Content-Type'] = contentType;
  for (const [k, v] of Object.entries(headers)) if (v === null) delete headers[k];
  const r = await fetch(`${CORE}/api/v1${path}`, { method, headers, body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body) });
  const text = await r.text();
  let json; try { json = JSON.parse(text); } catch { json = text; }
  return { status: r.status, json, text };
}

async function anonymous(method, path, headers = {}) {
  const r = await fetch(`${CORE}/api/v1${path}`, { method, headers });
  const text = await r.text();
  let json; try { json = JSON.parse(text); } catch { json = text; }
  return { status: r.status, json };
}

const heartbeat = (ep, devices = []) => machine(ep, 'POST', '/device-ingest/heartbeat', { gatewayVersion: '0.6.0', uptimeSeconds: 120,
  queue: { depth: 0, capacityPct: 0 }, devices: devices.map((d) => ({ deviceInstanceId: d, lastSeenAt: new Date().toISOString(), state: 'CONNECTED' })),
  hardening: { baseline: 'CIS-L1-2026.1', status: 'PASS' } });

const golden = (p, enc = 'utf8') => fs.readFileSync(path.join(GOLDEN, p), enc);
const hl7Now = () => new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
/** The golden PCD-01 message measured now, for an employee, with a unique control id. */
function oru(employeeId = 'E1001', sys = 124, dia = 78) {
  const id = 'E2E' + crypto.randomBytes(4).toString('hex').toUpperCase();
  return golden('vitals/csm-pcd01/normal.hl7', 'latin1').replace(/20261030091405/g, hl7Now()).replace(/MSG0001/g, id)
    .replace('E1001^^^WZ^PI', `${employeeId}^^^WZ^PI`).replace('|124|266016', `|${sys}|266016`).replace('|78|266016', `|${dia}|266016`);
}

/** Bluetooth Blood Pressure Measurement (0x2A35) with IEEE-11073 SFLOAT values and the current time stamp. */
function bpMeasurement(systolic10, diastolic, map) {
  const sfloat = (mantissa, exponent) => (((exponent & 0xf) << 12) | (mantissa & 0x0fff)) & 0xffff;
  const now = new Date();
  const b = Buffer.alloc(16);
  b.writeUInt8(0x06, 0); // flags: mmHg, time stamp, pulse rate
  b.writeUInt16LE(sfloat(systolic10, -1), 1); // e.g. 1184 × 10^-1 = 118.4 mm[Hg]
  b.writeUInt16LE(sfloat(diastolic, 0), 3);
  b.writeUInt16LE(sfloat(map, 0), 5);
  b.writeUInt16LE(now.getUTCFullYear(), 7);
  b.writeUInt8(now.getUTCMonth() + 1, 9);
  b.writeUInt8(now.getUTCDate(), 10);
  b.writeUInt8(now.getUTCHours(), 11);
  b.writeUInt8(now.getUTCMinutes(), 12);
  b.writeUInt8(now.getUTCSeconds(), 13);
  b.writeUInt16LE(sfloat(66, 0), 14); // pulse 66 /min
  return b.toString('hex').toUpperCase();
}
const bleBody = (hex) => JSON.stringify({ records: [{ peripheral: { addressHash: 'a3f1c9e0b2d4'.repeat(5) + 'a3f1', name: 'BLE-DEVICE', bondId: 'bond-01' },
  service: '0x1810', characteristic: '0x2A35', encoding: 'HEX', value: hex, receivedAt: new Date().toISOString() }] });

/** GDT 3.x spirometry export (EasyOne Connect) for an employee, measured now. */
function gdtNow(employeeId) {
  const d = new Date();
  const p2 = (n) => String(n).padStart(2, '0');
  const date = p2(d.getUTCDate()) + p2(d.getUTCMonth() + 1) + d.getUTCFullYear();
  const time = p2(d.getUTCHours()) + p2(d.getUTCMinutes()) + p2(d.getUTCSeconds());
  const line = (field, value) => { const s = field + value; return String(s.length + 5).padStart(3, '0') + s + '\r\n'; };
  return golden('spirometry/easyone-gdt/normal.gdt', 'latin1').split('\r\n').filter(Boolean).map((l) => {
    const field = l.slice(3, 7);
    if (field === '3000') return line('3000', employeeId);
    if (field === '6200') return line('6200', date);
    if (field === '6201') return line('6201', time);
    return l + '\r\n';
  }).join('');
}

// --------------------------------------------------------------------------------------------- dev CA (enrolment, mock cloud)

function devClient(name) {
  if (!fs.existsSync(path.join(DEV_CA, 'ca.key'))) return null;
  execFileSync(path.resolve(DEV_CA, '../dev-ca.sh'), ['client', name], { stdio: 'ignore' });
  const pem = fs.readFileSync(path.join(DEV_CA, name + '.pem'), 'utf8');
  const key = fs.readFileSync(path.join(DEV_CA, name + '.key'), 'utf8');
  const thumbprint = crypto.createHash('sha256').update(new crypto.X509Certificate(pem).raw).digest('hex');
  return { pem, key, thumbprint, sign: (msg) => crypto.sign('sha256', Buffer.from(msg), key).toString('base64') };
}

async function enrol(client, enrolmentToken, kind = 'GATEWAY', thumbprint = client.thumbprint) {
  const body = { enrolmentToken, endpointKind: kind, certificatePem: client.pem, proof: client.sign(`enrol:${enrolmentToken}:${client.thumbprint}`),
    hostname: 'e2e6-gw.test.local', platform: { os: 'Linux', osVersion: 'Debian 12', agentVersion: '0.6.0' }, hardening: { baseline: 'CIS-L1-2026.1', status: 'PASS' } };
  const r = await fetch(`${CORE}/api/v1/device-enrolment/enrol`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Client-Cert-Sha256': thumbprint }, body: JSON.stringify(body) });
  const text = await r.text();
  let json; try { json = JSON.parse(text); } catch { json = text; }
  return { status: r.status, json };
}

/** Mock vendor cloud (OAuth2 client credentials + paged daily summaries) on https://localhost with a dev-CA server certificate. */
function mockCloud() {
  if (!fs.existsSync(path.join(DEV_CA, 'ca.key'))) return null;
  const dir = fs.mkdtempSync(path.join(require('os').tmpdir(), 'wz-e2e6-cloud-'));
  const key = path.join(dir, 'server.key'), csr = path.join(dir, 'server.csr'), crt = path.join(dir, 'server.pem'), ext = path.join(dir, 'server.ext');
  execFileSync('openssl', ['ecparam', '-name', 'prime256v1', '-genkey', '-noout', '-out', key]);
  execFileSync('openssl', ['req', '-new', '-key', key, '-subj', '/O=WellnessZ Dev/CN=localhost', '-out', csr]);
  fs.writeFileSync(ext, 'basicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n');
  execFileSync('openssl', ['x509', '-req', '-in', csr, '-CA', path.join(DEV_CA, 'ca.pem'), '-CAkey', path.join(DEV_CA, 'ca.key'), '-CAcreateserial', '-days', '2',
    '-sha256', '-extfile', ext, '-out', crt], { stdio: 'ignore' });
  const state = { tokenCalls: 0, pageCalls: 0, basic: 'Basic ' + Buffer.from('wz-e2e6-client:e2e6-cloud-secret').toString('base64') };
  const day = new Date(Date.now() - 86400000).toISOString().slice(0, 10);
  const records = [
    { id: 'e2e6-r1', subject: { employeeNumber: 'E1001' }, day: { start: `${day}T00:00:00Z`, end: `${day}T23:59:59Z` }, summary: { steps: 9120, sleepMinutes: 431 } },
    { id: 'e2e6-r2', subject: { employeeNumber: 'E1002' }, day: { start: `${day}T00:00:00Z`, end: `${day}T23:59:58Z` }, summary: { steps: 4021, sleepMinutes: 380 } },
  ];
  const server = https.createServer({ key: fs.readFileSync(key), cert: fs.readFileSync(crt) }, (req, res) => {
    const u = new URL(req.url, 'https://localhost');
    if (u.pathname === '/oauth/token' && req.method === 'POST') {
      state.tokenCalls++;
      if (req.headers.authorization !== state.basic) { res.writeHead(401, { 'Content-Type': 'application/json' }); return res.end('{"error":"invalid_client"}'); }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ access_token: 'e2e6-access-token', token_type: 'Bearer', expires_in: 3600 }));
    }
    if (u.pathname === '/v1/daily-summaries') {
      state.pageCalls++;
      if (req.headers.authorization !== 'Bearer e2e6-access-token') { res.writeHead(401); return res.end(); }
      const since = u.searchParams.get('since');
      const items = since ? records.filter((r) => r.day.end >= since) : records; // the vendor repeats the record at the mark
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ items, nextCursor: null }));
    }
    res.writeHead(404); res.end();
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({ server, state, base: `https://localhost:${server.address().port}` })));
}

// demo data (E32DemoSeeder / E34DemoSeeder / R__demo_e10_*.sql)
const GW = demo('EP-LHR-GW-01');
const AGT = demo('EP-LHR-AGT-01');
const TAB = demo('EP-LHR-TAB-01');
const KSK = demo('EP-LHR-KSK-01');
const DA_TEST = 'e10de000-0000-4000-b000-000000000005'; // post-incident test for E1004 with ALCOHOL_BREATH, dispatched
const VITALS = 'E03.DEVICE_RESULT.VITALS';

(async () => {
  const browser = await chromium.launch();
  const run = crypto.randomBytes(3).toString('hex');
  const cloud = await mockCloud();

  // ======================================================================= roles: console users hold no person data
  const admin = await session(browser, OH, 'deviceadmin1');
  const meAdmin = await api(admin, 'GET', '/me');
  const chart = await api(admin, 'GET', '/employees/E1001/results');
  await admin.goto(`${OH}/#/devices/catalogue`);
  await admin.waitForTimeout(2000);
  const nav = await admin.locator('nav').first().innerText().catch(() => '');
  check('deviceadmin1 logs into the OH workspace: /me maxDisclosure NONE, a Devices menu and no chart access (403 / 404)',
    meAdmin.status === 200 && meAdmin.json.maxDisclosure === 'NONE' && /Devices/i.test(nav) && [403, 404].includes(chart.status),
    `roles=${(meAdmin.json.roles || []).join(',')} disclosure=${meAdmin.json.maxDisclosure} chart → ${chart.status}`);

  // ======================================================================= E32 catalogue
  const families = await api(admin, 'GET', '/device-catalogue/families');
  const models = await api(admin, 'GET', '/device-catalogue/models?size=500');
  const md001 = await api(admin, 'GET', '/device-catalogue/models/MD-001');
  const total = arr(families.json).reduce((s, f) => s + (f.models || 0), 0);
  const supported = (md001.json.options || []).filter((o) => o.supported).map((o) => o.option);
  check('catalogue lists 133 models in 11 families; MD-001 offers P1–P4 with its regulatory register (FDA cleared, EU / UK rows)',
    total === 133 && arr(families.json).length === 11 && arr(models.json).length === 133 && ['P1', 'P2', 'P3', 'P4'].every((o) => supported.includes(o))
    && (md001.json.regulatory || []).some((r) => r.market === 'US' && r.status === 'CLEARED') && (md001.json.regulatory || []).length >= 3,
    `families=${arr(families.json).length} models=${total}/${arr(models.json).length} MD-001 options=${supported.join(',')} regulatory=${(md001.json.regulatory || []).map((r) => r.market + ':' + r.status).join(' ')}`);
  await shot(admin, 'inc6-oh-01-catalogue', `${OH}/#/devices/catalogue`, 2500);
  await shot(admin, 'inc6-oh-02-catalogue-model', `${OH}/#/devices/catalogue/MD-001`, 2500);

  // ======================================================================= E34: new gateway identity, one-time token, enrolment
  const secops = await session(browser, OH, 'secops1');
  const created = await api(secops, 'POST', '/device-security/endpoints', { kind: 'GATEWAY', siteCode: 'LHR' });
  const newEp = created.json.endpointId;
  const reread = newEp ? await api(secops, 'GET', `/device-security/endpoints/${newEp}`) : { status: 0, json: {} };
  check('SECURITY_OPS creates a gateway identity: a one-time enrolment token is returned once and never shown again on reload',
    created.status === 201 && /^EP-LHR-GW-\d+$/.test(newEp || '') && (created.json.enrolmentToken || '').length >= 20 && reread.status === 200
    && reread.json.status === 'PENDING_ENROLMENT' && !JSON.stringify(reread.json).includes(created.json.enrolmentToken),
    `${newEp} token=${created.json.enrolmentToken ? 'returned once' : 'missing'} reload status=${reread.json.status} token in reload=${JSON.stringify(reread.json).includes(created.json.enrolmentToken || '#')}`);

  const client = devClient(`e2e6-gw-${run}`);
  let gw = null;
  if (client) {
    const wrongThumb = await enrol(client, created.json.enrolmentToken, 'GATEWAY', 'ab'.repeat(32));
    const enrolled = await enrol(client, created.json.enrolmentToken);
    const replay = await enrol(client, created.json.enrolmentToken);
    gw = { id: newEp, token: enrolled.json.endpointToken, thumbprint: client.thumbprint };
    const hb = await heartbeat(gw);
    check('zero-touch enrolment with the dev-CA certificate (proof of possession): wrong thumbprint refused, endpoint token issued, the enrolment token is one-time; heartbeat returns configVersion',
      wrongThumb.status >= 400 && enrolled.status === 201 && !!gw.token && replay.status >= 400 && hb.status === 200 && 'configVersion' in hb.json,
      `wrong thumbprint → ${wrongThumb.status} ${wrongThumb.json.code}; enrol → ${enrolled.status} ${enrolled.json.status || ''}; replay → ${replay.status} ${replay.json.code}; heartbeat configVersion=${hb.json.configVersion}`);
  } else {
    check('zero-touch enrolment with the dev-CA certificate', false, `no dev CA in ${DEV_CA} – run tools/device/dev-ca.sh init and start the core with WZ_DEVICE_TRUST_ANCHORS`);
  }
  await shot(secops, 'inc6-oh-03-security-endpoints', `${OH}/#/security/devices`, 2500);

  // ======================================================================= E34 on /device-ingest: both factors, fail closed
  const target = gw || GW;
  const noThumb = await machine({ ...target, thumbprint: null }, 'POST', '/device-ingest/hl7v2', oru(), 'application/hl7-v2');
  const badToken = await machine({ ...target, token: 'not-the-token' }, 'POST', '/device-ingest/hl7v2', oru(), 'application/hl7-v2');
  await sleep(500);
  const events = await api(secops, 'GET', `/device-security/endpoints/${target.id}/events`);
  const authFailed = arr(events.json).filter((e) => e.type === 'ENDPOINT_AUTH_FAILED').map((e) => e.reasonCode || (e.attributes || {}).reasonCode);
  check('ingest without the client-certificate thumbprint → 401 mtls_required, wrong token → 401 bad_token; ENDPOINT_AUTH_FAILED events visible to secops1, PHI-free',
    noThumb.status === 401 && noThumb.json.code === 'mtls_required' && badToken.status === 401 && badToken.json.code === 'bad_token'
    && authFailed.length >= 2 && !JSON.stringify(events.json).includes('E1001'),
    `no thumbprint → ${noThumb.status} ${noThumb.json.code}; bad token → ${badToken.status} ${badToken.json.code}; events=${authFailed.join(',')}`);

  // ======================================================================= E33 capture from device in the OH form (UI, SSE through the BFF)
  const nurse = await session(browser, OH, 'nurse1');
  await nurse.goto(`${OH}/#/employees/E1001/chart/results`);
  await nurse.getByRole('button', { name: 'Capture from device' }).click();
  const station = nurse.getByRole('combobox', { name: 'Capture station' });
  await Promise.race([station.waitFor({ timeout: 10000 }), nurse.getByRole('list', { name: 'Devices at this station' }).waitFor({ timeout: 10000 })]).catch(() => {});
  if (await station.count()) {
    await station.selectOption({ value: 'LHR-OH-ROOM-1' });
    await nurse.getByRole('button', { name: 'Use this station' }).click();
  }
  const list = nurse.getByRole('list', { name: 'Devices at this station' });
  await list.waitFor({ timeout: 10000 });
  const listText = await list.innerText();
  await shot(nurse, 'inc6-oh-04-capture-devices');
  // the session the panel opens (its own POST through the BFF)
  const opened = nurse.waitForResponse((r) => r.request().method() === 'POST' && /\/bff\/api\/device-capture\/sessions$/.test(r.url()), { timeout: 10000 });
  await list.getByRole('listitem').filter({ hasText: 'Connex' }).getByRole('button', { name: 'Start capture' }).click();
  const sid = await opened.then((r) => r.json()).then((j) => j.sessionId).catch(() => undefined);
  await nurse.getByText('Waiting for the device…').waitFor({ timeout: 10000 });
  // an EventSource in the browser, same origin through the Vite proxy and the BFF – the stream the panel uses
  if (sid) {
    await nurse.evaluate((sid) => {
      window.__sse = [];
      const es = new EventSource(`/bff/api/device-capture/sessions/${sid}/events`);
      for (const t of ['state', 'preview', 'progress']) es.addEventListener(t, (e) => window.__sse.push({ t, at: Date.now(), data: e.data }));
      window.__es = es;
    }, sid);
    await sleep(700);
  }
  await shot(nurse, 'inc6-oh-05-capture-listening');
  const key8 = 'E2E6:' + crypto.randomUUID();
  const msg8 = oru('E1001');
  const t0 = Date.now();
  const ack = await machine(GW, 'POST', '/device-ingest/hl7v2', msg8, 'application/hl7-v2', { 'X-Device-Instance': 'DEV-000003', 'Idempotency-Key': key8 });
  let previewMs = -1;
  try {
    await nurse.getByText('Reading received – check and accept').waitFor({ timeout: 5000 });
    previewMs = Date.now() - t0;
  } catch { /* measured below */ }
  const sse = sid ? await nurse.evaluate(() => window.__sse || []) : [];
  const ssePreview = sse.find((e) => e.t === 'preview');
  await shot(nurse, 'inc6-oh-06-capture-preview');
  const view = sid ? await api(nurse, 'GET', `/device-capture/sessions/${sid}`) : { json: {} };
  const pv = view.json.preview || {};
  check('HL7 ORU (PCD-01) from the gateway → MSA|AA; the preview with values, units and quality flags reaches the form within 5 s over SSE through the BFF',
    ack.status === 200 && /MSA\|AA/.test(ack.text) && previewMs >= 0 && previewMs <= 5000 && !!ssePreview && (pv.values || []).length >= 2
    && (pv.values || []).filter((v) => typeof v.value === 'number').every((v) => !!v.unit) && Array.isArray(pv.flags),
    `ACK ${ack.status} ${(ack.text.match(/MSA\|[A-Z]{2}/) || [''])[0]}; panel preview after ${previewMs} ms; SSE events=${sse.map((e) => e.t).join(',')} (preview +${ssePreview ? ssePreview.at - t0 : '-'} ms); values=${(pv.values || []).map((v) => `${v.key || v.label}=${v.value} ${v.unit || ''}`).join(', ')}`);
  const warnBoxes = nurse.getByLabel('I have reviewed this warning');
  for (let i = 0; i < await warnBoxes.count(); i++) await warnBoxes.nth(i).check();
  await nurse.getByRole('button', { name: 'Accept and file' }).click();
  await nurse.getByText(/Path used:/).waitFor({ timeout: 15000 }).catch(() => {});
  await shot(nurse, 'inc6-oh-07-capture-accepted');
  if (sid) await nurse.evaluate(() => window.__es && window.__es.close());
  const results = await api(nurse, 'GET', '/employees/E1001/results');
  const filed = arr(results.json).filter((r) => r.source === 'DEVICE' && r.deviceObservationId);
  const res8 = filed.find((r) => r.captureMethod === 'DEVICE') || filed[0] || {};
  const prov = res8.deviceObservationId ? await api(nurse, 'GET', `/device-engine/observations/${res8.deviceObservationId}`) : { status: 0, json: {} };
  check('accept files an E03 result with the device badge and provenance: source DEVICE, capture_method DEVICE, observation id, driver version, verifier',
    !!res8.id && res8.source === 'DEVICE' && res8.captureMethod === 'DEVICE' && /csm-pcd01@/.test(res8.driverVersion || '') && res8.verifiedBy === 'nurse1'
    && prov.status === 200 && JSON.stringify(prov.json).includes('DEV-000003'),
    `result ${res8.category} source=${res8.source} capture=${res8.captureMethod} driver=${res8.driverVersion} verifiedBy=${res8.verifiedBy} observation → ${prov.status}`);
  await shot(nurse, 'inc6-oh-08-results-device-badge', `${OH}/#/employees/E1001/chart/results`, 2500);

  // the same message again with the same Idempotency-Key: DUPLICATE, no second result
  const dup = await machine(GW, 'POST', '/device-ingest/hl7v2', msg8, 'application/hl7-v2', { 'X-Device-Instance': 'DEV-000003', 'Idempotency-Key': key8 });
  const results2 = await api(nurse, 'GET', '/employees/E1001/results');
  const sameObs = arr(results2.json).filter((r) => r.deviceObservationId === res8.deviceObservationId).length;
  check('re-posting the same message (same Idempotency-Key) → DUPLICATE, no second E03 result',
    dup.status === 200 && /MSA\|AA/.test(dup.text) && sameObs === 1 && arr(results2.json).length === arr(results.json).length,
    `re-post → ${dup.status} ${(dup.text.match(/MSA\|[A-Z]{2}/) || [''])[0]} results with that observation=${sameObs} total ${arr(results.json).length}→${arr(results2.json).length}`);

  // ======================================================================= BLE (SFLOAT) from the tablet into a capture session
  const openSession = (page, body) => api(page, 'POST', '/device-capture/sessions', { formVersion: '1', siteCode: 'LHR', ...body });
  const waitPreview = async (page, id, tries = 20) => {
    for (let i = 0; i < tries; i++) {
      const s = await api(page, 'GET', `/device-capture/sessions/${id}`);
      if (s.json.state && s.json.state !== 'LISTENING') return s;
      await sleep(250);
    }
    return api(page, 'GET', `/device-capture/sessions/${id}`);
  };
  const warnCodes = (s) => ((s.json.preview || {}).flags || []).filter((f) => f.outcome === 'WARN').map((f) => f.code);
  const bleS = await openSession(nurse, { formCode: VITALS, employeeId: 'E1003', deviceInstanceId: 'DEV-000004', stationCode: 'LHR-OH-ROOM-1' });
  const ble = await machine(TAB, 'POST', '/device-ingest/ble', bleBody(bpMeasurement(1184, 76, 90)), 'application/json', { 'X-Device-Instance': 'DEV-000004' });
  const bleV = bleS.json.sessionId ? await waitPreview(nurse, bleS.json.sessionId) : { json: {} };
  const sys = ((bleV.json.preview || {}).values || []).find((v) => /systolic/i.test(v.key || v.label || ''));
  const bleAcc = bleS.json.sessionId ? await api(nurse, 'POST', `/device-capture/sessions/${bleS.json.sessionId}/accept`, { acknowledgedFlags: warnCodes(bleV) }) : { status: 0, json: {} };
  check('BLE Blood Pressure Measurement with IEEE-11073 SFLOAT values (118.4 = 1184 × 10⁻¹) from the tablet → preview in the session → accepted into E03',
    ble.status === 202 && bleV.json.state === 'PREVIEW' && sys && Math.abs(Number(sys.value) - 118.4) < 0.05 && bleAcc.status === 200 && bleAcc.json.state === 'ACCEPTED',
    `ingest → ${ble.status}; preview ${bleV.json.state} systolic=${sys && sys.value} ${sys && sys.unit}; accept → ${bleAcc.json.state || brief(bleAcc)}`);

  // ======================================================================= POCT1-A with the QC-lot gate
  const poctMsg = (lot, control) => golden('poc/afinion2-poct1a/normal.xml').replace(/2026-10-30T09:55:00\+00:00/g, new Date().toISOString().slice(0, 19) + '+00:00')
    .replace('AF-LOT-2026-10', lot).replace('V="1001"', `V="${control}"`).replace('V="E1001"', 'V="E1002"');
  const lot = `AF-LOT-E2E-${run}`;
  const pocS1 = await openSession(nurse, { formCode: 'E03.DEVICE_RESULT.POC', employeeId: 'E1002', deviceInstanceId: 'DEV-000006', stationCode: 'LHR-OH-ROOM-2' });
  const poc1 = await machine(GW, 'POST', '/device-ingest/poct1a', poctMsg(lot, '2101'), 'application/xml', { 'X-Device-Instance': 'DEV-000006' });
  const pocV1 = pocS1.json.sessionId ? await waitPreview(nurse, pocS1.json.sessionId) : { json: {} };
  const lotFlag1 = ((pocV1.json.preview || {}).flags || []).find((f) => f.gate === 'QC_LOT' || /qc_lot|qc_status/.test(f.code)) || {};
  // re-test in the same session (RETEST returns it to LISTENING) after the QC run for the new lot
  const retest = pocS1.json.sessionId ? await api(nurse, 'POST', `/device-capture/sessions/${pocS1.json.sessionId}/reject`, { reasonCode: 'RETEST', note: 'QC for the new lot first' }) : { json: {} };
  const qc = await api(nurse, 'POST', '/device-calibration/checks', { deviceInstanceId: 'DEV-000006', checkType: 'POC_QC_LOT', outcome: 'PASS', lot,
    lotExpiry: new Date(Date.now() + 90 * 86400000).toISOString().slice(0, 10), values: { level1: 'IN_RANGE', level2: 'IN_RANGE' } });
  const pocS2 = retest.json.state === 'LISTENING' ? { json: { sessionId: pocS1.json.sessionId } }
    : await openSession(nurse, { formCode: 'E03.DEVICE_RESULT.POC', employeeId: 'E1002', deviceInstanceId: 'DEV-000006', stationCode: 'LHR-OH-ROOM-2' });
  const poc2 = await machine(GW, 'POST', '/device-ingest/poct1a', poctMsg(lot, '2102'), 'application/xml', { 'X-Device-Instance': 'DEV-000006' });
  const pocV2 = pocS2.json.sessionId ? await waitPreview(nurse, pocS2.json.sessionId) : { json: {} };
  const lotFlag2 = ((pocV2.json.preview || {}).flags || []).find((f) => f.gate === 'QC_LOT' || /qc_lot/.test(f.code)) || {};
  const pocAcc = pocS2.json.sessionId ? await api(nurse, 'POST', `/device-capture/sessions/${pocS2.json.sessionId}/accept`, { acknowledgedFlags: warnCodes(pocV2) }) : { status: 0, json: {} };
  check('POCT1-A from the Afinion data manager → ACK AA; a reagent lot without a passing QC run is held by the QC_LOT gate, the QC run releases it and the HbA1c is filed',
    /ACK.type_cd V="AA"/.test(poc1.text) && lotFlag1.outcome && lotFlag1.outcome !== 'PASS' && (pocV1.json.preview || {}).acceptEnabled !== true
    && (qc.status === 201 || qc.status === 200) && lotFlag2.outcome === 'PASS' && pocAcc.status === 200 && pocAcc.json.state === 'ACCEPTED',
    `lot ${lot}: before QC ${lotFlag1.code}/${lotFlag1.outcome} acceptEnabled=${(pocV1.json.preview || {}).acceptEnabled}; QC run → ${qc.status}; after ${lotFlag2.code}/${lotFlag2.outcome}; accept → ${pocAcc.json.state || brief(pocAcc)}`);

  // ======================================================================= GDT export (Device Host agent): lockout, fallback, release
  const spiroForm = 'E03.DEVICE_RESULT.SPIROMETRY';
  const lockedS = await openSession(nurse, { formCode: spiroForm, employeeId: 'E1002', deviceInstanceId: 'DEV-000001', stationCode: 'LHR-OH-ROOM-2' });
  const gdt1 = await machine(AGT, 'POST', '/device-ingest/files', gdtNow('E1002'), 'text/plain; charset=ISO-8859-1',
    { 'X-Device-Instance': 'DEV-000001', 'X-File-Name': `E1002_${run}_1.gdt`, 'X-File-Format': 'GDT' });
  const blockedObs = arr(gdt1.json.observationIds)[0];
  const blocked = blockedObs ? await api(nurse, 'GET', `/device-engine/observations/${blockedObs}`) : { json: {} };
  const lockFlag = (blocked.json.qualityFlags || blocked.json.flags || []).find((f) => /qc_due|device_out_of_calibration/.test(f.code)) || {};
  check('vendor export (GDT) from the Device Host agent while the spirometer is locked out (3-L syringe check due): the session offers the fall-back chain, the reading is BLOCKED with the lockout message',
    lockedS.json.state === 'FALLBACK_OFFERED' && lockedS.json.fallbackReason === 'DEVICE_LOCKED' && gdt1.status === 202 && blocked.json.status === 'BLOCKED' && !!lockFlag.code,
    `session ${lockedS.json.state}/${lockedS.json.fallbackReason}; GDT → ${gdt1.status} observation ${blocked.json.status} ${lockFlag.code}: ${(lockFlag.message || '').slice(0, 90)}`);

  // fall-back while locked: photo of the display (OCR, verified) – capture_method OCR_VERIFIED
  const img = await upload(nurse, '/device-capture/ocr', { deviceInstanceId: 'DEV-000001' }, PNG, 'display.png', 'image/png', 'image');
  const ocr = img.json.imageArchiveId ? await api(nurse, 'POST', `/device-capture/sessions/${lockedS.json.sessionId}/fallback`, { path: 'OCR_VERIFIED', reasonCode: 'DEVICE_LOCKED',
    imageArchiveId: img.json.imageArchiveId, ocrConfidence: 0.95, values: { fev1L: 3.71, fvcL: 4.66, fev1FvcRatio: 79.6, atsErsGrade: 'A' } }) : { status: 0, json: {} };
  const ocrAcc = ocr.status === 200 ? await api(nurse, 'POST', `/device-capture/sessions/${lockedS.json.sessionId}/accept`, { acknowledgedFlags: warnCodes({ json: ocr.json }) }) : { status: 0, json: {} };
  const resE1002 = await api(nurse, 'GET', '/employees/E1002/results');
  const ocrRes = arr(resE1002.json).find((r) => r.captureMethod === 'OCR_VERIFIED');
  check('fall-back while the device is locked: photo of the display stored encrypted, values typed and verified → E03 result capture_method OCR_VERIFIED',
    img.status === 201 && ocr.status === 200 && ocr.json.path === 'OCR_VERIFIED' && ocrAcc.status === 200 && !!ocrRes,
    `upload → ${img.status}; fallback → ${ocr.json.state || brief(ocr)} marking=${(ocr.json.preview || {}).marking}; accept → ${ocrAcc.json.state || brief(ocrAcc)}; result ${ocrRes ? ocrRes.category + ' ' + ocrRes.captureMethod : 'missing'}`);

  // quick QC entry PASS releases the lockout; the re-test through the agent is accepted
  const syringe = await api(nurse, 'POST', '/device-calibration/checks', { deviceInstanceId: 'DEV-000001', checkType: 'SPIRO_SYRINGE_3L', outcome: 'PASS', values: { volumeL: 3.01 } });
  const devs = await api(nurse, 'GET', `/device-capture/devices?formCode=${spiroForm}&siteCode=LHR&stationCode=LHR-OH-ROOM-2`);
  const spiroDev = arr(devs.json).find((d) => d.deviceInstanceId === 'DEV-000001') || {};
  const retestS = await openSession(nurse, { formCode: spiroForm, employeeId: 'E1002', deviceInstanceId: 'DEV-000001', stationCode: 'LHR-OH-ROOM-2' });
  const gdt2 = await machine(AGT, 'POST', '/device-ingest/files', gdtNow('E1002'), 'text/plain; charset=ISO-8859-1',
    { 'X-Device-Instance': 'DEV-000001', 'X-File-Name': `E1002_${run}_2.gdt`, 'X-File-Format': 'GDT' });
  const retestV = retestS.json.sessionId ? await waitPreview(nurse, retestS.json.sessionId) : { json: {} };
  const retestAcc = retestS.json.sessionId ? await api(nurse, 'POST', `/device-capture/sessions/${retestS.json.sessionId}/accept`, { acknowledgedFlags: warnCodes(retestV) }) : { status: 0, json: {} };
  check('quick QC entry (3-L syringe PASS) releases the lockout: the device is AVAILABLE again and the GDT re-test is previewed and accepted',
    (syringe.status === 201 || syringe.status === 200) && spiroDev.status === 'AVAILABLE' && retestS.json.state === 'LISTENING' && gdt2.status === 202
    && retestV.json.state === 'PREVIEW' && retestAcc.status === 200 && retestAcc.json.state === 'ACCEPTED',
    `QC → ${syringe.status}; device ${spiroDev.status}; session ${retestS.json.state}; GDT → ${gdt2.status}; preview ${retestV.json.state}; accept → ${retestAcc.json.state || brief(retestAcc)}`);
  await shot(nurse, 'inc6-oh-09-calibration', `${OH}/#/devices/calibration`, 2500);

  // manual entry with attestation – a Snellen result (no vision device at the station)
  const visionS = await openSession(nurse, { formCode: 'E03.DEVICE_RESULT.VISION', employeeId: 'E1003', stationCode: 'LHR-OH-ROOM-1' });
  const attested = { model: 'Snellen chart 6 m', serial: 'SNELLEN-LHR-1', calibrationId: 'CHART-2026-01', calibrationDate: new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10) };
  const visionGroup = visionS.json.sessionId ? await api(nurse, 'GET', '/device-capture/forms/E03.DEVICE_RESULT.VISION') : { json: {} };
  const visionFields = Object.fromEntries((visionGroup.json.fieldGroups || []).flatMap((g) => g.fields || []).filter((f) => f.required)
    .map((f) => [f.path.replace(/^measurements\./, ''), f.type === 'QUANTITY' ? 6 : '6/6']));
  const manual = visionS.json.sessionId ? await api(nurse, 'POST', `/device-capture/sessions/${visionS.json.sessionId}/fallback`, { path: 'MANUAL_ATTESTED', reasonCode: 'DEVICE_UNAVAILABLE',
    values: Object.keys(visionFields).length ? visionFields : { rightDistance: '6/6', leftDistance: '6/9' }, attested }) : { status: 0, json: {} };
  const manAcc = manual.status === 200 ? await api(nurse, 'POST', `/device-capture/sessions/${visionS.json.sessionId}/accept`, { acknowledgedFlags: warnCodes({ json: manual.json }) }) : { status: 0, json: {} };
  const resE1003 = await api(nurse, 'GET', '/employees/E1003/results');
  check('device-less capture of a Snellen result: manual entry with attestation (model, serial, calibration) → E03 result capture_method MANUAL_ATTESTED',
    ['FALLBACK_OFFERED', 'LISTENING'].includes(visionS.json.state) && manual.status === 200 && (manual.json.preview || {}).marking === 'entered manually – attested'
    && manAcc.status === 200 && arr(resE1003.json).some((r) => r.captureMethod === 'MANUAL_ATTESTED'),
    `session ${visionS.json.state || brief(visionS)}; fallback → ${manual.json.state || brief(manual)} (${(manual.json.preview || {}).marking}); accept → ${manAcc.json.state || brief(manAcc)}`);

  // ======================================================================= unmatched queue
  const unknown = golden('vitals/csm-pcd01/unknown-employee.hl7', 'latin1').replace(/20261030\d{6}/g, hl7Now()).replace(/MSG000\d/g, 'E2EU' + run);
  const um = await machine(GW, 'POST', '/device-ingest/hl7v2', unknown, 'application/hl7-v2', { 'X-Device-Instance': 'DEV-000003' });
  await sleep(300);
  const queue = await api(nurse, 'GET', '/device-engine/unmatched?siteCode=LHR');
  const item = arr(queue.json).filter((i) => i.deviceInstanceId === 'DEV-000003').sort((a, b) => (a.receivedAt < b.receivedAt ? 1 : -1))[0] || {};
  const sugg = (item.suggestions || [])[0] || {};
  await shot(nurse, 'inc6-oh-10-unmatched', `${OH}/#/devices/unmatched`, 2500);
  const adminQueue = await api(admin, 'GET', '/device-engine/unmatched');
  const resolved = item.id ? await api(nurse, 'POST', `/device-engine/unmatched/${item.id}/resolve`, { employeeId: sugg.employeeId || 'E1001', reasonCode: 'CONFIRMED_IDENTITY',
    note: 'Badge checked at the station' }) : { status: 0, json: {} };
  check('a reading without a session for an unknown employee id → AA and the unmatched queue with a suggestion (never auto-filed); nurse1 resolves with a reason; the console role cannot see the queue',
    /MSA\|AA/.test(um.text) && !!item.id && sugg.confidence < 0.95 && resolved.status === 200 && /PENDING_VERIFICATION|ROUTED/.test(resolved.json.observationStatus || '')
    && adminQueue.status === 403,
    `queue item ${item.reasonCode} suggestion ${sugg.employeeId} ${sugg.confidence}; resolve → ${resolved.json.observationStatus || brief(resolved)}; deviceadmin1 queue → ${adminQueue.status}`);

  // ======================================================================= signed driver packages (E34 signing, E32 registry)
  const tester = await session(browser, OH, 'inttester1');
  const approver = await session(browser, OH, 'deviceapprover1');
  /** Conformance → E34 signature (after the secret scan) → registry (verified through E34) → second-person certification. */
  async function publishDriver(base, version, change, beforeRegister) {
    const pkg = JSON.parse(JSON.stringify(base));
    delete pkg.signature;
    pkg.version = version;
    if (change) change(pkg);
    const conf = await api(tester, 'POST', '/device-engine/drivers/conformance', pkg);
    const signed = await api(tester, 'POST', '/device-security/packages/sign', pkg);
    const ev = { conformanceRunId: `CR-E2E6-${run}-${version}`, conformanceEvidenceSha256: conf.json.evidenceSha256 || 'ef'.repeat(32) };
    const extra = beforeRegister ? await beforeRegister(pkg, signed.json.signature, ev) : {};
    const reg = signed.status === 200 ? await api(tester, 'POST', '/device-catalogue/drivers', { packageJson: { ...pkg, signature: signed.json.signature }, ...ev }) : { status: 0, json: {} };
    const cert = reg.status === 201 ? await api(approver, 'POST', `/device-catalogue/drivers/${pkg.packageId}/${version}/certify`, { note: 'Conformance evidence reviewed' }) : { status: 0, json: {} };
    return { pkg, conf, signed, reg, cert, ...extra };
  }
  // the vendor's firmware 2.5 sends ORU^R30 (unsolicited point-of-care observation) – driver 1.0.0 accepts ORU^R01 only
  const csmBase = (await api(tester, 'GET', '/device-catalogue/drivers/vitals.welchallyn.csm-pcd01/1.0.0')).json.manifest;
  const fixVersion = `1.0.${100 + crypto.randomInt(800)}`;
  const fix = await publishDriver(csmBase, fixVersion, (p) => { p.protocol.messageTypes = ['ORU^R01', 'ORU^R30']; p.protocol.software = 'CSM firmware 2.4 / 2.5'; },
    async (pkg, signature, ev) => {
      const forged = JSON.parse(JSON.stringify(pkg));
      forged.mappings[0].plausible.max = 400; // changed after signing
      return {
        tampered: await api(tester, 'POST', '/device-catalogue/drivers', { packageJson: { ...forged, signature }, ...ev }),
        verifyForged: await api(tester, 'POST', '/device-security/packages/verify', { ...forged, signature }),
        unsigned: await api(tester, 'POST', '/device-catalogue/drivers', { packageJson: pkg, ...ev }),
      };
    });
  check('driver packages must be signed: conformance passes, E34 signs after the secret scan, a package changed after signing or without a signature is refused, the signed fix is registered and certified by a second person',
    fix.conf.json.passed === true && fix.signed.status === 200 && !!(fix.signed.json.signature || {}).keyId && fix.tampered.status >= 400 && fix.verifyForged.json.valid === false
    && fix.unsigned.status >= 400 && fix.reg.status === 201 && fix.cert.status === 200 && fix.cert.json.status === 'CERTIFIED',
    `${fix.pkg.packageId}@${fixVersion}: conformance passed=${fix.conf.json.passed}; signed with ${(fix.signed.json.signature || {}).keyId}; tampered → ${fix.tampered.status} ${fix.tampered.json.code} (verify valid=${fix.verifyForged.json.valid} ${fix.verifyForged.json.failureCode || ''}); unsigned → ${fix.unsigned.status} ${fix.unsigned.json.code}; signed → ${fix.reg.json.status || brief(fix.reg)}; certify → ${fix.cert.json.status || brief(fix.cert)}`);
  await shot(tester, 'inc6-oh-11-driver-registry', `${OH}/#/devices/drivers`, 2500);

  // ======================================================================= dead letter and replay after the driver fix, without duplicates
  const dlqS = await openSession(nurse, { formCode: VITALS, employeeId: 'E1002', deviceInstanceId: 'DEV-000003', stationCode: 'LHR-OH-ROOM-1' });
  const r30 = oru('E1002', 131, 83).replace('ORU^R01^ORU_R01', 'ORU^R30^ORU_R30');
  const ae = await machine(GW, 'POST', '/device-ingest/hl7v2', r30, 'application/hl7-v2', { 'X-Device-Instance': 'DEV-000003' });
  const dls = await api(tester, 'GET', '/device-engine/dead-letters?status=OPEN');
  const dl = arr(dls.json).filter((d) => d.deviceInstanceId === 'DEV-000003' && /ORU\^R30/.test(d.detailMasked || '')).pop() || {};
  await shot(tester, 'inc6-oh-12-dead-letters', `${OH}/#/devices/dead-letters`, 2500);
  const rep1 = dl.id ? await api(tester, 'POST', `/device-engine/dead-letters/${dl.id}/replay`, { driverVersion: fixVersion }) : { status: 0, json: {} };
  const dlqV = dlqS.json.sessionId ? await waitPreview(nurse, dlqS.json.sessionId) : { json: {} };
  const dlqAcc = dlqV.json.state === 'PREVIEW' ? await api(nurse, 'POST', `/device-capture/sessions/${dlqS.json.sessionId}/accept`, { acknowledgedFlags: warnCodes(dlqV) }) : { status: 0, json: {} };
  const rep2 = dl.id ? await api(tester, 'POST', `/device-engine/dead-letters/${dl.id}/replay`, { driverVersion: fixVersion }) : { status: 0, json: {} };
  const dlObs = arr(rep1.json.observationIds)[0];
  const e1002a = await api(nurse, 'GET', '/employees/E1002/results');
  const range = await api(admin, 'POST', '/device-engine/replay', { from: new Date(Date.now() - 3600000).toISOString(), to: new Date().toISOString(), deviceInstanceId: 'DEV-000003' });
  const e1002b = await api(nurse, 'GET', '/employees/E1002/results');
  const filedOnce = arr(e1002b.json).filter((r) => r.deviceObservationId === dlObs).length;
  check('firmware variant (ORU^R30) → AE ACK and a masked dead letter (no PHI); inttester1 replays it with the signed driver fix → routed once into the open capture session; a second replay is refused and a range replay creates no duplicate E03 result',
    /MSA\|AE/.test(ae.text) && !!dl.id && !JSON.stringify(dls.json).includes('E1002') && rep1.status === 200 && rep1.json.status === 'PROCESSED'
    && dlqAcc.status === 200 && dlqAcc.json.state === 'ACCEPTED' && rep2.status === 409 && range.status === 200 && filedOnce === 1
    && arr(e1002b.json).length === arr(e1002a.json).length,
    `ACK ${(ae.text.match(/MSA\|[A-Z]{2}/) || [''])[0]}; dead letter ${dl.code} "${dl.detailMasked}"; replay @${fixVersion} → ${rep1.json.status || brief(rep1)}; session ${dlqV.json.state} accept → ${dlqAcc.json.state || brief(dlqAcc)}; again → ${rep2.status} ${rep2.json.code}; range replay messages=${range.json.messages}; E1002 results with the observation=${filedOnce} (${arr(e1002a.json).length} → ${arr(e1002b.json).length})`);

  // ======================================================================= breath alcohol (restricted) into E10
  const collector = await session(browser, OH, 'collector1');
  const breathS = await openSession(collector, { formCode: 'E10.BREATH_ALCOHOL', employeeId: 'E1004', deviceInstanceId: 'DEV-000005', stationCode: 'LHR-OH-ROOM-1', orderRef: `DA:${DA_TEST}` });
  const frame = JSON.stringify({ records: [{ port: 'COM3', receivedAt: new Date().toISOString(), encoding: 'TEXT',
    data: `TEST#${run.slice(0, 4)}\r\nRESULT 0.000 g/210L\r\nTYPE SCREEN\r\nCAL 2026-10-01\r\nSN FC20-30112\r\n` }] });
  const serial = await machine(AGT, 'POST', '/device-ingest/serial', frame, 'application/json', { 'X-Device-Instance': 'DEV-000005' });
  const colV = breathS.json.sessionId ? await waitPreview(collector, breathS.json.sessionId) : { json: {} };
  const brac = ((colV.json.preview || {}).values || []).find((v) => /breath|brac/i.test(v.key || v.label || ''));
  const nurseV = breathS.json.sessionId ? await api(nurse, 'GET', `/device-capture/sessions/${breathS.json.sessionId}`) : { status: 0, json: {} };
  const mro = await session(browser, OH, 'mro1');
  const mroV = breathS.json.sessionId ? await api(mro, 'GET', `/device-capture/sessions/${breathS.json.sessionId}`) : { status: 0, json: {} };
  await shot(collector, 'inc6-oh-13-testing-collection', `${OH}/#/testing/collection`, 2500);
  const nurseAcc = breathS.json.sessionId ? await api(nurse, 'POST', `/device-capture/sessions/${breathS.json.sessionId}/accept`, {}) : { status: 0 };
  const colAcc = breathS.json.sessionId ? await api(collector, 'POST', `/device-capture/sessions/${breathS.json.sessionId}/accept`, { acknowledgedFlags: warnCodes(colV) }) : { status: 0, json: {} };
  const reqAfter = await api(collector, 'GET', '/testing/requirements');
  const nurseHidden = nurseV.status === 403 || nurseV.status === 404 || (nurseV.json.restrictedReading === true && !nurseV.json.preview);
  check('breath alcohol on DEV-000005 for the open E10 test (serial, restricted): the collector previews 0.000 in the E10 unit g/210 L (UCUM g/(210.L)) and accepts, nurse1 sees only "restricted reading", mro1 sees the value; the E10 test records the reading',
    serial.status === 202 && colV.json.state === 'PREVIEW' && brac && /^g\/\(?210\.?L\)?$/.test(brac.unit || '') && nurseHidden && nurseAcc.status === 403
    && ((mroV.json.preview || {}).values || []).length > 0 && colAcc.status === 200 && colAcc.json.routedRecordType === 'DA_TEST_READING'
    && reqAfter.status === 200 && !arr(reqAfter.json).some((r) => r.id === DA_TEST),
    `serial → ${serial.status}; collector ${colV.json.state} ${brac && brac.value} ${brac && brac.unit}; nurse → ${nurseV.status} restricted=${nurseV.json.restrictedReading}; nurse accept → ${nurseAcc.status}; mro values=${((mroV.json.preview || {}).values || []).length}; accept → ${colAcc.json.routedRecordType || brief(colAcc)}; breath requirement still open=${arr(reqAfter.json).some((r) => r.id === DA_TEST)}`);

  // ======================================================================= store-and-forward batch from the tablet
  const prefix = `${TAB.id}:${run}:`;
  const readings = Array.from({ length: 20 }, (_, i) => i + 1).sort(() => Math.random() - 0.5);
  const batchBody = JSON.stringify({ items: readings.map((i) => ({ kind: 'BLE', idempotencyKey: prefix + i, sequence: i,
    capturedAt: new Date(Date.now() - (30 - i) * 60000).toISOString(), contentType: 'application/json', headers: { 'X-Device-Instance': 'DEV-000004' },
    body: JSON.parse(bleBody(bpMeasurement(1150 + i, 70 + (i % 5), 85))) })) });
  const b1 = await machine(TAB, 'POST', '/device-ingest/batch', batchBody, 'application/json', { 'X-Replayed': 'true', 'X-Gateway-Sequence': '20' });
  const b2 = await machine(TAB, 'POST', '/device-ingest/batch', batchBody, 'application/json', { 'X-Replayed': 'true', 'X-Gateway-Sequence': '20' });
  const r1 = arr((b1.json || {}).results);
  const r2 = arr((b2.json || {}).results);
  const inOrder = r1.map((r) => Number(String(r.idempotencyKey).split(':').pop()));
  check('store-and-forward: 20 BLE readings replayed out of order with X-Replayed are processed in sequence order; a second replay is all DUPLICATE',
    b1.status === 200 && r1.length === 20 && inOrder.every((v, i) => v === i + 1) && b2.status === 200 && r2.length === 20
    && r2.every((r) => (r.receipt || {}).status === 'DUPLICATE'),
    `first → ${b1.status} ${r1.length} results order ${inOrder.slice(0, 5).join(',')}…; second → ${r2.filter((r) => (r.receipt || {}).status === 'DUPLICATE').length}/20 DUPLICATE`);

  // ======================================================================= E32 wizard → four-eyes → certification PDF → publish
  const step = (cfg, n) => ((cfg.steps || [])[n - 1] || {}).status;
  let cfg = (await api(admin, 'POST', '/device-configurations', { modelCode: 'MD-027', siteCode: 'LHR' })).json;
  const cfgId = cfg.configurationId;
  const dev = cfg.deviceInstanceId;
  cfg = (await api(admin, 'PUT', `/device-configurations/${cfgId}/steps/2`, { option: 'P4' })).json;
  const s3 = await api(admin, 'PUT', `/device-configurations/${cfgId}/steps/3`, { items: [
    { code: 'NETWORK_ZONE', status: 'DONE', evidence: { reference: 'DEVICE_VLAN', text: 'FW change CHG-2026-1206' } },
    { code: 'ENDPOINT_IDENTITY', evidence: { reference: target.id } },
    { code: 'CERTIFICATES', status: 'DONE', evidence: { reference: 'PKI-REQ-E2E6' } },
    { code: 'CALIBRATION_EVIDENCE', evidence: { documentId: 'CAL-CERT-E2E6', text: 'Vendor calibration certificate' } }] });
  const auto = await api(admin, 'POST', `/device-configurations/${cfgId}/steps/3/auto-check`, {});
  const params = (ip) => ({ deviceAddress: { ip }, mllp: { listenerEndpointId: target.id, port: 2575, tls: 'REQUIRED' },
    msh: { sendingApplication: 'CONNEX', sendingFacility: 'LHR_OH', receivingFacility: 'WZ', receivingApplication: 'WELLNESSZ' }, ackMode: 'ORIGINAL', messageProfile: 'IHE_PCD_01' });
  const s4bad = await api(admin, 'PUT', `/device-configurations/${cfgId}/steps/4`, { parameters: params('10.20.30.999') });
  const s4 = await api(admin, 'PUT', `/device-configurations/${cfgId}/steps/4`, { parameters: params('10.20.30.97') });
  const s5 = await api(admin, 'PUT', `/device-configurations/${cfgId}/steps/5`, { associationMethods: ['SESSION'], siteCode: 'LHR', stationCode: 'LHR-OH-ROOM-1',
    services: ['PERIODIC_MEDICAL'], formCodes: [VITALS] });
  const preview5 = await api(admin, 'POST', `/device-configurations/${cfgId}/steps/5/preview`, { datasetId: 'normal' });
  const sim = await api(admin, 'POST', `/device-configurations/${cfgId}/steps/6/tests`, { mode: 'SIMULATOR', datasetId: 'normal' });
  if (gw) await heartbeat(gw);
  const live = await api(admin, 'POST', `/device-configurations/${cfgId}/steps/6/tests`, { mode: 'LIVE' });
  await shot(admin, 'inc6-oh-14-wizard-test', `${OH}/#/devices/wizard/${cfgId}`, 2500);
  const review = await api(nurse, 'POST', `/device-configurations/${cfgId}/steps/6/nurse-review`, { outcome: 'ACCEPTED', note: 'Values match the reference monitor' });
  check('wizard for MD-027 at LHR-OH-ROOM-1 (P4 behind the enrolled gateway): step 3 auto-checks, step 4 schema error then valid, step 5 binding preview from a golden dataset, step 6 simulator and LIVE PASSED, nurse review by nurse1',
    step(s3.json, 3) === 'VALID' && auto.status === 200 && s4bad.status === 422 && step(s4.json, 4) === 'VALID' && step(s5.json, 5) === 'VALID'
    && preview5.status === 200 && JSON.stringify(preview5.json).includes('systolic') && sim.json.status === 'PASSED' && live.json.status === 'PASSED'
    && step(review.json, 6) === 'VALID',
    `${cfgId}/${dev}: step3 ${step(s3.json, 3)} (${JSON.stringify(s3.json).match(/Endpoint [^"]+/) || ''}); step4 bad → ${s4bad.status} ${s4bad.json.code} then ${step(s4.json, 4)}; step5 ${step(s5.json, 5)} preview → ${preview5.status}; simulator ${sim.json.status}; LIVE ${live.json.status}; step6 ${step(review.json, 6)}`);

  const submit = await api(admin, 'POST', `/device-configurations/${cfgId}/submit`);
  const self = await api(admin, 'POST', `/device-configurations/${cfgId}/approve`, { note: 'self' });
  await shot(approver, 'inc6-oh-15-approvals', `${OH}/#/devices/approvals`, 2500);
  const approved = await api(approver, 'POST', `/device-configurations/${cfgId}/approve`, { note: 'Evidence complete' });
  const pdf = await download(approver, `/device-configurations/${cfgId}/certificate.pdf`);
  check('four-eyes: deviceadmin1 cannot approve the own submission (409 four_eyes_violation); deviceapprover1 certifies → certification PDF (application/pdf)',
    submit.status === 200 && self.status === 409 && self.json.code === 'four_eyes_violation' && approved.status === 200 && approved.json.status === 'CERTIFIED'
    && pdf.status === 200 && /application\/pdf/.test(pdf.type) && pdf.head === '%PDF-',
    `submit → ${submit.status}; self-approval → ${self.status} ${self.json.code}; approver → ${approved.json.status || brief(approved)}; PDF ${pdf.status} ${pdf.type} ${pdf.size} bytes`);
  const pub = await api(admin, 'POST', `/device-configurations/${cfgId}/publish`);
  const reload = await api(admin, 'GET', `/device-configurations/${cfgId}`);
  await api(nurse, 'POST', '/device-calibration/checks', { deviceInstanceId: dev, checkType: 'BP_VALIDATION_INTERVAL', outcome: 'PASS' });
  const hbDev = gw ? await heartbeat(gw, [dev]) : { json: {} };
  const capDevs = await api(nurse, 'GET', `/device-capture/devices?formCode=${VITALS}&siteCode=LHR&stationCode=LHR-OH-ROOM-1`);
  check('publish → the instance is authorised on the enrolled gateway (no second identity, no token on reload); the gateway heartbeat returns configVersion and the device is offered at the station',
    pub.status === 200 && pub.json.status === 'PUBLISHED' && pub.json.endpointId === target.id && !('enrolmentToken' in pub.json)
    && !JSON.stringify(reload.json).includes('enrolmentToken') && 'configVersion' in hbDev.json && arr(capDevs.json).some((d) => d.deviceInstanceId === dev),
    `publish → ${pub.json.status} endpoint=${pub.json.endpointId}; heartbeat configVersion=${hbDev.json.configVersion}; station devices=${arr(capDevs.json).map((d) => d.deviceInstanceId + ':' + d.status).join(' ')}`);
  await shot(admin, 'inc6-oh-16-fleet', `${OH}/#/devices/fleet`, 2500);

  // ======================================================================= P6 vendor-cloud poll against the mock cloud
  if (cloud) {
    const p6drv = await publishDriver((await api(tester, 'GET', '/device-engine/drivers/wear.cloud.daily-poll/1.0.0')).json, '1.0.1');
    const posture = await api(secops, 'PUT', '/device-catalogue/models/MD-121/posture', { mds2: { received: true, reference: 'MDS2-E2E6' }, osSupported: true,
      patchPath: 'VENDOR_CLOUD_MANAGED', remoteSupport: 'NONE', supportStatus: 'CURRENT', sbom: { available: true, format: 'CycloneDX' }, contacts: { securityEmail: 'psirt@vendor.example' } });
    let p6 = (await api(admin, 'POST', '/device-configurations', { modelCode: 'MD-121', siteCode: 'LHR' })).json;
    const p6Id = p6.configurationId;
    const p6Dev = p6.deviceInstanceId;
    const p6s2 = await api(admin, 'PUT', `/device-configurations/${p6Id}/steps/2`, { option: 'P6' });
    const p6s3 = await api(admin, 'PUT', `/device-configurations/${p6Id}/steps/3`, { items: [
      { code: 'VENDOR_CLOUD_AGREEMENT', status: 'DONE', evidence: { reference: 'DPA-E2E6', text: 'DPA signed, UK hosting' } },
      { code: 'INTERFACE_SPEC_CONFIRMED', status: 'DONE', evidence: { reference: 'SPEC-E2E6' } },
      { code: 'CALIBRATION_EVIDENCE', status: 'NOT_APPLICABLE' }] });
    const p6params = { apiBaseUrl: cloud.base, residencyRegion: 'UK', poll: { enabled: true, intervalSeconds: 900, rateLimitPerMinute: 30 }, webhook: { enabled: false },
      oauth2: { tokenUrl: `${cloud.base}/oauth/token`, clientId: 'wz-e2e6-client' } };
    const p6off = await api(admin, 'PUT', `/device-configurations/${p6Id}/steps/4`, { parameters: { ...p6params, apiBaseUrl: 'https://api.not-allowed.example' },
      secrets: { 'oauth2.clientSecretRef': 'e2e6-cloud-secret' } });
    const p6s4 = await api(admin, 'PUT', `/device-configurations/${p6Id}/steps/4`, { parameters: p6params, secrets: { 'oauth2.clientSecretRef': 'e2e6-cloud-secret' } });
    const p6s5 = await api(admin, 'PUT', `/device-configurations/${p6Id}/steps/5`, { associationMethods: ['VENDOR_ID_MATCH'], siteCode: 'LHR', stationCode: 'LHR-OH-ROOM-2' });
    const p6sim = await api(admin, 'POST', `/device-configurations/${p6Id}/steps/6/tests`, { mode: 'SIMULATOR', datasetId: 'normal' });
    await api(admin, 'PUT', `/device-configurations/${p6Id}/steps/6`, { simulatorOnlyJustification: 'Vendor cloud sandbox only – live polling verified after publication with Poll now' });
    const p6rev = await api(nurse, 'POST', `/device-configurations/${p6Id}/steps/6/nurse-review`, { outcome: 'ACCEPTED' });
    await api(admin, 'POST', `/device-configurations/${p6Id}/submit`);
    await api(approver, 'POST', `/device-configurations/${p6Id}/approve`, { note: 'P6 evidence complete' });
    const p6co = await api(secops, 'POST', `/device-configurations/${p6Id}/approve`, { note: 'Simulator-only accepted for the vendor cloud' });
    const p6pub = await api(admin, 'POST', `/device-configurations/${p6Id}/publish`);
    const poll1 = await api(admin, 'POST', `/device-engine/ops/cloud-connectors/${p6Dev}/poll`);
    const poll2 = await api(admin, 'POST', `/device-engine/ops/cloud-connectors/${p6Dev}/poll`);
    const leak = JSON.stringify(p6pub.json) + JSON.stringify((await api(admin, 'GET', `/device-configurations/${p6Id}`)).json);
    check('P6 vendor cloud: wizard checks the egress allow-list (the config entry the poller enforces – host_not_allowed for another host), the client secret goes to the vault; Poll now against the mock cloud (OAuth2 client credentials) ingests the consented record, the second run is DUPLICATE from the high-water mark',
      p6drv.cert.status === 200 && p6s2.status === 200 && p6off.status === 422 && JSON.stringify(p6off.json).includes('host_not_allowed') && step(p6s4.json, 4) === 'VALID' && p6pub.status === 200
      && poll1.status === 200 && poll1.json.status === 'OK' && poll1.json.accepted >= 1 && poll2.json.duplicates >= 1 && poll2.json.accepted === 0
      && cloud.state.tokenCalls >= 1 && !leak.includes('e2e6-cloud-secret'),
      `driver ${p6drv.pkg.packageId}@1.0.1 signed → ${p6drv.signed.status} ${p6drv.signed.json.code || ''} registry → ${p6drv.reg.status} certify → ${p6drv.cert.json.status || p6drv.cert.status}; posture → ${posture.status}; step2 ${p6s2.status} step3 ${step(p6s3.json, 3)} other host → ${p6off.status} ${JSON.stringify(p6off.json).includes('host_not_allowed') ? 'host_not_allowed' : ''}; step4 ${step(p6s4.json, 4)} step5 ${step(p6s5.json, 5)} sim ${p6sim.json.status} review ${step(p6rev.json, 6)} co-approval ${p6co.json.status || brief(p6co)} publish → ${p6pub.json.status || brief(p6pub)}; poll → ${poll1.json.status} records=${poll1.json.records} accepted=${poll1.json.accepted} skippedNoConsent=${poll1.json.skippedNoConsent}; again → accepted=${poll2.json.accepted} duplicates=${poll2.json.duplicates}; token calls=${cloud.state.tokenCalls}`);
    await shot(admin, 'inc6-oh-17-engine-health', `${OH}/#/devices/operations`, 2500);
  } else {
    check('P6 vendor-cloud poll against the mock cloud', false, `no dev CA in ${DEV_CA}`);
  }

  // ======================================================================= outbound order with device ACK (start from the form)
  await heartbeat(GW, ['DEV-000003', 'DEV-000006', 'DEV-000007']);
  const ecgS = await openSession(nurse, { formCode: 'E03.DEVICE_RESULT.ECG', employeeId: 'E1001', deviceInstanceId: 'DEV-000007', stationCode: 'LHR-OH-ROOM-1' });
  await sleep(1500); // the session-opened event is relayed to the engine, which queues the gateway command
  let startCmd;
  for (let i = 0; i < 10 && !startCmd; i++) {
    const hb = await heartbeat(GW, ['DEV-000003', 'DEV-000006', 'DEV-000007']);
    startCmd = arr(hb.json.commands).find((c) => c.deviceInstanceId === 'DEV-000007' && /START_ACQUISITION|PUSH_WORKLIST/.test(c.type));
    if (!startCmd) await sleep(1000);
  }
  const outbound = startCmd ? await machine(GW, 'GET', `/device-ingest/commands/${startCmd.commandId}/outbound`) : { status: 0, json: {} };
  const om = arr((outbound.json || {}).messages)[0] || {};
  const otherGw = startCmd ? await machine(AGT, 'GET', `/device-ingest/commands/${startCmd.commandId}/outbound`) : { status: 0 };
  const cmdResult = startCmd ? await machine(GW, 'POST', `/device-ingest/commands/${startCmd.commandId}/result`, { status: 'DONE', detail: '1/1 start messages acknowledged',
    acks: [{ controlId: om.controlId, code: 'AA' }] }) : { status: 0 };
  const panel = ecgS.json.sessionId ? await api(nurse, 'GET', `/device-engine/outbound?sessionId=${ecgS.json.sessionId}`) : { json: {} };
  const pm = arr((panel.json || {}).messages)[0] || {};
  check('start from the form: the ECG capture session queues START_ACQUISITION for the gateway; the HL7 ORM^O01 order is fetched only by that gateway (MLLP), the device ACK (AA) is reported and the capture panel shows ACKED without message content',
    ecgS.status === 201 && !!startCmd && outbound.status === 200 && /ORM\^O01/.test(om.er7 || om.messageType || '') && otherGw.status === 404 && cmdResult.status === 204
    && pm.status === 'ACKED' && pm.ackCode === 'AA' && !JSON.stringify(panel.json).includes('MSH|'),
    `session ${ecgS.json.state || brief(ecgS)}; command ${startCmd && startCmd.type}; outbound ${outbound.status} ${outbound.json.transport} ${om.messageType}; other endpoint → ${otherGw.status}; result → ${cmdResult.status}; panel ${pm.messageType} ${pm.status} ${pm.ackCode}`);
  if (ecgS.json.sessionId) await api(nurse, 'POST', `/device-capture/sessions/${ecgS.json.sessionId}/cancel`, { reasonCode: 'OTHER' });

  // ======================================================================= historic retrieval with deterministic de-duplication
  const to = new Date(Date.now() - 3600000), from = new Date(to.getTime() - 5 * 86400000);
  const hrNurse = await api(nurse, 'POST', '/device-engine/historic-retrievals', { deviceInstanceId: 'DEV-000007', from: from.toISOString(), to: to.toISOString() });
  const hrSpiro = await api(admin, 'POST', '/device-engine/historic-retrievals', { deviceInstanceId: 'DEV-000001', from: from.toISOString(), to: to.toISOString() });
  const hr = await api(tester, 'POST', '/device-engine/historic-retrievals', { deviceInstanceId: 'DEV-000007', from: from.toISOString(), to: to.toISOString() });
  const hbHr = await heartbeat(GW, ['DEV-000007']);
  const hrCmd = arr(hbHr.json.commands).find((c) => c.type === 'RETRIEVE_HISTORY' && (c.payload || {}).retrievalId === hr.json.id);
  const hrOut = hrCmd ? await machine(GW, 'GET', `/device-ingest/commands/${hrCmd.commandId}/outbound`) : { json: {} };
  const ecgA = golden('cardiology/ecg.ge.mac-hl7/normal.hl7', 'latin1').replace(/ECG0001/g, `HR-${run}`);
  const ecgB = golden('cardiology/ecg.ge.mac-hl7/edge-long-qtc.hl7', 'latin1').replace(/20261030/g, '20261029');
  const hrBatch = (a, b, last) => ({ items: [{ kind: 'HL7V2', idempotencyKey: 'HQ:a', sequence: 1, contentType: 'application/hl7-v2', body: a },
    { kind: 'HL7V2', idempotencyKey: 'HQ:b', sequence: 2, contentType: 'application/hl7-v2', body: b }], last });
  const hr1 = hr.json.id ? await machine(GW, 'POST', `/device-ingest/retrievals/${hr.json.id}/batch`, hrBatch(ecgA, ecgB, false)) : { json: {} };
  // the same results again with new transmission fields (MSH-7 / MSH-10) – deterministic keys
  const resent = ecgA.replace(/\|HR-[0-9a-f]+\|/, '|HR-RESENT|').replace(/^(MSH(?:\|[^|]*){5}\|)\d+/, '$1' + hl7Now());
  const hr2 = hr.json.id ? await machine(GW, 'POST', `/device-ingest/retrievals/${hr.json.id}/batch`, hrBatch(resent, ecgB, true)) : { json: {} };
  const hrDone = hrCmd ? await machine(GW, 'POST', `/device-ingest/commands/${hrCmd.commandId}/result`, { status: 'DONE', detail: '2 results returned' }) : { status: 0 };
  const hrView = hr.json.id ? await api(tester, 'GET', `/device-engine/historic-retrievals/${hr.json.id}`) : { json: {} };
  check('historic retrieval (≤ 31 days, drivers that declare it): RETRIEVE_HISTORY → QRY^R02 for the gateway; re-sent results with new MSH-7 / MSH-10 are DUPLICATE – nothing stored twice; nurses cannot request, unsupported drivers are refused',
    hrNurse.status === 403 && hrSpiro.status === 422 && hrSpiro.json.code === 'historic_retrieval_not_supported' && hr.status === 201 && !!hrCmd
    && /QRY\^R02/.test(JSON.stringify(hrOut.json)) && (hr1.json.retrieval || {}).accepted === 2 && (hr2.json.retrieval || {}).duplicates === 2
    && hrDone.status === 204 && hrView.json.status === 'COMPLETED',
    `nurse → ${hrNurse.status}; spirometer → ${hrSpiro.status} ${hrSpiro.json.code}; request → ${hr.json.status} ${hr.json.method}; batch 1 accepted=${(hr1.json.retrieval || {}).accepted}; re-sent duplicates=${(hr2.json.retrieval || {}).duplicates}; ${hrView.json.status} received=${hrView.json.received}`);

  // ======================================================================= decommissioning revokes the instance on its endpoint
  const decReq = await api(admin, 'POST', '/device-configurations/CFG-000002/decommission', { reason: 'Audiometer replaced by a newer model', disposal: 'RETURNED_TO_VENDOR' });
  const decApp = await api(approver, 'POST', '/device-configurations/CFG-000002/decommission/approve', { note: 'Approved' });
  const audio = await machine(AGT, 'POST', '/device-ingest/files', golden('audiometry/cca200-db/normal.csv'), 'text/csv',
    { 'X-Device-Instance': 'DEV-000002', 'X-File-Name': `audiogram_${run}.csv`, 'X-File-Format': 'CSV' });
  check('decommissioning DEV-000002 (request + second-person approval) revokes the instance on the shared agent: its posts are refused',
    decReq.status === 200 && decApp.status === 200 && [401, 403, 404, 409].includes(audio.status),
    `request → ${decReq.json.status || brief(decReq)}; approve → ${decApp.json.status || brief(decApp)}; post → ${audio.status} ${audio.json.code}`);

  // ======================================================================= manager fleet counts (no device trace, no person data)
  const mgr = await session(browser, MGR, 'manager1');
  const fleet = await api(mgr, 'GET', '/workspace/device-fleet');
  const mgrTrace = await api(mgr, 'GET', '/device-diagnostics/DEV-000003/trace');
  const fleetText = JSON.stringify(fleet.json);
  check('manager1 sees site device availability counts in the manager workspace – no device trace, no person data',
    fleet.status === 200 && /\d/.test(fleetText) && !/E100\d|employeeId|displayName/.test(fleetText) && mgrTrace.status === 403,
    `fleet → ${fleet.status} ${fleetText.slice(0, 140)}; trace → ${mgrTrace.status}`);
  await shot(mgr, 'inc6-mgr-01-device-fleet', `${MGR}/#/device-fleet`, 2500);

  // ======================================================================= employee: released summary and wearable consent
  const emp = await session(browser, EMP, 'employee1', { width: 420, height: 900 });
  const summaries = await api(emp, 'GET', '/me/device-summaries');
  await shot(emp, 'inc6-emp-01-health-checks', `${EMP}/#/health-checks`, 2500);
  const grant = await api(emp, 'POST', '/me/device-consents', { purpose: 'WEARABLE_DATA', scopes: ['STEPS', 'SLEEP'], noticeVersion: '1' });
  const consents = await api(emp, 'GET', '/me/device-consents');
  const mine = arr(consents.json).find((c) => c.id === grant.json.id) || arr(consents.json)[0] || {};
  await shot(emp, 'inc6-emp-02-device-consents', `${EMP}/#/device-consents`, 2500);
  const revoke = mine.id ? await api(emp, 'DELETE', `/me/device-consents/${mine.id}`) : { status: 0 };
  const consents2 = await api(emp, 'GET', '/me/device-consents');
  const after2 = arr(consents2.json).find((c) => c.id === mine.id) || {};
  check('employee1 sees the released device summary (vitals captured above) in the employee app, consents to wearable scopes and revokes',
    summaries.status === 200 && JSON.stringify(summaries.json).includes(new Date().toISOString().slice(0, 10)) && (grant.status === 201 || grant.status === 200) && !!mine.id
    && (revoke.status === 200 || revoke.status === 204) && (after2.status === 'WITHDRAWN' || after2.status === 'REVOKED' || !after2.id || after2.active === false),
    `summaries=${arr(summaries.json).length} (${arr(summaries.json).map((s) => s.title || s.category).join(', ')}); consent → ${grant.status} ${(mine.scopes || []).join(',')}; revoke → ${revoke.status} ${after2.status || 'removed'}`);

  // ======================================================================= isolation → quarantine → release refused until re-certification
  const iso = await api(secops, 'POST', `/device-security/endpoints/${GW.id}/isolate`, { reasonCode: 'MANUAL_ISOLATION', note: 'E2E: suspected compromise of the clinic gateway' });
  await sleep(1000);
  const isoPost = await machine(GW, 'POST', '/device-ingest/hl7v2', oru('E1002'), 'application/hl7-v2', { 'X-Device-Instance': 'DEV-000003' });
  const fleetAdmin = await api(admin, 'GET', '/device-fleet?siteCode=LHR');
  const d3 = arr(fleetAdmin.json.devices || fleetAdmin.json).find((d) => d.deviceInstanceId === 'DEV-000003') || {};
  const offered = await api(nurse, 'GET', `/device-capture/devices?formCode=${VITALS}&siteCode=LHR&stationCode=LHR-OH-ROOM-1`);
  const release = await api(secops, 'POST', `/device-security/endpoints/${GW.id}/release`, { reasonCode: 'RELEASED', note: 'forensics clean' });
  await shot(secops, 'inc6-oh-18-security-events', `${OH}/#/security/devices/events`, 2500);
  check('secops1 isolates EP-LHR-GW-01 → the next ingest is 401 endpoint_isolated, the fleet shows DEV-000003 QUARANTINED, capture no longer offers it, and release is refused until re-certification',
    iso.status === 200 && iso.json.status === 'ISOLATED' && isoPost.status === 401 && isoPost.json.code === 'endpoint_isolated' && d3.status === 'QUARANTINED'
    && !arr(offered.json).some((d) => d.deviceInstanceId === 'DEV-000003') && release.status === 409 && release.json.code === 'recertification_required',
    `isolate → ${iso.json.status || brief(iso)}; ingest → ${isoPost.status} ${isoPost.json.code}; fleet DEV-000003 ${d3.status}; offered=${arr(offered.json).map((d) => d.deviceInstanceId).join(',')}; release → ${release.status} ${release.json.code}`);

  // ======================================================================= tenant isolation
  const platform = await session(browser, OH, 'admin1');
  const tenant = `E2E${run.slice(0, 3).toUpperCase()}`;
  const tq = await api(platform, 'PUT', `/device-engine/tenants/${tenant}/quota`, { messagesPerMinute: 600, maxConnectors: 5, catalogueScope: 'TENANT', residencyRegion: 'UK' });
  const tModels = await api(admin, 'GET', `/device-catalogue/models?tenantCode=${tenant}&size=500`);
  const tCfg = await api(admin, 'POST', '/device-configurations', { modelCode: 'MD-027', siteCode: 'LHR', tenantCode: tenant });
  const tEp = await api(secops, 'POST', '/device-security/endpoints', { kind: 'GATEWAY', siteCode: 'LHR', tenantCode: tenant });
  let tenantPost = { status: 0, json: {}, text: '' };
  if (client && tEp.json.enrolmentToken) {
    const tClient = devClient(`e2e6-tenant-${run}`);
    const te = await enrol(tClient, tEp.json.enrolmentToken);
    tenantPost = await machine({ id: tEp.json.endpointId, token: te.json.endpointToken, thumbprint: tClient.thumbprint }, 'POST', '/device-ingest/hl7v2',
      oru('E1003'), 'application/hl7-v2', { 'X-Device-Instance': dev });
  }
  // the enrolled E2E gateway (GROUP) for a GROUP device that is not authorised on it
  const crossDevice = await machine(target, 'POST', '/device-ingest/hl7v2', oru('E1003'), 'application/hl7-v2', { 'X-Device-Instance': 'DEV-000004' });
  // HL7 answers refusals with an AR acknowledgement (HTTP 200) carrying the error code in ERR-3
  const refused = (r) => (r.status === 200 && /MSA\|AR/.test(r.text) && /device_not_authorised_for_endpoint|device_not_published|unknown_device/.test(r.text)) || [401, 403, 404].includes(r.status);
  const ackOf = (r) => `${r.status} ${((r.text || '').match(/MSA\|A[A-Z]/) || [''])[0]} ${((r.text || '').match(/[a-z]+(_[a-z]+)+(?=\|E)/) || [(r.json || {}).code || (r.text || '').slice(0, 60)])[0]}`;
  check('tenant isolation: a TENANT-scoped tenant sees only its own catalogue and cannot configure a shared model (model_not_in_tenant_catalogue); its endpoint cannot see a GROUP device (device_not_published); an endpoint cannot post for a device not authorised on it',
    (tq.status === 200 || tq.status === 201) && tModels.status === 200 && arr(tModels.json).length === 0 && tCfg.status === 422
    && JSON.stringify(tCfg.json).includes('model_not_in_tenant_catalogue') && refused(tenantPost) && refused(crossDevice),
    `tenant ${tenant} → ${tq.status}; models=${arr(tModels.json).length}; configuration → ${tCfg.status} ${(arr(tCfg.json.errors)[0] || {}).code}; tenant endpoint for ${dev} → ${ackOf(tenantPost)}; ${target.id} for DEV-000004 → ${ackOf(crossDevice)}`);

  // ======================================================================= 401 / 403 semantics
  const anon = await anonymous('GET', '/device-engine/observations?employeeId=E1001');
  const anonSec = await anonymous('GET', '/device-security/endpoints');
  const userOnIngest = await anonymous('POST', '/device-ingest/heartbeat', { Authorization: 'Bearer not-a-real-token', 'Content-Type': 'application/json' });
  const deviceOnUser = await machine(TAB, 'GET', '/device-engine/gates');
  const kioskHl7 = await machine(KSK, 'POST', '/device-ingest/hl7v2', oru('E1001'), 'application/hl7-v2', { 'X-Device-Instance': 'DEV-000003' });
  const nurseSec = await api(nurse, 'GET', '/device-security/endpoints');
  const adminObs = await api(admin, 'GET', '/device-engine/observations?employeeId=E1001');
  const testerUnmatched = await api(tester, 'GET', '/device-engine/unmatched');
  const mgrConsole = await api(mgr, 'GET', '/device-catalogue/models');
  check('401 / 403: anonymous → 401; a bearer token on /device-ingest → 401; a device token on a user API → 401; an authenticated endpoint on a path its kind may not use → 403 endpoint_forbidden; console and clinical roles → 403 outside their scope',
    anon.status === 401 && anonSec.status === 401 && userOnIngest.status === 401 && deviceOnUser.status === 401 && kioskHl7.status === 403
    && kioskHl7.json.code === 'endpoint_forbidden' && nurseSec.status === 403 && adminObs.status === 403 && testerUnmatched.status === 403 && mgrConsole.status === 403,
    `anonymous ${anon.status}/${anonSec.status}; bearer on ingest ${userOnIngest.status}; device on user API ${deviceOnUser.status}; kiosk HL7 ${kioskHl7.status} ${kioskHl7.json.code}; nurse security ${nurseSec.status}; admin observations ${adminObs.status}; tester unmatched ${testerUnmatched.status}; manager catalogue ${mgrConsole.status}`);

  // ======================================================================= tamper-evident logs
  const chain = await api(secops, 'GET', '/device-security/events/verify');
  const auditor = await session(browser, OH, 'auditor1');
  const audit = await api(auditor, 'GET', '/audit/verify');
  const auditorEvents = await api(auditor, 'GET', '/device-security/events?size=5');
  check('security event chain verifies (intact: true) and the platform audit chain is intact; AUDITOR reads the security events',
    chain.status === 200 && chain.json.intact === true && audit.status === 200 && audit.json.intact === true && auditorEvents.status === 200,
    `device security chain intact=${chain.json.intact} checked=${chain.json.checked}; audit intact=${audit.json.intact} records=${audit.json.recordsChecked}; auditor events → ${auditorEvents.status}`);

  // partner portal: device data never reaches partner organisations
  const partner = await session(browser, PARTNER, 'partner1');
  const partnerDev = await api(partner, 'GET', '/device-engine/observations?employeeId=E1001');
  const partnerSum = await api(partner, 'GET', '/me/device-summaries');
  check('partner portal users get no device data (403 on the engine and on the employee summaries)', partnerDev.status === 403 && [403, 404].includes(partnerSum.status),
    `engine → ${partnerDev.status}; summaries → ${partnerSum.status}`);
  await shot(partner, 'inc6-partner-01-home', `${PARTNER}/#/`, 2000);

  if (cloud) cloud.server.close();
  await browser.close();
  console.log(log.join('\n'));
  if (log.some((l) => l.startsWith('FAIL'))) process.exit(1);
})().catch((e) => { console.log(log.join('\n')); console.error('E2E FAILED', e); process.exit(1); });
