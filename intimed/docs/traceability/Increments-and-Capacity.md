# IntiMed – Incremental delivery and capacity model (release 2.1)

The requirement pack plans 17 two-week sprints (IM-S0…IM-S16+, ≈1,395 SP). The platform was built in eight increments that follow the sprint order and the traceability above (L1–L5 local release, L6–L8 backlog follow-up and Azure Qatar Central); the capacity table compares the pack's planned story points per sprint with what is delivered.

## Increments

| Increment | Scope | Sprints covered |
|---|---|---|
| L1 | Foundation – engine core, security baseline, tenancy, MacBook kit | IM-S0, IM-S1 |
| L2 | Universal catalogue, driver registry, guided provisioning console & governance | IM-S2, IM-S3 |
| L3 | Configuration intelligence, exchange harness & template framework | IM-S4 |
| L4 | Device families (26 drivers, P1–P11), APIs, capture experience, anomaly isolation | IM-S5 … IM-S12 (local subsets) |
| L5 | Hardening: security test kit, documentation, demo pack, videos | IM-S13 … IM-S15 (local subsets) |
| L6 | Backlog follow-up: partner programme & badges, UDI register feeds, AI policy gateway, OpenAPI + SDKs, lab protocol codecs | IM-S2, IM-S7, IM-S8, IM-S9, IM-S16 (local subsets) |
| L7 | Azure Qatar Central: Terraform + Helm, Key Vault, PostgreSQL HA, WORM archive, CI/CD with signed images, observability, DR drills, cloud security kit, cost/TCO | IM-S1 cloud track, IM-S13, IM-S14 |
| L8 | Operations & commercial: regression suites, shadow sign-off, executive reports, health scores, billing, trials, white-label, k-anonymous benchmarks | IM-S8, IM-S13, IM-S15, IM-S16+ |

## Capacity by sprint (story points)

| Sprint | Theme | Planned SP | Delivered | Partial | Deferred | Local coverage* |
|---|---|---:|---:|---:|---:|---:|
| IM-S0 | Product inception & architecture | 26 | 26 | 0 | 0 | 100 % |
| IM-S1 | Core pipeline, simulators, MacBook kit, Azure Dev & security baseline | 225 | 178 | 47 | 0 | 90 % |
| IM-S2 | Universal catalogue, seed inventory & register feeds | 65 | 65 | 0 | 0 | 100 % |
| IM-S3 | Guided provisioning console & option packs | 102 | 102 | 0 | 0 | 100 % |
| IM-S4 | Configuration intelligence & exchange harness v1 | 170 | 157 | 13 | 0 | 96 % |
| IM-S5 | Acute-care monitoring & telemetry + flowsheet templates | 168 | 100 | 68 | 0 | 80 % |
| IM-S6 | Infusion, ventilation & anaesthesia; bidirectional workflows | 21 | 8 | 13 | 0 | 69 % |
| IM-S7 | Outpatient & occupational-health instruments (OHCM reference integration) | 172 | 31 | 128 | 13 | 55 % |
| IM-S8 | Remote patient monitoring, wearables, home devices & cloud APIs | 84 | 42 | 34 | 8 | 70 % |
| IM-S9 | Laboratory analysers, POCT & middleware | 26 | 0 | 26 | 0 | 50 % |
| IM-S10 | Cardiology diagnostics & implant remote monitoring | 21 | 8 | 13 | 0 | 69 % |
| IM-S11 | Imaging & PACS | 21 | 0 | 21 | 0 | 50 % |
| IM-S12 | Specialist units – dialysis, neonatal, OR, neuro/sleep, beds | 26 | 13 | 13 | 0 | 75 % |
| IM-S13 | GCP & private-cloud blueprints, HA/DR, UAT/Prod pipelines, hosting TCO | 112 | 57 | 8 | 47 | 54 % |
| IM-S14 | Security hardening, test kit on all clouds, pen test, assurance & regulatory files | 55 | 18 | 21 | 16 | 52 % |
| IM-S15 | Pilot, hypercare & general availability | 31 | 21 | 0 | 10 | 68 % |
| IM-S16+ | Scale – marketplace, partner certification, OEM, analytics | 70 | 70 | 0 | 0 | 100 % |
| **Total** | | **1395** | **896** | **405** | **94** | **79 %** |

\* Coverage counts PARTIAL items at 50 % – a planning convention, not a measurement.

## Capacity model for the local environment (IM-E01-BL-030 local subset)

| Measure | Requirement (NFR §14) | Local evidence |
|---|---|---|
| Spot result end-to-end | ≤ 3 s p95 | XT-10 per exchange test: typically 10–60 ms per message over loopback mTLS (see run evidence timings) |
| Streaming interval | ≤ 1 s | XT-10 continuous templates ≤ 1000 ms per message |
| Throughput per tenant | 500 msg/s with horizontal scaling | Token-bucket quota per tenant (500/s, burst 1000) and per endpoint (50/s); single-node local measurement documented in `docs/operations/Capacity-Model.md` |
| Harness run single device | ≤ 10 min | 1–3 s per run with simulators (20+ runs during demo autoprovision in ~2 min) |
| MacBook bootstrap | < 30 min | Demo pack: double-click launcher, first start < 1 min; code base: `./mvnw package` + run |
