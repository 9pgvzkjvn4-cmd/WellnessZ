/*
 * Playwright test for the built OH Clinical Workspace.
 *
 *   python3 demo/build.py && node demo/test/oh-workspace.test.js
 *
 * Opens demo/dist/oh-workspace.html over file:// and drives the OH steps of scenarios S01, S02, S04
 * and S08 through the UI (toolbar user switcher, queues, case tabs, forms). Steps that belong to the
 * other apps (employee, manager) are performed through the engine API in the same shared session.
 * It also checks that a general OH nurse sees only the restricted-case placeholder, that opening the
 * confidential record is refused and audited, and that there are no console errors.
 *
 * Set WZ_SHOTS=<dir> to save screenshots of the main screens.
 */
'use strict';
const path = require('path');
const fs = require('fs');
const assert = require('assert/strict');
const { chromium } = require('playwright');

const DIST = path.resolve(__dirname, '..', 'dist');
const APP = 'file://' + path.join(DIST, 'oh-workspace.html');
const KEY = 'wellnessz-demo-session';
const SHOTS = process.env.WZ_SHOTS || null;

function step(msg) { console.log('  ✓ ' + msg); }

(async () => {
  for (const f of ['oh-workspace.html', 'demo-data.js']) {
    if (!fs.existsSync(path.join(DIST, f))) { console.error('Missing demo/dist/' + f + ' – run: python3 demo/build.py'); process.exit(1); }
  }
  if (SHOTS) fs.mkdirSync(SHOTS, { recursive: true });
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('dialog', (d) => d.accept());

  // ---------------------------------------------------------------- helpers
  const state = () => page.evaluate((k) => JSON.parse(localStorage.getItem(k)), KEY);
  async function waitFor(check, label) {
    for (let i = 0; i < 60; i++) {
      const s = await state();
      try { if (check(s)) return s; } catch (e) { /* not yet */ }
      await page.waitForTimeout(100);
    }
    const err = await page.$$eval('[data-error]:not([hidden]), .wz-toast.danger', (els) => els.map((e) => e.textContent).join(' | ')).catch(() => '');
    throw new Error('timed out waiting for: ' + label + (err ? ' (shown: ' + err + ')' : ''));
  }
  async function signIn(username) {
    const name = await page.evaluate((u) => WZ.users('oh').find((x) => x.username === u).name, username);
    await page.selectOption('#wz-demo-toolbar select[data-wzt="user"]', username);
    await page.waitForSelector('.wz-header-user:has-text("' + name + '")');
  }
  async function go(route) {
    await page.evaluate((r) => { location.hash = '#/' + r; }, route);
    await page.waitForSelector('main h1');
  }
  /** Perform an engine action as a user of another app in the shared session, then return to the OH app. */
  async function act(app, user, action, ...args) {
    return page.evaluate(({ app, user, action, args }) => {
      const back = WZ.currentUser && WZ.currentUser.username;
      WZ._setApp(app); WZ.signIn(user);
      try { return WZ[action].apply(null, args); }
      finally { WZ._setApp('oh'); if (back) WZ.signIn(back); }
    }, { app, user, action, args });
  }
  async function startScenario(id) {
    await page.evaluate((id) => { WZ.reset(); WZ.startScenario(id); }, id);
    await page.evaluate(() => { location.hash = '#/home'; });
  }
  const markViewDone = () => page.evaluate(() => WZ.stepScenario(1));
  const scenario = () => page.evaluate(() => WZ.scenarioState());
  async function shot(name) { if (!SHOTS) return; await page.evaluate(() => document.querySelectorAll('.wz-toast').forEach((t) => t.remove())); await page.screenshot({ path: path.join(SHOTS, name + '.png'), fullPage: false }); }
  async function submit(formSel) { await page.click(formSel + ' button[type="submit"]'); }

  try {
    console.log('WellnessZ OH workspace test');
    await page.goto(APP);
    await page.waitForSelector('#wz-demo-toolbar select[data-wzt="user"]');
    await page.evaluate(() => WZ.reset());
    await page.waitForSelector('.wz-side');
    step('OH workspace loaded from file:// with the demo toolbar and side navigation');

    // ---- navigation follows role permissions
    await signIn('collector1');
    let nav = await page.$$eval('.wz-side a', (as) => as.map((a) => a.getAttribute('href')));
    assert.ok(nav.includes('#/testing/collection') && !nav.includes('#/queues') && !nav.includes('#/support') && !nav.includes('#/testing/mro'), 'collector nav: ' + nav.join(','));
    await signIn('psych1');
    nav = await page.$$eval('.wz-side a', (as) => as.map((a) => a.getAttribute('href')));
    assert.ok(nav.includes('#/support') && !nav.includes('#/queues') && !nav.includes('#/employees'), 'psych nav: ' + nav.join(','));
    await signIn('nurse1');
    nav = await page.$$eval('.wz-side a', (as) => as.map((a) => a.getAttribute('href')));
    assert.ok(nav.includes('#/queues') && !nav.includes('#/support') && !nav.includes('#/privacy/audit'), 'nurse nav: ' + nav.join(','));
    step('side navigation shows only what each role can use (collector, named clinician, nurse)');

    // =====================================================================================
    // S01 – EHS incident to triage, fitness, RTW and closure
    // =====================================================================================
    console.log('S01 EHS incident → RTW');
    await startScenario('S01-ehs-incident-rtw');
    const s01 = (await page.evaluate(() => WZ.scenarios().find((s) => s.id === 'S01-ehs-incident-rtw'))).steps;
    const ref = await act('manager', 'hse1', 'createReferral', s01[0].data);
    const caseId = ref.id;
    assert.match(caseId, /^GND-\d{4}-\d{6}$/);
    step('hse1 reported the EHS incident (engine API, manager app): ' + caseId);

    await signIn('nurse1');
    await go('queues?tab=intake');
    await page.waitForSelector(`a[href="#/case/${caseId}"]`);
    await shot('01-queues');
    await page.click(`a[href="#/case/${caseId}?tab=triage"]`);
    await page.waitForSelector('form[data-form="triage"]');
    await page.selectOption('form[data-form="triage"] select[name="protocol"]', 'MSK_INJURY');
    await page.check('form[data-form="triage"] input[name="urgency"][value="SEMI_URGENT"]');
    await page.check('form[data-form="triage"] input[name="recordable"]');
    await shot('02-case-triage');
    await submit('form[data-form="triage"]');
    let st = await waitFor((s) => s.cases.find((c) => c.id === caseId).status === 'ASSESSMENT', 'triage');
    let c = st.cases.find((x) => x.id === caseId);
    assert.equal(c.urgency, 'SEMI_URGENT'); assert.equal(c.triage.protocol, 'MSK_INJURY'); assert.equal(c.recordable, true);
    await page.waitForSelector('[data-test="case-summary"] .wz-badge:has-text("Being assessed")');
    step('nurse1 triaged it in the UI: Being assessed, Semi-urgent, MSK protocol, recordable');

    await go('queues?tab=team');
    await page.click(`button[data-act="assign-me"][data-id="${caseId}"]`);
    await waitFor((s) => s.cases.find((x) => x.id === caseId).owner === 'nurse1', 'assign');
    step('nurse1 assigned the case to herself from the queue');

    await signIn('physician1');
    await go(`case/${caseId}?tab=fitness`);
    await page.waitForSelector('form[data-form="fitness"]');
    await page.check('form[data-form="fitness"] input[name="status"][value="FIT_WITH_RESTRICTIONS"]');
    await page.check('form[data-form="fitness"] input[name="restr"][value="NO_LIFT_10"]');
    await shot('03-case-fitness');
    await submit('form[data-form="fitness"]');
    st = await waitFor((s) => s.cases.find((x) => x.id === caseId).fitness.status === 'FIT_WITH_RESTRICTIONS', 'fitness decision');
    c = st.cases.find((x) => x.id === caseId);
    assert.deepEqual(c.restrictions.map((r) => r.code), ['NO_LIFT_10']);
    assert.ok(st.disclosures.some((d) => d.employeeId === 'E1004' && /Gus Ground/.test(d.recipient)), 'disclosure to the line manager logged');
    step('physician1 recorded Fit with restrictions (No lifting above 10 kg); disclosure logged');

    await markViewDone(); // manager3 checks team status (manager app)
    await signIn('nurse1');
    await go(`case/${caseId}?tab=rtw`);
    await page.waitForSelector('form[data-form="rtw"]');
    const phases = s01[5].data.phases;
    for (let i = 0; i < phases.length; i++) {
      await page.fill(`form[data-form="rtw"] input[name="hours${i + 1}"]`, phases[i].hours);
      await page.fill(`form[data-form="rtw"] input[name="duties${i + 1}"]`, phases[i].duties);
    }
    await submit('form[data-form="rtw"]');
    st = await waitFor((s) => s.rtwPlans.some((p) => p.caseId === caseId), 'RTW plan');
    const plan = st.rtwPlans.find((p) => p.caseId === caseId);
    assert.equal(plan.status, 'ISSUED'); assert.equal(plan.phases.length, 3);
    step('nurse1 issued the 3-week RTW plan ' + plan.id + ' (awaiting acknowledgement)');

    await act('employee', 'ramp1', 'acknowledge', plan.id, {});
    await act('manager', 'manager3', 'acknowledge', plan.id, {});
    st = await state();
    assert.equal(st.rtwPlans.find((p) => p.id === plan.id).status, 'ACTIVE');
    await go(`case/${caseId}?tab=rtw`);
    await page.waitForSelector('.wz-card-head .wz-badge:has-text("Active")');
    step('Rami and Gus Ground acknowledged (engine API); the OH case shows the plan Active');

    await signIn('physician1');
    await go(`case/${caseId}?tab=fitness`);
    await page.check('form[data-form="fitness"] input[name="status"][value="FIT"]');
    await submit('form[data-form="fitness"]');
    st = await waitFor((s) => s.cases.find((x) => x.id === caseId).fitness.status === 'FIT', 'review FIT');
    assert.equal(st.cases.find((x) => x.id === caseId).restrictions.length, 0);
    step('physician1 recorded Fit for full duties; restrictions cleared');

    await signIn('nurse1');
    await go(`case/${caseId}`);
    await page.fill('form[data-form="close-case"] input[name="reason"]', s01[9].data.reason);
    await submit('form[data-form="close-case"]');
    st = await waitFor((s) => s.cases.find((x) => x.id === caseId).status === 'CLOSED', 'close case');
    assert.equal(st.rtwPlans.find((p) => p.id === plan.id).status, 'COMPLETED');
    let sc = await scenario();
    assert.ok(sc.completed.includes('S01-ehs-incident-rtw'), 'S01 complete in the scenario tracker');
    step('nurse1 closed the case; plan completed; scenario S01 complete');

    // =====================================================================================
    // S02 – confidential mental-health self-referral (restricted class)
    // =====================================================================================
    console.log('S02 confidential support (restricted class)');
    await startScenario('S02-mental-health-confidential');
    const s02 = (await page.evaluate(() => WZ.scenarios().find((s) => s.id === 'S02-mental-health-confidential'))).steps;
    const sup = await act('employee', 'pilot1', 'selfReferSupport', s02[0].data);
    const screen = await act('employee', 'pilot1', 'submitSupportScreen', sup.id, s02[1].data.answers);
    assert.equal(screen.urgent, true);
    step('pilot1 self-referred and screened (engine API): ' + sup.id + ', urgent call-back raised');

    await signIn('psych1');
    await go('support');
    await page.waitForSelector('.wz-alert.danger:has-text("urgent call-back")');
    await shot('04-support-caseload');
    await page.click(`a[href="#/support/${sup.id}"]`);
    await page.waitForSelector('form[data-form="callback"]');
    await page.check('form[data-form="callback"] input[name="outcome"][value="REACHED_SAFE"]');
    await submit('form[data-form="callback"]');
    st = await waitFor((s) => s.supportCases.find((x) => x.id === sup.id).status === 'ACTIVE', 'call-back');
    assert.equal(st.supportCases.find((x) => x.id === sup.id).urgentItems[0].status, 'REACHED_SAFE');
    await page.click(`button[data-act="ame-handoff"][data-id="${sup.id}"]`);
    await page.waitForSelector('button[data-act="ame-handoff"]:has-text("shared with the AME")');
    await shot('05-support-case');
    step('psych1 recorded the urgent call-back (reached, safe) and handed the reference to the AME');

    await signIn('nurse1');
    await go('employees');
    await page.fill('form[data-form="filter-emp"] input[name="q"]', 'Paolo');
    await submit('form[data-form="filter-emp"]');
    await page.click('a[href="#/employee/E1020"]');
    const ph = await page.waitForSelector('[data-test="restricted-placeholder"]');
    assert.match(await ph.textContent(), /1 restricted case/);
    const chartText = await page.textContent('main');
    assert.ok(!chartText.includes(sup.id) && !chartText.includes('Struggling to sleep'), 'no restricted reference or content on the nurse view');
    await shot('06-chart-restricted-placeholder');
    await go('support/' + sup.id);
    await page.waitForSelector('.wz-alert.restricted:has-text("Access refused")');
    st = await state();
    assert.ok(st.audit.some((a) => a.actor === 'nurse1' && a.entityId === sup.id && a.outcome === 'DENIED'), 'nurse attempt audited DENIED');
    await go('employee/E1023');
    assert.match(await page.textContent('[data-test="restricted-placeholder"]'), /2 restricted cases|1 restricted case/);
    await markViewDone();
    step('nurse1 sees only the "1 restricted case" placeholder; opening the record is refused and audited DENIED');

    await signIn('ame1');
    await go('employee/E1020');
    await page.waitForSelector('form[data-form="ame-relief"]');
    assert.equal(await page.inputValue('form[data-form="ame-relief"] input[name="ref"]'), sup.id);
    await page.selectOption('form[data-form="ame-relief"] select[name="status"]', 'TEMPORARILY_UNFIT');
    await submit('form[data-form="ame-relief"]');
    st = await waitFor((s) => s.employees.find((e) => e.id === 'E1020').dutyStatus === 'RELIEVED', 'AME relief');
    assert.equal(st.employees.find((e) => e.id === 'E1020').fitness.status, 'TEMPORARILY_UNFIT');
    assert.equal(st.supportCases.find((x) => x.id === sup.id).status, 'RELIEF');
    step('ame1 relieved Paolo from flying from the chart (Temporarily unfit, Relieved from duties)');

    await act('employee', 'pilot1', 'shareFitnessOnly', sup.id);
    await markViewDone(); await markViewDone(); // manager4 shared outcomes, crewsched1 crew status
    await signIn('dpo1');
    await go('privacy/audit');
    await page.selectOption('form[data-form="filter-audit"] select[name="outcome"]', 'DENIED');
    await page.waitForSelector(`table.wz-table td:has-text("${sup.id}")`);
    await shot('07-audit-denied');
    await page.selectOption('form[data-form="filter-audit"] select[name="outcome"]', '');
    await page.fill('form[data-form="filter-audit"] input[name="q"]', 'SUPPORT_SHARING');
    await submit('form[data-form="filter-audit"]');
    await page.waitForSelector('table.wz-table td:has-text("SUPPORT_SHARING")');
    await page.waitForSelector('.wz-alert.success:has-text("Hash chain intact")');
    await markViewDone();
    sc = await scenario();
    assert.ok(sc.completed.includes('S02-mental-health-confidential'), 'S02 complete');
    step('dpo1 sees the DENIED attempt and the sharing change in the intact hash-chained audit log; S02 complete');

    // =====================================================================================
    // S04 – random D&A testing, collection, MRO verdict
    // =====================================================================================
    console.log('S04 random D&A testing');
    await startScenario('S04-random-da-testing');
    await signIn('damanager1');
    await go('testing');
    await page.selectOption('form[data-form="random"] select[name="poolId"]', 'POOL-GND-ENG');
    await page.fill('form[data-form="random"] input[name="count"]', '2');
    await submit('form[data-form="random"]');
    const names = await (await page.waitForSelector('[data-test="selected-names"]')).textContent();
    assert.ok(names.includes('Bea Baggage') && names.includes('Rhea Rivet'), 'seeded draw: ' + names);
    await shot('08-testing-programme');
    st = await state();
    const beaTest = st.testing.tests.find((t) => t.employeeId === 'E1006' && t.status === 'NOTIFIED');
    assert.ok(beaTest, 'Bea notified');
    await markViewDone(); // baggage1 opens the notice (employee app)
    step('damanager1 ran the seeded random selection: ' + names.trim());

    await signIn('collector1');
    await go('testing/collection');
    await page.selectOption(`form[data-form="collect"][data-id="${beaTest.id}"] select[name="seals"]`, 'Intact');
    await submit(`form[data-form="collect"][data-id="${beaTest.id}"]`);
    st = await waitFor((s) => s.testing.tests.find((t) => t.id === beaTest.id).status === 'IN_LAB', 'collection');
    const spec = st.testing.tests.find((t) => t.id === beaTest.id);
    assert.ok(spec.specimen.id && spec.specimen.ccf && spec.custody.length === 2, 'specimen, CCF and chain of custody');
    step('collector1 collected Bea\'s specimen with CCF and chain of custody (In laboratory)');

    await signIn('damanager1');
    await go('testing');
    const selCard = await page.textContent('main');
    assert.ok(selCard.includes('With the testing programme'), 'masked status for the programme manager');
    assert.ok(!/Cannabinoids|Non-negative|Verified/.test(selCard), 'no result content for the programme manager');
    await go('testing/mro');
    await page.waitForSelector('.wz-alert.restricted:has-text("Access refused")');
    await markViewDone();
    step('damanager1 sees "With the testing programme" only; the MRO queue is refused');

    await signIn('mro1');
    await go('testing/mro');
    await page.check(`form[data-form="lab"][data-id="${beaTest.id}"] input[name="result"][value="NON_NEGATIVE"]`);
    await page.selectOption(`form[data-form="lab"][data-id="${beaTest.id}"] select[name="analyte"]`, 'Cannabinoids (demo)');
    await submit(`form[data-form="lab"][data-id="${beaTest.id}"]`);
    await waitFor((s) => s.testing.tests.find((t) => t.id === beaTest.id).status === 'MRO_REVIEW', 'lab result');
    step('mro1 received the simulated TOXLAB result (MRO review)');
    await page.selectOption(`form[data-form="verdict"][data-id="${beaTest.id}"] select[name="outcome"]`, 'POSITIVE');
    await page.fill(`form[data-form="verdict"][data-id="${beaTest.id}"] textarea[name="note"]`, 'No legitimate medical explanation (demo).');
    await shot('09-mro-review');
    await submit(`form[data-form="verdict"][data-id="${beaTest.id}"]`);
    st = await waitFor((s) => s.testing.tests.find((t) => t.id === beaTest.id).status === 'VERIFIED_POSITIVE', 'MRO verdict');
    const bea = st.employees.find((e) => e.id === 'E1006');
    assert.equal(bea.dutyStatus, 'REMOVED');
    assert.ok(st.cases.some((x) => x.employeeId === 'E1006' && x.category === 'SUBSTANCE'), 'restricted case opened');
    assert.ok(st.supportCases.some((x) => x.employeeId === 'E1006' && x.source === 'PROGRAMME_REFERRAL'), 'support referral opened');
    assert.ok(st.notifications.some((n) => n.to === 'hr1' && /Removed from safety-sensitive duty/.test(n.body) && !/Cannabinoid|positive/i.test(n.body)), 'HR status-only notice');
    await page.waitForSelector('button[data-act="rtd"][data-id="E1006"]');
    await markViewDone(); await markViewDone(); // hr1 duty status, manager3 team status
    sc = await scenario();
    assert.ok(sc.completed.includes('S04-random-da-testing'), 'S04 complete');
    step('mro1 verified positive: Bea removed from duty, restricted case + support referral, status-only notices; S04 complete');

    // =====================================================================================
    // S08 – communicable disease and contact tracing
    // =====================================================================================
    console.log('S08 communicable disease');
    await startScenario('S08-communicable-disease');
    await signIn('physician1');
    await go('communicable');
    await page.selectOption('form[data-form="cd-open"] select[name="employeeId"]', 'E1011');
    await page.fill('form[data-form="cd-open"] input[name="condition"]', 'Measles (suspected, demo)');
    await page.fill('form[data-form="cd-open"] input[name="exclusionDays"]', '5');
    await page.fill('form[data-form="cd-open"] input[name="incubationDays"]', '21');
    await submit('form[data-form="cd-open"]');
    await page.waitForSelector('main h1:has-text("Measles")');
    st = await state();
    const cd = st.cdCases.find((x) => x.employeeId === 'E1011' && x.status === 'OPEN');
    assert.ok(cd, 'CD case opened');
    assert.equal(st.employees.find((e) => e.id === 'E1011').dutyStatus, 'EXCLUDED');
    step('physician1 opened the suspected measles case for Sam Steward: ' + cd.id + ' (excluded 5 days)');

    await submit('form[data-form="trace"]');
    const traced = await (await page.waitForSelector('.wz-alert.success:has-text("identified")')).textContent();
    for (const n of ['Cara Cabin', 'Lily Lounge', 'Paolo Pilot', 'Carlos Captain']) assert.ok(traced.includes(n), n + ' traced');
    assert.match(traced, /4 contacts identified/);
    st = await state();
    const cara = st.contacts.find((x) => x.cdCaseId === cd.id && x.employeeId === 'E1010');
    assert.ok(st.notifications.some((n) => n.to === 'crew1' && /exposure/i.test(n.title) && !/Sam Steward|Measles/.test(n.body)), 'contact told without naming the case');
    await shot('10-communicable');
    step('contact tracing from the roster found Cara Cabin, Lily Lounge, Paolo Pilot and Carlos Captain');

    await markViewDone(); // crew1 reads the notice
    await act('employee', 'crew1', 'contactCheckIn', cara.id, { symptoms: false });
    await markViewDone(); // crewsched1 crew status
    await go('communicable/' + cd.id);
    await page.waitForSelector('td:has-text("well")');
    await page.click(`button[data-act="ph-report"][data-id="${cd.id}"]`);
    await page.waitForSelector('[data-test="ph-reported"]');
    st = await state();
    assert.ok(st.cdCases.find((x) => x.id === cd.id).publicHealthReported, 'public-health report time-stamped');
    sc = await scenario();
    assert.ok(sc.completed.includes('S08-communicable-disease'), 'S08 complete');
    step('Cara\'s check-in shows on the contact list; public-health report time-stamped; S08 complete');

    // ---- friendly WZError message (AME rule for crew)
    await go(`case/INF-${new Date().getFullYear()}-000103?tab=fitness`);
    await page.waitForSelector('.wz-alert.warn:has-text("Aeromedical examiner required")');
    step('physician1 is told an AME must decide fitness for cabin crew');

    if (SHOTS) {
      await signIn('nurse1'); await go('home'); await shot('11-my-work');
      await go(`case/ENG-${new Date().getFullYear()}-000104`); await shot('12-case-overview');
      await go('appointments'); await shot('13-clinic-diary');
      await go('surveillance'); await shot('14-surveillance');
      await signIn('promo1'); await go('campaigns'); await shot('15-campaigns');
      await signIn('casemgr1'); await go('absence'); await shot('16-absence');
    }

    assert.deepEqual(errors, [], 'no console errors');
    step('no console errors');
    console.log('PASS');
  } catch (e) {
    console.error('FAIL:', e.message);
    if (errors.length) console.error('console errors:', errors);
    await page.screenshot({ path: path.join(__dirname, 'oh-workspace-failure.png'), fullPage: true }).catch(() => {});
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
