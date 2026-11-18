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
plain='export PATH="$HOME/.local/share/jagent/bin:$PATH"'
line=$plain
add_path() {
  rc=$1
  if ! grep -Fqx -e "$plain" -e "$line" "$rc" 2>/dev/null; then
    printf '\n%s\n' "$line" >> "$rc"
    if [ -f "$base/path-entries" ]; then printf '%s\n' "$rc" >> "$base/path-entries"; fi
  fi
}
case ":$PATH:" in
  *":$base/bin:"*) ;;
  *)
    if [ -z "${JAGENT_INSTALL_DIR:-}" ]; then
      login_shell=${SHELL:-sh}
      case "${login_shell##*/}" in
        zsh) add_path "${ZDOTDIR:-$HOME}/.zshrc" ;;
        bash)
          if [ "$(uname -s)" = Darwin ]; then
            add_path "$HOME/.bash_profile"
          else
            # A Linux Bash login may source .bashrc, or skip it entirely.
            line='case ":$PATH:" in *":$HOME/.local/share/jagent/bin:"*) ;; *) export PATH="$HOME/.local/share/jagent/bin:$PATH" ;; esac'
            add_path "$HOME/.bashrc"
            for rc in "$HOME/.bash_profile" "$HOME/.bash_login" "$HOME/.profile"; do
              if [ -f "$rc" ] || [ "$rc" = "$HOME/.profile" ]; then add_path "$rc"; break; fi
            done
          fi ;;
        sh|dash|ksh) add_path "$HOME/.profile" ;;
      esac
    fi
    echo "Open a new terminal, or add $base/bin to PATH for your shell." ;;
esac
echo "Installed: $base/bin/jagent"
echo "For SSH automation or systemd, use this path and set PATH for Java (and Node.js for the server)."
