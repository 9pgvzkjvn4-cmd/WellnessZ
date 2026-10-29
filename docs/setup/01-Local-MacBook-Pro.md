# WellnessZ OHCM – Setup Guide 1: Local MacBook Pro

| | |
|---|---|
| **Document** | 05 – Setup guide: Local MacBook Pro (Apple Silicon) |
| **Audience** | Developers, testers, solution architects preparing demos |
| **Result** | Full stack on a laptop: PostgreSQL, Keycloak, mail servers, OTel collector, core API, BFF, both web apps; ready for incremental module development |
| **Time** | About 45 minutes on a clean machine (most of it downloads) |
| **Related** | 04 – Deployment Architecture · 03 – Developer Guide · 09 – CI/CD Guide |

![Local MacBook Pro topology](../images/08-deploy-local-macbook.png)

*Figure 1 – Local topology (source: `docs/images/08-deploy-local-macbook.svg`).*

> **What was validated.** The following steps were executed against this repository in a Linux sandbox with the
> same container images:
>
> * `./up.sh` and `./up.sh --apps`; all containers healthy;
> * a Keycloak client-credentials token accepted by `/api/v1/me`;
> * BFF login redirect with PKCE S256;
> * BFF readiness `UP`, `/actuator/env` denied;
> * EHS e-mail ingestion via IMAP and Excel import (2 of 2 rows accepted);
> * Flyway in both migration modes;
> * database privilege checks.
>
> The macOS-specific parts (Homebrew, Docker Desktop/Colima, IntelliJ) follow the vendors' documented procedures
> but were not run on a Mac in the sandbox.

## 1. Prerequisites

| Tool | Version used / minimum | Why |
|---|---|---|
| macOS | 14 Sonoma or later, Apple Silicon (M1–M4) | Intel Macs also work; images are multi-arch |
| Homebrew | current | Package manager |
| Eclipse Temurin JDK | 21 (LTS) | Build and run core/BFF |
| Maven | via `./mvnw` (Maven 3.9) | No global Maven needed |
| Node.js | 24 LTS | Web apps |
| Docker Desktop **or** Colima | Docker Desktop 4.40+ / Colima 0.8+ with Docker CLI 27+ and Compose v2 | Local infrastructure |
| Git | 2.40+ | Source control |
| kubectl, Helm, Terraform | kubectl 1.33, Helm 3.19 (Helm 4 also works), Terraform ≥ 1.11 (tested 1.13.4) | Optional locally; needed for chart testing and cloud setups |
| az / gcloud | latest | Only for the cloud guides |
| IDE | IntelliJ IDEA 2025.x or VS Code + Java extension pack | Running and debugging |
| RAM / disk | 16 GB RAM (32 GB recommended), 20 GB free disk | Keycloak + PostgreSQL + 2 JVMs + 2 dev servers |

### 1.1 Install with Homebrew

Install Homebrew if it is missing:

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

Install the JDK, Node and command-line tools:

```bash
brew install --cask temurin@21
```

```bash
brew install node@24 git jq openssl@3 kubernetes-cli helm terraform
```

```bash
brew link --overwrite --force node@24
```

Choose **one** container runtime. Option A is Docker Desktop:

```bash
brew install --cask docker
```

Option B is Colima (open source; no Docker Desktop licence needed):

```bash
brew install colima docker docker-compose docker-buildx
```

```bash
colima start --cpu 6 --memory 12 --disk 60 --vm-type vz --vz-rosetta
```

Make Temurin 21 the default JDK for your shell (add the line to `~/.zshrc`):

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

Optional cloud CLIs (needed for guides 2–4):

```bash
brew install azure-cli
```

```bash
brew install --cask google-cloud-sdk
```

### 1.2 Verify the toolchain

```bash
java -version
```

Expected: `openjdk version "21.0.x" … Temurin`.

```bash
node --version && npm --version
```

Expected: `v24.x` and `11.x`.

```bash
docker version --format '{{.Server.Version}}' && docker compose version
```

Expected: a server version (not an error) and `Docker Compose version v2.x` or later.

```bash
openssl version
```

Expected: `OpenSSL 3.x` (LibreSSL also works for `rand`).

## 2. Get the source

```bash
git clone https://github.com/OWNER/WellnessZ.git && cd WellnessZ
```

```bash
./mvnw -v
```

Expected: `Apache Maven 3.9.x` and `Java version: 21`. The first run downloads Maven into `~/.m2/wrapper`.

## 3. Start the local infrastructure

### 3.1 First start

```bash
./deploy/local/up.sh
```

On the first run the script creates `deploy/local/.env` (mode 600, git-ignored) with random values for:

* every database password;
* the Keycloak admin password;
* `WZ_MASTER_KEY`, `WZ_PSEUDONYM_KEY` and `WZ_BFF_MASTER_KEY` (32 random bytes each, base64).

It then starts the containers and waits until all healthchecks pass. Expected final output:

```text
WellnessZ local stack is up:
  PostgreSQL     localhost:5432   db wellnessz (wz_app / wz_migrator), db keycloak
  Keycloak       http://localhost:8180  realm: wellnessz  (admin console /admin)
  Mailpit UI     http://localhost:8025  SMTP localhost:1025
  GreenMail      IMAP localhost:3143  user ehs-feed@wellnessz.local
  OTel collector OTLP grpc localhost:4317 / http localhost:4318
```

To create `.env` yourself instead, copy `deploy/local/.env.example` and fill every value. Generate keys with:

```bash
openssl rand -base64 32
```

### 3.2 Verify

```bash
docker compose -f deploy/local/docker-compose.yml ps --format '{{.Service}}: {{.Status}}'
```

Expected: `postgres`, `keycloak`, `mailpit` and `greenmail` show `(healthy)`; `otel-collector` shows `Up`.

```bash
curl -s http://localhost:8180/realms/wellnessz/.well-known/openid-configuration | jq -r .issuer
```

Expected: `http://localhost:8180/realms/wellnessz`.

```bash
curl -s -d grant_type=client_credentials -d client_id=wellnessz-integration -d client_secret=change-me-local-only http://localhost:8180/realms/wellnessz/protocol/openid-connect/token | jq -r .access_token | cut -d. -f2 | base64 -d 2>/dev/null | jq '{aud, realm_access}'
```

Expected: `"aud": "wellnessz-api"` and `"roles": ["INTEGRATION"]`.

### 3.3 What is inside

| Service | URL / port | Credentials |
|---|---|---|
| PostgreSQL 16.15 | `localhost:5432`, databases `wellnessz`, `keycloak` | Roles `wz_migrator` (DDL), `wz_app` (core runtime), `wz_bff` (sessions); passwords in `.env` |
| Keycloak 26.8.1 | <http://localhost:8180/admin> | `KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD` from `.env` |
| Realm users | `employee1`, `manager1`, `hr1`, `clinician1`, `psych1`, `auditor1`, `admin1` | Temporary password `ChangeMe!123`; change it at first login |
| Mailpit | UI <http://localhost:8025>, SMTP `localhost:1025` | none |
| GreenMail | IMAP `localhost:3143`, SMTP `localhost:3025` | `ehs-feed@wellnessz.local` / `WZ_IMAP_PASSWORD` |
| OTel collector | OTLP `localhost:4317` (gRPC) / `4318` (HTTP) | – |

Realm users carry the ABAC attributes `employee_id` (for example `E1001`) and `org_scope` (for example
`/AIRLINE/OPS/CABIN`). They are emitted as claims to the BFF and mobile clients; only administrators can edit them.

## 4. Build and test

### 4.1 Full build

```bash
./mvnw verify
```

Expected: `BUILD SUCCESS`. The build runs unit tests, integration tests on a real PostgreSQL 16, ArchUnit
boundary rules, JaCoCo coverage and a CycloneDX SBOM (`target/bom.json`).

### 4.2 Integration-test database: Testcontainers or your local PostgreSQL

* **Testcontainers (default).** Starts `postgres:16-alpine` automatically. Docker must be running. With Colima, set:

```bash
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
```

* **Existing database (faster, no extra container).** Point the tests at the compose PostgreSQL. Use a separate
  database so test data never mixes with your dev data:

```bash
set -a; source deploy/local/.env; set +a
```

```bash
docker compose -f deploy/local/docker-compose.yml exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres psql -h 127.0.0.1 -U postgres -c "CREATE DATABASE wellnessz_test OWNER wz_migrator" -c "GRANT CONNECT, TEMPORARY ON DATABASE wellnessz_test TO wz_app"
```

```bash
export WZ_TEST_DB_URL=jdbc:postgresql://localhost:5432/wellnessz_test WZ_TEST_DB_USERNAME=wz_app WZ_TEST_DB_PASSWORD="$WZ_APP_DB_PASSWORD" WZ_TEST_DB_MIGRATOR_USERNAME=wz_migrator WZ_TEST_DB_MIGRATOR_PASSWORD="$WZ_MIGRATOR_DB_PASSWORD"
```

```bash
./mvnw verify
```

Unset the variables (`unset WZ_TEST_DB_URL`) to return to Testcontainers.

### 4.3 Security profile (as in CI)

```bash
./mvnw -Psecurity -DskipTests -Ddependency-check.skip=true verify
```

This runs SpotBugs + FindSecBugs. To include the OWASP dependency-check, request a free NVD API key and drop the
skip flag:

```bash
export NVD_API_KEY=<your-key> && ./mvnw -Psecurity -DskipTests verify
```

### 4.4 Web apps

Run this block in each of `web/employee-app` and `web/manager-workspace`:

```bash
cd web/employee-app && npm ci && npm run lint && npm test && npm run build && cd ../..
```

```bash
cd web/manager-workspace && npm ci && npm run lint && npm test && npm run build && cd ../..
```

## 5. Run core and BFF

You can run the services either from the IDE/terminal (best for debugging) or as containers (closest to
production). **Do not run both at the same time**: they use the same ports.

### 5.1 Option A – IDE or terminal with the `local` profile

Load the generated secrets into the current shell and map them to the application variables:

```bash
set -a; source deploy/local/.env; set +a
```

```bash
export SPRING_PROFILES_ACTIVE=local WZ_DB_PASSWORD="$WZ_APP_DB_PASSWORD" WZ_DB_MIGRATOR_USERNAME=wz_migrator WZ_DB_MIGRATOR_PASSWORD="$WZ_MIGRATOR_DB_PASSWORD" WZ_EHS_MAILBOX_MODE=imap WZ_IMAP_HOST=localhost WZ_IMAP_PORT=3143 WZ_IMAP_USERNAME=ehs-feed@wellnessz.local WZ_SMTP_HOST=localhost WZ_SMTP_PORT=1025 OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
```

Build the jars once:

```bash
./mvnw -q -DskipTests package
```

Start the **core** (terminal 1):

```bash
java -jar apps/wz-core-app/target/wz-core-app.jar
```

Expected: `Started WellnessZCoreApplication`; Flyway reports `Successfully applied … migrations` on the first
start.

Start the **BFF** in terminal 2, after the same two `export` steps. The BFF uses its own database role and its
own session key:

```bash
export WZ_BFF_DB_USERNAME=wz_bff WZ_BFF_DB_PASSWORD="$WZ_BFF_DB_PASSWORD" WZ_MASTER_KEY="$WZ_BFF_MASTER_KEY" WZ_BFF_CLIENT_SECRET=change-me-local-only
```

```bash
java -jar apps/wz-bff/target/wz-bff.jar
```

Expected: Tomcat started on ports `8090` and `8091`.

**IntelliJ IDEA:**

1. Create a *Spring Boot* run configuration for `com.wellnessz.WellnessZCoreApplication` (module `wz-core-app`),
   active profile `local`.
2. Use the EnvFile plugin or paste the variables above into *Environment variables*.
3. Do the same for `com.wellnessz.bff.WellnessZBffApplication` with the BFF variables.

Hot reload works through *Build → Recompile*.

### 5.2 Option B – hardened containers

```bash
./deploy/local/up.sh --apps
```

This builds both images from `deploy/docker` (first build about 5–10 minutes) and starts them:

* read-only root filesystem;
* all Linux capabilities dropped;
* uid 10001.

A small `apps-net` container lets the containers use the same issuer URL (`http://localhost:8180`) as your
browser.

### 5.3 Verify core and BFF

```bash
curl -s http://localhost:8081/actuator/health/readiness
```

Expected: `{"status":"UP"}`.

```bash
curl -s http://localhost:8091/actuator/health/readiness && curl -s -o /dev/null -w ' env=%{http_code}\n' http://localhost:8091/actuator/env
```

Expected: `{"status":"UP"} env=403`.

```bash
TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=wellnessz-integration -d client_secret=change-me-local-only http://localhost:8180/realms/wellnessz/protocol/openid-connect/token | jq -r .access_token)
```

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/me | jq
```

Expected: `"roles": ["INTEGRATION"]`. Without a token the API answers `401`.

```bash
curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' http://localhost:8090/oauth2/authorization/wellnessz
```

Expected: `302` to `http://localhost:8180/realms/wellnessz/protocol/openid-connect/auth?…code_challenge_method=S256`.

Swagger UI (local profile only): <http://localhost:8080/swagger-ui.html>.

## 6. Run the web apps

In separate terminals, start the employee portal:

```bash
cd web/employee-app && npm run dev
```

and the manager workspace:

```bash
cd web/manager-workspace && npm run dev
```

* Employee portal: <http://localhost:5173>
* Manager & HR workspace: <http://localhost:5174>

Both dev servers proxy `/bff`, `/oauth2`, `/login` and `/logout` to the BFF on `http://localhost:8090`. If the BFF
runs elsewhere (another port, a remote dev environment), set `WZ_BFF_TARGET`:

```bash
WZ_BFF_TARGET=http://localhost:18090 npm run dev
```

**Manual check.**

1. Open <http://localhost:5173> and choose *Sign in with your company account*.
2. Sign in as `employee1` / `ChangeMe!123` and set a new password.
3. Submit a self-referral.
4. In a private window, open <http://localhost:5174> as `manager1`. You should see the case **status only**, with
   no clinical text.

## 7. End-to-end smoke test (Playwright)

`tools/e2e/e2e.js` drives the same journey automatically:

* real Keycloak login through the BFF;
* cookie and storage assertions;
* employee self-referral;
* manager status-only view.

It needs the stack, core, BFF and both dev servers running.

```bash
npm install --no-save playwright@1 && npx playwright install chromium
```

```bash
node tools/e2e/e2e.js
```

Expected: the script prints a log that ends with these lines, and it writes screenshots to `tools/e2e/shots/`:

* `manager sees clinical text? false`
* `employee -> /bff/api/workspace/cases: 403`

A failure prints `E2E FAILED` and exits with status 1. The script changes the temporary passwords at first login,
so run it against a freshly imported realm: `./deploy/local/down.sh --purge`, then `up.sh`.

Override the targets with environment variables, for example for an environment behind real hostnames:

```bash
WZ_E2E_EMPLOYEE_URL=https://employee.dev.example.com WZ_E2E_WORKSPACE_URL=https://manager.dev.example.com WZ_E2E_IDP_HOST=login.microsoftonline.com WZ_E2E_OUT=/tmp/e2e-shots node tools/e2e/e2e.js
```

| Variable | Default | Meaning |
|---|---|---|
| `WZ_E2E_EMPLOYEE_URL` | `http://localhost:5173` | Employee portal origin |
| `WZ_E2E_WORKSPACE_URL` | `http://localhost:5174` | Manager workspace origin |
| `WZ_E2E_IDP_HOST` | `localhost:8180` | Regex for the IdP host (used to detect the login page) |
| `WZ_E2E_OUT` | `tools/e2e/shots` | Screenshot directory |

The test logs in with the local realm passwords. Against Entra ID, use dedicated test accounts and adapt the
login step.

## 8. EHS intake tests

### 8.1 Mailbox (IMAP via GreenMail)

The core polls `ehs-feed@wellnessz.local` every 5 minutes (`WZ_EHS_MAILBOX_POLL`, ISO-8601 duration, for example
`PT30S` locally). The local profile accepts senders ending in `@wellnessz.local`. Send a test incident:

```bash
python3 - <<'EOF'
import smtplib
from email.message import EmailMessage
m = EmailMessage()
m["From"] = "ehs-system@wellnessz.local"; m["To"] = "ehs-feed@wellnessz.local"; m["Subject"] = "EHS incident INC-9001"
m.set_content("Incident ID: INC-9001\nDate: 2026-10-28 14:30\nLocation: LHR Hangar 2\nEmployee ID: E1001\n"
              "Type: Slip/Trip\nSeverity: Medium\nInjury: Yes\nDescription: Slipped on wet floor\n")
smtplib.SMTP("127.0.0.1", 3025).send_message(m); print("sent")
EOF
```

After the next poll, verify in the database:

```bash
docker compose -f deploy/local/docker-compose.yml exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres psql -h 127.0.0.1 -U postgres -d wellnessz -c "select external_ref from intake.ehs_incident"
```

Expected: `INC-9001`. To trigger a poll immediately, call `POST /api/v1/intake/ehs/mailbox/poll` with an
`OH_ADMIN` or `PLATFORM_ADMIN` session.

### 8.2 Excel import

Create a sample workbook. Header names are matched case-insensitively against aliases such as *Incident ID*,
*Incident Date*, *Location*, *Employee ID*, *Department*, *Incident Type*, *Severity*, *Injury* and *Description*.

```bash
python3 -m pip install --user openpyxl
```

```bash
python3 - <<'EOF'
from openpyxl import Workbook
wb = Workbook(); ws = wb.active
ws.append(["Incident ID", "Incident Date", "Location", "Employee ID", "Department", "Incident Type", "Severity", "Injury", "Description"])
ws.append(["INC-9002", "2026-10-27", "FRA Ramp", "E1001", "OPS-CABIN", "Manual handling", "Low", "No", "Back strain while loading"])
ws.append(["INC-9003", "2026-10-26", "LHR T5", "M2001", "OPS", "Near miss", "Low", "No", "Unsecured trolley"])
wb.save("ehs-sample.xlsx"); print("ehs-sample.xlsx written")
EOF
```

```bash
curl -s -H "Authorization: Bearer $TOKEN" -F file=@ehs-sample.xlsx http://localhost:8080/api/v1/intake/ehs/excel | jq
```

Expected: `"rowsTotal": 2, "rowsAccepted": 2, "rowsRejected": 0`. A second upload of the same file reports
duplicates instead of creating new incidents.

## 9. Secrets in configuration: `ENC(...)`

Values that must live in configuration files, such as a connector password, are stored encrypted:

```bash
export WZ_MASTER_KEY="$(grep ^WZ_MASTER_KEY= deploy/local/.env | cut -d= -f2-)"
```

```bash
./tools/encrypt-secret.sh
```

The tool prompts for the value without echoing it and prints `ENC(…)`. Paste that into the configuration; the
application decrypts it at start-up with the same master key. Never commit plain secrets: gitleaks blocks them in
CI.

## 10. Optional: test the Helm chart on a local Kubernetes

Docker Desktop includes Kubernetes (*Settings → Kubernetes → Enable*). Alternatively, use kind
(`brew install kind`). The values file `deploy/helm/wellnessz/values-local.yaml` documents every command in its
header. In short:

1. Build the four images with tag `local`.
2. Create the namespace and the three Secrets (`wellnessz-core-secrets`, `wellnessz-bff-secrets`,
   `wellnessz-migrations-secrets`).
3. Install an ingress controller.
4. Run the install:

```bash
helm upgrade --install wellnessz deploy/helm/wellnessz -n wellnessz -f deploy/helm/wellnessz/values.yaml -f deploy/helm/wellnessz/values-local.yaml
```

Keycloak must then advertise an issuer that both pods and the browser can reach
(`KC_HOSTNAME=http://host.docker.internal:8180`). Use this setup to test the chart, not for day-to-day
development.

## 11. Incremental development loop

1. Create a branch: `git switch -c feature/E07-surveillance`.
2. Add the module as described in the Developer Guide (document 03). Add Flyway migrations under
   `apps/wz-core-app/src/main/resources/db/migration` and grant privileges to `${app_role}` explicitly.
3. Run `./mvnw verify` and the web checks, then restart core/BFF (or `./up.sh --apps`).
4. Run the local quality gates from the CI/CD Guide (gitleaks, Trivy, actionlint) before pushing.
5. Open a PR using the template; CI repeats every gate.

## 12. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `up.sh` stops with `required variable … is missing a value` | `.env` created by an older version lacks a new variable | Compare with `.env.example` and add the value, or run `./deploy/local/down.sh --purge`, delete `.env` and run `up.sh` again |
| Keycloak unhealthy, log shows `password authentication failed for user "keycloak"` | Old database volume with different passwords | `./deploy/local/down.sh --purge` (deletes local data), then `up.sh` |
| `Found non-empty schema(s) … but no schema history table` | Tables created manually in an app schema | Purge the volume or drop the stray tables as `wz_migrator` |
| Port already in use (5432, 8080, 8180, 5173 …) | Another local PostgreSQL / app / dev server | `lsof -nP -iTCP:<port> -sTCP:LISTEN` and stop it |
| BFF login loops or redirect URI error | Opened via `127.0.0.1` instead of `localhost`, or wrong port | Use `http://localhost:5173`; the realm allows `localhost:5173`, `5174` and `8090` |
| Core returns 401 for a valid token | Token issuer differs (for example `127.0.0.1` vs `localhost`) or audience missing | Request tokens from `http://localhost:8180`; check `aud` contains `wellnessz-api` |
| Core cannot start: `WZ_MASTER_KEY or WZ_MASTER_KEY_FILE is required` | Environment not loaded in this shell | Repeat the `source .env` and `export` steps of section 5.1 |
| Testcontainers cannot find Docker (Colima) | Socket path differs | Set `DOCKER_HOST` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` (section 4.2) |
| `npm ci` fails on Node version | Node 20/22 active | `brew link --overwrite --force node@24` |
| Apple Silicon: very slow image builds | Rosetta not enabled in Colima / Docker Desktop | Docker Desktop: enable *Use Rosetta*; Colima: `--vz-rosetta` |
| E-mail not ingested | Sender not in the allowed list or poll interval not elapsed | Use a `@wellnessz.local` sender; set `WZ_EHS_MAILBOX_POLL=PT30S` |

## 13. Teardown

Stop everything but keep the data:

```bash
./deploy/local/down.sh
```

Stop everything and delete the local database volume:

```bash
./deploy/local/down.sh --purge
```

Remove the generated secrets file (a new one is generated on the next `up.sh`):

```bash
rm deploy/local/.env
```
