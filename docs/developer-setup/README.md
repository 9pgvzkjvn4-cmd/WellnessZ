# WellnessZ OHCM – Developer Environment & IDE Setup Guide

| | |
|---|---|
| **Audience** | Developers joining the project who have never seen the code base |
| **Result** | A working workstation: JDK 21, Maven wrapper, Node 24, Docker, the local stack (PostgreSQL, Keycloak, Mailpit, GreenMail, OpenTelemetry collector), core API and BFF running and debuggable from your IDE, the four React apps on ports 5173–5176, tests and quality gates runnable locally |
| **Time** | About 60 minutes on a clean machine (mostly downloads) |
| **Package** | This folder (`docs/developer-setup/`) is self-contained: this guide plus ready-to-use IDE files in [`ide/`](ide/) |
| **Related** | [Setup guide 1 – Local MacBook Pro](../setup/01-Local-MacBook-Pro.md) · [Developer Guide](../architecture/03-Developer-Guide.md) · [CI/CD Guide](../cicd/CI-CD-Guide.md) · customisation guide in `docs/customisation/` (written separately) |

> **How this guide relates to the others.** *Setup guide 1* describes the local stack and the functional checks per
> increment in depth. The *Developer Guide* explains how to write a module. This guide covers the part in between: how
> to install the toolchain, configure an IDE, run and debug everything from it and keep the quality gates green before
> you push. Where a topic is already documented elsewhere, this guide links to it instead of repeating it.

## Contents

0. [The project in five minutes](#0-the-project-in-five-minutes)
1. [Choosing an IDE](#1-choosing-an-ide)
2. [Prerequisites per operating system](#2-prerequisites-per-operating-system)
3. [Clone, build and run – step by step](#3-clone-build-and-run--step-by-step)
4. [IDE-specific set-up](#4-ide-specific-set-up)
5. [Connecting tools](#5-connecting-tools)
6. [Quality gates, commits and secrets hygiene](#6-quality-gates-commits-and-secrets-hygiene)
7. [Troubleshooting](#7-troubleshooting)
8. [Adding a module or a client-specific customisation](#8-adding-a-module-or-a-client-specific-customisation)
9. [Files in this package](#9-files-in-this-package)

---

## 0. The project in five minutes

WellnessZ is an occupational-health case management (OHCM) platform: a **modular monolith** in Java plus a
**Backend-for-Frontend** and four **React** single-page apps.

| Path | What it is | Technology |
|---|---|---|
| `pom.xml` | Maven parent/aggregator (25 modules), managed versions, `security` profile | Spring Boot 4.1.2 parent, Java 21 |
| `platform/wz-*` | Shared platform services: secrets, security, master data, integration, notification, analytics, case kernel, documents, EHS intake | Spring, JPA, Flyway |
| `modules/wz-eNN-*` | Business modules (E01 intake … E14 scheduling) – talk to each other only through events | Spring |
| `apps/wz-core-app` | The runnable core API (port 8080, management 8081); **all Flyway migrations**, ArchUnit and integration tests | Spring Boot |
| `apps/wz-bff` | Backend-for-Frontend (port 8090, management 8091): OIDC login, encrypted server-side sessions, proxy to the core | Spring Boot, Spring Security OAuth2 client |
| `web/employee-app` | Employee / crew portal, port **5173** (also a PWA and Capacitor app) | React 19, Vite 8, TypeScript 6, Vitest 5, ESLint 10 |
| `web/manager-workspace` | Manager & HR workspace (status-only views), port **5174** | as above |
| `web/oh-workspace` | Occupational-health clinical workspace, port **5175** | as above |
| `web/partner-portal` | Partner providers and TPA adjusters, port **5176** | as above |
| `deploy/local` | Docker Compose stack for local development | PostgreSQL 16, Keycloak 26, Mailpit, GreenMail, OTel collector |
| `tools/e2e` | Playwright end-to-end scripts (`e2e.js`, `e2e-increment1.js` … `e2e-increment4.js`) | Node, Playwright |
| `tools/encrypt-secret.sh` | Produces `ENC(...)` values for configuration | Java (`wz-secrets`) |

**Request flow on your laptop.** Browser → Vite dev server (`localhost:517x`) → proxy `/bff`, `/oauth2`, `/login`,
`/logout` → BFF (`localhost:8090`) → core API (`localhost:8080`) → PostgreSQL (`localhost:5432`). Sign-in goes from the
BFF to Keycloak (`localhost:8180`); tokens never reach the browser.

**Rules you will meet on day one** (details: Developer Guide, section 1.3): clinical data stays in the OH domain; every
read goes through `PolicyEngine`; modules communicate by events; no PHI in logs; clinical free text is encrypted with
`EncryptedStringConverter`; schema changes only through Flyway.

---

## 1. Choosing an IDE

All four options below work with this repository. The ready-made files in [`ide/`](ide/) cover each of them.

| | IntelliJ IDEA Ultimate | IntelliJ IDEA (free / Community feature set) | VS Code | Eclipse with Spring Tools | Dev Container / GitHub Codespaces |
|---|---|---|---|---|---|
| **Java / Spring support** | Excellent: Maven import, Spring Boot run type, bean navigation, `application.yml` completion, HTTP client | Very good Java and Maven; no Spring Boot run type (use the plain *Application* type), no Spring tooling | Good with the Java Extension Pack and Spring Boot Extension Pack (Eclipse JDT language server) | Very good: m2e, Spring Tools, Boot Dashboard | Same as the editor you attach (VS Code by default; JetBrains Gateway also works) |
| **React / TypeScript support** | Excellent (bundled JavaScript/TypeScript, ESLint, Vitest, Chrome debugger) | None – use VS Code (or WebStorm) for `web/*` | Excellent (built-in TypeScript, ESLint and Vitest extensions, built-in JavaScript debugger) | Basic (Wild Web Developer); not recommended for `web/*` | As VS Code |
| **Debugging** | Java + JavaScript in one IDE, compound run configurations | Java only | Java (launch/attach) + Chrome/Node, compound launches | Java (launch/attach) | As VS Code; all ports forwarded |
| **Database client** | Built-in (Database tool window) | None (use psql, DBeaver or pgAdmin) | SQLTools extension | DBeaver plug-in or external | psql pre-installed, SQLTools |
| **Licence / cost** | Commercial subscription (free for some open-source/educational use) | Free (JetBrains now ships one IntelliJ IDEA distribution; the former Community features stay free, Ultimate features need a subscription) | Free | Free (EPL) | Dev Containers free locally; Codespaces billed per core-hour and storage beyond the included quota |
| **Recommended for** | Full-stack developers working on Java and the web apps every day | Back-end-only work, combined with VS Code for the web apps | Web-focused developers; anyone who prefers a light editor; Windows/WSL users | Developers already fluent in Eclipse | New starters, locked-down laptops, quick reviews, consistent toolchains |

**Recommendation.** Use **IntelliJ IDEA Ultimate** if you have a licence; otherwise **VS Code** for everything, or
IntelliJ IDEA (free) for Java plus VS Code for `web/*`. Use the **Dev Container** when you cannot (or do not want to)
install the toolchain on your machine.

---

## 2. Prerequisites per operating system

| Tool | Version | Source of truth in the repo |
|---|---|---|
| JDK | **Eclipse Temurin 21** (LTS) | `pom.xml` (`java.version` 21), `ci.yml` (`JAVA_VERSION: "21"`, `distribution: temurin`), `deploy/docker/Dockerfile.core` (`eclipse-temurin:21.0.12_8-jre-noble`) |
| Maven | **No global install** – use `./mvnw` (downloads Maven 3.9.11) | `.mvn/wrapper/maven-wrapper.properties` |
| Node.js + npm | **Node 24 LTS** (minimum 22.12 per `engines`), npm 11 | `ci.yml` (`NODE_VERSION: "24"`), `web/*/package.json` (`"node": ">=22.12.0"`), web `Dockerfile`s (`node:24.22.2-alpine3.24`) |
| Container runtime | Docker Desktop, Colima or Rancher Desktop (dockerd/moby engine) with Docker Compose v2 | `deploy/local/docker-compose.yml` |
| Git | 2.40 or later | – |
| OpenSSL | 3.x (LibreSSL is fine for `rand`) | `deploy/local/up.sh` generates secrets with it |
| *Optional:* psql | 16 (matches `postgres:16.15-bookworm`) | `deploy/local/docker-compose.yml` |
| *Optional:* kubectl, Helm, Terraform | kubectl 1.33, Helm 3.19, Terraform 1.13.4 | `ci.yml` (`helm` v3.19.0, `hashicorp/terraform:1.13.4`) |
| *Optional:* Playwright browsers | Playwright 1.x + Chromium | `tools/e2e/*.js` |
| Hardware | 16 GB RAM (32 GB recommended), 20 GB free disk | Keycloak + PostgreSQL + two JVMs + up to four dev servers |

There is no `.nvmrc` in the repository; this package ships one ([`ide/nvmrc`](ide/nvmrc), content `24`) that
`ide/scripts/install-ide-files.sh nvmrc` copies to the repository root for nvm/fnm users.

### 2.1 macOS (Apple silicon and Intel)

Install Homebrew if it is missing:

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

JDK 21 – **either** Homebrew:

```bash
brew install --cask temurin@21
```

```bash
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 21)' >> ~/.zshrc && source ~/.zshrc
```

**or** SDKMAN (useful if you switch JDKs often):

```bash
curl -s "https://get.sdkman.io" | bash
```

```bash
sdk list java | grep -- '-tem'
```

```bash
sdk install java 21.0.12-tem
```

Pick the newest `21.x…-tem` identifier from the list if `21.0.12-tem` is no longer offered.

Node 24, Git and command-line tools:

```bash
brew install node@24 git jq openssl@3 postgresql@16
```

```bash
brew link --overwrite --force node@24
```

(`postgresql@16` is only needed for `psql`; Homebrew installs it keg-only – add
`$(brew --prefix postgresql@16)/bin` to your `PATH` if `psql` is not found.)

Container runtime – choose **one**:

| Runtime | Install | Notes |
|---|---|---|
| Docker Desktop | `brew install --cask docker` | Licence required for larger companies. On Apple silicon enable *Settings → General → Use Rosetta for x86_64/amd64 emulation* |
| Colima (open source) | `brew install colima docker docker-compose docker-buildx` then `colima start --cpu 6 --memory 12 --disk 60 --vm-type vz --vz-rosetta` | Testcontainers needs the socket settings in section 3.2 |
| Rancher Desktop (open source) | `brew install --cask rancher` | Select the **dockerd (moby)** container engine, not containerd, so that `docker compose` works |

Optional tools:

```bash
brew install kubernetes-cli helm terraform
```

### 2.2 Windows 11 with WSL2

Develop **inside WSL2** (Ubuntu). The shell scripts (`mvnw`, `deploy/local/*.sh`, `ide/scripts/*.sh`) are Bash, the
repository uses LF line endings (`.editorconfig`), and file I/O on the WSL file system is much faster than on
`/mnt/c`.

In an elevated PowerShell:

```powershell
wsl --install -d Ubuntu-24.04
```

```powershell
winget install --id Git.Git -e
```

```powershell
winget install --id Docker.DockerDesktop -e
```

Install your IDE on Windows (it connects into WSL):

```powershell
winget install --id Microsoft.VisualStudioCode -e
```

```powershell
winget install --id JetBrains.IntelliJIDEA.Ultimate -e
```

(`JetBrains.IntelliJIDEA.Community` for the free edition.)

Then:

1. Docker Desktop → *Settings → Resources → WSL integration* → enable **Ubuntu-24.04**. (Docker Desktop needs a paid
   licence in larger companies; alternatively install Docker Engine inside WSL as in section 2.3.)
2. Give WSL enough memory: create `%UserProfile%\.wslconfig` with
   ```ini
   [wsl2]
   memory=12GB
   processors=6
   ```
   and run `wsl --shutdown`.
3. Open the Ubuntu shell and follow **section 2.3** for JDK, Node and tools.
4. Clone into the Linux home directory (`~/src/WellnessZ`), **not** under `/mnt/c`.
5. Keep LF line endings: `git config --global core.autocrlf input`.
6. IDE: VS Code with the *WSL* extension (`code .` from the Ubuntu shell), or IntelliJ IDEA opening
   `\\wsl$\Ubuntu-24.04\home\<you>\src\WellnessZ` with a **WSL JDK** as project SDK so that Maven, the applications
   and tests run inside WSL (the generated env files contain Linux paths).

### 2.3 Ubuntu 24.04 (also WSL2)

Base packages:

```bash
sudo apt-get update && sudo apt-get install -y git curl jq openssl unzip ca-certificates gnupg postgresql-client-16
```

JDK 21 – **either** the Adoptium APT repository:

```bash
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
```

```bash
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" | sudo tee /etc/apt/sources.list.d/adoptium.list
```

```bash
sudo apt-get update && sudo apt-get install -y temurin-21-jdk
```

**or** SDKMAN as in section 2.1 (`sdk install java 21.0.12-tem`).

Node 24 with nvm:

```bash
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.3/install.sh | bash
```

```bash
source ~/.bashrc && nvm install 24 && nvm alias default 24
```

Docker Engine with the Compose plugin (skip on WSL if you use Docker Desktop): follow
<https://docs.docker.com/engine/install/ubuntu/> (packages `docker-ce docker-ce-cli containerd.io docker-buildx-plugin
docker-compose-plugin`), then allow your user to use Docker without `sudo`:

```bash
sudo usermod -aG docker "$USER" && newgrp docker
```

Optional tools:

```bash
sudo snap install kubectl --classic && sudo snap install helm --classic
```

Terraform 1.13.x from the HashiCorp APT repository (<https://developer.hashicorp.com/terraform/install>), or run it from
the same image CI uses: `docker run --rm -v "$PWD:/tf" -w /tf hashicorp/terraform:1.13.4 …`.

### 2.4 Verify the toolchain (all systems)

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

Expected: a server version (not an error) and `Docker Compose version v2.x`.

---

## 3. Clone, build and run – step by step

### 3.1 Clone

```bash
git clone https://github.com/OWNER/WellnessZ.git && cd WellnessZ
```

(Replace `OWNER` with the organisation that hosts the repository.)

```bash
./mvnw -v
```

Expected: `Apache Maven 3.9.11` and `Java version: 21…`. The first call downloads Maven into `~/.m2/wrapper`.

Optionally copy the IDE files from this package into the places the IDEs look for them (never overwrites existing
files – see section 4 for what each target does):

```bash
docs/developer-setup/ide/scripts/install-ide-files.sh all
```

### 3.2 First build

```bash
./mvnw -B verify
```

Expected: `BUILD SUCCESS` for all 26 projects (parent + 25 modules). The build runs unit tests, the **integration tests
on a real PostgreSQL 16**, the ArchUnit boundary rules (`ArchitectureTest`), JaCoCo coverage and a CycloneDX SBOM
(`target/bom.json`). This is exactly the first step of the CI `java` job.

**Where the integration tests get their PostgreSQL** (`apps/wz-core-app/src/test/java/com/wellnessz/app/IntegrationTestBase.java`,
`apps/wz-bff/src/test/java/com/wellnessz/bff/BffSecurityTest.java`):

| Mode | When | What you need |
|---|---|---|
| **Testcontainers (default)** | `WZ_TEST_DB_URL` is not set | A running Docker daemon. A throw-away `postgres:16-alpine` container is started once per test JVM. Nothing else to do. |
| **Existing database** | `WZ_TEST_DB_URL` (+ `WZ_TEST_DB_USERNAME`, `WZ_TEST_DB_PASSWORD`, optional `WZ_TEST_DB_MIGRATOR_USERNAME`, `WZ_TEST_DB_MIGRATOR_PASSWORD`) is set | The local stack (section 3.4) and a **freshly recreated** test database before every run |

**Why the test database must be recreated before every run.** `IntegrationTestBase` generates a new `WZ_MASTER_KEY`
and `WZ_PSEUDONYM_KEY` for every test JVM, so encrypted columns are keyed per run. Rows written by the previous run can
no longer be decrypted and the next run fails (`AEADBadTagException`, "cannot decrypt" or "Decryption failed").
Testcontainers avoids this implicitly with a fresh container. With the local database, use the script that does both
steps for you:

```bash
docs/developer-setup/ide/scripts/verify-with-local-db.sh
```

It drops and recreates `wellnessz_test` in the compose PostgreSQL (`recreate-test-db.sh`), sets the `WZ_TEST_DB_*`
variables for that process only – reading the passwords from `deploy/local/.env` at run time – and runs
`./mvnw verify`. Arguments are passed through, for example:

```bash
docs/developer-setup/ide/scripts/verify-with-local-db.sh -pl apps/wz-core-app -am verify
```

Keep the `_test` suffix (the script enforces it): the E07 demo data skips the return-after-illness requirement for
`E1004` on `*_test` databases so that it does not block the E05 fitness tests.

**Testcontainers with Colima or Rancher Desktop.** Testcontainers looks for `/var/run/docker.sock`. Point it at your
runtime once, in `~/.testcontainers.properties` (works for terminal *and* IDE test runs):

```properties
# Colima
docker.host=unix:///Users/<you>/.colima/default/docker.sock
# Rancher Desktop (macOS)
# docker.host=unix:///Users/<you>/.rd/docker.sock
```

and export `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` in your shell profile (the socket path inside
the VM).

**Faster inner loop.** Build without tests while you work, then run the gates before pushing:

```bash
./mvnw -B -DskipTests package
```

```bash
./mvnw -B -pl apps/wz-core-app -am -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false test
```

(The second command runs only the ArchUnit rules – 22 at the time of writing – in a few seconds.)

### 3.3 Install the web dependencies

As CI does (lockfile only, no lifecycle scripts):

```bash
docs/developer-setup/ide/scripts/web-all.sh install
```

or per app:

```bash
cd web/oh-workspace && npm ci --ignore-scripts --no-audit --no-fund && cd ../..
```

`npm ci` refuses to run if `package-lock.json` and `package.json` disagree – never "fix" that with `npm install`
unless you are deliberately changing dependencies (exact versions only; see section 6).

### 3.4 Start the local stack

```bash
./deploy/local/up.sh
```

On the first run the script generates `deploy/local/.env` (mode 600, git-ignored) from `deploy/local/.env.example` with
random database passwords, the Keycloak admin password, `WZ_MASTER_KEY`, `WZ_PSEUDONYM_KEY`, `WZ_BFF_MASTER_KEY` and
`WZ_IMAP_PASSWORD`; then it starts the containers and waits until all health checks pass.

| Service | Address (bound to 127.0.0.1 only) | Credentials |
|---|---|---|
| PostgreSQL 16.15 | `localhost:5432` – databases `wellnessz` and `keycloak` | Roles `wz_migrator` (schema owner, Flyway DDL), `wz_app` (core runtime, DML only), `wz_bff` (BFF session schema), `keycloak`; passwords in `.env` |
| Keycloak 26.8.1 | <http://localhost:8180> – realm `wellnessz`, admin console <http://localhost:8180/admin> | `KEYCLOAK_ADMIN_USER` (default `kcadmin`) / `KEYCLOAK_ADMIN_PASSWORD` from `.env` |
| Mailpit 1.32 | UI <http://localhost:8025>, SMTP `localhost:1025` | none – catches every e-mail the platform and Keycloak send |
| GreenMail 2.1.15 | IMAP `localhost:3143`, SMTP `localhost:3025` | `ehs-feed@wellnessz.local` / `WZ_IMAP_PASSWORD` (EHS incident mailbox) |
| OpenTelemetry Collector (contrib 0.164.0) | OTLP gRPC `localhost:4317`, HTTP `localhost:4318`, health `localhost:13133` | – (debug exporter: traces appear in the container log) |

Check:

```bash
docker compose -f deploy/local/docker-compose.yml ps --format '{{.Service}}: {{.Status}}'
```

Expected: `postgres`, `keycloak`, `mailpit` and `greenmail` are `(healthy)`, `otel-collector` is `Up`.

`./deploy/local/up.sh --apps` would also build and start core and BFF as hardened containers. **Do not** combine that
with running them from the IDE – the ports clash. Stop the stack with `./deploy/local/down.sh` (keeps data) or
`./deploy/local/down.sh --purge` (also deletes the PostgreSQL volume, i.e. all local data and the imported realm).

### 3.5 Generate the IDE env files (no plaintext secrets in run configurations)

Core and BFF read their settings from environment variables. The variable names the applications expect differ from
the ones in `deploy/local/.env` (for example `WZ_DB_PASSWORD` ← `WZ_APP_DB_PASSWORD`, and the BFF's `WZ_MASTER_KEY` ←
`WZ_BFF_MASTER_KEY`). Instead of pasting passwords into run configurations, generate two env files:

```bash
docs/developer-setup/ide/scripts/make-ide-env.sh
```

| File | Content | Protection |
|---|---|---|
| `~/.config/wellnessz/core-master.key`, `bff-master.key` | The two 256-bit master keys (base64) – the only secrets in clear on disk besides `.env` | Outside the repository, directory mode 700, files mode 600 (override the location with `WZ_IDE_KEY_DIR`) |
| `deploy/local/core.local.env` | `SPRING_PROFILES_ACTIVE=local`, `WZ_MASTER_KEY_FILE=…/core-master.key`, database, IMAP and SMTP settings; **every secret as `ENC(...)`** | Git-ignored (`deploy/local/.gitignore`: `*.local.env`), mode 600 |
| `deploy/local/bff.local.env` | `SPRING_PROFILES_ACTIVE=local`, `WZ_MASTER_KEY_FILE=…/bff-master.key`, BFF database role, client secret – secrets as `ENC(...)` | as above |

How it works: `platform/wz-secrets` registers `SecretsEnvironmentPostProcessor`, which decrypts any `ENC(...)` value
(AES-256-GCM, produced by `EncryptCli`, the class behind `tools/encrypt-secret.sh`) with the key from
`WZ_MASTER_KEY_FILE` before Spring binds the configuration. The same mechanism also resolves `file:/path` references
for properties whose name contains *password*, *secret*, *key*, *token* or *credential* – the pattern used with mounted
secret files in Kubernetes. The run configurations in this package therefore only reference the env files; they never
contain a secret. Re-run the script after `./deploy/local/down.sh --purge` or whenever `.env` changes.

> The core was started from these env files against a PostgreSQL 16 with SCRAM authentication while this guide was
> written (Flyway applied all migrations, readiness `UP`); the BFF got as far as its own migration and needs Keycloak
> running to finish starting.

Leave `OTEL_EXPORTER_OTLP_ENDPOINT` unset when running from the IDE: the core's default
`http://localhost:4318/v1/traces` already points at the local collector. If you set it, include the `/v1/traces` path.

### 3.6 Start core and BFF from the IDE

Start the **core first**, then the BFF (the BFF calls the core and discovers Keycloak at start-up).

| | Core API | BFF |
|---|---|---|
| Main class | `com.wellnessz.WellnessZCoreApplication` (module `wz-core-app`) | `com.wellnessz.bff.WellnessZBffApplication` (module `wz-bff`) |
| Profile | `local` | `local` |
| Env file | `deploy/local/core.local.env` | `deploy/local/bff.local.env` |
| Ports | 8080 (API), 8081 (management) | 8090, 8091 (management) |
| Debug port when started by script | 5005 | 5006 |
| IntelliJ run configuration | *Core API (local)* / *Core API (local, Community)* | *BFF (local)* / *BFF (local, Community)* |
| VS Code launch | *Core API (local)* | *BFF (local)* |

What the `local` profile adds (core `application.yml`): Flyway also loads `classpath:db/demo` (synthetic HRIS
employees, OH staff roster and module demo data – never used outside `local`/`test`), Swagger UI and `/v3/api-docs`,
`sslmode=prefer` to PostgreSQL, 100 % trace sampling, CORS for ports 5173–5176, SMTP on 1025 without STARTTLS, the
partner MFA gate switched off (`WZ_MFA_REQUIRED_ROLES` empty) and IMAP without TLS.

**Without IDE integration** (any editor, or Eclipse attaching a debugger):

```bash
docs/developer-setup/ide/scripts/run-service.sh core
```

```bash
docs/developer-setup/ide/scripts/run-service.sh bff
```

Each packages its module (`-DskipTests`), loads the env file and starts the jar with a JDWP agent on `127.0.0.1:5005`
(core) or `5006` (BFF). `WZ_DEBUG_SUSPEND=y` waits for the debugger; `WZ_SKIP_BUILD=1` reuses the jar.

> **Do not use `./mvnw -pl apps/wz-core-app -am spring-boot:run`.** With `-am` Maven runs the `spring-boot:run` goal on
> the parent POM first and fails with *Unable to find a suitable main class*. Either use the script above, or install
> the reactor once and run the module alone: `./mvnw -q -DskipTests install` then
> `./mvnw -pl apps/wz-core-app spring-boot:run -Dspring-boot.run.profiles=local` (with the env file loaded; remember to
> re-install after changing platform or module code).

Verify:

```bash
curl -s http://localhost:8081/actuator/health/readiness && curl -s http://localhost:8091/actuator/health/readiness
```

Expected: `{"status":"UP"}{"status":"UP"}`.

```bash
TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=wellnessz-integration -d client_secret=change-me-local-only http://localhost:8180/realms/wellnessz/protocol/openid-connect/token | jq -r .access_token)
```

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/me | jq
```

Expected: `"roles": ["INTEGRATION"]`. (`change-me-local-only` is the documented local-only secret of the realm file –
never a real credential.)

### 3.7 Start the four Vite apps

| App | Directory | URL | Typical users |
|---|---|---|---|
| Employee / crew portal | `web/employee-app` | <http://localhost:5173> | `employee1`, `candidate1` |
| Manager & HR workspace | `web/manager-workspace` | <http://localhost:5174> | `manager1`, `hr1` |
| OH clinical workspace | `web/oh-workspace` | <http://localhost:5175> | `nurse1`, `nursinghead1`, `physician1`, `admin1`, `dpo1`, … |
| Partner portal | `web/partner-portal` | <http://localhost:5176> | `partner1`, `physio1`, `adjuster1` |

In one terminal per app (or with the IDE configurations *Vite: …*):

```bash
cd web/oh-workspace && npm run dev
```

Each dev server has `strictPort: true` and proxies `/bff`, `/oauth2`, `/login` and `/logout` to the BFF
(`WZ_BFF_TARGET`, default `http://localhost:8090`). Always use **`localhost`**, never `127.0.0.1`: the Keycloak redirect
URIs and the BFF cookies are bound to `localhost:5173`–`5176`.

### 3.8 Sign in with the demo users

All realm users (`deploy/local/keycloak/wellnessz-realm.json`) start with the **temporary password `ChangeMe!123`** and
must choose a new one at first login (policy: at least 12 characters with upper case, lower case, digit and special
character, not the user name or e-mail, not one of the last 5). The increment E2E scripts change it to
`Wz-Local-Demo-2026!` (`WZ_E2E_NEW_PASSWORD`). Use a private window per user to keep sessions apart.

| User | Realm roles | `employee_id` / `org_scope` | Use it for |
|---|---|---|---|
| `employee1` | EMPLOYEE | E1001 · /AIRLINE/CATERING | Employee portal: self-referral, consents, bookings |
| `manager1` | MANAGER | M2001 · /AIRLINE/CATERING | Manager workspace: status-only case and fitness views |
| `hr1` | HR | H3001 · /AIRLINE | Manager workspace: HR status, claims status only |
| `clinician1` | OH_CLINICIAN, CASE_MANAGER | C4001 · /AIRLINE/OH | OH workspace (generic clinician) |
| `psych1` | PSYCH_CLINICIAN | P5001 · /AIRLINE/OH | Restricted psychological cases |
| `auditor1` | AUDITOR | A6001 · /AIRLINE | Audit views |
| `admin1` | PLATFORM_ADMIN, OH_ADMIN | X7001 · /AIRLINE | Configuration screens, dead-letter queue |
| `nurse1`, `nurse2` | OH_CLINICIAN, CASE_MANAGER | N8001, N8002 · /AIRLINE/OH | Work queues, triage, escalation |
| `physician1` | OH_PHYSICIAN, OH_CLINICIAN | D8101 · /AIRLINE/OH | Physician escalations |
| `ame1` | AME, OH_PHYSICIAN, OH_CLINICIAN | D8201 · /AIRLINE/OH | Aeromedical queue, crew assessments |
| `cmo1` | CMO, OH_PHYSICIAN, OH_CLINICIAN | D8301 · /AIRLINE/OH | Protocol approval, CMO escalations |
| `nursinghead1` | NURSING_HEAD, OH_CLINICIAN, CASE_MANAGER | N8401 · /AIRLINE/OH | Team queue, reassignment, triage QA |
| `hse1` | HSE_OFFICER | S8501 · /AIRLINE | EHS reconciliation and exceptions |
| `crewsched1` | CREW_SCHEDULING | S8601 · /AIRLINE/OPS | Status-only crew APIs |
| `hygiene1` | HYGIENE_MANAGER | S8701 · /AIRLINE/CATERING | Food-safety status |
| `reception1` | CLINIC_RECEPTION | R8801 · /AIRLINE/OH | Walk-in registration |
| `firstaider1` | FIRST_AIDER | R8901 · /AIRLINE/GROUND | Emergency registration |
| `dpo1` | DPO | X9001 · /AIRLINE | Break-glass review, disclosure log, retention, legal hold (no clinical access) |
| `partner1` | PARTNER_PROVIDER | P9101 · /PARTNERS/CITYHOSP | Partner portal: City General Hospital referrals |
| `scheduler1` | CLINIC_SCHEDULER | S8611 · /AIRLINE | Clinic diary, bookings, campaigns |
| `biomed1` | BIOMEDICAL | S8602 · /AIRLINE | Equipment, calibration, cold chain |
| `crewlicensing1` | CREW_LICENSING | S8603 · /AIRLINE/OPS | Crew medical certificate validity |
| `candidate1` | CANDIDATE | C9001 · /CANDIDATES | Pre-employment candidate portal (employee app) |
| `claims1` | CLAIMS_COORDINATOR | S8711 · /AIRLINE | Claims, first reports, disclosure sets |
| `adjuster1` | TPA_ADJUSTER | X8702 · /PARTNERS/GULFTPA | Partner portal: GULFTPA claims, pre-authorisations |
| `legal1` | LEGAL_COUNSEL | S8703 · /AIRLINE | Legal & litigation |
| `finance1` | FINANCE | S8704 · /AIRLINE | Claim and treatment cost roll-ups |
| `procurement1` | PROCUREMENT | S8705 · /AIRLINE | Provider network |
| `physio1` | PARTNER_PROVIDER | X8706 · /PARTNERS/PHYSIOPLUS | Partner portal: PhysioPlus referrals, telehealth |
| `peer1` | PEER_SUPPORT_VOLUNTEER | S8801 · /AIRLINE/OPS | Increment 5 (in progress): peer support |
| `peercoord1` | PEER_SUPPORT_COORDINATOR | S8802 · /AIRLINE/OPS | Increment 5: peer-support coordination |
| `safety1` | SAFETY_MANAGER | S8803 · /AIRLINE | Increment 5: aggregated safety figures |
| `damanager1` | DA_PROGRAMME_MANAGER | S8804 · /AIRLINE | Increment 5: drug & alcohol programme |
| `mro1` | MRO | S8805 · /AIRLINE | Increment 5: medical review officer |
| `collector1` | COLLECTOR | S8806 · /AIRLINE | Increment 5: specimen collection |
| `promo1` | HEALTH_PROMOTION_LEAD | S8807 · /AIRLINE | Increment 5: health promotion campaigns |
| `ergo1` | ERGONOMIST | S8808 · /AIRLINE | Increment 5: ergonomics |
| `eap1` | PARTNER_PROVIDER | X8809 · /PARTNERS/EAP_WELLBEING | Increment 5: employee-assistance provider |

Machine client: `wellnessz-integration` (client credentials, role INTEGRATION, secret `change-me-local-only`) – used
by the E2E scripts and the `curl` checks above. Clients `wellnessz-bff` (confidential, redirect URIs for
5173–5176 and 8090) and `wellnessz-mobile` (public, PKCE) have `fullScopeAllowed=false`: a role that is not in their
scope mapping never reaches a token.

**Smoke test by hand.** Open <http://localhost:5173> as `employee1`, submit a self-referral; open
<http://localhost:5174> in a private window as `manager1` – you see the case **status only**; open
<http://localhost:5175> as `nursinghead1` – the work queue lists the new case. More checks per increment: setup
guide 1, sections 6–8.

---

## 4. IDE-specific set-up

The ready-made files live in [`ide/`](ide/). Install them with:

```bash
docs/developer-setup/ide/scripts/install-ide-files.sh intellij vscode devcontainer hook nvmrc
```

| Target | Copies to | Version control |
|---|---|---|
| `intellij` | `.run/*.run.xml` | `.run/` is **not** git-ignored – IntelliJ shares run configurations from there. They contain no secrets; agree with the team before committing them |
| `vscode` | `.vscode/{extensions,launch,settings,tasks}.json`, `.vscode/java-settings.prefs` | `.vscode/` is git-ignored (root `.gitignore`) – personal copy |
| `devcontainer` | `.devcontainer/` | Not git-ignored – commit it if the team wants Codespaces to pick it up automatically |
| `hook` | `.git/hooks/pre-commit` | Local only |
| `nvmrc` | `.nvmrc` | Not git-ignored |

The script never overwrites an existing file. The files are kept under `docs/developer-setup/ide/` (not in the
repository root) on purpose, so that this package can be shipped on its own.

### 4.1 IntelliJ IDEA

1. **Open the project.** *File → Open…* → select the root `pom.xml` → *Open as Project* → *Trust Project*. IntelliJ
   imports all 25 Maven modules.
2. **SDK.** *File → Project Structure → Project*: SDK **Temurin 21** (*Add SDK → Download JDK → Eclipse Temurin 21*
   if needed), language level 21. On Windows choose the JDK inside WSL.
3. **Maven.** *Settings → Build, Execution, Deployment → Build Tools → Maven*: *Maven home path* = **Use Maven
   wrapper**; *Runner → JRE* = project SDK. Reload with the Maven tool window (circular arrows) after `pom.xml`
   changes.
4. **Compiler.** No annotation processors are used (no Lombok, MapStruct or configuration processor), so nothing to
   enable. The root POM compiles with `-parameters`, which IntelliJ takes over from the Maven import – it is
   **required** because the controllers use `@PathVariable` / `@RequestParam` without explicit names. If requests fail
   with *parameter name information not available via reflection*, see section 7.
5. **Plugins.** Install **EnvFile** (*Settings → Plugins → Marketplace*); the run configurations use it to load
   `deploy/local/*.local.env`. Ultimate already bundles Spring, Database Tools, JavaScript/TypeScript, ESLint, Vitest
   and Docker. Newer IntelliJ versions can also load `.env` files natively in the run-configuration dialog; the plug-in
   is used here because it works the same in every edition.
6. **Run configurations.** Run `install-ide-files.sh intellij`; IntelliJ shows them grouped in folders:

   | Folder | Configurations |
   |---|---|
   | Backend | *Core API (local)*, *BFF (local)* (Ultimate, Spring Boot type), *Core API (local, Community)*, *BFF (local, Community)* (Application type), compound *Backend: core + BFF*, *Attach: core (5005)*, *Attach: BFF (5006)* |
   | Local stack | *Local stack: up*, *Local stack: down*, *Generate IDE env files* |
   | Maven | *mvn verify (all modules)*, *mvn verify (core app + dependencies)*, *mvn package (skip tests)*, *mvn -Psecurity verify (SpotBugs + FindSecBugs)*, *mvn verify (local test DB)*, *Recreate test DB* |
   | Tests | *ArchitectureTest (ArchUnit)*, *Core integration tests* (package `com.wellnessz.app`, Testcontainers), *Vitest: …* for each app |
   | Web | *Vite: …* for each app, compound *Web: all four Vite apps*, *Chrome: …* for each app, *Web: install, lint, test, build, audit (all apps)* |

   The shell-script configurations need the bundled *Shell Script* plug-in; on Windows set the interpreter to the WSL
   Bash if `/bin/bash` is not found.
7. **Debugging core/BFF.** Click the debug icon on *Backend: core + BFF* (or on each). Breakpoints in platform and
   module code work because all modules are on the classpath of `wz-core-app`. Hot swap: *Build → Recompile* (method
   bodies only). For a JVM started by `run-service.sh`, use *Attach: core (5005)* / *Attach: BFF (5006)*.
8. **Running tests.** Gutter icons on any test class or method. `ArchitectureTest` needs no database. Integration tests
   (`*IntegrationTest`, `BffSecurityTest`) start Testcontainers – Docker must be running (Colima/Rancher: section 3.2).
   To run them against the compose database use *mvn verify (local test DB)*; avoid setting `WZ_TEST_DB_*` in a JUnit
   run configuration, because you would have to remember to recreate the database before every run.
9. **Database tool window (Ultimate).** *Database → + → Data Source → PostgreSQL*: host `localhost`, port `5432`,
   database `wellnessz`, user `wz_app` (runtime role – sees what the application sees; use `wz_migrator` only to inspect
   DDL). Password handling:
   * Read it once from the git-ignored `.env` – `grep '^WZ_APP_DB_PASSWORD=' deploy/local/.env | cut -d= -f2-` – and
     paste it into the dialog with *Save: Forever*.
   * Make IntelliJ keep it in the **operating-system keychain**: *Settings → Appearance & Behavior → System Settings →
     Passwords → In native Keychain* (macOS Keychain, Windows Credential Manager, GNOME Keyring/KWallet).
   * Tick *Read-only* on the data source; `wz_app` also has a 30-second `statement_timeout`.
   * Encrypted columns show `v1:…` ciphertext – that is intended. Never decrypt clinical data outside the application.
   * Never put a database password into a run configuration, `dataSources.xml` or a script in the repository.
10. **ESLint and TypeScript.** *Settings → Languages & Frameworks → JavaScript → Code Quality Tools → ESLint →
    Automatic ESLint configuration* (each app has its own flat `eslint.config.js`) and tick *Run eslint --fix on save*.
    *TypeScript*: use the project's TypeScript (`web/<app>/node_modules/typescript`, 6.0.3). Node interpreter: Node 24.
    The repository does **not** use Prettier – leave Prettier's *Run on save* off, otherwise formatting drifts from
    ESLint and `.editorconfig`.
11. **Vitest.** Gutter icons in `src/**/*.test.ts(x)` or the *Vitest: …* configurations. Each app's `vite.config.ts`
    holds the test settings (`environment: 'node'`, `include: ['src/**/*.test.{ts,tsx}']`).
12. **Chrome debugging (Ultimate).** Start *Vite: OH workspace (5175)*, then debug *Chrome: OH workspace*; breakpoints in
    `.tsx` files work through Vite's source maps.
13. **Code style.** *Settings → Editor → Code Style → Scheme ⚙ → Import Scheme → IntelliJ IDEA code style XML* →
    [`ide/intellij/WellnessZ-code-style.xml`](ide/intellij/WellnessZ-code-style.xml): 4-space Java with 8-space
    continuation, **no wildcard imports**, import order *static / `java.*`, `javax.*` / everything else*, 2-space
    TypeScript with single quotes and semicolons, LF endings. Keep *Settings → Editor → Code Style → Enable EditorConfig
    support* on (`.editorconfig` in the root).
14. **Exclude noise.** Mark `.claude/worktrees` (if present – agent worktrees) and `docs/dist` as *Excluded* so that
    search and indexing ignore duplicated sources.

### 4.2 Visual Studio Code

1. Run `install-ide-files.sh vscode`, open the repository folder and accept *Install recommended extensions*:

   | Extension | Purpose |
   |---|---|
   | `vscjava.vscode-java-pack` | Java language server, Maven, JUnit test runner, debugger |
   | `vmware.vscode-boot-dev-pack` | Spring Boot tools, `application.yml` support, Boot dashboard |
   | `redhat.vscode-xml` | `pom.xml`, `*.run.xml` |
   | `dbaeumer.vscode-eslint` | ESLint (flat config, four working directories) |
   | `vitest.explorer` | Vitest in the Testing view |
   | `editorconfig.editorconfig` | `.editorconfig` |
   | `ms-azuretools.vscode-docker` | Docker / Compose |
   | `ms-vscode-remote.remote-containers` | Dev Containers |
   | `redhat.vscode-yaml` | YAML; Helm values validated against `values.schema.json` |
   | `ms-kubernetes-tools.vscode-kubernetes-tools` | Kubernetes and Helm |
   | `hashicorp.terraform` | Terraform (`deploy/terraform`) |
   | `github.vscode-github-actions` | Workflow files |
   | `timonwong.shellcheck` | Shell scripts (CI-equivalent check) |
   | `mtxr.sqltools` + `mtxr.sqltools-driver-pg` | PostgreSQL client |

   Prettier is listed under *unwantedRecommendations* because the project formats with ESLint and `.editorconfig` only.
2. **JDK.** If Java 21 is not your default, add to your **user** settings:
   `"java.configuration.runtimes": [{ "name": "JavaSE-21", "path": "/path/to/temurin-21", "default": true }]`.
3. **Workspace settings** (`ide/vscode/settings.json`) exclude `target/`, `node_modules/` and agent worktrees, prefer the
   Maven wrapper, register the four ESLint working directories, use the workspace TypeScript and point
   `java.settings.url` at `java-settings.prefs` – which switches on `-parameters` for the Eclipse compiler used by the
   Java extension (see section 4.1, step 4).
4. **Launch configurations** (`launch.json`, *Run and Debug* view):

   | Configuration | What it does |
   |---|---|
   | *Core API (local)*, *BFF (local)* | Launch with `envFile` = `deploy/local/core.local.env` / `bff.local.env` |
   | *Backend: core + BFF* (compound) | Both; start the stack first |
   | *Attach: core (5005)*, *Attach: BFF (5006)* | Attach to `run-service.sh` or the *Core/BFF: run with debugger* tasks |
   | *Vite: …* (four) | `npm run dev` in the app directory |
   | *Chrome: …* (four) | Chrome with the built-in JavaScript debugger, `webRoot` = the app |
   | *Debug … : Vite + Chrome* (compounds) | Dev server and browser together – if Chrome opens before Vite is ready, reload the page |
   | *E2E: run the open tools/e2e script* | Debug an E2E script step by step |

   The debug Chrome uses its own profile, which conveniently separates its Keycloak session from your normal browser.
5. **Tasks** (`tasks.json`, *Terminal → Run Task*): local stack up/down/purge/status, IDE env files, Maven verify
   (default build task), verify with local test DB, recreate test DB, package, SpotBugs, ArchitectureTest only, core/BFF
   with debugger, web install/lint/test/CI check (default test task: `web-all.sh test`), the four dev servers, the E2E
   scripts and gitleaks.
6. **Java tests.** The *Testing* view lists JUnit tests of all modules. Integration tests need Docker (Testcontainers).
   For the compose database, run the task *Maven: verify (local test DB)*.
7. **Vitest.** The Vitest explorer discovers the four `vite.config.ts` files; run or debug single tests from the
   Testing view or the gutter.
8. **Database.** The SQLTools connections in `settings.json` (`wz_app` and `wz_migrator`) use `askForPassword` – the
   password is never written to a file. Read it from `.env` when prompted.

### 4.3 Eclipse with Spring Tools

1. Install the current **Spring Tools for Eclipse** distribution (it bundles m2e and the Spring Boot tooling) and a
   Temurin 21 JDK (*Preferences → Java → Installed JREs → Add…*, tick it as default; *Compiler → compliance 21*).
2. *File → Import… → Maven → Existing Maven Projects* → root directory = repository → select all 26 projects → *Finish*.
3. *Preferences → Java → Compiler* → tick **Store information about method parameters (usable via reflection)** (m2e
   normally takes `<parameters>true</parameters>` from the POM; check it once, for the reasons in section 4.1, step 4).
4. No annotation processing to configure. *Preferences → Maven → User Settings* only if you need a proxy
   (`~/.m2/settings.xml`).
5. **Running and debugging.** Eclipse cannot load env files natively. Two options without plaintext secrets:
   * **Recommended:** start the services with `docs/developer-setup/ide/scripts/run-service.sh core` / `bff` in a
     terminal and import the attach configurations from [`ide/eclipse/`](ide/eclipse/) (*File → Import… → Run/Debug →
     Launch Configurations*): *WellnessZ-attach-core-5005*, *WellnessZ-attach-BFF-5006*.
   * Or create a *Spring Boot App* launch (Boot Dashboard or *Run Configurations…*), profile `local`, and copy the lines
     of `deploy/local/core.local.env` into the *Environment* tab – they only contain `ENC(...)` values and the key-file
     path, so the launch file holds no plaintext secret. Keep the launch in the workspace metadata (default), do not
     share it into the repository.
6. Tests: *Run As → JUnit Test* on a class or package; integration tests need Docker (Testcontainers).
7. Web apps: use VS Code (or IntelliJ Ultimate) – Eclipse's TypeScript support is limited.
8. Code style: import the Java formatter settings that match section 4.1, step 13 manually (4 spaces, continuation
   8, no wildcard imports, *Organize Imports* order `#`, `java`, `javax`, everything else).

### 4.4 Dev Container and GitHub Codespaces

The dev container ([`ide/devcontainer/`](ide/devcontainer/)) builds on `mcr.microsoft.com/devcontainers/base:ubuntu-24.04`
and adds:

| Component | Source |
|---|---|
| Temurin JDK 21 | feature `ghcr.io/devcontainers/features/java:1` (`jdkDistro: tem`; Maven comes from `./mvnw`) |
| Node 24 + npm | feature `ghcr.io/devcontainers/features/node:1` |
| Docker (for the compose stack and Testcontainers) | feature `ghcr.io/devcontainers/features/docker-in-docker:2` |
| kubectl, Helm 3.19.0 | feature `ghcr.io/devcontainers/features/kubectl-helm-minikube:1` |
| Terraform 1.13.4 | feature `ghcr.io/devcontainers/features/terraform:1` |
| `psql` 16, jq, openssl, shellcheck | `Dockerfile` (APT) |
| Maven cache | named volume `wellnessz-m2` → `/home/vscode/.m2` (survives rebuilds) |
| Forwarded ports | 5173–5176, 8080/8081, 8090/8091, 8180, 8025, 5432 |

`post-create.sh` fixes the cache-volume ownership, prints the tool versions and runs `npm ci --ignore-scripts` in the four
web apps. Set `WZ_DEVCONTAINER_PLAYWRIGHT=1` (for example in `remoteEnv`) to install Playwright and Chromium as well.

**Local Dev Containers (VS Code).**

1. Run `install-ide-files.sh devcontainer` (creates `.devcontainer/`).
2. *Dev Containers: Reopen in Container*. The first build takes 5–10 minutes.
3. In the container terminal: `./deploy/local/up.sh`, `docs/developer-setup/ide/scripts/make-ide-env.sh`,
   `./mvnw -B verify`, then use the launch configurations as in section 4.2.
4. Give Docker at least 8 CPUs / 16 GB if possible – the compose stack runs *inside* the container.

**Why some ports must keep their number.** Keycloak issues tokens for `http://localhost:8180/realms/wellnessz`, and the
realm only accepts redirect URIs on `http://localhost:5173`–`5176` and `8090`. `devcontainer.json` therefore marks
5173–5176, 8090 and 8180 with `requireLocalPort: true`: VS Code refuses to forward them to a different local port. Free
those ports on your machine first (no locally running Keycloak, BFF or Vite).

**GitHub Codespaces.** Commit `.devcontainer/` (or create the codespace from a branch that has it) and choose a machine
with at least 8 cores / 16 GB (`hostRequirements`). Build, tests, ESLint, Vitest and the API work everywhere. **Browser
sign-in only works when you open the codespace in VS Code desktop** (or JetBrains Gateway), which forwards the ports to
`localhost` on your machine. In the browser-based editor the forwarded URLs look like
`https://<name>-5173.app.github.dev`; they match neither the Keycloak issuer nor the registered redirect URIs, so the
login fails. Do not "fix" that by adding Codespaces URLs to the realm file in the repository.

---

## 5. Connecting tools

### 5.1 Database clients

| Client | Connection |
|---|---|
| psql | `psql -h localhost -U wz_app -d wellnessz` – prompts for the password (from `.env`). As the schema owner: `-U wz_migrator` |
| psql inside the container (no local client) | `docker compose -f deploy/local/docker-compose.yml exec postgres sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -U postgres -d wellnessz'` |
| IntelliJ Database tool window | Section 4.1, step 9 |
| VS Code SQLTools | Section 4.2, step 8 |
| DBeaver / pgAdmin | Host `localhost`, port 5432, database `wellnessz`; store the password in the tool's secure storage, not in a project file |

Application schemas: `platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, absence, surveillance,
scheduling, treatment, claims, mentalhealth, substance, travelhealth, promotion, analytics` (core) and `bff` (BFF
sessions). Flyway history: `platform.flyway_schema_history`.

### 5.2 Keycloak admin console

<http://localhost:8180/admin> → user `kcadmin` (or `KEYCLOAK_ADMIN_USER`), password from
`grep '^KEYCLOAK_ADMIN_PASSWORD=' deploy/local/.env | cut -d= -f2-` → realm **wellnessz**. Useful places: *Users* (reset
a demo password, required actions such as *Configure OTP*), *Clients → wellnessz-bff → Client scopes → Evaluate*
(preview the token of a user), *Realm settings → Email* (sends through Mailpit). Changes made here live in the
`keycloak` database, not in `wellnessz-realm.json` – copy deliberate changes back into the realm file (and the scope
mappings of `wellnessz-bff` and `wellnessz-mobile` for new roles).

### 5.3 OpenAPI / Swagger UI

With the `local` profile: Swagger UI <http://localhost:8080/swagger-ui.html>, JSON <http://localhost:8080/v3/api-docs>.
Use *Authorize* with the integration token from section 3.6 (user tokens stay inside the BFF and cannot be copied from
the browser). The committed contract is `docs/api/openapi.json`; IntelliJ Ultimate renders it with its OpenAPI support.

### 5.4 Mail catchers

* **Mailpit** <http://localhost:8025>: every notification and Keycloak e-mail (password reset, OTP set-up).
* **GreenMail** IMAP `localhost:3143` (`ehs-feed@wellnessz.local` / `WZ_IMAP_PASSWORD`): the EHS incident mailbox the
  core polls (`WZ_EHS_MAILBOX_MODE=imap`; default poll interval 5 minutes, `WZ_EHS_MAILBOX_POLL=PT30S` for faster
  tests). Senders must end in `@wellnessz.local`. Setup guide 1, section 8.1, shows how to drop a test mail.

### 5.5 OpenTelemetry and metrics

The collector prints received spans with its *debug* exporter:

```bash
docker compose -f deploy/local/docker-compose.yml logs -f otel-collector
```

Health: <http://localhost:13133>. The `local` profile samples 100 % of traces. Prometheus metrics:
<http://localhost:8081/actuator/prometheus> (core) and <http://localhost:8091/actuator/prometheus> (BFF).

### 5.6 Playwright end-to-end scripts

Install once (repository root, not saved to any `package.json`):

```bash
npm install --no-save playwright@1 && npx playwright install chromium
```

(On Ubuntu/WSL add `--with-deps` to the second command.) Requirements: local stack, core with the **`local` profile**
(demo data), BFF and the dev servers the script uses.

| Script | Apps it needs | Checks | Notes |
|---|---|---|---|
| `tools/e2e/e2e.js` | 5173, 5174 | Login, self-referral, manager status-only view | Expects **temporary** passwords: run on a freshly imported realm |
| `tools/e2e/e2e-increment1.js` | 5173–5175 | 18 | Handles first and later logins; repeatable |
| `tools/e2e/e2e-increment2.js` | 5173–5175 | 25 | Repeatable; creates new cases each run |
| `tools/e2e/e2e-increment3.js` | 5173–5175 | 32 | **Consumes demo state** – needs a fresh database |
| `tools/e2e/e2e-increment4.js` | 5173–5176 | 22 | **Consumes demo state**; MFA gate must be off (`local` default) |

Gate run on a fresh database:

```bash
./deploy/local/down.sh --purge && ./deploy/local/up.sh
```

```bash
docs/developer-setup/ide/scripts/make-ide-env.sh
```

Restart core and BFF from the IDE, start the four dev servers, then:

```bash
for n in 1 2 3 4; do node tools/e2e/e2e-increment$n.js || break; done
```

Variables: `WZ_E2E_EMPLOYEE_URL`, `WZ_E2E_WORKSPACE_URL`, `WZ_E2E_OH_URL`, `WZ_E2E_PARTNER_URL`, `WZ_E2E_CORE_URL`,
`WZ_E2E_IDP_URL`, `WZ_E2E_INTEGRATION_SECRET`, `WZ_E2E_NEW_PASSWORD`, `WZ_E2E_OUT` (screenshots, default
`tools/e2e/shots/`, git-ignored). To debug a script, open it in VS Code and run *E2E: run the open tools/e2e script*.

---

## 6. Quality gates, commits and secrets hygiene

### 6.1 Run the CI gates locally

Every pull request runs `.github/workflows/ci.yml` and `codeql.yml`. Run the same checks before you push:

| Gate | Command | IDE shortcut |
|---|---|---|
| Build, unit + integration tests, ArchUnit, JaCoCo, SBOM | `./mvnw -B verify` | IntelliJ *mvn verify (all modules)*; VS Code default build task |
| SAST – SpotBugs + FindSecBugs (fails on any finding) | `./mvnw -B -Psecurity -DskipTests -Ddependency-check.skip=true verify` | *mvn -Psecurity verify …* / task *Maven: SpotBugs + FindSecBugs* |
| SCA – OWASP dependency-check (CVSS ≥ 7) | `NVD_API_KEY=… ./mvnw -B -DskipTests package org.owasp:dependency-check-maven:aggregate` | – (slow; needs a free NVD API key) |
| ArchUnit only | `./mvnw -B -pl apps/wz-core-app -am -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false test` | *ArchitectureTest (ArchUnit)* |
| Web: install, lint (`--max-warnings 0`), Vitest, build (`tsc -b && vite build`), `npm audit` | `docs/developer-setup/ide/scripts/web-all.sh check` | *Web: install, lint, test, build, audit* / task *Web: CI check all* |
| Secrets (gitleaks) | `docker run --rm -v "$PWD:/repo:ro" ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo --config /repo/.gitleaks.toml --redact` | task *Secrets: gitleaks (as CI)* |
| IaC, Helm, Terraform, workflows, shell scripts | See CI/CD Guide, section 3 (Trivy 0.76.1, `helm lint`, `terraform fmt`, `actionlint`, `shellcheck`) | – |

Coverage reports: `*/target/site/jacoco/index.html`. SpotBugs exclusions only in `config/spotbugs-exclude.xml` and
dependency-check suppressions only in `config/dependency-check-suppressions.xml` – each with justification, owner and
expiry.

### 6.2 Pre-commit hook (recommended)

```bash
docs/developer-setup/ide/scripts/install-ide-files.sh hook
```

The hook ([`ide/git-hooks/pre-commit`](ide/git-hooks/pre-commit)) refuses staged `.env`, `*.local.env`, key, keystore,
`*.tfvars` and Terraform state files; runs gitleaks on the staged changes (same image and configuration as CI); and
runs ESLint for every web app with staged changes. In an emergency `WZ_HOOK_SKIP=gitleaks,eslint git commit …` skips
the slower steps – never the file check. CI repeats all of it anyway.

### 6.3 Branches, commits and pull requests

From the CI/CD Guide, section 1:

* **Trunk-based**: branch from `main` as `feature/<ticket>-<slug>`, `fix/<ticket>-<slug>` or `chore/…`; branches live
  for days, not weeks.
* **Commits are signed** (SSH or GPG) and **reference the ticket**. Write an imperative subject line of at most about 72
  characters that says what changes (for example `Absence feed: reject rows without a badge id`), and use the body for
  the why. Example set-up for SSH signing:

  ```bash
  git config --global gpg.format ssh && git config --global user.signingkey ~/.ssh/id_ed25519.pub && git config --global commit.gpgsign true
  ```
* **Squash merge** through a pull request only; fill in `.github/pull_request_template.md` (summary, change type,
  data-protection checklist, tests, deployment notes). Two approvals; Code Owners review `deploy/`, `.github/`,
  `platform/wz-security`, `platform/wz-secrets`, migrations, `.gitleaks.toml` and `.trivyignore`.

### 6.4 Secrets hygiene

* **Never commit** `deploy/local/.env`, `*.local.env`, master keys, `*.pem`, `*.key`, `*.p12`, `*.jks`, `*.tfvars` or
  Terraform state – they are git-ignored; do not force-add them. Do not paste them into tickets, chats or screenshots.
* Local values are for your machine only. Shared and cloud environments use Key Vault / Secret Manager / Vault through
  the Secrets Store CSI driver (setup guides 2–4).
* Secrets in configuration are either **`ENC(...)`** (create with `tools/encrypt-secret.sh`; the value is read without
  echo, never from arguments) or **`file:/path`** references (only honoured for property names containing *password*,
  *secret*, *key*, *token* or *credential*). Never plaintext.
* Run configurations, `launch.json`, `.run/*.xml` and Eclipse launches reference env files – they never contain
  secrets. Database passwords live in the IDE's password safe / OS keychain.
* The key files in `~/.config/wellnessz` should sit on an encrypted disk (FileVault, BitLocker, LUKS – usually the
  default on managed laptops).
* Test fixtures use throw-away keys generated per run (`IntegrationTestBase`); never add real or reusable keys to
  tests.
* No real personal or health data anywhere outside the platform – demo data is synthetic.

---

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `up.sh` or a container fails with *address already in use* / *port is already allocated* (5432, 8180, 8025, 8080, 8090, 5173–5176 …) | A local PostgreSQL, a second stack, `up.sh --apps` containers or an old dev server | `lsof -nP -iTCP:<port> -sTCP:LISTEN` (Windows: `netstat -ano \| findstr :<port>`) and stop it. Do not run `up.sh --apps` and the IDE apps together |
| Vite: *Port 5175 is already in use* | `strictPort: true` – another dev server still runs | Stop the other process; never change the port (the Keycloak redirect URIs are bound to it) |
| Apple silicon: *no matching manifest for linux/arm64* or very slow containers | An image without an arm64 variant, or emulation without Rosetta | Enable *Use Rosetta* (Docker Desktop) or `--vz-rosetta` (Colima); for a single image `DOCKER_DEFAULT_PLATFORM=linux/amd64 ./deploy/local/up.sh` |
| New roles or users missing, *Invalid parameter: redirect_uri*, OH workspace shows *Access restricted* | Keycloak imports the realm **only when it does not exist** (`--import-realm`); your realm predates the current `wellnessz-realm.json` | Keep app data: Keycloak admin console → realm *wellnessz* → *Delete realm*, then `docker compose -f deploy/local/docker-compose.yml restart keycloak` (re-imports; all demo passwords are temporary again). Or start clean: `./deploy/local/down.sh --purge && ./deploy/local/up.sh` and re-run `make-ide-env.sh` |
| Changes to a `db/demo/R__demo_*.sql` file have no effect | Repeatable migrations are re-run when their checksum changes, but the demo scripts insert fixed ids with `on conflict do nothing`, so existing rows are not updated | Purge and recreate the database (`./deploy/local/down.sh --purge`, `up.sh`, `make-ide-env.sh`, restart the core). Keep demo scripts idempotent |
| Core start fails: *Validate failed: Migrations have failed validation – checksum mismatch* | A **versioned** migration (`V*__*.sql`) that was already applied has been edited | Never edit applied `V` migrations – revert the change and add a new `V<next>__…sql` (V17 is the next free number at the time of writing). Locally only, after reverting is impossible: purge the database |
| `./mvnw verify` with `WZ_TEST_DB_URL` fails on the second run with `AEADBadTagException`, *Decryption failed* / *cannot decrypt*, or unexpected rows | Test keys are generated per test JVM; the database still holds data encrypted by the previous run | `docs/developer-setup/ide/scripts/recreate-test-db.sh` before every run, or use `verify-with-local-db.sh` (does it for you), or unset `WZ_TEST_DB_URL` to return to Testcontainers |
| Integration tests: *Could not find a valid Docker environment* | Testcontainers cannot see Docker (Colima, Rancher Desktop, WSL without integration) | `~/.testcontainers.properties` with `docker.host=…` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` (section 3.2); WSL: enable Docker Desktop's WSL integration |
| Core: *Master key is required for field-level encryption (WZ_MASTER_KEY or WZ_MASTER_KEY_FILE)* or *Property … is encrypted but no master key … is configured* | Run configuration started without the env file, or EnvFile plug-in disabled | Run `make-ide-env.sh`; check the run configuration's *EnvFile* tab is enabled and points at `deploy/local/core.local.env` |
| Core/BFF: decryption error for an `ENC(...)` value, or *Unable to read master key file* | Env files generated from an older `.env`, key directory moved, or a WSL path used by a Windows JVM | Re-run `make-ide-env.sh`; on Windows run the JVM inside WSL (WSL JDK) |
| BFF: *Unable to resolve Configuration with the provided Issuer of "http://localhost:8180/realms/wellnessz"* | Keycloak not (yet) running or not healthy | Start the stack and wait for `keycloak` to be healthy, then start the BFF |
| Login loops or *redirect_uri* error in the browser | App opened via `127.0.0.1` or another port; Codespaces browser URL | Use `http://localhost:5173`–`5176`; in Dev Containers keep `requireLocalPort` (section 4.4) |
| Requests fail with *Name for argument of type … not specified, and parameter name information not available via reflection* | Classes compiled by the IDE without `-parameters` | IntelliJ: reload the Maven project (*Settings → Build → Compiler → Java Compiler* must show `-parameters`); VS Code: `java.settings.url` → `java-settings.prefs`, then *Java: Clean Java Language Server Workspace*; Eclipse: *Store information about method parameters* |
| `./mvnw -pl apps/wz-core-app -am spring-boot:run` fails with *Unable to find a suitable main class* on `wellnessz-parent` | `-am` runs the goal on the parent POM and every library module | Use `run-service.sh`, the IDE configurations, or `./mvnw -q -DskipTests install` followed by `./mvnw -pl apps/wz-core-app spring-boot:run -Dspring-boot.run.profiles=local` |
| Empty work queues, referrals answer `404`, no demo claims/providers | Core started without the `local` profile, so `db/demo` was not loaded | The env files set `SPRING_PROFILES_ACTIVE=local`; check the start-up log line *The following 1 profile is active: "local"* |
| No traces in the collector log | `OTEL_EXPORTER_OTLP_ENDPOINT` set without the `/v1/traces` path | Unset it (default `http://localhost:4318/v1/traces`) or include the path |
| `npm ci` fails with *EBADENGINE* or odd syntax errors | Node older than 22.12 active | `nvm use 24` (or `brew link --overwrite --force node@24`); check `node --version` in the IDE's terminal and Node interpreter setting |
| ESLint in the IDE reports nothing or *Failed to load config* | ESLint extension started in the repository root | VS Code: `eslint.workingDirectories` (shipped settings); IntelliJ: *Automatic ESLint configuration*; run `npm ci` in the app first |
| Corporate proxy / TLS interception: `PKIX path building failed` (Maven), `SELF_SIGNED_CERT_IN_CHAIN` / `UNABLE_TO_GET_ISSUER_CERT_LOCALLY` (npm), image pulls fail | The proxy's root CA is unknown to the JDK, Node or Docker | JDK: `sudo keytool -importcert -cacerts -alias corp-proxy -file corp-ca.pem`; Maven proxy in `~/.m2/settings.xml` (`<proxies>`; the wrapper also honours `-Dhttps.proxyHost`/`-Dhttps.proxyPort` in `MAVEN_OPTS`); Node: `export NODE_EXTRA_CA_CERTS=/path/corp-ca.pem`; Docker Desktop trusts the OS store after a restart, Colima needs the CA inside the VM; web image builds accept `--secret id=npm_ca,src=/path/ca.pem` |
| `up.sh` stops with *required variable … is missing a value* | `.env` created by an older version lacks a new variable | Compare with `.env.example` and add it, or purge, delete `.env` and run `up.sh` again |
| Keycloak unhealthy, log shows *password authentication failed for user "keycloak"* | Old database volume with different passwords | `./deploy/local/down.sh --purge`, then `up.sh` |
| `e2e-increment3.js` / `e2e-increment4.js` fail on a second run (`409 claim_exists`, `409 export_exists`, calibration or advice checks) | The first run consumed the demo state | Purge and recreate the database and run the scripts 1 → 4 in order (section 5.6) |
| Partner portal: *Your account needs multi-factor sign-in* | Core started with `WZ_MFA_REQUIRED_ROLES` set | Remove it for local work (setup guide 1, section 6.1, explains the MFA rehearsal) |
| IDE search/indexing shows duplicate classes from `.claude/worktrees` | Local agent worktrees inside the checkout | Exclude the folder (VS Code settings shipped; IntelliJ *Mark Directory as → Excluded*) |

More symptoms specific to individual epics: setup guide 1, section 12.

---

## 8. Adding a module or a client-specific customisation

### 8.1 A new business module from the IDE

The architecture rules and the full checklist are in the **Developer Guide** (sections 2–13, worked examples in 14–17)
and the **CI/CD Guide** (section 10.1 for a Maven module, 10.3 for a web app). The IDE part:

1. Create a branch: `git switch -c feature/<ticket>-<slug>`.
2. **Create the module.** IntelliJ: *File → New → Module… → Maven*, parent `wellnessz-parent`, location
   `modules/wz-eNN-<name>`, artifactId `wz-eNN-<name>`; VS Code: create the folder and copy the `pom.xml` template from
   Developer Guide section 2.2; Eclipse: *New → Maven Module*. Use a package below `com.wellnessz` (component and entity
   scanning of `WellnessZCoreApplication` then picks it up).
3. Register it in the root `pom.xml` (`<modules>` **and** `<dependencyManagement>`) and as a dependency of
   `apps/wz-core-app/pom.xml`. Reload Maven (IntelliJ: Maven tool window; VS Code: *Java: Reload Projects*; Eclipse:
   *Maven → Update Project*). The existing run configurations need no change – the module is on the core's classpath.
4. Migrations in `apps/wz-core-app/src/main/resources/db/migration` (`V<next>__<name>.sql`, grants to `${app_role}`);
   demo data only in `db/demo/R__demo_*.sql` (idempotent). A new schema also goes into `spring.flyway.schemas`, the Helm
   migration settings, `deploy/local/postgres/01-init-wellnessz.sh`, `deploy/sql/bootstrap-managed-postgres.sql` and
   `deploy/hybrid/cnpg/03-cluster.yaml` (CI/CD Guide 10.1, step 3) – then purge your local database once.
5. Extend `ArchitectureTest` and add an `Increment…IntegrationTest` on `IntegrationTestBase` covering the eight mandatory
   cases (Developer Guide 9.2). Run *ArchitectureTest (ArchUnit)* and *mvn verify (core app + dependencies)*.
6. New roles: realm roles **and** the scope mappings of `wellnessz-bff` and `wellnessz-mobile` in
   `deploy/local/keycloak/wellnessz-realm.json`; re-import the realm locally (section 7).
7. Web screens in the right app (Developer Guide section 10); run `web-all.sh check`.
8. Run the gates in section 6.1, then open the pull request.

### 8.2 Client-specific customisation

Customer-specific configuration, branding, workflows and extensions are covered by the separate customisation guide in
**`docs/customisation/`** (written separately). From the IDE's point of view a customisation is developed exactly like a
module: same run configurations, same local stack, same gates. Keep client-specific values out of the product
defaults: technical settings belong in environment variables or Helm values, business configuration in the E18
configuration service (Developer Guide, section 11), secrets only as `ENC(...)`/`file:` references. At the time of
writing a hook for client-specific database migrations (a second Flyway pass with its own schema and history table,
configured through `WZ_FLYWAY_EXTRA_LOCATIONS`) is being added; the customisation guide will describe how to use it.
Pipeline changes for additional services or apps follow the CI/CD Guide, section 10.

---

## 9. Files in this package

| File | Purpose |
|---|---|
| `README.md` | This guide |
| `ide/scripts/install-ide-files.sh` | Copies the IDE files into `.run/`, `.vscode/`, `.devcontainer/`, the Git hook and `.nvmrc` – never overwrites |
| `ide/scripts/make-ide-env.sh` | Generates `deploy/local/core.local.env` / `bff.local.env` (secrets as `ENC(...)`) and the master key files in `~/.config/wellnessz` |
| `ide/scripts/run-service.sh` | Builds and starts core or BFF with the env file and a debug port (5005 / 5006) |
| `ide/scripts/recreate-test-db.sh` | Drops and recreates `wellnessz_test` in the compose PostgreSQL |
| `ide/scripts/verify-with-local-db.sh` | Recreates the test database and runs `./mvnw` with `WZ_TEST_DB_*` for that process only |
| `ide/scripts/web-all.sh` | `install`, `lint`, `test`, `build`, `audit` or `check` for all four web apps (as CI) |
| `ide/scripts/lib/common.sh` | Shared helpers (safe env-file parsing – env files are never `source`d) |
| `ide/intellij/run/*.run.xml` | 32 IntelliJ run configurations (backend, local stack, Maven, tests, web) |
| `ide/intellij/WellnessZ-code-style.xml` | IntelliJ code style scheme |
| `ide/vscode/extensions.json`, `launch.json`, `settings.json`, `tasks.json`, `java-settings.prefs` | VS Code workspace files |
| `ide/eclipse/WellnessZ-attach-core-5005.launch`, `WellnessZ-attach-BFF-5006.launch` | Eclipse remote-debug launches |
| `ide/devcontainer/devcontainer.json`, `Dockerfile`, `post-create.sh` | Dev Container / Codespaces definition |
| `ide/git-hooks/pre-commit` | Pre-commit hook (secret files, gitleaks, ESLint) |
| `ide/nvmrc` | Node version (`24`) for nvm/fnm, installed as `.nvmrc` |
