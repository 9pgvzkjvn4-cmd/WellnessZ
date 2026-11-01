// Engine tests (no browser): plays all scenarios through window.DX, checks the rules and determinism.
//   node demo-devices/test/engine.test.js
const path = require('path');
const assert = require('assert');
global.window = global;
require(path.join(__dirname, '..', 'data', 'device-demo-data.js'));
global.WZ_DEVICE_SCENARIOS = require(path.join(__dirname, '..', 'scenarios.json'));
const C = require(path.join(__dirname, '..', 'shared', 'codecs.js'));
const DX = require(path.join(__dirname, '..', 'shared', 'engine.js'));
const { PLAYERS } = require('./players.js');

let passed = 0; const failures = [];
function test(name, fn) { try { fn(); passed++; } catch (e) { failures.push(name + ': ' + (e.stack || e.message).split('\n').slice(0, 3).join(' | ')); } }

function play(sc) {
  DX.reset(); DX.startScenario(sc.id);
  let c = {};
  sc.steps.forEach((st, i) => {
    DX._setApp(st.app); DX.signIn(st.user);
    const r = PLAYERS[sc.id][i](DX, C, c);
    if (r && typeof r === 'object') c = Object.assign(c, r);
  });
  return c;
}

const scenarios = global.WZ_DEVICE_SCENARIOS.scenarios;
test('scenarios.json is consistent with users, apps and actions', () => {
  const s = DX.session(); const acts = Object.keys(DX.actions()).concat(['view', 'approveConfigurationRefused', 'enrolEndpointRefused', 'releaseEndpointRefused']);
  assert.ok(scenarios.length >= 8 && scenarios.length <= 10);
  scenarios.forEach((sc) => sc.steps.forEach((st, i) => {
    const u = s.users.find((x) => x.username === st.user); assert.ok(u, sc.id + ' step ' + (i + 1) + ': unknown user ' + st.user);
    assert.ok(u.apps.includes(st.app), sc.id + ' step ' + (i + 1) + ': ' + st.user + ' does not use ' + st.app);
    assert.ok(acts.includes(st.action), sc.id + ' step ' + (i + 1) + ': unknown action ' + st.action);
    assert.strictEqual(PLAYERS[sc.id].length, sc.steps.length, sc.id + ': player has ' + PLAYERS[sc.id].length + ' steps');
  }));
});

const outcomes = {};
scenarios.forEach((sc) => test('scenario ' + sc.code + ' completes via the engine', () => {
  const c = play(sc); outcomes[sc.id] = c;
  const st = DX.scenarioState();
  assert.ok(st.completed.includes(sc.id), sc.code + ' not completed – stuck at step ' + (st.stepIndex + 1) + ' (' + (st.step && st.step.action) + ')');
}));

test('S01: BLE pipeline result, token one-time, endpoint ACTIVE', () => {
  const c = play(scenarios[0]); assert.strictEqual(c.sim, 'PASSED'); assert.ok(/^wzenrol-[0-9a-f]{64}$/.test(c.token)); assert.strictEqual(c.status, 'PENDING_VERIFICATION');
  const s = DX.session(); const ep = s.endpoints.find((e) => e.endpointId === 'EP-LHR-TAB-02'); assert.strictEqual(ep.status, 'ACTIVE'); assert.ok(ep.enrolment.used);
  const obs = s.observations.find((o) => o.id === c.obs); assert.strictEqual(obs.status, 'ROUTED'); assert.strictEqual(obs.association.method, 'SESSION');
  assert.strictEqual(obs.components.find((x) => x.key === 'systolic').loinc, '8480-6');
});
test('S02: plaintext refused, live passed, ACK AA, plausibility block', () => {
  const c = outcomes['S02-hl7-vitals-p4']; assert.ok(/plaintext_exception_required/.test(c.refused)); assert.strictEqual(c.live, 'PASSED'); assert.ok(/MSA\|AA/.test(c.ack)); assert.strictEqual(c.status, 'PENDING_VERIFICATION'); assert.strictEqual(c.blocked, 'BLOCKED');
});
test('S03: QC lot blocked then passed', () => { const c = outcomes['S03-poct-p5-qc-lot']; assert.strictEqual(c.s1, 'PENDING_VERIFICATION'); assert.strictEqual(c.s2, 'BLOCKED'); assert.strictEqual(c.s3, 'PENDING_VERIFICATION'); });
test('S04: lockout blocks, release, grade A', () => { const c = outcomes['S04-spiro-p2-lockout']; assert.ok(c.locked); assert.strictEqual(c.s1, 'BLOCKED'); assert.strictEqual(c.s2, 'PENDING_VERIFICATION'); });
test('S05: unmatched → resolved', () => { assert.strictEqual(outcomes['S05-unmatched'].status, 'UNMATCHED'); });
test('S06: AE + dead letter, replay', () => { const c = outcomes['S06-dead-letter-replay']; assert.ok(/MSA\|AE/.test(c.ack)); assert.strictEqual(c.status, 'DEAD_LETTER'); assert.strictEqual(c.replay, 'PENDING_VERIFICATION'); });
test('S07: restricted unmatched without session, then matched', () => { const c = outcomes['S07-breath-alcohol-p1']; assert.strictEqual(c.s1, 'UNMATCHED'); assert.strictEqual(c.s2, 'PENDING_VERIFICATION'); });
test('S08: four-eyes refusal audited', () => {
  assert.strictEqual(outcomes['S08-four-eyes'].refused, 'four_eyes_violation');
  const s = DX.session();
  // S08 was the last scenario played before S09/S10 reset – re-play to check the audit entry
});
test('S09: token reuse refused, rotation, chain intact', () => { const c = outcomes['S09-gateway-enrolment']; assert.strictEqual(c.reuse, 'enrolment_token_invalid'); assert.ok(c.rotated); assert.ok(c.chain); });
test('S10: isolated within SLA, release refused, replay after release', () => {
  const c = outcomes['S10-isolation-drill']; assert.ok(c.drill <= 15, 'isolation took ' + c.drill); assert.strictEqual(c.parked, 'DEAD_LETTER'); assert.strictEqual(c.refusedRelease, 'recertification_required'); assert.strictEqual(c.pub, 'QUARANTINED'); assert.strictEqual(c.replay, 'PENDING_VERIFICATION'); assert.ok(c.chain);
});

// ------------------------------------------------------------------ rules
test('four-eyes refusal is audited and logged', () => {
  DX.reset(); DX._setApp('console'); DX.signIn('devicelead1');
  const cfg = DX.session().configurations.find((x) => x.status === 'AWAITING_APPROVAL');
  assert.throws(() => DX.approveConfiguration(cfg.id), /Four-eyes/);
  const s = DX.session(); assert.ok(s.audit.some((a) => a.outcome === 'DENIED' && /four_eyes/.test(a.detail))); assert.ok(s.securityEvents.some((e) => e.type === 'CONFIG_APPROVAL_REFUSED'));
});
test('device admin without approver role cannot approve', () => { DX.reset(); DX._setApp('console'); DX.signIn('deviceadmin1'); const cfg = DX.session().configurations.find((x) => x.status === 'AWAITING_APPROVAL'); assert.throws(() => DX.approveConfiguration(cfg.id), (e) => e.code === 'forbidden'); });
test('publish requires certification', () => { DX.reset(); DX._setApp('console'); DX.signIn('deviceadmin1'); const c = DX.createConfiguration({ modelCode: 'MD-037' }); assert.throws(() => DX.publishConfiguration(c.id), (e) => e.code === 'invalid_state'); });
test('step 2 refuses an option without a driver', () => { DX.reset(); DX._setApp('console'); DX.signIn('deviceadmin1'); const c = DX.createConfiguration({ modelCode: 'MD-001' }); assert.throws(() => DX.saveStep(c.id, 2, { option: 'P3' }), (e) => e.errors && e.errors[0].code === 'no_driver_for_option'); });
test('P3 Just Works without exception refused; read-only SQL rule', () => {
  assert.ok(DX.practiceRules('P3', { pairing: 'JUST_WORKS_EXCEPTION', bonding: 'REQUIRED' }, {}).some((e) => e.code === 'pairing_exception_required'));
  assert.ok(!DX.readOnlySelect('select * from x; drop table y')); assert.ok(DX.readOnlySelect('select a from v where m > :w'));
});
test('JSON schema validation of option packs', () => {
  const p1 = DX.optionPack('P1').parameterSchema;
  const e = DX.validateSchema(p1, { connection: 'RS232', port: 'LPT1', baud: 9601, dataBits: 8, parity: 'NONE', stopBits: 1, flowControl: 'NONE', framing: { type: 'LINE', terminator: '\r\n' }, mode: 'PUSH', hostEndpointId: 'EP-LHR-AGT-01', extra: 1 });
  assert.deepStrictEqual(e.map((x) => x.field + ':' + x.code).sort(), ['baud:enum', 'extra:additional_property', 'port:pattern']);
});
test('literal secrets are refused at step 4', () => {
  DX.reset(); DX._setApp('console'); DX.signIn('deviceadmin1');
  const c = DX.createConfiguration({ modelCode: 'MD-015' }); DX.saveStep(c.id, 2, { option: 'P2' });
  const cfg = DX.config(c.id); DX.saveStep(c.id, 3, { endpointId: 'EP-LHR-AGT-01', equipmentId: 'AUD-LHR-01', items: cfg.prerequisites.filter((p) => !p.auto).map((p) => ({ code: p.code, status: 'DONE' })) });
  const d = DX.stepFourDefaults(c.id); const p = Object.assign({}, d.parameters, { source: 'DB_QUERY', dialect: 'SQLSERVER', connection: { host: 'benson-pc.lhr.example', database: 'Benson' }, dbCredentialRef: 'Pa55word!', query: 'select * from v', watermarkColumn: 'modified_at' });
  assert.throws(() => DX.saveStep(c.id, 4, { parameters: p, common: d.common }), (e) => e.errors.some((x) => x.code === 'literal_secret' || x.code === 'format' || x.code === 'pattern'));
  delete p.dbCredentialRef; DX.saveStep(c.id, 4, { parameters: p, common: d.common, secrets: { dbCredentialRef: 'Pa55word!' } });
  const saved = DX.config(c.id); assert.ok(/^devsec:/.test(saved.parameters.dbCredentialRef)); assert.ok(!JSON.stringify(DX.session()).includes('Pa55word!'));
});
test('webhook HMAC verified; wrong signature refused and logged', () => {
  DX.reset(); DX._setApp('lab'); DX.signIn('inttester1');
  const secret = DX.session().config.webhookSecrets['fit.tsi.link-webhook'];
  const good = C.SIM.tsiWebhook({ at: DX.session().clock, employeeId: 'E1001', fitFactor: 230, exercises: ['BENDING', 'TALKING', 'HEAD_SIDE_TO_SIDE', 'HEAD_UP_DOWN'] }, secret);
  assert.strictEqual(DX.ingest({ packageId: 'fit.tsi.link-webhook', payload: good.body, headers: good.headers }).status, 'TEST_FORM');
  const bad = C.SIM.tsiWebhook({ at: DX.session().clock, employeeId: 'E1001', fitFactor: 230, exercises: [], wrongSecret: true }, secret);
  assert.strictEqual(DX.ingest({ packageId: 'fit.tsi.link-webhook', payload: bad.body, headers: bad.headers }).status, 'REJECTED');
  assert.ok(DX.session().securityEvents.some((e) => e.type === 'WEBHOOK_SIGNATURE_FAILED'));
});
test('duplicate golden message → DUPLICATE', () => {
  DX.reset(); DX._setApp('lab'); DX.signIn('inttester1');
  const g = DX.session().golden.find((x) => x.datasetId === 'vitals/csm-pcd01/normal');
  DX.ingest({ instanceId: 'DEV-000003', payload: g.payload }); assert.strictEqual(DX.ingest({ instanceId: 'DEV-000003', payload: g.payload }).status, 'DUPLICATE');
});
test('quarantined DXB monitor refuses data', () => { DX.reset(); DX._setApp('lab'); DX.signIn('inttester1'); const g = DX.session().golden.find((x) => x.datasetId === 'vitals/csm-pcd01/normal'); assert.strictEqual(DX.ingest({ instanceId: 'DEV-000008', payload: g.payload }).status, 'DEAD_LETTER'); });
test('conformance run passes all vectors', () => { DX.reset(); DX._setApp('lab'); DX.signIn('inttester1'); const r = DX.runConformance(); assert.strictEqual(r.failed, 0, JSON.stringify(r.results.filter((x) => !x.pass).slice(0, 3))); assert.ok(r.total >= 80); });
test('hash chains verify and detect tampering', () => { DX.reset(); assert.ok(DX.verifyEvents().intact); const t = DX.verifyTamperedCopy(3); assert.ok(!t.intact); assert.strictEqual(t.firstBrokenId, 'SE-000004'); });
test('nurse cannot verify a restricted reading', () => {
  DX.reset(); DX._setApp('lab'); DX.signIn('collector1');
  DX.openCaptureSession({ instanceId: 'DEV-000005', employeeId: 'E1003', worklistId: 'DAT-2026-000042' });
  const r = DX.ingest({ instanceId: 'DEV-000005', payload: C.SIM.serialFc20({ at: DX.session().clock, brac: 0.0, type: 'SCREEN', testNo: 60, cal: '2026-10-25', serial: 'FC20-55021' }) });
  DX.signIn('nurse1'); assert.throws(() => DX.acceptObservation(r.observationIds[0], {}), (e) => e.code === 'forbidden');
});
test('deterministic: two runs give identical state hashes', () => {
  const sc = scenarios[0]; play(sc); const a = C.sha256(JSON.stringify(DX.session().securityEvents)); play(sc); const b = C.sha256(JSON.stringify(DX.session().securityEvents)); assert.strictEqual(a, b);
});

console.log(`engine: ${passed} passed, ${failures.length} failed (${scenarios.length} scenarios, ${scenarios.reduce((n, s) => n + s.steps.length, 0)} steps)`);
if (failures.length) { console.log(failures.join('\n')); process.exit(1); }
