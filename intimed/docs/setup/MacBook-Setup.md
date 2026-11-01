# MacBook setup (developer kit)

Requirements: macOS 13+, Apple silicon or Intel, 16 GB RAM (32 GB recommended), JDK 21 (`brew install --cask temurin@21`).
Optional: Colima or Docker Desktop for the compose setup, `openssl` (pre-installed) for the security kit.

1. Unzip the code base, `cd intimed`.
2. `./deploy/macos/bootstrap.sh --demo` – builds and tests (51 tests), stores the master key in the login Keychain, offers to
   trust the local root CA, starts the platform with demo data. Without `--demo` a single tenant and a random admin password
   (`$INTIMED_HOME/initial-admin.txt`, includes the TOTP secret) are created.
3. Open https://localhost:9443.
4. Optional containers: `cd deploy/local && cp .env.example .env` (fill values), `docker compose up --build`.
5. Security check: `INTIMED_HOME=~/IntiMed-Dev-Data ./security-test-kit/run-kit.sh`.

IDE: import `intimed/pom.xml` as a Maven project (IntelliJ IDEA / VS Code Java). Run `io.intimed.IntiMedApplication`
with `INTIMED_HOME` set; the first start creates PKI and keys.
