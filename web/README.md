# WellnessZ web front-ends (`web/`)

| App | Users | Dev port | Auth | Delivery |
|---|---|---|---|---|
| [`employee-app`](employee-app/README.md) | employees & crew | 5173 | BFF session (browser/PWA) · OIDC + PKCE (native) | web, installable PWA, Capacitor 8 iOS/Android (`com.wellnessz.app`) |
| [`manager-workspace`](manager-workspace/README.md) | managers, HR, OH | 5174 | BFF session | web |

Both apps are React 19 + TypeScript 6 + Vite 8, no UI framework and no router library (hash routing), with exact
pinned dependency versions and a committed `package-lock.json`.

```bash
cd web/employee-app       # or web/manager-workspace
npm ci && npm run lint && npm test && npm run build
npm run dev               # proxies /bff /oauth2 /login /logout → http://localhost:8090 (apps/wz-bff)
```

## Architecture

```
 Browser / PWA ──(same origin, WZSESSION cookie, X-XSRF-TOKEN)──> wz-bff :8090 ──(Bearer JWT)──> wz-core-app :8080 /api/v1
 Native app ──(system browser, Auth Code + PKCE)──> IdP (Keycloak / Entra ID / Okta …)
            └─(Bearer JWT, CORS)───────────────────────────────────────────────> wz-core-app /api/v1
```

* **Tokens never in the browser** for web/PWA (BFF pattern). Native keeps access tokens in memory and the refresh token
  in Keychain/Keystore via a `SecureStorage` plugin hook.
* **Status-only** for managers/HR – the workspace whitelists status fields client-side as defence in depth.
* **No PHI in logs or caches** – ESLint bans `console.*` and `localStorage`/`sessionStorage`; the service worker caches
  the app shell only; all API calls are `no-store`.
* **15-minute idle logout** in both apps.
* **Containers:** multi-stage Dockerfiles → `nginxinc/nginx-unprivileged` (non-root, port 8080, `/healthz`), strict
  security headers, SPA fallback, `index.html` never cached. Run with a read-only root FS plus a writable `/tmp`.
* **Ingress contract:** each app's host must route `/bff`, `/oauth2`, `/login`, `/logout` to `wz-bff:8090`
  (the BFF session cookie is SameSite=Lax and the apps use relative URLs).

| Image | Build context |
|---|---|
| `wellnessz/employee-app` | `web/employee-app` (`--build-arg VITE_CSP_CONNECT_SRC=...` only needed for native-style builds) |
| `wellnessz/manager-workspace` | `web/manager-workspace` |
