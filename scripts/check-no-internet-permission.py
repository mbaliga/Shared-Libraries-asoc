#!/usr/bin/env python3
"""
Assert that :diagnostics-android's MERGED manifest — debug and release variants both — never
carries a <uses-permission> element, INTERNET or otherwise.

Why this exists: the README and DIAGNOSTICS_MODULE_SPEC.md both claim "no network permission,
ever — verifiable in the merged manifest" as the load-bearing part of the module's privacy
story (this suite ingests metrics from a privacy-first keyboard). A claim that is only verified
by a human reading the source AndroidManifest.xml is not verified at all: the MERGED manifest is
what actually ships, and AGP manifest merging can introduce a permission from a dependency's own
manifest that never appears in this module's source file. So this checks the merged output,
which requires the module to have actually been assembled first — checking the unmerged source
manifest would miss exactly the failure mode this guard exists to catch.

Usage:
    ./gradlew :diagnostics-android:assembleDebug :diagnostics-android:assembleRelease
    python3 scripts/check-no-internet-permission.py     (exit 1 on any <uses-permission>)
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

MANIFESTS = {
    "debug": ROOT / "diagnostics-android/build/intermediates/merged_manifest/debug"
                     "/processDebugManifest/AndroidManifest.xml",
    "release": ROOT / "diagnostics-android/build/intermediates/merged_manifest/release"
                       "/processReleaseManifest/AndroidManifest.xml",
}


def permissions_in(path: Path) -> list[str]:
    root = ET.parse(path).getroot()
    names = []
    for tag in ("uses-permission", "uses-permission-sdk-23", "permission"):
        for el in root.findall(tag):
            names.append(el.get(f"{ANDROID_NS}name", "<unnamed>"))
    return names


def main() -> int:
    problems = []
    missing = []

    for variant, path in MANIFESTS.items():
        if not path.exists():
            missing.append(variant)
            continue
        perms = permissions_in(path)
        if perms:
            problems.append(f"{variant} merged manifest declares permission(s): {', '.join(perms)}")

    if missing:
        print("check-no-internet-permission: merged manifest not found for: " + ", ".join(missing))
        print("Run first:  ./gradlew :diagnostics-android:assembleDebug :diagnostics-android:assembleRelease")
        return 1

    if problems:
        print("no-internet-permission check FAILED\n")
        for p in problems:
            print("  " + p)
        return 1

    print(f"no-internet-permission check OK — 0 permissions in {len(MANIFESTS)} merged manifest(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
