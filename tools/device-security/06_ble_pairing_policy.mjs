#!/usr/bin/env node
// SC-04 – Bluetooth LE Secure Connections and bonded-device policy (IEC 62443 SR 1.x, NIST AC-19 / MP-7): every BLE binding in the
// gateway configuration requires LE Secure Connections with authenticated pairing (or a documented "Just Works" exception for a
// device that cannot do better), bonding managed by the gateway, an approved-device allow-list, and hashed peripheral addresses
// (raw MAC addresses never leave the edge). Optionally compares the OS bonded devices (bluetoothctl) with the allow-list.
import { readFileSync, existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { Result, deviceHeaders, http, parseArgs } from './lib/pack.mjs';

const SCRIPT = '06_ble_pairing_policy';
const args = parseArgs(`${SCRIPT}.mjs`, 'BLE pairing policy of the gateway configuration and the bonded devices.');
const cfg = args.config;
const r = new Result(SCRIPT, ['SC-04'], cfg.environment);
const RAW_MAC = /\b([0-9A-F]{2}[:-]){5}[0-9A-F]{2}\b/i;

let gatewayConfig = null;
if (cfg.ble?.configFile && existsSync(cfg.ble.configFile)) {
  gatewayConfig = JSON.parse(readFileSync(cfg.ble.configFile, 'utf8'));
} else if (cfg.apiBase && cfg.endpoint?.token) {
  const res = await http('GET', `${cfg.apiBase}/device-ingest/config`, { headers: deviceHeaders(cfg, cfg.endpoint) });
  if (res.status === 200) gatewayConfig = res.json;
  else r.notRun('gateway-config', 'gateway configuration', `GET /device-ingest/config answered ${res.status}`);
}

const bindings = (gatewayConfig?.devices ?? []).filter((d) => d.ble);
if (!gatewayConfig) {
  r.notRun('ble-bindings', 'BLE bindings of the gateway', 'no gateway configuration (ble.configFile or endpoint credentials)');
} else if (bindings.length === 0) {
  r.notRun('ble-bindings', 'BLE bindings of the gateway', 'the configuration has no BLE devices');
}
for (const d of bindings) {
  const id = d.deviceInstanceId ?? 'unknown';
  const mode = d.ble.securityMode ?? d.ble.pairing ?? d.parameters?.pairing;
  const exception = d.ble.justWorksException === true;
  r.expect(`le-secure-connections:${id}`, 'LE Secure Connections with authenticated pairing (or documented exception)',
    /LE_SECURE|SECURE_CONNECTIONS|PASSKEY|NUMERIC_COMPARISON|OOB/i.test(String(mode ?? '')) || exception, `mode=${mode ?? 'unset'}${exception ? ' (exception)' : ''}`);
  const allow = d.ble.peripheralAllowList ?? [];
  r.expect(`allow-list:${id}`, 'approved-device allow-list present', Array.isArray(allow) && allow.length > 0, `${allow.length} entries`);
  r.expect(`hashed-addresses:${id}`, 'peripheral addresses hashed (no raw MAC in configuration)', !RAW_MAC.test(JSON.stringify(allow)), 'checked');
  r.expect(`bonding:${id}`, 'bonding required and managed by the gateway', d.ble.bonding !== false, `bonding=${d.ble.bonding ?? 'default'}`);
}

if (cfg.ble?.checkBondedDevices && process.platform === 'linux') {
  const p = spawnSync('bluetoothctl', ['devices', 'Bonded'], { encoding: 'utf8', timeout: 10000 });
  if (p.status === 0) {
    const bonded = p.stdout.split('\n').filter((l) => l.startsWith('Device ')).length;
    const approved = cfg.ble.approvedBondedCount ?? null;
    if (approved === null) r.notRun('bonded-devices', 'bonded devices reviewed against the allow-list', `${bonded} bonded – set ble.approvedBondedCount`);
    else r.expect('bonded-devices', 'bonded devices match the approved list', bonded <= approved, `${bonded} bonded, ${approved} approved`);
  } else {
    r.notRun('bonded-devices', 'bonded devices reviewed against the allow-list', 'bluetoothctl unavailable');
  }
}
r.write(args.out);
