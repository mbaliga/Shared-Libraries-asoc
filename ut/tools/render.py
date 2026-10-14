#!/usr/bin/env python3
"""Render a ut/templates/*.in file by substituting @TOKEN@ values. A stand-in for Clickable.

Clickable substitutes @CLICK_FRAMEWORK@, @CLICK_ARCH@ and @APPARMOR_POLICY@ itself when it packages
a click. This script exists so the lint (ut/lint/ut-lint.py) can be exercised in a container that has
no Clickable, and so a consumer can dry-run their token values. It is NOT a build tool.

    render.py TEMPLATE --set KEY=VALUE [--set ...] [--policy-groups a,b,c] [--out FILE]

Refuses (exit 1) when a token in the template has no value, when a --set key names no token in the
template (a typo), or when a value would break the JSON string it lands in. @POLICY_GROUPS@ is
filled from --policy-groups and expands to a quoted, comma-separated list; an empty list is an
empty string. Exit 2 on usage errors.
"""
from __future__ import annotations

import argparse
import re
import sys

TOKEN = re.compile(r"@([A-Z][A-Z0-9_]*)@")


def render(text: str, values: dict[str, str], groups: list[str], is_json: bool) -> str:
    present = set(TOKEN.findall(text))
    unknown = sorted(set(values) - present)
    if unknown:
        raise ValueError(f"--set names token(s) not in the template: {unknown}")
    if "POLICY_GROUPS" in present:
        values = dict(values)
        values["POLICY_GROUPS"] = ",\n        ".join('"%s"' % g for g in groups)
    elif groups:
        raise ValueError("--policy-groups given but the template has no @POLICY_GROUPS@")
    missing = sorted(present - set(values))
    if missing:
        raise ValueError(f"no value for token(s): {missing}")
    for key, value in values.items():
        if is_json and key != "POLICY_GROUPS" and re.search(r'["\\\n\r\t]', value):
            raise ValueError(f"value for {key} contains a character that needs JSON escaping; pick a plain value")
    return TOKEN.sub(lambda m: values[m.group(1)], text)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("template")
    ap.add_argument("--set", action="append", default=[], metavar="KEY=VALUE")
    ap.add_argument("--policy-groups", default="")
    ap.add_argument("--out")
    try:
        args = ap.parse_args(argv)
    except SystemExit as e:
        return 2 if e.code else 0
    values: dict[str, str] = {}
    for item in args.set:
        key, sep, value = item.partition("=")
        if not sep or not TOKEN.fullmatch(f"@{key}@"):
            print(f"render: bad --set '{item}' (want KEY=VALUE, KEY upper-case)", file=sys.stderr)
            return 2
        values[key] = value
    groups = [g for g in args.policy_groups.split(",") if g]
    try:
        text = open(args.template, encoding="utf-8").read()
        out = render(text, values, groups, is_json=args.template.endswith((".json.in", ".apparmor.in")))
    except (OSError, ValueError) as e:
        print(f"render: {e}", file=sys.stderr)
        return 1
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(out)
    else:
        sys.stdout.write(out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
