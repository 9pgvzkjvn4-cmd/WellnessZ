// End-to-end for increment 4 (E04 treatment coordination & partner referrals, E08 claims coordination) against the
// running local stack with real Keycloak logins through the BFF, across all four apps incl. the partner portal:
// treatment plan + employee view, e-referral with data-set and consent checks, partner provider accept / appointment /
// report / invoice, organisation isolation, provider directory + scorecards, pre-authorisation decided by the TPA adjuster,
// clinic medication with batch checks, outcomes, telehealth with consent + secure chat; claim with automatic nurse
// assignment, case badge, first report of injury, consent-gated disclosure set, adjuster collaboration, finance roll-up +
// ERP export, legal hold + medico-legal report, HR status view, DPO disclosure report. Screenshots for every new screen.
// Usage: npm i -D playwright && node tools/e2e/e2e-increment4.js  (stack running – see docs/setup/01-Local-MacBook-Pro.md)
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


// demo data (db/demo/R__demo_e04_treatment.sql, R__demo_e08_claims.sql)
const REFERRAL_OVERDUE = 'e04de000-0000-4000-b100-000000000003';
const PREAUTH_GULF = 'e04de000-0000-4000-b200-000000000001';
const CLAIM_LITIGATED = '8e080000-0000-4000-b000-000000000002';
const CLAIM_DEMO = '8e080000-0000-4000-b000-000000000001';
const DEMO_SET = '8e080000-0000-4000-e000-000000000001';
const PNG_BYTES = PNG;

(async () => {
  const browser = await chromium.launch();
  const run = Date.now().toString(36).toUpperCase();

  // ---------- employee self-referral opens an injury case (E1001, catering) and grants partner / insurer consent ----------
  const emp = await session(browser, EMP, 'employee1', { width: 420, height: 900 });
  const ref = await api(emp, 'POST', '/intake/referrals/self', { templateCode: 'CATERING', category: 'INJURY',
    description: 'Twisted my wrist lifting a trolley onto the high-loader', bodyParts: ['WRIST_L'], symptoms: ['PAIN'],
    answers: { atWork: 'YES', handledFood: 'YES' }, preferredContact: 'PHONE', language: 'en', consent: { assessment: true, purposes: [] } });
  const caseId = ref.json.caseId;
  const c1 = await api(emp, 'POST', '/consents', { purpose: 'TREATMENT_SHARING_PARTNER', scope: 'Physiotherapy for my wrist' });
  const c2 = await api(emp, 'POST', '/consents', { purpose: 'INSURER_SHARING', scope: 'Workers compensation claim' });
  check('employee reports an injury and grants partner + insurer consent in the app', ref.status === 201 && c1.status === 201 && c2.status === 201,
    `case ${ref.json.caseRef}; consents ${c1.status}/${c2.status}`);

  // ======================================================================= E04 treatment coordination
  const nurse = await session(browser, OH, 'nurse1');
  const tpl = await api(nurse, 'GET', '/treatment/plan-templates');
  const plan = await api(nurse, 'POST', `/cases/${caseId}/treatment-plans?activate=true`, { templateCode: 'WRIST_SPRAIN',
    followUps: [{ dueDate: plusDays(7), type: 'OH_REVIEW', siteCode: 'HQ-MC' }, { dueDate: plusDays(14), type: 'PHYSIOTHERAPY' }] });
  check('nurse builds an active treatment plan from the wrist-sprain template with follow-ups', plan.status === 201 && plan.json.status === 'ACTIVE'
    && (plan.json.followUps || []).length >= 2, `${(tpl.json || []).length} templates; expected recovery ${plan.json.expectedRecoveryDate}; guideline ${(plan.json.guideline || {}).expectedDays} d`);
  const print = await api(nurse, 'POST', `/treatment-plans/${plan.json.id}/print`);
  const myPlans = await api(emp, 'GET', '/me/treatment-plans');
  const myPlan = arr(myPlans.json).find((p) => p.id === plan.json.id);
  const fu = myPlan && (myPlan.followUps || []).find((f) => f.status === 'PLANNED' || f.status === 'BOOKED');
  const conf = fu ? await api(emp, 'POST', `/me/treatment-plans/${plan.json.id}/follow-ups/${fu.id}/response`, { response: 'CONFIRM' }) : { status: 0, json: {} };
  check('employee sees the plan in plain language (no OH-only fields), gets the printable plan and confirms a follow-up',
    print.status === 200 || print.status === 201 ? !!myPlan && !hasKeyDeep(myPlans.json, 'variance') && (conf.status === 200) : false,
    `print=${print.status} confirm=${conf.status}`);
  await shot(nurse, 'inc4-oh-01-case-treatment-plan', `${OH}/#/cases/${caseId}/treatment`, 2500);
  await shot(emp, 'inc4-emp-01-my-treatment', `${EMP}/#/treatment`, 2500);

  // ---------- e-referral wizard: data set + consent, then partner portal ----------
  const services = await api(nurse, 'GET', '/referrals/services');
  const physio = arr(services.json).find((s) => s.code === 'PHYSIOTHERAPY');
  const consents = await api(nurse, 'GET', `/cases/${caseId}/consents?purpose=TREATMENT_SHARING_PARTNER`);
  const consentId = (arr(consents.json)[0] || {}).id;
  const draft = await api(nurse, 'POST', `/cases/${caseId}/referrals`, { serviceCode: 'PHYSIOTHERAPY', urgency: 'URGENT', providerCode: 'PHYSIOPLUS',
    reason: 'Left wrist sprain after lifting at work', question: 'Assessment and graded strengthening programme', dataSet: { bodyPart: 'WRIST_L' },
    consentId, channel: 'PORTAL', attachDocumentIds: [] });
  const incomplete = await api(nurse, 'POST', `/referrals/${draft.json.id}/send`);
  await api(nurse, 'PUT', `/referrals/${draft.json.id}`, { serviceCode: 'PHYSIOTHERAPY', urgency: 'URGENT', providerCode: 'PHYSIOPLUS',
    reason: 'Left wrist sprain after lifting at work', question: 'Assessment and graded strengthening programme',
    dataSet: { bodyPart: 'WRIST_L', sessions: '6 sessions over 3 weeks' }, consentId, channel: 'PORTAL', attachDocumentIds: [] });
  const sent = await api(nurse, 'POST', `/referrals/${draft.json.id}/send`);
  check('referral needs the full data set and active consent; then the letter + FHIR payload are sent to the contracted provider',
    !!physio && incomplete.status === 422 && sent.status === 200 && sent.json.status === 'SENT',
    `required=${(physio && physio.requiredFields || []).map((f) => f.code).join(',')}; incomplete → ${incomplete.status} ${incomplete.json.code} ${incomplete.json.field || ''}; sent ${sent.json.ref} due ${String(sent.json.reportDueAt).slice(0, 10)}`);

  const phys = await session(browser, PARTNER, 'physio1');
  const inbox = await api(phys, 'GET', '/partner/referrals');
  const mine = arr(inbox.json).find((r) => r.id === draft.json.id);
  const city = await session(browser, PARTNER, 'partner1');
  const other = await api(city, 'GET', `/partner/referrals/${draft.json.id}`);
  check('partner provider sees the referral in its portal; another organisation gets 404', !!mine && other.status === 404,
    `${arr(inbox.json).length} referrals for PHYSIOPLUS; CITYHOSP → ${other.status}`);
  const acc = await api(phys, 'POST', `/partner/referrals/${draft.json.id}/accept`, {});
  const appt = await api(phys, 'POST', `/partner/referrals/${draft.json.id}/appointment`, { appointmentAt: new Date(Date.now() + 2 * 86400000).toISOString(),
    location: 'PhysioPlus Hounslow, room 2', instructions: 'Bring comfortable clothing' });
  const seen = await api(phys, 'POST', `/partner/referrals/${draft.json.id}/seen`, {});
  const rep = await upload(phys, `/partner/referrals/${draft.json.id}/report`, { summary: 'Assessment completed; graded programme started' }, PNG_BYTES, 'report.png', 'image/png');
  const inv = await upload(phys, `/partner/referrals/${draft.json.id}/invoice`, { invoiceNumber: `PP-${run}`, amount: '180.00', currency: 'GBP', serviceDate: today },
    PNG_BYTES, 'invoice.png', 'image/png');
  check('provider accepts, books the appointment, marks seen, uploads the report and invoices through the portal',
    acc.status === 200 && appt.status === 200 && seen.status === 200 && (rep.status === 200 || rep.status === 201) && (inv.status === 200 || inv.status === 201),
    `accept=${acc.status} appointment=${appt.status} seen=${seen.status} report=${rep.status} invoice=${inv.status}`);
  await shot(phys, 'inc4-partner-01-referral-inbox', `${PARTNER}/#/referrals`, 2500);
  await shot(phys, 'inc4-partner-02-referral-detail', `${PARTNER}/#/referrals/${draft.json.id}`, 2500);
  const after = await api(nurse, 'GET', `/referrals/${draft.json.id}`);
  const rate = await api(nurse, 'POST', `/referrals/${draft.json.id}/rating`, { rating: 5, comment: 'Clear functional findings' });
  const overdue = await api(nurse, 'GET', '/referrals?overdue=true');
  check('report attaches to the case, OH rates it; overdue reports are on the worklist with chase count',
    after.json.status === 'REPORT_RECEIVED' && !!after.json.reportDocumentId && rate.status === 200 && arr(overdue.json).some((r) => r.id === REFERRAL_OVERDUE),
    `status=${after.json.status} overdue=${arr(overdue.json).map((r) => `${r.ref}×${r.chaseCount}`).join(', ')}`);
  await shot(nurse, 'inc4-oh-02-referral-worklist', `${OH}/#/referrals`, 2500);

  // ---------- provider directory, scorecards, network gaps ----------
  const proc = await session(browser, OH, 'procurement1');
  const dir = await api(proc, 'GET', '/providers?serviceCode=PHYSIOTHERAPY');
  const gaps = await api(proc, 'GET', '/providers/network-gaps');
  const cmo = await session(browser, OH, 'cmo1');
  const lastMonth = new Date(new Date().getFullYear(), new Date().getMonth() - 1, 15).toISOString().slice(0, 7);
  const cards = await api(cmo, 'GET', `/providers/scorecards?month=${lastMonth}`);
  check('procurement searches contracted providers by service; CMO sees monthly vendor scorecards; network gaps listed',
    dir.status === 200 && arr(dir.json).length > 0 && cards.status === 200 && arr(cards.json).length > 0 && gaps.status === 200,
    `${arr(dir.json).map((p) => `${p.code}(${(p.contract || {}).status || 'NONE'})`).join(', ')}; scorecards=${arr(cards.json).length}; gaps=${arr(gaps.json).length}`);
  await shot(proc, 'inc4-oh-03-provider-directory', `${OH}/#/providers`, 2500);
  await shot(cmo, 'inc4-oh-04-provider-scorecards', `${OH}/#/providers/scorecards`, 2500);

  // ---------- pre-authorisation decided by the TPA adjuster in the partner portal ----------
  const insurerConsent = arr((await api(nurse, 'GET', `/cases/${caseId}/consents?purpose=INSURER_SHARING`)).json)[0] || {};
  const pa = await api(nurse, 'POST', `/cases/${caseId}/preauthorisations`, { payerCode: 'GULFTPA', serviceCode: 'PHYSIOTHERAPY', providerCode: 'PHYSIOPLUS',
    referralId: draft.json.id, estimate: { amount: '360.00', currency: 'GBP' }, justification: 'Six physiotherapy sessions for a work-related wrist sprain',
    consentId: insurerConsent.id });
  const paSub = await api(nurse, 'POST', `/preauthorisations/${pa.json.id}/submit`);
  const adj = await session(browser, PARTNER, 'adjuster1');
  const pending = await api(adj, 'GET', '/partner/preauthorisations?status=SUBMITTED');
  const dec = await api(adj, 'POST', `/partner/preauthorisations/${pa.json.id}/decision`, { status: 'APPROVED', approvalRef: `GT-${run}`,
    approvedAmount: { amount: '360.00', currency: 'GBP' }, validUntil: plusDays(60) });
  check('pre-authorisation is shared with the TPA under consent and approved by the adjuster in the partner portal',
    pa.status === 201 && paSub.json.status === 'SUBMITTED' && arr(pending.json).some((p) => p.id === pa.json.id) && dec.json.status === 'APPROVED',
    `${pa.json.ref} → ${paSub.json.status} → ${dec.json.status} ${dec.json.approvalRef || ''}; pending for GULFTPA=${arr(pending.json).length}`);
  await shot(adj, 'inc4-partner-03-preauthorisations', `${PARTNER}/#/preauthorisations`, 2500);

  // ---------- clinic medication with allergy / batch checks ----------
  const stock = await api(nurse, 'GET', '/treatment/stock?siteCode=LHR-AIRSIDE');
  const expired = await api(nurse, 'POST', `/cases/${caseId}/medications/administer`, { itemCode: 'ADRENALINE_AI_300', dose: '300 micrograms', route: 'INTRAMUSCULAR', batchNumber: 'ADR-2207', siteCode: 'LHR-AIRSIDE' });
  const given = await api(nurse, 'POST', `/cases/${caseId}/medications/administer`, { itemCode: 'PARACETAMOL_500', dose: '1 g', route: 'ORAL', batchNumber: 'PCM-2401', siteCode: 'LHR-AIRSIDE' });
  check('clinic medication recorded with batch and expiry; an expired batch is refused', expired.status === 422 && (given.status === 201 || given.status === 200),
    `expired → ${expired.status} ${expired.json.code}; given ${given.json.name || ''} batch ${given.json.batchNumber} exp ${given.json.expiry}; stock rows ${arr(stock.json).length}`);
  await shot(nurse, 'inc4-oh-05-medications', `${OH}/#/treatment/medications`, 2500);

  // ---------- outcomes ----------
  const o1 = await api(nurse, 'POST', `/cases/${caseId}/outcomes`, { milestone: 'BASELINE', painScore: 7, functionScale: 'QUICKDASH', functionScore: 55 });
  const o2 = await api(nurse, 'POST', `/cases/${caseId}/outcomes`, { milestone: 'REVIEW', painScore: 3, functionScale: 'QUICKDASH', functionScore: 25, rtwDate: plusDays(5) });
  const outs = await api(nurse, 'GET', `/cases/${caseId}/outcomes`);
  check('outcomes captured at milestones with validated scales and improvement', o1.status === 201 && o2.status === 201 && outs.json.improvement,
    `pain Δ ${(outs.json.improvement || {}).painDelta}, function Δ ${(outs.json.improvement || {}).functionDelta}`);
  await shot(nurse, 'inc4-oh-06-case-outcomes', `${OH}/#/cases/${caseId}/outcomes`, 2500);

  // ---------- telehealth with consent + secure chat ----------
  const doc = await session(browser, OH, 'physician1');
  const th = await api(doc, 'POST', '/telehealth/sessions', { employeeId: 'E1001', caseId, scheduledAt: new Date(Date.now() + 5 * 60000).toISOString(),
    durationMinutes: 20, mode: 'VIDEO', clinicianUsername: 'physician1', crewLayover: false });
  const noConsent = await api(emp, 'POST', `/me/telehealth/${th.json.id}/join`, {});
  const cons = await api(emp, 'POST', `/me/telehealth/${th.json.id}/consent`, {});
  const join = await api(emp, 'POST', `/me/telehealth/${th.json.id}/join`, {});
  await api(emp, 'POST', `/me/telehealth/${th.json.id}/messages`, { text: 'I am ready, the video is a bit slow on hotel Wi-Fi' });
  const msgs = await api(doc, 'GET', `/telehealth/sessions/${th.json.id}/messages`);
  const done = await api(doc, 'POST', `/telehealth/sessions/${th.json.id}/complete`, { note: 'Video review: swelling settling, range of motion improving. Continue physiotherapy.' });
  check('telehealth: join needs consent; secure chat; completion creates a draft consultation note', th.status === 201 && noConsent.status === 422 && cons.status === 200
    && !!join.json.joinUrl && arr(msgs.json).length >= 1 && !!done.json.encounterId,
    `no consent → ${noConsent.status}; join ${String(join.json.joinUrl).slice(0, 48)}…; messages ${arr(msgs.json).length}; encounter ${done.json.encounterId}`);
  await shot(doc, 'inc4-oh-07-telehealth', `${OH}/#/telehealth`, 2500);
  await shot(emp, 'inc4-emp-02-my-telehealth', `${EMP}/#/telehealth`, 2500);
  const kpis = await api(cmo, 'GET', `/treatment/kpis?from=${plusDays(-365)}&to=${today}`);
  check('treatment KPIs: referral-to-appointment, report within 7 days, missed appointments, cost per case', kpis.status === 200,
    `median days to appointment=${kpis.json.medianWorkingDaysReferralToAppointment} report≤7d=${kpis.json.reportWithin7DaysPct}% missed=${kpis.json.missedAppointmentPct}%`);
  await shot(nurse, 'inc4-oh-08-recovery-variance', `${OH}/#/treatment/variance`, 2500);
  await shot(nurse, 'inc4-oh-09-chart-treatment', `${OH}/#/employees/E1001/chart/treatment`, 2500);

  // ======================================================================= E08 claims coordination
  const claims = await session(browser, OH, 'claims1');
  const cl = await api(claims, 'POST', `/cases/${caseId}/claims`, { claimType: 'WORKERS_COMP', payerCode: 'GULFTPA', policyNumber: 'WC-2026-0042',
    adjusterName: 'Adam Adjuster', adjusterEmail: 'adjuster1@wellnessz.local', adjusterUsername: 'adjuster1', lostTime: true });
  const claimId = cl.json.id;
  const badge = await api(nurse, 'GET', `/cases/${caseId}/claim-status`);
  check('claims coordinator opens a lost-time claim from the case; a nurse case manager is assigned automatically; badge on the case header',
    cl.status === 201 && !!cl.json.nurseCaseManager && badge.status === 200 && badge.json.claimRef === cl.json.claimRef && !hasKeyDeep(badge.json, 'paidTotal'),
    `${cl.json.claimRef} nurse=${cl.json.nurseCaseManager} badge=${badge.json.status}`);

  const fr = await api(claims, 'POST', `/claims/${claimId}/reports`, { formCode: 'INSURER_FNOL' });
  let sub = await api(claims, 'POST', `/claims/reports/${fr.json.id}/submit`, { channel: 'API' });
  if (sub.status === 422 && sub.json.code === 'report_incomplete') {
    await api(claims, 'PUT', `/claims/reports/${fr.json.id}`, { fields: { location: 'LHR catering high-loader bay', description: 'Wrist twisted while lifting a trolley',
      bodyPart: 'Left wrist', injuryNature: 'Sprain', lostTime: '3 days', jobTitle: 'Catering associate', occurredAt: today } });
    sub = await api(claims, 'POST', `/claims/reports/${fr.json.id}/submit`, { channel: 'API' });
  }
  check('first report of injury auto-populated with a statutory deadline timer and submitted under consent', fr.status === 201 && sub.json.status === 'SUBMITTED',
    `${fr.json.ref} due ${String(fr.json.dueAt).slice(0, 16)} → ${sub.json.status || brief(sub)}`);
  await shot(claims, 'inc4-oh-10-claims', `${OH}/#/claims`, 2500);
  await shot(claims, 'inc4-oh-11-claim-reports', `${OH}/#/claims/reports`, 2500);

  const docs = await api(claims, 'GET', `/claims/${claimId}/documents`);
  const disclosable = arr(docs.json).filter((d) => d.disclosable).map((d) => d.documentId || d.id);
  const set = await api(claims, 'POST', `/claims/${claimId}/disclosure-sets`, { recipientCode: 'GULFTPA', purpose: 'Liability assessment', documentIds: disclosable.slice(0, 3) });
  const claimConsents = await api(claims, 'GET', `/claims/${claimId}/consents`);
  const sendSet = await api(claims, 'POST', `/claims/disclosure-sets/${set.json.id}/send`, { consentId: (arr(claimConsents.json)[0] || {}).id });
  // the demo DRAFT set cannot be sent under a consent that does not exist / is not active
  const noCons = await api(claims, 'POST', `/claims/disclosure-sets/${DEMO_SET}/send`, { consentId: '00000000-0000-4000-8000-000000000000' });
  const adjDocs = await api(adj, 'GET', '/partner/documents');
  check('consent-gated disclosure set: only claim-relevant documents, sent to the TPA and visible in its portal; disclosure logged',
    set.status === 201 && sendSet.json.status === 'SENT' && arr(adjDocs.json).length > 0 && noCons.status === 422, `${disclosable.length} disclosable of ${arr(docs.json).length}; `
    + `sent=${sendSet.json.status || brief(sendSet)}; adjuster documents=${arr(adjDocs.json).length}; invalid consent → ${noCons.status} ${noCons.json.code}`);
  await shot(claims, 'inc4-oh-12-claim-disclosures', `${OH}/#/claims/${claimId}/disclosures`, 2500);

  const pClaims = await api(adj, 'GET', '/partner/claims');
  const pDetail = await api(adj, 'GET', `/partner/claims/${CLAIM_DEMO}`);
  const task = await api(adj, 'POST', `/claims/${claimId}/tasks`, { title: 'Confirm modified duties start date', dueDate: plusDays(3), assigneeRole: 'NURSE_CM' });
  const msg = await api(adj, 'POST', `/claims/${claimId}/messages`, { text: 'Please share the expected return-to-work week when known.' });
  const clinicalMsg = await api(adj, 'POST', `/claims/${claimId}/messages`, { text: 'Is the diagnosis a scaphoid fracture (S62.0)?' });
  const providerDenied = await api(city, 'GET', '/partner/claims');
  check('adjuster collaborates in the portal: own claims, RTW status only, shared task, secure message; clinical wording refused; providers have no claim access',
    pClaims.status === 200 && arr(pClaims.json).some((c) => c.id === claimId) && pDetail.status === 200 && !hasKeyDeep(pDetail.json, 'diagnosis')
    && task.status === 201 && msg.status === 201 && clinicalMsg.status === 422 && providerDenied.status === 403,
    `claims=${arr(pClaims.json).length} rtw=${((pDetail.json.rtw || {}).fitnessStatus) || '-'} clinical → ${clinicalMsg.status}; provider → ${providerDenied.status}`);
  await shot(adj, 'inc4-partner-04-adjuster-claims', `${PARTNER}/#/claims`, 2500);
  await shot(adj, 'inc4-partner-05-claim-collaboration', `${PARTNER}/#/claims/${claimId}`, 2500);
  await shot(nurse, 'inc4-oh-13-case-claim', `${OH}/#/cases/${caseId}/claim`, 2500);

  const head = await session(browser, OH, 'nursinghead1');
  const na = await api(head, 'GET', `/claims/nurse-assignment?from=${plusDays(-90)}&to=${plusDays(1)}`);
  check('every lost-time claim has a nurse case manager within 48 hours', na.status === 200 && na.json.lostTimeClaims > 0,
    `${na.json.assignedWithin48h}/${na.json.lostTimeClaims} = ${na.json.pct}%`);
  await shot(head, 'inc4-oh-14-nurse-assignment', `${OH}/#/claims/nurse-assignment`, 2500);

  // ---------- finance ----------
  const fin = await session(browser, OH, 'finance1');
  const roll = await api(fin, 'GET', `/claims/costs/rollup?groupBy=SUBSIDIARY&from=${plusDays(-365)}&to=${today}`);
  const bva = await api(fin, 'GET', `/claims/costs/budget-vs-actual?year=${new Date().getFullYear()}&groupBy=SUBSIDIARY`);
  const exp = await api(fin, 'POST', '/claims/costs/exports', { month: today.slice(0, 7) });
  const rec = exp.json.id ? await api(fin, 'POST', `/claims/costs/exports/${exp.json.id}/reconcile`, { erpTotal: exp.json.total, erpReference: `ERP-${run}` }) : { status: 0, json: {} };
  const finDenied = await api(fin, 'GET', `/claims/${claimId}`);
  check('finance: costs by category rolled up by subsidiary (small groups suppressed, no names), budget vs actual, ERP export reconciled; no claim detail',
    roll.status === 200 && !hasKeyDeep(roll.json, 'displayName') && bva.status === 200 && (exp.status === 201 || exp.status === 200) && rec.json.status === 'RECONCILED' && finDenied.status === 403,
    `units=${arr(roll.json).length} suppressed=${arr(roll.json).filter((r) => r.suppressed).length}; export rows=${exp.json.rows} → ${rec.json.status}; claim detail → ${finDenied.status}`);
  await shot(fin, 'inc4-oh-15-finance-costs', `${OH}/#/claims/costs`, 2500);

  // ---------- legal ----------
  const legal = await session(browser, OH, 'legal1');
  const hold = await api(legal, 'POST', `/claims/${claimId}/legal-hold`, { reason: 'Notice of intended proceedings received' });
  const lr = await api(legal, 'POST', `/claims/${CLAIM_LITIGATED}/legal-requests`, { type: 'MEDICO_LEGAL_REPORT', questions: 'Is the shoulder condition consistent with the workplace incident?', dueDate: plusDays(14) });
  const opinion = await api(doc, 'POST', `/legal-requests/${lr.json.id}/report`, { opinion: 'On the balance of probabilities the incident caused an aggravation of a pre-existing condition.' });
  const pdf = await legal.evaluate(async (id) => (await fetch(`/bff/api/legal-requests/${id}/reports/1/content`)).status, lr.json.id);
  const claimsDenied = await claims.evaluate(async (id) => (await fetch(`/bff/api/legal-requests/${id}/reports/1/content`)).status, lr.json.id);
  check('legal hold suspends retention of the case documents; medico-legal report stays in the legal access set',
    hold.status === 200 && hold.json.litigationHold === true && opinion.json.status === 'REPORT_READY' && pdf === 200 && claimsDenied === 403,
    `hold=${hold.json.litigationHold} report v${(opinion.json.reports || []).length} legal=${pdf} claims coordinator=${claimsDenied}`);
  await shot(legal, 'inc4-oh-16-legal', `${OH}/#/legal`, 2500);

  // ---------- HR status view + DPO report ----------
  const hr = await session(browser, MGR, 'hr1');
  const hrClaims = await api(hr, 'GET', '/workspace/claims');
  check('HR sees claim status only (no costs, payer, adjuster or clinical detail)', hrClaims.status === 200 && arr(hrClaims.json).length > 0
    && !hasKeyDeep(hrClaims.json, 'paidTotal') && !hasKeyDeep(hrClaims.json, 'payerCode'), `${arr(hrClaims.json).length} claims`);
  await shot(hr, 'inc4-mgr-01-hr-claims', `${MGR}/#/claims`, 2500);
  const dpo = await session(browser, OH, 'dpo1');
  const dr = await api(dpo, 'GET', `/claims/disclosure-report?from=${plusDays(-30)}&to=${plusDays(1)}`);
  check('DPO disclosure report lists every insurer disclosure with consent', dr.status === 200 && arr(dr.json).length > 0, `${arr(dr.json).length} disclosures`);
  await shot(dpo, 'inc4-oh-17-dpo-claim-disclosures', `${OH}/#/claims/disclosures`, 2500);
  await shot(phys, 'inc4-partner-06-shared-documents', `${PARTNER}/#/documents`, 2500);

  await browser.close();
  console.log(log.join('\n'));
  if (log.some((l) => l.startsWith('FAIL'))) process.exit(1);
})().catch((e) => { console.log(log.join('\n')); console.error('E2E FAILED', e); process.exit(1); });
