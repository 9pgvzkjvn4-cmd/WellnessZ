#!/usr/bin/env node
// SC-14 – Time synchronisation and certificate life-cycle (NIST AU-8 / SC-17): the core's clock agrees with the tester's NTP-synchronised
// clock, TLS server certificates are valid for more than the warning period, and no ACTIVE endpoint identity has a client
// certificate expiring within the warning period without an open CERTIFICATE_EXPIRING alert.
import tls from 'node:tls';
import { spawnSync } from 'node:child_process';
import { Result, bearer, http, parseArgs } from './lib/pack.mjs';

const SCRIPT = '13_time_sync_and_cert_expiry';
const args = parseArgs(`${SCRIPT}.mjs`, 'Clock skew against the core, TLS certificate expiry, endpoint certificate expiry alerts.');
const cfg = args.config;
const r = new Result(SCRIPT, ['SC-14'], cfg.environment);
const warnDays = Number(cfg.certExpiryWarningDays ?? 30);
const maxSkew = Number(cfg.maxClockSkewSeconds ?? 120);

if (process.platform === 'linux') {
  const t = spawnSync('timedatectl', ['show', '-p', 'NTPSynchronized', '--value'], { encoding: 'utf8' });
  if (t.status === 0) r.expect('tester-ntp', 'tester clock NTP-synchronised (reference for the skew check)', t.stdout.trim() === 'yes', t.stdout.trim());
}
if (cfg.apiBase) {
  const before = Date.now();
  const res = await http('GET', `${cfg.apiBase}/modules`);
  const after = Date.now();
  const date = res.headers.get('date');
  if (date) {
    const skew = Math.abs(new Date(date).getTime() - (before + after) / 2) / 1000;
    r.expect('core-clock-skew', `core clock within ${maxSkew} s`, skew <= Math.max(maxSkew, 1), `${skew.toFixed(1)} s`);
  } else {
    r.notRun('core-clock-skew', 'core clock skew', `no Date header (HTTP ${res.status})`);
  }
}
for (const t of cfg.tlsTargets ?? []) {
  const cert = await new Promise((resolve) => {
    const s = tls.connect({ host: t.host, port: t.port, servername: t.host, rejectUnauthorized: false });
    s.setTimeout(5000, () => { s.destroy(); resolve(null); });
    s.once('secureConnect', () => { const c = s.getPeerCertificate(); s.end(); resolve(c); });
    s.once('error', () => resolve(null));
  });
  if (!cert?.valid_to) { r.notRun(`tls-cert:${t.host}:${t.port}`, 'TLS certificate expiry', 'no certificate'); continue; }
  const days = (new Date(cert.valid_to).getTime() - Date.now()) / 86400000;
  r.expect(`tls-cert:${t.host}:${t.port}`, `TLS certificate valid for more than ${warnDays} days`, days > warnDays, `${days.toFixed(0)} days left`);
}
if (cfg.apiBase && cfg.secopsBearer) {
  const eps = await http('GET', `${cfg.apiBase}/device-security/endpoints?status=ACTIVE`, { headers: bearer(cfg.secopsBearer) });
  const alerts = await http('GET', `${cfg.apiBase}/device-security/alerts?status=OPEN`, { headers: bearer(cfg.secopsBearer) });
  if (eps.status === 200) {
    const expiring = eps.json.filter((e) => e.certificateExpiresInDays !== undefined && e.certificateExpiresInDays !== null && e.certificateExpiresInDays < warnDays);
    const alerted = new Set((alerts.json ?? []).filter((a) => a.ruleCode === 'CERTIFICATE_EXPIRING').map((a) => a.subjectKey));
    const silent = expiring.filter((e) => !alerted.has(e.endpointId));
    const expired = eps.json.filter((e) => e.certificateExpiresInDays !== undefined && e.certificateExpiresInDays < 0);
    r.expect('endpoint-certs-expired', 'no ACTIVE endpoint with an expired client certificate', expired.length === 0,
      expired.map((e) => e.endpointId).join(',') || `${eps.json.length} active endpoints`);
    r.expect('endpoint-certs-alerted', `every certificate expiring within ${warnDays} days has a CERTIFICATE_EXPIRING alert`, silent.length === 0,
      silent.length ? `no alert yet: ${silent.map((e) => e.endpointId).join(',')} (the daily job raises it)` : `${expiring.length} expiring, all alerted`);
  } else {
    r.fail('endpoint-certs', 'endpoint certificate inventory readable', `HTTP ${eps.status}`);
  }
} else {
  r.notRun('endpoint-certs', 'endpoint certificate expiry', 'apiBase and secopsBearer are required');
}
r.write(args.out);
