// Automated security verification of a running WellnessZ stack (local test environment only).
// Each check has an id, the OWASP references it evidences (Top 10 2021, API Security Top 10 2023, ASVS 4.0.3) and
// the SOC 2 trust services criterion it supports. Results are written as JSON for the assessment report.
// Usage: node tools/security/security-verification.js   (stack as in docs/setup/01-Local-MacBook-Pro.md)
// Never point this at an environment you are not authorised to test.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { chromium } = require('playwright');

const CORE = process.env.WZ_SEC_CORE_URL || 'http://localhost:8080';
const CORE_MGMT = process.env.WZ_SEC_CORE_MGMT_URL || 'http://localhost:8081';
const BFF = process.env.WZ_SEC_BFF_URL || 'http://localhost:8090';
const IDP = process.env.WZ_SEC_IDP_URL || 'http://localhost:8180';
const APPS = {
  employee: process.env.WZ_E2E_EMPLOYEE_URL || 'http://localhost:5173',
  manager: process.env.WZ_E2E_WORKSPACE_URL || 'http://localhost:5174',
  oh: process.env.WZ_E2E_OH_URL || 'http://localhost:5175',
  partner: process.env.WZ_E2E_PARTNER_URL || 'http://localhost:5176',
};
const NEWPW = process.env.WZ_E2E_NEW_PASSWORD || 'Wz-Local-Demo-2026!';
const OUT = process.env.WZ_SEC_OUT || path.join(__dirname, 'results');
const ROOT = path.resolve(__dirname, '../..');
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function record(id, area, title, refs, tsc, ok, evidence) {
  results.push({ id, area, title, owasp: refs, tsc, result: ok ? 'PASS' : 'FAIL', evidence: String(evidence).slice(0, 400) });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id} ${title} – ${String(evidence).slice(0, 140)}`);
}

async function http(url, opts = {}) {
  const r = await fetch(url, { redirect: 'manual', ...opts });
  const text = await r.text();
  return { status: r.status, headers: r.headers, text };
}

const b64url = (b) => Buffer.from(b).toString('base64').replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
function forgedJwt(header, claims, key = crypto.randomBytes(32)) {
  const h = b64url(JSON.stringify(header));
  const p = b64url(JSON.stringify(claims));
  const sig = header.alg === 'none' ? '' : b64url(crypto.createHmac('sha256', key).update(`${h}.${p}`).digest());
  return `${h}.${p}.${sig}`;
}

async function login(page, user) {
  const app = new URL(page.url()).origin;
  await page.getByRole('button', { name: /Sign in with (your company account|multi-factor authentication)/ }).click();
  await page.waitForURL(new RegExp(new URL(IDP).host));
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

async function session(browser, url, user) {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto(url + '/');
  await login(page, user);
  return { page, ctx };
}

async function api(page, method, p, body, { xsrf = true, headers = {} } = {}) {
  return page.evaluate(async ([method, p, body, xsrf, extra]) => {
    const t = document.cookie.split(';').map((c) => c.trim().split('=')).find(([k]) => k === 'XSRF-TOKEN');
    const headers = { Accept: 'application/json', ...extra };
    if (xsrf && t) headers['X-XSRF-TOKEN'] = decodeURIComponent(t[1]);
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const r = await fetch('/bff/api' + p, { method, headers, body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)) });
    const text = await r.text();
    return { status: r.status, text, ct: r.headers.get('content-type') || '' };
  }, [method, p, body, xsrf, headers]);
}

const leaks = (t) => /(Exception|at com\.|at org\.|\.java:\d+|SQLState|PSQLException|stacktrace|org\.hibernate)/i.test(t);

(async () => {
  // ---------------------------------------------------------------- A. transport & headers
  for (const [name, url] of [['core API', `${CORE}/api/v1/me`], ['BFF', `${BFF}/bff/api/me`]]) {
    const r = await http(url);
    const h = (k) => r.headers.get(k) || '';
    record(`HDR-${name === 'BFF' ? 'B' : 'C'}01`, 'Security headers', `${name}: Content-Security-Policy denies framing and default sources`, 'A05; ASVS 14.4.3', 'CC6.6',
      /default-src 'none'/.test(h('content-security-policy')) && /frame-ancestors 'none'/.test(h('content-security-policy')), h('content-security-policy'));
    record(`HDR-${name === 'BFF' ? 'B' : 'C'}02`, 'Security headers', `${name}: X-Content-Type-Options nosniff`, 'A05; ASVS 14.4.4', 'CC6.6', h('x-content-type-options') === 'nosniff', h('x-content-type-options'));
    record(`HDR-${name === 'BFF' ? 'B' : 'C'}03`, 'Security headers', `${name}: X-Frame-Options DENY`, 'A05; ASVS 14.4.7', 'CC6.6', /deny/i.test(h('x-frame-options')), h('x-frame-options'));
    record(`HDR-${name === 'BFF' ? 'B' : 'C'}04`, 'Security headers', `${name}: Referrer-Policy restricts referrer`, 'A05; ASVS 14.4.5', 'C1.1', /no-referrer|same-origin/.test(h('referrer-policy')), h('referrer-policy'));
    record(`HDR-${name === 'BFF' ? 'B' : 'C'}05`, 'Security headers', `${name}: responses are not cacheable`, 'A04; ASVS 8.2.1', 'C1.1', /no-store|no-cache/.test(h('cache-control')), h('cache-control'));
    record(`HDR-${name === 'BFF' ? 'B' : 'C'}06`, 'Information disclosure', `${name}: no server/framework banner`, 'A05; ASVS 14.3.3', 'CC6.6', !h('server') && !h('x-powered-by'), `server="${h('server')}" x-powered-by="${h('x-powered-by')}"`);
  }
  for (const app of Object.keys(APPS)) {
    const dir = { employee: 'employee-app', manager: 'manager-workspace', oh: 'oh-workspace', partner: 'partner-portal' }[app];
    const conf = fs.readFileSync(path.join(ROOT, 'web', dir, 'security-headers.conf'), 'utf8');
    const need = ['Content-Security-Policy', 'Strict-Transport-Security', 'X-Content-Type-Options', 'Referrer-Policy', 'Permissions-Policy'];
    const missing = need.filter((k) => !conf.includes(k));
    const csp = (conf.match(/Content-Security-Policy\s+"([^"]+)"/) || [])[1] || '';
    record(`HDR-W-${app}`, 'Security headers', `${dir}: production nginx sets CSP, HSTS, nosniff, Referrer- and Permissions-Policy; no unsafe-eval`, 'A05; ASVS 14.4', 'CC6.6',
      missing.length === 0 && !/unsafe-eval/.test(csp) && /frame-ancestors 'none'/.test(csp), missing.length ? 'missing ' + missing : csp.slice(0, 200));
  }

  // ---------------------------------------------------------------- B. authentication / token validation
  const iss = `${IDP}/realms/wellnessz`;
  const now = Math.floor(Date.now() / 1000);
  const claims = { iss, aud: 'wellnessz-api', sub: 'attacker', preferred_username: 'admin1', realm_access: { roles: ['PLATFORM_ADMIN', 'OH_CLINICIAN', 'CMO'] }, exp: now + 600, iat: now };
  const tokenCases = [
    ['AUTH-01', 'no token', null],
    ['AUTH-02', 'unsigned token (alg none)', forgedJwt({ alg: 'none', typ: 'JWT' }, claims)],
    ['AUTH-03', 'HMAC-signed token with a guessed key (algorithm confusion)', forgedJwt({ alg: 'HS256', typ: 'JWT' }, claims)],
    ['AUTH-04', 'RS256 header with a forged signature', forgedJwt({ alg: 'RS256', typ: 'JWT', kid: 'forged' }, claims)],
    ['AUTH-05', 'token from an untrusted issuer', forgedJwt({ alg: 'HS256' }, { ...claims, iss: 'https://evil.example/realms/wellnessz' })],
    ['AUTH-06', 'expired token', forgedJwt({ alg: 'HS256' }, { ...claims, exp: now - 3600 })],
    ['AUTH-07', 'malformed bearer value', 'not-a-jwt'],
  ];
  for (const [id, label, tok] of tokenCases) {
    const r = await http(`${CORE}/api/v1/me`, { headers: tok ? { Authorization: 'Bearer ' + tok } : {} });
    record(id, 'Authentication', `core API rejects ${label}`, 'A07, A02; API2; ASVS 3.5.3', 'CC6.1', r.status === 401, `HTTP ${r.status}`);
  }
  // every documented operation must require authentication (except health/integration token redemption by design)
  const spec = JSON.parse(fs.readFileSync(path.join(ROOT, 'docs/api/openapi.json'), 'utf8'));
  let total = 0; const open = [];
  for (const [p, ops] of Object.entries(spec.paths)) {
    for (const m of Object.keys(ops)) {
      if (!['get', 'post', 'put', 'patch', 'delete'].includes(m)) continue;
      total++;
      const url = CORE + p.replace(/\{[^}]+\}/g, '00000000-0000-0000-0000-000000000001');
      const r = await http(url, { method: m.toUpperCase(), headers: { 'Content-Type': 'application/json' }, body: m === 'get' || m === 'delete' ? undefined : '{}' });
      if (r.status !== 401 && r.status !== 403) open.push(`${m.toUpperCase()} ${p} -> ${r.status}`);
    }
  }
  record('AUTH-08', 'Authentication', `all ${total} documented API operations refuse anonymous calls`, 'A01, A07; API2, API5; ASVS 4.1.1', 'CC6.1', open.length === 0, open.length ? open.slice(0, 8).join('; ') : `${total}/${total} returned 401/403`);

  // ---------------------------------------------------------------- C. management / exposure
  const act = await http(`${CORE}/actuator/env`);
  record('EXP-01', 'Exposure', 'actuator endpoints are not served on the public API port', 'A05; ASVS 14.3.2', 'CC6.6', act.status === 401 || act.status === 404 || act.status === 403, `HTTP ${act.status}`);
  for (const ep of ['env', 'heapdump', 'threaddump', 'configprops', 'beans', 'loggers', 'shutdown']) {
    const r = await http(`${CORE_MGMT}/actuator/${ep}`);
    record(`EXP-02-${ep}`, 'Exposure', `management port does not expose /actuator/${ep}`, 'A05; ASVS 14.3.2', 'CC6.6', r.status === 404 || r.status === 401 || r.status === 403, `HTTP ${r.status}`);
  }
  const tr = await new Promise((resolve) => {
    const u = new URL(`${CORE}/api/v1/me`);
    const req = require('http').request({ host: u.hostname, port: u.port, path: u.pathname, method: 'TRACE' }, (res) => {
      let text = ''; res.on('data', (c) => { text += c; }); res.on('end', () => resolve({ status: res.statusCode, text }));
    });
    req.on('error', (e) => resolve({ status: 599, text: String(e) }));
    req.end();
  });
  record('EXP-03', 'Exposure', 'HTTP TRACE is not echoed', 'A05; ASVS 14.5.1', 'CC6.6', tr.status >= 400 && !/TRACE \/api/.test(tr.text), `HTTP ${tr.status}`);
  const cors = await http(`${CORE}/api/v1/me`, { method: 'OPTIONS', headers: { Origin: 'https://evil.example', 'Access-Control-Request-Method': 'GET' } });
  record('EXP-04', 'CORS', 'core API does not grant CORS to an arbitrary origin', 'A05; API8; ASVS 14.5.3', 'CC6.6', !(cors.headers.get('access-control-allow-origin') || '').includes('evil'), `ACAO="${cors.headers.get('access-control-allow-origin') || ''}"`);
  const cors2 = await http(`${BFF}/bff/api/me`, { method: 'OPTIONS', headers: { Origin: 'https://evil.example', 'Access-Control-Request-Method': 'GET' } });
  record('EXP-05', 'CORS', 'BFF does not grant CORS to an arbitrary origin', 'A05; API8; ASVS 14.5.3', 'CC6.6', !(cors2.headers.get('access-control-allow-origin') || '').includes('evil'), `ACAO="${cors2.headers.get('access-control-allow-origin') || ''}"`);
  const redir = await http(`${BFF}/bff/login?redirect=https://evil.example/`);
  const loc = redir.headers.get('location') || '';
  record('EXP-06', 'Redirects', 'BFF login does not redirect to an untrusted site', 'A01; ASVS 5.1.5', 'CC6.6', !loc.includes('evil.example'), `HTTP ${redir.status} location=${loc.slice(0, 120)}`);

  // ---------------------------------------------------------------- D. session & CSRF (real login through the BFF)
  const browser = await chromium.launch();
  const nurse = await session(browser, APPS.oh, 'nurse1');
  const cookies = await nurse.ctx.cookies();
  const sess = cookies.find((c) => c.name === 'WZSESSION'); // the BFF session cookie (other localhost cookies belong to the IdP)
  record('SES-01', 'Session', 'session cookie is HttpOnly', 'A07; ASVS 3.4.2', 'CC6.1', !!sess && sess.httpOnly, sess ? `${sess.name} httpOnly=${sess.httpOnly}` : 'no session cookie');
  record('SES-02', 'Session', 'session cookie has SameSite protection', 'A01; ASVS 3.4.3', 'CC6.1', !!sess && /Lax|Strict/.test(sess.sameSite), sess ? `sameSite=${sess.sameSite}` : '-');
  const bffYml = fs.readFileSync(path.join(ROOT, 'apps/wz-bff/src/main/resources/application.yml'), 'utf8');
  record('SES-03', 'Session', 'session cookie is Secure outside the local profile (configuration)', 'A02; ASVS 3.4.1', 'CC6.7', /secure:\s*\$\{[A-Z_]+:true\}|secure:\s*true/.test(bffYml), (bffYml.match(/secure:.*$/m) || ['not found'])[0]);
  const tokenInBrowser = await nurse.page.evaluate(() => JSON.stringify(localStorage) + JSON.stringify(sessionStorage) + document.cookie);
  record('SES-04', 'Session', 'no access or refresh token is exposed to browser JavaScript', 'A07; ASVS 3.5.2', 'CC6.1', !/eyJ[A-Za-z0-9_-]{10,}\./.test(tokenInBrowser), 'storage and readable cookies scanned for JWTs');
  const noCsrf = await api(nurse.page, 'POST', '/queues/views', { name: 'csrf-probe', queue: 'MY' }, { xsrf: false });
  record('CSRF-01', 'CSRF', 'state-changing BFF call without the XSRF token is refused', 'A01; ASVS 4.2.2', 'CC6.1', noCsrf.status === 403, `HTTP ${noCsrf.status}`);
  const badCsrf = await api(nurse.page, 'POST', '/queues/views', { name: 'csrf-probe', queue: 'MY' }, { xsrf: false, headers: { 'X-XSRF-TOKEN': 'forged' } });
  record('CSRF-02', 'CSRF', 'forged XSRF token is refused', 'A01; ASVS 4.2.2', 'CC6.1', badCsrf.status === 403, `HTTP ${badCsrf.status}`);

  // ---------------------------------------------------------------- E. input handling (authenticated)
  const sqli = ["' OR '1'='1", "1'; DROP TABLE casemgmt.oh_case;--", "%' UNION SELECT username,null FROM pg_user--", "\\'; select pg_sleep(5);--"];
  for (const [i, s] of sqli.entries()) {
    const t0 = Date.now();
    const r = await api(nurse.page, 'GET', '/queues/TEAM?q=' + encodeURIComponent(s));
    const ms = Date.now() - t0;
    record(`INJ-0${i + 1}`, 'Injection', `SQL injection payload ${i + 1} in queue search is treated as data`, 'A03; API8; ASVS 5.3.4', 'PI1.2',
      r.status < 500 && !leaks(r.text) && ms < 4500 && !/pg_user|usename/.test(r.text), `HTTP ${r.status} in ${ms} ms`);
  }
  const malformed = await api(nurse.page, 'POST', '/queues/views', '{"name": "x", ', {});
  record('INJ-05', 'Input validation', 'malformed JSON returns 400 without internal detail', 'A04, A05; ASVS 7.4.1', 'PI1.2', malformed.status === 400 && !leaks(malformed.text), `HTTP ${malformed.status} ${malformed.text.slice(0, 100)}`);
  const badType = await api(nurse.page, 'GET', '/cases/not-a-uuid/tasks');
  record('INJ-06', 'Input validation', 'mistyped path parameter returns 400/404 without internal detail', 'A04, A05; ASVS 7.4.1', 'PI1.2', (badType.status === 400 || badType.status === 404) && !leaks(badType.text), `HTTP ${badType.status}`);
  const trav = await api(nurse.page, 'GET', '/documents/..%2f..%2f..%2fetc%2fpasswd/content');
  record('INJ-07', 'Path traversal', 'path traversal in a document id is rejected', 'A01, A03; ASVS 12.3.1', 'C1.1', trav.status >= 400 && trav.status < 500 && !/root:x:/.test(trav.text), `HTTP ${trav.status}`);
  const big = await api(nurse.page, 'POST', '/queues/views', { name: 'x'.repeat(2_000_000), queue: 'MY' });
  record('INJ-08', 'Resource consumption', 'oversized request body is refused without a server error', 'A04; API4; ASVS 13.1.3', 'A1.1', big.status === 400 || big.status === 413 || big.status === 422, `HTTP ${big.status}`);
  const xml = await api(nurse.page, 'POST', '/queues/views', '<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><x>&e;</x>', { headers: { 'Content-Type': 'application/xml' } });
  record('INJ-09', 'XXE', 'XML entity payload is not processed by the JSON API', 'A05; ASVS 5.5.2', 'PI1.2', xml.status >= 400 && xml.status < 500 && !/root:x:/.test(xml.text), `HTTP ${xml.status}`);

  // ---------------------------------------------------------------- F. access control spot checks (full matrix in rbac-matrix.js)
  const emp = await session(browser, APPS.employee, 'employee1');
  const empQueue = await api(emp.page, 'GET', '/queues/TEAM');
  record('AC-01', 'Access control', 'an employee cannot read the OH case queue', 'A01; API1, API5; ASVS 4.1.3', 'CC6.3', empQueue.status === 403 || empQueue.status === 404, `HTTP ${empQueue.status}`);
  const empAdmin = await api(emp.page, 'GET', '/intake/rules');
  record('AC-02', 'Access control', 'an employee cannot reach administration functions', 'A01; API5; ASVS 4.1.3', 'CC6.3', empAdmin.status === 403 || empAdmin.status === 404, `HTTP ${empAdmin.status}`);
  const mgr = await session(browser, APPS.manager, 'manager1');
  const mgrSupport = await api(mgr.page, 'GET', '/support/cases');
  record('AC-03', 'Restricted class', 'a line manager cannot list mental-health support cases', 'A01; API1; ASVS 4.2.1', 'C1.1, P6', mgrSupport.status === 403 || mgrSupport.status === 404, `HTTP ${mgrSupport.status}`);
  const nurseSupport = await api(nurse.page, 'GET', '/support/cases');
  record('AC-04', 'Restricted class', 'a general OH nurse cannot list mental-health support cases', 'A01; API1; ASVS 4.2.1', 'C1.1, P6', nurseSupport.status === 403 || nurseSupport.status === 404, `HTTP ${nurseSupport.status}`);
  const nurseMro = await api(nurse.page, 'GET', '/testing/mro/queue');
  record('AC-05', 'Restricted class', 'a general OH nurse cannot open the MRO result queue', 'A01; API1; ASVS 4.2.1', 'C1.1', nurseMro.status === 403 || nurseMro.status === 404, `HTTP ${nurseMro.status}`);
  const partner = await session(browser, APPS.partner, 'partner1');
  const partnerCases = await api(partner.page, 'GET', '/queues/TEAM');
  record('AC-06', 'Access control', 'a partner organisation user cannot read internal case queues', 'A01; API1, API5; ASVS 4.1.3', 'CC6.3', partnerCases.status === 403 || partnerCases.status === 404, `HTTP ${partnerCases.status}`);
  const mass = await api(emp.page, 'PUT', '/me/support/sharing', { scope: 'FITNESS_ONLY', employeeId: 'E9999', classification: 'PUBLIC' });
  record('AC-07', 'Mass assignment', 'unexpected fields (owner, classification) are ignored or rejected', 'A01, A08; API3; ASVS 5.1.2', 'PI1.2', mass.status < 500 && !/E9999/.test(mass.text), `HTTP ${mass.status}`);

  // ---------------------------------------------------------------- G. resource consumption (per-principal rate limit)
  const burst = await nurse.page.evaluate(async () => {
    const codes = await Promise.all(Array.from({ length: 220 }, () => fetch('/bff/api/me', { headers: { Accept: 'application/json' } }).then((r) => r.status)));
    return codes.reduce((m, c) => ({ ...m, [c]: (m[c] || 0) + 1 }), {});
  });
  record('RATE-01', 'Resource consumption', 'per-user API rate limit answers 429 once the burst allowance is exhausted', 'A04; API4; ASVS 11.1.4', 'A1.1, CC6.6',
    (burst['429'] || 0) > 0 && (burst['200'] || 0) > 0 && !burst['500'], JSON.stringify(burst));
  await new Promise((r) => setTimeout(r, 15000)); // let the bucket refill before the remaining checks

  // ---------------------------------------------------------------- H. logout
  await nurse.page.evaluate(async () => {
    const t = document.cookie.split(';').map((c) => c.trim().split('=')).find(([k]) => k === 'XSRF-TOKEN');
    await fetch('/logout', { method: 'POST', headers: t ? { 'X-XSRF-TOKEN': decodeURIComponent(t[1]) } : {}, redirect: 'manual' });
  });
  const after = await api(nurse.page, 'GET', '/me');
  record('SES-05', 'Session', 'session is invalidated on logout', 'A07; ASVS 3.3.1', 'CC6.1', after.status === 401 || after.status === 403, `HTTP ${after.status}`);

  await browser.close();
  const pass = results.filter((r) => r.result === 'PASS').length;
  fs.writeFileSync(path.join(OUT, 'security-verification.json'), JSON.stringify({ executedAt: new Date().toISOString(), total: results.length, pass, fail: results.length - pass, results }, null, 1));
  console.log(`\n${pass}/${results.length} checks passed`);
  process.exit(pass === results.length ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(2); });
