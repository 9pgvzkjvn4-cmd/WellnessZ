# Secure boot, measured boot and TPM – clinic gateway notes

**Status: recipe / design notes only.** Nothing in this file was executed on hardware in this repository; there was no
gateway hardware or TPM available. The commands are the standard Ubuntu 24.04 / systemd ≥ 252 tooling and must be
validated on the selected gateway model during the image pilot (follow-up E34-HLR-03 / E34-BL-014).

## 1. Hardware selection (procurement criteria)

* x86-64 or arm64 fanless industrial PC with **UEFI**, **Secure Boot** (Microsoft UEFI CA so the signed shim boots),
  discrete or firmware **TPM 2.0** (fTPM acceptable), firmware updates via LVFS / fwupd, two NICs (device VLAN, uplink).
* Firmware supervisor password; boot order locked to the internal disk; USB / PXE boot disabled; chassis intrusion
  logging where available.

## 2. Secure Boot chain

* Ubuntu / Debian boot with **shim (Microsoft-signed) → GRUB (Canonical / Debian-signed) → signed kernel**; kernel
  module signature enforcement follows from Secure Boot (`lockdown=integrity` set by the image).
* No custom unsigned modules. If an out-of-tree driver were ever needed, enrol a site MOK with `mokutil` under change
  control – avoid it.
* Verify: `mokutil --sb-state` → `SecureBoot enabled`; `dmesg | grep -i 'secure boot'`.

## 3. Disk encryption bound to the TPM (measured boot)

The autoinstall creates LUKS2 + LVM with a one-time passphrase; first boot binds it to the TPM and escrows a recovery key:

```bash
dev=$(blkid -t TYPE=crypto_LUKS -o device | head -n1)
systemd-cryptenroll --tpm2-device=auto --tpm2-pcrs=7 "$dev"      # PCR 7 = Secure Boot policy / state
systemd-cryptenroll --recovery-key "$dev"                         # escrow to the vault, then shred
systemd-cryptenroll --wipe-slot=password "$dev"                   # after the escrow is confirmed
# /etc/crypttab: add tpm2-device=auto, then update-initramfs -u
```

* PCR 7 only: survives kernel / initramfs updates (unattended security updates reboot at 03:30) but fails closed when
  Secure Boot is disabled or its keys change → recovery key needed (alert to IT operations).
* Stronger: add PCR 11 with `systemd-pcrlock` / signed PCR policies (unified kernel images). Not in the recipe because
  Ubuntu 24.04 does not ship UKIs by default.
* **Ubuntu Core 24** alternative: TPM-backed FDE and Secure Boot are built in (recovery key via `snap recovery`); the
  gateway would have to be packaged as a strictly confined snap – not built in this increment.

## 4. Gateway key material

| Secret | Where | Protection |
|---|---|---|
| Gateway root key (`WZ_MASTER_KEY_FILE`) – derives the journal, identity and configuration keys (HKDF) | `/etc/wellnessz/gateway/wz-master-key.cred` | `systemd-creds encrypt --with-key=tpm2 --tpm2-pcrs=7`; decrypted by systemd into the service's private credentials directory (`os-policy/systemd/10-wellnessz-hardening.conf`) |
| Group-PKI client key (`WZ_GATEWAY_KEYSTORE`) | PKCS#12 in `/etc/wellnessz/gateway` (0640 root:wellnessz), password as a second TPM-sealed credential | Key generated on the device (CSR / EST), never leaves it; PKCS#11 / TPM-resident key is a follow-up (the JDK key store is file-based today) |
| Enrolment token | file, deleted by the gateway after enrolment | one-time, ≤ 72 h |

On k3s the same root key is delivered as a Kubernetes Secret (`deviceGateway.secrets.masterKey`); k3s must run with
`--secrets-encryption` and the node on the TPM-bound LUKS volume, otherwise the key is only as safe as the disk.

## 5. Attestation (E34-BL-014 – remains *Partially*)

The core records `attested` at enrolment but does **not verify** a TPM quote. What the image can provide today:
`tpm2_createek` / `tpm2_createak` (Endorsement / Attestation keys) and `tpm2_quote` over PCRs 0–7 for a future
verifier (e.g. Keylime or a cloud attestation service). Building the verifier and binding the endpoint certificate to an
AK is outside this increment.

## 6. Evidence for go-live (per site)

`mokutil --sb-state` output, `systemd-cryptenroll "$dev"` slot list (tpm2 + recovery, no password), escrow ticket of the
recovery key, `systemd-creds list` on the gateway, firmware password set (photo / BIOS export), pack 05 JSON.
