/*
 * Pure-JS tests for the WellnessZ demo engine. Run: node demo/test/engine.test.js
 * Uses node:test and an in-memory localStorage shim. No browser needed.
 */
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('path');
const fs = require('fs');

// ---- minimal browser shims -----------------------------------------------------------
const mem = {};
globalThis.window = globalThis;
globalThis.localStorage = {
  getItem: (k) => (k in mem ? mem[k] : null),
  setItem: (k, v) => { mem[k] = String(v); },
  removeItem: (k) => { delete mem[k]; }
};
globalThis.WZ_SCENARIOS = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'scenarios.json'), 'utf8'));
require('../data/demo-data.js');
const WZ = require('../shared/engine.js');

const YEAR = new Date().getFullYear();
const S = WZ_SCENARIOS.scenarios;

/** Sign in as a demo user (switching the app context to one of the user's apps). */
function as(username) {
  const u = WZ.session().users.find((x) => x.username === username);
  assert.ok(u, 'unknown user ' + username);
  WZ._setApp(u.apps[0]);
  WZ.signIn(username);
  return WZ.currentUser;
}
function fresh() { WZ.reset(); }
function iso(days, hhmm) { const d = new Date(); d.setDate(d.getDate() + days); const [h, m] = (hhmm || '10:00').split(':'); d.setHours(+h, +m, 0, 0); return d.toISOString(); }
function today() { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); }
function throwsCode(fn, code) { assert.throws(fn, (e) => e.code === code, 'expected error ' + code); }
function raw() { return JSON.parse(localStorage.getItem(WZ.STORAGE_KEY)); }

// =====================================================================================
test('seed: synthetic data set is complete and version-stamped', () => {
  fresh();
  const s = WZ.session();
  assert.equal(s.employees.length, 40);
  assert.equal(s.meta.seedVersion, '1.0.0');
  assert.ok(s.meta.notice.includes('Synthetic'));
  const roles = new Set(s.users.flatMap((u) => u.roles));
  ['EMPLOYEE', 'MANAGER', 'HR', 'OH_CLINICIAN', 'OH_PHYSICIAN', 'AME', 'PSYCH_CLINICIAN', 'MRO', 'CASE_MANAGER', 'CLAIMS_COORDINATOR',
    'HEALTH_PROMOTION_LEAD', 'SAFETY_MANAGER', 'PARTNER_PROVIDER', 'EAP_PARTNER', 'TPA_ADJUSTER', 'DPO'].forEach((r) => assert.ok(roles.has(r), 'missing role ' + r));
  ['employee', 'manager', 'oh', 'partner'].forEach((app) => assert.ok(WZ.users(app).length >= 4, 'app ' + app));
  ['PILOT', 'CABIN_CREW', 'ENGINEER', 'RAMP', 'CATERING', 'OFFICE'].forEach((r) => assert.ok(s.safetyRoles.some((x) => x.code === r)));
  assert.ok(s.sites.some((x) => x.type === 'HUB') && s.sites.filter((x) => x.type === 'OUTSTATION').length >= 2);
  // every case, user and record refers to an existing employee
  const ids = new Set(s.employees.map((e) => e.id));
  for (const c of s.cases) assert.ok(ids.has(c.employeeId), c.id);
  for (const u of s.users) if (u.employeeId) assert.ok(ids.has(u.employeeId), u.username);
  for (const col of ['absences', 'surveillance', 'referrals', 'claims', 'supportCases', 'travelConsultations', 'contacts', 'consents', 'documents'])
    for (const r of s[col]) assert.ok(ids.has(r.employeeId), col + ' ' + (r.id || r.code));
  // each case is at a known stage and several stages are represented
  const stages = new Set(s.cases.map((c) => c.status));
  assert.ok(stages.size >= 6);
});

test('session: persists in localStorage, reset re-seeds, export/import round-trips', async () => {
  fresh();
  as('employee1');
  WZ.markAllRead();
  assert.ok(raw().notifications.filter((n) => n.to === 'employee1').every((n) => n.read));
  const exported = WZ.exportSession();
  const parsed = JSON.parse(exported);
  assert.equal(parsed.format, 'wellnessz-demo-session');
  WZ.reset();
  assert.ok(raw().notifications.some((n) => n.to === 'employee1' && !n.read));
  await WZ.importSession(exported);
  assert.ok(raw().notifications.filter((n) => n.to === 'employee1').every((n) => n.read));
  await assert.rejects(WZ.importSession('{"format":"other"}'), (e) => e.code === 'invalid_file');
  const info = WZ.sessionInfo();
  assert.equal(info.storageKey, 'wellnessz-demo-session');
});

test('subscribe: listeners fire on every change', () => {
  fresh();
  let n = 0;
  const off = WZ.subscribe(() => n++);
  as('employee1');
  WZ.markAllRead();
  off();
  WZ.markAllRead();
  assert.ok(n >= 2);
  const before = n; WZ.markAllRead(); assert.equal(n, before);
});

test('identity and permissions: users per app, sign-in limited to the app, role matrix', () => {
  fresh();
  WZ._setApp('employee');
  throwsCode(() => WZ.signIn('nurse1'), 'forbidden');
  as('nurse1');
  assert.equal(WZ.currentUser.username, 'nurse1');
  assert.ok(WZ.can('physician1', 'fitness.decide'));
  assert.ok(!WZ.can('nurse1', 'fitness.decide'));
  assert.ok(WZ.can('ame1', 'fitness.decide.aviation'));
  assert.ok(!WZ.can('hr1', 'case.read'));
  assert.ok(!WZ.can('damanager1', 'testing.results'));
  assert.ok(WZ.can('mro1', 'testing.mro'));
  assert.ok(WZ.can('adjuster1', 'claim.adjust'));
  assert.ok(!WZ.can('physio1', 'claim.adjust'));
  // every documented action exists
  const required = ['createReferral', 'triage', 'assign', 'recordFitnessDecision', 'setRestrictions', 'createRtwPlan', 'acknowledge', 'bookAppointment', 'recordAbsence',
    'raiseEarlyIntervention', 'createTreatmentPlan', 'sendPartnerReferral', 'partnerAccept', 'partnerUploadReport', 'openClaim', 'adjusterDecision', 'selfReferSupport',
    'urgentCallback', 'shareFitnessOnly', 'randomSelection', 'collectSpecimen', 'mroVerdict', 'travelConsultation', 'publishAdvisory', 'acknowledgeAdvisory', 'contactTrace',
    'campaignInvite', 'joinCampaign', 'screeningResult', 'closeCase'];
  required.forEach((a) => assert.equal(typeof WZ[a], 'function', a));
});

test('denied actions are refused and audited', () => {
  fresh();
  as('nurse1');
  throwsCode(() => WZ.recordFitnessDecision(`CAT-${YEAR}-000101`, { status: 'FIT' }), 'forbidden');
  const last = raw().audit.slice(-1)[0];
  assert.equal(last.outcome, 'DENIED');
  assert.equal(last.actor, 'nurse1');
});

test('audit log is hash-chained', () => {
  fresh();
  as('employee1'); WZ.markAllRead();
  as('nurse1'); WZ.triage(`GND-${YEAR}-000102`, { urgency: 'URGENT' });
  const a = raw().audit;
  for (let i = 1; i < a.length; i++) assert.equal(a[i].prevHash, a[i - 1].hash);
});

// =====================================================================================
test('S01 EHS incident -> triage -> fitness -> manager status only -> RTW -> close', () => {
  fresh();
  WZ.startScenario('S01-ehs-incident-rtw');
  as('hse1');
  const c = WZ.createReferral({ employeeId: 'E1004', source: 'EHS_INCIDENT', category: 'INJURY', concern: 'Lower back pain after lifting', location: 'Stand 12' });
  assert.match(c.id, /^GND-\d{4}-\d{6}$/);
  assert.ok(raw().notifications.some((n) => n.to === 'nurse1' && n.body.includes(c.id)));
  as('nurse1');
  WZ.triage(c.id, { urgency: 'SEMI_URGENT', protocol: 'MSK_INJURY', recordable: true });
  WZ.assign(c.id, 'nurse1');
  as('nurse1');
  throwsCode(() => WZ.recordFitnessDecision(c.id, { status: 'FIT' }), 'forbidden');
  as('physician1');
  WZ.recordFitnessDecision(c.id, { status: 'FIT_WITH_RESTRICTIONS', restrictions: ['NO_LIFT_10'], reviewDate: today() });
  // manager sees status only
  as('manager3');
  const sv = WZ.get('cases', c.id);
  assert.equal(sv.view, 'STATUS');
  assert.equal(sv.summary, undefined);
  assert.equal(sv.triage, undefined);
  assert.equal(sv.fitnessStatus, 'FIT_WITH_RESTRICTIONS');
  assert.deepEqual(sv.restrictions.map((r) => r.text), ['No lifting above 10 kg']);
  assert.ok(sv.timeline.every((t) => !/lifting a 30/.test(t.text)));
  const team = WZ.list('team').find((t) => t.id === 'E1004');
  assert.equal(team.fitnessStatus, 'FIT_WITH_RESTRICTIONS');
  // another manager cannot see it
  as('manager1');
  assert.equal(WZ.get('cases', c.id), null);
  as('nurse1');
  const plan = WZ.createRtwPlan(c.id, { phases: [{ duties: 'Light' }, { duties: 'Normal' }] });
  assert.equal(plan.status, 'ISSUED');
  as('ramp1');
  assert.ok(WZ.tasks().some((t) => t.kind === 'RTW_ACK'));
  const selfView = WZ.get('cases', c.id);
  assert.equal(selfView.view, 'SELF');
  assert.ok(selfView.timeline.every((t) => t.type !== 'NOTE'));
  WZ.acknowledge(plan.id);
  throwsCode(() => WZ.acknowledge(plan.id), 'invalid_state');
  as('manager3');
  const p2 = WZ.acknowledge(plan.id);
  assert.equal(p2.status, 'ACTIVE');
  as('physician1');
  WZ.recordFitnessDecision(c.id, { status: 'FIT' });
  as('nurse1');
  const closed = WZ.closeCase(c.id, { reason: 'Recovered' });
  assert.equal(closed.status, 'CLOSED');
  const st = WZ.scenarioState();
  assert.equal(st.stepIndex, 4, 'auto-advanced through the first four action steps (view step waits)');
});

test('fitness decisions for crew require an AME; closure guard for safety-critical roles', () => {
  fresh();
  as('physician1');
  throwsCode(() => WZ.recordFitnessDecision(`INF-${YEAR}-000103`, { status: 'FIT' }), 'forbidden');
  as('ame1');
  WZ.recordFitnessDecision(`INF-${YEAR}-000103`, { status: 'FIT' });
  assert.equal(raw().employees.find((e) => e.id === 'E1010').fitness.status, 'FIT');
  as('nurse1');
  throwsCode(() => WZ.closeCase(`ENG-${YEAR}-000104`), 'closure_guard');
  assert.ok(raw().notifications.some((n) => n.to === 'crewsched1' && n.title.includes('Crew roster')));
});

test('setRestrictions updates functional restrictions and discloses to the manager', () => {
  fresh();
  as('nurse1');
  WZ.setRestrictions(`ENG-${YEAR}-000105`, ['NO_HEIGHTS', { code: 'NO_NIGHT_SHIFTS' }], { until: today() });
  const c = raw().cases.find((x) => x.id === `ENG-${YEAR}-000105`);
  assert.equal(c.restrictions.length, 2);
  assert.ok(raw().disclosures.some((d) => d.employeeId === 'E1031' && d.what.includes('restrictions')));
});

// =====================================================================================
test('S02 confidential self-referral: restricted class, urgent call-back, share fitness only', () => {
  fresh();
  as('pilot1');
  throwsCode(() => WZ.selfReferSupport({ concern: 'x' }), 'consent_required');
  const sup = WZ.selfReferSupport({ concern: 'Poor sleep', contactPreference: 'PHONE', consent: true });
  assert.equal(sup.view, 'SELF');
  const r = WZ.submitSupportScreen(sup.id, [2, 2, 1, 2, 1, 1, 1, 1, 1]);
  assert.equal(r.urgent, true);
  assert.equal(r.emergencyNumber, '999');
  assert.ok(raw().notifications.some((n) => n.to === 'psych1' && n.kind === 'URGENT'));
  assert.ok(!raw().notifications.some((n) => n.to === 'manager4' && /support/i.test(n.title + n.body)));
  // general nurse: no access, placeholder count, audited
  as('nurse1');
  assert.equal(WZ.get('supportCases', sup.id), null);
  assert.equal(WZ.list('supportCases').length, 0);
  assert.equal(WZ.placeholderCount('E1020'), 1);
  assert.ok(raw().audit.some((a) => a.actor === 'nurse1' && a.entityId === sup.id && a.outcome === 'DENIED'));
  throwsCode(() => WZ.recordCallback(sup.id, null, { outcome: 'REACHED_SAFE' }), 'forbidden');
  // the named clinician sees everything
  as('psych1');
  const full = WZ.get('supportCases', sup.id);
  assert.equal(full.view, 'FULL');
  assert.equal(full.concern, 'Poor sleep');
  WZ.recordCallback(sup.id, null, { outcome: 'REACHED_SAFE' });
  assert.equal(WZ.get('supportCases', sup.id).status, 'ACTIVE');
  throwsCode(() => WZ.recordSupportFitness(sup.id, { status: 'TEMPORARILY_UNFIT' }), 'forbidden'); // crew relief is the AME's
  as('ame1');
  WZ.recordSupportFitness(sup.id, { status: 'TEMPORARILY_UNFIT' });
  assert.equal(raw().employees.find((e) => e.id === 'E1020').dutyStatus, 'RELIEVED');
  as('pilot1');
  const shared = WZ.shareFitnessOnly(sup.id);
  assert.equal(shared.sharing, 'FITNESS_ONLY');
  assert.ok(WZ.list('disclosures').some((d) => d.what.includes('Fitness status only')));
  throwsCode(() => WZ.setSupportSharing(sup.id, 'FITNESS_AND_ADJUSTMENTS', 'Needs counselling for anxiety'), 'clinical_content');
  as('manager4');
  const out = WZ.list('sharedOutcomes');
  const row = out.find((x) => x.employeeId === 'E1020');
  assert.deepEqual(Object.keys(row).sort(), ['adjustments', 'employeeId', 'employeeName', 'fitness', 'fitnessLabel', 'sharedAt'].sort());
  assert.equal(row.fitness, 'TEMPORARILY_UNFIT');
  assert.equal(row.adjustments, null);
  assert.equal(WZ.list('supportCases').length, 0);
  as('crewsched1');
  const crew = WZ.list('crewStatus').find((x) => x.employeeId === 'E1020');
  assert.equal(crew.rosterStatus, 'UNFIT');
  assert.equal(crew.conflict, true);
  assert.equal(crew.reason, undefined);
  // break-glass gives read-only access and alerts the DPO
  as('nurse1');
  WZ.breakGlass('E1020', 'Clinical emergency at the hub clinic');
  const bg = WZ.get('supportCases', sup.id);
  assert.equal(bg.readOnly, true);
  assert.ok(raw().notifications.some((n) => n.to === 'dpo1' && n.title.includes('Break-glass')));
  as('dpo1');
  assert.ok(WZ.list('audit').some((a) => a.action === 'BREAK_GLASS'));
});

test('urgentCallback request and closure guards on support cases', () => {
  fresh();
  as('office1');
  const sup = WZ.selfReferSupport({ concern: 'Stress', consent: true });
  const cb = WZ.urgentCallback(sup.id, { phone: '+00 7000 000 999' });
  assert.ok(cb.urgentItemId);
  as('psych1');
  throwsCode(() => WZ.closeSupportCase(sup.id), 'urgent_open');
  WZ.recordCallback(sup.id, cb.urgentItemId, { outcome: 'REACHED_SAFE' });
  WZ.closeSupportCase(sup.id, 'Resolved');
  throwsCode(() => WZ.addSupportNote(sup.id, 'late note'), 'case_closed');
});

test('restricted kernel cases are hidden from managers, HR and general OH', () => {
  fresh();
  const id = `FLT-${YEAR}-000110`;
  as('nurse1'); assert.equal(WZ.get('cases', id), null); assert.ok(!WZ.list('cases').some((c) => c.id === id));
  as('manager4'); assert.ok(!WZ.list('cases').some((c) => c.id === id));
  as('hr1'); assert.ok(!WZ.list('cases').some((c) => c.id === id));
  as('psych1'); assert.equal(WZ.get('cases', id).view, 'FULL');
  as('mro1'); assert.equal(WZ.get('cases', id), null); assert.equal(WZ.get('cases', `GND-${YEAR}-000111`).view, 'FULL');
});

// =====================================================================================
test('S03 injury -> treatment plan -> partner referral and report -> claim -> TPA decision', () => {
  fresh();
  const caseId = `ENG-${YEAR}-000104`;
  as('nurse1');
  const tp = WZ.createTreatmentPlan(caseId, { goals: ['Grip strength'], expectedRecoveryDays: 28 });
  assert.equal(tp.status, 'ACTIVE');
  throwsCode(() => WZ.sendPartnerReferral({ caseId, partnerOrg: 'PHYSIOPLUS', service: 'PHYSIO_ASSESS' }), 'consent_required');
  const ref = WZ.sendPartnerReferral({ caseId, partnerOrg: 'PHYSIOPLUS', service: 'PHYSIO_ASSESS', urgency: 'URGENT', question: 'Assess and treat', consent: true });
  assert.equal(ref.status, 'SENT');
  as('partner1');
  assert.ok(!WZ.list('referrals').some((r) => r.id === ref.id), 'other partner org must not see it');
  throwsCode(() => WZ.partnerAccept(ref.id), 'not_found');
  as('physio1');
  const inbox = WZ.list('referrals');
  assert.ok(inbox.every((r) => r.partnerOrg === 'PHYSIOPLUS'));
  assert.ok(inbox.some((r) => r.id === ref.id));
  const acc = WZ.partnerAccept(ref.id, { appointmentAt: iso(2) });
  assert.equal(acc.status, 'APPOINTMENT_SET');
  WZ.partnerUploadReport(ref.id, { summary: 'Soft tissue injury', recommendation: 'Light duties', fileName: 'r.pdf' });
  as('engineer1');
  const myRef = WZ.list('referrals').find((r) => r.id === ref.id);
  assert.equal(myRef.report, undefined, 'employee sees the referral status, not the report content');
  assert.ok(WZ.list('appointments').some((a) => a.external));
  as('claims1');
  throwsCode(() => WZ.openClaim({ caseId, payerOrg: 'GULFTPA' }), 'consent_required');
  const cl = WZ.openClaim({ caseId, payerOrg: 'GULFTPA', lostTimeDays: 3, reserve: 2500, consent: true });
  assert.equal(cl.status, 'SUBMITTED');
  throwsCode(() => WZ.openClaim({ caseId, payerOrg: 'GULFTPA', consent: true }), 'invalid_state');
  as('adjuster1');
  const ac = WZ.list('claims').find((x) => x.id === cl.id);
  assert.equal(ac.caseId, undefined);
  assert.ok(WZ.list('claims').every((x) => x.payerOrg === 'GULFTPA'));
  WZ.claimMessage(cl.id, 'Please send the fitness status.');
  const dec = WZ.adjusterDecision(cl.id, { decision: 'ACCEPTED', note: 'Work-related' });
  assert.equal(dec.status, 'ACCEPTED');
  as('hr1');
  const hv = WZ.list('claims').find((x) => x.id === cl.id);
  assert.equal(hv.status, 'ACCEPTED');
  assert.equal(hv.reserve, undefined);
  assert.equal(hv.payerOrg, undefined);
  assert.equal(hv.messages, undefined);
  as('physio1');
  throwsCode(() => WZ.adjusterDecision(cl.id, { decision: 'DENIED' }), 'forbidden');
});

test('consent withdrawal stops partner access immediately', () => {
  fresh();
  as('nurse1');
  const ref = WZ.sendPartnerReferral({ caseId: `ENG-${YEAR}-000104`, partnerOrg: 'PHYSIOPLUS', service: 'PHYSIO_ASSESS', consent: true });
  as('engineer1');
  const con = WZ.list('consents').find((c) => c.type === 'PARTNER_TREATMENT' && c.status === 'GIVEN');
  WZ.withdrawConsent(con.id);
  as('physio1');
  const v = WZ.list('referrals').find((r) => r.id === ref.id);
  assert.equal(v.status, 'CONSENT_WITHDRAWN');
  assert.equal(v.question, undefined);
  throwsCode(() => WZ.partnerAccept(ref.id), 'consent_withdrawn');
});

// =====================================================================================
test('S04 random D&A selection -> collection -> MRO -> HR duty status (status only)', () => {
  fresh();
  as('damanager1');
  const sel = WZ.randomSelection('POOL-GND-ENG', { count: 2 });
  assert.deepEqual(sel.selected, ['E1006', 'E1033'], 'seeded reproducible draw');
  const testId = sel.testIds[0];
  as('baggage1');
  const notice = WZ.list('tests').find((t) => t.id === testId);
  assert.equal(notice.maskedStatus, 'NOTIFIED');
  assert.ok(notice.notice.location && notice.notice.window);
  assert.equal(notice.reason, undefined);
  assert.ok(WZ.tasks().some((t) => t.kind === 'TEST_NOTICE'));
  as('collector1');
  const col = WZ.collectSpecimen(testId, { seals: 'Intact' });
  assert.equal(col.status, 'IN_LAB');
  as('damanager1');
  const pm = WZ.get('tests', testId);
  assert.equal(pm.statusLabel, 'With the testing programme');
  assert.equal(pm.lab, undefined);
  throwsCode(() => WZ.mroVerdict(testId, { outcome: 'NEGATIVE' }), 'forbidden');
  as('mro1');
  WZ.labResult(testId, { result: 'NON_NEGATIVE', analyte: 'Cannabinoids (demo)' });
  assert.equal(WZ.get('tests', testId).status, 'MRO_REVIEW');
  const v = WZ.mroVerdict(testId, { outcome: 'POSITIVE' });
  assert.ok(v.kernelCaseId && v.supportCaseId);
  as('hr1');
  const ds = WZ.list('dutyStatus').find((d) => d.employeeId === 'E1006');
  assert.equal(ds.dutyStatus, 'REMOVED');
  assert.deepEqual(Object.keys(ds).sort(), ['displayName', 'dutyLabel', 'dutyStatus', 'employeeId', 'since', 'unit'].sort());
  assert.ok(!WZ.list('cases').some((c) => c.id === v.kernelCaseId));
  as('manager3');
  assert.equal(WZ.list('team').find((t) => t.id === 'E1006').dutyStatus, 'REMOVED');
  assert.equal(WZ.list('tests').length, 0);
  as('baggage1');
  assert.equal(WZ.get('tests', testId).maskedStatus, 'COMPLETED');
  as('nurse1');
  assert.equal(WZ.get('cases', v.kernelCaseId), null);
  assert.equal(WZ.placeholderCount('E1006'), 2);
  as('psych1');
  assert.ok(WZ.get('supportCases', v.supportCaseId));
  as('mro1');
  WZ.returnToDuty('E1006', {});
  assert.equal(raw().employees.find((e) => e.id === 'E1006').dutyStatus, 'RETURN_PENDING');
});

test('for-cause requests: managers only for their team, masked status', () => {
  fresh();
  as('manager1');
  throwsCode(() => WZ.forCauseRequest({ employeeId: 'E1004', observations: ['a', 'b'] }), 'forbidden');
  const t = WZ.forCauseRequest({ employeeId: 'E1043', observations: ['Smell of alcohol', 'Unsteady on the high-loader'] });
  assert.equal(t.statusLabel, 'Please attend');
  as('collector1');
  WZ.collectSpecimen(t.id, { breathOnly: true });
  as('manager1');
  assert.equal(WZ.list('tests')[0].statusLabel, 'With the testing programme');
});

// =====================================================================================
test('S05 absence trigger -> early intervention -> advice report -> employee release -> manager', () => {
  fresh();
  as('manager3');
  const ab = WZ.recordAbsence({ employeeId: 'E1006', start: today(), reasonCategory: 'Illness – short term' });
  assert.equal(ab.triggers[0].rule, 'SPELLS_6M');
  throwsCode(() => WZ.recordAbsence({ employeeId: 'E1001', start: today() }), 'forbidden');
  as('hr1');
  const ei = WZ.list('earlyInterventions').find((x) => x.employeeId === 'E1006' && x.status === 'OPEN');
  assert.ok(ei);
  assert.ok(WZ.tasks().some((t) => t.kind === 'EARLY_INTERVENTION'));
  const res = WZ.raiseEarlyIntervention(ei.id, { questions: ['Underlying reason?'] });
  assert.ok(res.caseId && res.adviceRequestId);
  as('casemgr1');
  const c = WZ.get('cases', res.caseId);
  assert.equal(c.owner, 'casemgr1');
  WZ.writeAdviceReport(res.adviceRequestId, { fitness: 'FIT', workImpact: 'Fit for role', adjustments: 'Early shifts for 4 weeks' });
  as('manager3');
  const before = WZ.list('adviceRequests').find((a) => a.id === res.adviceRequestId);
  assert.equal(before.report, undefined, 'manager cannot read before release');
  as('baggage1');
  assert.ok(WZ.tasks().some((t) => t.kind === 'ADVICE_REVIEW'));
  WZ.releaseAdvice(res.adviceRequestId, { decision: 'RELEASE' });
  assert.ok(WZ.list('disclosures').some((d) => d.what.includes('advice report')));
  as('manager3');
  const after = WZ.list('adviceRequests').find((a) => a.id === res.adviceRequestId);
  assert.equal(after.report.adjustments, 'Early shifts for 4 weeks');
  assert.ok(WZ.list('documents').some((d) => d.type === 'ADVICE_REPORT' && d.employeeId === 'E1006'));
});

test('absence certificate upload and validation', () => {
  fresh();
  as('office1');
  const a = WZ.recordAbsence({ start: today(), end: today(), reasonCategory: 'Illness – short term', certificateRequired: true });
  assert.ok(WZ.tasks().some((t) => t.kind === 'CERTIFICATE'));
  WZ.uploadCertificate(a.id, { fileName: 'fit-note.pdf', issuer: 'Family doctor (fictional)' });
  as('casemgr1');
  const v = WZ.validateCertificate(a.id, 'VALIDATED');
  assert.equal(v.certificate.status, 'VALIDATED');
});

test('requestAdvice by a manager and employee withholding', () => {
  fresh();
  as('manager1');
  const ar = WZ.requestAdvice({ employeeId: 'E1001', questions: ['Can she return to wet work?'] });
  as('casemgr1');
  WZ.writeAdviceReport(ar.id, { workImpact: 'Fit for dry tasks' });
  as('employee1');
  WZ.releaseAdvice(ar.id, { decision: 'WITHHOLD' });
  as('manager1');
  assert.equal(WZ.list('adviceRequests').find((x) => x.id === ar.id).status, 'WITHHELD');
});

// =====================================================================================
test('S06 surveillance due -> booking -> result', () => {
  fresh();
  as('nurse1');
  const enr = WZ.list('surveillance', { due: true }).find((x) => x.employeeId === 'E1030' && x.programme === 'NOISE');
  WZ.inviteSurveillance(enr.id);
  as('engineer1');
  assert.ok(WZ.tasks().some((t) => t.kind === 'SURVEILLANCE_BOOK'));
  const slot = WZ.list('clinicSlots', { type: 'SURVEILLANCE' })[0];
  const ap = WZ.bookAppointment({ slotId: slot.id, surveillanceId: enr.id, reason: 'Audiometry' });
  assert.equal(ap.status, 'BOOKED');
  throwsCode(() => WZ.bookAppointment({ slotId: slot.id }), 'slot_taken');
  as('employee1');
  throwsCode(() => WZ.bookAppointment({ slotId: WZ.list('clinicSlots')[0].id, employeeId: 'E1030' }), 'forbidden');
  as('nurse1');
  const r = WZ.recordSurveillanceResult(enr.id, { outcome: 'NORMAL', summary: 'Normal audiogram' });
  assert.equal(r.status, 'NOT_DUE');
  assert.ok(r.dueDate > today());
  as('hr1');
  const h = WZ.list('surveillance').find((x) => x.id === enr.id);
  assert.equal(h.statusLabel, 'Up to date');
  assert.equal(h.results, undefined);
  assert.equal(h.lastOutcome, undefined);
  as('engineer1');
  assert.ok(WZ.list('documents').some((d) => d.type === 'SURVEILLANCE_RESULT'));
  const slot2 = WZ.list('clinicSlots', { type: 'OH_CONSULT' })[0];
  const ap2 = WZ.bookAppointment({ slotId: slot2.id, reason: 'Hand review' });
  assert.equal(WZ.cancelAppointment(ap2.id, 'Shift change').status, 'CANCELLED');
  assert.ok(WZ.list('clinicSlots', { type: 'OH_CONSULT' }).some((x) => x.id === slot2.id), 'slot released');
});

test('abnormal surveillance result opens a follow-up case', () => {
  fresh();
  as('nurse1');
  const enr = WZ.list('surveillance').find((x) => x.employeeId === 'E1033');
  const r = WZ.recordSurveillanceResult(enr.id, { outcome: 'ABNORMAL', summary: 'Threshold shift' });
  assert.ok(r.followUpCaseId);
});

// =====================================================================================
test('S07 travel consultation and an advisory to rostered crew with suppressed acknowledgements', () => {
  fresh();
  as('crew1');
  const tr = WZ.travelConsultation({ destination: 'PSL', departDate: today(), purpose: 'Rotation' });
  assert.ok(tr.suggested.vaccines.length);
  as('nurse1');
  const done = WZ.completeTravelConsultation(tr.id, { vaccinesGiven: ['Hepatitis A'] });
  assert.ok(done.certificateId);
  throwsCode(() => WZ.publishAdvisory({ destination: 'KSD', title: 'x', body: 'y' }), 'forbidden');
  as('physician1');
  const adv = WZ.publishAdvisory({ destination: 'KSD', title: 'Heat advisory', body: 'Hydrate', severity: 'HIGH' });
  assert.deepEqual(adv.recipients.slice().sort(), ['E1010', 'E1013', 'E1017', 'E1020', 'E1024']);
  as('crew1');
  assert.ok(WZ.tasks().some((t) => t.kind === 'ADVISORY_ACK'));
  WZ.acknowledgeAdvisory(adv.id);
  assert.ok(WZ.list('advisories', { mine: true }).find((a) => a.id === adv.id).acknowledgedAt);
  as('employee1');
  throwsCode(() => WZ.acknowledgeAdvisory(adv.id), 'forbidden');
  as('manager2');
  const rep = WZ.report('advisory', { advisoryId: adv.id });
  assert.equal(rep.suppressed, true);
  assert.equal(rep.acknowledged, null);
  assert.equal(rep.pending, undefined);
  as('physician1');
  assert.equal(WZ.report('advisory', { advisoryId: adv.id }).pending.length, 4);
});

// =====================================================================================
test('S08 communicable disease case and contact tracing from the roster', () => {
  fresh();
  as('physician1');
  const cd = WZ.openCommunicableCase({ employeeId: 'E1011', condition: 'Measles (suspected, demo)', exclusionDays: 5, incubationDays: 21 });
  const tr = WZ.contactTrace(cd.id, { windowDays: 7 });
  assert.deepEqual(tr.contacts.map((c) => c.employeeId).sort(), ['E1010', 'E1012', 'E1020', 'E1021']);
  assert.ok(tr.elapsedMs < 4 * 3600 * 1000);
  as('crew1');
  const mine = WZ.list('contacts')[0];
  assert.equal(mine.cdCaseId, undefined);
  assert.ok(!JSON.stringify(mine).includes('Sam Steward'));
  assert.ok(!JSON.stringify(WZ.notifications()).includes('Sam Steward'));
  assert.ok(!JSON.stringify(WZ.notifications()).includes('Measles'));
  const ci = WZ.contactCheckIn(mine.id, { symptoms: false });
  assert.equal(ci.status, 'MONITORING');
  as('crewsched1');
  const sam = WZ.list('crewStatus').find((x) => x.employeeId === 'E1011');
  assert.equal(sam.rosterStatus, 'UNFIT');
  assert.ok(!JSON.stringify(sam).includes('Measles'));
  as('physician1');
  WZ.reportPublicHealth(cd.id);
  WZ.clearContact(mine.id);
});

// =====================================================================================
test('S09 campaign invitation, participation, abnormal screening and follow-up', () => {
  fresh();
  as('promo1');
  const camp = WZ.list('campaigns').find((c) => c.title === 'Heart Health Check');
  const inv = WZ.campaignInvite(camp.id, { units: ['/AIRLINE/CATERING'] });
  assert.ok(inv.invitedAdded >= 5);
  as('employee1');
  const c2 = WZ.list('campaigns', { mine: true }).find((c) => c.id === camp.id);
  assert.equal(c2.invited, true);
  WZ.joinCampaign(camp.id, { eventId: camp.events[0].id });
  as('promo1');
  throwsCode(() => WZ.screeningResult({ campaignId: camp.id, employeeId: 'E1001', measures: {} }), 'forbidden');
  as('nurse1');
  throwsCode(() => WZ.screeningResult({ campaignId: camp.id, employeeId: 'E1002', measures: {} }), 'validation');
  const r = WZ.screeningResult({ campaignId: camp.id, employeeId: 'E1001', measures: { systolic: 152, diastolic: 96, cholesterol: 5.1, bmi: 26 } });
  assert.equal(r.outcome, 'ABNORMAL');
  assert.ok(r.followUpCaseId);
  as('employee1');
  assert.ok(WZ.list('documents').some((d) => d.id === r.letterId));
  assert.ok(WZ.list('screeningResults').some((x) => x.id === r.id));
  as('promo1');
  const rep = WZ.report('campaign', { campaignId: camp.id });
  const cat = rep.rows.find((x) => x.unit === '/AIRLINE/CATERING');
  const gnd = rep.rows.find((x) => x.unit === '/AIRLINE/GROUND');
  assert.equal(cat.suppressed, true);
  assert.equal(gnd.suppressed, false);
  assert.equal(gnd.participated, 5);
  assert.equal(WZ.list('screeningResults').length, 0, 'the promotion lead never sees person-level results');
  as('manager1');
  const mrep = WZ.report('campaign', { campaignId: camp.id });
  assert.deepEqual(mrep.rows.map((x) => x.unit), ['/AIRLINE/CATERING']);
  as('employee1');
  WZ.declineCampaign(WZ.list('campaigns').find((c) => c.title === 'Seasonal flu vaccination').id);
});

test('ergonomic actions are visible to the unit manager and can be updated', () => {
  fresh();
  as('ergo1');
  const a = WZ.addErgonomicAction({ unit: '/AIRLINE/CATERING', area: 'Wash-up', action: 'Anti-fatigue mats', rag: 'AMBER' });
  as('manager1');
  assert.ok(WZ.list('ergonomicActions').some((x) => x.id === a.id));
  WZ.updateErgonomicAction(a.id, { status: 'DONE' });
  as('manager3');
  assert.ok(!WZ.list('ergonomicActions').some((x) => x.id === a.id));
  throwsCode(() => WZ.updateErgonomicAction(a.id, { status: 'OPEN' }), 'forbidden');
});

// =====================================================================================
test('S10 EAP partner flow: attendance and closure only', () => {
  fresh();
  as('office1');
  const sup = WZ.selfReferSupport({ concern: 'Work–life stress', contactPreference: 'EMAIL', consent: true });
  as('psych1');
  WZ.addSupportNote(sup.id, 'Initial call, low risk.');
  as('nurse1');
  throwsCode(() => WZ.sendPartnerReferral({ supportCaseId: sup.id, partnerOrg: 'EAP_WELLBEING', consent: true }), 'forbidden');
  as('psych1');
  const ref = WZ.sendPartnerReferral({ supportCaseId: sup.id, partnerOrg: 'EAP_WELLBEING', service: 'EAP_COUNSELLING', consent: true });
  assert.equal(ref.kind, 'EAP');
  as('physio1');
  assert.ok(!WZ.list('referrals').some((r) => r.id === ref.id));
  as('eap1');
  const v = WZ.list('referrals').find((r) => r.id === ref.id);
  assert.equal(v.question, undefined);
  assert.equal(v.report, undefined);
  assert.equal(v.contactPreference, 'EMAIL');
  assert.ok(!JSON.stringify(v).includes('Work–life'));
  WZ.partnerAccept(ref.id, { appointmentAt: iso(3, '17:00') });
  throwsCode(() => WZ.partnerUploadReport(ref.id, { summary: 'notes' }), 'forbidden');
  WZ.partnerRecordAttendance(ref.id, { sessions: 3 });
  throwsCode(() => WZ.partnerClose(ref.id, { outcome: 'Free text is not allowed' }), 'validation');
  WZ.partnerClose(ref.id, { outcome: 'COMPLETED' });
  as('psych1');
  assert.equal(WZ.get('supportCases', sup.id).status, 'ACTIVE');
  WZ.closeSupportCase(sup.id, 'EAP completed');
  as('office1');
  const mine = WZ.list('supportCases')[0];
  assert.equal(mine.status, 'CLOSED');
  assert.equal(mine.concern, undefined);
});

test('peer support is pseudonymous', () => {
  fresh();
  as('crew1');
  const r = WZ.peerRequest({ alias: 'Falcon', base: 'HUB', callbackPhone: '+00 7000 000 333' });
  assert.match(r.code, /^PS-[A-Z2-9]{4}-[A-Z2-9]{4}$/);
  const stored = raw().peerRequests.find((p) => p.code === r.code);
  assert.equal(stored.employeeId, undefined);
  assert.ok(!JSON.stringify(stored).includes('E1010'));
  const aud = raw().audit.slice(-1)[0];
  assert.ok(!JSON.stringify(aud).includes(r.code));
  as('peer1');
  WZ.peerLogContact(r.code, { category: 'WORK_STRESS', outcome: 'SUPPORTED' });
  assert.ok(WZ.list('peerRequests').some((p) => p.code === r.code));
});

// =====================================================================================
test('small-cell suppression helpers', () => {
  const cells = WZ.suppressCells([{ k: 'a', count: 12 }, { k: 'b', count: 3 }, { k: 'c', count: 7 }, { k: 'd', count: 0 }], { k: 5 });
  assert.equal(cells[1].suppressed, true);
  assert.equal(cells[1].count, null);
  assert.equal(cells[2].suppressed, true, 'complementary suppression of the next smallest');
  assert.equal(cells[0].suppressed, false);
  assert.equal(cells[3].suppressed, false);
  assert.equal(WZ.suppressRate(2, 10, 5).suppressed, true);
  assert.equal(WZ.suppressRate(6, 4, 5).suppressed, true);
  assert.equal(WZ.suppressRate(6, 10, 5).pct, 60);
  assert.equal(WZ.isSmallCell(0, 5), false);
  fresh();
  as('safety1');
  const t = WZ.report('testing');
  assert.ok(t.cells.every((c) => c.suppressed || c.count >= 5));
});

test('tasks and notifications for each persona', () => {
  fresh();
  as('employee1'); assert.ok(WZ.unreadCount() > 0);
  as('nurse1'); assert.ok(WZ.tasks().some((t) => t.kind === 'TRIAGE'));
  as('mro1'); assert.ok(WZ.tasks().some((t) => t.kind === 'MRO_REVIEW'));
  as('collector1'); assert.ok(WZ.tasks().some((t) => t.kind === 'COLLECT'));
  as('manager1'); assert.ok(WZ.tasks().some((t) => t.kind === 'RTW_ACK'));
  as('psych1'); assert.ok(Array.isArray(WZ.tasks()));
  as('adjuster1'); assert.ok(WZ.tasks().some((t) => t.kind === 'CLAIM_DECISION'));
  as('crew1'); assert.ok(WZ.tasks().some((t) => t.kind === 'ADVISORY_ACK'));
  as('ramp1'); assert.ok(WZ.tasks().some((t) => t.kind === 'TEST_NOTICE'));
  const n = WZ.notifications()[0]; WZ.markNotificationRead(n.id);
  assert.ok(WZ.notifications().find((x) => x.id === n.id).read);
});

// =====================================================================================
test('scenarios.json: 10 scenarios, valid apps, users and engine actions', () => {
  fresh();
  assert.ok(S.length >= 8 && S.length <= 10);
  const s = WZ.session();
  for (const sc of S) {
    assert.ok(sc.id && sc.title && sc.personas.length && sc.steps.length, sc.id);
    for (const st of sc.steps) {
      assert.ok(['employee', 'manager', 'oh', 'partner'].includes(st.app), sc.id + ' app ' + st.app);
      const u = s.users.find((x) => x.username === st.user);
      assert.ok(u, sc.id + ' user ' + st.user);
      assert.ok(u.apps.includes(st.app), `${sc.id}: ${st.user} is not a user of ${st.app}`);
      assert.ok(st.action === 'view' || typeof WZ[st.action] === 'function', sc.id + ' action ' + st.action);
      if (st.action !== 'view') assert.ok(WZ.can(st.user, '__none__') === false && (WZ.permissions(st.user).length > 0), st.user);
      assert.ok(st.expected && st.data, sc.id + ' step fields');
    }
  }
  WZ.startScenario(S[0].id);
  assert.equal(WZ.scenarioState().stepIndex, 0);
  WZ.stepScenario(1);
  assert.equal(WZ.scenarioState().stepIndex, 1);
  WZ.stopScenario();
  assert.equal(WZ.scenarioState().active, false);
});

test('scenario steps are performed by users who hold the required permission', () => {
  fresh();
  const perms = WZ.actions();
  for (const sc of S) for (const st of sc.steps) {
    if (st.action === 'view') continue;
    const p = perms[st.action];
    if (!p) continue;
    const list = Array.isArray(p) ? p : [p];
    assert.ok(list.some((x) => WZ.can(st.user, x)), `${sc.id}: ${st.user} lacks ${list.join('|')} for ${st.action}`);
  }
});
