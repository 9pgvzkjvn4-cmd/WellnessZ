# WellnessZ – Partner Portal (`web/partner-portal`)

React 19 + TypeScript + Vite web app for **external partners** of WellnessZ Occupational Health, built against
[`docs/increment-04/API-Contract-E04-E08.md`](../../docs/increment-04/API-Contract-E04-E08.md):

* `PARTNER_PROVIDER` – hospitals, specialists, physiotherapy and telehealth partners (e.g. `partner1` / CITYHOSP, `physio1` / PHYSIOPLUS)
* `TPA_ADJUSTER` – TPA / insurer adjusters (e.g. `adjuster1` / GULFTPA)

Authentication is **BFF only** (same-origin session cookie, tokens never reach the browser). Partner access requires
**multi-factor authentication**: the IdP enforces it and the core API grants the partner roles only with MFA proof
(`amr` / `acr`, confidentiality rule 5). The portal therefore decides access on the roles from `GET /bff/api/me`, not
on the BFF session.

## Screens (hash routing)

| Route | Role | Screen | API (via `/bff/api`) |
|---|---|---|---|
| sign-in | – | MFA explanation, sign-in button, idle / expired notices | `GET /bff/session` |
| `#/referrals` | PARTNER_PROVIDER | Referral inbox: status filter (server), *report overdue only* (client), urgency / status / *Overdue* / *Due soon* badges; urgent first | `GET /partner/referrals?status=` |
| `#/referrals/{id}` | PARTNER_PROVIDER | Patient, service, reason, question, data set, letter + attachments download, history; actions by status: accept, decline (reason), set appointment (date-time, location, instructions), mark seen, upload report (file + summary), upload invoice (file, number, amount, currency, service date) | `GET /partner/referrals` (detail = list entry), `POST /partner/referrals/{id}/accept · decline · appointment · seen · report · invoice`, `GET /documents/{id}/content` |
| `#/telehealth` | PARTNER_PROVIDER | Sessions (join window 15 min before start → end, consent hint, layover), join opens the `joinUrl` in a new tab (`noopener,noreferrer`, http/https only, link fallback), secure chat | `GET /partner/telehealth/sessions`, `POST …/{id}/join`, `GET/POST …/{id}/messages` |
| `#/claims` | TPA_ADJUSTER | Claims where the organisation is payer: status filter, lost time, open tasks, unread messages | `GET /partner/claims?status=` |
| `#/claims/{id}` | TPA_ADJUSTER | Overview, RTW **status and milestones only**, shared tasks (complete own ADJUSTER tasks, create task), secure messages, consented documents | `GET /partner/claims/{id}` (+ `GET /claims/{id}/rtw` when the detail has no `rtw`), `GET/POST /claims/{id}/tasks`, `POST /claims/tasks/{taskId}/complete`, `GET/POST /claims/{id}/messages` |
| `#/preauthorisations` | TPA_ADJUSTER | Pending (`SUBMITTED`) requests by default; approve / partially approve (amount below the estimate) / decline, approval reference, valid until, note; request PDF | `GET /partner/preauthorisations?status=`, `POST /partner/preauthorisations/{id}/decision` |
| `#/documents` | both | Documents shared with the organisation, type filter, download | `GET /partner/documents`, `GET /documents/{id}/content` |

The header shows the **organisation code** (last segment of `orgScope` from `/me`, e.g. `/PARTNERS/CITYHOSP` → `CITYHOSP`).
`/me` without a partner role → *"Your account needs multi-factor sign-in or has no partner access – contact WellnessZ
Occupational Health"* (also for a 403 from `/me`); an account with only other WellnessZ roles → *"This portal is for
WellnessZ partner organisations"*. Opening the other role's route shows *Page not available*.

## Errors and confidentiality
* `404` → *"Not found or not shared with your organisation."* (also a referral id missing from the partner list).
* `422 clinical_content_not_allowed` with `field` highlights that control (`aria-invalid`, message, focus);
  `422 consent_required` explains the inactive consent; the problem `detail` is never rendered.
* Every screen shows a confidentiality hint; free-text fields carry the *no medical details* hint.
* Mappers in `src/api/*` whitelist the contract fields (no case ids, FHIR payloads, clinical notes, reserves, document
  shares / hashes, pre-auth justification).
* Uploads: PDF / JPEG / PNG, ≤ 10 MB (`src/api/upload.ts`), sent as multipart with the CSRF header.
* Money is the contract shape `{amount: "1234.50", currency}`; amounts are parsed / compared as decimal strings.
* All UI text lives in `src/strings.ts`.

## Quick start

```bash
npm ci
npm run dev      # http://localhost:5176 – proxies /bff, /oauth2, /login, /logout → $WZ_BFF_TARGET (default http://localhost:8090)
npm run lint
npm test         # Vitest – unit tests (node) + screen tests (jsdom + Testing Library)
npm run build    # tsc -b + vite build → dist/
```

In the `local` profile the core API does not require MFA (the demo realm has no OTP enrolment).

## Container

```bash
docker build -t wellnessz/partner-portal:dev .
docker run --rm -p 8080:8080 --read-only --tmpfs /tmp wellnessz/partner-portal:dev
```
Same image layout as the other web apps (`node` build stage → `nginx-unprivileged`, port 8080, `/healthz`, strict
security headers / CSP). The ingress for `partners.<domain>` must route `/bff`, `/oauth2`, `/login`, `/logout` on the
same host to `wz-bff:8090`.
