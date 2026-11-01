#!/usr/bin/env bash
# Linux / other Unix (requires Java 21 on PATH). macOS users: use "Start IntiMed Demo.command".
PACK="$(cd "$(dirname "$0")" && pwd)"; export INTIMED_HOME="${INTIMED_HOME:-$HOME/IntiMed-Demo-Data}"
exec java -Xmx1g -jar "$PACK/app/intimed-platform.jar" --spring.profiles.active=demo
