#!/usr/bin/env node
// SC-08 – Tamper-evident audit trail and SIEM forwarding; PHI masking (NIST AU-2 / AU-9 / AU-10, ISO 27001 A.8.15,
// HIPAA §164.312(b)): the platform audit chain (/audit/verify) and the device security event chain (/device-security/events/verify)
// are intact; security events carry only allow-listed, PHI-free attributes; the event classes the SIEM must receive are present.
import { Result, bearer, http, parseArgs } from './lib/pack.mjs';

const SCRIPT = '09_audit_log_integrity';
const args = parseArgs(`${SCRIPT}.mjs`, 'Hash-chain verification of the platform audit and the device security events, PHI-free events.');
const cfg = args.config;
const r = new Result(SCRIPT, ['SC-08'], cfg.environment);
const ALLOWED = new Set(['remoteAddress', 'method', 'path', 'protocol', 'port', 'rate', 'limit', 'keyId', 'algorithm', 'connectorId', 'baseline',
  'status', 'findings', 'skewMillis', 'schema', 'count', 'certificateSerial', 'purpose', 'reference', 'scope', 'script', 'outcome', 'environment',
  'version', 'configurationId', 'ruleCode', 'alertId', 'incidentRef', 'minutes', 'kind', 'previousThumbprint', 'thumbprint', 'issuer', 'format',
  'deviceSerialHash', 'listener']);
const PHI = /\bE\d{4,6}\b|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}|DeviceToken\s|Bearer\s/;

const reader = cfg.auditorBearer || cfg.secopsBearer;
if (!cfg.apiBase || !reader) {
  r.notRun('chains', 'audit chain verification', 'apiBase and auditorBearer (or secopsBearer) are required');
  r.write(args.out);
  process.exit();
}
const platform = await http('GET', `${cfg.apiBase}/audit/verify`, { headers: bearer(cfg.auditorBearer || cfg.platformAdminBearer || reader) });
if (platform.status === 200) {
  r.expect('platform-audit-chain', 'platform audit hash chain intact', platform.json?.intact === true, JSON.stringify(platform.json));
} else {
  r.notRun('platform-audit-chain', 'platform audit hash chain intact', `HTTP ${platform.status} (needs an AUDITOR / PLATFORM_ADMIN token)`);
}
const device = await http('GET', `${cfg.apiBase}/device-security/events/verify`, { headers: bearer(reader) });
r.expect('device-event-chain', 'device security event hash chain intact', device.status === 200 && device.json?.intact === true,
  device.status === 200 ? `checked=${device.json.checked} firstBrokenId=${device.json.firstBrokenId ?? 'none'}` : `HTTP ${device.status}`);

const events = await http('GET', `${cfg.apiBase}/device-security/events?limit=500`, { headers: bearer(reader) });
if (events.status === 200 && Array.isArray(events.json)) {
  const badKeys = new Set();
  let phi = 0;
  for (const e of events.json) {
    Object.keys(e.attributes ?? {}).forEach((k) => { if (!ALLOWED.has(k)) badKeys.add(k); });
    if (PHI.test(JSON.stringify(e.attributes ?? {}))) phi++;
  }
  r.expect('event-attributes-allow-listed', 'security event attributes only from the allow-list', badKeys.size === 0,
    badKeys.size ? [...badKeys].join(',') : `${events.json.length} events checked`);
  r.expect('event-attributes-phi-free', 'no PHI or credentials in security events', phi === 0, `${phi} event(s) with matches`);
  const types = new Set(events.json.map((e) => e.type));
  const expected = cfg.expectedEventTypes ?? ['ENDPOINT_AUTH_FAILED', 'ENROLMENT_SUCCEEDED', 'ENDPOINT_CREATED'];
  const missing = expected.filter((t) => !types.has(t));
  r.expect('event-classes', 'expected event classes present for SIEM forwarding', missing.length === 0, missing.length ? `missing ${missing.join(',')}` : 'all present');
} else {
  r.fail('event-attributes', 'security events readable for review', `HTTP ${events.status}`);
}
r.write(args.out);
