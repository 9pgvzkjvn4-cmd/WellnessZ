# WellnessZ OHCM – Device Administrator Guide

**Version 1.0**

*WellnessZ OHCM platform – increment 6 (Medical Device Integration Engine)*

| | |
|---|---|
| **Document** | Increment 6 – 04 Device Administrator Guide |
| **Audience** | Device administrators (biomedical / clinical engineering), device approvers, Nursing Heads, Security Operations (for the steps that need them) |
| **Applies to** | OH workspace → *Devices* (`#/devices/*`) and *Security → Devices* (`#/security/devices*`); edge application `wz-device-gateway` |
| **Companion documents** | [01 – Solution Design](01-Solution-Design-E31-E34.md) · [05 – Integration Tester Guide](05-Integration-Tester-Guide.md) · [gateway README](../../apps/wz-device-gateway/README.md) · [API contract](API-Contract-E31-E34.md) §13 |

> **Read this first.** You configure devices; you never see patients. The console roles (`DEVICE_ADMIN`,
> `DEVICE_APPROVER`, `INTEGRATION_TESTER`, `SECURITY_OPS`) have no access to person data: traces are masked, queues show
> counts. Every change is versioned and production changes need a second person. In this release the engine was
> verified with simulators and synthetic data only – **the LIVE test in wizard step 6 is the first time a real device
> talks to the engine**, so plan it with the vendor and a nurse. Hardware-related limits (no native serial / USB, BLE through
> a relay, DICOM via a drop folder) are listed in Section 9.

## 1. Roles and the two-person rule

| You are | Role | You can | You cannot |
|---|---|---|---|
| Device administrator | `DEVICE_ADMIN` | Catalogue edits, wizard steps 1–6 and 8, submit for approval, fleet, calibration / QC, diagnostics, model requests, request decommissioning, templates, export / import | Approve anything; see person data |
| Device approver | `DEVICE_APPROVER` | Approve / reject step 7, certify drivers, approve imports and decommissioning | Approve what you submitted **or edited in steps 4–6** (409 `four_eyes_violation`) |
| Integration tester | `INTEGRATION_TESTER` | Register driver packages, run simulators and conformance, replay dead letters, scope model requests, test-only configurations on test sites | Certify their own packages |
| Security operations | `SECURITY_OPS` | Endpoints, enrolment tokens, isolation / release, zone CIDRs, signing keys, posture exceptions, co-approve waived live tests | – |
| OH nurse / Nursing Head | `OH_CLINICIAN`, `NURSING_HEAD` | Nurse review of the test form (step 6), quick QC at the station | Change configurations |

Demo users (local stack only): `deviceadmin1`, `deviceapprover1`, `inttester1`, `secops1`, nurse `nurse1`.

## 2. The device catalogue (`#/devices/catalogue`)

1. **Search** by family, brand or model. The catalogue holds the client's 133-model inventory in 11 families (spirometry,
   audiometry, vitals and body composition, cardiology, vision, point-of-care, drug and alcohol, respiratory protection and
   ergonomics, exposure feeds, wearables, imaging).
2. **Open a model** to see *Identification* (UDI), *Regulatory status per market*, *Supported connectivity options*,
   *Security posture* and *Driver packages*. Compare up to several models side by side (*Side-by-side comparison*).
3. **Lifecycle.** Seeded models are `CANDIDATE`. A model becomes `CERTIFIED` (DEVICE_APPROVER) only with a certified driver
   package and an acceptable posture or approved exception; `DEPRECATED` warns in the wizard; `RETIRED` needs no published
   instance.
4. **UDI.** Paste the record downloaded from GUDID or EUDAMED (*UDI*, source, retrieval date). There is no online look-up.
5. **Security posture.** Record MDS2, SBOM, support status, operating system, patch path, remote-support method, security
   contacts and contract terms. The policy blocks unsupported operating systems, no patch path, direct vendor remote access
   or a missing MDS2. For a legacy vendor PC, request an **exception** listing compensating controls (gateway isolation in
   the device VLAN, outbound-only Device Host agent) with an expiry date; **only SECURITY_OPS can approve it**.
6. **Inventory confidence.** Rows not marked *High* in the inventory add the prerequisite *confirm the interface
   specification with the vendor* – do not skip it.
7. **Request a new model** (`#/devices/model-requests`) when the device is missing: manufacturer, model, family,
   connectivity, interface documents, sites, urgency. The integration team scopes it (existing family adapter, effort,
   target sprint); you are notified at every status change.

## 3. Before you start the wizard – checklist

| Item | Who | Where |
|---|---|---|
| The model is in the catalogue and its posture is not BLOCKED (or an exception is approved) | Device admin / SECURITY_OPS | Catalogue |
| A registered or certified **driver package** exists for the model and the option | Integration tester | `#/devices/drivers` |
| The station exists (site, room, station codes from E14) | Device admin | Wizard step 5 lists stations |
| The device is in the **E14 equipment register** with a valid calibration (spirometers, audiometers, BP devices …) | Biomedical engineering | E14 equipment screen |
| A **gateway or Device Host agent** fronts the site (for P1, P2, P4, P5, P7) and its site's zone CIDRs are configured | SECURITY_OPS | `#/security/devices`, zone CIDRs |
| Network change ticket for the device VLAN and the conduit (e.g. MLLP 2575 TLS device → gateway) | Network team | Exported firewall template (`#/security/devices/zones`) |
| Vendor software version within the package's supported versions | Vendor / device admin | Package manifest |

## 4. The eight-step wizard (`#/devices/wizard`)

Press *Create draft* (model code, optional site). The configuration `CFG-nnnnnn` and the device instance `DEV-nnnnnn` are
created. Each step shows its status (not started, in progress, valid, invalid); *Save and continue* validates the step.
Changing an earlier step invalidates the later ones – for example a new option resets steps 3–8 and clears stored secrets.
You can **pause** a draft at any step and resume later.

| Step | What you do | Tips |
|---|---|---|
| 1 Select device | Search and select the model | A DEPRECATED model gives a warning; a BLOCKED posture stops you unless an exception is approved |
| 2 Choose connectivity option | Pick one of the supported options; the comparison shows prerequisites, reliability, security level and effort | Options without a driver package are refused (`no_driver_for_option`) |
| 3 Prerequisites checklist | *Generate the checklist and run the automatic checks*; mark manual items DONE / NOT_APPLICABLE with evidence (text, document id or reference) | Automatic items: endpoint enrolled (E34), calibration valid (E14), vendor posture. Re-run after fixing |
| 4 Configure parameters | Fill the option form; defaults come from the driver package. Common items: time source, time zone, units, locale, driver version, reference-range overrides, extra quality gates, calibration schedule, verify-before-post, retention | **Secrets** (database passwords, API keys, webhook secrets) are write-only: after saving you only see "set". You can only **add or tighten** gates |
| 5 Associate and bind | Choose association methods (worklist, session, badge, vendor-id match – only those the driver supports), site / room / station, services, forms and field bindings; *Preview against the test vectors* | Prefer **session** binding at stations and **worklist** where the vendor software accepts orders. Restricted categories (breath alcohol) allow only worklist or session |
| 6 Test | *Run a test*: SIMULATOR (golden datasets including negative cases) and, for production stations, **LIVE** (the real device sends a sample into the test form). A nurse then performs the *Nurse review of the test form* | LIVE passes when the connector or the fronting endpoint was heard within 5 minutes. Without a live run you must record a *Simulator-only justification* – then SECURITY_OPS must co-approve in step 7 |
| 7 Certify and approve | *Submit for approval*; a different person approves or rejects with a note | The approver must not have edited steps 4–6. Approval creates the certification record `DCR-…` and the signed PDF |
| 8 Publish and monitor | Publish | The **endpoint id and one-time enrolment token** are shown **once** – copy the token into the gateway's token file immediately (Section 5). The device appears in nurses' *Capture from device* lists |

After publishing: **Clone configuration** to another station (a new draft at step 5 – steps 6–8 again, LIVE test at the new
station), **revise** (a new version; the published one keeps running until the new one is published), **suspend** /
*Resume service*, **Request decommissioning** (Section 7). The *Data flow (no person data)* panel shows device → endpoint →
adapter → driver → gates → association → forms → retention, and the certificate PDF is downloadable for audits.

## 5. Enrolling the gateway or agent (with SECURITY_OPS)

1. The endpoint is created at publish (or by SECURITY_OPS in `#/security/devices`). Kinds: gateway `GW`, Device Host agent
   `AGT`, tablet `TAB`, kiosk `KSK`, data manager `DM`. Ids look like `EP-LHR-GW-01`.
2. Obtain the client certificate for the node from the group PKI (extended key usage *clientAuth*) as a PKCS12 key store.
3. On the node, put the one-time token in the token file (`WZ_GATEWAY_ENROLMENT_TOKEN_FILE`) and start the gateway with the
   key store (`wz.gateway.tls.key-store`). The token is valid for at most **72 hours** and works once; ask SECURITY_OPS for a
   new one if it expired.
4. The gateway enrols automatically, stores its identity encrypted and deletes the token file. Check the status page on the
   node: `http://127.0.0.1:8095/`.
5. In `#/security/devices` the endpoint turns `ACTIVE`; re-run the step-3 automatic checks of waiting configurations.

## 6. Worked examples per connectivity option

The examples use the demo data of the local stack; replace site, station, addresses and ids with your own.

### 6.1 P2 – spirometer exporting GDT files (EasyOne Connect, Device Host agent)

1. *Select device*: MD-001 (ndd EasyOne). *Option*: **P2 Vendor software – database / file**.
2. *Prerequisites*: Device Host agent `EP-LHR-AGT-01` enrolled (auto), vendor software version within the package range,
   E14 calibration valid (auto), daily 3-L syringe schedule known to the nurses.
3. *Configure*: source `FOLDER_WATCH`, path `C:\EasyOneConnect\Export`, pattern `*.gdt`, encoding `ISO-8859-1`, poll 10 s,
   stability 5 s, *after read* `KEEP` (never touch the vendor's files), attachment rule "pair PDFs by file stem", host
   endpoint `EP-LHR-AGT-01`. Driver `spiro.ndd.easyone-connect-gdt` 1.2.0.
4. *Associate & bind*: methods WORKLIST, SESSION, VENDOR_ID_MATCH; station `LHR-OH-ROOM-2`; form
   `E03.DEVICE_RESULT.SPIROMETRY`; preview with the `normal` dataset shows FEV1, FVC, ratio, GLI predicted and % predicted.
5. *Test*: simulator run (normal, edge, malformed, time-skew, duplicate) → PASSED; LIVE: perform a test manoeuvre on a
   volunteer record, nurse review.
6. On the agent, add a watcher (`wz.gateway.watchers[n]` with `path`, `archive`, `quarantine`, `post-action: COPY`). The
   worklist push writes GDT 6302 files into `worklist.gdt-folder` when nurses open a capture session.
7. **Lockout check:** if the 3-L syringe check is overdue the device shows `LOCKED_OUT` to nurses and results are BLOCKED
   until a PASS check is recorded (Section 8).

### 6.2 P2 – audiometer vendor database (read-only)

1. MD-015 (Benson CCA-200), option **P2**, source `DB_QUERY`, dialect `SQLSERVER`, host / database of the vendor PC.
2. *Database credential*: enter it once – it is stored as a secret and replaced by a reference (or use a `vault:` reference
   from the vault agent). Ask the vendor for a **read-only** account.
3. *Query*: the package's read-only `select` with a watermark column (`modified_at`); poll every 30 s.
4. On the agent: `wz.gateway.db-pollers[n]` with the JDBC URL, `credential-ref`, query with **exactly one `?`** for the
   watermark, `watermark-column`; put the vendor JDBC driver into the agent's driver folder (`LOADER_PATH`).
5. Gates include the daily biological check, test-retest and ambient noise; bind to `E03.DEVICE_RESULT.AUDIOMETRY`.

### 6.3 P4 – vitals monitor over HL7 v2 MLLP (gateway)

1. MD-027 (Welch Allyn Connex), option **P4 LAN / Wi-Fi HL7 v2 MLLP**.
2. *Configure*: device address `10.20.30.41`, listener endpoint `EP-LHR-GW-01`, port `2575`, **TLS REQUIRED** (1.3),
   MSH sending application `CONNEX`, receiving application `WELLNESSZ`, ACK mode ORIGINAL, profile `IHE_PCD_01`, time zone.
   Plaintext MLLP is only allowed by a documented exception inside a segmented device VLAN behind the gateway.
3. *Associate & bind*: SESSION (and WORKLIST / VENDOR_ID_MATCH if used); form `E03.DEVICE_RESULT.VITALS`; BP cuff validation
   and plausibility gates.
4. On the gateway: `wz.gateway.mllp[n]` with `port: 2575`, TLS key store, `sending-applications: [CONNEX]`. Configure the
   monitor to send ORU^R01 to the gateway address and port.
5. *LIVE test*: take a reading; the monitor receives **AA** (the engine's ACK relayed by the gateway). If the core is
   unreachable the gateway answers AA only after the message is stored encrypted and forwards it later.

### 6.4 P3 – Bluetooth LE blood-pressure monitor on the nurse tablet

1. MD-037 (A&D UA-651BLE), option **P3 Bluetooth LE / Classic**.
2. *Configure*: transport BLE, pairing **LE Secure Connections – passkey**, bonding required, bonding owner TABLET
   (`EP-LHR-TAB-01`), service `0x1810`, characteristic `0x2A35`, peripheral allow-list (name and address hash), reconnect
   AUTO, minimum RSSI −80 dBm. *Just Works* pairing needs a recorded security-exception reference.
3. *Associate & bind*: SESSION, BADGE; forms `E03.DEVICE_RESULT.VITALS` and `E12.SCREENING_RESULT`.
4. Pair the device on the tablet's operating system. In this release the engine receives BLE readings from the tablet /
   kiosk endpoint or from the gateway's BLE relay; the radio itself is handled by the tablet or a local helper.

### 6.5 P1 – breath-alcohol tester on a serial port (Device Host agent)

1. MD-097 (Lifeloc FC20), option **P1 Serial / USB**.
2. *Configure*: connection RS232, port `COM3`, 9600 baud, 8 data bits, no parity, 1 stop bit, no flow control, framing LINE
   (terminator CR LF, max 4096 bytes), mode PUSH, host endpoint `EP-LHR-AGT-01`.
3. *Associate & bind*: WORKLIST (the E10 test id) and SESSION only – breath alcohol is a **restricted** category; form
   `E10.BREATH_ALCOHOL`; gate `QC_DAILY:BREATH_ACCURACY_CHECK` (30-day accuracy check).
4. On the agent: `wz.gateway.serial[n]` with `mode: TCP` to a serial device server (or `ser2net` in raw mode) on the vendor
   PC, `port-name: COM3`, `framing: LINE`. **There is no direct COM-port access in this release** – a serial device server
   or `ser2net` is needed; a non-loopback TCP target needs the plaintext exception flag.

### 6.6 P5 – point-of-care analyser through its data manager (POCT1-A)

1. MD-081 (Abbott Afinion 2), option **P5 POCT1-A2 data manager**.
2. *Configure*: data manager vendor `INFO_HQ`, endpoint `EP-LHR-GW-01`, device ids, DMS id; transport POCT1A_XML; operator
   list synchronised from the data manager; **QC-lot policy**: passing QC required, 24-hour window, new lot requires QC.
3. *Bind*: `E03.DEVICE_RESULT.POC`; gate `QC_LOT`; abnormal results go to the clinician's abnormal worklist.
4. On the gateway: `wz.gateway.poct[n]` (TLS listener); point the data manager at it. QC messages (`QCN.R01`) update the QC
   log automatically.

### 6.7 P6 – fit tester through the vendor cloud webhook

1. MD-105 (TSI PortaCount via TSI Link), option **P6 Vendor cloud API / webhook**.
2. *Configure*: API base URL (https, host must be on the egress allow-list `device.cloud.allowed-hosts`), webhook enabled
   with a **webhook secret** (write-only), signature header and timestamp header, replay window 300 s, consent mapping,
   residency region equal to the tenant's residency.
3. Give the vendor the webhook URL `https://<api host>/api/v1/device-webhooks/<DEV id>` and the shared secret through a
   secure channel. Each call must carry an HMAC-SHA256 signature over `timestamp + "." + body`.
4. *Bind*: `E03.DEVICE_RESULT.FIT_TEST`; gate `FIT_EXERCISES_COMPLETE`.
5. **Polling instead of (or with) the webhook** (revision 1.1): enable *poll*, set the interval (≥ 60 s) and the rate limit, the
   OAuth2 token URL, client id and the **client secret** (write-only; the platform asks the vendor's token endpoint with
   client credentials). Both the API host and the token host must be on `device.cloud.allowed-hosts`, and the residency region
   must equal the tenant's residency – otherwise the connector shows *Host not on the egress allow-list* or *Residency does not
   match the tenant* on *Engine health → Vendor cloud connectors (P6)* and makes no outbound call. Records are taken once
   (high-water mark); for wearables map the consent purpose `WEARABLE_DATA` – readings of employees without that consent are
   never stored. *Poll now* runs a connector outside its schedule.

### 6.7a Ordering, start from the form and historic results (revision 1.1)

* Devices whose driver declares an HL7 worklist (e.g. GE MAC `ecg.ge.mac-hl7`) receive the day's orders / demographics as
  ORM^O01 (or OML^O21, ADT^A04 / A08 – P4 parameter `worklist.messageType`) over MLLP. The gateway sends them only to the
  outbound target configured on the gateway for that device (`wz.gateway.mllp-outbound`); the capture panel shows whether the
  device accepted or rejected the order.
* *Start from the form*: drivers with a start command (GE MAC over MLLP, Lifeloc FC20 over a serial terminal server) start the
  measurement when the nurse opens the capture; other devices get the worklist (GDT 6302) – otherwise the nurse starts on the
  device.
* *Engine health → Historic retrieval*: ask a device or data manager for the results of a period (≤ 31 days); results already
  in the platform are recognised and not stored twice.
* **GE MAC (MD-045):** the catalogue keeps the client's inventory options P1 / P4 and adds the documented route **P2 – MUSE XML
  export** (driver `ecg.ge.muse-xml`); the wizard therefore offers P2 for this model, marked as a catalogue route.
* **BLE pairing:** packages declare LE Secure Connections with passkey. If a model turns out to support only *Just Works*,
  configure `JUST_WORKS_EXCEPTION` with a Security Operations exception reference (and no bonding requirement).
* **Tenants:** a configuration can be created for a tenant (`tenantCode`); only models of that tenant's catalogue are offered.

### 6.8 P7, P8, P9, P10 in brief

* **P7 DICOM** (MD-125 CXR): the modality or PACS exports instances into the gateway's drop folder
  (`wz.gateway.dicom[n]`); the gateway uploads the instance and its metadata. No C-STORE / worklist in this release.
* **P8 FHIR** (MD-121 wearable aggregator): the client posts FHIR Observations to `/device-ingest/fhir` with its own
  endpoint identity (mTLS + token).
* **P9 Photo / OCR** (MD-089 LeadCare II): nurses photograph the display; values are proposed only when an OCR engine is
  installed and always need verification.
* **P10 Manual with attestation** (MD-133 Snellen): nurses enter values and attest model, serial, calibration id and date.

## 7. Decommissioning

1. *Request decommissioning* on the configuration: reason and disposal (`DISPOSED`, `TRANSFERRED` with *Transferred to*,
   `RETURNED_TO_VENDOR`, `STORED`).
2. A device approver approves (four-eyes). The endpoint is revoked within 5 minutes, secrets are destroyed, bindings removed,
   the configuration archived (retention `DEVICE_CONFIGURATION`, 10 years) and a disposal record written. Device-sourced
   results stay in the record under their own retention class.
3. On a **shared gateway** only the decommissioned instance is removed – the gateway keeps serving the other devices.

## 8. Calibration and QC (`#/devices/calibration`)

| Check type | Interval (proposed) | Lockout |
|---|---|---|
| `AUDIO_DAILY_BIOLOGICAL` | 24 h, before the first test of the day | yes |
| `SPIRO_SYRINGE_3L` | 24 h | yes |
| `SPIRO_LINEARITY` | 7 days | yes |
| `BP_VALIDATION_INTERVAL` | 365 days | yes |
| `BREATH_ACCURACY_CHECK` | 30 days | yes |
| `POC_QC_LOT` | each new lot and every 30 days | yes |
| `FIT_DAILY_CHECK` | 24 h | yes |

* **Annual / exhaustive calibration** stays in the E14 equipment register; an expired E14 calibration blocks results
  (`device_out_of_calibration`).
* **Quick QC at the station:** nurses record the daily check in two taps (PASS / FAIL, values, lot and expiry for POC). A
  FAIL or an overdue check **locks the device out** (nurses see `LOCKED_OUT` and the reason); release needs a newer PASS – there
  is no override.
* Schedules can be tightened per instance; relaxing them needs approval. Production schedule changes need a second person.
* Reports: compliance per site (JSON / CSV / PDF) and the E14 migration data-quality report.

## 9. Fleet and operations

* **Fleet** (`#/devices/fleet`): per instance health (`CONNECTED`, `IDLE`, `ERROR`, `STALE` after 5 minutes without
  heartbeat, `SILENT` after 30), last seen, firmware / software / driver versions, endpoint status, calibration, QC and alerts.
  The site view shows healthy, degraded, silent, quarantined, calibration-due and locked-out counts.
* **Alerts**: acknowledge, or raise an **ITSM ticket** (sent through the outbox). Set the **on-duty administrator** per site so
  alerts reach the right person. Recording a firmware version that the pinned driver does not support raises
  `DRIVER_INCOMPATIBLE`.
* **Consumables**: lots, expiry, quantity per instance.
* **Diagnostics** (`#/devices/diagnostics/{id}`): masked live trace (names, ids, dates, values shown as `«masked»`), last
  payloads, parse and mapping outcome, time offset, re-send last result (audited).
* **Operations** (`#/devices/operations`, `#/devices/dead-letters`): connector health, latency, error and unmatched rates;
  dead letters can be replayed or discarded (see document 05).
* **Governance** (`#/devices/governance`): change history with diffs; **export** (secrets as references only, sealed with
  SHA-256) and **import with diff** – an importer cannot approve their own import; imported configurations start as drafts at
  step 6 (test again in the target environment); site and family templates and batch provisioning for a new clinic.

**Known limits that affect administrators.** No direct serial / USB port access (use a serial device server); USB VID/PID
allow-lists are recorded but not enforced by the agent; BLE pairing is done on the tablet / helper; DICOM only through a drop
folder; worklist push only as GDT files; *re-send last* and *start acquisition* are not supported by gateway connectors;
certificate renewal of gateways is manual (the gateway warns 30 days ahead).

## 10. Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| Step 2 refuses the option (`no_driver_for_option`) | No registered driver for model and option | Ask the integration tester to register / certify a package (document 05) |
| Step 3 auto-check `ENDPOINT_IDENTITY` open | Gateway / agent not enrolled or not ACTIVE | Enrol (Section 5); check `#/security/devices` |
| Step 3 `CALIBRATION_EVIDENCE` open | No E14 equipment link or calibration expired | Link the equipment id; ask biomedical engineering |
| Step 6 LIVE test fails | No heartbeat from the endpoint within 5 minutes, or no message received | Check the gateway status page, listener port and device settings; check the conduit / firewall |
| Step 7 `four_eyes_violation` | Approver submitted or edited steps 4–6 | Another approver must decide |
| Gateway does not start | No stored identity and no enrolment token | Provide the token file and key store |
| Enrolment `enrolment_token_expired` / `enrolment_token_invalid` | Token older than 72 h or already used | SECURITY_OPS issues a new token |
| Enrolment `certificate_untrusted` / `certificate_key_usage` | Certificate not from the configured device CA or no *clientAuth* | Re-issue from the group PKI; check `WZ_DEVICE_TRUST_ANCHORS` |
| Gateway calls answered 401 `zone_violation` | Gateway address not in its site's gateway-zone CIDRs | SECURITY_OPS adds the CIDR |
| 401 `mtls_required` | Request bypassed the mTLS ingress or the ingress did not set the thumbprint | Check the ingress route |
| 401 `endpoint_isolated` | Security Operations or an anomaly rule isolated the endpoint | Contact SECURITY_OPS; after investigation: revise → test → approve → publish, then SECURITY_OPS releases; start the gateway once with `release-quarantine=true` |
| Device shows `LOCKED_OUT` to nurses | Daily check / QC overdue or failed | Record a PASS check |
| Device shows `QUARANTINED` | Endpoint isolated or revoked | As for `endpoint_isolated` |
| Instances `SUSPENDED` | Driver version revoked, or signing key revoked | Move to a certified version (revise); see document 05 |
| MLLP device receives `AR` | Sending application not in the allow-list, queue full or not HL7 | Check MSH-3 and the gateway queue |
| Folder files land in quarantine | Unsupported format, empty, too large or failed structure check | Check the export settings in the vendor software |
| Many unmatched results | Association methods too narrow or vendor id format differs | Enable worklist / session binding; configure id normalisation per site |
| Gateway backlog alert (`device.gateway-backlog`) | Core unreachable or slow | Check connectivity; the queue holds ≥ 48 h (default 72 h / 2 GB) |
