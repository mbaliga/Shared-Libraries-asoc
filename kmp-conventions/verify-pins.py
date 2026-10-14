#!/usr/bin/env python3
"""Check the kmp-conventions pin catalogs against the owner's rulings, and that nothing applies them.

PORTING_PLAN.md F5. The two catalogs under kmp-conventions/catalogs/ are DATA that records two
owner rulings. This script makes a silent edit of that data, or a smuggled extra pin, a red build:

  kmp-non-ios.versions.toml   OQ-17 (2026-10-06, "B: staged pin"): Kotlin 2.1.20, Compose Multiplatform 1.8.2
  kmp-ios.versions.toml       OQ-38 (2026-10-07): Kotlin 2.2.21, Compose Multiplatform 1.9.3, Xcode 26.x

Checks (all must hold):
  catalogs   both files parse; only the [versions] and [plugins] tables exist (no [libraries] or
             [bundles]); the [versions] keys and values are EXACTLY the ruled ones (so an `agp`,
             `gradle` or any other pin cannot slip in); the [plugins] are exactly the three allowed
             ids, each tied to the right version.ref; the two files differ in Kotlin and in Compose.
  snippet    the Apple-target guard shown in README.md (between its `// BEGIN convention` and
             `// END convention` lines) is character-for-character the code in
             harness/inline/build.gradle.kts, which harness/run.sh configures and tests.
  tripwires  the conversions are still blocked. The OQ-17 "Also" approvals (converting :cell-shell,
             :crash-recovery, ... to kotlin("multiplatform")) are UNANSWERED, so: this repo's
             gradle/libs.versions.toml still pins Kotlin 2.1.0, and no file outside kmp-conventions/
             among settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml and */build.gradle.kts
             mentions `multiplatform`, `kmpPins` or `kmp-conventions`.
             If the owner approves a conversion, remove or relax the matching tripwire IN THE SAME
             CHANGE that cites the approval - the failure is the point.

Usage:  python3 -I kmp-conventions/verify-pins.py [--repo-root DIR] [--self-test]
Exit:   0 ok, 1 findings, 2 usage.
"""
from __future__ import annotations

import argparse
import re
import shutil
import sys
import tempfile
import tomllib
from pathlib import Path

RULED_VERSIONS = {
    "kmp-non-ios.versions.toml": {"kotlin": "2.1.20", "compose-multiplatform": "1.8.2"},
    "kmp-ios.versions.toml": {"kotlin": "2.2.21", "compose-multiplatform": "1.9.3", "xcode-major": "26"},
}
RULING_OF = {"kmp-non-ios.versions.toml": "OQ-17 option B", "kmp-ios.versions.toml": "OQ-38"}
RULED_PLUGINS = {
    "kotlin-multiplatform": {"id": "org.jetbrains.kotlin.multiplatform", "version": {"ref": "kotlin"}},
    "kotlin-plugin-compose": {"id": "org.jetbrains.kotlin.plugin.compose", "version": {"ref": "kotlin"}},
    "compose-multiplatform": {"id": "org.jetbrains.compose", "version": {"ref": "compose-multiplatform"}},
}
REPO_KOTLIN = "2.1.0"  # gradle/libs.versions.toml; moves only with Hyle (D-Q lockstep), not here
TRIPWIRE_FILES = ["settings.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml"]
TRIPWIRE_RE = re.compile(r"multiplatform|kmpPins|kmp-conventions")


def check_catalogs(conv: Path) -> list[str]:
    out: list[str] = []
    parsed: dict[str, dict] = {}
    for name, ruled in RULED_VERSIONS.items():
        path = conv / "catalogs" / name
        if not path.is_file():
            out.append(f"catalogs: {name} is missing")
            continue
        try:
            data = tomllib.loads(path.read_text(encoding="utf-8"))
        except tomllib.TOMLDecodeError as e:
            out.append(f"catalogs: {name} is not valid TOML: {e}")
            continue
        parsed[name] = data
        extra_tables = sorted(set(data) - {"versions", "plugins"})
        if extra_tables:
            out.append(f"catalogs: {name} has tables beyond [versions]/[plugins]: {extra_tables}")
        versions = data.get("versions", {})
        if versions != ruled:
            out.append(f"catalogs: {name} [versions] is {versions}, the {RULING_OF[name]} ruling is exactly {ruled}")
        if data.get("plugins", {}) != RULED_PLUGINS:
            out.append(f"catalogs: {name} [plugins] differs from the three ruled plugin entries {sorted(RULED_PLUGINS)}")
    if len(parsed) == 2:
        a, b = (parsed[n].get("versions", {}) for n in RULED_VERSIONS)
        if a.get("kotlin") == b.get("kotlin") or a.get("compose-multiplatform") == b.get("compose-multiplatform"):
            out.append("catalogs: the two pin sets share a Kotlin or Compose version; they are two different rulings")
    return out


def _between_markers(path: Path) -> list[str] | None:
    """Stripped lines strictly between `// BEGIN convention` and `// END convention`, or None."""
    if not path.is_file():
        return None
    lines = path.read_text(encoding="utf-8").splitlines()
    begin = [i for i, l in enumerate(lines) if l.strip().startswith("// BEGIN convention")]
    end = [i for i, l in enumerate(lines) if l.strip() == "// END convention"]
    if len(begin) != 1 or len(end) != 1 or end[0] <= begin[0]:
        return None
    return [l.strip() for l in lines[begin[0] + 1 : end[0]] if l.strip()]


def check_snippet(conv: Path) -> list[str]:
    readme = _between_markers(conv / "README.md")
    harness = _between_markers(conv / "harness" / "inline" / "build.gradle.kts")
    out: list[str] = []
    if readme is None:
        out.append("snippet: README.md must contain exactly one `// BEGIN convention` ... `// END convention` block")
    if harness is None:
        out.append("snippet: harness/inline/build.gradle.kts must contain exactly one `// BEGIN convention` ... `// END convention` block")
    if readme is not None and harness is not None:
        if not readme or readme != harness:
            out.append("snippet: the README's guard lines differ from the harness's; the documented convention is no longer the one that was run")
        if not any("HostManager.hostIsMac" in l for l in readme):
            out.append("snippet: the convention no longer guards on HostManager.hostIsMac")
    return out


def check_tripwires(conv: Path, repo: Path) -> list[str]:
    out: list[str] = []
    libs = repo / "gradle" / "libs.versions.toml"
    if libs.is_file():
        kotlin = tomllib.loads(libs.read_text(encoding="utf-8")).get("versions", {}).get("kotlin")
        if kotlin != REPO_KOTLIN:
            out.append(
                f"tripwire: gradle/libs.versions.toml pins Kotlin {kotlin!r}, expected {REPO_KOTLIN!r}. "
                "This repo moves only together with Hyle (D-Q); a bump needs the owner's OQ-17 'Also' approval."
            )
    files = [repo / f for f in TRIPWIRE_FILES] + sorted(repo.glob("*/build.gradle.kts"))
    for f in files:
        if not f.is_file() or conv in f.parents:
            continue
        for lineno, line in enumerate(f.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if TRIPWIRE_RE.search(line):
                out.append(
                    f"tripwire: {f.relative_to(repo)}:{lineno} mentions the KMP conversion or conventions "
                    f"({line.strip()[:70]!r}). Converting modules needs the OQ-17 'Also' approvals; "
                    "relax this tripwire in the change that cites them."
                )
    return out


def run(repo: Path) -> int:
    conv = repo / "kmp-conventions"
    findings = check_catalogs(conv) + check_snippet(conv) + check_tripwires(conv, repo)
    for f in findings:
        print("FAIL", f)
    if findings:
        print(f"verify-pins: {len(findings)} finding(s)", file=sys.stderr)
        return 1
    print("verify-pins: OK (non-iOS 2.1.20/1.8.2, iOS 2.2.21/1.9.3, no conversion applied)")
    return 0


# ---------------------------------------------------------------------------------------------------
# self-test: copy the real catalogs into throw-away repos and break them one way at a time
# ---------------------------------------------------------------------------------------------------
def self_test(real_repo: Path) -> int:
    failures = 0

    def expect(label: str, repo: Path, want_ok: bool, needle: str = "") -> None:
        nonlocal failures
        import contextlib
        import io

        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(io.StringIO()):
            code = run(repo)
        out = buf.getvalue()
        ok = (code == 0) == want_ok and (needle in out)
        print(("ok   - " if ok else "FAIL - ") + label)
        if not ok:
            failures += 1
            print("       " + out.replace("\n", "\n       "))

    def fresh(tmp: Path, name: str) -> Path:
        repo = tmp / name
        (repo / "gradle").mkdir(parents=True)
        (repo / "kmp-conventions").mkdir()
        shutil.copytree(real_repo / "kmp-conventions" / "catalogs", repo / "kmp-conventions" / "catalogs")
        shutil.copy(real_repo / "kmp-conventions" / "README.md", repo / "kmp-conventions" / "README.md")
        (repo / "kmp-conventions" / "harness" / "inline").mkdir(parents=True)
        shutil.copy(real_repo / "kmp-conventions" / "harness" / "inline" / "build.gradle.kts",
                    repo / "kmp-conventions" / "harness" / "inline" / "build.gradle.kts")
        (repo / "gradle" / "libs.versions.toml").write_text('[versions]\nkotlin = "2.1.0"\n')
        (repo / "settings.gradle.kts").write_text('include(":a")\n')
        (repo / "build.gradle.kts").write_text("plugins { }\n")
        (repo / "a").mkdir()
        (repo / "a" / "build.gradle.kts").write_text('plugins { alias(libs.plugins.kotlin.jvm) }\n')
        return repo

    def edit(repo: Path, catalog: str, old: str, new: str) -> None:
        p = repo / "kmp-conventions" / "catalogs" / catalog
        text = p.read_text()
        assert old in text, (catalog, old)
        p.write_text(text.replace(old, new, 1))

    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        expect("the real ruled catalogs pass", fresh(tmp, "good"), True, "OK")

        r = fresh(tmp, "kotlin-ni"); edit(r, "kmp-non-ios.versions.toml", 'kotlin = "2.1.20"', 'kotlin = "2.1.21"')
        expect("a changed non-iOS Kotlin pin is rejected", r, False, "kmp-non-ios.versions.toml [versions]")
        r = fresh(tmp, "cmp-ni"); edit(r, "kmp-non-ios.versions.toml", 'compose-multiplatform = "1.8.2"', 'compose-multiplatform = "1.8.3"')
        expect("a changed non-iOS Compose pin is rejected", r, False, "OQ-17 option B")
        r = fresh(tmp, "kotlin-ios"); edit(r, "kmp-ios.versions.toml", 'kotlin = "2.2.21"', 'kotlin = "2.2.20"')
        expect("a changed iOS Kotlin pin is rejected", r, False, "OQ-38")
        r = fresh(tmp, "cmp-ios"); edit(r, "kmp-ios.versions.toml", 'compose-multiplatform = "1.9.3"', 'compose-multiplatform = "1.9.2"')
        expect("a changed iOS Compose pin is rejected", r, False, "OQ-38")
        r = fresh(tmp, "xcode"); edit(r, "kmp-ios.versions.toml", 'xcode-major = "26"', 'xcode-major = "16"')
        expect("a changed Xcode line is rejected", r, False, "xcode-major")
        r = fresh(tmp, "agp"); edit(r, "kmp-ios.versions.toml", 'xcode-major = "26"', 'xcode-major = "26"\nagp = "9.4.1"')
        expect("a smuggled extra pin (agp) is rejected", r, False, "agp")
        r = fresh(tmp, "libs")
        p = r / "kmp-conventions" / "catalogs" / "kmp-non-ios.versions.toml"
        p.write_text(p.read_text() + '\n[libraries]\nx = { module = "a:b", version = "1" }\n')
        expect("a [libraries] table is rejected", r, False, "tables beyond")
        r = fresh(tmp, "plugin"); edit(r, "kmp-non-ios.versions.toml", 'id = "org.jetbrains.compose"', 'id = "org.example.other"')
        expect("a changed plugin id is rejected", r, False, "[plugins]")
        r = fresh(tmp, "same"); edit(r, "kmp-ios.versions.toml", 'kotlin = "2.2.21"', 'kotlin = "2.1.20"')
        expect("identical Kotlin in both sets is rejected", r, False, "share a Kotlin")
        r = fresh(tmp, "bad-toml"); (r / "kmp-conventions" / "catalogs" / "kmp-ios.versions.toml").write_text("[versions\n")
        expect("invalid TOML is rejected", r, False, "not valid TOML")
        r = fresh(tmp, "missing"); (r / "kmp-conventions" / "catalogs" / "kmp-ios.versions.toml").unlink()
        expect("a missing catalog is rejected", r, False, "is missing")

        r = fresh(tmp, "snip-readme")
        rp = r / "kmp-conventions" / "README.md"; rp.write_text(rp.read_text().replace("iosSimulatorArm64()", "iosX64()", 1))
        expect("snippet: a README guard that differs from the harness is rejected", r, False, "differ from the harness")
        r = fresh(tmp, "snip-harness")
        hp = r / "kmp-conventions" / "harness" / "inline" / "build.gradle.kts"; hp.write_text(hp.read_text().replace("HostManager.hostIsMac", "true", 1))
        expect("snippet: dropping the mac-host guard is rejected", r, False, "snippet:")
        r = fresh(tmp, "snip-marker")
        hp = r / "kmp-conventions" / "harness" / "inline" / "build.gradle.kts"; hp.write_text(hp.read_text().replace("// END convention", "", 1))
        expect("snippet: a missing marker is rejected", r, False, "must contain exactly one")

        r = fresh(tmp, "trip-kotlin"); (r / "gradle" / "libs.versions.toml").write_text('[versions]\nkotlin = "2.1.20"\n')
        expect("tripwire: the repo's own Kotlin pin moving is caught", r, False, "tripwire: gradle/libs.versions.toml")
        r = fresh(tmp, "trip-module"); (r / "a" / "build.gradle.kts").write_text('plugins { kotlin("multiplatform") }\n')
        expect("tripwire: a converted module is caught", r, False, "a/build.gradle.kts:1")
        r = fresh(tmp, "trip-settings"); (r / "settings.gradle.kts").write_text('pluginManagement { includeBuild("kmp-conventions") }\n')
        expect("tripwire: wiring kmp-conventions into settings is caught", r, False, "settings.gradle.kts:1")

    if failures:
        print(f"verify-pins self-test: {failures} failure(s)", file=sys.stderr)
        return 1
    print("verify-pins self-test: all expectations held")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parent.parent)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    repo = args.repo_root.resolve()
    return self_test(repo) if args.self_test else run(repo)


if __name__ == "__main__":
    sys.exit(main())
