#!/bin/bash
H="${INTIMED_HOME:-$HOME/IntiMed-Demo-Data}"
if [ -f "$H/intimed-demo.pid" ] && kill "$(cat "$H/intimed-demo.pid")" 2>/dev/null; then echo "IntiMed demo stopped."; rm -f "$H/intimed-demo.pid"; else echo "IntiMed demo is not running."; fi
sleep 2
