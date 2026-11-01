# DAST – OWASP ZAP baseline in CI (E34-HLR-06, E34-F05, E34-BL-005)

| | |
|---|---|
| **Workflow** | `.github/workflows/dast.yml` – job *ZAP baseline (staging, report only)* |
| **Trigger** | manual dispatch and weekly (Tuesday 03:23 UTC), `main` only |
| **Target** | staging only: hosts must match `*.uat.*` or `*.staging.*` and use `https://` (guard step refuses anything else) |
| **Gate** | protected GitHub Environment `dast-staging` – required reviewers Security Operations, deployment branch `main`; variable `DAST_TARGET_URLS` |
| **Tool** | `ghcr.io/zaproxy/zaproxy:2.16.1` (Docker), `zap-baseline.py -m 1 -I` |
| **Mode** | **passive** – the spider fetches pages for one minute and ZAP reports passive findings (headers, cookies, information leakage, CSP, mixed content …). No attack payloads |
| **Result** | **report only**: the job does not fail on findings. HTML / JSON / Markdown reports + `SHA256SUMS` are uploaded as artefact `zap-baseline-<run id>` (90 days); the run summary shows High / Medium / Low / Informational counts per URL |

## What it covers and what it does not

| Covered | Not covered (and where it is) |
|---|---|
| Passive checks of the public web hosts (employee, manager, OH, partner portals) and any API URL listed | Active scanning / injection – manual and approved only: penetration test (E34-BL-012) |
| Security headers written by Spring Security (CSP, HSTS, frame options, referrer policy) and nginx-unprivileged | Authenticated crawling – the baseline is unauthenticated; authenticated API checks are pack scripts 04 / 11 |
| TLS / cookie flags on the staging edge | OpenAPI-driven API scan – `tools/device-security/11_zap_api_baseline.sh` in `.github/workflows/device-security-pack.yml` (quarterly) |
| | Device ingest host (mTLS) – ZAP cannot present the group-PKI certificate; covered by pack scripts 02 / 03 / 04 |

## Configuration

```text
GitHub > Settings > Environments > dast-staging
  Required reviewers: Security Operations (prevent self-review)
  Deployment branches: main
  Variable DAST_TARGET_URLS = "https://employee.uat.<domain>/ https://oh.uat.<domain>/ https://manager.uat.<domain>/ https://partners.uat.<domain>/"
```

Staging WAF: allow the GitHub-hosted runner ranges for the scan window or run the job on a self-hosted runner;
Cloud Armor / App Gateway WAF in *prevention* mode may block the spider (findings then reflect the WAF, not the app).

## Triage

1. Security Operations reviews each run's summary; High / Medium findings become vulnerability records in
   `/device-security/sdlc` (due dates: critical 7 days, high 30 days – E34 governance) or the platform register.
2. False positives are documented with justification in the vulnerability record; a ZAP rules file
   (`-c rules.tsv`) may be added later to mark accepted IGNORE / WARN rules – it must be reviewed like `.trivyignore`.
3. The job stays report-only by decision: passive findings on staging should not block releases, but every High
   needs a record before the next production release (release checklist).

## Verification status

The workflow is linted (`actionlint` with shellcheck: clean; Checkov `github_actions`: clean). It has **not been run**
from this repository – it needs the `dast-staging` environment and a reachable staging deployment. The first run's
artefact is the evidence for E34-BL-005 (DAST in CI).
