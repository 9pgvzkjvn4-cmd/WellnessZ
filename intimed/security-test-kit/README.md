# IntiMed security test kit (IM-E06-F10)

Python 3 standard library + `openssl` only (stock macOS). Runs against a running platform and writes per-script JSON and a
consolidated `results/REPORT.md` (input to SC-16 evidence).

```bash
INTIMED_HOME=~/IntiMed-Demo-Data ./run-kit.sh
```

Environment: `INTIMED_URL` (default https://localhost:9443), `INTIMED_CA` (default `$INTIMED_HOME/pki/root-ca.pem`),
`KIT_ADMIN`/`KIT_SOC`/`KIT_DEV`/`KIT_ADMIN_B` persona names, `INTIMED_PASSWORD`, `INTIMED_TOTP_SECRET` (outside the demo
profile the kit computes TOTP codes itself).

| Script | Controls | Local |
|---|---|---|
| 01 network segmentation | SC-02/SC-07 | deferred (real segments) |
| 02 TLS / mTLS | SC-01, SC-14, SC-11 | ✓ |
| 03 MLLP authentication & robustness | SC-01, SC-12, SC-09 | ✓ (enrols a probe with a locally generated CSR) |
| 04 FHIR API authorisation | SC-11, SC-18 | ✓ |
| 05 / 05b host hardening | SC-03 | deferred (gateway / Windows images) |
| 06 BLE pairing policy (radio) | SC-04 | policy part in 18; radio deferred |
| 07 SBOM & vulnerability scan | SC-06 | ✓ (grype/trivy if installed) |
| 08 secrets & configuration audit | SC-05, SC-15 | ✓ |
| 09 audit log integrity | SC-08 | ✓ |
| 10 encryption at rest | SC-10 | ✓ |
| 11 ZAP API baseline | SC-11 | optional (needs ZAP) |
| 12 default credentials | SC-07, SC-13 | ✓ |
| 13 time sync & certificate expiry | SC-14 | ✓ |
| 14 rate limiting & anomaly | SC-09, SC-13 | ✓ |
| 15 report builder | SC-16 | ✓ |
| 16 cloud posture – `16_azure_posture_check.py` (Azure Qatar Central) / `16_gcp_posture_check.py` (GCP me-central1) | SC-17, SC-19 | offline against `fixtures/azure-*` / `fixtures/gcp-*`; live in UAT |
| 17 tenant isolation | SC-18 | ✓ |
| 18 recommendation guardrails | SC-19, RR-03/04 | ✓ |
| 19 exchange harness gate | SC-20 | ✓ |

Last run on the build machine: **69 checks, 69 passed**.

Cloud runs: `run-kit-aks.sh` (Azure, `AZ_RESOURCE_GROUP`) and `run-kit-gke.sh` (IntiMed_GCP, `GCP_PROJECT`,
`INTIMED_ENV=prod` for the production-only checks). Offline proof of the GCP check:
`INTIMED_ENV=prod python3 16_gcp_posture_check.py --from-json fixtures/gcp-good` → 31/31 PASS; `fixtures/gcp-bad` → 20 FAIL.
