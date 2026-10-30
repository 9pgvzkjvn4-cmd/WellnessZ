/*
 * Playwright test for the Manager & HR Workspace (dist/manager-workspace.html over file://).
 *
 *   python3 demo/build.py && node demo/test/manager-workspace.test.js
 *
 * Plays the manager/HR steps of S01 (EHS incident -> status only -> RTW acknowledgement),
 * S04 (random D&A testing -> duty status) and S05 (absence trigger -> early intervention ->
 * released advice report) through the UI. Steps that belong to other apps (OH, employee) are
 * seeded through the engine in the page context. Fails on any console error.
 */
'use strict';
const path = require('path');
const fs = require('fs');
const assert = require('assert/strict');
const { chromium } = require('playwright');

const DIST = path.resolve(__dirname, '..', 'dist');
const APP = 'file://' + path.join(DIST, 'manager-workspace.html');
const CLINICAL = /lower back|lifting a 30 kg bag|cannabinoid|non.negative|verified positive|recurrent short illnesses|diagnos[ie]s:/i;

function step(msg) { console.log('  ✓ ' + msg); }

/** Run an engine call as another demo user (any app), then restore the manager context. */
function as(page, app, user, body, arg) {
  return page.evaluate(([a, u, b, x]) => {
    const prev = WZ.app;
    WZ._setApp(a); WZ.signIn(u);
    try { return new Function('arg', b)(x); } finally { WZ._setApp(prev); }
  }, [app, user, body, arg]);
}
async function signIn(page, user) {
  await page.selectOption('#wz-demo-toolbar select[data-wzt="user"]', user);
  await page.waitForFunction((u) => WZ.currentUser && WZ.currentUser.username === u && document.querySelector('.wz-header-user'), user);
}
async function go(page, route) {
  await page.evaluate((r) => { location.hash = '#/' + r; }, route);
  await page.waitForFunction((r) => location.hash === '#/' + r && document.querySelector('main h1'), route);
}
async function mainText(page) { return page.textContent('main'); }

(async () => {
  if (!fs.existsSync(path.join(DIST, 'manager-workspace.html'))) { console.error('Missing demo/dist/manager-workspace.html – run: python3 demo/build.py'); process.exit(1); }
  const browser = await chromium.launch();
  const page = await (await browser.newContext({ viewport: { width: 1360, height: 900 } })).newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('dialog', (d) => d.accept());

  try {
    console.log('Manager & HR Workspace test');
    await page.goto(APP);
    await page.waitForSelector('#wz-demo-toolbar select[data-wzt="user"]');
    await page.evaluate(() => WZ.reset());
    await page.reload();
    const users = await page.$$eval('#wz-demo-toolbar select[data-wzt="user"] option', (o) => o.map((x) => x.value));
    ['manager3', 'hr1', 'crewsched1', 'safety1', 'hse1'].forEach((u) => assert.ok(users.includes(u), 'toolbar offers ' + u));
    await page.waitForSelector('.mgr-chip:has-text("STATUS ONLY")');
    step('workspace loaded from file:// with the demo toolbar and the STATUS ONLY principle in the header');

    // ================================================================= S01
    console.log(' S01 – EHS incident, status only, RTW acknowledgement');
    await page.evaluate(() => WZ.startScenario('S01-ehs-incident-rtw'));
    await signIn(page, 'hse1');
    await go(page, 'refer');
    await page.waitForSelector('h1:has-text("Report an EHS incident")');
    assert.equal(await page.$('.wz-side a[href="#/team"]'), null, 'HSE officer has no team view');
    await page.fill('form[data-form="ehs"] [name="employeeId"]', 'E1004');
    await page.fill('form[data-form="ehs"] [name="location"]', 'Stand 12, Central Hub');
    await page.fill('form[data-form="ehs"] [name="bodyPart"]', 'Lower back');
    await page.fill('form[data-form="ehs"] [name="concern"]', 'Lower back pain after lifting a 30 kg bag in the hold of WZ101');
    await page.click('form[data-form="ehs"] button[type="submit"]');
    await page.waitForSelector('.wz-alert-title:has-text("Incident sent to Occupational Health")');
    const caseId = (await page.textContent('.wz-alert.success')).match(/GND-\d{4}-\d{6}/)[0];
    let st = await page.evaluate(() => WZ.session());
    const c1 = st.cases.find((c) => c.id === caseId);
    assert.equal(c1.source, 'EHS_INCIDENT'); assert.equal(c1.employeeId, 'E1004'); assert.equal(c1.status, 'INTAKE');
    assert.ok(st.notifications.some((n) => n.to === 'nurse1' && n.body.includes(caseId)), 'OH nurse notified');
    assert.ok(st.notifications.some((n) => n.to === 'ramp1' && /referred/i.test(n.title)), 'Rami told he was referred');
    await go(page, 'incidents');
    await page.waitForSelector(`td:has-text("${caseId}")`);
    step('step 1: hse1 reported the EHS incident through the UI → ' + caseId + ' in the OH intake queue');

    // steps 2-4 belong to the OH workspace
    await as(page, 'oh', 'nurse1', 'WZ.triage(arg, { urgency: "SEMI_URGENT", protocol: "MSK_INJURY", recordable: true }); WZ.assign(arg, "nurse1");', caseId);
    await as(page, 'oh', 'physician1', 'WZ.recordFitnessDecision(arg.id, { status: "FIT_WITH_RESTRICTIONS", restrictions: ["NO_LIFT_10"], reviewDate: arg.review });', { id: caseId, review: await page.evaluate(() => UI.isoToday(14)) });
    await page.evaluate(() => WZ.stepScenario(1)); // step 5 is a 'view' step
    await page.reload();
    await signIn(page, 'manager3');
    await go(page, 'team');
    const rami = page.locator('tr', { has: page.locator('a:has-text("Rami Ramp")') });
    await rami.waitFor();
    const ramiText = await rami.innerText();
    assert.match(ramiText, /Fit with restrictions/);
    assert.match(ramiText, /No lifting above 10 kg/);
    assert.ok(await page.isVisible('[data-status-only] .wz-alert-title:has-text("Status only – no clinical detail")'));
    assert.doesNotMatch(await mainText(page), CLINICAL, 'no clinical content on the team view');
    await page.click('a:has-text("Rami Ramp")');
    await page.waitForSelector('h1:has-text("Rami Ramp")');
    assert.doesNotMatch(await mainText(page), CLINICAL, 'no clinical content on the employee status page');
    assert.ok(/Fitness decision: Fit with restrictions/.test(await mainText(page)), 'status timeline shown');
    step('step 5: manager3 sees Rami "Fit with restrictions" + "No lifting above 10 kg", no diagnosis or notes');

    await as(page, 'oh', 'nurse1', 'WZ.createRtwPlan(arg, { phases: [{ week: 1, hours: "6 hours", duties: "Marshalling and paperwork, no lifting" }, { week: 2, hours: "Full shift", duties: "Light baggage only (under 10 kg)" }] });', caseId);
    const planId = await page.evaluate((id) => WZ.session().rtwPlans.find((p) => p.caseId === id).id, caseId);
    await as(page, 'employee', 'ramp1', 'WZ.acknowledge(arg, {});', planId);
    await page.reload();
    await go(page, 'rtw');
    const card = page.locator(`[data-rtw="${planId}"]`);
    await card.waitFor();
    await card.locator('input[name="agree"]').check();
    await card.locator('button[type="submit"]').click();
    await page.waitForSelector('.wz-toast:has-text("plan is now active")');
    st = await page.evaluate(() => WZ.session());
    const plan = st.rtwPlans.find((p) => p.id === planId);
    assert.equal(plan.status, 'ACTIVE'); assert.ok(plan.ack.manager && plan.ack.employee);
    await page.waitForSelector(`[data-rtw="${planId}"] .wz-badge:has-text("Active")`);
    step('step 8: manager3 acknowledged ' + planId + ' in the UI → plan Active');

    await as(page, 'oh', 'physician1', 'WZ.recordFitnessDecision(arg, { status: "FIT" });', caseId);
    await as(page, 'oh', 'nurse1', 'WZ.closeCase(arg, { reason: "Recovered – full duties" });', caseId);
    await page.reload();
    await go(page, 'team');
    const ramiAfter = await page.locator('tr', { has: page.locator('a:has-text("Rami Ramp")') }).innerText();
    assert.match(ramiAfter, /\bFit\b/); assert.doesNotMatch(ramiAfter, /No lifting/);
    const sc = await page.evaluate(() => WZ.scenarioState());
    assert.ok(sc.completed.includes('S01-ehs-incident-rtw'), 'scenario S01 completed');
    step('steps 9-10 seeded: manager3 now sees Rami "Fit", restrictions cleared; scenario S01 complete');

    // ================================================================= S04
    console.log(' S04 – random D&A testing, duty status');
    await page.evaluate(() => { WZ.reset(); });
    await page.reload();
    const sel = await as(page, 'oh', 'damanager1', 'return WZ.randomSelection("POOL-GND-ENG", { count: 2 });');
    assert.deepEqual(sel.selectedNames.slice().sort(), ['Bea Baggage', 'Rhea Rivet']);
    const beaTest = await page.evaluate((sid) => WZ.session().testing.tests.find((t) => t.selectionId === sid && t.employeeId === 'E1006').id, sel.id);
    await as(page, 'oh', 'collector1', 'WZ.collectSpecimen(arg, { seals: "Intact" });', beaTest);
    await as(page, 'oh', 'mro1', 'WZ.labResult(arg, { result: "NON_NEGATIVE", analyte: "Cannabinoids (demo)" }); WZ.mroVerdict(arg, { outcome: "POSITIVE", note: "No legitimate medical explanation (demo)." });', beaTest);
    await page.reload();
    await signIn(page, 'hr1');
    await go(page, 'duty-status');
    const bea = page.locator('tr', { hasText: 'Bea Baggage' });
    await bea.waitFor();
    const beaTxt = await bea.innerText();
    assert.match(beaTxt, /Removed from safety-sensitive duty/);
    assert.ok(beaTxt.includes(await page.evaluate(() => UI.fmtDate(UI.isoToday()))), 'since today');
    assert.doesNotMatch(await mainText(page), CLINICAL, 'no test type, result or reason');
    assert.doesNotMatch(beaTxt, /random|test|drug|alcohol/i);
    step('step 7: hr1 sees Bea Baggage "Removed from safety-sensitive duty" since today – no reason, test type or result');
    await signIn(page, 'manager3');
    await go(page, 'team');
    const beaTeam = await page.locator('tr', { has: page.locator('a:has-text("Bea Baggage")') }).innerText();
    assert.match(beaTeam, /Removed from safety-sensitive duty/);
    assert.doesNotMatch(await mainText(page), CLINICAL);
    step('step 8: manager3 team status shows Bea "Removed from safety-sensitive duty" with no reason');
    // for-cause request (managers)
    await go(page, 'testing');
    await page.selectOption('form[data-form="for-cause"] [name="employeeId"]', 'E1009');
    await page.fill('form[data-form="for-cause"] [name="obs1"]', 'Unsteady when climbing into the tug at 07:40');
    await page.fill('form[data-form="for-cause"] [name="obs2"]', 'Missed two radio calls from the ramp controller');
    await page.click('form[data-form="for-cause"] button[type="submit"]');
    await page.waitForSelector('.wz-alert-title:has-text("For-cause test requested")');
    await page.waitForSelector('td:has-text("Lars Loader")');
    assert.ok(await page.isVisible('td .wz-badge:has-text("Please attend")'), 'masked status only');
    step('for-cause request by manager3 (two observations) → collector dispatched, masked status shown');

    // ================================================================= S05
    console.log(' S05 – absence trigger, early intervention, released advice');
    await page.evaluate(() => { WZ.reset(); WZ.startScenario('S05-absence-early-intervention'); });
    await page.reload();
    await signIn(page, 'manager3');
    await go(page, 'absence');
    await page.selectOption('form[data-form="absence"] [name="employeeId"]', 'E1006');
    await page.click('form[data-form="absence"] button[type="submit"]');
    await page.waitForSelector('.wz-alert-title:has-text("absence trigger reached")');
    assert.match(await page.textContent('.wz-alert.success'), /4 spells in the last 6 months/);
    st = await page.evaluate(() => WZ.session());
    assert.ok(st.absenceTriggers.some((t) => t.employeeId === 'E1006' && t.rule === 'SPELLS_6M' && t.status === 'OPEN'));
    assert.ok(st.notifications.some((n) => n.to === 'hr1' && n.title === 'Absence trigger reached'));
    assert.ok(st.notifications.some((n) => n.to === 'casemgr1' && n.title === 'Early-intervention queue'));
    step('step 1: manager3 recorded Bea’s 4th spell → SPELLS_6M trigger, HR and case manager notified');

    await signIn(page, 'hr1');
    await page.click('.wz-side a[href="#/absence"]');
    await page.waitForSelector('h1:has-text("Absence")');
    const eiRow = page.locator('tr', { hasText: 'Bea Baggage' }).filter({ has: page.locator('[data-act="ei-refer"]') });
    await eiRow.locator('[data-act="ei-refer"]').click();
    await page.waitForSelector('dialog.wz-modal [name="questions"]');
    assert.equal(await page.inputValue('dialog.wz-modal [name="questions"]'), 'Is there an underlying health reason for the absences?\nAre adjustments recommended?');
    await page.click('dialog.wz-modal button:has-text("Refer to OH")');
    await page.waitForSelector('.wz-alert-title:has-text("Referred to Occupational Health")');
    st = await page.evaluate(() => WZ.session());
    const ei = st.earlyInterventions.find((x) => x.employeeId === 'E1006' && x.status === 'REFERRED');
    const absCase = st.cases.find((c) => c.id === ei.caseId);
    assert.equal(absCase.queue, 'CASE_MANAGEMENT'); assert.equal(absCase.owner, 'casemgr1');
    const ar = st.adviceRequests.find((a) => a.id === ei.adviceRequestId);
    assert.deepEqual(ar.questions, ['Is there an underlying health reason for the absences?', 'Are adjustments recommended?']);
    step('step 2: hr1 raised the early intervention in the UI → ' + absCase.id + ' owned by Casey Casemanager, advice ' + ar.id);

    await as(page, 'oh', 'casemgr1', 'WZ.writeAdviceReport(arg, { fitness: "FIT", workImpact: "Fit for her role. Recurrent short illnesses; no work-related cause found.", adjustments: "Early shifts only for 4 weeks; review in 6 weeks.", likelyReturn: "Already at work", review: UI.isoToday(42) });', ar.id);
    await as(page, 'employee', 'baggage1', 'WZ.releaseAdvice(arg, { decision: "RELEASE" });', ar.id);
    await page.reload();
    await signIn(page, 'manager3');
    await go(page, 'advice');
    const adv = page.locator(`[data-advice="${ar.id}"]`);
    await adv.waitFor();
    const advTxt = await adv.innerText();
    assert.match(advTxt, /Released/);
    assert.match(advTxt, /Early shifts only for 4 weeks/);
    assert.match(advTxt, /Work impact/);
    assert.doesNotMatch(advTxt, /diagnos/i);
    assert.ok(await page.isVisible('[data-status-only]'));
    assert.equal((await page.evaluate(() => WZ.scenarioState())).stepIndex, 4, 'S05 advanced to the final view step');
    step('step 5: manager3 reads the released advice – work impact and adjustments only, no diagnosis');

    // ================================================================= errors shown as friendly messages
    await go(page, 'absence');
    await page.evaluate(() => { const o = document.createElement('option'); o.value = 'E1030'; o.textContent = 'Out of scope'; document.querySelector('form[data-form="absence"] [name="employeeId"]').appendChild(o); });
    await page.selectOption('form[data-form="absence"] [name="employeeId"]', 'E1030');
    await page.click('form[data-form="absence"] button[type="submit"]');
    await page.waitForSelector('form[data-form="absence"] .wz-error:not([hidden])');
    assert.match(await page.textContent('form[data-form="absence"] .wz-error'), /^Your role does not allow this\./);
    step('WZError "forbidden" shown as a friendly message (out-of-team absence)');

    assert.deepEqual(errors, [], 'no console errors');
    step('no console errors');
    console.log('PASS');
  } catch (e) {
    console.error('FAIL:', e.stack || e.message);
    if (errors.length) console.error('console errors:', errors);
    await page.screenshot({ path: path.join(__dirname, 'manager-failure.png'), fullPage: true }).catch(() => {});
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
