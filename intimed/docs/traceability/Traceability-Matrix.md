# IntiMed – Requirements Traceability Matrix (release 2.2 – local + Azure Qatar Central + Google Cloud Doha / IntiMed_GCP)

Generated from `tools/content/traceability.py` (single source of truth, also served at `/api/quality/backlog`).

| | Items | Story points |
|---|---:|---:|
| DELIVERED | 80 | 930 |
| PARTIAL | 29 | 392 |
| DEFERRED | 8 | 73 |
| **Total** | **117** | **1395** |

Status: **DELIVERED** – implemented in the product or its automation (LOCAL, Azure Qatar Central and Google Cloud me-central1); **PARTIAL** – implemented as far as software can go, the remainder needs people, hardware, vendors, licences or audits; **DEFERRED** – deliberately outside this release. Every item that still needs a manual step names its section M-01…M-16 in `docs/azure/Manual-Backlog-Execution-Guide.md` (Azure) and `docs/gcp/IntiMed_GCP_Manual_Backlog_Execution_Guide.md` (Google Cloud).

## IM-E01 – IntiMed Core Integration Engine

_Provide a generic, vendor-neutral device integration engine that connects any device by brand and model through ten connectivity options (serial/USB, vendor software DB/file export, Bluetooth, LAN/Wi-Fi HL7 MLLP / IHE DEV PCD-01, POCT1-A and LIS data managers, vendor cloud APIs, DICOM, FHIR, image/OCR, manual with attestation), runs one canonical pipeline (acquire → parse → normalise → associate →…_

### High-level requirements → features

| Feature | Name | HLRs | Backlog items (status) |
|---|---|---|---|
| IM-E01-F01 | Canonical pipeline & streaming support | IM-E01-HLR-01; IM-E01-HLR-12; IM-E01-HLR-14 | IM-E01-BL-001 (DELIVERED)<br>IM-E01-BL-002 (DELIVERED)<br>IM-E01-BL-030 (DELIVERED) |
| IM-E01-F02 | Adapter library P1–P10 (+ SDC-ready) | IM-E01-HLR-02 | IM-E01-BL-003 (PARTIAL)<br>IM-E01-BL-004 (PARTIAL)<br>IM-E01-BL-005 (PARTIAL)<br>IM-E01-BL-006 (PARTIAL)<br>IM-E01-BL-007 (PARTIAL)<br>IM-E01-BL-008 (PARTIAL)<br>IM-E01-BL-009 (PARTIAL)<br>IM-E01-BL-010 (DELIVERED) |
| IM-E01-F03 | Driver SDK, package registry & marketplace | IM-E01-HLR-03 | IM-E01-BL-011 (DELIVERED)<br>IM-E01-BL-012 (PARTIAL)<br>IM-E01-BL-013 (PARTIAL)<br>IM-E01-BL-014 (PARTIAL)<br>IM-E01-BL-015 (PARTIAL)<br>IM-E01-BL-016 (PARTIAL)<br>IM-E01-BL-017 (PARTIAL)<br>IM-E01-BL-018 (PARTIAL)<br>IM-E01-BL-019 (PARTIAL) |
| IM-E01-F04 | Normalisation & terminology service | IM-E01-HLR-04 | IM-E01-BL-020 (DELIVERED) |
| IM-E01-F05 | Association engine (worklist, encounter–bed, session, badge, matching) | IM-E01-HLR-05 | IM-E01-BL-021 (DELIVERED) |
| IM-E01-F06 | Quality gates & verification | IM-E01-HLR-06 | IM-E01-BL-022 (DELIVERED) |
| IM-E01-F07 | Routing to Template Bindings (many-to-many) | IM-E01-HLR-07 | IM-E01-BL-023 (DELIVERED) |
| IM-E01-F08 | Bidirectional device workflows | IM-E01-HLR-08 | IM-E01-BL-024 (DELIVERED) |
| IM-E01-F09 | Edge store-and-forward & buffering | IM-E01-HLR-09 | IM-E01-BL-025 (PARTIAL) |
| IM-E01-F10 | Simulators & golden datasets | IM-E01-HLR-10 | IM-E01-BL-026 (DELIVERED) |
| IM-E01-F11 | Connector & device observability | IM-E01-HLR-11 | IM-E01-BL-027 (DELIVERED) |
| IM-E01-F12 | Multi-tenant partitioning & residency | IM-E01-HLR-13 | IM-E01-BL-028 (DELIVERED) |
| IM-E01-F13 | Regulatory scope guardrails | IM-E01-HLR-14 | IM-E01-BL-029 (DELIVERED) |

### Backlog → implementation evidence

| Backlog | Title | SP | Sprint | Status | Increment | Evidence / reason | Manual guide |
|---|---|---:|---|---|---|---|---|
| IM-E01-BL-001 | Design the canonical observation model, pipeline stages, event contracts and streaming semantics; record ADRs | 13 | IM-S0 | DELIVERED | L1 | intimed-platform/src/main/java/io/intimed/engine/pipeline/* (canonical observation, stages, events); docs/architecture/ADR.md | — |
| IM-E01-BL-002 | Implement pipeline services with DLQ triage UI, ordering/late-arrival rules and alarm-state flagging | 21 | IM-S1 | DELIVERED | L1 | intimed-platform/src/main/java/io/intimed/engine/pipeline/Pipeline.java (DLQ + replay, idempotent ids, late/skew rules, alarm flags); web/ClinicalController DLQ triage; UI Queues | — |
| IM-E01-BL-003 | Serial/USB adapter for gateway and device host with port discovery and framing options | 13 | IM-S7 | PARTIAL | L4 | intimed-platform/src/main/java/io/intimed/engine/parse/SerialFrameParser.java; sdk gateway; Dockerfile.gateway | M-03 |
| IM-E01-BL-004 | HL7 v2 MLLP-TLS adapter: PCD-01 periodic and spot, PCD-02, ACM, IPEC, ORM/OML, ADT; IHE DEV conformance tests | 21 | IM-S5 | PARTIAL | L4 | intimed-platform/src/main/java/io/intimed/engine/transport/DeviceListeners.java (MLLP-TLS, ORU, ADT A01/A04/A08…, ORM/OML) | M-06 |
| IM-E01-BL-005 | Vendor database/file watcher adapter (SQL Server/SQLite/Access/PostgreSQL read-only; XML/GDT/CSV/PDF folders) | 13 | IM-S7 | PARTIAL | L4 | intimed-platform/src/main/java/io/intimed/engine/parse/{Csv,Xml,Gdt,JsonPath}Parser.java; P2 option pack; gateway image | M-03 |
| IM-E01-BL-006 | BLE adapter (standard GATT health profiles and vendor services) for gateway, kiosk and mobile with LE Secure Connections | 13 | IM-S7 | PARTIAL | L4 | intimed-platform/src/main/java/io/intimed/engine/parse/GattParser.java; RR-03 pairing policy | M-03 |
| IM-E01-BL-007 | POCT1-A2 adapter, LIS2/ASTM and IHE LAW analyser interfaces and data-manager HL7 bridges | 13 | IM-S9 | PARTIAL | L6 | intimed-platform/src/main/java/io/intimed/engine/transport/AstmE1381.java (ENQ/ACK/NAK/EOT, frames ≤ 240 chars with ETB/ETX, checksum, frame numbers mod 8, 6 retransmissions, sender + receiver state machines), engine/transport/Poct1aConversation.java (HEL/DST/REQ/OBS/EOT/END/ACK dialogue); RecordParser accepts raw E1381 captures; ProtocolCodecTests | M-04 |
| IM-E01-BL-008 | Cloud API/webhook adapter framework (OAuth2, polling, webhooks, rate limits, consent scopes) incl. CRM remote-monitoring portals, RPM and wearable platforms | 13 | IM-S8 | PARTIAL | L4 | intimed-platform/src/main/java/io/intimed/api/PublicMachineController.java webhook receiver (HMAC, replay window), JsonPathParser, cloud drivers; EgressClient allow-list | M-05 |
| IM-E01-BL-009 | DICOM adapter (C-STORE SCP, MWL, Q/R, MPPS, DICOMweb, SR, waveform) with PACS/VNA routing | 13 | IM-S11 | PARTIAL | L4 | intimed-platform/src/main/java/io/intimed/engine/parse/DicomJsonParser.java; imaging.ct.dicom-rdsr | M-04 |
| IM-E01-BL-010 | FHIR R4/R5 inbound adapter (PoCD IG, Subscriptions), image/OCR + barcode fallback adapter, SDC adapter stub | 13 | IM-S7 | DELIVERED | L4 | intimed-platform/src/main/java/io/intimed/engine/parse/{FhirObservationParser,FormParser}.java; api/FhirController POST Observation (P8); P9 OCR proposals with verification; P11 SDC stub (BICEPS JSON) | — |
| IM-E01-BL-011 | Driver package format, signing, compatibility rules; SDK CLI (scaffold/test/scan/sign/publish); registry with candidate/certified channels; partner submission workflow | 21 | IM-S2 | DELIVERED | L2 | intimed-platform/src/main/java/io/intimed/engine/driver/{DriverRegistry,DriverValidator,Signatures}.java; cli/Sdk.java keygen/sign/validate/test/publish; CANDIDATE/CERTIFIED channels; trusted publishers | — |
| IM-E01-BL-012 | Driver packages – acute-care monitoring & telemetry: Philips IntelliVue/PIIC iX, GE CARESCAPE Gateway, Mindray eGateway, Dräger Infinity Gateway, Nihon Kohden, Spacelabs, Masimo Root/Iris, Medtronic Capnostream/BIS, Edwards HemoSphere (PCD-01/02 periodic & spot, ACM observations, waveform snippets) | 13 | IM-S5 | PARTIAL | L4 | 2 acute-care monitor drivers (PCD-01, SDC) | M-04 |
| IM-E01-BL-013 | Driver packages – infusion, ventilation & anaesthesia: Alaris Systems Manager, ICU Medical MedNet, Baxter Dose IQ, B. Braun Space Online, Fresenius Vigilant, PharmGuard; Hamilton, Dräger Evita/Babylog, Getinge Servo, PB 980, Philips V60/Trilogy; Aisys, Perseus/Atlan, Mindray A7/A9 (IPEC/PIV, MEDIBUS.X, SDC stub) | 13 | IM-S6 | PARTIAL | L4 | infusion, anaesthesia drivers | M-04 |
| IM-E01-BL-014 | Driver packages – outpatient & occupational-health instruments: spirometers, audiometers, vision testers, vitals monitors, ECG systems, breath-alcohol and drug testers, fit testers, ergonomics, exposure feeds (serial/USB, vendor DB/file, BLE, HL7) – parity with the OHCM MDI pack families | 21 | IM-S7 | PARTIAL | L4 | OH instrument drivers (spirometry, audiometry, vision, D&A, fit test) | M-04 |
| IM-E01-BL-015 | Driver packages – RPM, wearables & home: RPM kits (BodyTrace, Smart Meter, Tenovi, Withings), CGM/insulin clouds (Dexcom, LibreView, CareLink, t:connect, Glooko), ResMed AirView, Care Orchestrator, hospital-at-home platforms, aggregators (Validic, Human API, HealthKit, Health Connect), telehealth kits | 13 | IM-S8 | PARTIAL | L4 | drivers rpm.bodytrace.cloud-webhook, hah.masimo.safetynet-cloud, wear.aggregator.fhir-pcha | M-05 |
| IM-E01-BL-016 | Driver packages – laboratory & POCT: middleware (Instrument Manager, cobas infinity, AlinIQ, Atellica DM, REMISOL, WAM), blood gas data managers (AQURE, GEMweb Plus, POCcelerator), molecular connectivity (C360, FilmArray Link), POC analysers via POCT1-A2 (i-STAT, Afinion, StatStrip, DCA, HemoCue) | 13 | IM-S9 | PARTIAL | L4 | lab + POCT drivers; POCT1-A2 / ASTM E1381 codecs (L6) | M-04 |
| IM-E01-BL-017 | Driver packages – cardiology diagnostics & implant remote monitoring: IHE IDCO from CareLink/Paceart, LATITUDE, Merlin.net, BIOTRONIK; Zio/MCOT portals; echo DICOM SR; MUSE/TraceMaster/IntelliSpace ECG; ABPM/Holter software | 13 | IM-S10 | PARTIAL | L4 | IDCO and ECG drivers | M-04 |
| IM-E01-BL-018 | Driver packages – imaging & PACS: DICOM MWL/MPPS/C-STORE/RDSR/SR, DICOMweb to Sectra/Agfa/Philips/Fujifilm/GE PACS-VNA, dose management, ophthalmic (FORUM/HEYEX), dental and endoscopy exports, AI-triage flags | 8 | IM-S11 | PARTIAL | L4 | CT RDSR driver | M-04 |
| IM-E01-BL-019 | Driver packages – specialist units: dialysis (TDMS/Kinexus, Baxter, Nexadia, Sharesource/Nx2me), ECMO/MCS consoles, incubators/warmers, bilirubinometers, newborn hearing, fetal monitors, OR integration and energy devices, sterilisation tracking, perfusion, EEG/PSG exports, smart beds, nurse call | 13 | IM-S12 | PARTIAL | L4 | dialysis, neonatal, fetal, sleep, perfusion drivers | M-04 |
| IM-E01-BL-020 | Terminology service with MDC/LOINC/SNOMED/UCUM maps, conversions, reference ranges, GLI, tenant locale/unit policy; FHIR writer and raw archive | 13 | IM-S1 | DELIVERED | L1 | intimed-platform/src/main/java/io/intimed/engine/terminology/Terminology.java (UCUM synonyms, MDC dimensions, conversions, analyte factors); FHIR writer (adapters); raw archive (kind raw, encrypted) | — |
| IM-E01-BL-021 | Association engine with worklist, encounter–bed–device binding (ADT transfer/discharge), session, badge/NFC/RFID and vendor ID matching; unmatched queue UI | 21 | IM-S5 | DELIVERED | L4 | intimed-platform/src/main/java/io/intimed/engine/association/AssociationEngine.java (worklist, encounter–bed with ADT transfer/discharge, session, badge, matching 0.97/0.92/0.85, consent); unmatched queue UI | — |
| IM-E01-BL-022 | Quality-gate rules engine with guideline grading library, plausibility/artefact rules, duplicates/late handling, verify-before-post per template | 13 | IM-S4 | DELIVERED | L3 | intimed-platform/src/main/java/io/intimed/engine/quality/QualityGates.java (calibration/QC lockout, plausibility, artefact, skew/late, ATS/ERS grading, verify-before-post, alarm flags); harness XT-14 | — |
| IM-E01-BL-023 | Routing service with many-to-many template binding resolution and provenance fan-out | 13 | IM-S4 | DELIVERED | L3 | intimed-platform/src/main/java/io/intimed/engine/pipeline/Router.java (many-to-many bindings, unit conversion with block, provenance fan-out) | — |
| IM-E01-BL-024 | As a clinician I want to push the patient to the device and start the acquisition from the template where the vendor supports it | 8 | IM-S6 | DELIVERED | L4 | intimed-platform/src/main/java/io/intimed/capture/CaptureService.acquire (start from the form); harness/PumpSimulator + XT-12 order push RGV^O15 → RRG^O16 | — |
| IM-E01-BL-025 | Encrypted store-and-forward on gateway, device host and mobile with streaming buffer and reconciliation | 13 | IM-S1 | PARTIAL | L7 | sdk gateway encrypted store-and-forward + Dockerfile.gateway | M-03 |
| IM-E01-BL-026 | Simulator suite and golden datasets per family incl. streaming bursts; CI stages; harness integration | 21 | IM-S1 | DELIVERED | L1 | intimed-platform/src/main/java/io/intimed/engine/sim/Simulator.java (golden sessions for 26 drivers, negative cases); sdk test; UnitTests driver CI | — |
| IM-E01-BL-027 | OpenTelemetry instrumentation, tenant-scoped dashboards, alert rules, ops API, streaming-gap detection | 8 | IM-S1 | DELIVERED | L7 | Managed Prometheus scraping of /actuator/prometheus (pod annotations), SLO alert rule group (deploy/azure/terraform/monitoring.tf), Azure Monitor workbook (dashboard.tf), Container Insights, SIEM stream → Sentinel analytics rule; docs/azure/SLO-and-Runbooks.md; GCP (IntiMed_GCP): Managed Service for Prometheus PodMonitoring, PromQL alert policies, Cloud Monitoring dashboard, SIEM log sink → Pub/Sub (Doha) | M-11 |
| IM-E01-BL-028 | Tenant partitioning, quotas, residency pinning and automated tenant onboarding | 13 | IM-S1 | DELIVERED | L1 | intimed-platform/src/main/java/io/intimed/tenancy/TenantService.java (residency, quotas, entitlements, metering, offboarding with crypto-shredding); store/DocumentStore tenant partitioning | — |
| IM-E01-BL-029 | IEC 62304 classification and MDDS scope record; feature flags for excluded functions; release labelling | 5 | IM-S0 | DELIVERED | L1 | intimed-platform/src/main/java/io/intimed/quality/QualityController classification record + feature flags (alarm management/interpretation off); docs/regulatory | — |
| IM-E01-BL-030 | Performance and soak testing at 2× projected acute-care peak; capacity model per environment | 8 | IM-S13 | DELIVERED | L7 | intimed-platform/src/main/java/io/intimed/cli/Sdk.java `sdk soak` (mTLS MLLP/HTTPS load generator with p50/p95/p99 + ack mix), .github/workflows/intimed-perf-soak.yml, docs/azure/SLO-and-Runbooks.md capacity per environment | M-11 |

## IM-E02 – Universal Device Catalogue & Worldwide Inventory

_Maintain a governed, continuously updated catalogue of medical devices in use worldwide – families, brands, models, variants, UDI/GUDID/EUDAMED references, regulatory status per market (FDA product code and clearance, CE/UKCA, Health Canada, TGA, PMDA, NMPA, CDSCO, GCC), performance standards, supported connectivity options, certified driver versions, vendor software requirements, security posture…_

### High-level requirements → features

| Feature | Name | HLRs | Backlog items (status) |
|---|---|---|---|
| IM-E02-F01 | Catalogue model, APIs & console views | IM-E02-HLR-01; IM-E02-HLR-04 | IM-E02-BL-001 (DELIVERED) |
| IM-E02-F02 | Register feeds & change review | IM-E02-HLR-02; IM-E02-HLR-03 | IM-E02-BL-003 (DELIVERED)<br>IM-E02-BL-004 (DELIVERED) |
| IM-E02-F03 | Connectivity matrix & verification states | IM-E02-HLR-06 | IM-E02-BL-005 (DELIVERED) |
| IM-E02-F04 | Vendor/partner portal & request-a-model | IM-E02-HLR-05 | IM-E02-BL-006 (DELIVERED) |
| IM-E02-F05 | Seed inventory & approval counts | IM-E02-HLR-07 | IM-E02-BL-002 (DELIVERED) |
| IM-E02-F06 | Catalogue analytics | IM-E02-HLR-08 | IM-E02-BL-007 (DELIVERED) |

### Backlog → implementation evidence

| Backlog | Title | SP | Sprint | Status | Increment | Evidence / reason | Manual guide |
|---|---|---:|---|---|---|---|---|
| IM-E02-BL-001 | Build the catalogue data model, APIs and console views with lifecycle states and tenant acceptance policies | 13 | IM-S2 | DELIVERED | L2 | intimed-platform/src/main/java/io/intimed/catalogue/CatalogueService.java (search, model detail, connectivity matrix, posture, acceptance policies); UI Device catalogue | — |
| IM-E02-BL-002 | Load the seed inventory (≈245 devices) with FDA codes, approval counts, connectivity options and verification states | 5 | IM-S2 | DELIVERED | L2 | seed/devices.json (225 devices, 24 families), seed/fda-codes.json (132 codes with counts) | — |
| IM-E02-BL-003 | Implement openFDA, AccessGUDID and EUDAMED feed connectors with change detection and reviewer approval | 13 | IM-S2 | DELIVERED | L6 | intimed-platform/src/main/java/io/intimed/catalogue/UdiFeedService.java (AccessGUDID lookup by DI, EUDAMED configurable search, content-hash change detection, shared reviewer queue → CatalogueService.applyUdiRecord), security/egress/EgressClient.java (HTTPS, host allow-list, no redirects, private-address refusal); RegisterFeedService openFDA; PartnerCatalogueTest (local stub server) | — |
| IM-E02-BL-004 | As a device administrator I want recalls and safety communications for my connected models surfaced with acknowledgement tracking | 8 | IM-S2 | DELIVERED | L2 | intimed-platform/src/main/java/io/intimed/catalogue/CatalogueService recalls + acknowledgement tracking; openFDA recall feed after review | — |
| IM-E02-BL-005 | As a device administrator I want to see how confident the catalogue is about each model's connectivity and the specification it is based on | 5 | IM-S2 | DELIVERED | L2 | Connectivity matrix verification states (CERTIFIED_IN_LAB / VENDOR_CONFIRMED / COMMUNITY_REPORTED / UNVERIFIED) with basis | — |
| IM-E02-BL-006 | As a vendor I want to submit my model, interface specification, MDS2/SBOM and driver for certification and track status | 13 | IM-S16+ | DELIVERED | L6 | intimed-platform/src/main/java/io/intimed/partner/PartnerSubmissionService.java (PARTNER_DEV submission: metadata, interface spec text/PDF ≤ 5 MB with SHA-256, MDS2 + CycloneDX/SPDX SBOM → catalogue posture, signed driver via DriverRegistry.publish → CANDIDATE; SUBMITTED → IN_REVIEW → HARNESS_PASSED → CERTIFIED/REJECTED, four-eyes certify, audited); web/PartnerController.java; UI Partners & registers; PartnerCatalogueTest | M-14 |
| IM-E02-BL-007 | As the product team I want catalogue coverage, request and backlog analytics per tenant and globally | 5 | IM-S16+ | DELIVERED | L8 | intimed-platform/src/main/java/io/intimed/commercial/BenchmarkService.java (opt-in, k ≥ 5 organisations, small-cell suppression, no tenant ids); OperationsCommercialTest | — |

## IM-E03 – Guided Provisioning Console with Configuration Intelligence

_Give device administrators a console and an eight-step wizard that, at every selection – device model, site, connectivity option, parameters, association method, template bindings, quality gates, security settings – suggests the recommended configuration with an explanation and a confidence level, derived from the certified driver package, the tenant's site and security profile, the target templat…_

### High-level requirements → features

| Feature | Name | HLRs | Backlog items (status) |
|---|---|---|---|
| IM-E03-F01 | Eight-step wizard & site templates | IM-E03-HLR-01; IM-E03-HLR-08 | IM-E03-BL-001 (DELIVERED) |
| IM-E03-F02 | Recommendation engine (defaults + context + knowledge base) | IM-E03-HLR-02; IM-E03-HLR-04 | IM-E03-BL-002 (DELIVERED)<br>IM-E03-BL-003 (DELIVERED)<br>IM-E03-BL-004 (DELIVERED) |
| IM-E03-F03 | Conflict & risk detection | IM-E03-HLR-03 | IM-E03-BL-005 (DELIVERED) |
| IM-E03-F04 | Documentation assistant (retrieval with citations) | IM-E03-HLR-05 | IM-E03-BL-006 (DELIVERED) |
| IM-E03-F05 | Connectivity option packs P1–P10 | IM-E03-HLR-06 | IM-E03-BL-007 (DELIVERED) |
| IM-E03-F06 | Fleet operations & diagnostics | IM-E03-HLR-07 | IM-E03-BL-008 (DELIVERED)<br>IM-E03-BL-009 (DELIVERED)<br>IM-E03-BL-010 (DELIVERED)<br>IM-E03-BL-015 (DELIVERED) |
| IM-E03-F07 | Governance & environment promotion | IM-E03-HLR-08 | IM-E03-BL-011 (DELIVERED) |
| IM-E03-F08 | Vendor posture gate & certification records | IM-E03-HLR-09 | IM-E03-BL-012 (DELIVERED)<br>IM-E03-BL-013 (DELIVERED) |
| IM-E03-F09 | Embeddable, multilingual console | IM-E03-HLR-10 | IM-E03-BL-014 (PARTIAL) |

### Backlog → implementation evidence

| Backlog | Title | SP | Sprint | Status | Increment | Evidence / reason | Manual guide |
|---|---|---:|---|---|---|---|---|
| IM-E03-BL-001 | As a device administrator I want an eight-step wizard with pause/resume, clone and site templates that shows only options my model supports | 21 | IM-S3 | DELIVERED | L2 | intimed-platform/src/main/java/io/intimed/console/ConfigService.java (8 steps, pause/resume, clone, new version); UI wizard; options limited to the model/driver | — |
| IM-E03-BL-002 | Build the recommendation rule engine over driver defaults and tenant context (network profile, vendor software, security policy, jurisdiction, template requirements) with explanations and confidence | 21 | IM-S4 | DELIVERED | L3 | intimed-platform/src/main/java/io/intimed/console/intelligence/RecommendationEngine.java (RR-01…RR-16, why, confidence, policy outcome) | — |
| IM-E03-BL-003 | Build the configuration knowledge base: de-identified configuration fingerprints from certified runs with outcome statistics, opt-in sharing, ranking and learning loop from test/production outcomes | 21 | IM-S8 | DELIVERED | L3 | intimed-platform/src/main/java/io/intimed/console/intelligence/KnowledgeBase.java (de-identified fingerprints from certified runs, opt-in, success ranking) | — |
| IM-E03-BL-004 | As a device administrator I want to accept, adjust or override each recommendation with a reason and see the audit trail | 8 | IM-S4 | DELIVERED | L3 | intimed-platform/src/main/java/io/intimed/console/ConfigService.decide (ACCEPT/ADJUST/OVERRIDE with reason, audit trail, decision log) | — |
| IM-E03-BL-005 | Implement conflict and risk detection with tenant policy (block/warn/justify) and a pre-save validation API | 13 | IM-S4 | DELIVERED | L3 | intimed-platform/src/main/java/io/intimed/console/intelligence/PolicyEngine.java (BLOCK/JUSTIFY/WARN; pre-save validation API) | — |
| IM-E03-BL-006 | As a device administrator I want cited suggestions from the vendor's interface specification I uploaded, marked unverified until the test passes | 13 | IM-S8 | DELIVERED | L6 | intimed-platform/src/main/java/io/intimed/console/intelligence/ExplanationService.java (policy gateway: global + tenant switch, PHI redaction, residency host allow-list, API key from secret:// or managed identity via IMDS, max tokens, rate limit, audit of prompt/response hashes only, UNVERIFIED label, citations required); DocAssistant BM25 retrieval; web/ExplanationController.java; PartnerCatalogueTest (stub endpoint) | M-15 |
| IM-E03-BL-007 | Author the ten connectivity option packs (schemas, guides, diagrams, validation, defaults) | 13 | IM-S3 | DELIVERED | L2 | optionpacks/P1…P11.json (schemas, defaults, validation, prerequisites, guides, test fragments); console/OptionPacks.java | — |
| IM-E03-BL-008 | As a device administrator I want calibration/QC schedules with lockout and release, two-tap check entry for clinicians and audit reports | 13 | IM-S3 | DELIVERED | L2 | intimed-platform/src/main/java/io/intimed/console/ConfigService calibration/QC (lockout, release, two-tap checks, history); UI Fleet | — |
| IM-E03-BL-009 | As a clinic manager I want a fleet dashboard by site/room/bed with health, versions, alerts and update campaigns | 13 | IM-S3 | DELIVERED | L2 | intimed-platform/src/main/java/io/intimed/console/ConfigService.fleet (site/room/bed, health, versions, alerts) | — |
| IM-E03-BL-010 | As a support engineer I want PHI-masked diagnostics, payload inspector, time offset and a re-send action | 8 | IM-S3 | DELIVERED | L2 | intimed-platform/src/main/java/io/intimed/web/ConsoleController diagnostics (PHI-masked payload inspector, clock offsets, re-send) | — |
| IM-E03-BL-011 | Implement roles, four-eyes approval, change history and environment promotion Dev → UAT → Prod with diffs | 13 | IM-S3 | DELIVERED | L2 | intimed-platform/src/main/java/io/intimed/console/ConfigService (four-eyes, change history, DEV→UAT→PROD promotion with diffs, SRE approval for PROD) | — |
| IM-E03-BL-012 | As a security officer I want MDS2/SBOM intake, EOL dates and a posture policy that gates certification with documented exceptions | 8 | IM-S3 | DELIVERED | L2 | intimed-platform/src/main/java/io/intimed/catalogue posture register + PolicyEngine SC-07 + security co-approval | — |
| IM-E03-BL-013 | As an auditor I want a certification record per device with configuration snapshot, test evidence, approvals and the recommendation trail | 5 | IM-S3 | DELIVERED | L2 | intimed-platform/src/main/java/io/intimed/console/ConfigService.certificationRecord (snapshot, evidence seal, approvals, recommendation trail) | — |
| IM-E03-BL-014 | As a host-product vendor I want to embed the console as web components / a SMART app and drive it by API | 13 | IM-S7 | PARTIAL | L4 | static/assets/intimed-capture.js web component + REST console API + OpenAPI | M-06 |
| IM-E03-BL-015 | As a device administrator I want decommissioning to revoke keys, remove bindings and archive configuration in one action | 5 | IM-S14 | DELIVERED | L2 | intimed-platform/src/main/java/io/intimed/console/ConfigService.decommission (revoke keys, remove bindings, archive) | — |

## IM-E04 – Configuration-Time Exchange Testing & Template Binding Verification

_Provide a built-in test harness that, during provisioning and after any change, exercises the complete exchange between the device (live, or its simulator) and the associated template(s) – connection, handshake, sample acquisition, parsing, normalisation, association to a test subject, binding into a sandbox copy of the template, field-coverage and unit/range/code checks, round-trip read-back, lat…_

### High-level requirements → features

| Feature | Name | HLRs | Backlog items (status) |
|---|---|---|---|
| IM-E04-F01 | Test plan generator | IM-E04-HLR-01 | IM-E04-BL-001 (DELIVERED) |
| IM-E04-F02 | Exchange test executor | IM-E04-HLR-02 | IM-E04-BL-002 (DELIVERED)<br>IM-E04-BL-009 (DEFERRED) |
| IM-E04-F03 | Security checks in the exchange test | IM-E04-HLR-03 | IM-E04-BL-003 (DELIVERED) |
| IM-E04-F04 | Publish gate & re-test triggers | IM-E04-HLR-04 | IM-E04-BL-004 (DELIVERED) |
| IM-E04-F05 | Shadow mode & parallel-run sign-off | IM-E04-HLR-05 | IM-E04-BL-005 (DELIVERED) |
| IM-E04-F06 | Evidence pack & audit retrieval | IM-E04-HLR-06 | IM-E04-BL-006 (DELIVERED) |
| IM-E04-F07 | Site regression suites | IM-E04-HLR-07 | IM-E04-BL-007 (DELIVERED) |
| IM-E04-F08 | Harness API for CI and partners | IM-E04-HLR-08 | IM-E04-BL-008 (DELIVERED) |

### Backlog → implementation evidence

| Backlog | Title | SP | Sprint | Status | Increment | Evidence / reason | Manual guide |
|---|---|---:|---|---|---|---|---|
| IM-E04-BL-001 | Build the test plan generator from configuration, template bindings and tenant policy with live/simulator modes and test-subject management | 13 | IM-S4 | DELIVERED | L3 | intimed-platform/src/main/java/io/intimed/harness/HarnessService.plan | — |
| IM-E04-BL-002 | Implement the exchange test executor with sandbox template instances, coverage/unit/code/range checks, round-trip read-back, latency and negative cases | 21 | IM-S4 | DELIVERED | L3 | intimed-platform/src/main/java/io/intimed/harness/HarnessService.run (sandbox instances, coverage/unit/code/range, round trip, latency, negatives) | — |
| IM-E04-BL-003 | Add security checks (TLS/mTLS, plaintext refusal, pairing policy, identity binding) to every exchange test with control mapping | 8 | IM-S4 | DELIVERED | L3 | intimed-platform/src/main/java/io/intimed/harness/HarnessService.securityChecks (plaintext, no client cert, TLS 1.1, cert validity, pairing, scopes, secrets, HMAC, OAuth) | — |
| IM-E04-BL-004 | As a governance owner I want publication blocked until the exchange test passes and re-tests triggered by driver, template, firmware or parameter changes | 8 | IM-S4 | DELIVERED | L3 | intimed-platform/src/main/java/io/intimed/console/ConfigService.publish gate on configuration hash; re-test on any technical change | — |
| IM-E04-BL-005 | As a template owner I want a shadow-mode parallel run with discrepancy reports by field and device before I sign off go-live | 13 | IM-S15 | DELIVERED | L8 | intimed-platform/src/main/java/io/intimed/operations/ShadowReviewService.java (manual reference values encrypted, per-field/device agreement report, four-eyes clinical sign-off ends shadow mode); OperationsCommercialTest | M-08 |
| IM-E04-BL-006 | As an auditor I want the evidence pack of every run attached to the device's certification record | 5 | IM-S4 | DELIVERED | L3 | intimed-platform/src/main/java/io/intimed/harness/EvidenceSealer.java (manifest, results, renderings, masked traces, timings, security, SHA-256 + Ed25519 seal) attached to certification | — |
| IM-E04-BL-007 | As a device administrator I want scheduled site regression suites with trend reporting | 5 | IM-S13 | DELIVERED | L8 | intimed-platform/src/main/java/io/intimed/operations/RegressionService.java (nightly re-run of every PUBLISHED configuration, trends, regression.failed event + audit), web/OperationsController.java, UI Operations & commercial; OperationsCommercialTest | — |
| IM-E04-BL-008 | Expose the harness through an API used by driver CI and partner integration tests, sharing simulators and golden datasets | 8 | IM-S4 | DELIVERED | L3 | intimed-platform/src/main/java/io/intimed/web/MachineApiController /api/v1/harness/runs (scope harness.run); sdk test | — |
| IM-E04-BL-009 | Validate the harness in UAT with real devices from five families and document false-positive/negative rates | 5 | IM-S13 | DEFERRED | — | Needs real devices from five families in UAT | M-04 |

## IM-E05 – Template Binding & Clinical Capture Experience

_Define templates generically – an EHR flowsheet, a clinic form, an occupational-health record, a laboratory result, an anaesthesia or dialysis record, an RPM dashboard – as versioned Template Definitions with field semantics (codes, units, ranges, required flags), bind device observations to them through a visual binding editor with recommended bindings from the driver, and give clinicians an embe…_

### High-level requirements → features

| Feature | Name | HLRs | Backlog items (status) |
|---|---|---|---|
| IM-E05-F01 | Template Definitions & catalogue | IM-E05-HLR-01 | IM-E05-BL-001 (DELIVERED) |
| IM-E05-F02 | Binding editor with recommendations | IM-E05-HLR-02 | IM-E05-BL-002 (DELIVERED) |
| IM-E05-F03 | Template adapters to host products | IM-E05-HLR-03 | IM-E05-BL-003 (DELIVERED) |
| IM-E05-F04 | 'Capture from device' components (web, SMART, mobile SDKs) | IM-E05-HLR-04 | IM-E05-BL-004 (DELIVERED)<br>IM-E05-BL-005 (DEFERRED)<br>IM-E05-BL-014 (DEFERRED) |
| IM-E05-F05 | Fallback chain | IM-E05-HLR-05 | IM-E05-BL-006 (DELIVERED) |
| IM-E05-F06 | Batch, kiosk & mobile modes | IM-E05-HLR-06 | IM-E05-BL-007 (PARTIAL) |
| IM-E05-F07 | Continuous-feed presentation (interval values, trends) | IM-E05-HLR-07 | IM-E05-BL-008 (DELIVERED) |
| IM-E05-F08 | Patient/employee summaries & consent | IM-E05-HLR-08 | IM-E05-BL-009 (DEFERRED) |
| IM-E05-F09 | Reference template & binding library | IM-E05-HLR-09 | IM-E05-BL-010 (DELIVERED)<br>IM-E05-BL-011 (DELIVERED)<br>IM-E05-BL-012 (DELIVERED)<br>IM-E05-BL-013 (DELIVERED) |

### Backlog → implementation evidence

| Backlog | Title | SP | Sprint | Status | Increment | Evidence / reason | Manual guide |
|---|---|---:|---|---|---|---|---|
| IM-E05-BL-001 | Implement Template Definitions with field semantics, versioning, FHIR/host schema import and the catalogue by care setting | 13 | IM-S4 | DELIVERED | L3 | intimed-platform/src/main/java/io/intimed/templates/TemplateRegistry.java (22 reference templates, versioning, FHIR Questionnaire import) | — |
| IM-E05-BL-002 | As a template owner I want a binding editor pre-filled with recommended bindings and regression tests on template changes | 13 | IM-S4 | DELIVERED | L3 | UI binding editor (step 5) pre-filled from the reference library (RR-10); regression via re-test on template version change | — |
| IM-E05-BL-003 | Build template adapters: FHIR, HL7 v2 ORU, host REST, embedded post-back, PDF attachment with acknowledgements and error handling | 21 | IM-S5 | DELIVERED | L3 | intimed-platform/src/main/java/io/intimed/templates/TemplateAdapters.java (FHIR transaction + Provenance, HL7 v2.6 ORU, REST post-back, PDF) with acknowledgements | — |
| IM-E05-BL-004 | As a clinician I want a 'capture from device' component in my template that lists certified, in-calibration devices at my station or bed and files the result after my preview | 21 | IM-S5 | DELIVERED | L4 | static/assets/intimed-capture.js + capture/CaptureService (devices at station, in calibration, preview, accept/correct/reject) | — |
| IM-E05-BL-005 | SMART on FHIR app and iOS/Android SDKs exposing the capture component for host products | 13 | IM-S7 | DEFERRED | — | SMART app packaging and native iOS/Android SDKs need app-store accounts, signing and mobile CI | M-07 |
| IM-E05-BL-006 | As a clinician I want the next certified option (other device, OCR with verification, manual with attestation) when a device is unavailable, with the path recorded | 5 | IM-S7 | DELIVERED | L4 | intimed-platform/src/main/java/io/intimed/capture/CaptureService.fallback (other certified device → OCR with verification → manual with attestation) | — |
| IM-E05-BL-007 | As a nurse I want batch screening mode with BLE devices and offline queueing; as a patient I want kiosk self-measurement with consent | 13 | IM-S7 | PARTIAL | L4 | Session association, BLE parsing, gateway queue | M-03 |
| IM-E05-BL-008 | As an ICU nurse I want validated interval values from continuous feeds with trends and artefact flags in my flowsheet, without alarms | 13 | IM-S5 | DELIVERED | L4 | CONTINUOUS templates with interval series, artefact flags, alarm flagged-not-interpreted; demo streaming | — |
| IM-E05-BL-009 | As a patient I want plain-language result summaries in my language and control over my wearable data consent | 8 | IM-S8 | DEFERRED | — | Patient-facing app with localisation and patient identity is a separate product track | M-07 |
| IM-E05-BL-010 | Reference templates & bindings – acute care: ICU/ward interval flowsheet, ED triage vitals set, anaesthesia record, infusion/MAR, blood gas & POC result (TPL-01…05) with field semantics, units, codes and harness regression cases | 13 | IM-S5 | DELIVERED | L3 | templates TPL-01…TPL-05 with bindings from drivers | — |
| IM-E05-BL-011 | Reference templates & bindings – occupational health & outpatient: audiogram, spirometry, vision & colour vision, drug & alcohol with chain of custody, fit test & ergonomics, wellness/wearable summary (TPL-17…22) – aligned with OHCM forms | 13 | IM-S7 | DELIVERED | L3 | templates TPL-17…TPL-21 (audiogram, spirometry, vision, D&A chain of custody, fit test) | — |
| IM-E05-BL-012 | Reference templates & bindings – laboratory, imaging & cardiology: laboratory result report, imaging order/report link & dose record, echo/ECG structured report, device-clinic remote-monitoring (IDCO) report (TPL-06…09) | 8 | IM-S10 | DELIVERED | L3 | templates TPL-06…TPL-09 | — |
| IM-E05-BL-013 | Reference templates & bindings – RPM & specialist units: RPM daily readings dashboard, hospital-at-home flowsheet, dialysis session, neonatal observation chart, partogram, sleep/neurophysiology report, operative/perfusion record (TPL-10…16) | 13 | IM-S12 | DELIVERED | L3 | templates TPL-10…TPL-16, TPL-22 | — |
| IM-E05-BL-014 | Time-and-motion and UX audit in two reference sites (acute ward and OH clinic) | 5 | IM-S15 | DEFERRED | — | Time-and-motion study needs two reference sites | M-08 |

## IM-E06 – Endpoint & Integration Security as a core feature

_Make endpoint security a built-in property of IntiMed in every environment setting: per-endpoint identities and mutual TLS for gateways, device hosts, mobile apps and vendor connections; medical-device network zoning templates; hardened gateway and device-host images; vault-managed secrets; a secure development life-cycle with SBOM and vulnerability management (IEC 81001-5-1, FDA §524B expectation…_

### High-level requirements → features

| Feature | Name | HLRs | Backlog items (status) |
|---|---|---|---|
| IM-E06-F01 | Endpoint identity & PKI options | IM-E06-HLR-01 | IM-E06-BL-001 (DELIVERED) |
| IM-E06-F02 | Network zoning reference designs | IM-E06-HLR-02 | IM-E06-BL-002 (DELIVERED) |
| IM-E06-F03 | Hardened gateway & device-host images | IM-E06-HLR-03 | IM-E06-BL-003 (PARTIAL) |
| IM-E06-F04 | Vault/KMS integration per cloud | IM-E06-HLR-04 | IM-E06-BL-004 (DELIVERED) |
| IM-E06-F05 | Secure development life-cycle & SBOM | IM-E06-HLR-05 | IM-E06-BL-005 (DELIVERED) |
| IM-E06-F06 | Vendor posture gate | IM-E06-HLR-06 | IM-E06-BL-006 (DELIVERED) |
| IM-E06-F07 | Tamper-evident audit & SIEM | IM-E06-HLR-07 | IM-E06-BL-007 (DELIVERED) |
| IM-E06-F08 | Anomaly detection & isolation playbook | IM-E06-HLR-08 | IM-E06-BL-008 (DELIVERED) |
| IM-E06-F09 | Privacy controls & DPIA templates | IM-E06-HLR-09 | IM-E06-BL-009 (PARTIAL) |
| IM-E06-F10 | Security test kit (cloud-aware) | IM-E06-HLR-10 | IM-E06-BL-010 (PARTIAL) |
| IM-E06-F11 | Assurance programme | IM-E06-HLR-11 | IM-E06-BL-011 (DEFERRED) |

### Backlog → implementation evidence

| Backlog | Title | SP | Sprint | Status | Increment | Evidence / reason | Manual guide |
|---|---|---:|---|---|---|---|---|
| IM-E06-BL-001 | Implement PKI options (client CA / per-tenant managed CA), enrolment, rotation, revocation, OAuth2 clients and mTLS enforcement in all adapters | 13 | IM-S1 | DELIVERED | L1 | intimed-platform/src/main/java/io/intimed/security/pki/LocalPki.java (root + per-tenant CA, CSR enrolment, rotation tokens, revocation); security/auth/TokenService OAuth2; mTLS in all device listeners | — |
| IM-E06-BL-002 | Publish reference network designs for hospital, clinic, home and cloud with firewall templates and validation scripts | 8 | IM-S1 | DELIVERED | L7 | security-test-kit/01_network_segmentation_check.py (mTLS-only device ports, plaintext refused, other ports closed, console reachability), NSGs + Kubernetes NetworkPolicies (Terraform/Helm), docs/security/Network-Reference-Designs.md; GCP (IntiMed_GCP): VPC firewall rules (device ports from site CIDRs only, deny-all ingress), Dataplane V2 NetworkPolicies, internal passthrough NLB for devices | M-02 |
| IM-E06-BL-003 | Build hardened gateway and device-host images with zero-touch enrolment, drift detection and patch campaigns | 13 | IM-S1 | PARTIAL | L7 | deploy/docker/Dockerfile.gateway (non-root, minimal JRE image) + enrolment API (one-time token, CSR) | M-03 |
| IM-E06-BL-004 | Integrate Azure Key Vault, GCP Secret Manager/Cloud KMS and HashiCorp Vault behind one secrets interface; HSM for production; CI secret scanning | 13 | IM-S1 | DELIVERED | L9 | intimed-platform/src/main/java/io/intimed/security/secrets/{AzureKeyVaultSecretStore,AzureIdentity,GcpSecretManagerSecretStore,GcpIdentity,HashiCorpVaultSecretStore}.java – Azure Key Vault, GCP Secret Manager (Doha-only replication, tenant-bound annotations) and Vault behind one SecretStore contract; file-based runtime secrets (common/Secrets.java); CloudAdaptersTest + GcpAdaptersTest | — |
| IM-E06-BL-005 | Establish the IEC 81001-5-1 life-cycle: threat models, SAST/DAST/SCA gates, signed releases with SBOM, advisory monitoring, disclosure process and SLAs | 13 | IM-S1 | DELIVERED | L7 | Release pipeline: CodeQL (SAST), Trivy (SCA/image), gitleaks, OWASP ZAP baseline (DAST), CycloneDX SBOM, cosign-signed images, signed driver packages; docs/security/Threat-Model.md | M-10 |
| IM-E06-BL-006 | As a security officer I want a vendor posture gate (MDS2/SBOM/EOL) with isolation patterns and exceptions per tenant policy | 8 | IM-S3 | DELIVERED | L2 | Vendor posture gate with exceptions (catalogue posture, SC-07 JUSTIFY, security co-approval) | — |
| IM-E06-BL-007 | Tamper-evident audit store with SIEM forwarding (Sentinel, Chronicle, Splunk, QRadar) and PHI masking | 8 | IM-S1 | DELIVERED | L1 | intimed-platform/src/main/java/io/intimed/security/audit/AuditService.java (HMAC hash chain, verify, SIEM JSONL sink, PHI masking) | — |
| IM-E06-BL-008 | As a SOC analyst I want anomaly alerts with one-click isolation and a re-certification gate; run the drill | 13 | IM-S14 | DELIVERED | L4 | intimed-platform/src/main/java/io/intimed/security/endpoint/AnomalyService.java (AN-01…AN-07, one-click isolation, re-certification gate, drill metrics) | — |
| IM-E06-BL-009 | Privacy controls (minimisation, consent, retention, pseudonymised telemetry, de-identified non-prod) and DPIA templates per deployment setting | 8 | IM-S8 | PARTIAL | L4 | Consent, minimisation, retention via offboarding/crypto-shred, masking; DPIA template for Qatar (guide appendix A) | M-01 |
| IM-E06-BL-010 | Maintain the security test kit with cloud-specific checks (Azure, GCP, private cloud) and run it in all reference environments per release | 13 | IM-S14 | PARTIAL | L9 | security-test-kit/{16_azure_posture_check,16_gcp_posture_check,05_container_runtime_check,01_network_segmentation_check}.py + run-kit-aks.sh / run-kit-gke.sh; azure/gcp good+bad fixtures; both release pipelines run 16 + 05 against UAT | M-13 |
| IM-E06-BL-011 | Launch the assurance programme: SOC 2 Type II readiness, ISO 27001 scope, GA penetration test, IEC 80001-1 agreement template | 8 | IM-S14 | DEFERRED | — | SOC 2 / ISO 27001 / penetration test / IEC 80001-1 agreements are organisational programmes | M-10 |

## IM-E07 – Interoperability APIs, SDKs & Medical-Product Integration

_Expose IntiMed as a platform that medical products and healthcare systems integrate in days: FHIR R4/R5 APIs with Subscriptions and SMART on FHIR, HL7 v2 and IHE DEV interfaces, event webhooks, GraphQL for the console, SDKs (TypeScript, Java/Kotlin, .NET, Swift, Kotlin/Android, Python) and web components, template adapters, a partner certification programme and a published Integration Guide for em…_

### High-level requirements → features

| Feature | Name | HLRs | Backlog items (status) |
|---|---|---|---|
| IM-E07-F01 | FHIR API, Subscriptions & SMART launch | IM-E07-HLR-01; IM-E07-HLR-08 | IM-E07-BL-001 (PARTIAL)<br>IM-E07-BL-008 (DELIVERED) |
| IM-E07-F02 | HL7 v2 & IHE DEV endpoints | IM-E07-HLR-02 | IM-E07-BL-002 (PARTIAL) |
| IM-E07-F03 | Webhooks & event topics | IM-E07-HLR-03 | IM-E07-BL-003 (DELIVERED) |
| IM-E07-F04 | SDKs, web components, samples & sandbox | IM-E07-HLR-04 | IM-E07-BL-004 (PARTIAL) |
| IM-E07-F05 | Partner certification & marketplaces | IM-E07-HLR-05 | IM-E07-BL-005 (DELIVERED) |
| IM-E07-F06 | Integration Guide for medical products | IM-E07-HLR-06 | IM-E07-BL-006 (DELIVERED) |
| IM-E07-F07 | Reference integrations | IM-E07-HLR-07 | IM-E07-BL-007 (PARTIAL) |

### Backlog → implementation evidence

| Backlog | Title | SP | Sprint | Status | Increment | Evidence / reason | Manual guide |
|---|---|---:|---|---|---|---|---|
| IM-E07-BL-001 | Expose the FHIR R4 API surface with Subscriptions, SMART launch, scopes and OpenAPI/conformance statements; contract tests | 21 | IM-S5 | PARTIAL | L4 | intimed-platform/src/main/java/io/intimed/api/FhirController.java (CapabilityStatement, Observation, Device, Subscription rest-hook, Patient create/update with system/Patient.write) | M-06 |
| IM-E07-BL-002 | HL7 v2 and IHE DEV endpoints with profiles and conformance tests; prepare connectathon participation | 13 | IM-S5 | PARTIAL | L4 | MLLP-TLS endpoints with ORU in/out, ADT/ORM in | M-06 |
| IM-E07-BL-003 | Signed webhooks and bus topics with retries, replay and subscription management | 8 | IM-S5 | DELIVERED | L4 | intimed-platform/src/main/java/io/intimed/api/WebhookService.java (HMAC, retries, replay, subscriptions) + events/EventBus (persisted, replayable) | — |
| IM-E07-BL-004 | Publish SDKs (TypeScript, Java/Kotlin, .NET, Swift, Android, Python), web components, sample apps, sandbox tenants and the developer portal | 21 | IM-S7 | PARTIAL | L6 | intimed-platform/src/main/java/io/intimed/web/OpenApiController.java (OpenAPI 3.1 generated at runtime from RequestMappingHandlerMapping, /api/public/openapi.json: machine APIs, FHIR, intelligence, partner); intimed/sdk/typescript (fetch, typed) and intimed/sdk/python (urllib, offline unittest) with client-credentials helpers | M-07 |
| IM-E07-BL-005 | As a partner I want a certification programme with automated test suites and a badge listed in the catalogue and marketplaces | 13 | IM-S16+ | DELIVERED | L6 | intimed-platform/src/main/java/io/intimed/partner/BadgeService.java (badge with level, date, harness version, run id, evidence seal; shown in catalogue results and GET /api/partner/badges; auto-revoked on failing run.completed or driver withdrawal); PartnerCatalogueTest | — |
| IM-E07-BL-006 | Write and publish the Integration Guide for medical products with per-environment instructions (MacBook; Dev/UAT/Prod on Azure, GCP, private cloud) and checklists | 8 | IM-S13 | DELIVERED | L5 | docs/integration/Integration-Guide.md (MacBook + Dev/UAT/Prod per cloud, seven patterns) | — |
| IM-E07-BL-007 | Build and maintain reference integrations (EHR flowsheet FHIR + v2, OHCM, RPM app, LIS) with harness regression in CI | 21 | IM-S7 | PARTIAL | L4 | Reference host page, host receiver, FHIR and HL7 adapters | M-06 |
| IM-E07-BL-008 | API versioning policy, deprecation windows and partner contract tests | 3 | IM-S5 | DELIVERED | L4 | docs/integration/API-Versioning-Policy.md; FHIR CapabilityStatement; /api/v1 machine API | — |

## IM-E08 – Deployment, Environments & Operations

_Deliver IntiMed as reproducible infrastructure for four environment settings: a developer workstation on a MacBook (Apple silicon) that runs the whole platform with simulators; a shared Dev cluster; a production-like UAT with real devices and the security test kit; and a highly available Prod – each deployable on Microsoft Azure, Google Cloud or a client's private cloud (VMware/OpenShift/Rancher) …_

### High-level requirements → features

| Feature | Name | HLRs | Backlog items (status) |
|---|---|---|---|
| IM-E08-F01 | MacBook developer kit | IM-E08-HLR-01 | IM-E08-BL-001 (DELIVERED) |
| IM-E08-F02 | Cloud blueprints & IaC (Azure, GCP, private cloud) | IM-E08-HLR-02; IM-E08-HLR-07 | IM-E08-BL-002 (DELIVERED)<br>IM-E08-BL-003 (DELIVERED)<br>IM-E08-BL-004 (DEFERRED)<br>IM-E08-BL-010 (PARTIAL) |
| IM-E08-F03 | HA/DR design & drills | IM-E08-HLR-03 | IM-E08-BL-005 (DELIVERED) |
| IM-E08-F04 | CI/CD & promotion | IM-E08-HLR-04 | IM-E08-BL-006 (DELIVERED) |
| IM-E08-F05 | SRE operations & FinOps | IM-E08-HLR-05 | IM-E08-BL-007 (DELIVERED) |
| IM-E08-F06 | Edge packaging & fleet management | IM-E08-HLR-06 | IM-E08-BL-008 (PARTIAL) |
| IM-E08-F07 | Hosting TCO comparison model | IM-E08-HLR-08 | IM-E08-BL-009 (DELIVERED) |

### Backlog → implementation evidence

| Backlog | Title | SP | Sprint | Status | Increment | Evidence / reason | Manual guide |
|---|---|---:|---|---|---|---|---|
| IM-E08-BL-001 | Build the MacBook developer kit: bootstrap, arm64/amd64 images, local cluster/compose, simulators, local PKI/Vault, sample templates and docs | 13 | IM-S1 | DELIVERED | L1 | Self-contained platform jar (embedded encrypted H2, local PKI, simulators), deploy/local (compose with PostgreSQL + Vault dev), deploy/macos bootstrap, demo pack launcher | — |
| IM-E08-BL-002 | Terraform modules and Helm charts for Azure (AKS, PostgreSQL Flexible HA, Event Hubs/Strimzi, Blob, Key Vault, Entra, Front Door/WAF, Monitor/Sentinel) | 21 | IM-S1 | DELIVERED | L7 | deploy/azure/terraform (VNet, AKS, PostgreSQL Flexible HA, Key Vault, ACR, WORM Blob, App Gateway WAF, Firewall, VPN, Log Analytics/Sentinel, Managed Prometheus, policy, budget; terraform validate + 4 mocked tests) and deploy/helm/intimed (schema-validated) | M-01 |
| IM-E08-BL-003 | Terraform modules and Helm charts for GCP (GKE, Cloud SQL HA, Pub/Sub/Strimzi, Cloud Storage, KMS/Secret Manager, Identity Platform, Cloud Armor, Logging/Chronicle) | 21 | IM-S13 | DELIVERED | L9 | deploy/gcp/terraform (VPC, private regional GKE with Workload Identity/Dataplane V2/Shielded nodes/Binary Authorization, Cloud SQL PG16 regional HA + PITR, Cloud KMS HSM CMEK, Secret Manager Doha-only, Artifact Registry, retention-locked GCS, Cloud Armor, regional ALB, HA VPN, Cloud NGFW, org location policy, monitoring, budget; terraform validate + 3 plan tests) and the Helm chart cloud=gcp mode | M-01 |
| IM-E08-BL-004 | Private-cloud blueprint (OpenShift/Rancher on VMware, Patroni PostgreSQL, Strimzi/NATS, MinIO, Vault, Keycloak, HAProxy/F5, Prometheus/Grafana/Loki) with Ansible/Terraform | 21 | IM-S13 | DEFERRED | — | Private-cloud blueprint is outside the Azure (2.1) and GCP (2.2) releases | M-13 |
| IM-E08-BL-005 | HA/DR implementation and drills on each cloud; failover automation; backup verification | 13 | IM-S13 | DELIVERED | L7 | Zone-redundant AKS + Premium ZRS volume (pod reschedules across zones), PostgreSQL zone-redundant HA + 35-day PITR, WORM archive via intimed-platform/src/main/java/io/intimed/cloud/BlobArchiver.java, deploy/azure/scripts/dr-drill.sh (D1 zone loss, D2 DB failover, D3 PITR restore, D4 archive) – docs/azure/Operations-and-DR.md; GCP (IntiMed_GCP): regional GKE + regional PD (pod restarts in the second zone with its data), Cloud SQL REGIONAL HA + 35-day PITR, retention-locked GCS archive, deploy/gcp/scripts/dr-drill.sh (D1–D4), docs/gcp/IntiMed_GCP_Operations_and_DR.md | M-11 |
| IM-E08-BL-006 | CI/CD with signed images, SBOM, simulator/performance/security stages, GitOps promotion with approval gates; independent driver and configuration promotion | 13 | IM-S1 | DELIVERED | L7 | .github/workflows/intimed-azure-release.yml (tests, CodeQL, gitleaks, Trivy gate, push to ACR, cosign signature with HSM key in Key Vault, UAT deploy + helm test + posture checks + ZAP, PROD behind environment approval with signature verification), intimed-azure-infra.yml; GCP (IntiMed_GCP): .github/workflows/intimed-gcp-release.yml (Workload Identity Federation, Trivy gate, Artifact Registry, cosign with the Cloud KMS HSM key, Binary Authorization attestation, UAT gates, PROD approval + enforced attestation), intimed-gcp-infra.yml | M-11 |
| IM-E08-BL-007 | SLO catalogue, observability stacks per cloud, runbooks for top-20 failure modes, on-call, patch windows, capacity model and FinOps dashboards | 13 | IM-S13 | DELIVERED | L7 | docs/azure/SLO-and-Runbooks.md (SLOs, top-20 failure modes RB-01…RB-20), alert rules, workbook, budget alerts, docs/azure/IntiMed-Azure-Qatar-Cost-Model.xlsx; GCP (IntiMed_GCP): docs/gcp/IntiMed_GCP_SLO_and_Runbooks.md, PromQL alert policies + dashboard + billing budget (monitoring.tf), docs/gcp/IntiMed_GCP_Cost_Model.xlsx | M-11 |
| IM-E08-BL-008 | Edge packaging (gateway arm64/amd64 images, Windows device host MSI, mobile SDK packaging) and cloud-independent fleet management | 8 | IM-S1 | PARTIAL | L7 | deploy/docker/Dockerfile.gateway (arm64/amd64 OCI gateway image, non-root, sdk gateway encrypted store-and-forward) | M-03 |
| IM-E08-BL-009 | Build the 5-year hosting TCO model per cloud and environment with cost per connected device; review with Finance | 5 | IM-S13 | DELIVERED | L7 | docs/azure/IntiMed-Azure-Qatar-Cost-Model.xlsx (live Qatar Central retail prices, UAT/PROD bill of materials, 5-year TCO, cost per connected device) built by tools/azure/cost_model.py; GCP (IntiMed_GCP): docs/gcp/IntiMed_GCP_Cost_Model.xlsx (me-central1 price snapshot with per-line source and confidence, UAT/PROD BOM, 5-year TCO with 1y/3y CUDs) built by tools/gcp/cost_model.py | M-11 |
| IM-E08-BL-010 | Reference deployments: IntiMed-hosted SaaS (Azure) and client-hosted single tenant (GCP and private cloud) with runbook parity | 8 | IM-S13 | PARTIAL | L9 | Reference deployments on Azure Qatar Central (2.1) and Google Cloud me-central1 Doha (2.2, IntiMed_GCP) – IntiMed-hosted or client-hosted, Terraform envs uat/prod, setup guides | M-13 |

## IM-E09 – Multi-Tenancy, Commercial Operations & Analytics

_Run IntiMed as a business: tenant onboarding and lifecycle, licensing models (per facility, per connected device, per tenant bands, OEM/embedded), metering and billing feeds, entitlement enforcement, customer success tooling, usage and outcome analytics (capture rates, time saved, data quality, device fleet health) for clients and for the product, benchmarking across tenants with privacy safeguard…_

### High-level requirements → features

| Feature | Name | HLRs | Backlog items (status) |
|---|---|---|---|
| IM-E09-F01 | Tenant lifecycle automation | IM-E09-HLR-01 | IM-E09-BL-001 (DELIVERED) |
| IM-E09-F02 | Licensing, entitlements & metering | IM-E09-HLR-02 | IM-E09-BL-002 (DELIVERED) |
| IM-E09-F03 | Client analytics | IM-E09-HLR-03 | IM-E09-BL-003 (DELIVERED) |
| IM-E09-F04 | Product analytics & benchmarking | IM-E09-HLR-04 | IM-E09-BL-004 (DELIVERED) |
| IM-E09-F05 | Customer success tooling | IM-E09-HLR-05 | IM-E09-BL-005 (DELIVERED) |
| IM-E09-F06 | White-label / OEM | IM-E09-HLR-06 | IM-E09-BL-006 (DELIVERED) |

### Backlog → implementation evidence

| Backlog | Title | SP | Sprint | Status | Increment | Evidence / reason | Manual guide |
|---|---|---:|---|---|---|---|---|
| IM-E09-BL-001 | Automate tenant lifecycle incl. trials with sandbox devices and offboarding export/deletion evidence | 13 | IM-S16+ | DELIVERED | L8 | intimed-platform/src/main/java/io/intimed/commercial/TrialService.java (trial tenants with expiry, sandbox, suspension, offboarding with crypto-shred) + simulator-only enforcement in Pipeline and endpoint registration; OperationsCommercialTest | — |
| IM-E09-BL-002 | Implement licensing models, entitlements and metering with billing/marketplace feeds and reconciliation | 13 | IM-S16+ | DELIVERED | L8 | intimed-platform/src/main/java/io/intimed/commercial/BillingService.java (HMAC-signed metering export CSV/JSONL, reconciliation against invoice lines); OperationsCommercialTest | M-14 |
| IM-E09-BL-003 | As a client executive I want monthly analytics on capture rates, time saved, data quality, calibration compliance and fleet health | 8 | IM-S15 | DELIVERED | L8 | intimed-platform/src/main/java/io/intimed/commercial/MonthlyReportService.java (JSON/CSV executive report, monthly snapshots); OperationsCommercialTest | — |
| IM-E09-BL-004 | Privacy-preserving product analytics and cross-tenant benchmarking feeding the knowledge base (opt-in) | 8 | IM-S8 | DELIVERED | L8 | intimed-platform/src/main/java/io/intimed/commercial/BenchmarkService.java + KnowledgeBase.contributeAggregate (k-anonymous aggregates only); OperationsCommercialTest | — |
| IM-E09-BL-005 | As a customer success manager I want health scores and adoption dashboards with support-case integration | 5 | IM-S16+ | DELIVERED | L8 | intimed-platform/src/main/java/io/intimed/commercial/TenantHealthService.java (0–100 health score with explained factors); OperationsCommercialTest | — |
| IM-E09-BL-006 | As an OEM partner I want to white-label IntiMed with my branding and support channels | 8 | IM-S16+ | DELIVERED | L8 | intimed-platform/src/main/java/io/intimed/commercial/BrandingService.java (validated tokens, hardened SVG/PNG logo check, CSSOM application in console and capture component); OperationsCommercialTest | — |

## IM-E10 – Product Regulatory, Quality & Clinical Safety

_Establish IntiMed as a quality-managed health software product: software safety classification and IEC 62304 life-cycle, ISO 14971 risk management, ISO 13485-aligned QMS processes, MDDS/SaMD regulatory analysis per market, clinical safety case templates for deployments (e.g., DCB0129/0160-style), usability engineering (IEC 62366) for the capture and console experiences, labelling and instructions …_

### High-level requirements → features

| Feature | Name | HLRs | Backlog items (status) |
|---|---|---|---|
| IM-E10-F01 | Regulatory analysis & classification record | IM-E10-HLR-01 | IM-E10-BL-001 (DELIVERED) |
| IM-E10-F02 | IEC 62304 process & traceability | IM-E10-HLR-02 | IM-E10-BL-002 (DELIVERED) |
| IM-E10-F03 | Risk management file | IM-E10-HLR-03 | IM-E10-BL-003 (PARTIAL) |
| IM-E10-F04 | Usability engineering file | IM-E10-HLR-04 | IM-E10-BL-004 (DEFERRED) |
| IM-E10-F05 | Clinical safety case templates, labelling & IFU | IM-E10-HLR-05 | IM-E10-BL-005 (PARTIAL) |
| IM-E10-F06 | Post-market surveillance & complaints | IM-E10-HLR-06 | IM-E10-BL-006 (DEFERRED) |
| IM-E10-F07 | Supplier management | IM-E10-HLR-07 | IM-E10-BL-007 (DELIVERED) |

### Backlog → implementation evidence

| Backlog | Title | SP | Sprint | Status | Increment | Evidence / reason | Manual guide |
|---|---|---:|---|---|---|---|---|
| IM-E10-BL-001 | Produce the regulatory analysis and classification record per market with exclusions and maintenance procedure | 8 | IM-S0 | DELIVERED | L1 | docs/regulatory/Classification-Record.md; /api/quality/classification | — |
| IM-E10-BL-002 | Implement IEC 62304 processes within the agile model and traceability tooling from epics/HLR to tests and releases | 13 | IM-S1 | DELIVERED | L1 | Traceability epic → HLR → backlog → code → tests (this matrix, /api/quality/backlog, docs/traceability) | — |
| IM-E10-BL-003 | Build the ISO 14971 risk management file with hazard analysis and controls traced to features and tests | 13 | IM-S4 | PARTIAL | L3 | docs/regulatory/Risk-Management-Summary.md (hazards → controls → tests incl. 2.0 rows) | M-09 |
| IM-E10-BL-004 | Usability engineering file with formative and summative evaluations of capture component, console and wizard | 8 | IM-S14 | DEFERRED | — | IEC 62366-1 formative/summative studies need representative users | M-08 |
| IM-E10-BL-005 | Clinical safety case templates, hazard logs, labelling, IFU, release notes and known-anomaly process | 8 | IM-S14 | PARTIAL | L5 | Release notes, labelling of excluded functions, classification record | M-09 |
| IM-E10-BL-006 | Post-market surveillance, complaint handling and vigilance interfaces with timelines and risk-file feedback | 5 | IM-S15 | DEFERRED | — | Post-market surveillance needs a live product | M-09 |
| IM-E10-BL-007 | Supplier management register for open source, cloud providers and device vendors | 5 | IM-S13 | DELIVERED | L7 | tools/compliance/supplier_register.py (SBOM components + Azure services + device vendors → supplier register workbook) | M-09 |

## HLR index

| HLR | Requirement (abridged) | Priority |
|---|---|---|
| IM-E01-HLR-01 | The engine shall implement one canonical observation pipeline (acquire → parse → normalise → associate → validate → route → persist → acknowledge) for episodic and continuous device data, independent of vendor and protoc | Must |
| IM-E01-HLR-02 | The engine shall provide a protocol adapter library covering: serial/USB (RS-232, USB-CDC, HID), Bluetooth LE/Classic (standard GATT health profiles and vendor services), HL7 v2 over MLLP-TLS (IHE DEV PCD-01/DEV-01 perio | Must |
| IM-E01-HLR-03 | A driver SDK shall allow new device models to be added as declarative, signed driver packages (protocol settings per option, grammar/schema, MDC/LOINC/UCUM mappings, unit conversions, reference ranges, quality rules, def | Must |
| IM-E01-HLR-04 | Every measurement shall be normalised to a canonical observation: code (MDC and LOINC, SNOMED where applicable), value/unit (UCUM), method, body site, device identity (UDI, serial, firmware, software), driver version, ca | Must |
| IM-E01-HLR-05 | Subject association shall use, in configurable priority: worklist/order push (ADT/ORM/MWL/vendor demographics), encounter–bed–device binding for continuous monitors (with ADT transfer/discharge handling), session binding | Must |
| IM-E01-HLR-06 | Configurable quality gates shall run before posting: calibration/QC validity, guideline grading (e.g., ATS/ERS, audiometric validity, BP validation), physiologic plausibility and artefact rules, duplicate and late-arriva | Must |
| IM-E01-HLR-07 | Observations shall be routed to the exact fields of the associated template(s) through Template Bindings (IM-E05) configured per device model × template version, with the host product performing clinical logic; one measu | Must |
| IM-E01-HLR-08 | Bidirectional device workflows shall be supported where devices accept input: demographics/order push, start/stop acquisition from the template, retrieval of historic results, and acknowledgement of data receipt to data  | Should |
| IM-E01-HLR-09 | Edge store-and-forward shall queue encrypted results on gateways, device hosts and mobile apps during network loss (≥ 48 h) and reconcile with ordering and duplicate suppression; continuous feeds shall buffer at configur | Must |
| IM-E01-HLR-10 | A simulator suite and golden datasets shall exist per family (recorded sessions, synthetic edge cases, malformed messages, time-skew, duplicates, streaming bursts) and shall be used in CI for driver conformance, regressi | Must |
| IM-E01-HLR-11 | Observability shall cover each connector and device: heartbeat, message volumes and latency percentiles, parse/validation/association error rates, unmatched and dead-letter queue sizes, calibration-due counts, streaming  | Must |
| IM-E01-HLR-12 | Performance: spot results ≤ 3 s end-to-end at p95; streaming summary intervals ≤ 1 s; 500 messages/s per tenant with horizontal scaling; waveform/image payloads streamed to object storage; capacity model per environment. | Must |
| IM-E01-HLR-13 | Multi-tenancy shall isolate catalogues, drivers, keys, queues, templates and data per tenant with quotas, residency pinning and per-tenant observability. | Must |
| IM-E01-HLR-14 | The product scope shall remain Medical Device Data System-type functionality (transfer, store, convert, display) plus configuration intelligence; any function that would constitute active monitoring, alarm management or  | Must |
| IM-E02-HLR-01 | The catalogue shall model families, brands, models, variants and vendor software with UDI/GUDID/EUDAMED references, regulatory status per market, performance standards, supported connectivity options, certified drivers,  | Must |
| IM-E02-HLR-02 | Automated feeds shall refresh regulatory data from openFDA (classification, 510(k), registration & listing, recalls), AccessGUDID and EUDAMED public extracts at least monthly, with change detection and reviewer approval  | Must |
| IM-E02-HLR-03 | Recalls and safety communications shall be linked to catalogue models and surfaced to tenants that have the model connected, with acknowledgement tracking. | Must |
| IM-E02-HLR-04 | Tenants shall configure an acceptance policy per jurisdiction (e.g., FDA or CE plus local registration) that gates which models can be certified for their sites. | Must |
| IM-E02-HLR-05 | A vendor/partner portal shall allow manufacturers and integrators to submit models, interface specifications, MDS2/SBOM and driver packages for certification, with status tracking and community verification of connectivi | Should |
| IM-E02-HLR-06 | Connectivity details per model shall carry a confidence/verification state (vendor-specified, lab-verified, field-verified, unverified) and interface-specification references; the console shall warn when provisioning rel | Must |
| IM-E02-HLR-07 | The seed inventory shall cover the main healthcare device families – acute care monitoring, infusion, respiratory and anaesthesia, cardiology diagnostics and implant remote monitoring, laboratory and point-of-care analys | Must |
| IM-E02-HLR-08 | Catalogue analytics shall report coverage by family and setting, most-requested models, certification backlog and verification status, per tenant and globally. | Should |
| IM-E03-HLR-01 | The console shall provide an eight-step provisioning wizard (select device; choose connectivity option; prerequisites; parameters; associate & bind templates; test; certify & approve; publish & monitor) with pause/resume | Must |
| IM-E03-HLR-02 | At every step the wizard shall present a recommended configuration with an explanation ('why') and a confidence level, derived in priority from: the certified driver package's validated defaults; tenant context rules (si | Must |
| IM-E03-HLR-03 | The intelligence layer shall detect conflicts and risks before save – e.g., Just-Works pairing where passkey is supported, plaintext MLLP, TLS below policy, unit or code mismatch with the template, missing required templ | Must |
| IM-E03-HLR-04 | Recommendations shall never be applied automatically in UAT or production: administrators accept, adjust or override with a reason; every recommendation, acceptance and override shall be audited and fed back to the knowl | Must |
| IM-E03-HLR-05 | A documentation assistant shall retrieve relevant passages from uploaded vendor interface specifications and IFUs (per tenant, access-controlled) to suggest parameter values with citations; suggestions shall be marked as | Should |
| IM-E03-HLR-06 | Parameter configuration shall follow internationally accepted practice per option (serial framing; USB allow-lists; BLE pairing/bonding/UUIDs; MLLP address/port/TLS/mTLS/MSH identifiers/ACK mode/profile; POCT1-A and LIS  | Must |
| IM-E03-HLR-07 | Fleet operations shall provide inventory by site/room/station/bed, calibration and QC logs with automatic lockout and release, firmware/driver versions and update campaigns, consumables and lots, health dashboards, alert | Must |
| IM-E03-HLR-08 | Configuration governance shall enforce roles, four-eyes approval for UAT/production, complete change history, export/import and promotion of configuration sets Dev → UAT → Prod with diffs, and batch provisioning from sit | Must |
| IM-E03-HLR-09 | Vendor security onboarding (MDS2/SBOM intake, support/EOL dates, posture policy, compensating-control exceptions) shall gate certification; certification records (configuration snapshot, test evidence, approvals, recomme | Must |
| IM-E03-HLR-10 | The console shall be multilingual and WCAG 2.2 AA, embeddable in host products (web components / SMART app) and fully API-driven for automation. | Should |
| IM-E04-HLR-01 | The harness shall generate an exchange test plan automatically from the device model, connectivity option, parameters, association method, template bindings and tenant policy, selecting live-device or simulator mode and  | Must |
| IM-E04-HLR-02 | The harness shall execute and report, as a minimum: connection and handshake; sample acquisition; parse and normalise results; association to the test subject; binding dry-run into a sandbox instance of each associated t | Must |
| IM-E04-HLR-03 | Security checks shall be part of every exchange test: TLS/mTLS policy, plaintext refusal, pairing policy, certificate validity and identity binding, with results mapped to the security control matrix. | Must |
| IM-E04-HLR-04 | Publication of a device configuration to UAT or production shall be blocked until the exchange test passes (or a documented, approved exception exists); any change to driver, template version, firmware or key parameters  | Must |
| IM-E04-HLR-05 | Shadow mode shall route live device data into a shadow copy of the template for a configurable period, compare with manually entered values where present, report discrepancies by field and device, and support parallel-ru | Must |
| IM-E04-HLR-06 | The harness shall produce an evidence pack per run (machine-readable results, sandbox template screenshots, PHI-masked traces, timings, security results) attached to the certification record and retrievable by auditors. | Must |
| IM-E04-HLR-07 | Site regression suites shall re-run exchange tests for all devices at a site on schedule and after platform releases, with trend reporting. | Should |
| IM-E04-HLR-08 | The same harness shall be callable from driver CI (IM-E01) and from host-product integration tests (IM-E07) through an API, with simulators and golden datasets shared. | Must |
| IM-E05-HLR-01 | Templates shall be defined generically as versioned Template Definitions with field semantics (code systems, units, ranges, required flags, verification rules, display rules), imported from FHIR Questionnaire/StructureDe | Must |
| IM-E05-HLR-02 | A binding editor shall map device observations to template fields per model × template version, pre-filled with recommended bindings from the driver package and template semantics, supporting many-to-many bindings, trans | Must |
| IM-E05-HLR-03 | Template adapters shall deliver bound values to host products via FHIR (Observation, QuestionnaireResponse, DiagnosticReport), HL7 v2 ORU to flowsheets, host REST APIs, embedded components posting back to the host, and P | Must |
| IM-E05-HLR-04 | Clinicians shall capture through an embeddable 'capture from device' control (web components, SMART on FHIR app, iOS/Android SDKs) that lists only certified, in-calibration devices at the station or bed or paired to the  | Must |
| IM-E05-HLR-05 | Fallback paths (another device, image/OCR with mandatory verification, manual entry with device attestation) shall be offered when a device is unavailable, with the path recorded in provenance. | Must |
| IM-E05-HLR-06 | Batch screening mode (queue of subjects, one tap per measurement, offline), kiosk self-measurement with identification and consent, and mobile capture shall be supported. | Must |
| IM-E05-HLR-07 | For continuous feeds the capture experience shall present validated spot values at configurable intervals with trend sparklines and artefact flags for display only, leaving monitoring and alarms to the host and the devic | Must |
| IM-E05-HLR-08 | Patient/employee-facing summaries with plain-language meaning, language preference and consent management for home and wearable devices shall be provided as embeddable components. | Should |
| IM-E05-HLR-09 | A library of reference templates and bindings shall ship for common forms across settings (ward vitals flowsheet, ED triage, ICU interval flowsheet, anaesthesia record, OPD visit vitals, OH audiogram/spirometry/vision/vi | Must |
| IM-E06-HLR-01 | Every gateway, device host, mobile app instance, embedded component and vendor connection shall have a unique cryptographic identity (X.509 from the client's CA or an IntiMed-managed per-tenant CA, or OAuth2 clients with | Must |
| IM-E06-HLR-02 | Reference network designs shall be provided for hospital, clinic, home/RPM and cloud topologies: medical-device zones, gateway zones, conduits, deny-by-default policies, no device-to-internet by default, brokered vendor  | Must |
| IM-E06-HLR-03 | Gateways and device hosts shall run hardened images (CIS baselines, disk encryption, secure boot where available, EDR hooks, central logging, time sync) with zero-touch enrolment, hardware-backed identity where available | Must |
| IM-E06-HLR-04 | Secrets shall live in the environment's vault/KMS (Azure Key Vault, GCP Secret Manager/Cloud KMS, HashiCorp Vault on private cloud; HSM-backed in production), injected at runtime, rotated and audited; never in configurat | Must |
| IM-E06-HLR-05 | IntiMed shall be developed under IEC 81001-5-1 with threat models per adapter and environment, SAST/DAST/SCA gates, signed releases with SBOM (CycloneDX), advisory monitoring, coordinated disclosure and remediation SLAs  | Must |
| IM-E06-HLR-06 | Vendor posture gating (MDS2/SBOM, support/EOL) shall apply to connected device models; unsupported models shall be isolated behind gateways with compensating controls or rejected per tenant policy. | Must |
| IM-E06-HLR-07 | Audit events (provisioning, identity, configuration, data access, connections, authentication failures) shall be tamper-evident (hash-chained), forwarded to the client SIEM, with PHI masking in diagnostics. | Must |
| IM-E06-HLR-08 | Anomaly detection (new identity, unexpected port/protocol, rate spikes, authentication bursts, certificate anomalies) shall alert the SOC; a compromise playbook shall isolate the endpoint within 15 minutes, preserve evid | Must |
| IM-E06-HLR-09 | Privacy by design: data minimisation, purpose binding, consent for home/wearable devices, retention classes, pseudonymised telemetry, de-identified non-production data, DPIA templates per deployment setting; residency pe | Must |
| IM-E06-HLR-10 | A security test kit (scripts for TLS/mTLS, segmentation, MLLP/FHIR authentication and robustness, gateway/device-host hardening, BLE policy, SBOM/vulnerability scan, secrets/config audit, audit integrity, encryption at r | Must |
| IM-E06-HLR-11 | The SaaS offering shall pursue SOC 2 Type II and ISO 27001 certification with control evidence generated by the platform; penetration tests shall be performed before GA and annually; an IEC 80001-1 responsibility agreeme | Must |
| IM-E07-HLR-01 | IntiMed shall expose a FHIR R4 (R5-ready) API for device data and templates with Subscriptions, SMART on FHIR launch for capture and console components, OAuth2 scopes and tenant isolation, documented with OpenAPI and con | Must |
| IM-E07-HLR-02 | HL7 v2 (ORU/ORM/ADT) and IHE DEV (PCD-01/02, ACM observations, IPEC) endpoints shall be available inbound and outbound for EHRs, gateways and data managers, with message profiles and conformance testing. | Must |
| IM-E07-HLR-03 | Event webhooks and message-bus topics shall notify host products of observations posted, device alerts, certifications and exchange-test results, with retries, signatures and replay. | Must |
| IM-E07-HLR-04 | SDKs (TypeScript, Java/Kotlin, .NET, Swift, Android, Python), web components and sample apps shall be published with sandbox tenants and simulators so a developer can run an end-to-end capture on a MacBook in under an ho | Must |
| IM-E07-HLR-05 | A partner certification programme shall define integration levels, automated test suites (via the exchange harness API), security requirements and badges; certified integrations shall be listed in the catalogue and marke | Should |
| IM-E07-HLR-06 | An Integration Guide for medical products shall document, per environment setting (MacBook development; Dev, UAT and Prod with HA on Azure, GCP or client private cloud): embedding patterns (SaaS API, sidecar module in th | Must |
| IM-E07-HLR-07 | Reference integrations shall be maintained for an EHR flowsheet (FHIR and HL7 v2 paths), the OHCM occupational-health platform (first client), an RPM application and a laboratory system, each with automated regression us | Must |
| IM-E07-HLR-08 | API versioning shall guarantee no breaking changes within a major version, deprecation windows of ≥ 12 months and contract tests published to partners. | Must |
| IM-E08-HLR-01 | A MacBook developer kit shall run the full platform locally on Apple silicon (arm64 images with amd64 parity) with one-command bootstrap, simulators, local PKI/Vault and sample templates, in ≤ 30 minutes. | Must |
| IM-E08-HLR-02 | Dev, UAT and Prod environments shall be deployable from infrastructure-as-code on Microsoft Azure, Google Cloud and client private cloud with identical application images and environment-specific modules (compute, databa | Must |
| IM-E08-HLR-03 | Prod shall be highly available across zones with RPO ≤ 15 minutes and RTO ≤ 2 hours, documented and drilled twice a year; UAT shall be production-like with real devices and the security test kit as release gate. | Must |
| IM-E08-HLR-04 | CI/CD shall build signed images with SBOM, run unit/contract/simulator/performance/security stages, and promote through Dev → UAT → Prod with GitOps and approval gates; driver packages and configuration sets shall promot | Must |
| IM-E08-HLR-05 | Operations shall include an SLO catalogue, observability stack per cloud, runbooks for the top failure modes, on-call, patch windows, capacity management and a FinOps cost model with cost per connected device per cloud. | Must |
| IM-E08-HLR-06 | Edge components (gateway images, Windows device host, mobile SDKs) shall be packaged and fleet-managed consistently regardless of the central cloud. | Must |
| IM-E08-HLR-07 | Both operating models shall be supported with the same runbooks: IntiMed-hosted multi-tenant SaaS (Azure or GCP) and single-tenant client-hosted (client cloud subscription or private cloud), including residency pinning. | Must |
| IM-E08-HLR-08 | A cost comparison model shall estimate 5-year hosting TCO for Dev/UAT/Prod on Azure, GCP and private cloud (compute, managed data services, storage, networking/egress, security services, operations staff, licences) with  | Must |
| IM-E09-HLR-01 | Tenant lifecycle shall be automated: trial creation with sandbox devices, onboarding with templates and identity federation, scaling, renewal and offboarding with complete data export and deletion evidence. | Must |
| IM-E09-HLR-02 | Licensing shall support per-facility, per-connected-device, tenant bands and OEM/embedded models with entitlement enforcement and grace periods; metering shall count devices, messages, templates and users with ≥ 99.9% ac | Must |
| IM-E09-HLR-03 | Client analytics shall report capture rates, time saved estimates, unmatched and error rates, calibration compliance and fleet health per site, exportable and embeddable. | Must |
| IM-E09-HLR-04 | Product analytics and cross-tenant benchmarking shall be privacy-preserving (aggregation thresholds, opt-in) and shall feed the configuration knowledge base. | Should |
| IM-E09-HLR-05 | Customer success tooling shall provide health scores, adoption dashboards and support-case integration. | Should |
| IM-E09-HLR-06 | White-labelling and OEM configuration shall allow vendors to embed IntiMed under their brand with their support channels. | Should |
| IM-E10-HLR-01 | A regulatory analysis and classification record shall define IntiMed's status per target market (MDDS-type functionality; exclusions; conditions under which modules would become regulated) and shall be maintained with ea | Must |
| IM-E10-HLR-02 | Software life-cycle processes per IEC 62304 (with safety classification) shall be implemented within the agile model with traceability from epics and HLRs to design, tests and releases. | Must |
| IM-E10-HLR-03 | A risk management file per ISO 14971 shall analyse hazards (wrong-subject data, unit/code errors, delayed or missing data, misleading display, security breaches) with risk controls traced to features and verified by test | Must |
| IM-E10-HLR-04 | Usability engineering (IEC 62366) shall cover the capture component, console and wizard, with formative and summative evaluations in representative settings. | Should |
| IM-E10-HLR-05 | Clinical safety case templates and hazard logs shall be provided for client deployments, together with labelling, IFU, release notes and known-anomaly lists. | Must |
| IM-E10-HLR-06 | Post-market surveillance, complaint handling and vigilance interfaces shall be operated with defined timelines, feeding the risk file and the backlog. | Must |
| IM-E10-HLR-07 | Supplier management shall cover open-source components (SBOM, licences), cloud providers (shared-responsibility evidence) and device vendors (MDS2). | Must |

## Tests

| Test class | What it proves |
|---|---|
| **Total 138 automated tests** (Maven) + 7 Terraform tests (4 Azure, 3 GCP; mocked providers) + security kit 69 local checks + cloud checks (01, 05, 16 Azure / 16 GCP) | |
| `UnitTests` (36) | All 26 driver packages valid, signed, secret-free, golden session parses every semantic key, malformed rejected; XXE refused; SFLOAT; serial checksum; GDT length; payload size; UCUM; AES-GCM context binding; RFC 6238 TOTP vector; PHI masking; secret scanner |
| `PlatformIntegrationTest` (10) | Wizard with intelligence → exchange test 15/15 over real mTLS → four-eyes refusal/approval → publish gate → DEV/UAT, PROD needs SRE; live capture files into TPL-18, PHI encrypted at rest, idempotent re-send; re-test after change; RR-03 block; SC-19 accept-all refused in UAT; plaintext/no-cert/revoked refused; anomaly isolation + re-certification gate; tenant isolation (ciphertext copied across tenants cannot be read); audit tamper detection; HTTP controls (401, CSRF, CSP, HSTS, FHIR auth, webhook HMAC); password policy and lockout |
| `ClinicalIntelligenceTests` (42), `ClinicalIntelligenceDeferredIntegrationTest` (8) | Reference ranges, cohorts, validation, eGFR/derived values, delta checks, EP28 governance, host demographics, RR-17/RR-18, trajectories, terminology adapter |
| `OperationsCommercialTest` (8) | Regression suites, shadow sign-off, monthly report, health score, billing HMAC + reconciliation, trials (simulator-only), branding hardening, k-anonymous benchmarks |
| `PartnerCatalogueTest` (9), `ProtocolCodecTests` (8) | Partner submissions + four-eyes + badges, GUDID/EUDAMED feeds, AI gateway policy, OpenAPI; ASTM E1381 / POCT1-A2 codecs |
| `CloudAdaptersTest` (4) | Azure Key Vault secret store (tenant binding, HTTPS/host rules), workload-identity token exchange, immutable Blob archiver |
| `GcpAdaptersTest` (4) | GCP Secret Manager store (Doha-only replication, tenant binding, endpoint/project/region validation), metadata-server token caching, create-only GCS archive |
| `ArchitectureTest` (4) | Engine core independent of control plane; security independent of engine; templates host-agnostic; nothing depends on the demo |
| `security-test-kit/` | SC-01…SC-20 local scripts against the running platform (see kit README) |
