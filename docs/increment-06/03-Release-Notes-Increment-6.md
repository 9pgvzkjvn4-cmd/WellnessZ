# WellnessZ OHCM – Release Notes, Increment 6 (E31 Device Integration Engine + E32 Device Console + E33 Module-Embedded Capture + E34 Endpoint & Integration Security)

**Version 1.1**

*WellnessZ OHCM platform – increment 6 (Medical Device Integration Engine)*

| | |
|---|---|
| **Document** | Increment 6 – 03 Release Notes |
| **Release content** | E31 Medical Device Integration Engine · E32 Device Catalogue, Administration & Connectivity Provisioning Console · E33 Module-Embedded Device Capture · E34 Endpoint & Integration Security · edge application `wz-device-gateway` · device security test pack |
| **Audience** | CIO, CMO, Nursing Head, device administrators, integration testers, CISO / Security Operations, Cyber GRC, DPO, platform operations, developers |
| **Database** | Flyway V21, V22, V23, V24, V25, V26 (+ repeatable demo data `R__demo_e32_device_console.sql`, `R__demo_e34_device_security.sql` and four demo seeders – `local` / test profiles only) |
| **Applications** | **New:** `apps/wz-device-gateway` (edge – clinic gateway and Device Host agent). Four new backend modules inside `wz-core-app`; new screens in the OH workspace, employee app and manager workspace |
| **Revision 1.1** | Second build round (1 November 2026): P6 vendor-cloud polling (OAuth2 client credentials), outbound orders / worklist (ORM^O01, OML^O21, ADT^A04 / A08) over MLLP, start-from-form, historic retrieval, tenant-scoped catalogue / configurations / signing keys and per-tenant connector limits, driver SDK command line, data fixes (BLE pairing, GE MAC catalogue route), web screens; V26. See Section 2.6 |
| **Companion documents** | [01 – Solution Design](01-Solution-Design-E31-E34.md) · [02 – Requirements Traceability Matrix](02-Requirements-Traceability-Matrix.md) · [04 – Device Administrator Guide](04-Device-Administrator-Guide.md) · [05 – Integration Tester Guide](05-Integration-Tester-Guide.md) · [API contract](API-Contract-E31-E34.md) · [device security pack](device-security/) · [gateway README](../../apps/wz-device-gateway/README.md) |

---

## 1. Highlights

* **Capture from device in the OH form.** Nurses press *Capture from device*, pick the device at their station (only
  certified, in-calibration devices – no ports, no pairing codes), take the measurement and see the result with units,
  quality flags and provenance before accepting it. Accepted values land read-only in the form, marked "device-sourced",
  and the E03 result carries the observation id, capture path, driver version and verifier.
* **One vendor-neutral engine for every connection type.** HL7 v2 (IHE PCD-01) with synchronous ACK, FHIR R4, POCT1-A
  data managers, vendor-software exports (CSV, XML, GDT, PDF) and read-only databases, Bluetooth LE health profiles,
  serial frames, signed vendor webhooks, DICOMweb metadata with streamed images and PDFs, photo / OCR and manual entry with
  attestation. New models are added as **signed, declarative driver packages** – configuration, not code.
* **Quality before speed.** Expired 3-L syringe or daily biological checks lock the device out; ATS/ERS grades, GLI-2012
  predicted values, test-retest validity, BP cuff validation, plausibility and clock-skew checks run before posting. Wrong
  employee? Nothing below 95 % association confidence is ever filed automatically – it goes to the unmatched queue.
* **A governed console for device administrators.** The client's 133-device inventory is in the catalogue; ten
  connectivity option packs guide configuration by accepted practice; an eight-step wizard ends with a **four-eyes
  certification** and a signed certification PDF; fleet health, calibration / QC, governance with sealed export / import,
  vendor security posture and request-a-model are included.
* **Zero-trust device endpoints.** Every gateway, agent, tablet and kiosk has its own certificate-bound identity and token,
  enrols zero-touch, and is checked on every call. Fifteen anomaly rules, automatic isolation within 15 minutes, a
  hash-chained security log forwarded to the SIEM, and a 15-script **security test pack** for the Cyber team.
* **Offline is normal.** The gateway stores every message encrypted before acknowledging the device and forwards it
  later with the original idempotency key – no loss, no duplicates. Screening days of 200 employees reconcile without loss.

## 2. What is new, by epic and feature

### 2.1 E31 – Medical Device Integration Engine

| Feature | Delivered |
|---|---|
| F01 Pipeline | Acquire → parse → normalise → associate → validate → route → persist → acknowledge; raw payload stored first (AES-GCM, SHA-256); deterministic UUIDv5 observation ids; dead letters and replay (item, range, pinned driver version) |
| F02 Adapters | HL7 v2 ORU^R01 PCD-01 **and outbound ORM^O01 / OML^O21 / ADT^A04 / ADT^A08 / QRY^R02 (HAPI) with device ACK**, FHIR R4, POCT1-A, CSV / XML / GDT + PDF pairing, BLE GATT (BLP, HTP, PLX, WSS, BCS), serial templates, webhooks (HMAC, timestamp, nonce), **P6 vendor-cloud polling (OAuth2 client credentials, schedule, rate limit, back-off, high-water mark, consent scope, egress allow-list, residency)**, DICOMweb JSON + attachments, OCR / manual fallback, batch store-and-forward |
| F03 Driver SDK | Package format `wellnessz.device-driver/1`, validation, conformance against test vectors, signing workflow; **SDK command line `tools/device-sdk` (scaffold, validate, offline conformance, sign with E34 or a dev key, diff)**; 19 packages, 99 golden datasets |
| F04 Normalisation | MDC → LOINC / UCUM, unit conversion, reference ranges, BMI, GLI-2012 predicted values, OSHA App. F age correction, clock-skew correction |
| F05 Association | Worklist → session → badge → vendor id; thresholds 0.95 / 0.60; unmatched queue with suggestions and reason codes |
| F06 Quality gates | 15 gates incl. calibration lockout via E14, QC lots, ATS/ERS, test-retest, BP cuff, plausibility, consent, attestation, clock skew, verify-before-post |
| F07 Bindings | Default bindings in packages, overridable in the wizard; routing adapters inside E03, E07, E10, E12, E14 |
| F08 Bidirectional | Worklist endpoint; GDT 6302 worklist files; **HL7 order / ADT push over MLLP with acknowledgements, start-from-form (`START_ACQUISITION` – serial start bytes or MLLP order, where the driver declares a start command), historic retrieval (`RETRIEVE_HISTORY` – HL7 query or vendor-database range query, deterministic de-duplication)** |
| F09 Store-and-forward | Gateway encrypted journal; `/device-ingest/batch` reconciliation in sequence order |
| F10 Simulators | Golden-dataset replay per adapter as virtual endpoints; dry-run conformance |
| F11 Observability | Micrometer meters, operations API, connector alerts (`STALE`, `SILENT`, error rate, latency, unmatched SLA, queue capacity, calibration due) |
| F12 Tenancy | `tenant_code`, tenant-partitioned work queue, quotas (429); **tenants with catalogue scope and residency (`PUT /device-engine/tenants/{code}/quota`), per-tenant connector limit (429 `tenant_connector_limit`), tenant-scoped catalogue and configurations (E32), per-tenant signing keys (E34)** |

### 2.2 E32 – Device Catalogue, Administration & Connectivity Provisioning Console

Catalogue of 133 models (11 families) seeded idempotently, UDI / GUDID / EUDAMED import, lifecycle, vendor posture with
SECURITY_OPS exceptions; option packs P1–P10 (JSON Schema 2020-12, step guides, English and Arabic help); driver registry
(immutable versions, signature verification, second-person certification, revocation suspends instances); eight-step
wizard with clone, revise, pause, suspend and decommission; calibration / QC with lockout and E14 integration; fleet
dashboard with ITSM tickets; governance (approval queue, sealed export, import diff with second-person approval,
templates, batch); request-a-model with KPI; masked diagnostics; read-only test review for clinicians; certification PDF.

### 2.3 E33 – Module-Embedded Device Capture

Form registry (16 forms, vision templates DOT, FAA / EASA, DSE, airside driving); station device list
(AVAILABLE / BUSY / LOCKED_OUT / OFFLINE); capture sessions with SSE and polling fallback; preview, accept / reject /
re-test; fallback chain (alternate device → OCR with encrypted photo → manual with attestation); restricted readings for
collector and MRO; batch mode (≤ 300 entries); kiosk with badge and consent (3-minute idle); employee summaries in English,
Arabic and French; wearable consents; ECG / ABPM physician sign-off; capture KPIs.

### 2.4 E34 – Endpoint & Integration Security

Endpoint registry `EP-<SITE>-<KIND>-<NN>`; zero-touch enrolment with group-PKI certificates and proof of possession;
peppered HMAC tokens, rotation with 15-minute grace, ≤ 90-day expiry; fail-closed authenticator without cache; five zones,
nine conduits, firewall template export, zone CIDRs; Ed25519 (optional ECDSA P-256) package signing keys; write-only
connector secrets (`devsec:` / `vault:`); hash-chained security event log → SIEM; HIGH / CRITICAL → ITSM; 15 anomaly
rules; automatic isolation within 15 minutes and re-certification before release; governance records and the go-live
readiness gate; security test pack.

### 2.5 Platform and edge

* **V21:** E03 result provenance columns, five proposed retention classes, module flags and engine thresholds.
* **Kernel:** 15 device SPIs with safe defaults (`DeviceSpis`), separate device-ingest filter chain (certificate thumbprint
  from the ingress + endpoint id + `DeviceToken`; enrolment and webhooks verified by their controllers).
* **Roles:** four console roles and the machine role `DEVICE_ENDPOINT`; 13 content-free `device.*` notification templates;
  outbound allow-lists `siem-device-security` and `itsm-tickets`; 28 ArchUnit rules.
* **Edge:** `apps/wz-device-gateway` – one Java 21 / Spring Boot 4.1 agent for the clinic gateway and the Windows / Linux
  Device Host agent (Section 8).

### 2.6 Second build round – software gaps of the traceability matrix (revision 1.1)

| Item | Delivered | Where |
|---|---|---|
| P6 vendor-cloud polling (E31-BL-008) | OAuth2 client-credentials poller per published P6 connector: per-connector schedule (`poll.intervalSeconds` ≥ 60, claimed with compare-and-set so instances never poll the same connector twice), rate limit (`poll.rateLimitPerMinute`), exponential back-off (to 1 h) and vendor `Retry-After`; client secret through `DeviceSecretStore` (purpose `CLOUD_POLL`), token cached in memory; API and token hosts must be on `device.cloud.allowed-hosts` and HTTPS; `residencyRegion` must equal the tenant's residency; high-water mark persisted after durable ingest; records enter the pipeline with `CP:<connector>:<record id>` (repeats are `DUPLICATE`); consent-scoped wearables (records of employees without the mapped consent are not stored). New sample package `wear.cloud.daily-poll` (MD-121, CLOUD_POLL_JSON) | E31 `cloud/` (`CloudPollService`, `CloudPollJob`); `GET /device-engine/ops/cloud-connectors`, `POST …/{id}/poll`; E32 P6 option pack (`poll.path`, wizard defaults from the package) |
| Outbound orders / worklist (E31-BL-004, -016) | `ORM^O01`, `OML^O21`, `ADT^A04`, `ADT^A08` built with HAPI from the E03 / E14 worklist (minimised: date of birth / sex only when the driver needs them); gateway command `PUSH_WORKLIST` fetches them (`GET /device-ingest/commands/{id}/outbound`), sends each over MLLP to the device's **locally configured** `wz.gateway.mllp-outbound` target (TLS unless loopback / segmented-VLAN exception) and reports one acknowledgement per message (`acks[]` in the command result); GDT 6302 import files unchanged | E31 `outbound/`; gateway `DeviceCommandService`, `MllpClient`; `GET /device-engine/outbound?sessionId=|deviceInstanceId=` |
| Start from form (E31-BL-019) | E33 session start → `START_ACQUISITION` only where the driver declares `protocol.startCommand` (SERIAL bytes for serial-over-TCP connectors with an optional expected reply; MLLP order for the session's employee); otherwise the worklist push the driver supports (GDT 6302) – the documented fallback is the manual start on the device. Packages `ecg.ge.mac-hl7` (MLLP) and `da.lifeloc.fc20-serial` (serial; command bytes to be confirmed with the vendor) declare start commands | E31 `CaptureSessionListener`, `OutboundService`; gateway `SerialConnector.command` |
| Historic retrieval (E31-HLR-08) | `POST /device-engine/historic-retrievals {deviceInstanceId, from, to}` (≤ 31 days; drivers with `historicRetrieval`) → gateway command `RETRIEVE_HISTORY` → `QRY^R02` over MLLP (result frames ORU / ORF) or the vendor-database connector's read-only query bounded by the range → `POST /device-ingest/retrievals/{id}/batch`; deterministic keys `HR:<UUIDv5(device, content without MSH-7 / MSH-10)>` and raw-hash matching – overlapping retrievals and results already pushed live are never stored twice | E31 `HistoricRetrievalService`; gateway `DeviceCommandService`, `VendorDbPoller.history` |
| Tenant scoping (E31-HLR-12) | Tenants (`deviceint.tenant_quota`) with catalogue scope `GROUP` (shared + own) / `TENANT` (own only) and residency; kernel SPI `DeviceTenantDirectory`; tenant-scoped models and driver packages (`devicecat.model.tenant_code`, `driver_package.tenant_code`, `PUT /device-catalogue/models/{code}/tenant`, `?tenantCode=` filters), configurations created for a tenant must use a model of its catalogue (`model_not_in_tenant_catalogue`); per-tenant signing keys (one active key per tenant; another tenant's key → `key_not_for_tenant` at registry import and at engine load; group keys sign the shared catalogue); per-tenant connector limit enforced at ingest | E31 `TenantService`; E32; E34 `PackageSignatureService` (`/device-security/signing-keys {tenantCode}`, `/packages/sign?tenantCode=`) |
| Driver SDK command line (E31-BL-011) | `tools/device-sdk` – Java, runs the engine's parser, validator, simulator and golden datasets offline with the V22 terminology: `scaffold`, `validate`, `conformance`, `keygen`, `sign` (local `DEV-` key or E34), `verify`, `diff` | `tools/device-sdk/README.md` |
| Data fixes | **BLE pairing:** the Tanita WSS and Withings BCS / HTP packages declared `LE_SECURE_CONNECTIONS_JUST_WORKS`, which is not a P3 schema value and is unauthenticated pairing; following the solution document (LE Secure Connections with passkey where supported, *Just Works* only by security exception) they now declare `LE_SECURE_CONNECTIONS_PASSKEY` (device displays the passkey, the bonding owner enters it – to be confirmed per model in the device lab; a model that cannot do it is configured with `JUST_WORKS_EXCEPTION` and a SECURITY_OPS exception). E31 validator and E32 registry reject other pairing values; demo pack regenerated. **GE MAC (MD-045):** inventory row kept as provided (P1 / P4); catalogue route `e32/catalogue-routes.json` adds P2 via the GE MUSE XML export for driver `ecg.ge.muse-xml` (inventory options and contract totals unchanged) | E31 packages, `DriverValidator`; E32 `DriverPackageRules`, `CatalogueRoutes`; `demo-devices` |
| Web | Engine health: vendor-cloud connector status with *Poll now*, historic retrieval request and list; capture panel: outbound order status (sent / accepted / rejected by the device) | `web/oh-workspace` |

## 3. What is new, by application

| Application | New |
|---|---|
| OH workspace | *Devices*: catalogue, wizard, approvals, test review, driver registry, model requests, governance, calibration, fleet, diagnostics, operations, dead letters, unmatched queue, observations, simulators, terminology, batch mode, ECG sign-off, capture KPIs; *Security → Devices*: endpoints, alerts / incidents, events + chain verification, zones / conduits, signing keys, secrets metadata, assurance / go-live gate; *Capture from device* embedded in the E03 results tab, the surveillance due list, E10 collection and E12 screening |
| Employee app | `/health-checks` (released device results), wearable consents, kiosk page `#/kiosk` (badge, consent, own-language summary) |
| Manager workspace | `/device-fleet` – site device availability counts |
| Gateway | Status page on `127.0.0.1:8095`, Prometheus metrics |

## 4. New roles and demo users

### 4.1 New roles

| Role | Purpose | Disclosure |
|---|---|---|
| `DEVICE_ADMIN` | Catalogue, wizard (steps 1–6, 8), option packs, fleet, calibration / QC, masked diagnostics, model requests, decommissioning requests | **No person data** (`/me` → `NONE`) |
| `DEVICE_APPROVER` | Four-eyes certification (step 7), driver certification, import and decommissioning approval; never own submissions | No person data |
| `INTEGRATION_TESTER` | Driver packages, simulators, golden datasets, conformance, signing requests, dead-letter replay, model-request scoping | No person data (masked) |
| `SECURITY_OPS` | Endpoints, enrolment tokens, isolation / release, security events, zones / CIDRs, signing keys, secrets metadata, posture exceptions, test-pack runs | No person data |
| `DEVICE_ENDPOINT` (machine) | Granted by the device-ingest filter to an authenticated endpoint; never in the IdP, never held by a person | – |

Existing roles with new rights: clinicians (`OH_CLINICIAN`, `OH_PHYSICIAN`, `AME`, `CMO`, `NURSING_HEAD`) – capture,
verification, unmatched queue, quick QC, test review; physicians – ECG / ABPM sign-off; `COLLECTOR`, `MRO` – restricted
breath-alcohol capture and reading; `BIOMEDICAL` – calibration screens; `EMPLOYEE` – summaries, wearable consents, kiosk;
`MANAGER` – fleet counts; `AUDITOR` – certification records and security events (read); `DPO` – DPIA approval;
`PLATFORM_ADMIN` – tenant quotas, read-only console.

### 4.2 Demo users (local realm `deploy/local/keycloak/wellnessz-realm.json`; temporary password as for the other demo users)

| User | Role | Employee ID / org scope | Used for |
|---|---|---|---|
| `deviceadmin1` | DEVICE_ADMIN | S8901, `/AIRLINE` | Catalogue, wizard, fleet, calibration, diagnostics, decommissioning |
| `deviceapprover1` | DEVICE_APPROVER | S8902 | Four-eyes approval, driver certification |
| `inttester1` | INTEGRATION_TESTER | S8903 | Drivers, simulators, dead letters |
| `secops1` | SECURITY_OPS | S8904 | Endpoints, isolation, events, keys, zones, test pack |

Demo endpoints (local / test only, documented non-secrets – token `wzdemo-<endpoint id in lower case>-not-a-secret`):
`EP-LHR-GW-01` (gateway: DEV-000003 vitals P4 MLLP, DEV-000006 Afinion P5; the console also seeds DEV-000007, a 12-lead
ECG on P4 HL7), `EP-LHR-AGT-01` (Device Host agent:
DEV-000001 spirometer P2 GDT – locked out by an expired syringe check, DEV-000002 audiometer P2 DB, DEV-000005 breath alcohol
P1), `EP-LHR-TAB-01` (tablet: DEV-000004 BLE BP P3), `EP-LHR-KSK-01` (kiosk, certificate expiring in 20 days),
`EP-DXB-GW-01` (isolated – DEV-000008 quarantined), `EP-LHR-GW-02` (pending enrolment); draft `CFG-000009` (fit tester)
paused at step 4.

## 5. API summary

| Prefix | Owner | Consumers |
|---|---|---|
| `/device-ingest/**` | E31 (kiosk: E33; certificate renewal: E34) | Gateways, agents, tablets, kiosks – **machine only, never through the BFF** |
| `/device-webhooks/{connectorId}` | E31 | Vendor clouds (HMAC) |
| `/device-enrolment/enrol` | E34 | Gateways / agents / tablets / kiosks at enrolment |
| `/device-engine/**` | E31 | Clinicians (observations, unmatched), console roles (dead letters, ops, drivers, simulators), `PLATFORM_ADMIN` (tenants) |
| `/device-catalogue/**`, `/device-configurations/**`, `/device-calibration/**`, `/device-fleet/**`, `/device-governance/**`, `/device-model-requests/**`, `/device-diagnostics/**`, `/workspace/device-fleet` | E32 | Console roles, clinicians (calibration, test review), managers (counts) |
| `/device-capture/**`, `/me/device-summaries`, `/me/device-consents` | E33 | Clinicians, collector, MRO, employees |
| `/device-security/**` | E34 | `SECURITY_OPS`, `AUDITOR` (events), `DPO` (DPIA), `INTEGRATION_TESTER` (public keys) |

Full contract: [`API-Contract-E31-E34.md`](API-Contract-E31-E34.md). `docs/api/openapi.json` must be regenerated for this
release.

## 6. Database migrations

| Migration | Schema(s) | Content |
|---|---|---|
| `V21__increment6_platform.sql` | ohrecord, docs, masterdata | `ohrecord.result` + `device_observation_id` (unique where not null), `capture_method`, `driver_version`, `verified_by`, `verified_at`; retention classes `DEVICE_RAW_PAYLOAD` (40 y), `DEVICE_RAW_UNFILED` (1 y), `DEVICE_CALIBRATION_QC` (40 y), `DEVICE_CONFIGURATION` (10 y after decommissioning), `DEVICE_SECURITY_EVENT` (6 y) – **proposed**; 16 configuration entries (module flags, thresholds) |
| `V22__e31_device_engine.sql` | `deviceint` (new) | 25 tables (messages, raw objects, stage log, dead letters, observations, FHIR resources, association candidates, unmatched items, terminology, unit conversions, reference ranges, GLI equations and splines, OSHA table, golden datasets, simulator runs, gate sign-off, tenant quotas, connector / gateway state, gateway commands, webhook nonces, replay jobs, signing requests); seeds terminology and clinical tables; `device.cloud.allowed-hosts`, `device.pipeline.inline` |
| `V23__e32_device_console.sql` | `devicecat` (new) | 20 tables (seed state, models, posture exceptions, driver packages, stations, configurations, change history, approvals, certifications, calibration schedules, QC checks, lockouts, fleet alerts, consumables, on-duty, model requests, governance exports / imports, templates, batches); 7 calibration schedules |
| `V24__e33_device_capture.sql` | `devicecap` (new) | 10 tables (form rules, batches, sessions, observations, events, annotations, OCR images, observation notices, employee summaries, device consents); 16 form rules |
| `V25__e34_device_security.sql` | `devicesec` (new) | 27 tables (endpoints and instances, enrolment tokens, zones, conduits, zone CIDRs, site hours, signing keys, secrets, security events – append-only with trigger, anomaly rules, alerts, traffic, isolation, incidents with alerts / evidence / timeline, compensating controls, threat models, SBOM, vulnerabilities, pen-test findings, responsibility agreement, DPIA, test-pack runs / results); 5 zones, 9 conduits, 15 anomaly rules; token settings |
| `V26__increment6_device_round2.sql` | deviceint, devicecat, devicesec | Second build round (V22–V25 unchanged – a development database may already have run them): `cloud_poll_state`, `outbound_message` (no message bodies), `historic_retrieval`; gateway command `RETRIEVE_HISTORY`; tenant residency and catalogue-scope check; `tenant_code` on catalogue models and driver packages; per-tenant signing keys (one active key per tenant) |
| `db/demo/R__demo_e32_device_console.sql`, `R__demo_e34_device_security.sql` + `DeviceEngineDemoSeeder`, `E32DemoSeeder`, `DeviceCaptureDemoSeeder`, `E34DemoSeeder` | as above | Demo stations, instances, endpoints, zone CIDRs (loopback and RFC 1918 – **demo only**), clinic hours, governance samples. **Loaded only with the `local` / test profiles** |

Platform first, then one migration per module – the same pattern as increments 3–5. Grants are table-level; append-only
evidence tables get no update / delete grant. Schema lists (core `spring.flyway.schemas`, Helm `values.yaml`, local Postgres
init, managed-database bootstrap, CloudNativePG cluster) already contain `deviceint, devicecat, devicecap, devicesec`.

**Rollback.** Forward-only. Running the increment-5 application against the V25 schema is not supported; roll back by
restoring the pre-upgrade backup with the previous images.

## 7. Configuration

| Item | Change |
|---|---|
| `spring.flyway.schemas` (core) | Adds `deviceint, devicecat, devicecap, devicesec` |
| `WZ_DEVICE_TRUST_ANCHORS` → `wellnessz.device.pki.trust-anchors` | **Required for enrolment.** PEM (inline or a file path, several certificates allowed) of the group-PKI CA(s) that issue device client certificates. Empty = every enrolment fails `certificate_untrusted` |
| `WZ_DEVICE_TOKEN_PEPPER` → `wellnessz.device.security.token-pepper` | Pepper for endpoint / enrolment token hashes (`ENC(...)` or `file:` reference; ≥ 16 bytes). Unset = derived from the master key with HKDF. **Changing it invalidates every endpoint token** – set it once per environment before the first enrolment |
| `WZ_DEVICE_CERT_HEADER` → `wellnessz.device.ingest.certificate-header` | Default `X-Client-Cert-Sha256` – the header the mTLS ingress sets with the hex SHA-256 of the DER client certificate (the ingress must strip client-supplied values) |
| `WZ_DEVICE_REQUIRE_CLIENT_CERT` → `wellnessz.device.ingest.require-client-certificate` | Default `true`; never `false` outside a developer machine |
| Zone CIDRs | **Must be configured by SECURITY_OPS before any gateway / agent can authenticate**: `POST /device-security/zone-cidrs {siteCode, zone: GATEWAY_ZONE, cidr}`. Demo CIDRs exist only in the `local` / test profiles |
| `device.cloud.allowed-hosts` (E18) | Empty – egress allow-list for P6 vendor clouds, checked by the wizard and **enforced by the engine's poller** before every outbound call (exact hosts or `.domain` suffixes) |
| `wellnessz.device.cloud.poll-tick-ms` / `poll-initial-delay-ms` | 30 s / 60 s – scheduler tick of the P6 poller (each connector keeps its own interval); `wellnessz.device.cloud.allow-loopback-http` `false` (tests only: plain HTTP to a loopback mock cloud) |
| `device.pipeline.inline` (E18) | `true` – process accepted messages in the request thread (workers retry) |
| Engine thresholds (E18, V21) | `device.association.auto-file-threshold` 0.95, `suggest-threshold` 0.60, `session-window-seconds` 900, `device.clock.max-skew-seconds` 120, `device.capture.session-timeout-minutes` 10, `device.unmatched.resolution-sla-hours` 8, `device.ocr.min-confidence` 0.90, `device.ingest.max-payload-bytes` 52428800, `device.dlq.max-attempts` 5, `device.security.isolation-sla-minutes` 15, `revocation-sla-minutes` 5, `cert-expiry-warning-days` 30 |
| Token settings (E18, V25) | `device.security.enrolment-token-hours` 72, `endpoint-token-days` 90, `token-grace-minutes` 15, `device.security.soc-email` (**set per environment**) |
| Module flags | `modules.e31..e34.enabled` (`true`) |
| `wellnessz.device.console.tenant-residency` | Default `UK` – fallback residency; the tenant's own `residencyRegion` (`/device-engine/tenants`) is compared with P6 vendor regions by the wizard and the poller |
| Outbound allow-lists | **New:** `siem-device-security` (`messageId, eventId, type, severity, endpointId, deviceInstanceId, siteCode, zone, sourceModule, reasonCode, actor, remoteAddress, protocol, port, rate, keyId, chainHash`) and `itsm-tickets` (`messageId, ticketRef, deviceInstanceId, siteCode, alertType, severity, raisedAt`) |
| Job schedules | `wellnessz.device.worker.poll-interval-ms` (5 s), `housekeeping-interval-ms` (10 min), `alert-interval-ms` (5 min), `wellnessz.device.retention-cron` (02:40); `wellnessz.device.console.hourly-cron` (:07), `daily-cron` (02:17); `wellnessz.device-capture.expiry-cron` (every 5 min); `wellnessz.device.security.isolation-sweep` (30 s), `traffic-flush` (60 s), `cert-expiry-cron` (03:10), `retention-cron` (03:40) |
| OCR (optional) | `wellnessz.documents.ocr.tesseract-path` – without it OCR proposes no values and the clinician types them |

## 8. Gateway installation summary (`apps/wz-device-gateway`)

| Topic | Summary |
|---|---|
| Roles | `GATEWAY` (clinic / outstation industrial PC, Linux – MLLP, POCT1-A, BLE relay, DICOM drop) and `DEVICE_HOST_AGENT` (vendor PC, Windows service or Linux systemd – export folders, vendor DB, serial) |
| Build | `./mvnw -pl apps/wz-device-gateway -am install` (71 tests); container `docker build -f deploy/docker/Dockerfile.gateway -t wellnessz/wz-device-gateway:<ver> .` (non-root uid 10001) |
| Linux | `service/linux/wellnessz-gateway.service` (account `wellnessz`, `StateDirectory` 0700, `ProtectSystem=strict`, `NoNewPrivileges`, `UMask=0077`); configuration `/etc/wellnessz/gateway/application.yml`; `gateway.env` with `WZ_MASTER_KEY_FILE`, `WZ_GATEWAY_ENROLMENT_TOKEN_FILE` |
| Windows | Java 21 runtime; WinSW with `service/windows/wellnessz-gateway.xml`; dedicated low-privilege account (gMSA) with read access to the vendor export folder / database only |
| Enrolment | Create the endpoint (wizard publish or `POST /device-security/endpoints`) → one-time token (≤ 72 h); install the client certificate from the group PKI (PKCS12 key store); start with `wz.gateway.enrolment.token[-file]` and `wz.gateway.tls.key-store`. Without identity and token the gateway refuses to start; the identity is stored encrypted in `data-dir/identity.enc` |
| Network | Outbound HTTPS only to the core ingest host (mTLS); inbound only from the device VLAN on the configured listener ports (MLLP 2575 TLS, POCT, DICOM drop share); status page bound to `127.0.0.1:8095` |
| Queue | `data-dir/queue` on a persistent, encrypted disk; 72 h / 2 GB default (never below 48 h); full queue refuses new messages (MLLP `AR`) |
| Commands | `RELOAD_CONFIG`, `ROTATE_TOKEN`, `ISOLATE`, `PUSH_WORKLIST` (HL7 over MLLP with ACKs, or GDT 6302), `START_ACQUISITION` (serial-over-TCP start bytes, or MLLP order), `RETRIEVE_HISTORY` (HL7 query / vendor DB); `RESEND_LAST` not supported. Outbound MLLP targets only from the local `wz.gateway.mllp-outbound` list (the core names the device instance, never a host) |
| Release after isolation | SECURITY_OPS releases the endpoint (after re-certification); then start once with `wz.gateway.release-quarantine=true` |
| Local development | `--spring.profiles.active=local` (core `http://localhost:8080`, **ingress simulation** – the gateway sends the thumbprint itself; reported as hardening finding `INGRESS_SIMULATION_ENABLED`) |

Full reference: [`apps/wz-device-gateway/README.md`](../../apps/wz-device-gateway/README.md); placement per setup:
Deployment Architecture §22.

## 9. Verification results (1 November 2026)

| Check | Result |
|---|---|
| Second build round (1 November 2026, `./mvnw -o -B test`, fresh PostgreSQL 16 database) | **782 tests, 0 failures, 0 errors** – new: `Increment6DeviceRound2IntegrationTest` (4: P6 polling against a mock vendor cloud, outbound orders / start from the form with ACKs, historic retrieval with deterministic de-duplication, tenant isolation of catalogue / configurations / signing keys / connector limits), `Hl7OutboundBuilderTest` (4), `CloudConnectorTest` (3), `CatalogueRoutesTest` (2), `DriverPackageTest` protocol rules, gateway `DeviceCommandServiceTest` (6 – gateway total 71), `DeviceSdkTest` (2); SpotBugs + FindSecBugs (`-Psecurity install`) **0 findings**; OH workspace lint, **907** vitest tests and build clean; demo pack `codecs` 179 / `engine` 35 passed |
| Java build and tests (`./mvnw verify`) | **744 tests, 0 failures, 0 errors** – including `Increment6EngineIntegrationTest` (7), `Increment6ConsoleIntegrationTest` (9), `Increment6CaptureIntegrationTest` (12), `Increment6SecurityIntegrationTest` (11), `Increment6EndToEndIntegrationTest` (3) on PostgreSQL 16, `ArchitectureTest` (28 rules), the E31–E34 unit tests and the 65 gateway tests |
| SAST (SpotBugs + FindSecBugs, `-Psecurity`) | **0 findings**; new justified exclusions: device-ingest chain CSRF / XSS false positives (stateless machine traffic), XPath from signed packages, SHA-1 for UUIDv5 (RFC 9562) |
| Web unit tests (vitest) | OH workspace console and security screens **83**; capture, engine, employee and manager device screens and app totals: **<WEB TESTS>** |
| End-to-end (`tools/e2e/e2e-increment6.js`, real Keycloak logins, device calls with demo endpoint tokens) | Increment 6 **<E2E>** (21 checks planned – contract §20); regressions increments 1–5 **<E2E>** |
| Integration hardening | 10 cross-module defects found when the modules were merged and fixed (QC event payload, simulator dataset ids, LIVE wizard test, endpoint zone at publish, signing-key revocation consumer, demo package signing, configured association methods, accept notified after commit, instance-only revocation on shared gateways, never-seen device health) |
| Security test pack | Delivered (`tools/device-security/`); **not yet executed in staging** |
| Hardware | **No real device tested** – simulators and synthetic golden datasets only |
| Performance | **Not measured** (no load or soak test) |
| API contract | `docs/api/openapi.json` to be regenerated (<PATHS>) |

## 10. Known limitations and decisions needed

| Area | Limitation / decision | Planned handling |
|---|---|---|
| Hardware | No native serial / USB library (no VID/PID allow-list, no HID wedge; start-from-form for serial devices needs serial-over-TCP); BLE via relay only (no radio in the agent); DICOM drop folder, not C-STORE / MWL / Q-R; outbound orders, start commands and historic queries tested against simulated devices only; certificate renewal not automated in the gateway | Follow-on stories with the device lab (document 02 §9) |
| Device lab | Device lab and vendor demonstration units were not available – **no real device was connected**; vendor interface specifications must confirm inventory rows not marked *High* confidence | Procurement; wizard prerequisite `INTERFACE_SPEC_CONFIRMED` |
| Clinical reference data | GLI-2012 coefficients / approximations, OSHA Appendix F female table, plausibility ranges and the gate rule set need **Clinical Governance sign-off** (`signOff PENDING`) | CMO, Nursing Head |
| Retention | Five device retention classes are **proposed** | DPO / CMO approval |
| Privacy | DPIA for device data flows pending | DPO (`/device-security/privacy/dpia/approve`) |
| Security assurance | Independent penetration test and the Cyber Review test-pack run **must be executed in staging before go-live**; CIS golden images not built; CRL / OCSP left to the ingress / PKI | CISO, infrastructure |
| Zone CIDRs (**go-live item**) | Production gateway zone CIDRs must be configured by SECURITY_OPS – until then no gateway or agent can authenticate (`zone_violation`) | Upgrade step 6 |
| 401 vs 403 (**decided**) | One rule for the kernel filter, the ingest access-denied handler and the module controllers: an authenticated endpoint calling a path its kind may not use gets **403 `endpoint_forbidden`**; every authentication failure (missing / wrong token or credentials, missing client-certificate thumbprint `mtls_required`, unknown / isolated / revoked endpoint, zone violation) gets **401** with its `failureCode` | Implemented and covered by `DeviceEndpointAuthenticationFilterTest` and the E34 / engine integration tests (contract §7, §15.3) |
| Drug-screen readers (**decision**) | Instrumented drug-screen readers are rejected in this increment (`E10.DRUG_SCREEN` refused) | MRO / Legal to confirm |
| Engine gaps | No performance / soak test; no tenant onboarding template; no stop-acquisition command; vendor clouds, data managers and order-accepting devices not validated with real systems | Document 02 §9 |
| Capture gaps | No native tablet app with on-device queue; no respirator clearance register; WCAG audit and time-and-motion study outstanding | Mobile team, UX, Nursing Head |
| E35 | Kafka / NATS, Kubernetes topologies, deployment options and cost model are **not** in this increment | E35 |
| Gateway image | Not yet built by the container workflow; edge sites install the JAR / systemd / WinSW or build the image locally | DevSecOps |

**PMO action – epic renumbering.** The client's E31–E34 numbering is authoritative. The strategy pack
(`docs/strategy/epics/`, document 42 of the index) earlier proposed epics **E20–E31**; its **E31 "wellbeing marketplace"
clashes** with the client's E31 Medical Device Integration Engine, and E32–E35 now belong to this pack. **Action for the
PMO:** renumber the strategy pack's proposed E20–E31 (from E36 onwards, or as the PMO decides), update the epic catalogue
and roadmap workbook, the remaining-epics build plan and every cross-reference, and confirm the new numbers with the client
before further workbooks are produced.

## 11. Upgrade steps (from increment 5)

1. **Back up** the database (point-in-time recovery enabled) and note the Flyway version (expected V20). Keep the
   increment-5 images for the rollback window.
2. **Schema list** – the repository files already list `deviceint, devicecat, devicecap, devicesec`. On an **existing**
   managed database or CNPG cluster, create the four schemas owned by `wz_migrator` and update the `wz_app` / `wz_migrator`
   search paths and default privileges manually, as for the other module schemas.
3. **Identity provider – roles and users** – create `DEVICE_ADMIN`, `DEVICE_APPROVER`, `INTEGRATION_TESTER`,
   `SECURITY_OPS` (Entra ID: app roles and groups) and **add all four to the role scope mappings of `wellnessz-bff` and
   `wellnessz-mobile`**. Do **not** create `DEVICE_ENDPOINT` in the IdP. Assign approvers so that no person holds both
   `DEVICE_ADMIN` and `DEVICE_APPROVER` for the same site. Locally, re-import the realm
   (`./deploy/local/down.sh --purge` then `./deploy/local/up.sh`).
4. **Secrets** – store the device CA bundle and (optionally) the token pepper in the key vault / secret manager and mount
   them as files: `WZ_DEVICE_TRUST_ANCHORS=/mnt/secrets/core/device-ca.pem`, `WZ_DEVICE_TOKEN_PEPPER=file:/mnt/secrets/core/device-token-pepper`
   (through `core.extraEnv`). Different values per environment.
5. **mTLS ingress** – publish a dedicated host or route for `/api/v1/device-ingest/**` and `/api/v1/device-enrolment/**`
   that requires a client certificate issued by the device CA, **strips any client-supplied `X-Client-Cert-Sha256`** and
   sets it to the hex SHA-256 of the presented DER certificate; it must also **overwrite** `X-Forwarded-For` with the real
   client address (the zone-CIDR check uses it). Keep `/api/v1/device-webhooks/**` on a TLS-only route (vendor
   clouds authenticate by HMAC). The BFF must not proxy any of these paths (setup guides 02–04, increment-6 sections).
6. **Zone CIDRs (go-live item)** – SECURITY_OPS records each site's gateway-zone address ranges with
   `POST /device-security/zone-cidrs`; verify with `GET /device-security/zones` and the conduit export.
7. **Configuration** – set `device.security.soc-email`; review `device.cloud.allowed-hosts`,
   `wellnessz.device.console.tenant-residency` and the calibration schedules; keep `WZ_FLYWAY_LOCATIONS` **without**
   `classpath:db/demo`.
8. **Outbound allow-lists** – add `siem-device-security` and `itsm-tickets` if the allow-list is overridden per environment;
   connect the outbox relay to the SIEM and the ITSM tool.
9. **Migrate** – run the Flyway job with the migrator role; confirm V21–V25 applied, the four schemas exist with runtime
   grants and `devicesec.security_event` rejects updates.
10. **Deploy** core API, BFF and the web apps by promoting the signed release digest. The gateway is deployed separately at
    each site (Section 8).
11. **Driver packages and signing key** – SECURITY_OPS creates the production signing key
    (`POST /device-security/signing-keys`); INTEGRATION_TESTER registers the needed packages
    (`POST /device-catalogue/drivers` after a conformance run); DEVICE_APPROVER certifies them.
12. **Sign-offs** – clinical reference data and gate rule set, retention classes, DPIA, calibration schedules, reference
    network design, IEC 80001-1 agreement; run the security test pack and the penetration test in staging (go-live gate
    `GET /device-security/go-live-readiness`).
13. **First devices** – provision through the wizard (document 04), enrol the gateways, run LIVE tests with nurse review.
14. **Smoke test** – `tools/e2e/e2e-increment6.js` (and the increment 1–5 scripts) against the environment, or the manual
    checks: ingest without thumbprint → 401 `mtls_required`; capture session → preview → accept → E03 result with device
    badge; duplicate → `DUPLICATE`; isolation → 401 `endpoint_isolated`; security chain `intact: true`.
15. **Monitoring** – watch connector alerts, the unmatched queue SLA, dead letters, gateway backlog (`device.gateway-backlog`),
    certificate expiry, security alerts and the isolation sweep, and the scheduled jobs in `platform.scheduled_job_run`.

## 12. Increment 6 in the programme

Increment 6 is the first increment built from the client's enhancement pack after the five planned increments. It adds
**four epic modules** (E31–E34, now 17 in total), **one edge application** and **five Flyway versions** (now 25). E35
(deployment options and operations of the engine) follows separately.
