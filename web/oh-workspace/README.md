# WellnessZ – OH Workspace (`web/oh-workspace`)

The clinical console for **Occupational Health**: OH nurses / case managers, OH physicians, AMEs, the CMO, the Nursing
Head, OH administrators, plus **clinic reception and first aiders** (walk-in / emergency registration only).
It delivers increment 1 (**E01 intake**, **E02 triage, queues, assignment and SLA**) and increment 2 (**E03 OH record &
clinical encounters**, **E05 fitness for duty, restrictions and return to work**), plus the **DPO** privacy area.

The API contracts are [`docs/increment-01/API-Contract-E01-E02.md`](../../docs/increment-01/API-Contract-E01-E02.md) and
[`docs/increment-02/API-Contract-E03-E05.md`](../../docs/increment-02/API-Contract-E03-E05.md). Increment-2 calls are typed
in `src/api/record.ts` (E03), `src/api/fitness.ts` (E05) and `src/api/platform.ts` (master data, documents, break-glass,
disclosures, retention).
Every call goes through the BFF as `/bff/api/...` (core `/api/v1/...`). The stack, auth, security conventions, CSP,
nginx and container setup are the same as in [`manager-workspace`](../manager-workspace/README.md): React 19 +
TypeScript 6 (strict) + Vite 8, no UI framework, hash routing, exact pinned versions.

## Quick start

```bash
npm ci
npm run dev      # http://localhost:5175 – proxies /bff, /oauth2, /login, /logout → $WZ_BFF_TARGET (default http://localhost:8090)
npm run lint     # ESLint, --max-warnings 0 (no console.*, no localStorage/sessionStorage)
npm test         # Vitest
npm run build    # tsc -b + vite build → dist/ (CSP <meta> injected)
```

Point the proxy at a BFF on another host with `WZ_BFF_TARGET=http://bff.local:8090 npm run dev`.
Demo users (local profile): `nurse1`, `nurse2`, `physician1`, `ame1`, `cmo1`, `nursinghead1`. A reception or
first-aider account sees only the reception screen.

## Screens (hash routes)

| Route | Screen | Main API calls |
|---|---|---|
| `#/queues` | **Work queues** (E02-F04): tabs My / Team / Unassigned / Overdue / Awaiting external / Awaiting employee / Escalated with counts; case table (ref, employee, unit, type, urgency, safety-critical, owner, SLA state + time left, waiting state, intensity, duplicate flag, open tasks); filters; saved personal views; pagination; bulk reassign (Nursing Head) / remind / close duplicates with a result for each item | `GET /queues/counts`, `GET /queues/{queue}`, `GET/POST/DELETE /queues/views`, `POST /queues/bulk`, `GET /staff` |
| `#/cases/{id}/{tab}` | **Case view**: E02-BL-018 header (status, urgency, SLA, safety badge, badges, open tasks, next action, owner, queue, intensity, complexity) plus tabs: | `GET /cases/{id}`, `GET /cases/{id}/header` |
| ↳ `overview` | HRIS identity (with an `hrisOverride` warning), case and intake details, audited attachment download, links (add), waiting state (pauses the SLA), link to the employee timeline | `POST /cases/{id}/links`, `POST /cases/{id}/waiting-state`, `GET /cases/{id}/attachments/{attId}` |
| ↳ `triage` | Protocol picker → branching **question runner** driven by the protocol JSON (`onYes`/`onNo`/`rules`/`next`). Red-flag questions are highlighted, and a "yes" stops at EMERGENCY. Shows a preview only; the server's `TriageResult` is authoritative. Overriding needs a reason. Telephone mode adds a call log, a consent checkbox and a call-back time. The advice can then be released to the employee. | `GET /triage/protocols[/{code}]`, `POST/GET /cases/{id}/triage`, `POST /cases/{id}/advice` |
| ↳ `recordability` | Wizard (questions from the server), work-relatedness, override with a reason code, result with `localCategory` / `caaReportable`, history | `GET /intake/recordability/wizard`, `POST /cases/{id}/recordability`, `GET …/history` |
| ↳ `checklists` | Food handler / driving / height checklist, chosen from `safetyCategory` | `GET /cases/{id}/checklists`, `POST /cases/{id}/checklists/{code}` |
| ↳ `assignment` | Assign / reassign with a reason (or self-assign), history, handover form | `POST /cases/{id}/assign`, `GET /cases/{id}/assignments`, `POST /cases/{id}/handover` |
| ↳ `escalation` | Escalate (level and reason), list, acknowledge / decide (physician / AME / CMO), case conferences (CMO schedules) and minutes | `POST /cases/{id}/escalations`, `GET /escalations?caseId=`, `…/acknowledge`, `…/decision`, `GET/POST /cases/{id}/conferences`, `POST /conferences/{id}/minutes` |
| ↳ `complexity` | Score, drivers, intensity, risk-flags form, recalculate | `GET /cases/{id}/complexity`, `PUT /cases/{id}/risk-flags`, `POST …/complexity/recalculate` |
| ↳ `duplicates` | Candidates with their reasons; merge (reason required) or link instead | `GET /cases/{id}/duplicates`, `POST /cases/{id}/merge`, `POST /cases/{id}/links` |
| ↳ `tasks` | List, create, complete | `GET/POST /cases/{id}/tasks`, `POST /tasks/{id}/complete` |
| ↳ `timeline` | Case events, with a link to the employee timeline | `GET /cases/{id}/timeline` |
| ↳ `encounters` | Encounters linked to the case; start a new one (case pre-linked) | `GET /employees/{id}/encounters`, `POST /employees/{id}/encounters` |
| ↳ `fitness` | **E05-F01 fitness decision**: standard picker (source, validity, sign-off role, reference link), outcome, limitation codes, catalogue restrictions with values, valid from / until (defaulted from the standard) / review date, OH-only rationale, draft certificate toggle; **duration comparison** card (E05-BL-020) with a mandatory override reason when exceeded; decision history with sign-off status (AME / CMO approve or reject). Licensed standards are blocked for non-AME/CMO. The increment-1 quick status form stays available below | `GET /masterdata/fitness-standards`, `GET /masterdata/restrictions`, `POST/GET /cases/{id}/fitness-decisions`, `GET /cases/{id}/duration-comparison`, `POST /fitness-decisions/{id}/signoff`, `POST /cases/{id}/fitness` |
| ↳ `restrictions` | Restrictions (pre-filled from the latest final decision) matched against job-demand profiles, ranked suggestions with conflicts, propose a modified duty (functional wording), proposals with manager decisions / HR escalation | `POST /fitness/match`, `POST/GET /cases/{id}/duty-proposals` |
| ↳ `rtw` | Phased **RTW plan builder** (weeks, hours, duties, restrictions per phase; review dates), edit draft, activate, employee / manager acknowledgements, complete reviews (overdue badge), printable PDF, HR job offers (read-only) | `POST/GET /cases/{id}/rtw-plans`, `PUT /rtw-plans/{id}`, `POST …/activate`, `POST …/reviews/{reviewId}/complete`, `GET /cases/{id}/job-offers` |
| ↳ `food-handler` | Only for `FOOD_HANDLER` cases: exclusion checklist + OH-only reason → exclude, request return, clearance (symptom-free 48 h) → clearance document; episode history | `GET /cases/{id}/food-handler`, `POST …/food-handler/exclude\|return-request\|clear` |
| ↳ `crew` | Only for flight / cabin crew cases: AME assessment with EASA limitation codes (operational ones flagged) → crew medical report (CAA layout) | `GET /crew/limitation-codes`, `POST /cases/{id}/crew-assessment` |
| ↳ `ihr` | Ill-health retirement / redeployment: physician assessment, CMO decision, appeal (on behalf of the employee), appeal decision by a different CMO | `/cases/{id}/ihr-assessments`, `/ihr-assessments/{id}/cmo-decision\|appeal\|appeal-decision` |
| ↳ `documents` | Case documents (same panel as the chart, scoped to the case) | see chart `documents` |
| ↳ `summary` | Rule-based (non-AI) handover summary: key dates, diagnoses, fitness, restrictions, open tasks, last encounter | `GET /cases/{id}/summary` |
| ↳ `status` | Status transitions. A 409 / 422 shows the server `detail`, the correlationId and a link to the missing step (recordability / fitness / checklist) | `POST /cases/{id}/transition` |
| `#/employees/{id}/timeline` | E01-BL-018 employee timeline | `GET /employees/{id}/timeline` |
| `#/employees` | **Employee chart** search (also linked from the case header and the queue rows) | `GET /intake/employees/lookup?q=` |
| `#/employees/{id}/chart/{tab}` | **OH chart** (E03-F01): summary panel (alerts with severity symbol, fitness, restrictions, open cases, surveillance due / overdue, immunisations, counts), **“N restricted items hidden” + Emergency access (break-glass)** dialog (justification ≥ 20 characters, DPO-alert warning, chart reload), FHIR / ODH download links. Tabs: | `GET /employees/{id}/chart`, `POST /break-glass`, `GET /employees/{id}/fhir`, `GET /employees/{id}/odh` |
| ↳ `problems` · `allergies` | Coded lists with add / edit (terminology picker) | `GET/POST /employees/{id}/problems\|allergies`, `PUT /problems/{id}`, `PUT /allergies/{id}` |
| ↳ `medications` | Add / edit with **CDS**: a `409 cds_alert` shows each alert (severity, message, reference) and requires an override reason per alert before resubmitting with `overrides` | `GET/POST /employees/{id}/medications`, `PUT /medications/{id}` |
| ↳ `immunisations` · `jobs` | Immunisation record; ODH job & exposure history (HRIS vs manual, hazards) with manual add / correct | `GET/POST /employees/{id}/immunisations\|jobs`, `PUT /jobs/{id}` |
| ↳ `results` | Results with flags (symbol + text), STS detail, overdue badge, category filter; orders (order sets or LOINC items, priority, duplicate-test CDS override, cancel); **device result entry** – audiometry grid L/R × 500–8000 Hz, spirometry, vision, ECG; calibration ID required unless manual | `GET /employees/{id}/results\|orders`, `GET /order-sets`, `POST /employees/{id}/orders`, `POST /orders/{id}/cancel`, `POST /employees/{id}/results/device` |
| ↳ `documents` | List + full-text search (snippets), generate from template (dynamic merge-field form), sign (SHA-256 shown), new version, share (EMPLOYEE / MANAGER / HR / PARTNER – clinical templates never to manager/HR, partner needs consent id), revoke share, download PDF, scan / upload (PDF/JPEG/PNG ≤ 10 MB, multipart) | `/documents*` (contract §3.2) |
| ↳ `encounters` · `timeline` | Encounter list (AI-assisted badge) + new encounter (type picker from templates; MENTAL_HEALTH only for PSYCH_CLINICIAN); record timeline | `GET /encounter-templates`, `GET/POST /employees/{id}/encounters`, `GET /employees/{id}/record-timeline` |
| `#/encounters/{id}` | **Encounter editor** (E03-F02): template sections / fields incl. CODE fields with the **terminology picker** (search, favourites ★, recent), SOAP, **body map** (front / back view, region toggle buttons, mechanism per site), diagnoses with one primary, CDS / protocol prompts + fitness-standard reference, save draft, **sign** (pre-sign checks, signature hash shown, then locked), signed addenda, entered-in-error, photo upload with a mandatory consent checkbox, **AI assistant** (consent capture → draft request; “AI assistant not configured” on `503 ai_not_configured`; insertion is explicit and flags the note AI-assisted) | `GET/PUT /encounters/{id}`, `POST …/sign\|addenda\|entered-in-error\|photos\|ai-consent\|ai-draft`, `GET /body-map/mechanisms`, `/terminology/search\|favourites\|recent` |
| `#/encounters/unsigned` | **Unsigned notes** reminder (mine; Nursing Head / CMO can switch to all) | `GET /encounters/unsigned?mine=` |
| `#/results/worklist` | **Results worklist**: unacknowledged abnormal / critical, overdue (> 72 h) badge and filter, acknowledge dialog (action + note), generate result letter | `GET /results/worklist`, `POST /results/{id}/acknowledge`, `POST /results/{id}/letter` |
| `#/documents/scan` | Scan an external document for an employee (clinic reception and OH) | `POST /documents/scan` |
| `#/cds/statistics` | CDS alerts fired / overridden / override rate by type (CMO, Nursing Head) | `GET /cds/statistics` |
| `#/fitness/signoff` | **Fitness sign-off queue** (AME / CMO; permanently unfit = CMO only), approve / reject with note | `GET /fitness-decisions/awaiting-signoff`, `POST /fitness-decisions/{id}/signoff` |
| `#/restrictions` | **Active restrictions** with EXPIRING / LAPSED quick filters and unit filter, extend (new date + reason) / close (reason); **restrictions dashboard** by unit for Nursing Head / CMO | `GET /restrictions/active`, `POST /restrictions/{id}/extend\|close`, `GET /restrictions/dashboard` |
| `#/rtw/overdue` | RTW overdue reviews (escalated after 2 days) | `GET /rtw/reviews/overdue` |
| `#/crew/dashboard` | **Crew dashboard** (CMO): unfit / limited by base, by fleet, weekly trend; no identifiers; suppressed cells show **`<5`** | `GET /crew/dashboard?from&to` |
| `#/crew/roster-feed` | **Roster-feed reconciliation**: sent / acknowledged, unacknowledged > 15 min, p95 latency, recent status-only messages | `GET /crew/roster-feed/reconciliation`, `GET /crew/roster-feed` |
| `#/dpo/break-glass` | **Break-glass reviews** (DPO): pending (overdue flag) or all, review dialog (appropriate / inappropriate + note) | `GET /break-glass?status=PENDING`, `POST /break-glass/{id}/review` |
| `#/dpo/break-glass-report` | Monthly break-glass report (DPO, CMO) | `GET /break-glass/report?month=` |
| `#/dpo/disclosures` | Disclosure log by employee (DPO) | `GET /disclosures?employeeId=` |
| `#/dpo/retention` | Retention schedule (DPO edits, OH_ADMIN reads), records past retention, legal hold set / release on a document | `GET/PUT /documents/retention`, `GET /documents/retention/due`, `POST/DELETE /documents/{id}/legal-hold` |
| `#/escalations` | Escalations inbox (open / mine), acknowledge and decide | `GET /escalations?status&mine` |
| `#/handovers` | Pending handovers → acknowledge | `GET /handovers/pending`, `POST /handovers/{id}/acknowledge` |
| `#/reception` | **Walk-in & first aid** (E01-F03): badge lookup that works with keyboard-wedge scanners (Enter triggers the lookup, not the submit), name search, Walk-in / **Emergency** mode with exactly five fields (employee, time, location, chief complaint, action taken), the site / post set once per workstation, an in-memory offline retry queue, the encounter list, a one-click nurse **Convert to case**, and an enrichment form (vitals, notes) | `GET /intake/employees/by-badge/{badge}`, `GET /intake/employees/lookup`, `POST/GET/PATCH /intake/encounters`, `POST /intake/encounters/{id}/convert` |
| `#/intake/suggestions` | E01-BL-005 accept → case / dismiss with a reason code | `GET /intake/suggestions`, `POST …/accept`, `POST …/dismiss` |
| `#/intake/reconciliation` | E01-BL-004 filters, **CSV export link**, weekly exception report (HSE / Nursing Head) | `GET /intake/reconciliation[/export\|/exceptions]` |
| `#/intake/incidents[/{id}]` | E01-BL-020 incident → cases (multi-casualty, status only) | `GET /intake/incidents/{id}/cases` |
| `#/intake/dashboard` | E01-BL-026 tiles, by-source / by-unit tables and a trend chart (with a data table). Suppressed cells show as **`<k`** | `GET /intake/dashboard` |
| `#/intake/dlq` | Dead-letter queue: replay / discard (OH_ADMIN, PLATFORM_ADMIN) | `GET /intake/dlq`, `POST …/replay`, `POST …/discard` |
| `#/caseload` | E02-F03 caseload against cap, utilisation bar (with a text %), out-of-office / cover, **drag a case onto a clinician** to reassign (HTML5 DnD), with the keyboard alternative **"Move to…"** on each case row | `GET /assignment/caseload`, `GET /queues/{queue}`, `POST /cases/{id}/assign`, `PUT /staff/{u}/availability` |
| `#/triage-audit` | E02-BL-016 draw a sample, review agree / disagree, agreement report | `/triage/audit/*` |
| `#/admin/protocols` | Triage protocols (view the flow, CMO approves) | `GET /triage/protocols`, `POST …/{code}/approve` |
| `#/admin/assignment-rules` | List / create / edit, plus a dry-run test | `/assignment/rules`, `POST /assignment/rules/test` |
| `#/admin/sla` | SLA policies (edit) | `GET /sla/policies`, `PUT /sla/policies/{id}` |
| `#/admin/intake-rules` | List / create / edit, plus a dry-run test with a sample incident | `/intake/rules`, `POST /intake/rules/test` |
| `#/admin/notifications` | Notification matrix: view, or edit the whole list (OH_ADMIN) | `GET/PUT /intake/notification-matrix` |
| `#/admin/recordability-mapping` | Local legal mapping (OH_ADMIN edits) | `GET/PUT /intake/recordability/mapping` |
| `#/admin/fitness-standards` | Fitness standards (versioned: each save creates a new version; body without `version`) – OH_ADMIN, CMO | `GET /masterdata/fitness-standards`, `PUT …/{code}` |
| `#/admin/restrictions` | Restriction catalogue (upsert by code) – OH_ADMIN, Nursing Head | `GET /masterdata/restrictions`, `PUT …/{code}` |
| `#/admin/job-demands` | Job-demand profiles: edit one as JSON (OH_ADMIN, Nursing Head), bulk **JSON import** (OH_ADMIN) | `GET /masterdata/job-demands?unit=`, `PUT …/{jobCode}`, `POST …/import` |
| `#/admin/encounter-templates` | The eight versioned templates (fields, mandatory, flags); CMO approves DRAFT versions | `GET /encounter-templates`, `POST /encounter-templates/{type}/approve` |
| `#/admin/terminology` | Loaded terminology releases (licence) and concept import (JSON or CSV, preview first) – OH_ADMIN | `GET /terminology/releases`, `POST /terminology/concepts/import` |

## Roles (UI gating – the server enforces)

Roles come from `GET /bff/session` (normalised: upper-case, `ROLE_` stripped). The rules live in `src/auth/roles.ts`
and are unit-tested.

| Role(s) | Sees |
|---|---|
| OH roles (`OH_CLINICIAN`, `OH_PHYSICIAN`, `AME`, `CMO`, `NURSING_HEAD`, `CASE_MANAGER`, `PSYCH_CLINICIAN`) | Queues, case view (incl. the E03/E05 tabs), escalations, handovers, reception (including Convert to case), suggestions, reconciliation, incident → cases, caseload, protocols, employee chart (read), device results, documents, active restrictions, RTW overdue reviews, encounter templates (view) |
| Clinicians (OH roles minus `CASE_MANAGER`) | Chart writes, encounters (create / sign / addenda / photos / AI), break-glass, unsigned notes, document signing. Only `PSYCH_CLINICIAN` starts mental-health encounters |
| Physicians (`OH_PHYSICIAN`, `AME`, `CMO`) + `OH_CLINICIAN` | Orders, results worklist, fitness decisions (nurses: non-licensed standards only), food-handler clearance. Physicians: IHR assessments |
| `AME`, `CMO` | Fitness sign-off queue, crew assessment. The CMO also: crew dashboard, roster feed, IHR / appeal decisions, template approval, break-glass report, CDS statistics |
| `CLINIC_RECEPTION`, `FIRST_AIDER` (without an OH role) | **Reception only**. Reception also sees the encounter list and **Scan documents** (E03-BL-016); first aiders see only their own registrations from this session, which they can enrich |
| `DPO` | **Privacy area only**: break-glass reviews and report, disclosure log, retention & legal hold. No clinical screen |
| `CREW_SCHEDULING` | Roster-feed reconciliation only (status-only data) |
| `NURSING_HEAD` | Also configuration screens, bulk reassign, drag-and-drop rebalancing, triage-audit sampling, dashboard |
| `OH_ADMIN` | Configuration screens (it is the only role that writes the notification matrix and the recordability mapping; also fitness standards, restriction catalogue, job demands incl. import, terminology), DLQ, dashboard, roster feed, retention schedule (read). No clinical screens |
| `OH_PHYSICIAN`, `AME`, `CMO` | Acknowledge / decide escalations. The CMO also approves protocols and schedules case conferences |
| `HSE_OFFICER` | Reconciliation (with exceptions) and incident → cases |
| `PLATFORM_ADMIN` | DLQ |
| `HR`, `MANAGER`, `HYGIENE_MANAGER`, claims roles, anything else | Full-page **Access restricted** (managers / HR never reach clinical screens). A route the user may not open shows an inline "Access restricted" panel |

## Security notes

* **BFF only** – tokens never reach the browser. Session `GET /bff/session`; login redirect
  `/oauth2/authorization/wellnessz`; logout `POST /logout` with `X-XSRF-TOKEN`. Every state-changing API call sends
  `X-XSRF-TOKEN` from the `XSRF-TOKEN` cookie. All fetches use `cache: 'no-store'`.
* **No PHI in browser storage or logs.** ESLint bans `console.*`, `localStorage` and `sessionStorage`; `src/security/log.ts`
  logs event codes only. The walk-in **offline retry queue lives in memory only** (`src/domain/offlineQueue.ts`). Its
  unit test asserts that local/session storage, IndexedDB and the Cache API are never touched. Pending items survive
  navigation inside the app but are lost when the tab closes, so the screen shows a warning and a `beforeunload`
  prompt while items are pending. Each registration carries a client-generated `clientRef` (`crypto.randomUUID()`), so
  replays are idempotent. Network errors, 5xx, 408 and 429 are retried; other 4xx responses are shown and not retried.
* **15-minute idle logout** (`src/security/idleTimeout.ts`).
* **Errors** are RFC 9457 problem details. The UI shows `detail` plus `correlationId` (falling back to the
  `X-Correlation-Id` header) and never renders other payload fields.
* **Client never decides urgency.** The protocol runner only chooses the next question and shows a preview. It submits
  only the answers on the active path, and the server re-evaluates them.
* **SLA is never shown by colour alone.** Each state has a shape symbol and a text label (On track ● / At risk ▲ /
  Breached ■ / Met ✓) plus the time remaining or overdue.
* **k-anonymity.** Dashboard cells marked suppressed (`null`, `{suppressed:true}` or a row flag), and positive counts
  below k (defence in depth), render as `<k`. k is read from `minGroupSize`, default 5.
* **Downloads** (attachments, the reconciliation CSV) are plain same-origin links through the BFF and are audited
  server-side.
* **CSP / headers**: same as manager-workspace. The build injects a strict CSP `<meta>` (no inline script or style,
  `connect-src 'self'`). nginx sends CSP with `frame-ancestors 'none'`, HSTS, nosniff, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, Permissions-Policy and COOP/CORP. `index.html` is `no-store`; `/assets/*` is immutable.

## Container

```bash
docker build -t wellnessz/oh-workspace:dev .
docker run --rm -p 8080:8080 --read-only --tmpfs /tmp wellnessz/oh-workspace:dev
```

The build stage is `node:24.22.2-alpine3.24`; it runs on `nginxinc/nginx-unprivileged:1.30.6-alpine3.24` (uid 101, port
8080, `/healthz`). The ingress must route `/bff`, `/oauth2`, `/login` and `/logout` on the same host to `wz-bff:8090`.

## Tests (Vitest)

| File | Covers |
|---|---|
| `src/domain/protocolRunner.test.ts` | Traversal: red flag → EMERGENCY hard stop, rule thresholds (`>=8` → URGENT, `>=4`/else → next), pruning of abandoned branches, range validation, cycles, unknown questions, condition grammar |
| `src/components/badges.test.tsx` | SLA badge for each state (class, symbol, label, time left / overdue, accessible title), urgency and safety badges |
| `src/auth/roles.test.ts` | Role gating: workspace access, reception-only, clinical, admin, CMO, HSE, route → feature |
| `src/domain/offlineQueue.test.ts` | In-memory retry queue: offline queueing, flush on reconnect, idempotent `clientRef`, retryable vs. rejected, ordering, **no storage use** |
| `src/api/links.test.ts` | CSV export link, query building and encoding, attachment and queue paths |
| `src/components/BulkResultList.test.tsx` | Bulk action result for each item, summary and rendering |
| `src/domain/dnd.test.ts` | Drag-and-drop reassign handler (payload, drop, same owner, missing reason, failure, keyboard path) |
| `src/components/format.test.tsx` | Problem-detail parsing and display (`detail` + correlationId) |
| `src/domain/suppression.test.ts` | Dashboard `<k` rendering |
| `src/auth/bffAuthClient.test.ts`, `src/security/idleTimeout.test.ts` | BFF client (XSRF header on mutating calls only), idle timeout |
| `src/screens/screens.smoke.test.tsx` | Initial render of every screen and tab (increment 1 and 2); the five-field registration form |
| `src/App.test.tsx` | Shell: DPO lands on break-glass reviews and sees only the Privacy group; DPO / HR / MANAGER cannot open a chart (no request is made); physicians get the Record and Fitness groups |
| `src/screens/chart/*.test.tsx` | Chart summary, tabs, FHIR/ODH links, **break-glass flow**, case-manager gating, **CDS override flow** (reason per alert, resubmit with `overrides`), problems / allergies / immunisations / jobs forms, terminology picker (search, favourites, recent), chart entry points (queue rows, search) |
| `src/screens/encounter/Encounter.test.tsx` | Template rendering, pre-sign checks, body map, diagnosis picker, save + sign (hash shown, locked), addendum, entered-in-error, photo consent + multipart, AI consent / not configured / explicit insertion (AI-assisted), new encounter (mental-health gating), unsigned notes |
| `src/screens/results/Results.test.tsx`, `src/screens/documents/Documents.test.tsx` | Audiometry grid + calibration rule, manual spirometry, vision/ECG validation, order sets + duplicate-test override, results list, worklist acknowledge + letter; documents list / sign / revoke / generate (merge fields) / share rules / new version / search / scan |
| `src/screens/case/E05Tabs.test.tsx` | Fitness decision (limitations, values, duration override, licensed standard, CMO sign-off, increment-1 form kept), job matching + duty proposal, RTW builder / activate / review, food handler, crew, IHR, summary, case tabs |
| `src/screens/fitness/WorkScreens.test.tsx`, `src/screens/admin/Admin.test.tsx`, `src/screens/dpo/Dpo.test.tsx` | Sign-off queue, active restrictions (filters, extend / close, dashboard), RTW overdue, crew dashboard `<5`, roster feed, CDS statistics; admin screens; DPO screens |
| `src/domain/increment2.test.ts` | CDS helpers, device validation / payloads, sign rules, diagnoses, body map, fitness rules, RTW plan rules, import parsers |

Interaction tests run in jsdom (`// @vitest-environment jsdom`) with Testing Library; API calls go through the real
`BffAuthClient` with a fake `fetch` (`src/testing/mockApi.ts`), so paths, query strings, bodies and problem details are
asserted exactly as they would reach the BFF.

## Increment 2 – contract notes

Places where the contract left room for interpretation, and what this UI does (please align the backend):

* **Body map** `{region, side, mechanism}`: `side` is the view (`FRONT` / `BACK`); laterality is part of `region`
  (e.g. `LEFT_KNEE`).
* **Favourites** `DELETE /terminology/favourites` sends `{system, code}` as a JSON body.
* **AI-assisted flag**: `PUT /encounters/{id}` carries `aiAssisted: true` once any part of an AI draft was inserted
  (the contract says the flag is set "when the clinician saves any part" without naming the field).
* **Document templates** `mergeFields`: strings are rendered as required text inputs; objects `{key, label, required}`
  are accepted too. Legal-hold release (`DELETE …/legal-hold`) sends `{reason}` as a body.
* **Retention due list** and **food-handler episodes** (`GET /cases/{id}/food-handler`) have no stated shape; the UI reads
  `documentId|id, documentNumber, title, recordClass, employeeId, retentionEndsAt|dueSince` and
  `status, reasonCategory, excludedFrom, expectedReturn, clearedAt` respectively.
* **Crew dashboard** cells may be numbers, `null` or `{suppressed:true}`; all render as `<5` (positive counts below 5 too).
* **Order category for an order set** is taken from the set's first item.
* **Crew / food-handler case tabs** are shown only when the case `safetyCategory` is `FLIGHT_CREW` / `CABIN_CREW` /
  `FOOD_HANDLER`.
* People call the `/crew/roster-feed…` aliases, never `/integration/**`.
