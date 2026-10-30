# shellcheck shell=bash
# Shared helpers for the developer-setup scripts. Source it, do not execute it.
#
# Env files are parsed line by line (KEY=VALUE, '#' comments) instead of being
# sourced, so values such as ENC(...) need no shell quoting and nothing in the
# file is ever executed.

# Repository root: docs/developer-setup/ide/scripts/lib -> five levels up
WZ_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../../.." && pwd)"
export WZ_ROOT

WZ_WEB_APPS=(employee-app manager-workspace oh-workspace partner-portal)

die() {
  echo "[wz] $*" >&2
  exit 1
}

info() {
  echo "[wz] $*"
}

# env_value FILE KEY - print the value of KEY from FILE (exit 1 if absent)
env_value() {
  local file=$1 key=$2 line
  while IFS= read -r line || [[ -n $line ]]; do
    if [[ $line == "$key="* ]]; then
      printf '%s' "${line#*=}"
      return 0
    fi
  done <"$file"
  return 1
}

# export_env_file FILE - export every KEY=VALUE of FILE into the current shell
export_env_file() {
  local file=$1 line key
  [[ -f $file ]] || die "Env file not found: $file"
  while IFS= read -r line || [[ -n $line ]]; do
    [[ -z $line || $line == \#* ]] && continue
    key=${line%%=*}
    [[ $key =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    export "$key=${line#*=}"
  done <"$file"
}

require_dotenv() {
  WZ_DOTENV="$WZ_ROOT/deploy/local/.env"
  [[ -f $WZ_DOTENV ]] || die "deploy/local/.env not found - run ./deploy/local/up.sh first (it generates the file)"
}
