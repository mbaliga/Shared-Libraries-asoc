#!/usr/bin/env python3
"""
Assert that diagnostics-noop mirrors diagnostics-android's public facade.

Why this is a script and not a code review note: signature drift between the real facade and the
no-op does not fail at the moment it is introduced. It fails later, in someone's release build,
which is the worst possible time to discover that `Diagnostics.audioCallback` exists in debug and
not in release. This runs in CI and fails immediately instead.

Checked:
  - every public function on the real `object Diagnostics` exists on the no-op, and vice versa
  - parameter NAMES and ORDER match (Kotlin named arguments make these part of the API)
  - every field of the real `Config` exists on the no-op Config, with the same order

Usage:  python3 check-noop-parity.py           (exit 1 on drift)
"""

import re
import sys
from pathlib import Path

# This script lives in scripts/, one level below the repo root the module folders (:diagnostics-
# android, :diagnostics-noop) actually sit in — .parent.parent, not .parent.
ROOT = Path(__file__).resolve().parent.parent
REAL = ROOT / "diagnostics-android/src/main/kotlin/dev/aarso/diagnostics/Diagnostics.kt"
NOOP = ROOT / "diagnostics-noop/src/main/kotlin/dev/aarso/diagnostics/Diagnostics.kt"

# Internal helpers that intentionally have no no-op counterpart.
INTERNAL = {"currentSession", "currentConfig", "overlay", "wireDefaultSources"}


def balanced(text, start, open_ch, close_ch):
    """Return the substring from the first open_ch at/after start to its matching close_ch."""
    i = text.index(open_ch, start)
    depth = 0
    for j in range(i, len(text)):
        if text[j] == open_ch:
            depth += 1
        elif text[j] == close_ch:
            depth -= 1
            if depth == 0:
                return text[i:j + 1]
    raise ValueError("unbalanced")


def strip_comments(s):
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    s = re.sub(r"//[^\n]*", "", s)
    return s


def object_body(path):
    text = path.read_text(encoding="utf-8")
    return balanced(text, text.index("object Diagnostics"), "{", "}")


def functions(body):
    """name -> [param names], for public functions only."""
    body = strip_comments(body)
    out = {}
    for m in re.finditer(r"\bfun\s+(?:<[^>]+>\s+)?(\w+)\s*\(", body):
        name = m.group(1)
        if name in INTERNAL:
            continue
        params = balanced(body, m.end() - 1, "(", ")")[1:-1]
        names = re.findall(r"(?:^|,)\s*(?:vararg\s+)?(\w+)\s*:", params)
        out[name] = names
    return out


def config_fields(path):
    text = strip_comments(path.read_text(encoding="utf-8"))
    body = balanced(text, text.index("data class Config"), "(", ")")
    return re.findall(r"\bval\s+(\w+)\s*:", body)


def main():
    problems = []

    real = functions(object_body(REAL))
    noop = functions(object_body(NOOP))

    for name in sorted(set(real) - set(noop)):
        problems.append(f"missing from no-op: Diagnostics.{name}({', '.join(real[name])})")
    for name in sorted(set(noop) - set(real)):
        problems.append(f"extra in no-op:     Diagnostics.{name}({', '.join(noop[name])})")
    for name in sorted(set(real) & set(noop)):
        if real[name] != noop[name]:
            problems.append(
                f"parameter drift:    Diagnostics.{name}\n"
                f"                      real: ({', '.join(real[name])})\n"
                f"                      noop: ({', '.join(noop[name])})")

    rc, nc = config_fields(REAL.parent / "Config.kt"), config_fields(NOOP)
    for f in rc:
        if f not in nc:
            problems.append(f"missing from no-op Config: {f}")
    for f in nc:
        if f not in rc:
            problems.append(f"extra in no-op Config:     {f}")
    if [f for f in rc if f in nc] != [f for f in nc if f in rc]:
        problems.append("Config field ORDER differs — positional construction would break")

    if problems:
        print("no-op parity FAILED\n")
        for p in problems:
            print("  " + p)
        print(f"\n{len(problems)} problem(s)")
        return 1

    print(f"no-op parity OK — {len(real)} functions, {len(rc)} Config fields")
    return 0


if __name__ == "__main__":
    sys.exit(main())
