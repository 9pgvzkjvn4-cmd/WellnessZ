// Playwright UI tests: every scenario of scenarios.json is played through the screens of the built apps over file://
// (three tabs, one shared session), with no console errors. The engine is only read (scenario progress, assertions).
//   python3 demo-devices/build.py && node demo-devices/test/ui.test.js [S03 S05 …]
const path = require('path');
const assert = require('assert');
const { chromium } = require('playwright');

const DIST = path.resolve(__dirname, '..', 'dist');
const FILES = { console: 'device-console.html', lab: 'integration-lab.html', security: 'security-console.html' };
const only = process.argv.slice(2);
const errors = [];
let pages, ctx;

async function settle(p, ms) { await p.waitForTimeout(ms || 120); }
async function go(app, route) { const p = pages[app]; await p.bringToFront(); await p.evaluate((r) => { location.hash = '#/' + r; }, route); await settle(p, 200); return p; }
async function as(app, user) {
  const p = pages[app]; await p.bringToFront();
  const cur = await p.evaluate(() => DX.currentUser && DX.currentUser.username);
  if (cur !== user) { await p.selectOption('#wz-demo-toolbar [data-wzt=user]', user); await settle(p, 200); }
  return p;
}
async function click(p, sel) { await p.locator(sel).first().click(); await settle(p, 200); }
async function closeModal(p) { const b = p.locator('dialog.wz-modal .wz-modal-foot button').last(); await b.click(); await settle(p); }
async function toastText(p) { return (await p.locator('.wz-toast').allInnerTexts()).join(' | '); }
async function step(p) { return p.evaluate(() => DX.scenarioState()); }
async function wizardStep(p) { return Number(await p.locator('[data-test=step-panel]').getAttribute('data-step')); }
async function instanceId(p) { return (await p.locator('[data-test=instance-id]').innerText()).trim(); }
async function setAllPrereqsDone(p) { for (const s of await p.locator('#f-step3 select[data-code]').all()) await s.selectOption('DONE'); }
async function labSend(target, fill) {
  const p = await go('lab', 'simulators');
  await p.selectOption('#f-sim [name=target]', target); await settle(p, 200);
  if (fill) await fill(p);
  if (await p.locator('[data-test=generate]').count()) await click(p, '[data-test=generate]');
  await click(p, '[data-test=send]');
  return p.locator('[data-test=result]').getAttribute('data-status');
}
async function labGolden(target, datasetId) {
  const p = await go('lab', 'simulators');
  await p.selectOption('#f-sim [name=target]', target); await settle(p, 200);
  await p.selectOption('#f-sim [name=golden]', datasetId); await settle(p);
  await click(p, '[data-test=send]');
  return p.locator('[data-test=result]').getAttribute('data-status');
}
async function acceptCard(filter, acks) {
  const p = await go('lab', 'capture');
  const card = p.locator('[data-test=obs][data-status=PENDING_VERIFICATION]').filter(filter || {}).first();
  for (const a of acks || []) await card.locator(`input[name=ack][value=${a}]`).check();
  await card.locator('[data-test=accept]').click(); await settle(p, 250);
}
async function startWizard(search, modelCode) {
  const p = await as('console', 'deviceadmin1'); await go('console', 'catalogue');
  await p.fill('#cat-search [name=q]', search); await settle(p);
  await click(p, `[data-test=model-link][data-model=${modelCode}]`);
  await click(p, '[data-test=start-provisioning]');
  return p;
}
async function certifyAndPublish(opts) {
  let p = await as('console', 'nurse1'); await click(p, '[data-test=nurse-accept]');
  p = await as('console', 'deviceadmin1'); await go('console', 'wizard/' + opts.cfg + '?step=7'); await click(p, '[data-test=submit]');
  p = await as('console', 'deviceapprover1'); await click(p, '[data-test=approve]');
  if (opts.coApprove) { p = await as('console', 'secops1'); await click(p, '[data-test=coapprove]'); }
  p = await as('console', 'deviceadmin1'); await go('console', 'wizard/' + opts.cfg + '?step=8'); await click(p, '[data-test=publish]');
  return p;
}
async function cfgIdFromHash(p) { return (await p.evaluate(() => location.hash)).match(/wizard\/([^?]+)/)[1]; }

// ------------------------------------------------------------------ scenarios
const SCENARIOS = {
  async 'S01-ble-bp-p3'() {
    let p = await startWizard('UA-651', 'MD-037');
    const cfg = await cfgIdFromHash(p); const inst = await instanceId(p);
    await click(p, '[data-test=option-P3]'); await click(p, '#f-step2 [data-test=save-step]');
    assert.strictEqual(await wizardStep(p), 3);
    await p.fill('[data-test=endpoint-input]', 'EP-LHR-TAB-02'); await p.selectOption('#f-step3 [name=equipmentId]', 'BP-LHR-07'); await p.fill('#f-step3 [name=serial]', 'UA651-77344');
    await setAllPrereqsDone(p); await click(p, '#f-step3 [data-test=save-step]');
    assert.strictEqual(await wizardStep(p), 4, 'prerequisites not accepted: ' + await toastText(p));
    assert.strictEqual(await p.inputValue('#pf-bondingOwnerEndpointId'), 'EP-LHR-TAB-02');
    await p.selectOption('#pf-pairing', 'JUST_WORKS_EXCEPTION'); await click(p, '#f-step4 [data-test=save-step]');
    assert.ok(await p.locator('[data-error-code=pairing_exception_required]').count(), 'Just Works was not refused');
    await p.selectOption('#pf-pairing', 'LE_SECURE_CONNECTIONS_PASSKEY'); await click(p, '#f-step4 [data-test=save-step]');
    assert.strictEqual(await wizardStep(p), 5, 'step 4 refused: ' + await toastText(p));
    await p.uncheck('#m-VENDOR_ID_MATCH'); await p.check('#m-BADGE'); await p.check('#m-SESSION');
    await p.selectOption('#f-step5 [name=stationCode]', 'LHR-OH-ROOM-2');
    await click(p, '[data-test=preview]');
    assert.strictEqual((await p.locator('[data-test=preview-table] [data-key=systolic]').first().innerText()).trim(), '122');
    await click(p, '#f-step5 [data-test=save-step]'); assert.strictEqual(await wizardStep(p), 6);
    await click(p, '[data-test=run-sim]'); assert.strictEqual(await p.locator('[data-test=test-run][data-mode=SIMULATOR]').first().getAttribute('data-status'), 'PASSED');
    await p.fill('#f-step6 [name=justification]', 'New tablet EP-LHR-TAB-02 is enrolled at publish; live check at first use'); await click(p, '[data-test=save-justification]');
    p = await certifyAndPublish({ cfg, coApprove: true });
    const token = (await p.locator('[data-test=one-time-token]').innerText()).trim();
    assert.ok(/^wzenrol-[0-9a-f]{64}$/.test(token), 'token ' + token); await closeModal(p);
    assert.match(await p.locator('[data-test=cfg-status]').innerText(), /Published/);
    // security: enrol the tablet
    p = await as('security', 'secops1'); await go('security', 'endpoints');
    await p.selectOption('#f-enrol [name=endpointId]', 'EP-LHR-TAB-02'); await settle(p, 200);
    await p.fill('#f-enrol [name=token]', token); await p.selectOption('#f-enrol [name=endpointKind]', 'MOBILE_APP');
    await click(p, '#f-enrol [data-act=gen-cert]'); await click(p, '[data-test=enrol]'); await closeModal(p);
    assert.match(await p.locator('[data-test=ep-status-EP-LHR-TAB-02]').innerText(), /Active/);
    // lab: capture
    p = await as('lab', 'nurse1'); await go('lab', 'capture');
    await p.selectOption('#f-session [name=instanceId]', inst); await p.selectOption('#f-session [name=employeeId]', 'E1004'); await click(p, '[data-test=open-session]');
    await click(p, `[data-test=measure-${inst}]`);
    await acceptCard({ hasText: 'Cara Cabin' }, ['cuff_size_unknown']);
    assert.ok(await p.locator('[data-test=done-obs][data-status=ROUTED]').count());
  },
  async 'S02-hl7-vitals-p4'() {
    let p = await startWizard('Connex', 'MD-027');
    const cfg = await cfgIdFromHash(p); const inst = await instanceId(p);
    await click(p, '[data-test=option-P4]'); await click(p, '#f-step2 [data-test=save-step]');
    await p.fill('[data-test=endpoint-input]', 'EP-LHR-GW-01'); await p.selectOption('#f-step3 [name=equipmentId]', 'VS-LHR-03'); await p.fill('#f-step3 [name=serial]', 'CSM-100077');
    await setAllPrereqsDone(p); await click(p, '#f-step3 [data-test=save-step]'); assert.strictEqual(await wizardStep(p), 4);
    await p.fill('#pf-deviceAddress-ip', '10.20.30.47'); await p.fill('#pf-msh-sendingApplication', 'CONNEX');
    await p.selectOption('#pf-mllp-tls', 'EXCEPTION_SEGMENTED_VLAN'); await click(p, '#f-step4 [data-test=save-step]');
    assert.ok(await p.locator('[data-error-code=plaintext_exception_required]').count(), 'plaintext MLLP was not refused');
    await p.selectOption('#pf-mllp-tls', 'REQUIRED'); await click(p, '#f-step4 [data-test=save-step]'); assert.strictEqual(await wizardStep(p), 5, await toastText(p));
    await p.check('#m-WORKLIST'); await p.check('#m-SESSION'); await p.check('#m-VENDOR_ID_MATCH'); await p.selectOption('#f-step5 [name=stationCode]', 'LHR-OH-ROOM-2');
    await click(p, '#f-step5 [data-test=save-step]'); assert.strictEqual(await wizardStep(p), 6, await toastText(p));
    p = await as('console', 'inttester1'); await click(p, '[data-test=run-sim]');
    assert.strictEqual(await p.locator('[data-test=test-run][data-mode=SIMULATOR]').first().getAttribute('data-status'), 'PASSED');
    p = await as('console', 'deviceadmin1'); await click(p, '[data-test=run-live]');
    assert.strictEqual(await p.locator('[data-test=test-run][data-mode=LIVE]').first().getAttribute('data-status'), 'PASSED');
    await certifyAndPublish({ cfg });
    await as('lab', 'inttester1');
    assert.strictEqual(await labSend(inst), 'PENDING_VERIFICATION');
    assert.match(await pages.lab.locator('[data-test=ack]').innerText(), /MSA\|AA/);
    assert.strictEqual(await labGolden(inst, 'vitals/csm-pcd01/sys-400'), 'BLOCKED');
    assert.ok(await pages.lab.locator('[data-gate=PLAUSIBILITY][data-outcome=BLOCK]').count());
  },
  async 'S03-poct-p5-qc-lot'() {
    await as('lab', 'inttester1');
    assert.strictEqual(await labSend('DEV-000006', async (p) => { await p.fill('#f-sim [name=value]', '5.6'); }), 'PENDING_VERIFICATION');
    assert.strictEqual(await labSend('DEV-000006', async (p) => { await p.fill('#f-sim [name=value]', '6.1'); await p.fill('#f-sim [name=lot]', 'AF-LOT-2026-11'); }), 'BLOCKED');
    assert.ok(await pages.lab.locator('[data-gate=QC_LOT][data-outcome=BLOCK]').count());
    const p = await as('console', 'biomed1'); await go('console', 'calibration');
    await p.selectOption('#f-check [name=instanceId]', 'DEV-000006'); await p.selectOption('#f-check [name=checkType]', 'POC_QC_LOT'); await p.selectOption('#f-check [name=outcome]', 'PASS');
    await p.fill('#f-check [name=lot]', 'AF-LOT-2026-11'); await p.fill('#f-check [name=lotExpiry]', '2027-02-28'); await click(p, '[data-test=record-check]');
    await as('lab', 'inttester1');
    assert.strictEqual(await labSend('DEV-000006', async (q) => { await q.fill('#f-sim [name=value]', '6.1'); await q.fill('#f-sim [name=lot]', 'AF-LOT-2026-11'); }), 'PENDING_VERIFICATION');
    await as('lab', 'nurse1'); await acceptCard({ hasText: 'AF-LOT-2026-11' });
  },
  async 'S04-spiro-p2-lockout'() {
    let p = await as('console', 'deviceadmin1'); await go('console', 'calibration');
    assert.match(await p.locator('[data-test=lock-DEV-000001]').innerText(), /LOCKED OUT/);
    await as('lab', 'inttester1'); assert.strictEqual(await labSend('DEV-000001'), 'BLOCKED');
    assert.ok(await pages.lab.locator('[data-gate=QC_DAILY][data-outcome=BLOCK]').count());
    p = await as('console', 'biomed1'); await go('console', 'calibration');
    await p.selectOption('#f-check [name=instanceId]', 'DEV-000001'); await p.selectOption('#f-check [name=checkType]', 'SPIRO_SYRINGE_3L'); await p.selectOption('#f-check [name=outcome]', 'PASS'); await click(p, '[data-test=record-check]');
    p = await as('console', 'deviceadmin1'); await go('console', 'calibration'); await click(p, '[data-test=release-DEV-000001]');
    assert.match(await p.locator('[data-test=lock-DEV-000001]').innerText(), /available/i);
    await as('lab', 'inttester1');
    assert.strictEqual(await labSend('DEV-000001', async (q) => { await q.fill('#f-sim [name=orderRef]', 'ORD-2026-000101'); }), 'PENDING_VERIFICATION');
    assert.match(await pages.lab.locator('[data-stage=ASSOCIATE]').innerText(), /WORKLIST/);
    await as('lab', 'nurse1'); await acceptCard({ has: pages.lab.locator('text=SPIROMETRY') });
  },
  async 'S05-unmatched'() {
    await as('lab', 'inttester1'); assert.strictEqual(await labGolden('DEV-000003', 'vitals/csm-pcd01/unknown-employee'), 'UNMATCHED');
    const p = await as('lab', 'nurse1'); await go('lab', 'unmatched');
    const card = p.locator('[data-test=um][data-status=OPEN]').first();
    assert.match(await card.innerText(), /Erin Employee/);
    await card.locator('[data-test=resolve]').click(); await settle(p, 250);
    await acceptCard({ hasText: 'Erin Employee' });
  },
  async 'S06-dead-letter-replay'() {
    await as('lab', 'inttester1'); assert.strictEqual(await labGolden('DEV-000003', 'vitals/csm-pcd01/missing-pid'), 'DEAD_LETTER');
    assert.match(await pages.lab.locator('[data-test=ack]').innerText(), /MSA\|AE/);
    const p = await go('lab', 'dead-letters');
    const bad = p.locator('[data-test=dl][data-code=parse_failed][data-status=OPEN]').first();
    await bad.locator('[name=reason]').fill('Vendor confirmed a test message without patient'); await bad.locator('[data-test=discard]').click(); await settle(p, 250);
    await p.locator('[data-test=dl][data-code=route_target_unavailable][data-status=OPEN] [data-test=replay]').click(); await settle(p, 250);
    assert.ok(await p.locator('[data-test=dl][data-code=route_target_unavailable][data-status=REPLAYED]').count());
    await as('lab', 'nurse1'); await acceptCard({ has: pages.lab.locator('text=AUDIOMETRY') });
  },
  async 'S07-breath-alcohol-p1'() {
    let p = await as('console', 'deviceadmin1'); await go('console', 'wizard/CFG-000005?step=4');
    assert.strictEqual(await p.inputValue('#pf-port'), 'COM3');
    await as('lab', 'inttester1'); assert.strictEqual(await labSend('DEV-000005'), 'UNMATCHED');
    p = await as('lab', 'collector1'); await go('lab', 'capture');
    await p.selectOption('#f-session [name=instanceId]', 'DEV-000005'); await p.selectOption('#f-session [name=employeeId]', 'E1003'); await p.selectOption('#f-session [name=worklistId]', 'DAT-2026-000042');
    await click(p, '[data-test=open-session]'); await click(p, '[data-test=measure-DEV-000005]');
    p = await as('lab', 'nurse1'); await go('lab', 'capture');
    assert.ok(await p.locator('[data-test=obs][data-category=BREATH_ALCOHOL] [data-test=restricted-mask]').count(), 'restricted reading not masked for the nurse');
    assert.strictEqual(await p.locator('[data-test=obs][data-category=BREATH_ALCOHOL] [data-test=accept]').count(), 0);
    await as('lab', 'collector1'); await acceptCard({ has: pages.lab.locator('text=BREATH_ALCOHOL') });
  },
  async 'S08-four-eyes'() {
    let p = await as('console', 'devicelead1'); await go('console', 'approvals');
    await click(p, '[data-test=approval-link][data-model=MD-045]');
    const cfg = await cfgIdFromHash(p);
    await click(p, '[data-test=approve]');
    assert.match(await toastText(p), /Four-eyes/); assert.match(await p.locator('[data-test=cfg-status]').innerText(), /Awaiting approval/);
    p = await as('console', 'deviceapprover1'); await click(p, '[data-test=approve]');
    assert.match(await p.locator('[data-test=cfg-status]').innerText(), /Certified/);
    await click(p, '[data-test=cert-link]'); assert.match(await p.locator('[data-test=certificate]').innerText(), /DCR-2026-/);
    assert.ok(await p.locator('[data-test=print]').count());
    p = await as('console', 'deviceadmin1'); await go('console', 'wizard/' + cfg + '?step=8'); await click(p, '[data-test=publish]');
    assert.match(await p.locator('[data-test=cfg-status]').innerText(), /Published/);
  },
  async 'S09-gateway-enrolment'() {
    const p = await as('security', 'secops1'); await go('security', 'endpoints');
    await p.selectOption('#f-create [name=kind]', 'GATEWAY'); await p.selectOption('#f-create [name=siteCode]', 'DXB'); await p.selectOption('#f-create [name=zone]', 'GATEWAY_ZONE');
    await click(p, '[data-test=create-endpoint]');
    const token = (await p.locator('[data-test=one-time-token]').innerText()).trim(); await closeModal(p);
    assert.match(await p.locator('[data-test=ep-status-EP-DXB-GW-02]').innerText(), /Pending enrolment/);
    assert.strictEqual(await p.inputValue('#f-enrol [name=token]'), token);
    await click(p, '#f-enrol [data-act=gen-cert]'); await click(p, '[data-test=enrol]'); await closeModal(p);
    assert.match(await p.locator('[data-test=ep-status-EP-DXB-GW-02]').innerText(), /Active/);
    await p.selectOption('#f-enrol [name=endpointId]', 'EP-DXB-GW-02'); await settle(p, 200);
    await p.fill('#f-enrol [name=token]', token); await click(p, '#f-enrol [data-act=gen-cert]'); await click(p, '[data-test=enrol]');
    assert.match(await toastText(p), /one-time|already used/);
    await click(p, '[data-test=rotate-EP-DXB-GW-02]'); await closeModal(p);
    await go('security', 'events'); await click(p, '[data-test=verify-chain]');
    assert.strictEqual(await p.locator('[data-test=chain-result]').getAttribute('data-intact'), 'yes');
    await click(p, '[data-act=tamper-chain]'); assert.strictEqual(await p.locator('[data-test=chain-result]').getAttribute('data-intact'), 'no');
    assert.ok(await p.locator('[data-test=event-type]', { hasText: 'ENROLMENT_FAILED' }).count());
  },
  async 'S10-isolation-drill'() {
    let p = await as('security', 'secops1'); await go('security', 'anomaly');
    await p.selectOption('#f-drill [name=endpointId]', 'EP-LHR-TAB-01'); await click(p, '[data-test=start-drill]');
    await p.locator('[data-test=drill][data-finished=yes]').waitFor({ timeout: 15000 });
    assert.ok(Number((await p.locator('[data-test=drill-minutes]').innerText()).replace(/\D/g, '')) <= 15);
    await as('lab', 'inttester1'); assert.strictEqual(await labSend('DEV-000004', async (q) => { await q.fill('#f-sim [name=badge]', 'BDG-71005'); }), 'DEAD_LETTER');
    p = await as('security', 'secops1'); await go('security', 'endpoints'); await click(p, '[data-test=release-EP-LHR-TAB-01]');
    assert.match(await toastText(p), /recertification_required/);
    p = await as('console', 'deviceadmin1'); await go('console', 'wizard/CFG-000004?step=8'); await click(p, '[data-test=revise]');
    await click(p, '[data-test=run-sim]');
    await p.fill('#f-step6 [name=justification]', 'Endpoint isolated – live check after the security release'); await click(p, '[data-test=save-justification]');
    await certifyAndPublish({ cfg: 'CFG-000004', coApprove: true });
    assert.match(await pages.console.locator('[data-test=cfg-status]').innerText(), /Quarantined/);
    p = await as('security', 'secops1'); await go('security', 'endpoints'); await click(p, '[data-test=release-EP-LHR-TAB-01]');
    assert.match(await p.locator('[data-test=ep-status-EP-LHR-TAB-01]').innerText(), /Active/);
    p = await as('lab', 'inttester1'); await go('lab', 'dead-letters');
    await p.locator('[data-test=dl][data-code=ENDPOINT_ISOLATED][data-status=OPEN] [data-test=replay]').click(); await settle(p, 250);
    assert.ok(await p.locator('[data-test=dl][data-code=ENDPOINT_ISOLATED][data-status=REPLAYED]').count());
    p = await as('security', 'secops1'); await go('security', 'events'); await click(p, '[data-test=verify-chain]');
    assert.strictEqual(await p.locator('[data-test=chain-result]').getAttribute('data-intact'), 'yes');
  }
};

(async () => {
  const browser = await chromium.launch();
  ctx = await browser.newContext({ viewport: { width: 1366, height: 900 }, acceptDownloads: true });
  pages = {};
  for (const [k, f] of Object.entries(FILES)) {
    const p = await ctx.newPage();
    p.on('pageerror', (e) => errors.push(`${k}: ${e.message}`));
    p.on('console', (m) => { if (m.type() === 'error') errors.push(`${k}: ${m.text()}`); });
    p.on('dialog', (d) => d.accept());
    await p.goto('file://' + path.join(DIST, f)); pages[k] = p;
  }
  const results = [];
  const scs = require(path.join(__dirname, '..', 'scenarios.json')).scenarios.filter((s) => !only.length || only.includes(s.code));
  for (const sc of scs) {
    const p = pages.console; await p.bringToFront();
    await p.click('#wz-demo-toolbar [data-wzt=reset]'); await settle(p, 300);
    await p.click('#wz-demo-toolbar [data-wzt=scenario]'); await p.selectOption('#wz-demo-toolbar [data-wzt=pick]', sc.id); await settle(p, 200);
    await p.click('#wz-demo-toolbar [data-wzt=scenario]'); // close the panel
    const before = errors.length; const t0 = Date.now();
    try {
      await SCENARIOS[sc.id]();
      const st = await step(pages.console);
      assert.ok(st.completed.includes(sc.id), 'scenario not completed in the toolbar – stuck at step ' + (st.stepIndex + 1) + ' (' + (st.step && st.step.action_label) + ')');
      assert.strictEqual(errors.length, before, 'console errors: ' + errors.slice(before).join('; '));
      results.push({ code: sc.code, ok: true, ms: Date.now() - t0 });
      console.log(`PASS ${sc.code} ${sc.title} (${sc.steps.length} steps, ${((Date.now() - t0) / 1000).toFixed(1)} s)`);
    } catch (e) {
      results.push({ code: sc.code, ok: false, error: e.message });
      console.log(`FAIL ${sc.code} ${sc.title}: ${e.message.split('\n')[0]}`);
      await pages.console.screenshot({ path: path.join(__dirname, 'ui-failure-' + sc.code + '.png') }).catch(() => {});
    }
  }
  // session export / import round trip through the toolbar
  try {
    const p = pages.console; await p.bringToFront();
    const [dl] = await Promise.all([p.waitForEvent('download'), p.click('#wz-demo-toolbar [data-wzt=export]')]);
    const file = await dl.path(); const rev = await p.evaluate(() => DX.sessionInfo().rev);
    await p.click('#wz-demo-toolbar [data-wzt=reset]'); await settle(p, 200);
    await p.setInputFiles('#wz-demo-toolbar input[type=file]', file); await settle(p, 400);
    assert.strictEqual(await p.evaluate(() => DX.sessionInfo().rev), rev, 'import did not restore the session');
    assert.ok(await pages.lab.evaluate((r) => DX.sessionInfo().rev === r, rev), 'other tab not synchronised');
    results.push({ code: 'export/import', ok: true }); console.log('PASS export / import / cross-tab sync');
  } catch (e) { results.push({ code: 'export/import', ok: false, error: e.message }); console.log('FAIL export/import: ' + e.message); }
  await browser.close();
  const failed = results.filter((r) => !r.ok);
  console.log(`\nui: ${results.length - failed.length} passed, ${failed.length} failed; console errors: ${errors.length}`);
  if (errors.length) console.log(errors.join('\n'));
  process.exit(failed.length || errors.length ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
