#!/bin/bash
cd "$(dirname "$0")/.." || exit 1
node "scripts/start-server.mjs" "$@"
status=$?
if [ "$status" -ne 0 ]; then
  printf '\nStartup failed. Press Enter to close.'
  read -r _
fi
exit "$status"
