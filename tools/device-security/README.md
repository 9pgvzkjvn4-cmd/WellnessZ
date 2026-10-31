# Device security test pack (E34-HLR-11, E34-F10)

Defensive, repeatable checks for the Cyber team: 15 test scripts and a report builder, Node 18+ and Bash only (no third-party
packages). Every script refuses to run without `--authorised` and a targets file, writes one JSON evidence file (+ `.sha256`)
and a one-line summary, and exits 0 (PASS / NOT_RUN), 1 (FAIL) or 2 (refused).

| Script | Control(s) | What it proves |
|---|---|---|
| `01_network_segmentation_check.mjs` | SC-02, SC-07 | conduit allow-list probes, zone violation, no direct device identities |
| `02_tls_mtls_check.mjs` | SC-01 | TLS ≥ 1.2, mTLS required, wrong thumbprint / token refused, plaintext refused |
| `03_mllp_auth_and_robustness.mjs` | SC-01, SC-12 | malformed / oversized / fuzzed HL7 → AE / AR, no crash |
| `04_fhir_api_authz.mjs` | SC-11 | device vs person tokens, replayed / expired enrolment token, revoked endpoint, object-level access |
| `05_gateway_hardening_linux.sh` | SC-03, SC-04 | CIS-style gateway checks incl. USB allow-list |
| `05b_device_host_hardening_windows.mjs` | SC-03, SC-04 | Windows Device Host checks (PowerShell, read-only) |
| `06_ble_pairing_policy.mjs` | SC-04 | LE Secure Connections, allow-list, hashed addresses |
| `07_sbom_and_vuln_scan.mjs` | SC-06 | CycloneDX SBOM, dependency-check, unsigned / tampered driver packages rejected |
| `08_secrets_and_config_audit.mjs` | SC-05, SC-15 | secret scan, secrets never returned, PHI masking in logs |
| `09_audit_log_integrity.mjs` | SC-08 | audit and security-event hash chains, PHI-free events |
| `10_encryption_at_rest_check.sh` | SC-10 | field encryption, DB TLS, encrypted queue volume, backups |
| `11_zap_api_baseline.sh` | SC-11 | OWASP ZAP API baseline (staging) |
| `12_device_default_credentials_check.mjs` | SC-07 | vendor default passwords refused |
| `13_time_sync_and_cert_expiry.mjs` | SC-14 | clock skew, TLS / endpoint certificate expiry |
| `14_rate_limit_and_anomaly_check.mjs` | SC-09, SC-13 | 429 behaviour, FAILED_AUTH_BURST isolation ≤ 15 min (drill record) |
| `15_report_builder.mjs` | SC-16 + all | report against the control matrix; `--submit` records the run |

```bash
cp targets.example.json targets.local.json   # git-ignored; secrets only as env:NAME
./run-all.sh --authorised --targets targets.local.json --out ./device-security-evidence [--submit]
```

Test plan and rules of engagement, evidence template and control matrix (IEC 62443, NIST SP 800-53, FDA §524B / premarket
guidance, IEC 81001-5-1, OWASP): `docs/increment-06/device-security/`.

**Local stack.** Create the throw-away CA with `tools/device/dev-ca.sh init && tools/device/dev-ca.sh client pack`, start the
core with `WZ_DEVICE_TRUST_ANCHORS=$PWD/tools/device/dev-ca/ca.pem`, and use a demo identity of `E34DemoSeeder` as `endpoint`
(local / test profiles only, documented non-secrets): token `wzdemo-<endpoint id in lower case>-not-a-secret`, thumbprint
`SHA-256("wellnessz-demo-certificate:<endpoint id>")`, e.g. `EP-LHR-GW-01`. The dev stack has no mTLS ingress, so the scripts
send the thumbprint header themselves, exactly as the E2E script does.
