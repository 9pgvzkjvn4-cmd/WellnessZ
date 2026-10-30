# Security Assessment Attestation

**Version 1.0**

*WellnessZ OHCM platform – security assessment 2026-10*

| | |
|---|---|
| **Attestation ID** | WZ-SEC-ATT-2026-10 |
| **Date of issue** | 2026-10-30 |
| **System** | WellnessZ Airline Occupational Health Case Management (OHCM) |
| **Supporting documents** | [01 – Security Assessment Report](01-Security-Assessment-Report.md) · [02 – Summary](02-Security-Assessment-Summary.md) · [04 – Evidence Guide](04-Security-Test-Evidence-Guide.md) · evidence pack `evidence/` |

---

**Nature of this document.** This attestation is a statement of the security assessment work performed and of its
results. It is **not** a certification by an independent third party, **not** a SOC 2 report or HITRUST certification,
and **not** an assurance that the system is free of vulnerabilities.

## 1. What was assessed

| Item | Detail |
|---|---|
| Build | Assessment branch, commit `9228d13c194cc91bb0b35984c34b79e5088d8ac7` |
| Components | Core API and BFF (Java 21 / Spring Boot 4.1.2), employee app, manager workspace, OH workspace, partner portal (React 19), Helm chart, Terraform (Azure and Google Cloud), CI workflows, Dockerfiles |
| Date of testing | 2026-10-30 |
| Assessment type | Internal, white-box, pre-release application security assessment |
| Dynamic test target | Local test stack only, synthetic data; no cloud environment, no production data, no third-party system |
| Techniques | Source and bytecode static analysis; software composition analysis; secrets scanning; infrastructure-as-code review; 61 automated dynamic security checks; role-based access-control matrix (11,440 checks); functional regression (455 Java tests, 1,373 web tests, 121 end-to-end checks) |

## 2. Standards used

NIST SP 800-115 (test phases) · OWASP Web Security Testing Guide · OWASP ASVS 4.0.3 Level 2 · OWASP Top 10 2021 ·
OWASP API Security Top 10 2023 · CVSS 3.1 with a business-impact adjustment for special-category health data ·
AICPA SOC 2 Trust Services Criteria (Security, Availability, Processing Integrity, Confidentiality, Privacy) with a
HITRUST CSF cross-reference.

## 3. Results

| Statement | Value |
|---|---|
| Findings raised | **21** (High 1, Medium 4, Low 9, Informational 7) |
| Fixed and retested | **15** |
| Formally accepted with documented residual risk and owner | **4** (F-12, F-14, F-16, F-17) |
| False positives | **2** (F-13, F-15) |
| **Open findings at assessment close** | **Critical 0 · High 0 · Medium 0 · Low 0 · Informational 0** |
| Final scans | Semgrep 0; SpotBugs/FindSecBugs 0; OSV 0 of 219; npm audit 0 of 966; secrets 0; Checkov 0 failed (Terraform, Dockerfiles, workflows, 9 rendered Kubernetes overlays) |
| Final dynamic results | Verification suite 61/61 PASS; access matrix 11,440/11,440 PASS |

One regression (F-19) introduced during remediation was detected by the verification gate before release and fixed.

## 4. Conditions and limitations

1. Results apply only to the build, scope and date above. Cloud environments, the production identity provider (MFA),
   container image OS packages, mobile apps and third-party systems were **not** tested.
2. Trivy, OWASP ZAP and OWASP Dependency-Check could not be run in the assessment environment; equivalent checks were
   used as described in Section 14 of the report.
3. Production go-live is conditional on:
   - **G1** an independent CREST- or OSCP-certified penetration test of the deployed UAT environment, with no Critical
     or High finding open and a retest letter on file;
   - **G2** container-scan and SBOM evidence for the release digests;
   - **G3** signed-image admission on the PROD cluster;
   - **G4** removal of demo identities and local-only secrets, real named clinicians configured, and the migration image
     digest set;
   - **G5** the customer-managed-key decision for Azure PostgreSQL;
   - **G6** patch SLAs in force.

## 5. Validity

This attestation is valid **for commit `9228d13` only**. It must be renewed by re-assessment after any material change:
a new increment or release, a change to authentication, authorisation, cryptography or data flows, a new external
integration, a new hosting environment, or a Critical/High advisory affecting a shipped component. It does not replace
the independent penetration test required before production.

## 6. Signatures

**Assessor** – I confirm that the work described above was performed and that the results are reported accurately.

| Role | Name | Signature | Date |
|---|---|---|---|
| Engineering security lead (Assessor) | | | |

**Client acceptance** – We have reviewed the assessment results, accept the residual risks of the accepted items and
note the go-live conditions.

| Role | Name | Signature | Date |
|---|---|---|---|
| Chief Information Security Officer (CISO) | | | |
| Cyber GRC lead | | | |
| Chief Information Officer (CIO) | | | |

*End of attestation.*
