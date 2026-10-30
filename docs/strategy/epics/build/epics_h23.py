# -*- coding: utf-8 -*-
"""Epic content E24, E25, E27, E28, E29, E30, E31 (horizons 2 and 3). Tuple layout as in epics_h1.py."""
from epics_h1 import ALL, CREW, FIELD, epic

EPICS_B = []

# ======================================================================================================== E24
EPICS_B.append(epic(
    id="E24", slug="Aviation_Medical_Certification_and_Regulator_Interface", name="Aviation Medical Certification & Regulator Interface",
    horizon="H2", span="H1–H2", team="T1 Clinical Care", start_q=2, end_q=6,
    deps=["E05", "E07", "E15", "E16", "E23"],
    one_line="End-to-end AME certification with qualified signatures and electronic submission to the aviation authority's medical system.",
    goal="Give aeromedical examiners an end-to-end certification workflow – examination in the authority's format, assessment, "
         "limitation codes, issue or referral to the authority's medical assessor, qualified electronic signature – and an "
         "interface to the civil aviation authority's medical system (for example the UK CAA, the UAE GCAA or an EASA member-state "
         "authority): through its API where one is offered, otherwise assisted submission with structured export and receipt "
         "reconciliation.",
    value="Crew medical certificate lapses ground aircraft and expose the operator; double entry into the authority's portal costs AME "
          "time and causes transcription errors. Qualified signatures give certificates and reports legal effect outside the group "
          "(risk R16). A clean regulator interface is a differentiator few OH platforms offer for airlines.",
    in_scope="• Authority examination and report formats (pilot class 1 / 2, cabin crew medical report, ATCO where applicable) as versioned templates\n"
             "• Limitation codes, referral to the authority's medical assessor, certificate issue / deferral / denial states\n"
             "• Qualified or advanced electronic signature through a trust service provider (D12)\n"
             "• Authority interface: API submission where offered; otherwise assisted submission, receipt capture and reconciliation\n"
             "• AME credential and designation tracking; audit of every submission; status-only feed to crew licensing and rostering",
    out_scope="• Acting as the authority's system of record\n• Medical assessor decisions (authority)\n• Licensing of flight crew beyond medical status",
    stakeholders="AME lead; AMEs; CMO; Crew licensing (CREW_LICENSING); Crew scheduling; DPO; Legal; the civil aviation authority's medical section",
    kpis="• Crew medical certificate lapses = 0 (E07 KPI)\n"
         "• ≥ 98 % of examinations submitted to the authority within its deadline\n"
         "• < 2 % of submissions rejected for format or data errors\n"
         "• AME administrative time per examination −20 % (indicative, to be baselined)\n"
         "• 100 % of documents with external legal effect signed with an advanced or qualified signature",
    dependencies="E05 Fitness (crew assessment, status feed); E07 Surveillance (crew certificates); E15 Integration (authority gateway); E16 Identity / signature; E23 Interoperability (FHIR / document exchange)",
    standards="• EASA-MED Part-MED (Reg (EU) 1178/2011 Annex IV); ICAO-A1 Ch. 6; ICAO-8984\n• UK CAA and UAE GCAA medical requirements as LOCAL-OHS\n• eIDAS-style advanced / qualified electronic signatures\n• LOCAL-PDP medical confidentiality",
    evidence="Increment-2 sign-off S9: CAA report layouts and limitation-code labels draft; O19: CAA confirmation of crew medical examination formats; "
             "R16: simple electronic signature may not be accepted by the CAA or courts; D12 recommends qualified signing for documents with external effect.",
    start="H1 – confirm authority formats and interface options (spike); H2 – build",
    hlrs=[
        ("E24-HLR-01", "AMEs shall complete examinations and reports in the authority's current formats, held as versioned templates with limitation codes, and record the certification outcome (issue, defer, refer, deny).",
         "Formats are pending authority confirmation; outcome states drive crew availability.", "Must", "EASA-MED; ICAO-A1; LOCAL-OHS", CREW),
        ("E24-HLR-02", "Certificates and reports with legal effect outside the group shall carry an advanced or qualified electronic signature from a trust service provider.",
         "Risk R16; decision D12.", "Must", "EASA-MED; LOCAL-PDP", CREW),
        ("E24-HLR-03", "Examinations and reports shall be submitted to the authority electronically through its API where available, otherwise by assisted submission with structured export, receipt capture and reconciliation.",
         "Removes double entry; regulator interfaces differ by country.", "Must", "EASA-MED; LOCAL-OHS", CREW),
        ("E24-HLR-04", "The platform shall track AME designations, credentials and audit history, and block certification by an AME whose designation has lapsed.",
         "Authority oversight of AMEs; IOSA crew-fitness standards.", "Should", "EASA-MED; IOSA", CREW),
        ("E24-HLR-05", "Crew licensing and rostering shall receive the certificate status and validity only – never clinical content – within 15 minutes of a change.",
         "Status-only disclosure rule; E05 KPI.", "Must", "EASA-MED; LOCAL-PDP", CREW),
    ],
    features=[
        ("E24-F01", "Authority examination templates", "Versioned templates per authority and class, limitation-code catalogue, outcome states, AME / CMO sign-off.", "AME; CMO", "Must", "E24-HLR-01",
         "Formats confirmed in writing by the authority; outcome states drive certificate validity.", "One template set per operating country."),
        ("E24-F02", "Advanced / qualified electronic signature", "Signature adapter to a trust service provider; signature validation on import; long-term validation.", "AME; Legal", "Must", "E24-HLR-02",
         "Signed PDF validates in a standard verifier; long-term validation profile.", "Internal documents keep the simple signature."),
        ("E24-F03", "Authority submission gateway", "Gateway adapter per authority: API client or assisted-submission package, receipt capture, reconciliation queue, deadline timers.", "AME; Integration team", "Must", "E24-HLR-03",
         "Submissions tracked to receipt; overdue items escalate; reconciliation report weekly.", "API availability differs by authority."),
        ("E24-F04", "AME designation management", "Designation, credentials, expiry alerts and a certification guard.", "AME lead; Crew licensing", "Should", "E24-HLR-04",
         "Expired designation blocks sign-off; alert 60 days before expiry.", "None"),
        ("E24-F05", "Status-only certificate feed", "Certificate status and validity to crew licensing and rostering via the outbox; acknowledgement tracking.", "Crew licensing; Crew scheduling", "Must", "E24-HLR-05",
         "Feed within 15 min; payload passes the outbound allow-list.", "Reuses the E05 roster-feed pattern."),
    ],
    backlog=[
        ("E24-F01", "Spike", "Confirm current examination and report formats and interface options with each authority (API, portal, file)", "Written confirmation per authority; interface option matrix", 3, "H1", "P1", "Product Owner; AME lead", "–"),
        ("E24-F01", "Story", "As an AME I want the class 1 / class 2 and cabin crew templates in the authority's format so that I do not transcribe into the portal", "Templates versioned per authority; validation of mandatory fields", 8, "H2", "P1", "Clinical SME (OH Nurse / Physician); Platform / Backend", "E03"),
        ("E24-F01", "Story", "As an AME I want to record issue, defer, refer or deny with limitation codes so that crew availability is correct the moment I sign", "Outcome states; limitation catalogue; validity calculated", 5, "H2", "P1", "Platform / Backend", "E07"),
        ("E24-F02", "Spike", "Select the trust service provider for advanced / qualified signatures (D12), including in-country processing", "Decision record; DPA; residency confirmed", 3, "H1", "P1", "Security & Privacy; Legal", "E16"),
        ("E24-F02", "Enabler", "Signature adapter with long-term validation for externally effective documents", "Signed documents validate in a standard verifier; tests for revoked certificates", 8, "H2", "P1", "Platform / Backend", "E16"),
        ("E24-F03", "Enabler", "Authority gateway SPI with an API client for the first authority that offers one", "Submission and receipt round-trip in the authority's test environment", 13, "H2", "P1", "Integration", "E15"),
        ("E24-F03", "Story", "As an AME I want an assisted-submission package and receipt capture where the authority offers only a portal", "Structured export; receipt number captured; reconciliation queue", 8, "H2", "P2", "Integration; Frontend / UX", "E15"),
        ("E24-F03", "Story", "As the AME lead I want deadline timers and escalation for unsubmitted examinations", "Overdue items escalate to AME lead; dashboard", 3, "H2", "P2", "Platform / Backend", "E18"),
        ("E24-F04", "Story", "As crew licensing I want AME designations tracked so that only valid AMEs certify", "Guard blocks expired designation; 60-day alert", 5, "H2", "P2", "Platform / Backend", "E18"),
        ("E24-F05", "Story", "As crew scheduling I want certificate status and validity within 15 minutes, without clinical detail", "Status feed; acknowledgement; allow-list test", 5, "H2", "P1", "Integration", "E05, E15"),
        ("E24-F05", "Task", "Agree with the authority and crew licensing the reconciliation process for authority-issued decisions", "Process signed; monthly reconciliation report", 2, "H2", "P2", "Product Owner; Crew licensing", "–"),
    ],
))

# ======================================================================================================== E25
EPICS_B.append(epic(
    id="E25", slug="Fatigue_Risk_and_Shift_Work_Health", name="Fatigue Risk & Shift-Work Health (FRMS integration)",
    horizon="H2", span="H2", team="T2 Workforce Health & Prevention", start_q=4, end_q=7,
    deps=["E02", "E05", "E09", "E12", "E15", "E26"],
    one_line="Connect occupational health with the airline's FRMS and rostering – health-related fatigue handled clinically, insight shared only in aggregate.",
    goal="Connect occupational health with the airline's Fatigue Risk Management System and rostering without operating the FRMS: "
         "fatigue reports with a health component reach OH with the employee's consent, OH restrictions that affect duty patterns "
         "(for example no night duty) reach rostering as status, aggregated health-related fatigue insight goes to the FRMS safety "
         "action group with suppression, and shift workers get a governed sleep-health screening and referral pathway.",
    value="Fatigue is a recognised flight-safety hazard and a driver of MSK injury, absence and psychological strain in shift work. "
          "Today the FRMS and OH work in silos; the CREW_FATIGUE triage protocol has no red-flag question. Joining them safely improves "
          "both safety and health outcomes while keeping the non-punitive reporting culture.",
    in_scope="• Consent-based referral from FRMS fatigue reports to OH (health component only)\n"
             "• Duty-pattern restrictions to rostering as status (extends the E05 feed)\n"
             "• Aggregated, suppressed health-fatigue indicators for the FRMS safety action group\n"
             "• Sleep-health screening and referral pathway for shift workers and crew (instruments under clinical governance)\n"
             "• Fatigue-related absence and injury trend analytics (via E26)\n"
             "• Red-flag review of the CREW_FATIGUE triage protocol",
    out_scope="• FRMS operations, bio-mathematical fatigue modelling and flight-time limitation compliance (Flight Ops SMS – client E12 out of scope)\n• Rostering optimisation",
    stakeholders="FRMS manager / safety action group; Flight Ops; Crew scheduling; Ground operations shift managers; CMO; AME; Nursing Head; Mental-health lead; Employee representatives / unions",
    kpis="• ≥ 95 % of consented health-related fatigue reports reach OH within 24 h\n"
         "• 100 % of duty-pattern restrictions delivered to rostering within 15 min\n"
         "• ≥ 90 % of positive sleep screens followed up within 10 working days\n"
         "• Fatigue-related sickness absence and injury rate in targeted units: downward trend (indicative)\n"
         "• 0 identifiable data in FRMS aggregates (k ≥ 5)",
    dependencies="E02 Triage (CREW_FATIGUE protocol); E05 Fitness (restrictions, roster feed); E09 Psychosocial risk; E12 fatigue & sleep campaigns; E15 Integration (FRMS, rostering); E26 Data platform",
    standards="• ICAO-A6 and ICAO Doc 9966 FRMS manual\n• EASA ORO.FTL.120 FRMS (benchmark)\n• ISO45001; ISO45003 (shift work as psychosocial hazard)\n• LOCAL-PDP",
    evidence="Client E12 out of scope: 'Fatigue Risk Management System operations (Flight Ops SMS)' and E12 scope 'fatigue / sleep education and referral (complements FRMS)'; "
             "increment-1 limitation: CREW_FATIGUE triage protocol has no red-flag question; E09-HLR-05 psychosocial risk includes shift / fatigue; seeded E12 campaign 'fatigue & sleep'.",
    start="H2 – Scale (months 6–18), after rostering transport (E15) and the data platform (E26) are live",
    hlrs=[
        ("E25-HLR-01", "When an employee consents, a fatigue report with a health component raised in the FRMS shall create an OH referral containing only the minimum data, without returning clinical content to the FRMS.",
         "Joins safety and health while preserving non-punitive reporting.", "Must", "ICAO-A6; LOCAL-PDP", CREW + "; " + FIELD),
        ("E25-HLR-02", "OH restrictions that affect duty patterns (night work, duty length, time-zone crossings) shall reach rostering as coded status within 15 minutes.",
         "Restrictions must be enforced by rostering; extends the status-only feed.", "Must", "EASA-MED; ICAO-A6", CREW + "; " + FIELD),
        ("E25-HLR-03", "The FRMS safety action group shall receive aggregated, suppressed indicators of health-related fatigue by fleet, base or unit.",
         "Systemic insight without identifying individuals.", "Should", "ICAO-A6; ISO45003", CREW + "; " + FIELD),
        ("E25-HLR-04", "Shift workers and crew shall have access to a sleep-health screening and referral pathway using instruments approved by clinical governance.",
         "Sleep disorders are under-diagnosed in shift work and affect fitness.", "Should", "ISO45001; ILO-C161", ALL),
        ("E25-HLR-05", "The CREW_FATIGUE triage protocol shall have clinically approved red-flag criteria and a pathway to AME review where fitness may be affected.",
         "Increment-1 limitation; safety-critical gap.", "Must", "EASA-MED; ICAO-A1", CREW),
    ],
    features=[
        ("E25-F01", "FRMS-to-OH consented referral", "Inbound FRMS event with consent token, minimal dataset, referral creation, acknowledgement without clinical content.", "FRMS manager; OH nurse; Employee", "Must", "E25-HLR-01",
         "Referral created only with consent; FRMS receives 'received' status only.", "Union consultation before go-live."),
        ("E25-F02", "Duty-pattern restriction codes in the roster feed", "Restriction codes for night work, duty length and time zones in the restriction catalogue and the status-only feed.", "Physician; AME; Crew scheduling", "Must", "E25-HLR-02",
         "Codes delivered within 15 min; acknowledgement tracked.", "Ground rostering systems included."),
        ("E25-F03", "Aggregated fatigue-health indicators", "Suppressed indicators by fleet / base / unit published to the FRMS group via the data platform.", "FRMS manager; CMO", "Should", "E25-HLR-03",
         "Indicators suppressed below k = 5; quarterly publication.", "No individual-level data leaves OH."),
        ("E25-F04", "Sleep-health pathway", "Self-screen in the app, scoring under governance, referral to OH or sleep service, follow-up tracking.", "Employee; OH nurse; Physician", "Should", "E25-HLR-04",
         "Positive screens followed up within 10 working days; instruments licensed and approved.", "Instrument choice by CMO."),
        ("E25-F05", "CREW_FATIGUE red-flag review", "Clinical review of the protocol, red-flag questions, AME routing.", "CMO; AME lead", "Must", "E25-HLR-05",
         "Approved protocol version live; red flag routes to AME queue.", "None"),
    ],
    backlog=[
        ("E25-F05", "Task", "Clinically review the CREW_FATIGUE triage protocol and add approved red-flag questions", "New protocol version APPROVED by CMO; tests updated", 3, "H1", "P1", "Clinical SME (OH Nurse / Physician)", "E02"),
        ("E25-F01", "Spike", "Agree the FRMS interface, consent model and minimal dataset with the FRMS manager and employee representatives", "Interface specification and consent text approved", 3, "H2", "P1", "Product Owner; Integration", "–"),
        ("E25-F01", "Story", "As a crew member who files a fatigue report I want to choose whether OH contacts me so that health help never feels punitive", "Consent choice in the FRMS hand-off; referral only with consent", 8, "H2", "P1", "Integration; Frontend / UX", "E15, E16"),
        ("E25-F01", "Enabler", "Inbound FRMS referral endpoint bound to the FRMS client with minimal dataset and content-free acknowledgement", "org_scope-bound client; allow-list on response; idempotent", 5, "H2", "P1", "Integration", "E15"),
        ("E25-F02", "Story", "As a physician I want duty-pattern restriction codes so that rostering stops assigning night duty or long sectors when I advise against it", "Codes in restriction catalogue; feed within 15 min; acknowledgement", 5, "H2", "P1", "Platform / Backend", "E05"),
        ("E25-F03", "Story", "As the FRMS manager I want quarterly health-related fatigue indicators by fleet and base so that the safety action group sees systemic issues", "Suppressed indicators; definitions approved by CMO and FRMS manager", 5, "H2", "P2", "Data & Analytics", "E26"),
        ("E25-F04", "Task", "Select and licence sleep-health screening instruments and set cut-offs under clinical governance", "Instruments approved; licences recorded; translations per E21", 2, "H2", "P2", "Clinical SME (OH Nurse / Physician)", "E21"),
        ("E25-F04", "Story", "As a shift worker I want to self-screen my sleep health in the app and get a referral if needed", "Screen scored server-side; positive screen creates follow-up task", 8, "H2", "P2", "Frontend / UX; Platform / Backend", "E13"),
        ("E25-F04", "Story", "As an OH nurse I want positive sleep screens in a work queue with a 10-day target", "Queue with SLA timer; outcome recorded", 3, "H2", "P2", "Platform / Backend", "E02"),
        ("E25-F03", "Story", "As a Nursing Head I want fatigue-related absence and injury trends by unit", "Trend from the data platform; suppression applied", 3, "H2", "P3", "Data & Analytics", "E26"),
        ("E25-F01", "Task", "Consult employee representatives and publish the non-punitive principle for fatigue referrals", "Consultation record; principle published in the app", 2, "H2", "P1", "Change & Training", "–"),
    ],
))

# ======================================================================================================== E27
EPICS_B.append(epic(
    id="E27", slug="Predictive_Health_and_Absence_Analytics", name="Predictive Health & Absence Analytics (governed)",
    horizon="H2", span="H2–H3", team="T6 Data & Analytics", start_q=5, end_q=10,
    deps=["E26", "E06", "E05", "E16", "E19"],
    one_line="Advisory early-intervention signals for OH clinicians, under clinical-safety, bias and AI governance – never automated decisions.",
    goal="Use the governed data platform to give OH clinicians advisory early-intervention signals – risk of an absence becoming "
         "long-term, return-to-work delay against duration guidelines, likely surveillance non-attendance, MSK hotspot forecasts – "
         "with a human in the loop for every action, a clinical safety case, model governance, bias testing, regulatory "
         "classification and in-country training and hosting.",
    value="Early intervention is the differentiator of leading OH services (client E06 rationale). If a governed model helps clinicians "
          "contact the right employee a week earlier, lost workdays and claim costs fall. The value only materialises if clinicians "
          "trust the signals and employees trust the use of their data – hence governance first.",
    in_scope="• Use-case selection with clinicians; silent (shadow) evaluation before any live use\n"
             "• Models for long-term absence risk, RTW delay, surveillance non-attendance and MSK hotspot forecasting\n"
             "• Clinician-facing explanations; advisory only; outcome feedback loop\n"
             "• Model governance: model cards, validation, bias and drift monitoring, change control, decommissioning\n"
             "• Clinical safety case (DCB0129 / DCB0160-style) and DPIA; regulatory classification (EU AI Act style)\n"
             "• In-country training and inference; no PHI to external services",
    out_scope="• Any automated decision about an individual (fitness, employment, discipline)\n• Any individual-level prediction visible to managers, HR or rostering\n• Insurance or claims-reserving models",
    stakeholders="CMO; Clinical safety officer; DPO; Data product owner; Data scientists; Nursing Head; Cyber GRC; Employee representatives; Legal",
    kpis="• Silent-pilot discrimination at or above the threshold agreed in the clinical safety case (e.g. AUROC ≥ 0.75, indicative)\n"
         "• ≥ 70 % of signals rated useful by clinicians in the pilot\n"
         "• Long-term absence conversion −10 % in pilot units vs comparison units (indicative, to be validated)\n"
         "• Performance gap between protected groups ≤ 5 points; 0 unresolved bias findings\n"
         "• 100 % of signals reviewed by a clinician before any contact",
    dependencies="E26 Data platform (prerequisite); E06 Absence; E05 Fitness / RTW; E16 Consent, ABAC and audit; E19 Security; decision on AI governance board",
    standards="• EU-AI-ACT (Reg (EU) 2024/1689) risk classification as benchmark; local AI regulation\n• DCB0129 / DCB0160 clinical risk management (benchmark)\n• ISO42001 AI management system; ISO23894 AI risk management; NIST AI RMF\n• LOCAL-PDP incl. GDPR Art. 22 and 35 as benchmark",
    evidence="Architecture doc: 'governed predictive models' not delivered; HIMSS DHI (client standard) includes predictive analytics; client E06 rationale: proactive "
             "case management (early intervention) is the differentiator; E06 early-intervention queue already exists with rule-based triggers.",
    start="H2 – governance, use-case selection and silent pilot; H3 – live advisory use in pilot units, then scale",
    hlrs=[
        ("E27-HLR-01", "Predictive outputs shall be advisory only, shown to authorised OH clinicians, and require a clinician's review before any contact or action; no automated decision shall be made about an individual.",
         "Human oversight; GDPR Art. 22-type rights; trust.", "Must", "EU-AI-ACT; LOCAL-PDP", ALL),
        ("E27-HLR-02", "Each model shall have a clinical safety case, a DPIA, a model card and a regulatory classification approved before live use.",
         "Health and employment contexts can be high-risk under AI regulation.", "Must", "DCB0129; EU-AI-ACT; ISO42001", ALL),
        ("E27-HLR-03", "Models shall be validated in a silent pilot and monitored in use for performance, calibration, drift and bias across subsidiaries and protected groups.",
         "Prevents harm from biased or decaying models.", "Must", "ISO23894; NIST-AI-RMF", ALL),
        ("E27-HLR-04", "Training and inference shall run in-country on pseudonymised data; no personal health data shall be sent to an external model service.",
         "Residency and special-category data rules.", "Must", "LOCAL-PDP; ISO27001", ALL),
        ("E27-HLR-05", "Employees shall be told how predictive signals are used, and the use shall be covered by the privacy notice and works-council / employee-representative consultation where required.",
         "Transparency and legitimacy.", "Must", "LOCAL-PDP; EU-AI-ACT", ALL),
        ("E27-HLR-06", "Aggregate forecasts (e.g. MSK hotspots, surveillance demand) shall be available to prevention and planning roles with suppression.",
         "Population-level value without individual exposure.", "Should", "ISO45001", ALL),
    ],
    features=[
        ("E27-F01", "AI governance and clinical safety", "AI governance board, clinical safety case, DPIA, model cards, classification, change control.", "Clinical safety officer; DPO; CMO", "Must", "E27-HLR-02",
         "Board chartered; safety case approved before live use.", "Applies also to E28."),
        ("E27-F02", "Model development and silent pilot", "Feature engineering on pseudonymised data, model training in-country, silent evaluation against outcomes.", "Data scientists; Clinicians", "Must", "E27-HLR-03",
         "Silent-pilot report meets agreed thresholds; bias analysis included.", "Restricted classes excluded unless approved."),
        ("E27-F03", "Clinician-facing advisory signals", "Signals in the E06 early-intervention queue with explanation, confidence and feedback; no exposure outside OH.", "OH nurse; Physician", "Must", "E27-HLR-01",
         "Signals visible to OH roles only; review recorded before action; feedback captured.", "Pilot units only at first."),
        ("E27-F04", "Model monitoring and lifecycle", "Performance, calibration, drift and fairness monitoring; retraining under change control; decommissioning.", "Data scientists; Clinical safety officer", "Must", "E27-HLR-03",
         "Monthly monitoring report; alert on drift; retraining approved before release.", "None"),
        ("E27-F05", "Transparency and employee communication", "Privacy notice update, employee FAQ, representative consultation, opt-out where required.", "DPO; Change & Training", "Must", "E27-HLR-05",
         "Notice published before live use; consultation record.", "Local law may require opt-out."),
        ("E27-F06", "Aggregate forecasts", "Suppressed forecasts for MSK hotspots and surveillance demand feeding E12 and E14.", "Health promotion lead; Clinic manager", "Should", "E27-HLR-06",
         "Forecast error reported; suppression applied.", "Feeds E30."),
    ],
    backlog=[
        ("E27-F01", "Task", "Charter the AI governance board (CMO, clinical safety officer, DPO, CISO, legal, employee representative)", "Terms of reference; RACI; first meeting held", 2, "H2", "P1", "Product Owner; DPO", "–"),
        ("E27-F01", "Task", "Clinical safety case and DPIA for the long-term absence risk use case", "Hazard log; risk controls; approval by clinical safety officer and DPO", 5, "H2", "P1", "Clinical safety officer; DPO", "E26"),
        ("E27-F01", "Spike", "Regulatory classification of each use case under the applicable AI regulation and local law", "Classification memo per use case with obligations", 3, "H2", "P1", "Legal; Security & Privacy", "–"),
        ("E27-F02", "Enabler", "Feature store on pseudonymised data in the in-country analytics platform", "Features documented; no direct identifiers; lineage", 8, "H2", "P1", "Data & Analytics", "E26"),
        ("E27-F02", "Story", "As the CMO I want a silent pilot of long-term absence risk against real outcomes so that we only go live if it helps", "6-month silent run; report on discrimination, calibration, fairness", 13, "H2", "P1", "Data & Analytics", "E06"),
        ("E27-F02", "Story", "As a Nursing Head I want an RTW-delay signal against duration guidelines so that stalled cases are reviewed earlier", "Silent evaluation; threshold agreed; explanation available", 8, "H3", "P2", "Data & Analytics", "E05"),
        ("E27-F03", "Story", "As an OH nurse I want advisory risk signals in my early-intervention queue with the main contributing factors so that I can decide whether to call", "Signal, explanation and confidence shown to OH roles only; review recorded", 8, "H3", "P1", "Frontend / UX; Data & Analytics", "E06, E16"),
        ("E27-F03", "Story", "As a clinician I want to give feedback on each signal so that the model and its thresholds can be reviewed", "Feedback captured; used in monitoring report", 3, "H3", "P2", "Frontend / UX", "–"),
        ("E27-F04", "Enabler", "Monitoring of performance, calibration, drift and fairness with alerting", "Monthly report; drift alert tested", 8, "H3", "P1", "Data & Analytics", "E20"),
        ("E27-F04", "Task", "Model change-control and decommissioning procedure", "Procedure approved by governance board", 2, "H2", "P2", "Clinical safety officer; Data & Analytics", "–"),
        ("E27-F05", "Task", "Update privacy notice, employee FAQ and consult representatives before any live use", "Notice published; consultation record", 3, "H2", "P1", "DPO; Change & Training", "–"),
        ("E27-F06", "Story", "As the health promotion lead I want suppressed MSK hotspot forecasts by unit so that campaigns are planned before injuries rise", "Forecast with error reported; suppression k ≥ 5", 5, "H3", "P3", "Data & Analytics", "E12"),
    ],
))

# ======================================================================================================== E28
EPICS_B.append(epic(
    id="E28", slug="Clinical_Documentation_Assistant", name="Clinical Documentation Assistant (governed)",
    horizon="H2", span="H1–H2", team="T1 Clinical Care", start_q=2, end_q=7,
    deps=["E03", "E16", "E19", "E21", "E27"],
    one_line="Switch on the existing assistant extension point with an in-country, governed provider – drafts that clinicians always review and sign.",
    goal="Enable the platform's existing, consent-gated documentation-assistant extension point (AiDocumentationProvider) with an "
         "in-country, governed provider: dictation or consented ambient capture produces a draft structured note, case summary, "
         "handover or referral-letter draft that the clinician edits and signs; assistant involvement is flagged and audited; "
         "restricted classes are excluded by default; quality is measured continuously.",
    value="The client E03 KPI targets −30 % documentation time with templates and assistance; the illustrative business case values "
          "documentation savings among the largest benefits. Clinician time returned to patients improves access and wait times. "
          "The extension point, consent purpose and audit already exist – the remaining work is governance, provider and evaluation.",
    in_scope="• Evaluation spike and DPIA (D13), 5-clinician pilot (increment-2 recommendation), then staged roll-out\n"
             "• In-country provider integration behind AiDocumentationProvider (locally hosted or contracted with DPA and in-country processing)\n"
             "• Dictation and consented ambient capture front end; Arabic and English transcripts\n"
             "• Draft note, case summary, handover and referral-letter drafts; clinician edit and sign-off\n"
             "• Evaluation harness: accuracy, omissions, unsupported statements; monitoring in use\n"
             "• Optional on-host OCR for scanned images (D14) where volumes justify",
    out_scope="• Autonomous documentation or coding without clinician sign-off\n• Use in E09 / E10 restricted classes unless separately approved\n• Diagnostic decision support beyond existing CDS rules",
    stakeholders="CMO; Clinical safety officer; DPO; OH physicians and nurses (pilot group); Cyber GRC; Nursing Head; Employee representatives",
    kpis="• Documentation time per encounter −30 % vs baseline (E03 KPI)\n"
         "• ≥ 80 % of drafts accepted with minor edits in pilot\n"
         "• 0 signed notes containing unreviewed assistant content (audit check)\n"
         "• 100 % of assistant-assisted notes flagged and audited\n"
         "• Employee consent uptake tracked; 0 complaints upheld about assistant use",
    dependencies="E03 Record (extension point, consent purpose); E16 Consent and audit; E19 Security (provider assessment); E21 Arabic language support; E27 AI governance board (shared)",
    standards="• DCB0129 / DCB0160 clinical risk management (benchmark)\n• EU-AI-ACT transparency obligations as benchmark; ISO42001\n• LOCAL-PDP (DPIA, processor DPA, in-country processing)\n• ISO27001 supplier security",
    evidence="Increment-2: consent-gated assistant extension point with no provider enabled, no audio capture front end, pilot not run (E03-BL-019); sign-off S11: "
             "provider and DPIA pending; decision D13: run the evaluation spike with DPIA before enabling any provider; client E03-HLR-07 requires explicit "
             "consent, clinician review and sign-off, and a draft within 60 s.",
    start="H1 – evaluation spike and DPIA; H2 – pilot and staged roll-out",
    hlrs=[
        ("E28-HLR-01", "The assistant shall only run with the employee's explicit consent for the encounter, and the clinician shall review, edit and sign every draft; nothing is filed without sign-off.",
         "Client E03-HLR-07; clinical accountability.", "Must", "LOCAL-PDP; DCB0129", ALL),
        ("E28-HLR-02", "Audio, transcripts and drafts shall be processed in-country by a provider under a DPA, with no retention or training on the data beyond the agreed purpose.",
         "Special-category data; residency; no PHI to external models without DPA.", "Must", "LOCAL-PDP; ISO27001", ALL),
        ("E28-HLR-03", "Every assistant-assisted note shall be flagged and audited (consent, provider, model version, edits), visible to the clinician and the DPO.",
         "Transparency and traceability.", "Must", "EU-AI-ACT; ISO42001", ALL),
        ("E28-HLR-04", "Draft quality shall be evaluated before roll-out and monitored in use for accuracy, omissions and unsupported statements, with a clinical safety case.",
         "Draft errors are a clinical hazard.", "Must", "DCB0129; ISO23894", ALL),
        ("E28-HLR-05", "The assistant shall support Arabic and English consultations and produce drafts in the clinician's documentation language.",
         "Bilingual consultations are common.", "Should", "WCAG22", ALL),
    ],
    features=[
        ("E28-F01", "Evaluation, DPIA and provider selection", "Evaluation of locally hosted vs contracted in-country options against privacy, accuracy, latency, cost; DPIA.", "CMO; DPO; Cyber GRC", "Must", "E28-HLR-02",
         "Decision record; DPIA approved; DPA signed if contracted.", "Decision D13."),
        ("E28-F02", "Capture front end", "Dictation and consented ambient capture in the OH workspace and tablet, consent prompt, indicator while capturing.", "OH physician; OH nurse", "Must", "E28-HLR-01",
         "Capture only after consent; visible indicator; audio discarded after transcription per policy.", "Not available in restricted classes."),
        ("E28-F03", "Draft generation and sign-off", "Provider behind AiDocumentationProvider; draft structured note, summary, handover, referral letter; side-by-side review; sign-off.", "OH physician; OH nurse", "Must", "E28-HLR-01",
         "Draft within 60 s; edits tracked; unsigned drafts never filed.", "Existing SPI and audit reused."),
        ("E28-F04", "Assistant transparency and audit", "Flag on notes, audit entries with provider and version, DPO report.", "DPO; Clinicians", "Must", "E28-HLR-03",
         "100 % of assisted notes flagged; monthly DPO report.", "None"),
        ("E28-F05", "Quality evaluation and monitoring", "Evaluation set, clinician rating, error taxonomy, monitoring dashboard, safety case.", "Clinical safety officer; CMO", "Must", "E28-HLR-04",
         "Pilot report meets thresholds; monitoring live.", "Shared AI governance board with E27."),
    ],
    backlog=[
        ("E28-F01", "Spike", "Evaluate locally hosted vs contracted in-country assistant options against privacy, accuracy, latency, Arabic support and cost (D13)", "Evaluation report and recommendation", 5, "H1", "P1", "Product Owner; Security & Privacy", "E19"),
        ("E28-F01", "Task", "DPIA and clinical safety case for the documentation assistant", "DPIA approved by DPO; hazard log approved by clinical safety officer", 5, "H1", "P1", "DPO; Clinical safety officer", "–"),
        ("E28-F01", "Task", "Contract and DPA with the selected provider, including in-country processing, no retention and no training on WellnessZ data", "Signed DPA; security due diligence complete", 2, "H2", "P1", "Procurement; Legal", "E19"),
        ("E28-F02", "Story", "As an OH physician I want to dictate or, with consent, capture the consultation so that I can focus on the employee", "Consent prompt; capture indicator; audio policy enforced", 8, "H2", "P1", "Frontend / UX", "E03"),
        ("E28-F03", "Enabler", "Provider adapter implementing AiDocumentationProvider with timeouts, retries and no PHI in logs", "Adapter selected by configuration; draft within 60 s at p95", 8, "H2", "P1", "Platform / Backend", "E03"),
        ("E28-F03", "Story", "As a clinician I want a side-by-side review of the draft structured note before I sign so that I stay accountable for every word", "Diff of edits; sign-off required; unsigned drafts expire", 8, "H2", "P1", "Frontend / UX", "E03"),
        ("E28-F03", "Story", "As a case manager I want a case summary and handover draft so that cover clinicians get context quickly", "Summary drafts from signed notes only; clinician review", 5, "H2", "P2", "Platform / Backend; Frontend / UX", "–"),
        ("E28-F04", "Story", "As the DPO I want a monthly report of assistant use, consent and flags so that I can evidence lawful use", "Report by unit; suppression where needed", 3, "H2", "P2", "Platform / Backend", "E16"),
        ("E28-F05", "Enabler", "Evaluation harness with a de-identified evaluation set and error taxonomy", "Harness repeatable; results per provider version", 5, "H2", "P1", "Data & Analytics", "E26"),
        ("E28-F05", "Task", "Run the 5-clinician pilot and decide on staged roll-out", "Pilot report: time saved, acceptance, errors; governance decision", 5, "H2", "P1", "Clinical SME (OH Nurse / Physician); Product Owner", "–"),
        ("E28-F02", "Story", "As a bilingual clinician I want Arabic consultations transcribed and drafted in English so that the record stays consistent", "Arabic audio supported; draft language selectable", 5, "H2", "P3", "Platform / Backend", "E21"),
        ("E28-F03", "Spike", "Assess on-host OCR for scanned images (D14) as an input to drafts and search", "Volume analysis; recommendation; no cloud OCR without DPA", 2, "H2", "P3", "Platform / Backend", "–"),
    ],
))

# ======================================================================================================== E29
EPICS_B.append(epic(
    id="E29", slug="Multi_Tenant_Subsidiary_Onboarding_and_Extensions", name="Multi-Subsidiary Tenancy, Onboarding & Extension Platform",
    horizon="H2", span="H2–H3", team="T5 Platform & SRE", start_q=4, end_q=10,
    deps=["E16", "E18", "E20", "E19"],
    one_line="Onboard a new subsidiary or client in weeks, with configurable tenancy, white-label branding and signed extensions instead of forks.",
    goal="Make WellnessZ a product the group can roll out to every subsidiary – and potentially to external clients – quickly and safely: "
         "a documented tenancy model with options per residency need, automated tenant provisioning, white-label branding, tenant "
         "content packs, a signed extension (plug-in) model so client code no longer needs a fork, and governed feature flags.",
    value="Subsidiaries (catering, cargo, duty free, hospitality, engineering) have different hazards and rules; each onboarding today is "
          "configuration plus, for code, a fork or client module. A repeatable onboarding factory reduces time to value and keeps all "
          "tenants on one upgrade path – the precondition for any commercial offering.",
    in_scope="• Tenancy options: logical tenant in a shared instance (org_scope + tenant configuration), schema-per-tenant, instance-per-tenant for separate jurisdictions\n"
             "• Tenant provisioning automation (IdP realm / groups, configuration, content packs, environments)\n"
             "• White-label branding of the four web apps, documents and notifications\n"
             "• Signed extension model (launcher change, compatibility contract, signing, sandbox rules)\n"
             "• Feature-flag governance and tenant-level enablement\n"
             "• Tenant isolation test suite and upgrade orchestration",
    out_scope="• Commercial terms, pricing and contracts\n• Changing the residency principle (each tenant still in its own jurisdiction)",
    stakeholders="CIO; Product leadership; Subsidiary OH leads; Platform & SRE team; Architecture; Cyber GRC; DPO; Procurement / commercial",
    kpis="• New subsidiary onboarded in ≤ 4 weeks from signed scope (indicative target)\n"
         "• 0 cross-tenant data access in the isolation test suite and pen test\n"
         "• ≥ 80 % of client-specific requirements met by configuration or signed extensions, without a fork\n"
         "• All tenants on the current release within one sprint of general availability",
    dependencies="E16 Identity (org_scope, realms); E18 Configuration (tenant settings, content packs); E20 SRE (instances, upgrades); E19 Security (extension signing, pen test)",
    standards="• ISO27001 A.8.3 / A.5.15 information access restriction\n• SOC2 CC6.1; ISO27017 cloud controls (tenant segregation)\n• LOCAL-PDP residency per tenant\n• NIST-SSDF and SLSA for extensions",
    evidence="Release notes 5: client extension JARs are not implemented (client code = fork / branch or client module); WZ_FEATURE_* flags are plumbing only; "
             "decision D22 and open item O34; SPI overrides need @Order to take effect (customisation guide §7); org_scope and subsidiary roll-ups already exist.",
    start="H2 – tenancy model decision and onboarding factory; H3 – white-label and extension marketplace",
    hlrs=[
        ("E29-HLR-01", "The platform shall define and support tenancy options – logical tenant in a shared instance, schema-per-tenant and instance-per-tenant – selected per residency and contractual need.",
         "Subsidiaries share a jurisdiction; external clients may not.", "Must", "ISO27017; LOCAL-PDP", ALL),
        ("E29-HLR-02", "A new tenant shall be provisioned by automation: identity configuration, organisation scope, configuration and content packs, branding and environments.",
         "Repeatable, auditable onboarding in weeks.", "Must", "SOC2 CC8.1", ALL),
        ("E29-HLR-03", "Client-specific code shall be delivered as signed extensions against a published compatibility contract, loaded without forking the product.",
         "Decision D22; forks fragment upgrades.", "Should", "NIST-SSDF; SLSA", ALL),
        ("E29-HLR-04", "Tenants shall be able to apply their own branding to the web apps, documents and notifications without code changes.",
         "Subsidiary identity and external clients.", "Could", "WCAG22", ALL),
        ("E29-HLR-05", "Tenant isolation shall be verified continuously by an automated test suite and in each penetration test.",
         "Cross-tenant disclosure is the worst-case failure.", "Must", "ISO27001; SOC2 CC6.1", ALL),
        ("E29-HLR-06", "Feature flags shall be governed (owner, scope, expiry, audit) and enable product features per tenant.",
         "Flags are plumbing only today (O34).", "Should", "SOC2 CC8.1", ALL),
    ],
    features=[
        ("E29-F01", "Tenancy model and decision record", "Options analysis, reference architecture per option, cost and operational impact.", "Architecture; CIO", "Must", "E29-HLR-01",
         "Decision record approved; isolation controls defined per option.", "Residency principle unchanged."),
        ("E29-F02", "Tenant provisioning factory", "Automation for IdP realm / groups, org_scope, configuration and content packs, branding, environments.", "Platform & SRE; Subsidiary OH lead", "Must", "E29-HLR-02",
         "Test tenant provisioned end to end in < 1 day of automation time.", "Content packs per sector (catering, cargo, retail…)."),
        ("E29-F03", "Signed extension model", "Launcher change, extension API and compatibility contract, signing and verification, extension registry.", "Architecture; Platform / Backend", "Should", "E29-HLR-03",
         "Unsigned extension refused; sample extension upgrades across two releases without change.", "Replaces fork / client module for new clients."),
        ("E29-F04", "White-label branding", "Theme tokens, logos, document and notification templates per tenant, accessible colour checks.", "Frontend / UX", "Could", "E29-HLR-04",
         "Branding change without deployment; contrast check passes.", "None"),
        ("E29-F05", "Tenant isolation assurance", "Isolation test suite across APIs, analytics, documents and notifications; included in pen test scope.", "QA; Security & Privacy", "Must", "E29-HLR-05",
         "Nightly suite: 0 cross-tenant access.", "Extends the access-control matrix of E19."),
        ("E29-F06", "Feature-flag governance", "Flag register, tenant scope, expiry, audit and product features reading flags.", "Release manager; Product owners", "Should", "E29-HLR-06",
         "Every flag has owner and expiry; tenant toggles audited.", "Shared with E20 dark launches."),
    ],
    backlog=[
        ("E29-F01", "Spike", "Tenancy options analysis (shared logical, schema-per-tenant, instance-per-tenant) with residency, cost and operations impact", "Decision record approved by architecture board and DPO", 5, "H2", "P1", "Platform / Backend; Security & Privacy", "E16"),
        ("E29-F02", "Enabler", "Tenant provisioning pipeline: IdP groups / realm, org_scope, configuration, environment overlays", "Test tenant provisioned by pipeline; audit trail", 13, "H2", "P1", "SRE / Platform", "E18, E20"),
        ("E29-F02", "Task", "Sector content packs (catering, cargo, retail, engineering, hospitality) for hazards, programmes and templates", "Packs versioned; approved by CMO / Nursing Head", 5, "H2", "P2", "Clinical SME (OH Nurse / Physician); Product Owner", "E18"),
        ("E29-F02", "Story", "As a subsidiary OH lead I want an onboarding checklist and tracker so that go-live in ≤ 4 weeks is predictable", "Checklist covering IdP, data, content, training, sign-offs", 3, "H2", "P2", "Product Owner", "–"),
        ("E29-F03", "Spike", "Design the extension launcher and compatibility contract (D22) with signing and verification", "Design approved; threat model per E19", 5, "H2", "P1", "Platform / Backend", "E19"),
        ("E29-F03", "Enabler", "Implement signed extension loading, registry and compatibility checks", "Unsigned / incompatible extension refused at start; sample extension works across two releases", 13, "H3", "P2", "Platform / Backend", "E19"),
        ("E29-F04", "Story", "As a subsidiary I want my branding on apps, documents and notifications without a deployment", "Theme tokens; templates per tenant; contrast checks", 8, "H3", "P3", "Frontend / UX", "E21"),
        ("E29-F05", "Enabler", "Tenant isolation test suite (APIs, analytics, documents, notifications) running nightly", "Suite in CI; 0 cross-tenant access; added to pen-test scope", 8, "H2", "P1", "QA; Security & Privacy", "E19"),
        ("E29-F06", "Story", "As a release manager I want governed feature flags read by product features per tenant", "Flag register; tenant scope; expiry; audit", 8, "H2", "P2", "Platform / Backend", "E18, E20"),
        ("E29-F02", "Enabler", "Upgrade orchestration so that all tenants move to a new release within one sprint", "Wave plan automated; rollback per tenant", 8, "H3", "P2", "SRE / Platform", "E20"),
        ("E29-F01", "Task", "Instance-per-tenant reference deployment for a client in another jurisdiction", "Reference overlay and runbook; residency verified", 5, "H3", "P3", "SRE / Platform", "E20"),
    ],
))

# ======================================================================================================== E30
EPICS_B.append(epic(
    id="E30", slug="Workforce_Health_Digital_Twin_and_Capacity_Planning", name="Workforce Health Digital Twin & Capacity Planning",
    horizon="H3", span="H3", team="T6 Data & Analytics", start_q=7, end_q=12,
    deps=["E26", "E27", "E14", "E07", "E11", "E15"],
    one_line="An aggregate, de-identified model of workforce health readiness per base and fleet, for scenario planning and OH capacity.",
    goal="Build an aggregate, de-identified model of workforce health readiness – fit and certified crew per base and fleet versus roster "
         "need, certificate-expiry horizon, surveillance and immunisation coverage, OH clinic capacity – and use it to simulate "
         "scenarios (outbreak at a station, new route to an endemic area, fleet growth, heat season, mass vaccination) so that "
         "operations and OH plan capacity before the problem arrives.",
    value="Turns OH from reactive to predictive at the level executives care about: will we have enough fit, certified crew and ground "
          "staff, and enough clinic capacity, next quarter? Supports network planning decisions with health evidence while never "
          "exposing an individual.",
    in_scope="• Aggregate readiness model per base / fleet / unit from E05, E07, E11 and roster data (suppressed)\n"
             "• Clinic demand and capacity model linked to E14\n"
             "• Scenario simulation engine with documented assumptions\n"
             "• Executive and operations dashboards; planning exports\n"
             "• Validation of forecasts against actuals",
    out_scope="• Individual-level predictions (E27)\n• Rostering or network optimisation (operations systems)",
    stakeholders="COO / Network planning; Crew planning; CMO; Nursing Head; Clinic managers; Data product owner; DPO",
    kpis="• Clinic demand forecast within ± 10 % at month level\n"
         "• Crew unavailability caused by certificate expiry = 0\n"
         "• Scenario analysis delivered within 1 working day of request\n"
         "• Clinic utilisation ≥ 80 % (E14 KPI) with wait time ≤ 15 min",
    dependencies="E26 Data platform; E27 forecasts; E14 Scheduling capacity; E07 Surveillance and certificates; E11 Immunisation; E15 rostering / network data",
    standards="• ISO45001 planning; ICAO-A6 operator responsibilities\n• ISO20889 de-identification; LOCAL-PDP\n• WHO-IHR preparedness",
    evidence="E14 capacity forecasting exists at clinic level; E11 tracing and campaigns exist; no cross-domain readiness view; outbreak playbook and tabletop exercise "
             "outstanding (E11-BL-013); the Tier-0 decision pack values operational disruption avoided (E07 / E05 crew) as a benefit category.",
    start="H3 – Ecosystem (months 18–36), once E26 and E27 are live",
    hlrs=[
        ("E30-HLR-01", "The platform shall compute aggregate health-readiness indicators per base, fleet and unit (fit, certified, restricted, due) with suppression.",
         "Executive and planning view without individual exposure.", "Must", "ISO45001; LOCAL-PDP", CREW + "; " + FIELD),
        ("E30-HLR-02", "OH clinic demand and capacity shall be forecast from surveillance due lists, campaigns, case trends and seasonality.",
         "Prevents backlogs that cause certificate lapses.", "Must", "ISO45001", ALL),
        ("E30-HLR-03", "Planners shall be able to simulate scenarios (outbreak, new destination, fleet growth, heat season, mass vaccination) with explicit assumptions.",
         "Preparedness for health events with operational impact.", "Should", "WHO-IHR; ICAO-A6", ALL),
        ("E30-HLR-04", "Forecasts shall be validated against actuals and the error published with every forecast.", "Trust in the model.", "Must", "ISO23894", ALL),
    ],
    features=[
        ("E30-F01", "Aggregate readiness model", "Readiness indicators per base / fleet / unit from status data, suppressed.", "Crew planning; CMO", "Must", "E30-HLR-01",
         "Indicators refreshed daily; suppression k ≥ 5.", "Status data only."),
        ("E30-F02", "Clinic demand and capacity forecast", "Forecast feeding E14 capacity planning and staffing.", "Clinic managers; Nursing Head", "Must", "E30-HLR-02",
         "Forecast error ± 10 % monthly after two quarters.", "None"),
        ("E30-F03", "Scenario simulation", "Scenario engine with templates and documented assumptions; comparison view.", "Network planning; CMO", "Should", "E30-HLR-03",
         "Five scenario templates; results within 1 working day.", "Used in the outbreak tabletop."),
        ("E30-F04", "Forecast validation and dashboards", "Back-testing, error reporting, executive dashboard.", "Data product owner", "Must", "E30-HLR-04",
         "Error shown with each forecast; monthly validation report.", "None"),
    ],
    backlog=[
        ("E30-F01", "Enabler", "Readiness data model from fitness, certificate, surveillance and immunisation statuses joined to roster need", "Model documented; suppressed indicators per base / fleet", 8, "H3", "P1", "Data & Analytics", "E26"),
        ("E30-F01", "Story", "As crew planning I want certified-crew readiness per base for the next 90 days so that expiries never ground a rotation", "90-day horizon; status only; suppression", 8, "H3", "P1", "Data & Analytics", "E07, E24"),
        ("E30-F02", "Story", "As a clinic manager I want a monthly demand forecast by service so that I staff clinics ahead of surveillance peaks", "Forecast by service and site; error shown", 8, "H3", "P1", "Data & Analytics", "E14, E27"),
        ("E30-F02", "Story", "As the Nursing Head I want staffing recommendations from the forecast", "Recommended sessions per site; manual override", 5, "H3", "P2", "Data & Analytics; Frontend / UX", "E14"),
        ("E30-F03", "Enabler", "Scenario engine with parameterised templates (outbreak, destination, growth, heat, vaccination)", "Five templates; assumptions recorded with results", 13, "H3", "P2", "Data & Analytics", "–"),
        ("E30-F03", "Task", "Use the scenario engine in the outbreak tabletop exercise (E11-BL-013)", "Exercise report with lessons learned", 2, "H3", "P2", "Clinical SME (OH Nurse / Physician); Change & Training", "E11"),
        ("E30-F04", "Enabler", "Back-testing and forecast error reporting", "Monthly validation report; error displayed on dashboards", 5, "H3", "P1", "Data & Analytics", "–"),
        ("E30-F04", "Story", "As the COO I want an executive readiness dashboard so that network decisions consider workforce health", "Dashboard with suppressed indicators and scenario comparison", 5, "H3", "P2", "Data & Analytics; Frontend / UX", "E26"),
        ("E30-F01", "Task", "DPIA for the readiness model and dashboards", "DPIA approved; audiences defined", 2, "H3", "P1", "DPO", "–"),
        ("E30-F02", "Spike", "Assess linking the forecast to E14 diary templates for automatic session proposals", "Feasibility memo", 3, "H3", "P3", "Platform / Backend", "E14"),
    ],
))

# ======================================================================================================== E31
EPICS_B.append(epic(
    id="E31", slug="Wellbeing_Marketplace_and_Benefits_Integration", name="Employee Wellbeing Marketplace & Benefits Integration",
    horizon="H3", span="H3", team="T2 Workforce Health & Prevention", start_q=8, end_q=12,
    deps=["E12", "E13", "E15", "E16", "E26"],
    one_line="A curated, integration-led marketplace of wellbeing services in the employee app – eligibility from benefits systems, no OH data to vendors.",
    goal="Offer employees a curated marketplace of wellbeing services – EAP, physiotherapy, sleep, nutrition, smoking cessation, fitness "
         "and benefits-provider programmes – inside the employee app, with eligibility from the group's HR / benefits systems, "
         "consented hand-off to the vendor, and only aggregated participation and outcomes coming back; WellnessZ does not "
         "administer benefits.",
    value="Employees get one trusted front door for wellbeing; the group gets higher participation (E12 KPI ≥ 40 %), a measurable link "
          "between prevention spend and outcomes (with E26), and a standard vendor-onboarding route with DPAs and security due diligence.",
    in_scope="• Marketplace catalogue in the employee app with eligibility by subsidiary, role and benefits plan\n"
             "• Vendor onboarding: DPA, security due diligence, API contract, content review\n"
             "• Consented hand-off (deep link / referral token) and aggregate participation / outcome return\n"
             "• Links from OH pathways (e.g. MSK case → physiotherapy offer) without sharing clinical data\n"
             "• Spend and outcome analytics with suppression (via E26)",
    out_scope="• Benefits / gym administration and payroll deductions (client E12 out of scope)\n• Vendor service delivery\n• Sharing any OH record data with vendors",
    stakeholders="Health promotion lead; HR / reward; Procurement; DPO; Cyber GRC; EAP partner; Employees",
    kpis="• Campaign / programme participation ≥ 40 % by unit (E12 KPI)\n"
         "• Vendor onboarding ≤ 6 weeks with DPA and security review\n"
         "• Employee satisfaction with the marketplace ≥ 4 / 5\n"
         "• 0 health data shared with vendors without explicit consent\n"
         "• Cost per engaged employee reported quarterly",
    dependencies="E12 Health promotion (campaign app, wearables); E13 Portal / app; E15 Integration (benefits system, vendor APIs); E16 Consent; E26 Data platform (ROI)",
    standards="• LOCAL-PDP consent and processor agreements\n• ISO27001 A.5.19–A.5.22 supplier relationships\n• ISO45003 wellbeing interventions\n• WCAG22",
    evidence="Client E12 out of scope: 'Gym / benefits administration'; E12-F04 campaign app and wearable integration partial (no live app); EAP partner already in the partner portal; "
             "ROI metric missing (E12-F05).",
    start="H3 – Ecosystem (months 18–36)",
    hlrs=[
        ("E31-HLR-01", "Employees shall find wellbeing services they are eligible for in one catalogue in the employee app, with eligibility derived from HR / benefits data.",
         "One front door; less confusion and higher uptake.", "Should", "ISO45003", ALL),
        ("E31-HLR-02", "Vendors shall be onboarded only with a DPA, a security due-diligence review and an approved integration contract.",
         "Supplier risk and special-category data.", "Must", "ISO27001; LOCAL-PDP", ALL),
        ("E31-HLR-03", "Hand-off to a vendor shall require explicit consent and carry no OH clinical data; only aggregated participation and outcomes return.",
         "Status-only and minimum-necessary principles.", "Must", "LOCAL-PDP", ALL),
        ("E31-HLR-04", "OH pathways may recommend marketplace services without disclosing why to the vendor.", "Joins care and prevention safely.", "Could", "LOCAL-PDP", ALL),
        ("E31-HLR-05", "Programme spend and outcomes shall be reported with suppression to support ROI decisions.", "E12-F05 gap; value for money.", "Should", "ISO45001", ALL),
    ],
    features=[
        ("E31-F01", "Marketplace catalogue", "Catalogue with eligibility rules, search, accessibility and language support.", "Employee; Health promotion lead", "Should", "E31-HLR-01",
         "Eligible services shown per employee; WCAG 2.2 AA.", "Content reviewed by OH."),
        ("E31-F02", "Vendor onboarding workflow", "Due-diligence checklist, DPA tracking, integration certification, periodic review.", "Procurement; DPO; Cyber GRC", "Must", "E31-HLR-02",
         "No vendor live without DPA and review; annual re-review.", "None"),
        ("E31-F03", "Consented hand-off and aggregate return", "Referral token / deep link with consent; aggregate participation and outcome import.", "Employee; Vendor", "Must", "E31-HLR-03",
         "Consent recorded; vendor receives only identifier token and eligibility; aggregates suppressed.", "None"),
        ("E31-F04", "Pathway recommendations", "OH clinicians or pathways suggest services; reason never shared.", "OH nurse; Physician", "Could", "E31-HLR-04",
         "Recommendation visible to employee only; vendor sees no reason.", "None"),
        ("E31-F05", "Spend and outcome analytics", "Spend, participation and outcome by programme with suppression.", "Health promotion lead; Finance", "Should", "E31-HLR-05",
         "Quarterly report; suppression k ≥ 5.", "Uses E26."),
    ],
    backlog=[
        ("E31-F02", "Task", "Define the vendor due-diligence checklist, DPA template and integration certification", "Approved by DPO, Cyber GRC and procurement", 3, "H3", "P1", "Procurement; Security & Privacy", "E19"),
        ("E31-F01", "Story", "As an employee I want to browse wellbeing services I am eligible for in my language so that I can act on my health", "Catalogue with eligibility; ar / en; accessible", 8, "H3", "P1", "Frontend / UX", "E13, E21"),
        ("E31-F01", "Enabler", "Eligibility service from HR / benefits data (plan, subsidiary, role)", "Eligibility cached with short TTL; no health data used", 5, "H3", "P1", "Integration", "E15"),
        ("E31-F03", "Story", "As an employee I want to consent before I am handed to a vendor so that I control what they know", "Explicit consent; token only; withdrawal supported", 5, "H3", "P1", "Frontend / UX; Platform / Backend", "E16"),
        ("E31-F03", "Enabler", "Aggregate participation and outcome import from vendors bound to their organisation", "org_scope-bound clients; aggregates only; suppression", 5, "H3", "P1", "Integration", "E15"),
        ("E31-F04", "Story", "As an OH nurse I want to recommend a physiotherapy or sleep programme from the marketplace so that prevention follows the case", "Recommendation to employee only; vendor sees no reason", 5, "H3", "P3", "Platform / Backend", "E04"),
        ("E31-F05", "Story", "As the health promotion lead I want spend and outcome per programme so that I can decide what to renew", "Quarterly suppressed report; finance definition", 5, "H3", "P2", "Data & Analytics", "E26"),
        ("E31-F02", "Task", "Onboard the first three vendors (e.g. EAP, physiotherapy, sleep) through the workflow", "Three vendors live with DPA and certification", 5, "H3", "P2", "Product Owner; Integration", "–"),
        ("E31-F01", "Task", "Content review process for marketplace listings by OH", "Process approved; listings reviewed before publication", 2, "H3", "P2", "Clinical SME (OH Nurse / Physician)", "–"),
        ("E31-F03", "Story", "As a DPO I want a report of vendor hand-offs and consents so that I can evidence lawful sharing", "Report by vendor; suppression", 3, "H3", "P2", "Platform / Backend", "E16"),
    ],
))
