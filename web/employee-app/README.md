# WellnessZ – Employee / Crew App (`web/employee-app`)

One React 19 + TypeScript + Vite codebase delivered three ways:

| Channel | How | Auth mode |
|---|---|---|
| Browser | static build behind the BFF (same origin) | **BFF session** (`WZSESSION` cookie, no tokens in the browser) |
| Installable PWA | same build; `manifest.webmanifest` + app-shell service worker | **BFF session** |
| iOS / Android | Capacitor 8 wrapper (`com.wellnessz.app`) | **OIDC Auth Code + PKCE** via system browser |

Screens (hash routing, no router library): **Home** (welcome + my cases with status), **Contact OH** (self-referral),
**My consents** (list / grant / withdraw), **Profile** (`GET /me` + app info).

## Quick start

```bash
npm ci
npm run dev      # http://localhost:5173 – proxies /bff, /oauth2, /login, /logout → http://localhost:8090 (wz-bff)
npm run lint     # ESLint (typescript-eslint, react-hooks, no-console, no localStorage)
npm test         # Vitest unit tests
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

All calls go through `AuthClient.api(path)` with paths relative to the core API `/api/v1`:

| Screen | Call |
|---|---|
| Home | `GET /cases` (own cases) |
| Contact OH | `POST /cases/self-referral` `{category, description, preferredContact}` – category ∈ `INJURY, ILLNESS, DECREASE_IN_FITNESS, PSYCHOLOGICAL, SUBSTANCE, EXPOSURE, OTHER`; preferredContact (free string ≤ 20, UI offers `EMAIL, PHONE, IN_APP`) → case object |
| My consents | `GET /consents/me`, `POST /consents {purpose}` – purpose ∈ `TREATMENT_SHARING_PARTNER, EAP_REFERRAL, INSURER_SHARING, WEARABLE_DATA, HEALTH_CAMPAIGNS, RESEARCH_DEIDENTIFIED`; `DELETE /consents/{id}`. `GRANTED` consents are listed with *Withdraw*; `WITHDRAWN` ones appear under *Consent history* |
| Profile | `GET /me` → `{subject, username, displayName, employeeId, orgScope, roles, maxDisclosure}` |

## Auth modes (`src/auth`)

`AuthClient` (`src/auth/AuthClient.ts`) is the only thing screens use. `createAuthClient()` picks the implementation:

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

## Container

```bash
docker build -t wellnessz/employee-app:dev .
docker run --rm -p 8080:8080 --read-only --tmpfs /tmp wellnessz/employee-app:dev   # http://localhost:8080
```
Behind a TLS-intercepting corporate proxy pass its CA to `npm ci` as a BuildKit secret:
`docker build --secret id=npm_ca,src=/path/to/ca.pem ...` (never baked into the image). nginx listens on IPv4 `8080` only.


Multi-stage: `node:24.22.2-alpine3.24` build → `nginxinc/nginx-unprivileged:1.30.6-alpine3.24` (uid 101, port 8080,
`/healthz` probe). The container serves static files only: the ingress/gateway **must route `/bff`, `/oauth2`,
`/login`, `/logout` on the same host to `wz-bff:8090`** (SameSite=Strict session cookie + relative URLs). nginx returns
404 for those paths if they ever reach it, so misrouting never falls back to `index.html`.

## Layout
```
capacitor.config.ts        Capacitor 8 config (appId com.wellnessz.app, webDir dist)
public/                    manifest, sw.js (app shell only), config.json (runtime defaults), icons
src/auth/                  AuthClient, BFF client, native OIDC+PKCE client, PKCE, SecureStorage, biometric hook
src/config/                ManagedConfig (MDM) + runtime config loader
src/security/              idle timeout, PHI-safe logger, privacy screen
src/screens/               Home, SelfReferral, Consents, Profile
nginx.conf, security-headers.conf, Dockerfile
```
