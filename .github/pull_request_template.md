## Summary

<!-- What does this change do and why? Link the work item / change request. -->

Change request / ticket:

## Type of change

- [ ] Feature
- [ ] Bug fix
- [ ] Security fix
- [ ] Infrastructure / deployment (Helm, Terraform, CI/CD)
- [ ] Documentation

## Data protection & security checklist (SOC 2 CC6/CC7/CC8)

- [ ] No secrets, credentials, tokens, keys or real personal/health data in code, tests, fixtures, logs or screenshots
- [ ] Clinical data stays in the OH domain; manager/HR/ops endpoints return status-only projections
- [ ] New/changed endpoints enforce authentication, role/ABAC checks and write audit events
- [ ] Logs remain PHI-safe (masking verified for new fields)
- [ ] Database changes are additive Flyway migrations; grants follow least privilege (`wz_app` DML only)
- [ ] New dependencies reviewed (licence, maintenance, CVEs); SBOM impact understood
- [ ] Infrastructure changes keep encryption in transit/at rest, private networking and least-privilege IAM
- [ ] Threat model / DPIA updated if data flows changed

## Testing

- [ ] Unit / integration tests added or updated
- [ ] `./mvnw verify` / `npm test` pass locally
- [ ] Manually verified against the local stack (`deploy/local/up.sh`)

## Deployment notes

<!-- Config/secret changes, migration impact, rollback plan, feature flags. -->

## Reviewer notes

<!-- Anything reviewers should focus on. Two approvals required for changes under deploy/, .github/ or security code (CODEOWNERS). -->
