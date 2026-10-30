# Security verification tools

Run against a local or test stack you are authorised to test (see `docs/setup/01-Local-MacBook-Pro.md`).

| Tool | What it does |
|---|---|
| `security-verification.js` | Automated security checks (headers, token validation, anonymous access to every documented operation, actuator exposure, CORS, redirects, session cookie, CSRF, injection payloads, input validation, restricted classes, rate limit, logout). Writes `results/security-verification.json`. |
| `rbac-extract-policy.py` | Static extraction of the expected access policy from the Spring controllers (`@PreAuthorize`, URL rules, BFF workspace gating) → `expected-policy.json`. |
| `rbac-matrix.js` | Signs every human demo user in through the BFF and verifies the expected policy for all GET endpoints, object-level access, unauthenticated access and workspace gating. |
| `rbac-build-workbook.py` | Builds the role-based access matrix workbook (`.xlsx`) from the policy and the results. |

```bash
node tools/security/security-verification.js
```

```bash
python3 tools/security/rbac-extract-policy.py . target/rbac && node tools/security/rbac-matrix.js --policy target/rbac/expected-policy.json --out target/rbac && python3 tools/security/rbac-build-workbook.py target/rbac
```

Environment variables override the default local ports (`WZ_SEC_CORE_URL`, `WZ_SEC_BFF_URL`, `WZ_SEC_IDP_URL`,
`WZ_E2E_CORE_URL`, `WZ_E2E_BFF_URL`, `WZ_E2E_IDP_URL`, `WZ_E2E_*_URL` for the four apps). The per-user API rate limit
applies: set `WZ_RATE_LIMIT_PER_MINUTE=0` on the core for the full matrix run, and keep it on for `security-verification.js`
(check RATE-01).
