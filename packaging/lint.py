#!/usr/bin/env python3
"""Lint (and optionally render) the packaging templates.

Run:   python3 -I packaging/lint.py
Emit:  python3 -I packaging/lint.py --emit OUTDIR [--values values.json]

Every template under packaging/ is listed in TEMPLATES below. The lint substitutes sample
placeholders, parses the result as its declared format (XML / YAML / JSON / plist / shell / ...)
and applies a few policy checks. Passing proves the templates are well-formed. It does NOT prove
any template builds an installer; nothing here has been run on Linux packaging tools, Windows,
macOS, iOS or Ubuntu Touch.

Placeholders are written @@NAME@@ and substituted by plain string replacement.
Self-contained on purpose: `python3 -I` does not put this directory on sys.path.
"""
import argparse
import json
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent

# placeholder -> (sample value, validation regex). Samples are obviously fake (R11: no real
# per-platform identifier is written until it has a NAMES.md row).
PLACEHOLDERS = {
    "APP_NAME": ("sampleapp", r"[a-z][a-z0-9]*"),
    "APP_TITLE": ("Sample App", r"[A-Za-z0-9][A-Za-z0-9 ]*"),
    "VERSION": ("0.0.1", r"\d+\.\d+\.\d+"),
    "BUILD_NUMBER": ("1", r"\d+"),
    "VENDOR": ("Sample Vendor", r"[A-Za-z0-9][A-Za-z0-9 ._-]*"),
    "MAINTAINER": ("Sample Maintainer <maintainer@example.invalid>", r"[A-Za-z0-9 ._-]+ <[^<>@\s]+@[^<>@\s]+>"),
    "DESCRIPTION": ("A placeholder description", r"[A-Za-z0-9][A-Za-z0-9 .,_-]*"),
    "HOMEPAGE": ("https://example.invalid/sampleapp", r"https://[A-Za-z0-9./_-]+"),
    "LICENSE_SPDX": ("Apache-2.0", r"[A-Za-z0-9.+-]+"),
    "RELEASE_DATE": ("2026-01-01", r"\d{4}-\d{2}-\d{2}"),
    "INPUT_DIR": ("build/input", r"[A-Za-z0-9./_-]+"),
    "OUT_DIR": ("build/jpackage", r"[A-Za-z0-9./_-]+"),
    "MAIN_JAR": ("sampleapp.jar", r"[A-Za-z0-9._-]+\.jar"),
    "MAIN_CLASS": ("sample.MainKt", r"[A-Za-z0-9._$]+"),
    "NFPM_ARCH": ("amd64", r"amd64|arm64"),
    "APPIMAGE_DIR": ("build/jpackage/sampleapp", r"[A-Za-z0-9./_-]+"),
    "DESKTOP_FILE": ("app.desktop", r"[A-Za-z0-9./_-]+"),
    "METAINFO_FILE": ("app.metainfo.xml", r"[A-Za-z0-9./_-]+"),
    "FLATPAK_ID": ("invalid.example.Sampleapp", r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_-]*){2,}"),
    "FLATPAK_RUNTIME_VERSION": ("00.00", r"\d+\.\d+"),
    "APPIMAGE_URL": ("https://example.invalid/sampleapp-linux.tar.gz", r"https://[A-Za-z0-9./_-]+"),
    "APPIMAGE_SHA256": ("0" * 64, r"[0-9a-f]{64}"),
    "WIX_UPGRADE_GUID": ("00000000-0000-0000-0000-000000000000", r"[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}"),
    "WINGET_ID": ("Invalid.SampleApp", r"[A-Za-z0-9]+(\.[A-Za-z0-9-]+)+"),
    "MSI_URL": ("https://example.invalid/sampleapp.msi", r"https://[A-Za-z0-9./_-]+"),
    "MSI_SHA256": ("0" * 64, r"[0-9a-f]{64}"),
    "APP_PATH": ("build/SampleApp.app", r"[A-Za-z0-9./_ -]+"),
    "ENTITLEMENTS_PATH": ("entitlements.plist", r"[A-Za-z0-9./_-]+"),
    "DMG_URL": ("https://example.invalid/sampleapp.dmg", r"https://[A-Za-z0-9./_-]+"),
    "DMG_SHA256": ("0" * 64, r"[0-9a-f]{64}"),
    "CLICK_NAME": ("sampleapp.invalid", r"[a-z0-9][a-z0-9.-]*"),
    "CLICK_ARCH": ("arm64", r"arm64|armhf|amd64|all"),
    "UT_FRAMEWORK": ("ubuntu-sdk-00.00", r"ubuntu-sdk-\d+\.\d+"),
    "APPARMOR_POLICY_VERSION": ("0.0", r"\d+\.\d+"),
}

# (template path, kind, output path). kind: text xml yaml json plist shell ruby desktop jpackage
TEMPLATES = [
    ("linux/jpackage.args.in", "jpackage", "linux/jpackage.args"),
    ("linux/nfpm.yaml.in", "yaml", "linux/nfpm.yaml"),
    ("linux/flatpak/manifest.yaml.in", "yaml", "linux/flatpak/manifest.yaml"),
    ("linux/flatpak/app.desktop.in", "desktop", "linux/flatpak/app.desktop"),
    ("linux/flatpak/app.metainfo.xml.in", "xml", "linux/flatpak/app.metainfo.xml"),
    ("linux/tarball/install.sh.in", "shell", "linux/tarball/install.sh"),
    ("windows/Product.wxs.in", "xml", "windows/Product.wxs"),
    ("windows/winget/version.yaml.in", "yaml", "windows/winget/version.yaml"),
    ("windows/winget/installer.yaml.in", "yaml", "windows/winget/installer.yaml"),
    ("windows/winget/locale.en-US.yaml.in", "yaml", "windows/winget/locale.en-US.yaml"),
    ("macos/entitlements.plist.in", "plist", "macos/entitlements.plist"),
    ("macos/Info.plist.in", "plist", "macos/Info.plist"),
    ("macos/notarize.sh.in", "shell", "macos/notarize.sh"),
    ("macos/homebrew/cask.rb.in", "ruby", "macos/homebrew/cask.rb"),
    ("ios/project.yml.in", "yaml", "ios/project.yml"),
    ("ios/PrivacyInfo.xcprivacy.in", "plist", "ios/PrivacyInfo.xcprivacy"),
    ("ubuntu-touch/clickable.yaml.in", "yaml", "ubuntu-touch/clickable.yaml"),
    ("ubuntu-touch/manifest.json.in", "json", "ubuntu-touch/manifest.json"),
    ("ubuntu-touch/apparmor.in", "json", "ubuntu-touch/apparmor.json"),
]

# Conservative reading of "common" Ubuntu Touch policy groups; unverified against OpenStore policy.
UT_COMMON_GROUPS = {"networking", "audio", "video", "camera", "microphone", "location",
                    "content_exchange", "content_exchange_source", "webview", "keep-display-on"}
HEADER = "TEMPLATE - UNSIGNED - NOT FOR RELEASE"
NO_HEADER_KINDS = {"json"}  # JSON has no comments; README carries the label
PLACEHOLDER_RE = re.compile(r"@@([A-Z0-9_]+)@@")
# I-1: nothing in a template may mention telemetry-like machinery.
FORBIDDEN_RE = re.compile(r"analytics|telemetry|crashlytics|sentry|firebase|appcenter|usermetrics", re.I)
SECRET_RE = re.compile(r"BEGIN [A-Z ]*PRIVATE KEY|\.p12\b|\.p8\b|\.mobileprovision")

try:
    import yaml  # PyYAML
except ImportError:  # pragma: no cover
    yaml = None


def safe_value(name, value):
    if name not in PLACEHOLDERS:
        raise ValueError("unknown placeholder " + name)
    if not re.fullmatch(PLACEHOLDERS[name][1], value):
        raise ValueError("value for %s fails %s: %r" % (name, PLACEHOLDERS[name][1], value))
    return value


def render(text, values):
    missing = set(PLACEHOLDER_RE.findall(text)) - set(values)
    if missing:
        raise ValueError("no value for " + ", ".join(sorted(missing)))
    return PLACEHOLDER_RE.sub(lambda m: values[m.group(1)], text)


def run_tool(argv, text, suffix):
    """Run a syntax checker on text via a temp file; return (ok, message) or None if tool absent."""
    if shutil.which(argv[0]) is None:
        return None
    with tempfile.TemporaryDirectory() as d:
        p = Path(d) / ("f" + suffix)
        p.write_text(text)
        r = subprocess.run(argv + [str(p)], capture_output=True, text=True)
        return r.returncode == 0, (r.stderr or r.stdout).strip()


def check(rel, kind, text, notes):
    """Parse rendered text; return list of error strings. notes collects SKIPPED messages."""
    errs = []
    if kind not in NO_HEADER_KINDS and HEADER not in text:
        errs.append("missing header label")
    if FORBIDDEN_RE.search(text):
        errs.append("mentions telemetry-like term (I-1)")
    if SECRET_RE.search(text):
        errs.append("looks like signing material (R5/R6)")
    if PLACEHOLDER_RE.search(text):
        errs.append("unsubstituted placeholder")
    try:
        if kind == "xml":
            ET.fromstring(text)
        elif kind == "plist":
            doc = plistlib.loads(text.encode())
            errs += plist_policy(rel, doc)
        elif kind == "json":
            doc = json.loads(text)
            errs += json_policy(rel, doc)
        elif kind == "yaml":
            if yaml is None:
                notes.append("SKIPPED %s: PyYAML not installed" % rel)
            else:
                errs += yaml_policy(rel, yaml.safe_load(text))
        elif kind == "shell":
            if not text.startswith("#!"):
                errs.append("no shebang")
            r = run_tool(["bash", "-n"], text, ".sh")
            if r is None:
                notes.append("SKIPPED %s: bash not found" % rel)
            elif not r[0]:
                errs.append("bash -n: " + r[1])
            if rel.endswith("notarize.sh") and "ASOC_NOTARIZE_ENABLE" not in text:
                errs.append("notarize.sh lost its disabled-by-default guard (R6)")
            if rel.endswith("install.sh") and re.search(r"\b(curl|wget|nc)\b", text):
                errs.append("install.sh must not use the network")
        elif kind == "ruby":
            r = run_tool(["ruby", "-c"], text, ".rb")
            if r is None:
                notes.append("SKIPPED %s: ruby not found (only checked for leftovers)" % rel)
            elif not r[0]:
                errs.append("ruby -c: " + r[1])
        elif kind == "desktop":
            keys = dict(l.split("=", 1) for l in text.splitlines() if "=" in l and not l.startswith("#"))
            for k in ("Type", "Name", "Exec"):
                if k not in keys:
                    errs.append("desktop entry lacks " + k)
            if not text.splitlines()[1:2] == ["[Desktop Entry]"]:
                errs.append("[Desktop Entry] group must follow the header comment")
        elif kind == "jpackage":
            for l in text.splitlines():
                if l.strip() and not l.startswith("#") and not l.startswith("--"):
                    errs.append("jpackage arg line must start with --: " + l)
    except Exception as e:  # parse failure is the finding
        errs.append("parse error: %s: %s" % (type(e).__name__, e))
    return errs


def plist_policy(rel, doc):
    errs = []
    if rel.endswith("PrivacyInfo.xcprivacy"):
        if doc.get("NSPrivacyTracking") is not False:
            errs.append("NSPrivacyTracking must be false")
        if doc.get("NSPrivacyTrackingDomains") != [] or doc.get("NSPrivacyCollectedDataTypes") != []:
            errs.append("tracking domains and collected data must be empty")
    if rel.endswith("Info.plist") and not re.fullmatch(r"com\.asoc\.[a-z][a-z0-9]*", str(doc.get("CFBundleIdentifier"))):
        errs.append("CFBundleIdentifier must be com.asoc.<appname>")
    if rel.endswith("entitlements.plist"):
        for k, v in doc.items():
            if not isinstance(v, (bool, list)):
                errs.append("entitlement %s has unexpected type" % k)
    return errs


def json_policy(rel, doc):
    errs = []
    if rel.endswith("apparmor.json"):
        groups = set(doc.get("policy_groups", []))
        if groups - UT_COMMON_GROUPS:
            errs.append("non-common policy groups: " + ", ".join(sorted(groups - UT_COMMON_GROUPS)))
    if rel.endswith("manifest.json"):
        for k in ("name", "version", "framework", "architecture", "hooks"):
            if k not in doc:
                errs.append("manifest missing " + k)
    return errs


def yaml_policy(rel, doc):
    errs = []
    if rel.endswith("ios/project.yml"):
        base = doc.get("settings", {}).get("base", {})
        if not str(base.get("DEVELOPMENT_TEAM", "")).startswith("${"):
            errs.append("DEVELOPMENT_TEAM must come from the environment, not the repo")
        if str(base.get("CODE_SIGNING_ALLOWED")) != "NO":
            errs.append("CODE_SIGNING_ALLOWED must default to NO")
        for t in doc.get("targets", {}).values():
            bid = t.get("settings", {}).get("base", {}).get("PRODUCT_BUNDLE_IDENTIFIER", "")
            if not re.fullmatch(r"com\.asoc\.[a-z][a-z0-9]*", bid):
                errs.append("bundle id must be com.asoc.<appname>")
    if rel.endswith("flatpak/manifest.yaml"):
        for m in doc.get("modules", []):
            for s in m.get("sources", []):
                if s.get("type") == "archive" and "sha256" not in s:
                    errs.append("archive source without sha256 (Flathub offline rule)")
        if "--share=network" in doc.get("finish-args", []):
            errs.append("network permission must be added per app, not in the template")
    return errs


def lint_all(values, out_dir=None):
    errors, notes = [], []
    listed = {t[0] for t in TEMPLATES}
    for p in sorted(ROOT.rglob("*.in")):
        rel = p.relative_to(ROOT).as_posix()
        if rel not in listed:
            errors.append("%s: template not listed in TEMPLATES" % rel)
    used = set()
    for src, kind, dst in TEMPLATES:
        p = ROOT / src
        if not p.is_file():
            errors.append("%s: missing" % src)
            continue
        raw = p.read_text()
        used |= set(PLACEHOLDER_RE.findall(raw))
        unknown = set(PLACEHOLDER_RE.findall(raw)) - set(PLACEHOLDERS)
        if unknown:
            errors.append("%s: undeclared placeholders %s" % (src, sorted(unknown)))
            continue
        try:
            text = render(raw, values)
        except ValueError as e:
            errors.append("%s: %s" % (src, e))
            continue
        for e in check(dst, kind, text, notes):
            errors.append("%s: %s" % (src, e))
        if out_dir is not None:
            o = Path(out_dir) / dst
            o.parent.mkdir(parents=True, exist_ok=True)
            o.write_text(text)
            if kind == "shell":
                o.chmod(0o755)
    for name in sorted(set(PLACEHOLDERS) - used):
        notes.append("note: placeholder %s is declared but unused" % name)
    return errors, notes


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--emit", metavar="OUTDIR", help="write rendered files here (must not be inside packaging/)")
    ap.add_argument("--values", metavar="JSON", help="JSON object of placeholder values (overrides samples)")
    a = ap.parse_args()
    values = {k: v[0] for k, v in PLACEHOLDERS.items()}
    if a.values:
        user = json.loads(Path(a.values).read_text())
        try:
            for k, v in user.items():
                values[k] = safe_value(k, str(v))
        except ValueError as e:
            print("values rejected: %s" % e, file=sys.stderr)
            return 2
    elif a.emit:
        print("--emit without --values would write sample placeholder values; pass --values", file=sys.stderr)
        return 2
    out = None
    if a.emit:
        out = Path(a.emit).resolve()
        if ROOT in out.parents or out == ROOT:
            print("refusing to emit inside packaging/", file=sys.stderr)
            return 2
    errors, notes = lint_all(values, out)
    for n in notes:
        print(n)
    for e in errors:
        print("ERROR " + e)
    print("%d templates, %d errors" % (len(TEMPLATES), len(errors)))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
