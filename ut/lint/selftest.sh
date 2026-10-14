#!/usr/bin/env bash
# Self-test for the Ubuntu Touch lint and renderer. Drives the REAL checked-in templates: render them
# with synthetic values, lint the result, then break the result in targeted ways and expect the lint to
# say so. Also runs ut-lint.py's own in-process self-test. No network, no Clickable, no device.
# The synthetic values below are obviously fake (.invalid is reserved by RFC 2606); they exist only in
# this script and in a temp directory, never in a checked-in manifest (rule R11).
set -u

here="$(cd "$(dirname "$0")" && pwd)"
ut="$(cd "$here/.." && pwd)"
lint=(python3 -I "$here/ut-lint.py")
render=(python3 -I "$ut/tools/render.py")
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
failures=0
ok()  { printf 'ok   - %s\n' "$1"; }
bad() { printf 'FAIL - %s\n' "$1"; failures=$((failures + 1)); }

# expect LABEL WANT_EXIT NEEDLE -- command...
expect() {
  local label="$1" want="$2" needle="$3"; shift 4
  local out code
  out="$("$@" 2>&1)"; code=$?
  if [ "$code" -ne "$want" ]; then bad "$label (exit $code, wanted $want)"; printf '%s\n' "$out" | sed 's/^/       /'; return; fi
  if [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF -- "$needle"; then bad "$label (output lacks: $needle)"; printf '%s\n' "$out" | sed 's/^/       /'; return; fi
  ok "$label"
}

vals=(--set CLICK_NAME=lint-fixture.invalid --set APP_TITLE="Lint fixture" --set APP_VERSION=0.0.1
      --set APP_DESCRIPTION="Synthetic manifest for the lint self-test."
      --set APP_MAINTAINER="Fixture Maintainer <maintainer@example.invalid>"
      --set CLICK_FRAMEWORK=ubuntu-touch-24.04-1.x --set CLICK_ARCH=arm64 --set APP_HOOK=fixture)

# --- the lint's own in-process self-test -----------------------------------------------------------
expect "ut-lint.py --self-test" 0 "all expectations held" -- "${lint[@]}" --self-test

# --- render the real templates and lint the result --------------------------------------------------
"${render[@]}" "$ut/templates/manifest.json.in" "${vals[@]}" --out "$tmp/manifest.json" || bad "render manifest"
"${render[@]}" "$ut/templates/app.apparmor.in" --set APPARMOR_POLICY=1.0 --policy-groups audio,content_exchange --out "$tmp/fixture.apparmor" || bad "render apparmor"
expect "the rendered pair passes" 0 "ut-lint: OK" -- "${lint[@]}" --manifest "$tmp/manifest.json" --apparmor "$tmp/fixture.apparmor"
expect "the checked-in templates pass the R11 check" 0 "ut-lint: OK" -- "${lint[@]}" --templates "$ut/templates"

# --- break the rendered pair ---------------------------------------------------------------------
mut() { # file sed-expr -> $tmp/m or $tmp/a copy
  sed "$2" "$1" > "$3"
}
mut "$tmp/manifest.json" 's/ubuntu-touch-24.04-1.x/ubuntu-sdk-20.04/' "$tmp/m1.json"
expect "a 20.04 framework is refused" 1 "framework:" -- "${lint[@]}" --manifest "$tmp/m1.json" --apparmor "$tmp/fixture.apparmor"
mut "$tmp/manifest.json" 's/"Lint fixture"/"@APP_TITLE@"/' "$tmp/m2.json"
expect "a left-over token is refused" 1 "unrendered token @APP_TITLE@" -- "${lint[@]}" --manifest "$tmp/m2.json" --apparmor "$tmp/fixture.apparmor"
mut "$tmp/fixture.apparmor" 's/"audio"/"contacts"/' "$tmp/a1.apparmor"
expect "a reserved policy group is refused" 1 "RESERVED" -- "${lint[@]}" --manifest "$tmp/manifest.json" --apparmor "$tmp/a1.apparmor"
mut "$tmp/fixture.apparmor" 's/"audio"/"unconfined"/' "$tmp/a2.apparmor"
expect "an unknown policy group is refused" 1 "not in the common allowlist" -- "${lint[@]}" --manifest "$tmp/manifest.json" --apparmor "$tmp/a2.apparmor"
mut "$tmp/manifest.json" 's/"fixture.apparmor"/"elsewhere.apparmor"/' "$tmp/m3.json"
expect "a pair that does not belong together is refused" 1 "does not belong together" -- "${lint[@]}" --manifest "$tmp/m3.json" --apparmor "$tmp/fixture.apparmor"
printf '{ not json' > "$tmp/broken.json"
expect "invalid JSON is refused" 1 "json: manifest" -- "${lint[@]}" --manifest "$tmp/broken.json" --apparmor "$tmp/fixture.apparmor"
expect "--policy-version pins the value" 1 "not the pinned" -- "${lint[@]}" --manifest "$tmp/manifest.json" --apparmor "$tmp/fixture.apparmor" --policy-version 24.04
expect "no arguments is a usage error" 2 "give --manifest" -- "${lint[@]}"
expect "an unreadable manifest is a finding, not a crash" 1 "cannot read" -- "${lint[@]}" --manifest "$tmp/nope.json" --apparmor "$tmp/fixture.apparmor"

# --- the renderer refuses sloppy input ---------------------------------------------------------------
expect "render: a missing token value is refused" 1 "no value for token" -- "${render[@]}" "$ut/templates/manifest.json.in" --set CLICK_NAME=x
expect "render: a --set that names no token is refused" 1 "not in the template" -- "${render[@]}" "$ut/templates/app.apparmor.in" --set APPARMOR_POLICY=1.0 --set TYPO=1
expect "render: a value that would break the JSON string is refused" 1 "needs JSON escaping" -- "${render[@]}" "$ut/templates/manifest.json.in" "${vals[@]}" --set APP_TITLE='say "hi"'
expect "render: --policy-groups on a template without the token is refused" 1 "no @POLICY_GROUPS@" -- "${render[@]}" "$ut/templates/manifest.json.in" "${vals[@]}" --policy-groups audio
expect "render: a malformed --set is a usage error" 2 "bad --set" -- "${render[@]}" "$ut/templates/manifest.json.in" --set lower=1

echo
if [ "$failures" -gt 0 ]; then echo "ut lint selftest: $failures expectation(s) failed" >&2; exit 1; fi
echo "ut lint selftest: all expectations held"
