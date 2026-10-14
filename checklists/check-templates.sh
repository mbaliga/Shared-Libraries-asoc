#!/usr/bin/env bash
# PORTING_PLAN.md S0.3 done-when: "Files exist; every row NEEDS-DEVICE-VALIDATION."
# The checklists in this directory are TEMPLATES. A filled-in copy belongs in the consumer's own
# evidence folder; a template row that has been ticked here without a device run is a false claim.
#
# Checks, per DEVICE_CHECKLIST_{UT,LINUX,IOS,MACOS,WINDOWS}.md in --dir (default: this directory):
#   - the file exists and has at least one row (a table line beginning `| <ID>-<nn> |`);
#   - every such row's Status cell is `NEEDS-DEVICE-VALIDATION`, its Result cell is `[ ]`, and its
#     Evidence cell is empty.
# Usage: checklists/check-templates.sh [--dir DIR] [--self-test]     Exit: 0 ok, 1 findings, 2 usage.
set -u

dir="$(cd "$(dirname "$0")" && pwd)"
self_test=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dir)       dir="${2:?--dir needs a directory}"; shift 2 ;;
    --self-test) self_test=1; shift ;;
    *)           echo "check-templates: unknown argument: $1" >&2; exit 2 ;;
  esac
done

row_re='^\| [A-Z]+-[0-9]+ \|'
good_tail='\| `NEEDS-DEVICE-VALIDATION` \| `\[ \]` \| \|$'

check() { # dir -> prints findings, returns 0/1
  local d="$1" status=0 plat f rows bad
  for plat in UT LINUX IOS MACOS WINDOWS; do
    f="$d/DEVICE_CHECKLIST_$plat.md"
    if [ ! -f "$f" ]; then echo "MISSING $f"; status=1; continue; fi
    rows=$(grep -cE "$row_re" "$f")
    if [ "$rows" -lt 1 ]; then echo "NOROWS  $f"; status=1; continue; fi
    bad=$(grep -E "$row_re" "$f" | grep -vcE "$good_tail")
    if [ "$bad" -ne 0 ]; then
      echo "NOT-NDV $f: $bad of $rows row(s) are not 'NEEDS-DEVICE-VALIDATION' / '[ ]' / empty evidence"
      status=1
    fi
  done
  return $status
}

if [ "$self_test" -eq 1 ]; then
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
  fails=0
  mk() { # plat status result evidence   (the row layout is the one the real templates use)
    local tail="| |"
    [ -z "$4" ] || tail="| $4 |"
    printf '| Id | Item |\n|---|---|\n| %s-01 | something | steps | needs | `%s` | `%s` %s\n' "$1" "$2" "$3" "$tail" > "$tmp/DEVICE_CHECKLIST_$1.md"
  }
  for p in UT LINUX IOS MACOS WINDOWS; do mk "$p" NEEDS-DEVICE-VALIDATION '[ ]' ''; done
  if check "$tmp" >/dev/null; then echo "ok   - all-NDV templates pass"; else echo "FAIL - clean templates rejected"; fails=$((fails + 1)); fi
  mk IOS PASS '[+]' 'done on my phone'
  if check "$tmp" | grep -q '^NOT-NDV'; then echo "ok   - a PASS row in a template is rejected"; else echo "FAIL - PASS row accepted"; fails=$((fails + 1)); fi
  mk IOS NEEDS-DEVICE-VALIDATION '[ ]' ''
  rm -f "$tmp/DEVICE_CHECKLIST_UT.md"
  if check "$tmp" | grep -q '^MISSING'; then echo "ok   - a missing template is reported"; else echo "FAIL - missing file accepted"; fails=$((fails + 1)); fi
  [ "$fails" -eq 0 ] || { echo "check-templates self-test: $fails failure(s)" >&2; exit 1; }
  echo "check-templates self-test: all expectations held"
  exit 0
fi

if check "$dir"; then echo "check-templates: OK (all rows in the five templates are NEEDS-DEVICE-VALIDATION)"; else exit 1; fi
