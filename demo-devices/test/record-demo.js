// Records the device integration demo: plays six key scenarios (S01, S02, S04, S05, S06, S10) through the built apps (one shared session, three tabs),
// signs in as each persona, opens the screen that persona uses and takes a screenshot. Writes <out>/shots/*.png and
// <out>/manifest.json ({ file, scenario, title, narration (40–80 spoken words), app, appName, user, userName, route })
// for a narrated video. Deterministic: the demo clock and ids are the same on every run.
// Usage: python3 demo-devices/build.py && node demo-devices/test/record-demo.js [outDir]
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const { PLAYERS } = require('./players.js');

const DIST = path.resolve(__dirname, '..', 'dist');
const OUT = path.resolve(process.argv[2] || path.join(DIST, 'recording'));
const FILES = { console: 'device-console.html', lab: 'integration-lab.html', security: 'security-console.html' };
const APP = { console: 'Device Console', lab: 'Integration Lab', security: 'Security Console', launcher: 'Demo launcher' };
const SCEN = require(path.join(__dirname, '..', 'scenarios.json')).scenarios;
fs.rmSync(path.join(OUT, 'shots'), { recursive: true, force: true });
fs.mkdirSync(path.join(OUT, 'shots'), { recursive: true });

const manifest = []; const errors = []; const warnings = [];
let n = 0, pages, c = {};

async function runStep(sc, i) {
  const st = sc.steps[i];
  const r = await pages[st.app].evaluate(async ([user, src, ctx]) => {
    DX.signIn(user);
    // eslint-disable-next-line no-new-func
    return new Function('DX', 'C', 'c', 'return (' + src + ')(DX, C, c)')(DX, window.DXC, ctx);
  }, [st.user, PLAYERS[sc.id][i].toString(), c]);
  if (r && typeof r === 'object') Object.assign(c, r);
}
async function open(app, user, route) {
  const p = pages[app]; await p.bringToFront();
  await p.evaluate(([u, r]) => { DX.signIn(u); location.hash = '#/' + r; }, [user, route]);
  await p.waitForTimeout(700);
  return p;
}
async function shot(app, user, route, scenario, title, narration, opts) {
  const p = route == null ? pages[app] : await open(app, user, route);
  if (opts && opts.before) await opts.before(p);
  const words = narration.trim().split(/\s+/).length;
  if (words < 40 || words > 80) warnings.push(`${title}: narration has ${words} words`);
  n += 1;
  const file = `shot-${String(n).padStart(3, '0')}.png`;
  await p.screenshot({ path: path.join(OUT, 'shots', file), fullPage: !!(opts && opts.fullPage) });
  const u = app === 'launcher' ? null : await p.evaluate(() => DX.currentUser);
  manifest.push({ file, scenario, title, narration, words, app, appName: APP[app], user: u ? u.username : null, userName: u ? u.name : null, role: u ? u.title : null, route: route || (await p.evaluate(() => location.hash.slice(2))) });
  console.log(`${file}  ${scenario.padEnd(46)} ${APP[app].padEnd(16)} ${(u && u.username) || '-'}`);
}
async function lastMessage(app) { return pages[app].evaluate(() => DX.session().messages[DX.session().messages.length - 1].id); }
async function play(sc, from, to) { for (let i = from; i < to; i++) await runStep(sc, i); }
async function reset() { await pages.console.evaluate(() => DX.reset()); await pages.console.waitForTimeout(300); c = {}; }
const S = (code) => SCEN.find((x) => x.code === code);

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, timezoneId: 'UTC', locale: 'en-GB' });
  pages = {};
  for (const [k, f] of Object.entries(FILES)) {
    const p = await context.newPage();
    p.on('pageerror', (e) => errors.push(`${k}: ${e.message}`));
    p.on('console', (m) => { if (m.type() === 'error') errors.push(`${k}: ${m.text()}`); });
    p.on('dialog', (d) => d.accept());
    await p.goto('file://' + path.join(DIST, f)); pages[k] = p;
  }
  await reset();

  // ---------------------------------------------------------------- launcher
  const launcher = await context.newPage();
  await launcher.goto('file://' + path.join(DIST, 'index.html')); await launcher.waitForTimeout(600);
  pages.launcher = launcher;
  await shot('launcher', null, null, 'Start', 'The device integration demo launcher',
    'This demo pack shows how WellnessZ connects occupational health devices. Three offline applications share one demo session and one data file: the device console for administrators and approvers, the integration lab for testers and nurses, and the security console for security operations. The catalogue holds all one hundred and thirty-three models of the client inventory, with signed driver packages and real golden datasets.');
  await launcher.close(); delete pages.launcher;

  // ---------------------------------------------------------------- S01
  let sc = S('S01'), T = 'S01 · ' + sc.title;
  await play(sc, 0, 3);
  await shot('console', 'deviceadmin1', 'wizard/' + c.cfg + '?step=4', T, 'Step 4 – parameters from the P3 option pack',
    'Scenario one provisions an A and D blood-pressure monitor over Bluetooth Low Energy. After choosing option P3 and completing the prerequisites, the administrator sees a form generated from the option-pack JSON schema. Defaults come from the certified driver package: LE Secure Connections with passkey, bonding to the nurse tablet, and the blood-pressure service and characteristic. Weak Just Works pairing is refused without a security exception.');
  await play(sc, 3, 6);
  await shot('console', 'deviceadmin1', 'wizard/' + c.cfg + '?step=6', T, 'Step 6 – simulator test with negative cases',
    'The simulator test replays every test vector of the driver package through the real pipeline: a normal reading, a truncated value that must fail parsing, and a reading reported in kilopascals that must be converted to millimetres of mercury. All three pass, and the evidence is stored as a SHA-256 hash. Because the new tablet is not yet enrolled, the live test is waived with a justification.');
  await play(sc, 6, 11);
  await shot('console', 'deviceapprover1', 'certificate/' + c.cfg, T, 'Four-eyes certification record',
    'Certification follows the four-eyes rule. The approver is a different person from the submitter and the editors, and because the live test was waived, security operations co-approves as well. The printable certification record holds the configuration snapshot hash, the signed driver package, the prerequisites, the test evidence, the nurse review and both approvals, with a data-flow summary that contains no personal data.');
  const pc = await open('console', 'deviceadmin1', 'wizard/' + c.cfg + '?step=8');
  await pc.click('[data-test=publish]'); await pc.waitForTimeout(500);
  c.token = (await pc.locator('[data-test=one-time-token]').innerText()).trim();
  c.instance = await pc.evaluate((id) => DX.config(id).instanceId, c.cfg);
  await shot('console', 'deviceadmin1', null, T, 'Publish with a one-time enrolment token',
    'Publishing creates the identity of the new tablet in the security service and shows a one-time enrolment token, exactly once. The token is valid for at most seventy-two hours, works for one kind of endpoint only, and only its hash is stored. The administrator hands it to the installer; the device now appears in the nurses\' capture-from-device list for clinic room two.');
  await pc.locator('dialog.wz-modal .wz-modal-foot button').last().click();
  await play(sc, 12, 15);
  const m1 = await lastMessage('lab');
  await shot('lab', 'inttester1', 'message/' + m1, T, 'The BLE reading through the pipeline',
    'In the integration lab the reading arrives as a Bluetooth characteristic value in hexadecimal. The pipeline decodes each IEEE eleven-zero-seventy-three SFLOAT, maps the MDC codes to LOINC and UCUM, associates the employee through the nurse\'s open capture session with confidence one, runs the quality gates, and stores the raw payload with its SHA-256 hash before anything else happens.', { fullPage: false });
  await shot('lab', 'nurse1', 'capture', T, 'The nurse verifies the reading',
    'The nurse sees exactly what the capture dialogue shows: the values, the association, the provenance and one warning. The device model is validated, but the cuff size was not reported, so the nurse must confirm it before posting. Accepting posts the observation to the occupational health record with full provenance and closes the capture session.');
  await play(sc, 15, 16);

  // ---------------------------------------------------------------- S02
  await reset(); sc = S('S02'); T = 'S02 · ' + sc.title;
  await play(sc, 0, 12);
  const m2 = await lastMessage('lab');
  await shot('lab', 'inttester1', 'message/' + m2, T, 'HL7 ORU^R01 with acknowledgement',
    'Scenario two provisions a Welch Allyn vitals monitor behind the clinic gateway over HL7 version two with TLS; plaintext was refused during configuration. After simulator and live tests and certification, the tester sends an observation result message. Every segment is parsed, the vendor employee identifier matches exactly, the cuff validation gate passes, and the device receives an application-accept acknowledgement.');
  await play(sc, 12, 13);
  const m3 = await lastMessage('lab');
  await shot('lab', 'inttester1', 'message/' + m3, T, 'Plausibility gate blocks a systolic of 400',
    'The golden dataset with a systolic pressure of four hundred shows the plausibility gate. The value is outside the plausible range defined in the driver package, so the gate blocks posting with a plain-language message. Nothing reaches the record; the observation waits as blocked, and the nurse would be asked to repeat the measurement.');

  // ---------------------------------------------------------------- S04
  await reset(); sc = S('S04'); T = 'S04 · ' + sc.title;
  await play(sc, 0, 1);
  await shot('console', 'deviceadmin1', 'calibration', T, 'The spirometer is locked out',
    'Scenario four concerns a spirometer that exports GDT files from the vendor software into a watched folder. The daily three-litre syringe check was last done two days ago, so the device is locked out. Annual calibration remains in the equipment register; the device console owns the daily checks and quality-control lots and blocks results from any device whose check is overdue.');
  await play(sc, 1, 5);
  const m4 = await lastMessage('lab');
  await shot('lab', 'inttester1', 'message/' + m4, T, 'After release: grade A and predicted values',
    'The first export was blocked with a clear message. The biomedical engineer recorded a passing syringe check and the administrator released the lockout, which is only possible after a newer passing check. The next export is associated through the platform order with confidence one, graded A against ATS and ERS criteria, and shows GLI predicted values, labelled as an approximation pending clinical verification.');

  // ---------------------------------------------------------------- S05 + S06
  await reset(); sc = S('S05'); T = 'S05 · ' + sc.title;
  await play(sc, 0, 1);
  await shot('lab', 'nurse1', 'unmatched', T, 'Nothing below 0.95 is filed automatically',
    'Scenario five shows the unmatched queue. A vendor typed the employee number in lower case with a leading zero. After normalisation the match has a confidence of zero point nine two, below the auto-file threshold of zero point nine five, so the result is never filed automatically. The nurse sees the suggested employee, confirms the identity with a reason code, and the result re-enters validation.');
  await play(sc, 1, 3);
  sc = S('S06'); T = 'S06 · ' + sc.title;
  await play(sc, 0, 2);
  await shot('lab', 'inttester1', 'dead-letters', T, 'Dead letters: discard and replay',
    'Scenario six covers failures. A message without a patient segment is acknowledged with an application error and dead-lettered; the tester discards it with a reason. An audiogram that failed overnight because the result service was unavailable is replayed from the stored raw payload. Observation identifiers are derived deterministically, so replaying a whole day of traffic never creates duplicates.');

  // ---------------------------------------------------------------- S10
  await reset(); sc = S('S10'); T = 'S10 · ' + sc.title;
  const ps = await open('security', 'secops1', 'anomaly');
  await ps.selectOption('#f-drill [name=endpointId]', 'EP-LHR-TAB-01');
  await ps.click('[data-test=start-drill]');
  await ps.locator('[data-test=drill][data-finished=yes]').waitFor({ timeout: 15000 });
  await ps.evaluate(() => DX.stepScenario(0));
  await shot('security', 'secops1', null, T, 'Isolation drill within the 15-minute SLA',
    'Scenario ten is a time-compressed isolation drill. The nurse tablet suddenly sends more than five times its usual message rate, and an unknown device serial appears behind it. The anomaly rule raises an alert, the endpoint is throttled, and the playbook isolates it eight minutes after detection, inside the fifteen-minute target. Its device is quarantined and the evidence is preserved by hash.');
  await play(sc, 1, 12);
  await shot('security', 'secops1', 'incidents', T, 'Release only after re-certification',
    'While isolated, data from the tablet is refused and parked. Security operations cannot release the endpoint until the device is re-certified, so the administrator revises the configuration, it is tested, reviewed and approved again, and only then is the endpoint released. The incident closes with its full timeline, and the parked reading is replayed.');
  await play(sc, 12, 13);
  const pe = await open('security', 'secops1', 'events');
  await pe.click('[data-test=verify-chain]'); await pe.waitForTimeout(300);
  await shot('security', 'secops1', null, T, 'Hash-chained security event log verified',
    'Every security-relevant action in all three applications is written to an append-only security event log. Each event carries a SHA-256 hash of the previous hash and its own canonical content. Verification recomputes the whole chain and reports it intact, while a tampered copy is detected at the first changed event. The log contains no personal health data and is forwarded to the SIEM.');

  await browser.close();
  fs.writeFileSync(path.join(OUT, 'manifest.json'), JSON.stringify({ title: 'WellnessZ Medical Device Integration Engine – demo walkthrough', generatedBy: 'demo-devices/test/record-demo.js', viewport: '1440x900', shots: manifest }, null, 1));
  console.log(`\n${manifest.length} screenshots -> ${OUT}`);
  if (warnings.length) console.log('Narration warnings:\n' + warnings.join('\n'));
  if (errors.length) { console.error('Console errors:\n' + errors.join('\n')); process.exit(1); }
  if (warnings.length) process.exit(1);
})().catch((e) => { console.error(e); process.exit(2); });
