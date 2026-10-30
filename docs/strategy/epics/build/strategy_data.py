# -*- coding: utf-8 -*-
"""Single source for the WellnessZ strategy epic catalogue (E20 onwards) and the catalogue workbook.

The epic content itself lives in epics_h1.py (E20-E23, E26 – mostly horizon 1 starts) and epics_h23.py (the rest).
Story points are indicative relative sizes; team-sprints are derived with an indicative velocity (VELOCITY).
"""
from epics_h1 import EPICS_A
from epics_h23 import EPICS_B

DOC_DATE = "30 October 2026"
DOC_LINE = ("Airline Group Occupational Health Case Management (OHCM) | v1.0 – Strategy beyond increment 5, draft for "
            "C-suite, CIO, CMO / Nursing Head and Cyber GRC review | " + DOC_DATE)

# Indicative delivery velocity of one cross-functional product team (6–8 people) per two-week sprint.
VELOCITY = 30

EPICS = sorted(EPICS_A + EPICS_B, key=lambda e: e["id"])

ALL = "ALL – All subsidiaries & departments (group-wide)"

# ----------------------------------------------------------------------------------------------- existing catalogue
# (id, name, status, delivered by, evidence / notes, items traced, implemented, sign-off, partial, horizon for completion)
EXISTING = [
    ("E01", "Case Intake & Incident-Triggered Case Initiation", "Done", "Increment 1",
     "Module wz-e01-intake. Partial items: SMS / push channels, offline walk-in, export; triage and recordability content awaiting CMO / HSE sign-off.",
     42, 26, 0, 16, "H1 (sign-offs, E15 feeds)"),
    ("E02", "Clinical Triage, Risk Stratification & Case Assignment", "Done", "Increment 1",
     "Module wz-e02-triage. 13 triage protocols DRAFT (CREW_FATIGUE and BODY_FLUID without red-flag question); SLA in elapsed minutes until the E18 business calendar exists.",
     32, 26, 0, 6, "H1 (sign-offs, E18 calendar)"),
    ("E03", "Occupational Health Record & Clinical Encounter Documentation", "Done", "Increment 2",
     "Module wz-e03-record. Partial: OCR of image-only scans, device drivers, terminology server and licences, assistant provider (extension point only, no provider).",
     38, 21, 0, 17, "H1 sign-offs; H2 via E23 / E28"),
    ("E04", "Injury & Illness Management, Treatment Coordination & Partner Referrals", "Done", "Increment 4",
     "Module wz-e04-treatment. FHIR exchange tested with a simulated partner only; telehealth video not procured; e-prescribing no-op pending decision memo.",
     33, 24, 0, 9, "H1 (E15 connectors, D15, D19)"),
    ("E05", "Fitness for Duty, Work Restrictions & Return-to-Work", "Done", "Increment 2",
     "Module wz-e05-fitness. 12 fitness standards and 28 of 150 job-demand profiles await validation; rostering transport not built; RTW training deferred.",
     35, 28, 0, 6, "H1 (sign-offs, rostering transport)"),
    ("E06", "Absence & Sickness Management", "Done", "Increment 3",
     "Module wz-e06-absence. AbsenceFeedConnector is a no-op (push endpoint ready); HRIS / time & attendance / crew connectors outstanding.",
     26, 20, 0, 6, "H1 (E15 connectors)"),
    ("E07", "Medical Surveillance, Periodic & Statutory Medicals", "Done", "Increment 3",
     "Module wz-e07-surveillance. 13 programmes await CMO validation; CAA examination formats pending; bulk invitation not load-tested.",
     36, 31, 0, 5, "H1 sign-offs; H2 via E24"),
    ("E08", "Workers' Compensation, Insurance & Claims Coordination", "Done", "Increment 4",
     "Module wz-e08-claims. Insurer / authority / ERP connectors default to outbox + content-free e-mail; report forms await Legal / HSE sign-off.",
     24, 19, 0, 5, "H1 (E15 connectors, sign-offs)"),
    ("E09", "Mental Health, Psychosocial Risk & Peer Support Programmes", "Done", "Increment 5",
     "Module wz-e09-mentalhealth. Screening bands, urgent-risk protocol and signposting await sign-off; named-clinician seed (psych1) is a go-live item; EAP system API not connected.",
     27, 21, 5, 1, "H1 (sign-offs, go-live item)"),
    ("E10", "Drug, Alcohol & Psychoactive Substance Testing Programme", "Done", "Increment 5",
     "Module wz-e10-substance. Toxicology LIMS simulated (bound client ready); policy matrix PENDING; MRO document access via /documents outstanding (SEC-E05).",
     20, 14, 3, 3, "H1 (LIMS, MRO, sign-offs)"),
    ("E11", "Travel Health, Immunisation Campaigns & Communicable Disease / Exposure Management", "Done", "Increment 5",
     "Module wz-e11-travelhealth. Destination content source not licensed; protocol timers await sign-off; public-health authority submission by outbox + e-mail.",
     25, 17, 6, 2, "H1 (sign-offs, authority connector)"),
    ("E12", "Health Promotion, Wellbeing & Prevention Campaigns", "Done", "Increment 5",
     "Module wz-e12-promotion. Campaign app and wearable aggregator simulated; no ROI metric; absence not an insight source; hearing / vision results not captured in E12.",
     18, 10, 2, 6, "H1 connectors; H2 via E26"),
    ("E13", "Employee & Manager Portal / App (self-service)", "Partial", "Increment 0, extended in 1–5",
     "Employee / crew portal as web, PWA and Capacitor shell; manager workspace; partner portal. Gaps: secure-storage and MAM plug-ins (R6), no offline, no native NFC, no push, Arabic only on employee screens.",
     None, None, None, None, "H1 (store-ready) → E21 / E22"),
    ("E14", "Scheduling, Clinic Operations & Resource Management", "Done", "Increment 3",
     "Module wz-e14-scheduling. IoT cold-chain gateway and telehealth video not connected; campaign booking and capacity not load-tested.",
     20, 18, 0, 2, "H1 (D15, D16, load test)"),
    ("E15", "Integration & API Gateway", "Partial", "Increment 0, extended in 1–5",
     "Outbox, idempotency, FHIR R4 / HL7 v2 inbound, outbound allow-list. Gaps: production broker adapter (D3) and real connectors – HRIS, rostering, EHS transport, LIMS, insurer / TPA, authority, ERP, EAP, campaign app, wearables, public-health authority, SMS.",
     None, None, None, None, "H1 (production grade)"),
    ("E16", "Identity, Access, Consent & Audit", "Partial", "Increment 0, extended in 1–5",
     "Multi-issuer OIDC, ABAC, consent, hash-chained audit, MFA gate, restricted classes. Gaps: MFA enrolment in PROD IdP, envelope encryption + re-encryption job (D5), audit anchoring (O2), automatic deletion of OH records (D6).",
     None, None, None, None, "H1 (production grade)"),
    ("E17", "Analytics & Reporting (de-identified)", "Partial", "Increment 0, extended in 1–5",
     "De-identified view, KPI API with k = 5 suppression, per-module suppressed reports. Gaps: warehouse feed, read-only reporting role (O7), several client KPIs not computed (RTW within 5 days, documentation time, ROI, litigated-claim trend).",
     None, None, None, None, "H1 completion → E26"),
    ("E18", "Master Data & No-Code Configuration", "Partial", "Increment 0, extended in 1–5",
     "Versioned rules, protocols, templates and configuration API. Gaps: business / site calendar (O10), OH staff roster API or feed (O9), maker-checker for configuration changes, feature-flag governance (O34).",
     None, None, None, None, "H1 (production grade)"),
    ("E19", "Security Hardening & Assurance (drafted as 'E15', renumbered)", "Planned", "Drafted Oct 2026 (security assessment)",
     "17 HLRs, 17 features, 46 backlog items, 256 indicative SP (docs/security/epics). Go-live conditions G1–G6: pen test, scan evidence, AKS admission, demo-identity guard, CMK decision, patch SLAs.",
     None, None, None, None, "H1 (Now / Next), H2 (Later)"),
]

# Completion effort of the existing platform epics in H1 (indicative team-sprints; not in a new workbook).
PLATFORM_COMPLETION = [
    # (id, scope summary, team-sprints, team, horizon)
    ("E13", "Store-ready mobile build (secure storage, MAM SDK registration), status-only screens for crew scheduling and hygiene manager, per-user channel preferences", 4, "T3 Experience & Mobile", "H1"),
    ("E15", "Broker adapter (D3) and 12 production connectors: HRIS, rostering / duty feed, EHS transport, LIMS, insurer / TPA FNOL, authority e-reporting, ERP, EAP, campaign app, wearable aggregator, public-health authority, SMS gateway", 22, "T4 Integration & Interoperability", "H1"),
    ("E16", "MFA enrolment evidence, envelope encryption + re-encryption job, audit hash anchoring and SIEM export, retention purge jobs for OH records after DPO approval", 7, "T5 Platform & SRE", "H1"),
    ("E17", "Read-only reporting role, outstanding client KPIs on the current KPI API, warehouse feed groundwork shared with E26", 4, "T6 Data & Analytics", "H1"),
    ("E18", "Business / site calendar, OH staff roster feed, maker-checker on configuration changes, feature-flag governance", 5, "T5 Platform & SRE", "H1"),
    ("E19", "Security Hardening & Assurance – 46 backlog items, 256 indicative SP (Now / Next in H1, Later in H2)", 9, "T5 Platform & SRE + security champions", "H1–H2"),
]

TEAMS = [
    ("T1", "Clinical Care", "E01–E05, E08, E24, E28", "Product owner (OH), clinical safety officer liaison, 5–6 engineers, QA"),
    ("T2", "Workforce Health & Prevention", "E06, E07, E09–E12, E14, E25, E31", "Product owner (prevention), 5–6 engineers, QA"),
    ("T3", "Experience & Mobile", "E13, E21, E22", "Product owner (experience), UX / accessibility lead, web + mobile engineers"),
    ("T4", "Integration & Interoperability", "E15, E23", "Integration architect, 4–5 engineers, interface analyst"),
    ("T5", "Platform & SRE", "E16, E18, E19, E20, E29", "Platform lead, SREs, security champion, DBA"),
    ("T6", "Data & Analytics", "E17, E26, E27, E30", "Data product owner, data engineers, data scientist, privacy engineer"),
]

# KPI tree: (level, id, parent, KPI, target, owner, source epics)
KPI_TREE = [
    ("North star", "NS", "", "Every safety-critical employee fit, supported and back at work safely – with health data that never leaks", "–", "CMO + CIO", "All"),
    ("Outcome", "O1", "NS", "Lost workdays per lost-time case", "−25 % in 12 months (E05 KPI; illustrative business case)", "CMO / Nursing Head", "E05, E06, E27"),
    ("Outcome", "O2", "NS", "Lost-time claim cost per case", "−15 % in 18 months (E08 KPI; illustrative)", "Claims lead / Finance", "E04, E08, E26"),
    ("Outcome", "O3", "NS", "Crew medical certificate lapses", "0 (E07 KPI)", "AME lead / Crew licensing", "E07, E24, E30"),
    ("Outcome", "O4", "NS", "Platform availability (PROD, measured SLO)", "≥ 99.99 % per month", "CIO / Head of SRE", "E19, E20"),
    ("Outcome", "O5", "NS", "Disclosure or residency incidents involving health data", "0", "DPO / CISO", "E16, E19, E26, E29"),
    ("Outcome", "O6", "NS", "Employee trust and reach", "≥ 90 % awareness of confidential pathways; app adoption ≥ 70 % of crew", "Nursing Head / HR", "E09, E13, E21, E22"),
    ("Driver", "D1.1", "O1", "% long-term absences (> 4 weeks) with an OH case", "≥ 95 % (E06 KPI)", "Nursing Head", "E06, E15"),
    ("Driver", "D1.2", "O1", "% RTW plans started within 5 days of fitness decision", "≥ 90 % (E05 KPI)", "Nursing Head", "E05, E17"),
    ("Driver", "D1.3", "O1", "Early-intervention contacts generated by governed risk signals and accepted by clinicians", "≥ 70 % rated useful in pilot", "CMO", "E27"),
    ("Driver", "D2.1", "O2", "FROI / statutory submissions within deadline", "100 % (E08 KPI)", "Claims lead", "E08, E15"),
    ("Driver", "D2.2", "O2", "Median referral-to-appointment time", "< 5 working days (E04 KPI)", "Case management", "E04, E23"),
    ("Driver", "D3.1", "O3", "Regulator submissions within the authority's deadline", "≥ 98 %", "AME lead", "E24"),
    ("Driver", "D3.2", "O3", "Fitness status reaching rostering within 15 minutes", "100 % (E05 KPI)", "Crew scheduling", "E05, E15, E25"),
    ("Driver", "D4.1", "O4", "Region failover proven in drill (RTO / RPO)", "≤ 15 min / ≤ 5 min, every quarter", "Head of SRE", "E20"),
    ("Driver", "D4.2", "O4", "Change failure rate", "≤ 10 %", "Engineering leads", "E20, E19"),
    ("Driver", "D5.1", "O5", "Critical / High vulnerabilities open beyond SLA", "0", "CISO", "E19"),
    ("Driver", "D5.2", "O5", "Re-identification incidents from analytics or research datasets", "0", "DPO", "E26, E27"),
    ("Driver", "D6.1", "O6", "WCAG 2.2 AA critical / serious issues open", "0 across the four web apps and the mobile app", "Head of Experience", "E21"),
    ("Driver", "D6.2", "O6", "Employee-facing screens available in Arabic (RTL) and top workforce languages", "100 % Arabic; ≥ 95 % top-5 languages", "Head of Experience", "E21"),
    ("Driver", "D6.3", "O6", "Documentation time per encounter (frees clinician time for people)", "−30 % vs baseline (E03 KPI)", "CMO", "E03, E28"),
]

TEAM_OF = {}
for _eid in ("E01", "E02", "E03", "E04", "E05", "E08"):
    TEAM_OF[_eid] = "T1 Clinical Care"
for _eid in ("E06", "E07", "E09", "E10", "E11", "E12", "E14"):
    TEAM_OF[_eid] = "T2 Workforce Health & Prevention"
TEAM_OF.update({"E13": "T3 Experience & Mobile", "E15": "T4 Integration & Interoperability", "E16": "T5 Platform & SRE",
                "E17": "T6 Data & Analytics", "E18": "T5 Platform & SRE", "E19": "T5 Platform & SRE"})
