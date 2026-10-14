#!/usr/bin/env bash
# Self-test for windows-path-lint.sh. Builds throw-away git repositories in a temp directory,
# registers deliberately bad paths straight into the index (git update-index --cacheinfo), so no
# file with a hostile name is ever created on disk, and asserts that the lint:
#   - passes a clean repo,
#   - fails each rule with the matching `FAIL <rule>` line, and
#   - names the offending path.
# Exit 0 only if every expectation holds. PORTING_PLAN.md S0.1.
set -u

here="$(cd "$(dirname "$0")" && pwd)"
lint="$here/windows-path-lint.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

failures=0
pass() { printf 'ok   - %s\n' "$1"; }
bad()  { printf 'FAIL - %s\n' "$1"; failures=$((failures + 1)); }

new_repo() { # name -> prints path
  local d="$tmp/$1"
  mkdir -p "$d"
  git -C "$d" init -q
  printf '%s' "$d"
}

add_path() { # repo path   (blob content is irrelevant; it only has to exist)
  local blob
  blob=$(printf 'x' | git -C "$1" hash-object -w --stdin)
  git -C "$1" update-index --add --cacheinfo "100644,$blob,$2"
}

# $1 label  $2 repo  $3 expected exit  $4 expected substring (or "" for none)  rest: lint args
expect() {
  local label="$1" repo="$2" want_exit="$3" want_text="$4"; shift 4
  local out code
  out=$("$lint" --repo "$repo" "$@" 2>&1); code=$?
  if [ "$code" -ne "$want_exit" ]; then
    bad "$label (exit $code, wanted $want_exit)"; printf '%s\n' "$out" | sed 's/^/       /'; return
  fi
  if [ -n "$want_text" ] && ! printf '%s' "$out" | grep -qF -- "$want_text"; then
    bad "$label (output lacks: $want_text)"; printf '%s\n' "$out" | sed 's/^/       /'; return
  fi
  pass "$label"
}

# --- clean repo ------------------------------------------------------------------------------
r=$(new_repo clean)
add_path "$r" "src/main/Foo.kt"
add_path "$r" "docs/README.md"
printf 'specs/fixtures/* -text\n*.sh text eol=lf\n' > "$r/.gitattributes"
git -C "$r" add .gitattributes
add_path "$r" "specs/fixtures/a.json"
add_path "$r" "scripts/run.sh"
expect "clean repo passes" "$r" 0 "OK"

# --- each rule -------------------------------------------------------------------------------
r=$(new_repo colon);      add_path "$r" "notes/12:30.txt";        expect "colon is rejected"            "$r" 1 "FAIL invalid-char"
r=$(new_repo angle);      add_path "$r" "a<b.txt";                expect "angle bracket is rejected"    "$r" 1 "FAIL invalid-char"
r=$(new_repo quote);      add_path "$r" 'say"hi".txt';            expect "double quote is rejected"     "$r" 1 "FAIL invalid-char"
r=$(new_repo pipe);       add_path "$r" "a|b";                    expect "pipe is rejected"             "$r" 1 "FAIL invalid-char"
r=$(new_repo qmark);      add_path "$r" "why?.md";                expect "question mark is rejected"    "$r" 1 "FAIL invalid-char"
r=$(new_repo star);       add_path "$r" "glob*.md";               expect "asterisk is rejected"         "$r" 1 "FAIL invalid-char"
r=$(new_repo backslash);  add_path "$r" 'dir\file.txt';           expect "backslash is rejected"        "$r" 1 "FAIL invalid-char"
r=$(new_repo ctrl);       add_path "$r" "tab$(printf '\t')name";  expect "control char is rejected"     "$r" 1 "FAIL invalid-char"

r=$(new_repo aux);        add_path "$r" "src/aux.txt";            expect "aux.txt is reserved"          "$r" 1 "FAIL reserved-name"
r=$(new_repo nul);        add_path "$r" "NUL";                    expect "NUL is reserved"              "$r" 1 "FAIL reserved-name"
r=$(new_repo con_dir);    add_path "$r" "con/readme.md";          expect "a con/ directory is reserved" "$r" 1 "FAIL reserved-name"
r=$(new_repo com1);       add_path "$r" "x/Com1.tar.gz";          expect "Com1.tar.gz is reserved"      "$r" 1 "FAIL reserved-name"
r=$(new_repo lpt9);       add_path "$r" "lpt9";                   expect "lpt9 is reserved"            "$r" 1 "FAIL reserved-name"
r=$(new_repo ok_names);   add_path "$r" "src/console.kt"; add_path "$r" "src/com10.txt"; add_path "$r" "auxiliary/nullable.kt"
expect "console / com10 / nullable are fine" "$r" 0 "OK"

r=$(new_repo dot);        add_path "$r" "docs/trailing."; expect "trailing dot is rejected" "$r" 1 "FAIL trailing"
r=$(new_repo space);      add_path "$r" "docs/trailing ";  expect "trailing space is rejected" "$r" 1 "FAIL trailing"

r=$(new_repo case);       add_path "$r" "docs/Readme.md"; add_path "$r" "docs/README.md"
expect "case-only collision is rejected" "$r" 1 "FAIL case-collision"

r=$(new_repo longpath)
long="$(printf 'd%.0s' $(seq 1 130))/$(printf 'f%.0s' $(seq 1 130)).txt"
add_path "$r" "$long";   expect "over-long path is rejected"      "$r" 1 "FAIL path-length"
expect "--max-path raises the limit"                              "$r" 0 "OK" --max-path 400

r=$(new_repo fixture)
add_path "$r" "specs/fixtures/golden.json"
expect "fixture without -text is rejected" "$r" 1 "FAIL fixture-text"
printf 'specs/fixtures/* -text\n' > "$r/.gitattributes"
expect "fixture with -text passes"         "$r" 0 "OK"
expect "--byte-exact replaces the default list" "$r" 0 "OK" --byte-exact golden-data
add_path "$r" "golden-data/g.bin"
expect "--byte-exact flags the named directory" "$r" 1 "FAIL fixture-text" --byte-exact golden-data

r=$(new_repo eol)
add_path "$r" "tools/run.sh"
expect "*.sh without eol=lf is rejected" "$r" 1 "FAIL script-eol"
printf '*.sh text eol=lf\n' > "$r/.gitattributes"
expect "*.sh with eol=lf passes"         "$r" 0 "OK"

# --- usage errors ------------------------------------------------------------------------------
expect "non-repository is a usage error"   "$tmp" 2 "not a git repository"
expect "unknown flag is a usage error"     "$tmp" 2 "unknown argument" --nope
expect "bad --max-path is a usage error"   "$tmp" 2 "must be a number" --max-path abc

# --- the real repository must pass its own lint --------------------------------------------------
real="$(cd "$here/.." && pwd)"
if git -C "$real" rev-parse --git-dir >/dev/null 2>&1; then
  expect "this repository passes its own lint" "$real" 0 "OK"
fi

echo
if [ "$failures" -gt 0 ]; then
  echo "test-windows-path-lint: $failures expectation(s) failed" >&2
  exit 1
fi
echo "test-windows-path-lint: all expectations held"
