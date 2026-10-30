# WellnessZ OHCM – Setup Guide 1: Local MacBook Pro

| | |
|---|---|
| **Document** | 05 – Setup guide: Local MacBook Pro (Apple Silicon) |
| **Audience** | Developers, testers, solution architects preparing demos |
| **Result** | Full stack on a laptop: PostgreSQL, Keycloak, mail servers, OTel collector, core API, BFF, the four web apps (employee portal, manager workspace, OH workspace and – since increment 4 – the partner portal); ready for incremental module development |
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
>
> **Increment 1** (E01 intake, E02 triage) was verified on this setup: 65 Java tests, web tests (oh-workspace 151,
> employee-app 81, manager-workspace 55) and `tools/e2e/e2e-increment1.js` with 18/18 checks passing (section 7.1).
>
> **Increment 2** (E03 OH record, E05 fitness for duty) was verified on this setup: 128 Java tests, web tests
> (oh-workspace 305, employee-app 138, manager-workspace 99) and `tools/e2e/e2e-increment2.js` with 25/25 checks
> passing (section 7.2). New here: schemas `docs`, `ohrecord`, `fitness`, the `dpo1` and `partner1` users, gateway
> smoke calls (section 8.4) and the optional PDF font and OCR (section 8.5).
>
> **Increment 3** (E06 absence & sickness, E07 medical surveillance, E14 scheduling & clinic operations) was verified on
> this setup: 210 Java tests, web tests (oh-workspace 463, employee-app 191, manager-workspace 154) and
> `tools/e2e/e2e-increment3.js` with 32/32 checks passing (section 7.3). New here: schemas `absence`, `surveillance`,
> `scheduling`, the users `scheduler1`, `biomed1`, `crewlicensing1` and `candidate1`, a test database that must be
> recreated before every run (section 4.2), gateway smoke calls for absence feeds and fridge readings (section 8.6) and
> manual checks for the three epics (section 8.7).
>
> **Increment 4** (E04 treatment coordination & partner referrals, E08 claims coordination, partner portal) was
> verified on this setup: 275 Java tests, web tests (oh-workspace 570, employee-app 223, manager-workspace 164,
> partner-portal 119) and `tools/e2e/e2e-increment4.js` with 22/22 checks passing, plus the three earlier scripts
> (section 7.4). New here: schemas `treatment`, `claims`, the users `claims1`, `adjuster1`, `legal1`, `finance1`,
> `procurement1`, `physio1` (section 3.3), the partner portal on port 5176 (section 6), optional MFA rehearsal for
> partner and adjuster users (section 6.1), FHIR / telehealth smoke calls (section 8.8) and manual checks for E04 / E08
> (section 8.9).

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
| Realm users | `employee1`, `manager1`, `hr1`, `clinician1`, `psych1`, `auditor1`, `admin1`; increment 1: `nurse1`, `nurse2`, `physician1`, `ame1`, `cmo1`, `nursinghead1`, `hse1`, `crewsched1`, `hygiene1`, `reception1`, `firstaider1`; increment 2: `dpo1`, `partner1`; increment 3: `scheduler1`, `biomed1`, `crewlicensing1`, `candidate1`; increment 4: `claims1`, `adjuster1`, `legal1`, `finance1`, `procurement1`, `physio1` | Temporary password `ChangeMe!123`; change it at first login |
| Mailpit | UI <http://localhost:8025>, SMTP `localhost:1025` | none |
| GreenMail | IMAP `localhost:3143`, SMTP `localhost:3025` | `ehs-feed@wellnessz.local` / `WZ_IMAP_PASSWORD` |
| OTel collector | OTLP `localhost:4317` (gRPC) / `4318` (HTTP) | – |

Realm users carry the ABAC attributes `employee_id` (for example `E1001`) and `org_scope` (for example
`/AIRLINE/CATERING`). They are emitted as claims to the BFF and mobile clients; only administrators can edit them.

**Increment-1 roles.** The realm defines `OH_PHYSICIAN`, `AME`, `CMO`, `NURSING_HEAD`, `HSE_OFFICER`,
`CREW_SCHEDULING`, `HYGIENE_MANAGER`, `CLINIC_RECEPTION` and `FIRST_AIDER`. The clients `wellnessz-bff` and
`wellnessz-mobile` have `fullScopeAllowed=false`, so their **role scope mappings** list every role a token may carry;
a role that is not in the scope mapping is silently missing from the token. If you add a role, add it to both scope
mappings.

| User | Roles | Use it for |
|---|---|---|
| `nurse1`, `nurse2` | OH_CLINICIAN, CASE_MANAGER | Work queues, triage, escalation |
| `physician1` | OH_PHYSICIAN, OH_CLINICIAN | Physician escalations, food-safety queue |
| `ame1` | AME, OH_PHYSICIAN, OH_CLINICIAN | Aeromedical queue |
| `cmo1` | CMO, OH_PHYSICIAN, OH_CLINICIAN | Protocol approval, CMO escalations, case conferences |
| `nursinghead1` | NURSING_HEAD, OH_CLINICIAN, CASE_MANAGER | Team queue, reassignment, caseload, configuration, triage QA |
| `hse1` | HSE_OFFICER | Reconciliation and exceptions |
| `crewsched1` / `hygiene1` | CREW_SCHEDULING / HYGIENE_MANAGER | Status-only APIs |
| `reception1` / `firstaider1` | CLINIC_RECEPTION / FIRST_AIDER | Walk-in and emergency registration |
| `admin1` | PLATFORM_ADMIN, OH_ADMIN | Configuration screens, dead-letter queue |
| `dpo1` (increment 2) | DPO | Break-glass reviews, disclosure log, retention schedule and legal hold in the OH workspace; no clinical access |
| `partner1` (increment 2) | PARTNER_PROVIDER, `org_scope=/PARTNERS/CITYHOSP` | Documents shared with partner `CITYHOSP` under consent (`GET /api/v1/partner/documents`) |
| `scheduler1` (increment 3) | CLINIC_SCHEDULER (`S8611`, `/AIRLINE`) | Clinic diary, bookings, campaigns, waitlist, surveillance due list and invitations in the OH workspace; no clinical or absence data |
| `biomed1` (increment 3) | BIOMEDICAL (`S8602`, `/AIRLINE`) | Equipment register, calibration certificates, fridge readings and cold-chain reports |
| `crewlicensing1` (increment 3) | CREW_LICENSING (`S8603`, `/AIRLINE/OPS`) | Crew medical certificate validity, expiring list and crew without a valid medical (status only) |
| `candidate1` (increment 3) | CANDIDATE (`C9001`, `/CANDIDATES`) | Pre-employment candidate portal in the employee app (questionnaire, consent, documents, booking); `C9001` is deliberately not in the HRIS master |
| `partner1` (increment 4 use) | PARTNER_PROVIDER (`P9101`, `/PARTNERS/CITYHOSP`) | **Partner portal** (5176): referral inbox of City General Hospital (demo referral awaiting acceptance), documents |
| `physio1` (increment 4) | PARTNER_PROVIDER (`X8706`, `/PARTNERS/PHYSIOPLUS`) | Partner portal: PhysioPlus referrals – accept, appointment, seen, report and invoice upload, telehealth |
| `adjuster1` (increment 4) | TPA_ADJUSTER (`X8702`, `/PARTNERS/GULFTPA`) | Partner portal: GULFTPA claims (RTW status, tasks, messages, consented documents) and pre-authorisation decisions |
| `claims1` (increment 4) | CLAIMS_COORDINATOR (`S8711`, `/AIRLINE`) | OH workspace: claims, first reports and deadlines, disclosure sets, costs, pre-authorisation follow-up; no clinical screens |
| `legal1` (increment 4) | LEGAL_COUNSEL (`S8703`, `/AIRLINE`) | OH workspace *Legal & litigation*: litigation hold, privilege, medico-legal requests and reports |
| `finance1` (increment 4) | FINANCE (`S8704`, `/AIRLINE`) | OH workspace *Claim & treatment costs*: roll-ups by unit (no names), budget vs actual, ERP export and reconciliation |
| `procurement1` (increment 4) | PROCUREMENT (`S8705`, `/AIRLINE`) | OH workspace *Provider network*: directory, contracts, rates, SLAs, network gaps, scorecards |

**Increment-2 role.** The realm adds `DPO`, included in the scope mappings of `wellnessz-bff` and `wellnessz-mobile`.
A realm imported before increment 2 has neither the role nor `dpo1` / `partner1`: run `./deploy/local/down.sh --purge`
and `./deploy/local/up.sh` to re-import it (this deletes local data).

**Increment-3 roles.** The realm adds `CLINIC_SCHEDULER`, `BIOMEDICAL`, `CREW_LICENSING` and `CANDIDATE`, all four in
the scope mappings of `wellnessz-bff` and `wellnessz-mobile`, and the users `scheduler1`, `biomed1`, `crewlicensing1`
and `candidate1`. A realm imported before increment 3 has none of them: run `./deploy/local/down.sh --purge` and
`./deploy/local/up.sh` to re-import it (this deletes local data). Check after the re-import (with `.env` loaded):

```bash
docker compose -f deploy/local/docker-compose.yml exec -T keycloak /opt/keycloak/bin/kcadm.sh get roles -r wellnessz --no-config --server http://localhost:8180 --realm master --user "$KEYCLOAK_ADMIN_USER" --password "$KEYCLOAK_ADMIN_PASSWORD" --fields name | grep -E 'CLINIC_SCHEDULER|BIOMEDICAL|CREW_LICENSING|CANDIDATE'
```

Expected: the four role names.

**Increment-4 roles.** The realm adds `CLAIMS_COORDINATOR`, `TPA_ADJUSTER`, `LEGAL_COUNSEL`, `FINANCE` and
`PROCUREMENT` (all in the scope mappings of `wellnessz-bff` and `wellnessz-mobile`), the users `claims1`, `adjuster1`,
`legal1`, `finance1`, `procurement1` and `physio1`, and for the partner portal the redirect URI
`http://localhost:5176/login/oauth2/code/wellnessz`, the post-logout URI `http://localhost:5176/*` and the web origin
`http://localhost:5176` on `wellnessz-bff`. A realm imported before increment 4 has none of them – the partner portal
login then fails with *Invalid parameter: redirect_uri*. Run `./deploy/local/down.sh --purge` and
`./deploy/local/up.sh` to re-import it (this deletes local data). Check after the re-import:

```bash
docker compose -f deploy/local/docker-compose.yml exec -T keycloak /opt/keycloak/bin/kcadm.sh get roles -r wellnessz --no-config --server http://localhost:8180 --realm master --user "$KEYCLOAK_ADMIN_USER" --password "$KEYCLOAK_ADMIN_PASSWORD" --fields name | grep -cE 'CLAIMS_COORDINATOR|TPA_ADJUSTER|LEGAL_COUNSEL|"FINANCE"|PROCUREMENT'
```

Expected: `5`.

```bash
docker compose -f deploy/local/docker-compose.yml exec -T keycloak /opt/keycloak/bin/kcadm.sh get clients -r wellnessz -q clientId=wellnessz-bff --no-config --server http://localhost:8180 --realm master --user "$KEYCLOAK_ADMIN_USER" --password "$KEYCLOAK_ADMIN_PASSWORD" --fields redirectUris | grep -c 5176
```

Expected: `1`.

The Keycloak container imports the realm only when it does not exist yet (`--import-realm`). A stack created before
increment 1 keeps the old realm without the new roles and users: run `./deploy/local/down.sh --purge` and
`./deploy/local/up.sh` to re-import it (this deletes local data).

**Demo data.** With the `local` profile, Flyway also runs `apps/wz-core-app/src/main/resources/db/demo`
(`R__demo_reference_people.sql`): HRIS employees `E1001`–`E1005` and `M2001`, the OH staff roster (`nurse1`,
`nurse2`, `clinician1`, `physician1`, `ame1`, `cmo1`, `nursinghead1`, `psych1`) and the crew-scheduling and hygiene
notification addresses. Other profiles never load it. Increment 2 adds `R__demo_e03_record.sql` (OH chart of
`E1004`: penicillin allergy, a work-related problem, hepatitis B immunisation, a baseline audiogram and an earlier job) and `R__demo_e05_fitness.sql` (fleet and base of the crew members
`E1002`, `E1003`). Increment 3 adds five employees (`E1006` engineer, `E1007` security screener, `E1008` forklift
operator, `E1009` aircraft cleaner, `E1010` catering associate) to `R__demo_reference_people.sql` and three module
files: `R__demo_e06_absence.sql` (short absences and a Monday / Friday pattern for `E1001` with an early-intervention
item and an advice request awaiting the employee's review, a six-week absence of `E1004` on the LTA pathway, crew
sickness of `E1003` in the AME review queue, a certificate and an advice request for `E1010`, feed runs with
unmatched badge / crew ids), `R__demo_e07_surveillance.sql` (dates of birth for age-based frequencies, the expired crew
member `E7001`, enrolments, the pre-employment record of candidate `C9001`, and a return-after-illness requirement for
`E1004` that is skipped on `*_test` databases) and `R__demo_e14_scheduling.sql` (clinicians, rooms and diary templates
for the clinic sites, the equipment `AUD-LHR-01`, `AUD-LHR-02` (calibration overdue), `SPIRO-HQ-01`, `ECG-HQ-01`,
`VIS-LHR-01`, `FRIDGE-HQ-01`, `FRIDGE-LHR-01`, a vaccination appointment for `E1001` 30 minutes after the first start
and the campaign *LHR ramp audiometry day*). Encrypted columns stay empty in demo data. Increment 4 adds
`R__demo_e04_treatment.sql` (injury case of `E1004` with an active `BACK_STRAIN` plan and an open variance alert,
referrals `RFL-<year>-900001` to `CITYHOSP` awaiting acceptance, `900002` scheduled with `PHYSIOPLUS`, `900003` with an
overdue report, `900004` with a received report, `900005` draft; pre-authorisations `PA-<year>-900001` submitted to
`GULFTPA` and `900002` approved with a matched invoice; a telehealth session of `E1003` with `TELEDOC`; clinic stock with
an expired batch; provider scorecards for last month) and `R__demo_e08_claims.sql` (claims `CLM-<year>-900001` open and
lost-time with payer `GULFTPA`, `900002` litigated and on hold, `900003` closed, `900004` notified; first reports,
budgets and last month's ERP export). The `ClaimsDemoSeeder` bean (`local` / `test` profiles) then writes the encrypted
E08 demo fields through the application. Demo data cannot contain encrypted narrative text otherwise – the demo
referral `RFL-<year>-900001` therefore has no letter text.

**Schemas.** `01-init-wellnessz.sh` creates `platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness,
absence, surveillance, scheduling, treatment, claims, analytics`. Load `.env` first (`set -a; source deploy/local/.env;
set +a`) for the check below. On a database volume created before increment 2, 3 or 4, Flyway V6–V8, V10–V12 and
V14–V15 create the new schemas themselves; `search_path` of `wz_app` is only updated by a fresh init, so prefer
`./deploy/local/down.sh --purge` once.
Check:

```bash
docker compose -f deploy/local/docker-compose.yml exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres psql -h 127.0.0.1 -U postgres -d wellnessz -c '\dn'
```

Expected (after the core has started once): `docs`, `ohrecord`, `fitness`, `absence`, `surveillance`, `scheduling`,
`treatment` and `claims` in the list, all owned by `wz_migrator`.

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

**Recreate the test database before every run.** The integration tests generate new encryption keys
(`WZ_MASTER_KEY`, `WZ_PSEUDONYM_KEY`) for every test JVM, so rows encrypted by the previous run can no longer be
decrypted and the next run fails. Drop and recreate `wellnessz_test` before each `./mvnw verify` (Testcontainers does
this implicitly with a fresh container):

```bash
docker compose -f deploy/local/docker-compose.yml exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres psql -h 127.0.0.1 -U postgres -c "DROP DATABASE IF EXISTS wellnessz_test WITH (FORCE)" -c "CREATE DATABASE wellnessz_test OWNER wz_migrator" -c "GRANT CONNECT, TEMPORARY ON DATABASE wellnessz_test TO wz_app"
```

Keep the `_test` suffix: the E07 demo data skips the return-after-illness requirement for `E1004` on `*_test`
databases so that it does not block the E05 fitness tests.

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

Run this block in each of `web/employee-app`, `web/manager-workspace`, `web/oh-workspace` and `web/partner-portal`:

```bash
cd web/employee-app && npm ci && npm run lint && npm test && npm run build && cd ../..
```

```bash
cd web/manager-workspace && npm ci && npm run lint && npm test && npm run build && cd ../..
```

```bash
cd web/oh-workspace && npm ci && npm run lint && npm test && npm run build && cd ../..
```

```bash
cd web/partner-portal && npm ci && npm run lint && npm test && npm run build && cd ../..
```

Expected test counts at the increment-4 baseline: employee-app 223, manager-workspace 164, oh-workspace 570,
partner-portal 119 (increment 1: 81, 55, 151).

## 5. Run core and BFF

You can run the services either from the IDE/terminal (best for debugging) or as containers (closest to
production). **Do not run both at the same time**: they use the same ports.

### 5.1 Option A – IDE or terminal with the `local` profile

Load the generated secrets into the current shell and map them to the application variables:

```bash
set -a; source deploy/local/.env; set +a
```

```bash
export SPRING_PROFILES_ACTIVE=local WZ_DB_PASSWORD="$WZ_APP_DB_PASSWORD" WZ_DB_MIGRATOR_USERNAME=wz_migrator WZ_DB_MIGRATOR_PASSWORD="$WZ_MIGRATOR_DB_PASSWORD" WZ_EHS_MAILBOX_MODE=imap WZ_IMAP_HOST=localhost WZ_IMAP_PORT=3143 WZ_IMAP_USERNAME=ehs-feed@wellnessz.local WZ_SMTP_HOST=localhost WZ_SMTP_PORT=1025 OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318/v1/traces
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

the manager workspace:

```bash
cd web/manager-workspace && npm run dev
```

and the OH workspace (increment 1):

```bash
cd web/oh-workspace && WZ_BFF_TARGET=http://localhost:8090 npx vite --port 5175
```

`npm run dev` in `web/oh-workspace` is equivalent (port 5175 is set in `vite.config.ts`).

and the partner portal (increment 4):

```bash
cd web/partner-portal && npm run dev
```

* Employee portal: <http://localhost:5173>
* Manager & HR workspace: <http://localhost:5174>
* OH workspace: <http://localhost:5175>
* Partner portal: <http://localhost:5176> (sign-in button *Sign in with multi-factor authentication*)

All four dev servers proxy `/bff`, `/oauth2`, `/login` and `/logout` to the BFF on `http://localhost:8090`. If the BFF
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
5. In another private window, open <http://localhost:5175> as `nursinghead1`. The **Work queues** screen lists the
   new case with its urgency, owner and SLA state. Signing in as `reception1` shows only the reception screen.

6. In another private window, open <http://localhost:5176> as `physio1`. The partner portal shows the PhysioPlus
   referral inbox; as `adjuster1` it shows the GULFTPA claims and pending pre-authorisations. An OH user (for example
   `nurse1`) sees *This portal is for WellnessZ partner organisations*.

The BFF redirect URIs for the four ports (`http://localhost:517[3-6]/login/oauth2/code/wellnessz`) and the CORS
origins (`WZ_CORS_ORIGINS`, local profile `wellnessz.security.cors-origins`) already include ports 5175 and 5176.

### 6.1 MFA for partner and adjuster users (increment 4, optional locally)

In shared environments the core grants `PARTNER_PROVIDER` and `TPA_ADJUSTER` only when the access token proves MFA
(`amr` contains `mfa`, `otp`, `totp`, `hwk`, `swk`, `sms`, `webauthn` or `fido`, or `acr` is `2`, `3`, `mfa` or `c1`).
The `local` profile switches the gate off (`WZ_MFA_REQUIRED_ROLES` empty) because the demo realm has no OTP enrolment,
and the E2E scripts rely on that. To rehearse the production behaviour:

1. Start the core with the gate on (section 5.1, terminal 1):

   ```bash
   WZ_MFA_REQUIRED_ROLES=PARTNER_PROVIDER,TPA_ADJUSTER java -jar apps/wz-core-app/target/wz-core-app.jar
   ```

   Sign in to <http://localhost:5176> as `physio1`: the portal shows *Your account needs multi-factor sign-in or has
   no partner access* (the core dropped the role), and `/bff/api/partner/referrals` answers `403`.
2. Enrol OTP for the user. Keycloak admin console (<http://localhost:8180/admin>) → realm `wellnessz` → *Users* →
   `physio1` → *Required user actions*: **Configure OTP** → *Save*. At the next login Keycloak shows a QR code; scan it
   with an authenticator app and enter the code. The default browser flow (*Conditional OTP*) then asks for the code at
   every login.
3. Make the token prove it. Either
   * add an **AMR** mapper: *Clients* → `wellnessz-bff` → *Client scopes* → `wellnessz-bff-dedicated` → *Add mapper* →
     *By configuration* → *Authentication Method Reference (AMR)*, *Add to access token* on; then in *Authentication* →
     *browser* flow set the *Authentication Reference* of the *OTP Form* execution to `otp` (and of *Username Password
     Form* to `pwd`) where your Keycloak version asks for it; or
   * use a step-up flow: *Realm settings* → *General* → *ACR to LoA Mapping* `mfa` → `2`; copy the *browser* flow and
     replace the conditional OTP sub-flow by two *Condition – Level of Authentication* sub-flows (level 1: password,
     level 2: OTP form, max age 0), bind the copy as browser flow and set *Default ACR values* `2` on a client that only
     partners use. Without such a flow Keycloak issues `acr` = `1` and the gate keeps dropping the role.
4. Sign out, sign in again with password and OTP: the portal now shows the inbox, and
   <http://localhost:5176/bff/api/me> lists `PARTNER_PROVIDER` in `roles`. If the role is still missing, the token
   carries no MFA proof – check the mapper output in *Clients* → `wellnessz-bff` → *Client scopes* → *Evaluate* (user
   `physio1`, *Generated access token*). Tokens never reach the browser (BFF pattern), so browser developer tools do not
   show them.

Switch the gate off again by restarting the core without `WZ_MFA_REQUIRED_ROLES` before running the E2E scripts.

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

### 7.1 Increment-1 end-to-end test (E01, E02)

`tools/e2e/e2e-increment1.js` covers the increment with 18 checks: EHS webhook → auto-created and routed case,
employee self-referral through the UI, manager referral rejected for clinical wording and refused outside the org
scope, status-only manager view, queue counts, reassignment by the Nursing Head, red-flag hard stop, triage,
escalation, SLA clock in the case header, first-aid incident parked as a suggestion, dead-letter queue, reception
and crew-scheduling restrictions. It needs the stack, core (local profile, for the demo people), BFF and all three
dev servers.

```bash
node tools/e2e/e2e-increment1.js
```

Expected: 18 lines starting with `PASS`; exit code 0. Screenshots go to `tools/e2e/shots/` (`WZ_E2E_OUT`); the
published set is in `docs/images/screens/increment-01/`. Unlike `e2e.js`, the script handles both the first login
(temporary password → `WZ_E2E_NEW_PASSWORD`, default `Wz-Local-Demo-2026!`) and later runs with the changed password,
so it can be repeated without purging the realm. Other variables: `WZ_E2E_OH_URL`, `WZ_E2E_IDP_URL`,
`WZ_E2E_CORE_URL`, `WZ_E2E_INTEGRATION_SECRET` (see the Developer Guide, section 14.7).

### 7.2 Increment-2 end-to-end test (E03, E05)

`tools/e2e/e2e-increment2.js` covers increment 2 with 25 checks: chart summary with allergy alert, CDS block of a
penicillin-class medication, terminology search, signed injury encounter with body map and coded diagnosis, device
audiogram with threshold-shift flag, fitness decision against the food-handler standard, certificate signed and
shared, job-demand matching, modified duty and RTW plan, manager view with functional restrictions only and no chart
access, HR job offer, employee acknowledgements and disclosure log, AME crew assessment, status-only roster feed,
restricted psychological case, break-glass, DPO review and DPO without clinical access. It needs the same setup as
section 7.1 and the increment-2 realm (`dpo1`).

```bash
node tools/e2e/e2e-increment2.js
```

Expected: 25 lines starting with `PASS`; exit code 0. Screenshots go to `tools/e2e/shots/` (`WZ_E2E_OUT`); the
published set is in `docs/images/screens/increment-02/`. Like `e2e-increment1.js`, the script handles first and later
logins, so it can be repeated; each run creates new cases. Run `e2e-increment1.js` first when you verify a release.

### 7.3 Increment-3 end-to-end test (E06, E07, E14)

`tools/e2e/e2e-increment3.js` covers increment 3 with 32 checks: absence feed with matched and unmatched rows,
`DURATION_10D` early intervention, case and first contact, trigger rules, employee photo certificate validated by OH
with HR seeing validity only, LTA milestones (weeks 2, 4, 8, 12, 26) with overdue items, HR stage with clinical text
→ `422`, advice report with a diagnosis → `422`, signed report awaiting the employee, employee consent → released,
manager sees fitness only and never the reason category, analytics suppression and KPIs; hearing-conservation
simulation, scheduler invitation and booking, noise dosimetry above the action level, audiometer `AUD-LHR-02`
blocked (`422`) and accepted after calibration, threshold-shift follow-up case, FIT decision for `E1004` blocked with
`422 clearance_required`, candidate questionnaire and consent, HR clearance status only, crew licensing sees expiring
and expired medicals, GS1 vaccine entry, compliance and anonymised audit pack; double booking `409
resource_conflict`, self-booking and reschedule, badge check-in, queue board without names, call-next hand-off to the
chart, fridge reading of 10.4 °C opening an excursion that is acknowledged, campaign cohort booking and capacity
forecast. It needs the same setup as section 7.1, the increment-3 realm (section 3.3) and a core started with the
`local` profile on a database that contains the increment-3 demo data.

```bash
node tools/e2e/e2e-increment3.js
```

Expected: 32 lines starting with `PASS`; exit code 0. Screenshots go to `tools/e2e/shots/` (`WZ_E2E_OUT`); the
published set (37 images) is in `docs/images/screens/increment-03/`. The script handles first and later logins, but
several checks **consume demo state** (they calibrate `AUD-LHR-02`, release the demo advice report and complete the
candidate's consent). For a clean repeat, purge and recreate the local database (`./deploy/local/down.sh --purge`,
`./deploy/local/up.sh`, restart the core) and run the increment scripts in order.

### 7.4 Increment-4 end-to-end test (E04, E08, partner portal)

`tools/e2e/e2e-increment4.js` covers increment 4 with 22 checks across all four apps: employee injury report with
partner and insurer consent, treatment plan from the wrist-sprain template and the employee's plain-language view,
referral data-set and consent checks with letter and FHIR payload, partner accept / appointment / seen / report /
invoice in the portal and `404` for another organisation, report rating and overdue worklist, provider search and
scorecards, pre-authorisation approved by `adjuster1`, clinic medication with an expired batch refused, outcomes,
telehealth consent and chat, treatment KPIs; claim with automatic nurse case manager and case badge, first report of
injury with deadline, consent-gated disclosure set visible to the adjuster, adjuster collaboration with clinical
wording refused and providers without claim access, nurse assignment KPI, finance roll-up and reconciled ERP export
without claim detail, legal hold and medico-legal report, HR status only, DPO disclosure report. It needs the same
setup as section 7.1 plus the partner portal dev server (section 6), the increment-4 realm (section 3.3) and a core
started with the `local` profile (MFA gate off, increment-4 demo data).

```bash
node tools/e2e/e2e-increment4.js
```

Expected: 22 lines starting with `PASS`; exit code 0. Screenshots go to `tools/e2e/shots/` (`WZ_E2E_OUT`); the
published set (26 images) is in `docs/images/screens/increment-04/`. Additional variable: `WZ_E2E_PARTNER_URL`
(default `http://localhost:5176`). Like the increment-3 script it **consumes demo state** (claims, the monthly ERP
export, the litigation hold), so for a gate run purge and recreate the database and run the four increment scripts in
order:

```bash
./deploy/local/down.sh --purge && ./deploy/local/up.sh
```

Then restart the core with the `local` profile (section 5.1) and run:

```bash
for n in 1 2 3 4; do node tools/e2e/e2e-increment$n.js || break; done
```

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

### 8.3 EHS webhook (increment 1)

The EHS system can push incidents as JSON. The endpoint needs a client-credentials token with role `INTEGRATION`
(`$TOKEN` from section 5.3) and an `Idempotency-Key` header (8–200 characters):

```bash
curl -s -X POST http://localhost:8080/api/v1/intake/ehs/incidents -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: local-$(date +%s)" -d '{"incidents":[{"externalRef":"INC-9101","occurredAt":"2026-10-28T09:15:00Z","incidentClass":"INJURY_MEDICAL_TREATMENT","severity":"MEDIUM","employeeId":"E1004","unitCode":"GND","bodyPart":"HAND","location":"LHR stand 512","description":"Hand caught in belt loader"},{"externalRef":""}]}' | jq
```

Expected: HTTP `202` with `"accepted": 1, "deadLettered": 1`. The first incident matches the seeded rule *Injury –
medical treatment* (`AUTO_CREATE`, `SEMI_URGENT`) and appears as a `GND-…` case in the OH workspace queues; the second
is in the dead-letter queue (`GET /api/v1/intake/dlq`, OH workspace *Intake → DLQ* as `admin1`). Repeating the call
with the same key and body returns the stored response with `Idempotent-Replayed: true`.

### 8.4 Gateway smoke calls (increment 2)

Laboratory, device and rostering gateways are machine clients with the `INTEGRATION` role. Locally the client
`wellnessz-integration` plays all of them (`$TOKEN` from section 5.3):

```bash
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/api/v1/integration/lis/orders?status=QUEUED' | jq 'map({placerOrderNumber, status})'
```

Expected: HTTP `200` with the orders waiting for the laboratory (an empty list until a physician places an order in
the OH workspace). Each entry carries the HL7 v2 `ORM^O01` message in `hl7`; the gateway acknowledges it with
`POST /api/v1/integration/lis/orders/{id}/ack` and returns results with `POST /api/v1/integration/lis/results` (JSON)
or `POST /api/v1/integration/hl7v2` (`ORU^R01`, `Content-Type: text/plain`).

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/integration/roster-feed | jq 'map({messageId, employeeId, rosterStatus, validUntil, operationalLimitations})'
```

Expected: `200` with status-only roster messages (after a fitness decision on a crew case, for example from
`e2e-increment2.js`); the rostering gateway acknowledges each with `POST /api/v1/integration/roster-feed/{messageId}/ack`.
A user token (for example `crewsched1` through the BFF) gets `403` on `/integration/**`; people use
`/api/v1/crew/roster-feed`. Device middleware posts to `POST /api/v1/employees/{id}/results/device` (contract:
`docs/increment-02/API-Contract-E03-E05.md`, section 4.4).

### 8.5 Optional: PDF font and OCR (increment 2)

Generated PDFs use Helvetica, which has no Arabic glyphs. To render Arabic or other scripts, point the core at a
Unicode TrueType font before starting it (section 5.1), for example Noto Sans Arabic downloaded from Google Fonts:

```bash
export WZ_DOCUMENTS_FONT_PATH="$HOME/Library/Fonts/NotoSansArabic-Regular.ttf"
```

OCR of scanned JPEG / PNG images is off by default (PDFs are indexed from their text layer). To try local OCR,
install Tesseract with the language packs and set the two allow-listed properties:

```bash
brew install tesseract tesseract-lang
```

```bash
export WELLNESSZ_DOCUMENTS_OCR_TESSERACTPATH=/opt/homebrew/bin/tesseract WELLNESSZ_DOCUMENTS_OCR_LANGUAGES=eng+ara+fra
```

Only `/usr/bin/tesseract`, `/usr/local/bin/tesseract` and `/opt/homebrew/bin/tesseract` and the language lists `eng`,
`ara`, `fra`, `eng+ara`, `eng+fra`, `eng+ara+fra` are accepted; any other value stops the core at start-up. In
Option B (containers) neither font nor Tesseract is in the image; use Option A to try them.

### 8.6 Gateway smoke calls (increment 3)

The absence-feed, industrial-hygiene, dosimetry, recruiting and IoT fridge gateways are machine clients with the
`INTEGRATION` role; locally `wellnessz-integration` plays all of them (`$TOKEN` from section 5.3). Push one absence
episode for a demo employee and one for an unknown badge:

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' http://localhost:8080/api/v1/integration/absence/episodes -d "{\"sourceSystem\":\"HRIS\",\"episodes\":[{\"sourceRef\":\"SMOKE-$(date +%s)-1\",\"employeeRef\":\"E1005\",\"startDate\":\"$(date +%F)\",\"absenceType\":\"SICKNESS\",\"reasonCategory\":\"RESPIRATORY\",\"certified\":false},{\"sourceRef\":\"SMOKE-$(date +%s)-2\",\"employeeRef\":\"B-9998\",\"startDate\":\"$(date +%F)\",\"absenceType\":\"SICKNESS\",\"certified\":false}]}" | jq
```

Expected: `200` with `received: 2`, `matched: 1`, `unmatched: 1`; the unknown badge appears in *OH workspace →
Absence → Absence feed* (as `admin1`). Only the allow-listed absence types (`SICKNESS`, `INJURY_WORK`,
`INJURY_NON_WORK`, `MATERNITY_RELATED`, `OTHER_AUTHORISED`) and reason categories are accepted.

Post an IoT fridge reading outside 2–8 °C:

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' http://localhost:8080/api/v1/integration/cold-chain/readings -d "[{\"deviceId\":\"FRIDGE-HQ-01\",\"temperatureC\":9.2,\"recordedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"},{\"deviceId\":\"FRIDGE-UNKNOWN\",\"temperatureC\":5.0,\"recordedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]" | jq
```

Expected: `200` with `accepted: 1`, `excursionsOpened: 1` (or `0` if an excursion for the fridge is already open) and
`unknownDevices: ["FRIDGE-UNKNOWN"]`. A clinician (for example `nurse1`) acknowledges the excursion in *OH workspace →
Clinic → Equipment & cold chain*; `biomed1` sees it but cannot acknowledge. The other machine endpoints are
`POST /api/v1/integration/absence/certificates`, `/integration/ih-measurements`, `/integration/cosmic-dose` and
`/integration/pre-employment` (contract: `docs/increment-03/API-Contract-E06-E07-E14.md`). A user token through the
BFF gets `403` on `/integration/**`.

### 8.7 Manual verification of the three epics (increment 3)

With the core on the `local` profile, the BFF and the three dev servers running (section 6):

| Epic | Sign in as | Do | Expected |
|---|---|---|---|
| E06 absence | `nurse1` (OH workspace) | *Absence → Early intervention* | Items raised by trigger rules (for example `E1001`); *Absence → Long-term absence* shows `E1004` with milestones |
| E06 absence | `manager1` (manager workspace) | *Absence* | Team table with dates and days only – no reason category |
| E06 advice | `employee1` (employee app) | *Advice* | The demo report waits for review; consent releases it to `manager1`, who sees fitness and functional advice only |
| E06 certificates | `employee1` | *Absence* → upload a certificate (photo or PDF) | Upload succeeds through the BFF; `hr1` later sees the certificate **validity** only |
| E07 surveillance | `nurse1` / `cmo1` | *Surveillance → Programmes*, *Due list*, *Compliance & audit* | 13 programmes; due list with invitations; compliance with groups below 5 shown as suppressed |
| E07 crew | `crewlicensing1` (OH workspace) | *Surveillance → Crew medicals* | Expiring certificates and `E7001` without a valid medical; no examination content |
| E07 pre-employment | `candidate1` (employee app) | Candidate portal | Only the pre-employment questionnaire, consent, documents and booking; no other menu entries |
| E14 scheduling | `scheduler1` (OH workspace) | *Clinic → Clinic diary*; book the same clinician twice for the same slot | Second booking answers `409 resource_conflict` |
| E14 clinic flow | `reception1` | *Clinic → Check-in kiosk* → check in `E1001` (demo vaccination appointment) by badge `B-1001`; open *Clinic → Queue board* | A ticket number, no names; `nurse1` uses *Call next* and lands in the chart |
| E14 equipment | `biomed1` | *Clinic → Equipment & cold chain* | `AUD-LHR-02` overdue; after recording a passing calibration, device results for it are accepted again |

### 8.8 Gateway smoke calls (increment 4)

Partner hospital systems, insurer systems and the telehealth video platform are machine clients with the
`INTEGRATION` role; locally `wellnessz-integration` plays them (`$TOKEN` from section 5.3). Read a demo referral as a
FHIR R4 `ServiceRequest`:

```bash
REF_ID=$(docker compose -f deploy/local/docker-compose.yml exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres psql -h 127.0.0.1 -U postgres -d wellnessz -tAc "select id from treatment.referral where ref like 'RFL-%-900002'")
```

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/integration/referrals/$REF_ID/fhir | jq '{resourceType, status, intent}'
```

Expected: `"resourceType": "ServiceRequest"`, `"status": "active"`, `"intent": "order"`; the read is written to the
disclosure log (recipient `Partner PHYSIOPLUS (FHIR)`). Redeem an invalid telehealth token:

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' http://localhost:8080/api/v1/integration/telehealth/tokens/redeem -d '{"token":"not-a-real-token-0000000000"}' | jq
```

Expected: `{"valid": false}` and an audit row `TELEHEALTH_TOKEN_REDEEM` with outcome `DENIED`. The other machine
endpoints are `POST /api/v1/integration/referrals/fhir/document-reference`,
`/integration/preauthorisations/{ref}/decision` and `/integration/claims/reports/{ref}/acknowledgement` (contract:
`docs/increment-04/API-Contract-E04-E08.md`). Outbound messages to `insurer-gateway`, `authority-gateway` and
`erp-finance` go to the outbox and the logging publisher; check them as `admin1` with
`GET /api/v1/integration/events` (metadata only).

### 8.9 Manual verification of E04 and E08 (increment 4)

With the core on the `local` profile, the BFF and the four dev servers running (section 6):

| Epic | Sign in as | Do | Expected |
|---|---|---|---|
| E04 plan | `nurse1` (OH workspace) | Open the demo injury case of `E1004` → tab *Treatment* | Active `BACK_STRAIN` plan with follow-ups; *Treatment → Recovery variance* lists the open alert |
| E04 referral | `nurse1` | *Treatment → Referrals* | `RFL-…-900003` overdue with chase count; send a new referral without the data set → `422 data_set_incomplete` |
| E04 partner | `partner1` (partner portal) | *Referrals* | Only CITYHOSP referrals (`RFL-…-900001`); opening a PhysioPlus referral id → *Not found or not shared with your organisation* |
| E04 pre-auth | `adjuster1` (partner portal) | *Pre-authorisations* | `PA-…-900001` pending; approve with an approval reference → status `APPROVED` in the OH workspace |
| E04 providers | `procurement1` (OH workspace) | *Provider network*, *Network gaps*, *Provider scorecards* | Contracts with ACTIVE / EXPIRING / EXPIRED status; scorecards for last month |
| E04 medication | `nurse1` | *Clinic medications* (stock) and the case's administration form with batch `ADR-2207` | `422 batch_expired` |
| E08 claims | `claims1` (OH workspace) | *Claims → Claims* → `CLM-…-900001` | Lost-time claim with nurse case manager, report deadlines, draft disclosure set; no menu entry for the chart |
| E08 adjuster | `adjuster1` (partner portal) | *Claims* → `CLM-…-900001` | RTW status and milestones, tasks, messages; a message with a diagnosis → `422` |
| E08 finance | `finance1` (OH workspace) | *Claims → Claim & treatment costs* | Units with fewer than 5 cases suppressed; no names; last month's export can be reconciled |
| E08 legal | `legal1` (OH workspace) | *Claims → Legal & litigation* | `CLM-…-900002` litigated, on hold and privileged; medico-legal request open |
| E08 HR | `hr1` (manager workspace) | *Claims* | Claim status only – no costs, payer or adjuster |

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

1. Build the images with tag `local` (core, BFF and the four web apps – `values-local.yaml` lists the commands,
   including `wellnessz/partner-portal:local`; the chart serves the partner portal on `partners.localtest.me`).
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
| BFF login loops or redirect URI error | Opened via `127.0.0.1` instead of `localhost`, or wrong port | Use `http://localhost:5173`; the realm allows `localhost:5173`, `5174`, `5175`, `5176` and `8090` |
| OH workspace shows *Access restricted* for `nurse1` (or `GET /bff/session` lacks the new roles) | Realm imported before increment 1, or a role missing from the `wellnessz-bff` scope mapping | `./deploy/local/down.sh --purge` and `up.sh` to re-import; when editing the realm, add new roles to the scope mappings of `wellnessz-bff` and `wellnessz-mobile` |
| Referrals answer `404` for every employee, reception lookups fail with `unknown_employee`, or no case owner is assigned | Core not started with the `local` profile, so the demo employees and OH staff roster were not loaded | Start the core with `SPRING_PROFILES_ACTIVE=local` (section 5.1) |
| Webhook returns `400 idempotency_key_required` | Missing or too short `Idempotency-Key` | Send a key of 8–200 characters `[A-Za-z0-9._:-]` |
| Core returns 401 for a valid token | Token issuer differs (for example `127.0.0.1` vs `localhost`) or audience missing | Request tokens from `http://localhost:8180`; check `aud` contains `wellnessz-api` |
| Core cannot start: `WZ_MASTER_KEY or WZ_MASTER_KEY_FILE is required` | Environment not loaded in this shell | Repeat the `source .env` and `export` steps of section 5.1 |
| Testcontainers cannot find Docker (Colima) | Socket path differs | Set `DOCKER_HOST` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` (section 4.2) |
| `npm ci` fails on Node version | Node 20/22 active | `brew link --overwrite --force node@24` |
| Apple Silicon: very slow image builds | Rosetta not enabled in Colima / Docker Desktop | Docker Desktop: enable *Use Rosetta*; Colima: `--vz-rosetta` |
| E-mail not ingested | Sender not in the allowed list or poll interval not elapsed | Use a `@wellnessz.local` sender; set `WZ_EHS_MAILBOX_POLL=PT30S` |
| `dpo1` cannot log in, or the OH workspace shows *Access restricted* for it | Realm imported before increment 2 | `./deploy/local/down.sh --purge` and `up.sh` to re-import the realm |
| Chart of `E1004` is empty, crew dashboard has no fleet | Core not started with the `local` profile, so `R__demo_e03_record.sql` / `R__demo_e05_fitness.sql` were not loaded | Start the core with `SPRING_PROFILES_ACTIVE=local` |
| Core stops with `Unsupported OCR binary location` or `Unsupported OCR language list` | OCR properties outside the allow-list | Use one of the values in section 8.5 or unset both variables |
| Arabic text in generated PDFs is missing or replaced | No Unicode font configured | Set `WZ_DOCUMENTS_FONT_PATH` to a readable TrueType font (section 8.5) |
| Closing a case fails with `422 diagnosis_required` | Clinical case without a coded diagnosis (intended) | Sign an encounter with a coded diagnosis, or set `WZ_DIAGNOSIS_REQUIRED_ON_CLOSE=false` for local experiments only |
| `scheduler1`, `biomed1`, `crewlicensing1` or `candidate1` cannot log in, or see *Access restricted* | Realm imported before increment 3 (roles, users or scope mappings missing) | `./deploy/local/down.sh --purge` and `up.sh` to re-import the realm (section 3.3) |
| Second `./mvnw verify` against `WZ_TEST_DB_URL` fails with decryption errors (`AEADBadTagException`, `cannot decrypt`) or unexpected demo rows | Test keys are generated per run; the database still holds data encrypted by the previous run | Drop and recreate `wellnessz_test` before every run (section 4.2) |
| Certificate or document upload in a browser fails with `400` | BFF started with multipart parsing enabled (for example an overridden `spring.servlet.multipart.enabled`) | Keep `spring.servlet.multipart.enabled=false` in the BFF (shipped default); restart the BFF |
| Device result answers `422 device_out_of_calibration` / `device_out_of_service` | The device is overdue or out of service in the equipment register (intended) | Record a passing calibration as `biomed1`, or use a calibrated device |
| FIT decision answers `422 clearance_required` | An open return-after-illness requirement (for example the demo for `E1004`) | Complete the return-after-illness medical in *Surveillance → Return after illness* |
| Absence queue, due list or diary are empty | Core not started with the `local` profile, so the increment-3 demo files were not loaded | Start the core with `SPRING_PROFILES_ACTIVE=local` |
| `e2e-increment3.js` fails on the calibration, advice or candidate checks on a second run | The first run consumed the demo state | Purge and recreate the database (section 7.3) |
| Partner portal login: *Invalid parameter: redirect_uri*; or `claims1`, `adjuster1`, `legal1`, `finance1`, `procurement1`, `physio1` cannot log in | Realm imported before increment 4 (5176 redirect, roles or users missing) | `./deploy/local/down.sh --purge` and `up.sh` to re-import the realm (section 3.3) |
| Partner portal shows *Your account needs multi-factor sign-in or has no partner access* for `partner1` / `adjuster1` | Core started with `WZ_MFA_REQUIRED_ROLES` set while the token has no `amr` / `acr` proof | Unset the variable for local work, or enrol OTP and add the AMR mapper / LoA flow (section 6.1) |
| Partner portal API calls fail with CORS errors (core called directly) | `WZ_CORS_ORIGINS` overridden without `http://localhost:5176` | Use the shipped local value (all four dev ports) or call through the BFF |
| Referral send answers `422 consent_required` | The employee has no active `TREATMENT_SHARING_PARTNER` consent (intended) | Choose a consent in the picker; the employee grants it in the employee app (*Consents*) |
| Disclosure set send or pre-authorisation submit answers `422 consent_required` | No active `INSURER_SHARING` consent for the employee | Same as above with the insurer purpose |
| `e2e-increment4.js` fails on the claim, ERP export or legal-hold checks on a second run | The first run consumed the demo state (`409 claim_exists`, `409 export_exists`) | Purge and recreate the database and run the scripts 1 → 4 in order (section 7.4) |
| Claims, providers or costs screens are empty | Core not started with the `local` profile, so the increment-4 demo files and `ClaimsDemoSeeder` did not run | Start the core with `SPRING_PROFILES_ACTIVE=local` |

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
