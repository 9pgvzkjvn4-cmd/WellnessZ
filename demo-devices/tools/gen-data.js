#!/usr/bin/env node
/*
 * Generates demo-devices/data/device-demo-data.js – THE demo data file of the device integration demo pack.
 *
 *   node demo-devices/tools/gen-data.js
 *
 * Sources (read-only, from the repository):
 *   modules/wz-e32-device-console/src/main/resources/e32/device-inventory.json   133 catalogue models (client inventory)
 *   modules/wz-e32-device-console/src/main/resources/e32/option-packs.json       P1–P10 option packs (JSON Schema 2020-12)
 *   modules/wz-e31-device-engine/src/main/resources/e31/drivers/*.json            18 driver packages
 *   modules/wz-e31-device-engine/src/main/resources/e31/golden/**                 golden datasets (real sample payloads)
 *
 * Everything else (people, users, sites, configurations, endpoints, calibration records) is synthetic and defined below.
 * Driver packages are signed with a fixed, publicly documented DEMO Ed25519 key (seed below – not a secret), so the output
 * is byte-for-byte deterministic. The browser verifies the signatures with WebCrypto.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.resolve(__dirname, '..', '..');
const E32 = path.join(ROOT, 'modules/wz-e32-device-console/src/main/resources/e32');
const E31 = path.join(ROOT, 'modules/wz-e31-device-engine/src/main/resources/e31');
const OUT = path.resolve(__dirname, '..', 'data', 'device-demo-data.js');

const DEMO_NOW = '2026-10-30T08:30:00Z';
const readJson = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));
const sha256hex = (s) => crypto.createHash('sha256').update(s, 'utf8').digest('hex');
const minus = (iso, ms) => new Date(Date.parse(iso) - ms).toISOString().replace('.000Z', 'Z');
const plus = (iso, ms) => new Date(Date.parse(iso) + ms).toISOString().replace('.000Z', 'Z');
const H = 3600000, D = 24 * H;

function canonical(v) {
  if (Array.isArray(v)) return '[' + v.map(canonical).join(',') + ']';
  if (v && typeof v === 'object') return '{' + Object.keys(v).sort().map((k) => JSON.stringify(k) + ':' + canonical(v[k])).join(',') + '}';
  return JSON.stringify(v);
}

// ------------------------------------------------------------------ demo signing key (documented, not a secret)
function signingKey(label) {
  const seed = crypto.createHash('sha256').update('wellnessz-device-demo-signing-key:' + label + ':not-a-secret').digest();
  const der = Buffer.concat([Buffer.from('302e020100300506032b657004220420', 'hex'), seed]);
  const priv = crypto.createPrivateKey({ key: der, format: 'der', type: 'pkcs8' });
  const pubJwk = crypto.createPublicKey(priv).export({ format: 'jwk' });
  return { priv, publicKey: Buffer.from(pubJwk.x, 'base64url').toString('base64') };
}
const KEY_ACTIVE = { keyId: 'wz-dev-sign-2026-01', ...signingKey('2026-01') };
const KEY_RETIRED = { keyId: 'wz-dev-sign-2025-02', ...signingKey('2025-02') };

// ------------------------------------------------------------------ catalogue
const FAMILY = {
  'Spirometry & lung function': 'SPIROMETRY', 'Audiometry & hearing': 'AUDIOMETRY', 'Vital signs & body composition': 'VITALS',
  Cardiology: 'CARDIOLOGY', 'Vision & ophthalmic': 'VISION', 'Point-of-care laboratory': 'POC', 'Drug & alcohol testing': 'DRUG_ALCOHOL',
  'Respiratory protection & ergonomics': 'RESP_ERGONOMICS', 'Exposure & environment feeds': 'EXPOSURE', 'Wearables & personal health': 'WEARABLE',
  'Imaging & other': 'IMAGING'
};
const inventory = readJson(path.join(E32, 'device-inventory.json'));
const optionPacks = readJson(path.join(E32, 'option-packs.json'));

// ------------------------------------------------------------------ drivers (signed)
const driverFiles = fs.readdirSync(path.join(E31, 'drivers')).filter((f) => f.endsWith('.json')).sort();
const drivers = driverFiles.map((f) => {
  const pkg = readJson(path.join(E31, 'drivers', f));
  delete pkg.signature;
  const canon = canonical(pkg);
  const sig = crypto.sign(null, Buffer.from(canon, 'utf8'), KEY_ACTIVE.priv).toString('base64');
  return Object.assign(pkg, {
    signature: { keyId: KEY_ACTIVE.keyId, algorithm: 'Ed25519', value: sig, canonicalSha256: sha256hex(canon) },
    registry: { status: 'CERTIFIED', certifiedBy: 'deviceapprover1', certifiedAt: '2026-10-20T10:00:00Z', source: 'modules/wz-e31-device-engine/src/main/resources/e31/drivers/' + f }
  });
});
const driversByModel = {};
drivers.forEach((d) => d.models.forEach((m) => { (driversByModel[m] = driversByModel[m] || []).push(d.packageId + '@' + d.version); }));

const POSTURE_PENDING = new Set(['MD-012', 'MD-021', 'MD-064', 'MD-099']); // MDS2 not yet received (demo)
const catalogue = inventory.map((m) => ({
  id: m.id, family: m.family, familyCode: FAMILY[m.family] || 'OTHER', model: m.model, manufacturer: m.manufacturer, ohUse: m.ohUse,
  connectivity: m.connectivity, protocol: m.protocol, fdaCodes: m.fdaCodes, sprint: m.sprint, confidence: m.confidence, options: m.options,
  lifecycle: 'ACTIVE', posture: POSTURE_PENDING.has(m.id) ? 'PENDING_MDS2' : 'ACCEPTABLE', drivers: driversByModel[m.id] || []
}));

// ------------------------------------------------------------------ golden datasets (real payloads)
const index = readJson(path.join(E31, 'golden', 'index.json'));
const golden = index.filter((x) => x.kind !== 'PERFORMANCE').map((x) => ({
  datasetId: x.datasetId, family: x.family, adapter: x.adapter, packageId: x.packageId, kind: x.kind, contentType: x.contentType,
  description: x.description, headers: x.headers || {}, expect: x.expect || {},
  payload: fs.readFileSync(path.join(E31, x.resource), 'utf8')
}));
const perf = readJson(path.join(E31, 'golden', 'perf', 'mixed-50ps.json'));

// ------------------------------------------------------------------ people (synthetic – "First Jobword" names)
const employees = [
  { id: 'E1001', name: 'Erin Employee', first: 'Erin', sex: 'F', dob: '1985-04-12', heightCm: 168, site: 'LHR', job: 'Customer service agent', badge: 'BDG-71001', language: 'en' },
  { id: 'E1002', name: 'Paul Pilot', first: 'Paul', sex: 'M', dob: '1981-03-14', heightCm: 180, site: 'LHR', job: 'First officer', badge: 'BDG-71002', language: 'en' },
  { id: 'E1003', name: 'Rami Ramp', first: 'Rami', sex: 'M', dob: '1990-07-02', heightCm: 176, site: 'LHR', job: 'Ramp agent', badge: 'BDG-71003', language: 'ar' },
  { id: 'E1004', name: 'Cara Cabin', first: 'Cara', sex: 'F', dob: '1993-11-23', heightCm: 165, site: 'LHR', job: 'Cabin crew', badge: 'BDG-71004', language: 'en' },
  { id: 'E1005', name: 'Eddie Engineer', first: 'Eddie', sex: 'M', dob: '1978-01-30', heightCm: 182, site: 'LHR', job: 'Aircraft engineer', badge: 'BDG-71005', language: 'en' },
  { id: 'E1006', name: 'Bea Baggage', first: 'Bea', sex: 'F', dob: '1988-05-19', heightCm: 160, site: 'LHR', job: 'Baggage handler', badge: 'BDG-71006', language: 'en' },
  { id: 'E1007', name: 'Fiona Fueller', first: 'Fiona', sex: 'F', dob: '1986-09-09', heightCm: 171, site: 'LHR', job: 'Into-plane fueller', badge: 'BDG-71007', language: 'en' },
  { id: 'E1008', name: 'Omar Office', first: 'Omar', sex: 'M', dob: '1983-12-01', heightCm: 174, site: 'LHR', job: 'Planning analyst', badge: 'BDG-71008', language: 'ar' },
  { id: 'E1009', name: 'Hana Handler', first: 'Hana', sex: 'F', dob: '1995-02-14', heightCm: 158, site: 'DXB', job: 'Ground handler', badge: 'BDG-72009', language: 'ar' },
  { id: 'E1010', name: 'Dev Driver', first: 'Dev', sex: 'M', dob: '1991-08-27', heightCm: 179, site: 'DXB', job: 'Airside driver', badge: 'BDG-72010', language: 'en' },
  { id: 'E1011', name: 'Lara Loader', first: 'Lara', sex: 'F', dob: '1989-04-04', heightCm: 169, site: 'LHR', job: 'Cargo loader', badge: 'BDG-71011', language: 'en' },
  { id: 'E1012', name: 'Tom Technician', first: 'Tom', sex: 'M', dob: '1984-06-16', heightCm: 185, site: 'LHR', job: 'Avionics technician', badge: 'BDG-71012', language: 'en' }
];
const users = [
  { username: 'deviceadmin1', name: 'Dana Device', title: 'Device administrator', roles: ['DEVICE_ADMIN'], apps: ['console', 'lab'], employeeId: 'S8901' },
  { username: 'devicelead1', name: 'Lee Lead', title: 'Device integration lead (admin + approver)', roles: ['DEVICE_ADMIN', 'DEVICE_APPROVER'], apps: ['console'], employeeId: 'S8905' },
  { username: 'deviceapprover1', name: 'Abe Approver', title: 'Clinical engineering approver', roles: ['DEVICE_APPROVER'], apps: ['console'], employeeId: 'S8902' },
  { username: 'biomed1', name: 'Ben Biomed', title: 'Biomedical engineer', roles: ['BIOMEDICAL'], apps: ['console'], employeeId: 'S8906' },
  { username: 'inttester1', name: 'Tess Tester', title: 'Integration tester', roles: ['INTEGRATION_TESTER'], apps: ['lab', 'console'], employeeId: 'S8903' },
  { username: 'nurse1', name: 'Nia Nurse', title: 'OH nurse', roles: ['OH_CLINICIAN'], apps: ['lab', 'console'], employeeId: 'S8907' },
  { username: 'collector1', name: 'Cole Collector', title: 'Breath-alcohol collector', roles: ['COLLECTOR'], apps: ['lab'], employeeId: 'S8908' },
  { username: 'secops1', name: 'Sam Secure', title: 'Security operations', roles: ['SECURITY_OPS'], apps: ['security', 'console'], employeeId: 'S8904' },
  { username: 'auditor1', name: 'Ava Auditor', title: 'Auditor (read-only)', roles: ['AUDITOR'], apps: ['security', 'console'], employeeId: 'S8909' }
];
const sites = [
  { code: 'LHR', name: 'Hub OH clinic (LHR)', test: false, residency: 'UK', stations: [
    { code: 'LHR-OH-ROOM-1', name: 'Clinic room 1' }, { code: 'LHR-OH-ROOM-2', name: 'Clinic room 2' }, { code: 'LHR-OH-BOOTH-1', name: 'Audiometry booth 1' },
    { code: 'LHR-OH-KIOSK-1', name: 'Reception kiosk' }] },
  { code: 'DXB', name: 'Outstation OH room (DXB)', test: false, residency: 'UK', stations: [{ code: 'DXB-OUT-ROOM-1', name: 'Outstation room 1' }] },
  { code: 'LAB', name: 'Integration test lab', test: true, residency: 'UK', stations: [{ code: 'LAB-BENCH-1', name: 'Test bench 1' }] }
];

// ------------------------------------------------------------------ E14 equipment register (calibration authority) – synthetic
const equipment = [
  ['SPIRO-HQ-01', 'MD-001', 'EA-2209-4411'], ['AUD-LHR-01', 'MD-015', 'AU300-1001'], ['VS-LHR-01', 'MD-027', 'CSM-100042'],
  ['BP-LHR-04', 'MD-037', 'UA651-77310'], ['BAT-LHR-01', 'MD-097', 'FC20-55021'], ['POC-LHR-01', 'MD-081', 'AF2-30871'],
  ['ECG-HQ-01', 'MD-045', 'MAC55-90112'], ['VS-DXB-01', 'MD-027', 'CSM-100099'], ['FIT-LHR-01', 'MD-105', 'PC8048-2201'],
  ['ECG-LHR-02', 'MD-045', 'MAC55-90140'],
  // spare, not yet provisioned (used by the provisioning scenarios)
  ['BP-LHR-07', 'MD-037', 'UA651-77344'], ['VS-LHR-03', 'MD-027', 'CSM-100077'], ['POC-LHR-02', 'MD-081', 'AF2-30902'], ['BAT-DXB-01', 'MD-097', 'FC20-55107']
].map(([id, model, serial]) => ({ equipmentId: id, modelCode: model, serial, calibrationStatus: 'IN_CALIBRATION', calibrationDueOn: '2027-03-31', lastCalibratedOn: '2026-03-31' }));

// ------------------------------------------------------------------ configurations (mirrors the E32 demo seed)
const drv = (id) => drivers.find((d) => d.packageId === id);
const COMMON = (packageId, version, extra) => Object.assign({ timeSource: 'NTP_GATEWAY', timeZone: 'Europe/London', units: 'SI', locale: 'en-GB',
  driverPackageId: packageId, driverVersion: version, referenceRangeOverrides: [], qualityGateAdditions: [], calibrationSchedule: [], verifyBeforePost: true,
  retentionClass: 'DEVICE_RAW_PAYLOAD', tenantCode: 'GROUP' }, extra || {});
const mllp = (ip, listener, port, app) => ({ deviceAddress: { ip }, mllp: { listenerEndpointId: listener, port, tls: 'REQUIRED', tlsVersion: '1.3', cipherPolicy: 'MODERN', clientCertRequired: true },
  msh: { sendingApplication: app, sendingFacility: 'OH_CLINIC', receivingApplication: 'WELLNESSZ', receivingFacility: 'WZ' }, ackMode: 'ORIGINAL', messageProfile: 'IHE_PCD_01' });
const AGT = 'EP-LHR-AGT-01', GW = 'EP-LHR-GW-01';
const specs = [
  ['1', 'MD-001', 'P2', 'LHR', 'LHR-OH-ROOM-2', AGT, 'DEVICE_HOST_AGENT', 'EA-2209-4411', 'SPIRO-HQ-01', 'spiro.ndd.easyone-connect-gdt', ['WORKLIST', 'SESSION', 'VENDOR_ID_MATCH'],
    { source: 'FOLDER_WATCH', path: 'C:\\EasyOneConnect\\Export', pattern: '*.gdt', encoding: 'ISO-8859-1', pollSeconds: 10, stabilitySeconds: 5, afterRead: 'KEEP', attachmentRule: { pairBy: 'STEM', extensions: ['pdf'] }, hostEndpointId: AGT }, 'PUBLISHED'],
  ['2', 'MD-015', 'P2', 'LHR', 'LHR-OH-BOOTH-1', AGT, 'DEVICE_HOST_AGENT', 'AU300-1001', 'AUD-LHR-01', 'audio.benson.cca200-db', ['WORKLIST', 'SESSION', 'VENDOR_ID_MATCH'],
    { source: 'DB_QUERY', dialect: 'SQLSERVER', connection: { host: 'benson-pc.lhr.example', port: 1433, database: 'Benson' }, dbCredentialRef: 'vault:device/lhr/benson-readonly',
      query: 'select * from v_wz_audiogram where modified_at > :watermark', watermarkColumn: 'modified_at', pollSeconds: 30, hostEndpointId: AGT }, 'PUBLISHED'],
  ['3', 'MD-027', 'P4', 'LHR', 'LHR-OH-ROOM-1', GW, 'GATEWAY', 'CSM-100042', 'VS-LHR-01', 'vitals.welchallyn.csm-pcd01', ['WORKLIST', 'SESSION', 'VENDOR_ID_MATCH'], mllp('10.20.30.41', GW, 2575, 'CONNEX'), 'PUBLISHED'],
  ['4', 'MD-037', 'P3', 'LHR', 'LHR-OH-ROOM-1', 'EP-LHR-TAB-01', 'MOBILE_APP', 'UA651-77310', 'BP-LHR-04', 'vitals.and.ua651ble-blp', ['SESSION', 'BADGE'],
    { transport: 'BLE', pairing: 'LE_SECURE_CONNECTIONS_PASSKEY', bonding: 'REQUIRED', bondingOwner: 'TABLET', bondingOwnerEndpointId: 'EP-LHR-TAB-01', service: '0x1810', characteristics: ['0x2A35'], indications: true,
      peripheralAllowList: [{ name: 'UA-651BLE', addressHash: sha256hex('demo-peripheral-ua651ble') }], reconnect: 'AUTO', rssiMinDbm: -80 }, 'PUBLISHED'],
  ['5', 'MD-097', 'P1', 'LHR', 'LHR-OH-ROOM-1', AGT, 'DEVICE_HOST_AGENT', 'FC20-55021', 'BAT-LHR-01', 'da.lifeloc.fc20-serial', ['WORKLIST', 'SESSION'],
    { connection: 'RS232', port: 'COM3', baud: 9600, dataBits: 8, parity: 'NONE', stopBits: 1, flowControl: 'NONE', framing: { type: 'LINE', terminator: '\r\n', maxFrameBytes: 4096 }, mode: 'PUSH', hostEndpointId: AGT }, 'PUBLISHED'],
  ['6', 'MD-081', 'P5', 'LHR', 'LHR-OH-ROOM-2', GW, 'DATA_MANAGER', 'AF2-30871', 'POC-LHR-01', 'poc.abbott.afinion2-poct1a', ['SESSION', 'VENDOR_ID_MATCH'],
    { dms: { vendor: 'INFO_HQ', endpoint: GW, deviceIds: ['AF2-30871', 'AFN2-55120'], dmsId: 'INFOHQ-LHR' }, transport: 'POCT1A_XML', operatorList: 'SYNC_FROM_DMS', qcLotPolicy: { requirePassingQc: true, qcWindowHours: 24, newLotRequiresQc: true } }, 'PUBLISHED'],
  ['7', 'MD-045', 'P4', 'LHR', 'LHR-OH-ROOM-1', GW, 'GATEWAY', 'MAC55-90112', 'ECG-HQ-01', 'ecg.ge.mac-hl7', ['WORKLIST', 'SESSION', 'VENDOR_ID_MATCH'], mllp('10.20.30.45', GW, 2576, 'MUSE'), 'PUBLISHED'],
  ['8', 'MD-027', 'P4', 'DXB', 'DXB-OUT-ROOM-1', 'EP-DXB-GW-01', 'GATEWAY', 'CSM-100099', 'VS-DXB-01', 'vitals.welchallyn.csm-pcd01', ['SESSION', 'VENDOR_ID_MATCH'], mllp('10.40.10.21', 'EP-DXB-GW-01', 2575, 'CONNEX'), 'QUARANTINED']
];

function prerequisitesFor(option, model) {
  const pack = optionPacks.options.find((o) => o.option === option);
  const items = pack.prerequisites.map((code) => ({ code, status: 'DONE', evidence: { text: 'Verified at provisioning' } }));
  if (model.confidence !== 'High') items.push({ code: 'INTERFACE_SPEC_CONFIRMED', status: 'DONE', evidence: { text: 'Vendor interface specification reviewed' } });
  return items;
}
function bindingsFor(pkg) { return pkg.bindings.map((b) => JSON.parse(JSON.stringify(b))); }

const configurations = specs.map((s, i) => {
  const [n, modelCode, option, site, station, endpoint, kind, serial, eq, pkgId, assoc, params, status] = s;
  const pkg = drv(pkgId); const model = catalogue.find((m) => m.id === modelCode);
  const publishedAt = minus(DEMO_NOW, (40 - i) * D);
  const t0 = minus(publishedAt, 2 * D);
  return {
    id: 'CFG-00000' + n, version: 1, instanceId: 'DEV-00000' + n, modelCode, option, siteCode: site, stationCode: station, status,
    currentStep: 8, paused: false, endpointId: endpoint, endpointKind: kind, serial, equipmentId: eq,
    driver: { packageId: pkg.packageId, version: pkg.version },
    stepStatus: { 1: 'VALID', 2: 'VALID', 3: 'VALID', 4: 'VALID', 5: 'VALID', 6: 'VALID', 7: 'VALID', 8: 'VALID' },
    prerequisites: prerequisitesFor(option, model), parameters: params, common: COMMON(pkg.packageId, pkg.version),
    secrets: params.dbCredentialRef ? { dbCredentialRef: { set: true, ref: params.dbCredentialRef } } : {},
    associationMethods: assoc, services: ['Spot measurement'], bindings: bindingsFor(pkg),
    tests: [{ testRunId: 'TR-' + n + '-01', mode: 'SIMULATOR', status: 'PASSED', vectors: pkg.testVectors.length, evidenceSha256: sha256hex('sim:' + pkgId + ':' + n), finishedAt: plus(t0, H) },
      { testRunId: 'TR-' + n + '-02', mode: 'LIVE', status: 'PASSED', vectors: 1, evidenceSha256: sha256hex('live:' + pkgId + ':' + n), finishedAt: plus(t0, 2 * H) }],
    nurseReview: { by: 'nurse1', at: plus(t0, 3 * H), outcome: 'ACCEPTED', note: 'Test form preview checked' },
    simulatorOnlyJustification: null, editors: ['deviceadmin1'], createdBy: 'deviceadmin1', createdAt: t0,
    submittedBy: 'deviceadmin1', submittedAt: plus(t0, 4 * H), approvedBy: 'deviceapprover1', approvedAt: plus(t0, 26 * H), coApprovedBy: null,
    certificationId: 'DCR-2026-00000' + n, publishedBy: 'deviceadmin1', publishedAt,
    history: [{ at: t0, by: 'deviceadmin1', action: 'CREATED' }, { at: plus(t0, 4 * H), by: 'deviceadmin1', action: 'SUBMITTED' },
      { at: plus(t0, 26 * H), by: 'deviceapprover1', action: 'APPROVED' }, { at: publishedAt, by: 'deviceadmin1', action: 'PUBLISHED' }]
      .concat(status === 'QUARANTINED' ? [{ at: minus(DEMO_NOW, 3 * D), by: 'system', action: 'QUARANTINED', note: 'Endpoint EP-DXB-GW-01 isolated by Security Operations' }] : [])
  };
});
// CFG-000009 – fit tester draft paused at step 4 (P6 vendor cloud)
const fit = drv('fit.tsi.link-webhook');
configurations.push({
  id: 'CFG-000009', version: 1, instanceId: 'DEV-000009', modelCode: 'MD-105', option: 'P6', siteCode: 'LHR', stationCode: 'LHR-OH-ROOM-2', status: 'DRAFT', currentStep: 4, paused: true,
  endpointId: null, endpointKind: 'VENDOR_CLOUD', serial: 'PC8048-2201', equipmentId: 'FIT-LHR-01', driver: { packageId: fit.packageId, version: fit.version },
  stepStatus: { 1: 'VALID', 2: 'VALID', 3: 'VALID', 4: 'IN_PROGRESS', 5: 'NOT_STARTED', 6: 'NOT_STARTED', 7: 'NOT_STARTED', 8: 'NOT_STARTED' },
  prerequisites: prerequisitesFor('P6', catalogue.find((m) => m.id === 'MD-105')), parameters: { webhook: { enabled: true, signatureHeader: 'X-Signature', timestampHeader: 'X-Timestamp', replayWindowSeconds: 300 }, residencyRegion: 'UK' },
  common: COMMON(fit.packageId, fit.version), secrets: {}, associationMethods: [], services: [], bindings: [], tests: [], nurseReview: null, simulatorOnlyJustification: null,
  editors: ['deviceadmin1'], createdBy: 'deviceadmin1', createdAt: minus(DEMO_NOW, 2 * D), submittedBy: null, approvedBy: null, coApprovedBy: null, certificationId: null,
  history: [{ at: minus(DEMO_NOW, 2 * D), by: 'deviceadmin1', action: 'CREATED' }, { at: minus(DEMO_NOW, 2 * D - H), by: 'deviceadmin1', action: 'PAUSED', note: 'Waiting for the vendor webhook secret' }]
});
// CFG-000010 – ECG export (P2 MUSE XML) awaiting approval; Lee Lead edited the bindings (step 5) → may not approve
const muse = drv('ecg.ge.muse-xml');
const t10 = minus(DEMO_NOW, 1 * D);
configurations.push({
  id: 'CFG-000010', version: 1, instanceId: 'DEV-000010', modelCode: 'MD-045', option: 'P2', siteCode: 'LHR', stationCode: 'LHR-OH-ROOM-2', status: 'AWAITING_APPROVAL', currentStep: 7, paused: false,
  endpointId: AGT, endpointKind: 'DEVICE_HOST_AGENT', serial: 'MAC55-90140', equipmentId: 'ECG-LHR-02', driver: { packageId: muse.packageId, version: muse.version },
  stepStatus: { 1: 'VALID', 2: 'VALID', 3: 'VALID', 4: 'VALID', 5: 'VALID', 6: 'VALID', 7: 'IN_PROGRESS', 8: 'NOT_STARTED' },
  prerequisites: prerequisitesFor('P2', catalogue.find((m) => m.id === 'MD-045')),
  parameters: { source: 'FOLDER_WATCH', path: 'D:\\MUSE\\Export\\Room2', pattern: '*.xml', encoding: 'UTF-8', pollSeconds: 10, stabilitySeconds: 5, afterRead: 'KEEP', attachmentRule: { pairBy: 'XPATH', selector: '/RestingECG/PDF/@file', extensions: ['pdf'] }, hostEndpointId: AGT },
  common: COMMON(muse.packageId, muse.version), secrets: {}, associationMethods: ['WORKLIST', 'SESSION', 'VENDOR_ID_MATCH'], services: ['Resting ECG'], bindings: bindingsFor(muse),
  tests: [{ testRunId: 'TR-10-01', mode: 'SIMULATOR', status: 'PASSED', vectors: muse.testVectors.length, evidenceSha256: sha256hex('sim:muse:10'), finishedAt: plus(t10, H) },
    { testRunId: 'TR-10-02', mode: 'LIVE', status: 'PASSED', vectors: 1, evidenceSha256: sha256hex('live:muse:10'), finishedAt: plus(t10, 2 * H) }],
  nurseReview: { by: 'nurse1', at: plus(t10, 3 * H), outcome: 'ACCEPTED', note: 'ECG test form fields correct' }, simulatorOnlyJustification: null,
  editors: ['deviceadmin1', 'devicelead1'], createdBy: 'deviceadmin1', createdAt: t10, submittedBy: 'deviceadmin1', submittedAt: plus(t10, 4 * H), approvedBy: null, coApprovedBy: null, certificationId: null,
  history: [{ at: t10, by: 'deviceadmin1', action: 'CREATED' }, { at: plus(t10, 30 * 60000), by: 'deviceadmin1', action: 'STEP_4_SAVED' },
    { at: plus(t10, 50 * 60000), by: 'devicelead1', action: 'STEP_5_SAVED', note: 'Added the interpretation class binding' },
    { at: plus(t10, H), by: 'deviceadmin1', action: 'STEP_6_TEST_PASSED' }, { at: plus(t10, 4 * H), by: 'deviceadmin1', action: 'SUBMITTED' }]
});

// ------------------------------------------------------------------ endpoints (E34) – synthetic thumbprints
const cert = (id, days) => ({ sha256: sha256hex('demo-cert:' + id), subject: 'CN=' + id + ',OU=Devices,O=WellnessZ (demo)', issuer: 'CN=WellnessZ Group Device CA (demo)',
  serial: sha256hex('serial:' + id).slice(0, 16).toUpperCase(), notBefore: minus(DEMO_NOW, 100 * D), notAfter: plus(DEMO_NOW, days * D) });
const ep = (endpointId, kind, siteCode, zone, ids, days, status, extra) => Object.assign({ endpointId, kind, siteCode, zone, tenantCode: 'GROUP', status,
  certificate: status === 'PENDING_ENROLMENT' ? null : cert(endpointId, days), tokenHash: status === 'PENDING_ENROLMENT' ? null : sha256hex('wzdemo-token-' + endpointId.toLowerCase()),
  tokenIssuedAt: minus(DEMO_NOW, 20 * D), tokenExpiresAt: plus(DEMO_NOW, 70 * D), previousTokenHash: null, previousTokenValidUntil: null,
  deviceInstanceIds: ids, createdBy: 'secops1', createdAt: minus(DEMO_NOW, 60 * D), enrolledAt: status === 'PENDING_ENROLMENT' ? null : minus(DEMO_NOW, 59 * D),
  lastSeenAt: status === 'ACTIVE' ? minus(DEMO_NOW, 2 * 60000) : minus(DEMO_NOW, 3 * D), heartbeat: { hardening: 'PASS', agentVersion: '1.0.0', os: kind === 'MOBILE_APP' || kind === 'KIOSK' ? 'Android 14 (MDM)' : 'Ubuntu 24.04 LTS' },
  isolatedAt: null, revokedAt: null, recertificationRequired: false, enrolment: null, ratePerMinuteP95: kind === 'GATEWAY' ? 30 : 6 }, extra || {});
const endpoints = [
  ep('EP-LHR-GW-01', 'GATEWAY', 'LHR', 'GATEWAY_ZONE', ['DEV-000003', 'DEV-000006', 'DEV-000007'], 365, 'ACTIVE'),
  ep('EP-LHR-AGT-01', 'DEVICE_HOST_AGENT', 'LHR', 'GATEWAY_ZONE', ['DEV-000001', 'DEV-000002', 'DEV-000005', 'DEV-000010'], 365, 'ACTIVE'),
  ep('EP-LHR-TAB-01', 'MOBILE_APP', 'LHR', 'CLINIC_LAN', ['DEV-000004'], 365, 'ACTIVE'),
  ep('EP-LHR-KSK-01', 'KIOSK', 'LHR', 'CLINIC_LAN', [], 20, 'ACTIVE'),
  ep('EP-DXB-GW-01', 'GATEWAY', 'DXB', 'GATEWAY_ZONE', ['DEV-000008'], 365, 'ISOLATED', { isolatedAt: minus(DEMO_NOW, 3 * D), recertificationRequired: true }),
  ep('EP-LHR-GW-02', 'GATEWAY', 'LHR', 'GATEWAY_ZONE', [], 0, 'PENDING_ENROLMENT', { createdAt: minus(DEMO_NOW, 1 * D),
    enrolment: { tokenHash: sha256hex('wzdemo-enrol-ep-lhr-gw-02-not-a-secret'), expiresAt: plus(DEMO_NOW, 47 * H), used: false, kind: 'GATEWAY', demoToken: 'wzdemo-enrol-ep-lhr-gw-02-not-a-secret' } })
];

const zones = [
  { code: 'DEVICE_VLAN', name: 'Medical-device VLAN / SSID', description: 'Medical devices and vendor PCs; deny-by-default, no internet, no direct path to the core', defaultPolicy: 'DENY' },
  { code: 'GATEWAY_ZONE', name: 'Gateway zone (clinic DMZ)', description: 'Clinic gateways and Device Host agents; outbound-only mTLS to the core', defaultPolicy: 'DENY' },
  { code: 'CLINIC_LAN', name: 'Clinic LAN / staff SSID', description: 'Nurse tablets and kiosks (managed devices, MDM)', defaultPolicy: 'DENY' },
  { code: 'VENDOR_CLOUD', name: 'Vendor cloud (internet)', description: 'Vendor clouds sending webhooks or polled with OAuth2 client credentials', defaultPolicy: 'DENY' },
  { code: 'CORE', name: 'Core platform', description: 'Integration engine and OH record (no inbound from device zones except listed conduits)', defaultPolicy: 'DENY' }
];
const conduits = [
  ['C-DEV-GW-MLLP', 'DEVICE_VLAN', 'GATEWAY_ZONE', 'MLLP_TLS', '2575', 'INBOUND', 'HL7 v2 over MLLP (TLS; plaintext only with a documented segmented-VLAN exception) from devices and data managers to the gateway'],
  ['C-DEV-GW-POCT', 'DEVICE_VLAN', 'GATEWAY_ZONE', 'MLLP_TLS', '5001', 'INBOUND', 'POCT1-A2 XML from the POC data manager to the gateway'],
  ['C-DEV-GW-DICOM', 'DEVICE_VLAN', 'GATEWAY_ZONE', 'DICOM_TLS', '2762', 'INBOUND', 'DICOM export drop / C-STORE to the gateway'],
  ['C-DEV-GW-BLE', 'DEVICE_VLAN', 'GATEWAY_ZONE', 'BLE', 'n/a', 'LOCAL', 'Bluetooth LE Secure Connections, bonded-device allow-list'],
  ['C-DEV-AGT-SER', 'DEVICE_VLAN', 'GATEWAY_ZONE', 'SERIAL', 'n/a', 'LOCAL', 'RS-232 / USB-CDC on the Device Host (USB VID/PID allow-list)'],
  ['C-GW-CORE', 'GATEWAY_ZONE', 'CORE', 'HTTPS_MTLS', '443', 'OUTBOUND', 'Gateway / Device Host agent to the ingest API (mutual TLS + per-endpoint token)'],
  ['C-LAN-CORE', 'CLINIC_LAN', 'CORE', 'HTTPS_MTLS', '443', 'OUTBOUND', 'Nurse tablet and kiosk to the ingest API (mutual TLS + per-endpoint token)'],
  ['C-VC-CORE', 'VENDOR_CLOUD', 'CORE', 'HTTPS_HMAC', '443', 'INBOUND', 'Vendor webhooks (HMAC-SHA256 signature, 300 s replay window)'],
  ['C-CORE-VC', 'CORE', 'VENDOR_CLOUD', 'HTTPS_OAUTH2', '443', 'OUTBOUND', 'Engine polls vendor clouds with OAuth2 client credentials (egress allow-list)']
].map(([id, fromZone, toZone, protocol, ports, direction, description]) => ({ id, fromZone, toZone, protocol, ports, direction, description }));
const anomalyRules = [
  ['NEW_DEVICE_IDENTITY', 'First authentication of an endpoint, or first message from an unknown device serial behind a known gateway', 'MEDIUM', 1, 60, 'ALERT', null],
  ['UNKNOWN_ENDPOINT', 'Authentication attempts with an endpoint id that does not exist (per source address)', 'MEDIUM', 3, 15, 'ALERT', null],
  ['FAILED_AUTH_BURST', 'Failed authentications for one endpoint id or source address within the window; auto-isolation at the isolation threshold', 'HIGH', 10, 5, 'ISOLATE', 30],
  ['MESSAGE_RATE_SPIKE', 'Requests per minute above 5x the 7-day p95 or above the ceiling; sustained for the window -> throttle; playbook escalates to isolation', 'MEDIUM', 600, 10, 'THROTTLE', null],
  ['UNEXPECTED_PROTOCOL', 'Message kind / path not allowed for the endpoint type', 'HIGH', 1, 60, 'REJECT_ALERT', null],
  ['UNEXPECTED_PORT', 'Gateway heartbeat reports a listener / port outside its conduits', 'HIGH', 1, 60, 'ALERT', null],
  ['ZONE_VIOLATION', 'Endpoint called from an address outside its zone / CIDR policy, or a device tried to reach the core directly', 'HIGH', 1, 60, 'REJECT_ALERT', null],
  ['CERTIFICATE_ANOMALY', 'Thumbprint change without renewal, unexpected issuer or expired certificate presented', 'HIGH', 1, 60, 'REJECT_ALERT', null],
  ['CERTIFICATE_EXPIRING', 'Endpoint certificate expires within the warning period (threshold = days)', 'LOW', 30, 1440, 'NOTIFY', null],
  ['HARDENING_DRIFT', 'Heartbeat hardening status DRIFT or FAIL', 'MEDIUM', 1, 60, 'ALERT', null],
  ['WEBHOOK_SIGNATURE_FAILURES', 'Webhook signature failures per connector within the window', 'HIGH', 5, 10, 'ALERT', null],
  ['OFF_HOURS_TRAFFIC', 'Data traffic from an endpoint outside the clinic hours of its site', 'LOW', 1, 1440, 'ALERT', null],
  ['OFF_HOURS_CONFIG_ACCESS', 'Configuration export or secret metadata access outside clinic hours', 'LOW', 1, 1440, 'ALERT', null],
  ['PAYLOAD_SCHEMA_ANOMALY', 'Unparseable or profile-violating payloads from one endpoint within the window', 'MEDIUM', 5, 10, 'ALERT', null],
  ['CLOCK_SKEW', 'Edge or device clock skew above the configured maximum', 'LOW', 1, 60, 'ALERT', null]
].map(([code, description, severity, threshold, windowMinutes, action, isolateThreshold]) => ({ code, description, severity, enabled: true, threshold, windowMinutes, action, isolateThreshold }));

// ------------------------------------------------------------------ calibration / QC (E32, proposed schedules – clinical sign-off pending)
const schedules = [
  ['AUDIO_DAILY_BIOLOGICAL', 'AUDIOMETRY', 24], ['SPIRO_SYRINGE_3L', 'SPIROMETRY', 24], ['SPIRO_LINEARITY', 'SPIROMETRY', 168], ['BP_VALIDATION_INTERVAL', 'VITALS', 8760],
  ['BREATH_ACCURACY_CHECK', 'DRUG_ALCOHOL', 720], ['POC_QC_LOT', 'POC', 720], ['FIT_DAILY_CHECK', 'RESP_ERGONOMICS', 24]
].map(([checkType, family, intervalHours]) => ({ checkType, family, intervalHours, graceMinutes: 0, lockout: true, reminderBeforeHours: 2 }));
let chk = 0;
const check = (instanceId, checkType, at, outcome, extra) => Object.assign({ id: 'QC-' + String(++chk).padStart(5, '0'), instanceId, checkType, performedAt: at, outcome, by: 'biomed1' }, extra || {});
const calibrationChecks = [
  check('DEV-000001', 'SPIRO_SYRINGE_3L', minus(DEMO_NOW, 2 * D), 'PASS', { values: { volumeL: 3.01, errorPct: 0.3 } }),
  check('DEV-000001', 'SPIRO_LINEARITY', minus(DEMO_NOW, 2 * D), 'PASS'),
  check('DEV-000002', 'AUDIO_DAILY_BIOLOGICAL', minus(DEMO_NOW, H), 'PASS'),
  check('DEV-000003', 'BP_VALIDATION_INTERVAL', minus(DEMO_NOW, 30 * D), 'PASS'),
  check('DEV-000004', 'BP_VALIDATION_INTERVAL', minus(DEMO_NOW, 30 * D), 'PASS'),
  check('DEV-000005', 'BREATH_ACCURACY_CHECK', minus(DEMO_NOW, 5 * D), 'PASS', { values: { referenceGas: 0.040, measured: 0.041 } }),
  check('DEV-000006', 'POC_QC_LOT', minus(DEMO_NOW, 2 * D), 'PASS', { lot: 'AF-LOT-2026-10', lotExpiry: '2026-12-29' }),
  check('DEV-000008', 'BP_VALIDATION_INTERVAL', minus(DEMO_NOW, 60 * D), 'PASS')
];
const instanceChecks = { 'DEV-000001': ['SPIRO_SYRINGE_3L', 'SPIRO_LINEARITY'], 'DEV-000002': ['AUDIO_DAILY_BIOLOGICAL'], 'DEV-000003': ['BP_VALIDATION_INTERVAL'],
  'DEV-000004': ['BP_VALIDATION_INTERVAL'], 'DEV-000005': ['BREATH_ACCURACY_CHECK'], 'DEV-000006': ['POC_QC_LOT'], 'DEV-000007': [], 'DEV-000008': ['BP_VALIDATION_INTERVAL'], 'DEV-000010': [] };

// ------------------------------------------------------------------ worklist (platform orders / tests), consents
const worklist = [
  { id: 'ORD-2026-000101', employeeId: 'E1002', category: 'SPIROMETRY', ref: 'ORD-2026-000101', module: 'E07', status: 'OPEN', note: 'Respirator clearance spirometry' },
  { id: 'APT-2026-000214', employeeId: 'E1001', category: 'IMAGING', ref: 'ACC-20261030-01', module: 'E07', status: 'OPEN', note: 'Silica surveillance chest X-ray' },
  { id: 'DAT-2026-000042', employeeId: 'E1003', category: 'BREATH_ALCOHOL', ref: 'DAT-2026-000042', module: 'E10', status: 'OPEN', note: 'Random breath-alcohol test (pool POOL-GND-RAMP)' },
  { id: 'ORD-2026-000117', employeeId: 'E1001', category: 'POC', ref: 'ORD-2026-000117', module: 'E07', status: 'OPEN', note: 'Annual HbA1c (health surveillance)' }
];
const consents = [{ employeeId: 'E1001', purpose: 'WEARABLE_DATA', status: 'GIVEN', at: minus(DEMO_NOW, 30 * D) }];

// ------------------------------------------------------------------ prior traffic and one transient dead letter
const audioRaw = golden.find((g) => g.datasetId === 'audiometry/cca200-db/normal');
const deadLetters = [{
  id: 'DL-000001', messageId: 'MSG-000001', stage: 'ROUTE', code: 'route_target_unavailable', detailMasked: 'E03 result sink unavailable (HTTP 503) – 5 attempts with back-off 5 s, 30 s, 2 min, 10 min, 1 h',
  attempts: 5, firstFailedAt: minus(DEMO_NOW, 14 * H), lastFailedAt: minus(DEMO_NOW, 13 * H), status: 'OPEN', resolvedBy: null, reason: null
}];
const messages = [{
  id: 'MSG-000001', receivedAt: minus(DEMO_NOW, 14 * H), endpointId: AGT, instanceId: 'DEV-000002', packageId: 'audio.benson.cca200-db', driverVersion: '1.0.0', adapter: 'FILE_CSV',
  contentType: 'text/csv', headers: audioRaw.headers, idempotencyKey: 'audiogram_1.csv:' + sha256hex(audioRaw.payload).slice(0, 16), raw: audioRaw.payload, sha256: sha256hex(audioRaw.payload),
  status: 'DEAD_LETTER', stage: 'ROUTE', mode: 'LIVE', datasetId: 'audiometry/cca200-db/normal', trace: [], observationIds: []
}];

const seed = {
  meta: { version: '1.0.0', schema: 1, demoNow: DEMO_NOW, title: 'WellnessZ Medical Device Integration Engine – demo data (E31–E34)', generatedBy: 'demo-devices/tools/gen-data.js',
    note: 'People are synthetic. Device brands and models are entries from the client inventory (133 models). Driver packages and golden datasets are the engine resources. Signing key and tokens are demo values, not secrets.',
    sources: { inventory: 'modules/wz-e32-device-console/src/main/resources/e32/device-inventory.json', optionPacks: 'modules/wz-e32-device-console/src/main/resources/e32/option-packs.json',
      drivers: 'modules/wz-e31-device-engine/src/main/resources/e31/drivers/', golden: 'modules/wz-e31-device-engine/src/main/resources/e31/golden/' } },
  config: { autoFileThreshold: 0.95, suggestThreshold: 0.60, sessionWindowSeconds: 900, maxSkewSeconds: 120, isolationSlaMinutes: 15, unmatchedSlaHours: 8, ocrMinConfidence: 0.9,
    enrolmentTokenHours: 72, endpointTokenDays: 90, tokenGraceMinutes: 15, allowedCloudHosts: ['api.tsilink.example', '.withings.example'], residency: 'UK',
    webhookSecrets: { 'fit.tsi.link-webhook': 'wzdemo-webhook-secret-tsi-not-a-secret' }, idPrefixes: { site: ['E', 'EMP'] } },
  employees, users, sites, catalogue, optionPacks, drivers, perfProfile: perf, golden,
  signingKeys: [
    { keyId: KEY_ACTIVE.keyId, algorithm: 'Ed25519', publicKey: KEY_ACTIVE.publicKey, status: 'ACTIVE', createdAt: '2026-01-05T09:00:00Z' },
    { keyId: KEY_RETIRED.keyId, algorithm: 'Ed25519', publicKey: KEY_RETIRED.publicKey, status: 'RETIRED', createdAt: '2025-02-03T09:00:00Z' }
  ],
  equipment, configurations, endpoints, zones, conduits, anomalyRules, alerts: [
    { id: 'ALR-000001', rule: 'CERTIFICATE_EXPIRING', severity: 'LOW', endpointId: 'EP-LHR-KSK-01', status: 'OPEN', raisedAt: minus(DEMO_NOW, 2 * H), detail: 'Kiosk certificate expires in 20 days' }
  ],
  incidents: [{ incidentRef: 'DSI-2026-000001', endpointId: 'EP-DXB-GW-01', alertIds: [], reason: 'FAILED_AUTH_BURST', detectedAt: minus(DEMO_NOW, 3 * D + 12 * 60000), isolatedAt: minus(DEMO_NOW, 3 * D),
    evidence: [{ type: 'EVENTS', sha256: sha256hex('evidence:dxb:events'), archiveId: 'ARC-DXB-0001' }], status: 'OPEN', timeline: [
      { at: minus(DEMO_NOW, 3 * D + 12 * 60000), text: '34 failed authentications in 5 minutes from 10.40.10.99' }, { at: minus(DEMO_NOW, 3 * D), text: 'Endpoint isolated automatically (FAILED_AUTH_BURST ≥ 30)' }] }],
  calibration: { schedules, checks: calibrationChecks, instanceChecks },
  worklist, consents, captureSessions: [], messages, observations: [], unmatched: [], deadLetters, conformanceRuns: [],
  securityEvents: [
    ['ENDPOINT_CREATED', 'LOW', 'EP-LHR-GW-01', 'secops1'], ['ENROLMENT_SUCCEEDED', 'LOW', 'EP-LHR-GW-01', 'EP-LHR-GW-01'], ['ENDPOINT_CREATED', 'LOW', 'EP-LHR-AGT-01', 'secops1'],
    ['ENROLMENT_SUCCEEDED', 'LOW', 'EP-LHR-AGT-01', 'EP-LHR-AGT-01'], ['ENDPOINT_CREATED', 'LOW', 'EP-LHR-TAB-01', 'secops1'], ['ENROLMENT_SUCCEEDED', 'LOW', 'EP-LHR-TAB-01', 'EP-LHR-TAB-01'],
    ['ENDPOINT_CREATED', 'LOW', 'EP-DXB-GW-01', 'secops1'], ['ENDPOINT_AUTH_FAILED', 'HIGH', 'EP-DXB-GW-01', 'KERNEL'], ['ENDPOINT_ISOLATED', 'CRITICAL', 'EP-DXB-GW-01', 'system'],
    ['ENDPOINT_CREATED', 'LOW', 'EP-LHR-GW-02', 'secops1'], ['CERTIFICATE_EXPIRING', 'LOW', 'EP-LHR-KSK-01', 'system']
  ].map(([type, severity, endpointId, actor], i) => ({ type, severity, endpointId, actor, sourceModule: 'E34', reasonCode: null, attributes: {}, occurredAt: minus(DEMO_NOW, (60 - i * 5) * D / 10) })),
  audit: []
};

const banner = '/* WellnessZ Medical Device Integration Engine – DEMO DATA FILE (generated by demo-devices/tools/gen-data.js – edit the generator, then rebuild).\n' +
  ' * Synthetic people only. Device brands/models are real catalogue entries from the client inventory. Demo keys and tokens are not secrets. */\n';
fs.writeFileSync(OUT, banner + 'window.WZ_DEVICE_DEMO_SEED = ' + JSON.stringify(seed, null, 1) + ';\n');
console.log('written', path.relative(ROOT, OUT), (fs.statSync(OUT).size / 1024).toFixed(1) + ' kB', catalogue.length, 'models', drivers.length, 'drivers', golden.length, 'datasets');
