#!/usr/bin/env bash
# Fail if any tracked path cannot be checked out on Windows: a character NTFS forbids
# (: < > | ? * "), a reserved device name as any path component (with or without an
# extension), a component ending in a dot or space, or a backslash.
#
# Usage: scripts/windows-path-lint.sh [repo-dir]   (default: the repo containing this script)
set -euo pipefail

repo="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$repo"

bad=0
while IFS= read -r -d '' path; do
  reason=""
  if [[ "$path" =~ [\:\<\>\|\?\*\"\\] ]]; then
    reason='forbidden character'
  else
    IFS='/' read -r -a parts <<<"$path"
    for part in "${parts[@]}"; do
      stem="${part%%.*}"
      upper="$(printf '%s' "$stem" | tr '[:lower:]' '[:upper:]')"
      case "$upper" in
        CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]) reason="reserved device name '$part'"; break ;;
      esac
      if [[ "$part" == *. || "$part" == *' ' ]]; then
        reason="component ends in a dot or space '$part'"; break
      fi
    done
  fi
  if [[ -n "$reason" ]]; then
    printf 'windows-path-lint: %s: %s\n' "$reason" "$path" >&2
    bad=1
  fi
done < <(git ls-files -z)

if [[ "$bad" -ne 0 ]]; then
  exit 1
fi
echo "windows-path-lint: ok"
