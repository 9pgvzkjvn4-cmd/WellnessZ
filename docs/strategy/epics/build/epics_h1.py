# -*- coding: utf-8 -*-
"""Epic content E20, E21, E22, E23, E26 (horizon-1 starts and the interoperability hub).

Tuples:
  HLR      (id, requirement, rationale, MoSCoW, standards, applicable units)
  FEATURE  (id, name, description, primary roles, MoSCoW, traces to HLR, acceptance criteria, unit-specific notes)
  BACKLOG  (feature id, type, title / user story, acceptance criteria, story points, horizon, priority, owner, dependencies)
"""

ALL = "ALL – All subsidiaries & departments (group-wide)"
CREW = "FLT – Flight Operations; CAB – Cabin Services"
FIELD = "GND – Ground Services; CGO – Cargo & Freight; CAT – Catering; ENG – Engineering & Maintenance; SEC – Security, Fire & Rescue"


def epic(**kw):
    kw["overview"] = [
        ("Epic ID", kw["id"]),
        ("Epic name", kw["name"]),
        ("Goal", kw["goal"]),
        ("Business value", kw["value"]),
        ("In scope", kw["in_scope"]),
        ("Out of scope", kw["out_scope"]),
        ("Primary stakeholders / roles", kw["stakeholders"]),
        ("Success KPIs", kw["kpis"]),
        ("Dependencies", kw["dependencies"]),
        ("Standards & references", kw["standards"]),
        ("Evidence base (why now)", kw["evidence"]),
        ("Recommended start horizon", kw["start"]),
    ]
    return kw


EPICS_A = []

# ======================================================================================================== E20
EPICS_A.append(epic(
    id="E20", slug="Resilience_and_Tier0_Operations", name="Resilience & Tier-0 Operations (SRE)",
    horizon="H1", span="H1–H2", team="T5 Platform & SRE", start_q=1, end_q=4,
    deps=["E15", "E16", "E19"],
    one_line="Run PROD as a Tier-0 service at 99.99 % inside one jurisdiction, with failover, SLOs and drills proven, not assumed.",
    goal="Operate the WellnessZ production service as a Tier-0 system at 99.99 % availability inside a single jurisdiction: a two-region "
         "in-country deployment with automated failover, service-level objectives and error budgets per critical journey, "
         "production-volume performance evidence, chaos and disaster-recovery drills, zero-downtime change and an on-call "
         "operating model with runbooks and post-incident learning.",
    value="Closes the largest operational risks in the GRC register (G9 service unavailability, R8 single region, R9 audit "
          "throughput ceiling, R36 pipeline never run against a real cloud). Fitness-for-duty status reaches rostering "
          "even during a regional outage, so safety-critical rostering never works from stale data for long. 99.99 % "
          "allows about 52 minutes of downtime a year – this only holds if failover is automated and rehearsed.",
    in_scope="• Two-region in-country Azure deployment (primary + warm standby), allowed-locations policy, DNS-only traffic failover\n"
             "• SLOs, error budgets and multi-window burn-rate alerts as code for the critical journeys\n"
             "• Production-volume load, soak and capacity tests; capacity model per environment\n"
             "• Chaos experiments and quarterly DR drills (zone, region, database, broker, identity provider, ransomware restore)\n"
             "• Zero-downtime migrations (expand / contract), progressive delivery, feature-flag dark launches\n"
             "• Observability: golden signals, synthetic probes per region, on-call, runbooks, post-incident reviews; BCP manual fallback",
    out_scope="• Enterprise SOC / SIEM operation (E19 and client SOC)\n• Commercial hosting decisions already taken in the Tier-0 decision pack\n• Business continuity of non-IT processes beyond the OH manual fallback",
    stakeholders="CIO; Head of SRE / Platform; Platform & SRE team; DBA; Network; Crew scheduling (consumer of fitness status); CMO (BCP fallback); Cyber GRC; Release manager",
    kpis="• Availability of PROD (measured SLO) ≥ 99.99 % per calendar month\n"
         "• Region failover proven every quarter: RTO ≤ 15 min, RPO ≤ 5 min (Tier-0 decision pack targets)\n"
         "• Quarterly isolated restore test pass rate 100 %\n"
         "• p95 API latency ≤ 400 ms at 1.5 × forecast peak; 0 SLO breaches caused by capacity\n"
         "• Change failure rate ≤ 10 %; mean time to restore (P1) ≤ 30 min\n"
         "• 0 schema migrations that require downtime",
    dependencies="E15 Integration (broker adapter D3, rostering transport); E16 Identity & security (customer-managed keys, envelope encryption); E19 Security (SEC-E03 admission, SEC-E04 private control plane, SEC-E09 CMK); decision D1 / D9 and the Tier-0 cost decision pack",
    standards="• SOC2 A1.2 / A1.3 availability and recovery\n• ISO22301 business continuity; ISO27001 A.5.30, A.8.13, A.8.14\n• SRE: service-level objectives, error budgets, multi-window burn-rate alerting\n• CIS-K8S benchmarks for AKS",
    evidence="Architecture doc §12: 'Region failure – not automated in the current IaC (single region)'; release notes 3–5: bulk invitation, "
             "50K-staff vaccination campaign, tracing over large rosters, worklists and exports not load-tested at production volume; "
             "R9 audit advisory lock throughput ceiling 'to be load-tested'; R36 promotion pipeline not yet executed against real clouds.",
    start="H1 – Go-live readiness (months 0–6) for two-region, SLOs, load tests and the first DR drill; H2 for chaos automation and progressive delivery",
    hlrs=[
        ("E20-HLR-01", "The production service shall run in two regions of the same jurisdiction (primary and warm standby) with an asynchronous database replica, customer-managed keys, immutable backups replicated only in-country and DNS-only traffic failover; an allowed-locations policy shall block any resource outside the jurisdiction.",
         "Single-jurisdiction residency and Tier-0 availability are client constraints; one region delivers about 99.91 % by composite SLA.", "Must", "SOC2 A1.2; ISO22301; LOCAL-PDP", ALL),
        ("E20-HLR-02", "Each critical journey (sign-in, triage queue, fitness status to rostering, urgent mental-health call-back, contact tracing, partner referral) shall have a published SLO, an error budget and burn-rate alerts managed as code.",
         "Alerting on symptoms users feel instead of host metrics; error budget governs release pace.", "Must", "SRE; SOC2 CC7.2", ALL),
        ("E20-HLR-03", "The platform shall be load-, soak- and stress-tested at production volume before go-live and before each major release, including a 50K-staff vaccination campaign, bulk invitations, roster-wide contact tracing, worklists, roll-ups, exports and audit-chain write throughput.",
         "Release notes 3–5 state that none of these was tested at production volume.", "Must", "SOC2 A1.1; ISO27001 A.8.6", ALL),
        ("E20-HLR-04", "Resilience shall be verified by scheduled chaos experiments and quarterly disaster-recovery drills covering zone, region, database, broker and identity-provider failure, plus a restore from immutable backup into an isolated environment.",
         "Recovery targets are only credible when rehearsed; evidence is required for SOC 2 availability criteria.", "Must", "ISO22301; SOC2 A1.3", ALL),
        ("E20-HLR-05", "Releases shall not require downtime: database migrations shall follow expand / contract, application releases shall be progressive (canary or blue / green) with automatic rollback, and new features shall be dark-launched behind governed feature flags.",
         "Tier-0 budget leaves no room for maintenance windows; flags exist today as plumbing only.", "Should", "SOC2 CC8.1; ISO27001 A.8.32", ALL),
        ("E20-HLR-06", "Operations shall have 24×7 on-call with runbooks for every alert, synthetic probes per region, post-incident reviews without blame, and a documented manual fallback for fitness-for-duty decisions during an outage.",
         "Architecture doc §12: BCP must define the manual fallback for fitness decisions.", "Must", "ISO22301; IOSA; SOC2 CC7.4", CREW + "; " + ALL),
    ],
    features=[
        ("E20-F01", "Two-region in-country topology", "Recovery stack in the paired in-country region: warm AKS cluster, async PostgreSQL replica with CMK, replicated registry, immutable backup vault, Traffic Manager DNS failover, allowed-locations policy.", "SRE; DBA; Network", "Must", "E20-HLR-01",
         "Policy denies out-of-country resources; replica lag alert ≤ 5 min; failover runbook automated end to end in UAT.", "Same design for a UK or UAE region pair; UAE Central needs an access request."),
        ("E20-F02", "SLOs, error budgets and burn-rate alerting", "SLO catalogue per journey, recording rules and burn-rate alerts as code, error-budget policy that pauses feature releases when exhausted.", "SRE; Product owners", "Must", "E20-HLR-02",
         "Six journeys with SLOs live; alert fires in a fault-injection test; monthly SLO report to the CIO.", "Fitness-status journey owned jointly with crew scheduling."),
        ("E20-F03", "Performance and capacity engineering", "Load-test suite with production-shaped synthetic data, soak tests, capacity model and per-release performance budget in the pipeline.", "SRE; QA; Core API team", "Must", "E20-HLR-03",
         "Campaign, tracing, worklist and audit scenarios pass at 1.5 × forecast peak; capacity model approved.", "Synthetic data only – never production health data."),
        ("E20-F04", "Chaos and disaster-recovery programme", "Chaos experiments in UAT on a schedule, quarterly region failover and restore drills in PROD-like conditions, ransomware recovery from immutable copies.", "SRE; DBA; Cyber GRC", "Must", "E20-HLR-04",
         "Drill report each quarter with measured RTO / RPO; findings tracked to closure.", "First drill is a go-live condition."),
        ("E20-F05", "Zero-downtime delivery", "Expand / contract migration guideline and lint, canary releases with automatic analysis and rollback, governed feature flags (owner, expiry, audit).", "Platform; Core API team; Release manager", "Should", "E20-HLR-05",
         "Two consecutive releases with zero downtime; every flag has owner and expiry.", "Flags must never switch off a safety control."),
        ("E20-F06", "On-call, runbooks and operational learning", "On-call rota, runbook per alert, synthetic probes per region, post-incident review template, BCP manual fallback for fitness decisions.", "SRE; CMO; Crew scheduling", "Must", "E20-HLR-06",
         "100 % of paging alerts link a runbook; BCP fallback exercised in a tabletop with crew scheduling.", "Manual fallback covers crew and safety-sensitive ground roles."),
    ],
    backlog=[
        ("E20-F01", "Enabler", "Retarget all environments to the in-country region pair and add an allowed-locations policy to the Terraform landing zone", "No resource outside the jurisdiction in policy compliance report; plan applies cleanly in DEV", 8, "H1", "P1", "SRE / Platform", "E19"),
        ("E20-F01", "Enabler", "Build the recovery-region stack: warm AKS cluster, async PostgreSQL replica with customer-managed key, replicated registry, immutable backup vault", "Replica lag < 5 min under load; vault deletion lock and dual control verified", 13, "H1", "P1", "SRE / Platform", "E16, E19"),
        ("E20-F01", "Story", "As the on-call engineer I want a one-command, scripted region failover (promote replica, scale cluster, switch DNS, verify) so that RTO ≤ 15 min is achievable at 3 a.m.", "Automated runbook executes in UAT within 15 min; outbox replay re-sends fitness status after failover", 13, "H1", "P1", "SRE / Platform", "E15"),
        ("E20-F02", "Enabler", "Define SLOs and error budgets for six critical journeys and implement burn-rate alerts (1 h / 6 h windows) as code", "SLO catalogue approved by product owners; alerts tested by fault injection", 8, "H1", "P1", "SRE / Platform", "–"),
        ("E20-F02", "Task", "Agree the error-budget policy (release freeze, escalation) with the CIO and product owners", "Signed policy; first monthly SLO report delivered", 2, "H1", "P2", "Product Owner; SRE", "–"),
        ("E20-F03", "Enabler", "Build a load-test suite with production-shaped synthetic data for 50K staff, 30K crew rosters and five years of case history", "Generator reproducible; no real data; scenarios scripted for campaign, tracing, worklists, exports", 13, "H1", "P1", "QA; SRE / Platform", "–"),
        ("E20-F03", "Story", "As the CIO I want evidence that the platform sustains 1.5 × forecast peak so that go-live does not depend on assumptions", "Report with p95 / p99 latency, error rate, DB saturation and audit-chain throughput; defects fixed or accepted", 8, "H1", "P1", "SRE / Platform", "–"),
        ("E20-F03", "Spike", "Assess the audit-chain advisory lock (R9) under load and decide whether partitioned chains are needed", "Measured writes per second vs forecast; decision record", 3, "H1", "P2", "Platform / Backend", "E16"),
        ("E20-F04", "Enabler", "Introduce chaos experiments in UAT (pod, node, zone, database failover, broker outage, IdP outage) on a monthly schedule", "Experiments run unattended; steady-state hypothesis checked; results published", 8, "H2", "P2", "SRE / Platform", "E15"),
        ("E20-F04", "Task", "Run the first region failover and isolated restore drill before PROD go-live", "Measured RTO ≤ 15 min, RPO ≤ 5 min; drill report signed by the CIO and Cyber GRC", 5, "H1", "P1", "SRE / Platform; Cyber GRC", "E19"),
        ("E20-F05", "Enabler", "Expand / contract migration guideline with a Flyway lint in CI that blocks destructive changes in a single release", "Lint rejects DROP / RENAME without contract phase; guideline in developer guide", 5, "H2", "P2", "Platform / Backend", "–"),
        ("E20-F05", "Enabler", "Canary releases with automated analysis against SLOs and automatic rollback in the promotion pipeline", "Canary of 10 % for 30 min; rollback on SLO breach proven in UAT", 8, "H2", "P2", "SRE / Platform", "–"),
        ("E20-F05", "Story", "As a release manager I want feature flags with owner, expiry and audit so that dark launches do not become permanent configuration", "Flag register; expired flags fail the build; flag change audited", 5, "H2", "P3", "Platform / Backend", "E18"),
        ("E20-F06", "Task", "Set up 24×7 on-call, runbook per paging alert, synthetic probes per region and the post-incident review process", "Rota staffed; probes from both regions; first review published", 5, "H1", "P1", "SRE / Platform", "–"),
        ("E20-F06", "Task", "Document and exercise the manual fallback for fitness-for-duty decisions with crew scheduling and the CMO", "Tabletop completed; fallback form and contact tree approved", 3, "H1", "P1", "Clinical SME (OH Nurse / Physician); Crew scheduling", "E05"),
    ],
))

# ======================================================================================================== E21
EPICS_A.append(epic(
    id="E21", slug="Accessibility_and_Internationalisation", name="Accessibility & Internationalisation",
    horizon="H1", span="H1–H2", team="T3 Experience & Mobile", start_q=2, end_q=5,
    deps=["E13", "E18", "E22"],
    one_line="WCAG 2.2 AA everywhere and full Arabic (RTL) plus the workforce's main languages across all apps and documents.",
    goal="Make every WellnessZ front end and generated document usable by every employee, crew member, clinician and partner: "
         "WCAG 2.2 AA conformance across the four web apps and the mobile app, full Arabic right-to-left support, the "
         "workforce's main languages for employee-facing content, locale-aware formats for a multinational crew, and "
         "governed translation of clinical content.",
    value="A 50K multinational workforce, including outstation and night-shift staff, can only use self-service if it is accessible and "
          "in their language; inaccessible health services expose the group to discrimination claims and erode trust in the "
          "confidential pathways. Localised clinical content (e.g. validated screening translations) is a clinical-safety issue, not "
          "a cosmetic one.",
    in_scope="• WCAG 2.2 AA audit and remediation of the OH workspace, employee app, manager workspace and partner portal\n"
             "• Accessibility in the SDLC: automated checks in CI, manual audit, assistive-technology testing\n"
             "• Arabic RTL across all four apps; Arabic shaping and bidirectional text in generated PDFs\n"
             "• Multilingual employee content (top workforce languages from HRIS data) with translation management\n"
             "• Locale formats: dates, numerals, time zones of stations, optional Hijri display\n"
             "• Clinical translation governance (validated instrument translations, back-translation, sign-off)",
    out_scope="• Translation of the clinical record itself (records stay in the clinician's language)\n• Sign-language interpretation services (procured by OH)",
    stakeholders="Head of Experience; UX / accessibility lead; Nursing Head; CMO; DPO (content-free notifications in every language); HR diversity & inclusion; employee representatives",
    kpis="• 0 open WCAG 2.2 AA critical or serious issues across the four web apps and the mobile app\n"
         "• 100 % of employee-facing screens and documents available in Arabic with correct RTL\n"
         "• ≥ 95 % of employee-facing content in the top five workforce languages\n"
         "• Task-completion rate parity (± 5 points) between languages in usability tests\n"
         "• Approved clinical translations published within 10 working days of source approval",
    dependencies="E13 Portal / app; E18 Master data (languages, templates); E22 Native mobile (platform accessibility APIs); E03 / E09 content owners for clinical translations",
    standards="• WCAG22: W3C WCAG 2.2 level AA\n• EN301549: EN 301 549 accessibility requirements for ICT\n• PDF-UA: ISO 14289 accessible PDF\n• Unicode bidirectional algorithm (UAX #9); CLDR locale data\n• ISPOR good practice for translation of patient-reported outcome measures",
    evidence="Employee app already switches Arabic to dir=\"rtl\" on the portal screens and holds en / ar / fr strings for self-referral; the OH workspace, "
             "manager workspace and partner portal have no locale layer; increment-2 sign-off S14: clinical-text guard vocabulary is English only; "
             "triage advice texts en / ar / fr are drafts; no WCAG audit is recorded in the delivery evidence.",
    start="H1 – audit and Arabic for employee-facing journeys before go-live; H2 – workspaces, further languages and PDFs",
    hlrs=[
        ("E21-HLR-01", "All four web apps and the mobile app shall conform to WCAG 2.2 level AA, verified by an independent manual audit and by automated checks on every build.",
         "Legal accessibility duties and inclusion; the employee app is the main entry to confidential pathways.", "Must", "WCAG22; EN301549", ALL),
        ("E21-HLR-02", "Every front end and every employee-facing generated document shall support Arabic with correct right-to-left layout, Arabic glyph shaping and mixed-direction text.",
         "Arabic is a primary language for the workforce in the Gulf operations; only part of the employee app supports it today.", "Must", "WCAG22; PDF-UA", ALL),
        ("E21-HLR-03", "Employee-facing content shall be available in the workforce's main languages, chosen from HRIS language data, with a translation-management workflow and fall-back to English.",
         "Multinational crews and ground staff; low-literacy and second-language users at airside posts.", "Should", "LOCAL-PDP; WCAG22", ALL),
        ("E21-HLR-04", "Clinical content shown to employees in another language (screening instruments, advice, signposting, consent texts) shall use validated or back-translated versions approved by the content owner before release.",
         "A wrong translation of a PHQ-9 item or a consent text is a clinical and legal risk.", "Must", "ISO45003; LOCAL-PDP", ALL),
        ("E21-HLR-05", "Dates, times, numbers and calendars shall follow the user's locale, and crew-facing times shall show the station time zone alongside UTC.",
         "Crew operate across time zones; misread times cause missed appointments and protocol timers.", "Should", "ICAO-A6", CREW),
    ],
    features=[
        ("E21-F01", "Accessibility audit and remediation", "Independent WCAG 2.2 AA audit of all apps; remediation backlog; accessibility statement per app.", "UX / accessibility lead; Web team", "Must", "E21-HLR-01",
         "Audit report; 0 critical / serious issues open; statements published.", "Kiosk check-in and queue board included."),
        ("E21-F02", "Accessibility in the SDLC", "Automated accessibility checks in CI for every app, component library with accessible primitives, assistive-technology test protocol.", "Web team; QA", "Must", "E21-HLR-01",
         "Build fails on new serious violations; screen-reader test protocol run each release.", "None"),
        ("E21-F03", "Arabic and RTL across apps and documents", "Locale layer in the three workspaces, RTL layout, Arabic fonts and shaping in PDF generation, bidirectional text tests.", "Web team; Platform / Backend", "Must", "E21-HLR-02",
         "All screens pass RTL visual review; Arabic PDFs render correctly and are tagged.", "Partner portal Arabic optional per partner."),
        ("E21-F04", "Multilingual content management", "Translation keys externalised, translation-management workflow, language selection from HRIS preference, English fall-back.", "Frontend / UX; Content owners", "Should", "E21-HLR-03",
         "Top-five languages live on employee journeys; missing keys reported, never blank.", "Content-free notifications checked in every language."),
        ("E21-F05", "Clinical translation governance", "Register of translated clinical content with source version, method (validated / back-translated), approver and licence.", "CMO; Nursing Head; Mental-health lead", "Must", "E21-HLR-04",
         "No clinical text published in a language without an approved translation record.", "Licensed instrument translations may carry fees."),
        ("E21-F06", "Locale and time-zone handling", "Locale-aware formatting; station time zone plus UTC for crew; optional Hijri display.", "Frontend / UX", "Should", "E21-HLR-05",
         "Appointment and protocol times shown correctly across 5 time-zone test cases.", "Station list from E18 master data."),
    ],
    backlog=[
        ("E21-F01", "Task", "Commission an independent WCAG 2.2 AA audit of the four web apps and the mobile build", "Audit report with issue list by severity and success criterion", 3, "H1", "P1", "Product Owner; UX / accessibility lead", "–"),
        ("E21-F01", "Story", "As an employee using a screen reader I want to complete self-referral, booking and consent screens so that I can use confidential services independently", "Journeys pass manual screen-reader tests (desktop and mobile); no critical issue open", 13, "H1", "P1", "Frontend / UX", "E13"),
        ("E21-F01", "Story", "As a clinician with low vision I want the OH workspace worklists and forms to meet contrast, focus and target-size criteria", "Worklists and forms pass WCAG 2.2 AA (incl. 2.4.11 focus not obscured, 2.5.8 target size)", 13, "H2", "P2", "Frontend / UX", "–"),
        ("E21-F02", "Enabler", "Add automated accessibility checks to the web unit and end-to-end tests of all four apps", "CI fails on new serious violations; baseline recorded", 5, "H1", "P1", "Frontend / UX; QA", "–"),
        ("E21-F03", "Enabler", "Introduce a shared locale layer (language, direction, formatting) in the OH workspace, manager workspace and partner portal", "Language switch works without reload; RTL mirrored layout; tests in en and ar", 8, "H2", "P2", "Frontend / UX", "E18"),
        ("E21-F03", "Enabler", "Arabic glyph shaping, bidirectional text and embedded fonts in PDF generation; tagged PDF output", "Certificates, letters and results render Arabic correctly; PDF/UA checker passes", 8, "H1", "P1", "Platform / Backend", "–"),
        ("E21-F03", "Story", "As an Arabic-speaking employee I want every employee-app screen in Arabic so that I understand my fitness outcome and my rights", "100 % employee-app screens translated and RTL-reviewed", 8, "H1", "P1", "Frontend / UX", "E13"),
        ("E21-F04", "Enabler", "Externalise all UI strings and connect a translation-management workflow (export, translate, review, import)", "No hard-coded strings in employee journeys; missing-key report in CI", 5, "H2", "P2", "Frontend / UX", "–"),
        ("E21-F04", "Story", "As a crew member I want the app in my preferred language from HRIS so that I do not have to set it on every device", "Preference read from HRIS / profile; fall-back to English; change audited", 3, "H2", "P2", "Frontend / UX", "E15"),
        ("E21-F05", "Task", "Create the clinical translation register and approve validated translations of PHQ-9, GAD-7, consent and signposting texts", "Register in E18; each translation has source version, method, approver and licence", 5, "H1", "P1", "Clinical SME (OH Nurse / Physician); DPO", "E09, E18"),
        ("E21-F05", "Task", "Extend the clinical-text guard vocabulary to Arabic and French (increment-2 sign-off S14)", "Vocabulary approved by DPO and Nursing Head; guard tests in three languages", 3, "H1", "P1", "Security & Privacy; Clinical SME (OH Nurse / Physician)", "E16"),
        ("E21-F06", "Story", "As a crew member I want appointment and protocol times in my station time zone and UTC so that I never miss a PEP or surveillance appointment on a layover", "Times rendered with station zone and UTC; tested across 5 zones", 5, "H2", "P2", "Frontend / UX", "E18"),
    ],
))

# ======================================================================================================== E22
EPICS_A.append(epic(
    id="E22", slug="Native_Mobile_and_Offline", name="Native Mobile, Offline & Field Capability",
    horizon="H1", span="H1–H2", team="T3 Experience & Mobile", start_q=1, end_q=5,
    deps=["E13", "E15", "E16", "E19"],
    one_line="A store- and MDM-ready crew and field app that works securely without network: offline first-aid capture, NFC, push and a certificate wallet.",
    goal="Turn the Capacitor shell into a production crew and field app: secure on-device storage and app-protection policies, "
         "offline-tolerant capture at airside first-aid posts and for telephone triage with an encrypted queue and safe sync, "
         "NFC badge scan, content-free push notifications with per-user channel preferences, and crew features that work on layover.",
    value="Most OH demand comes from a dispersed 24×7 workforce: ramp, cargo, catering, engineering and crew away from base. A first-aid "
          "post that cannot register an encounter when the network drops loses the case and the evidence. Push and a status wallet "
          "(medical certificate, travel vaccinations) reduce lapses and missed appointments.",
    in_scope="• Secure storage (Keychain / Keystore) and app-protection SDK (MAM) – store and MDM release\n"
             "• Offline capture for first-aid posts and telephone triage: minimal dataset, encrypted local queue, sync and conflict handling\n"
             "• NFC / badge scan against an HRIS cache for registration under 60 s\n"
             "• Push notifications (content-free) and per-user channel preferences\n"
             "• Crew features: status wallet (certificate validity, travel vaccinations), roster-aware reminders, layover access\n"
             "• Device test matrix, crash reporting without PHI, release engineering",
    out_scope="• Offline access to the full clinical record (deliberately not cached)\n• Bring-your-own-device policy (client IT)\n• Mobile hardening assessment itself (E19 SEC-E11)",
    stakeholders="Head of Experience; Mobile team; First aiders; Clinic reception; OH nurses on telephone triage; Crew; Client mobility / MDM team; DPO; CISO",
    kpis="• First-aid encounter registered in < 60 s, including with no network (E01-HLR-03)\n"
         "• 0 health data at rest unprotected on devices (MASVS L2 assessment pass)\n"
         "• Sync conflict rate < 0.5 %; 0 lost offline encounters\n"
         "• Crash-free sessions ≥ 99.5 %\n"
         "• App adoption ≥ 70 % of crew within 12 months of release\n"
         "• Push delivered within 1 minute for ≥ 95 % of messages",
    dependencies="E13 Portal / app; E15 Integration (HRIS cache, push provider); E16 Identity (token storage, MFA); E19 Security (SEC-E11 MASVS); decision D4 mobile distribution",
    standards="• OWASP-MASVS v2 (L2 for PHI apps)\n• LOCAL-PDP; ISO27001 A.8.1 user end-point devices\n• Platform app-store health-data guidelines\n• WCAG22 for mobile",
    evidence="Risk R6 / G14: refresh token in memory only until the secure-storage plug-in is registered, MAM SDK not integrated; increment-1 limitations: "
             "no native NFC, offline retry in memory only, telephone triage not offline-capable, SMS / push are logging adapters, no per-user channel opt-in; "
             "client requirement E01-HLR-03: airside posts work offline-tolerant (mobile sync).",
    start="H1 – secure storage, MAM and store / MDM release before crew go-live; H2 – offline capture, NFC, push and wallet",
    hlrs=[
        ("E22-HLR-01", "The mobile app shall store tokens only in hardware-backed secure storage, enforce app-protection policies (no copy / paste or backup of app data, PIN, remote wipe) and be distributed through MDM for managed devices.",
         "Risk R6 and G14; decision D4 recommends MDM-managed distribution with app protection.", "Must", "OWASP-MASVS; ISO27001", CREW + "; " + FIELD),
        ("E22-HLR-02", "First-aid posts and telephone triage shall continue to capture a minimal encounter dataset without network, held in an encrypted local queue and synchronised idempotently when connectivity returns.",
         "Airside and night-shift posts lose connectivity; E01-HLR-03 requires offline tolerance.", "Must", "LOCAL-PDP; ISO45001", FIELD),
        ("E22-HLR-03", "Registration shall accept NFC / badge scan against a cached HRIS lookup so that a walk-in is registered in under 60 seconds.",
         "Speed at the point of injury; current flow is manual.", "Should", "ISO45001", FIELD),
        ("E22-HLR-04", "The app shall deliver content-free push notifications and let each user choose channels (push, SMS, e-mail) within the limits of mandatory safety messages.",
         "Push and SMS are logging adapters today; no per-user opt-in.", "Should", "LOCAL-PDP", ALL),
        ("E22-HLR-05", "Crew shall see a status wallet (medical certificate validity, travel vaccinations, upcoming surveillance) and roster-aware reminders, usable on layover.",
         "Certificate lapses ground crew; KPI 'crew medical certificate lapses = 0'.", "Should", "EASA-MED; ICAO-A1", CREW),
    ],
    features=[
        ("E22-F01", "Secure, managed mobile platform", "Secure-storage plug-in, MAM SDK, jailbreak / root signals, MDM managed configuration, store and enterprise distribution pipeline.", "Mobile team; Client MDM team", "Must", "E22-HLR-01",
         "Tokens in Keychain / Keystore; MAM policies enforced in test; signed builds distributed through MDM.", "Unmanaged devices limited to web portal if policy requires."),
        ("E22-F02", "Offline encounter capture and sync", "Minimal dataset form, encrypted queue with device-bound key, idempotent sync with Idempotency-Key, conflict rules, max offline age, wipe on sign-out.", "First aider; Clinic reception; OH nurse", "Must", "E22-HLR-02",
         "Encounters created offline appear once after sync; queue unreadable without app unlock; 72 h maximum offline age.", "Only first-aid and triage data offline – no record browsing."),
        ("E22-F03", "NFC and badge registration", "NFC / barcode badge scan, HRIS lookup cache with short TTL and minimal fields.", "First aider; Clinic reception", "Should", "E22-HLR-03",
         "Registration < 60 s in field test at three posts.", "Badge technology varies by station."),
        ("E22-F04", "Push and channel preferences", "Push provider integration through the notification gateway, content-free templates, per-user channel preferences, mandatory-message rules.", "Employee; Crew; DPO", "Should", "E22-HLR-04",
         "Push delivered < 1 min for 95 %; no clinical words in any push (content rule tests).", "Mandatory safety advisories cannot be switched off."),
        ("E22-F05", "Crew status wallet and reminders", "Wallet card showing statuses only, offline-readable, roster-aware reminders.", "Crew; AME; Crew licensing", "Should", "E22-HLR-05",
         "Wallet shows status without diagnosis; reminders before expiry at 60 / 30 / 7 days.", "Status only – no clinical detail on the device."),
    ],
    backlog=[
        ("E22-F01", "Enabler", "Register the secure-storage plug-in for refresh tokens on iOS and Android and remove in-memory fall-back in release builds", "Tokens persisted only in Keychain / Keystore; MASVS storage checks pass", 5, "H1", "P1", "Mobile", "E19"),
        ("E22-F01", "Enabler", "Integrate the app-protection (MAM) SDK and managed configuration; verify selective wipe", "Copy / paste and backup blocked; selective wipe removes app data in test", 8, "H1", "P1", "Mobile", "E19"),
        ("E22-F01", "Task", "Set up enterprise / MDM distribution and store listings with health-data declarations", "Signed builds delivered through MDM to pilot group; store review passed", 5, "H1", "P1", "Mobile; Product Owner", "–"),
        ("E22-F02", "Story", "As a first aider at an airside post I want to register an injury when the network is down so that no encounter is lost", "Offline form with minimal dataset; encrypted queue; sync once online; duplicate-safe", 13, "H2", "P1", "Mobile; Platform / Backend", "E01"),
        ("E22-F02", "Enabler", "Idempotent sync API with conflict rules and maximum offline age for queued encounters", "Server rejects stale (> 72 h) items for manual review; replay is idempotent", 8, "H2", "P1", "Platform / Backend", "E15"),
        ("E22-F02", "Story", "As a triage nurse on night shift I want telephone triage to continue offline with the protocol script cached so that outstations get the same quality", "Approved protocol versions cached; result synced with protocol version id", 8, "H2", "P2", "Mobile; Clinical SME (OH Nurse / Physician)", "E02"),
        ("E22-F03", "Story", "As clinic reception I want to scan a staff badge by NFC so that the walk-in is registered in under 60 seconds", "NFC and barcode supported; HRIS cache TTL ≤ 24 h; field test at three posts", 5, "H2", "P2", "Mobile", "E15"),
        ("E22-F04", "Enabler", "Push channel adapter behind NotificationChannel with content-free templates", "Adapter selected by @Order; content rules tested; delivery metrics", 5, "H2", "P2", "Platform / Backend", "E15"),
        ("E22-F04", "Story", "As an employee I want to choose how I am contacted (push, SMS, e-mail) so that reminders reach me without exposing my health", "Preference per user; mandatory messages flagged; audit of changes", 5, "H2", "P2", "Frontend / UX", "E16"),
        ("E22-F05", "Story", "As a crew member I want a wallet card that shows my medical certificate and travel vaccination status offline so that I can prove compliance on layover", "Status-only card; offline readable; refreshed on sign-in", 5, "H2", "P2", "Mobile", "E07, E11"),
        ("E22-F05", "Story", "As a crew member I want roster-aware reminders before my certificate or surveillance is due so that I am never grounded by an expiry", "Reminders at 60 / 30 / 7 days; respects rest periods from roster data", 5, "H2", "P3", "Mobile", "E15, E25"),
        ("E22-F01", "Task", "Define the device test matrix and crash reporting without PHI", "Matrix covers top 10 device models; crash payloads scrubbed and verified", 3, "H1", "P2", "Mobile; QA", "–"),
    ],
))

# ======================================================================================================== E23
EPICS_A.append(epic(
    id="E23", slug="Interoperability_Hub", name="Interoperability Hub (FHIR, IHE, HL7 v2, national records)",
    horizon="H2", span="H1–H3", team="T4 Integration & Interoperability", start_q=2, end_q=8,
    deps=["E15", "E16", "E18"],
    one_line="Standards-based exchange with hospitals, labs, regulators and national health records – no more e-mail for clinical data.",
    goal="Grow the FHIR R4 and HL7 v2 endpoints of the platform into an interoperability hub: a complete, published FHIR profile "
         "set with R5 readiness, FHIR subscriptions, SMART app launch, IHE document and identity profiles, an HL7 v2 interface "
         "capability for laboratories and devices, a licensed terminology service, and connectors to national or emirate-level "
         "health information exchanges where the law and the employee's consent allow.",
    value="Paper and e-mail reports are the main confidentiality leakage point today (E03 rationale). Electronic exchange shortens "
          "referral-to-report time, removes re-keying errors and lets each new partner hospital or laboratory be onboarded in "
          "weeks instead of a project. Standards protect against lock-in to any partner's proprietary interface.",
    in_scope="• FHIR R4 profiles (Patient, Encounter, Condition, Observation, ServiceRequest, DocumentReference, Procedure, Consent, Task) published as an implementation guide; R5 readiness\n"
             "• FHIR subscriptions (R4B / R5 backport) for partner notifications\n"
             "• SMART on FHIR app launch for approved third-party clinical apps\n"
             "• IHE MHD / XDS.b document exchange, PIXm / PDQm identity, ATNA audit, IUA authorisation\n"
             "• HL7 v2 (ORU, ORM, ADT) interface capability for labs and devices\n"
             "• Terminology service with licensed national editions (D10)\n"
             "• National / emirate-level health information exchange connector (consent-gated, where legally permitted)",
    out_scope="• Building partner systems' interfaces\n• Insurer / ERP / HRIS business connectors (E15 completion)\n• Regulator medical-certification portals (E24)",
    stakeholders="Integration architect; Integration team; CMO; DPO; Partner hospitals and laboratories; Procurement (partner contracts); Cyber GRC",
    kpis="• ≥ 95 % of partner clinical exchanges electronic and standards-based (no e-mail attachments)\n"
         "• Onboarding time for a new FHIR partner ≤ 4 weeks\n"
         "• Referral reports returned electronically ≥ 80 % (supports E04 KPI ≥ 90 % within 7 days)\n"
         "• 100 % conformance-test pass of published profiles\n"
         "• Terminology releases applied within 30 days",
    dependencies="E15 Integration (gateway, broker, outbox); E16 Consent and audit; E18 Terminology and master data; E04 / E03 as main consumers",
    standards="• HL7-FHIR R4 / R4B / R5; HL7-ODH; SMART App Launch 2.x\n• IHE ITI profiles: MHD, XDS.b, PIXm, PDQm, ATNA, IUA\n• HL7-V2 v2.5.1 messaging\n• SNOMED-ICD with licensed national editions\n• LOCAL-PDP consent for disclosure",
    evidence="Increment-2 limitations: FHIR Procedure, Consent, Task, ServiceRequest not mapped, no subscriptions; increment 4: ServiceRequest / "
             "DocumentReference exchange tested with a simulated partner only (O25); terminology starter subset only (O13, D10); OCR off (D14).",
    start="H2 – Scale (months 6–18); spike on national exchange eligibility in H1",
    hlrs=[
        ("E23-HLR-01", "The platform shall publish and conform to a FHIR implementation guide covering all resources it exchanges, including Procedure, Consent and Task, and shall be ready to accept FHIR R5 partners.",
         "Partners need a stable contract; missing resources block live hospital exchange.", "Must", "HL7-FHIR; HL7-ODH", ALL),
        ("E23-HLR-02", "Partners shall be able to subscribe to relevant events (e.g. referral accepted, report due) through FHIR subscriptions instead of polling or e-mail.",
         "Removes chasing by e-mail; standard notification mechanism.", "Should", "HL7-FHIR", ALL),
        ("E23-HLR-03", "Document and identity exchange with hospitals and regional exchanges shall use IHE profiles (MHD / XDS.b, PIXm / PDQm) with ATNA-conformant audit.",
         "Regional exchanges and hospital groups expose IHE profiles; audit must stay end to end.", "Should", "IHE; ISO27001", ALL),
        ("E23-HLR-04", "Laboratories and devices that only speak HL7 v2 shall be connected through a managed interface capability with mapping, acknowledgement and replay.",
         "Many laboratory and audiometry systems still use v2.", "Must", "HL7-V2; SNOMED-ICD", ALL),
        ("E23-HLR-05", "Coding shall use a terminology service with licensed national editions and an update runbook.",
         "Starter subset is not safe for production coding; licence per country (R13).", "Must", "SNOMED-ICD", ALL),
        ("E23-HLR-06", "Where the law permits and the employee consents, the platform shall retrieve or contribute summary data to a national or emirate-level health information exchange.",
         "Avoids duplicate tests and gives OH clinicians relevant history; must never be default.", "Could", "LOCAL-PDP; HL7-FHIR", ALL),
    ],
    features=[
        ("E23-F01", "Published FHIR implementation guide", "Profiles, extensions and examples for every exchanged resource; conformance test suite in CI; R5 mapping layer.", "Integration architect", "Must", "E23-HLR-01",
         "IG published; conformance tests pass; Procedure, Consent, Task mapped.", "None"),
        ("E23-F02", "FHIR subscriptions", "Topic-based subscriptions with content-free notifications and authorised fetch.", "Integration team; Partners", "Should", "E23-HLR-02",
         "Partner receives referral-status notifications; payload carries no clinical content.", "Partner org binding (org_scope) applies."),
        ("E23-F03", "IHE document and identity profiles", "MHD / XDS.b document source and consumer, PIXm / PDQm identity, ATNA audit forwarding, IUA tokens.", "Integration team", "Should", "E23-HLR-03",
         "Connectathon-style test with a reference implementation passes.", "Depends on the target exchange's profile set."),
        ("E23-F04", "HL7 v2 interface capability", "Managed v2 interface (MLLP over TLS / VPN) with mapping, ACK / NACK, replay and monitoring.", "Integration team; Laboratory", "Must", "E23-HLR-04",
         "ORU results from a live laboratory flow into the pull queue; NACKs alert.", "Toxicology LIMS uses its own bound API (E10)."),
        ("E23-F05", "Terminology service", "Terminology server with licensed national editions, value-set management and update runbook.", "CMO; Integration team", "Must", "E23-HLR-05",
         "Licensed edition loaded per environment; search ≤ 300 ms at load.", "Licence per operating country (D10)."),
        ("E23-F06", "National health information exchange connector", "Consent-gated query / contribute connector for one national or emirate-level exchange.", "DPO; CMO; Integration team", "Could", "E23-HLR-06",
         "Pilot with explicit consent; every query in the disclosure log.", "Eligibility and onboarding vary by exchange."),
    ],
    backlog=[
        ("E23-F01", "Enabler", "Author the WellnessZ FHIR implementation guide (profiles, extensions, examples) and publish it to partners", "IG built and versioned; reviewed by two partners", 8, "H2", "P1", "Integration", "E15"),
        ("E23-F01", "Enabler", "Map Procedure, Consent and Task resources and add them to the FHIR export and partner API", "Round-trip tests; consent resources reflect the consent register", 8, "H2", "P1", "Integration", "E16"),
        ("E23-F01", "Enabler", "Conformance test suite for the IG in CI and an R5 compatibility mapping layer", "Suite runs per build; R5 test partner exchange passes", 8, "H2", "P2", "Integration; QA", "–"),
        ("E23-F02", "Story", "As a partner hospital I want to subscribe to referral events so that I stop polling and chasing by e-mail", "Topic subscription; content-free notification; authorised fetch", 8, "H2", "P2", "Integration", "E04"),
        ("E23-F03", "Spike", "Assess eligibility and profile sets of the national / emirate-level exchanges in the operating countries", "Decision memo per country: legal basis, profiles, onboarding effort", 3, "H1", "P2", "Integration; DPO", "–"),
        ("E23-F03", "Enabler", "Implement MHD document source / consumer and ATNA audit forwarding", "Test with reference implementation; audit events reach SIEM", 13, "H2", "P2", "Integration", "E16"),
        ("E23-F03", "Enabler", "Implement PIXm / PDQm identity cross-referencing with the HRIS employee identity", "Identity matches reviewed; no automatic merge without clinician confirmation", 8, "H3", "P3", "Integration", "E15"),
        ("E23-F04", "Enabler", "Managed HL7 v2 interface capability with mapping, ACK / NACK, replay and monitoring", "Live ORU flow from one laboratory; replay tested; dashboards", 13, "H2", "P1", "Integration", "E15"),
        ("E23-F05", "Task", "Procure terminology licences and load national editions; write the update runbook", "Licences on file per environment; runbook tested with one release", 5, "H1", "P1", "Product Owner; Integration", "–"),
        ("E23-F05", "Enabler", "Connect the terminology registry to a terminology server and value-set management", "Search ≤ 300 ms at load; value sets versioned", 8, "H2", "P2", "Integration", "E18"),
        ("E23-F06", "Story", "As an OH physician I want to retrieve a consented summary from the national exchange so that I avoid repeating tests", "Explicit consent per query; disclosure log entry; pilot with one exchange", 13, "H3", "P3", "Integration; Clinical SME (OH Nurse / Physician)", "E16"),
        ("E23-F01", "Story", "As an integration lead I want a partner onboarding kit (sandbox, test data, certificates, checklist) so that a new FHIR partner goes live in ≤ 4 weeks", "Kit published; second partner onboarded within 4 weeks", 5, "H2", "P2", "Integration", "E29"),
    ],
))

# ======================================================================================================== E26
EPICS_A.append(epic(
    id="E26", slug="Data_Platform_and_Deidentified_Datasets", name="Data Platform & Research-Grade De-identified Datasets",
    horizon="H1", span="H1–H2", team="T6 Data & Analytics", start_q=2, end_q=6,
    deps=["E15", "E16", "E17", "E18"],
    one_line="An in-country, governed analytics platform that finally reports every client KPI and offers de-identified datasets safely.",
    goal="Extend E17 into a governed, in-country data platform: change-data capture from the outbox and module schemas into an "
         "analytics store in the same jurisdiction, purpose-specific pseudonymisation, tiered de-identification (operational KPIs, "
         "management analytics, research-grade datasets), a semantic KPI layer for every client epic KPI, data quality rules, "
         "lineage and a catalogue, and a trusted research environment with ethics approval for de-identified studies.",
    value="Many client KPIs are not reported today (RTW within 5 days, documentation time, litigated-claim trend, campaign ROI, absence "
          "as an insight source). Without them the business case of the programme cannot be proven. A governed platform is also the "
          "precondition for any predictive model (E27) and the workforce digital twin (E30).",
    in_scope="• CDC / outbox-based pipeline into an in-country analytics store (no export outside the jurisdiction)\n"
             "• Pseudonymisation with purpose-specific keys; de-identification tiers and release rules\n"
             "• Semantic KPI layer for all client epic KPIs, with small-cell suppression inherited\n"
             "• Data quality rules, lineage, catalogue and data contracts with each module\n"
             "• Trusted research environment for de-identified datasets with ethics / DPO approval\n"
             "• Read-only reporting role (O7) and BI access for approved audiences",
    out_scope="• Predictive models (E27)\n• Enterprise HR analytics outside OH\n• Any person-level OH data for managers or HR",
    stakeholders="Data product owner; DPO; CMO; Nursing Head; Finance (ROI); Health promotion lead; Cyber GRC; Research / ethics committee",
    kpis="• 100 % of client epic KPIs (E01–E14) reported from one semantic layer with definitions signed off\n"
         "• Data freshness ≤ 1 h for operational dashboards, ≤ 24 h for analytics\n"
         "• Data-quality rule pass rate ≥ 98 % on critical fields\n"
         "• 0 re-identification incidents; 100 % of releases with a documented de-identification assessment\n"
         "• Research dataset request turnaround ≤ 20 working days",
    dependencies="E15 Integration (CDC / broker); E16 Identity, consent and keys; E17 Analytics (de-identified view, KPI API, suppression); E18 Master data (definitions, org hierarchy)",
    standards="• ISO20889 privacy-enhancing de-identification techniques\n• ISO25012 data quality model\n• LOCAL-PDP (GDPR Art. 5, 9, 25, 89 as benchmark)\n• ISO27701 privacy information management\n• HL7-ODH for occupational data",
    evidence="Architecture doc: 'the data-warehouse feed and predictive models' not delivered; O7 read-only reporting role; release notes: RTW-within-5-days, "
             "matched-duty rate and documentation time not reported (inc 2), litigated-claim trend (inc 4), ROI not computed and absence not an "
             "insight source (inc 5, E12-HLR-01, E12-F05 partial).",
    start="H1 – pipeline and KPI layer for the outstanding KPIs; H2 – research environment and de-identified datasets",
    hlrs=[
        ("E26-HLR-01", "Analytical data shall flow from the operational platform to an analytics store hosted in the same jurisdiction through change-data capture or the outbox, without manual extracts.",
         "Residency and auditability; manual extracts are a leakage risk.", "Must", "LOCAL-PDP; ISO27001", ALL),
        ("E26-HLR-02", "Personal identifiers shall be replaced by purpose-specific pseudonyms before data reaches the analytics store; re-linking shall be possible only through a controlled, audited service.",
         "Data minimisation and purpose limitation for special-category data.", "Must", "ISO20889; LOCAL-PDP", ALL),
        ("E26-HLR-03", "Every client epic KPI shall have a signed-off definition in a semantic layer and be reported with small-cell suppression (k ≥ 5) to its audience.",
         "Business case evidence; consistent definitions across subsidiaries.", "Must", "ISO45001; HIMSS", ALL),
        ("E26-HLR-04", "Data quality, lineage and a data catalogue shall be maintained, with data contracts between each module and the platform.",
         "Analytics and models are only as good as the data; contracts prevent silent breakage.", "Should", "ISO25012", ALL),
        ("E26-HLR-05", "De-identified research datasets shall be released only into a trusted research environment after a documented re-identification risk assessment, DPO approval and, where applicable, ethics approval.",
         "Enables evidence-based OH (e.g. MSK, fatigue, exposure studies) without exposing individuals.", "Should", "ISO20889; LOCAL-PDP", ALL),
    ],
    features=[
        ("E26-F01", "In-country analytics pipeline", "CDC / outbox connectors, landing and curated zones, schema evolution, replay; analytics store in the same region pair.", "Data engineers; SRE", "Must", "E26-HLR-01",
         "Freshness targets met; pipeline replay tested; no resource outside the jurisdiction.", "None"),
        ("E26-F02", "Pseudonymisation and de-identification service", "Purpose-specific HMAC keys, tokenisation, generalisation and suppression rules, controlled re-linking service with audit.", "Privacy engineer; DPO", "Must", "E26-HLR-02",
         "No direct identifier in the analytics store; re-link requires approved purpose and is audited.", "Restricted classes (E09 / E10) aggregate-only by default."),
        ("E26-F03", "Semantic KPI layer and dashboards", "KPI definitions as code, suppressed aggregates, dashboards per audience (CMO, Nursing Head, HSE, Finance, subsidiaries).", "Data product owner; KPI owners", "Must", "E26-HLR-03",
         "All client KPIs live with signed definitions; suppression tests pass.", "Subsidiary roll-ups follow org_scope."),
        ("E26-F04", "Data quality, lineage and catalogue", "Quality rules on critical fields, lineage from source to KPI, catalogue with owners and classifications, data contracts.", "Data engineers; Module teams", "Should", "E26-HLR-04",
         "≥ 98 % rule pass; lineage visible for every KPI; contract breaks fail CI.", "None"),
        ("E26-F05", "Trusted research environment", "Isolated workspace, approved de-identified extracts, output checking, ethics / DPO workflow.", "DPO; Research lead; CMO", "Should", "E26-HLR-05",
         "First study released after risk assessment; outputs checked before leaving the environment.", "No linkage with external data without approval."),
    ],
    backlog=[
        ("E26-F01", "Enabler", "Provision the in-country analytics store and landing / curated zones with infrastructure as code", "Store in the approved region pair; CMK; private networking; policy compliant", 8, "H1", "P1", "Data & Analytics; SRE / Platform", "E20"),
        ("E26-F01", "Enabler", "CDC / outbox pipeline for the case kernel and modules E01–E08 with replay and schema-evolution handling", "Freshness ≤ 1 h; replay tested; schema change does not break pipeline", 13, "H1", "P1", "Data & Analytics", "E15"),
        ("E26-F01", "Enabler", "Extend the pipeline to E09–E14 with restricted-class aggregation at source", "Restricted classes arrive aggregated only; verified by test", 8, "H2", "P2", "Data & Analytics", "E16"),
        ("E26-F02", "Enabler", "Purpose-specific pseudonymisation service with key separation and audited re-linking", "Different pseudonyms per purpose; re-link requires approved purpose; audit chain entries", 8, "H1", "P1", "Security & Privacy; Data & Analytics", "E16"),
        ("E26-F02", "Task", "De-identification standard: tiers, generalisation rules, suppression, release checklist – approved by the DPO", "Standard approved; applied to first two releases", 3, "H1", "P1", "Security & Privacy; DPO", "–"),
        ("E26-F03", "Story", "As the CMO I want the outstanding KPIs (RTW within 5 days, matched-duty rate, documentation time, litigated-claim trend) so that I can evidence the programme's benefits", "KPIs live with signed definitions and suppression", 8, "H1", "P1", "Data & Analytics", "E17"),
        ("E26-F03", "Story", "As the health promotion lead I want cost per participant and per outcome so that campaign ROI can be reported (E12-F05 gap)", "ROI metrics computed from budget and outcomes; finance-approved definition", 5, "H2", "P2", "Data & Analytics", "E12"),
        ("E26-F03", "Story", "As a Nursing Head I want absence intelligence as a campaign insight source without person-level data (E12-HLR-01 gap)", "Absence aggregates available to the insight rules via the analytics layer; suppression applied", 5, "H2", "P2", "Data & Analytics", "E06, E12"),
        ("E26-F04", "Enabler", "Data-quality rules on critical fields and lineage from source to KPI", "Rule pass rate dashboard; lineage graph for every KPI", 8, "H2", "P2", "Data & Analytics", "–"),
        ("E26-F04", "Enabler", "Data contracts between modules and the platform with contract tests in CI", "Breaking change fails the producing module's build", 5, "H2", "P2", "Platform / Backend; Data & Analytics", "–"),
        ("E26-F05", "Enabler", "Trusted research environment with output checking", "Isolated workspace; no internet egress; output review workflow", 8, "H2", "P3", "Data & Analytics; SRE / Platform", "E19"),
        ("E26-F05", "Task", "Research data access process with DPO and ethics committee, including re-identification risk assessment template", "Process approved; first request handled within 20 working days", 3, "H2", "P3", "DPO; Product Owner", "–"),
        ("E26-F03", "Enabler", "Read-only reporting database role and governed BI access (O7)", "Role grants only analytics views; access reviewed quarterly", 3, "H1", "P1", "Platform / Backend", "E16"),
    ],
))
