# WellnessZ epic catalogue – strategy epics E20–E31

**Version 1.0** · 30 October 2026 · companion to the [Platform Enhancement Strategy](../Platform-Enhancement-Strategy.md)

This folder holds the proposed epic catalogue beyond increment 5. Each new epic has a workbook in the client's epic format (same sheets, columns, review and decision dropdowns as `E09.xlsx`): *Epic Overview*, *High-Level Requirements*, *Feature List*, *Implementation Backlog* and *Legends*. The overview carries an extra row, *Evidence base (why now)*, that points to the release notes, traceability matrices and architecture open items the epic responds to.

> Nothing in an E20+ workbook exists in the product today. Story points are indicative first-cut sizes; team-sprints apply an indicative velocity of 30 points per team per sprint and an uncertainty allowance (×1.5 H1, ×2.0 H2, ×2.5 H3). Benefits and KPI targets are indicative and must be baselined by their owners.

## 1. Numbering

| Range | Meaning |
|---|---|
| E01–E12, E14 | Client business epics – delivered in increments 1–5 (status *Done*, with sign-offs and connectors outstanding) |
| E13, E15–E18 | Client platform epics (portal / app, integration, identity and security, analytics, master data and configuration) – partly covered by the base framework (status *Partial*), completed to production grade in H1 |
| E19 | Security Hardening & Assurance – drafted as "E15" in `docs/security/epics/`; renumbered because E15 is the client's Integration epic. Only the ID prefix changes |
| E20–E31 | New strategy epics proposed here (status *Planned*) |

## 2. New epics

| Epic | Name | Goal (one line) | Horizon | Team | HLR / F / BL | SP | Team-sprints | Workbook |
|---|---|---|---|---|---|---|---|---|
| **E20** | Resilience & Tier-0 Operations (SRE) | Run PROD as a Tier-0 service at 99.99 % inside one jurisdiction, with failover, SLOs and drills proven, not assumed. | H1–H2 | T5 Platform & SRE | 6 / 6 / 15 | 107 | 6 | [E20_Resilience_and_Tier0_Operations.xlsx](E20_Resilience_and_Tier0_Operations.xlsx) |
| **E21** | Accessibility & Internationalisation | WCAG 2.2 AA everywhere and full Arabic (RTL) plus the workforce's main languages across all apps and documents. | H1–H2 | T3 Experience & Mobile | 5 / 6 / 12 | 79 | 4 | [E21_Accessibility_and_Internationalisation.xlsx](E21_Accessibility_and_Internationalisation.xlsx) |
| **E22** | Native Mobile, Offline & Field Capability | A store- and MDM-ready crew and field app that works securely without network: offline first-aid capture, NFC, push and a certificate wallet. | H1–H2 | T3 Experience & Mobile | 5 / 5 / 12 | 75 | 4 | [E22_Native_Mobile_and_Offline.xlsx](E22_Native_Mobile_and_Offline.xlsx) |
| **E23** | Interoperability Hub (FHIR, IHE, HL7 v2, national records) | Standards-based exchange with hospitals, labs, regulators and national health records – no more e-mail for clinical data. | H1–H3 | T4 Integration & Interoperability | 6 / 6 / 12 | 100 | 7 | [E23_Interoperability_Hub.xlsx](E23_Interoperability_Hub.xlsx) |
| **E24** | Aviation Medical Certification & Regulator Interface | End-to-end AME certification with qualified signatures and electronic submission to the aviation authority's medical system. | H1–H2 | T1 Clinical Care | 5 / 5 / 11 | 63 | 5 | [E24_Aviation_Medical_Certification_and_Regulator_Interface.xlsx](E24_Aviation_Medical_Certification_and_Regulator_Interface.xlsx) |
| **E25** | Fatigue Risk & Shift-Work Health (FRMS integration) | Connect occupational health with the airline's FRMS and rostering – health-related fatigue handled clinically, insight shared only in aggregate. | H2 | T2 Workforce Health & Prevention | 5 / 5 / 11 | 47 | 4 | [E25_Fatigue_Risk_and_Shift_Work_Health.xlsx](E25_Fatigue_Risk_and_Shift_Work_Health.xlsx) |
| **E26** | Data Platform & Research-Grade De-identified Datasets | An in-country, governed analytics platform that finally reports every client KPI and offers de-identified datasets safely. | H1–H2 | T6 Data & Analytics | 5 / 5 / 13 | 85 | 5 | [E26_Data_Platform_and_Deidentified_Datasets.xlsx](E26_Data_Platform_and_Deidentified_Datasets.xlsx) |
| **E27** | Predictive Health & Absence Analytics (governed) | Advisory early-intervention signals for OH clinicians, under clinical-safety, bias and AI governance – never automated decisions. | H2–H3 | T6 Data & Analytics | 6 / 6 / 12 | 68 | 5 | [E27_Predictive_Health_and_Absence_Analytics.xlsx](E27_Predictive_Health_and_Absence_Analytics.xlsx) |
| **E28** | Clinical Documentation Assistant (governed) | Switch on the existing assistant extension point with an in-country, governed provider – drafts that clinicians always review and sign. | H1–H2 | T1 Clinical Care | 5 / 5 / 12 | 61 | 5 | [E28_Clinical_Documentation_Assistant.xlsx](E28_Clinical_Documentation_Assistant.xlsx) |
| **E29** | Multi-Subsidiary Tenancy, Onboarding & Extension Platform | Onboard a new subsidiary or client in weeks, with configurable tenancy, white-label branding and signed extensions instead of forks. | H2–H3 | T5 Platform & SRE | 6 / 6 / 11 | 81 | 6 | [E29_Multi_Tenant_Subsidiary_Onboarding_and_Extensions.xlsx](E29_Multi_Tenant_Subsidiary_Onboarding_and_Extensions.xlsx) |
| **E30** | Workforce Health Digital Twin & Capacity Planning | An aggregate, de-identified model of workforce health readiness per base and fleet, for scenario planning and OH capacity. | H3 | T6 Data & Analytics | 4 / 4 / 10 | 59 | 5 | [E30_Workforce_Health_Digital_Twin_and_Capacity_Planning.xlsx](E30_Workforce_Health_Digital_Twin_and_Capacity_Planning.xlsx) |
| **E31** | Employee Wellbeing Marketplace & Benefits Integration | A curated, integration-led marketplace of wellbeing services in the employee app – eligibility from benefits systems, no OH data to vendors. | H3 | T2 Workforce Health & Prevention | 5 / 5 / 10 | 46 | 4 | [E31_Wellbeing_Marketplace_and_Benefits_Integration.xlsx](E31_Wellbeing_Marketplace_and_Benefits_Integration.xlsx) |
| | **Total** | | | | 63 / 64 / 141 | **871** | **60** | |

## 3. Status of the existing catalogue

| Epic | Name | Status | Delivered by | Items traced | Implemented | Sign-off pending | Partial |
|---|---|---|---|---|---|---|---|
| E01 | Case Intake & Incident-Triggered Case Initiation | Done | Increment 1 | 42 | 26 | 0 | 16 |
| E02 | Clinical Triage, Risk Stratification & Case Assignment | Done | Increment 1 | 32 | 26 | 0 | 6 |
| E03 | Occupational Health Record & Clinical Encounter Documentation | Done | Increment 2 | 38 | 21 | 0 | 17 |
| E04 | Injury & Illness Management, Treatment Coordination & Partner Referrals | Done | Increment 4 | 33 | 24 | 0 | 9 |
| E05 | Fitness for Duty, Work Restrictions & Return-to-Work | Done | Increment 2 | 35 | 28 | 0 | 6 |
| E06 | Absence & Sickness Management | Done | Increment 3 | 26 | 20 | 0 | 6 |
| E07 | Medical Surveillance, Periodic & Statutory Medicals | Done | Increment 3 | 36 | 31 | 0 | 5 |
| E08 | Workers' Compensation, Insurance & Claims Coordination | Done | Increment 4 | 24 | 19 | 0 | 5 |
| E09 | Mental Health, Psychosocial Risk & Peer Support Programmes | Done | Increment 5 | 27 | 21 | 5 | 1 |
| E10 | Drug, Alcohol & Psychoactive Substance Testing Programme | Done | Increment 5 | 20 | 14 | 3 | 3 |
| E11 | Travel Health, Immunisation Campaigns & Communicable Disease / Exposure Management | Done | Increment 5 | 25 | 17 | 6 | 2 |
| E12 | Health Promotion, Wellbeing & Prevention Campaigns | Done | Increment 5 | 18 | 10 | 2 | 6 |
| E13 | Employee & Manager Portal / App (self-service) | Partial | Increment 0, extended in 1–5 | – | – | – | – |
| E14 | Scheduling, Clinic Operations & Resource Management | Done | Increment 3 | 20 | 18 | 0 | 2 |
| E15 | Integration & API Gateway | Partial | Increment 0, extended in 1–5 | – | – | – | – |
| E16 | Identity, Access, Consent & Audit | Partial | Increment 0, extended in 1–5 | – | – | – | – |
| E17 | Analytics & Reporting (de-identified) | Partial | Increment 0, extended in 1–5 | – | – | – | – |
| E18 | Master Data & No-Code Configuration | Partial | Increment 0, extended in 1–5 | – | – | – | – |
| E19 | Security Hardening & Assurance (drafted as 'E15', renumbered) | Planned | Drafted Oct 2026 (security assessment) | – | – | – | – |

Counts come from the requirements traceability matrices of increments 1–5 (document 02 of each increment). *Sign-off pending* is used by increment 5 only; earlier increments count such items as partial. E05 also has one deferred training item.

## 4. Catalogue and roadmap workbook

[Epic-Catalogue-and-Roadmap.xlsx](Epic-Catalogue-and-Roadmap.xlsx) has five sheets:

| Sheet | Content |
|---|---|
| Catalogue | All epics E01–E31 with category, status (Done / Partial / Planned), increment or horizon, traced-item counts, evidence and gaps, team, indicative SP and workbook |
| Horizon roadmap | 12 quarters from November 2026: go-live gates, platform-epic completion, E19 and E20–E31 with milestones |
| Dependencies | 37 predecessor → successor links, hard or soft, with the reason |
| Capacity plan | Indicative team-sprints per epic and quarter, funded teams (6 → 5 → 4), reserve for run work, headroom and utilisation (formulas) |
| KPI tree | North star, six outcomes and fifteen drivers with indicative targets, owners and contributing epics |

## 5. Rebuilding

The workbooks and this README are generated from one data source. From `docs/strategy/epics/build/`:

```bash
python3 -B build_epics.py ..       # E20–E31 workbooks
python3 -B build_catalogue.py ..   # Epic-Catalogue-and-Roadmap.xlsx
python3 -B build_readme.py ..      # this README
```

| File | Purpose |
|---|---|
| `epics_h1.py`, `epics_h23.py` | Epic content (overview, HLRs, features, backlog) |
| `strategy_data.py` | Existing catalogue status, platform-epic completion effort, teams, KPI tree, velocity |
| `wb_style.py` | Shared workbook styling (client layout) |
| `build_epics.py`, `build_catalogue.py`, `build_readme.py` | Generators (requires `openpyxl`) |

The builders reuse the layout code of the E15 / E19 security epic builder. Formulas (summary metrics, feature lookups, capacity and utilisation) are calculated when the workbook is opened in Excel or LibreOffice.
