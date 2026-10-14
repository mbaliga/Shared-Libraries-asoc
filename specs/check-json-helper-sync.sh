#!/usr/bin/env bash
# The three SpecJson.kt test helpers (cell-shell, crash-recovery, feedback) are deliberate
# copies: Phase 0 may not touch module build files, so there is no shared test dependency to put
# one helper in. This check keeps them from drifting: they must be byte-identical once the
# `package` line is normalised. PORTING_PLAN.md S0.2.
#
# Usage:  specs/check-json-helper-sync.sh [--root DIR] [--self-test]
# Exit:   0 in sync, 1 drifted or missing, 2 usage.
set -u

root=""
self_test=0
while [ $# -gt 0 ]; do
  case "$1" in
    --root)      root="${2:?--root needs a directory}"; shift 2 ;;
    --self-test) self_test=1; shift ;;
    *)           echo "check-json-helper-sync: unknown argument: $1" >&2; exit 2 ;;
  esac
done

rel_paths="
cell-shell/src/test/kotlin/dev/aarso/cellshell/SpecJson.kt
crash-recovery/src/test/java/dev/aarso/crashrecovery/SpecJson.kt
feedback/src/test/kotlin/dev/aarso/feedback/SpecJson.kt
"

normalised() { sed '1s/^package .*$/package NORMALISED/' "$1"; }

check() { # root -> 0/1
  local dir="$1" first="" status=0 ref
  ref="$(mktemp)"
  for rel in $rel_paths; do
    if [ ! -f "$dir/$rel" ]; then
      echo "MISSING $rel"; status=1; continue
    fi
    if [ -z "$first" ]; then
      first="$rel"; normalised "$dir/$rel" > "$ref"
    elif ! normalised "$dir/$rel" | cmp -s - "$ref"; then
      echo "DRIFT   $rel differs from $first"; status=1
    fi
  done
  rm -f "$ref"
  return $status
}

if [ "$self_test" -eq 1 ]; then
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
  fails=0
  for rel in $rel_paths; do mkdir -p "$tmp/$(dirname "$rel")"; done
  n=0
  for rel in $rel_paths; do
    n=$((n + 1))
    printf 'package p%s\n\nobject SpecJson { val x = 1 }\n' "$n" > "$tmp/$rel"
  done
  if check "$tmp" >/dev/null; then echo "ok   - identical copies pass"; else echo "FAIL - identical copies rejected"; fails=$((fails + 1)); fi
  second="$(echo $rel_paths | cut -d' ' -f2)"
  printf 'package p2\n\nobject SpecJson { val x = 2 }\n' > "$tmp/$second"
  if check "$tmp" | grep -q '^DRIFT'; then echo "ok   - a changed copy is reported as drift"; else echo "FAIL - drift not detected"; fails=$((fails + 1)); fi
  rm -f "$tmp/$second"
  if check "$tmp" | grep -q '^MISSING'; then echo "ok   - a missing copy is reported"; else echo "FAIL - missing copy not detected"; fails=$((fails + 1)); fi
  [ "$fails" -eq 0 ] || { echo "check-json-helper-sync self-test: $fails failure(s)" >&2; exit 1; }
  echo "check-json-helper-sync self-test: all expectations held"
  exit 0
fi

[ -n "$root" ] || root="$(cd "$(dirname "$0")/.." && pwd)"
if check "$root"; then
  echo "check-json-helper-sync: OK (3 copies identical modulo the package line)"
else
  echo "check-json-helper-sync: the SpecJson.kt copies have drifted; make them identical again" >&2
  exit 1
fi
