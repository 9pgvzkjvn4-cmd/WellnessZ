# WellnessZ demo pack – developer notes

The demo pack is four single-file HTML apps that run offline from `file://` in Chrome or Edge. They share
**one synthetic data file** and **one demo session**. This note is the contract for the developers building
the manager workspace, the OH workspace and the partner portal against the shared engine.

```
demo/
  data/demo-data.js        THE demo data file: window.WZ_DEMO_SEED (synthetic, version-stamped)
  shared/engine.js         the demo backend: window.WZ
  shared/ui.css, ui.js     shared design system: window.UI
  shared/scenarios.js      generated from scenarios.json by build.py (do not edit)
  scenarios.json           10 end-to-end scenarios (persona chain + steps)
  src/<app>.html           app sources (employee-app.html done; manager-workspace, oh-workspace, partner-portal to do)
  src/index.html           launcher source
  build.py                 inlines css/js -> dist/<app>.html, copies demo-data.js, builds dist/index.html
  test/engine.test.js      node demo/test/engine.test.js   (pure JS, no browser)
  test/smoke.js            python3 demo/build.py && node demo/test/smoke.js   (Playwright, file://)
  dist/                    build output (git-ignored); ship this folder
```

## Building a new app

Copy the head and script block of `src/employee-app.html`:

```html
<link rel="stylesheet" href="../shared/ui.css" data-wz-inline>
...
<script src="../data/demo-data.js" data-wz-data></script>   <!-- stays a separate file -->
<script src="../shared/scenarios.js" data-wz-inline></script>
<script src="../shared/engine.js" data-wz-inline></script>
<script src="../shared/ui.js" data-wz-inline></script>
<script>
  WZ.init({ app: 'manager' });          // 'employee' | 'manager' | 'oh' | 'partner'; mounts the demo toolbar
  WZ.subscribe(render);                 // re-render on any change (this tab, other tabs, reset, import)
</script>
```

* File names are fixed: `manager-workspace.html`, `oh-workspace.html`, `partner-portal.html` (see `WZ.APPS`).
  `build.py` picks up any `src/*.html` automatically, and the launcher enables the tiles of the apps it finds.
* Use hash routing (`#/route`); it works on `file://`. Notification links carry `{ app, route }`; the routes the
  engine already emits are listed under *Notification routes* below. Please implement them or map them.
* Partner portal: `<body data-theme="partner">` for the teal brand. Workspaces: use `.wz-layout` / `.wz-side`.
* Do not wipe a form the user is typing in when `subscribe` fires (see `ui.dirty` in the employee app).
* Never read `WZ.session()` for screens. It is the raw state, used by tests and the toolbar only. Screens use
  `WZ.list`, `WZ.get`, `WZ.report` and `WZ.tasks`, which apply the visibility rules.

## Engine API (`window.WZ`)

### Session, sync and files

| Function | Notes |
|---|---|
| `WZ.init({ app, toolbar = true })` | Sets the app context, seeds the session if needed, signs in that app's first user if nobody is signed in, and mounts the toolbar. |
| `WZ.session()` | Current state. It is loaded from localStorage `wellnessz-demo-session`, or seeded from `WZ_DEMO_SEED` (the schema is checked). Treat it as read-only. |
| `WZ.save()` | Persists and bumps the revision. Actions persist on their own, so apps rarely call this. |
| `WZ.reset()` | Re-seeds all apps from `demo-data.js`. |
| `WZ.exportSession()` | Downloads `wellnessz-demo-session.json` and returns the JSON string. `WZ.exportData()` returns the object. |
| `WZ.importSession(fileOrJsonOrObject)` | Returns a Promise of the meta. It checks `format` and `schema`. |
| `WZ.sessionInfo()` | `{ sessionId, seedVersion, engineVersion, schema, createdAt, updatedAt, rev, sizeKb, storageKey, counts }` |
| `WZ.subscribe(fn)` | Returns `unsubscribe`. `fn({ type: 'change' \| 'sync' \| 'reset' \| 'import' })`. A `'storage'` event from another tab triggers `sync`. |

Each action re-reads the latest state from localStorage, applies its change and writes back in one step. Tabs
therefore do not overwrite each other's work.

### Identity and authorisation

| Function | Notes |
|---|---|
| `WZ.users(app?)` | Demo users (all users, or the users of one app). Each has `{ username, name, roles[], apps[], employeeId, orgScope, title, partnerOrg }`. |
| `WZ.signIn(username)` | Only a user of the current app can sign in (`forbidden` otherwise). |
| `WZ.signOut()` | |
| `WZ.currentUser` | Getter. A copy of the signed-in user, or `null`. |
| `WZ.app` | Getter for the current app context. |
| `WZ.can(userOrUsername, permission)` | Role–permission check against `WZ.ROLE_PERMISSIONS`. |
| `WZ.permissions(user?)` | |
| `WZ.me()` | The signed-in user's own employee record, with fitness, duty status and active restrictions. |

### Read side (visibility rules applied for the signed-in user)

* `WZ.list(collection, filter?)` and `WZ.get(collection, id)`. Restricted denials are audited `DENIED`, de-duplicated over 60 s.
* Collections: `employees`, `cases` (filter `{ employeeId, open, queue, status, owner }`), `team`, `crewStatus`,
  `dutyStatus` (`{ notAvailable }`), `sharedOutcomes`, `rtwPlans`, `absences`, `absenceTriggers`, `earlyInterventions`,
  `adviceRequests`, `surveillance` (`{ due }`), `clinicSlots` (`{ type, site, all }`), `appointments`, `treatmentPlans`,
  `referrals` (`{ kind: 'TREATMENT'|'EAP' }`), `claims`, `supportCases`, `peerRequests`, `tests`, `testPools`, `testSelections`,
  `travelConsultations`, `advisories` (`{ mine }`), `cdCases`, `contacts` (`{ cdCaseId }`), `campaigns` (`{ mine }`),
  `screeningResults`, `ergonomicActions`, `notifications`, `audit`, `disclosures`, `consents`, `documents`, `breakGlass`, `roster`.
* `WZ.ref(name)` returns reference data: `orgUnits, sites, jobCodes, safetyRoles, partnerOrgs, serviceCatalogue, restrictionCatalogue,
  triageProtocols, surveillanceProgrammes, destinations, config, testPolicies, meta`.
* `WZ.placeholderCount(employeeId)` gives the restricted-case count for general OH users (the read is audited).
* `WZ.tasks()` returns the work list for the signed-in user: `[{ kind, title, detail, link: { app, route }, priority, due }]`.
* `WZ.notifications()`, `WZ.unreadCount()`, `WZ.markNotificationRead(id)`, `WZ.markAllRead()`.
* `WZ.report(name, params)` returns aggregates, always small-cell suppressed:
  `campaign {campaignId}`, `advisory {advisoryId}` (OH also gets `pending[]`), `testing`, `peer`, `absence`, `supportRate`.
* `WZ.label(kind, code)` gives display labels (`caseStatus, fitness, duty, urgency, category, source, rtw, referral, claim, support,
  sharing, test, testMasked, surveillance, absenceCert, advice, contact, travel, campaign, consent, consentType, eapOutcome, slot,
  contactPreference`).

**Visibility rules (mirroring the platform):**

| Viewer | Gets |
|---|---|
| The employee | `SELF` projections: status, fitness, restrictions, `STATUS`/`SELF` timeline entries. No clinical notes or partner reports. Tests are masked (`NOTIFIED` with where/when → `COLLECTED` → `COMPLETED`). |
| Manager / HR | `STATUS` projections, scoped to direct reports (manager) or `orgScope` (HR). No summary, triage, notes or reason. Restricted cases are never listed. HR sees claim status only. |
| Crew scheduling | `crewStatus`: FIT / FIT_WITH_LIMITATIONS / PENDING / UNFIT, plus a roster conflict flag. No reason. |
| General OH roles | `FULL` for CLINICAL cases. RESTRICTED_CLINICAL returns `null` / is not listed, and the attempt is audited `DENIED`. `placeholderCount` gives the count. |
| Named psych clinician (`config.namedClinicians`) | Full support cases and PSYCHOLOGICAL kernel cases. |
| MRO (or `config.actingMroPhysicians`) | Test results and SUBSTANCE kernel cases. The D&A programme manager sees masked status only. The collector sees requirements and their own collections. |
| Break-glass | `WZ.breakGlass(employeeId, justification)` gives clinicians read-only access (`accessBasis: 'BREAK_GLASS'`) for 4 h. The DPO is alerted. |
| Partners (`partnerOrg`) | Own organisation only. When consent is withdrawn, the referral shows `CONSENT_WITHDRAWN` with no data. EAP referrals carry name and contact preference only. The adjuster sees own-payer claims without `caseId`. |

### Actions

Each action:

* checks permissions and the record scope;
* mutates the session atomically and appends the case timeline (`vis: STATUS | SELF | OH | RESTRICTED`) and the hash-chained audit log;
* writes disclosure-log entries where data leaves OH;
* notifies the right users;
* auto-advances an active scenario step.

Errors are thrown as `WZError` with a `code`: `forbidden`, `not_found`, `validation`, `invalid_state`, `consent_required`,
`consent_withdrawn`, `slot_taken`, `closure_guard`, `urgent_open`, `relief_active`, `case_closed`, `clinical_content`.
Use `UI.attempt(fn, okMessage)` or `UI.errorMessage(e)`.

| Area | Signature (who) |
|---|---|
| Intake | `createReferral({ employeeId?, source?, category, concern, workImpact?, incidentRef?, incidentDate?, location?, bodyPart?, consent })` (employee self (consent required), manager (team), HR (scope), HSE (`EHS_INCIDENT`)) |
| Triage | `triage(caseId, { urgency, protocol?, queue?, recordable?, answers?, note? })` (OH clinicians) · `assign(caseId, username)` · `addCaseNote(caseId, text, vis?)` |
| Fitness | `recordFitnessDecision(caseId, { status, reviewDate?, note?, restrictions? })` (OH physician / AME; pilot and cabin crew require an AME) · `setRestrictions(caseId, [code \| {code,text,until}], { until? })` |
| RTW | `createRtwPlan(caseId, { goal?, startDate?, reviewDate?, phases: [{ week?, hours?, duties }] })` · `acknowledge(rtwPlanId, { comment? })` (employee or line manager; the plan becomes ACTIVE when both have acknowledged) |
| Close | `closeCase(caseId, { reason })` (has a closure guard for safety-critical roles) · `breakGlass(employeeId, justification)` · `reviewBreakGlass(grantId, { outcome })` (DPO) |
| Appointments | `bookAppointment({ slotId, employeeId?, reason?, caseId?, surveillanceId? })` · `cancelAppointment(id, reason?)` |
| Absence | `recordAbsence({ employeeId?, start, end?, reasonCategory, certificateRequired? })` returns `triggers[]` (rules `SPELLS_6M`, `LONG_TERM`) · `endAbsence(id, date)` · `uploadCertificate(absenceId, { fileName, issuer?, from?, to? })` · `validateCertificate(absenceId, 'VALIDATED'\|'REJECTED')` |
| Early intervention | `raiseEarlyIntervention(eiId \| triggerId \| employeeId, { note?, questions?, referToOh = true })` returns `{ caseId, adviceRequestId }` (HR / case manager) |
| Advice | `requestAdvice({ employeeId, questions, caseId? })` · `writeAdviceReport(id, { fitness?, workImpact, adjustments, likelyReturn?, review? })` · `releaseAdvice(id, { decision: 'RELEASE'\|'REQUEST_CHANGES'\|'WITHHOLD', comment?, onBehalf? })` |
| Surveillance | `inviteSurveillance(enrolmentId)` · `recordSurveillanceResult(enrolmentId, { outcome: 'NORMAL'\|'ABNORMAL', summary })` (ABNORMAL opens a follow-up case) |
| Treatment | `createTreatmentPlan(caseId, { goals[], interventions?, expectedRecoveryDays?, followUps? })` |
| Partners | `sendPartnerReferral({ caseId \| supportCaseId, partnerOrg, service?, urgency?, question?, dataSet?, consent })` (EAP is used by the named clinician) · `partnerAccept(id, { appointmentAt? })` · `partnerDecline(id, reason)` · `partnerUploadReport(id, { summary, recommendation?, fileName?, sessions? })` (not available for EAP) · `partnerRecordAttendance(id, { sessions })` (EAP) · `partnerClose(id, { outcome: COMPLETED\|DID_NOT_ENGAGE\|ONWARD_REFERRAL\|EMPLOYEE_WITHDREW })` |
| Claims | `openClaim({ caseId, payerOrg = 'GULFTPA', type?, lostTimeDays?, reserve?, consent })` · `claimMessage(id, text)` · `adjusterDecision(id, { decision: ACCEPTED\|DENIED\|MORE_INFO\|UNDER_REVIEW, note?, reserve? })` (own payer only) · `closeClaim(id, reason)` |
| Support (restricted) | `selfReferSupport({ concern?, contactPreference?, consent, screen?[9] })` · `submitSupportScreen(id, answers[9 × 0–3])` returns `{ score, band, urgent, urgentItemId, emergencyNumber }` (item 9 > 0 or a score ≥ 20 creates an urgent call-back) · `urgentCallback(id, { phone? })` (employee) · `recordCallback(id, itemId?, { outcome: REACHED_SAFE\|NO_ANSWER\|EMERGENCY_SERVICES, note? })` · `addSupportNote(id, text)` · `recordSupportFitness(id, { status, reviewDate? })` (psych or AME; crew relief requires the AME) · `setSupportSharing(id, NOTHING\|FITNESS_ONLY\|FITNESS_AND_ADJUSTMENTS, adjustments?)` · `shareFitnessOnly(id)` · `closeSupportCase(id, reason)` |
| Peer | `peerRequest({ alias, base?, language?, callbackPhone })` returns `{ code }`; no employee id is stored · `peerLogContact(code, { category, outcome })` (volunteer) |
| D&A | `randomSelection(poolId, { count, panel? })` (seeded and reproducible: the first draw from `POOL-GND-ENG` × 2 picks Bea Baggage and Rhea Rivet) · `forCauseRequest({ employeeId, observations[≥2], location? })` · `collectSpecimen(testId, { specimenId?, ccf?, seals?, breathOnly?, refusal? })` · `labResult(testId, { result: NEGATIVE\|NON_NEGATIVE, analyte? })` (simulated TOXLAB, by the MRO) · `mroVerdict(testId, { outcome, note? })` (positive or refusal → duty REMOVED, restricted case, support referral, status-only notices) · `returnToDuty(employeeId, { final? })` |
| Travel / CD | `travelConsultation({ destination, departDate, purpose? })` (employee) · `completeTravelConsultation(id, { recommendations?, vaccinesGiven?, certificate? })` · `publishAdvisory({ destination, title, body, severity?, withinDays = 14 })` (sent to crew rostered to the destination) · `acknowledgeAdvisory(id)` · `openCommunicableCase({ employeeId, condition, onsetDate?, exclusionDays?, incubationDays? })` · `contactTrace(cdCaseId, { windowDays = 7 })` (same flight or shift on the same date; contacts are notified without naming the case) · `contactCheckIn(contactId, { symptoms, note? })` · `clearContact(id)` · `reportPublicHealth(cdCaseId)` |
| Promotion | `createCampaign({...})` · `campaignInvite(campaignId, { units[] })` · `joinCampaign(id, { eventId? })` · `declineCampaign(id)` · `screeningResult({ campaignId, employeeId, measures: { systolic, diastolic, cholesterol, bmi }, outcome? })` (ABNORMAL creates a letter and a 48 h follow-up case) · `addErgonomicAction({ unit, area?, assessment?, rag?, finding?, action, owner?, due? })` · `updateErgonomicAction(id, { status, note? })` |
| Consent | `giveConsent({ type, scope, partnerOrg? })` · `withdrawConsent(consentId)` (partner access stops immediately) |

`WZ.actions()` returns `{ actionName: permission(s) }`.

### Small-cell suppression

* `WZ.suppressCells(cells, { k = config.minGroupSize (5), field = 'count', hide = [field], complementary = true })` suppresses
  cells with 1..k−1. If exactly one cell was hidden, the next smallest is hidden too. Suppressed cells carry `suppressed: true`
  and `null` values. Leave them out of totals.
* `WZ.suppressRate(numerator, denominator, k?)` returns `{ suppressed, numerator, denominator, pct }`. It is suppressed if
  `denominator < k` or `0 < numerator < k`.
* `WZ.isSmallCell(n, k?)`

### Scenarios and toolbar

`WZ.scenarios()` · `WZ.startScenario(id)` · `WZ.scenarioState()` returns `{ active, id, title, stepIndex, stepCount, step, completed[] }` ·
`WZ.stepScenario(±1)` · `WZ.stopScenario()`. A step whose `action` is an engine action advances when that `user`
performs it. `view` steps are marked done in the toolbar. The toolbar (`WZ.mountToolbar({ app })`, which `init` calls)
has these controls:

* the user switcher, limited to the app's users;
* scenario picker and next-step hint, with "Open <app>" and "Switch to <user>";
* Session info;
* Export, Import and Reset;
* a Launcher link.

### Notification routes emitted by the engine

* `employee`: `home, cases, fitness, rtw, appointments, absence, support, travel, campaigns, testing, documents`
* `manager`: `team, rtw, absence, advice, duty-status, crew, shared-outcomes, claims, ergonomics`
* `oh`: `case/<id>, queues, support, support/<id>, testing/mro, testing/collection, referrals, claims, travel, communicable, appointments, absence, peer-support, privacy/break-glass`
* `partner`: `referrals, eap, claims`

## UI kit (`window.UI`)

* Templating and dates: ``UI.h`...` `` (escapes values, `UI.raw()` for trusted HTML), `UI.esc`, `fmtDate`, `fmtDateTime`, `fmtRelative`, `isoToday(offset)`.
* Badges: `UI.status(kind, code)` (tone from `UI.TONES`), `UI.badge(text, tone)`. Tones: `info | success | warn | danger | neutral | restricted`.
* Components: `UI.alert(tone, title, body)` (with `emergency` and `restricted` variants), `UI.dl(pairs)`, `UI.table(columns, rows, { empty, caption })`,
  `UI.empty(title, text, actionHtml)`, `UI.header({ appName, user, bell, orgChip })`, `UI.nav(items, active)`.
* Forms: `UI.field({ type, name, label, options, hint, optional, required })`, `UI.checkbox`, `UI.radios`, `UI.formData(form)`.
* Feedback: `UI.toast(msg, tone)`, `UI.modal({ title, body, actions })`, `UI.confirm(title, text, okLabel, tone)`, `UI.attempt(fn, ok)`.
* Routing and events: `UI.router(routes)`, `UI.go(route)`, `UI.on(container, event, selector, fn)`.
* CSS classes: `.wz-header .wz-nav .wz-layout .wz-side .wz-main .wz-card .wz-grid .wz-kpi .wz-tiles .wz-table .wz-badge .wz-alert .wz-btn(.secondary/.danger/.ghost/.small/.block) .wz-form .wz-field .wz-timeline .wz-empty .wz-modal .wz-toast .wz-letter .wz-code-box`.
  `body.wz-mobile` gives the narrow, centred phone layout. `body[data-theme=partner]` gives the teal theme. Dark mode follows the OS.

## Demo data (`demo-data.js`)

* Version `1.0.0`, schema 1. Dates are relative to the day the session is first seeded.
* Contents:
  * 40 employees in 8 units across 4 sites (hub plus 3 outstations), on 15 job codes in 6 safety roles;
  * 33 demo users;
  * 12 cases at different stages;
  * RTW plans, absences and a trigger, advice, surveillance due list and 80 clinic slots;
  * treatment, referrals (hospital, physio, EAP), a TPA claim and 3 restricted support cases;
  * peer requests, D&A pools, selections, tests and an MRO queue;
  * a crew roster, destinations, travel consultations, an advisory, a closed communicable-disease case with contacts;
  * 3 campaigns, screening results, ergonomic actions, consents, documents, disclosures, notifications and audit entries.
* All names follow an obviously fictional "First Jobword" pattern (Erin Employee, Rami Ramp…). Organisations are marked
  "(fictional)". There are no real people.
* To change the data, edit `demo/data/demo-data.js`, bump `meta.version`, rebuild, then use **Reset** in the toolbar.
  If you change the state shape incompatibly, bump `SCHEMA` in `engine.js` and `meta.schema`. Old sessions are then re-seeded.

## Tests

* `node demo/test/engine.test.js` runs 31 tests:
  * all 10 scenarios played through the engine;
  * the visibility rules;
  * suppression;
  * consent withdrawal;
  * the audit chain;
  * validation of `scenarios.json` against users, apps, actions and permissions.
* `python3 demo/build.py && node demo/test/smoke.js` runs the Playwright smoke test. It opens `dist/employee-app.html` over `file://`
  and checks:
  * sign-in through the toolbar;
  * a self-referral, and a confidential support request with screening;
  * that the state is in localStorage, survives a reload and reaches a second tab;
  * that the restricted case is hidden from the nurse;
  * that there are no console errors.
