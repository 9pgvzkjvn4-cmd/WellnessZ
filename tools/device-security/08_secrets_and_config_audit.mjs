#!/usr/bin/env node
// SC-05 / SC-15 – Secrets in vault / KMS, none in configuration, images or driver packages (NIST IA-5 / SC-12, ASVS V6, OWASP API8)
// and no PHI in logs / non-production (GDPR Art. 5 / 25, HIPAA minimum necessary).
//   * repository / configuration scan: credential-like keys must hold ENC(...), file:, ${...}, devsec: or vault: references;
//   * secrets never returned: a secret stored through the API is not echoed and the metadata listing never contains a value;
//   * the core secret scanner flags literal secrets in a driver package;
//   * PHI masking in logs: log files contain no employee ids, dates of birth, e-mail addresses, bearer / device tokens or
//     measurement payloads (patterns configurable in phiPatterns).
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { Result, bearer, http, parseArgs } from './lib/pack.mjs';

const SCRIPT = '08_secrets_and_config_audit';
const args = parseArgs(`${SCRIPT}.mjs`, 'Secret scanner over configuration and packages, write-only secrets at the API, PHI patterns in logs.');
const cfg = args.config;
const r = new Result(SCRIPT, ['SC-05', 'SC-15'], cfg.environment);

const KEY = /(password|passwd|secret|token|api[-_]?key|private[-_]?key|credential)/i;
const REFERENCE = /^(ENC\(.+\)|file:.+|\$\{[^}]+\}|devsec:.+|vault:.+|<[^>]+>|\*+|changeme|)$/i;
const ALLOW = [/test\//, /\/target\//, /node_modules/, /\.git\//, /docs\//, /tools\/e2e\//, /demo\//];
const EXT = /\.(ya?ml|json|properties|env|conf|ini|xml)$/i;

function walk(dir, out = [], depth = 0) {
  if (depth > 8 || !existsSync(dir)) return out;
  for (const n of readdirSync(dir)) {
    if (n.startsWith('.') && n !== '.env') continue;
    const p = join(dir, n);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, out, depth + 1);
    else if (EXT.test(n) && st.size < 2_000_000) out.push(p);
  }
  return out;
}

const roots = cfg.secretScan?.roots ?? [cfg.repoRoot || process.cwd()];
let findings = 0;
const examples = [];
for (const root of roots) {
  for (const f of walk(root)) {
    const rel = relative(root, f);
    if (ALLOW.some((a) => a.test('/' + rel.replace(/\\/g, '/')))) continue;
    const lines = readFileSync(f, 'utf8').split(/\r?\n/);
    lines.forEach((line, i) => {
      const m = /^\s*"?([A-Za-z0-9_.\-]+)"?\s*[:=]\s*"?([^"#,]*)"?/.exec(line);
      if (!m || !KEY.test(m[1])) return;
      const v = m[2].trim();
      if (v === '' || REFERENCE.test(v) || /^(true|false|null|\d+)$/i.test(v) || /^[A-Z_]+$/.test(v) || v.startsWith('${')) return;
      findings++;
      if (examples.length < 20) examples.push(`${rel}:${i + 1} key ${m[1]}`); // location only, never the value
    });
    if (/-----BEGIN [A-Z ]*PRIVATE KEY-----/.test(lines.join('\n'))) {
      findings++;
      if (examples.length < 20) examples.push(`${rel}: embedded private key`);
    }
  }
}
r.expect('config-secret-scan', 'no literal secrets in configuration, images or packages (zero findings)', findings === 0,
  findings === 0 ? `scanned ${roots.length} root(s)` : `${findings} finding(s): ${examples.join('; ')}`);

if (cfg.apiBase && cfg.secopsBearer) {
  const marker = `pack-${Math.random().toString(36).slice(2)}-Value!`;
  const stored = await http('POST', `${cfg.apiBase}/device-security/secrets`, { headers: bearer(cfg.secopsBearer),
    body: { scope: 'TEST-PACK', name: 'write-only check', value: marker } });
  r.expect('secret-not-echoed', 'stored secret is not returned in the response', stored.status === 201 && !stored.text.includes(marker),
    `HTTP ${stored.status}`);
  const listed = await http('GET', `${cfg.apiBase}/device-security/secrets?scope=TEST-PACK`, { headers: bearer(cfg.secopsBearer) });
  r.expect('secret-metadata-only', 'secret listing shows metadata only, never the value', listed.status === 200 && !listed.text.includes(marker),
    `HTTP ${listed.status}, ${Array.isArray(listed.json) ? listed.json.length : 0} entries`);
  r.expect('no-store', 'secret responses are not cached (Cache-Control: no-store)', /no-store/.test(listed.headers.get('cache-control') ?? ''),
    listed.headers.get('cache-control') ?? 'none');
  if (stored.json?.reference) {
    await http('DELETE', `${cfg.apiBase}/device-security/secrets/${stored.json.reference}`, { headers: bearer(cfg.secopsBearer) });
  }
  const scan = await http('POST', `${cfg.apiBase}/device-security/secret-scan`, { headers: bearer(cfg.secopsBearer),
    body: { protocol: { db: { password: 'literal', credentialRef: 'devsec:00000000-0000-4000-8000-000000000000' } } } });
  r.expect('core-secret-scanner', 'core secret scanner flags the literal value and accepts the reference',
    scan.status === 200 && Array.isArray(scan.json) && scan.json.length === 1, `HTTP ${scan.status}`);
} else {
  r.notRun('secrets-api', 'secrets never returned through the API', 'apiBase and secopsBearer are required');
}

const PHI = (cfg.phiPatterns ?? [
  '\\bE\\d{4,6}\\b',                                  // employee ids
  '\\b(19|20)\\d{2}-\\d{2}-\\d{2}\\b(?=.*(dob|birth))', // dates of birth
  '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}',   // e-mail addresses
  '(Bearer|DeviceToken)\\s+[A-Za-z0-9._~+/=-]{16,}',    // credentials
  'OBX\\|\\d+\\|(NM|ST)\\|',                            // HL7 observation payloads
  '"(employeeId|dateOfBirth|displayName)"\\s*:',       // person fields in JSON logs
]).map((p) => new RegExp(p, 'i'));
const logs = cfg.logFiles ?? [];
if (logs.length === 0) r.notRun('phi-in-logs', 'PHI masking in logs', 'no logFiles in the targets file');
for (const f of logs) {
  if (!existsSync(f)) { r.notRun(`phi-in-logs:${f}`, 'PHI masking in logs', 'file not found'); continue; }
  const hits = {};
  readFileSync(f, 'utf8').split(/\r?\n/).slice(-200000).forEach((line) => {
    PHI.forEach((re, i) => { if (re.test(line)) hits[i] = (hits[i] || 0) + 1; });
  });
  const total = Object.values(hits).reduce((a, b) => a + b, 0);
  r.expect(`phi-in-logs:${f}`, 'no PHI / credentials in logs (masked)', total === 0,
    total === 0 ? 'no matches' : `matches per pattern: ${JSON.stringify(hits)} (values not reproduced)`);
}
r.write(args.out);
