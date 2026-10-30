# WellnessZ OHCM – Security Test Evidence Guide

**Version 1.0**

*WellnessZ OHCM platform – security assessment 2026-10*

| | |
|---|---|
| **Document** | Security Test Evidence Guide – re-running the assessment and locating its evidence |
| **Document ID** | WZ-SEC-EVG-2026-10 |
| **Version** | 1.0 – 2026-10-30 |
| **Audience** | Security Operations Centre (SOC), internal and external auditors, Cyber GRC, platform engineers |
| **Related** | [01 – Security Assessment Report](01-Security-Assessment-Report.md) (Appendix A: the 61 checks; Appendix C: manifest with hashes) · [Local setup guide](../../setup/01-Local-MacBook-Pro.md) · [tools/security/README.md](../../../tools/security/README.md) |

---

## 1. Purpose

This guide lets a SOC analyst or an auditor:

1. find the evidence file behind every number in the assessment report;
2. check that the evidence files have not been altered;
3. re-run every test against the same build and compare the results.

Re-running must only be done against a stack you are authorised to test – normally your own local stack or an
ephemeral test environment. **Never run the verification suite or the access matrix against production**: the suite
sends attack payloads (including a `DROP TABLE` string and oversized bodies) and exhausts rate limits on purpose.

## 2. Evidence pack layout

```text
evidence/
├── SHA256SUMS                          integrity list for every file below
├── findings-register.md                21 findings, final scan state
├── iac-triage.md                       32 failed Checkov checks: decision, change, residual risk
├── semgrep-before.json                 semgrep-final.json
├── spotbugs-build-before.log           mvn-verify-before.log            mvn-final-verify.log
├── java-runtime-libs.json              osv-java-before.json             osv-java-after.json
├── npm-audit-<app>.json  (4)           vitest-<app>.json  (4)           lint-<app>.txt  (4)
├── detect-secrets.json                 history-secrets.txt
├── checkov-{terraform,docker,gha}-before.json
├── checkov-{terraform,docker,gha}-final.json
├── checkov-helm-framework.json
├── checkov-k8s-rendered-{azure,gcp,hybrid}-{dev,uat,prod}.json  (9)
├── helm-rendered/helm-{azure,gcp,hybrid}-{dev,uat,prod}.yaml    (9)
├── security-verification-first.json    security-verification-final.json security-verification-final.log
├── rbac-first-run/rbac-results.json
├── rbac/expected-policy.json  rbac-results.json  reviewed.json  run.log  WellnessZ-Role-Based-Access-Matrix.xlsx
└── e2e/e2e-increment{1..5}.txt         e2e/screenshots/
```

The full manifest with hashes is Appendix C of the report.

### 2.1 Verify integrity

```bash
cd evidence && sha256sum -c SHA256SUMS
```

Every line must report `OK`. Any mismatch means the evidence pack was modified after packaging and must be re-issued.

## 3. Where each reported number comes from

| Reported number | Evidence file | How to read it |
|---|---|---|
| Semgrep 15 → 0 findings; 1,731 files | `semgrep-before.json`, `semgrep-final.json` | `jq '.results \| length'`; `jq '.paths.scanned \| length'` |
| SpotBugs 0 in 25 modules | `mvn-final-verify.log` | `grep -c "BugInstance size is 0"` → 25 |
| Java tests 454 → 455, 0 failures | `mvn-verify-before.log`, `mvn-final-verify.log` | Sum the `Tests run:` summary lines; `BUILD SUCCESS` at the end |
| OSV 2 of 218 → 0 of 219 | `osv-java-before.json`, `osv-java-after.json` | `jq '.checked, (.hits \| keys)'`; `jq '.checked, .vulnerable'` |
| npm audit 0 in 966 dependencies | `npm-audit-<app>.json` | `jq '.metadata.vulnerabilities, .metadata.dependencies.total'` |
| 47 secret candidates, 0 real | `detect-secrets.json`, `history-secrets.txt` | `jq '[.results[]] \| map(length) \| add'`; history file is empty |
| Checkov Terraform 26 → 0, Docker 4 → 0, GHA 2 → 0 | `checkov-*-before.json`, `checkov-*-final.json` | `jq '.summary'` |
| Rendered Kubernetes 0 failed (9 overlays) | `checkov-k8s-rendered-*.json` | `jq '.summary'` |
| Verification 61/61 | `security-verification-final.json` | `jq '.total, .pass, .fail'`; per check: `jq '.results[] \| [.id,.result,.evidence]'` |
| `AUTH-08` 754/754 | same | `jq '.results[] \| select(.id=="AUTH-08")'` |
| `RATE-01` 175 × 200, 45 × 429 | same | `jq '.results[] \| select(.id=="RATE-01")'` |
| Access matrix 11,440/11,440 | `rbac/WellnessZ-Role-Based-Access-Matrix.xlsx` (sheet *Summary*); raw `rbac/rbac-results.json` | Summary rows: 9,828 + 663 + 754 + 195 |
| End-to-end 121/121 | `e2e/e2e-increment{1..5}.txt` | `grep -c '^PASS'` → 18, 25, 32, 22, 24; `grep -c '^FAIL'` → 0 |
| Web tests 1,373 | `vitest-<app>.json` | `jq '.numTotalTests, .numFailedTests'` → 285, 188, 769, 131; failures 0 |
| ESLint 0 warnings | `lint-<app>.txt` | Empty files |

## 4. Preparing a test stack

1. Check out the assessed build: `git checkout 9228d13`.
2. Start the local infrastructure (PostgreSQL 16, Keycloak 26.8.1, Mailpit) as in the
   [local setup guide](../../setup/01-Local-MacBook-Pro.md), Section 3.
3. Build: `./mvnw -q -DskipTests package`.
4. Start the core API and the BFF with the `local` profile (setup guide Section 5) and the four web apps
   (`npm run dev` in each `web/<app>`, Section 6).
5. Default ports: core `8080` (management `8081`), BFF `8090`, identity provider `8180`, web apps `5173`–`5176`.
   If you use other ports, export `WZ_SEC_CORE_URL`, `WZ_SEC_CORE_MGMT_URL`, `WZ_SEC_BFF_URL`, `WZ_SEC_IDP_URL` and the
   `WZ_E2E_*_URL` variables (see `tools/security/README.md`).
6. Prerequisites for the scripts: Node 22 with Playwright and Chromium; Python 3 with `openpyxl` (workbook).

## 5. Re-running each test

| # | Test | Command | Expected result on build `9228d13` | Output to compare with |
|---|---|---|---|---|
| 1 | Source SAST | `semgrep scan --config p/java --config p/typescript --config p/react --config p/owasp-top-ten --config p/secrets --config p/dockerfile --config p/kubernetes --config p/terraform --config p/github-actions --json -o semgrep.json .` | 0 results (Semgrep 1.183.0; newer rule versions may add results – triage them as new findings) | `semgrep-final.json` |
| 2 | Bytecode SAST + Java regression | Create `wellnessz_test` (setup guide §4.2), export `WZ_TEST_DB_*`, then `./mvnw -B -Psecurity -Ddependency-check.skip=true clean verify` | `BUILD SUCCESS`; 455 tests, 0 failures; `BugInstance size is 0` × 25 | `mvn-final-verify.log` |
| 3 | Dependency-Check (CI equivalent) | `export NVD_API_KEY=<key> && ./mvnw -Psecurity -DskipTests verify` | No CVSS ≥ 7 (build fails otherwise) | Not run in this assessment (limitation L-04) |
| 4 | Java SCA (OSV) | List `BOOT-INF/lib/*.jar` in `apps/wz-core-app/target/wz-core-app.jar` and `apps/wz-bff/target/wz-bff.jar`, map to Maven coordinates, query `https://api.osv.dev/v1/querybatch` (or `osv-scanner` on the CycloneDX SBOM) | 219 coordinates, 0 vulnerable on 2026-10-30 (later advisories may appear) | `osv-java-after.json` |
| 5 | Web SCA | `npm audit --json` in each `web/<app>` | 0 vulnerabilities | `npm-audit-<app>.json` |
| 6 | Secrets | `git ls-files -z \| xargs -0 detect-secrets scan > ds.json`; `gitleaks detect --log-opts="--all"` (CI) | Candidates only from the triaged list in F-15; 0 real secrets | `detect-secrets.json`, `history-secrets.txt` |
| 7 | IaC | `checkov -d deploy/terraform --framework terraform -o json`; `checkov -d deploy/docker --framework dockerfile -o json`; `checkov -d .github/workflows --framework github_actions -o json` | 0 failed (15, 0 and 2 reviewed skips) | `checkov-*-final.json`, `iac-triage.md` |
| 8 | Rendered Kubernetes | For each cloud and env: `helm template wellnessz deploy/helm/wellnessz -n wellnessz -f deploy/helm/wellnessz/values-<cloud>.yaml -f deploy/helm/wellnessz/environments/<cloud>/<env>.yaml > m.yaml && checkov -f m.yaml --framework kubernetes -o json` | 0 failed in all nine | `checkov-k8s-rendered-*.json` |
| 9 | Verification suite | Core started **with** the default rate limit; `node tools/security/security-verification.js` (output in `tools/security/results/`) | `61/61 checks passed` | `security-verification-final.json` |
| 10 | Access matrix | Restart core with `WZ_RATE_LIMIT_PER_MINUTE=0`; `python3 tools/security/rbac-extract-policy.py . target/rbac && node tools/security/rbac-matrix.js --policy target/rbac/expected-policy.json --out target/rbac && python3 tools/security/rbac-build-workbook.py target/rbac`; copy `reviewed.json` into `target/rbac` first | 11,440 / 11,440 PASS (counts change if roles, users or endpoints change) | `rbac/` |
| 11 | Web regression | `npm ci && npm run lint && npm test` in each `web/<app>` | 0 lint warnings; 1,373 tests pass in total | `vitest-*.json`, `lint-*.txt` |
| 12 | End-to-end | `node tools/e2e/e2e-increment<N>.js` for N = 1…5, resetting the database between increments 2, 3, 4 and 5 | 121 PASS, 0 FAIL | `e2e/` |

**Important:** restore the default rate limit after step 10. The access matrix is the only test run with the limiter
disabled; the verification suite and end-to-end tests ran with it active.

## 6. Interpreting results

| Situation | Meaning | Action |
|---|---|---|
| A check fails on the assessed build | Environment difference or harness issue (e.g. port, cookie jar, variable name), or tampering | Compare with the evidence file; re-run in a clean stack; raise an incident only once the harness is ruled out |
| A new SCA advisory on the assessed build | A vulnerability published after 2026-10-30 | Handle under the patch SLAs (SEC-E13); not a defect of the assessment |
| A new Semgrep/Checkov result with newer rules | Rule packs changed | Triage as a new finding; record in the register |
| Counts differ on a newer build | Expected after functional change | Re-assessment required (attestation validity) |

## 7. Evidence for SOC monitoring

The assessment exercised behaviour that the SOC can detect in production. Proposed detection sources (SEC-E08):

| Behaviour exercised | Signal in production |
|---|---|
| Forged, unsigned, expired or foreign-issuer tokens (`AUTH-02`…`07`) | `401` bursts; authentication-failure audit events from `SecurityEventAuditor` |
| Anonymous sweep of all operations (`AUTH-08`) | High volume of `401`/`403` from one source |
| Enumeration of restricted classes (`AC-03`…`05`) | Repeated `404` on `/support/**` and MRO endpoints; audit `DENIED` entries |
| Rate-limit exhaustion (`RATE-01`) | `429` storms per principal |
| Break-glass use (end-to-end increment 2) | `BREAK_GLASS` audit event and DPO review |

## 8. Retention

Keep the evidence pack, the signed attestation and the independent penetration-test report together in the release
record for at least the retention period required by the client's audit policy (recommended: seven years for
systems processing health data, or as set by the DPO).

*End of document.*
