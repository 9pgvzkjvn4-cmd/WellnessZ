# Threat model (STRIDE) – local release

| Threat | STRIDE | Mitigation in this release | Verified by |
|---|---|---|---|
| Rogue or spoofed device/gateway posts fabricated results | S | per-endpoint certificates, fingerprint ↔ registry on every connection, expected device serials, AN-05/AN-07 auto-isolation | IT, kit 03 |
| Compromised vendor PC pivots into the record | E | device host is outbound-only through mTLS ingress; configuration bound to endpoint; isolation playbook | kit 03, IT |
| Interception / alteration on the clinical network | T/I | TLS 1.2+/mTLS only, raw SHA-256, provenance with configuration hash | kit 02 |
| Wrong-patient association | T | thresholds (0.95 auto, 0.60 suggest), ADT-driven beds, restricted categories without matching, unmatched queue, audit | unit/IT, XT-05 |
| Credential leakage in drivers, exports, evidence | I | vault references only, secret scanner on load/export/evidence, masked traces | kit 08, XT-13/XT-16 |
| Malformed messages / bursts exhaust the engine | D | size limits, safe parsers, DLQ, token-bucket quotas | kit 03/14 |
| Unauthorised API access | I | OAuth2 scopes, tenant from token, 404 for foreign ids, CSRF, CSP | kit 04 |
| Cross-tenant leakage | I | tenant-partitioned store, per-tenant DEK with AAD | kit 17, IT |
| Poisoned knowledge base / insecure recommendation | T | KB only from certified runs, de-identified, policy blocks insecure values regardless | kit 18 |
| Malicious driver package | T | Ed25519 signatures from trusted publishers, validation, secret scan, harness certification | unit tests |
| Loss or tampering of audit/evidence | R | HMAC hash chain, SIEM sink, sealed read-only evidence packs | kit 09/19 |
| Publishing an untested configuration | process | hash-bound publish gate, four-eyes, SRE for PROD | kit 19, IT |
| Brute force / default credentials | S | no default credentials (random initial admin), lockout, rate limits, TOTP | kit 12 |
