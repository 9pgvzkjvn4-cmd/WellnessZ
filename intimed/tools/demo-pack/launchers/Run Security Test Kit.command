#!/bin/bash
PACK="$(cd "$(dirname "$0")" && pwd)"
export INTIMED_HOME="${INTIMED_HOME:-$HOME/IntiMed-Demo-Data}"
cd "$PACK/security-test-kit" && ./run-kit.sh
echo; echo "Report: $PACK/security-test-kit/results/REPORT.md"; open "$PACK/security-test-kit/results/REPORT.md" 2>/dev/null
read -r -p "Press Enter to close"
