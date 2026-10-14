#!/usr/bin/env bash
# Runs the standalone harness for one or both ruled pin sets and checks what it proves.
#
#   kmp-conventions/harness/run.sh [non-ios|ios|both] [--unguarded-probe] [--probe-compile]
#
# For each pin set it runs (unpiped, to a log; the exit code is checked, not tail's):
#   :inline:jvmTest :printPins
# and then asserts:
#   1. Gradle exit code 0 (configuration on THIS host never failed, with Apple targets guarded);
#   2. the log does not contain "Disabled Kotlin/Native Targets" (the guard really kept Apple
#      targets out of a non-mac configuration);
#   3. the Kotlin plugin that actually resolved equals the catalog's `kotlin` (no silent drift);
#   4. :inline:jvmTest ran (the Compose compiler plugin matched Kotlin and the test passed).
# With --unguarded-probe it also configures :unguarded (Apple targets declared with no guard) and
# PRINTS what the host did, without failing the run: the result is information, not a gate.
# With --probe-compile (implies --unguarded-probe) it also runs `:unguarded:compileKotlinIosArm64` and
# prints whether the host SKIPPED it (Kotlin 2.1.20 on Linux: disabled target) or really compiled it
# (Kotlin 2.2.21 on Linux, observed once: a klib, no Xcode needed). That downloads the Kotlin/Native
# distribution for the pin set (~0.8 GB per Kotlin version) the first time.
#
# Needs network (Gradle plugin portal, Maven Central, Google, foojay for the JDK 17 toolchain).
# On a mac host the guarded targets ARE declared and this script's check 2 is skipped.
# Evidence class: CI (hosted VM) / CONTAINER-BUILD-ONLY. It proves nothing about iOS itself.
set -u

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
which_sets="${1:-both}"
probe=0
probe_compile=0
for a in "$@"; do
  [ "$a" = "--unguarded-probe" ] && probe=1
  [ "$a" = "--probe-compile" ] && { probe=1; probe_compile=1; }
done
case "$which_sets" in non-ios|ios|both|--unguarded-probe|--probe-compile) ;; *) echo "usage: run.sh [non-ios|ios|both] [--unguarded-probe] [--probe-compile]" >&2; exit 2 ;; esac
case "$which_sets" in --unguarded-probe|--probe-compile) which_sets=both ;; esac

export LANG=C.UTF-8 LC_ALL=C.UTF-8
is_mac=0; [ "$(uname -s)" = "Darwin" ] && is_mac=1

catalog_value() { # file key -> value   (a `key = "value"` line under [versions])
  sed -n "s/^$2 = \"\(.*\)\"\$/\1/p" "$1" | head -1
}

failures=0
run_set() {
  local set="$1" cat="$root/kmp-conventions/catalogs/kmp-$1.versions.toml" log code want
  log="$(mktemp)"
  want="$(catalog_value "$cat" kotlin)"
  echo "== pin set: $set (catalog kotlin=$want, compose=$(catalog_value "$cat" compose-multiplatform))"
  "$root/gradlew" -p "$here" --no-daemon --console=plain -PpinSet="$set" \
      :inline:jvmTest :printPins > "$log" 2>&1
  code=$?
  if [ "$code" -ne 0 ]; then
    echo "FAIL  gradle exit $code (log: $log)"; tail -25 "$log" | sed 's/^/      /'; failures=$((failures + 1)); return
  fi
  echo "ok    gradle exit 0"
  if [ "$is_mac" -eq 0 ]; then
    if grep -q "Disabled Kotlin/Native Targets" "$log"; then
      echo "FAIL  the guard let Apple targets reach a non-mac configuration"; failures=$((failures + 1))
    else
      echo "ok    no 'Disabled Kotlin/Native Targets' warning (Apple targets were not declared on this host)"
    fi
  fi
  got="$(sed -n 's/^PIN_RESOLVED_KOTLIN_PLUGIN=//p' "$log" | head -1)"
  if [ "$got" = "$want" ]; then echo "ok    resolved Kotlin plugin $got == catalog"; else echo "FAIL  resolved Kotlin plugin '$got' != catalog '$want'"; failures=$((failures + 1)); fi
  for t in :inline:jvmTest; do
    if grep -q "Task $t\$" "$log" || grep -q "Task $t UP-TO-DATE" "$log"; then echo "ok    $t ran"; else echo "FAIL  $t did not run"; failures=$((failures + 1)); fi
  done
  if [ "$probe" -eq 1 ]; then
    echo "-- unguarded probe ($set): declaring iOS targets with no guard"
    "$root/gradlew" -p "$here" --no-daemon --console=plain -PpinSet="$set" -PunguardedProbe=true :unguarded:jvmTest > "$log.probe" 2>&1
    echo "   gradle exit $? ; 'Disabled Kotlin/Native Targets' warning: $(grep -c 'Disabled Kotlin/Native Targets' "$log.probe")"
    grep -E "^(e|w): |Disabled Kotlin|not supported|cannot be built" "$log.probe" | head -8 | sed 's/^/   /'
    if [ "$probe_compile" -eq 1 ]; then
      "$root/gradlew" -p "$here" --no-daemon --console=plain -PpinSet="$set" -PunguardedProbe=true :unguarded:compileKotlinIosArm64 > "$log.compile" 2>&1
      echo "   compileKotlinIosArm64: gradle exit $? ; $(grep -E 'Task :unguarded:compileKotlinIosArm64' "$log.compile" | tail -1 | sed 's/^> //')"
    fi
  fi
  rm -f "$log" "$log.probe" "$log.compile"
}

case "$which_sets" in
  non-ios) run_set non-ios ;;
  ios)     run_set ios ;;
  both)    run_set non-ios; run_set ios ;;
esac

echo
if [ "$failures" -gt 0 ]; then echo "harness: $failures check(s) failed" >&2; exit 1; fi
echo "harness: all checks passed on this host ($(uname -s) $(uname -m))"
