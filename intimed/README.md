# IntiMed – Intelligent Medical Device Integration Platform (local release 1.0.0-local)

IntiMed connects **any medical device by brand and model** to **the exact fields of any healthcare product's template**,
with two differentiators built in: **configuration intelligence** (a recommended configuration at every wizard step,
explainable, never auto-applied in UAT/PROD) and **configuration-time exchange testing** (a publish gate that proves the
data lands in the right template fields, with sealed evidence). **Security is a core feature**: every device connection
is mutual TLS with a per-endpoint identity, every tenant has its own data key, every action is in a tamper-evident audit
chain.

It is an **independent product** (ADR-01) stored next to the OHCM code base; it does not depend on any OHCM module. OHCM
is its first reference client through the published APIs.

## What runs on your MacBook

One self-contained process (Java 21 / Spring Boot 4) – the whole platform with simulators:

| Port | Listener | Security |
|---|---|---|
| `https://localhost:9443` | Web console, console API, FHIR R4 API, OAuth2 token, webhooks | TLS 1.2/1.3 (local root CA), session + CSRF + TOTP MFA for privileged roles; OAuth2 ES256 JWT for machines; strict CSP/HSTS |
| `:8443` | Device ingress (gateway / device-host / mobile envelopes) | **mTLS required**, endpoint identity re-checked per connection |
| `:2575` | MLLP over TLS (HL7 v2 ORU, ADT, ORM) | **mTLS required**, plaintext refused |
| `http://127.0.0.1:9444/actuator` | health, Prometheus metrics, SBOM | loopback only |

Data lives in `$INTIMED_HOME` (default `./intimed-data`): AES-encrypted H2 database (or PostgreSQL with the `postgres`
profile), local PKI (root + per-tenant CAs, keys wrapped), evidence packs (read-only), SIEM audit sink.

## Quick start

```bash
./mvnw package                       # build + 51 tests (unit, end-to-end integration, architecture)
```

```bash
INTIMED_HOME=~/IntiMed-Demo-Data java -jar intimed-platform/target/intimed-platform.jar --spring.profiles.active=demo
```

Open https://localhost:9443 and sign in as a demo persona (password `IntiMed-Demo-2026!`, synthetic data only – see
`docs/demo/Demo-Guide.md`). Without `demo` the platform creates one tenant and a random administrator password in
`$INTIMED_HOME/initial-admin.txt`. The macOS bootstrap (`deploy/macos/bootstrap.sh`) keeps the master key in the login
Keychain; `deploy/local/docker-compose.yml` runs it with PostgreSQL 16 and HashiCorp Vault.

The **demo pack** (separate download) wraps this jar with a bundled Java runtime and a double-click launcher.

## Map of the code base

| Path | Epic | What |
|---|---|---|
| `engine/` | IM-E01 | canonical pipeline, 13 grammar parsers behind P1–P11, terminology/UCUM, association, quality gates, router, MLLP-TLS and mTLS ingress, simulators |
| `engine/driver/`, `resources/drivers/` | IM-E01 | signed declarative driver packages (26), registry, validator, Ed25519 signatures |
| `catalogue/`, `resources/seed/` | IM-E02 | 225 devices / 24 families / 132 FDA codes, connectivity matrix, posture, recalls, openFDA feed |
| `console/`, `console/intelligence/` | IM-E03 | 8-step wizard, RR-01…RR-16 recommendations, policy engine, knowledge base, documentation assistant, governance, calibration, fleet |
| `harness/` | IM-E04 | test plan, XT-01…XT-16 over real transports, sealed evidence packs, publish gate |
| `templates/`, `capture/`, `static/assets/intimed-capture.js` | IM-E05 | TPL-01…TPL-22, adapters (FHIR/HL7 v2/REST/PDF), capture component |
| `security/` | IM-E06 | PKI, crypto (envelope per tenant), secrets (local / Vault), audit chain, auth (Argon2, TOTP, JWT), anomaly isolation, PHI masking |
| `api/` | IM-E07 | FHIR R4, signed webhooks, OAuth2 token, enrolment, JWKS |
| `deploy/`, `.github/workflows/intimed-ci.yml` | IM-E08 | Docker, compose, macOS bootstrap, CI |
| `tenancy/` | IM-E09 | tenants, residency, entitlements, metering, offboarding |
| `quality/`, `docs/regulatory/` | IM-E10 | traceability API, classification record, risk summary |
| `cli/Sdk.java` | IM-E01/E08 | `mdi-sdk`: keygen, sign, validate, test, simulate, enrol, send, gateway (encrypted store-and-forward) |
| `security-test-kit/` | IM-E06 | 13 scripts, 69 checks, report builder |
| `tools/content/` | – | generators for templates, drivers, option packs and traceability documents |

## Documentation

`docs/README.md` is the index. Key documents: traceability matrix and **backlog not applicable to the local scope with
reasons and strategies** (`docs/traceability/`), security architecture, integration guide, demo guide.
