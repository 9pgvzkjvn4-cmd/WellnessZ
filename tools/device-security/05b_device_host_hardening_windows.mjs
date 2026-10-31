#!/usr/bin/env node
// SC-03 / SC-04 – Hardened Windows Device Host (vendor PC running the Device Host agent): BitLocker, Defender / EDR running with
// current signatures, firewall profiles on with inbound block, time synchronisation, USB device-installation restrictions,
// agent service under a non-administrator account. Run ON the Device Host (PowerShell is used read-only); NOT_RUN elsewhere.
import { spawnSync } from 'node:child_process';
import { Result, parseArgs } from './lib/pack.mjs';

const SCRIPT = '05b_device_host_hardening_windows';
const args = parseArgs(`${SCRIPT}.mjs`, 'Windows Device Host hardening (BitLocker, Defender, firewall, time, USB policy, service account).');
const cfg = args.config;
const r = new Result(SCRIPT, ['SC-03', 'SC-04'], cfg.environment);

function ps(command) {
  const p = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command], { encoding: 'utf8', timeout: 30000 });
  return p.status === 0 ? p.stdout.trim() : null;
}

if (process.platform !== 'win32') {
  r.notRun('platform', 'Windows Device Host hardening', 'not a Windows host – run on the Device Host');
} else {
  const service = cfg.deviceHost?.serviceName || 'WellnessZDeviceHost';
  const bitlocker = ps("(Get-BitLockerVolume -MountPoint $env:SystemDrive).ProtectionStatus");
  r.expect('bitlocker', 'system drive encrypted (BitLocker on)', bitlocker === 'On' || bitlocker === '1', bitlocker ?? 'unavailable');
  const defender = ps('$s=Get-MpComputerStatus; "$($s.AntivirusEnabled)|$($s.RealTimeProtectionEnabled)|$($s.AntivirusSignatureAge)"');
  const [av, rt, age] = (defender ?? '').split('|');
  r.expect('edr', 'Defender / EDR on with real-time protection and signatures ≤ 3 days old', av === 'True' && rt === 'True' && Number(age) <= 3,
    defender ?? 'unavailable');
  const fw = ps("Get-NetFirewallProfile | ForEach-Object { \"$($_.Name)=$($_.Enabled)/$($_.DefaultInboundAction)\" }");
  r.expect('firewall', 'all firewall profiles on, inbound blocked by default', !!fw && !/False|Allow/.test(fw), fw ?? 'unavailable');
  const w32 = ps('w32tm /query /status');
  r.expect('time-sync', 'Windows Time synchronised with the clinic NTP source', !!w32 && /Source:\s*(?!Local CMOS Clock|Free-running)/.test(w32),
    w32 ? w32.split(/\r?\n/).filter((l) => /Source|Last Successful/.test(l)).join('; ') : 'unavailable');
  const usb = ps("(Get-ItemProperty 'HKLM:\\SOFTWARE\\Policies\\Microsoft\\Windows\\DeviceInstall\\Restrictions' -ErrorAction SilentlyContinue).DenyUnspecified");
  r.expect('usb-policy', 'USB device installation restricted to the allow-list (DenyUnspecified)', usb === '1', usb ?? 'policy not set');
  const account = ps(`(Get-CimInstance Win32_Service -Filter "Name='${service.replace(/[^A-Za-z0-9_]/g, '')}'").StartName`);
  if (account === null || account === '') r.notRun('service-account', 'agent service under a least-privilege account', `service ${service} not found`);
  else r.expect('service-account', 'agent service under a least-privilege account', !/LocalSystem|Administrator/i.test(account), account);
  const smb1 = ps('(Get-WindowsOptionalFeature -Online -FeatureName SMB1Protocol).State');
  r.expect('smb1', 'SMBv1 disabled', smb1 !== 'Enabled', smb1 ?? 'unavailable');
}
r.write(args.out);
