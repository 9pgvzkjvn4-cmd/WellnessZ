# Local runbook

| Situation | Action |
|---|---|
| Start / stop | `java -jar intimed-platform.jar [--spring.profiles.active=demo]`; stop with Ctrl-C (graceful) |
| Ports busy | `INTIMED_PORT`, `INTIMED_INGRESS_PORT`, `INTIMED_MLLP_PORT`, `INTIMED_MGMT_PORT` |
| Browser warns about the certificate | trust `$INTIMED_HOME/pki/root-ca.pem` (macOS: `security add-trusted-cert -r trustRoot -k ~/Library/Keychains/login.keychain-db …`) or proceed for localhost |
| Reset everything | stop, delete `$INTIMED_HOME` (keys, PKI, database, evidence) |
| Lost master key | data cannot be decrypted by design – restore `$INTIMED_HOME` or the Keychain item together |
| Device refused | Security → Endpoints: status ACTIVE? certificate valid? configuration bound (published with that endpoint)? |
| Result missing in the form | Queues: unmatched / held / dead letters; Fleet → diagnostics (masked payload, re-send) |
| Endpoint isolated | Security → Alerts; re-run the exchange test of every bound configuration, then *Release* |
| Health / metrics / SBOM | `http://127.0.0.1:9444/actuator/health`, `/actuator/prometheus`, `/actuator/sbom` |
| Audit to SIEM | tail `$INTIMED_HOME/siem/audit.jsonl` with your collector |
| Security check | `./security-test-kit/run-kit.sh` → `security-test-kit/results/REPORT.md` |
