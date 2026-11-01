# IntiMed 1.0.0-local – release notes

**Scope:** LOCAL environment setting (MacBook) of the IntiMed product pack – all ten epics in their local subset, built in
five increments (L1–L5) following the sprint plan. 55 backlog items delivered (659 SP), 43 partially delivered (535 SP),
19 deferred (201 SP) – see `traceability/`.

## Highlights

* Canonical pipeline with 13 grammar parsers behind P1–P11; 26 signed driver packages; 22 reference templates.
* Universal catalogue (225 devices, 24 families, 132 FDA codes), connectivity verification states, posture gate, openFDA feed.
* Eight-step wizard with configuration intelligence RR-01…RR-16, policy engine, de-identified knowledge base, documentation assistant.
* Exchange test harness XT-01…XT-16 over the real mTLS listeners; sealed evidence packs; hash-bound publish gate; four-eyes; DEV → UAT → PROD with SRE approval.
* Capture component (web component), fallback chain, verify-before-post, calibration/QC lockout, shadow mode.
* Security: per-endpoint PKI and CSR enrolment, mTLS-only device ports, tenant envelope encryption, vault interface (local / HashiCorp Vault), hash-chained audit with SIEM sink, anomaly isolation and re-certification gate, TOTP MFA, OAuth2 ES256 JWT, CSP/HSTS/CSRF, PHI masking, secret scanning, SBOM.
* FHIR R4 API, HL7 v2 over MLLP-TLS, signed webhooks, machine API, `mdi-sdk` CLI with gateway store-and-forward.

## Verification

| Check | Result |
|---|---|
| `./mvnw verify` | 51 tests passing (36 unit, 11 end-to-end integration, 4 architecture) |
| Security test kit | 13 scripts, 69 checks, 69 passing |
| Demo scenarios | 22 of 22 template scenarios provisioned → exchange test passed → certified → published to PROD → live reading filed |
| Throughput | 613 msg/s single node idle (430 msg/s under concurrent load) |

## Defects found and fixed during verification

The harness, the integration tests and the security kit found real defects that were fixed before release: missing
plausibility ranges on 26 template fields (out-of-range values were filed); local codes dropped during normalisation;
leaf-only client certificate chains; X.509 key identifiers required by strict validators; harness probes able to see
live association context; ATS/ERS grading key; XT-07 coverage across several devices bound to the same template; an
XT-08 null pointer; revoked-certificate attempts attributed to the installation instead of the tenant; 500 instead of
405/415/400 responses.

## Known limitations (local scope)

* Device radios and ports (BLE, RS-232/USB), vendor databases and DICOM networking are represented by the gateway envelope,
  simulators and the DICOMweb JSON path; native agents and routers are deferred.
* Cloud environments (Azure, GCP, private cloud), HA/DR, cloud secret managers and signed container releases are deferred.
* The documentation assistant uses retrieval only; the LLM explanation layer is deferred.
* GLI-2012 predicted values are not computed (licensed coefficient tables); spirometry grading uses ATS/ERS repeatability.
* Codes marked `local` (INTIMED-*) are placeholders where a standard code was not confirmed; configuration intelligence flags them (RR-10) for clinical-informatics mapping.
