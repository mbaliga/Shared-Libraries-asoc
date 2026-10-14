#!/usr/bin/env bash
# Self-test for build-runtime.sh that needs NO network and no aarch64 JDK. It checks the recipe's
# plumbing offline:
#   - argument validation and --dry-run (the exact jlink command is what the recipe promises);
#   - the archive gate: a wrong SHA-256, a missing jmods dir, and a non-AArch64 archive are all refused;
#   - the happy path end to end with a FAKE host jlink and a FAKE aarch64 "JDK" (tiny hand-built ELF
#     headers): it asserts jlink receives the promised arguments, the result is verified, and
#     RUNTIME_MANIFEST.txt is written;
#   - a runtime of the wrong architecture is refused after the link step;
#   - the GLIBC-ceiling scan against a REAL ELF set (the host JDK, when there is one).
# What this does NOT prove: that a real Temurin aarch64 JDK links and that the result runs. A run of
# build-runtime.sh against the real archive is the proof of linking; S-UT1 on a device is the proof of running.
set -u

here="$(cd "$(dirname "$0")" && pwd)"
recipe="$here/build-runtime.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
failures=0
ok()  { printf 'ok   - %s\n' "$1"; }
bad() { printf 'FAIL - %s\n' "$1"; failures=$((failures + 1)); }

# expect LABEL WANT_EXIT NEEDLE -- command...
expect() {
  local label="$1" want="$2" needle="$3"; shift 4
  local out code
  out="$("$@" 2>&1)"; code=$?
  if [ "$code" -ne "$want" ]; then bad "$label (exit $code, wanted $want)"; printf '%s\n' "$out" | sed 's/^/       /' | head -15; return; fi
  if [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF -- "$needle"; then bad "$label (output lacks: $needle)"; printf '%s\n' "$out" | sed 's/^/       /' | head -15; return; fi
  ok "$label"
}

# --- arguments -----------------------------------------------------------------------------------
expect "--help shows the usage" 0 "build-runtime.sh - produce an aarch64 Temurin 21 jlink runtime" -- bash "$recipe" --help
expect "an unknown flag is a usage error" 2 "unknown argument" -- bash "$recipe" --nope
expect "--jdk-archive without --sha256 is refused" 2 "requires --sha256" -- bash "$recipe" --jdk-archive /x.tar.gz
expect "--sha256 without --jdk-archive is refused" 2 "only makes sense" -- bash "$recipe" --sha256 abc
expect "a malformed --modules is refused" 2 "comma-separated list" -- bash "$recipe" --modules 'java.base;rm'
expect "a malformed --max-glibc is refused" 2 "must look like 2.17" -- bash "$recipe" --max-glibc 2
expect "a malformed --expect-arch is refused" 2 "aarch64, x86-64 or any" -- bash "$recipe" --verify /x --expect-arch sparc
expect "a malformed --jdk-version is refused" 2 "must be a number" -- bash "$recipe" --jdk-version twenty-one

# --- dry run -------------------------------------------------------------------------------------
dry="$(bash "$recipe" --dry-run --out "$tmp/never" --modules java.base,java.logging 2>&1)"; code=$?
[ "$code" -eq 0 ] && ok "--dry-run exits 0" || bad "--dry-run exit $code"
for needle in "--add-modules java.base,java.logging" "--compress=zip-6" "--strip-java-debug-attributes" "--no-header-files" "--no-man-pages" "linux/aarch64 Temurin 21" "GLIBC_2.17" "nothing downloaded or executed"; do
  printf '%s' "$dry" | grep -qF -- "$needle" && ok "--dry-run states: $needle" || bad "--dry-run lacks: $needle"
done
printf '%s' "$dry" | grep -qF -- "--strip-debug" && bad "--dry-run still uses --strip-debug (host objcopy cannot strip AArch64)" || ok "--dry-run avoids --strip-debug and the objcopy-based native stripper"
printf '%s' "$dry" | grep -qF -- "strip-native" && bad "--dry-run uses the native stripper" || true
[ ! -e "$tmp/never" ] && ok "--dry-run creates nothing" || bad "--dry-run created the output directory"

# --- fakes -----------------------------------------------------------------------------------------
mk_elf() { # file machine_hex   (a bare 64-byte ELF64 header: enough for readelf -h)
  python3 -I - "$1" "$2" <<'PY'
import struct, sys
path, machine = sys.argv[1], int(sys.argv[2], 16)
ident = b"\x7fELF" + bytes([2, 1, 1, 0]) + bytes(8)
hdr = ident + struct.pack("<HHIQQQIHHHHHH", 2, machine, 1, 0, 0, 0, 0, 64, 56, 0, 64, 0, 0)
assert len(hdr) == 64
open(path, "wb").write(hdr)
PY
}
AARCH64=b7
X8664=3e

fake_jdk() { # dir machine -> tar.gz path on stdout
  local d="$tmp/jdk-$2" t="$tmp/jdk-$2.tar.gz"
  mkdir -p "$d/fakejdk/bin" "$d/fakejdk/jmods"
  mk_elf "$d/fakejdk/bin/java" "$2"; chmod +x "$d/fakejdk/bin/java"
  printf 'x' > "$d/fakejdk/jmods/java.base.jmod"
  tar -czf "$t" -C "$d" fakejdk
  printf '%s' "$t"
}
sha_of() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

# a fake host jlink: reports version 21.0.1, logs its arguments, and writes a "runtime" whose
# bin/java has the machine type named in $FAKE_RUNTIME_MACHINE
cat > "$tmp/jlink" <<'FAKE'
#!/usr/bin/env bash
if [ "${1:-}" = "--version" ]; then echo "21.0.1"; exit 0; fi
echo "$@" > "$FAKE_JLINK_LOG"
out=""; while [ $# -gt 0 ]; do [ "$1" = "--output" ] && out="$2"; shift; done
mkdir -p "$out/bin" "$out/lib"
cp "$FAKE_ELF_DIR/runtime-$FAKE_RUNTIME_MACHINE" "$out/bin/java"; chmod +x "$out/bin/java"
cp "$FAKE_ELF_DIR/runtime-$FAKE_RUNTIME_MACHINE" "$out/lib/libfake.so"
FAKE
chmod +x "$tmp/jlink"
mkdir -p "$tmp/elf"; mk_elf "$tmp/elf/runtime-b7" "$AARCH64"; mk_elf "$tmp/elf/runtime-3e" "$X8664"

common=(--host-jlink "$tmp/jlink" --jdk-version 21 --cache "$tmp/cache")
export FAKE_JLINK_LOG="$tmp/jlink.args" FAKE_ELF_DIR="$tmp/elf"

# --- the archive gate ------------------------------------------------------------------------------
good_tar="$(fake_jdk x $AARCH64)"; good_sha="$(sha_of "$good_tar")"
export FAKE_RUNTIME_MACHINE=b7
expect "a wrong SHA-256 is refused" 1 "SHA-256 mismatch" -- bash "$recipe" "${common[@]}" --out "$tmp/o1" --jdk-archive "$good_tar" --sha256 0000000000000000000000000000000000000000000000000000000000000000
[ ! -e "$tmp/o1" ] && ok "nothing is written after a checksum failure" || bad "output exists after a checksum failure"

nojmods="$tmp/nojmods"; mkdir -p "$nojmods/fakejdk/bin"; mk_elf "$nojmods/fakejdk/bin/java" "$AARCH64"; tar -czf "$tmp/nojmods.tar.gz" -C "$nojmods" fakejdk
expect "an archive with no jmods (a JRE) is refused" 1 "no jmods/java.base.jmod" -- bash "$recipe" "${common[@]}" --out "$tmp/o2" --jdk-archive "$tmp/nojmods.tar.gz" --sha256 "$(sha_of "$tmp/nojmods.tar.gz")"

wrong_tar="$(fake_jdk y $X8664)"
expect "a non-AArch64 archive is refused" 1 "not AArch64" -- bash "$recipe" "${common[@]}" --out "$tmp/o3" --jdk-archive "$wrong_tar" --sha256 "$(sha_of "$wrong_tar")"

printf '#!/bin/sh\necho 17.0.9\n' > "$tmp/jlink17"; chmod +x "$tmp/jlink17"
expect "a host jlink of the wrong major is refused" 1 "needs the same major" -- bash "$recipe" --host-jlink "$tmp/jlink17" --cache "$tmp/cache" --out "$tmp/o4" --jdk-archive "$good_tar" --sha256 "$good_sha"

# --- the happy path, end to end ------------------------------------------------------------------------
expect "the full recipe runs against the fake archive" 0 "VERIFY OK" -- bash "$recipe" "${common[@]}" --out "$tmp/rt" --modules java.base,java.logging --jdk-archive "$good_tar" --sha256 "$good_sha"
args="$(cat "$FAKE_JLINK_LOG" 2>/dev/null)"
for needle in "--add-modules java.base,java.logging" "--output $tmp/rt" "--strip-java-debug-attributes" "--no-header-files" "--no-man-pages" "--compress=zip-6" "--module-path"; do
  printf '%s' "$args" | grep -qF -- "$needle" && ok "jlink was called with $needle" || bad "jlink was not called with $needle (got: $args)"
done
m="$tmp/rt/RUNTIME_MANIFEST.txt"
if [ -f "$m" ]; then
  for needle in "arch=aarch64" "modules=java.base,java.logging" "jdk_archive_sha256=$good_sha" "ceiling_glibc=2.17" "evidence=CONTAINER-BUILD-ONLY"; do
    grep -qF -- "$needle" "$m" && ok "RUNTIME_MANIFEST.txt records $needle" || bad "RUNTIME_MANIFEST.txt lacks $needle"
  done
else bad "RUNTIME_MANIFEST.txt was not written"; fi
expect "an existing output directory is not clobbered without --force" 1 "already exists" -- bash "$recipe" "${common[@]}" --out "$tmp/rt" --jdk-archive "$good_tar" --sha256 "$good_sha"
expect "--force replaces it" 0 "VERIFY OK" -- bash "$recipe" "${common[@]}" --out "$tmp/rt" --force --jdk-archive "$good_tar" --sha256 "$good_sha"

export FAKE_RUNTIME_MACHINE=3e
expect "a linked runtime of the wrong architecture is refused" 1 "NOT a usable runtime" -- bash "$recipe" "${common[@]}" --out "$tmp/rt-wrong" --jdk-archive "$good_tar" --sha256 "$good_sha"

# --- the verify mode and the GLIBC ceiling, on real ELF files -----------------------------------------------
expect "--verify rejects a directory with no bin/java" 1 "bin/java is missing" -- bash "$recipe" --verify "$tmp"
expect "--verify accepts the fake aarch64 runtime" 0 "machine='" -- bash "$recipe" --verify "$tmp/rt"
expect "--verify rejects it when x86-64 is expected" 1 "expected X86-64" -- bash "$recipe" --verify "$tmp/rt" --expect-arch x86-64

hostjdk=""
if command -v java >/dev/null 2>&1; then
  cand="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  [ -f "$cand/lib/server/libjvm.so" ] && hostjdk="$cand"
fi
if [ -n "$hostjdk" ] && command -v readelf >/dev/null 2>&1; then
  case "$(uname -m)" in x86_64|amd64) arch=x86-64 ;; aarch64|arm64) arch=aarch64 ;; *) arch=any ;; esac
  expect "the GLIBC scan passes a real JDK under a high ceiling" 0 "VERIFY OK" -- bash "$recipe" --verify "$hostjdk" --expect-arch "$arch" --max-glibc 99.0
  # a ceiling no dynamically linked JDK can meet, so this holds on any host (a host Temurin is built
  # against glibc 2.17, so "fails under 2.17" would NOT be a portable expectation)
  expect "the GLIBC scan fails the same JDK under a ceiling of 1.0 and names a file" 1 "above the ceiling GLIBC_1.0" -- bash "$recipe" --verify "$hostjdk" --expect-arch any --max-glibc 1.0
  seen="$(bash "$recipe" --verify "$hostjdk" --expect-arch any --max-glibc 99.0 | sed -n 's/.*max_GLIBC=\([0-9.]*\) .*/\1/p')"
  if [ -n "$seen" ]; then
    expect "the ceiling is inclusive: a ceiling equal to the highest requirement ($seen) passes" 0 "VERIFY OK" -- bash "$recipe" --verify "$hostjdk" --expect-arch any --max-glibc "$seen"
  else
    bad "could not read max_GLIBC from the verify output"
  fi
else
  echo "skip - no host JDK with libjvm.so / readelf: the GLIBC scan on real ELF files was not exercised"
  failures=$((failures + 1))
fi

echo
if [ "$failures" -gt 0 ]; then echo "jlink selftest: $failures expectation(s) failed" >&2; exit 1; fi
echo "jlink selftest: all expectations held"
