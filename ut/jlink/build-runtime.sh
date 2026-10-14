#!/usr/bin/env bash
# build-runtime.sh - produce an aarch64 Temurin 21 jlink runtime for a JVM-cored Ubuntu Touch click.
#
# PORTING_PLAN.md F7 / S-UT1. The program records that this recipe was proven once at 39 MB with a
# GLIBC_2.17 ceiling; this script is that recipe as runnable code, so it can be repeated and checked
# instead of remembered. It CROSS-links: a host JDK's jlink (any architecture) reads the *target*
# architecture's jmods and writes a target-architecture runtime. Nothing is executed on the target.
#
# What it does
#   1. checks the host has a jlink of the SAME major version as the target JDK (jlink cross-linking
#      needs matching majors);
#   2. downloads the latest GA Temurin <version> linux/aarch64 JDK from the Adoptium API (or takes
#      --jdk-archive with a --sha256 you supply), VERIFIES its SHA-256, and extracts it;
#   3. runs `jlink` over the target jmods with the module list you choose;
#   4. verifies the result: ELF machine type, and the highest GLIBC_x.y version any ELF file needs
#      (default ceiling 2.17), then writes RUNTIME_MANIFEST.txt beside it.
#
# Usage
#   ut/jlink/build-runtime.sh [--out DIR] [--modules LIST] [--jdk-version N] [--force]
#                             [--jdk-archive FILE --sha256 HEX] [--cache DIR] [--host-jlink PATH]
#                             [--max-glibc VER] [--dry-run]
#   ut/jlink/build-runtime.sh --verify DIR [--expect-arch aarch64|x86-64|any] [--max-glibc VER]
#
#   --out DIR         where the runtime is written (default ./build/ut-runtime; jlink refuses an
#                     existing directory, so pass --force to replace one)
#   --modules LIST    comma-separated module list (default java.base,java.logging,jdk.unsupported).
#                     Derive a real list from your jar: jdeps --print-module-deps --ignore-missing-deps app.jar
#                     Omit java.desktop for a headless core: it drags in toolkit libraries a click
#                     does not have and costs tens of MB. S1.2's Swing window lives in a separate package for this reason.
#   --jdk-version N   target major (default 21); the HOST jlink must be the same major
#   --jdk-archive F   use this aarch64 Temurin .tar.gz instead of downloading; requires --sha256
#   --cache DIR       download cache (default ${XDG_CACHE_HOME:-$HOME/.cache}/ut-jlink)
#   --max-glibc VER   fail if any ELF needs a newer GLIBC than this (default 2.17)
#   --verify DIR      check an existing runtime directory and exit (no build)
#   --expect-arch A   with --verify: aarch64 (default), x86-64, or any (skip the machine check)
#   --dry-run         print the plan and the exact jlink command; download and run nothing
#
# Needs: bash, curl, tar, sha256sum (or shasum), python3, readelf, jlink. No root. Network only for the
# Adoptium download. Exit: 0 ok, 1 build or verification failure, 2 usage.
#
# Evidence: a container run of this script is CONTAINER-BUILD-ONLY. It proves the runtime *links* and
# that its binaries target aarch64 with a stated glibc ceiling. It does NOT prove the runtime starts on
# a device; that is S-UT1 and a device gate (checklists/DEVICE_CHECKLIST_UT.md).
set -u -o pipefail

out="./build/ut-runtime"
modules="java.base,java.logging,jdk.unsupported"
jdk_version="21"
archive=""
sha256=""
cache="${XDG_CACHE_HOME:-$HOME/.cache}/ut-jlink"
host_jlink=""
max_glibc="2.17"
verify_dir=""
expect_arch="aarch64"
dry_run=0
force=0

die()   { echo "build-runtime: $*" >&2; exit 1; }
usage() { echo "build-runtime: $*" >&2; echo "try: $0 --help" >&2; exit 2; }
need()  { command -v "$1" >/dev/null 2>&1 || die "required tool not found: $1"; }

show_help() { sed -n '2,/^set -u/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --out)         out="${2:?--out needs a directory}"; shift 2 ;;
    --modules)     modules="${2:?--modules needs a list}"; shift 2 ;;
    --jdk-version) jdk_version="${2:?--jdk-version needs a number}"; shift 2 ;;
    --jdk-archive) archive="${2:?--jdk-archive needs a file}"; shift 2 ;;
    --sha256)      sha256="${2:?--sha256 needs a hex digest}"; shift 2 ;;
    --cache)       cache="${2:?--cache needs a directory}"; shift 2 ;;
    --host-jlink)  host_jlink="${2:?--host-jlink needs a path}"; shift 2 ;;
    --max-glibc)   max_glibc="${2:?--max-glibc needs a version}"; shift 2 ;;
    --verify)      verify_dir="${2:?--verify needs a directory}"; shift 2 ;;
    --expect-arch) expect_arch="${2:?--expect-arch needs a value}"; shift 2 ;;
    --force)       force=1; shift ;;
    --dry-run)     dry_run=1; shift ;;
    -h|--help)     show_help; exit 0 ;;
    *)             usage "unknown argument: $1" ;;
  esac
done

case "$jdk_version" in ''|*[!0-9]*) usage "--jdk-version must be a number" ;; esac
case "$max_glibc" in [0-9]*.[0-9]*) ;; *) usage "--max-glibc must look like 2.17" ;; esac
case "$expect_arch" in aarch64|x86-64|any) ;; *) usage "--expect-arch must be aarch64, x86-64 or any" ;; esac
case "$modules" in ''|*[!A-Za-z0-9_.,]*) usage "--modules must be a comma-separated list of module names" ;; esac
if [ -n "$archive" ] && [ -z "$sha256" ]; then usage "--jdk-archive requires --sha256 (an unchecked archive is not accepted)"; fi
if [ -z "$archive" ] && [ -n "$sha256" ]; then usage "--sha256 only makes sense with --jdk-archive"; fi

# ---------------------------------------------------------------------------------------------------
sha256_of() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

# version_gt A B: true when A is a strictly newer dotted version than B
version_gt() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n 1)" = "$1" ]; }

is_elf() { [ "$(head -c 4 "$1" 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]; }

# verify_runtime DIR ARCH MAXGLIBC - prints a one-line summary on success; returns 1 on any problem.
# Sets (globals) v_files v_glibc v_glibcxx v_kib for the caller.
verify_runtime() {
  local dir="$1" arch="$2" ceiling="$3" f machine g fmax max="" maxx="" bad=0 n=0
  [ -d "$dir" ] || { echo "VERIFY FAIL: $dir is not a directory"; return 1; }
  [ -x "$dir/bin/java" ] || { echo "VERIFY FAIL: $dir/bin/java is missing"; return 1; }
  machine="$(readelf -h "$dir/bin/java" 2>/dev/null | sed -n 's/^ *Machine: *//p')"
  case "$arch" in
    aarch64) case "$machine" in *AArch64*) ;; *) echo "VERIFY FAIL: bin/java machine is '$machine', expected AArch64"; bad=1 ;; esac ;;
    x86-64)  case "$machine" in *X86-64*)   ;; *) echo "VERIFY FAIL: bin/java machine is '$machine', expected X86-64"; bad=1 ;; esac ;;
    any) ;;
  esac
  while IFS= read -r f; do
    is_elf "$f" || continue
    n=$((n + 1))
    fmax=""
    for g in $(readelf -V "$f" 2>/dev/null | grep -o 'GLIBC_[0-9][0-9.]*' | sort -uV); do
      g="${g#GLIBC_}"
      if [ -z "$fmax" ] || version_gt "$g" "$fmax"; then fmax="$g"; fi
    done
    for g in $(readelf -V "$f" 2>/dev/null | grep -o 'GLIBCXX_[0-9][0-9.]*' | sort -uV); do
      g="${g#GLIBCXX_}"
      if [ -z "$maxx" ] || version_gt "$g" "$maxx"; then maxx="$g"; fi
    done
    if [ -n "$fmax" ]; then
      if [ -z "$max" ] || version_gt "$fmax" "$max"; then max="$fmax"; fi
      if version_gt "$fmax" "$ceiling"; then
        echo "VERIFY FAIL: ${f#"$dir"/} needs GLIBC_$fmax, above the ceiling GLIBC_$ceiling"
        bad=1
      fi
    fi
  done < <(find "$dir" -type f)
  v_files="$n"; v_glibc="${max:-none}"; v_glibcxx="${maxx:-none}"
  v_kib="$(du -sk "$dir" | cut -f1)"
  [ "$n" -gt 0 ] || { echo "VERIFY FAIL: no ELF files found under $dir"; return 1; }
  [ "$bad" -eq 0 ] || return 1
  echo "VERIFY OK: machine='$machine' elf_files=$n max_GLIBC=$v_glibc max_GLIBCXX=$v_glibcxx (ceiling $ceiling) size=${v_kib} KiB"
  return 0
}

# ---------------------------------------------------------------------------------------------------
if [ -n "$verify_dir" ]; then
  need readelf; need find; need du; need od; need sort
  verify_runtime "$verify_dir" "$expect_arch" "$max_glibc"
  exit $?
fi

# --- plan ------------------------------------------------------------------------------------------
if [ -z "$host_jlink" ]; then
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jlink" ]; then host_jlink="$JAVA_HOME/bin/jlink"
  else host_jlink="$(command -v jlink || true)"; fi
fi

# NOT `--strip-debug` (and NOT any --strip-native-debug-symbols form): in JDK 21 the native-symbol
# stripper always shells out to the HOST's objcopy, which cannot read foreign (AArch64) ELF files; it
# prints a screenful of "Unable to recognise the format" errors and strips nothing. Temurin's native
# libraries ship already stripped, so nothing is lost by leaving it out (measured: the result is
# byte-for-byte the same size with and without the attempt). Only the Java debug attributes are stripped.
jlink_flags=(--strip-java-debug-attributes --no-header-files --no-man-pages --compress=zip-6)

echo "plan:"
echo "  target             linux/aarch64 Temurin $jdk_version (hotspot jdk)"
if [ -n "$archive" ]; then echo "  jdk source         local archive $archive (sha256 given: $sha256)"
else echo "  jdk source         latest GA from https://api.adoptium.net, checksum taken from the API and verified"; fi
echo "  host jlink         ${host_jlink:-<not found>}"
echo "  modules            $modules"
echo "  output             $out"
echo "  glibc ceiling      GLIBC_$max_glibc"
echo "  jlink command      jlink --module-path <extracted-jdk>/jmods --add-modules $modules --output $out ${jlink_flags[*]}"
if [ "$dry_run" -eq 1 ]; then echo "dry run: nothing downloaded or executed"; exit 0; fi

# --- preflight -------------------------------------------------------------------------------------
for t in tar python3 readelf find du od sort; do need "$t"; done
[ -z "$archive" ] && need curl
[ -n "$host_jlink" ] && [ -x "$host_jlink" ] || die "no jlink found; put a JDK $jdk_version on PATH, set JAVA_HOME, or pass --host-jlink"
host_ver="$("$host_jlink" --version 2>/dev/null | head -n 1)"
host_major="${host_ver%%.*}"
[ "$host_major" = "$jdk_version" ] || die "host jlink is '$host_ver' but the target is JDK $jdk_version; cross-linking needs the same major version"
if [ -e "$out" ]; then
  if [ "$force" -eq 1 ]; then rm -rf "$out"; else die "$out already exists (pass --force to replace it)"; fi
fi
mkdir -p "$cache" "$(dirname "$out")"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT

# --- obtain and verify the target JDK --------------------------------------------------------------
release="local-archive"
if [ -z "$archive" ]; then
  api="https://api.adoptium.net/v3/assets/latest/${jdk_version}/hotspot?architecture=aarch64&os=linux&image_type=jdk"
  echo "resolving: $api"
  meta="$(curl -fsSL --retry 3 "$api")" || die "could not query the Adoptium API"
  # one line: release_name <TAB> link <TAB> sha256
  line="$(printf '%s' "$meta" | python3 -I -c '
import json, sys
d = json.load(sys.stdin)
b = d[0]
print("\t".join([b["release_name"], b["binary"]["package"]["link"], b["binary"]["package"]["checksum"]]))
')" || die "unexpected Adoptium API response"
  release="$(printf '%s' "$line" | cut -f1)"
  link="$(printf '%s' "$line" | cut -f2)"
  sha256="$(printf '%s' "$line" | cut -f3)"
  archive="$cache/$(basename "${link%%\?*}")"
  if [ -f "$archive" ] && [ "$(sha256_of "$archive")" = "$sha256" ]; then
    echo "cached:    $archive"
  else
    echo "download:  $link"
    curl -fL --retry 3 -o "$archive.part" "$link" || die "download failed"
    mv "$archive.part" "$archive"
  fi
fi
[ -f "$archive" ] || die "archive not found: $archive"
actual="$(sha256_of "$archive")"
[ "$actual" = "$sha256" ] || { rm -f "$cache/$(basename "$archive").part"; die "SHA-256 mismatch for $archive: expected $sha256, got $actual"; }
echo "sha256 ok: $sha256"

tar -xzf "$archive" -C "$work" || die "could not extract $archive"
jmods="$(find "$work" -maxdepth 3 -type d -name jmods | head -n 1)"
[ -n "$jmods" ] && [ -f "$jmods/java.base.jmod" ] || die "no jmods/java.base.jmod in the archive: is it a full JDK (not a JRE)?"
# the extracted JDK's own bin/java must be a target-architecture binary, or this is the wrong archive
case "$(readelf -h "$(dirname "$jmods")/bin/java" 2>/dev/null | sed -n 's/^ *Machine: *//p')" in
  *AArch64*) ;; *) die "the archive's bin/java is not AArch64; refusing to link the wrong architecture" ;;
esac

# --- link -------------------------------------------------------------------------------------------
echo "jlink:     $host_ver -> $out"
"$host_jlink" --module-path "$jmods" --add-modules "$modules" --output "$out" "${jlink_flags[@]}" || die "jlink failed"

# --- verify + manifest -------------------------------------------------------------------------------
v_files=0; v_glibc=none; v_glibcxx=none; v_kib=0
if ! verify_runtime "$out" aarch64 "$max_glibc"; then die "verification failed; $out is NOT a usable runtime"; fi
{
  echo "built_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "jdk_release=$release"
  echo "jdk_archive_sha256=$sha256"
  echo "host_jlink=$host_ver"
  echo "modules=$modules"
  echo "jlink_flags=${jlink_flags[*]}"
  echo "elf_files=$v_files"
  echo "max_glibc=$v_glibc"
  echo "max_glibcxx=$v_glibcxx"
  echo "ceiling_glibc=$max_glibc"
  echo "size_kib=$v_kib"
  echo "arch=aarch64"
  echo "evidence=CONTAINER-BUILD-ONLY (linked and inspected; never started on a device)"
} > "$out/RUNTIME_MANIFEST.txt"
echo "wrote:     $out/RUNTIME_MANIFEST.txt"
echo "size:      $v_kib KiB (~$((v_kib / 1024)) MiB), max GLIBC_$v_glibc"
