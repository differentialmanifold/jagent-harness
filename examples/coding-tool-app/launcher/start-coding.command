#!/bin/bash

set -u

LAUNCHER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Finder-launched .command files may not inherit Homebrew or nvm paths.
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
if ! command -v node >/dev/null 2>&1; then
  NVM_DIR="${NVM_DIR:-$HOME/.nvm}"
  if [[ -s "$NVM_DIR/nvm.sh" ]]; then
    set +u
    # shellcheck disable=SC1090
    . "$NVM_DIR/nvm.sh"
    nvm use --silent default >/dev/null 2>&1 || nvm use --silent node >/dev/null 2>&1 || true
    set -u
  fi
fi

if ! command -v node >/dev/null 2>&1; then
  printf '\nError: Node.js ^20.19.0 or >=22.12.0 is required.\n' >&2
  if [[ -t 0 ]]; then
    printf '\nPress Enter to close...'
    read -r _
  fi
  exit 1
fi

node "$LAUNCHER_DIR/start-coding.mjs"
EXIT_CODE=$?

if [[ "$EXIT_CODE" -ne 0 && "$EXIT_CODE" -ne 129 && "$EXIT_CODE" -ne 130 && "$EXIT_CODE" -ne 143 && -t 0 ]]; then
  printf '\nPress Enter to close...'
  read -r _
fi

exit "$EXIT_CODE"
