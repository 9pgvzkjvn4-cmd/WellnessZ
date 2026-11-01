#!/usr/bin/env bash
# SC-03 / SC-04 – Hardened Linux gateway (CIS baseline, NIST CM-6 / SI-2, IEC 62443 SR 7.x) incl. the USB VID/PID allow-list.
# Run ON the gateway as a user that may read system state (no changes are made):
#   ./05_gateway_hardening_linux.sh --authorised --targets targets.json [--out <dir>]
# Checks: gateway service runs as a non-root account, management port bound to localhost, full-disk encryption, firewall with a
# default-drop input policy, EDR service active, time synchronised, USB allow-list udev policy present, SSH password logins
# disabled, automatic security updates enabled, no plaintext MLLP listener unless a documented exception.
set -u
AUTH=0; TARGETS=""; OUT="${WZ_PACK_OUT:-$PWD/device-security-evidence}"
while [ $# -gt 0 ]; do
  case "$1" in
    --authorised) AUTH=1 ;;
    --targets) TARGETS="$2"; shift ;;
    --out) OUT="$2"; shift ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
  esac
  shift
done
if [ "$AUTH" != 1 ] || [ -z "$TARGETS" ] || [ ! -f "$TARGETS" ]; then
  echo "Refusing to run: --authorised and --targets <file> are required (defensive checks of your own systems only)." >&2
  exit 2
fi
mkdir -p "$OUT"
SCRIPT=05_gateway_hardening_linux
cfg() { node -e "const c=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8'));const v=process.argv[2].split('.').reduce((o,k)=>o==null?undefined:o[k],c);if(v!==undefined)process.stdout.write(String(v))" "$TARGETS" "$1" 2>/dev/null; }
SERVICE="$(cfg gateway.serviceName)"; SERVICE="${SERVICE:-wellnessz-gateway}"   # apps/wz-device-gateway/service/linux/wellnessz-gateway.service
MGMT_PORT="$(cfg gateway.managementPort)"; MGMT_PORT="${MGMT_PORT:-8095}"   # gateway status page + actuator (server.port, WZ_GATEWAY_STATUS_PORT)
EDR="$(cfg gateway.edrService)"; EDR="${EDR:-falcon-sensor}"
ENVIRONMENT="$(cfg environment)"; ENVIRONMENT="${ENVIRONMENT:-unspecified}"
CHECKS=""
add() { # id outcome description detail
  local d; d=$(printf '%s' "$4" | tr -d '"\\' | tr '\n' ' ' | cut -c1-300)
  CHECKS="${CHECKS}${CHECKS:+,}{\"id\":\"$1\",\"description\":\"$3\",\"outcome\":\"$2\",\"detail\":\"$d\"}"
}
have() { command -v "$1" >/dev/null 2>&1; }
SYSTEMD=0; [ -d /run/systemd/system ] && SYSTEMD=1
STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)

if [ "$(uname -s)" != "Linux" ]; then
  add platform NOT_RUN "Linux gateway hardening" "not a Linux host"
else
  # service account
  if [ "$SYSTEMD" = 1 ] && systemctl list-unit-files "${SERVICE}.service" >/dev/null 2>&1; then
    U=$(systemctl show -p User --value "$SERVICE" 2>/dev/null)
    if [ -n "$U" ] && [ "$U" != "root" ]; then add service-account PASS "gateway service runs as a non-root account" "User=$U"
    else add service-account FAIL "gateway service runs as a non-root account" "User=${U:-root}"; fi
  else add service-account NOT_RUN "gateway service runs as a non-root account" "service $SERVICE not found"; fi
  # management port on localhost only
  if have ss; then
    L=$(ss -ltnH "( sport = :$MGMT_PORT )" 2>/dev/null | awk '{print $4}')
    if [ -z "$L" ]; then add mgmt-port NOT_RUN "management port bound to localhost" "port $MGMT_PORT not listening"
    elif echo "$L" | grep -qvE '^(127\.0\.0\.1|\[::1\]):'; then add mgmt-port FAIL "management port bound to localhost" "$L"
    else add mgmt-port PASS "management port bound to localhost" "$L"; fi
    if ss -ltnH 2>/dev/null | awk '{print $4}' | grep -qE ':2575$'; then
      if [ "$(cfg gateway.plaintextMllpException)" = "true" ]; then add plaintext-mllp PASS "no plaintext MLLP listener" "documented exception"
      else add plaintext-mllp FAIL "no plaintext MLLP listener (TLS listener on another port)" "listener on 2575 without exception"; fi
    else add plaintext-mllp PASS "no plaintext MLLP listener" "none on 2575"; fi
  fi
  # disk encryption
  if have lsblk; then
    if lsblk -rno TYPE 2>/dev/null | grep -q crypt; then add disk-encryption PASS "full-disk encryption (LUKS)" "crypt device present"
    else add disk-encryption FAIL "full-disk encryption (LUKS)" "no crypt device"; fi
  fi
  # firewall default drop
  if have nft && nft list ruleset 2>/dev/null | grep -qE 'hook input.*policy drop'; then add firewall PASS "host firewall, input policy drop" "nftables"
  elif have iptables && iptables -S INPUT 2>/dev/null | head -1 | grep -q -- '-P INPUT DROP'; then add firewall PASS "host firewall, input policy drop" "iptables"
  else add firewall FAIL "host firewall, input policy drop" "no default-drop input policy found (or insufficient rights)"; fi
  # EDR
  if [ "$SYSTEMD" != 1 ]; then add edr NOT_RUN "EDR agent active" "no systemd on this host – verify the EDR agent manually"
  elif systemctl is-active --quiet "$EDR" 2>/dev/null; then add edr PASS "EDR agent active" "$EDR"
  else add edr FAIL "EDR agent active" "$EDR not active"; fi
  # time sync
  if [ "$SYSTEMD" = 1 ] && have timedatectl; then
    S=$(timedatectl show -p NTPSynchronized --value 2>/dev/null)
    if [ "$S" = "yes" ]; then add time-sync PASS "time synchronised (NTP)" "NTPSynchronized=yes"
    elif [ -z "$S" ]; then add time-sync NOT_RUN "time synchronised (NTP)" "timedatectl gave no answer"
    else add time-sync FAIL "time synchronised (NTP)" "NTPSynchronized=$S"; fi
  elif have chronyc && chronyc tracking >/dev/null 2>&1; then
    chronyc tracking 2>/dev/null | grep -q 'Leap status *: Normal' && add time-sync PASS "time synchronised (NTP)" "chrony" || add time-sync FAIL "time synchronised (NTP)" "chrony not synchronised"
  else add time-sync NOT_RUN "time synchronised (NTP)" "no timedatectl / chrony"; fi
  # USB allow-list (udev: deny by default, authorise listed VID/PID)
  if ls /etc/udev/rules.d/*usb*allow* /etc/udev/rules.d/*wellnessz*usb* >/dev/null 2>&1 || grep -rqs 'authorized_default' /etc/udev/rules.d/; then
    add usb-allow-list PASS "USB VID/PID allow-list policy present" "udev rule found"
  else add usb-allow-list FAIL "USB VID/PID allow-list policy present" "no udev allow-list rule"; fi
  # SSH
  if have sshd; then
    P=$(sshd -T 2>/dev/null | awk '/^passwordauthentication/{print $2}')
    [ "$P" = "no" ] && add ssh-passwords PASS "SSH password logins disabled" "PasswordAuthentication no" || add ssh-passwords FAIL "SSH password logins disabled" "PasswordAuthentication ${P:-unknown}"
  fi
  # automatic security updates
  if [ -f /etc/apt/apt.conf.d/20auto-upgrades ] && grep -q 'Unattended-Upgrade "1"' /etc/apt/apt.conf.d/20auto-upgrades; then
    add auto-updates PASS "automatic security updates in the approved window" "unattended-upgrades on"
  else add auto-updates FAIL "automatic security updates in the approved window" "unattended-upgrades not enabled"; fi
fi

FINISHED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
OUTCOME=NOT_RUN
echo "$CHECKS" | grep -q '"outcome":"PASS"' && OUTCOME=PASS
echo "$CHECKS" | grep -q '"outcome":"FAIL"' && OUTCOME=FAIL
FILE="$OUT/$SCRIPT.json"
printf '{"script":"%s","controls":["SC-03","SC-04"],"packVersion":"1.0.0","environment":"%s","startedAt":"%s","finishedAt":"%s","outcome":"%s","checks":[%s]}\n' \
  "$SCRIPT" "$ENVIRONMENT" "$STARTED" "$FINISHED" "$OUTCOME" "$CHECKS" > "$FILE"
SHA=$(sha256sum "$FILE" | awk '{print $1}')
echo "$SHA  $SCRIPT.json" > "$FILE.sha256"
echo "$SCRIPT SC-03,SC-04 $OUTCOME evidence=$FILE sha256=$SHA"
[ "$OUTCOME" = FAIL ] && exit 1 || exit 0
