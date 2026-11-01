# Clinic edge – hardened gateway image guidance (E34-HLR-03, E34-HLR-04, E34-F03, E34-BL-003)

Recipe for the Linux host that runs `wz-device-gateway` next to the medical devices. Two run modes share the same
image:

* **systemd** (default, smallest): `apps/wz-device-gateway/service/linux/wellnessz-gateway.service` + the hardening
  drop-in in `os-policy/systemd/`.
* **single-node k3s**: the Helm chart with `values-edge-k3s.yaml` + `environments/edge/<env>.yaml` (component
  `deviceGateway`: one replica, Recreate, persistent journal volume, exec probes on the loopback status page,
  NetworkPolicy for the device zone, LoadBalancer restricted to the device VLAN). Image `wz-device-gateway` is built,
  scanned, SBOM-attested and cosign-signed by `container.yml` and promoted by digest with the other images.

| File | Content | Status |
|---|---|---|
| `hardening-checklist.md` | CIS Ubuntu 24.04 L1 aligned checklist mapped to SC-xx and pack checks | document |
| `cloud-init/autoinstall-user-data.yaml` | Ubuntu Server 24.04 autoinstall (LUKS2 + LVM) + first-boot cloud-init: packages, SSH, sysctl, module blacklist, auditd, unattended upgrades, chrony, policy files, TPM2 enrolment, AIDE | **recipe only** – validated against the subiquity autoinstall schema and the cloud-init 24.4 schema; not installed |
| `os-policy/udev/70-wellnessz-usb-allowlist.rules` | USB deny-by-default (with `usbcore.authorized_default=0`), VID:PID allow-list, no mass storage / new HID, serial node ownership, attach logging | **recipe only** – example VID:PIDs; not tried on hardware |
| `os-policy/bluetooth/main.conf`, `input.conf` | BlueZ: LE only, Secure Connections only, pairing window, no Just-Works re-pairing, bonded HID only | **recipe only** – the gateway has no BLE radio today (BLE via the tablet / kiosk relay) |
| `os-policy/nftables/wellnessz-gateway.nft` | Host firewall, default drop in and out | **recipe only** – `nft -c` syntax check passed; placeholder addresses |
| `os-policy/systemd/10-wellnessz-hardening.conf` | TPM2-sealed root key via `LoadCredentialEncrypted`, extra sandboxing, IP allow-list | **recipe only** – `systemd-analyze verify` passed with the unit; not run under systemd |
| `secure-boot-tpm.md` | Secure Boot chain, TPM-bound LUKS, key protection, attestation gap | notes |

**What is verified here and what is not.** Syntax / schema checks only (listed above). No golden image was built, no
host was hardened, no CIS scan (e.g. `usg audit cis_level1_server` or OpenSCAP) was run, no EDR was installed, and
Secure Boot / TPM behaviour was not exercised – there was no hardware. E34-HLR-03, E34-F03, E34-BL-003 and E34-BL-014
therefore stay *Partially* in the traceability matrix until the image pilot produces the pack 05 / 06 / 10 evidence on a
real gateway.

## Build flow (recipe)

1. Publish `deploy/edge/os-policy/**` and `apps/wz-device-gateway/service/linux/*` of the release to the internal
   artefact store (`https://artefacts.example.internal/wellnessz/edge/<release>/…`, referenced by `source.uri`).
2. Replace the `<placeholders>` in `cloud-init/autoinstall-user-data.yaml` (addresses, admin key, NTS server, site
   name) and the address sets in the nftables file; adjust the USB allow-list to the approved device list of the site
   (E32 option pack P1 `usbAllowList`).
3. Boot the Ubuntu 24.04 live-server ISO with `autoinstall` and the NoCloud seed; on first boot escrow the LUKS
   recovery key and remove the install passphrase (`secure-boot-tpm.md` §3).
4. Install the EDR agent of the group (licence-specific, not in the recipe).
5. Deploy the gateway: jar + systemd unit (seal the root key with `systemd-creds`, provision the client key store and
   the enrolment token into `/etc/wellnessz/gateway`) **or** k3s:
   `curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--disable traefik --protect-kernel-defaults --secrets-encryption --node-ip <uplink-ip>" sh -`
   (pin `INSTALL_K3S_VERSION`, verify the installer checksum), then the Helm release with the edge overlays.
6. Run `tools/device-security/05_gateway_hardening_linux.sh --authorised` (and 06, 10) on the host; archive the JSON +
   `.sha256` with the site certification (E32).
