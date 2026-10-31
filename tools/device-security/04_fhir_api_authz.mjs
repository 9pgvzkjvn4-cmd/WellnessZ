#!/usr/bin/env node
// SC-11 – API authentication / authorisation (OWASP API Top 10 API1 / API2 / API5, ASVS V4 / V13) for device and person traffic:
//   * a device token is useless on the user API, a person's bearer token is refused on the ingest API;
//   * replayed token: a one-time enrolment token can be exchanged exactly once (second use → 401 enrolment_token_invalid);
//   * expired enrolment token → 401 enrolment_token_expired (staging only: the expiry is moved back through psql);
//   * revoked endpoint → 401 endpoint_revoked on the next request;
//   * object-level / function-level checks: console roles never reach person data or another role's functions.
import { spawnSync } from 'node:child_process';
import { Result, bearer, deviceHeaders, enrol, http, loadClient, parseArgs, provisionTestEndpoint } from './lib/pack.mjs';

const SCRIPT = '04_fhir_api_authz';
const args = parseArgs(`${SCRIPT}.mjs`, 'Device vs user tokens, replayed / expired enrolment tokens, revoked endpoints, object-level access.');
const cfg = args.config;
const r = new Result(SCRIPT, ['SC-11'], cfg.environment);
const api = cfg.apiBase;

if (!api) {
  r.notRun('api', 'API authorisation checks', 'apiBase is required');
  r.write(args.out);
  process.exit();
}

if (cfg.endpoint?.token) {
  const onUserApi = await http('GET', `${api}/me`, { headers: deviceHeaders(cfg, cfg.endpoint) });
  r.expect('device-token-on-user-api', 'device credentials are refused on the user API', onUserApi.status === 401, `HTTP ${onUserApi.status}`);
}
if (cfg.userBearer) {
  const onIngest = await http('POST', `${api}/device-ingest/heartbeat`, { headers: bearer(cfg.userBearer), body: {} });
  r.expect('user-token-on-ingest', "a person's bearer token is refused on the ingest API", onIngest.status === 401, `HTTP ${onIngest.status}`);
  const fhir = await http('POST', `${api}/device-ingest/fhir`, { headers: { ...bearer(cfg.userBearer), 'Content-Type': 'application/fhir+json' },
    body: JSON.stringify({ resourceType: 'Observation', status: 'final' }) });
  r.expect('user-token-on-fhir-ingest', 'FHIR ingest refuses person tokens', fhir.status === 401, `HTTP ${fhir.status}`);
} else {
  r.notRun('user-token-on-ingest', "person token on the ingest API", 'userBearer not configured');
}
if (cfg.consoleBearer) {
  // a device console role (no person data) must not open person-level resources
  for (const path of cfg.objectLevelProbes ?? ['/employees/E1001/results', '/device-capture/sessions']) {
    const res = await http('GET', `${api}${path}`, { headers: bearer(cfg.consoleBearer) });
    r.expect(`console-role:${path}`, 'console role denied on person-level resources (403 / 404)', [403, 404].includes(res.status), `HTTP ${res.status}`);
  }
  const keys = await http('POST', `${api}/device-security/signing-keys`, { headers: bearer(cfg.consoleBearer), body: {} });
  r.expect('function-level', 'console role cannot rotate signing keys (SECURITY_OPS only)', keys.status === 403, `HTTP ${keys.status}`);
}

const client = loadClient(cfg.devCa?.clientCert, cfg.devCa?.clientKey);
if (cfg.secopsBearer && client) {
  // replayed one-time enrolment token
  const ep = await provisionTestEndpoint(cfg, { kind: 'MOBILE_APP' });
  if (ep.error) {
    r.fail('replayed-enrolment-token', 'one-time enrolment token cannot be replayed', ep.error);
  } else {
    const replay = await enrol(cfg, client, ep.enrolmentToken, 'MOBILE_APP');
    r.expect('replayed-enrolment-token', 'second use of the enrolment token → 401 enrolment_token_invalid',
      replay.status === 401 && replay.json?.code === 'enrolment_token_invalid', `HTTP ${replay.status} ${replay.json?.code ?? ''}`);
    const ok = await http('POST', `${api}/device-ingest/certificate-renewal`, { headers: deviceHeaders(cfg, ep), body: {} });
    r.expect('enrolled-endpoint-authenticates', 'enrolled endpoint authenticates (control)', ok.status === 400, `HTTP ${ok.status}`);
    // revoked endpoint
    const rev = await http('POST', `${api}/device-security/endpoints/${ep.id}/revoke`, { headers: bearer(cfg.secopsBearer), body: { reasonCode: 'TEST_PACK_REVOCATION' } });
    const after = await http('POST', `${api}/device-ingest/certificate-renewal`, { headers: deviceHeaders(cfg, ep), body: {} });
    r.expect('revoked-endpoint', 'revoked endpoint → 401 endpoint_revoked on the next request', rev.status === 200 && after.status === 401
      && after.json?.code === 'endpoint_revoked', `revoke HTTP ${rev.status}; next request HTTP ${after.status} ${after.json?.code ?? ''}`);
  }
  // expired enrolment token
  const created = await http('POST', `${api}/device-security/endpoints`, { headers: bearer(cfg.secopsBearer), body: { kind: 'GATEWAY', siteCode: 'LHR' } });
  if (created.status === 201 && cfg.database?.psqlUrl) {
    const sql = `update devicesec.enrolment_token set expires_at = now() - interval '1 second' where endpoint_id = '${created.json.endpointId.replace(/[^A-Za-z0-9-]/g, '')}'`;
    const p = spawnSync('psql', [cfg.database.psqlUrl, '-q', '-c', sql], { encoding: 'utf8' });
    if (p.status === 0) {
      const res = await enrol(cfg, client, created.json.enrolmentToken, 'GATEWAY');
      r.expect('expired-enrolment-token', 'expired enrolment token → 401 enrolment_token_expired',
        res.status === 401 && res.json?.code === 'enrolment_token_expired', `HTTP ${res.status} ${res.json?.code ?? ''}`);
    } else {
      r.notRun('expired-enrolment-token', 'expired enrolment token', 'psql could not move the expiry back');
    }
  } else {
    r.notRun('expired-enrolment-token', 'expired enrolment token', 'needs database.psqlUrl of the staging database (expiry is moved back)');
  }
  if (created.status === 201) {
    await http('POST', `${api}/device-security/endpoints/${created.json.endpointId}/revoke`, { headers: bearer(cfg.secopsBearer), body: { reasonCode: 'TEST_PACK_CLEANUP' } });
  }
  // unknown / random enrolment token
  const random = await enrol(cfg, client, 'x'.repeat(43), 'GATEWAY');
  r.expect('unknown-enrolment-token', 'unknown enrolment token → 401 enrolment_token_invalid', random.status === 401, `HTTP ${random.status}`);
} else {
  r.notRun('enrolment-tokens', 'replayed / expired enrolment tokens and revocation', 'secopsBearer and devCa client certificate are required');
}
r.write(args.out);
