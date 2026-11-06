# IntiMed Python reference client

Thin client for the IntiMed machine APIs (IM-E07-BL-004, partial). Standard library only (`urllib`), Python ≥ 3.9.

* OAuth2 **client-credentials** token helper (`client_secret_post`), cached and refreshed 60 s before expiry
* FHIR R4: `search_observations`, `get_observation`, `list_devices`
* Machine API v1: `run_harness`, `get_harness_run`, `search_catalogue` (incl. partner badges), `events`
* `request(method, path, body)` for any other path of the OpenAPI document; `openapi()` fetches
  `/api/public/openapi.json`
* HTTPS required (plain `http://` only for `localhost` with `allow_insecure_localhost=True`); redirects are refused so a
  bearer token is never forwarded to another host

```python
import ssl
from intimed_client import IntiMedClient

ctx = ssl.create_default_context(cafile="intimed-data/pki/root-ca.pem")   # LOCAL: trust the platform root CA
c = IntiMedClient("https://localhost:9443", "my-client-id", "my-client-secret",
                  scope="system/Observation.read catalogue.read", ssl_context=ctx)
for m in c.search_catalogue("spirometer"):
    print(m.id, m.model, [b.level for b in m.badges])
print(c.search_observations(count=10)["total"])
```

API clients and their secrets are created in the console (*Integration & APIs › Create client*); keep the secret in your
host product's vault, never in source code.

Console-session APIs (`/api/intelligence/**`, `/api/partner/**`) require an interactive session with MFA and CSRF and
are intentionally not wrapped.

## Tests (offline)

```bash
cd intimed/sdk/python
python3 -m unittest discover -s tests -v
```

The test starts a stub HTTP server on `127.0.0.1` – no network access and no IntiMed instance needed.
