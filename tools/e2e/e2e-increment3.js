// End-to-end for increment 3 (E06 absence & sickness, E07 medical surveillance, E14 scheduling & clinic operations)
// against the running local stack with real Keycloak logins through the BFF: absence feed + triggers + early
// intervention, sick certificates, long-term absence pathway, management advice with employee release, absence
// analytics; programmes, due lists + invitations, IH import, device calibration block, return-after-illness guard,
// candidate portal + HR status, crew certificates, immunisation fast entry, compliance + audit pack; diary booking with
// conflict detection, self-booking, check-in + queue + call-next, equipment calibration, cold-chain excursion, campaign,
// capacity. Screenshots for every new screen.
// Usage: npm i -D playwright && node tools/e2e/e2e-increment3.js  (stack running – see docs/setup/01-Local-MacBook-Pro.md)
const { chromium } = require('playwright');

const EMP = process.env.WZ_E2E_EMPLOYEE_URL || 'http://localhost:5173';
const MGR = process.env.WZ_E2E_WORKSPACE_URL || 'http://localhost:5174';
const OH = process.env.WZ_E2E_OH_URL || 'http://localhost:5175';
const IDP_URL = process.env.WZ_E2E_IDP_URL || 'http://localhost:8180';
const CORE = process.env.WZ_E2E_CORE_URL || 'http://localhost:8080';
// Local-only client secret from deploy/local/keycloak/wellnessz-realm.json; never a real credential.
const INTEGRATION_SECRET = process.env.WZ_E2E_INTEGRATION_SECRET || 'change-me-local-only';
const OUT = process.env.WZ_E2E_OUT || __dirname + '/shots';
require('fs').mkdirSync(OUT, { recursive: true });
const NEWPW = process.env.WZ_E2E_NEW_PASSWORD || 'Wz-Local-Demo-2026!';
const today = new Date().toISOString().slice(0, 10);
const plusDays = (n) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);
const log = [];
const check = (name, ok, detail = '') => log.push(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' – ' + detail : ''}`);
const brief = (r) => `HTTP ${r.status} ${typeof r.json === 'string' ? r.json.slice(0, 160) : JSON.stringify(r.json).slice(0, 160)}`;
// 1×1 PNG – stands in for a phone photo of a sick certificate
const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

// demo data (db/demo/R__demo_e06/e07/e14_*.sql)
const E1004_LTA_CASE = '6e060000-0000-4000-a000-000000000002';
const PRE_EMPLOYMENT_C9001 = '7e07de00-0000-4000-8000-000000000301';
const ENROLMENT_E1007 = '7e07de00-0000-4000-8000-000000000112';
const CAMPAIGN = 'e14c0000-0000-4000-8000-000000000001';
const AUD_OVERDUE = 'e14e0000-0000-4000-8000-000000000002';
const AUDIOGRAM = { left: { 500: 10, 1000: 10, 2000: 15, 3000: 15, 4000: 20, 6000: 20, 8000: 15 },
  right: { 500: 10, 1000: 10, 2000: 15, 3000: 15, 4000: 20, 6000: 20, 8000: 15 } };

async function login(page, user) {
  const app = new URL(page.url()).origin;
  await page.getByRole('button', { name: /Sign in with your company account/ }).click();
  await page.waitForURL(new RegExp(new URL(IDP_URL).host));
  for (const pw of ['ChangeMe!123', NEWPW]) {
    await page.fill('#username', user);
    await page.fill('#password', pw);
    await page.click('#kc-login');
    await page.waitForLoadState('networkidle');
    if (await page.locator('#password-new').count()) {
      await page.fill('#password-new', NEWPW);
      await page.fill('#password-confirm', NEWPW);
      await page.click('[type=submit]');
    }
    if (page.url().startsWith(app)) break;
  }
  await page.waitForURL((u) => u.href.startsWith(app), { timeout: 15000 });
}

async function api(page, method, path, body) {
  return page.evaluate(async ([method, path, body]) => {
    const xsrf = document.cookie.split(';').map((c) => c.trim().split('=')).find(([k]) => k === 'XSRF-TOKEN');
    const headers = { Accept: 'application/json' };
    if (xsrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(xsrf[1]);
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const r = await fetch('/bff/api' + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await r.text();
    let json; try { json = JSON.parse(text); } catch { json = text; }
    return { status: r.status, json };
  }, [method, path, body]);
}

/** Multipart upload through the BFF (fields + one file). */
async function upload(page, path, fields, base64, fileName, type) {
  return page.evaluate(async ([path, fields, base64, fileName, type]) => {
    const xsrf = document.cookie.split(';').map((c) => c.trim().split('=')).find(([k]) => k === 'XSRF-TOKEN');
    const fd = new FormData();
    for (const [k, v] of Object.entries(fields)) fd.append(k, v);
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
    fd.append('file', new Blob([bytes], { type }), fileName);
    const headers = { Accept: 'application/json' };
    if (xsrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(xsrf[1]);
    const r = await fetch('/bff/api' + path, { method: 'POST', headers, body: fd });
    const text = await r.text();
    let json; try { json = JSON.parse(text); } catch { json = text; }
    return { status: r.status, json };
  }, [path, fields, base64, fileName, type]);
}

async function session(browser, url, user, viewport = { width: 1440, height: 900 }) {
  const page = await (await browser.newContext({ viewport })).newPage();
  await page.goto(url + '/');
  await login(page, user);
  return page;
}

async function shot(page, name, route, wait = 1800) {
  if (route) {
    await page.goto(route);
    await page.reload(); // a hash-only navigation may keep a mounted screen; reload so it shows fresh data
  }
  await page.waitForTimeout(wait);
  await page.screenshot({ path: `${OUT}/${name}.png` });
}

async function integrationToken() {
  const r = await fetch(`${IDP_URL}/realms/wellnessz/protocol/openid-connect/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'client_credentials', client_id: 'wellnessz-integration', client_secret: INTEGRATION_SECRET }),
  });
  if (!r.ok) throw new Error('integration token ' + r.status);
  return (await r.json()).access_token;
}

async function integration(token, path, body) {
  const r = await fetch(`${CORE}/api/v1${path}`, { method: 'POST',
    headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json', 'Idempotency-Key': 'e2e3-' + Math.random().toString(36).slice(2) },
    body: JSON.stringify(body) });
  const text = await r.text();
  let json; try { json = JSON.parse(text); } catch { json = text; }
  return { status: r.status, json };
}

const arr = (j) => (Array.isArray(j) ? j : (j && (j.rows || j.items || j.content)) || []);
const hasKeyDeep = (o, key) => JSON.stringify(o).includes(`"${key}"`);

(async () => {
  const token = await integrationToken();
  const run = Date.now().toString(36).toUpperCase();
  const browser = await chromium.launch();

  // ======================================================================= E06 absence & sickness
  // ---------- absence feed from HRIS / T&A with reconciliation ----------
  const feed = await integration(token, '/integration/absence/episodes', { sourceSystem: 'HRIS', episodes: [
    { sourceRef: `HR-${run}-1`, employeeRef: 'E1005', startDate: plusDays(-12), absenceType: 'SICKNESS', reasonCategory: 'MSK', certified: true },
    { sourceRef: `HR-${run}-2`, employeeRef: 'B-1009', startDate: plusDays(-2), endDate: plusDays(-1), absenceType: 'SICKNESS', reasonCategory: 'GASTRO', certified: false },
    { sourceRef: `HR-${run}-3`, employeeRef: 'X-UNKNOWN', startDate: plusDays(-3), absenceType: 'SICKNESS', certified: false }] });
  check('absence feed ingested and reconciled to the employee master (id and badge), unknown ref listed for HR',
    feed.status === 200 && feed.json.matched === 2 && feed.json.unmatched === 1, `received=${feed.json.received} matched=${feed.json.matched} unmatched=${feed.json.unmatched} triggered=${feed.json.triggered}`);

  const nurse = await session(browser, OH, 'nurse1');
  const queue = await api(nurse, 'GET', '/absence/queue');
  const items = arr(queue.json);
  const e1005 = items.find((q) => q.employeeId === 'E1005' && q.status === 'NEW');
  check('duration rule (> 10 days) raises an early-intervention item with the absence history', !!e1005,
    e1005 ? `${e1005.ruleCode} → ${e1005.action}, SLA ${String(e1005.slaDueAt).slice(0, 10)}, history ${(e1005.history || []).length} episode(s)` : brief(queue));
  if (e1005) {
    const acc = await api(nurse, 'POST', `/absence/queue/${e1005.id}/accept`);
    const con = await api(nurse, 'POST', `/absence/queue/${e1005.id}/contact`, { channel: 'PHONE', outcome: 'REACHED', note: 'Agreed weekly calls' });
    check('nurse accepts the item (case opened, manager/HR told "OH engaged") and records first contact', acc.status === 200 && con.status === 200
      && !!con.json.firstContactAt, `case=${acc.json.caseRef} status=${con.json.status} firstContact=${con.json.firstContactAt}`);
  }
  await shot(nurse, 'inc3-oh-01-absence-queue', `${OH}/#/absence/queue`, 2500);

  const admin = await session(browser, OH, 'admin1');
  const rules = await api(admin, 'GET', '/absence/rules');
  const freq = arr(rules.json).find((r) => r.code === 'FREQ_3_IN_90');
  const test = freq ? await api(admin, 'POST', '/absence/rules/test', { ruleId: freq.id }) : { status: 0, json: {} };
  check('trigger rule test mode shows who would be flagged without creating cases', test.status === 200 && typeof test.json.count === 'number',
    `${arr(rules.json).length} rules; FREQ_3_IN_90 would match ${test.json.count}`);
  await shot(admin, 'inc3-oh-02-absence-rules', `${OH}/#/absence/rules`);
  await shot(admin, 'inc3-oh-03-absence-feed-quality', `${OH}/#/absence/feed`);

  // ---------- sick certificate from the employee's phone ----------
  const emp = await session(browser, EMP, 'employee1', { width: 420, height: 900 });
  const up = await upload(emp, '/me/absence/certificates', { fromDate: plusDays(-3), toDate: plusDays(4) }, PNG, 'sick-note.png', 'image/png');
  check('employee uploads a sick certificate photo from the app', up.status === 201, up.status === 201 ? `status=${up.json.status} id=${up.json.id}` : brief(up));
  const certs = await api(nurse, 'GET', '/absence/certificates?employeeId=E1001');
  const mine = arr(certs.json).find((c) => c.id === up.json.id);
  const val = mine ? await api(nurse, 'POST', `/absence/certificates/${mine.id}/validate`, { checklist: { issuerIdentified: true,
    registrationValid: true, datesConsistent: true, signaturePresent: true, coversAbsence: true }, outcome: 'VALID', note: 'GP practice confirmed' }) : { status: 0, json: {} };
  const hr = await session(browser, MGR, 'hr1');
  const hrCerts = await api(hr, 'GET', '/workspace/absence/certificates?employeeId=E1001');
  const hrRow = arr(hrCerts.json).find((c) => c.id === up.json.id);
  check('OH validates the certificate; HR sees validity only (no issuer, OCR or file)', val.status === 200 && hrRow && hrRow.validity === 'VALID'
    && !hasKeyDeep(hrCerts.json, 'extracted') && !hasKeyDeep(hrCerts.json, 'documentId') && !hasKeyDeep(hrCerts.json, 'issuerName'),
    `OH=${val.json.status} HR=${hrRow && hrRow.validity}`);
  await shot(nurse, 'inc3-oh-04-absence-certificates', `${OH}/#/absence/certificates`);

  // ---------- long-term absence pathway + HR stage ----------
  const lta = await api(nurse, 'GET', `/absence/lta/${E1004_LTA_CASE}`);
  const ms = lta.json.milestones || [];
  check('long-term absence pathway has the week 2/4/8/12/26 milestones with overdue flags', lta.status === 200 && ms.length >= 5
    && ms.some((m) => m.status === 'OVERDUE'), `weeks=${[...new Set(ms.map((m) => m.week))].join(',')} overdue=${ms.filter((m) => m.status === 'OVERDUE').length}`);
  await shot(nurse, 'inc3-oh-05-lta-pathway', `${OH}/#/absence/lta/${E1004_LTA_CASE}`, 2500);
  const badStage = await api(hr, 'POST', `/workspace/absence/lta/${E1004_LTA_CASE}/hr-stage`, { stage: 'FORMAL_REVIEW_1', note: 'Lumbar disc prolapse confirmed on MRI' });
  const stage = await api(hr, 'POST', `/workspace/absence/lta/${E1004_LTA_CASE}/hr-stage`, { stage: 'FORMAL_REVIEW_1', note: 'First formal review meeting booked' });
  check('HR moves the LTA to a formal review; clinical wording in HR notes is refused', badStage.status === 422 && stage.status === 200,
    `clinical note → ${badStage.status} ${badStage.json.code}; clean → ${stage.status} ${stage.json.hrStage || ''}`);
  await shot(hr, 'inc3-mgr-01-hr-absence', `${MGR}/#/absence`, 2500);

  // ---------- management advice request → OH report → employee release ----------
  const mgr = await session(browser, MGR, 'manager1');
  const req = await api(mgr, 'POST', '/workspace/advice-requests', { employeeId: 'E1001', questionCodes: ['FIT_FOR_ROLE', 'ADJUSTMENTS', 'EXPECTED_RTW'],
    context: 'Frequent short absences on the early shift; how can we support her?' });
  check('manager requests OH advice with structured questions', req.status === 201, `${req.json.ref} SLA ${String(req.json.slaDueAt).slice(0, 10)}`);
  const doc = await session(browser, OH, 'physician1');
  await api(doc, 'POST', `/absence/advice-requests/${req.json.id}/accept`);
  const bad = await api(doc, 'PUT', `/absence/advice-requests/${req.json.id}/report`, { fitness: 'FIT_WITH_ADJUSTMENTS', prognosis: 'RTW_WITHIN_2_WEEKS',
    adjustments: 'Diagnosed with irritable bowel syndrome (K58.9)', answers: { FIT_FOR_ROLE: 'Yes' } });
  const draft = await api(doc, 'PUT', `/absence/advice-requests/${req.json.id}/report`, { fitness: 'FIT_WITH_ADJUSTMENTS', prognosis: 'RTW_WITHIN_2_WEEKS',
    adjustments: 'Access to rest facilities; later shift start for four weeks', restrictions: 'None', reviewDate: plusDays(28),
    answers: { FIT_FOR_ROLE: 'Yes, with the adjustments below', ADJUSTMENTS: 'Later shift start and access to rest facilities', EXPECTED_RTW: 'She is at work' } });
  const sign = await api(doc, 'POST', `/absence/advice-requests/${req.json.id}/report/sign`);
  check('advice report refuses a diagnosis, then is signed for employee review', bad.status === 422 && draft.status === 200 && sign.status === 200
    && sign.json.status === 'AWAITING_EMPLOYEE', `diagnosis → ${bad.status} field=${bad.json.field}; signed → ${sign.json.status}`);
  await shot(doc, 'inc3-oh-06-advice-report', `${OH}/#/absence/advice`, 2500);
  const reports = await api(emp, 'GET', '/me/advice-reports');
  const rep = arr(reports.json).find((r) => r.status === 'AWAITING_EMPLOYEE' && r.requestRef === req.json.ref);
  await shot(emp, 'inc3-emp-01-advice-review', `${EMP}/#/advice`, 2500);
  const dec = rep ? await api(emp, 'POST', `/me/advice-reports/${rep.id}/decision`, { decision: 'CONSENT', comment: 'Happy for this to be shared' }) : { status: 0, json: {} };
  const mgrView = await api(mgr, 'GET', '/workspace/advice-requests');
  const released = arr(mgrView.json).find((r) => r.id === req.json.id);
  check('employee consents; manager receives fitness/adjustments/prognosis only, with the signed report', dec.status === 200 && released
    && released.status === 'RELEASED' && released.report && !!released.report.documentId && !hasKeyDeep(released.report, 'diagnosis'),
    `release=${dec.json.status} manager sees fitness=${released && released.report && released.report.fitness}`);
  await shot(mgr, 'inc3-mgr-02-advice-requests', `${MGR}/#/advice-requests`, 2500);
  const mgrEp = await api(mgr, 'GET', '/absence/employees/E1001/episodes');
  check('manager sees absence dates and status but never the reason category', mgrEp.status === 200 && arr(mgrEp.json).length > 0
    && !hasKeyDeep(mgrEp.json, 'reasonCategory'), `${arr(mgrEp.json).length} episodes`);
  await shot(mgr, 'inc3-mgr-03-team-absence', `${MGR}/#/absence`, 2500);
  await shot(emp, 'inc3-emp-02-my-absence', `${EMP}/#/absence`, 2500);

  // ---------- absence analytics ----------
  const cmo = await session(browser, OH, 'cmo1');
  const units = await api(cmo, 'GET', `/absence/analytics/units?groupBy=DEPARTMENT&from=${plusDays(-365)}&to=${today}`);
  const kpis = await api(cmo, 'GET', `/absence/analytics/kpis?from=${plusDays(-365)}&to=${today}`);
  check('absence analytics by unit with small-group suppression and responsiveness KPIs', units.status === 200 && kpis.status === 200
    && arr(units.json).some((u) => u.suppressed), `units=${arr(units.json).length} suppressed=${arr(units.json).filter((u) => u.suppressed).length} `
    + `LTA with case=${kpis.json.ltaWithCasePct}% median trigger→contact=${kpis.json.medianDaysTriggerToContact} d`);
  await shot(cmo, 'inc3-oh-07-absence-analytics', `${OH}/#/absence/analytics`, 2500);
  await shot(nurse, 'inc3-oh-08-chart-absence', `${OH}/#/employees/E1001/chart/absence`, 2500);

  // ======================================================================= E07 surveillance
  const sim = await api(admin, 'POST', '/surveillance/programmes/HEARING_CONSERVATION/simulate', {});
  check('programme simulation shows the eligible population by unit', sim.status === 200 && sim.json.eligibleHeadcount > 0,
    `eligible=${sim.json.eligibleHeadcount} units=${(sim.json.byUnit || []).length}`);
  await shot(admin, 'inc3-oh-09-programmes', `${OH}/#/surveillance/programmes`);
  await shot(admin, 'inc3-oh-10-programme-builder', `${OH}/#/surveillance/programmes/HEARING_CONSERVATION`, 2500);

  const sched = await session(browser, OH, 'scheduler1');
  const due = await api(sched, 'GET', '/surveillance/due');
  const inv = await api(sched, 'POST', '/surveillance/invitations', { enrolmentIds: [ENROLMENT_E1007], book: true, siteCode: 'LHR-AIRSIDE' });
  check('scheduler sees the due list and invites + books in bulk through the clinic diary', due.status === 200 && inv.status === 200 && inv.json.invited === 1,
    `due rows=${arr(due.json).length} invited=${inv.json.invited} booked=${inv.json.booked}`);
  await shot(sched, 'inc3-oh-11-due-list', `${OH}/#/surveillance/due`, 2500);

  const hse = await session(browser, OH, 'hse1');
  const ih = await api(hse, 'POST', '/surveillance/ih-measurements/import', [{ sourceRef: `IH-${run}`, segCode: 'LHR-CARGO-NOISE', hazardCode: 'NOISE',
    measurementType: 'NOISE_DOSIMETRY', value: 86.5, unit: 'dB(A)', measuredOn: today }]);
  check('noise dosimetry above the action level enrols the exposure group into hearing conservation', ih.status === 200 && ih.json.segsAboveActionLevel >= 1,
    `imported=${ih.json.imported} SEGs above=${ih.json.segsAboveActionLevel} enrolled=${ih.json.employeesEnrolled}`);
  await shot(hse, 'inc3-oh-12-exposure', `${OH}/#/surveillance/exposure`, 2500);

  // ---------- E14 equipment register blocks out-of-calibration devices in E03 result capture ----------
  const blocked = await api(nurse, 'POST', '/employees/E1008/results/device', { category: 'AUDIOMETRY', deviceId: 'AUD-LHR-02', deviceModel: 'Clinic audiometer',
    calibrationId: 'CAL-2025-0901', calibrationDate: plusDays(-400), measurements: AUDIOGRAM });
  const biomed = await session(browser, OH, 'biomed1');
  const cal = await api(biomed, 'POST', `/scheduling/equipment/${AUD_OVERDUE}/calibrations`, { performedOn: today, vendor: 'Acme Audiology Services',
    certificateNumber: `CAL-${run}`, result: 'PASS' });
  const okCapture = await api(nurse, 'POST', '/employees/E1008/results/device', { category: 'AUDIOMETRY', deviceId: 'AUD-LHR-02', deviceModel: 'Clinic audiometer',
    calibrationId: `CAL-${run}`, calibrationDate: today, measurements: AUDIOGRAM });
  check('out-of-calibration audiometer is blocked; accepted after the biomedical calibration', blocked.status === 422 && blocked.json.code === 'device_out_of_calibration'
    && cal.status === 201 && (okCapture.status === 201 || okCapture.status === 200), `before=${blocked.status} ${blocked.json.code} calibration=${cal.status} after=${okCapture.status}`);
  await shot(biomed, 'inc3-oh-13-equipment', `${OH}/#/scheduling/equipment`, 2500);

  const fu = await api(doc, 'GET', '/surveillance/follow-ups?status=OPEN');
  check('abnormal surveillance results have a follow-up case and recheck task', fu.status === 200 && arr(fu.json).some((f) => f.caseId),
    arr(fu.json).map((f) => `${f.employeeId} ${f.ruleCode} ${f.caseRef}`).slice(0, 3).join('; '));
  await shot(doc, 'inc3-oh-14-follow-ups', `${OH}/#/surveillance/follow-ups`);

  // ---------- return-after-illness guard on the E05 fitness decision ----------
  const fit = await api(doc, 'POST', `/cases/${E1004_LTA_CASE}/fitness-decisions`, { standardCode: 'AIRSIDE_DRIVING', outcome: 'FIT', validFrom: today,
    rationale: 'Recovered' });
  check('FIT decision for a safety-critical role is blocked until the return-after-illness medical is done', fit.status === 422 && fit.json.code === 'clearance_required',
    `${fit.status} ${fit.json.code} – ${fit.json.detail || ''}`);
  await shot(doc, 'inc3-oh-15-return-after-illness', `${OH}/#/surveillance/rai`);

  // ---------- candidate portal → OH outcome → HR status ----------
  const cand = await session(browser, EMP, 'candidate1', { width: 420, height: 900 });
  const qs = await api(cand, 'GET', '/me/pre-employment/questionnaire');
  const answers = Object.fromEntries(arr(qs.json).map((q) => [q.code, q.type === 'YES_NO' ? (q.code === 'DECLARATION' ? 'YES' : 'NO')
    : q.type === 'DATE' ? plusDays(-400) : 'None']));
  const qa = await api(cand, 'PUT', '/me/pre-employment/questionnaire', { answers });
  const consent = await api(cand, 'POST', '/me/pre-employment/consent', { consentGiven: true, purposes: ['PRE_EMPLOYMENT_ASSESSMENT', 'HR_STATUS_FEED'] });
  check('candidate completes the health questionnaire and consent online', qa.status === 200 && consent.status === 200,
    `${arr(qs.json).length} questions; questionnaire=${qa.status} consent=${consent.status}`);
  await shot(cand, 'inc3-emp-03-candidate-portal', `${EMP}/#/pre-employment`, 2500);
  const outcome = await api(doc, 'POST', `/surveillance/pre-employment/${PRE_EMPLOYMENT_C9001}/outcome`, { outcome: 'FIT' });
  const hrPre = await api(hr, 'GET', '/workspace/pre-employment');
  const hrRowPre = arr(hrPre.json).find((p) => p.id === PRE_EMPLOYMENT_C9001);
  check('OH records the outcome; HR onboarding sees FIT/UNFIT/PENDING only', outcome.status === 200 && hrRowPre && hrRowPre.hrClearance === 'FIT'
    && !hasKeyDeep(hrPre.json, 'questionnaire'), `HR clearance=${hrRowPre && hrRowPre.hrClearance}`);
  await shot(doc, 'inc3-oh-16-pre-employment', `${OH}/#/surveillance/pre-employment`, 2500);
  await shot(hr, 'inc3-mgr-04-pre-employment', `${MGR}/#/pre-employment`, 2500);

  // ---------- crew certificates ----------
  const lic = await session(browser, OH, 'crewlicensing1');
  const crew = await api(lic, 'GET', '/surveillance/crew/certificates');
  const missing = await api(lic, 'GET', '/surveillance/crew/without-valid-medical');
  check('crew licensing sees certificate validity and the crew without a valid medical (status only)', crew.status === 200 && missing.status === 200
    && arr(crew.json).some((c) => c.status === 'EXPIRING') && arr(missing.json).length > 0 && !hasKeyDeep(crew.json, 'answers'),
    `expiring=${arr(crew.json).filter((c) => c.status === 'EXPIRING').length} without valid medical=${arr(missing.json).map((m) => m.employeeId).join(',')}`);
  await shot(lic, 'inc3-oh-17-crew-certificates', `${OH}/#/surveillance/crew`, 2500);

  // ---------- immunisation fast entry with a GS1 lot barcode ----------
  const vac = await api(nurse, 'POST', '/surveillance/immunisation/administrations', { employeeId: 'E1001', vaccineCode: 'HEP_A', doseNumber: 2,
    lotBarcode: `(17)${plusDays(300).slice(2).replace(/-/g, '')}(10)HA${run.slice(-4)}`, site: 'LEFT_DELTOID', route: 'IM', consentConfirmed: true });
  check('vaccine recorded in seconds from a scanned GS1 lot barcode (lot, expiry, site, consent)', vac.status === 201,
    `lot=${vac.json.lotNumber} expiry=${vac.json.lotExpiry}`);
  await shot(nurse, 'inc3-oh-18-immunisation', `${OH}/#/surveillance/immunisation`, 2500);

  // ---------- compliance dashboard + audit pack ----------
  const comp = await api(cmo, 'GET', '/surveillance/compliance?groupBy=PROGRAMME');
  const pack = await api(cmo, 'POST', '/surveillance/audit-packs', { template: 'FOOD_SAFETY', programmeCode: 'FOOD_HANDLER', from: plusDays(-365), to: today, anonymise: true });
  const pdf = pack.json.id ? await cmo.evaluate(async (id) => (await fetch(`/bff/api/surveillance/audit-packs/${id}/content?format=pdf`)).status, pack.json.id) : 0;
  check('compliance dashboard by programme and an anonymised food-safety audit pack (PDF + CSV)', comp.status === 200 && (pack.status === 201 || pack.status === 200) && pdf === 200,
    `programmes=${arr(comp.json).length} overall=${comp.json.overallCompliancePct ?? '–'}% pack=${pack.json.id ? 'generated' : brief(pack)}`);
  await shot(cmo, 'inc3-oh-19-compliance', `${OH}/#/surveillance/compliance`, 2500);
  await shot(nurse, 'inc3-oh-20-chart-surveillance', `${OH}/#/employees/E1001/chart/surveillance`, 2500);
  const team = await api(mgr, 'GET', '/workspace/surveillance/compliance');
  check('manager sees team surveillance status only', team.status === 200 && !hasKeyDeep(team.json, 'resultIds'), `${arr(team.json).length} rows`);
  await shot(mgr, 'inc3-mgr-05-compliance', `${MGR}/#/compliance`, 2500);
  await shot(emp, 'inc3-emp-04-my-surveillance', `${EMP}/#/surveillance`, 2500);

  // ======================================================================= E14 scheduling & clinic operations
  const startsAt = new Date(Math.ceil((Date.now() + 15 * 60000) / 300000) * 300000).toISOString();
  const book = await api(sched, 'POST', '/scheduling/appointments', { employeeId: 'E1005', appointmentType: 'GENERAL_OH', siteCode: 'HQ-MC', startsAt,
    clinicianUsername: 'physician1', roomCode: 'HQ-C2' });
  const clash = await api(sched, 'POST', '/scheduling/appointments', { employeeId: 'E1006', appointmentType: 'GENERAL_OH', siteCode: 'HQ-MC', startsAt,
    clinicianUsername: 'physician1', roomCode: 'HQ-C2' });
  check('scheduler books a clinician + room; a double booking is refused with a conflict', book.status === 201 && clash.status === 409,
    `booked ${String(book.json.startsAt).slice(11, 16)} → clash ${clash.status} ${clash.json.code}`);
  await shot(sched, 'inc3-oh-21-diary', `${OH}/#/scheduling/diary`, 2500);

  const slots = await api(emp, 'GET', `/me/appointments/slots?type=VACCINATION&siteCode=HQ-MC&from=${plusDays(2)}&to=${plusDays(9)}`);
  const slot = arr(slots.json)[0];
  // one open booking per type: an existing vaccination appointment is moved, otherwise a new one is booked
  const mineAppts = await api(emp, 'GET', '/me/appointments');
  const open = arr(mineAppts.json).find((a) => a.appointmentType === 'VACCINATION' && ['BOOKED', 'CHECKED_IN'].includes(a.status));
  const self = !slot ? { status: 0, json: {} } : open
    ? await api(emp, 'POST', `/me/appointments/${open.id}/reschedule`, { startsAt: slot.startsAt })
    : await api(emp, 'POST', '/me/appointments', { appointmentType: 'VACCINATION', siteCode: 'HQ-MC', startsAt: slot.startsAt });
  check('employee self-books (or reschedules) a vaccination slot in the app', self.status === 201 || self.status === 200,
    `${arr(slots.json).length} free slots; ${open ? 'rescheduled' : 'booked'} ${self.json.startsAt || brief(self)}`);
  await shot(emp, 'inc3-emp-05-appointments', `${EMP}/#/appointments`, 2500);

  const recep = await session(browser, OH, 'reception1');
  const checkin = await api(recep, 'POST', '/scheduling/check-in', { siteCode: 'HQ-MC', badgeId: 'B-1005' });
  const board = await api(recep, 'GET', '/scheduling/queue?siteCode=HQ-MC');
  check('reception checks in by badge; the queue board shows tickets, never names', checkin.status === 200 && board.status === 200
    && !hasKeyDeep(board.json, 'displayName') && !hasKeyDeep(board.json, 'employeeId'), `ticket=${checkin.json.ticket} waiting=${(board.json.entries || []).length}`);
  await shot(recep, 'inc3-oh-22-reception-kiosk', `${OH}/#/scheduling/reception`);
  await shot(recep, 'inc3-oh-23-queue-board', `${OH}/#/scheduling/queue`, 2500);
  const next = await api(doc, 'POST', '/scheduling/queue/call-next', { siteCode: 'HQ-MC' });
  check('physician calls the next patient and is handed off to the chart', next.status === 200 && !!next.json.encounterLink,
    `${next.json.ticket} ${next.json.displayName} → ${next.json.encounterLink}`);

  const iot = await integration(token, '/integration/cold-chain/readings', [{ deviceId: 'FRIDGE-LHR-01', temperatureC: 10.4, recordedAt: new Date().toISOString() }]);
  const exc = await api(nurse, 'GET', '/scheduling/excursions?status=OPEN');
  const lhr = arr(exc.json).find((x) => x.assetTag === 'FRIDGE-LHR-01');
  const ack = lhr ? await api(nurse, 'POST', `/scheduling/excursions/${lhr.id}/acknowledge`, { action: 'Vaccines quarantined; fridge door seal replaced' }) : { status: 0, json: {} };
  check('vaccine-fridge IoT reading outside 2–8 °C opens an excursion that the nurse acknowledges', iot.status === 200 && iot.json.excursionsOpened >= 1 && ack.status === 200,
    `opened=${iot.json.excursionsOpened} ack=${ack.json.status}`);

  const camp = await api(sched, 'POST', `/scheduling/campaigns/${CAMPAIGN}/book`, { employeeIds: ['E1004', 'E1010'] });
  const perf = await api(sched, 'GET', `/scheduling/campaigns/${CAMPAIGN}`);
  check('campaign day books a cohort in one call with performance tracking', camp.status === 200 && perf.status === 200,
    `booked=${camp.json.booked} slots=${(perf.json.performance || {}).slots} attended=${(perf.json.performance || {}).attended}`);
  await shot(sched, 'inc3-oh-24-campaigns', `${OH}/#/scheduling/campaigns`);
  await shot(sched, 'inc3-oh-25-campaign-day', `${OH}/#/scheduling/campaigns/${CAMPAIGN}`, 2500);

  const cap = await api(cmo, 'GET', `/scheduling/capacity?from=${today}&to=${plusDays(90)}`);
  check('capacity forecast compares surveillance / absence demand with clinic capacity per site', cap.status === 200 && arr(cap.json).length > 0
    && arr(cap.json).some((r) => r.forecastDemand > 0), arr(cap.json).filter((r) => r.forecastDemand > 0).slice(0, 3).map((r) => `${r.siteCode} ${r.month}: ${r.forecastDemand}/${r.capacityAppointments}`).join('; '));
  await shot(cmo, 'inc3-oh-26-capacity', `${OH}/#/scheduling/capacity`, 2500);
  await shot(admin, 'inc3-oh-27-catalogue', `${OH}/#/scheduling/catalogue`, 2500);

  await browser.close();
  console.log(log.join('\n'));
  if (log.some((l) => l.startsWith('FAIL'))) process.exit(1);
})().catch((e) => { console.log(log.join('\n')); console.error('E2E FAILED', e); process.exit(1); });
