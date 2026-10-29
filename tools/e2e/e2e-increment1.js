// End-to-end for increment 1 (E01 intake, E02 triage queue & SLA) against the running local stack:
// EHS webhook -> auto-created case, employee self-referral, manager referral (status-only), OH workspace queues,
// triage, assignment and escalation, intake operations and configuration screens. Real Keycloak logins via the BFF.
// Usage: npm i -D playwright && node tools/e2e/e2e-increment1.js  (stack running – see docs/setup/01-Local-MacBook-Pro.md)
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
const log = [];
const check = (name, ok, detail = '') => log.push(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' – ' + detail : ''}`);

async function login(page, user) {
  const app = new URL(page.url()).origin;
  await page.getByRole('button', { name: /Sign in with your company account/ }).click();
  await page.waitForURL(new RegExp(new URL(IDP_URL).host));
  // first run: temporary password + forced change; later runs: the changed password
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

/** Calls the core API through the BFF from the signed-in page (session cookie + X-XSRF-TOKEN). */
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

async function shot(page, name, wait = 1200) {
  await page.waitForTimeout(wait);
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: false });
}

async function integrationToken() {
  const r = await fetch(`${IDP_URL}/realms/wellnessz/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'client_credentials', client_id: 'wellnessz-integration', client_secret: INTEGRATION_SECRET }),
  });
  if (!r.ok) throw new Error('integration token ' + r.status);
  return (await r.json()).access_token;
}

function incident(ref, employeeId, incidentClass, extra = {}) {
  return {
    externalRef: ref, occurredAt: new Date(Date.now() - 3600_000).toISOString(), incidentClass, severity: 'MEDIUM', bodyPart: 'HAND',
    location: 'LHR-T5 stand 512', unitCode: 'GND', shift: 'EARLY', employeeId, orgUnitCode: 'GROUND',
    description: 'Hand caught between baggage dolly and belt loader', reporterEmail: 'hse.officer@wellnessz.local', ...extra,
  };
}

(async () => {
  // ---------- EHS system pushes incidents (E01 webhook) ----------
  const token = await integrationToken();
  const run = Date.now().toString(36).toUpperCase();
  const feed = await fetch(`${CORE}/api/v1/intake/ehs/incidents`, {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json', 'Idempotency-Key': 'e2e-' + run },
    body: JSON.stringify({ incidents: [
      incident(`EHS-${run}-1`, 'E1004', 'INJURY_MEDICAL_TREATMENT'),
      incident(`EHS-${run}-2`, 'E1003', 'INJURY_FIRST_AID', { unitCode: 'CAB', orgUnitCode: 'CABIN', bodyPart: 'BACK', description: 'Strained back lifting a bag into the overhead bin' }),
      incident(`EHS-${run}-3`, 'E1004', 'NEAR_MISS'),
      { externalRef: '' },
    ] }),
  });
  const feedBody = await feed.json();
  check('EHS webhook accepted', feed.status === 202, JSON.stringify(feedBody).slice(0, 160));
  await new Promise((r) => setTimeout(r, 4000)); // outbox relay -> intake rules -> case

  const browser = await chromium.launch();

  // ---------- employee self-referral (E01) ----------
  const emp = await (await browser.newContext({ viewport: { width: 420, height: 900 } })).newPage();
  await emp.goto(EMP + '/');
  await login(emp, 'employee1');
  await emp.waitForURL(new RegExp(EMP));
  await shot(emp, 'inc1-employee-01-home', 1500);
  await emp.goto(EMP + '/#/refer');
  await emp.waitForSelector('#rf-template', { timeout: 15000 });
  await emp.selectOption('#rf-template', 'CATERING').catch(() => {});
  await emp.locator('input[name=category][value=INJURY]').check().catch(() => {});
  await emp.locator('button.toggle[data-code="HAND_L"]').click().catch(() => {});
  await emp.waitForTimeout(400);
  await shot(emp, 'inc1-employee-02-referral-template', 300);
  for (const label of ['Burn', 'Pain']) await emp.getByRole('checkbox', { name: label, exact: true }).check().catch(() => {});
  await emp.fill('#rf-description', 'Burnt my left hand on the oven door during the breakfast service.').catch(() => {});
  // template yes/no questions – answer "Yes" (first option) where nothing is selected yet
  const groups = new Set(await emp.locator('input[type=radio]').evaluateAll((els) => els.map((e) => e.name)));
  for (const name of groups) {
    if (!(await emp.locator(`input[type=radio][name="${name}"]:checked`).count())) await emp.locator(`input[type=radio][name="${name}"]`).first().check();
  }
  for (const sel of await emp.locator('form select').all()) { // template questions rendered as selects (Yes / No / Unsure)
    if (!(await sel.inputValue())) await sel.selectOption({ index: 1 });
  }
  await emp.locator('#rf-consent').check().catch(() => {});
  await emp.locator('#rf-description').scrollIntoViewIfNeeded().catch(() => {});
  await shot(emp, 'inc1-employee-03-referral-filled', 300);
  await emp.getByRole('button', { name: /submit|send/i }).last().click();
  await emp.waitForTimeout(2500);
  const empText = await emp.locator('main').innerText();
  const empRef = (empText.match(/[A-Z]{2,5}-\d{4}-\d{6}/) || [])[0];
  check('employee self-referral submitted through the UI', !!empRef, empRef || empText.slice(0, 200).replace(/\n/g, ' / '));
  await shot(emp, 'inc1-employee-04-referral-confirmation', 300);
  await emp.goto(EMP + '/#/fitness-declaration');
  await shot(emp, 'inc1-employee-05-fitness-declaration');
  await emp.goto(EMP + '/#/advice');
  await shot(emp, 'inc1-employee-06-advice');
  const empQueue = await api(emp, 'GET', '/queues/MY');
  check('employee cannot open OH queues', empQueue.status === 403, 'HTTP ' + empQueue.status);

  // ---------- manager referral, clinical-text guard, status-only (E01) ----------
  const mgr = await (await browser.newContext({ viewport: { width: 1280, height: 860 } })).newPage();
  await mgr.goto(MGR + '/');
  await login(mgr, 'manager1');
  await mgr.waitForURL(new RegExp(MGR));
  await mgr.goto(MGR + '/#/refer');
  await shot(mgr, 'inc1-manager-01-referral-form', 1500);
  const blocked = await api(mgr, 'POST', '/intake/referrals/manager',
    { employeeId: 'E1001', dutiesAffected: 'I think she has depression', startDate: new Date().toISOString().slice(0, 10), safetyConcern: false });
  check('manager referral with clinical wording is rejected', blocked.status === 422 && blocked.json.code === 'clinical_content_not_allowed',
    `HTTP ${blocked.status} ${blocked.json.code || ''}`);
  const mref = await api(mgr, 'POST', '/intake/referrals/manager',
    { employeeId: 'E1001', dutiesAffected: 'Cannot lift trolleys above 10 kg on the early shift', startDate: new Date().toISOString().slice(0, 10), safetyConcern: true });
  check('manager referral accepted', mref.status === 201, 'HTTP ' + mref.status);
  const outOfScope = await api(mgr, 'POST', '/intake/referrals/manager', { employeeId: 'E1002', dutiesAffected: 'Missed shifts', safetyConcern: false });
  check('manager cannot refer an employee outside their org scope', outOfScope.status === 404, 'HTTP ' + outOfScope.status);
  await mgr.goto(MGR + '/#/');
  await shot(mgr, 'inc1-manager-02-team-status-only', 2000);
  const mgrText = await mgr.locator('main').innerText();
  check('manager team view carries no clinical text', !/oven|burn|depression/i.test(mgrText));

  // ---------- nursing head: team queue and assignment (E02) ----------
  const head = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  await head.goto(OH + '/');
  await login(head, 'nursinghead1');
  await head.waitForURL(new RegExp(OH));
  await head.goto(OH + '/#/queues');
  await shot(head, 'inc1-oh-01-queues-team', 2500);
  const counts = await api(head, 'GET', '/queues/counts');
  check('queue counts', counts.status === 200, JSON.stringify(counts.json));
  const team = await api(head, 'GET', '/queues/TEAM?size=50');
  const items = (team.json && team.json.items) || [];
  const ehsCase = items.find((c) => c.employeeId === 'E1004') || items.find((c) => /^GND-/.test(c.caseRef || ''));
  check('EHS incident auto-created and routed a case', !!ehsCase,
    ehsCase ? `${ehsCase.caseRef} urgency=${ehsCase.urgency} queue=${ehsCase.queueCode} owner=${ehsCase.ownerUsername}` : JSON.stringify(team.json).slice(0, 200));
  const caseId = ehsCase && (ehsCase.caseId || ehsCase.id);
  if (caseId) {
    const re = await api(head, 'POST', `/cases/${caseId}/assign`, { toUsername: 'nurse1', reason: 'Rebalance caseload' });
    check('nursing head reassigns the case to nurse1', re.status === 200, 'HTTP ' + re.status + ' ' + JSON.stringify(re.json).slice(0, 120));
  }

  // ---------- OH nurse: my queue, case, triage, escalation (E02) ----------
  const oh = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  await oh.goto(OH + '/');
  await login(oh, 'nurse1');
  await oh.waitForURL(new RegExp(OH));
  await oh.goto(OH + '/#/queues');
  await shot(oh, 'inc1-oh-02-queues-my', 2500);
  if (caseId) {
    const id = caseId;
    await oh.goto(`${OH}/#/cases/${encodeURIComponent(id)}/overview`);
    await shot(oh, 'inc1-oh-03-case-overview', 2000);
    await oh.goto(`${OH}/#/cases/${encodeURIComponent(id)}/triage`);
    await shot(oh, 'inc1-oh-04-case-triage', 2000);
    const red = await api(oh, 'POST', `/cases/${id}/triage`,
      { protocolCode: 'MSK', mode: 'IN_PERSON', answers: { q1: true }, overrideUrgency: 'ROUTINE', overrideReason: 'test' });
    check('red-flag answer cannot be downgraded (hard stop)', red.status >= 400, 'HTTP ' + red.status + ' ' + (red.json.code || ''));
    const tri = await api(oh, 'POST', `/cases/${id}/triage`, { protocolCode: 'MSK', mode: 'IN_PERSON', answers: { q1: false, q2: false, q3: 3, q4: true } });
    check('triage protocol recorded', tri.status === 200 || tri.status === 201, `HTTP ${tri.status} ${JSON.stringify(tri.json).slice(0, 140)}`);
    await oh.reload();
    await shot(oh, 'inc1-oh-05-case-triage-result', 2000);
    const esc = await api(oh, 'POST', `/cases/${id}/escalations`, { level: 'PHYSICIAN', reasonCode: 'SECOND_OPINION', note: 'Please review return-to-duty plan' });
    check('escalation raised to physician', esc.status === 201 || esc.status === 200, 'HTTP ' + esc.status);
    for (const tab of ['assignment', 'complexity', 'checklists', 'recordability', 'escalation', 'timeline']) {
      await oh.goto(`${OH}/#/cases/${encodeURIComponent(id)}/${tab}`);
      await shot(oh, `inc1-oh-06-case-${tab}`, 1500);
    }
    const header = await api(oh, 'GET', `/cases/${id}/header`);
    check('case header carries the SLA clock', header.status === 200 && !!header.json.sla, JSON.stringify(header.json.sla || {}));
  }
  for (const [route, name] of [['/intake/suggestions', 'intake-suggestions'],
    ['/intake/reconciliation', 'intake-reconciliation'], ['/escalations', 'escalations'], ['/handovers', 'handovers']]) {
    await oh.goto(OH + '/#' + route);
    await shot(oh, 'inc1-oh-07-' + name, 1800);
  }
  const sugg = await api(oh, 'GET', '/intake/suggestions');
  const suggList = Array.isArray(sugg.json) ? sugg.json : (sugg.json.items || []);
  check('first-aid incident waits as a suggestion for nurse review', sugg.status === 200 && suggList.length > 0,
    JSON.stringify(suggList[0] || sugg.json).slice(0, 200));

  // ---------- nursing head: configuration, caseload, QA, DLQ ----------
  for (const [route, name] of [['/caseload', 'caseload'], ['/triage-audit', 'triage-audit'], ['/admin/protocols', 'admin-protocols'],
    ['/admin/assignment-rules', 'admin-assignment-rules'], ['/admin/sla', 'admin-sla'], ['/admin/intake-rules', 'admin-intake-rules'],
    ['/admin/notifications', 'admin-notifications'], ['/intake/dashboard', 'intake-dashboard']]) {
    await head.goto(OH + '/#' + route);
    await shot(head, 'inc1-oh-08-' + name, 1800);
  }

  // ---------- OH administrator: dead-letter queue of rejected EHS records ----------
  const adm = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  await adm.goto(OH + '/');
  await login(adm, 'admin1');
  await adm.goto(OH + '/#/intake/dlq');
  await shot(adm, 'inc1-oh-08-intake-dlq', 1800);
  const dlq = await api(adm, 'GET', '/intake/dlq');
  check('invalid EHS record is held in the dead-letter queue', dlq.status === 200 && JSON.stringify(dlq.json).length > 2, 'HTTP ' + dlq.status);

  // ---------- clinic reception: walk-in registration without clinical fields ----------
  const rec = await (await browser.newContext({ viewport: { width: 1280, height: 860 } })).newPage();
  await rec.goto(OH + '/');
  await login(rec, 'reception1');
  await rec.waitForURL(new RegExp(OH));
  await shot(rec, 'inc1-oh-09-reception', 2000);
  const recQueue = await api(rec, 'GET', '/queues/TEAM');
  check('reception cannot read clinical queues', recQueue.status === 403, 'HTTP ' + recQueue.status);

  // ---------- crew scheduling: fitness status only ----------
  const sched = await (await browser.newContext()).newPage();
  await sched.goto(OH + '/');
  await login(sched, 'crewsched1');
  await sched.waitForURL(new RegExp(OH));
  const crew = await api(sched, 'GET', '/status/crew?employeeIds=E1002,E1003');
  check('crew scheduling sees fitness status only', crew.status === 200 && !/diagnos|description|triage/i.test(JSON.stringify(crew.json)),
    `HTTP ${crew.status} ${JSON.stringify(crew.json).slice(0, 200)}`);

  await browser.close();
  console.log(log.join('\n'));
  if (log.some((l) => l.startsWith('FAIL'))) process.exit(1);
})().catch((e) => { console.log(log.join('\n')); console.error('E2E FAILED', e); process.exit(1); });
