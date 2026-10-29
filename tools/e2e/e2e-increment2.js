// End-to-end for increment 2 (E03 OH record & encounters, E05 fitness / restrictions / RTW) against the running local
// stack with real Keycloak logins through the BFF: chart, encounter, CDS, device audiometry + worklist, fitness decision,
// certificate, modified duty, RTW plan, job offer, crew assessment + roster feed, break-glass + DPO review.
// Usage: npm i -D playwright && node tools/e2e/e2e-increment2.js  (stack running – see docs/setup/01-Local-MacBook-Pro.md)
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

async function session(browser, url, user, viewport = { width: 1440, height: 900 }) {
  const page = await (await browser.newContext({ viewport })).newPage();
  await page.goto(url + '/');
  await login(page, user);
  return page;
}

async function shot(page, name, route, wait = 1800) {
  if (route) await page.goto(route);
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

(async () => {
  // ---------- seed: pilot incident (E1002) so the AME has a crew case ----------
  const token = await integrationToken();
  const run = Date.now().toString(36).toUpperCase();
  await fetch(`${CORE}/api/v1/intake/ehs/incidents`, {
    method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json', 'Idempotency-Key': 'e2e2-' + run },
    body: JSON.stringify({ incidents: [{ externalRef: `EHS-${run}-P`, occurredAt: new Date(Date.now() - 3600_000).toISOString(),
      incidentClass: 'INJURY_MEDICAL_TREATMENT', severity: 'MEDIUM', bodyPart: 'HAND', location: 'LHR crew room', unitCode: 'FLT',
      employeeId: 'E1002', orgUnitCode: 'FLIGHTOPS', description: 'Hand trapped in flight-deck door', reporterEmail: 'hse.officer@wellnessz.local' }] }),
  });
  const browser = await chromium.launch();

  // ---------- employee: self-referral (catering food handler) ----------
  const emp = await session(browser, EMP, 'employee1', { width: 420, height: 900 });
  const ref = await api(emp, 'POST', '/intake/referrals/self', { templateCode: 'CATERING', category: 'INJURY',
    description: 'Strained my wrist lifting trays onto the high-loader', bodyParts: ['WRIST_L'], symptoms: ['PAIN'],
    answers: { atWork: 'YES', handledFood: 'YES' }, preferredContact: 'PHONE', language: 'en', consent: { assessment: true, purposes: [] } });
  const caseId = ref.json.caseId;
  check('employee self-referral creates the case', ref.status === 201 && !!caseId, `HTTP ${ref.status} ${ref.json.caseRef || ''}`);

  // ---------- nurse: chart, encounter, CDS, device audiometry ----------
  const nurse = await session(browser, OH, 'nurse1');
  const chart = await api(nurse, 'GET', '/employees/E1004/chart');
  check('chart summary for the ramp agent (allergy alert, surveillance, counts)', chart.status === 200 && JSON.stringify(chart.json.alerts || []).includes('ALLERGY'),
    `alerts=${(chart.json.alerts || []).map((a) => a.kind).join(',')}`);
  await shot(nurse, 'inc2-oh-01-chart-summary', `${OH}/#/employees/E1004/chart`, 2500);
  await shot(nurse, 'inc2-oh-02-chart-jobs-odh', `${OH}/#/employees/E1004/chart/jobs`);
  await shot(nurse, 'inc2-oh-03-chart-results', `${OH}/#/employees/E1004/chart/results`);
  const amox = await api(nurse, 'POST', '/employees/E1004/medications', { name: 'Amoxicillin 500 mg capsules', source: 'ADMINISTERED' });
  check('CDS blocks penicillin-class medication for a penicillin allergy', amox.status === 409 && amox.json.code === 'cds_alert',
    (amox.json.alerts || []).map((a) => a.code).join(','));
  const terms = await api(nurse, 'GET', '/terminology/search?system=ICD_10&q=wrist&limit=5');
  check('terminology search', terms.status === 200 && terms.json.length > 0, (terms.json[0] || {}).code + ' ' + (terms.json[0] || {}).display);
  const enc = await api(nurse, 'POST', '/employees/E1001/encounters', { type: 'INJURY', caseId });
  const encId = enc.json.id;
  const tmpl = await api(nurse, 'GET', '/encounter-templates');
  const injury = (tmpl.json || []).find((t) => t.type === 'INJURY');
  const answers = {};
  for (const sec of injury.sections.filter((x) => x.key !== 'soap')) for (const f of sec.fields) { // SOAP goes in `soap`
    if (!f.required) continue;
    answers[f.key] = f.type === 'DATE' ? today : f.type === 'NUMBER' ? 3 : f.type === 'BOOLEAN' ? false
      : f.type === 'MULTISELECT' ? [f.options[0].value] : f.options && f.options.length ? f.options[0].value : 'Lifting trays at work';
  }
  const dx = terms.json[0];
  const saved = await api(nurse, 'PUT', `/encounters/${encId}`, { answers,
    soap: { subjective: 'Pain in left wrist after lifting trays', objective: 'Tender over radial side, full range of movement',
      assessment: 'Wrist strain', plan: 'Modified duties for two weeks' },
    bodyMap: [{ region: 'WRIST', side: 'LEFT', view: 'FRONT', mechanism: 'LIFTING' }],
    diagnoses: [{ system: dx.system, code: dx.code, display: dx.display, primary: true }] });
  const signed = await api(nurse, 'POST', `/encounters/${encId}/sign`);
  check('injury encounter documented with body map, coded diagnosis and e-signature', saved.status === 200 && signed.status === 200
    && signed.json.status === 'SIGNED', `save=${saved.status} sign=${signed.status} ${signed.status === 200 ? 'sha256=' + ((signed.json.signature || {}).sha256 || '').slice(0, 12) + '…' : JSON.stringify(saved.status === 200 ? signed.json : saved.json).slice(0, 200)}`);
  await shot(nurse, 'inc2-oh-04-encounter-signed', `${OH}/#/encounters/${encId}`, 2500);
  const audio = await api(nurse, 'POST', '/employees/E1004/results/device', { category: 'AUDIOMETRY', deviceId: 'AUD-LHR-01',
    deviceModel: 'Clinic audiometer', calibrationId: 'CAL-2026-0901', calibrationDate: '2026-09-01',
    measurements: { left: { 500: 10, 1000: 15, 2000: 30, 3000: 35, 4000: 40, 6000: 45, 8000: 35 },
      right: { 500: 10, 1000: 10, 2000: 15, 3000: 15, 4000: 20, 6000: 25, 8000: 20 } } });
  check('device audiogram flags a standard threshold shift against the baseline', audio.status === 201 || audio.status === 200
    ? audio.json.flag === 'ABNORMAL' : false, `flag=${audio.json.flag} sts=${JSON.stringify((audio.json.detail || {}).sts || {})}`);
  await shot(nurse, 'inc2-oh-05-results-worklist', `${OH}/#/results/worklist`);
  await shot(nurse, 'inc2-oh-06-case-fitness-tab', `${OH}/#/cases/${caseId}/fitness`);

  // ---------- physician: fitness decision → certificate, modified duty, RTW plan ----------
  const doc = await session(browser, OH, 'physician1');
  const decision = await api(doc, 'POST', `/cases/${caseId}/fitness-decisions`, { standardCode: 'FOOD_HANDLER', outcome: 'FIT_WITH_LIMITATIONS',
    restrictions: [{ code: 'LIFT_MAX_KG', value: 10 }, { code: 'NO_KNIFE_WORK' }], validFrom: today, validUntil: plusDays(28),
    reviewDate: plusDays(14), rationale: 'Left wrist strain; avoid loading for four weeks' });
  check('fitness decision against the food-handler standard', decision.status === 201, `kernel=${decision.json.kernelStatus} restrictions=${(decision.json.restrictions || []).map((r) => r.label).join('; ')}`);
  const cert = decision.json.certificateDocumentId;
  await api(doc, 'POST', `/documents/${cert}/sign`);
  const sh1 = await api(doc, 'POST', `/documents/${cert}/share`, { target: 'MANAGER' });
  const sh2 = await api(doc, 'POST', `/documents/${cert}/share`, { target: 'EMPLOYEE' });
  check('certificate signed and shared with manager and employee', sh1.status === 201 && sh2.status === 201);
  const match = await api(doc, 'POST', '/fitness/match', { employeeId: 'E1001', restrictions: [{ code: 'LIFT_MAX_KG', value: 10 }, { code: 'NO_KNIFE_WORK' }] });
  const compatible = (match.json || []).filter((m) => m.compatible).map((m) => m.jobCode);
  check('job-demand matching suggests compatible duties', compatible.length > 0, compatible.slice(0, 4).join(', '));
  const proposal = await api(doc, 'POST', `/cases/${caseId}/duty-proposals`, { jobCode: compatible[0], duties: 'Seated tray-set packing, no knives',
    hoursPerDay: 6, startDate: today, endDate: plusDays(28) });
  const plan = await api(doc, 'POST', `/cases/${caseId}/rtw-plans`, { startDate: today, goal: 'Back to full catering associate duties by week 5',
    reviewDates: [plusDays(7), plusDays(21)], phases: [{ fromWeek: 1, toWeek: 2, hoursPerDay: 4, duties: 'Seated tray packing', restrictions: [{ code: 'LIFT_MAX_KG', value: 10 }] },
      { fromWeek: 3, toWeek: 4, hoursPerDay: 6, duties: 'Packing and stores support' }] });
  const activated = await api(doc, 'POST', `/rtw-plans/${plan.json.id}/activate`);
  check('modified duty proposed and RTW plan activated', proposal.status === 201 && activated.status === 200, `plan=${activated.json.status}`);
  await shot(doc, 'inc2-oh-07-case-fitness-decision', `${OH}/#/cases/${caseId}/fitness`, 2500);
  await shot(doc, 'inc2-oh-08-case-restrictions-duties', `${OH}/#/cases/${caseId}/restrictions`, 2500);
  await shot(doc, 'inc2-oh-09-case-rtw-plan', `${OH}/#/cases/${caseId}/rtw`, 2500);
  await shot(doc, 'inc2-oh-10-case-documents', `${OH}/#/cases/${caseId}/documents`, 2500);
  await shot(doc, 'inc2-oh-11-active-restrictions', `${OH}/#/restrictions`);

  // ---------- manager: status-only views, accept duty, acknowledge plan ----------
  const mgr = await session(browser, MGR, 'manager1', { width: 1280, height: 860 });
  const team = await api(mgr, 'GET', '/workspace/team-restrictions');
  const teamText = JSON.stringify(team.json);
  check('manager sees functional restrictions only', team.status === 200 && teamText.includes('No lifting above 10 kg') && !/strain|wrist/i.test(teamText),
    `HTTP ${team.status} ${teamText.slice(0, 200)}`);
  const props = await api(mgr, 'GET', '/workspace/duty-proposals?status=PROPOSED');
  const mine = Array.isArray(props.json) ? props.json.find((p) => p.caseId === caseId) : null;
  if (!mine) log.push(`INFO  duty proposals HTTP ${props.status} ${JSON.stringify(props.json).slice(0, 200)}`);
  const accepted = mine ? await api(mgr, 'POST', `/duty-proposals/${mine.id}/decision`, { accept: true, note: 'Rota updated' }) : { status: 0 };
  check('manager accepts the modified duty', accepted.status === 200, accepted.json && accepted.json.status);
  await shot(mgr, 'inc2-mgr-01-team-restrictions', `${MGR}/#/restrictions`);
  await shot(mgr, 'inc2-mgr-02-duty-proposals', `${MGR}/#/duty-proposals`);
  await shot(mgr, 'inc2-mgr-03-rtw-plans', `${MGR}/#/rtw-plans`);
  const mack = await api(mgr, 'POST', `/rtw-plans/${plan.json.id}/manager-acknowledge`, { comment: 'Rota adjusted for weeks 1-4' });
  check('manager acknowledges the RTW plan', mack.status === 200);
  await shot(mgr, 'inc2-mgr-04-documents', `${MGR}/#/documents`);
  const chartAsMgr = await api(mgr, 'GET', '/employees/E1001/chart');
  check('manager cannot open the clinical chart', chartAsMgr.status === 403 || chartAsMgr.status === 404, 'HTTP ' + chartAsMgr.status);

  // ---------- HR: alternative-duty job offer ----------
  const hr = await session(browser, MGR, 'hr1', { width: 1280, height: 860 });
  const offer = await api(hr, 'POST', `/cases/${caseId}/job-offers`, { jobCode: compatible[0], jobTitle: 'Tray-set packer', duties: 'Seated tray-set packing',
    location: 'LHR catering unit', shift: 'Early', hoursPerWeek: 30, payBasis: 'PROTECTED_PAY', payNote: 'Current grade protected',
    restrictions: [{ code: 'LIFT_MAX_KG', value: 10 }], startDate: plusDays(2), contactName: 'Hana HR', contactEmail: 'hr.partner@wellnessz.local', respondBy: plusDays(1) });
  check('HR issues an electronic job offer', offer.status === 201, offer.json.status);
  await shot(hr, 'inc2-hr-01-job-offers', `${MGR}/#/cases/${caseId}/job-offers`);

  // ---------- employee: documents, RTW plan, job offer, privacy ----------
  await shot(emp, 'inc2-emp-01-documents', `${EMP}/#/documents`);
  const ack = await api(emp, 'POST', `/me/documents/${cert}/acknowledge`);
  check('employee acknowledges the fitness certificate', ack.status === 200 && !!ack.json.acknowledgedAt);
  const eack = await api(emp, 'POST', `/me/rtw-plans/${plan.json.id}/acknowledge`, { agree: true });
  check('employee agrees to the RTW plan', eack.status === 200);
  await shot(emp, 'inc2-emp-02-rtw-plan', `${EMP}/#/rtw`);
  await shot(emp, 'inc2-emp-03-job-offer', `${EMP}/#/job-offers`);
  const resp = await api(emp, 'POST', `/me/job-offers/${offer.json.id}/respond`, { accept: true, comment: 'Happy to start' });
  check('employee accepts the job offer', resp.status === 200, resp.json.status);
  await shot(emp, 'inc2-emp-04-privacy', `${EMP}/#/privacy`);
  const disc = await api(emp, 'GET', '/me/disclosures');
  check('employee sees who received the certificate', JSON.stringify(disc.json).includes('MANAGER'));

  // ---------- AME: crew assessment → roster feed (crew scheduling) ----------
  const ame = await session(browser, OH, 'ame1');
  const crewCases = await api(ame, 'GET', '/queues/TEAM?size=100&q=E1002');
  const pilotCase = ((crewCases.json && crewCases.json.items) || []).find((c) => c.employeeId === 'E1002');
  let crewOk = false;
  if (pilotCase) {
    const pid = pilotCase.caseId || pilotCase.id;
    const ca = await api(ame, 'POST', `/cases/${pid}/crew-assessment`, { standardCode: 'CAA_CLASS1', outcome: 'FIT_WITH_LIMITATIONS',
      limitationCodes: ['VDL', 'OML'], examinationDate: today, rationale: 'Hand injury healed; refractive error corrected' });
    crewOk = ca.status === 201;
    check('AME crew assessment with EASA limitation codes and medical report', crewOk, `report=${ca.json.reportDocumentId ? 'yes' : 'no'}`);
    await shot(ame, 'inc2-oh-12-case-crew', `${OH}/#/cases/${pid}/crew`, 2500);
  } else check('AME crew assessment', false, 'no pilot case found');
  const sched = await session(browser, OH, 'crewsched1');
  await sched.waitForTimeout(3000);
  const feed = await api(sched, 'GET', `/crew/roster-feed?since=${today}T00:00:00Z`);
  const pilotMsg = (feed.json || []).find((m) => m.employeeId === 'E1002');
  check('crew scheduling receives status, dates and operational limitations only', !!pilotMsg && pilotMsg.rosterStatus === 'LIMITED'
    && !/hand|refractive/i.test(JSON.stringify(feed.json)), pilotMsg ? `${pilotMsg.rosterStatus} ${pilotMsg.operationalLimitations || ''}` : 'HTTP ' + feed.status);
  await shot(sched, 'inc2-oh-13-roster-feed', `${OH}/#/crew/roster-feed`);

  // ---------- break-glass and DPO ----------
  const psych = await api(emp, 'POST', '/cases/self-referral', { category: 'PSYCHOLOGICAL', description: 'Very stressed after an incident on board' });
  check('psychological self-referral is created in the restricted class', psych.status === 201, 'HTTP ' + psych.status + ' ' + JSON.stringify(psych.json).slice(0, 160));
  // a clinician without a break-glass grant sees the restricted case only as a hidden-item count
  const before = await api(doc, 'GET', '/employees/E1001/chart');
  await shot(doc, 'inc2-oh-14-chart-restricted-hidden', `${OH}/#/employees/E1001/chart`, 2500);
  const bg = await api(nurse, 'POST', '/break-glass', { employeeId: 'E1001', reason: 'Collapsed at work, emergency team needs full history now' });
  const after = await api(nurse, 'GET', '/employees/E1001/chart');
  check('break-glass reveals the restricted case for a limited time', bg.status === 201 && before.json.restrictedItemsHidden > 0
    && after.json.restrictedItemsHidden === 0, `hidden before=${before.json.restrictedItemsHidden} after=${after.json.restrictedItemsHidden} expires=${bg.json.expiresAt}`);
  const dpo = await session(browser, OH, 'dpo1');
  await shot(dpo, 'inc2-dpo-01-break-glass-reviews', `${OH}/#/dpo/break-glass`);
  const rev = await api(dpo, 'POST', `/break-glass/${bg.json.id}/review`, { review: 'APPROPRIATE', note: 'Genuine emergency' });
  check('DPO reviews the break-glass use', rev.status === 200, rev.json.review);
  const dpoChart = await api(dpo, 'GET', '/employees/E1001/chart');
  check('DPO has no clinical access', dpoChart.status === 403 || dpoChart.status === 404, 'HTTP ' + dpoChart.status);
  await shot(dpo, 'inc2-dpo-02-disclosure-log', `${OH}/#/dpo/disclosures`);
  await shot(dpo, 'inc2-dpo-03-retention', `${OH}/#/dpo/retention`);

  // ---------- CMO / admin views ----------
  const cmo = await session(browser, OH, 'cmo1');
  await shot(cmo, 'inc2-oh-15-crew-dashboard', `${OH}/#/crew/dashboard`);
  await shot(cmo, 'inc2-oh-16-fitness-signoff', `${OH}/#/fitness/signoff`);
  await shot(cmo, 'inc2-oh-17-admin-fitness-standards', `${OH}/#/admin/fitness-standards`);
  const admin = await session(browser, OH, 'admin1');
  await shot(admin, 'inc2-oh-18-admin-job-demands', `${OH}/#/admin/job-demands`);
  await shot(admin, 'inc2-oh-20-admin-restriction-catalogue', `${OH}/#/admin/restrictions`);
  await shot(cmo, 'inc2-oh-19-encounter-templates', `${OH}/#/admin/encounter-templates`);

  await browser.close();
  console.log(log.join('\n'));
  if (log.some((l) => l.startsWith('FAIL'))) process.exit(1);
})().catch((e) => { console.log(log.join('\n')); console.error('E2E FAILED', e); process.exit(1); });
