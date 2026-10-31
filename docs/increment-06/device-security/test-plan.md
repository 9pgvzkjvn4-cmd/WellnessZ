# Device security test pack – test plan and rules of engagement

| | |
|---|---|
| **Epic / requirement** | E34 – Endpoint & integration security · E34-HLR-11, E34-F10, E34-BL-010 / -011 |
| **Pack** | `tools/device-security/` (version 1.0.0) – 15 test scripts plus the report builder, Node 18+ / Bash, no third-party packages |
| **Control matrix** | [`control-mapping.csv`](control-mapping.csv) – SC-01 … SC-16 mapped to IEC 62443, NIST SP 800-53 r5, FDA §524B / premarket guidance, IEC 81001-5-1, OWASP ASVS / API Top 10, ISO 27001 |
| **Evidence** | one JSON file per script (+ `.sha256`), `report.json` / `report.md`; template: [`evidence-template.md`](evidence-template.md) |
| **Owner** | Security Operations (`SECURITY_OPS`); executed with the Cyber team before every production release and quarterly |

## 1. Purpose and scope

The pack lets the Cyber team verify, repeatably and without vendor help, that the device integration engine, the clinic
gateways, the Device Host agents and the provisioning console behave as designed (solution document section 10). It covers:
unique endpoint identity and mutual TLS, segmentation, MLLP / FHIR authentication and robustness, gateway hardening (Linux and
Windows), BLE pairing policy, SBOM and vulnerability scanning, signed driver packages, secrets and configuration, audit-log
integrity, encryption at rest, API baseline scanning, default credentials, time synchronisation and certificate expiry, rate
limiting and the 15-minute isolation playbook.

Out of scope: the devices' own firmware (handled through procurement, segmentation and compensating controls), destructive or
denial-of-service testing, social engineering, production data.

## 2. Rules of engagement

1. **Authorisation.** Every run needs a change / test ticket approved by the system owner (OH) and Security Operations. The
   scripts refuse to start without the flag `--authorised` and a targets file; the flag documents that the ticket exists.
2. **Own systems only.** Targets are the organisation's staging environment and, for the hardening scripts, the gateway /
   Device Host the script runs on. Never point a script at a vendor's or a third party's system. Script 11 (ZAP) and the
   robustness cases of script 03 run against staging only.
3. **Environment.** Staging with synthetic data only (no PHI outside production). Production runs are limited to the
   read-only scripts 05, 05b, 06, 09, 10 and 13 and require the change ticket to say so.
4. **Credentials.** The targets file holds no secrets: bearer tokens, endpoint tokens and database URLs are given as
   `env:NAME` and read from the environment of the CI job / tester. Use dedicated test accounts (`secops1`-style), never a
   personal account. Evidence files redact tokens, keys and e-mail addresses.
5. **Side effects.** Scripts 01, 04, 07, 08 and 14 create short-lived test objects (endpoint identities created with the dev
   / test CA, a test secret, a signed self-test package) and revoke / delete them at the end. Script 14 deliberately isolates
   a sacrificial endpoint; script 04 moves one enrolment token's expiry back with `psql` (staging database only). Script 12
   tries at most five default passwords per management page with pauses to avoid lock-outs.
6. **Stop conditions.** Stop and inform Security Operations on any unexpected service degradation, any sign of real
   compromise, or any PHI in a response or log. Do not continue to "prove" a finding.
7. **Findings.** Every FAIL is recorded as a pen-test / test finding (`POST /device-security/pentest/findings`) or a
   vulnerability (`POST /device-security/sdlc/vulnerabilities`, due dates critical 7 days, high 30 days) and tracked to closure.

## 3. Preconditions

| Item | How |
|---|---|
| Test CA trusted by the core (staging) | `tools/device/dev-ca.sh init` and `client pack`; set `WZ_DEVICE_TRUST_ANCHORS` (property `wellnessz.device.pki.trust-anchors`) to the CA file. In production the group PKI issues test certificates instead. |
| Accounts | SECURITY_OPS, AUDITOR, INTEGRATION_TESTER, DEVICE_ADMIN (console, no person data) and one clinician token for the rate-limit check |
| Demo / test endpoint | an enrolled endpoint with its token and certificate thumbprint (`endpoint` in the targets file); locally the demo identities of `E34DemoSeeder` |
| Targets file | copy `tools/device-security/targets.example.json` to `targets.local.json` (git-ignored) and fill hosts and `env:` names |
| Tools | Node 18+, Bash, `openssl` (dev CA), `psql` (scripts 04 / 10, optional), Docker (script 11, optional), PowerShell on Windows Device Hosts (05b) |

## 4. Test cases

| # | Script | Control | Test cases (check ids in the evidence) | Pass criteria |
|---|---|---|---|---|
| 1 | `01_network_segmentation_check.mjs` | SC-02, SC-07 | TCP probes per zone vs the conduit allow-list; **zone violation** (endpoint bound to a CIDR range called from elsewhere); device-VLAN identity refused; deny-by-default rule in the firewall template | every probe as expected; `zone_violation`; 422 for direct device identities |
| 2 | `02_tls_mtls_check.mjs` | SC-01 | TLS 1.2 / 1.3 only; **mTLS required** (no client certificate → handshake refused / 401 `mtls_required`); **wrong thumbprint** → `certificate_mismatch`; wrong token → `bad_token`; unknown endpoint; plaintext MLLP / HTTP refused | all refusals with the documented code |
| 3 | `03_mllp_auth_and_robustness.mjs` | SC-01, SC-12 | garbage, missing MSH, bad delimiters, truncated, 1 MB oversized, NUL bytes, 50 000 repetitions, 20 fuzzed frames; listener alive afterwards; ingest API unauthenticated / garbage / 2 MB | never AA, no 5xx, listener answers afterwards |
| 4 | `04_fhir_api_authz.mjs` | SC-11 | device token on the user API; person token on ingest / FHIR ingest; console role on person resources; function-level (key rotation); **replayed token** (second use of an enrolment token); **expired enrolment token**; **revoked endpoint** | 401 / 403 / 404 as documented |
| 5 | `05_gateway_hardening_linux.sh` | SC-03, SC-04 | non-root service, management port on localhost, LUKS, default-drop firewall, EDR, NTP, USB allow-list, SSH passwords off, unattended upgrades, no plaintext MLLP | all PASS (exceptions documented) |
| 6 | `05b_device_host_hardening_windows.mjs` | SC-03, SC-04 | BitLocker, Defender / EDR with current signatures, firewall profiles, w32time, USB DenyUnspecified, least-privilege service, SMBv1 off | all PASS or compensating control recorded |
| 7 | `06_ble_pairing_policy.mjs` | SC-04 | LE Secure Connections, allow-list, hashed addresses, bonding, bonded-device review | all PASS |
| 8 | `07_sbom_and_vuln_scan.mjs` | SC-06 | CycloneDX SBOM; dependency-check (CVSS ≥ 7 fails); **unsigned or tampered driver package** rejected, signed package verifies, package with a literal secret never signed; SBOM recorded; remediation SLA | all PASS |
| 9 | `08_secrets_and_config_audit.mjs` | SC-05, SC-15 | configuration secret scan (zero findings); **secrets never returned** (store, list, no-store); core secret scanner; **PHI masking in logs** | zero findings, no value echoed, no PHI pattern in logs |
| 10 | `09_audit_log_integrity.mjs` | SC-08 | platform audit chain; device security event chain; allow-listed, PHI-free event attributes; event classes present | intact, no PHI |
| 11 | `10_encryption_at_rest_check.sh` | SC-10 | secrets, signing keys, outbox payloads encrypted; DB TLS; queue on encrypted volume; backup policy | all PASS |
| 12 | `11_zap_api_baseline.sh` | SC-11 | ZAP API scan of the staging OpenAPI document | no high-risk alert |
| 13 | `12_device_default_credentials_check.mjs` | SC-07 | vendor default passwords on management pages | none accepted |
| 14 | `13_time_sync_and_cert_expiry.mjs` | SC-14 | tester NTP; core clock skew ≤ 120 s; TLS certificates > 30 days; endpoint certificates expiring within 30 days alerted, none expired | all PASS |
| 15 | `14_rate_limit_and_anomaly_check.mjs` | SC-09, SC-13 | 429 with Retry-After; **rate and anomaly isolation within the time limit** (FAILED_AUTH_BURST → ISOLATED ≤ 15 min, valid credentials refused, incident `withinSla`, alert raised, release blocked until re-certification) | drill ≤ SLA |
| – | `15_report_builder.mjs` | SC-16 + all | consolidates the evidence against SC-01 … SC-16, writes `report.json` / `report.md`, `--submit` records the run at the core | no control FAIL |

The automated part of the same controls also runs in the build: `Increment6SecurityIntegrationTest` (enrolment with a test
CA, every authentication failure code, rotation, renewal, 15-minute isolation, re-certification gate, signatures, write-only
secrets, SIEM / ITSM allow-lists, chain verification) and the E34 unit tests.

## 5. Execution

```bash
cd tools/device-security
cp targets.example.json targets.local.json      # fill in; secrets as env:NAME
export WZ_PACK_SECOPS_TOKEN=... WZ_PACK_AUDITOR_TOKEN=... WZ_PACK_TESTER_TOKEN=...   # from the IdP test clients
./run-all.sh --authorised --targets targets.local.json --out ./device-security-evidence --submit
# hardening scripts run on the gateway / Device Host itself:
sudo -u wz-audit ./05_gateway_hardening_linux.sh --authorised --targets targets.local.json
node 05b_device_host_hardening_windows.mjs --authorised --targets targets.local.json     # on Windows
```

Exit codes: 0 = PASS or NOT_RUN only, 1 = at least one FAIL, 2 = refused (missing authorisation / targets).

**CI / schedule (E34-BL-011).** The pipeline stage `device-security-pack` runs `run-all.sh` against staging after deployment
and before the production promotion; the quarterly schedule re-runs it. Evidence directories are archived with the release
(retention class `DEVICE_SECURITY_EVENT`, 6 years) and the run is recorded with `--submit`
(`GET /device-security/test-pack/runs`); `GET /device-security/go-live-readiness` requires the last run without FAIL.

## 6. Mapping of the brief's named tests

| Named test | Script / check |
|---|---|
| mTLS required | 02 `mtls-handshake`, `mtls-required` |
| wrong thumbprint | 02 `wrong-thumbprint` |
| replayed token | 04 `replayed-enrolment-token` (one-time enrolment token); rotation grace and expiry are covered by the integration test |
| revoked endpoint | 04 `revoked-endpoint` |
| zone violation | 01 `zone-violation`, `device-vlan-direct` |
| unsigned or tampered driver package | 07 `package-unsigned`, `package-tampered` |
| expired enrolment token | 04 `expired-enrolment-token` |
| rate and anomaly isolation within the time limit | 14 `rate-limit-429`, `isolation-within-sla`, `drill-record` |
| secrets never returned | 08 `secret-not-echoed`, `secret-metadata-only`, `no-store` |
| PHI masking in logs | 08 `phi-in-logs:*`; 09 `event-attributes-phi-free` |

## 7. Sign-off

The Cyber team signs the report (`report.md`) with the evidence hashes in the [evidence template](evidence-template.md). The
go-live gate additionally needs the external penetration test without open critical findings and the signed IEC 80001-1
responsibility agreement (SC-16).
