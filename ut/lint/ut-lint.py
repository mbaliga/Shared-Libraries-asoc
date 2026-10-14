#!/usr/bin/env python3
"""Lint a RENDERED Ubuntu Touch manifest.json / apparmor pair, or the templates they come from.

PORTING_PLAN.md F7 enabler kit. Plain Python 3 standard library; no network; no Clickable.

    ut-lint.py --manifest manifest.json --apparmor app.apparmor [--framework F] [--policy-version V]
    ut-lint.py --templates ut/templates
    ut-lint.py --self-test            (see also ut/lint/selftest.sh, which drives the real templates)

Rendered pair (what a click would contain). ERRORS, any of which exits 1:
  json          either file is not valid JSON, is not an object, or repeats an object key
                (duplicate keys silently override each other in most JSON readers)
  token         an unrendered @TOKEN@ is left anywhere in either file
  manifest      a required key is missing or malformed: name, title, version, description,
                maintainer, framework, hooks; `architecture`, if present, must be one of
                all / armhf / arm64 / amd64 (or a list of them)
  framework     the framework is not the expected one (default: ubuntu-touch-24.04-1.x)
  hooks         no hook, or a hook without string `apparmor` and `desktop` entries ending in
                .apparmor / .desktop, or no hook whose `apparmor` entry names the AppArmor file given
  apparmor      a top-level key other than policy_groups / policy_version (this kit allows
                common policy groups ONLY: no template, no read_path / write_path), a policy
                group that is not a unique string, or a missing / malformed policy_version
  policy-group  a group outside the COMMON allowlist below. RESERVED groups (need manual OpenStore
                review) are named as such; anything else is "unknown".
NOTES (printed, never fail): groups that widen the network or telemetry posture
(networking, push-notification-client, usermetrics) - constellation rule I-1 (no telemetry) means a
consumer should be able to say why it asks for them - and hook keys this lint does not check.

The COMMON / RESERVED lists are the UBports AppArmor documentation page's two classes as read on
2026-10-14 (https://docs.ubports.com/en/latest/appdev/platform/apparmor.html), fetched through a
summarising tool: re-confirm against the page before relying on a list. The rendered numeric value
Clickable substitutes for @APPARMOR_POLICY@ was NOT verified, so policy_version is checked for SHAPE
only (a string or number like "1.0" / 24.04); pin it with --policy-version once observed.

Templates (--templates DIR). Rule R11: no app identifier in any manifest before a NAMES.md row
exists. The templates must hold TOKENS ONLY wherever a name could go:
  - manifest.json.in is valid JSON once every token is replaced by a plain value, every string value
    is made of tokens plus bare punctuation or the .apparmor / .desktop suffix, and every object key is
    a structural key or a token; framework / architecture are the Clickable-owned tokens;
  - app.apparmor.in is valid JSON once @POLICY_GROUPS@ is replaced by a string, with policy_version the
    Clickable-owned token;
  - clickable.yaml.in carries the literal framework ubuntu-touch-24.04-1.x, and its `kill:` is a token.

Exit: 0 clean, 1 findings, 2 usage.
"""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import re
import sys
import tempfile
from pathlib import Path

DEFAULT_FRAMEWORK = "ubuntu-touch-24.04-1.x"
ARCHITECTURES = {"all", "armhf", "arm64", "amd64"}

COMMON_GROUPS = {
    "accounts", "audio", "camera", "connectivity", "content_exchange", "content_exchange_source",
    "keep-display-on", "location", "microphone", "networking", "nfc", "push-notification-client",
    "sensors", "usermetrics", "video", "webview",
}
RESERVED_GROUPS = {
    "bluetooth", "calendar", "contacts", "debug", "document_files", "document_files_read", "history",
    "music_files", "music_files_read", "picture_files", "picture_files_read", "video_files",
    "video_files_read",
}
POSTURE_NOTE_GROUPS = {"networking", "push-notification-client", "usermetrics"}
MANIFEST_REQUIRED = ["name", "title", "version", "description", "maintainer", "framework", "hooks"]
KNOWN_HOOK_KEYS = {"apparmor", "desktop"}

TOKEN = re.compile(r"@[A-Z][A-Z0-9_]*@")
NAME_RE = re.compile(r"^[a-z0-9][a-z0-9+.\-]*$")
VERSION_RE = re.compile(r"^[0-9][A-Za-z0-9.+~\-]*$")
MAINTAINER_RE = re.compile(r"^[^<>@]+ <[^<>@\s]+@[^<>@\s]+>$")
POLICY_VERSION_RE = re.compile(r"^[0-9]+(\.[0-9]+)*$")


class Findings:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.notes: list[str] = []

    def error(self, kind: str, msg: str) -> None:
        self.errors.append(f"{kind}: {msg}")

    def note(self, msg: str) -> None:
        self.notes.append(msg)

    def report(self) -> int:
        for n in self.notes:
            print("note:", n)
        for e in self.errors:
            print("FAIL", e)
        if self.errors:
            print(f"ut-lint: {len(self.errors)} finding(s)", file=sys.stderr)
            return 1
        print("ut-lint: OK")
        return 0


def _no_duplicates(pairs: list[tuple[str, object]]) -> dict:
    out: dict = {}
    for k, v in pairs:
        if k in out:
            raise ValueError(f"duplicate key {k!r}")
        out[k] = v
    return out


def load_json(path: Path, f: Findings, what: str):
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as e:
        f.error("json", f"{what} {path}: cannot read ({e})")
        return None, ""
    try:
        data = json.loads(text, object_pairs_hook=_no_duplicates)
    except (ValueError, RecursionError) as e:
        f.error("json", f"{what} {path}: {e}")
        return None, text
    if not isinstance(data, dict):
        f.error("json", f"{what} {path}: top level is not an object")
        return None, text
    return data, text


def lint_pair(manifest: Path, apparmor: Path, framework: str, policy_version: str | None) -> int:
    f = Findings()
    m, m_text = load_json(manifest, f, "manifest")
    a, a_text = load_json(apparmor, f, "apparmor")
    for what, text in (("manifest", m_text), ("apparmor", a_text)):
        for tok in sorted(set(TOKEN.findall(text))):
            f.error("token", f"{what} still contains the unrendered token {tok}")

    if m is not None:
        for key in MANIFEST_REQUIRED:
            if key not in m:
                f.error("manifest", f"missing required key {key!r}")
        for key in ("name", "title", "version", "description", "maintainer", "framework"):
            if key in m and (not isinstance(m[key], str) or not m[key].strip()):
                f.error("manifest", f"{key!r} must be a non-empty string")
        if isinstance(m.get("name"), str) and m["name"].strip() and not NAME_RE.match(m["name"]):
            f.error("manifest", f"name {m['name']!r} is not a valid click package name (lower-case letters, digits, + . -)")
        if isinstance(m.get("version"), str) and m["version"].strip() and not VERSION_RE.match(m["version"]):
            f.error("manifest", f"version {m['version']!r} is not a valid click version")
        if isinstance(m.get("maintainer"), str) and m["maintainer"].strip() and not MAINTAINER_RE.match(m["maintainer"]):
            f.error("manifest", f"maintainer {m['maintainer']!r} should look like 'Name <address>'")
        if isinstance(m.get("framework"), str) and m["framework"] != framework:
            f.error("framework", f"{m['framework']!r} is not the expected {framework!r}")
        if "architecture" in m:
            arch = m["architecture"]
            archs = arch if isinstance(arch, list) else [arch]
            if not archs or not all(isinstance(x, str) and x in ARCHITECTURES for x in archs):
                f.error("manifest", f"architecture {arch!r} must be one of {sorted(ARCHITECTURES)} (or a list of them)")
        hooks = m.get("hooks")
        pair_named = False
        if "hooks" in m:
            if not isinstance(hooks, dict) or not hooks:
                f.error("hooks", "'hooks' must be a non-empty object")
            else:
                for hook, entry in hooks.items():
                    if not isinstance(entry, dict):
                        f.error("hooks", f"hook {hook!r} must be an object")
                        continue
                    for need, suffix in (("apparmor", ".apparmor"), ("desktop", ".desktop")):
                        v = entry.get(need)
                        if not isinstance(v, str) or not v.endswith(suffix):
                            f.error("hooks", f"hook {hook!r} needs a string {need!r} entry ending in {suffix}")
                    extra = sorted(set(entry) - KNOWN_HOOK_KEYS)
                    if extra:
                        f.note(f"hook {hook!r} has key(s) {extra} that this lint does not check")
                    v = entry.get("apparmor")
                    if isinstance(v, str) and Path(v).name == apparmor.name:
                        pair_named = True
                if not pair_named:
                    f.error("hooks", f"no hook's 'apparmor' entry names the file {apparmor.name!r}; the pair does not belong together")

    if a is not None:
        extra = sorted(set(a) - {"policy_groups", "policy_version"})
        if extra:
            f.error("apparmor", f"key(s) {extra} go beyond common policy groups (no template, read_path or write_path in this kit)")
        groups = a.get("policy_groups")
        if "policy_groups" not in a or not isinstance(groups, list) or not all(isinstance(g, str) for g in groups):
            f.error("apparmor", "'policy_groups' must be a list of strings (it may be empty)")
        else:
            if len(set(groups)) != len(groups):
                f.error("apparmor", "'policy_groups' repeats a group")
            for g in groups:
                if g in COMMON_GROUPS:
                    if g in POSTURE_NOTE_GROUPS:
                        f.note(f"policy group {g!r}: constellation rule I-1 (no telemetry) means the consumer should be able to say why it needs this")
                elif g in RESERVED_GROUPS:
                    f.error("policy-group", f"{g!r} is RESERVED (manual OpenStore review); this kit allows common groups only")
                else:
                    f.error("policy-group", f"{g!r} is not in the common allowlist")
        pv = a.get("policy_version")
        if "policy_version" not in a:
            f.error("apparmor", "missing 'policy_version'")
        elif isinstance(pv, bool) or not isinstance(pv, (str, int, float)) or not POLICY_VERSION_RE.match(str(pv)):
            f.error("apparmor", f"policy_version {pv!r} must be a number like 24.04 or a string like '1.0'")
        elif policy_version is not None and str(pv) != policy_version:
            f.error("apparmor", f"policy_version {pv!r} is not the pinned {policy_version!r}")
    return f.report()


# ----------------------------------------------------------------------------------------------------
# templates (rule R11)
# ----------------------------------------------------------------------------------------------------
STRUCTURAL_KEYS = {"name", "title", "version", "description", "maintainer", "framework", "architecture",
                   "hooks", "apparmor", "desktop"}
SUFFIX_OK = re.compile(r"^[\s.\-_/]*(\.apparmor|\.desktop)?$")


def _strings(node, path=""):
    """Yield (kind, path, string) for every key and string value of a parsed JSON tree."""
    if isinstance(node, dict):
        for k, v in node.items():
            yield "key", path, k
            yield from _strings(v, f"{path}/{k}")
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from _strings(v, f"{path}[{i}]")
    elif isinstance(node, str):
        yield "value", path, node


def lint_templates(directory: Path) -> int:
    f = Findings()
    files = {n: directory / n for n in ("manifest.json.in", "app.apparmor.in", "clickable.yaml.in")}
    for n, p in files.items():
        if not p.is_file():
            f.error("template", f"{n} is missing from {directory}")
    if f.errors:
        return f.report()

    # manifest.json.in -----------------------------------------------------------------------
    text = files["manifest.json.in"].read_text(encoding="utf-8")
    try:
        tok_tree = json.loads(text, object_pairs_hook=_no_duplicates)
    except ValueError as e:
        f.error("template", f"manifest.json.in is not valid JSON as written (tokens may only sit inside strings): {e}")
        tok_tree = None
    if tok_tree is not None:
        for kind, path, s in _strings(tok_tree):
            residue = TOKEN.sub("", s)
            if kind == "key":
                if s not in STRUCTURAL_KEYS and not TOKEN.fullmatch(s):
                    f.error("R11", f"manifest.json.in has the literal key {s!r} at {path or '/'}; a name belongs in a token")
            elif not SUFFIX_OK.match(residue):
                f.error("R11", f"manifest.json.in value at {path} carries literal text {residue!r}; only @TOKENS@ may name anything")
        if isinstance(tok_tree, dict):
            for key, want in (("framework", "@CLICK_FRAMEWORK@"), ("architecture", "@CLICK_ARCH@")):
                if tok_tree.get(key) != want:
                    f.error("template", f"manifest.json.in {key!r} must be the Clickable-owned token {want}, found {tok_tree.get(key)!r}")
            if not TOKEN.search(json.dumps(tok_tree.get("hooks", {}))):
                f.error("template", "manifest.json.in hooks carry no token (a literal hook name is an identifier)")

    # app.apparmor.in ------------------------------------------------------------------------
    text = files["app.apparmor.in"].read_text(encoding="utf-8")
    try:
        probe = json.loads(text.replace("@POLICY_GROUPS@", '"x"'), object_pairs_hook=_no_duplicates)
    except ValueError as e:
        f.error("template", f"app.apparmor.in is not valid JSON once @POLICY_GROUPS@ is replaced: {e}")
        probe = None
    if probe is not None:
        if probe.get("policy_version") != "@APPARMOR_POLICY@":
            f.error("template", f"app.apparmor.in policy_version must be the Clickable-owned token @APPARMOR_POLICY@, found {probe.get('policy_version')!r}")
        if set(probe) != {"policy_groups", "policy_version"}:
            f.error("template", f"app.apparmor.in keys must be exactly policy_groups and policy_version, found {sorted(probe)}")
        if "@POLICY_GROUPS@" not in text:
            f.error("template", "app.apparmor.in must take its groups from @POLICY_GROUPS@ (no hard-coded group)")

    # clickable.yaml.in ----------------------------------------------------------------------
    keys: dict[str, str] = {}
    for line in files["clickable.yaml.in"].read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        k, sep, v = line.partition(":")
        if sep and not line.startswith((" ", "\t")):
            keys[k.strip()] = v.strip()
    if keys.get("framework") != DEFAULT_FRAMEWORK:
        f.error("template", f"clickable.yaml.in framework must be {DEFAULT_FRAMEWORK!r}, found {keys.get('framework')!r}")
    for ident_key in ("kill", "builder"):
        v = keys.get(ident_key)
        if v is None:
            f.error("template", f"clickable.yaml.in has no {ident_key!r} line")
        elif not TOKEN.fullmatch(v):
            f.error("R11", f"clickable.yaml.in {ident_key}: {v!r} is a literal; it must be an @TOKEN@")
    return f.report()


# ----------------------------------------------------------------------------------------------------
# self-test
# ----------------------------------------------------------------------------------------------------
GOOD_MANIFEST = {
    "name": "lint-fixture.invalid",
    "title": "Lint fixture",
    "version": "0.0.1",
    "description": "A synthetic manifest used only to test the lint.",
    "maintainer": "Fixture Maintainer <maintainer@example.invalid>",
    "framework": DEFAULT_FRAMEWORK,
    "architecture": "arm64",
    "hooks": {"fixture": {"apparmor": "fixture.apparmor", "desktop": "fixture.desktop"}},
}
GOOD_APPARMOR = {"policy_groups": ["audio", "content_exchange"], "policy_version": "1.0"}


def self_test() -> int:
    failures = 0

    def quiet(fn, *a):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(io.StringIO()):
            code = fn(*a)
        return code, buf.getvalue()

    def check(label: str, code_out, want_ok: bool, needle: str = "") -> None:
        nonlocal failures
        code, out = code_out
        ok = (code == 0) == want_ok and needle in out
        print(("ok   - " if ok else "FAIL - ") + label)
        if not ok:
            failures += 1
            print("       " + out.replace("\n", "\n       "))

    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)

        def pair(manifest=None, apparmor=None, raw_m=None, raw_a=None, fw=DEFAULT_FRAMEWORK, pv=None):
            m, a = dict(GOOD_MANIFEST), dict(GOOD_APPARMOR)
            if manifest:
                m.update(manifest)
            if apparmor:
                a.update(apparmor)
            mp, ap = tmp / "manifest.json", tmp / "fixture.apparmor"
            mp.write_text(raw_m if raw_m is not None else json.dumps(m), encoding="utf-8")
            ap.write_text(raw_a if raw_a is not None else json.dumps(a), encoding="utf-8")
            return quiet(lint_pair, mp, ap, fw, pv)

        check("a good pair passes", pair(), True, "ut-lint: OK")
        check("a good pair with no groups passes", pair(apparmor={"policy_groups": []}), True)
        check("a numeric policy_version passes", pair(apparmor={"policy_version": 24.04}), True)
        check("networking passes with an I-1 note", pair(apparmor={"policy_groups": ["networking"]}), True, "constellation rule I-1")

        check("invalid manifest JSON fails", pair(raw_m="{ nope"), False, "json: manifest")
        check("invalid apparmor JSON fails", pair(raw_a="[1,2"), False, "json: apparmor")
        check("a non-object manifest fails", pair(raw_m="[]"), False, "top level is not an object")
        check("a duplicate manifest key fails", pair(raw_m='{"name": "a", "name": "b"}'), False, "duplicate key")
        check("an unrendered token fails", pair(manifest={"title": "@APP_TITLE@"}), False, "unrendered token @APP_TITLE@")
        check("a token in the apparmor file fails", pair(apparmor={"policy_version": "@APPARMOR_POLICY@"}), False, "unrendered token @APPARMOR_POLICY@")
        check("the wrong framework fails", pair(manifest={"framework": "ubuntu-sdk-20.04"}), False, "framework:")
        check("--framework accepts an override", pair(manifest={"framework": "ubuntu-sdk-20.04"}, fw="ubuntu-sdk-20.04"), True)
        bad_missing = dict(GOOD_MANIFEST); del bad_missing["maintainer"]
        check("a missing manifest key fails", pair(raw_m=json.dumps(bad_missing)), False, "missing required key 'maintainer'")
        check("a bad package name fails", pair(manifest={"name": "Bad Name"}), False, "not a valid click package name")
        check("a bad maintainer fails", pair(manifest={"maintainer": "just a name"}), False, "should look like")
        check("a bad architecture fails", pair(manifest={"architecture": "x86"}), False, "architecture")
        check("an empty hooks object fails", pair(manifest={"hooks": {}}), False, "non-empty object")
        check("a hook without desktop fails", pair(manifest={"hooks": {"h": {"apparmor": "fixture.apparmor"}}}), False, "'desktop'")
        check("a hook naming another apparmor file fails", pair(manifest={"hooks": {"h": {"apparmor": "other.apparmor", "desktop": "h.desktop"}}}), False, "does not belong together")
        check("an unknown hook key is only a note", pair(manifest={"hooks": {"fixture": {"apparmor": "fixture.apparmor", "desktop": "f.desktop", "urls": "u.json"}}}), True, "does not check")

        check("a reserved group fails", pair(apparmor={"policy_groups": ["contacts"]}), False, "RESERVED")
        check("an unknown group fails", pair(apparmor={"policy_groups": ["unconfined"]}), False, "not in the common allowlist")
        check("a repeated group fails", pair(apparmor={"policy_groups": ["audio", "audio"]}), False, "repeats")
        check("a template key fails", pair(apparmor={"template": "unconfined"}), False, "beyond common policy groups")
        check("a read_path key fails", pair(apparmor={"read_path": ["/"]}), False, "read_path")
        check("a non-list policy_groups fails", pair(apparmor={"policy_groups": "audio"}), False, "must be a list")
        check("a missing policy_version fails", pair(raw_a=json.dumps({"policy_groups": []})), False, "missing 'policy_version'")
        check("a malformed policy_version fails", pair(apparmor={"policy_version": "latest"}), False, "policy_version")
        check("--policy-version pins the value", pair(pv="24.04"), False, "not the pinned")

        # templates --------------------------------------------------------------------------
        real = Path(__file__).resolve().parent.parent / "templates"
        if real.is_dir():
            check("the checked-in templates pass the R11 check", quiet(lint_templates, real), True, "ut-lint: OK")
            for name, old, new, needle in [
                ("manifest.json.in", '"@CLICK_NAME@"', '"some.real-app"', "R11"),
                ("manifest.json.in", '"@APP_MAINTAINER@"', '"A Person <a@b.c>"', "R11"),
                ("manifest.json.in", '"@APP_HOOK@": {', '"realhook": {', "R11"),
                ("manifest.json.in", '"@CLICK_FRAMEWORK@"', '"ubuntu-touch-24.04-1.x"', "Clickable-owned token"),
                ("manifest.json.in", '"description"', '"descriptionx"', "R11"),
                ("app.apparmor.in", "@POLICY_GROUPS@", '"networking"', "must take its groups"),
                ("app.apparmor.in", '"@APPARMOR_POLICY@"', '"24.04"', "Clickable-owned token"),
                ("clickable.yaml.in", "kill: @APP_EXEC_NAME@", "kill: realapp", "R11"),
                ("clickable.yaml.in", "framework: ubuntu-touch-24.04-1.x", "framework: ubuntu-sdk-20.04", "framework must be"),
            ]:
                work = tmp / f"tpl-{name}-{abs(hash((old, new)))}"
                work.mkdir()
                for n in ("manifest.json.in", "app.apparmor.in", "clickable.yaml.in"):
                    (work / n).write_text((real / n).read_text(encoding="utf-8"), encoding="utf-8")
                text = (work / name).read_text(encoding="utf-8")
                assert old in text, (name, old)
                (work / name).write_text(text.replace(old, new, 1), encoding="utf-8")
                check(f"template mutation {old!r} -> {new!r} is caught", quiet(lint_templates, work), False, needle)
            empty = tmp / "empty"; empty.mkdir()
            check("missing templates are reported", quiet(lint_templates, empty), False, "is missing")
        else:
            print("skip - templates directory not found; template checks not exercised")
            failures += 1

    if failures:
        print(f"ut-lint self-test: {failures} failure(s)", file=sys.stderr)
        return 1
    print("ut-lint self-test: all expectations held")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--manifest", type=Path)
    ap.add_argument("--apparmor", type=Path)
    ap.add_argument("--framework", default=DEFAULT_FRAMEWORK)
    ap.add_argument("--policy-version")
    ap.add_argument("--templates", type=Path)
    ap.add_argument("--self-test", action="store_true")
    try:
        args = ap.parse_args(argv)
    except SystemExit as e:
        return 2 if e.code else 0
    if args.self_test:
        return self_test()
    if args.templates:
        return lint_templates(args.templates)
    if args.manifest and args.apparmor:
        return lint_pair(args.manifest, args.apparmor, args.framework, args.policy_version)
    ap.print_usage(sys.stderr)
    print("ut-lint: give --manifest and --apparmor, or --templates DIR, or --self-test", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
