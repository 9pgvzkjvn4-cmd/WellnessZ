# Security architecture & controls (local release)

Security is designed in at every layer and verified by the automated tests (51) and the security test kit (69 checks).

## Identity and trust

* **Endpoints** – every gateway, device host, mobile app, kiosk, vendor connector and the harness probe has its own X.509
  identity (EC P-256) issued by the tenant CA under the installation root. Enrolment: the console issues a single-use token
  (15 min, stored as SHA-256), the endpoint generates its key and a CSR locally, `POST /api/enrol` returns the chain. The
  certificate's fingerprint is bound to the endpoint record; every connection is re-checked (ACTIVE only), so revocation and
  isolation take effect immediately. Rotation issues a new token; certificates expiring within 30 days are reported.
* **Users** – Argon2id password hashes, ≥ 12 characters with mixed case and digits, lockout after 5 failures (15 min),
  login rate limiting, session fixation protection, `Secure; HttpOnly; SameSite=Strict` cookie, CSRF double-submit token,
  RFC 6238 TOTP mandatory for privileged roles (device administrator, SOC, CISO/DPO, SRE, tenant administrator).
* **Machines** – OAuth2 client credentials, ES256 JWT (15 min, `tenant` claim, SMART-style scopes), secrets shown once and
  stored as SHA-256, revocation; JWKS at `/.well-known/jwks.json`.
* **Role boundaries** (architecture §2.2) – device administrators, SOC, SRE and tenant administrators never read clinical
  content; template owners may change bindings only; auditors are read-only.

## Data protection

* Envelope encryption: a data-encryption key per tenant wrapped by the master KEK; PHI-bearing documents (observations,
  template instances, raw payloads, queues, subjects, enrolments) are encrypted as a whole with AES-256-GCM and AAD =
  tenant + document id. H2 file encryption (AES) adds a second layer locally; PostgreSQL uses TDE/disk encryption in the cloud.
* Master key from `INTIMED_MASTER_KEY` (Keychain on macOS) or a generated owner-only file (LOCAL); purpose keys derived.
* PKI and signing keys stored wrapped, never as clear PEM; keystore password derived at runtime.
* Minimisation: events and webhooks carry ids and statuses only; diagnostics, traces, audit details and evidence packs are
  PHI-masked; harness traffic never touches live association context; consent withdrawal stops storage of RPM data.

## Transport

No plaintext device port exists. MLLP-TLS and the ingress require client certificates (TLS 1.2/1.3); the console is
HTTPS-only with HSTS. Vendor clouds use HMAC-SHA256 webhooks with a 300 s replay window and a signature replay cache.
Outbound webhooks are HTTPS-only to an allow-listed set of hosts (SSRF guard), signed, retried and replayable.

## Application security

Strict CSP (`script-src 'self'`, no inline script or style), `frame-ancestors 'self'`, no-referrer, restrictive
Permissions-Policy, `Cache-Control: no-store`; DOM built with text nodes only; RFC 9457 problem responses without stack
traces; XXE-safe XML (DTDs disallowed); payload and frame size limits; ReDoS guard on serial grammars; idempotent ingest.

## Supply chain

Driver packages are signed (Ed25519) by the IntiMed release key (held offline) or tenant-trusted publishers, validated and
secret-scanned before load; CycloneDX SBOM in every jar (`/actuator/sbom`); CI runs tests, driver signature checks and
gitleaks.

## Detection and response

Hash-chained audit (HMAC-SHA256 per record, end-to-end verification, SIEM JSONL sink); anomaly rules AN-01…AN-07 (unknown
identity, plaintext attempts, rate, malformed bursts, new device serial, replay, fabricated streams with auto-isolation);
one-click isolation measured against the 15-minute target; release only after a passing exchange test of every bound
configuration (re-certification gate).

## Controls SC-01…SC-20 – local status

| Control | Local implementation | Verified by |
|---|---|---|
| SC-01 identity + mTLS | LocalPki, enrolment, DeviceListeners | kit 02, 03; IT `deviceListenersRefuse…`; XT-01/XT-13 |
| SC-02 zoning | endpoint zones, firewall request pre-fill, reference designs | design review (script 01 deferred) |
| SC-03 hardened images | – | deferred (edge/cloud) |
| SC-04 BLE pairing / USB allow-list | RR-03 policy, P1 `usbAllowList` | kit 18, IT `technicalChange…` |
| SC-05 secrets | SecretStore (local AES-GCM / Vault), scanner, `secret://` refs | kit 08, XT-13, unit tests |
| SC-06 SDLC/SBOM | CycloneDX, signed drivers, CI | kit 07, CI |
| SC-07 posture gate | posture register, JUSTIFY + security co-approval | demo TPL-17, IT |
| SC-08 audit | AuditService chain + SIEM sink + masking | kit 09, IT `auditChainDetectsTampering` |
| SC-09 anomaly/isolation | AnomalyService | kit 03/14, IT `anomalyIsolation…` |
| SC-10 encryption at rest | tenant DEK + H2 cipher | kit 10, IT `liveCapture…` |
| SC-11 API authZ | OAuth2 scopes, CSRF, CSP, object checks | kit 04, IT `httpSecurityControls` |
| SC-12 robustness | limits, XXE, DLQ | kit 03, unit tests |
| SC-13 rate limiting | token buckets | kit 12/14 |
| SC-14 time/certificates | skew correction, expiry checks, rotation | kit 13, XT-13 |
| SC-15 privacy | consent, minimisation, de-identified KB | kit 08 |
| SC-16 assurance | report builder output | organisational – deferred |
| SC-17 cloud posture | – | deferred |
| SC-18 tenant isolation | partitioned store, per-tenant keys | kit 17, IT `tenantIsolation…` |
| SC-19 guardrails | accept-all only in DEV, provenance, KB de-identified | kit 18, IT `acceptAll…` |
| SC-20 publish gate | hash-bound gate, sealed evidence | kit 19, IT `provisioning…` |
