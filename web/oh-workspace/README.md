# WellnessZ – OH Workspace (`web/oh-workspace`)

The clinical console for **Occupational Health**: OH nurses / case managers, OH physicians, AMEs, the CMO, the Nursing
Head, OH administrators, plus **clinic reception and first aiders** (walk-in / emergency registration only).
It delivers increment 1: **E01 intake** and **E02 triage, queues, assignment and SLA**.

The API contract is [`docs/increment-01/API-Contract-E01-E02.md`](../../docs/increment-01/API-Contract-E01-E02.md).
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
| ↳ `fitness` | Record the fitness status and restriction end date | `POST /cases/{id}/fitness` |
| ↳ `status` | Status transitions. A 409 / 422 shows the server `detail`, the correlationId and a link to the missing step (recordability / fitness / checklist) | `POST /cases/{id}/transition` |
| `#/employees/{id}/timeline` | E01-BL-018 employee timeline | `GET /employees/{id}/timeline` |
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

## Roles (UI gating – the server enforces)

Roles come from `GET /bff/session` (normalised: upper-case, `ROLE_` stripped). The rules live in `src/auth/roles.ts`
and are unit-tested.

| Role(s) | Sees |
|---|---|
| OH roles (`OH_CLINICIAN`, `OH_PHYSICIAN`, `AME`, `CMO`, `NURSING_HEAD`, `CASE_MANAGER`, `PSYCH_CLINICIAN`) | Queues, case view, escalations, handovers, reception (including Convert to case), suggestions, reconciliation, incident → cases, caseload, protocols |
| `CLINIC_RECEPTION`, `FIRST_AIDER` (without an OH role) | **Reception only**. Reception also sees the encounter list; first aiders see only their own registrations from this session, which they can enrich |
| `NURSING_HEAD` | Also configuration screens, bulk reassign, drag-and-drop rebalancing, triage-audit sampling, dashboard |
| `OH_ADMIN` | Configuration screens (it is the only role that writes the notification matrix and the recordability mapping), DLQ, dashboard. No clinical screens |
| `OH_PHYSICIAN`, `AME`, `CMO` | Acknowledge / decide escalations. The CMO also approves protocols and schedules case conferences |
| `HSE_OFFICER` | Reconciliation (with exceptions) and incident → cases |
| `PLATFORM_ADMIN` | DLQ |
| anything else | Full-page **Access restricted**. A route the user may not open shows an inline "Access restricted" panel |

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
| `src/screens/screens.smoke.test.tsx` | Initial render of every screen and tab; the five-field registration form |
