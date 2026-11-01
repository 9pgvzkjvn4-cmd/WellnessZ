# WellnessZ OHCM – Integration Tester Guide

**Version 1.0**

*WellnessZ OHCM platform – increment 6 (Medical Device Integration Engine)*

| | |
|---|---|
| **Document** | Increment 6 – 05 Integration Tester Guide |
| **Audience** | Integration testers (`INTEGRATION_TESTER`), QA, Security Operations and the Cyber team (security test pack), developers |
| **Applies to** | `/device-engine/**` (drivers, simulators, golden datasets, dead letters, operations), `/device-ingest/**` (machine API), `tools/device-security/`, OH workspace `#/devices/simulators`, `#/devices/dead-letters`, `#/devices/operations`, `#/devices/unmatched` |
| **Companion documents** | [01 – Solution Design](01-Solution-Design-E31-E34.md) · [04 – Device Administrator Guide](04-Device-Administrator-Guide.md) · [API contract](API-Contract-E31-E34.md) §6–§12, §15.11, §20 · [test plan](device-security/test-plan.md) · Architecture 03 §19 |

> **Scope of what you can prove.** This release has **no device lab**: drivers are exercised with synthetic golden
> datasets that follow published formats, through simulators that replay them into the pipeline. Passing conformance
> proves the driver, the mappings, the gates and the bindings against those datasets – it does **not** prove that a real
> device or vendor software emits exactly that format. Rows of the device inventory not marked *High* confidence need the
> vendor's interface specification before a package is certified. Never use real patient data: simulators and golden
> datasets are synthetic, and test traffic is retained only as `DEVICE_RAW_UNFILED` (1 year).

## 1. Environment

* **Local stack:** core on `http://localhost:8080`, realm users `inttester1` (INTEGRATION_TESTER), `deviceadmin1`,
  `deviceapprover1`, `secops1`, `nurse1`; demo data loaded with the `local` profile. Get a bearer token as in setup
  guide 01 (`TOKEN=…`) or work in the OH workspace through the BFF.
* **Demo endpoints** (local / test only, documented non-secrets): token `wzdemo-<endpoint id in lower case>-not-a-secret`,
  thumbprint = hex SHA-256 of `wellnessz-demo-certificate:<endpoint id>`. The dev stack has no mTLS ingress, so test
  clients send `X-Client-Cert-Sha256` themselves:

```bash
EP=EP-LHR-GW-01
TOK="wzdemo-$(echo $EP | tr 'A-Z' 'a-z')-not-a-secret"
THUMB=$(printf 'wellnessz-demo-certificate:%s' "$EP" | openssl dgst -sha256 -r | cut -d' ' -f1)
```

* **Machine call example** (HL7 PCD-01 vitals for `DEV-000003`):

```bash
curl -s -X POST http://localhost:8080/api/v1/device-ingest/hl7v2 \
  -H "X-Device-Endpoint: $EP" -H "Authorization: DeviceToken $TOK" -H "X-Client-Cert-Sha256: $THUMB" \
  -H "Idempotency-Key: $EP:$(date +%s)" -H "X-Device-Instance: DEV-000003" -H 'Content-Type: application/hl7-v2' \
  --data-binary @modules/wz-e31-device-engine/src/main/resources/e31/golden/vitals/csm-pcd01/normal.hl7
```

Expected: an HL7 ACK with `MSA|AA`. Repeating the call with the same `Idempotency-Key` returns the original receipt
(`DUPLICATE`) and files nothing twice.

## 2. Golden datasets

94 synthetic datasets live in `modules/wz-e31-device-engine/src/main/resources/e31/golden/<family>/<package>/` with an
index `golden/index.json` (`datasetId`, `family`, `adapter`, `packageId`, `kind`, `resource`, `contentType`, `description`,
`headers`, `expect`).

| Kind | Count | Purpose |
|---|---|---|
| `NORMAL` | 18 | A typical result per package |
| `EDGE` | 21 | Boundary values, missing optional fields, special IEEE 11073 values, missing device time |
| `MALFORMED` | 18 | Broken envelopes, missing segments, bad delimiters – must dead-letter or answer AE, never crash |
| `TIME_SKEW` | 18 | Edge clock offset (header `edgeClockOffsetSeconds`) – must be corrected and flagged |
| `DUPLICATE` | 18 | Same payload twice with the same idempotency key – must file once |
| `PERFORMANCE` | 1 | `perf/mixed-50ps.json` – profile for a 50 msg/s mixed load (no load test has been run yet) |

By family: vitals 33, cardiology 10, POC 10, spirometry 6, and 5 each for audiometry, drug and alcohol, wearables,
respiratory protection, imaging, vision and exposure. List them with `GET /device-engine/golden-datasets?family=VITALS`.

**Adding a dataset.** Put the file next to the package's other datasets, add an index entry with a unique `datasetId`
(`<family>/<package>/<kind>`), reference it from the package's `testVectors` with the expected values and gate outcomes, and
run the conformance tests (Section 4). Keep data synthetic (employee ids `E1001`…, fictitious names) – the PHI patterns of
test script 08 also scan logs.

## 3. Simulators

`POST /device-engine/simulators/{adapter}/run` (INTEGRATION_TESTER, DEVICE_ADMIN):

```json
{ "datasetId": "vitals/csm-pcd01/normal", "count": 5, "ratePerSecond": 2,
  "driverPackageId": "vitals.welchallyn.csm-pcd01", "driverVersion": "1.0.0", "targetTestForm": false }
```

* The simulator feeds the dataset into the real pipeline as the virtual endpoint `SIM-<adapter>` (no token, test traffic
  flagged). `{adapter}` is the dataset's driver adapter type and must match it: `HL7V2`, `FHIR`, `POCT1A`, `FILE_CSV`,
  `FILE_XML`, `FILE_GDT`, `FILE_PDF`, `BLE_GATT`, `SERIAL`, `WEBHOOK_JSON`, `CLOUD_POLL_JSON`, `DICOMWEB`, `OCR`, `MANUAL`
  (`SimulatorService.KIND`); for example `POST /device-engine/simulators/HL7V2/run`. The response gives accepted,
  duplicate and failed counts, observation ids and statuses, and an evidence SHA-256.
* Duplicate datasets are sent twice with the same idempotency key; time-skew datasets carry a shifted edge clock.
* Observations are **never routed to production sinks** unless `targetTestForm=true`, which routes them to the wizard test
  form (wizard step 6).
* Runs are recorded in `deviceint.simulator_run` and audited (`DEVICE_SIMULATOR_RUN`). In the OH workspace use
  `#/devices/simulators`.

Gateway-side simulation (edge behaviour without hardware): `--simulate-serial=<file>` or `--simulate-serial=-` (stdin)
replays serial frames; MLLP can be exercised with any MLLP client against a loopback listener; folder watchers pick up files
dropped into the watched folder; see the gateway README.

## 4. Driver conformance and registration

1. **Validate** – `POST /device-engine/drivers/validate` with the package JSON: schema, secret scan (literal values for
   password / secret / token / API key / private key / credential keys are refused unless `devsec:` / `vault:`), mapping
   coverage (every parsed field mapped, LOINC mandatory, UCUM for quantities, MDC where known), known gates, test vectors
   present (≥ 1 normal, ≥ 1 malformed, ≥ 1 edge). Nothing is stored.
2. **Conformance** – `POST /device-engine/drivers/conformance` runs every test vector through the in-memory `DryRun`
   pipeline (parse → normalise → validate → binding preview) and returns `{passed, results[{name, outcome, diff}]}` plus the
   **evidence SHA-256**. Keep the evidence hash – the registry asks for the conformance run.
3. **Sign** – `POST /device-engine/drivers/sign` creates a signing request; a DEVICE_APPROVER approves
   (`POST /device-engine/drivers/sign/{requestId}/approve`), which signs with the active E34 key (Ed25519). Alternatively
   SECURITY_OPS signs through `POST /device-security/packages/sign`.
4. **Register** – `POST /device-catalogue/drivers` with the signed package and the conformance run → `REGISTERED`. The
   signature is verified again (422 `signature_invalid` and a security event if it fails); versions are immutable – change
   the version for every change (MAJOR when mapping keys or bindings change incompatibly).
5. **Certify** – a DEVICE_APPROVER who is not the registrant certifies the version. Only certified versions can be used on
   production stations.

The build runs the same checks for every bundled package: `BundledPackagesConformanceTest`
(`everySamplePackageValidatesWithFullCoverage`, `everyTestVectorPassesInTheSimulator`,
`everyPackageHasTheFiveGoldenDatasetKinds`, `goldenDatasetsParseAsDeclared`) and `RegistryPackagesCompatibilityTest`.

```bash
./mvnw -pl modules/wz-e31-device-engine -am test -Dtest='BundledPackagesConformanceTest,RegistryPackagesCompatibilityTest,ParsersTest' -Dsurefire.failIfNoSpecifiedTests=false
```

**Revocation.** SECURITY_OPS can revoke a package version (`POST /device-catalogue/drivers/{id}/{version}/revoke`) or a
signing key (`POST /device-security/signing-keys/{keyId}/revoke`); every instance on an affected version is SUSPENDED and the
on-duty administrator is alerted.

## 5. Dead letters and replay

| Situation | Behaviour |
|---|---|
| Deterministic failure (unparseable, profile violation, missing mapping, unconvertible unit, invalid signature, unpaired PDF after 10 min) | Dead-lettered at once; HL7 answered **AE** (the device must not resend) |
| Transient failure (stage exception, routing target unavailable) | Retried 5 s, 30 s, 2 min, 10 min, 1 h; dead-lettered after `device.dlq.max-attempts` (5) |
| Endpoint isolated while messages are in flight | Parked with code `ENDPOINT_ISOLATED` |

* **List** – `GET /device-engine/dead-letters?stage=&status=OPEN` (masked detail; DEVICE_ADMIN, INTEGRATION_TESTER,
  PLATFORM_ADMIN) or `#/devices/dead-letters`.
* **Replay one** – `POST /device-engine/dead-letters/{id}/replay {"driverVersion": "1.0.1"}` (version optional – default the
  currently published one). Replay re-runs the message **from the stored raw payload**.
* **Discard** – `POST /device-engine/dead-letters/{id}/discard {"reason": "…"}` – the raw payload becomes `DEVICE_RAW_UNFILED`.
* **Replay a range** – `POST /device-engine/replay {"from": "…", "to": "…", "deviceInstanceId": "DEV-000003", "stageFrom": "PARSE"}`
  (DEVICE_ADMIN, audited `DEVICE_REPLAY_RANGE`).
* **No duplicates:** the observation id is a UUIDv5 of message id + record index + binding keys, and every sink is idempotent
  on it, so replaying a day's traffic never files anything twice (`Increment6EngineIntegrationTest#unmatchedQueueResolutionExposureRoutingDeadLetterReplayWithoutDuplicates`).

**Typical test:** post `vitals/csm-pcd01/missing-pid.hl7` or a malformed dataset → AE and a dead letter; fix the driver or
the data, replay → routed once; replay again → no second result.

## 6. The unmatched queue

* Results whose association confidence is below **0.95**, ambiguous (two candidates within 0.05) or in conflict (session
  employee ≠ employee in the message) are **UNMATCHED** – never filed. Candidates at or above **0.60** are shown as
  suggestions.
* Console roles see only counts (`GET /device-engine/unmatched/count?siteCode=` → `{open, overdue}`); **clinicians** of the
  site team resolve items (`#/devices/unmatched`, `POST /device-engine/unmatched/{id}/resolve` with `employeeId` and a reason
  code `CONFIRMED_IDENTITY | WORKLIST_ERROR | BADGE_ERROR | TEST_TRAFFIC | DUPLICATE | OTHER`, or `discard`). The item re-enters
  validation. SLA 8 hours (`device.unmatched.resolution-sla-hours`); alert `UNMATCHED_SLA`.
* Restricted categories (breath alcohol) show "restricted reading" without values in the queue.
* **Test cases:** `vitals/csm-pcd01/unknown-employee.hl7` without a capture session → unmatched with a suggestion; a message for
  E1002 while a session for E1001 is open on the device → conflict → unmatched; fuzzy name matches are capped at 0.70 and can
  never auto-file.

## 7. Conformance areas to cover per new driver

| Area | What to check | Reference test |
|---|---|---|
| Parsing | All fields, units, identity (employee, badge, serial, order), device time and time zone | `ParsersTest` |
| Normalisation | LOINC / UCUM / MDC on every component, conversions, derived values (BMI, predicted) | `NormaliserTest`, `CalculationsTest` |
| Gates | Each gate in the package: PASS, WARN and BLOCK examples | `GatesTest` |
| Association | Session, worklist and vendor-id cases; restricted rules | `AssociationEngineTest` |
| Bindings | Every binding key exists in the form registry; preview shows the expected fields | `WizardRulesTest`, wizard step 5 preview |
| Robustness | Malformed, oversized, duplicate, time-skew datasets | `BundledPackagesConformanceTest` |
| Edge | Gateway connector for the option (framing, ACK, de-duplication, queueing) | gateway `ConnectorsTest`, `MllpFramingTest`, `FileWatcherTest` |

## 8. Operations checks

* `GET /device-engine/ops/connectors` – connector health (`CONNECTED`, `IDLE`, `ERROR`, `STALE` > 5 min, `SILENT` > 30 min).
* `GET /device-engine/ops/metrics?window=PT1H` – messages, p95 throughput, latency p50 / p95, parse error, validation block and
  unmatched rates, open dead letters and unmatched items, SLA breaches.
* `GET /device-engine/ops/alerts` – `STALE`, `SILENT`, `ERROR_RATE`, `LATENCY`, `UNMATCHED_SLA`, `QUEUE_CAPACITY`,
  `CALIBRATION_DUE`.
* Micrometer meters (`/actuator/prometheus` on the management port): `wz.device.messages`, `wz.device.latency`,
  `wz.device.unmatched`, `wz.device.dead_letters`; gateway `wz_gateway_*`.
* `GET /device-engine/archive/{archiveId}/verify` – recomputes the raw-payload hash (`intact: true`).

## 9. Security test pack

The pack (`tools/device-security/`, Node 18+ and Bash, no third-party packages) lets the Cyber team verify controls SC-01 …
SC-16 repeatably. Rules of engagement, test cases and pass criteria are in [`device-security/test-plan.md`](device-security/test-plan.md);
evidence format in [`evidence-template.md`](device-security/evidence-template.md); control matrix in
[`control-mapping.csv`](device-security/control-mapping.csv).

| Script | Controls |
|---|---|
| `01_network_segmentation_check.mjs` | SC-02, SC-07 |
| `02_tls_mtls_check.mjs` | SC-01 |
| `03_mllp_auth_and_robustness.mjs` | SC-01, SC-12 |
| `04_fhir_api_authz.mjs` | SC-11 |
| `05_gateway_hardening_linux.sh`, `05b_device_host_hardening_windows.mjs` | SC-03, SC-04 |
| `06_ble_pairing_policy.mjs` | SC-04 |
| `07_sbom_and_vuln_scan.mjs` | SC-06 |
| `08_secrets_and_config_audit.mjs` | SC-05, SC-15 |
| `09_audit_log_integrity.mjs` | SC-08 |
| `10_encryption_at_rest_check.sh` | SC-10 |
| `11_zap_api_baseline.sh` | SC-11 |
| `12_device_default_credentials_check.mjs` | SC-07 |
| `13_time_sync_and_cert_expiry.mjs` | SC-14 |
| `14_rate_limit_and_anomaly_check.mjs` | SC-09, SC-13 |
| `15_report_builder.mjs` | SC-16 + consolidation |

**Running it.**

1. Get the change / test ticket approved (system owner and Security Operations). Scripts refuse to run without
   `--authorised` and a targets file.
2. Locally: `tools/device/dev-ca.sh init && tools/device/dev-ca.sh client pack`; start the core with
   `WZ_DEVICE_TRUST_ANCHORS=$PWD/tools/device/dev-ca/ca.pem`. In staging the group PKI issues the test certificate.
3. `cp tools/device-security/targets.example.json tools/device-security/targets.local.json` (git-ignored) and fill hosts;
   secrets only as `env:NAME`. **Adjust the gateway block to the delivered gateway**: service `wellnessz-gateway`,
   management (status) port `8095`, queue directory `/var/lib/wellnessz-gateway/queue` – the example file still shows
   `wz-device-gateway`, `8091` and `/var/lib/wz-device-gateway/queue`.
4. Run all scripts and the report builder:

```bash
tools/device-security/run-all.sh --authorised --targets tools/device-security/targets.local.json --out ./device-security-evidence --submit
```

5. Each script writes one JSON evidence file plus `.sha256` and a one-line summary; exit code 0 (PASS / NOT_RUN), 1 (FAIL),
   2 (refused). `15_report_builder.mjs` writes `report.json` / `report.md` against SC-01 … SC-16; `--submit` records the run
   at `POST /device-security/test-pack/runs` (visible in `#/security/devices/assurance` and used by the go-live gate).
6. Record every FAIL as a finding (`POST /device-security/pentest/findings`) or a vulnerability
   (`POST /device-security/sdlc/vulnerabilities` – due dates critical 7 days, high 30 days).

**Side effects to expect** (test plan §2): scripts 01, 04, 07, 08 and 14 create short-lived test objects and remove them;
script 14 deliberately isolates a sacrificial endpoint; script 04 moves one enrolment token's expiry with `psql` (staging only);
script 12 tries at most five default passwords per page. Production runs are limited to the read-only scripts 05, 05b, 06, 09,
10 and 13.

**Status in this release.** The pack is delivered but **has not yet been executed in staging**; it has no CI stage or quarterly
schedule yet (E34-BL-010, E34-BL-011). The automated part of the same controls runs in every build in
`Increment6SecurityIntegrationTest` and the E34 unit tests.

## 10. End-to-end script

`tools/e2e/e2e-increment6.js` (Playwright, real Keycloak logins through the BFF; device calls directly to the core with the
demo endpoint tokens and the thumbprint header) covers the 21 checks of contract §20: console login without person data,
catalogue totals, wizard with four-eyes and certificate PDF, publish with one-time token, enrolment, authentication refusals
and security events, capture → preview → accept → E03 result, duplicate, lockout and quick QC, unmatched resolution, dead letter
and replay, OCR / manual fallback, restricted breath alcohol, webhook HMAC, batch store-and-forward, isolation and quarantine,
decommissioning, manager counts, employee summary and consent, chain verification. Result: **<E2E>**. Run it on a freshly
purged local database after the increment 1–5 scripts.

## 11. Troubleshooting for testers

| Symptom | Cause | Fix |
|---|---|---|
| 401 `mtls_required` | No `X-Client-Cert-Sha256` header (dev stack) | Send the demo thumbprint |
| 401 `bad_token` / `certificate_mismatch` | Wrong demo token or thumbprint for the endpoint | Recompute from the endpoint id |
| 401 `zone_violation` | Caller address not in the endpoint's gateway-zone CIDRs | Locally the demo CIDRs cover loopback and RFC 1918; elsewhere ask SECURITY_OPS |
| 403 `device_not_authorised_for_endpoint` | Instance not bound to that endpoint | Use the fronting endpoint (e.g. DEV-000003 → `EP-LHR-GW-01`) |
| 404 `device_not_published` | Instance not published / decommissioned | Publish through the wizard |
| 409 `device_quarantined` | Endpoint isolated | Use another instance or ask SECURITY_OPS |
| HL7 AE | Parse or profile error | Check the dead letter detail (masked) and the driver |
| Result stays `PENDING_VERIFICATION` | Verify-before-post | A clinician must accept in the capture preview |
| Result `BLOCKED` | A gate blocked (calibration, QC, plausibility, attestation …) | Read the flag message in the preview; fix the cause |
| Conformance diff on a value | Mapping, conversion or decimals | Compare with the dataset; fix the package and bump the version |
| `signature_invalid` at registration | Package changed after signing or wrong key | Re-sign the exact canonical package |
