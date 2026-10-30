#!/usr/bin/env bash
# Runs once after the dev container is created (postCreateCommand).
# Keeps it light: fixes the Maven cache volume ownership, checks the toolchain and
# installs the web dependencies exactly as CI does. The Maven build itself is left
# to the developer (./mvnw verify) because it needs Docker for Testcontainers.
set -euo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

# The named volume for ~/.m2 is created root-owned
sudo chown -R "$(id -u):$(id -g)" "$HOME/.m2" 2>/dev/null || true

echo "[devcontainer] Toolchain"
java -version 2>&1 | head -1
./mvnw -v | head -1
node --version
npm --version
psql --version
docker --version || true

for app in employee-app manager-workspace oh-workspace partner-portal; do
  echo "[devcontainer] npm ci web/$app"
  (cd "web/$app" && npm ci --ignore-scripts --no-audit --no-fund)
done

if [[ ${WZ_DEVCONTAINER_PLAYWRIGHT:-0} == 1 ]]; then
  echo "[devcontainer] Playwright + Chromium for tools/e2e"
  npm install --no-save playwright@1
  npx playwright install --with-deps chromium
fi

cat <<'EOF'

[devcontainer] Ready. Next steps (docs/developer-setup/README.md, section 3):
  ./deploy/local/up.sh                                   # local stack (Docker in the container)
  docs/developer-setup/ide/scripts/make-ide-env.sh       # IDE env files for core and BFF
  ./mvnw -B verify                                       # first build
EOF
