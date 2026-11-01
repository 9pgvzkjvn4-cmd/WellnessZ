#!/bin/bash
H="${INTIMED_HOME:-$HOME/IntiMed-Demo-Data}"
read -r -p "Delete all IntiMed demo data in $H (database, PKI, evidence)? [y/N] " a
if [ "$a" = "y" ]; then rm -rf "$H"; security delete-generic-password -s intimed-demo -a master-key >/dev/null 2>&1; echo "Demo data and Keychain master key removed."; fi
sleep 2
