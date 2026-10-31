# Increment 6 – E31 Medical Device Integration Engine, E32 Device Console, E33 Module-Embedded Capture, E34 Endpoint & Integration Security: API and Domain Contract

Binding source of truth for the four parallel module developers (`modules/wz-e31-device-engine`, `modules/wz-e32-device-console`,
`modules/wz-e33-device-capture`, `modules/wz-e34-device-security`), the edge application (`apps/wz-device-gateway`), the
routing adapters in the existing OH modules, and the UIs (`web/oh-workspace`, `web/manager-workspace`, `web/employee-app`).
Requirements: the client's epic workbooks E31–E34 (authoritative numbering) and the solution document (sections 3–8, 10–12,
Appendix A inventory of 133 devices, Appendix C workflows F45–F50, R19–R22). E35 (deployment topologies, Kafka / NATS,
Kubernetes options, cost model) is **not** in scope.

**Conventions (unchanged from increments 1–5).** Paths relative to `/api/v1` (browsers call `/bff/api/...`); RFC 9457 problems
`{type,title,status,detail,code,correlationId,field?}`; ISO-8601 UTC timestamps, `YYYY-MM-DD` dates; plain JSON arrays unless
stated; null fields omitted; denied record access → `404`; business-rule violations → `422` with `code`; conflicts → `409`;
validation → `400 validation_failed`. Free text shown outside the owning clinical team is screened with
`ClinicalContentException.screen(field, value)`. British English in all UI text and messages; synthetic data only; no PHI in
logs, traces, metrics labels, security events or notifications.

**Device-data confidentiality rules.**
1. Device console roles (`DEVICE_ADMIN`, `DEVICE_APPROVER`, `INTEGRATION_TESTER`, `SECURITY_OPS`) **never** see person data:
   traces are PHI-masked (names, employee ids, badge ids, dates of birth, free text and **values** replaced by `«masked»`),
   fleet and health views are counts, security events carry no employee id. `/me` discloses `NONE` for them.
2. Device-sourced observations are `DataClassification.CLINICAL` and are read through the existing ABAC
   (`RecordGuard` / `EmployeeRecordAccess`) of the employee they belong to. Breath-alcohol and drug-screen observations
   (categories `BREATH_ALCOHOL`, `DRUG_SCREEN`) are **restricted** (`DeviceObservation.restricted=true`,
   `RESTRICTED_CLINICAL`): only `COLLECTOR` (own capture session, no result history), `MRO` and `OH_PHYSICIAN` acting as MRO
   may preview or read them; other OH users see "restricted reading" without values (as E10 in increment 5).
3. An unassociated ("unmatched") reading is visible to clinicians of the site's OH team in the unmatched queue with the
   candidate suggestions; never to console roles (they see the count only).
4. Values are immutable once captured: a wrong value is rejected and re-measured, never edited (provenance integrity).
5. Every person-level read of device data is audited; every disclosure outside OH goes through the existing disclosure log.

## 0. Technology decisions and recorded deviations

| Topic | Solution document | This increment (binding) | Reason |
|---|---|---|---|
| Runtime | Java 21 / Spring Boot 3 | Java 21 / **Spring Boot 4.1** (code base) | one runtime for the modular monolith |
| Routing framework | Apache Camel | **plain Spring components** (pipeline stages as beans, `@Transactional` stage hand-off through the Postgres work table) | Camel adds nothing the stage contract needs |
| HL7 v2 / FHIR | HAPI HL7 v2, HAPI FHIR | **HAPI HL7 v2 (`hapi-structures-v25`, generic parser accepts 2.3–2.6) and HAPI FHIR R4 structures** – already managed in the root pom; FHIR *validation* = strict HAPI parse + hand-written PoCD / vital-signs profile rules (the `hapi-fhir-validation` artefacts are not available offline) | reuse; offline build |
| DICOM | dcm4che / Orthanc | **DICOMweb-style JSON metadata** (PS3.18 JSON model) posted by the edge + the instance archived as raw payload; no C-STORE SCP in the core (the gateway's DICOM listener is a later edge capability) | dcm4che is not on Maven Central |
| Message bus | Kafka / Redpanda, NATS JetStream at the edge | **Postgres work table + existing outbox `EventBus` / in-process events**; the gateway's encrypted local queue replaces JetStream | Kafka / NATS bindings belong to E35 |
| Canonical store | HAPI FHIR JPA server, TimescaleDB, MinIO | **FHIR R4 resources as validated JSON in PostgreSQL tables (`deviceint`)**; raw payloads in a `RawPayloadStore` abstraction (DB-backed, AES-GCM encrypted, SHA-256) with an S3-compatible implementation as a later option | no separate FHIR server in the monolith |
| Windows Device Host agent | .NET 8 worker | **one Java 21 agent (`apps/wz-device-gateway`) in two roles – clinic gateway and Device Host agent – on Linux and Windows** | one code base, one SBOM, one test suite |
| Rule engine | Drools / embedded DSL | **declarative gate configuration in driver packages + Java gate implementations** | small, auditable rule set |
| PKI | step-ca / Vault PKI | **certificates from the group PKI (EST/SCEP outside the platform); E34 binds the thumbprint at enrolment, verifies the chain with JDK `CertPathValidator` and proof of possession with JDK `Signature`**; package signatures **Ed25519 (JDK)** | no Bouncy Castle dependency |
| Secrets | HashiCorp Vault / cloud KMS | **`DeviceSecretStore` (E34) on wz-secrets field encryption**; `vault:<path>` references resolved by the same interface when an external vault is configured | reuse of wz-secrets |
| Terminology | Snowstorm / Ontoserver, RTMMS | **versioned reference tables in `deviceint`** (MDC → LOINC / UCUM, SNOMED CT only where free to use), GLI-2012 coefficients, OSHA App. F age-correction table | deterministic, offline |
| Observability | OpenTelemetry → Prometheus / Grafana | Micrometer / OpenTelemetry already in the app; connector metrics with **PHI-free labels** (`connector`, `option`, `site`, `stage`, `outcome`) | reuse |

## 1. Modules, packages, schemas, migrations (done – platform base)

| Module | Package | Schema | Flyway | Owns REST prefixes | Implements kernel SPIs |
|---|---|---|---|---|---|
| `modules/wz-e31-device-engine` | `com.wellnessz.e31` | `deviceint` | `V22__e31_device_engine.sql` | `/device-ingest/**` (except `/device-ingest/kiosk/**` and `/device-ingest/certificate-renewal`), `/device-webhooks/**`, `/device-engine/**` | `DeviceObservationGateway`, `DeviceDiagnostics`, `DeviceEndpointStatusListener`; **and the routing adapters** `DeviceObservationSink` in E03 / E07 / E10 / E12, `DeviceWorklistSource` in E03 / E14 |
| `modules/wz-e32-device-console` | `com.wellnessz.e32` | `devicecat` | `V23__e32_device_console.sql` | `/device-catalogue/**`, `/device-configurations/**`, `/device-fleet/**`, `/device-calibration/**`, `/device-governance/**`, `/device-model-requests/**`, `/device-diagnostics/**`, `/workspace/device-fleet/**` | `DeviceCatalogueLookup`, `DeviceEndpointStatusListener`; **and** the `DeviceEquipmentRegister` adapter in E14 |
| `modules/wz-e33-device-capture` | `com.wellnessz.e33` | `devicecap` | `V24__e33_device_capture.sql` | `/device-capture/**`, `/device-ingest/kiosk/**`, `/me/device-summaries/**`, `/me/device-consents/**` | `DeviceCaptureSessions`, `DeviceObservationListener` |
| `modules/wz-e34-device-security` | `com.wellnessz.e34` | `devicesec` | `V25__e34_device_security.sql` | `/device-enrolment/**`, `/device-security/**`, `/device-ingest/certificate-renewal` | `DeviceEndpointAuthenticator`, `DeviceSecurityEventPublisher`, `DeviceEndpointProvisioning`, `DevicePackageSignatures`, `DeviceSecretStore` |
| platform (done) | `com.wellnessz.casekernel.device` (+ `.ingest`) | – | `V21__increment6_platform.sql` | security filter chain for `/device-ingest/**`, `/device-enrolment/**`, `/device-webhooks/**` | `DeviceSpis` (defaults) |
| `apps/wz-device-gateway` (built by the E31 developer; E34 owns its hardening baseline and enrolment client contract) | `com.wellnessz.gateway` | local encrypted queue only | – | none (client of `/device-ingest`, `/device-enrolment`) | – |

**Flyway order (justified).** `V21` platform first, then `V22`–`V25` one per module – the same order as increments 3, 4 and 5
(V9, V13, V16 platform before the module migrations): module migrations may rely on platform rows (config keys, retention
classes, the E03 provenance columns). `V22`–`V25` exist as base files (`create schema if not exists …;` + `grant usage`); each
developer appends tables, indexes, seed rows and **table-level** grants (`grant select, insert[, update]` per table – append-only
tables get no update grant) in their own file only. Demo data goes to `db/demo/R__demo_e3x_*.sql` (local / test only).
Schema lists are already updated in `application.yml`, `deploy/local/postgres/01-init-wellnessz.sh`,
`deploy/sql/bootstrap-managed-postgres.sql`, `deploy/hybrid/cnpg/03-cluster.yaml`, `deploy/helm/wellnessz/values.yaml`
(test DB: Flyway `create-schemas` from `application.yml`).

**V21 platform (done).** `ohrecord.result` + `device_observation_id uuid` (unique where not null – idempotency key of the
E03 sink), `capture_method` (`DEVICE | OCR_VERIFIED | MANUAL_ATTESTED`), `driver_version`, `verified_by`, `verified_at`;
retention classes (§17); config keys `modules.e31..e34.enabled` and the engine thresholds (§8, §9, §13, §14).

**Package layout inside each module (recommended, not enforced):** `…web` (controllers + request records), `…model`
(entities, repositories), `…service`, `…spi` (kernel SPI implementations), `…job` (scheduled jobs, `JobRunGuard`).
E31 additionally: `…pipeline` (stages), `…adapter.<option>` (one package per adapter family), `…driver` (package runtime),
`…terminology`, `…association`, `…gate`, `…routing`, `…store`, `…simulator`. Routing adapters live **in the consuming module**
under `com.wellnessz.e03.device`, `com.wellnessz.e07.device`, `com.wellnessz.e10.device`, `com.wellnessz.e12.device`,
E14 adapters under `com.wellnessz.e14.device` – they depend on the kernel only.

**Module registry (done).** `ModuleRegistry` lists E31–E34 (group `device`); each module registers an `EXXModule`
`CaseModule` bean; entry points call `moduleRegistry.requireEnabled("E3x")` (503 `module_disabled`).

**Architecture rules (done, `ArchitectureTest`).** E31–E34 depend only on the platform and `com.wellnessz.casekernel.device`
(never on each other, never on E01–E14 / `ehsintake`); E01–E14 and `ehsintake` never depend on E31–E34; platform, kernel and
document service never depend on E31–E34.

## 2. Roles and demo users (realm, `Roles.java`, scope mappings, `MeController` – done)

| Role | Purpose | Demo user (employeeId, org_scope) |
|---|---|---|
| `DEVICE_ADMIN` | catalogue, wizard (steps 1–6, 8), option packs, fleet, calibration / QC, masked diagnostics, model requests, decommissioning; submits changes for approval | `deviceadmin1` (S8901, /AIRLINE) |
| `DEVICE_APPROVER` | four-eyes approval of certification (step 7), production configuration changes, environment import, decommissioning; **cannot approve own submission** | `deviceapprover1` (S8902) |
| `INTEGRATION_TESTER` | driver packages (author, test, sign request, publish to registry), simulators, golden datasets, conformance runs, wizard tests in simulator mode | `inttester1` (S8903) |
| `SECURITY_OPS` | endpoint identities, enrolment tokens, security events and alerts, isolation / release, package-signing keys, vendor-posture exceptions, security test pack runs | `secops1` (S8904) |
| `DEVICE_ENDPOINT` (machine) | granted by the device-ingest filter to an authenticated endpoint; never in Keycloak, never held by a person | – |
| existing | `BIOMEDICAL` (`biomed1`: E14 register + calibration evidence, also reads the E32 calibration screens), `OH_CLINICIAN`, `OH_PHYSICIAN`, `AME`, `CMO`, `NURSING_HEAD` (capture, verification, unmatched queue), `COLLECTOR` (breath-alcohol capture), `MRO`, `EMPLOYEE` (summaries, wearable consent, kiosk via badge), `MANAGER` (site fleet summary), `PLATFORM_ADMIN` (read-only console), `AUDITOR` (certification records, security events read-only), `DPO` (retention, DPIA, consent reports) | |

All demo users use the temporary password `ChangeMe!123` (changed on first login). The realm is imported only when absent:
after pulling, run `./down.sh --purge && ./up.sh`. `Roles.DEVICE_CONSOLE` = the four console roles.

## 3. Kernel SPIs (`com.wellnessz.casekernel.device` – done, do not change signatures)

Obtain optional SPIs through `DeviceSpis` (injectable component) – it returns safe defaults when the implementing module is
absent and 503 `module_disabled` for commands that need it. Multi-implementation SPIs are injected as lists.

| SPI | Implemented by | Called by | Default without implementation | Key methods |
|---|---|---|---|---|
| `DeviceObservationSink` | routing adapters in E03 (`E03.RESULT`), E07 (`E07.IH_MEASUREMENT`), E10 (`E10.BREATH_ALCOHOL`), E12 (`E12.SCREENING`, `E12.WEARABLE`) | E31 router only | target not deployed → observation stays `ROUTE_PENDING`, retried, then dead-lettered | `String target()`, `Set<Category> categories()`, `Delivery deliver(String actor, Routed routed)` – idempotent on `observationId` |
| `DeviceCatalogueLookup` | E32 | E31, E33, E34 | `EMPTY` (nothing published → ingest refuses `device_not_published`) | `instance(id)`, `instancesForEndpoint(endpointId, serial)`, `instancesAtStation(site, station, category)`, `driverPackage(id, version)`, `binding(instanceId, formCode)`, `qcStatus(instanceId, day, lot)` |
| `DeviceEndpointAuthenticator` | E34 | kernel ingest filter only | `DENY_ALL` (401 `endpoint_security_unavailable`) | `Result authenticate(Credentials)` |
| `DeviceSecurityEventPublisher` | E34 | kernel filter, E31, E32, E33 | writes to the hash-chained platform audit (`DEVICE_SECURITY_EVENT`) | `publish(DeviceSecurityEvent)` |
| `DeviceCaptureSessions` | E33 | E31 association | `NONE` | `Optional<SessionBinding> activeSession(instanceId, at)` |
| `DeviceObservationListener` | E33 | E31 (after commit) | none | `observationUpdated(ObservationNotice)` |
| `DeviceObservationGateway` | E31 | E33 (and E32 for test-form evidence) | 503 | `preview`, `accept`, `reject`, `submitFallback` |
| `DeviceDiagnostics` | E31 | E32 | 503 | `trace`, `health`, `runTest`, `resendLast` |
| `DeviceEndpointProvisioning` | E34 | E32 | 503 | `provision`, `revoke`, `state` |
| `DeviceEndpointStatusListener` | E32, E31 | E34 | none | `endpointStatusChanged(endpointId, instanceIds, status, reason)` |
| `DevicePackageSignatures` | E34 | E32 (import / publish), E31 (load) | 503 | `verify(canonical, sig, keyId)`, `sign(actor, canonical)` |
| `DeviceSecretStore` | E34 | E32 (store / rotate), E31 (resolve at runtime), gateway via `/device-ingest/secrets/{ref}` | 503 | `store`, `resolve(ref, purpose)`, `rotate`, `destroy` |
| `DeviceEquipmentRegister` | E14 adapter (written by the E32 developer) | E32 | `NONE` | `list()`, `find(equipmentDeviceId)` |
| `DeviceWorklistSource` | E14 adapter (appointments) + E03 adapter (open device orders) – written by the E31 developer | E31 | empty | `worklist(site, day, category)` |
| existing `DeviceCalibrationCheck` | E14 (`EquipmentCalibrationCheck`) | E31 gate `CALIBRATION` (all beans, as E03 does) | `UNKNOWN` | `check(equipmentDeviceId, day)` |

Code lists (`DeviceCodes`): `Family` (11 inventory families) `SPIROMETRY | AUDIOMETRY | VITALS | CARDIOLOGY | VISION | POC |
DRUG_ALCOHOL | RESP_ERGONOMICS | EXPOSURE | WEARABLE | IMAGING`; `Category` (15 form families) `AUDIOMETRY | SPIROMETRY | VISION |
ECG | VITALS | BODY_COMPOSITION | ABPM_HOLTER | BREATH_ALCOHOL | DRUG_SCREEN | POC | FIT_TEST | ERGONOMICS | EXPOSURE | WEARABLE |
IMAGING`; `Option` `P1_SERIAL_USB … P10_MANUAL` (`code()` = `P1`…`P10`); `CaptureMethod` `DEVICE | OCR_VERIFIED |
MANUAL_ATTESTED`; `GateOutcome` `PASS | INFO | WARN | BLOCK`; `EndpointKind` `GATEWAY | DEVICE_HOST_AGENT | MOBILE_APP | KIOSK |
DATA_MANAGER | VENDOR_CLOUD | DEVICE`; `EndpointStatus` `PENDING_ENROLMENT | ACTIVE | ISOLATED | REVOKED`; `Severity` `INFO |
LOW | MEDIUM | HIGH | CRITICAL`.

Inventory family → `Family`: Spirometry & lung function → SPIROMETRY; Audiometry & hearing → AUDIOMETRY; Vital signs & body
composition → VITALS; Cardiology → CARDIOLOGY; Vision & ophthalmic → VISION; Point-of-care laboratory → POC; Drug & alcohol
testing → DRUG_ALCOHOL; Respiratory protection & ergonomics → RESP_ERGONOMICS; Exposure & environment feeds → EXPOSURE;
Wearables & personal health → WEARABLE; Imaging & other → IMAGING.

## 4. Canonical observation model and FHIR resource shapes (E31-HLR-04, -14; E31-F04)

The kernel record `DeviceObservation` (components, quality flags, provenance, attachments) is what crosses module
boundaries. E31 persists the same content as **FHIR R4 JSON** (validated by strict HAPI parsing + the profile rules below) in
`deviceint` with search columns kept outside the JSON. Every resource carries `meta.tag` `{system:"urn:wellnessz:tenant", code:<tenantCode>}`.

**Storage (E31, `deviceint`, recommended names):** `message` (raw message envelope: id, endpoint_id, device_instance_id,
kind, idempotency_key **unique per endpoint**, gateway_sequence, captured_at, received_at, raw_object_id, sha256, stage, status,
attempts, next_attempt_at, error_code), `stage_log` (append-only), `dead_letter`, `raw_object` (bytea, AES-GCM, sha256, size,
media_type, retention_class, purge_after), `fhir_resource` (id, resource_type, employee_id, observation_id, category,
effective_at, status, json text **field-encrypted**, version), `observation` (search row: id, report_id, employee_id,
device_instance_id, category, status, capture_method, restricted, association_method, confidence, session_id, route_target,
routed_record_id, created_at), `association_candidate`, `unmatched_item`, `terminology_map`, `unit_conversion`,
`reference_range`, `predicted_equation`, `golden_dataset`, `simulator_run`, `connector_metric_minute`.

**Status of an observation:** `RECEIVED → NORMALISED → ASSOCIATED | UNMATCHED → VALIDATED → PENDING_VERIFICATION | BLOCKED →
ROUTED | REJECTED`; `DEAD_LETTER` from any stage after `device.dlq.max-attempts`.

### 4.1 Device (FHIR `Device`, PoCD MDS level)

```json
{ "resourceType": "Device", "id": "DEV-000123",
  "identifier": [ {"system": "urn:wellnessz:device-instance", "value": "DEV-000123"},
                  {"system": "urn:wellnessz:equipment", "value": "SPI-LHR-001"},
                  {"type": {"text": "Serial"}, "value": "EA-2209-4411"} ],
  "udiCarrier": [ {"deviceIdentifier": "07640140580016", "carrierHRF": "(01)07640140580016(21)EA-2209-4411"} ],
  "status": "active", "manufacturer": "ndd Medical Technologies",
  "deviceName": [ {"name": "EasyOne Air", "type": "model-name"} ], "modelNumber": "MD-001",
  "type": {"coding": [ {"system": "urn:iso:std:iso:11073:10101", "code": "MDC_DEV_SPEC_PROFILE_SPIRO"} ]},
  "version": [ {"type": {"text": "firmware"}, "value": "3.2.1"}, {"type": {"text": "software"}, "value": "EasyOne Connect 3.9"} ],
  "location": {"identifier": {"system": "urn:wellnessz:station", "value": "LHR-OH-ROOM-2"}},
  "property": [ {"type": {"text": "calibrationStatus"}, "valueCode": [ {"text": "VALID"} ]} ] }
```

### 4.2 DeviceMetric (calibration and channel state)

```json
{ "resourceType": "DeviceMetric", "id": "DEV-000123-flow",
  "type": {"coding": [ {"system": "urn:iso:std:iso:11073:10101", "code": "MDC_FLOW_AWAY"} ]},
  "source": {"reference": "Device/DEV-000123"}, "operationalStatus": "on", "category": "measurement",
  "calibration": [ {"type": "gain", "state": "calibrated", "time": "2026-10-31T07:42:00Z"} ] }
```

### 4.3 Observation (component panel; single values use `valueQuantity` at top level)

```json
{ "resourceType": "Observation", "id": "5f2c…", "status": "preliminary",
  "category": [ {"coding": [ {"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "exam"} ]} ],
  "code": {"coding": [ {"system": "http://loinc.org", "code": "81459-0", "display": "Spirometry panel"} ]},
  "subject": {"identifier": {"system": "urn:wellnessz:employee", "value": "E1001"}},
  "encounter": {"identifier": {"system": "urn:wellnessz:case", "value": "<caseId>"}},
  "basedOn": [ {"identifier": {"system": "urn:wellnessz:order", "value": "ORD:<orderId>"}} ],
  "effectiveDateTime": "2026-10-31T09:14:05Z", "issued": "2026-10-31T09:14:07Z",
  "performer": [ {"identifier": {"system": "urn:wellnessz:user", "value": "nurse1"}} ],
  "device": {"reference": "Device/DEV-000123"},
  "method": {"text": "ATS/ERS 2019 forced expiratory manoeuvre"},
  "component": [
    {"code": {"coding": [ {"system": "http://loinc.org", "code": "20150-9", "display": "FEV1"},
                          {"system": "urn:iso:std:iso:11073:10101", "code": "MDC_VOL_AWAY_EXP_FORCED_1S"} ]},
     "valueQuantity": {"value": 3.42, "unit": "L", "system": "http://unitsofmeasure.org", "code": "L"}},
    {"code": {"coding": [ {"system": "http://loinc.org", "code": "19870-5", "display": "FVC"} ]},
     "valueQuantity": {"value": 4.31, "unit": "L", "system": "http://unitsofmeasure.org", "code": "L"}} ],
  "extension": [
    {"url": "urn:wellnessz:fhir:quality-flag", "extension": [ {"url": "gate", "valueCode": "ATS_ERS_GRADE"},
        {"url": "outcome", "valueCode": "PASS"}, {"url": "code", "valueCode": "grade_A"} ]},
    {"url": "urn:wellnessz:fhir:capture-method", "valueCode": "DEVICE"},
    {"url": "urn:wellnessz:fhir:clock-skew-ms", "valueInteger": 1200},
    {"url": "urn:wellnessz:fhir:binding-key", "valueString": "fev1L"} ] }
```

Rules: every posted observation carries **LOINC + UCUM** for quantities and **MDC** where the nomenclature has a term
(100 % coverage for certified drivers, E31-F04); `status` `preliminary` until verified, `final` when routed; body site /
laterality as `bodySite` (`LEFT_EAR`, `RIGHT_EAR`, `LEFT_EYE`, `RIGHT_EYE`, `LEFT_ARM` …, SNOMED CT code where free to use);
`effectiveDateTime` is skew-corrected UTC, the original device time is kept in `extension clock-skew-ms`.

### 4.4 DiagnosticReport (composite results)

Used for the audiogram (two ears × frequencies), spirometry session (best test + trials, curve image), 12-lead ECG
(measurements + interpretation text flagged `unverified` + PDF / waveform), POC panel, fit test (exercises + overall fit factor),
ABPM / Holter (indices + report). `result[]` references the component observations, `presentedForm[]` the attachments
(`{contentType, url:"urn:wellnessz:raw:<archiveId>", hash:<base64 SHA-1 per FHIR> }` plus extension `sha256`).
ECG / ABPM interpretation text from the device lives in `conclusion` with extension `urn:wellnessz:fhir:interpretation-status = UNVERIFIED`
until physician sign-off (E33-F06).

### 4.5 Provenance (E31-HLR-14)

```json
{ "resourceType": "Provenance", "target": [ {"reference": "Observation/5f2c…"} ], "recorded": "2026-10-31T09:14:07Z",
  "activity": {"text": "DEVICE_CAPTURE"},
  "agent": [ {"type": {"text": "device"}, "who": {"reference": "Device/DEV-000123"}},
             {"type": {"text": "operator"}, "who": {"identifier": {"value": "nurse1"}}},
             {"type": {"text": "verifier"}, "who": {"identifier": {"value": "nurse1"}}},
             {"type": {"text": "driver"}, "who": {"display": "spiro.ndd.easyone-connect@1.2.0"}},
             {"type": {"text": "endpoint"}, "who": {"display": "EP-LHR-GW-01"}} ],
  "entity": [ {"role": "source", "what": {"identifier": {"system": "urn:wellnessz:raw", "value": "<archiveId>"},
               "display": "sha256:9f86d0…"}} ],
  "extension": [ {"url": "urn:wellnessz:fhir:calibration", "valueString": "CAL-2026-0441|2026-10-31|VALID"},
                 {"url": "urn:wellnessz:fhir:association", "valueString": "SESSION|1.00"},
                 {"url": "urn:wellnessz:fhir:config-version", "valueInteger": 3} ] }
```

**Raw archive.** Every inbound message body and attachment is stored before parsing (`RawPayloadStore.put(bytes|stream) →
{archiveId, sha256, size}`), AES-GCM encrypted with a per-object key derived (HKDF) from the master key, retention class
`DEVICE_RAW_UNFILED` until the observation is routed, then `DEVICE_RAW_PAYLOAD`. `GET /device-engine/archive/{id}/verify`
recomputes the hash (E31-F04 acceptance). Payloads up to `device.ingest.max-payload-bytes` (50 MB) are streamed, never held
in memory as a whole.

## 5. Device catalogue model and seed (E32-F01, E32-HLR-01)

**Seed.** The 133-model inventory (Appendix A, 11 families) is shipped as
`modules/wz-e32-device-console/src/main/resources/e32/device-inventory.json` (fields `id` MD-001…MD-133, `family`, `model`,
`manufacturer`, `ohUse`, `connectivity`, `protocol`, `fdaCodes` (`;`-separated), `sprint`, `confidence`, `options[P1..P10]`).
E32 loads it with an **idempotent seed loader** (`CatalogueSeedLoader`, `ApplicationRunner` guarded by `JobRunGuard`, upsert by
`inventoryId`, records `seed_version` = SHA-256 of the file, never overwrites administrator edits – fields edited in the console
are flagged `locally_modified`). Brand = manufacturer; `modelCode` = inventory id; lifecycle `CANDIDATE` for every seeded model
(confidence other than `High` adds a prerequisite "confirm interface specification with the vendor"). Totals after seeding:
133 models; options P1 73, P2 66, P4 40, P6 32, P3 22, P10 13, P9 12, P5 12, P7 7, P8 4 (asserted by the E32 integration test).

**Lifecycle (model):** `CANDIDATE → CERTIFIED → DEPRECATED → RETIRED`; `CERTIFIED` requires at least one signed driver package in
the registry for the model, a passing conformance run and an acceptable posture (or an approved exception, §13.8).

**Model record (`GET /device-catalogue/models/{modelCode}`):**
`{modelCode, family, manufacturer, model, variants[], ohUse, guidelineDrivers, units[], formsFed[], options[{option, supported, notes}],
protocol, vendorSoftware[{name, versions}], udi{deviceIdentifier?, issuingAgency? GS1|HIBCC|ICCBBA, gudidRef?, eudamedRef?, source, retrievedOn}?,
regulatory[{market US|EU|UK|CA|AU|JP|CN|IN|SA|AE|QA|BH|OTHER, regime, status CLEARED|CE_MARKED|REGISTERED|NOT_REQUIRED|UNKNOWN, productCodes[], reference?, verifiedOn?}],
security{mds2:{received, version?, documentId?}, sbom:{available, format? CYCLONEDX|SPDX, documentId?}, supportStatus SUPPORTED|END_OF_SUPPORT|UNKNOWN,
osSupported?, patchPath?, posture ACCEPTABLE|EXCEPTION_APPROVED|BLOCKED|NOT_ASSESSED}, drivers[{packageId, version, status, certifiedAt}],
lifecycle, sprint, confidence, inventoryId, locallyModified}`.

## 6. Driver packages (E31-HLR-03, E31-F03; registry in E32, signatures by E34)

A driver package is **declarative JSON** – no code per model. One package serves one adapter type for one or more catalogue
models. Packages are semantically versioned (`MAJOR.MINOR.PATCH`; MAJOR when bindings or mapping keys change incompatibly),
signed (Ed25519 by default; signature over the canonical JSON – UTF-8, keys sorted, no insignificant whitespace – of the
package **without** the `signature` member), and contain **no secrets** (E34-HLR-05: the E34 secret scanner rejects
`password|secret|token|apiKey|privateKey` values that are not `devsec:` / `vault:` references).

### 6.1 Schema (`wellnessz.device-driver/1`)

| Member | Type | Rules |
|---|---|---|
| `schema` | const `"wellnessz.device-driver/1"` | required |
| `packageId` | `[a-z0-9]+(\.[a-z0-9\-]+){1,4}` | required, e.g. `spiro.ndd.easyone-connect` |
| `version` | semver | required; unique with `packageId` |
| `family` | `DeviceCodes.Family` | required |
| `models` | `[modelCode]` | ≥ 1, must exist in the catalogue |
| `adapter` | `HL7V2 \| FHIR \| POCT1A \| FILE_CSV \| FILE_XML \| FILE_GDT \| FILE_PDF \| BLE_GATT \| SERIAL \| WEBHOOK_JSON \| CLOUD_POLL_JSON \| DICOMWEB \| OCR \| MANUAL` | required |
| `options` | `[P1..P10]` | options this package serves (subset of the models' options) |
| `engineCompatibility` | range, e.g. `">=1.0 <2.0"` | engine refuses to load outside the range |
| `vendorSoftware` | `{name, versions[]}` | optional |
| `capabilities` | `{worklistPush: NONE\|HL7_ORM\|HL7_ADT\|GDT_6302\|DICOM_MWL\|VENDOR_IMPORT_FILE, startFromForm: bool, historicRetrieval: bool}` | E31-HLR-08 |
| `protocol` | adapter-specific defaults (the §13.3 parameter schemas use them as wizard defaults) | required |
| `parse` | `{type, identity{employee?, badge?, deviceSerial?, orderRef?, effectiveTime{selector, format, timeZone?}}, records?, fields[{key, selector, type QUANTITY\|STRING\|CODE\|BOOLEAN, unit?, required?}]}` | `type` ∈ `HL7_SEGMENTS \| FHIR_PATH \| POCT1A_XPATH \| CSV_COLUMNS \| XPATH \| GDT_FIELDS \| JSON_POINTER \| GATT_STRUCT \| REGEX_LINES \| DICOM_JSON \| FORM` |
| `mappings` | `[{key, mdc?, loinc, display, ucum?, convert?{fromUnit, factor, offset?}, bodySite?, method?, decimals?, plausible?{min, max}}]` | every `parse.fields.key` mapped; LOINC mandatory; UCUM mandatory for QUANTITY |
| `referenceRanges` | `[{key, sex M\|F\|*, ageMin, ageMax, low?, high?, text?}]` | optional |
| `predicted` | `{equation GLI_2012 \| NHANES_III \| NONE, keys{fev1L: FEV1, fvcL: FVC, ratio: FEV1FVC}}` | spirometry |
| `qualityGates` | `[gateCode[:parameter]]` | from §10; order = execution order |
| `bindings` | `[{formCode, formVersion, category, target, fields{key → formField}, verifyBeforePost}]` | default result-to-form bindings (E31-F07); an instance binding may override in the wizard (step 5) |
| `testVectors` | `[{name, input (golden dataset path), expect{key → value, gates{gate → outcome}}}]` | ≥ 1 normal, ≥ 1 malformed, ≥ 1 edge case (E31-HLR-10) |
| `signature` | `{keyId, algorithm, value}` | added by `POST /device-engine/drivers/sign` or offline with the SDK |

**Selectors** per `parse.type`: `HL7_SEGMENTS` – `OBX[OBX-3.1=150456^MDC_PULS_OXIM_SAT_O2]/OBX-5`, `PID-3.1`, `OBR-7`;
`CSV_COLUMNS` – header name or 1-based index; `XPATH` / `POCT1A_XPATH` – XPath 1.0 (namespaces declared in `parse.namespaces`);
`GDT_FIELDS` – four-digit GDT field id (`3000` patient number, `8410` test id, `8420` value, `8421` unit); `JSON_POINTER` –
RFC 6901; `GATT_STRUCT` – ordered fields `{name, type UINT8|UINT16|SFLOAT|FLOAT|DATETIME, flagBit?}` per Bluetooth SIG profile;
`REGEX_LINES` – named groups; `DICOM_JSON` – tag `00100020`; `FHIR_PATH` – `Observation.component.where(code.coding.code='8480-6').valueQuantity.value`.

**Compatibility and conformance.** A package is accepted into the registry only if: schema-valid, signature valid (E34), no
secrets, every test vector passes in the simulator (`POST /device-engine/drivers/conformance`), mappings cover every field, and
the bindings reference form codes known to a deployed sink. The registry keeps every version immutable; instances pin
`packageId@version`; upgrading an instance to a new MAJOR is a configuration change with four-eyes approval (§13.5).

### 6.2 Sample packages (one per adapter family; abbreviated to the members that differ)

**HL7V2 – vitals (IHE PCD-01, MD-027 Welch Allyn Connex Spot Monitor, P4)**
```json
{"schema":"wellnessz.device-driver/1","packageId":"vitals.welchallyn.csm-pcd01","version":"1.0.0","family":"VITALS","models":["MD-027"],
 "adapter":"HL7V2","options":["P4"],"engineCompatibility":">=1.0 <2.0","capabilities":{"worklistPush":"HL7_ADT","startFromForm":false,"historicRetrieval":false},
 "protocol":{"hl7Version":"2.6","messageTypes":["ORU^R01"],"profile":"IHE_PCD_01","ackMode":"ORIGINAL","mllpPort":2575,"tls":"REQUIRED"},
 "parse":{"type":"HL7_SEGMENTS","identity":{"employee":"PID-3.1","deviceSerial":"OBX[OBX-3.1=69837^MDC_DEV_METER_PHYSIO_MULTI_PARAM_MDS]/OBX-18.1",
          "effectiveTime":{"selector":"OBR-7","format":"yyyyMMddHHmmssZ"}},
   "fields":[{"key":"systolic","selector":"OBX[OBX-3.1=150021]/OBX-5","type":"QUANTITY","unit":"mm[Hg]"},
             {"key":"diastolic","selector":"OBX[OBX-3.1=150022]/OBX-5","type":"QUANTITY","unit":"mm[Hg]"},
             {"key":"pulse","selector":"OBX[OBX-3.1=149530]/OBX-5","type":"QUANTITY","unit":"/min"},
             {"key":"spo2","selector":"OBX[OBX-3.1=150456]/OBX-5","type":"QUANTITY","unit":"%"},
             {"key":"temperatureC","selector":"OBX[OBX-3.1=150364]/OBX-5","type":"QUANTITY","unit":"Cel"},
             {"key":"cuffSize","selector":"OBX[OBX-3.1=CUFF_SIZE]/OBX-5","type":"CODE"}]},
 "mappings":[{"key":"systolic","mdc":"MDC_PRESS_BLD_NONINV_SYS","loinc":"8480-6","display":"Systolic blood pressure","ucum":"mm[Hg]","plausible":{"min":60,"max":260}},
             {"key":"diastolic","mdc":"MDC_PRESS_BLD_NONINV_DIA","loinc":"8462-4","display":"Diastolic blood pressure","ucum":"mm[Hg]","plausible":{"min":30,"max":160}},
             {"key":"pulse","mdc":"MDC_PULS_RATE_NON_INV","loinc":"8867-4","display":"Heart rate","ucum":"/min","plausible":{"min":25,"max":240}},
             {"key":"spo2","mdc":"MDC_PULS_OXIM_SAT_O2","loinc":"59408-5","display":"Oxygen saturation by pulse oximetry","ucum":"%","plausible":{"min":50,"max":100}},
             {"key":"temperatureC","mdc":"MDC_TEMP_BODY","loinc":"8310-5","display":"Body temperature","ucum":"Cel","plausible":{"min":30,"max":43}},
             {"key":"cuffSize","loinc":"8358-4","display":"Blood pressure device cuff size"}],
 "qualityGates":["CALIBRATION","BP_CUFF_VALIDATION","PLAUSIBILITY","VERIFY_BEFORE_POST"],
 "bindings":[{"formCode":"E03.DEVICE_RESULT.VITALS","formVersion":"1","category":"VITALS","target":"E03.RESULT",
              "fields":{"systolic":"measurements.systolic","diastolic":"measurements.diastolic","pulse":"measurements.pulse","spo2":"measurements.spo2",
                        "temperatureC":"measurements.temperatureC","cuffSize":"measurements.cuffSize"},"verifyBeforePost":true}],
 "testVectors":[{"name":"normal","input":"golden/vitals/csm-pcd01/normal.hl7","expect":{"systolic":124,"diastolic":78,"gates":{"PLAUSIBILITY":"PASS"}}},
                {"name":"malformed","input":"golden/vitals/csm-pcd01/missing-pid.hl7","expect":{"error":"parse_failed"}},
                {"name":"implausible","input":"golden/vitals/csm-pcd01/sys-400.hl7","expect":{"gates":{"PLAUSIBILITY":"BLOCK"}}}],
 "signature":{"keyId":"pkg-dev-2026","algorithm":"Ed25519","value":"…"}}
```

**FILE_GDT – spirometry (MD-001 / MD-002 ndd EasyOne Connect export, P2)**
```json
{"packageId":"spiro.ndd.easyone-connect-gdt","version":"1.2.0","family":"SPIROMETRY","models":["MD-001","MD-002"],"adapter":"FILE_GDT","options":["P2"],
 "capabilities":{"worklistPush":"GDT_6302","startFromForm":true,"historicRetrieval":false},
 "protocol":{"encoding":"ISO-8859-1","pattern":"*.gdt","pollSeconds":10,"attachmentRule":{"pairBy":"STEM","extensions":["pdf"]},"deleteAfterRead":false},
 "parse":{"type":"GDT_FIELDS","identity":{"employee":"3000","effectiveTime":{"selector":"6200+6201","format":"ddMMyyyyHHmmss","timeZone":"Europe/London"}},
   "records":"8410","fields":[{"key":"fev1L","selector":"8410=FEV1/8420","type":"QUANTITY","unit":"L"},{"key":"fvcL","selector":"8410=FVC/8420","type":"QUANTITY","unit":"L"},
   {"key":"ratio","selector":"8410=FEV1%FVC/8420","type":"QUANTITY","unit":"%"},{"key":"pefLs","selector":"8410=PEF/8420","type":"QUANTITY","unit":"L/s"},
   {"key":"fef2575Ls","selector":"8410=FEF2575/8420","type":"QUANTITY","unit":"L/s"},{"key":"sessionGrade","selector":"8410=QUALITY/8420","type":"CODE"},
   {"key":"acceptableTrials","selector":"8410=NACCEPT/8420","type":"QUANTITY","unit":"1"}]},
 "mappings":[{"key":"fev1L","mdc":"MDC_VOL_AWAY_EXP_FORCED_1S","loinc":"20150-9","display":"FEV1","ucum":"L","decimals":2,"plausible":{"min":0.3,"max":8}},
   {"key":"fvcL","mdc":"MDC_VOL_AWAY_EXP_FORCED_CAPACITY","loinc":"19870-5","display":"FVC","ucum":"L","decimals":2,"plausible":{"min":0.5,"max":10}},
   {"key":"ratio","loinc":"19926-5","display":"FEV1/FVC","ucum":"%","decimals":1},{"key":"pefLs","loinc":"33452-4","display":"Peak expiratory flow","ucum":"L/s"},
   {"key":"fef2575Ls","loinc":"19927-3","display":"FEF 25-75%","ucum":"L/s"},{"key":"sessionGrade","loinc":"LOCAL:ATS_GRADE","display":"ATS/ERS session grade"},
   {"key":"acceptableTrials","loinc":"LOCAL:N_ACCEPTABLE","display":"Acceptable manoeuvres","ucum":"1"}],
 "predicted":{"equation":"GLI_2012","keys":{"fev1L":"FEV1","fvcL":"FVC","ratio":"FEV1FVC"}},
 "qualityGates":["CALIBRATION","QC_DAILY:SPIRO_SYRINGE_3L","ATS_ERS_GRADE:MIN=C","PLAUSIBILITY","VERIFY_BEFORE_POST"],
 "bindings":[{"formCode":"E03.DEVICE_RESULT.SPIROMETRY","formVersion":"1","category":"SPIROMETRY","target":"E03.RESULT",
   "fields":{"fev1L":"measurements.fev1L","fvcL":"measurements.fvcL","pefLs":"measurements.pefLs","fef2575Ls":"measurements.fef2575Ls",
             "sessionGrade":"measurements.atsGrade","fev1PredictedL":"measurements.fev1PredictedL","fvcPredictedL":"measurements.fvcPredictedL"},"verifyBeforePost":true}]}
```
(`LOCAL:` codes are WellnessZ local LOINC-style codes registered in `terminology_map` until a standard code exists; they count as
"coded" for the coverage KPI only when listed in the terminology register.)

**FILE_CSV / database watcher – audiometry (MD-015 Benson CCA-200mini, P2 read-only SQL Server view exported as CSV by the Device Host agent)**
```json
{"packageId":"audio.benson.cca200-db","version":"1.0.0","family":"AUDIOMETRY","models":["MD-015"],"adapter":"FILE_CSV","options":["P2"],
 "protocol":{"source":"DB_QUERY","dialect":"SQLSERVER","query":"select * from v_wz_audiogram where modified_at > :watermark","watermarkColumn":"modified_at",
             "credentialRef":"PARAMETER:dbCredentialRef","pollSeconds":30,"csvDelimiter":","},
 "parse":{"type":"CSV_COLUMNS","identity":{"employee":"EmployeeNo","deviceSerial":"AudiometerSerial","effectiveTime":{"selector":"TestDateTime","format":"yyyy-MM-dd'T'HH:mm:ss","timeZone":"Europe/London"}},
   "fields":[{"key":"left.500","selector":"L500","type":"QUANTITY","unit":"dB[HL]"},{"key":"left.1000","selector":"L1000","type":"QUANTITY","unit":"dB[HL]"},
             {"key":"left.2000","selector":"L2000","type":"QUANTITY","unit":"dB[HL]"},{"key":"left.3000","selector":"L3000","type":"QUANTITY","unit":"dB[HL]"},
             {"key":"left.4000","selector":"L4000","type":"QUANTITY","unit":"dB[HL]"},{"key":"left.6000","selector":"L6000","type":"QUANTITY","unit":"dB[HL]"},
             {"key":"left.8000","selector":"L8000","type":"QUANTITY","unit":"dB[HL]"},{"key":"right.500","selector":"R500","type":"QUANTITY","unit":"dB[HL]"},
             {"key":"right.4000","selector":"R4000","type":"QUANTITY","unit":"dB[HL]"},
             {"key":"ambientNoiseFlag","selector":"AmbientExceeded","type":"BOOLEAN"},{"key":"retest1000Left","selector":"L1000Retest","type":"QUANTITY","unit":"dB[HL]"},
             {"key":"transducer","selector":"Transducer","type":"CODE"},{"key":"tester","selector":"Operator","type":"STRING"}]},
 "mappings":[{"key":"left.4000","mdc":null,"loinc":"89019-4","display":"Hearing threshold 4000 Hz left ear (air)","ucum":"dB[HL]","bodySite":"LEFT_EAR","method":"AIR_CONDUCTION","plausible":{"min":-10,"max":120}}],
 "qualityGates":["CALIBRATION","QC_DAILY:AUDIO_DAILY_BIOLOGICAL","AUDIO_TEST_RETEST:TOLERANCE_DB=5","AUDIO_AMBIENT_NOISE","PLAUSIBILITY","VERIFY_BEFORE_POST"],
 "bindings":[{"formCode":"E03.DEVICE_RESULT.AUDIOMETRY","formVersion":"1","category":"AUDIOMETRY","target":"E03.RESULT",
   "fields":{"left.500":"measurements.left.500","left.4000":"measurements.left.4000","right.4000":"measurements.right.4000","transducer":"measurements.transducer"},"verifyBeforePost":true}]}
```
(Mappings abbreviated: one entry per ear × frequency 500–8000 Hz, LOINC hearing-threshold codes per frequency and laterality.)

**FILE_XML – ECG (MD-045 GE MAC via MUSE XML export, P2) with PDF attachment**
```json
{"packageId":"ecg.ge.muse-xml","version":"1.0.0","family":"CARDIOLOGY","models":["MD-045"],"adapter":"FILE_XML","options":["P2"],
 "protocol":{"pattern":"*.xml","encoding":"UTF-8","attachmentRule":{"pairBy":"XPATH","selector":"/RestingECG/PDF/@file","extensions":["pdf"]}},
 "parse":{"type":"XPATH","identity":{"employee":"/RestingECG/PatientDemographics/PatientID","effectiveTime":{"selector":"concat(/RestingECG/TestDemographics/AcquisitionDate,' ',/RestingECG/TestDemographics/AcquisitionTime)","format":"MM-dd-yyyy HH:mm:ss"}},
   "fields":[{"key":"heartRate","selector":"/RestingECG/RestingECGMeasurements/VentricularRate","type":"QUANTITY","unit":"/min"},
             {"key":"prMs","selector":"/RestingECG/RestingECGMeasurements/PRInterval","type":"QUANTITY","unit":"ms"},
             {"key":"qrsMs","selector":"/RestingECG/RestingECGMeasurements/QRSDuration","type":"QUANTITY","unit":"ms"},
             {"key":"qtcMs","selector":"/RestingECG/RestingECGMeasurements/QTCorrected","type":"QUANTITY","unit":"ms"},
             {"key":"interpretation","selector":"string-join(/RestingECG/Diagnosis/DiagnosisStatement/StmtText,' ')","type":"STRING"}]},
 "mappings":[{"key":"heartRate","mdc":"MDC_ECG_HEART_RATE","loinc":"8867-4","display":"Heart rate","ucum":"/min"},{"key":"prMs","mdc":"MDC_ECG_TIME_PD_PR","loinc":"8625-6","display":"PR interval","ucum":"ms"},
             {"key":"qrsMs","mdc":"MDC_ECG_TIME_PD_QRS","loinc":"8633-0","display":"QRS duration","ucum":"ms"},{"key":"qtcMs","mdc":"MDC_ECG_TIME_PD_QTc","loinc":"8636-3","display":"QTc interval","ucum":"ms"},
             {"key":"interpretation","loinc":"18844-1","display":"ECG interpretation (device, unverified)"}],
 "qualityGates":["CALIBRATION","PLAUSIBILITY","VERIFY_BEFORE_POST"],
 "bindings":[{"formCode":"E03.DEVICE_RESULT.ECG","formVersion":"1","category":"ECG","target":"E03.RESULT",
   "fields":{"heartRate":"measurements.heartRate","qrsMs":"measurements.qrsMs","qtcMs":"measurements.qtcMs","interpretation":"measurements.interpretation"},"verifyBeforePost":true}]}
```

**BLE_GATT – blood pressure (MD-037 A&D UA-651BLE, standard Blood Pressure Profile 0x1810 / 0x2A35, P3)**
```json
{"packageId":"vitals.and.ua651ble-blp","version":"1.0.0","family":"VITALS","models":["MD-037"],"adapter":"BLE_GATT","options":["P3"],
 "protocol":{"service":"0x1810","characteristic":"0x2A35","pairing":"LE_SECURE_CONNECTIONS_PASSKEY","bonding":"REQUIRED","indications":true},
 "parse":{"type":"GATT_STRUCT","fields":[{"key":"flags","selector":"UINT8"},{"key":"systolic","selector":"SFLOAT","type":"QUANTITY","unit":"mm[Hg]"},
   {"key":"diastolic","selector":"SFLOAT","type":"QUANTITY","unit":"mm[Hg]"},{"key":"map","selector":"SFLOAT","type":"QUANTITY","unit":"mm[Hg]"},
   {"key":"timestamp","selector":"DATETIME:flagBit=1"},{"key":"pulse","selector":"SFLOAT:flagBit=2","type":"QUANTITY","unit":"/min"},
   {"key":"unitKpa","selector":"FLAG:bit=0","type":"BOOLEAN"}],"identity":{"effectiveTime":{"selector":"timestamp","format":"GATT_DATETIME"}}},
 "mappings":[{"key":"systolic","mdc":"MDC_PRESS_BLD_NONINV_SYS","loinc":"8480-6","display":"Systolic blood pressure","ucum":"mm[Hg]","convert":{"fromUnit":"kPa","factor":7.50062}},
             {"key":"diastolic","mdc":"MDC_PRESS_BLD_NONINV_DIA","loinc":"8462-4","display":"Diastolic blood pressure","ucum":"mm[Hg]","convert":{"fromUnit":"kPa","factor":7.50062}},
             {"key":"pulse","mdc":"MDC_PULS_RATE_NON_INV","loinc":"8867-4","display":"Heart rate","ucum":"/min"}],
 "qualityGates":["CALIBRATION","BP_CUFF_VALIDATION","PLAUSIBILITY","VERIFY_BEFORE_POST"],
 "bindings":[{"formCode":"E03.DEVICE_RESULT.VITALS","formVersion":"1","category":"VITALS","target":"E03.RESULT","fields":{"systolic":"measurements.systolic","diastolic":"measurements.diastolic","pulse":"measurements.pulse"},"verifyBeforePost":true},
             {"formCode":"E12.SCREENING_RESULT","formVersion":"1","category":"VITALS","target":"E12.SCREENING","fields":{"systolic":"measures.systolic","diastolic":"measures.diastolic"},"verifyBeforePost":false}]}
```

**SERIAL – breath alcohol (MD-097 Lifeloc FC20, RS-232 via Device Host agent, P1)** – restricted category
```json
{"packageId":"da.lifeloc.fc20-serial","version":"1.0.0","family":"DRUG_ALCOHOL","models":["MD-097"],"adapter":"SERIAL","options":["P1"],
 "protocol":{"baud":9600,"dataBits":8,"parity":"NONE","stopBits":1,"flowControl":"NONE","framing":{"type":"LINE","terminator":"\r\n"},"mode":"PUSH"},
 "parse":{"type":"REGEX_LINES","fields":[{"key":"testNumber","selector":"^TEST#(?<v>\\d+)","type":"STRING"},
   {"key":"brac","selector":"^RESULT (?<v>\\d\\.\\d{3}) g/210L","type":"QUANTITY","unit":"g/(210.L)"},
   {"key":"sequence","selector":"^TYPE (?<v>SCREEN|CONFIRM)","type":"CODE"},{"key":"lastCalibration","selector":"^CAL (?<v>\\d{4}-\\d{2}-\\d{2})","type":"STRING"},
   {"key":"deviceSerial","selector":"^SN (?<v>\\S+)","type":"STRING"}],"identity":{"deviceSerial":"deviceSerial","effectiveTime":{"selector":"RECEIVED_AT"}}},
 "mappings":[{"key":"brac","loinc":"75367-0","display":"Alcohol [Mass/volume] in Breath","ucum":"g/(210.L)","decimals":3,"plausible":{"min":0,"max":0.6}},
             {"key":"sequence","loinc":"LOCAL:BREATH_TEST_TYPE","display":"Breath test sequence"}],
 "qualityGates":["CALIBRATION","QC_DAILY:BREATH_ACCURACY_CHECK","PLAUSIBILITY","VERIFY_BEFORE_POST"],
 "bindings":[{"formCode":"E10.BREATH_ALCOHOL","formVersion":"1","category":"BREATH_ALCOHOL","target":"E10.BREATH_ALCOHOL","fields":{"brac":"breathScreenResult","sequence":"sequence"},"verifyBeforePost":true}]}
```

**POCT1A – point of care (MD-081 Abbott Afinion 2 via data manager, P5)**
```json
{"packageId":"poc.abbott.afinion2-poct1a","version":"1.0.0","family":"POC","models":["MD-081"],"adapter":"POCT1A","options":["P5"],
 "protocol":{"messages":["OBS.R01","EVS.R01","QCN.R01"],"ack":"ACK.R01","dmsVendor":"INFO_HQ"},
 "parse":{"type":"POCT1A_XPATH","namespaces":{},"identity":{"employee":"/OBS.R01/PT/PT.patient_id/@V","deviceSerial":"/OBS.R01/HDR/HDR.device_id/@V",
   "effectiveTime":{"selector":"/OBS.R01/PT/OBS/OBS.observation_dttm/@V","format":"ISO_OFFSET"}},
   "records":"/OBS.R01/PT/OBS","fields":[{"key":"hba1c","selector":"OBS.observation_id[@V='HbA1c']/../OBS.value/@V","type":"QUANTITY","unit":"%"},
   {"key":"qcLot","selector":"/OBS.R01/PT/OBS/REG/REG.reagent_lot_number/@V","type":"STRING"},{"key":"operator","selector":"/OBS.R01/OPR/OPR.operator_id/@V","type":"STRING"}]},
 "mappings":[{"key":"hba1c","loinc":"4548-4","display":"Hemoglobin A1c/Hemoglobin.total in Blood","ucum":"%","plausible":{"min":3,"max":20}}],
 "referenceRanges":[{"key":"hba1c","sex":"*","ageMin":18,"ageMax":120,"high":6.0}],
 "qualityGates":["CALIBRATION","QC_LOT","PLAUSIBILITY","VERIFY_BEFORE_POST"],
 "bindings":[{"formCode":"E03.DEVICE_RESULT.POC","formVersion":"1","category":"POC","target":"E03.RESULT","fields":{"hba1c":"measurements.hba1c","qcLot":"measurements.qcLot"},"verifyBeforePost":true}]}
```

**FHIR – wearable aggregator / modern gateway (MD-121, P8)**
```json
{"packageId":"wear.aggregator.fhir-pcha","version":"1.0.0","family":"WEARABLE","models":["MD-121"],"adapter":"FHIR","options":["P8"],
 "protocol":{"resources":["Observation","Bundle"],"profiles":["http://hl7.org/fhir/StructureDefinition/vitalsigns","PCHA"],"consentPurpose":"WEARABLE_DATA"},
 "parse":{"type":"FHIR_PATH","identity":{"employee":"Observation.subject.identifier.value","effectiveTime":{"selector":"Observation.effectivePeriod.end"}},
   "fields":[{"key":"steps","selector":"Observation.where(code.coding.code='55423-8').valueQuantity.value","type":"QUANTITY","unit":"{steps}"},
             {"key":"sleepMinutes","selector":"Observation.where(code.coding.code='93832-4').valueQuantity.value","type":"QUANTITY","unit":"min"}]},
 "mappings":[{"key":"steps","loinc":"55423-8","display":"Number of steps","ucum":"{steps}"},{"key":"sleepMinutes","loinc":"93832-4","display":"Sleep duration","ucum":"min"}],
 "qualityGates":["CONSENT","PLAUSIBILITY"],
 "bindings":[{"formCode":"E12.WEARABLE_DAILY","formVersion":"1","category":"WEARABLE","target":"E12.WEARABLE","fields":{"steps":"STEPS","sleepMinutes":"SLEEP_MINUTES"},"verifyBeforePost":false}]}
```

**WEBHOOK_JSON / CLOUD_POLL_JSON – fit test (MD-105 TSI PortaCount via TSI Link, P6)**
```json
{"packageId":"fit.tsi.link-webhook","version":"1.0.0","family":"RESP_ERGONOMICS","models":["MD-105"],"adapter":"WEBHOOK_JSON","options":["P6"],
 "protocol":{"signature":{"header":"X-Signature","algorithm":"HMAC_SHA256","timestampHeader":"X-Timestamp","replayWindowSeconds":300,"secretRef":"PARAMETER:webhookSecretRef"},
             "poll":{"enabled":true,"url":"PARAMETER:apiBaseUrl","oauth2":{"tokenUrl":"PARAMETER:tokenUrl","clientId":"PARAMETER:clientId","secretRef":"PARAMETER:clientSecretRef","scopes":["fittest.read"]},"intervalSeconds":300}},
 "parse":{"type":"JSON_POINTER","identity":{"employee":"/subject/employeeNumber","deviceSerial":"/instrument/serial","effectiveTime":{"selector":"/completedAt","format":"ISO_OFFSET"}},
   "fields":[{"key":"overallFitFactor","selector":"/overallFitFactor","type":"QUANTITY","unit":"1"},{"key":"pass","selector":"/pass","type":"BOOLEAN"},
             {"key":"protocol","selector":"/protocol","type":"CODE"},{"key":"respiratorModel","selector":"/respirator/model","type":"STRING"},
             {"key":"respiratorSize","selector":"/respirator/size","type":"CODE"},{"key":"exercises","selector":"/exercises","type":"STRING"}]},
 "mappings":[{"key":"overallFitFactor","loinc":"LOCAL:FIT_FACTOR_OVERALL","display":"Respirator overall fit factor","ucum":"1","plausible":{"min":1,"max":10000}}],
 "qualityGates":["CALIBRATION","QC_DAILY:FIT_DAILY_CHECK","FIT_EXERCISES_COMPLETE","PLAUSIBILITY","VERIFY_BEFORE_POST"],
 "bindings":[{"formCode":"E03.DEVICE_RESULT.FIT_TEST","formVersion":"1","category":"FIT_TEST","target":"E03.RESULT",
   "fields":{"overallFitFactor":"measurements.overallFitFactor","pass":"measurements.pass","protocol":"measurements.protocol","respiratorModel":"measurements.respiratorModel","respiratorSize":"measurements.respiratorSize"},"verifyBeforePost":true}]}
```

**DICOMWEB – chest X-ray (MD-125, P7; metadata JSON + archived instance)**
```json
{"packageId":"img.cxr.dicomweb","version":"1.0.0","family":"IMAGING","models":["MD-125"],"adapter":"DICOMWEB","options":["P7"],
 "protocol":{"sopClasses":["1.2.840.10008.5.1.4.1.1.1.1"],"mwl":false,"maxInstanceBytes":52428800},
 "parse":{"type":"DICOM_JSON","identity":{"employee":"00100020","orderRef":"00080050","effectiveTime":{"selector":"00080020+00080030","format":"yyyyMMddHHmmss"}},
   "fields":[{"key":"studyUid","selector":"0020000D","type":"STRING"},{"key":"modality","selector":"00080060","type":"CODE"},{"key":"bodyPart","selector":"00180015","type":"CODE"}]},
 "mappings":[{"key":"studyUid","loinc":"LOCAL:DICOM_STUDY_UID","display":"Imaging study"},{"key":"modality","loinc":"LOCAL:DICOM_MODALITY","display":"Modality"}],
 "qualityGates":["VERIFY_BEFORE_POST"],
 "bindings":[{"formCode":"E03.DEVICE_RESULT.IMAGING","formVersion":"1","category":"IMAGING","target":"E03.RESULT","fields":{"studyUid":"measurements.studyUid","modality":"measurements.modality","bodyPart":"measurements.bodyPart"},"verifyBeforePost":true}]}
```

**OCR – display-only device (MD-089 LeadCare II, P9) and MANUAL – Snellen chart (MD-133, P10)**
```json
{"packageId":"poc.magellan.leadcare2-ocr","version":"1.0.0","family":"POC","models":["MD-089"],"adapter":"OCR","options":["P9"],
 "protocol":{"template":"LEADCARE_II_DISPLAY","regions":[{"key":"bll","box":[0.18,0.32,0.62,0.55],"pattern":"\\d{1,2}\\.\\d"}],"minConfidence":0.90},
 "parse":{"type":"FORM","fields":[{"key":"bll","selector":"bll","type":"QUANTITY","unit":"ug/dL"}]},
 "mappings":[{"key":"bll","loinc":"5671-3","display":"Lead [Mass/volume] in Blood","ucum":"ug/dL","plausible":{"min":0,"max":65}}],
 "qualityGates":["CALIBRATION","QC_LOT","PLAUSIBILITY","VERIFY_BEFORE_POST"],
 "bindings":[{"formCode":"E03.DEVICE_RESULT.POC","formVersion":"1","category":"POC","target":"E03.RESULT","fields":{"bll":"measurements.bll"},"verifyBeforePost":true}]}
{"packageId":"vision.manual.snellen","version":"1.0.0","family":"VISION","models":["MD-133"],"adapter":"MANUAL","options":["P10"],
 "protocol":{"attestation":"DEVICE_AND_CALIBRATION_ATTESTED","csvTemplate":"employeeId,acuityRight,acuityLeft,binocular,corrected"},
 "parse":{"type":"FORM","fields":[{"key":"acuityRight","type":"STRING"},{"key":"acuityLeft","type":"STRING"},{"key":"binocular","type":"STRING"},{"key":"corrected","type":"BOOLEAN"}]},
 "mappings":[{"key":"acuityRight","loinc":"79880-1","display":"Visual acuity right eye","bodySite":"RIGHT_EYE"},{"key":"acuityLeft","loinc":"79881-9","display":"Visual acuity left eye","bodySite":"LEFT_EYE"},
             {"key":"binocular","loinc":"98498-9","display":"Visual acuity both eyes"}],
 "qualityGates":["ATTESTATION","PLAUSIBILITY","VERIFY_BEFORE_POST"],
 "bindings":[{"formCode":"E03.DEVICE_RESULT.VISION","formVersion":"1","category":"VISION","target":"E03.RESULT","fields":{"binocular":"measurements.binocular","acuityLeft":"measurements.acuityLeft","acuityRight":"measurements.acuityRight"},"verifyBeforePost":true}]}
```
All codes above are illustrative and must be confirmed against the LOINC / RTMMS releases loaded into the terminology tables
(E31-BL-013); the conformance suite fails a package whose LOINC or MDC code is not in the loaded release.

## 7. Ingest API for gateways, agents, apps and vendor systems (E31; machine traffic)

**Authentication (kernel filter, done).** Every `/device-ingest/**` call carries:
`X-Device-Endpoint: <endpointId>`, `Authorization: DeviceToken <per-endpoint token>`, and – set by the **ingress** after verifying
the client certificate (TLS 1.2+/1.3 mutual authentication) – `X-Client-Cert-Sha256: <hex SHA-256 of the DER certificate>`
(header name `wellnessz.device.ingest.certificate-header`; the ingress must strip any client-supplied value). Missing
thumbprint → 401 `mtls_required` (plaintext refused, E34-HLR-01); missing token / endpoint → 401 `credentials_missing`; E34
verdicts → 401 with its `failureCode`. The principal is `DeviceEndpointPrincipal.current()`; authority `ROLE_DEVICE_ENDPOINT`;
rate limit = integration budget per endpoint (`wellnessz.security.integration-rate-limit-per-minute`). Person bearer tokens are
**not** accepted on these paths, and device tokens are not accepted anywhere else. Local / test: the E2E script and the
integration tests send the thumbprint header themselves (no ingress in the dev stack).

**Common request headers.** `Idempotency-Key` (**required**; the gateway message id `"<endpointId>:<sequence>"` or SHA-256 of the
body) – unique per endpoint, a repeat returns the original receipt with `status: DUPLICATE` and `Idempotent-Replayed: true`;
`X-Gateway-Sequence` (monotonic per endpoint, for ordering after store-and-forward); `X-Captured-At` (edge receipt time);
`X-Edge-Clock` (edge clock at send – the engine computes `clockSkewMillis = X-Edge-Clock − server time` and applies the edge's
NTP offset reported in the heartbeat); `X-Device-Instance` (optional when the message identifies the device by serial / MSH);
`X-Replayed: true` for store-and-forward replays.

**Common response `IngestReceipt` (202):** `{messageId, status: ACCEPTED|DUPLICATE, receivedAt, deviceInstanceId?, observationIds[]?}`
– 202 means the raw message is **durably stored** (acquire stage committed); processing continues asynchronously. Errors:
400 `parse_failed` (unparseable – dead-lettered, do not retry), 403 `device_not_authorised_for_endpoint`, 404 `device_not_published`,
409 `device_quarantined`, 413 `payload_too_large`, 415 `unsupported_media_type`, 422 `unknown_device` (no catalogue match),
429 `rate_limited` (retry after `Retry-After`), 503 (retry with back-off).

| Method | Path | Body / content type | Response | Notes |
|---|---|---|---|---|
| POST | `/device-ingest/hl7v2` | `application/hl7-v2` or `text/plain` – one ER7 message, MLLP framing removed by the gateway (ORU^R01 PCD-01 / data-manager ORU, ORM / OML acknowledgements, QRY) | `200 text/plain` HL7 ACK: **AA** = stored durably; **AE** = parse / profile error (dead-lettered; device must not resend); **AR** = rejected (unknown or quarantined device, size) – resend only after the cause is fixed | the gateway relays this ACK over MLLP to the device; enhanced ACK mode = original-mode only in this increment |
| POST | `/device-ingest/fhir` | `application/fhir+json` – `Observation`, or `Bundle` (`collection` / `transaction`) with `Device`, `DeviceMetric`, `Observation` (PoCD IG), subject by identifier only (`urn:wellnessz:employee`, badge, or vendor id) | 202 receipt; errors as FHIR `OperationOutcome` (400 / 422) | strict HAPI parse + profile rules (§4.3) |
| POST | `/device-ingest/poct1a` | `application/xml` – POCT1-A2 `OBS.R01`, `EVS.R01`, `QCN.R01`, `DST.R01` (device status) | `200 application/xml` `ACK.R01` with `ACK.type_cd AA|AE` | QC messages update the E32 QC log through the catalogue’s QC import (§13.6) – E31 forwards them as `QcObservation` events |
| POST | `/device-ingest/files` | one exported file per request: `text/csv`, `application/xml`, `text/plain` (GDT, charset declared), `application/pdf`; headers `X-File-Name`, `X-File-Format CSV|XML|GDT|PDF`, `X-File-Sha256` (verified), `X-Source-Path-Hash` (hash of the watched path, never the path) | 202 receipt | PDF exports are paired with their data file by the driver `attachmentRule`; unpaired PDFs wait 10 min, then dead-letter `attachment_unpaired` |
| POST | `/device-ingest/ble` | `application/json` `{records:[{deviceInstanceId?, peripheral:{addressHash, name, bondId}, service:"0x1810", characteristic:"0x2A35", encoding:"HEX"|"BASE64", value, receivedAt, sessionId?, queueEntryId?, operator?}]}` (≤ 100 records) | 202 receipt with one `observationId` per record | from gateways, the nurse tablet (`MOBILE_APP`) and kiosks; `addressHash` = SHA-256(MAC + endpoint salt) – raw MAC addresses never leave the edge |
| POST | `/device-ingest/serial` | `application/json` `{records:[{deviceInstanceId?, port, receivedAt, encoding:"TEXT"|"HEX"|"BASE64", data}]}` | 202 receipt | Device Host agent (P1); framing done at the edge with the driver's `protocol.framing`, parsing centrally |
| POST | `/device-ingest/dicomweb` | `application/dicom+json` – PS3.18 JSON dataset(s) of the instance metadata + `{archiveId}` of the instance uploaded first through `/attachments` | 202 receipt | no pixel processing in the core; DICOM links in the record (E03 IMAGING) |
| POST | `/device-ingest/attachments` | streamed `application/octet-stream` / `application/pdf` / `application/dicom` / `image/png` / `image/jpeg`; headers `X-Attachment-Kind PDF|WAVEFORM|IMAGE|DICOM|RAW`, `X-Content-Sha256` | `201 {archiveId, sha256, sizeBytes}` | ≤ `device.ingest.max-payload-bytes` (50 MB); not multipart; unreferenced attachments purged after 24 h |
| POST | `/device-ingest/fallback` | `application/json` – same body as `POST /device-capture/sessions/{id}/fallback` (§14.3) + `capturedAt`, `operator` | 202 receipt | tablet batch mode offline OCR / manual entries replayed on reconnection; operator must match the batch session operator |
| POST | `/device-ingest/batch` | `application/json` `{items:[{kind:"HL7V2"|"FHIR"|"POCT1A"|"FILE"|"BLE"|"SERIAL"|"FALLBACK", idempotencyKey, sequence, capturedAt, contentType, headers{}, body (base64 for binary)}]}` (≤ 100 items, ≤ 10 MB) | `200 {results:[{idempotencyKey, httpStatus, receipt|problem, ack?}]}` | reconciliation after store-and-forward; items processed in `sequence` order per device instance |
| GET | `/device-ingest/config` | – | `200 GatewayConfig` (§16.3) | pulled on start, on `RELOAD_CONFIG`, and when the heartbeat reports a stale `configVersion` |
| POST | `/device-ingest/heartbeat` | `{gatewayVersion, configVersion, uptimeSeconds, ntpOffsetMillis, queue:{depth, oldestCapturedAt, bytes, capacityPct}, diskFreePct, devices:[{deviceInstanceId, lastSeenAt, state CONNECTED|IDLE|ERROR|PAIRING, signalDbm?}], hardening:{baseline, status PASS|DRIFT|FAIL, findings[]}}` | `200 {serverTime, configVersion, commands:[{commandId, type RELOAD_CONFIG|RESEND_LAST|ROTATE_TOKEN|ISOLATE|PUSH_WORKLIST|START_ACQUISITION, deviceInstanceId?, payload?}]}` | every 60 s; missing for 5 min → connector `STALE`, 30 min → `SILENT` alert |
| POST | `/device-ingest/commands/{commandId}/result` | `{status DONE|FAILED, detail?}` | 204 | |
| GET | `/device-ingest/worklist?deviceInstanceId=&date=` | – | `200 [{orderRef, employeeId, displayName, badgeId?, dateOfBirth?, sex?, scheduledAt}]` | only for instances with `capabilities.worklistPush ≠ NONE`; minimised to what the driver declares it needs; gateway converts to ORM/ADT, GDT 6302 or the vendor import file |
| GET | `/device-ingest/secrets/{ref}` | – | `200 {reference, value}` (no-store) | only references bound to this endpoint's instances (P2 vendor-database credentials for the Device Host agent); resolution audited by E34 |
| POST | `/device-webhooks/{connectorId}` | vendor JSON as sent; headers per driver (`X-Signature` = hex HMAC-SHA256 over `timestamp + "." + rawBody`, `X-Timestamp`) | `202 {messageId}`; 401 `signature_invalid` / `timestamp_out_of_window` (security event `WEBHOOK_SIGNATURE_FAILED`) | **no client certificate** (vendor clouds cannot present one); `connectorId` = a published P6 instance id; per-connector rate limit 600/min; replay window 300 s, nonce = `X-Timestamp`+hash |
| E33 | `/device-ingest/kiosk/**` | see §14.6 | | kiosk self-measurement (endpoint kind `KIOSK`) |

## 8. Pipeline stage contract, dead letters and replay (E31-HLR-01, -13; E31-F01)

**Stages** (each a Spring bean implementing `PipelineStage` inside E31; the message row carries `stage` and `status`; hand-off
by committing the next stage in the same transaction as the stage result – at-least-once, idempotent per stage):

| # | Stage | Input → output | Idempotency / rule | Failure codes |
|---|---|---|---|---|
| 1 | ACQUIRE | HTTP body → `message` + `raw_object` (sha256) | unique `(endpoint_id, idempotency_key)`; duplicate → receipt `DUPLICATE` | `payload_too_large`, `unsupported_media_type` |
| 2 | PARSE | raw → adapter records (driver `parse`) | deterministic for a given driver version | `parse_failed`, `profile_violation`, `attachment_unpaired` |
| 3 | NORMALISE | records → canonical components (mappings, unit conversion, decimals, predicted values, reference ranges, clock-skew correction) | | `mapping_missing`, `unit_unconvertible`, `clock_skew_excessive` (held) |
| 4 | ASSOCIATE | canonical → employee / order / session (§9) | below threshold → `UNMATCHED` (never routed) | `association_conflict` |
| 5 | VALIDATE | canonical → quality flags (§10) | gates per binding / package | `BLOCK` → status `BLOCKED` (with message) |
| 6 | ROUTE | verified (or auto-post) → `DeviceObservationSink.deliver` | sink idempotent on `observationId`; `verifyBeforePost` waits for `accept` | `route_target_unavailable` (retry), `sink_rejected:<code>` |
| 7 | PERSIST | FHIR resources final (`status final`), provenance, raw retention class → `DEVICE_RAW_PAYLOAD` | | |
| 8 | ACKNOWLEDGE | notify listeners (E33), connector metrics, outbox `device.observation.routed.v1` (in-process, PHI-free: ids, category, status) | | |

**Retries and dead letters.** Transient failures (stage exception, sink unavailable) retry with exponential back-off
(5 s, 30 s, 2 min, 10 min, 1 h) up to `device.dlq.max-attempts` (5); deterministic failures (parse, mapping) go to the dead-letter
queue at once. `dead_letter {id, messageId, stage, code, detailMasked, attempts, firstFailedAt, lastFailedAt, status OPEN|REPLAYED|DISCARDED, resolvedBy?, reason?}`.

**Replay semantics.** `POST /device-engine/dead-letters/{id}/replay` (and bulk `POST /device-engine/replay {from, to, deviceInstanceId?, stageFrom PARSE|NORMALISE}`)
re-runs the message **from the stored raw payload** with the **currently published** driver version (or `driverVersion` pinned in
the request) – a new processing attempt of the same `messageId`; observations already routed are not duplicated because the
canonical `observationId` is derived deterministically = UUIDv5(`messageId` + record index + binding key set) and every sink is
idempotent on it. Replaying a day's traffic therefore produces **no duplicates** (E31-F01 acceptance). `discard` requires a
reason and moves the raw payload to `DEVICE_RAW_UNFILED`.

**Ordering.** Per device instance, observations are routed in `(X-Gateway-Sequence, effectiveAt)` order; an out-of-sequence
message older than an already routed observation of the same category for the same employee is routed with flag
`OUT_OF_SEQUENCE` (INFO) – never dropped.

**Performance (E31-HLR-13).** Spot measurement device-to-form ≤ 5 s p95 (ACQUIRE→PENDING_VERIFICATION visible in the capture
dialogue); 50 msg/s sustained (`QueuePerformanceTest`-style test `DevicePipelinePerformanceTest` against simulators, 2× peak soak
documented, E31-BL-024); stage workers are virtual-thread executors polling `message` with `for update skip locked`.

## 9. Association rules and confidence thresholds (E31-HLR-05, E31-F05)

Priority order (first that yields a candidate ≥ threshold wins; all candidates kept as `association_candidate` for audit):

| # | Method | Source | Confidence |
|---|---|---|---|
| 1 | `WORKLIST` | message carries an order / accession / worklist reference issued by the platform (`ORD:` / `APT:`, DICOM accession, GDT 8310 request id) that is open for that employee and device category | 1.00 when reference + employee id agree; 0.90 reference only (employee absent in message) |
| 2 | `SESSION` | `DeviceCaptureSessions.activeSession(instance, effectiveAt)` – the nurse selected the employee in the form; measured within `device.association.session-window-seconds` (900 s) after the session opened | 1.00; 0.80 if the message also carries a **different** employee id → `association_conflict` → UNMATCHED |
| 3 | `BADGE` | badge / NFC id read at the device or kiosk (`masterdata.employee.badge_id`) | 0.98 exact; never fuzzy |
| 4 | `VENDOR_ID_MATCH` | employee id typed in vendor software (PID-3, GDT 3000, CSV column): exact employee id 0.97; exact after normalisation (case, leading zeros, prefixes configured per site) 0.92; fuzzy name + date of birth (vendor demographics) Jaro-Winkler ≥ 0.92 and DOB exact 0.85 | as stated |
| – | `MANUAL_RESOLUTION` | clinician resolved from the unmatched queue | 1.00 (resolver recorded) |

**Thresholds (config, V21):** `device.association.auto-file-threshold` = **0.95** – at or above, the observation proceeds
(still subject to verify-before-post); `device.association.suggest-threshold` = **0.60** – between 0.60 and 0.95 the
observation is **UNMATCHED** with the candidate shown as a suggestion; below 0.60 UNMATCHED without suggestion. **Nothing below
0.95 is ever auto-filed** (E31-F05 acceptance). Two candidates within 0.05 of each other → UNMATCHED (`ambiguous`).
Restricted categories (`BREATH_ALCOHOL`, `DRUG_SCREEN`) accept only `WORKLIST` (E10 test id) or `SESSION`.

**Unmatched queue.** `unmatched_item {id, observationId, siteCode, category, receivedAt, suggestions[{employeeId, displayName, method, confidence}], status OPEN|RESOLVED|DISCARDED, resolvedBy?, resolvedAt?, reason?}`;
SLA `device.unmatched.resolution-sla-hours` (8 – one working day); KPI unmatched < 0.5 % of messages; resolution requires a
reason code `CONFIRMED_IDENTITY | WORKLIST_ERROR | BADGE_ERROR | TEST_TRAFFIC | DUPLICATE | OTHER` and is audited
(`DEVICE_UNMATCHED_RESOLVE`); after resolution the observation re-enters VALIDATE.

## 10. Quality-gate catalogue (E31-HLR-06, E31-F06, E32-F04)

Gates run in the order listed by the binding (fallback: package `qualityGates`). Each returns a `QualityFlag {gate, outcome, code, message}`.
`BLOCK` stops posting with a plain-language message shown in the capture dialogue ("Expired calibration blocks posting with a
clear message", E31-F06); `WARN` requires the verifier to acknowledge the flag in the accept call (`acknowledgedFlags[]`).

| Gate code (parameter) | Applies to | Rule | Outcome |
|---|---|---|---|
| `CALIBRATION` | all device captures | every `DeviceCalibrationCheck` bean (**E14 `EquipmentCalibrationCheck`** on `equipmentDeviceId`, exactly as E03 does today): `OUT_OF_CALIBRATION` / `OUT_OF_SERVICE` → BLOCK `device_out_of_calibration` / `device_out_of_service`; `UNKNOWN` → WARN `device_not_registered` (BLOCK when `wellnessz.record.require-registered-devices=true`) | PASS / WARN / BLOCK |
| `QC_DAILY:<checkType>` | audiometer `AUDIO_DAILY_BIOLOGICAL`, spirometer `SPIRO_SYRINGE_3L` (+ weekly `SPIRO_LINEARITY`), breath alcohol `BREATH_ACCURACY_CHECK`, fit tester `FIT_DAILY_CHECK`, BP `BP_VALIDATION_INTERVAL` | `DeviceCatalogueLookup.qcStatus(instance, day, null)`: `DUE` / `FAILED` → BLOCK `qc_due` / `qc_failed` (lockout, E32-HLR-04); `NOT_REQUIRED` → PASS | PASS / BLOCK |
| `QC_LOT` | POC analysers, LeadCare | lot from the message (`qcLot`) must be in date and have a passing QC run in its window (`qcStatus(instance, day, lot)`) | PASS / BLOCK `qc_lot_invalid` |
| `ATS_ERS_GRADE[:MIN=C]` | spirometry | session grade from device, or computed from trials when the driver delivers them (ATS/ERS 2019: ≥ 3 acceptable, repeatability FEV1 and FVC within 0.150 L → A; 0.200 L → B; 0.250 L → C; …); below `MIN` → WARN `grade_below_minimum` with re-test prompt; grade `F` or no acceptable trial → BLOCK | PASS / WARN / BLOCK |
| `PREDICTED_VALUES` | spirometry | GLI-2012 (age, sex, height from the encounter / employee master data; missing height → WARN `predicted_unavailable`) adds `fev1PredictedL`, `fvcPredictedL`, `%predicted`, LLN | INFO / WARN |
| `AUDIO_TEST_RETEST[:TOLERANCE_DB=5]` | audiometry | 1 kHz re-test of each ear within ± tolerance; otherwise WARN `retest_invalid` (re-test prompt) | PASS / WARN |
| `AUDIO_AMBIENT_NOISE` | audiometry | ambient-noise flag from the audiometer / booth monitor (ANSI S3.1) → WARN `ambient_noise_exceeded` | PASS / WARN |
| `BP_CUFF_VALIDATION` | blood pressure | device model must carry an ISO 81060-2 validation flag in the catalogue (`regulatory`/`validationListed`) → otherwise WARN `bp_device_not_validated`; cuff size present and matching arm circumference when recorded → else WARN `cuff_size_unknown` / `cuff_size_mismatch` | PASS / WARN |
| `FIT_EXERCISES_COMPLETE` | fit test | all protocol exercises present (OSHA App. A) → else BLOCK `fit_test_incomplete` | PASS / BLOCK |
| `PLAUSIBILITY` | all | package `plausible{min,max}` per component and physiological cross-checks (systolic > diastolic, FEV1 ≤ FVC, SpO2 ≤ 100, thresholds −10…120 dB HL, breath alcohol ≤ 0.6): out of range → BLOCK `implausible_<key>`; outlier vs the employee's last 3 values of the same category (> 4 SD or configured delta) → WARN `outlier_<key>` | PASS / WARN / BLOCK |
| `CLOCK_SKEW` | all | `|skew| > device.clock.max-skew-seconds` (120 s) → WARN `clock_skew_corrected`; > 10× → BLOCK `clock_skew_excessive` (held for review) | PASS / WARN / BLOCK |
| `CONSENT` | wearables, kiosk | active `WEARABLE_DATA` consent (E12 enrolment) or kiosk consent of the session → else BLOCK `consent_missing` (data discarded, not stored beyond `DEVICE_RAW_UNFILED`) | PASS / BLOCK |
| `ATTESTATION` | manual entry | attested model, serial and calibration id/date present → else BLOCK `attestation_incomplete` | PASS / BLOCK |
| `OCR_CONFIDENCE` | OCR | any field below `device.ocr.min-confidence` (0.90) is left empty for the clinician; verification always mandatory | INFO |
| `VERIFY_BEFORE_POST` | per binding / instance | `true` → status `PENDING_VERIFICATION` until a clinician accepts (always true for OCR, manual, ECG interpretation, restricted categories); `false` (e.g. wearable daily totals, kiosk screening) → auto-route | INFO |

The gate set and parameters per family are clinical content: the rule set needs clinical SME sign-off (E31-BL-017) –
`GET /device-engine/gates` exposes `{code, description, families, parameters, signOff {status PENDING|APPROVED, by, at}}`.

## 11. Routing SPI into the OH modules (E31-HLR-07, E31-F07)

The engine never writes into another module's tables. It calls `DeviceObservationSink.deliver(actor, Routed)` of the sink whose
`target()` equals the binding's `target`. Sinks are small adapter classes in the consuming module (package `<module>.device`,
written by the **E31 developer**, depending on the kernel only), idempotent on `observationId`, running the module's own
clinical logic. Form codes are `<MODULE>.<FORM>[.<CATEGORY>]`, version `"1"` for all forms in this increment.

| Target | Consuming module and what it does | Categories / form codes | Delivery result |
|---|---|---|---|
| `E03.RESULT` | **E03 record – extends the existing device-result capture** (`ResultService.device`, the logic behind `POST /employees/{employeeId}/results/device`): the adapter builds the same `DeviceRequest` and calls a new package-level entry `ResultService.deviceFromEngine(DeviceRequest, EngineProvenance)` that (a) reuses the calibration check, `DeviceRules` (audiometry STS vs baseline, spirometry ratio / % predicted, vision, ECG), abnormal worklist, letters, `ResultFollowUpListener` (→ E07 surveillance follow-ups) and audit, (b) sets `source=DEVICE`, `device_observation_id`, `capture_method`, `driver_version`, `verified_by`, `verified_at` (V21 columns), (c) returns the existing result on a repeated `device_observation_id`. The E03 `DeviceRequest.category` pattern is widened to `AUDIOMETRY|SPIROMETRY|VISION|ECG|VITALS|BODY_COMPOSITION|ABPM_HOLTER|POC|FIT_TEST|ERGONOMICS|IMAGING` and `measurements` to ≤ 60 entries; new `DeviceRules` evaluators for the added categories are minimal (plausible ranges; POC abnormal vs reference range → `ABNORMAL`; FIT_TEST `pass=false` → `ABNORMAL`; others `NORMAL`, coded LOINC panel) | `E03.DEVICE_RESULT.{AUDIOMETRY, SPIROMETRY, VISION, ECG, VITALS, BODY_COMPOSITION, ABPM_HOLTER, POC, FIT_TEST, ERGONOMICS, IMAGING}` | `RESULT` + result id; the E03 chart shows the device badge (`source`, `capture_method`, device, time) |
| `E07.IH_MEASUREMENT` | **E07 surveillance** – exposure feeds (noise dosimeters, gas-detector docks, vibration): adapter calls the existing `ExposureService` import for one `MeasurementRow {sourceRef = observationId, segCode, hazardCode, measurementType NOISE_DOSIMETRY|AIR_SAMPLING|VIBRATION, value, unit, measuredOn}` (duplicates by `sourceRef` already ignored) – programme enrolment and action levels stay E07 logic. Personal audiometry / spirometry / fit-test results reach E07 **through E03** (`ResultFollowUpListener`), not directly | `E07.IH_MEASUREMENT` (category `EXPOSURE`) | `IH_MEASUREMENT` |
| `E10.BREATH_ALCOHOL` | **E10 substance programme (restricted)** – records the device breath reading against the open test named by `orderRef` (E10 test id; worklist / session only): screening and confirmation sequence, device serial, calibration date, operator, time, location; feeds the existing collection / MRO pathway (`breathScreenResult`); no open test → `REJECTED no_open_test` | `E10.BREATH_ALCOHOL` (`BREATH_ALCOHOL`), `E10.DRUG_SCREEN` (`DRUG_SCREEN`, instrumented readers) | `DA_TEST_READING` |
| `E12.SCREENING` | **E12 health promotion** – kiosk / screening-day vitals into `POST /promotion/screening-events/{id}/results` logic (`Measures` systolic, diastolic, bmi, glucoseMmol, cholesterolMmol, waistCm); `orderRef` = screening event id; thresholds and follow-up cases stay E12 logic | `E12.SCREENING_RESULT` (`VITALS`, `BODY_COMPOSITION`, `POC`) | `SCREENING_RESULT` |
| `E12.WEARABLE` | **E12 consented wearables** – daily aggregates into the existing wearable-data logic (active enrolment + `WEARABLE_DATA` consent, else `REJECTED consent_missing`) | `E12.WEARABLE_DAILY` (`WEARABLE`) | `WEARABLE_DAILY` |
| E05 fitness (no sink) | **E05 consumes device-sourced E03 results** through the existing chart / `ChartContributor`; fitness decisions remain manual E05 actions triggered from the E33 comparison screen (§14.7). ECG interpretation sign-off = E33 sign-off → E03 result acknowledgement (`ackAction FITNESS_REVIEW` where needed) | – | – |
| E14 (no sink) | equipment register stays the calibration authority (`DeviceCalibrationCheck`); `DeviceEquipmentRegister` adapter for E32 migration; `DeviceWorklistSource` adapter (appointments) | – | – |

Sinks for E11 (exposure travel) and E20–E28 partner / incident modules are **out of scope** for this increment (bindings may be
authored; routing to an undeployed target dead-letters with `route_target_unavailable`).

## 12. E31 engine – user and operations API (`/device-engine/**`)

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/device-engine/observations?employeeId=&category=&from=&to=` | OH roles (ABAC on the employee; restricted categories MRO / OH_PHYSICIAN only) | `[ObservationSummary {observationId, category, status, effectiveAt, deviceInstanceId, model, captureMethod, flags[], routedRecordId?}]` – person-level read audited `DEVICE_OBSERVATION_READ` |
| GET | `/device-engine/observations/{id}` | same | `ObservationPreview` (+ FHIR bundle with `?format=fhir`: Observation, DiagnosticReport, Device, Provenance) |
| GET | `/device-engine/observations/{id}/attachments/{archiveId}` | same | streamed PDF / image; waveform as JSON series |
| GET | `/device-engine/unmatched?siteCode=&status=OPEN` | OH_CLINICIAN, OH_PHYSICIAN, NURSING_HEAD, AME, CMO (site team) | `[UnmatchedItem {id, observationId, category, receivedAt, ageHours, slaDueAt, deviceInstanceId, suggestions[{employeeId, displayName, method, confidence}], restricted}]` (restricted: no values) |
| POST | `/device-engine/unmatched/{id}/resolve` | same | `{employeeId, reasonCode, note?}` → re-enters VALIDATE; `{discard:true, reasonCode}` discards |
| GET | `/device-engine/unmatched/count?siteCode=` | + DEVICE_ADMIN | `{open, overdue}` only |
| GET | `/device-engine/dead-letters?stage=&status=` | DEVICE_ADMIN, INTEGRATION_TESTER, PLATFORM_ADMIN | masked `[DeadLetter]` |
| POST | `/device-engine/dead-letters/{id}/replay` · `/discard` | DEVICE_ADMIN, INTEGRATION_TESTER | `{driverVersion?}` / `{reason}` |
| POST | `/device-engine/replay` | DEVICE_ADMIN (four-eyes not required; audited) | `{from, to, deviceInstanceId?, stageFrom}` → `{jobId, messages}` |
| GET | `/device-engine/ops/connectors?siteCode=&state=` | DEVICE_ADMIN, SECURITY_OPS, PLATFORM_ADMIN, NURSING_HEAD | `[ConnectorHealth]` (E31-F11 operations API) |
| GET | `/device-engine/ops/metrics?window=PT1H\|P1D` | same | `{messages, perSecondP95, latencyP50Ms, latencyP95Ms, parseErrorRate, validationBlockRate, unmatchedRate, unmatchedOpen, deadLettersOpen, slaBreaches}` per connector / site |
| GET | `/device-engine/ops/alerts` | same | active alerts `{type STALE|SILENT|ERROR_RATE|LATENCY|UNMATCHED_SLA|QUEUE_CAPACITY|CALIBRATION_DUE, deviceInstanceId?, siteCode, since, level}` → `device.connector-alert` / `device.unmatched-queue` notifications to the on-duty administrator |
| GET / POST | `/device-engine/drivers` · `/device-engine/drivers/validate` | INTEGRATION_TESTER (DEVICE_ADMIN read) | upload package JSON → schema + secret scan + mapping coverage report (no persistence) |
| POST | `/device-engine/drivers/conformance` | INTEGRATION_TESTER | `{packageJson}` → runs all test vectors in the simulator → `{passed, results[{name, outcome, diff}]}`; evidence SHA-256 |
| POST | `/device-engine/drivers/sign` | INTEGRATION_TESTER (request), DEVICE_APPROVER (approve) | signing request; on approval calls `DevicePackageSignatures.sign` and hands the signed package to the E32 registry (`POST /device-catalogue/drivers`) |
| GET | `/device-engine/golden-datasets?family=` | INTEGRATION_TESTER, DEVICE_ADMIN | `[{datasetId, family, adapter, kind NORMAL|EDGE|MALFORMED|TIME_SKEW|DUPLICATE|PERFORMANCE, description}]` (synthetic only) |
| POST | `/device-engine/simulators/{adapter}/run` | INTEGRATION_TESTER, DEVICE_ADMIN | `{datasetId, deviceInstanceId? or configurationId, ratePerSecond?, count?}` → feeds the pipeline as a virtual endpoint (`SIM-<adapter>`, test traffic flagged, retention `DEVICE_RAW_UNFILED`, never routed to production sinks unless `targetTestForm=true` which routes to the wizard test form) |
| GET | `/device-engine/terminology/maps?system=MDC&code=` · `/device-engine/terminology/predicted?equation=GLI_2012&age=&sex=&heightCm=` | INTEGRATION_TESTER, clinicians | reference data; versions listed at `/device-engine/terminology/versions` |
| GET | `/device-engine/gates` | all device roles + clinicians | gate catalogue with sign-off status |
| GET | `/device-engine/archive/{archiveId}/verify` | DEVICE_ADMIN, AUDITOR | `{sha256Stored, sha256Computed, intact}` – no content |
| GET | `/device-engine/tenants` · PUT `/device-engine/tenants/{code}/quota` | PLATFORM_ADMIN | `{code, messagesPerMinute, maxConnectors, catalogueScope}` (E31-F12; quota exceeded → 429 for that tenant's endpoints) |

**Multi-tenancy (E31-HLR-12).** Every engine row carries `tenant_code` (`GROUP` for the airline group; partner tenants from E20
onwards). Endpoints are bound to one tenant (E34); catalogue visibility per tenant (E32 `catalogue_scope`); queues are partitioned by
`tenant_code` in the work-table polling; negative isolation tests are part of `Increment6EngineIntegrationTest`.

## 13. E32 – Device catalogue, administration & provisioning console

### 13.1 Catalogue, option packs and driver registry (E32-F01, F03, F07)

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/device-catalogue/families` | device roles, BIOMEDICAL, OH roles (read) | `[{family, label, models, certifiedModels}]` |
| GET | `/device-catalogue/models?family=&q=&option=&lifecycle=&posture=` | same | `[ModelSummary {modelCode, family, manufacturer, model, options[], lifecycle, posture, certifiedDrivers, confidence, sprint}]` |
| GET | `/device-catalogue/models/{modelCode}` | same | model record (§5) |
| POST / PUT | `/device-catalogue/models[/{modelCode}]` | DEVICE_ADMIN | create (non-seed models get `modelCode` `WZ-000001`…) / edit regulatory, UDI, vendor-software fields (sets `locallyModified`) |
| POST | `/device-catalogue/models/{modelCode}/lifecycle` | DEVICE_ADMIN (to DEPRECATED / RETIRED), DEVICE_APPROVER (to CERTIFIED) | `{to, reason}`; CERTIFIED needs a certified driver + posture ACCEPTABLE or EXCEPTION_APPROVED (422 `posture_blocked`); RETIRED needs no PUBLISHED instance (409) |
| POST | `/device-catalogue/models/{modelCode}/udi` | DEVICE_ADMIN | `{source GUDID|EUDAMED|MANUAL, deviceIdentifier, record (JSON as downloaded), retrievedOn}` → UDI / regulatory fields with source and date (E32-BL-002; no outbound call in this increment) |
| GET | `/device-catalogue/compare?models=MD-001,MD-003` | same as read | side-by-side |
| GET | `/device-catalogue/options[/{option}]` | same as read | option pack `{option, name, howItWorks, typicalDevices[], parameterSchema (JSON Schema 2020-12, §13.3), commonSchema, prerequisites[], stepGuide[{step, text}], diagramUrl, securityLevel HIGH|MEDIUM|LOW, reliability, effort, help{en, ar?…}}` – texts in two languages (E32-BL-004, E32-HLR-11) |
| GET | `/device-catalogue/drivers?modelCode=&status=` · `/device-catalogue/drivers/{packageId}/{version}` | device roles | registry entries `{packageId, version, family, models, adapter, options, status REGISTERED|CERTIFIED|DEPRECATED|REVOKED, sha256, signatureKeyId, conformanceEvidenceSha256, registeredBy, certifiedBy?}`; manifest JSON |
| POST | `/device-catalogue/drivers` | INTEGRATION_TESTER | signed package JSON + `conformanceRunId` → signature verified by `DevicePackageSignatures` (422 `signature_invalid`, event `PACKAGE_SIGNATURE_INVALID`), schema, secret scan, models exist → REGISTERED |
| POST | `/device-catalogue/drivers/{packageId}/{version}/certify` · `/deprecate` · `/revoke` | DEVICE_APPROVER (certify, not the registrant), SECURITY_OPS (revoke) | revoke → instances on that version SUSPENDED + `device.connector-alert` |
| GET | `/device-catalogue/drivers/matrix?modelCode=` | device roles | compatibility matrix models × options × package versions × engine range |

### 13.2 Provisioning wizard state machine (E32-HLR-02, E32-F02)

A **configuration** (`CFG-000123`) provisions one device instance (`DEV-000123`, id fixed at creation) of one model at one
site / station through one option. Configuration status:

```
DRAFT ──submit──▶ AWAITING_APPROVAL ──approve (other person)──▶ CERTIFIED ──publish──▶ PUBLISHED
  ▲  ▲                    │ reject(reason)                                  │  revise ─▶ new DRAFT version (vN+1)
  │  └────────────────────┘                                                 │  suspend / resume (DEVICE_ADMIN)
  │                                     E34 isolation ─▶ QUARANTINED ───────┤  (re-certification: revise → test → approve → publish,
  │                                                                         │   plus E34 release)
 pause/resume (flag `paused`, any non-final state)                          └─ decommission (four-eyes) ─▶ DECOMMISSIONED (final)
```

`currentStep` 1–8 with per-step status `NOT_STARTED | IN_PROGRESS | VALID | INVALID`. Steps may be revisited; changing an earlier
step invalidates later steps (e.g. a new option resets 3–8). Every write is versioned in `change_history` (actor, step, diff).

| Step | Name | Request (`PUT /device-configurations/{id}/steps/{n}`) | Validations (422 `step_invalid` with `errors[{field, code, message}]`) |
|---|---|---|---|
| 1 | Select device | `{modelCode}` | model exists in the tenant's catalogue; lifecycle ≠ RETIRED (DEPRECATED → warning); posture not BLOCKED unless exception approved; or link to request-a-model (§13.9) |
| 2 | Choose connectivity option | `{option}` | option ∈ model `options` with `supported=true`; a REGISTERED/CERTIFIED driver package exists for (model, option) (`no_driver_for_option`); response includes the comparison of all supported options `{option, prerequisites, reliability, securityLevel, effort}`; unsupported options are hidden |
| 3 | Prerequisites checklist | `{items:[{code, status DONE|NOT_APPLICABLE|OPEN, evidence?{text?, documentId?, reference?}}]}`; `POST …/steps/3/auto-check` reruns AUTO items | generated items per option + model: `NETWORK_ZONE` (zone + firewall change reference), `ENDPOINT_IDENTITY` (gateway / agent enrolled in E34 – AUTO via `DeviceEndpointProvisioning.state`), `VENDOR_SOFTWARE_VERSION` (within package `vendorSoftware.versions`), `DRIVERS_INSTALLED`, `PAIRING_CREDENTIALS` (P3), `CERTIFICATES` (P4/P7/P8 TLS), `CONSUMABLES`, `CALIBRATION_EVIDENCE` (AUTO: linked E14 equipment via `DeviceEquipmentRegister` and `DeviceCalibrationCheck` = VALID), `VENDOR_POSTURE` (AUTO), `INTERFACE_SPEC_CONFIRMED` (models with confidence ≠ High); leave only when every required item is DONE / NOT_APPLICABLE |
| 4 | Configure parameters | `{parameters{…option schema…}, common{timeSource, timeZone, units, locale, driverPackageId, driverVersion, referenceRangeOverrides[], qualityGateAdditions[], calibrationSchedule[], verifyBeforePost, retentionClass}, secrets{name: value}}` | parameters valid against the option JSON schema (§13.3) with defaults from the driver package; secrets are stored through `DeviceSecretStore` and replaced by `devsec:` references (responses show `{set:true}` only); gates can only be **added or tightened**, never removed below the package set; driver version CERTIFIED (or REGISTERED for test-only) |
| 5 | Associate & bind | `{associationMethods[], siteCode, roomCode?, stationCode, services[], formCodes[], bindings[{formCode, formVersion, target, fields{key → field}, verifyBeforePost}]}`; `POST …/steps/5/preview {datasetId}` returns the bound fields from a golden dataset | association methods ⊆ driver capabilities (WORKLIST needs `worklistPush ≠ NONE`); station exists (E14 site / room codes); every binding key is a package mapping key; fields exist in the E33 form registry (`GET /device-capture/forms/{formCode}`) |
| 6 | Test | `POST …/steps/6/tests {mode SIMULATOR|LIVE, datasetId?}` → `TestRun` (via `DeviceDiagnostics.runTest`); `POST …/steps/6/nurse-review {outcome ACCEPTED|REJECTED, note}` (OH_CLINICIAN / NURSING_HEAD – the test-form preview) | a PASSED simulator run including the package's negative vectors; for production sites also a PASSED **LIVE** run (connection + end-to-end sample into the test form, security checks: endpoint authenticated with mTLS, plaintext refused) unless `simulatorOnlyJustification` is given (then approval is by DEVICE_APPROVER **and** SECURITY_OPS); nurse review ACCEPTED; evidence SHA-256 stored (publish blocked on failure – E32-BL-005) |
| 7 | Certify & approve | `POST /device-configurations/{id}/submit` (DEVICE_ADMIN) → AWAITING_APPROVAL (+ `device.approval-required`); `POST …/approve {note}` / `POST …/reject {reason}` (DEVICE_APPROVER) | **four-eyes**: approver ≠ submitter and ≠ anyone who changed steps 4–6 of this version (409 `four_eyes_violation`); posture exception recorded; on approval → CERTIFIED, certification record + signed PDF (§13.5), `device.certified` |
| 8 | Publish & monitor | `POST …/publish {}` (DEVICE_ADMIN) → `{deviceInstanceId, configVersion, endpointId, enrolmentToken?, enrolmentExpiresAt?}` | status CERTIFIED; calls `DeviceEndpointProvisioning.provision` (token shown **once**); instance becomes visible through `DeviceCatalogueLookup` (status PUBLISHED) and in nurses' capture lists; calibration / QC schedule and monitoring active; `POST …/clone {siteCode, stationCode}` creates a new DRAFT at step 5 with `clonedFrom` (steps 6–8 required again; LIVE test at the new station) |

Other endpoints: `POST /device-configurations {modelCode, siteCode?, templateId?}` → DRAFT; `GET /device-configurations?status=&siteCode=&modelCode=`;
`GET /device-configurations/{id}` (full, secrets as `{set}`); `POST …/pause` · `/resume`; `POST …/revise` (new DRAFT version of a
PUBLISHED config; the published version keeps running until the new one is published – atomic switch); `POST …/suspend {reason}` ·
`/resume-service`; `GET …/history` (change history with diffs, E32-F06); `GET …/data-flow` (§13.5); `GET …/certification` ·
`GET …/certificate.pdf`. Roles: DEVICE_ADMIN writes; DEVICE_APPROVER approves; INTEGRATION_TESTER may create test-only
configurations on sites flagged `test`; SECURITY_OPS, PLATFORM_ADMIN, AUDITOR read.

### 13.3 Connectivity option parameter schemas P1–P10 (E32-HLR-03, E32-F03)

Each schema is published as JSON Schema 2020-12 at `GET /device-catalogue/options/{option}` (`parameterSchema`); defaults come from
the driver package `protocol`. Secret-typed fields (`format: "secret"`) are write-only and stored via `DeviceSecretStore`.

| Option | Parameters (type, default, validation) |
|---|---|
| **P1 Serial / USB** | `connection` `RS232|USB_CDC|USB_HID_WEDGE`; `port` string (`COM3`, `/dev/ttyUSB0`, or `AUTO` with discovery); `baud` enum 1200…115200 (9600); `dataBits` 7|8 (8); `parity` `NONE|EVEN|ODD` (NONE); `stopBits` 1|2 (1); `flowControl` `NONE|RTS_CTS|XON_XOFF`; `framing{type LINE|STX_ETX|LENGTH|FIXED, terminator, maxFrameBytes ≤ 65536}`; `mode` `PUSH|POLL`, `pollCommand?`, `pollSeconds?`; `usbAllowList[{vid hex4, pid hex4}]` (required for USB); `serialServer{host, port, authRef (secret)}` for serial-over-TCP (authenticated, E34-HLR-04); `hostEndpointId` (Device Host agent) |
| **P2 Vendor software DB / file** | `source` `DB_QUERY|FOLDER_WATCH`; DB: `dialect SQLSERVER|SQLITE|ACCESS`, `connection{host?, port?, database|path}`, `dbCredentialRef` (secret; **read-only** account, E34), `query` (from package, read-only `select` only – validated), `watermarkColumn`, `pollSeconds` 10–3600 (30); folder: `path`, `pattern`, `encoding`, `pollSeconds` (10), `stabilitySeconds` (5, file unchanged before read), `afterRead` `KEEP|MOVE_TO_ARCHIVE` (KEEP – vendor folder is read-only), `attachmentRule`; `dedupKey` (package default); `hostEndpointId` |
| **P3 Bluetooth LE / Classic** | `transport` `BLE|CLASSIC_SPP`; `pairing` `LE_SECURE_CONNECTIONS_PASSKEY|LE_SECURE_CONNECTIONS_NUMERIC|JUST_WORKS_EXCEPTION` (JUST_WORKS only with SECURITY_OPS exception, E34-HLR-04); `bondingOwner` `GATEWAY|TABLET|KIOSK`; `bondingOwnerEndpointId`; `service` UUID, `characteristics[]` UUIDs; `peripheralAllowList[{name?, addressHash}]`; `reconnect` `AUTO|ON_DEMAND`; `rssiMinDbm` (−80) |
| **P4 LAN / Wi-Fi HL7 v2 MLLP** | `deviceAddress{ip|dns}`; `mllp{listenerEndpointId (gateway), port 1024–65535 (2575), tls REQUIRED|EXCEPTION_SEGMENTED_VLAN, tlsVersion 1.2|1.3 (1.3), cipherPolicy MODERN|COMPATIBLE, clientCertRequired bool}`; `msh{sendingApplication, sendingFacility, receivingApplication (WELLNESSZ), receivingFacility}`; `ackMode ORIGINAL`; `messageProfile IHE_PCD_01|VENDOR`; `timeZone`; `worklist{enabled, messageType ADT^A04|ORM^O01, push MLLP_OUT|NONE, outboundPort?}`; plaintext only via a documented exception inside a segmented device VLAN behind the gateway (E34-HLR-01) |
| **P5 POCT1-A2 data manager** | `dms{vendor INFO_HQ|RALS|COBAS_INFINITY_POC|POCCELERATOR|CONNECT_801|CLINITEK_CONNECT|OTHER, endpoint (gateway listener or HTTP), deviceIds[], dmsId}`; `transport POCT1A_XML|HL7_ORU` (data-manager HL7 bridge); `operatorList` `SYNC_FROM_DMS|MANUAL`; `qcLotPolicy{requirePassingQc true, qcWindowHours 24, newLotRequiresQc true}`; `resultMapping` (package); `pollSeconds?` |
| **P6 Vendor cloud API / webhook** | `apiBaseUrl` (https, host must be in `device.cloud.allowed-hosts`); `oauth2{tokenUrl, clientId, clientSecretRef (secret), scopes[]}`; `webhook{enabled, webhookSecretRef (secret), signatureHeader, timestampHeader, replayWindowSeconds 300}`; `poll{enabled, intervalSeconds ≥ 60, rateLimitPerMinute}`; `consentMapping{purpose WEARABLE_DATA|PROGRAMME, scopes[]}`; `residencyRegion` (must match the tenant's residency – `residency_check`) |
| **P7 DICOM** | `aeTitles{calling, called (WZ_GATEWAY)}`; `port` (11112); `tls` REQUIRED; `storageClasses[]` (SOP class UIDs); `mwl{enabled, filters{modality, stationAeTitle}}`; `routing{pacsDestination?}`; `iloReadingWorklist` bool; `maxInstanceBytes` ≤ 52428800 |
| **P8 HL7 FHIR R4** | `clientEndpointId`; `auth` `MTLS_TOKEN|SMART_BACKEND` (`smartScopes[]` e.g. `system/Observation.write`); `profiles[]`; `subjectIdentifierSystem`; `tenantMapping`; `bundleMaxEntries` (100) |
| **P9 Image capture & OCR / barcode** | `ocrTemplate`; `regions[{key, box[x1,y1,x2,y2] relative, pattern}]`; `minConfidence` 0.5–1.0 (0.90); `barcode{enabled, symbology QR|CODE128|DATAMATRIX, payloadMapping}`; `verification` const `MANDATORY`; `imageRetention` `DEVICE_RAW_PAYLOAD|DEVICE_RAW_UNFILED` |
| **P10 Manual entry with attestation** | `attestationFields[MODEL, SERIAL, CALIBRATION_ID, CALIBRATION_DATE]` (all required); `csvTemplate` (campaign bulk import); `validationRules` (package plausibility); `allowedRoles` (clinician roles) |
| **Common (all options)** | `timeSource` `NTP_GATEWAY|DEVICE_RTC|SERVER_RECEIPT`; `timeZone` IANA; `units` `SI|DEVICE` (conversion in mappings); `locale`; `driverPackageId`, `driverVersion`; `referenceRangeOverrides[]`; `qualityGateAdditions[]`; `calibrationSchedule[{checkType, intervalHours, lockout}]`; `verifyBeforePost` (true); `retentionClass` (DEVICE_RAW_PAYLOAD); `tenantCode` |

### 13.4 Configuration governance and environment promotion (E32-HLR-05, E32-F06)

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/device-governance/approvals?status=PENDING` | DEVICE_APPROVER, SECURITY_OPS | pending submissions, decommission requests, imports, driver certifications |
| GET | `/device-governance/changes?from=&to=&configurationId=` | device roles, AUDITOR | complete change history (actor, action, diff, approvalRef) |
| GET | `/device-governance/export?configurationIds=` | DEVICE_ADMIN | `{exportId, sourceEnvironment, createdAt, items[{configuration snapshot without secret values (references only), driver refs}], sha256}` |
| POST | `/device-governance/imports` | DEVICE_ADMIN | export JSON → `{importId, items[{configurationId, action CREATE|UPDATE, diff[{path, from, to}]}]}`; secrets must be re-bound in the target environment |
| POST | `/device-governance/imports/{id}/approve` | DEVICE_APPROVER (≠ importer) | creates DRAFT versions at step 6 (test required in the target environment), then the normal 7–8 path – no production change without an approval record |
| GET / POST / PUT | `/device-governance/templates[/{id}]` | DEVICE_ADMIN | site and family templates `{name, scope SITE|FAMILY, items[{modelCode, option, parameters, bindings, stationPattern}]}` |
| POST | `/device-governance/templates/{id}/batch` | DEVICE_ADMIN | `{siteCode, stations[]}` → drafts for every item; `GET /device-governance/batches/{id}` report (E32-BL-010) |

### 13.5 Certification record, PDF and data-flow summary (E32-HLR-10, E32-F10)

Certification record (immutable, retention `DEVICE_CONFIGURATION`): `{certificationId "DCR-2026-000001", configurationId, version,
deviceInstanceId, model, option, site, station, configurationSnapshot (JSON, secrets as references), snapshotSha256,
driver{packageId, version, sha256, signatureKeyId}, tests[{testRunId, mode, status, evidenceSha256, finishedAt}], nurseReview{by, at, outcome},
prerequisites[{code, status, evidence}], postureException?, submittedBy, submittedAt, approvedBy, approvedAt, dataFlow}`.
The PDF is rendered by E32 with the platform `PdfRenderer` (wz-documents) and stored in `devicecat` with SHA-256 and an approval
signature hash (the employee-bound `DocumentService` is **not** used – the record concerns a device, not a person).
Data-flow summary (`GET /device-configurations/{id}/data-flow`): `{device → endpoint (kind, zone) → adapter → driver → gates →
association methods → bindings [{form, target module, fields, verifyBeforePost}] → retention}` – no person data.

### 13.6 Calibration, QC and lockout (E32-HLR-04, E32-F04; E14 remains the calibration authority)

Annual / exhaustive calibration certificates stay in **E14** (`DeviceCalibrationCheck`); E32 owns the **daily and periodic checks
and QC lots** that E14 does not model, and links each instance to its E14 equipment (`equipmentDeviceId`, migrated with
`DeviceEquipmentRegister`, E32-BL-017 – data-quality report at `GET /device-calibration/migration-report`).

Seeded schedules (proposed, clinical sign-off): `AUDIO_DAILY_BIOLOGICAL` 24 h (before first test of the day), `SPIRO_SYRINGE_3L` 24 h,
`SPIRO_LINEARITY` 7 d, `BP_VALIDATION_INTERVAL` 365 d, `BREATH_ACCURACY_CHECK` 30 d (DOT / vendor quality-assurance plan),
`POC_QC_LOT` per new lot + 30 d, `FIT_DAILY_CHECK` 24 h. All with `lockout=true`.

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET / PUT | `/device-calibration/schedules[/{family}/{checkType}]` | DEVICE_ADMIN (four-eyes for production changes) | `{intervalHours, graceMinutes, lockout, reminderBeforeHours}` |
| POST | `/device-calibration/checks` | DEVICE_ADMIN, BIOMEDICAL, clinicians (quick QC at the station, "two taps", E32-BL-007) | `{deviceInstanceId, checkType, performedAt, outcome PASS|FAIL, values?{}, lot?, lotExpiry?, evidenceDocumentId?}` → status; FAIL → lockout, `device.locked-out` |
| GET | `/device-calibration/status?siteCode=&stationCode=` | device roles, clinicians, BIOMEDICAL | `[{deviceInstanceId, model, checks[{checkType, verdict, lastCheckAt, dueAt}], calibration{verdict, dueOn} (E14), lockedOut}]` – also the station badge in the capture dialogue |
| GET | `/device-calibration/instances/{id}/history` | same | |
| POST | `/device-calibration/instances/{id}/release` | DEVICE_ADMIN | only with a newer PASS check (no override) |
| GET | `/device-calibration/report?siteCode=&from=&to=&format=json|csv|pdf` | DEVICE_ADMIN, NURSING_HEAD, AUDITOR | compliance per site |

QC from devices (POCT1-A `QCN.R01`, vendor QC exports) arrives through E31 and is published in-process as
`device.qc.received.v1` `{deviceInstanceId, checkType, lot, lotExpiry, outcome, performedAt}` (PHI-free) – E32 records it as a check.
Job `device-calibration-reminders` (hourly, `JobRunGuard`) → `device.calibration-due` to the on-duty administrator and the station's
nursing head.

### 13.7 Fleet dashboard and alerts (E32-HLR-04, E32-F05)

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/device-fleet?siteCode=&family=&state=` | DEVICE_ADMIN, SECURITY_OPS, NURSING_HEAD, PLATFORM_ADMIN | `[{deviceInstanceId, modelCode, model, siteCode, stationCode, status, health (from `DeviceDiagnostics.health`), lastSeenAt, firmware, softwareVersion, driverVersion, endpointStatus, calibration, qc, alerts[]}]` |
| GET | `/device-fleet/sites` | same | `[{siteCode, devices, healthy, degraded, silent, quarantined, calibrationDue, lockedOut}]` (inventory map) |
| GET / POST | `/device-fleet/alerts` · `/device-fleet/alerts/{id}/acknowledge` · `/device-fleet/alerts/{id}/ticket` | DEVICE_ADMIN | ticket → outbox destination `itsm-tickets` (flat, allow-listed) |
| PUT | `/device-fleet/instances/{id}/versions` | DEVICE_ADMIN | `{firmware, softwareVersion}` – recorded; incompatible with the pinned driver → alert `DRIVER_INCOMPATIBLE` |
| GET / POST | `/device-fleet/instances/{id}/consumables` | DEVICE_ADMIN, clinicians | `{item, lot, expiry, quantity}` |
| GET / PUT | `/device-fleet/on-duty` | DEVICE_ADMIN | `[{siteCode, username, from, to}]` – alert routing to the on-duty administrator |
| GET | `/workspace/device-fleet` | MANAGER, NURSING_HEAD (own sites) | `{sites:[{siteCode, devices, available, lockedOut, alerts}]}` – counts only (manager workspace, BFF core workspace resource) |

### 13.8 Vendor security onboarding (E32-HLR-06, E32-F07 – with E34-F06)

`PUT /device-catalogue/models/{modelCode}/posture` (DEVICE_ADMIN, SECURITY_OPS) `{mds2{received, version, documentId}, sbom{available, format, documentId},
supportStatus, osName, osSupported, patchPath NONE|VENDOR|SELF, remoteSupport BROKERED|DIRECT|NONE, contacts{securityEmail, disclosureUrl},
contract{supportUntil, patchSlaDays, securityClauses}}` → policy evaluation `{posture, findings[{rule, outcome}]}`. Policy (E34-HLR-07):
unsupported OS, no patch path, direct vendor remote access or missing MDS2 → `BLOCKED`; legacy vendor PCs may be connected only with
an approved exception whose compensating controls include isolation behind a gateway in zone `DEVICE_VLAN` and outbound-only
Device Host agent. `POST …/posture/exceptions {compensatingControls[], expiresOn, riskOwner}` (DEVICE_ADMIN) →
`POST …/posture/exceptions/{id}/approve` (**SECURITY_OPS**) → `EXCEPTION_APPROVED` (expiry job re-blocks).

### 13.9 Request-a-new-model (E32-HLR-07, E32-F08)

`POST /device-model-requests` (DEVICE_ADMIN) `{manufacturer, model, family, connectivity, interfaceDocumentIds[], sites[], urgency}` →
`{requestRef "DMR-2026-000001", status SUBMITTED}`; `GET /device-model-requests?status=`; `PUT /device-model-requests/{id}/scoping`
(INTEGRATION_TESTER) `{existingFamilyAdapter, adapter, effortDays, targetSprint, status SCOPING|SCOPED|IN_DEVELOPMENT|CERTIFIED|REJECTED, note}`
→ `device.model-request-update` to the requester; KPI median scoping turnaround ≤ 5 working days (`GET /device-model-requests/kpi`).

### 13.10 Diagnostics and masked trace (E32-HLR-09, E32-F09)

`GET /device-diagnostics/{deviceInstanceId}/trace?limit=50` · `/health` (DEVICE_ADMIN, INTEGRATION_TESTER, SECURITY_OPS) – via
`DeviceDiagnostics`, PHI-masked (verified by a masking test with synthetic names / ids / dates); `POST /device-diagnostics/{id}/resend`
(DEVICE_ADMIN, audited `DEVICE_RESEND`). No access to the clinical record from these roles.

### 13.11 Decommissioning and key revocation (E32-HLR-08, E32-F11)

`POST /device-configurations/{id}/decommission` (DEVICE_ADMIN) `{reason, disposal DISPOSED|TRANSFERRED|RETURNED_TO_VENDOR|STORED, transferTo?}`
→ approval by DEVICE_APPROVER (four-eyes) → `DeviceEndpointProvisioning.revoke` (effective ≤ 5 min), secrets destroyed
(`DeviceSecretStore.destroy`), bindings removed, instance `DECOMMISSIONED` (no longer returned by `DeviceCatalogueLookup`),
configuration archived (`DEVICE_CONFIGURATION`), device-sourced data retained per its class, disposal record. Negative test: a
post after decommission → 401 `endpoint_revoked` / 404 `device_not_published`.

E32 implements `DeviceEndpointStatusListener`: ISOLATED / REVOKED → instances `QUARANTINED` (`device.endpoint-isolated` to the
on-duty administrator); return to service only through revise → test → approve → publish **and** E34 release (§15.8).

## 14. E33 – Module-embedded device capture

### 14.1 Capture-session state machine (E33-HLR-01, -02, -10; E31-BL-014)

A capture session binds **one employee (and optional case / order) in one OH form** to **one device instance** for one
measurement. At most one LISTENING / RECEIVING session per device instance (409 `device_busy`, shows who holds it).

```
            open                     result notice                       accept (route OK)
  (none) ─────────▶ LISTENING ───────────────────▶ PREVIEW ─────────────────────────────▶ ACCEPTED (final)
                     │   │  ▲ re-test                │  │ reject(reason)                 
                     │   │  └────────────────────────┘  └──────────▶ REJECTED (final; values not filed)
                     │   └─ timeout (device.capture.session-timeout-minutes = 10) ──▶ EXPIRED (final)
                     ├─ cancel ──▶ CANCELLED (final)
                     └─ device unavailable / BLOCKED ──▶ FALLBACK_OFFERED ──choose──▶ LISTENING (other device)
                                                                                 └──▶ FALLBACK_ENTRY (OCR or manual) ──submit──▶ PREVIEW
```

`RECEIVING` is a transient sub-state of LISTENING (progress shown while a multi-part result such as a spirogram session or an
audiogram arrives). A BLOCKED observation shows its gate messages in PREVIEW with *accept* disabled. Every transition is audited
(`DEVICE_CAPTURE_<STATE>`); the final record says which **path** was used: `DEVICE` (instance id), `ALTERNATE_DEVICE`,
`OCR_VERIFIED`, `MANUAL_ATTESTED` (E33-HLR-10).

Session record: `{sessionId, state, employeeId, caseId?, orderRef?, formCode, formVersion, fieldGroup, deviceInstanceId?, path,
operator, siteCode, stationCode, mode FORM|BATCH|KIOSK, openedAt, expiresAt, observationId?, routedRecordId?, fallbackReason?,
rejectReason?}`.

### 14.2 Form-binding API (E33-F01, F02)

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/device-capture/forms` · `/device-capture/forms/{formCode}` | clinicians, COLLECTOR, device roles (field catalogue only) | form registry `{formCode, formVersion, module, category, fieldGroups[{group, fields[{path, label, type, unit?, required}]}], target}` – seeded for the 15 categories (E03.DEVICE_RESULT.*, E07.IH_MEASUREMENT, E10.BREATH_ALCOHOL, E10.DRUG_SCREEN, E12.SCREENING_RESULT, E12.WEARABLE_DAILY); used by the E32 binding editor (step 5) |
| GET | `/device-capture/devices?formCode=&siteCode=&stationCode=` | clinicians, COLLECTOR | station-aware list from `DeviceCatalogueLookup.instancesAtStation` filtered to PUBLISHED, in-calibration, not locked-out, not quarantined: `[{deviceInstanceId, displayName ("Spirometer – Room 2"), model, option, status AVAILABLE|BUSY|LOCKED_OUT|OFFLINE, lockReason?, nextAction?}]` – **no technical parameters** (E33-HLR-01) |
| POST | `/device-capture/sessions` | clinicians (COLLECTOR for E10 forms) | `{formCode, formVersion, fieldGroup?, employeeId, caseId?, orderRef?, deviceInstanceId, siteCode, stationCode}` → `201 Session` (LISTENING); ABAC write check on the employee; triggers worklist / demographics push when the driver supports it (`START_ACQUISITION` / `PUSH_WORKLIST` gateway command) |
| GET | `/device-capture/sessions/{id}` | session operator, clinicians of the site | session + `preview` (`DeviceObservationGateway.preview`) when PREVIEW: values with units, quality flags, raw report / waveform links, bound fields, comparison block (§14.7) |
| GET | `/device-capture/sessions/{id}/events` | same | Server-Sent Events `state` / `progress` / `preview` (fallback: poll `GET …/{id}` every 2 s); end-to-end ≤ 5 s p95 |
| POST | `/device-capture/sessions/{id}/accept` | clinicians (the verifier; restricted categories: COLLECTOR within own session, MRO) | `{acknowledgedFlags[], note?}` → `DeviceObservationGateway.accept` → `{state ACCEPTED, routedRecordType, routedRecordId}`; 422 `observation_blocked`, 422 `flags_not_acknowledged` |
| POST | `/device-capture/sessions/{id}/reject` | same | `{reasonCode RETEST|WRONG_EMPLOYEE|ARTEFACT|DEVICE_FAULT|OTHER, note?}`; `RETEST` keeps the session LISTENING |
| POST | `/device-capture/sessions/{id}/cancel` | operator | |
| POST | `/device-capture/sessions/{id}/fallback` | operator | `{path ALTERNATE_DEVICE|OCR_VERIFIED|MANUAL_ATTESTED, reasonCode DEVICE_UNAVAILABLE|DEVICE_LOCKED|CONNECTION_FAILED|DISPLAY_ONLY|OTHER, deviceInstanceId? (alternate), imageArchiveId? (OCR), values{key: value}, ocrConfidence?, attested{model, serial, calibrationId, calibrationDate}?}` → `DeviceObservationGateway.submitFallback` → PREVIEW |
| POST | `/device-capture/ocr` | clinicians | `multipart image` (≤ 10 MB) + `{deviceInstanceId|modelCode}` → `{imageArchiveId, fields[{key, value, confidence}]}` (OCR through the platform `OcrProvider` when configured; otherwise empty fields – the clinician types and the image is kept as evidence) |
| GET | `/device-capture/employees/{employeeId}/summary?category=` | clinicians (ABAC) | comparison data (§14.7) |

**UI contract.** The "Capture from device" control appears in every measurement field group of the bound forms (E03 device
result, vitals, POC, fit test, ECG; E10 collection; E12 screening). Accepted values fill the bound fields **read-only**, marked
"device-sourced · {model} · {time}" (or "photo verified" / "entered manually – attested") with the provenance link; re-measure
replaces the whole set. Controls meet WCAG 2.2 AA, 44 × 44 px touch targets (glove use), no technical settings (E33-HLR-11).

### 14.3 Fallback chain (E33-HLR-10, E33-BL-002)

Order offered when the chosen device is unavailable, locked out or fails: (1) **another certified device** of the same category at the
station (`/device-capture/devices`), (2) **image / OCR with mandatory verification** when the model's options include P9 or a P9
package exists for the category, (3) **manual entry with attestation** (P10: model, serial, calibration id and date attested; the
attestation statement code `DEVICE_AND_CALIBRATION_ATTESTED`). The session records `path` and `fallbackReason`; OCR and manual
observations always need verification; the E03 result carries `capture_method`. Restricted categories have no manual path except
by COLLECTOR with MRO-visible attestation.

### 14.4 Family-specific capture rules (E33-HLR-03 … -06; module logic stays in the module)

* **Audiometry (E33-F03):** thresholds per ear 500–8000 Hz incl. 3000 / 6000 Hz, transducer, booth, ambient-noise flag, tester; E03
  `DeviceRules.audiometry` computes STS vs baseline (rule sets OSHA / HSE / aviation configured in E03 / E07, age correction OSHA App. F
  from the E31 terminology tables); audiogram chart rendered in the preview.
* **Spirometry (E33-F04):** FEV1, FVC, FEV1/FVC, PEF, FEF25–75, best test, ATS/ERS grade, GLI-2012 predicted, % predicted, LLN, curve
  image; respirator-clearance hook = E03 result follow-up (`ResultFollowUpListener` → E07).
* **Vision (E33-F05):** acuity per eye and distance (far / intermediate / near), with / without correction, colour-vision method and
  result, stereopsis / phoria / fields; templates `DOT`, `FAA_EASA`, `DSE`, `AIRSIDE_DRIVING` = E33 form variants of
  `E03.DEVICE_RESULT.VISION` (field groups), selected by the programme of the order.
* **ECG / ABPM / Holter (E33-F06):** 12-lead PDF + waveform, measurements, device interpretation **unverified** until physician sign-off
  (`POST /device-capture/sessions/{id}/sign-off` – PHYSICIANS – sets the interpretation status and acknowledges the E03 result);
  ABPM / Holter indices (mean day / night systolic / diastolic, dipping %) + report.
* **Vitals & body composition (E33-F07):** BP with cuff size, position (`SITTING|STANDING|SUPINE`), arm, ISO 81060 validation flag,
  pulse, SpO2, temperature, height, weight, BMI (computed by E31 normalisation), impedance metrics; one sequence = one session with
  several observations (`sequence:true`).
* **Breath alcohol & drug screen (E33-F08, restricted):** device model / serial, calibration / accuracy-check date, screening and
  confirmation sequence (confirmation within 15–30 min window shown as a timer), result, operator, time, location, consent and
  chain-of-custody references from E10; session `orderRef` = E10 test id (mandatory).
* **POC & fit test (E33-F09):** analytes with units, reference ranges, QC lot status, operator; abnormal → E03 abnormal worklist
  (clinician queue); fit test respirator model / size, protocol, exercises, overall fit factor, pass → E03 FIT_TEST result.

### 14.5 Mobile batch mode (E33-HLR-07, E33-F10)

`POST /device-capture/batches` (clinicians) `{siteCode, screeningEventId?, formCode, deviceInstanceIds[], employeeIds[] | badgeIds[]}` →
`{batchId, sessions:[{queueEntryId, sessionId, employeeId, displayName}]}` (sessions pre-created in LISTENING-on-demand state so the
tablet can work **offline**); `GET /device-capture/batches/{id}`; `POST /device-capture/batches/{id}/next` (online one-tap advance);
offline, the tablet (`MOBILE_APP` endpoint) submits readings via `/device-ingest/ble` (with `sessionId`, `queueEntryId`) and
fallbacks via `/device-ingest/fallback`; on reconnection `/device-ingest/batch` reconciles in sequence order; acceptance of queued
previews by the nurse when back online (`POST /device-capture/batches/{id}/accept-all {sessionIds[], acknowledgedFlags{}}` – only
observations without WARN / BLOCK flags). Acceptance: 200 employees offline without loss.

### 14.6 Kiosk self-measurement (E33-HLR-07, E33-BL-013)

Kiosk = endpoint kind `KIOSK` (authenticated by the device-ingest filter). `POST /device-ingest/kiosk/identify {badgeId}` →
`{kioskSessionId, firstName, language, consentRequired, consentNoticeVersion}` (no other personal data); `POST /device-ingest/kiosk/{kioskSessionId}/consent
{accepted, noticeVersion}` (consent purpose `KIOSK_SELF_MEASUREMENT` recorded through the platform `ConsentService`);
readings via `/device-ingest/ble` / `/serial` with `sessionId = kioskSessionId`; `GET /device-ingest/kiosk/{kioskSessionId}/summary` →
plain-language summary in the employee's language (BP category text, BMI band – no diagnosis); session ends after 3 min idle
(privacy screen). Routing: `E12.SCREENING` (auto-post, verifyBeforePost=false) or `E03.RESULT` with verification by the clinic.

### 14.7 Comparison, trends and clinician actions (E33-HLR-08, E33-F11)

`GET /device-capture/employees/{employeeId}/summary?category=AUDIOMETRY|SPIROMETRY|VITALS|BODY_COMPOSITION` →
`{previous[{effectiveAt, values, flag}], baseline?, thresholds[{key, value, source}], trend[{date, key, value}]}` (from E03 results via
the existing chart API – E33 calls the E03 **REST** contract from the UI, the server-side summary uses only E31 observations);
annotations `POST /device-capture/sessions/{id}/annotations {text}` (screened); actions from the same screen call the existing module
endpoints from the UI (E05 fitness decision, restriction, referral, programme completion) – E33 does not wrap them.

### 14.8 Employee summaries and wearable consent (E33-HLR-09, E33-F12)

`GET /me/device-summaries` (EMPLOYEE) → released summaries `[{summaryId, category, measuredAt, plainLanguage (in the preferred language), educationLink?}]`
– released when the clinician accepts and the form's release rule allows (`releaseToEmployee` in the binding, default false for
restricted / ECG); notification `device.summary-available`. Wearable consent reuses E12 enrolments and the platform
consent (`WEARABLE_DATA`): `GET /me/device-consents` · `POST /me/device-consents {purpose WEARABLE_DATA, scopes[STEPS|SLEEP|HEART_RATE|ACTIVITY], campaignId?}` ·
`DELETE /me/device-consents/{id}` (revocation → E31 stops accepting, E12 purge as in increment 5).

## 15. E34 – Endpoint & integration security

### 15.1 Endpoint identity (E34-HLR-01, E34-F01)

Endpoint `{endpointId "EP-<SITE>-<KIND>-<NN>" (e.g. EP-LHR-GW-01), kind, siteCode, zone, tenantCode, status PENDING_ENROLMENT|ACTIVE|ISOLATED|REVOKED,
certificate{sha256, subject, issuer, serial, notBefore, notAfter}, tokenHash (PBKDF2-HMAC-SHA256 ≥ 210 000 iterations or HMAC-SHA256
with a server pepper; constant-time compare), tokenIssuedAt, tokenExpiresAt (≤ 90 days, rotation), deviceInstanceIds[], createdBy, enrolledAt?,
isolatedAt?, revokedAt?, recertificationRequired}`. Unique identity per gateway, agent, tablet, kiosk, data manager and vendor cloud
connection – **no shared secrets** (KPI 100 %). Vendor clouds (`VENDOR_CLOUD`) authenticate webhooks by HMAC (§7) and are pulled
with OAuth2 client credentials held in `DeviceSecretStore`.

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/device-security/endpoints?siteCode=&status=&kind=` | SECURITY_OPS, DEVICE_ADMIN (read) | inventory of identities (E34-F01) incl. certificate expiry |
| POST | `/device-security/endpoints` | SECURITY_OPS, DEVICE_ADMIN | `{kind, siteCode, zone, tenantCode, deviceInstanceIds[]}` → `{endpointId, enrolmentToken (once), enrolmentExpiresAt (≤ 72 h)}` (the same as `DeviceEndpointProvisioning.provision`) |
| POST | `/device-security/endpoints/{id}/rotate-token` | SECURITY_OPS | new token returned once (or delivered as a `ROTATE_TOKEN` gateway command over the authenticated channel); old token valid 15 min grace |
| POST | `/device-security/endpoints/{id}/isolate` · `/release` · `/revoke` | SECURITY_OPS | `{reasonCode, note}`; release requires `recertified=true` from E32 for the affected instances (409 `recertification_required`) |
| GET | `/device-security/endpoints/{id}/events` | SECURITY_OPS | security events of the endpoint |

### 15.2 Enrolment (gateway / agent / tablet / kiosk)

1. Administrator creates the endpoint (wizard publish or §15.1) → one-time **enrolment token** (256-bit, shown once, hashed at rest, ≤ 72 h).
2. The device obtains its **client certificate from the group PKI** (EST / SCEP / manual CSR outside the platform; local dev:
   `tools/device/dev-ca.sh` creates a throw-away CA and client certificates – written by the E34 developer).
3. `POST /device-enrolment/enrol` (no bearer; TLS with the new client certificate through the mTLS ingress, which sets
   `X-Client-Cert-Sha256`) `{enrolmentToken, endpointKind, certificatePem, proof (base64 signature with the certificate's private key
   over "enrol:" + enrolmentToken + ":" + X-Client-Cert-Sha256), hostname, platform{os, osVersion, agentVersion}, hardening{baseline, status},
   attestation? (TPM quote, optional – E34-BL-014)}` → E34 checks token (one-time, unexpired, kind matches), chain against the configured
   trust anchors (JDK `CertPathValidator`, `wellnessz.device.pki.trust-anchors` PEM), key usage `clientAuth`, proof of possession, thumbprint
   equals header → `201 {endpointId, endpointToken (once), tokenExpiresAt, configUrl:"/api/v1/device-ingest/config", serverTime}`.
4. Failure codes: 400 `enrolment_invalid`, 401 `enrolment_token_invalid|enrolment_token_expired`, 409 `already_enrolled`, 422
   `certificate_untrusted|certificate_key_usage|proof_invalid|thumbprint_mismatch`; throttled 10 attempts / 15 min per source address
   (429); every failure is a security event `ENROLMENT_FAILED`.
5. Certificate renewal: `POST /device-ingest/certificate-renewal {certificatePem, proof}` (authenticated with the old certificate +
   token) binds the new thumbprint; expiry < 30 days raises `CERTIFICATE_EXPIRING` (`device.certificate-expiring`).

### 15.3 Request authentication for device / gateway calls (done in the kernel; E34 decides)

Kernel `DeviceEndpointAuthenticationFilter` → `DeviceEndpointAuthenticator.authenticate(Credentials{endpointId, certificateSha256, token,
remoteAddress, method, path})`. E34 rules in order: endpoint exists (`unknown_endpoint`) → status ACTIVE (`endpoint_pending`,
`endpoint_isolated`, `endpoint_revoked`) → token hash matches and not expired (`bad_token`, `token_expired`) → thumbprint equals the
bound certificate and the certificate is within validity (`certificate_mismatch`) → zone rule for the path and remote address
(`zone_violation`) → `Result.success(principal)` with the authorised `deviceInstanceIds`. Revocation is effective on the next request
(cache ≤ 60 s; SLA 5 min). E34 must not publish the failure event (the filter does, once). Successful authentications are **not**
written to the audit log per request (volume); E34 keeps per-endpoint counters for anomaly rules.

### 15.4 Network zoning (E34-HLR-02, E34-F02)

Zones `DEVICE_VLAN` (medical devices, deny-by-default), `GATEWAY_ZONE` (gateways, Device Host agents), `CLINIC_LAN` (tablets,
kiosks via SSID), `VENDOR_CLOUD` (internet webhooks / pulls), `CORE` (engine). Conduits `{fromZone, toZone, protocol HTTPS_MTLS|MLLP_TLS|DICOM_TLS|BLE|SERIAL,
ports[], direction}` stored and exported as firewall policy templates (`GET /device-security/zones` · `/conduits` ·
`/conduits/export?format=csv|json`, SECURITY_OPS). Rule enforced at authentication: endpoints of kind `GATEWAY`, `DEVICE_HOST_AGENT` must
call from address ranges configured for their site's `GATEWAY_ZONE` (`device-security.zone-cidrs`), otherwise `zone_violation`; devices
never reach the core directly (no endpoint kind `DEVICE` with zone `DEVICE_VLAN` may authenticate – they go through a gateway).
Vendor remote support only via a brokered, recorded session (documented pattern in the reference network design, E34-BL-002).

### 15.5 Driver-package signatures (E31-HLR-03, E34-HLR-06)

`DevicePackageSignatures` – Ed25519 (JDK `Signature.getInstance("Ed25519")`), optional `SHA256withECDSA` (P-256). Keys
`{keyId, algorithm, publicKey, status ACTIVE|RETIRED|REVOKED, createdAt}`; private key encrypted with wz-secrets (production: HSM / KMS
reference). `GET /device-security/signing-keys` (SECURITY_OPS, INTEGRATION_TESTER read public keys) · `POST /device-security/signing-keys`
(SECURITY_OPS, rotate) · `POST /device-security/signing-keys/{keyId}/revoke` (packages signed with it → REVOKED in the E32 registry,
instances SUSPENDED). Canonical form: UTF-8 JSON, object keys sorted lexicographically, no whitespace, numbers as written, `signature`
member removed.

### 15.6 Secrets (E34-HLR-05, E34-F04)

`DeviceSecretStore` – values encrypted with `FieldEncryptor` (context `devsec:<ref>`), never logged, never returned to browsers;
`resolve` only for the engine runtime and the gateway endpoint bound to the instance (audited `DEVICE_SECRET_RESOLVE` with purpose);
rotation keeps the reference. `GET /device-security/secrets?scope=` (SECURITY_OPS: metadata only `{reference, scope, name, createdAt,
rotatedAt, lastResolvedAt, boundInstances}`) · `POST /device-security/secrets/{ref}/rotate`. Secret scanner (shared by E32 import,
E31 driver validation and the test pack): rejects literal values for keys matching `(?i)(password|secret|token|api[-_]?key|private[-_]?key|credential)`
unless the value matches `^(devsec|vault):`.

### 15.7 Vendor posture and compensating controls (E34-HLR-07, E34-F06)

Posture intake and policy live in E32 (§13.8); E34 provides the isolation pattern: legacy vendor PCs get a Device Host agent endpoint in
`GATEWAY_ZONE` with outbound-only conduits, EDR status reported in the heartbeat, and a compensating-control register
(`GET /device-security/compensating-controls`, SECURITY_OPS) referenced by the approved exception. Contract clauses for new purchases
are a template document (`docs/increment-06` security pack).

### 15.8 Anomaly rules, isolation and compromise playbook (E34-HLR-09, E34-F08)

Rules (configurable `PUT /device-security/anomaly-rules/{code}`, SECURITY_OPS; seeded):

| Code | Condition (window) | Severity | Automatic action |
|---|---|---|---|
| `NEW_DEVICE_IDENTITY` | first authentication of an endpoint / first message from an unknown device serial behind a known gateway | MEDIUM | alert |
| `FAILED_AUTH_BURST` | ≥ 10 `ENDPOINT_AUTH_FAILED` for one endpoint id or source address in 5 min | HIGH | alert; ≥ 30 → auto-isolate the endpoint |
| `MESSAGE_RATE_SPIKE` | > 5× the endpoint's 7-day p95 per minute, or > configured ceiling | MEDIUM | alert; sustained 10 min → throttle (429) |
| `UNEXPECTED_PROTOCOL` | message kind / path not allowed for the endpoint's instances (e.g. FHIR from a serial agent) | HIGH | reject + alert |
| `UNEXPECTED_PORT` | gateway heartbeat reports a listener / port outside its conduits | HIGH | alert |
| `CERTIFICATE_ANOMALY` | thumbprint change without renewal, certificate from an unexpected issuer, expired certificate presented | HIGH | reject + alert |
| `CERTIFICATE_EXPIRING` | certificate expires within `device.security.cert-expiry-warning-days` (30) | LOW | notification |
| `HARDENING_DRIFT` | heartbeat `hardening.status = DRIFT|FAIL` | MEDIUM | alert |
| `WEBHOOK_SIGNATURE_FAILURES` | ≥ 5 `WEBHOOK_SIGNATURE_FAILED` per connector in 10 min | HIGH | alert; connector paused |
| `OFF_HOURS_CONFIG_ACCESS` | configuration export / secret metadata access outside the site's clinic hours | LOW | alert |

Alerts: `GET /device-security/alerts?status=OPEN` · `POST /device-security/alerts/{id}/acknowledge|close {resolution}` (SECURITY_OPS);
`device.security-alert` notification to SECURITY_OPS on duty. **Isolation playbook** (`POST /device-security/endpoints/{id}/isolate`
or automatic): within `device.security.isolation-sla-minutes` (15) the endpoint status becomes ISOLATED (authentication refused at
once), a `ISOLATE` command is queued for the gateway (quarantine of the local queue), evidence preserved (`POST /device-security/incidents`
creates `{incidentRef "DSI-2026-000001", endpointId, alertIds[], evidence[{type RAW_MESSAGES|EVENTS|HEARTBEATS, sha256, archiveId}], timeline[]}`
– raw messages referenced by archive id and hash, not copied), `DeviceEndpointStatusListener.endpointStatusChanged(… ISOLATED …)` →
E32 quarantines the instances, E31 parks in-flight messages (`ENDPOINT_ISOLATED` dead-letter code). Release requires re-certification
(E32) and SECURITY_OPS release. The drill record (`isolationCompletedAt − detectedAt`) is reported at `GET /device-security/incidents/{ref}`.

### 15.9 Security event model and SIEM (E34-HLR-08, E34-F07)

`DeviceSecurityEvent {type, severity, endpointId?, deviceInstanceId?, siteCode?, actor, sourceModule E31|E32|E33|E34|KERNEL, reasonCode?,
attributes{} (PHI-free), occurredAt}` – stored by E34 in an **append-only, hash-chained** table (`devicesec.security_event`: id, previous
hash, hash = SHA-256(previous + canonical event), no update / delete grant, trigger rejects changes), verified by
`GET /device-security/events/verify` (`{intact, checked, firstBrokenId}`) and forwarded to the SIEM via the outbox destination
**`siem-device-security`** (flat, allow-listed fields: `messageId, eventId, type, severity, endpointId, deviceInstanceId, siteCode, zone,
sourceModule, reasonCode, actor, remoteAddress, protocol, port, rate, keyId, chainHash` + `eventType, occurredAt`).

Event types: `ENDPOINT_CREATED`, `ENROLMENT_SUCCEEDED`, `ENROLMENT_FAILED`, `ENDPOINT_AUTH_FAILED`, `TOKEN_ROTATED`, `CERTIFICATE_RENEWED`,
`CERTIFICATE_EXPIRING`, `ENDPOINT_ISOLATED`, `ENDPOINT_RELEASED`, `ENDPOINT_REVOKED`, `UNKNOWN_DEVICE_IDENTITY`, `MESSAGE_RATE_SPIKE`,
`UNEXPECTED_PROTOCOL`, `ZONE_VIOLATION`, `WEBHOOK_SIGNATURE_FAILED`, `PACKAGE_SIGNATURE_INVALID`, `PACKAGE_SIGNED`, `SIGNING_KEY_ROTATED`,
`SECRET_STORED`, `SECRET_RESOLVED`, `SECRET_ROTATED`, `CONFIG_SUBMITTED`, `CONFIG_APPROVED`, `CONFIG_REJECTED`, `CONFIG_PUBLISHED`,
`CONFIG_EXPORTED`, `CONFIG_IMPORTED`, `DEVICE_DECOMMISSIONED`, `POSTURE_EXCEPTION_APPROVED`, `HARDENING_DRIFT`, `DIAGNOSTIC_TRACE_VIEWED`,
`DEVICE_DATA_ACCESSED` (aggregated per user / hour, no employee id), `TEST_PACK_RUN`. Query: `GET /device-security/events?type=&endpointId=&from=&to=`
(SECURITY_OPS, AUDITOR); access report `GET /device-security/reports/access?from=&to=`.

### 15.10 Privacy controls (E34-HLR-10, E34-F09)

Minimisation (gateways strip demographics not declared by the driver; BLE addresses hashed; worklist push minimised); purpose binding
(`resolve(ref, purpose)`, consent purposes `WEARABLE_DATA`, `KIOSK_SELF_MEASUREMENT`); retention classes (§17) enforced by E31 / E32 / E34
jobs (`device-retention`, daily, `JobRunGuard`); telemetry pseudonymised (metric labels never carry employee ids, instance ids only);
no PHI in non-production (simulators and golden datasets are synthetic; the import of production config sets carries no person data);
DPIA update tracked at `GET /device-security/privacy/dpia` `{version, status PENDING|APPROVED, approvedBy?, approvedAt?}` (DPO approves via
`POST …/dpia/approve`).

### 15.11 Security test pack (E34-HLR-11, E34-F10 – separate deliverable `tools/security/device-test-pack/`)

Scripts (defensive, run only with `--authorised` and a targets file; each writes JSON evidence + a summary line):
`01_network_segmentation_check.py`, `02_tls_mtls_check.sh`, `03_mllp_auth_and_robustness.py` (malformed / oversized / fuzzed HL7 – no
crash, AE/AR, no resource exhaustion), `04_fhir_api_authz.py` (object-level checks, tenant isolation, device token vs user token),
`05_gateway_hardening_linux.sh` (incl. USB allow-list), `05b_device_host_hardening_windows.ps1`, `06_ble_pairing_policy.py`,
`07_sbom_and_vuln_scan.sh` (CycloneDX via the existing Maven plugin, dependency-check thresholds), `08_secrets_and_config_audit.py`
(secret scanner + PHI patterns), `09_audit_log_integrity.py` (platform audit `/audit/verify` + `/device-security/events/verify`),
`10_encryption_at_rest_check.sh`, `11_zap_api_baseline.sh`, `12_device_default_credentials_check.py`, `13_time_sync_and_cert_expiry.py`,
`14_rate_limit_and_anomaly_check.py` (429 behaviour, FAILED_AUTH_BURST → isolation ≤ 15 min), `15_report_builder.py` (control matrix
SC-01…SC-16 → evidence). Plus `test-plan.md` (rules of engagement), `evidence-template.md`, `control-mapping.csv` (IEC 62443, NIST
800-53, FDA §524B, IEC 81001-5-1, OWASP ASVS / API Top 10). Runs are recorded: `POST /device-security/test-pack/runs {version, environment,
results[{script, control, outcome PASS|FAIL|NOT_RUN, evidenceSha256}]}` · `GET /device-security/test-pack/runs` (SECURITY_OPS); CI stage
`device-security-pack` (pre-release, E34-BL-011) and quarterly schedule.

### 15.12 Secure life-cycle, penetration test and IEC 80001-1 (E34-HLR-06, -12; E34-F05, F11)

SDLC artefacts tracked at `GET /device-security/sdlc` `{threatModels[{adapterOrOption, version, reviewedOn}], sbom{release, documentId},
vulnerabilities[{id, severity, foundOn, dueOn (critical 7 d, high 30 d), status}], disclosureUrl}`; pen-test findings
`GET/POST /device-security/pentest/findings` `{ref, severity, title, status OPEN|FIXED|ACCEPTED, closedOn?}` (go-live gate: no open
critical); IEC 80001-1 responsibility agreement `GET/PUT /device-security/responsibility-agreement` `{version, parties[], signedOn?, reviewCadence}`.
These are governance records (no code gate beyond the go-live check `GET /device-security/go-live-readiness`).

## 16. Edge gateway and Device Host agent (`apps/wz-device-gateway`; E31-HLR-09, E31-F09, E34-HLR-03)

One Java 21 Spring Boot application (no web UI; management port bound to localhost) in two roles selected by its configuration:
`GATEWAY` (clinic / outstation industrial PC, Linux) and `DEVICE_HOST_AGENT` (vendor PC, Windows service or Linux systemd).
Built by the E31 developer; the hardening baseline, enrolment client behaviour and secret handling follow E34.

### 16.1 Protocols at the edge

| Capability | Edge behaviour | Forwarded as |
|---|---|---|
| HL7 v2 MLLP listener (P4) | TLS listener (plaintext only with a documented segmented-VLAN exception), MLLP framing `0x0B … 0x1C 0x0D`, per-device MSH filter; returns the **core's ACK** when online; when offline stores the message and returns **AA** only after the encrypted queue write is fsync-ed (store-and-forward accept semantics, documented per device) | `POST /device-ingest/hl7v2` |
| POCT1-A2 listener (P5) | XML over TCP/TLS from the data manager | `/device-ingest/poct1a` |
| Folder / file watcher (P2) | read-only watch, stability wait, SHA-256, PDF pairing, never deletes vendor files; vendor DB polling with watermark (read-only account from `/device-ingest/secrets/{ref}`) | `/device-ingest/files` |
| Serial / USB (P1) | RS-232 / USB-CDC framing; HID keyboard-wedge capture in agent mode; serial-over-TCP and **stdin simulation** (`--simulate-serial <file>`) for tests; USB VID/PID allow-list enforced | `/device-ingest/serial` |
| BLE intake (P3) | BlueZ / OS BLE stack on gateways; payloads from the nurse tablet and kiosk go direct (they are endpoints themselves); bonded-device allow-list from config | `/device-ingest/ble` |
| DICOM metadata (P7) | instances placed in a drop folder by the modality / PACS export → metadata JSON + instance upload | `/device-ingest/attachments` + `/device-ingest/dicomweb` |
| Worklist push (E31-HLR-08) | pulls `/device-ingest/worklist`, converts to ORM / ADT over MLLP, GDT 6302 files or the vendor import file | – |

### 16.2 Store-and-forward (≥ 48 h, E31-F09)

Local queue = append-only segment files encrypted with AES-256-GCM (key in the OS key store / TPM where available, otherwise a
key file readable only by the service account), each item `{sequence (monotonic, persisted), idempotencyKey, kind, capturedAt,
contentType, headers, body}`; capacity ≥ 48 h at 2× peak (default 2 GB); alerts at 70 % / 90 % (`queue.capacityPct` in the heartbeat →
`device.gateway-backlog`); **no PHI outside the encrypted queue** (logs carry sequence numbers and instance ids only). Forwarding:
FIFO per device instance, `POST /device-ingest/batch` (≤ 100 items) on reconnection, single calls when online; an item is deleted only
after a 2xx receipt (`ACCEPTED` or `DUPLICATE`); 4xx deterministic errors move it to a local dead-letter folder reported in the
heartbeat; 401 `endpoint_isolated` / `endpoint_revoked` stops forwarding and quarantines the queue. Clock: NTP status and offset
reported in every heartbeat; items carry `capturedAt` from the edge clock and the core corrects with the reported offset.

### 16.3 Configuration pulled from the core (`GET /device-ingest/config` → `GatewayConfig`)

`{endpointId, configVersion, role GATEWAY|DEVICE_HOST_AGENT, heartbeatSeconds 60, instances:[{deviceInstanceId, option, driverPackageId,
driverVersion, framing?, listener?{protocol, port, tls}, watch?{path, pattern, stabilitySeconds, encoding}, db?{dialect, connection,
query, watermarkColumn, credentialRef}, ble?{service, characteristics, peripheralAllowList}, usbAllowList?, worklist?{mode}}],
queue{maxBytes, retentionHours 72}, ntpServers[], logLevel}` – secrets only as references, resolved on demand. A new `configVersion`
is applied atomically; the previous one is kept for rollback.

### 16.4 Hardening (E34-HLR-03, E34-F03)

CIS-baseline images (Debian / Ubuntu Core for gateways, Windows for agents) are infrastructure deliverables of E34; the application:
runs as a non-root least-privilege service account, binds the management port to localhost, refuses to start without a valid
endpoint identity (except `enrol` mode), refuses plaintext listeners unless the exception flag is set, reports
`hardening{baseline, status, findings}` from a local self-check (disk encryption, firewall, EDR service running, time sync, USB policy) in
every heartbeat (drift → `HARDENING_DRIFT`).

## 17. Notifications, audit actions, retention classes, allow-lists and configuration (done unless marked)

**Notification templates (content-free, approved – `NotificationTemplates`, content-rule test passes):**
`device.calibration-due`, `device.locked-out`, `device.connector-alert`, `device.unmatched-queue`, `device.verification-pending`,
`device.approval-required`, `device.certified`, `device.model-request-update`, `device.security-alert`, `device.endpoint-isolated`,
`device.certificate-expiring`, `device.gateway-backlog`, `device.summary-available`. Placeholders only from the allowed set (`reference` =
device instance / endpoint / configuration / request reference, `site`, `level`, `dueDate`, `caseRef`, `firstName`, `portalLink`).
Never put a value, measurement type of a named person, or employee id into a notification.

**Audit actions (platform `AuditService`; person-level reads always; machine traffic only on state changes):**
E31 `DEVICE_OBSERVATION_READ`, `DEVICE_OBSERVATION_ACCEPT`, `DEVICE_OBSERVATION_REJECT`, `DEVICE_UNMATCHED_RESOLVE`, `DEVICE_UNMATCHED_DISCARD`,
`DEVICE_DLQ_REPLAY`, `DEVICE_DLQ_DISCARD`, `DEVICE_REPLAY_RANGE`, `DEVICE_DRIVER_VALIDATE`, `DEVICE_SIMULATOR_RUN`, `DEVICE_ARCHIVE_VERIFY`,
`DEVICE_TENANT_QUOTA`; E32 `DEVICE_MODEL_CREATE|UPDATE|LIFECYCLE`, `DEVICE_DRIVER_REGISTER|CERTIFY|REVOKE`, `DEVICE_CONFIG_CREATE`,
`DEVICE_CONFIG_STEP`, `DEVICE_CONFIG_TEST`, `DEVICE_CONFIG_SUBMIT`, `DEVICE_CONFIG_APPROVE`, `DEVICE_CONFIG_REJECT`, `DEVICE_CONFIG_PUBLISH`,
`DEVICE_CONFIG_REVISE`, `DEVICE_CONFIG_EXPORT`, `DEVICE_CONFIG_IMPORT`, `DEVICE_DECOMMISSION`, `DEVICE_QC_CHECK`, `DEVICE_LOCKOUT_RELEASE`,
`DEVICE_POSTURE_UPDATE`, `DEVICE_POSTURE_EXCEPTION`, `DEVICE_TRACE_VIEW`, `DEVICE_RESEND`, `DEVICE_CERTIFICATE_PDF`; E33
`DEVICE_CAPTURE_OPEN|ACCEPT|REJECT|CANCEL|EXPIRE|FALLBACK`, `DEVICE_CAPTURE_SIGN_OFF`, `DEVICE_BATCH_CREATE`, `DEVICE_KIOSK_SESSION`,
`DEVICE_SUMMARY_RELEASE`; E34 `DEVICE_ENDPOINT_CREATE|ISOLATE|RELEASE|REVOKE|ROTATE`, `DEVICE_ENROL`, `DEVICE_SECRET_STORE|RESOLVE|ROTATE|DESTROY`,
`DEVICE_SIGNING_KEY_ROTATE|REVOKE`, `DEVICE_PACKAGE_SIGN`, `DEVICE_ANOMALY_RULE_UPDATE`, `DEVICE_INCIDENT_OPEN|CLOSE`, `DEVICE_TEST_PACK_RUN`,
`DEVICE_DPIA_APPROVE`; kernel fallback `DEVICE_SECURITY_EVENT` (only when E34 is absent).

**Retention classes (`docs.retention_schedule`, V21, proposed – DPO / CMO approval):** `DEVICE_RAW_PAYLOAD` 40 y from last encounter
(raw payloads / waveforms / PDFs / images of filed observations), `DEVICE_RAW_UNFILED` 1 y from receipt (rejected, unmatched-discarded,
dead letters after resolution, simulator / test traffic), `DEVICE_CALIBRATION_QC` 40 y (calibration, daily-check and QC-lot records),
`DEVICE_CONFIGURATION` 10 y after decommissioning (configuration versions, certification records, approvals, disposal records),
`DEVICE_SECURITY_EVENT` 6 y (security events, enrolment / revocation, isolation records). Canonical FHIR resources of filed
observations follow the OH record they belong to (`OH_RECORD` / `EXPOSURE_RECORD`).

**Outbound allow-lists (`application.yml`, done):** `siem-device-security` (§15.9) and `itsm-tickets: [messageId, ticketRef,
deviceInstanceId, siteCode, alertType, severity, raisedAt]`. In-process events (no allow-list needed): `device.observation.routed.v1`
`{observationId, category, target, routedRecordId, deviceInstanceId}`, `device.qc.received.v1`, `device.endpoint.status.v1`.
**Egress hosts** for P6 polling: config `device.cloud.allowed-hosts` (E31, empty by default – no outbound call without an entry).

**Configuration keys (V21, done):** `modules.e31..e34.enabled`, `device.association.auto-file-threshold` 0.95,
`device.association.suggest-threshold` 0.60, `device.association.session-window-seconds` 900, `device.clock.max-skew-seconds` 120,
`device.capture.session-timeout-minutes` 10, `device.unmatched.resolution-sla-hours` 8, `device.ocr.min-confidence` 0.90,
`device.ingest.max-payload-bytes` 52428800, `device.dlq.max-attempts` 5, `device.security.isolation-sla-minutes` 15,
`device.security.revocation-sla-minutes` 5, `device.security.cert-expiry-warning-days` 30. Application properties:
`wellnessz.device.ingest.certificate-header` (`X-Client-Cert-Sha256`), `wellnessz.device.ingest.require-client-certificate` (true),
module-owned: `wellnessz.device.pki.trust-anchors` (E34), `device.cloud.allowed-hosts` (E31).

**Consent purpose (done):** `ConsentPurpose.KIOSK_SELF_MEASUREMENT` (added; the employee app must add its label in `types.ts` and the
i18n bundles – E33 UI task).

## 18. BFF resources and web screens

**BFF (done).** `/workspace/device-fleet` added to `CORE_WORKSPACE_RESOURCES` (manager workspace, counts only). All other device
endpoints are reached through `/bff/api/**` (authenticated) from the OH workspace; `WORKSPACE_ROLES` unchanged (device console roles
use the OH workspace, not the manager workspace). Machine endpoints (`/device-ingest`, `/device-enrolment`, `/device-webhooks`) are
**never** proxied by the BFF.

**OH workspace (`web/oh-workspace`) – new route group "Devices" (role gating in `src/auth/roles.ts`):**

| Route | Roles | Content |
|---|---|---|
| `#/devices/catalogue` (+ `/{modelCode}`, compare) | device roles, BIOMEDICAL, NURSING_HEAD (read) | catalogue search, model detail, regulatory / UDI, posture, drivers |
| `#/devices/wizard` (+ `/{configurationId}`) | DEVICE_ADMIN (INTEGRATION_TESTER test sites; DEVICE_APPROVER step 7) | 8-step wizard with option comparison, checklist, parameter forms (from JSON schema), binding editor with preview, test runner, approval, publish (token shown once with copy button) |
| `#/devices/approvals` | DEVICE_APPROVER, SECURITY_OPS | pending approvals (four-eyes) |
| `#/devices/fleet` (+ `/{deviceInstanceId}`) | DEVICE_ADMIN, SECURITY_OPS, NURSING_HEAD | fleet dashboard, site map, alerts, versions, consumables, data-flow, certificate PDF |
| `#/devices/calibration` | DEVICE_ADMIN, BIOMEDICAL, clinicians | schedules, status, quick QC entry, report |
| `#/devices/diagnostics/{deviceInstanceId}` | DEVICE_ADMIN, INTEGRATION_TESTER, SECURITY_OPS | masked trace, payload inspector, mapping outcome, time offset, re-send |
| `#/devices/drivers` · `#/devices/simulators` | INTEGRATION_TESTER (DEVICE_ADMIN read) | driver registry, validation, conformance, golden datasets, simulator runs |
| `#/devices/governance` · `#/devices/model-requests` | DEVICE_ADMIN, DEVICE_APPROVER, INTEGRATION_TESTER | change history, export / import with diff, templates & batch, model requests |
| `#/devices/dead-letters` · `#/devices/operations` | DEVICE_ADMIN, INTEGRATION_TESTER, PLATFORM_ADMIN | DLQ triage, replay; connector health, latency, error rates, alerts |
| `#/devices/unmatched` | clinicians (site team) | unmatched queue with suggestions, resolution with reason |
| `#/security/devices` (+ endpoints, events, alerts, incidents, keys, zones, test pack) | SECURITY_OPS (AUDITOR read events) | endpoint identities, enrolment tokens, isolation / release, security events + chain verification, anomaly rules, signing keys, conduits export, test-pack runs |
| capture control inside existing screens | clinicians, COLLECTOR, MRO | "Capture from device" in the E03 device-result / vitals / POC / ECG forms, E10 collection, E12 screening results; preview dialogue; comparison panel; batch mode screen `#/devices/batch` |

**Manager workspace (`web/manager-workspace`):** `/device-fleet` – site device availability (counts) for MANAGER / NURSING_HEAD.
**Employee app (`web/employee-app`):** `/health-checks` – released device summaries in the preferred language; wearable consent
scopes under the existing privacy / campaigns screen; consent label for `KIOSK_SELF_MEASUREMENT`.
**Kiosk:** a minimal kiosk page served by the gateway or a kiosk shell is **out of scope** for the web apps in this increment (the
kiosk API is delivered and exercised by tests and the E2E script).

## 19. Demo data and simulators (synthetic only)

* Users: `deviceadmin1`, `deviceapprover1`, `inttester1`, `secops1` (+ existing `nurse1`, `physician1`, `biomed1`, `collector1`, `mro1`, `manager1`, `employee1`).
* `R__demo_e32_device_console.sql` (local / test): sites `LHR` (stations `LHR-OH-ROOM-1`, `LHR-OH-ROOM-2`, `LHR-OH-BOOTH-1`), `DXB`
  (outstation), published instances: `DEV-000001` spirometer MD-001 (P2 GDT, Device Host agent `EP-LHR-AGT-01`), `DEV-000002`
  audiometer MD-015 (P2 DB), `DEV-000003` vitals MD-027 (P4 MLLP via `EP-LHR-GW-01`), `DEV-000004` BLE BP MD-037 (P3, tablet
  `EP-LHR-TAB-01`), `DEV-000005` breath alcohol MD-097 (P1), `DEV-000006` Afinion MD-081 (P5), one QUARANTINED and one locked-out
  instance for the demo of gates; E14 equipment links for calibration; a draft configuration paused at step 4.
* `R__demo_e34_device_security.sql`: endpoints above with **test-only** token hashes and thumbprints of the dev CA (the plaintext
  tokens appear only in `tools/e2e/e2e-increment6.js` and the integration tests – local-only, documented as non-secrets).
* `R__demo_e31_device_engine.sql`: terminology release, GLI-2012 coefficients, OSHA App. F table, golden-dataset index; a few routed
  and one unmatched observation for `E1001` / `E1002`.
* Golden datasets (E31, `modules/wz-e31-device-engine/src/main/resources/e31/golden/<family>/<package>/…`): for every package in §6.2 at
  least `normal`, `edge`, `malformed`, `time-skew`, `duplicate`; performance profile `perf/mixed-50ps.json`.
* Simulator endpoints `SIM-<adapter>` are virtual (no tokens), usable only through `/device-engine/simulators/**`.

## 20. Tests

**Unit (per module):** driver schema + canonicalisation + signature verification; each parser type with golden vectors; unit
conversion, GLI-2012 predicted values and OSHA age correction vs reference tables; association scoring and thresholds (no auto-file
< 0.95, ambiguity rule); every quality gate; deterministic observation ids and replay without duplicates; stage retry / DLQ
policy; wizard step validations and state machine (incl. four-eyes violation, invalidation of later steps); option JSON schemas;
calibration schedules and lockout; posture policy; capture-session state machine incl. timeout and fallback chain; masking of traces;
endpoint authentication rules (token hash, thumbprint, status, zone), anomaly rules and isolation timing, hash-chained events, secret
scanner; notification content (existing `NotificationRulesTest`).

**Integration (`apps/wz-core-app/src/test/java/com/wellnessz/app/`, real PostgreSQL, recreate the test DB every run):**
`Increment6EngineIntegrationTest` (E31: ingest of HL7 / FHIR / POCT1-A / GDT / CSV / XML / BLE / serial / webhook HMAC / DICOMweb /
attachments; ACK semantics; idempotency; association; gates incl. E14 calibration lockout; routing into E03 / E07 / E10 / E12 sinks;
dead letter and replay without duplicates; tenant isolation; unmatched queue), `Increment6ConsoleIntegrationTest` (E32: seed totals
133 / option counts, wizard end-to-end with simulator test, four-eyes, publish → provisioning, governance export / import diff,
calibration lockout, decommission → revoked), `Increment6CaptureIntegrationTest` (E33: session → device message → preview → accept →
E03 result with `device_observation_id` and `capture_method`, reject / re-test, fallback OCR / manual, batch offline replay, kiosk
consent, restricted breath-alcohol visibility), `Increment6DeviceSecurityIntegrationTest` (E34: enrolment with dev CA, auth failures and
events, isolation ≤ 15 min, re-certification gate, signatures, secrets never returned, SIEM outbox allow-list, event chain verify).
Negative tests everywhere: console roles get 403 / masked data on person endpoints; a device token cannot call user APIs; a user
token cannot call `/device-ingest`; another tenant's endpoint → 403 / 404. `ArchitectureTest` and `PlatformIntegrationTest`
(`increment6DevicePlatformBase`) already cover the platform base.

**E2E – `tools/e2e/e2e-increment6.js`** (Playwright, real Keycloak logins through the BFF like increments 1–5; device calls go
**directly** to the core with the demo endpoint tokens and the thumbprint header, since the dev stack has no mTLS ingress). Checks:

1. `deviceadmin1` logs into the OH workspace; `/me` → `maxDisclosure NONE`; Devices menu present, no chart / case screens.
2. Catalogue lists 133 models in 11 families; MD-001 shows options P1–P4 and its regulatory fields.
3. Wizard: create configuration for MD-027 at `LHR-OH-ROOM-1`, P4; step 3 auto-checks; step 4 schema validation error then valid; step 5 binding preview from a golden dataset; step 6 simulator test PASSED; nurse review by `nurse1`.
4. Four-eyes: `deviceadmin1` cannot approve own submission (409 `four_eyes_violation`); `deviceapprover1` approves → certification PDF downloadable (`application/pdf`).
5. Publish → endpoint id + one-time enrolment token returned; the token is not shown again on reload.
6. E34: enrolment with the dev certificate thumbprint → endpoint token; heartbeat returns `configVersion`.
7. Ingest without thumbprint → 401 `mtls_required`; with wrong token → 401 `bad_token`; a security event `ENDPOINT_AUTH_FAILED` visible to `secops1`.
8. `nurse1` opens a capture session for `E1001` on `DEV-000003` from the vitals form; an HL7 ORU (PCD-01) is posted → AA ACK; preview shows values, units and quality flags within 5 s; accept → E03 result with device badge (`source DEVICE`, `capture_method DEVICE`).
9. Re-posting the same message (same Idempotency-Key) → `DUPLICATE`, no second E03 result.
10. Spirometer `DEV-000001` with expired 3-L syringe check → preview BLOCKED with the lockout message; quick QC entry PASS → re-test accepted.
11. Message without a session and an unknown employee id → unmatched queue item with suggestion; `nurse1` resolves with reason → routed.
12. Malformed HL7 → AE ACK + dead letter; `inttester1` replays after the driver fix → routed once.
13. Fallback: device locked → OCR path (photo upload, fields typed) → accept → `capture_method OCR_VERIFIED`; manual attested path for a Snellen result.
14. Breath alcohol on `DEV-000005` for an open E10 test: `collector1` previews and accepts; `nurse1` sees "restricted reading" without the value; `mro1` sees the value.
15. Webhook with a valid HMAC accepted (fit test → E03 FIT_TEST); tampered body → 401 `signature_invalid` + security event.
16. Batch store-and-forward: 20 BLE readings posted via `/device-ingest/batch` out of order with `X-Replayed` → routed in sequence order, no duplicates after a second replay.
17. `secops1` isolates `EP-LHR-GW-01` → next ingest 401 `endpoint_isolated`; fleet shows `DEV-000003` QUARANTINED; release refused until re-certified.
18. Decommission `DEV-000002` (request + approval) → its endpoint revoked; post → 401 / 404.
19. `manager1` sees `/device-fleet` counts in the manager workspace, no device trace or person data.
20. `employee1` sees a released summary in the employee app (`/health-checks`), consents to wearable scopes and revokes.
21. Security event chain verify → `intact: true`; platform audit `/audit/verify` → intact.

## 21. Error codes (in addition to the platform codes)

`credentials_missing`, `mtls_required`, `endpoint_unauthenticated`, `endpoint_security_unavailable`, `unknown_endpoint`, `bad_token`,
`token_expired`, `certificate_mismatch`, `endpoint_pending`, `endpoint_isolated`, `endpoint_revoked`, `zone_violation`,
`endpoint_forbidden`, `device_not_authorised_for_endpoint`, `device_not_published`, `device_quarantined`, `unknown_device`,
`payload_too_large`, `unsupported_media_type`, `parse_failed`, `profile_violation`, `attachment_unpaired`, `mapping_missing`,
`unit_unconvertible`, `clock_skew_excessive`, `association_conflict`, `observation_blocked`, `flags_not_acknowledged`, `observation_not_pending`,
`route_target_unavailable`, `sink_rejected`, `binding_field_unknown`, `signature_invalid`, `timestamp_out_of_window`, `no_driver_for_option`,
`step_invalid`, `four_eyes_violation`, `posture_blocked`, `recertification_required`, `device_busy`, `session_expired`, `consent_missing`,
`attestation_incomplete`, `qc_due`, `qc_failed`, `qc_lot_invalid`, `device_out_of_calibration`, `device_out_of_service`, `device_not_registered`,
`enrolment_invalid`, `enrolment_token_invalid`, `enrolment_token_expired`, `already_enrolled`, `certificate_untrusted`,
`certificate_key_usage`, `proof_invalid`, `thumbprint_mismatch`, `module_disabled`.

## Appendix A – Traceability (every HLR and feature of E31–E34)

### E31 – Engine

| ID | Requirement (short) | Endpoint / component |
|---|---|---|
| E31-HLR-01 | canonical pipeline, at-least-once, idempotent, replay, DLQ | §8 stages; `/device-ingest/*` (Idempotency-Key); `/device-engine/dead-letters`, `/device-engine/replay` |
| E31-HLR-02 | adapter library P1–P10 | §6.1 `adapter` types; §7 ingest endpoints; §16.1 edge protocols; §13.3 option schemas |
| E31-HLR-03 | driver SDK, declarative signed packages | §6; `/device-engine/drivers/validate|conformance|sign`; E32 registry `/device-catalogue/drivers`; `DevicePackageSignatures` |
| E31-HLR-04 | canonical observation, FHIR, raw archive hash-linked | §4; `DeviceObservation`; `RawPayloadStore`; `/device-engine/archive/{id}/verify` |
| E31-HLR-05 | association priority, unmatched queue, never auto-file | §9; `DeviceCaptureSessions`, `DeviceWorklistSource`; `/device-engine/unmatched` |
| E31-HLR-06 | quality gates incl. calibration / QC / grades / plausibility / verify | §10; `DeviceCalibrationCheck` (E14); `DeviceCatalogueLookup.qcStatus` |
| E31-HLR-07 | result-to-form bindings, module logic | §11 `DeviceObservationSink`; bindings in packages and wizard step 5; E33 form registry |
| E31-HLR-08 | bidirectional workflows | `capabilities` in packages; `/device-ingest/worklist`; gateway commands `PUSH_WORKLIST`, `START_ACQUISITION`; session open push (§14.2) |
| E31-HLR-09 | store-and-forward at the edge | §16.2; `/device-ingest/batch`, `X-Gateway-Sequence`, `X-Replayed` |
| E31-HLR-10 | simulator and golden datasets in CI | §19; `/device-engine/golden-datasets`, `/device-engine/simulators/{adapter}/run`; package `testVectors` |
| E31-HLR-11 | observability per connector | `/device-engine/ops/connectors|metrics|alerts`; heartbeat; Micrometer metrics; `DeviceDiagnostics.health` |
| E31-HLR-12 | multi-tenancy and quotas | `tenant_code` everywhere; `/device-engine/tenants`; endpoint tenant binding (E34) |
| E31-HLR-13 | performance 5 s p95, 50 msg/s, 50 MB streaming | §8 performance; `/device-ingest/attachments` streaming; `DevicePipelinePerformanceTest` |
| E31-HLR-14 | provenance | §4.5 Provenance; E03 V21 columns; `DeviceObservation.Provenance` |
| E31-F01 | pipeline & message bus | §8; Postgres work table + outbox (deviation recorded §0) |
| E31-F02 | protocol adapter library | §6.1, §7, §16.1 |
| E31-F03 | driver SDK & packages | §6; driver endpoints §12; registry §13.1 |
| E31-F04 | normalisation & terminology | §4, §6 mappings; `/device-engine/terminology/*`; GLI-2012 / OSHA tables |
| E31-F05 | association engine | §9; unmatched queue §12 |
| E31-F06 | quality gates & clinician verification | §10; E33 accept with acknowledged flags (§14.2) |
| E31-F07 | result-to-form bindings | §11; wizard step 5 (§13.2); `/device-capture/forms` |
| E31-F08 | bidirectional workflows | as E31-HLR-08 |
| E31-F09 | edge store-and-forward | §16.2 |
| E31-F10 | simulator & golden datasets | §19; simulator endpoints §12 |
| E31-F11 | connector & device observability | §12 ops API; §13.7 fleet |
| E31-F12 | multi-tenant partitioning | §12 tenants; §15.1 endpoint `tenantCode` |

### E32 – Console

| ID | Requirement (short) | Endpoint / component |
|---|---|---|
| E32-HLR-01 | governed catalogue with UDI, regulatory, options, drivers, posture, lifecycle | §5, §13.1; seed from `device-inventory.json` |
| E32-HLR-02 | 8-step wizard with pause / resume / clone | §13.2 |
| E32-HLR-03 | parameters per option per accepted practice | §13.3 P1–P10 schemas |
| E32-HLR-04 | fleet operations, calibration / QC lockout | §13.6, §13.7 |
| E32-HLR-05 | governance: RBAC, four-eyes, history, export / import, templates | §13.2 step 7, §13.4 |
| E32-HLR-06 | vendor onboarding MDS2 / SBOM, block unless exception | §13.8 |
| E32-HLR-07 | request-a-new-model | §13.9 |
| E32-HLR-08 | decommissioning with revocation | §13.11; `DeviceEndpointProvisioning.revoke` |
| E32-HLR-09 | diagnostics without clinical access | §13.10; `DeviceDiagnostics` |
| E32-HLR-10 | data-flow summary and certification PDF | §13.5 |
| E32-HLR-11 | multilingual UI, WCAG 2.2 AA | option-pack help texts in two languages (§13.1); OH workspace i18n (§18) |
| E32-F01 | catalogue & regulatory register | §5, §13.1 |
| E32-F02 | provisioning wizard | §13.2 |
| E32-F03 | option packs P1–P10 | §13.1 `/device-catalogue/options`, §13.3 |
| E32-F04 | calibration, QC & lockout | §13.6; gate `QC_DAILY` / `QC_LOT` (§10) |
| E32-F05 | fleet dashboard & alerts | §13.7; `itsm-tickets` |
| E32-F06 | governance & environment promotion | §13.4 |
| E32-F07 | vendor security onboarding | §13.8 |
| E32-F08 | request-a-new-model | §13.9 |
| E32-F09 | diagnostics & masked trace | §13.10 |
| E32-F10 | certification record & data flow | §13.5 |
| E32-F11 | decommissioning & key revocation | §13.11 |

### E33 – Capture

| ID | Requirement (short) | Endpoint / component |
|---|---|---|
| E33-HLR-01 | capture control, station-aware certified in-calibration devices, context push | §14.2 `/device-capture/devices`, `/device-capture/sessions` |
| E33-HLR-02 | progress, preview with flags and raw report, accept / reject / re-test, provenance marking | §14.1, §14.2 (SSE events, accept, reject) |
| E33-HLR-03 | audiometry, spirometry, vision, ECG specifics | §14.4; E03 `DeviceRules` via `E03.RESULT` |
| E33-HLR-04 | vitals, body composition, ABPM / Holter | §14.4; packages §6.2 |
| E33-HLR-05 | breath alcohol & drug screen with custody | §14.4; `E10.BREATH_ALCOHOL` sink (§11); restricted rule |
| E33-HLR-06 | POC with QC lots, abnormal routing; fit test clearance | §14.4; gates `QC_LOT`, `FIT_EXERCISES_COMPLETE`; E03 POC / FIT_TEST |
| E33-HLR-07 | mobile batch, kiosk, offline | §14.5, §14.6; `/device-ingest/batch` |
| E33-HLR-08 | comparison, trends, clinician actions | §14.7 |
| E33-HLR-09 | employee summary, wearable consent | §14.8 |
| E33-HLR-10 | graceful degradation, path recorded | §14.3; `capture_method` (V21) |
| E33-HLR-11 | accessibility, gloves, time-and-motion | §14.2 UI contract; E33-BL-016 study (governance) |
| E33-F01 | capture control & station awareness | §14.2 |
| E33-F02 | preview, flags & verification | §14.1, §14.2 |
| E33-F03 | audiometry & STS | §14.4 |
| E33-F04 | spirometry & ATS/ERS | §14.4; gate `ATS_ERS_GRADE`, `PREDICTED_VALUES` |
| E33-F05 | vision templates | §14.4 |
| E33-F06 | ECG, ABPM & Holter with sign-off | §14.4 sign-off endpoint |
| E33-F07 | vitals & body composition | §14.4 |
| E33-F08 | breath alcohol & drug screen | §14.4, §11 |
| E33-F09 | POC & fit test | §14.4 |
| E33-F10 | mobile batch & kiosk | §14.5, §14.6 |
| E33-F11 | comparison & clinician actions | §14.7 |
| E33-F12 | employee summary & consent | §14.8 |

### E34 – Security

| ID | Requirement (short) | Endpoint / component |
|---|---|---|
| E34-HLR-01 | unique identity, mTLS, plaintext refused | §15.1–15.3; kernel `DeviceIngestSecurityConfig` (done) |
| E34-HLR-02 | segmentation, zones, conduits, brokered vendor access | §15.4 |
| E34-HLR-03 | hardened gateways / agents | §16.4; heartbeat `hardening`; anomaly `HARDENING_DRIFT` |
| E34-HLR-04 | BLE LE Secure Connections, USB allow-lists, authenticated serial servers | §13.3 P1 / P3 parameters; §16.1; test script 06 |
| E34-HLR-05 | secrets in vault, runtime injection, rotation, audit | §15.6 `DeviceSecretStore` |
| E34-HLR-06 | secure life-cycle, SBOM, signed releases | §15.5, §15.12; test script 07 |
| E34-HLR-07 | MDS2 / SBOM procurement gate, isolation of legacy PCs | §13.8, §15.7 |
| E34-HLR-08 | tamper-evident audit, SIEM, PHI masking | §15.9; `siem-device-security`; masked traces §13.10 |
| E34-HLR-09 | anomaly rules, 15-minute isolation, re-certification | §15.8; `DeviceEndpointStatusListener` |
| E34-HLR-10 | privacy by design | §15.10; retention classes §17; consent purposes |
| E34-HLR-11 | security test pack | §15.11 |
| E34-HLR-12 | penetration test, IEC 80001-1 agreement | §15.12 |
| E34-F01 | endpoint identity & PKI | §15.1, §15.2 |
| E34-F02 | zoning & policy templates | §15.4 |
| E34-F03 | hardened images | §16.4 |
| E34-F04 | secrets & key management | §15.6 |
| E34-F05 | SDLC & SBOM | §15.12 |
| E34-F06 | vendor intake & compensating controls | §13.8, §15.7 |
| E34-F07 | tamper-evident audit & SIEM | §15.9 |
| E34-F08 | anomaly detection & playbook | §15.8 |
| E34-F09 | privacy controls | §15.10 |
| E34-F10 | security test pack | §15.11 |
| E34-F11 | pen test & IEC 80001-1 | §15.12 |

## Appendix B – Notes for the module developers

1. **Never import another device module.** Use `DeviceSpis`; if you need something that is not in a kernel SPI, raise it with the
   platform-base developer – do not add a cross-module dependency (ArchUnit fails the build).
2. **Owned files.** Each developer edits only their module, their `V2x` migration, their `R__demo_e3x_*.sql`, their web routes, and the
   adapters assigned in §1 (E31: sinks in E03 / E07 / E10 / E12 and the E03 / E14 worklist adapters, plus `ResultService.deviceFromEngine`
   and the widened `DeviceRequest` pattern in E03; E32: the E14 `DeviceEquipmentRegister` adapter). Shared files already prepared
   (roles, realm, schemas, allow-lists, notification templates, retention classes, BFF list) should not need edits – coordinate if they do.
3. **Machine paths.** `/device-ingest/**` (authenticated endpoints), `/device-enrolment/**` and `/device-webhooks/**` (unauthenticated at
   the filter – the controller must verify the enrolment token / HMAC) are owned by the kernel security chain; do not add other
   unauthenticated paths. E34 additionally owns `/device-ingest/certificate-renewal`, E33 owns `/device-ingest/kiosk/**`.
4. **Audit volume.** `AuditService.record` takes a global advisory lock (hash chain); do not audit every machine message – audit state
   changes and person-level reads only; per-message history lives in `deviceint.stage_log`.
5. **PHI.** No employee ids, names, values or payload excerpts in logs, metric labels, exceptions, security events or notifications.
   Traces shown to console roles go through the masking function (E31 owns it; unit-tested with synthetic PHI).
6. **Restricted categories.** Breath alcohol and drug screen observations are `restricted=true`; enforce MRO / COLLECTOR visibility in
   every read path (E31 observations, E33 preview, unmatched queue).
7. **Tests need the test DB recreated before every run** (command in the developer guide / increment brief).
