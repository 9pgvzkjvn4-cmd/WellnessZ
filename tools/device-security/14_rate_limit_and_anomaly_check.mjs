#!/usr/bin/env node
// SC-09 / SC-13 – Rate limiting and the device-compromise playbook (OWASP API4, NIST SC-5, IR-4 / SI-4, IEC 62443 SR 6.2):
//   * 429 behaviour: a burst above the per-principal budget is answered 429 with Retry-After (when the limiter is enabled);
//   * FAILED_AUTH_BURST → automatic isolation within the SLA (default 15 min): a sacrificial endpoint (created and enrolled by the
//     script with the dev CA) receives bad tokens until the rule's isolation threshold; the script polls until the endpoint is
//     ISOLATED, records the drill time, checks that valid credentials are now refused (endpoint_isolated), that the incident
//     reports withinSla = true with preserved evidence, and that release is blocked until re-certification.
import { Result, bearer, deviceHeaders, http, parseArgs, provisionTestEndpoint, sleep } from './lib/pack.mjs';

const SCRIPT = '14_rate_limit_and_anomaly_check';
const args = parseArgs(`${SCRIPT}.mjs`, '429 behaviour and FAILED_AUTH_BURST isolation within the SLA (drill record).');
const cfg = args.config;
const r = new Result(SCRIPT, ['SC-09', 'SC-13'], cfg.environment);
const api = cfg.apiBase;

if (api && cfg.rateLimit?.bearer) {
  const n = Number(cfg.rateLimit.requests ?? 800);
  let limited = null;
  for (let i = 0; i < n && !limited; i++) {
    const res = await http('GET', `${api}${cfg.rateLimit.path ?? '/me'}`, { headers: bearer(cfg.rateLimit.bearer), timeoutMs: 5000 });
    if (res.status === 429) limited = { at: i + 1, retryAfter: res.headers.get('retry-after') };
  }
  r.expect('rate-limit-429', `burst of ${n} requests is throttled with 429 and Retry-After`, !!limited && !!limited.retryAfter,
    limited ? `429 after ${limited.at} requests, Retry-After ${limited.retryAfter}` : 'never throttled (limiter disabled?)');
} else {
  r.notRun('rate-limit-429', '429 behaviour', 'rateLimit.bearer not configured');
}

if (api && cfg.secopsBearer && cfg.devCa?.clientCert) {
  const sla = Number(cfg.isolationSlaMinutes ?? 15);
  const rules = await http('GET', `${api}/device-security/anomaly-rules`, { headers: bearer(cfg.secopsBearer) });
  const burst = (rules.json ?? []).find((x) => x.code === 'FAILED_AUTH_BURST');
  const attempts = burst?.isolateThreshold ?? burst?.threshold ?? 30;
  const ep = await provisionTestEndpoint(cfg, { kind: 'MOBILE_APP', deviceInstanceIds: ['DEV-PACK-ISOLATION'] });
  if (ep.error) {
    r.fail('isolation-drill', 'FAILED_AUTH_BURST isolates the endpoint within the SLA', ep.error);
  } else {
    const ok = await http('POST', `${api}/device-ingest/certificate-renewal`, { headers: deviceHeaders(cfg, ep), body: {} });
    r.expect('control-request', 'sacrificial endpoint authenticates before the drill', ok.status === 400, `HTTP ${ok.status}`);
    const start = Date.now();
    for (let i = 0; i < attempts; i++) {
      await http('POST', `${api}/device-ingest/certificate-renewal`, { headers: deviceHeaders(cfg, ep, { token: `pack-guess-${i}` }), body: {} });
    }
    let state = null;
    while (Date.now() - start < sla * 60000) {
      const s = await http('GET', `${api}/device-security/endpoints/${ep.id}`, { headers: bearer(cfg.secopsBearer) });
      state = s.json?.status;
      if (state === 'ISOLATED') break;
      await sleep(5000);
    }
    const minutes = (Date.now() - start) / 60000;
    r.expect('isolation-within-sla', `endpoint ISOLATED within ${sla} minutes of the burst`, state === 'ISOLATED' && minutes <= sla,
      `${state ?? 'unknown'} after ${minutes.toFixed(2)} min (${attempts} failed attempts)`);
    const refused = await http('POST', `${api}/device-ingest/certificate-renewal`, { headers: deviceHeaders(cfg, ep), body: {} });
    r.expect('isolated-refused', 'valid credentials refused once isolated (endpoint_isolated)', refused.status === 401 && refused.json?.code === 'endpoint_isolated',
      `HTTP ${refused.status} ${refused.json?.code ?? ''}`);
    const incidents = await http('GET', `${api}/device-security/incidents?endpointId=${ep.id}`, { headers: bearer(cfg.secopsBearer) });
    const inc = (incidents.json ?? [])[0];
    r.expect('drill-record', 'incident drill record within SLA with preserved evidence', !!inc && inc.withinSla === true && (inc.evidence ?? []).length > 0,
      inc ? `${inc.incidentRef} minutesToIsolate=${inc.minutesToIsolate?.toFixed?.(2)} evidence=${inc.evidence.length}` : `HTTP ${incidents.status}`);
    const alerts = await http('GET', `${api}/device-security/alerts?endpointId=${ep.id}`, { headers: bearer(cfg.secopsBearer) });
    r.expect('alert-raised', 'FAILED_AUTH_BURST alert raised for Security Operations', (alerts.json ?? []).some((a) => a.ruleCode === 'FAILED_AUTH_BURST'),
      `${(alerts.json ?? []).length} alert(s)`);
    const release = await http('POST', `${api}/device-security/endpoints/${ep.id}/release`, { headers: bearer(cfg.secopsBearer), body: { reasonCode: 'TEST_PACK' } });
    r.expect('recertification-gate', 'release refused until the instances are re-certified (409 recertification_required)',
      release.status === 409 && release.json?.code === 'recertification_required', `HTTP ${release.status} ${release.json?.code ?? ''}`);
    await http('POST', `${api}/device-security/endpoints/${ep.id}/revoke`, { headers: bearer(cfg.secopsBearer), body: { reasonCode: 'TEST_PACK_CLEANUP' } });
  }
} else {
  r.notRun('isolation-drill', 'FAILED_AUTH_BURST isolation drill', 'apiBase, secopsBearer and devCa are required');
}
r.write(args.out);
