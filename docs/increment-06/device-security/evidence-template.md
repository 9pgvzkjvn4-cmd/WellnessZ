# Device security test pack – evidence record

Copy this template once per run (release candidate or quarterly run). Attach the evidence directory (`*.json`, `*.sha256`,
`report.json`, `report.md`) to the change / test ticket. Do not paste tokens, keys or personal data into this record.

## 1. Run

| Field | Value |
|---|---|
| Test / change ticket | |
| Release / build | (e.g. 0.6.0, image digest) |
| Environment | staging / production (read-only scripts only) |
| Pack version | 1.0.0 |
| Date and time (UTC) | |
| Tester(s) | |
| Security Operations approver | |
| Targets file version (hash) | `sha256sum targets.local.json` |
| Run recorded at the core | `GET /device-security/test-pack/runs` id: |

## 2. Results per control

| Control | Title | Outcome (PASS / FAIL / NOT_RUN) | Evidence file(s) | SHA-256 | Notes / exception reference |
|---|---|---|---|---|---|
| SC-01 | Unique identity, mTLS, plaintext refused | | 02_tls_mtls_check.json, 03_mllp_auth_and_robustness.json | | |
| SC-02 | Segmentation, deny-by-default conduits | | 01_network_segmentation_check.json | | |
| SC-03 | Hardened gateway / Device Host images | | 05_gateway_hardening_linux.json, 05b_device_host_hardening_windows.json | | |
| SC-04 | BLE pairing policy, USB allow-list | | 06_ble_pairing_policy.json, 05_… | | |
| SC-05 | Secrets in vault, none in configuration | | 08_secrets_and_config_audit.json | | |
| SC-06 | SBOM, vulnerability thresholds, signed artefacts | | 07_sbom_and_vuln_scan.json | | |
| SC-07 | Vendor posture gate, legacy PC isolation | | 12_device_default_credentials_check.json, 01_… | | |
| SC-08 | Tamper-evident audit, SIEM, PHI masking | | 09_audit_log_integrity.json | | |
| SC-09 | Anomaly detection, isolation < 15 min | | 14_rate_limit_and_anomaly_check.json | | drill minutes: |
| SC-10 | Encryption at rest | | 10_encryption_at_rest_check.json | | |
| SC-11 | API authentication / authorisation | | 04_fhir_api_authz.json, 11_zap_api_baseline.json | | |
| SC-12 | Robustness against malformed messages | | 03_mllp_auth_and_robustness.json | | |
| SC-13 | Rate limiting | | 14_rate_limit_and_anomaly_check.json | | |
| SC-14 | Time synchronisation, certificate expiry | | 13_time_sync_and_cert_expiry.json | | |
| SC-15 | Privacy by design (no PHI in logs / non-production) | | 08_secrets_and_config_audit.json | | DPIA version / status: |
| SC-16 | Pen test, IEC 80001-1 agreement | | report.json | | pen-test report ref:, agreement signed on: |

Overall verdict from `15_report_builder` (`report.json` → `verdict`): ______  · report SHA-256: ______________________

## 3. Findings

| # | Control | Check id | Severity (CRITICAL / HIGH / MEDIUM / LOW) | Description (no PHI, no secrets) | Tracked as (pen-test ref / vulnerability id) | Due date (critical 7 d, high 30 d) | Owner |
|---|---|---|---|---|---|---|---|
| 1 | | | | | | | |

## 4. NOT_RUN justification

| Script / check | Reason (e.g. no Windows Device Host at this site, ZAP not permitted in production) | Compensating evidence |
|---|---|---|
| | | |

## 5. Manual evidence attached

- [ ] Reference network design approval (SC-02)
- [ ] CIS benchmark scan of the gateway and Device Host images (SC-03)
- [ ] Approved BLE device review (SC-04)
- [ ] Vault / KMS rotation evidence (SC-05)
- [ ] Threat models reviewed for this release (SC-06)
- [ ] MDS2 forms / posture exceptions (SC-07)
- [ ] SIEM parser acceptance (SC-08)
- [ ] Tabletop drill minutes (SC-09)
- [ ] Backup encryption policy (SC-10)
- [ ] DPIA approval (SC-15)
- [ ] External penetration test report and IEC 80001-1 agreement (SC-16)

## 6. Sign-off

| Role | Name | Decision (accept / accept with findings / reject) | Date | Signature |
|---|---|---|---|---|
| Cyber team lead | | | | |
| Security Operations | | | | |
| OH system owner | | | | |
