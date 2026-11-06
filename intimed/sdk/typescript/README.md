# IntiMed TypeScript reference client

Thin, typed, `fetch`-based client for the IntiMed machine APIs (IM-E07-BL-004, partial). No runtime dependencies;
Node ≥ 18, Deno or browsers.

* `ClientCredentials` – OAuth2 client-credentials token helper (`client_secret_post`), cached, refreshed 60 s early
* FHIR R4: `searchObservations`, `getObservation`, `listDevices`, `postObservation`
* Machine API v1: `runHarness`, `getHarnessRun`, `searchCatalogue` (incl. partner badges), `events`
* `request(method, path, body)` for any other path of the OpenAPI document; `openApi()` fetches
  `/api/public/openapi.json`
* HTTPS required (plain `http://` only for `localhost` with `allowInsecureLocalhost: true`); `redirect: 'error'` so a
  bearer token is never forwarded to another host

```ts
import { IntiMedClient } from '@intimed/client';

const intimed = new IntiMedClient({
  baseUrl: 'https://intimed.example.org',
  clientId: process.env.INTIMED_CLIENT_ID!,
  clientSecret: process.env.INTIMED_CLIENT_SECRET!,   // from your vault – never commit it
  scope: 'system/Observation.read catalogue.read',
});

const models = await intimed.searchCatalogue('spirometer');
console.log(models.map(m => `${m.id} ${m.model} ${m.badges?.map(b => b.level).join(',') ?? ''}`));
const bundle = await intimed.searchObservations({ count: 10 });
```

For the LOCAL setting (self-signed platform root CA) run Node with `NODE_EXTRA_CA_CERTS=intimed-data/pki/root-ca.pem`.

Console-session APIs (`/api/intelligence/**`, `/api/partner/**`) require an interactive session with MFA and CSRF and
are intentionally not wrapped; see the OpenAPI document.

## Build

```bash
cd intimed/sdk/typescript
tsc -p tsconfig.json        # emits dist/ (ES2022 modules + .d.ts)
```
