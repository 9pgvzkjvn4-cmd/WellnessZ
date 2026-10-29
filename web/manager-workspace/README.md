# WellnessZ – Manager & HR Workspace (`web/manager-workspace`)

React 19 + TypeScript + Vite web app for **managers, HR and Occupational Health staff**. It shows
**status-only** information: case status, fitness-for-work status and restriction end date – never clinical data.
Authentication is **BFF only** (tokens never reach the browser).

## Screens (hash routing)

| Route | Screen | API (via BFF) |
|---|---|---|
| `#/` | Team case status – rows: caseRef, employeeDisplayName, status, fitnessStatus, restrictionUntil, updatedAt (`disclosure: STATUS_ONLY`), with the banner *"Status-only view – clinical information is held by Occupational Health"* | `GET /bff/api/cases` |
| `#/cases/{id}` | Case status detail (+ status history if provided) | `GET /bff/api/cases/{id}/status` |
| `#/refer` | **Management referral – module E01 (coming in increment)** – disabled placeholder form (`POST /cases/self-referral` is EMPLOYEE-only) | – |
| `#/analytics` | Analytics tile: cases by status per org unit; k-anonymity-suppressed groups render as **"<5 – suppressed"** | `GET /bff/api/analytics/kpis/cases-by-status?groupBy=orgUnit` |
| any | **Access restricted** page when the session has none of `MANAGER`, `HR`, `OH_CLINICIAN`, `OH_ADMIN`, `CASE_MANAGER`, `PSYCH_CLINICIAN`, `PLATFORM_ADMIN` | `GET /bff/session` |

Defence in depth:
* `src/api/statusOnly.ts` copies only whitelisted status fields from API responses, so an unexpected clinical field
  is dropped before rendering (unit-tested).
* `src/api/kpi.ts` treats any group with `suppressed: true`, a missing count **or a count below k** as suppressed, and
  never renders its number. k comes from the response (`minGroupSize`) and defaults to 5 (`WZ_ANALYTICS_MIN_GROUP`).
* Role gating (`src/auth/roles.ts`) is a UX guard only – the BFF (`/bff/api/workspace/**`) and core API enforce access.

KPI response (backend contract; parsing stays tolerant of `groups`/`rows`/`data`/bare arrays, `status`/`category`,
`count`/`value`/`n`). `category` is the case status; suppressed cells have no count:
```json
{ "kpi": "cases-by-status", "groupBy": "orgUnit", "minGroupSize": 5,
  "cells": [ { "group": "CABIN", "category": "OPEN",   "count": 12, "suppressed": false },
             { "group": "CARGO", "category": "TRIAGE", "suppressed": true } ] }
```

Case status values: `INTAKE, TRIAGE, ASSESSMENT, FITNESS_REVIEW, TREATMENT, CLAIMS, MONITORING, CLOSED`.
Fitness: `FIT, FIT_WITH_RESTRICTIONS, TEMPORARILY_UNFIT, PERMANENTLY_UNFIT, UNDER_REVIEW, NOT_ASSESSED`.

## Quick start

```bash
npm ci
npm run dev      # http://localhost:5174 – proxies /bff, /oauth2, /login, /logout → http://localhost:8090 (wz-bff)
npm run lint
npm test
npm run build    # tsc -b + vite build → dist/
```

Sign in as `manager1` or `hr1` (password `ChangeMe!123`); `employee1` sees the *Access restricted* page.

## Auth (BFF)
* Session: `GET /bff/session` → `{authenticated, name, roles}`; roles normalised (`ROLE_` stripped, upper-case).
* Login: redirect to `/oauth2/authorization/wellnessz`. Logout: `POST /logout` with `X-XSRF-TOKEN` from the
  `XSRF-TOKEN` cookie. All mutating API calls send the same header.
* Session cookie `WZSESSION` (HttpOnly, Secure, SameSite=Strict) – therefore the app and the BFF must share an origin
  (Vite proxy in dev, ingress path routing in Kubernetes).
* 15-minute idle timeout → automatic logout. No `console.*` (ESLint) – `src/security/log.ts` logs event codes only.

## Security headers / CSP
The production build injects a strict CSP `<meta>` (`default-src 'self'`, no inline script/style, `connect-src 'self'`,
`object-src 'none'`, `base-uri 'self'`, `form-action 'self'`). `nginx.conf` + `security-headers.conf` send CSP with
`frame-ancestors 'none'`, HSTS, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer`, `Permissions-Policy`, COOP/CORP; `index.html` is `no-store`, `/assets/*` immutable.

## Container

```bash
docker build -t wellnessz/manager-workspace:dev .
docker run --rm -p 8080:8080 --read-only --tmpfs /tmp wellnessz/manager-workspace:dev
```
Behind a TLS-intercepting corporate proxy pass its CA to `npm ci` as a BuildKit secret:
`docker build --secret id=npm_ca,src=/path/to/ca.pem ...` (never baked into the image). nginx listens on IPv4 `8080` only.

`node:24.22.2-alpine3.24` build stage → `nginxinc/nginx-unprivileged:1.30.6-alpine3.24` (uid 101, port 8080, `/healthz`).
The ingress must route `/bff`, `/oauth2`, `/login`, `/logout` on the same host to `wz-bff:8090`.

## MDM
The workspace is a desktop/browser app. On managed devices publish it as a web clip / Edge for Business managed
bookmark; Intune App Protection for Microsoft Edge (and Entra ID Conditional Access "require app protection policy")
or Workspace ONE Web can enforce copy/paste, download and screenshot restrictions. No app config keys are needed –
all configuration lives in the BFF.
