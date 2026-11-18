#!/bin/sh
set -eu
umask 077
src=$(CDPATH= cd -P "$(dirname "$0")/.." && pwd)
base=${JAGENT_INSTALL_DIR:-"$HOME/.local/share/jagent"}
state=${JAGENT_STATE_DIR:-"$HOME/.jagent"}
mkdir -p "$base/bin" "$base/releases" "$state"
slot=$(mktemp -d "$base/releases/app.XXXXXXXX")
trap 'rm -rf "$slot"' EXIT
if [ -f "$src/pom.xml" ]; then
  node "$src/scripts/package.mjs" --output "$slot/build"
  version=$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$src/pom.xml" | head -1)
  src="$slot/build/jagent-$version"
fi
if [ -f "$src/distribution.json" ]; then
  for part in server runner runners console scripts sources distribution.json .env.example; do cp -R "$src/$part" "$slot/"; done
  if [ ! -f "$state/.env.local" ]; then cp "$src/.env.example" "$state/.env.local"; fi
  echo "Configure the model in $state/.env.local and run jagent."
elif [ -f "$src/coding/runner.jar" ]; then
  if [ -f "$base/current" ] && [ -f "$base/releases/$(cat "$base/current")/distribution.json" ]; then
    echo 'The installed full application already includes the Runner. Update it with the full package.' >&2; exit 1
  fi
  mkdir "$slot/runner"
  cp "$src/coding/runner.jar" "$slot/runner/"
  echo 'Start with jagent runner --server <url> --credentials <file>.'
else
  echo 'Run the installer from the extracted JAgent application or Coding Runner ZIP.' >&2; exit 1
fi
cp "$src/scripts/jagent" "$base/bin/.jagent-$(basename "$slot")"
chmod 755 "$base/bin/.jagent-$(basename "$slot")"
mv -f "$base/bin/.jagent-$(basename "$slot")" "$base/bin/jagent"
printf '%s\n' "$(basename "$slot")" > "$slot/current"
if [ -d "$slot/build" ]; then rm -rf "$slot/build"; fi
# An empty record means PATH already worked or was managed by the user.
if [ ! -f "$base/current" ] && [ ! -e "$base/path-entries" ]; then : > "$base/path-entries"; fi
mv "$slot/current" "$base/current"
trap - EXIT
case ":$PATH:" in
  *":$base/bin:"*) ;;
  *)
    if [ -z "${JAGENT_INSTALL_DIR:-}" ]; then
      login_shell=${SHELL:-sh}
      case "${login_shell##*/}" in
        zsh) rc="${ZDOTDIR:-$HOME}/.zshrc";;
        bash) rc="$HOME/.bashrc"; if [ "$(uname -s)" = Darwin ]; then rc="$HOME/.bash_profile"; fi;;
        sh|dash|ksh) rc="$HOME/.profile";;
        *) rc="";;
      esac
      line='export PATH="$HOME/.local/share/jagent/bin:$PATH"'
      if [ -n "$rc" ]; then
        if ! grep -Fqx "$line" "$rc" 2>/dev/null; then
          printf '\n%s\n' "$line" >> "$rc"
          if [ -f "$base/path-entries" ]; then printf '%s\n' "$rc" >> "$base/path-entries"; fi
        fi
        echo "Open a new terminal to use jagent."
      fi
    fi
    echo "Command directory: $base/bin (add it to PATH for other shells).";;
esac
echo "Installed: $base/bin/jagent"
