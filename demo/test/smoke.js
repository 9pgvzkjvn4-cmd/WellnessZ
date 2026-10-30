/*
 * Playwright smoke test for the built Employee & Crew App.
 *
 *   python3 demo/build.py && node demo/test/smoke.js
 *
 * Opens demo/dist/employee-app.html via file://, signs in through the demo toolbar, performs a
 * self-referral (Contact OH) and a confidential support self-referral, and asserts that the
 * state persisted in localStorage, survives a reload and reaches a second tab (shared session).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const assert = require('assert/strict');
const { chromium } = require('playwright');

const DIST = path.resolve(__dirname, '..', 'dist');
const APP = 'file://' + path.join(DIST, 'employee-app.html');
const LAUNCHER = 'file://' + path.join(DIST, 'index.html');
const KEY = 'wellnessz-demo-session';

function step(msg) { console.log('  ✓ ' + msg); }

(async () => {
  for (const f of ['employee-app.html', 'index.html', 'demo-data.js']) {
    if (!fs.existsSync(path.join(DIST, f))) { console.error('Missing demo/dist/' + f + ' – run: python3 demo/build.py'); process.exit(1); }
  }
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 420, height: 900 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('dialog', (d) => d.accept());

  try {
    console.log('WellnessZ demo smoke test');
    await page.goto(APP);
    await page.waitForSelector('#wz-demo-toolbar select[data-wzt="user"]');
    // start from the seed
    await page.evaluate(() => WZ.reset());
    step('employee app loaded from file:// with the demo toolbar');

    // ---- sign in through the toolbar user switcher
    await page.selectOption('#wz-demo-toolbar select[data-wzt="user"]', 'office1');
    await page.waitForSelector('.wz-header-user:has-text("Olive Office")');
    await page.waitForSelector('h1:has-text("Welcome, Olive Office")');
    step('signed in as Olive Office (office1)');

    // ---- self-referral (Contact OH)
    await page.click('.wz-nav a[href="#/concern"]');
    await page.waitForSelector('form[data-form="referral"]');
    await page.selectOption('select[name="category"]', 'OTHER');
    await page.fill('textarea[name="concern"]', 'Wrist and neck pain after long days at the screen.');
    await page.fill('textarea[name="workImpact"]', 'Needs more breaks from typing.');
    await page.check('input[name="consent"]');
    await page.click('form[data-form="referral"] button[type="submit"]');
    await page.waitForSelector('h1:has-text("Referral sent")');
    const ref = (await page.textContent('.wz-alert.success strong.wz-mono')).trim();
    assert.match(ref, /^COR-\d{4}-\d{6}$/);
    step('self-referral submitted: ' + ref);

    // ---- persisted in localStorage
    const state = await page.evaluate((k) => JSON.parse(localStorage.getItem(k)), KEY);
    assert.ok(state, 'session exists in localStorage');
    assert.equal(state.signedIn.employee, 'office1');
    const c = state.cases.find((x) => x.id === ref);
    assert.ok(c, 'case persisted');
    assert.equal(c.employeeId, 'E1050');
    assert.equal(c.source, 'SELF_REFERRAL');
    assert.equal(c.status, 'INTAKE');
    assert.ok(state.consents.some((x) => x.employeeId === 'E1050' && x.type === 'OH_PROCESSING' && x.scope.includes(ref)), 'consent recorded');
    assert.ok(state.audit.some((a) => a.action === 'CREATE_REFERRAL' && a.entityId === ref), 'audited');
    assert.ok(state.notifications.some((n) => n.to === 'nurse1' && n.body.includes(ref)), 'OH nurse notified');
    step('case, consent, audit entry and OH notification persisted in localStorage');

    // ---- survives a reload and appears under My cases
    await page.reload();
    await page.click('.wz-nav a[href="#/cases"]');
    await page.waitForSelector('.wz-card-title:has-text("' + ref + '")');
    step('case visible under My cases after reload (status only)');

    // ---- shared session: a second tab sees the change and syncs live
    const launcher = await context.newPage();
    await launcher.goto(LAUNCHER);
    await launcher.waitForSelector('h1:has-text("WellnessZ demo pack")');
    const revBefore = await launcher.evaluate(() => WZ.sessionInfo().rev);

    // ---- confidential support self-referral with screening
    await page.click('.wz-nav a[href="#/support"]');
    await page.waitForSelector('form[data-form="support"]');
    await page.fill('form[data-form="support"] textarea[name="concern"]', 'Finding it hard to switch off.');
    await page.check('form[data-form="support"] input[name="consent"]');
    await page.click('form[data-form="support"] button[type="submit"]');
    await page.waitForSelector('form[data-form="screen"]');
    for (let i = 0; i < 9; i++) await page.check(`form[data-form="screen"] input[name="q${i}"][value="${i < 2 ? 1 : 0}"]`);
    await page.click('form[data-form="screen"] button[type="submit"]');
    await page.waitForSelector('.wz-alert-title:has-text("Thank you for completing the check")');
    const st2 = await page.evaluate((k) => JSON.parse(localStorage.getItem(k)), KEY);
    const sup = st2.supportCases.find((x) => x.employeeId === 'E1050');
    assert.ok(sup && sup.status === 'SCREENED' && sup.screens.length === 1, 'support case and screen persisted');
    assert.equal(sup.sharing, 'NOTHING');
    step('confidential support request and wellbeing check persisted (' + sup.id + ')');

    await launcher.waitForFunction((r) => WZ.sessionInfo().rev > r, revBefore);
    step('second tab (launcher) received the change through the shared session');

    // ---- the general OH nurse cannot open it (restricted class)
    const visibleToNurse = await page.evaluate((id) => { WZ._setApp('oh'); WZ.signIn('nurse1'); const v = WZ.get('supportCases', id); WZ._setApp('employee'); return v; }, sup.id);
    assert.equal(visibleToNurse, null);
    step('restricted support case hidden from the general OH nurse');

    assert.deepEqual(errors, [], 'no console errors');
    step('no console errors');
    console.log('PASS');
  } catch (e) {
    console.error('FAIL:', e.message);
    await page.screenshot({ path: path.join(__dirname, 'smoke-failure.png'), fullPage: true }).catch(() => {});
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
