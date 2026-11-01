// Unit tests for shared/codecs.js against the real driver packages and golden datasets (no browser).
//   node demo-devices/test/codecs.test.js
const path = require('path');
const assert = require('assert');
const crypto = require('crypto');
global.window = global;
require(path.join(__dirname, '..', 'data', 'device-demo-data.js'));
const C = require(path.join(__dirname, '..', 'shared', 'codecs.js'));
const SEED = global.WZ_DEVICE_DEMO_SEED;

let passed = 0; const failures = [];
function test(name, fn) { try { fn(); passed++; } catch (e) { failures.push(name + ': ' + e.message); } }

// ------------------------------------------------------------------ primitives
test('sha256 matches node crypto', () => {
  ['', 'abc', 'MSH|^~\\&|x', 'é – unicode ✓', 'a'.repeat(1000)].forEach((s) => assert.strictEqual(C.sha256(s), crypto.createHash('sha256').update(s, 'utf8').digest('hex')));
});
test('sha1 matches node crypto', () => assert.strictEqual(C.sha1('hello world'), crypto.createHash('sha1').update('hello world').digest('hex')));
test('hmac-sha256 matches node crypto', () => {
  assert.strictEqual(C.hmacSha256('key', 'The quick brown fox'), crypto.createHmac('sha256', 'key').update('The quick brown fox').digest('hex'));
  const long = 'k'.repeat(100); assert.strictEqual(C.hmacSha256(long, 'x'), crypto.createHmac('sha256', long).update('x').digest('hex'));
});
test('uuidv5 matches RFC 4122 example (DNS namespace, python.org)', () => assert.strictEqual(C.uuidv5('python.org', '6ba7b810-9dad-11d1-80b4-00c04fd430c8'), '886313e1-3b8a-5372-9b90-0c9aee199e5d'));
test('SFLOAT decode / encode', () => {
  assert.strictEqual(C.sfloatDecode(0x007A), 122); assert.strictEqual(C.sfloatDecode(0xF0A3), 16.3); assert.strictEqual(C.sfloatDecode(0x2001), 100);
  assert.ok(Number.isNaN(C.sfloatDecode(0x07FF))); assert.strictEqual(C.sfloatDecode(C.sfloatEncode(36.6, -1)), 36.6); assert.strictEqual(C.sfloatDecode(C.sfloatEncode(-12, 0)), -12);
});
test('FLOAT decode', () => { assert.strictEqual(C.floatDecode(0xFF000170), 36.8); assert.strictEqual(C.floatDecode(0xFF0003DA), 98.6); });
test('GDT length check rejects broken lines', () => { assert.throws(() => C.parseGdt('0158000\r\nnot a gdt line\r\n'), /declared length/); });
test('CSV unterminated quote', () => { assert.throws(() => C.parseCsv('a,b\r\n1,"x\r\n'), /unterminated/); });
test('XML truncated document', () => { assert.throws(() => C.parseXml('<a><b>1</b>'), /not closed/); });
test('HL7 ACK', () => { const m = C.parseHl7('MSH|^~\\&|A|B|WELLNESSZ|WZ|20261030||ORU^R01|X1|P|2.6\rPID|||E1'); assert.ok(/MSA\|AA\|X1/.test(C.hl7Ack(m, 'AA', null, '2026-10-30T09:00:00Z'))); });
test('ID normalisation', () => { assert.strictEqual(C.normaliseId('e01001'), 'E1001'); assert.strictEqual(C.normaliseId('EMP-1001'), 'E1001'); });

// ------------------------------------------------------------------ every driver test vector
const permissive = {
  gates: { calibration: () => ({ status: 'VALID', equipmentId: 'TEST', dueOn: '2027-01-01' }), qc: () => 'OK', lot: () => ({ ok: true }), consentFor: () => true, validationListed: true, maxSkewSeconds: 120 },
  association: { employees: SEED.employees, worklist: [], thresholds: { auto: 0.95, suggest: 0.6 } },
  employeeById: (id) => SEED.employees.find((e) => e.id === id)
};
let vectors = 0;
SEED.drivers.forEach((d) => {
  d.testVectors.forEach((tv) => {
    vectors++;
    test(d.packageId + ' / ' + tv.name, () => {
      const dsId = tv.input.replace(/^golden\//, '').replace(/\.[a-z0-9]+$/, '');
      const ds = SEED.golden.find((g) => g.datasetId === dsId) || SEED.golden.find((g) => g.datasetId.startsWith(dsId.split('/').slice(0, 2).join('/')) && tv.input.endsWith(g.datasetId.split('/').pop() + tv.input.slice(tv.input.lastIndexOf('.'))));
      assert.ok(ds, 'dataset ' + tv.input + ' not in the data file');
      const ctx = Object.assign({}, permissive, { gates: Object.assign({}, permissive.gates, { validationListed: !!(d.protocol && d.protocol.validationListed) }) });
      const r = C.runPipeline({ driver: d, raw: ds.payload, headers: ds.headers, receivedAt: '2026-10-30T09:20:00Z', messageId: 'T-' + dsId, instanceId: 'DEV-T', rawSha256: C.sha256(ds.payload), ctx });
      if (tv.expect.error) { assert.ok(r.error, 'expected error ' + tv.expect.error + ' but got ' + r.status); assert.strictEqual(r.error.code, tv.expect.error); return; }
      assert.ok(!r.error, 'unexpected error ' + JSON.stringify(r.error));
      const rec = r.records[0];
      Object.keys(tv.expect).filter((k) => k !== 'gates').forEach((k) => {
        const c = rec.norm.components.find((x) => x.key === k);
        assert.ok(c, 'component ' + k + ' missing');
        if (typeof tv.expect[k] === 'number') assert.ok(Math.abs(c.value - tv.expect[k]) < 0.051, k + ' = ' + c.value + ', expected ' + tv.expect[k]);
        else assert.strictEqual(String(c.value), String(tv.expect[k]), k);
      });
      Object.entries(tv.expect.gates || {}).forEach(([g, out]) => {
        const f = rec.gates.flags.find((x) => x.gate === g); assert.ok(f, 'gate ' + g + ' not evaluated'); assert.strictEqual(f.outcome, out, g + ' → ' + f.outcome + ' (' + f.message + ')');
      });
    });
  });
});

// ------------------------------------------------------------------ golden kinds: malformed → error, time-skew → corrected, normal → no error
SEED.golden.forEach((g) => {
  const d = SEED.drivers.find((x) => x.packageId === g.packageId);
  test('golden ' + g.datasetId, () => {
    const r = C.runPipeline({ driver: d, raw: g.payload, headers: g.headers, receivedAt: '2026-10-30T09:20:00Z', messageId: 'G-' + g.datasetId, instanceId: 'DEV-T', rawSha256: C.sha256(g.payload), ctx: permissive });
    if (g.kind === 'MALFORMED') assert.ok(r.error && /parse_failed|profile_violation/.test(r.error.code), 'malformed should fail: ' + r.status);
    else assert.ok(!r.error, 'unexpected ' + JSON.stringify(r.error));
    if (g.kind === 'TIME_SKEW') assert.strictEqual(r.records[0].gates.flags.find((f) => f.gate === 'CLOCK_SKEW').outcome, 'WARN');
    if (g.expect.error) assert.strictEqual(r.error.code, g.expect.error);
  });
});

// ------------------------------------------------------------------ simulators round-trip through the parsers
const drv = (id) => SEED.drivers.find((d) => d.packageId === id);
const at = '2026-10-30T09:40:00Z';
function sim(pkg, raw, headers) { return C.runPipeline({ driver: drv(pkg), raw, headers: headers || {}, receivedAt: at, messageId: 'S-' + pkg, instanceId: 'DEV-S', rawSha256: C.sha256(raw), ctx: permissive }); }
const val = (r, k) => r.records[0].norm.components.find((c) => c.key === k).value;
test('sim HL7 vitals', () => { const r = sim('vitals.welchallyn.csm-pcd01', C.SIM.hl7Vitals({ at, employeeId: 'E1003', name: 'Rami Ramp', systolic: 131, diastolic: 84, pulse: 71, spo2: 97, temperature: 36.6, cuff: 'ADULT' })); assert.strictEqual(val(r, 'systolic'), 131); assert.strictEqual(r.records[0].association.employeeId, 'E1003'); assert.ok(/MSA\|AA/.test(r.ack)); });
test('sim HL7 without PID → AE', () => { const r = sim('vitals.welchallyn.csm-pcd01', C.SIM.hl7Vitals({ at, employeeId: 'E1003', systolic: 131, diastolic: 84, omitPid: true })); assert.strictEqual(r.error.code, 'parse_failed'); assert.ok(/MSA\|AE/.test(r.ack)); });
test('sim BLE BP mmHg and kPa', () => { assert.strictEqual(val(sim('vitals.and.ua651ble-blp', C.SIM.bleBloodPressure({ at, systolic: 128, diastolic: 82, pulse: 70 })), 'systolic'), 128); assert.strictEqual(val(sim('vitals.and.ua651ble-blp', C.SIM.bleBloodPressure({ at, systolic: 128, diastolic: 82, pulse: 70, unit: 'kPa' })), 'systolic'), 128); });
test('sim BLE PLX / HTP / WSS', () => { assert.strictEqual(val(sim('vitals.nonin.plx-ble', C.SIM.blePulseOximeter({ at, spo2: 96, pulse: 77 })), 'spo2'), 96); assert.strictEqual(val(sim('vitals.withings.thermo-htp', C.SIM.bleThermometer({ at, temperature: 37.4 })), 'temperatureC'), 37.4); assert.strictEqual(val(sim('vitals.tanita.wss-ble', C.SIM.bleScale({ at, weightKg: 82.5, heightCm: 177 })), 'heightCm'), 177); });
test('sim POCT1-A', () => assert.strictEqual(val(sim('poc.abbott.afinion2-poct1a', C.SIM.poct1a({ at, employeeId: 'E1001', value: 5.7, lot: 'AF-LOT-2026-10' })), 'hba1c'), 5.7));
test('sim GDT spirometry grade A', () => { const r = sim('spiro.ndd.easyone-connect-gdt', C.SIM.gdtSpirometry({ at, employeeId: 'E1002', last: 'Pilot', first: 'Paul', dob: '1981-03-14', sex: 'M', heightCm: 180, fev1: 3.9, fvc: 4.95, pef: 9.1, fef2575: 3.8, grade: 'A', nAccept: 3, fev1Trials: '3.90;3.86;3.84', fvcTrials: '4.95;4.90;4.88' })); assert.strictEqual(val(r, 'fev1L'), 3.9); assert.ok(r.records[0].norm.predicted.fev1PredictedL > 3); });
test('sim CSV audiogram', () => assert.strictEqual(val(sim('audio.benson.cca200-db', C.SIM.csvAudiogram({ at, employeeId: 'E1005', sex: 'M', age: 48, left: [10, 15, 15, 20, 30, 35, 25], right: [10, 10, 15, 20, 25, 30, 25], retestL: 15, retestR: 10 })), 'left.4000'), 30));
test('sim serial FC20', () => assert.strictEqual(val(sim('da.lifeloc.fc20-serial', C.SIM.serialFc20({ at, brac: 0.021, type: 'CONFIRM', testNo: 44, cal: '2026-10-01', serial: 'FC20-55021' })), 'brac'), 0.021));
test('sim FHIR wearable', () => assert.strictEqual(val(sim('wear.aggregator.fhir-pcha', C.SIM.fhirWearable({ employeeId: 'E1001', date: '2026-10-29', steps: 10234, sleep: 401 })), 'steps'), 10234));
test('sim webhook HMAC', () => { const w = C.SIM.tsiWebhook({ at, employeeId: 'E1001', fitFactor: 210, exercises: ['BENDING', 'TALKING', 'HEAD_SIDE_TO_SIDE', 'HEAD_UP_DOWN'] }, 's3'); assert.strictEqual(w.headers['X-Signature'], 'sha256=' + crypto.createHmac('sha256', 's3').update(w.headers['X-Timestamp'] + '.' + w.body).digest('hex')); assert.strictEqual(val(sim('fit.tsi.link-webhook', w.body), 'overallFitFactor'), 210); });
test('sim DICOM JSON', () => assert.strictEqual(val(sim('img.cxr.dicomweb', C.SIM.dicomJson({ at, employeeId: 'E1001', accession: 'ACC-1' })), 'modality'), 'DX'));

console.log(`codecs: ${passed} passed, ${failures.length} failed (${vectors} driver test vectors, ${SEED.golden.length} golden datasets)`);
if (failures.length) { console.log(failures.join('\n')); process.exit(1); }
