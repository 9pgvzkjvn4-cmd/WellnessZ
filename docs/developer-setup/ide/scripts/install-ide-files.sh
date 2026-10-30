#!/usr/bin/env bash
# =============================================================================
# Copy the ready-made IDE files from docs/developer-setup/ide into the places
# where the IDEs look for them. Existing files are NEVER overwritten.
#
#   docs/developer-setup/ide/scripts/install-ide-files.sh [intellij] [vscode] [devcontainer] [hook] [nvmrc]
#   docs/developer-setup/ide/scripts/install-ide-files.sh all
#
#   intellij      -> .run/*.run.xml                (shared run configurations)
#   vscode        -> .vscode/*.json, java-settings.prefs  (.vscode/ is git-ignored)
#   devcontainer  -> .devcontainer/                (Dev Containers / Codespaces)
#   hook          -> .git/hooks/pre-commit         (local only)
#   nvmrc         -> .nvmrc                        (Node 24, as in CI)
#
# .run/ and .devcontainer/ are not git-ignored: agree with the team before
# committing them (see docs/developer-setup/README.md, section 4).
# =============================================================================
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

IDE_DIR="$WZ_ROOT/docs/developer-setup/ide"

copy_new() { # src dst
  if [[ -e $2 ]]; then
    echo "  skip  ${2#"$WZ_ROOT"/} (exists)"
  else
    mkdir -p "$(dirname "$2")"
    cp "$1" "$2"
    echo "  added ${2#"$WZ_ROOT"/}"
  fi
}

do_intellij() {
  local f
  for f in "$IDE_DIR"/intellij/run/*.run.xml; do copy_new "$f" "$WZ_ROOT/.run/$(basename "$f")"; done
}
do_vscode() {
  local f
  for f in extensions.json launch.json settings.json tasks.json java-settings.prefs; do
    copy_new "$IDE_DIR/vscode/$f" "$WZ_ROOT/.vscode/$f"
  done
}
do_devcontainer() {
  local f
  for f in devcontainer.json Dockerfile post-create.sh; do
    copy_new "$IDE_DIR/devcontainer/$f" "$WZ_ROOT/.devcontainer/$f"
  done
  chmod +x "$WZ_ROOT/.devcontainer/post-create.sh" 2>/dev/null || true
}
do_hook() {
  local hooks
  hooks="$(git -C "$WZ_ROOT" rev-parse --git-path hooks)"
  [[ $hooks == /* ]] || hooks="$WZ_ROOT/$hooks"
  copy_new "$IDE_DIR/git-hooks/pre-commit" "$hooks/pre-commit"
  chmod +x "$hooks/pre-commit"
}
do_nvmrc() {
  copy_new "$IDE_DIR/nvmrc" "$WZ_ROOT/.nvmrc"
}

[[ $# -gt 0 ]] || set -- all
for target in "$@"; do
  case "$target" in
    all) do_intellij; do_vscode; do_devcontainer; do_hook; do_nvmrc ;;
    intellij) do_intellij ;;
    vscode) do_vscode ;;
    devcontainer) do_devcontainer ;;
    hook) do_hook ;;
    nvmrc) do_nvmrc ;;
    *) die "unknown target '$target' (intellij|vscode|devcontainer|hook|nvmrc|all)" ;;
  esac
done
