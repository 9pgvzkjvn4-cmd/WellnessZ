# Clinic gateway / Linux Device Host – hardening checklist (CIS-aligned)

Scope: the Linux host that runs `wz-device-gateway` at a clinic (systemd service or single-node k3s with the Helm
component `deviceGateway`). Baseline: **CIS Ubuntu Linux 24.04 LTS Benchmark, Level 1 – Server** (areas named, not
section numbers, because numbering changes between benchmark versions), plus the IEC 62443-3-3 / E34 controls SC-02,
SC-03, SC-04, SC-10, SC-14. The Windows Device Host follows the CIS Microsoft Windows benchmark and is checked by
`tools/device-security/05b_device_host_hardening_windows.mjs`; it is not covered by this recipe.

**Status column.** *Recipe* = implemented as configuration in `deploy/edge/` (and schema / syntax checked where a tool was
available) but **not applied to a real host in this repository**. *Gateway* = enforced or reported by the gateway software
itself (tested by `GatewayIntegrationTest` / `HardeningCheck`). *Pack* = verifiable on the built host with the named
device-security pack check. *Manual* = organisational step with evidence outside the repository.

| # | CIS area / control | Requirement | Implementation | Status | Verified by |
|---|---|---|---|---|---|
| 1 | Initial setup – minimal install | Server-minimal, no desktop, no snapd, no avahi / cups / telnet | `cloud-init/autoinstall-user-data.yaml` (`source: ubuntu-server-minimal`, runcmd purge / disable) | Recipe | manual review of `dpkg -l` |
| 2 | Filesystem – full-disk encryption | LUKS2 for root and the gateway state (`/var/lib/wellnessz-gateway`, k3s `/var/lib/rancher`) | autoinstall `storage.layout: lvm` + `password`, first-boot `systemd-cryptenroll --tpm2-pcrs=7`, recovery key escrowed | Recipe | Pack 05 `disk-encryption`, Pack 10 `queue-volume` |
| 3 | Filesystem – unused filesystems / buses | cramfs, hfs, udf, jffs2 …, usb-storage, firewire, thunderbolt disabled | `/etc/modprobe.d/60-wellnessz-blacklist.conf` | Recipe | `modprobe -n -v usb-storage` → `install /bin/false` |
| 4 | Software updates | Security updates automatically in the maintenance window, reboot at 03:30 | `20auto-upgrades`, `52wellnessz-unattended` | Recipe | Pack 05 `auto-updates` |
| 5 | Filesystem integrity | AIDE baseline after build, daily check | `aideinit` in runcmd (dailyaidecheck timer from the package) | Recipe | `aide --check` report |
| 6 | Bootloader | Secure Boot on, firmware / GRUB password, no USB / PXE boot | `secure-boot-tpm.md` (firmware steps), kernel parameters in `60-wellnessz.cfg` | Recipe + Manual | `mokutil --sb-state`; BIOS evidence photo / export |
| 7 | Process hardening | ASLR, `kptr_restrict`, `ptrace_scope`, no core dumps of setuid, unprivileged BPF off, `lockdown=integrity`, `init_on_alloc` | `/etc/sysctl.d/60-wellnessz.conf`, GRUB parameters | Recipe | `sysctl -a` diff against the file |
| 8 | Mandatory access control | AppArmor enabled and enforcing | GRUB `apparmor=1 security=apparmor`, `apparmor-utils` | Recipe | `aa-status` |
| 9 | Warning banners | Legal banner for SSH | `/etc/issue.net` | Recipe | – |
| 10 | Services – time synchronisation | chrony with NTS against the site / group time source; gateway reports skew | chrony drop-in; gateway `ntpOffsetMillis`, `CLOCK_SKEW` finding | Recipe + Gateway | Pack 05 `time-sync`, Pack 13 |
| 11 | Network – kernel parameters | No forwarding, no redirects / source routing, rp_filter, syncookies, IPv6 off where unused | `/etc/sysctl.d/60-wellnessz.conf` | Recipe | `sysctl` |
| 12 | Host-based firewall | Default drop in **and** out; device VLAN → 2575 / 2576 / 8097 only; SSH from the management subnet only; out to the ingest LB 443, DNS, NTP, mirror, EDR / SIEM | `os-policy/nftables/wellnessz-gateway.nft` (`nft -c` syntax-checked) | Recipe | Pack 05 `firewall`, Pack 01 segmentation probes |
| 13 | Access – SSH | Keys only, no root, `AllowGroups sudo`, modern ciphers / KEX / MACs, no forwarding | `/etc/ssh/sshd_config.d/50-wellnessz.conf` | Recipe | Pack 05 `ssh-passwords`, `sshd -T` |
| 14 | Access – accounts | Root locked, one break-glass admin with key, gateway runs as system user `wellnessz` without shell | autoinstall `users`, `useradd --system` | Recipe + Gateway | Pack 05 `service-account`; gateway finding `RUNS_AS_PRIVILEGED_ACCOUNT` |
| 15 | Logging and auditing | auditd with immutable rules (identity, time, firewall, USB / BLE policy, gateway config, modules); journald → SIEM forwarder | `/etc/audit/rules.d/60-wellnessz.rules` (`-e 2`) | Recipe | `auditctl -s` (`enabled 2`) |
| 16 | EDR | Group EDR agent installed and active | site package (not in the recipe – licence / tenant specific) | Manual | Pack 05 `edr` (`gateway.edrService`) |
| 17 | Gateway service sandbox | Non-root, `ProtectSystem=strict`, `NoNewPrivileges`, no capabilities, syscall filter, `IPAddressDeny=any` + allow-list, status page on loopback | unit `apps/wz-device-gateway/service/linux/wellnessz-gateway.service` + drop-in `os-policy/systemd/10-wellnessz-hardening.conf` (`systemd-analyze verify` clean) | Recipe + Gateway | `systemd-analyze security wellnessz-gateway`; Pack 05 `mgmt-port`; gateway finding `MANAGEMENT_PORT_NOT_LOCALHOST` |
| 18 | Key protection | Gateway root key TPM2-sealed (`systemd-creds --with-key=tpm2`), never a plain file | drop-in `LoadCredentialEncrypted=wz-master-key` → `WZ_MASTER_KEY_FILE=%d/wz-master-key` | Recipe | gateway finding `KEY_FILE_PERMISSIONS` absent; `systemd-creds list` |
| 19 | USB device policy (SC-04) | Deny by default; authorise allow-listed VID:PID only; never mass storage or new HID; serial nodes owned by `wellnessz` | kernel `usbcore.authorized_default=0` + `os-policy/udev/70-wellnessz-usb-allowlist.rules` | Recipe | Pack 05 `usb-allow-list` |
| 20 | Bluetooth policy (SC-04) | Default: Bluetooth masked and modules blacklisted. BLE sites: LE only, Secure Connections only, pairing window 120 s, no Just-Works re-pairing, HID only bonded | `os-policy/bluetooth/main.conf`, `input.conf`, modprobe blacklist | Recipe | Pack 06 (`bonded-devices`, allow-list via `/device-ingest/config`) |
| 21 | Plaintext listeners | TLS on device listeners; plaintext only on loopback or under the documented exception | gateway `allow-plaintext-listeners=false` | Gateway | Pack 05 `plaintext-mllp`; finding `PLAINTEXT_LISTENER_EXCEPTION:*` |
| 22 | Store-and-forward at rest | Journal AES-256-GCM, key derived per purpose; on an encrypted volume | `EncryptedJournal` + item 2 | Gateway + Recipe | Pack 10 `queue-volume` |
| 23 | k3s variant (if used) | k3s CIS hardening guide: `--protect-kernel-defaults`, `--secrets-encryption`, Pod Security `restricted` for the namespace, no Traefik, ServiceLB bound to the device-VLAN address, NetworkPolicies of the chart (`deviceGateway.networkPolicy`) | `values-edge-k3s.yaml`, `README.md` (k3s section) | Recipe (chart rendered + kubeconform + Checkov clean) | `kube-bench --benchmark k3s-cis-1.8` (manual) |
| 24 | Drift detection | Hardening status in every heartbeat → `HARDENING_DRIFT` alert | gateway `HardeningCheck` (application level); OS drift = AIDE + auditd + pack 05 quarterly | Gateway + Recipe | `device-security-pack.yml` quarterly run |

Go-live evidence per site: the pack 05 / 06 / 10 JSON files with `.sha256` from the quarterly workflow (or a manual
`--authorised` run on the host), the AIDE baseline hash, the BIOS / Secure Boot evidence and the recovery-key escrow
ticket.
