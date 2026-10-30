/*
 * Playwright test for the Partner Portal (dist/partner-portal.html over file://).
 *
 *   python3 demo/build.py && node demo/test/partner-portal.test.js
 *
 * Plays the partner steps of S03 (physio accept / upload report, other organisation cannot see the
 * referral, TPA adjuster decision) and S10 (EAP accept, attendance, coded closure) through the UI,
 * including the MFA-style sign-in. OH and employee steps are seeded through the engine in the page
 * context. Also checks the consent-withdrawn view and that there are no console errors.
 */
'use strict';
const path = require('path');
const fs = require('fs');
const assert = require('assert/strict');
const { chromium } = require('playwright');

const DIST = path.resolve(__dirname, '..', 'dist');
const APP = 'file://' + path.join(DIST, 'partner-portal.html');
const YEAR = new Date().getFullYear();

function step(msg) { console.log('  ✓ ' + msg); }

function as(page, app, user, body, arg) {
  return page.evaluate(([a, u, b, x]) => {
    const prev = WZ.app;
    WZ._setApp(a); WZ.signIn(u);
    try { return new Function('arg', b)(x); } finally { WZ._setApp(prev); }
  }, [app, user, body, arg]);
}
/** Switch user in the toolbar and complete the two-step verification. */
async function signIn(page, user) {
  await page.selectOption('#wz-demo-toolbar select[data-wzt="user"]', user);
  await page.waitForSelector('.pp-auth h1:has-text("Two-step verification")');
  await page.fill('form[data-form="mfa"] [name="code"]', '246810');
  await page.click('form[data-form="mfa"] button[type="submit"]');
  await page.waitForFunction((u) => WZ.currentUser.username === u && document.querySelector('.wz-header-user') && document.querySelector('main h1'), user);
}
async function go(page, route) {
  await page.evaluate((r) => { location.hash = '#/' + r; }, route);
  await page.waitForFunction((r) => location.hash === '#/' + r && document.querySelector('main h1'), route);
}

(async () => {
  if (!fs.existsSync(path.join(DIST, 'partner-portal.html'))) { console.error('Missing demo/dist/partner-portal.html – run: python3 demo/build.py'); process.exit(1); }
  const browser = await chromium.launch();
  const page = await (await browser.newContext({ viewport: { width: 1360, height: 900 } })).newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('dialog', (d) => d.accept());

  try {
    console.log('Partner Portal test');
    await page.goto(APP);
    await page.waitForSelector('#wz-demo-toolbar select[data-wzt="user"]');
    await page.evaluate(() => { WZ.reset(); sessionStorage.clear(); });
    await page.reload();
    assert.equal(await page.getAttribute('body', 'data-theme'), 'partner');
    await page.waitForSelector('.pp-auth h1:has-text("Sign in to your account")');
    await page.selectOption('form[data-form="signin"] [name="username"]', 'physio1');
    await page.click('form[data-form="signin"] button[type="submit"]');
    await page.waitForSelector('.pp-auth h1:has-text("Two-step verification")');
    await page.fill('form[data-form="mfa"] [name="code"]', '12');
    await page.click('form[data-form="mfa"] button[type="submit"]');
    await page.waitForSelector('form[data-form="mfa"] .wz-error:not([hidden])');
    await page.fill('form[data-form="mfa"] [name="code"]', '246810');
    await page.click('form[data-form="mfa"] button[type="submit"]');
    await page.waitForSelector('main h1:has-text("Referral inbox")');
    assert.equal(await page.textContent('.wz-org-chip'), 'PHYSIOPLUS');
    step('teal portal, MFA-style sign-in (account → 6-digit code) as Phoebe Physio of PHYSIOPLUS');

    // ================================================================= S03
    console.log(' S03 – partner referral, report, claim decision');
    const caseId = `ENG-${YEAR}-000104`;
    await as(page, 'oh', 'nurse1', 'WZ.createTreatmentPlan(arg, { goals: ["Full grip strength", "Return to hand-tool work"], interventions: ["Hand therapy", "Home exercises"], expectedRecoveryDays: 28 });', caseId);
    const ref = await as(page, 'oh', 'nurse1', 'return WZ.sendPartnerReferral({ caseId: arg, partnerOrg: "PHYSIOPLUS", service: "PHYSIO_ASSESS", urgency: "URGENT", consent: true });', caseId);
    await page.reload();
    await page.waitForSelector(`a:has-text("${ref.id}")`);
    const row = await page.locator('tr', { hasText: ref.id }).innerText();
    assert.match(row, /Ezra Engineer/); assert.match(row, /Urgent/); assert.match(row, /Sent/);
    await page.click(`a:has-text("${ref.id}")`);
    await page.waitForSelector(`main h1:has-text("Referral ${ref.id}")`);
    await page.fill('form[data-form="accept"] [name="appointmentAt"]', await page.evaluate(() => { const d = new Date(); d.setDate(d.getDate() + 2); return d.toISOString().slice(0, 10) + 'T10:00'; }));
    await page.click('form[data-form="accept"] button[type="submit"]');
    await page.waitForSelector('main h1 + .wz-badge:has-text("Appointment set"), .pp-head .wz-badge:has-text("Appointment set")');
    let st = await page.evaluate(() => WZ.session());
    let r = st.referrals.find((x) => x.id === ref.id);
    assert.equal(r.status, 'APPOINTMENT_SET');
    assert.ok(st.appointments.some((a) => a.employeeId === 'E1030' && a.external && a.start === r.appointmentAt), 'Ezra sees the appointment');
    step('step 3: physio1 accepted ' + ref.id + ' with an appointment → "Appointment set"');

    await signIn(page, 'partner1');
    await go(page, 'referrals');
    assert.equal(await page.locator(`text=${ref.id}`).count(), 0, 'not in the City Hospital inbox');
    await go(page, 'referral/' + ref.id);
    await page.waitForSelector('main h1:has-text("Referral not available")');
    assert.match(await page.textContent('main'), /belongs to another organisation/);
    assert.doesNotMatch(await page.textContent('main'), /Ezra/);
    const direct = await page.evaluate((id) => { try { WZ.partnerAccept(id, {}); return 'ok'; } catch (e) { return e.code; } }, ref.id);
    assert.equal(direct, 'not_found');
    step('step 4: partner1 (City Hospital) cannot see or act on the PhysioPlus referral – graceful "not available"');

    await signIn(page, 'physio1');
    await go(page, 'referral/' + ref.id);
    await page.fill('form[data-form="report"] [name="summary"]', 'Soft-tissue crush injury, no bony tenderness. Grip 70% of the other side.');
    await page.fill('form[data-form="report"] [name="recommendation"]', 'Light duties 2 weeks, no vibrating tools.');
    await page.setInputFiles('form[data-form="report"] [name="file"]', { name: 'physioplus-ezra.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4 demo') });
    await page.click('form[data-form="report"] button[type="submit"]');
    await page.waitForSelector('.pp-head .wz-badge:has-text("Report received")');
    st = await page.evaluate(() => WZ.session());
    r = st.referrals.find((x) => x.id === ref.id);
    assert.equal(r.report.fileName, 'physioplus-ezra.pdf');
    assert.ok(st.notifications.some((n) => n.to === 'nurse1' && n.title === 'Partner report received'));
    await go(page, 'documents');
    await page.waitForSelector('td:has-text("physioplus-ezra.pdf")');
    step('step 5: physio1 uploaded the report → "Report received", nurse1 notified, listed under Shared documents');

    const claim = await as(page, 'oh', 'claims1', 'return WZ.openClaim({ caseId: arg, payerOrg: "GULFTPA", lostTimeDays: 3, reserve: 2500, consent: true });', caseId);
    await page.reload();
    await signIn(page, 'adjuster1');
    await go(page, 'claims');
    await page.waitForSelector(`a:has-text("${claim.id}")`);
    await page.click(`a:has-text("${claim.id}")`);
    await page.waitForSelector(`main h1:has-text("Claim ${claim.id}")`);
    assert.doesNotMatch(await page.textContent('main'), /crush|grip/i, 'no clinical content for the adjuster');
    await page.fill('form[data-form="message"] [name="text"]', 'Please confirm the date of the first lost shift.');
    await page.click('form[data-form="message"] button[type="submit"]');
    await page.waitForSelector('.pp-msg.mine:has-text("first lost shift")');
    await page.check('form[data-form="decision"] input[name="decision"][value="ACCEPTED"]');
    await page.fill('form[data-form="decision"] [name="note"]', 'Accepted – work-related injury confirmed.');
    await page.click('form[data-form="decision"] button[type="submit"]');
    await page.waitForSelector('.pp-head .wz-badge:has-text("Accepted")');
    st = await page.evaluate(() => WZ.session());
    const cl = st.claims.find((x) => x.id === claim.id);
    assert.equal(cl.status, 'ACCEPTED'); assert.equal(cl.decision.note, 'Accepted – work-related injury confirmed.');
    assert.ok(st.notifications.some((n) => n.to === 'claims1' && /TPA decision: Accepted/.test(n.title)));
    assert.ok(st.notifications.some((n) => n.to === 'hr1' && n.title === 'Claim status updated' && /status only/.test(n.body)));
    step('step 7: adjuster1 messaged and accepted ' + claim.id + ' → claims coordinator and HR (status only) notified');

    // consent withdrawn: the employee withdraws the treatment consent; the referral shows no data
    await as(page, 'employee', 'engineer1', 'WZ.withdrawConsent(arg);', r.consentId);
    await page.reload();
    await signIn(page, 'physio1');
    await page.waitForSelector(`tr:has-text("${ref.id}") .wz-badge:has-text("Consent withdrawn")`);
    await go(page, 'referral/' + ref.id);
    await page.waitForSelector('.wz-alert-title:has-text("Consent withdrawn – access stopped")');
    const wtxt = await page.textContent('main');
    assert.doesNotMatch(wtxt, /Ezra|Grip|crush/i);
    assert.equal(await page.$('form[data-form="report"]'), null);
    const viaEngine = await page.evaluate((id) => { try { WZ.partnerClose(id, { outcome: 'COMPLETED' }); return 'ok'; } catch (e) { return e.code; } }, ref.id);
    assert.equal(viaEngine, 'consent_withdrawn');
    step('consent withdrawn → referral shows "Consent withdrawn", no personal data, no actions');

    // ================================================================= S10
    console.log(' S10 – EAP referral, attendance, coded closure');
    await page.evaluate(() => { WZ.reset(); WZ.startScenario('S10-partner-eap'); });
    const sup = await as(page, 'employee', 'office1', 'return WZ.selfReferSupport({ concern: "Work–life stress after moving house", contactPreference: "EMAIL", consent: true });');
    await as(page, 'oh', 'psych1', 'WZ.addSupportNote(arg, "Initial call. Low risk. Offered EAP short-term counselling; accepted.");', sup.id);
    const eapRef = await as(page, 'oh', 'psych1', 'return WZ.sendPartnerReferral({ supportCaseId: arg, partnerOrg: "EAP_WELLBEING", service: "EAP_COUNSELLING", consent: true });', sup.id);
    await page.reload();
    await signIn(page, 'eap1');
    await go(page, 'eap');
    await page.waitForSelector('main h1:has-text("EAP referrals")');
    const erow = await page.locator('tr', { hasText: eapRef.id }).innerText();
    assert.match(erow, /Olive Office/); assert.match(erow, /E-mail/);
    await page.click(`tr:has-text("${eapRef.id}") a.wz-mono`);
    await page.waitForSelector(`main h1:has-text("Referral ${eapRef.id}")`);
    const etxt = await page.textContent('main');
    assert.doesNotMatch(etxt, /moving house|Low risk/i, 'EAP sees name and contact preference only');
    await page.fill('form[data-form="accept"] [name="appointmentAt"]', await page.evaluate(() => { const d = new Date(); d.setDate(d.getDate() + 3); return d.toISOString().slice(0, 10) + 'T17:00'; }));
    await page.click('form[data-form="accept"] button[type="submit"]');
    await page.waitForSelector('.pp-head .wz-badge:has-text("Appointment set")');
    step('step 4: eap1 accepted ' + eapRef.id + ' and set the first session');
    await page.fill('form[data-form="attendance"] [name="sessions"]', '3');
    await page.click('form[data-form="attendance"] button[type="submit"]');
    await page.waitForSelector('.pp-head .wz-badge:has-text("In progress")');
    st = await page.evaluate(() => WZ.session());
    assert.equal(st.referrals.find((x) => x.id === eapRef.id).sessions, 3);
    assert.equal(await page.$('form[data-form="report"]'), null, 'no report upload for EAP');
    step('step 5: attendance recorded – 3 sessions, no notes');
    assert.equal(await page.$('form[data-form="close"] textarea, form[data-form="close"] input[type="text"]'), null, 'no free text at closure');
    await page.check('form[data-form="close"] input[name="outcome"][value="COMPLETED"]');
    await page.click('form[data-form="close"] button[type="submit"]');
    await page.waitForSelector('.pp-head .wz-badge:has-text("Closed")');
    st = await page.evaluate(() => WZ.session());
    const er = st.referrals.find((x) => x.id === eapRef.id);
    assert.equal(er.status, 'CLOSED'); assert.equal(er.outcome, 'COMPLETED');
    assert.ok(st.notifications.some((n) => n.to === 'psych1' && n.title === 'EAP referral closed'));
    assert.ok(st.notifications.some((n) => n.to === 'office1' && n.title === 'Your EAP sessions have ended'));
    assert.equal((await page.evaluate(() => WZ.scenarioState())).stepIndex, 6, 'S10 advanced through the partner steps');
    step('step 6: closed with coded outcome "Completed" → psych1 and Olive notified');

    await signIn(page, 'physio1');
    await go(page, 'referral/' + eapRef.id);
    await page.waitForSelector('main h1:has-text("Referral not available")');
    step('the EAP referral is not visible to another partner organisation');

    // notification route mapping: the EAP partner's notification opens the EAP inbox
    await signIn(page, 'eap1');
    await go(page, 'notifications');
    const href = await page.getAttribute('main a.wz-link-btn[data-act="open-note"]', 'href');
    assert.equal(href, '#/eap');
    step('notification links map to portal routes');

    assert.deepEqual(errors, [], 'no console errors');
    step('no console errors');
    console.log('PASS');
  } catch (e) {
    console.error('FAIL:', e.stack || e.message);
    if (errors.length) console.error('console errors:', errors);
    await page.screenshot({ path: path.join(__dirname, 'partner-failure.png'), fullPage: true }).catch(() => {});
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
