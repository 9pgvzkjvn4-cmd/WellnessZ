// Role-based access-control matrix for the four client applications, run against a local stack.
//
// Every human demo user of the local realm signs in once through the BFF (real Keycloak login, as in tools/e2e) and
// calls every GET endpoint of docs/api/openapi.json that has no path parameter through /bff/api/**. A curated set of
// object-level checks then uses real ids, read from the responses of permitted users, and calls them as every other
// user. Each status is compared with the expected policy extracted statically from the controllers (@PreAuthorize,
// URL rules, BFF workspace gating). Unauthenticated calls are made to every operation, through the BFF and directly
// against the core API. Only GET requests are sent with a session; unauthenticated writes never reach a handler.
//
// Usage:
//   node tools/security/rbac-matrix.js --policy <expected-policy.json> --out <dir>
// Environment (defaults match docs/setup/01-Local-MacBook-Pro.md):
//   WZ_E2E_EMPLOYEE_URL / WZ_E2E_WORKSPACE_URL / WZ_E2E_OH_URL / WZ_E2E_PARTNER_URL   (5173 / 5174 / 5175 / 5176)
//   WZ_E2E_IDP_URL (http://localhost:8180)  WZ_E2E_CORE_URL (http://localhost:8080)  WZ_E2E_BFF_URL (http://localhost:8090)
//   WZ_E2E_NEW_PASSWORD  WZ_RBAC_USERS (comma-separated subset)  WZ_RBAC_CONCURRENCY (6)
// Output: <out>/rbac-results.json
'use strict';
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const { chromium } = require('playwright');

const REPO = path.resolve(__dirname, '..', '..');
const arg = (name, dflt) => {
  const i = process.argv.indexOf('--' + name);
  return i > 0 ? process.argv[i + 1] : dflt;
};
const POLICY_FILE = arg('policy', process.env.WZ_RBAC_POLICY);
const OUT = arg('out', process.env.WZ_RBAC_OUT || path.join(__dirname, 'out'));
if (!POLICY_FILE) {
  console.error('usage: node tools/security/rbac-matrix.js --policy <expected-policy.json> [--out <dir>]');
  process.exit(2);
}
const APPS = {
  'employee-app': process.env.WZ_E2E_EMPLOYEE_URL || 'http://localhost:5173',
  'manager-workspace': process.env.WZ_E2E_WORKSPACE_URL || 'http://localhost:5174',
  'oh-workspace': process.env.WZ_E2E_OH_URL || 'http://localhost:5175',
  'partner-portal': process.env.WZ_E2E_PARTNER_URL || 'http://localhost:5176',
};
const IDP_URL = process.env.WZ_E2E_IDP_URL || 'http://localhost:8180';
const CORE = process.env.WZ_E2E_CORE_URL || 'http://localhost:8080';
const BFF = process.env.WZ_E2E_BFF_URL || 'http://localhost:8090';
const NEWPW = process.env.WZ_E2E_NEW_PASSWORD || 'Wz-Local-Demo-2026!';
const CONCURRENCY = Number(process.env.WZ_RBAC_CONCURRENCY || 6);
const TODAY = new Date().toISOString().slice(0, 10);
const MONTH = TODAY.slice(0, 7);
const ZERO_UUID = '00000000-0000-4000-8000-000000000000';

const policy = JSON.parse(fs.readFileSync(POLICY_FILE, 'utf8'));
const openapi = JSON.parse(fs.readFileSync(path.join(REPO, 'docs/api/openapi.json'), 'utf8'));
const realm = JSON.parse(fs.readFileSync(path.join(REPO, 'deploy/local/keycloak/wellnessz-realm.json'), 'utf8'));
const NON_ROLES = new Set(['default-roles-wellnessz', 'offline_access', 'uma_authorization']);
const USERS = realm.users
  .filter((u) => !u.username.startsWith('service-account-'))
  .filter((u) => !process.env.WZ_RBAC_USERS || process.env.WZ_RBAC_USERS.split(',').includes(u.username))
  .map((u) => ({
    username: u.username,
    roles: (u.realmRoles || []).filter((r) => !NON_ROLES.has(r)),
    employeeId: (u.attributes?.employee_id || [])[0],
    orgScope: (u.attributes?.org_scope || [])[0],
  }));

// ------------------------------------------------------------------------------------------- expected policy
const norm = (p) => p.replace(/\{[^}]+\}/g, '{}');
const policyIndex = new Map(policy.endpoints.map((e) => [e.method + ' ' + norm(e.path), e]));
const WORKSPACE_ROLES = policy.workspaceRoles;

/** Expected outcome of `method corePath` for user u: {allow, basis, scoped}. */
function expectation(method, corePath, u) {
  const e = policyIndex.get(method + ' ' + norm(corePath));
  if (!e) return { allow: null, basis: 'no static policy found', scoped: false, policy: null };
  const rel = corePath.slice('/api/v1'.length);
  const viaWorkspace = rel.startsWith('/workspace/');
  if (viaWorkspace && !u.roles.some((r) => WORKSPACE_ROLES.includes(r))) {
    return { allow: false, basis: 'BFF /bff/api/workspace/** requires ' + WORKSPACE_ROLES.join(','), scoped: false, policy: e };
  }
  const allowed = e.coreAllowed;
  if (allowed === 'ANY_AUTHENTICATED') {
    return { allow: true, basis: 'any authenticated user' + (e.dataScope.length ? '; ' + e.dataScope.join('; ') : '; data scope in service'),
      scoped: true, policy: e };
  }
  if (allowed === 'PERMIT_ALL') return { allow: true, basis: 'permitAll', scoped: false, policy: e };
  const hit = u.roles.filter((r) => allowed.includes(r));
  return hit.length
    ? { allow: true, basis: 'role ' + hit.join(',') + ' in ' + allowed.join(','), scoped: e.dataScope.length > 0, policy: e }
    : { allow: false, basis: 'requires one of ' + allowed.join(','), scoped: false, policy: e };
}

// ------------------------------------------------------------------------------------------- request helpers
function sampleValue(name, schema = {}) {
  const fixed = { employeeId: 'E1001', employeeIds: 'E1001', q: 'Erin', destinations: 'KE', system: 'ICD_10', code: 'J06.9',
    siteCode: 'LHR-OH', month: MONTH, year: 2026, type: schema.pattern?.startsWith('CLASS1') ? 'CLASS1' : 'PERIODIC' };
  if (name in fixed) return fixed[name];
  if (schema.format === 'uuid') return ZERO_UUID;
  if (schema.format === 'date') return TODAY;
  if (schema.format === 'date-time') return new Date().toISOString();
  if (schema.enum) return schema.enum[0];
  if (schema.type === 'integer' || schema.type === 'number') return schema.minimum ?? 1;
  if (schema.type === 'boolean') return false;
  return 'x';
}

function withQuery(p, op) {
  const req = (op.parameters || []).filter((x) => x.required && x.in === 'query');
  if (!req.length) return p;
  return p + '?' + req.map((x) => encodeURIComponent(x.name) + '=' + encodeURIComponent(sampleValue(x.name, x.schema))).join('&');
}

const toBff = (corePath) => '/bff/api' + corePath.slice('/api/v1'.length);

async function get(ctx, base, p) {
  const t0 = Date.now();
  try {
    const r = await ctx.request.get(base + p, { headers: { Accept: 'application/json' }, maxRedirects: 0, timeout: 30000 });
    const body = await r.body();
    let json;
    try { json = JSON.parse(body.toString('utf8')); } catch { json = undefined; }
    return { status: r.status(), bytes: body.length, json, ms: Date.now() - t0, location: r.headers().location };
  } catch (err) {
    return { status: 0, bytes: 0, error: String(err.message || err).slice(0, 200), ms: Date.now() - t0 };
  }
}

/** Does the body carry data (anything other than an empty list / empty object / error document)? */
function hasData(res) {
  const j = res.json;
  if (res.status < 200 || res.status >= 300) return false;
  if (j === undefined) return res.bytes > 0;
  if (Array.isArray(j)) return j.length > 0;
  if (j && typeof j === 'object') {
    const vals = Object.values(j);
    if (!vals.length) return false;
    const arrays = vals.filter(Array.isArray);
    if (arrays.length === vals.length) return arrays.some((a) => a.length);
    return true;
  }
  return j !== null && j !== '';
}

async function pool(items, n, fn) {
  const out = new Array(items.length);
  let i = 0;
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, async () => {
    while (i < items.length) {
      const k = i++;
      out[k] = await fn(items[k], k);
    }
  }));
  return out;
}

// ------------------------------------------------------------------------------------------- login
function primaryApp(u) {
  if (u.roles.some((r) => r === 'PARTNER_PROVIDER' || r === 'TPA_ADJUSTER')) return 'partner-portal';
  if (u.roles.every((r) => r === 'EMPLOYEE' || r === 'CANDIDATE')) return 'employee-app';
  if (u.roles.some((r) => ['MANAGER', 'HR', 'SAFETY_MANAGER', 'CREW_SCHEDULING'].includes(r))) return 'manager-workspace';
  return 'oh-workspace';
}

async function login(browser, u) {
  const app = APPS[primaryApp(u)];
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto(app + '/oauth2/authorization/wellnessz');
  await page.waitForURL(new RegExp(new URL(IDP_URL).host), { timeout: 20000 });
  for (const pw of ['ChangeMe!123', NEWPW]) {
    await page.fill('#username', u.username);
    await page.fill('#password', pw);
    await page.click('#kc-login');
    await page.waitForLoadState('networkidle');
    if (await page.locator('#password-new').count()) {
      await page.fill('#password-new', NEWPW);
      await page.fill('#password-confirm', NEWPW);
      await page.click('[type=submit]');
      await page.waitForLoadState('networkidle');
    }
    if (page.url().startsWith(app)) break;
  }
  await page.waitForURL((x) => x.href.startsWith(app), { timeout: 20000 });
  await page.close();
  const session = await get(ctx, app, '/bff/session');
  return { ctx, app, session: session.json };
}

// ------------------------------------------------------------------------------------------- workspace gating
function ohWorkspaceRoles() {
  try {
    const out = execFileSync(process.execPath, ['--experimental-strip-types', '--no-warnings', '-e',
      `import(${JSON.stringify(path.join(REPO, 'web/oh-workspace/src/auth/roles.ts'))}).then((m) =>
        console.log(JSON.stringify([...new Set(Object.values(m.FEATURES).flat())].sort())))`], { encoding: 'utf8' });
    return JSON.parse(out.trim());
  } catch {
    return null;
  }
}
const APP_ROLES = {
  'employee-app': { roles: 'ANY_AUTHENTICATED', rule: 'self-service for any signed-in user; candidates see pre-employment only' },
  'manager-workspace': { roles: WORKSPACE_ROLES, rule: 'manager-workspace hasWorkspaceAccess = BFF WORKSPACE_ROLES' },
  'oh-workspace': { roles: ohWorkspaceRoles(), rule: 'oh-workspace hasWorkspaceAccess = union of FEATURES roles' },
  'partner-portal': { roles: ['PARTNER_PROVIDER', 'TPA_ADJUSTER'], rule: 'partner-portal resolveAccess = partner role in /me' },
};
const DENIED_TEXT = /Access restricted|Partner access not available|This portal is for WellnessZ partner organisations/i;

async function uiGate(ctx, appName) {
  const page = await ctx.newPage();
  try {
    await page.goto(APPS[appName] + '/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1200);
    const text = await page.locator('body').innerText();
    const signIn = /Sign in with/i.test(text) && !/Sign out/i.test(text);
    return { state: DENIED_TEXT.test(text) ? 'RESTRICTED' : signIn ? 'SIGN-IN PAGE' : 'ACCESSIBLE', excerpt: text.replace(/\s+/g, ' ').slice(0, 140) };
  } finally {
    await page.close();
  }
}

// ------------------------------------------------------------------------------------------- object-level tests
const hasAny = (u, ...roles) => u.roles.some((r) => roles.includes(r));
const OH_CLINICAL = ['OH_CLINICIAN', 'CASE_MANAGER', 'OH_PHYSICIAN', 'AME', 'CMO', 'NURSING_HEAD'];
const inScope = (u, orgPath) => !!u.orgScope && !!orgPath && (orgPath === u.orgScope || orgPath.startsWith(u.orgScope + '/'));
/** CaseAccess / DefaultPolicies: self, psych, OH clinical (non-restricted), manager/HR in org scope (status only). */
function caseDecision(u, c, minimum) {
  const order = ['NONE', 'STATUS_ONLY', 'SELF', 'CLINICAL', 'RESTRICTED_CLINICAL'];
  const restricted = c.classification === 'RESTRICTED_CLINICAL';
  let best = 'NONE';
  const up = (lvl) => { if (order.indexOf(lvl) > order.indexOf(best)) best = lvl; };
  if (u.employeeId && u.employeeId === c.employeeId) up('SELF');
  if (hasAny(u, 'PSYCH_CLINICIAN')) up('RESTRICTED_CLINICAL');
  if (hasAny(u, ...OH_CLINICAL) && !restricted) up('CLINICAL');
  if (hasAny(u, 'MANAGER', 'HR') && inScope(u, c.orgPath)) up('STATUS_ONLY');
  return { level: best, allow: order.indexOf(best) >= order.indexOf(minimum) && best !== 'NONE' };
}

async function discoverObjects(sessions, byName) {
  const g = (name, p) => get(sessions[name].ctx, sessions[name].app, '/bff/api' + p);
  const objs = [];
  const orgUnits = (await g('clinician1', '/masterdata/org-units')).json || [];
  const orgPath = (code) => (orgUnits.find((o) => o.code === code) || {}).path;
  // employee1's own case + an out-of-scope (ground) case, read by the OH clinician
  const ohCases = (await g('clinician1', '/cases?limit=500')).json || [];
  const caseInfo = (c) => ({ id: c.id, ref: c.caseRef, employeeId: c.employeeId, classification: c.classification || 'CLINICAL',
    orgPath: orgPath(c.orgUnitCode) || orgPath(c.departmentCode) });
  const own = ohCases.find((c) => c.employeeId === byName.employee1.employeeId);
  const ground = ohCases.find((c) => (caseInfo(c).orgPath || '').startsWith('/AIRLINE/GROUND'));
  const restricted = ((await g('psych1', '/cases?limit=500')).json || []).find((c) => c.classification === 'RESTRICTED_CLINICAL');
  for (const [label, c] of [['employee1 case (/AIRLINE/CATERING)', own], ['out-of-scope ground case', ground], ['restricted-class case', restricted]]) {
    if (!c) continue;
    const info = caseInfo(c);
    for (const [suffix, minimum, where] of [['', 'STATUS_ONLY', 'CaseService.get → CaseAccess.decide (404 when denied)'],
      ['/status', 'STATUS_ONLY', 'CaseService.status → CaseAccess.require(STATUS_ONLY)'],
      ['/timeline', 'CLINICAL', 'CaseWorkService.timeline → CaseAccess.clinical']]) {
      objs.push({ object: 'Case ' + info.ref, label, path: `/cases/${info.id}${suffix}`, owner: info.employeeId, codeRef: where,
        expect: (u) => {
          const d = caseDecision(u, info, minimum);
          return { allow: d.allow, level: d.level, rule: `ABAC: self / psych / OH clinical (non-restricted) / MANAGER-HR in org scope ${info.orgPath || '?'}; minimum ${minimum}` };
        } });
    }
  }
  // clinical chart and results of employee1 (EmployeeRecordAccess.requireClinical, minimum CLINICAL – self is SELF)
  for (const sub of ['chart', 'results']) {
    const own1 = byName.employee1;
    objs.push({ object: `Employee ${own1.employeeId} ${sub}`, label: 'clinical record', path: `/employees/${own1.employeeId}/${sub}`, owner: own1.employeeId,
      codeRef: 'RecordGuard.read → EmployeeRecordAccess.requireClinical(CLINICAL)',
      expect: (u) => ({ allow: hasAny(u, 'PSYCH_CLINICIAN', ...OH_CLINICAL), rule: 'OH clinical roles + psych (self has SELF < CLINICAL; employees use /me/*)' }) });
  }
  // E09 support case (restricted class): named mental-health clinician only
  const sup = ((await g('psych1', '/support/cases')).json || [])[0];
  if (sup) {
    for (const suffix of ['', '/consents']) {
      objs.push({ object: 'Support case ' + sup.ref, label: 'restricted class (E09)', path: `/support/cases/${sup.id}${suffix}`, owner: sup.employeeId,
        codeRef: 'SupportCaseService.require → SupportAccess.canRead (named PSYCH_CLINICIAN or break-glass)',
        expect: (u) => ({ allow: hasAny(u, 'PSYCH_CLINICIAN'), rule: 'named PSYCH_CLINICIAN only (no break-glass grants in demo data)' }) });
    }
  }
  // E08 claim: staff readers and TPA payer binding
  const claimList = (await g('claims1', '/claims')).json || [];
  const adjClaims = (await g('adjuster1', '/partner/claims')).json || [];
  const claim = claimList.find((c) => adjClaims.some((a) => a.id === c.id)) || claimList[0];
  if (claim) {
    const detail = (await g('claims1', '/claims/' + claim.id)).json || {};
    const nurse = detail.nurseUsername || detail.claim?.nurseUsername;
    const legal = !!(detail.litigationHold || detail.claim?.litigationHold || detail.privileged || detail.claim?.privileged);
    objs.push({ object: 'Claim ' + claim.claimRef, label: 'claims staff view', path: '/claims/' + claim.id, owner: claim.employeeId,
      codeRef: 'ClaimsController.get @PreAuthorize + ClaimAccess.staffReader',
      expect: (u) => ({ allow: hasAny(u, 'CLAIMS_COORDINATOR', 'NURSING_HEAD', 'CMO', 'CASE_MANAGER') || u.username === nurse || (hasAny(u, 'LEGAL_COUNSEL') && legal),
        rule: `CLAIMS_COORDINATOR, NURSING_HEAD, CMO, CASE_MANAGER, assigned nurse (${nurse || 'none'}), LEGAL_COUNSEL only if litigated (${legal})` }) });
    const payer = detail.payerCode || detail.claim?.payerCode || claim.payerCode;
    objs.push({ object: 'Claim ' + claim.claimRef, label: 'TPA partner view', path: '/partner/claims/' + claim.id, owner: claim.employeeId,
      codeRef: 'ClaimCollaborationController.partnerClaim + ClaimAccess.isPayerOrg',
      expect: (u) => ({ allow: hasAny(u, 'TPA_ADJUSTER') && u.orgScope === '/PARTNERS/' + payer, rule: `TPA_ADJUSTER of payer ${payer}` }) });
    objs.push({ object: 'Claim ' + claim.claimRef, label: 'claim costs', path: `/claims/${claim.id}/costs`, owner: claim.employeeId,
      codeRef: 'ClaimsController costs @PreAuthorize', expect: (u) => ({ allow: hasAny(u, 'CLAIMS_COORDINATOR', 'FINANCE'), rule: 'CLAIMS_COORDINATOR, FINANCE' }) });
  }
  // E04 partner referral: provider organisation binding
  for (const prov of ['physio1', 'partner1']) {
    const r = ((await g(prov, '/partner/referrals')).json || [])[0];
    if (!r) continue;
    const org = byName[prov].orgScope;
    objs.push({ object: 'Partner referral ' + r.ref, label: 'provider binding ' + org, path: '/partner/referrals/' + r.id, owner: r.patient?.employeeId,
      codeRef: 'PartnerController referral detail (provider organisation of the token)',
      expect: (u) => ({ allow: hasAny(u, 'PARTNER_PROVIDER') && u.orgScope === org, rule: `PARTNER_PROVIDER of ${org}` }) });
  }
  // E10 return-to-duty plan (test result follow-up): MRO / OH physician
  const rtd = ((await g('mro1', '/testing/rtd-plans')).json || [])[0];
  if (rtd) {
    objs.push({ object: 'Return-to-duty plan ' + rtd.ref, label: 'drug & alcohol (E10)', path: '/testing/rtd-plans/' + rtd.id, owner: rtd.employeeId,
      codeRef: 'TestingController rtd-plans/{id} @PreAuthorize', expect: (u) => ({ allow: hasAny(u, 'MRO', 'OH_PHYSICIAN'), rule: 'MRO, OH_PHYSICIAN' }) });
  }
  // E10 laboratory result (MRO queue) – specimen chain of custody
  const mro = ((await g('mro1', '/testing/mro/queue')).json || [])[0];
  if (mro?.specimenId) {
    objs.push({ object: 'Specimen ' + mro.specimenId, label: 'chain of custody (E10)', path: `/testing/specimens/${encodeURIComponent(mro.specimenId)}/custody`,
      owner: mro.employeeId, codeRef: 'TestingController specimens/{id}/custody @PreAuthorize',
      expect: (u) => ({ allow: hasAny(u, 'COLLECTOR', 'DA_PROGRAMME_MANAGER', 'MRO'), rule: 'COLLECTOR, DA_PROGRAMME_MANAGER, MRO' }) });
  }
  return objs;
}

// ------------------------------------------------------------------------------------------- main
(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const started = new Date().toISOString();
  const ops = Object.entries(openapi.paths).flatMap(([p, v]) => Object.entries(v)
    .filter(([m]) => ['get', 'post', 'put', 'patch', 'delete'].includes(m)).map(([m, op]) => ({ method: m.toUpperCase(), path: p, op })));
  const getOps = ops.filter((o) => o.method === 'GET' && !o.path.includes('{') && o.path.startsWith('/api/v1/'));
  const browser = await chromium.launch();

  // 1. unauthenticated: every operation through the BFF (with a valid XSRF token, so authentication is what is tested)
  //    and directly against the core API without a bearer token
  const anon = await browser.newContext();
  await get(anon, BFF, '/bff/session');
  const xsrf = (await anon.cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value;
  const unauth = await pool(ops, 12, async (o) => {
    const concrete = o.path.replace(/\{[^}]+\}/g, (m) => (/id\b|Id\}/i.test(m) ? ZERO_UUID : 'X1'));
    const target = withQuery(concrete, o.op);
    const res = {};
    for (const [via, base, p] of [['BFF', BFF, toBff(target)], ['core', CORE, target]]) {
      try {
        const r = await anon.request.fetch(base + p, { method: o.method, maxRedirects: 0, timeout: 30000,
          headers: { Accept: 'application/json', ...(via === 'BFF' && xsrf ? { 'X-XSRF-TOKEN': xsrf } : {}) } });
        const body = await r.body();
        res[via] = { status: r.status(), bytes: body.length, location: r.headers().location };
      } catch (err) {
        res[via] = { status: 0, error: String(err.message || err).slice(0, 120) };
      }
    }
    return { method: o.method, path: o.path, tested: target, bff: res.BFF, core: res.core };
  });
  await anon.close();
  console.log(`unauthenticated: ${unauth.length} operations`);

  // 2. object discovery with the permitted users' own sessions
  const byName = Object.fromEntries(USERS.map((u) => [u.username, u]));
  const sessions = {};
  const ensure = async (u) => {
    const s = sessions[u.username];
    if (s?.ctx && (await get(s.ctx, s.app, '/bff/session')).json?.authenticated) return s;
    if (s?.ctx) await s.ctx.close();
    try {
      sessions[u.username] = await login(browser, u);
    } catch (err) {
      sessions[u.username] = { error: String(err.message || err).slice(0, 200) };
      console.log('LOGIN FAILED', u.username, err.message);
    }
    return sessions[u.username];
  };
  for (const n of ['clinician1', 'psych1', 'claims1', 'adjuster1', 'physio1', 'partner1', 'mro1']) {
    if (byName[n]) await ensure(byName[n]);
  }
  const objects = await discoverObjects(sessions, byName);
  for (const s of Object.values(sessions)) await s.ctx?.close();
  for (const k of Object.keys(sessions)) delete sessions[k];
  console.log(`object-level: ${objects.length} objects discovered`);

  // 3. per user (signed in once): role matrix, object-level checks, workspace gating
  const results = [];
  const objectResults = [];
  const gating = [];
  await pool(USERS, Number(process.env.WZ_RBAC_PARALLEL_USERS || 3), async (u) => {
    const s = await ensure(u);
    if (!s?.ctx) return;
    const me = await get(s.ctx, s.app, '/bff/api/me');
    u.effectiveRoles = me.json?.roles || [];
    // role matrix: every GET without path parameters
    const rows = await pool(getOps, CONCURRENCY, async (o) => {
      const exp = expectation('GET', o.path, u);
      const tested = withQuery(o.path, o.op);
      const res = await get(s.ctx, s.app, toBff(tested));
      return { user: u.username, roles: u.roles, app: primaryApp(u), method: 'GET', path: o.path, tested: toBff(tested),
        expectedAllow: exp.allow, basis: exp.basis, scoped: exp.scoped, status: res.status, bytes: res.bytes, hasData: hasData(res),
        errorCode: res.json && typeof res.json === 'object' && !Array.isArray(res.json) ? (res.json.code || res.json.error || undefined) : undefined,
        sample: res.status >= 200 && res.status < 300 && res.json !== undefined ? JSON.stringify(res.json).slice(0, 160) : undefined,
        ms: res.ms, error: res.error };
    });
    results.push(...rows);
    // object-level authorisation
    for (const o of objects) {
      const exp = o.expect(u);
      const res = await get(s.ctx, s.app, '/bff/api' + o.path);
      const disclosure = res.json && typeof res.json === 'object' && !Array.isArray(res.json) ? res.json.disclosure : undefined;
      objectResults.push({ object: o.object, label: o.label, path: '/bff/api' + o.path, owner: o.owner, codeRef: o.codeRef,
        user: u.username, roles: u.roles, expectedAllow: exp.allow, expectedLevel: exp.level, rule: exp.rule,
        status: res.status, hasData: hasData(res), disclosure, bytes: res.bytes });
    }
    // workspace gating: BFF gate (workspace alias of /me) and the four apps' entry decision
    const alias = await get(s.ctx, s.app, '/bff/api/workspace/me');
    const row = { user: u.username, roles: u.roles, sessionRoles: s.session?.roles, coreRoles: u.effectiveRoles,
      bffWorkspaceExpected: u.roles.some((r) => WORKSPACE_ROLES.includes(r)), bffWorkspaceStatus: alias.status, apps: {} };
    for (const appName of Object.keys(APPS)) {
      const spec = APP_ROLES[appName];
      const expected = spec.roles === 'ANY_AUTHENTICATED' ? true : spec.roles ? u.roles.some((r) => spec.roles.includes(r)) : null;
      row.apps[appName] = { expected, rule: spec.rule, ...(await uiGate(s.ctx, appName)) };
    }
    gating.push(row);
    await s.ctx.close();
    console.log(`${u.username}: ${rows.length} endpoint calls, ${objects.length} object calls`);
  });

  await browser.close();
  const out = { started, finished: new Date().toISOString(), apps: APPS, core: CORE, bff: BFF, idp: IDP_URL,
    users: USERS.map((u) => ({ ...u, app: primaryApp(u), loginError: sessions[u.username]?.error })),
    appRoles: APP_ROLES, getEndpoints: getOps.length, operations: ops.length,
    results, objects: objects.map(({ expect, ...o }) => o), objectResults, unauthenticated: unauth, gating };
  fs.writeFileSync(path.join(OUT, 'rbac-results.json'), JSON.stringify(out, null, 1));
  console.log('written', path.join(OUT, 'rbac-results.json'));
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
