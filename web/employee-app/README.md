# WellnessZ – Employee / Crew App (`web/employee-app`)

One React 19 + TypeScript + Vite codebase delivered three ways:

| Channel | How | Auth mode |
|---|---|---|
| Browser | static build behind the BFF (same origin) | **BFF session** (`WZSESSION` cookie, no tokens in the browser) |
| Installable PWA | same build; `manifest.webmanifest` + app-shell service worker | **BFF session** |
| iOS / Android | Capacitor 8 wrapper (`com.wellnessz.app`) | **OIDC Auth Code + PKCE** via system browser |

Screens (hash routing, no router library): **Home** (`#/` – welcome, tiles, case cards), **Contact OH** (`#/refer` –
self-referral), **My advice** (`#/advice`), **My documents** (`#/documents`), **My return to work** (`#/rtw`),
**Job offers** (`#/job-offers`), **Crew declaration** (`#/fitness-declaration`), **Privacy – who received my
information** (`#/privacy`), **My consents** (`#/consents`), **Profile** (`#/profile`).

## Quick start

```bash
npm ci
npm run dev      # http://localhost:5173 – proxies /bff, /oauth2, /login, /logout → http://localhost:8090 (wz-bff)
npm run lint     # ESLint (typescript-eslint, react-hooks, no-console, no localStorage)
npm test         # Vitest unit tests (node environment, no DOM library – see "Tests")
npm run build    # type-check (tsc -b) + production build into dist/
npm run preview  # serve dist/ on :4173 (no proxy – use the container or the BFF for an end-to-end check)
```

Requires Node ≥ 22.12 (the Dockerfile uses Node 24 LTS). Start the backend first (`deploy/local` docker-compose,
then `wz-core-app` on 8080 and `wz-bff` on 8090) and sign in as `employee1` / `ChangeMe!123`.

> Dev login: the Vite proxy forwards `X-Forwarded-*` headers (`xfwd`) and keeps the `Host` header. If the BFF honours
> forwarded headers, the OAuth2 callback is `http://localhost:5173/login/oauth2/code/wellnessz` (proxied); otherwise it
> is `http://localhost:8090/...` – the redirect URI registered on the Keycloak `wellnessz-bff` client must match, and the
> BFF's post-login redirect should point back to `http://localhost:5173/`.

## API usage

All calls go through `AuthClient.api(path)` with paths relative to the core API `/api/v1`. The increment-1 contract
(E01 intake / E02 triage) is `docs/increment-01/API-Contract-E01-E02.md`.

| Screen | Call |
|---|---|
| Home | `GET /cases` (own cases) → projected to `id, caseRef, category, status, fitnessStatus, restrictionUntil, createdAt, updatedAt` (`src/api/cases.ts`); everything else is dropped |
| Contact OH | `GET /languages`, `GET /intake/templates?audience=EMPLOYEE&lang=`, `GET /intake/body-map`, `GET /me` (department pre-selection) → `POST /intake/referrals/self` → `POST /cases/{caseId}/attachments` (multipart `file`, one request per file) |
| My advice | `GET /me/advice` → `[{id, caseRef, title, text, sentAt}]`; **404 = empty list** |
| Crew declaration | `GET /me` (crew hint) → `POST /intake/triggers/fitness-declaration` `{reason, unfitFrom, expectedUntil?, details?}` |
| My consents | `GET /consents/me`, `POST /consents {purpose}` – purpose ∈ `TREATMENT_SHARING_PARTNER, EAP_REFERRAL, INSURER_SHARING, WEARABLE_DATA, HEALTH_CAMPAIGNS, RESEARCH_DEIDENTIFIED`; `DELETE /consents/{id}`. `GRANTED` consents are listed with *Withdraw*; `WITHDRAWN` ones appear under *Consent history* |
| Profile | `GET /me` → `{subject, username, displayName, employeeId, orgScope, roles, maxDisclosure}` |
| My documents | `GET /me/documents`, `GET /documents/{id}/content` (PDF), `POST /me/documents/{id}/acknowledge` `{}` |
| My return to work | `GET /me/rtw-plans`, `POST /me/rtw-plans/{id}/acknowledge` `{agree:true, comment?}` |
| Job offers | `GET /me/job-offers`, `POST /me/job-offers/{id}/respond` `{accept, comment?}` |
| Privacy | `GET /me/disclosures`, `GET /consents/me`, `DELETE /consents/{id}` (also revokes partner shares) |
| Home tiles | `GET /me/documents`, `/me/rtw-plans`, `/me/job-offers` (pending counts; each optional) |

The increment-2 contract (E03 record / E05 fitness & RTW) is `docs/increment-02/API-Contract-E03-E05.md`; the
employee-facing calls live in `src/api/portal.ts` (projection + calls, unit-tested against a real `BffAuthClient` over
a mocked `fetch`, i.e. `/bff/api/...` paths and the `X-XSRF-TOKEN` header).

Problem details (RFC 9457) are parsed into `ApiError.problem = {title, detail, code, field, correlationId}`; the UI
shows `title` or its own text, never `detail` (it may echo user input). A `FormData` body is sent as
`multipart/form-data` (no JSON content type, the browser sets the boundary) by both the BFF and the native client.

### Self-referral (E01-BL-006/009/010)
One scrolling page in five numbered sections with a sticky progress hint ("n of m required items completed"); target
< 3 minutes:
1. **Language** (`GET /languages`, fallback en/ar/fr built in; initial choice from the browser languages).
   UI strings for `en`, `ar`, `fr` live in `src/i18n/strings.ts`; template labels come from the server in the selected
   language (templates are re-fetched on switch, the selected template and answers are kept). **Arabic renders with
   `dir="rtl"`** (`dirFor()`); the body-map drawing is never mirrored (anatomical left/right). The language is sent as
   `language` and recorded on the case.
2. **Template** – pre-selected by department: `/me.departmentCode` if the backend provides it, else the first
   `orgScope` code matching a template's `departments`, else the only template. Marked "Suggested for your department".
3. **Category** (`CaseCategory`) and **dynamic fields** in template order: `TEXT`, `TEXTAREA`, `SELECT`, `MULTISELECT`
   (checkboxes), `BOOLEAN` (yes/no radios, so a required yes/no must be *answered*, "No" is valid), `DATE`; unknown types
   fall back to a text input. `required` and `maxLength` are validated client-side; answers of fields not in the
   selected template are never sent.
4. **Body map** (`src/components/BodyMap.tsx`) – SVG front/back drawing (pointer shortcut, `aria-hidden`) **plus a
   list of native toggle buttons with `aria-pressed`** for keyboard and screen readers; every region from
   `GET /intake/body-map` is in the list, including codes the drawing does not know. Codes like `HAND_R` / `KNEE_LEFT`
   are drawn from `src/components/bodyMapGeometry.ts`. `BODY_MAP` → `bodyParts`, `SYMPTOMS` (from
   `template.symptomOptions`) → `symptoms`. If the template has no `BODY_MAP` field the map is shown as optional.
5. **Description** (≤ 4000), **preferred contact**, **explicit consent** checkbox for OH assessment (required) and
   optional purposes (collapsed) → `consent:{assessment:true, purposes:[…]}`.
6. **Attachments** – JPEG/PNG/PDF, ≤ 5 MB each, max 5 (`src/intake/attachments.ts`; extension fallback when the WebView
   reports no MIME type). In the native app a second button opens the camera (`<input type=file accept="image/*"
   capture="environment">`). Files stay in memory only, are uploaded one by one **after** the case is created, and are
   dropped afterwards whether the upload succeeded or not (no previews, no object URLs, nothing in the service worker).
7. Errors: an error summary (focus moves to it; links focus the field without touching the hash route) plus
   `aria-invalid`/`aria-describedby` on each field. The **acknowledgement** shows the case reference
   (`{UNIT}-{YYYY}-{NNNNNN}`), the server acknowledgement text and "n of m attachments uploaded".

### Crew declaration (E01-BL-016)
Shown to everyone (nav "Crew declaration"), explained as "for flight and cabin crew"; non-crew (best-effort detection
from `/me` `jobCode`/`departmentCode`/`orgScope`) see a pointer to *Contact OH*. Reason, unfit-from (default today),
expected-until (≥ unfit-from), optional details (≤ 1000). The confirmation explains that **crew scheduling only sees
"unfit – pending"** and that an AME will make contact.

### Increment 2 portal screens (E03 / E05)
All four screens and the crew-declaration outcome render in the **app language** (header selector: English, العربية,
Français; initial value from the browser languages, kept in memory only). Strings are in `src/i18n/portal.ts`
(`PortalStrings`, same key set in en/ar/fr – unit-tested); Arabic renders with `dir="rtl"` on the screen section and the
navigation; reference numbers, phone numbers and e-mail addresses are wrapped in `<bdi>`. Errors are localised
(`errorText`, same rules as `describeError`: RFC 9457 `title` or own text, never `detail`); `409` gets an "already
answered – reload" text. List endpoints treat `404` as empty (as `/me/advice`). Every response is projected onto the
rendered fields (`toMyDocument`, `toRtwPlan`, `toJobOffer`, `normaliseDisclosures`); `shares`, `sha256`, internal review
notes etc. never reach UI state. Confirmation steps are inline (`ConfirmPanel`, `role="alertdialog"`, focus on the
confirm button) rather than `window.confirm`.

* **My documents** (E03-BL-015, E05-BL-003) – certificates, restriction notes, RTW plan, job offer, food-handler
  clearance, crew medical reports, result letters. `DRAFT`/`REVOKED` documents are never listed; `SUPERSEDED` ones are
  shown with "Replaced by a newer version". Order: needs acknowledgement → newest. Badges: *Please acknowledge*,
  *New*, *Acknowledged* (with date). **Open / download:** browser & PWA use a plain same-origin link to
  `/bff/api/documents/{id}/content` (`AuthClient.directUrl`) – *Open PDF* (`target="_blank"`, `rel="noopener
  noreferrer"`) and *Download* (`download="DOC-….pdf"`), which works on mobile browsers and in the installed PWA
  (the service worker never touches `/bff`). The native app cannot open an authenticated URL, so it fetches the PDF with
  the bearer token (`AuthClient.download`) and opens a short-lived object URL (revoked after 60 s; nothing written to
  the device). For a production-grade native viewer add `@capacitor/filesystem` + a file-opener plugin.
* **My return to work** (E05-BL-007) – active plans with start, goal, phases (weeks, hours/day, duties, functional
  restrictions with `{value}` substituted), review dates (done/next), manager acknowledgement and contact; *I agree to
  this plan* with an optional comment (≤ 1000), then the agreed state with date and comment.
* **Job offers** (E05-BL-008) – role, duties, location, shift, hours/week, pay basis + note, restrictions respected,
  start/end, respond-by (warning after the date; the server decides), contact; *Accept* / *Decline* with optional
  comment → confirmation step (electronic acceptance replaces a paper signature) → responded state.
* **Privacy – who received my information** (E03-BL-022) – disclosure log (date, recipient + type, document, purpose,
  *Access active* / *Access revoked*) and active consents with a two-step **withdraw** that explains that partner
  access stops immediately and that the effect is logged; after withdrawal consents **and** the disclosure log are
  reloaded. *My consents* (`#/consents`) remains for giving consent and the history (its withdraw prompt now carries
  the same explanation).
* **Crew declaration outcome** (E05-BL-010) – after submit: case reference, *do not operate*, "acknowledged by
  {acknowledgementDueBy}", the server's `nextSteps[]` (fallback to the built-in AME steps), the fixed
  "scheduling only sees unfit – pending" statement and the **AME contact** (tel:/mailto: links).
* **Home** – tiles for the four screens with "n items need your attention" (certificates/notes to acknowledge, active
  plans not yet agreed, open offers); a failing endpoint just omits its count.

#### Contract points resolved in the app
| Topic | Contract | App behaviour |
|---|---|---|
| Which documents can be acknowledged | "certificate / RTW plan / restriction note" | `FITNESS_CERTIFICATE`, `RESTRICTION_NOTE`, `FOOD_HANDLER_CLEARANCE`, `CREW_MEDICAL_REPORT`, `CABIN_CREW_MEDICAL_REPORT` via `/me/documents/{id}/acknowledge`. The `RTW_PLAN` document links to *My return to work* (plan acknowledgement with `agree`/comment) and `JOB_OFFER` to *Job offers*, so nothing is acknowledged twice. |
| "Unread" state | no read marker in `Document` | *New* until opened in this session (memory only) or a server `readAt`/`viewedAt` if ever provided; acknowledgeable documents show *Please acknowledge* until `acknowledgedAt` is set. |
| Acknowledge response | not pinned | `acknowledgedAt` from the body if present, else the client time; `204` accepted. |
| RTW disagreement | body is `{agree:true, comment?}` | Only agreement is sent; the screen tells the employee to contact OH instead of confirming if they disagree. |
| RTW contact | `RtwPlan` has no contact field | `contact{name,email,phone}` or `contactName/contactEmail/contactPhone` if present, else a link to *Contact OH*. Legacy `reviewDates[]` is accepted when `reviews[]` is absent. |
| Offer restrictions | request `{code,value}` (no label) | catalogue `label` with `{value}` if present, else humanised code + value. |
| `respondBy` | date or timestamp | a date counts to the end of that local day; afterwards a warning is shown but replying stays possible (server decides, `409` is explained). |
| Respond response | not pinned | uses the returned `JobOffer` if any, else sets `ACCEPTED`/`DECLINED` + now locally. |
| Disclosure rows | no `id`; `purpose` code or text | key from row content; known consent-purpose codes are translated, other codes humanised, free text shown as sent. |
| Declaration `nextSteps` / `ameContact` | element types not pinned | steps: strings or `{text|title|description|label}`; contact: `{name, phone, email, hours}` or a plain string (rendered as text). |
| Comment length | not pinned | ≤ 1000 characters client-side. |

### Home
Case cards: case reference (unit + year decoded from `{UNIT}-{YYYY}-{NNNNNN}`, `OH` = Occupational Health), employee-
friendly case status (incl. `MERGED`), fitness-for-work status (incl. `UNFIT_PENDING` → "Unfit – pending
assessment"), restriction end date, last update.

## Auth modes (`src/auth`)

`AuthClient` (`src/auth/AuthClient.ts`) is the only thing screens use (`api`, plus `download` for binary content and
`directUrl` – a same-origin link in BFF mode, `undefined` in native mode). `createAuthClient()` picks the implementation:

### a) Browser / PWA → BFF (`BffAuthClient`)
* `GET /bff/session` → `{authenticated, name, roles}` (roles normalised: `ROLE_` prefix stripped, upper-case).
* Login: full-page redirect to `/oauth2/authorization/wellnessz`.
* API: `/bff/api/...` (BFF relays the access token to core `/api/v1/...`).
* Mutating requests and `POST /logout` send `X-XSRF-TOKEN` read from the `XSRF-TOKEN` cookie.
* Logout uses `redirect: 'manual'`; if the BFF answers 2xx with a `Location` header (RP-initiated logout URL) the app
  navigates there, otherwise to `/`.

### b) Native (Capacitor) → OIDC + PKCE (`NativeOidcAuthClient`)
Selected when `Capacitor.isNativePlatform()`; follows RFC 8252 (OAuth for native apps):
1. OIDC discovery from `wz.oidcIssuer`.
2. PKCE S256 verifier/challenge, `state` and `nonce` generated with **WebCrypto** (`src/auth/pkce.ts`).
3. Authorization request opened in the **system browser** (`@capacitor/browser` → SFSafariViewController / Custom Tabs).
4. Redirect `com.wellnessz.app://callback` received via `@capacitor/app` `appUrlOpen`; `state` + `nonce` verified.
5. Code exchanged at the token endpoint (public client `wellnessz-mobile`, no secret).
6. **Access/ID token in memory only.** **Refresh token in `SecureStorage`** (see below). Never `localStorage`
   (ESLint `no-restricted-globals` enforces this).
7. Silent refresh (single-flight) before expiry and once on 401; logout revokes the refresh token (RFC 7009) when the
   IdP advertises a `revocation_endpoint`, then clears local state.

The core API must allow the WebView origins via CORS (`WZ_CORS_ORIGINS=capacitor://localhost,https://localhost`),
and the IdP must allow CORS for the token endpoint (Keycloak: client *Web origins*; Entra ID: register the app as
"Mobile and desktop applications" platform with the custom-scheme redirect).

### Secure storage
`src/auth/secureStorage.ts` defines `SecureStorage` with an **in-memory fallback** (user signs in again after the app
is killed). For production register a Keychain/Keystore-backed plugin, e.g.
[`capacitor-secure-storage-plugin`](https://github.com/martinkasa/capacitor-secure-storage-plugin) or
`@aparajita/capacitor-secure-storage`:

```ts
// src/main.tsx – before bootstrap()
import { SecureStoragePlugin } from 'capacitor-secure-storage-plugin';
import { registerSecureStorage } from './auth/secureStorage';
registerSecureStorage({
  name: 'keychain-keystore', persistent: true,
  get: async (key) => { try { return (await SecureStoragePlugin.get({ key })).value; } catch { return null; } },
  set: async (key, value) => { await SecureStoragePlugin.set({ key, value }); },
  remove: async (key) => { try { await SecureStoragePlugin.remove({ key }); } catch { /* absent */ } },
});
```

### Biometric unlock
When `wz.requireBiometric=true`, a stored refresh token is used only after `verifyBiometric()` succeeds. Register an
implementation with `registerBiometricVerifier()` (e.g. `@aparajita/capacitor-biometric-auth`). Without one the app
**fails closed** (interactive sign-in required).

## Runtime configuration & MDM (`src/config`)

Precedence: **MDM managed app config → `/config.json` → built-in defaults.**

| Key | Meaning | Default (`public/config.json`) |
|---|---|---|
| `wz.apiBaseUrl` | Core API base **including** `/api/v1` (native mode only) | `http://localhost:8080/api/v1` |
| `wz.oidcIssuer` | OIDC issuer URI | `http://localhost:8180/realms/wellnessz` |
| `wz.oidcClientId` | Public client id | `wellnessz-mobile` |
| `wz.redirectUri` | Custom-scheme redirect | `com.wellnessz.app://callback` |
| `wz.requireBiometric` | `true`/`false` | `false` |

`ManagedConfig` (`src/config/managedConfig.ts`) abstracts the platform store:
* **Built-in plugin hook:** if a native plugin named `ManagedConfigurations` is installed (API-compatible with
  `@capawesome/capacitor-managed-configurations`: `getString/getBoolean({key})`), it is used automatically
  (iOS `com.apple.configuration.managed` dictionary, Android `RestrictionsManager`).
* **Custom provider:** call `registerManagedConfig({ name, read })` at startup (e.g. Intune MAM SDK / Workspace ONE SDK bridge).
* Browser/PWA: no MDM; `/config.json` can be replaced at deploy time (ConfigMap mount – it is served `no-store`).

Android needs `res/xml/app_restrictions.xml` declaring the keys (+ `<meta-data android:name="android.content.APP_RESTRICTIONS" …>`
in the manifest) after `npx cap add android`.

### Microsoft Intune
* **App Configuration policy** (Managed devices → iOS/Android Enterprise): add the `wz.*` keys above as
  configuration keys (string / boolean).
* **App Protection Policy (MAM)**: for full MAM (copy/paste restrictions, screenshot blocking, selective wipe,
  conditional launch) the native projects must integrate the **Intune App SDK** (iOS) / **Intune App SDK for Android**
  (or be wrapped with the Intune App Wrapping Tool). The web layer needs no change; register a `ManagedConfig`
  provider that reads the MAM-delivered app config if you use MAM-WE (unenrolled devices).
* Use Entra ID Conditional Access to require an app protection policy for the `wellnessz-mobile` app registration.

### VMware/Omnissa Workspace ONE UEM
* Add the app, then under *Assignment → Application Configuration* enter the `wz.*` key/value pairs (delivered via
  the same managed app config channel). Per-app VPN/Tunnel can be used to reach on-prem APIs.
* Workspace ONE SDK features (DLP, SSO, compliance) require the SDK in the native projects.

Jamf Pro uses the same iOS managed app config dictionary (`<dict><key>wz.apiBaseUrl</key><string>…</string>…</dict>`).

## Native builds (Capacitor 8)

Native projects are not committed yet – generate them:

```bash
npm ci
npm run build                 # web assets → dist/
npx cap add ios               # requires macOS + current Xcode (see Capacitor 8 environment setup docs)
npx cap add android           # requires current Android Studio + JDK 21
npx cap sync                  # copy dist/ + plugins into native projects (repeat after each web build: npm run cap:sync)
npx cap open ios              # or: npx cap open android
```

After adding the platforms:
* **Deep link** `com.wellnessz.app://callback`:
  * iOS – `ios/App/App/Info.plist`: add `CFBundleURLTypes` → `CFBundleURLSchemes` = `com.wellnessz.app`.
  * Android – `android/app/src/main/AndroidManifest.xml`, in the main activity:
    ```xml
    <intent-filter>
      <action android:name="android.intent.action.VIEW" />
      <category android:name="android.intent.category.DEFAULT" />
      <category android:name="android.intent.category.BROWSABLE" />
      <data android:scheme="com.wellnessz.app" android:host="callback" />
    </intent-filter>
    ```
  * Prefer Universal Links / App Links (https redirect) for production to prevent scheme hijacking; set `wz.redirectUri` accordingly.
* **CSP for native**: the app calls the IdP and API directly, so build with the allowed origins:
  `VITE_CSP_CONNECT_SRC="'self' https://login.example.com https://api.example.com" npm run build`.
* Disable WebView debugging and backups in release builds (`android:allowBackup="false"`, already
  `webContentsDebuggingEnabled: false` in `capacitor.config.ts`).

### Privacy screen
`src/security/privacyScreen.ts` hides the UI when the page becomes hidden (web/PWA best effort). On native, add
`@capacitor-community/privacy-screen` (Android `FLAG_SECURE`, iOS snapshot overlay) – or enforce screenshot blocking via
Intune APP – so health information never appears in the app switcher or screenshots.

## Security notes
* **Idle timeout:** 15 minutes without interaction (wall-clock based, so time in background counts) → automatic logout.
* **No PHI in logs:** `console.*` is banned by ESLint; `src/security/log.ts` only logs static event codes, HTTP status
  and error class names – never payloads, form values, tokens or URLs with identifiers.
* **Service worker** (`public/sw.js`) caches the **app shell only** (`/`, `/assets/*`, icons, manifest). `/bff`, `/api`,
  `/oauth2`, `/login`, `/logout`, `/config.json`, anything with a query string, non-GET and cross-origin requests are
  never intercepted or cached (unit-tested in `src/pwa/serviceWorker.test.ts`). It is registered only in production
  web builds, never inside the native app.
* **CSP:** the production build injects a strict `<meta http-equiv="Content-Security-Policy">` (no inline script or
  style, `object-src 'none'`, `base-uri 'self'`, `form-action 'self'`). nginx additionally sends CSP with
  `frame-ancestors 'none'`, HSTS, `X-Content-Type-Options`, `Referrer-Policy: no-referrer`, `Permissions-Policy`,
  COOP/CORP. The dev server has no CSP (Vite HMR needs inline scripts).
* All API requests use `cache: 'no-store'`; nginx serves `index.html` and `config.json` with `no-store`.

## Tests
`npm test` runs Vitest in the node environment without a DOM/testing library (no extra dependencies). Stateful screens
are split into a stateful container and a **hook-free view** (`SelfReferralForm`, `BodyMap`, `TemplateField`,
`AdviceList`, `CaseCard`): views are rendered with `react-dom/server` for markup/ARIA assertions, and
`src/test/tree.ts` expands their element tree so tests call the real `onClick`/`onChange` handlers. Coverage includes
body-map toggling (SVG + keyboard toggle-button list), template-driven rendering and required validation, attachment
type/size/count rules and multipart upload, RTL switching, advice 404 handling, the case-card projection and the
fitness declaration. Increment 2 adds `src/api/portal.test.ts` (projections, ordering, and the acknowledge / agree /
accept-decline / withdraw flows through a real `BffAuthClient` over a mocked `fetch` – paths, bodies, CSRF header,
404-as-empty, 401/4xx/5xx errors, native PDF opening), one view test per new screen (`MyDocuments`, `MyReturnToWork`,
`JobOffers`, `Privacy`, `FitnessDeclaration` outcome: rendering, empty and busy states, confirmation steps, handler
calls, Arabic RTL + French smoke) and `src/i18n/portal.test.tsx` (key parity en/ar/fr, fallbacks, localised errors,
home tiles). Helpers: `src/test/fetch.ts`, `src/test/client.ts`, `src/test/portalFixtures.ts` (contract-shaped
payloads).

## Container

```bash
docker build -t wellnessz/employee-app:dev .
docker run --rm -p 8080:8080 --read-only --tmpfs /tmp wellnessz/employee-app:dev   # http://localhost:8080
```
Behind a TLS-intercepting corporate proxy pass its CA to `npm ci` as a BuildKit secret:
`docker build --secret id=npm_ca,src=/path/to/ca.pem ...` (never baked into the image). nginx listens on IPv4 `8080` only.


Multi-stage: `node:24.22.2-alpine3.24` build → `nginxinc/nginx-unprivileged:1.30.6-alpine3.24` (uid 101, port 8080,
`/healthz` probe). The container serves static files only: the ingress/gateway **must route `/bff`, `/oauth2`,
`/login`, `/logout` on the same host to `wz-bff:8090`** (SameSite=Lax session cookie + relative URLs). nginx returns
404 for those paths if they ever reach it, so misrouting never falls back to `index.html`.

## Layout
```
capacitor.config.ts        Capacitor 8 config (appId com.wellnessz.app, webDir dist)
public/                    manifest, sw.js (app shell only), config.json (runtime defaults), icons
src/auth/                  AuthClient, BFF client, native OIDC+PKCE client, PKCE, SecureStorage, biometric hook
src/config/                ManagedConfig (MDM) + runtime config loader
src/security/              idle timeout, PHI-safe logger, privacy screen
src/screens/               Home, SelfReferral (+ SelfReferralForm view), FitnessDeclaration, MyAdvice, MyDocuments,
                           MyReturnToWork, JobOffers, Privacy, Consents, Profile
src/api/                   core API shapes, case/advice projections, portal.ts (increment-2 employee calls)
src/intake/                referral form state/validation, attachment rules + upload, fitness declaration
src/i18n/                  en/ar/fr strings for the referral form (strings.ts) and portal screens (portal.ts), RTL helper
src/components/            ui, portal (PortalSection, ConfirmPanel, ContactLines), BodyMap (+ geometry), TemplateField
src/test/                  test-only helpers (element-tree walker, fixtures)
nginx.conf, security-headers.conf, Dockerfile
```
