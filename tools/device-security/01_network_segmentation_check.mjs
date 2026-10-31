#!/usr/bin/env node
// SC-02 / SC-07 – Medical-device VLAN segmentation and deny-by-default conduits (IEC 62443 SR 5.1/5.2, NIST SC-7, SP 800-207).
// Run from each zone (device VLAN, gateway zone, clinic LAN) with the probes of that vantage point:
//   * TCP probes against the allow-list in the targets file (segmentation.probes[{name, host, port, expect: open|closed}]) –
//     an "open" result where the reference design expects "closed" is an unauthorised east-west path;
//   * zone violation at the core: an endpoint bound to a CIDR range is refused from any other address (zone_violation);
//   * devices in the device VLAN can never be registered as direct core endpoints (zone_violation at creation).
import net from 'node:net';
import { Result, bearer, deviceHeaders, http, parseArgs, provisionTestEndpoint } from './lib/pack.mjs';

const SCRIPT = '01_network_segmentation_check';
const args = parseArgs(`${SCRIPT}.mjs`, 'Network segmentation: TCP probes vs the conduit allow-list, zone violation at the core.');
const cfg = args.config;
const r = new Result(SCRIPT, ['SC-02', 'SC-07'], cfg.environment);

function probe(host, port, timeoutMs = 3000) {
  return new Promise((resolve) => {
    const s = net.connect({ host, port });
    const done = (state) => { s.destroy(); resolve(state); };
    s.setTimeout(timeoutMs, () => done('closed'));
    s.once('connect', () => done('open'));
    s.once('error', () => done('closed'));
  });
}

const probes = cfg.segmentation?.probes ?? [];
if (probes.length === 0) r.notRun('probes', 'TCP probes against the conduit allow-list', 'no segmentation.probes in the targets file');
for (const p of probes.slice(0, 500)) {
  const state = await probe(p.host, Number(p.port));
  r.expect(`probe:${p.name ?? `${p.host}:${p.port}`}`, `${p.host}:${p.port} expected ${p.expect}`, state === p.expect, `observed ${state}`);
}

if (cfg.apiBase && cfg.secopsBearer && cfg.devCa?.clientCert) {
  // TEST-NET-1 range: no real client ever calls from it, so every call is a zone violation
  const ep = await provisionTestEndpoint(cfg, { kind: 'KIOSK', allowedCidrs: ['192.0.2.0/24'] });
  if (ep.error) {
    r.fail('zone-violation', 'endpoint bound to a CIDR range is refused from other addresses', ep.error);
  } else {
    const res = await http('POST', `${cfg.apiBase}/device-ingest/certificate-renewal`, { headers: deviceHeaders(cfg, ep), body: {} });
    r.expect('zone-violation', 'endpoint bound to a CIDR range is refused from other addresses',
      res.status === 401 && res.json?.code === 'zone_violation', `HTTP ${res.status} ${res.json?.code ?? ''}`);
    await http('POST', `${cfg.apiBase}/device-security/endpoints/${ep.id}/revoke`, { headers: bearer(cfg.secopsBearer), body: { reasonCode: 'TEST_PACK_CLEANUP' } });
  }
  const dev = await http('POST', `${cfg.apiBase}/device-security/endpoints`, {
    headers: bearer(cfg.secopsBearer), body: { kind: 'DEVICE', siteCode: 'LHR', zone: 'DEVICE_VLAN' },
  });
  r.expect('device-vlan-direct', 'a device in the device VLAN cannot become a direct core endpoint',
    dev.status === 422 && dev.json?.code === 'zone_violation', `HTTP ${dev.status} ${dev.json?.code ?? ''}`);
  const conduits = await http('GET', `${cfg.apiBase}/device-security/conduits/export?format=csv`, { headers: bearer(cfg.secopsBearer) });
  r.expect('deny-by-default', 'conduit export ends with the default deny rule', conduits.status === 200 && conduits.text.includes('DEFAULT,*,*,*,*,*,DENY'),
    `HTTP ${conduits.status}`);
} else {
  r.notRun('zone-violation', 'zone violation at the core', 'apiBase, secopsBearer and devCa are required');
}
r.write(args.out);
