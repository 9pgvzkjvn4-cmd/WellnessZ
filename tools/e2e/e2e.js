// End-to-end: real Keycloak login via BFF, employee self-referral, manager status-only view.
const { chromium } = require('playwright');
// Usage: npm i -D playwright && node tools/e2e/e2e.js  (with the local stack running – see docs/setup/01-Local-MacBook-Pro.md)
const EMP = process.env.WZ_E2E_EMPLOYEE_URL || 'http://localhost:5173';
const WS = process.env.WZ_E2E_WORKSPACE_URL || 'http://localhost:5174';
const IDP = new RegExp(process.env.WZ_E2E_IDP_HOST || 'localhost:8180');
const OUT = process.env.WZ_E2E_OUT || __dirname + '/shots';
require('fs').mkdirSync(OUT, { recursive: true });
const NEWPW = 'Wz-Local-Demo-2026!';

async function login(page, user) {
  await page.getByRole('button', { name: /Sign in with your company account/ }).click();
  await page.waitForURL(IDP);
  await page.screenshot({ path: `${OUT}/${user}-01-idp.png` });
  await page.fill('#username', user);
  await page.fill('#password', 'ChangeMe!123');
  await page.click('#kc-login');
  if (await page.locator('#password-new').count()) {          // temporary password -> forced change
    await page.fill('#password-new', NEWPW);
    await page.fill('#password-confirm', NEWPW);
    await page.click('[type=submit]');
  }
}

(async () => {
  const browser = await chromium.launch();
  const log = [];
  // ---------- employee ----------
  const emp = await (await browser.newContext({ viewport: { width: 420, height: 860 } })).newPage();
  await emp.goto(EMP + '/');
  await emp.screenshot({ path: `${OUT}/employee-00-landing.png` });
  await login(emp, 'employee1');
  await emp.waitForURL(new RegExp(EMP));
  await emp.waitForTimeout(1500);
  await emp.screenshot({ path: `${OUT}/employee-02-home.png` });
  const cookies = await emp.context().cookies();
  log.push('cookies: ' + cookies.map(c => `${c.name}(httpOnly=${c.httpOnly},sameSite=${c.sameSite})`).join(', '));
  log.push('localStorage keys: ' + await emp.evaluate(() => Object.keys(localStorage).join(',')));
  await emp.goto(EMP + '/#/refer');
  await emp.waitForTimeout(800);
  if (!(await emp.locator('#description').count())) {
    const links = await emp.locator('a').allInnerTexts(); log.push('links: ' + links.join('|'));
  }
  await emp.locator('input[name=category][value=INJURY]').check();
  await emp.fill('#description', 'Twisted ankle on the aircraft steps during turnaround at LHR.');
  await emp.screenshot({ path: `${OUT}/employee-03-referral.png` });
  await emp.getByRole('button', { name: /submit|send/i }).click();
  await emp.waitForTimeout(1500);
  await emp.screenshot({ path: `${OUT}/employee-04-submitted.png` });
  log.push('employee after submit: ' + (await emp.locator('main').innerText()).slice(0, 300).replace(/\n/g, ' / '));
  await emp.goto(EMP + '/#/consents'); await emp.waitForTimeout(1000);
  await emp.screenshot({ path: `${OUT}/employee-05-consents.png` });
  await emp.goto(EMP + '/#/profile'); await emp.waitForTimeout(1000);
  await emp.screenshot({ path: `${OUT}/employee-06-profile.png` });
  log.push('profile: ' + (await emp.locator('main').innerText()).slice(0, 300).replace(/\n/g, ' / '));

  // ---------- manager ----------
  const mgr = await (await browser.newContext({ viewport: { width: 1280, height: 800 } })).newPage();
  await mgr.goto(WS + '/');
  await mgr.screenshot({ path: `${OUT}/manager-00-landing.png` });
  await login(mgr, 'manager1');
  await mgr.waitForURL(new RegExp(WS));
  await mgr.waitForTimeout(2000);
  await mgr.screenshot({ path: `${OUT}/manager-02-team.png` });
  const text = await mgr.locator('main').innerText();
  log.push('manager view: ' + text.slice(0, 400).replace(/\n/g, ' / '));
  log.push('manager sees clinical text? ' + text.includes('ankle'));
  for (const h of ['#/analytics']) { await mgr.goto(WS + '/' + h); await mgr.waitForTimeout(1200);
    await mgr.screenshot({ path: `${OUT}/manager-03-analytics.png` }); }
  // direct API call through BFF from the manager session
  const api = await mgr.evaluate(async () => { const r = await fetch('/bff/api/cases'); return r.status + ' ' + (await r.text()).slice(0, 400); });
  log.push('manager /bff/api/cases: ' + api);
  // ---------- employee cannot use workspace ----------
  const empInWs = emp;
  const ws = await empInWs.evaluate(async () => (await fetch('/bff/api/workspace/cases')).status).catch(e => 'err ' + e);
  log.push('employee -> /bff/api/workspace/cases: ' + ws);
  console.log(log.join('\n'));
  await browser.close();
})().catch(e => { console.error('E2E FAILED', e); process.exit(1); });
