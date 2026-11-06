# IntiMed 2.1.0 – release notes (Azure Qatar Central)

## Highlights

- **Deployable to Azure Qatar Central.**
  - Terraform: network, AKS, PostgreSQL Flexible 16 with zone-redundant HA, Key Vault Premium (HSM), ACR Premium,
    immutable archive, Application Gateway WAF v2, Azure Firewall, site-to-site VPN, Log Analytics + Sentinel, Managed
    Prometheus, data-residency policy and a budget.
  - Helm chart and one-command scripts.
  - CI/CD with cosign-signed images and four-eyes promotion.
  - Step-by-step [Setup Guide](azure/Setup-Guide.md).
- **Cloud adapters in the product.**
  - Azure Key Vault secret store with Entra workload identity, so no keys are configured.
  - Write-once Blob archive of sealed evidence packs and the audit stream.
  - SIEM stream to Sentinel.
  - Configurable certificate SANs.
  - New `sdk` commands: `smoke`, `db-bootstrap` (least-privilege role), `phi-check`, `soak` (mTLS load generator).
- **Deferred backlog implemented.** Every item that software can deliver is now in, across three groups:
  - **Operations & commercial (L8):** regression suites, shadow sign-off, monthly executive reports, tenant health,
    billing export and reconciliation, simulator-only trials, white-label branding, k-anonymous benchmarks.
  - **Partners & integration (L6):** vendor submissions with four-eyes certification and badges, GUDID/EUDAMED feeds,
    the AI explanation gateway (off by default), the runtime OpenAPI with TypeScript/Python SDKs, and ASTM E1381 /
    POCT1-A2 codecs.
  - **Clinical intelligence 2.1:**
    - host demographics from ADT PID-7/8 and FHIR Patient;
    - derived values (eGFR CKD-EPI 2021, BMI, anion gap, corrected calcium; off by default);
    - laboratory interval governance with CLSI EP28 verification and four-eyes approval;
    - delta checks;
    - the drift alert workflow;
    - RR-17/RR-18 recommendations;
    - physiological trajectories with informational NEWS2;
    - a terminology-server adapter.
- **Manual remainder made explicit.** The [Manual Backlog Execution Guide](azure/Manual-Backlog-Execution-Guide.md)
  covers every backlog item that still needs people, hardware, vendors, licences, audits or regulator decisions, in 16
  sections (M-01…M-16) with steps, tooling and closing evidence.
- **Costs.** The [cost model](azure/IntiMed-Azure-Qatar-Cost-Model.xlsx) is built from live Qatar Central retail prices
  retrieved 2026-11-06. List prices: UAT about USD 1,627 per month, PROD about USD 5,949 per month. The workbook also
  has a 5-year TCO, reservation scenarios and cost per connected device.

## Backlog status (1.0 product backlog, 117 items)

| Status | 1.0 release | 2.1 release |
|---|---:|---:|
| DELIVERED | 55 | 78 |
| PARTIAL (software complete, manual remainder) | 43 | 30 |
| DEFERRED (outside this Azure release – path in the manual guide) | 19 | 9 |

The 2.0 enhancement backlog stands at 32 delivered, 2 partial and 3 deferred; the remaining items are covered by guide
sections M-12 and M-16.

## Verification

| Check | Result |
|---|---|
| Maven tests (unit, integration, architecture) | 134 / 134 pass |
| Terraform | `validate` clean; `terraform test` 4 / 4: UAT and PROD mocked apply, residency guard, phase-2 guard |
| Helm | `helm lint` clean; rendered manifests pass strict Kubernetes 1.30 schema validation |
| PostgreSQL 16 (postgres profile) | Flyway migration, 20/20 demo scenarios live, security kit 69/69, least-privilege role migrates and runs |
| New security-kit checks | 16 Azure posture: compliant fixture passes, violations detected. 05 container runtime: rendered chart passes, violations detected. 01 segmentation: 8/8 against a live instance |
| Console | All pages and tabs opened in a browser as four personas with no errors |

## Fixed

- Demo scenario summaries stored subject identifiers in clear text. With H2 this was hidden by whole-file encryption; on
  PostgreSQL it was visible. They are now pseudonymous references, and the security kit checks this on PostgreSQL.

## Known limitations

- **Single active pod.** Availability comes from zone-redundant storage and database HA; horizontal scale-out is future
  work.
- **Management port hang, seen once.** During testing the management port of one local instance stopped answering while
  the console kept working. It could not be reproduced. The probes tolerate transient failures (4 × 15 s), and an alert
  covers repeated restarts.
- **Not run against a real Azure subscription in this build.** Terraform and Helm were validated with mocked providers
  and schema checks. The first UAT run of the Setup Guide is the end-to-end acceptance.
