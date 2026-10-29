# WellnessZ web front-ends (`web/`)

| App | Users | Dev port | Auth | Delivery |
|---|---|---|---|---|
| [`employee-app`](employee-app/README.md) | employees & crew | 5173 | BFF session (browser/PWA) · OIDC + PKCE (native) | web, installable PWA, Capacitor 8 iOS/Android (`com.wellnessz.app`) |
| [`manager-workspace`](manager-workspace/README.md) | managers, HR, OH | 5174 | BFF session | web |
| [`oh-workspace`](oh-workspace/README.md) | OH nurses, physicians, AME, CMO, Nursing Head, OH admin, clinic reception, first aiders | 5175 | BFF session | web |

All apps are React 19 + TypeScript 6 + Vite 8, no UI framework and no router library (hash routing), with exact
pinned dependency versions and a committed `package-lock.json`.

```bash
cd web/employee-app       # or web/manager-workspace, web/oh-workspace
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
| `wellnessz/oh-workspace` | `web/oh-workspace` |

## OH workspace (`oh-workspace`) – increment 1 (E01 intake, E02 triage / queues / SLA)

Clinical console built against [`docs/increment-01/API-Contract-E01-E02.md`](../docs/increment-01/API-Contract-E01-E02.md):
work queues with SLA board and bulk actions, the case view (triage protocol runner, recordability wizard, safety
checklists, assignment / handover, escalation, complexity, duplicates, tasks, timeline, fitness, status transitions),
intake review (suggestions, reconciliation + CSV export, incident → cases, DLQ, dashboard), walk-in / emergency
registration with an **in-memory** offline retry queue (no PHI in browser storage), caseload rebalancing (drag-and-drop
with a keyboard alternative) and the E01/E02 configuration screens. Role gating in the UI is convenience only; the BFF
and core API enforce access. Same stack, security conventions, CSP, nginx and container layout as the other apps.
See [`oh-workspace/README.md`](oh-workspace/README.md).
