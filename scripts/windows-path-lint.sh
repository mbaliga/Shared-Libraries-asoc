#!/usr/bin/env bash
# windows-path-lint.sh - fail if any tracked path cannot be checked out on a Windows (or
# case-insensitive macOS) runner, or if a byte-exact fixture / shell script would be rewritten
# by a Windows checkout.   PORTING_PLAN.md S0.1 (R3: "a Windows-checkout path lint before any
# windows-* lane").
#
# What it checks, over `git ls-files` of the repo under --repo (default: the current directory):
#
#   invalid-char   a path containing any of  < > : " | ? *  \  or an ASCII control character
#   reserved-name  a path component whose part before the first dot is a Windows device name
#                  (CON PRN AUX NUL COM1-COM9 LPT1-LPT9), any case, with or without extension
#   trailing       a path component ending in a dot or a space (Windows silently strips both)
#   case-collision two tracked paths that differ only by letter case
#   path-length    a repo-relative path longer than --max-path characters (default 240; the
#                  Windows MAX_PATH is 260 and the runner workspace prefix eats ~20)
#   fixture-text   a file under a byte-exact directory (default: specs/fixtures) whose `text`
#                  attribute is not unset - i.e. no `-text` line covers it, so a Windows
#                  checkout with core.autocrlf would rewrite its bytes
#   script-eol     a tracked *.sh file whose `eol` attribute is not `lf` - a CRLF checkout
#                  breaks the shebang line
#
# Known limitation: case-collision compares whole file paths. A directory that differs only by
# case from a sibling directory (Foo/x and foo/y) merges harmlessly on Windows and is not
# flagged; a *file* that collides with a *directory* of the other case is not detected either.
#
# Usage:  scripts/windows-path-lint.sh [--repo DIR] [--max-path N] [--byte-exact DIR]...
#         (--byte-exact is repeatable and, when given, REPLACES the default specs/fixtures)
# Exit:   0 clean, 1 findings, 2 usage / not a git repository.
# Prints one `FAIL <rule> <path>` line per finding. Needs only bash, git, awk and tr.
set -u

repo="."
max_path=240
byte_exact_dirs=()

while [ $# -gt 0 ]; do
  case "$1" in
    --repo)        repo="${2:?--repo needs a directory}"; shift 2 ;;
    --max-path)    max_path="${2:?--max-path needs a number}"; shift 2 ;;
    --byte-exact)  byte_exact_dirs+=("${2:?--byte-exact needs a directory}"); shift 2 ;;
    -h|--help)     sed -n '2,/^set -u/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)             echo "windows-path-lint: unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$max_path" in ''|*[!0-9]*) echo "windows-path-lint: --max-path must be a number" >&2; exit 2 ;; esac
if [ ${#byte_exact_dirs[@]} -eq 0 ]; then byte_exact_dirs=("specs/fixtures"); fi

if ! git -C "$repo" rev-parse --git-dir >/dev/null 2>&1; then
  echo "windows-path-lint: $repo is not a git repository" >&2
  exit 2
fi

fails=0
fail() { # rule path
  printf 'FAIL %s %s\n' "$1" "$2"
  fails=$((fails + 1))
}

upper() { printf '%s' "$1" | tr '[:lower:]' '[:upper:]'; }

count=0
all_paths=""
while IFS= read -r -d '' p; do
  count=$((count + 1))
  # printable form for messages: control characters shown as '?'
  shown=$(printf '%s' "$p" | tr '[:cntrl:]' '?')
  all_paths="${all_paths}${shown}
"

  # invalid characters ------------------------------------------------------------------
  if printf '%s' "$p" | LC_ALL=C grep -q '[[:cntrl:]]'; then
    fail invalid-char "$shown"
  else
    case "$p" in
      *[\<\>:\"\|\?\*\\]*) fail invalid-char "$p" ;;
    esac
  fi

  # path length -------------------------------------------------------------------------
  if [ "${#p}" -gt "$max_path" ]; then
    fail path-length "$shown (${#p} > $max_path)"
  fi

  # per-component rules -----------------------------------------------------------------
  rest="$p"
  while [ -n "$rest" ]; do
    case "$rest" in
      */*) comp="${rest%%/*}"; rest="${rest#*/}" ;;
      *)   comp="$rest"; rest="" ;;
    esac
    base_up=$(upper "${comp%%.*}")
    case "$base_up" in
      CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]) fail reserved-name "$shown" ;;
    esac
    case "$comp" in
      *.|*" ") fail trailing "$shown" ;;
    esac
  done

  # byte-exact fixtures -----------------------------------------------------------------
  for d in "${byte_exact_dirs[@]}"; do
    case "$p" in
      "$d"/*)
        attr=$(git -C "$repo" check-attr text -- "$p" | sed 's/^.*: text: //')
        if [ "$attr" != "unset" ]; then
          fail fixture-text "$shown (text attribute is '$attr'; needs '-text')"
        fi
        ;;
    esac
  done

  # shell scripts must keep LF -----------------------------------------------------------
  case "$p" in
    *.sh)
      attr=$(git -C "$repo" check-attr eol -- "$p" | sed 's/^.*: eol: //')
      if [ "$attr" != "lf" ]; then
        fail script-eol "$shown (eol attribute is '$attr'; needs 'eol=lf')"
      fi
      ;;
  esac
done < <(git -C "$repo" ls-files -z)

# case collisions ---------------------------------------------------------------------------
collisions=$(printf '%s' "$all_paths" | awk '
  NF == 0 && $0 == "" { next }
  { k = tolower($0); n[k]++; names[k] = (k in names) ? names[k] " | " $0 : $0 }
  END { for (k in n) if (n[k] > 1) print names[k] }')
if [ -n "$collisions" ]; then
  while IFS= read -r line; do fail case-collision "$line"; done <<EOF
$collisions
EOF
fi

if [ "$fails" -gt 0 ]; then
  echo "windows-path-lint: $fails finding(s) across $count tracked path(s)" >&2
  exit 1
fi
echo "windows-path-lint: OK ($count tracked paths checked)"
exit 0
