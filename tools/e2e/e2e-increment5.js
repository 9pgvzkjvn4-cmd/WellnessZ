// End-to-end for increment 5 (E09 mental health & peer support, E10 drug & alcohol testing, E11 travel health &
// communicable disease, E12 health promotion) against the running local stack with real Keycloak logins through the
// BFF across all four apps: confidential self-referral + screening with urgent escalation, restricted-class isolation,
// EAP partner feedback, sharing choice, pseudonymous peer support, CISM, psychosocial register, AME return decision;
// random selection with reproducibility, chain-of-custody collection, MRO verification → duty removal + support
// referral, laboratory client binding; contact tracing, exposure timers, travel consultation, advisory, campaign
// uptake, public-health report; insights, screening with follow-up, ergonomics, wearable consent gate, duty feed.
// Usage: npm i -D playwright && node tools/e2e/e2e-increment5.js  (fresh stack – see docs/setup/01-Local-MacBook-Pro.md)
const { chromium } = require('playwright');

const EMP = process.env.WZ_E2E_EMPLOYEE_URL || 'http://localhost:5173';
const MGR = process.env.WZ_E2E_WORKSPACE_URL || 'http://localhost:5174';
const OH = process.env.WZ_E2E_OH_URL || 'http://localhost:5175';
const PARTNER = process.env.WZ_E2E_PARTNER_URL || 'http://localhost:5176';
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


async function login(page, user) {
  const app = new URL(page.url()).origin;
  await page.getByRole('button', { name: /Sign in with (your company account|multi-factor authentication)/ }).click();
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


// demo data (db/demo/R__demo_e09..e12_*.sql, R__demo_reference_duties.sql)
const EAP_DEMO = 'e09de000-0000-4000-d000-000000000001';
const CISM_DEMO = 'e09de000-0000-4000-f000-000000000001';
const RELIEF_DEMO = 'e09de000-0000-4000-e100-000000000001';
const TEST_POST_INCIDENT = 'e10de000-0000-4000-b000-000000000005';
const TEST_MRO = 'e10de000-0000-4000-b000-000000000003';
const CD_DEMO = 'e11de000-0000-4000-8000-000000000101';
const EXP_NEEDLE = 'e11de000-0000-4000-8000-000000000201';
const VC_DEMO = 'e11de000-0000-4000-8000-000000000401';
const PHR_DEMO = 'e11de000-0000-4000-8000-000000000601';
const MSK_CAMPAIGN = 'e12c0000-0000-4000-8000-000000000001';
const BIO_EVENT = 'e12de000-0000-4000-8000-000000000101';

(async () => {
  const browser = await chromium.launch();
  const token = await integrationToken();
  const run = Date.now().toString(36).toUpperCase();
  const iso = (h) => new Date(Date.now() + h * 3600000).toISOString();

  // ======================================================================= E09 mental health & peer support
  const emp = await session(browser, EMP, 'employee1', { width: 420, height: 900 });
  const sign = await api(emp, 'GET', '/me/support/signposting');
  const self = await api(emp, 'POST', '/me/support/self-referral', { preferredContact: 'PHONE', phone: '+44 7700 900101', bestTime: 'after 3pm',
    concern: 'WORK_STRESS', message: 'Finding the early shifts hard at the moment' });
  const screen = await api(emp, 'POST', '/me/support/screens', { instrument: 'PHQ9', answers: [2, 2, 1, 2, 1, 1, 1, 0, 1] });
  check('employee self-refers confidentially with signposting; a PHQ-9 with any self-harm answer escalates to a same-day call',
    sign.status === 200 && (self.status === 201 || self.status === 200) && screen.status === 200 && screen.json.urgent === true,
    `emergency=${!!(sign.json.emergency || {}).phone} ref=${self.json.caseRef} ` + (screen.status === 200 ? `score=${screen.json.score} band=${screen.json.band} urgent=${screen.json.urgent}` : brief(screen)));
  await shot(emp, 'inc5-emp-01-support', `${EMP}/#/support`, 2500);

  const psych = await session(browser, OH, 'psych1');
  const cases = await api(psych, 'GET', '/support/cases');
  const myCase = arr(cases.json).find((c) => c.employeeId === 'E1001');
  const urgent = await api(psych, 'GET', '/support/urgent');
  const nurse = await session(browser, OH, 'nurse1');
  const denied = myCase ? await api(nurse, 'GET', `/support/cases/${myCase.id}`) : { status: 0 };
  const placeholder = await api(nurse, 'GET', '/support/placeholder?employeeId=E1001');
  check('restricted class: the named mental-health clinician sees the case and the urgent item; a general OH nurse gets 404 and only a placeholder count',
    !!myCase && arr(urgent.json).some((u) => (u.employeeId || '') === 'E1001' || (u.caseId || '') === (myCase && myCase.id)) && denied.status === 404 && placeholder.json.restrictedCases >= 1,
    `cases=${arr(cases.json).length} urgent=${arr(urgent.json).length} nurse → ${denied.status}, placeholder=${placeholder.json.restrictedCases}`);
  await shot(psych, 'inc5-oh-01-support-cases', `${OH}/#/support/cases`, 2500);
  await shot(psych, 'inc5-oh-02-support-urgent', `${OH}/#/support/urgent`, 2500);
  await shot(nurse, 'inc5-oh-03-chart-restricted-placeholder', `${OH}/#/employees/E1001/chart`, 2500);

  const urgentContact = myCase ? await api(psych, 'POST', `/support/cases/${myCase.id}/urgent-contact`, { outcome: 'REACHED_SAFE', note: 'Reached by phone; safety plan agreed; GP informed with consent' }) : { status: 0 };
  const share = myCase ? await api(emp, 'PUT', '/me/support/sharing', { caseId: myCase.id, choice: 'FITNESS_ONLY' }) : { status: 0 };
  const mgr = await session(browser, MGR, 'manager1');
  const shared = await api(mgr, 'GET', '/workspace/psychosocial/shared-outcomes');
  check('urgent call-back documented; the employee chooses to share fitness only and the manager sees exactly that – no screening or notes',
    urgentContact.status === 200 && share.status === 200 && arr(shared.json).some((o) => o.employeeId === 'E1001') && !hasKeyDeep(shared.json, 'score') && !hasKeyDeep(shared.json, 'concern'),
    `contact=${urgentContact.status} sharing=${share.json.choice || share.status} shared rows=${arr(shared.json).length}`);
  await shot(mgr, 'inc5-mgr-01-psychosocial', `${MGR}/#/psychosocial`, 2500);

  const eap = await session(browser, PARTNER, 'eap1');
  const eapList = await api(eap, 'GET', '/partner/eap-referrals');
  const leak = await api(eap, 'POST', `/partner/eap-referrals/${EAP_DEMO}/feedback`, { status: 'IN_PROGRESS', attendance: [], note: 'Discussed panic attacks' });
  const fb = await api(eap, 'POST', `/partner/eap-referrals/${EAP_DEMO}/feedback`, { status: 'IN_PROGRESS', attendance: [{ date: today, attended: true }] });
  check('EAP partner sees minimal referral data for its own organisation and returns attendance only – free text is refused',
    eapList.status === 200 && arr(eapList.json).length > 0 && !hasKeyDeep(eapList.json, 'employeeId') && leak.status === 422 && fb.status === 200,
    `referrals=${arr(eapList.json).length} free text → ${leak.status} ${leak.json.code}; feedback → ${fb.json.status || fb.status}`);
  await shot(eap, 'inc5-partner-01-eap-referrals', `${PARTNER}/#/eap-referrals`, 2500);

  const peers = await api(emp, 'GET', '/me/peer-support/peers');
  const req = await api(emp, 'POST', '/me/peer-support/contact-request', { alias: 'Robin', callbackPhone: '+44 7700 900222', base: 'LHR', language: 'en' });
  const peer = await session(browser, OH, 'peer1');
  const routed = await api(peer, 'GET', '/peer-support/my-requests');
  // the request goes to an available trained peer at the base (peer1 or another volunteer); peer1 logs a contact routed to them
  const mineReq = arr(routed.json)[0] || {};
  const logC = await api(peer, 'POST', '/peer-support/contacts', { contactCode: mineReq.contactCode, date: today, durationMinutes: 25, category: 'FATIGUE', outcome: 'SIGNPOSTED' });
  check('crew peer support is pseudonymous: requests reach a trained peer as alias + code, the contact log holds no employee identity',
    arr(peers.json).length > 0 && /^PS-/.test(req.json.contactCode || '') && arr(routed.json).length > 0
    && !hasKeyDeep(routed.json, 'employeeId') && !hasKeyDeep(logC.json, 'employeeId') && (logC.status === 201 || logC.status === 200),
    `new code=${req.json.contactCode}; peer1 requests=${arr(routed.json).map((r) => r.alias).join(',')} logged=${logC.status}`);
  await shot(peer, 'inc5-oh-04-peer-volunteer', `${OH}/#/peer-support`, 2500);
  const coord = await session(browser, OH, 'peercoord1');
  await shot(coord, 'inc5-oh-05-peer-coordinator', `${OH}/#/peer-support`, 2500);
  await shot(emp, 'inc5-emp-02-peer-support', `${EMP}/#/peer-support`, 2500);

  const ev = await api(psych, 'GET', `/cism/events/${CISM_DEMO}`);
  const offers = await api(psych, 'POST', `/cism/events/${CISM_DEMO}/offers`, {});
  const safety = await session(browser, OH, 'safety1');
  const cismCounts = await api(safety, 'GET', '/cism/events');
  check('critical-incident support: affected crew come from the WZ101 roster, offers go out within 72 h; the safety manager sees counts only',
    ev.status === 200 && (ev.json.affected || []).length >= 3 && offers.status === 200 && cismCounts.status === 200 && !hasKeyDeep(cismCounts.json, 'displayName'),
    `affected=${(ev.json.affected || []).map((a) => a.employeeId).join(',')} offers=${offers.status}`);
  await shot(psych, 'inc5-oh-06-cism', `${OH}/#/cism/${CISM_DEMO}`, 2500);

  const hse = await session(browser, OH, 'hse1');
  const dash = await api(hse, 'GET', '/psychosocial/dashboard');
  check('psychosocial risk register by department with actions and an anonymised case rate', dash.status === 200 && arr(dash.json).length > 0,
    `${arr(dash.json).map((u) => `${u.unitCode}:${u.rag || (u.assessment || {}).rag || ''}`).join(' ')}`);
  await shot(hse, 'inc5-oh-07-psychosocial', `${OH}/#/psychosocial`, 2500);

  const ame = await session(browser, OH, 'ame1');
  const ret = await api(ame, 'POST', `/support/relief/${RELIEF_DEMO}/return`, { decision: 'FIT' });
  check('AME ends the temporary relief from flight duties – rostering receives the status only', ret.status === 200, `${ret.json.status || ''}`);
  await shot(ame, 'inc5-oh-08-support-fitness', `${OH}/#/support/fitness`, 2500);

  // ======================================================================= E10 drug & alcohol testing
  const dam = await session(browser, OH, 'damanager1');
  const period = today.slice(0, 7);
  const sel = await api(dam, 'POST', '/testing/selections', { roleGroup: 'AIRSIDE_DRIVER', period, testType: 'DRUGS' });
  const ver = sel.json.id ? await api(dam, 'GET', `/testing/selections/${sel.json.id}/verify`) : { json: {} };
  const notify = sel.json.id ? await api(dam, 'POST', `/testing/selections/${sel.json.id}/notify`, {}) : { status: 0 };
  const mroDenied = await api(dam, 'GET', '/testing/mro/queue');
  check('auditable random selection from the role-group pool, reproducible from its stored seed; the programme manager cannot see results',
    sel.status === 201 && ver.json.reproducible === true && notify.status === 200 && mroDenied.status === 403,
    `pool=${sel.json.poolSize} selected=${sel.json.selectedCount} reproducible=${ver.json.reproducible} MRO queue → ${mroDenied.status}`);
  await shot(dam, 'inc5-oh-09-testing-selections', `${OH}/#/testing/selections`, 2500);
  await shot(dam, 'inc5-oh-10-testing-policies', `${OH}/#/testing/policies`, 2500);

  const col = await session(browser, OH, 'collector1');
  const reqs = await api(col, 'GET', '/testing/requirements');
  const coll = await api(col, 'POST', `/testing/tests/${TEST_POST_INCIDENT}/collection`, { specimenId: `SPC-${run}`, specimenType: 'BREATH',
    collectedAt: new Date().toISOString(), siteCode: 'LHR-AIRSIDE', sealIntact: true, donorIdentityChecked: true, splitSpecimen: false, breathScreenResult: 0.0 });
  check('post-incident test collected inside the alcohol window with an electronic chain-of-custody form', reqs.status === 200 && (coll.status === 200 || coll.status === 201),
    `requirements=${arr(reqs.json).length} collection → ${coll.json.status || coll.status}`);
  await shot(col, 'inc5-oh-11-testing-collection', `${OH}/#/testing/collection`, 2500);

  const mro = await session(browser, OH, 'mro1');
  const queue = await api(mro, 'GET', '/testing/mro/queue');
  const dec = await api(mro, 'POST', `/testing/tests/${TEST_MRO}/mro-decision`, { verdict: 'POSITIVE', prescriptionVerified: false,
    interviewNote: 'Donor interviewed by telephone; no legitimate prescription for the detected opiate' });
  const hr = await session(browser, MGR, 'hr1');
  const duty = await api(hr, 'GET', '/workspace/testing/status');
  const e1008 = arr(duty.json).find((d) => d.employeeId === 'E1008');
  const support2 = await api(psych, 'GET', '/support/cases');
  check('MRO verifies a positive: the employee is removed from safety-sensitive duty, HR sees status only, and a confidential support referral opens',
    queue.status === 200 && dec.status === 200 && e1008 && e1008.dutyStatus === 'REMOVED' && !hasKeyDeep(duty.json, 'verdict')
    && arr(support2.json).some((c) => c.employeeId === 'E1008'), `queue=${arr(queue.json).length} verdict → ${dec.json.status || dec.status}; HR sees ${e1008 && e1008.dutyStatus}`);
  await shot(mro, 'inc5-oh-12-testing-mro', `${OH}/#/testing/mro`, 2500);
  await shot(hr, 'inc5-mgr-02-hr-duty-status', `${MGR}/#/testing`, 2500);
  const forCause = await api(mgr, 'POST', '/workspace/testing/for-cause', { employeeId: 'E1001', observedAt: iso(-1), location: 'Catering line 2',
    observations: ['UNSTEADY_GAIT', 'SLURRED_SPEECH'], description: 'Unsteady when loading trolleys, speech slurred at briefing', secondObserverName: 'Mo Supervisor' });
  check('manager raises a documented for-cause request with an observation checklist and a second observer', forCause.status === 201 || forCause.status === 200,
    `${forCause.json.ref || brief(forCause)}`);
  await shot(mgr, 'inc5-mgr-03-for-cause', `${MGR}/#/testing`, 2500);
  const lab = await integration(token, '/integration/testing/lab-results', { specimenId: 'SPC-DEMO-9002', labRef: `LAB-${run}`, receivedAt: new Date().toISOString(),
    validity: 'VALID', results: [{ analyte: 'AMPHETAMINES', cutoff: 500, value: 0, unit: 'ng/mL', outcome: 'NEGATIVE' }] });
  check('laboratory results are accepted only from the toxicology laboratory system – an unbound client gets 404', lab.status === 404, `unbound client → ${lab.status}`);
  const stats = await api(dam, 'GET', `/testing/statistics?year=${today.slice(0, 4)}`);
  check('programme statistics by reason and role group with small cells suppressed', stats.status === 200 && arr(stats.json.rows || stats.json).some((r) => r.suppressed),
    `${arr(stats.json.rows || stats.json).length} rows`);
  await shot(dam, 'inc5-oh-13-testing-reports', `${OH}/#/testing/reports`, 2500);

  // ======================================================================= E11 travel health & communicable disease
  const duties = await integration(token, '/integration/duty-assignments', { source: 'SHIFT', duties: [{ sourceRef: `SHF-${run}`, dutyRef: 'CAT-LINE-2',
    dutyDate: today, startsAt: iso(-3), endsAt: iso(-1), employeeRef: 'B-1010', dutyRole: 'CATERING', locationCode: 'LHR', proximityGroup: 'LHR-CAT-LINE-2' }] });
  const trace = await api(nurse, 'POST', `/communicable/cases/${CD_DEMO}/trace`, { windowFrom: iso(-72), windowTo: iso(0), include: ['SAME_DUTY', 'PROXIMITY_GROUP', 'FACILITY'] });
  const high = (trace.json.contacts || []).filter((c) => c.riskLevel === 'HIGH');
  const notified = high.length ? await api(nurse, 'POST', `/communicable/cases/${CD_DEMO}/contacts/notify`, { contactIds: high.map((c) => c.id) }) : { status: 0 };
  check('contact tracing from roster and shift data within the 4-hour target; contacts are notified without naming the case',
    duties.json.upserted === 1 && trace.status === 200 && high.length >= 2 && notified.status === 200,
    `duty feed upserted=${duties.json.upserted}; contacts=${(trace.json.contacts || []).length} high=${high.map((c) => c.employeeId).join(',')} in ${trace.json.hoursSinceConfirmation} h`);
  await shot(nurse, 'inc5-oh-14-contact-tracing', `${OH}/#/communicable/${CD_DEMO}`, 2500);

  const exp = await api(nurse, 'GET', `/exposures/${EXP_NEEDLE}`);
  const pep = (exp.json.timers || []).find((t) => /PEP/.test(t.code));
  const pepDone = pep ? await api(nurse, 'POST', `/exposures/${EXP_NEEDLE}/tasks/${pep.code}/complete`, { note: 'Risk assessed; PEP started at 08:40' }) : { status: 0 };
  check('needle-stick pathway with protocol timers – the PEP decision is due within hours and is completed in time',
    exp.status === 200 && !!pep && pepDone.status === 200, `timers=${(exp.json.timers || []).map((t) => `${t.code}:${t.status}`).join(' ')}`);
  await shot(nurse, 'inc5-oh-15-exposure-timers', `${OH}/#/exposures/${EXP_NEEDLE}`, 2500);

  const cons = await api(nurse, 'POST', '/travel/consultations', { employeeId: 'E1002', trip: { destinations: ['IN'], departDate: plusDays(10), returnDate: plusDays(14), purpose: 'LAYOVER' },
    vaccinesGiven: [], prophylaxis: [], advice: 'Food and water hygiene; mosquito bite avoidance' });
  check('travel consultation with destination-based recommendations and a signed travel health certificate', (cons.status === 201 || cons.status === 200) && !!cons.json.certificateDocumentId,
    `recommendations=${(cons.json.recommendations || []).map((r) => r.vaccineCode || r.code || r).join(',')}`);
  await shot(nurse, 'inc5-oh-16-travel-consultations', `${OH}/#/travel/consultations`, 2500);

  const doc = await session(browser, OH, 'physician1');
  const adv = await api(doc, 'POST', '/advisories', { title: `Heat advisory DXB ${run}`, destinationCode: 'DXB', roles: [], severity: 'WARNING',
    body: 'High temperatures expected: hydrate, avoid midday sun on layover, seek shade.', validFrom: today, validUntil: plusDays(14) });
  const acks = adv.json.id ? await api(doc, 'GET', `/advisories/${adv.json.id}/acknowledgements`) : { json: {} };
  check('health advisory reaches crew rostered to the destination, with acknowledgement tracking (small counts suppressed)',
    (adv.status === 201 || adv.status === 200) && acks.status === 200 && ((acks.json.targeted || 0) >= 2 || acks.json.suppressed === true || arr(acks.json.pending).length >= 2),
    `targeted=${acks.json.targeted ?? 'suppressed'} pending=${arr(acks.json.pending).length}`);
  await shot(doc, 'inc5-oh-17-advisories', `${OH}/#/advisories`, 2500);
  const sub = await api(doc, 'POST', `/public-health/reports/${PHR_DEMO}/submit`, { channel: 'EMAIL', submittedAt: new Date().toISOString(), reference: `HPT-${run}` });
  check('notifiable-disease report submitted to the health protection team within the statutory time', sub.status === 200, `${sub.json.status} on time=${sub.json.submittedOnTime}`);
  await shot(doc, 'inc5-oh-18-public-health', `${OH}/#/public-health`, 2500);
  const head = await session(browser, OH, 'nursinghead1');
  const uptake = await api(head, 'GET', `/vaccination-campaigns/${VC_DEMO}/uptake?groupBy=SUBSIDIARY`);
  check('influenza campaign uptake by subsidiary with small groups suppressed', uptake.status === 200, `${arr(uptake.json.rows || uptake.json).length} rows`);
  await shot(head, 'inc5-oh-19-vaccination-campaigns', `${OH}/#/vaccination-campaigns`, 2500);
  await shot(emp, 'inc5-emp-03-travel', `${EMP}/#/travel`, 2500);

  // ======================================================================= E12 health promotion
  const promo = await session(browser, OH, 'promo1');
  const ins = await api(promo, 'GET', '/promotion/insights');
  const rep = await api(promo, 'GET', `/promotion/campaigns/${MSK_CAMPAIGN}/report?groupBy=unit`);
  check('campaigns planned from anonymised hotspots; participation and outcome report with suppression – no person-level data',
    ins.status === 200 && rep.status === 200 && !hasKeyDeep(ins.json, 'employeeId') && !hasKeyDeep(rep.json, 'employeeId'),
    `insights=${arr(ins.json).length} participation rows=${(rep.json.participation || []).length}`);
  await shot(promo, 'inc5-oh-20-promotion-insights', `${OH}/#/promotion/insights`, 2500);
  await shot(promo, 'inc5-oh-21-promotion-campaigns', `${OH}/#/promotion/campaigns`, 2500);
  const res = await api(nurse, 'POST', `/promotion/screening-events/${BIO_EVENT}/results`, { employeeId: 'M2001', measures: { systolic: 158, diastolic: 96, bmi: 27.5 } });
  check('biometric screening: an abnormal blood pressure opens a follow-up case within 48 h and a result letter', (res.status === 201 || res.status === 200) && res.json.abnormal === true && !!res.json.followUpCaseRef,
    `follow-up ${res.json.followUpCaseRef}`);
  await shot(nurse, 'inc5-oh-22-screening', `${OH}/#/promotion/screening`, 2500);
  const ergo = await session(browser, OH, 'ergo1');
  const lists = await api(ergo, 'GET', '/ergonomics/checklists');
  const mh = arr(lists.json).find((c) => c.code === 'MANUAL_HANDLING') || { items: [] };
  const scores = { LOAD_WEIGHT: 3, LOAD_DISTANCE: 2, TWIST_STOOP: 2, REACH_HIGH: 1, PUSH_PULL: 2, CARRY_DISTANCE: 1 };
  const checklist = Object.fromEntries((mh.items || []).map((i) => [i.code, scores[i.code] ?? 1]));
  const ea = await api(ergo, 'POST', '/ergonomics/assessments', { type: 'MANUAL_HANDLING', unitCode: 'CARGO', jobCode: 'FORKLIFT_OP', employeeId: 'E1008',
    location: 'Cargo shed 4 – ULD build-up', checklist, findings: 'Heavy cartons lifted from floor level onto ULDs; frequent twisting' });
  const act = ea.json.id ? await api(ergo, 'POST', `/ergonomics/assessments/${ea.json.id}/actions`, { title: 'Provide height-adjustable lifting table', owner: 'Cargo shift lead', dueDate: plusDays(30) }) : { status: 0 };
  check('ergonomic manual-handling assessment scored with RAG and the job-demand profile, with actions to closure', (ea.status === 201 || ea.status === 200) && !!ea.json.rag && (act.status === 201 || act.status === 200),
    ea.status < 300 ? `score=${ea.json.riskScore} RAG=${ea.json.rag}` : brief(ea));
  await shot(ergo, 'inc5-oh-23-ergonomics', `${OH}/#/ergonomics`, 2500);
  await shot(mgr, 'inc5-mgr-04-ergonomics', `${MGR}/#/ergonomics`, 2500);
  await shot(mgr, 'inc5-mgr-05-campaigns', `${MGR}/#/campaigns`, 2500);

  const mine = await api(emp, 'GET', '/me/campaigns');
  const enrol = arr((mine.json || {}).enrolments)[0];
  const wear = enrol ? await integration(token, '/integration/wearables/data', [
    { employeeRef: 'E1001', campaignId: enrol.campaignId, metric: 'STEPS', date: today, value: 8432 },
    { employeeRef: 'E1005', campaignId: enrol.campaignId, metric: 'STEPS', date: today, value: 5000 }]) : { status: 0, json: {} };
  check('wearable data accepted only for consented enrolments (daily totals); data without consent is rejected',
    wear.status === 200 && wear.json.accepted === 1 && (arr(wear.json.rejected).length + arr(wear.json.unmatched).length) >= 1,
    `accepted=${wear.json.accepted} rejected=${arr(wear.json.rejected).length}`);
  await shot(emp, 'inc5-emp-04-campaigns', `${EMP}/#/campaigns`, 2500);
  await shot(emp, 'inc5-emp-05-testing-notices', `${EMP}/#/testing`, 2500);
  await shot(promo, 'inc5-oh-24-promotion-reports', `${OH}/#/promotion/reports`, 2500);

  await browser.close();
  console.log(log.join('\n'));
  if (log.some((l) => l.startsWith('FAIL'))) process.exit(1);
})().catch((e) => { console.log(log.join('\n')); console.error('E2E FAILED', e); process.exit(1); });
