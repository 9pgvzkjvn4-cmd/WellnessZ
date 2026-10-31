#!/usr/bin/env node
// SC-07 – Vendor posture: no device or vendor PC accepts its manufacturer default credentials (MDS2, IEC 80001-1, NIST SR-6).
// For each management page listed in deviceAdminPages[{name, url, username, passwords[]}] the script tries HTTP Basic logins with
// the listed defaults – at most 5 attempts per page with a pause, so no lock-out storm – and fails when one is accepted. Pages
// that do not use Basic authentication are reported NOT_RUN for manual verification (MDS2 section "Authentication").
import { Result, http, parseArgs, sleep } from './lib/pack.mjs';

const SCRIPT = '12_device_default_credentials_check';
const args = parseArgs(`${SCRIPT}.mjs`, 'Vendor default credentials on device / vendor PC management interfaces (HTTP Basic).');
const cfg = args.config;
const r = new Result(SCRIPT, ['SC-07'], cfg.environment);
const pages = cfg.deviceAdminPages ?? [];
if (pages.length === 0) r.notRun('default-credentials', 'vendor default credentials refused', 'no deviceAdminPages in the targets file');

for (const page of pages) {
  const anon = await http('GET', page.url, { timeoutMs: 8000 });
  const basic = /basic/i.test(anon.headers.get('www-authenticate') ?? '');
  if (anon.status === 0) { r.notRun(`default-credentials:${page.name}`, 'vendor default credentials refused', 'unreachable'); continue; }
  if (anon.status === 200) { r.fail(`no-auth:${page.name}`, 'management page requires authentication', 'page served without credentials'); continue; }
  if (!basic) { r.notRun(`default-credentials:${page.name}`, 'vendor default credentials refused', `HTTP ${anon.status}, not HTTP Basic – verify manually`); continue; }
  let accepted = null;
  for (const pw of (page.passwords ?? ['admin', 'password', '1234', 'service']).slice(0, 5)) {
    const res = await http('GET', page.url, { headers: { Authorization: 'Basic ' + Buffer.from(`${page.username ?? 'admin'}:${pw}`).toString('base64') }, timeoutMs: 8000 });
    if (res.status >= 200 && res.status < 400) { accepted = page.username ?? 'admin'; break; }
    await sleep(1500);
  }
  r.expect(`default-credentials:${page.name}`, 'vendor default credentials refused', accepted === null,
    accepted ? `a default password was accepted for account ${accepted} – change it and record the posture exception` : 'all defaults refused');
}
r.write(args.out);
