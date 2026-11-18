#!/bin/sh
set -eu

base=${JAGENT_INSTALL_DIR:-"$HOME/.local/share/jagent"}
if [ ! -e "$base/current" ]; then
  echo "No global JAgent installation at $base."
  exit 0
fi
for path in "$base" "$base/bin" "$base/releases" "$base/current" "$base/path-entries"; do
  if [ -L "$path" ]; then echo "Unexpected symbolic link: $path" >&2; exit 1; fi
done
release=$(cat "$base/current")
if ! printf '%s\n' "$release" | grep -Eq '^app\.[a-zA-Z0-9]{8}$'; then
  echo "Unrecognized JAgent installation at $base." >&2; exit 1
fi

# Older installers added this exact line before path-entries was recorded.
line='export PATH="$HOME/.local/share/jagent/bin:$PATH"'
remove_path() {
  rc=$1
  if [ -f "$rc" ] && grep -Fqx "$line" "$rc"; then
    temp=$(mktemp "$base/.uninstall.XXXXXXXX")
    trap 'rm -f "$temp"' EXIT
    awk -v entry="$line" '$0 != entry' "$rc" > "$temp"
    cat "$temp" > "$rc"
    rm -f "$temp"
    trap - EXIT
  fi
}

if [ "$base" = "$HOME/.local/share/jagent" ]; then
  if [ -f "$base/path-entries" ]; then
    while IFS= read -r rc; do remove_path "$rc"; done < "$base/path-entries"
  else
    for rc in "${ZDOTDIR:-$HOME}/.zshrc" "$HOME/.bashrc" "$HOME/.bash_profile" "$HOME/.profile"; do remove_path "$rc"; done
  fi
fi

for slot in "$base"/releases/app.????????; do
  [ -d "$slot" ] && [ ! -L "$slot" ] && [ -f "$slot/runner/runner.jar" ] || continue
  for part in runner server runners console scripts sources distribution.json .env.example; do
    rm -rf "$slot/$part"
  done
  rmdir "$slot" 2>/dev/null || echo "Retained additional files in $slot."
done
rm -f "$base/bin/jagent" "$base/path-entries" "$base/current"
for dir in "$base/bin" "$base/releases" "$base"; do rmdir "$dir" 2>/dev/null || :; done
echo 'JAgent uninstalled. Your settings, credentials and workspaces are retained. Open a new terminal.'
