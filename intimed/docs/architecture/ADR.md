# Architecture decisions (local release)

The product ADRs ADR-01…ADR-12 apply. Local implementation decisions:

| ADR | Decision | Consequence |
|---|---|---|
| ADR-01 | Separate product under `intimed/` with its own parent POM and package `io.intimed`; no dependency on OHCM modules | OHCM untouched; integration only through APIs |
| ADR-02 | Canonical pipeline + declarative signed driver packages (manifest · transport · grammar · semantics · bindings · recommendations · tests · docs) | New devices are configuration; 26 drivers prove 11 options and 22 templates |
| ADR-03 | Recommendations = rules RR-01…RR-16 + de-identified knowledge base; no LLM decides values; documentation assistant is retrieval with citations | Explainable, auditable; LLM explanation layer deferred behind a policy gateway |
| ADR-04 | One harness service for wizard, driver CI and partners; tests run through the real device listeners with the harness's own mTLS identity | Evidence proves transport security, not just parsing |
| ADR-05 | Host-agnostic Template Definitions, many-to-many bindings, four adapters | Any host form; FHIR Questionnaire import |
| ADR-L1 | Single deployable for LOCAL (Spring Boot 4, virtual threads) with an AES-encrypted embedded H2 in PostgreSQL mode; `postgres` profile for PostgreSQL 16 | Double-click demo without Docker; same SQL on both |
| ADR-L2 | Tenant-partitioned document store with whole-document encryption for PHI-bearing kinds (tenant DEK, AAD = tenant + id) | Cross-tenant copies cannot be decrypted (tested); crypto-shredding on offboarding |
| ADR-L3 | Local PKI (root + per-tenant issuing CAs, EC P-256, CSR enrolment, one-time tokens) instead of step-ca in the single-process kit | Same enrolment API that gateways and step-ca/Vault PKI use in the cloud |
| ADR-L4 | Device listeners on the JDK (`SSLServerSocket`, `HttpsServer`) independent of the web server | `needClientAuth` on every device port, no plaintext port at all |
| ADR-L5 | Vanilla JS console without build step; DOM built with text nodes only; strict CSP without `unsafe-inline` | Small attack surface, no npm supply chain in the product runtime |
| ADR-L6 | Driver release key held offline; only the public key is in the repository; tenants trust additional publisher keys | Supply-chain control for driver packages (threat "malicious driver") |
