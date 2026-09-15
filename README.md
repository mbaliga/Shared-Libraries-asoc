# Shared Libraries — a system of cells

Cross-app libraries for the constellation. Each module is an independent Maven coordinate under
`dev.aarso`, consumed the same way Hyle is: a **git submodule pinned by the consumer** plus
`includeBuild(...)` (Personal-Tracker `DECISIONS.md` D-A).

| Module | Coordinate | Platform | What it is |
|---|---|---|---|
| `:search-core` | `dev.aarso:search-core` | pure JVM | On-device search: query language, facet evaluation, ranking. No Android, no storage engine, no coroutines. |
| `:search-testkit` | `dev.aarso:search-testkit` | pure JVM | Conformance fixtures and golden-corpus helpers for anything implementing the search contracts. |
| `:crash-recovery` | `dev.aarso:crash-recovery` | Android library | Capture an uncaught crash to a file; show a recovery screen on the next launch instead of bricking. Zero Hyle dependency. |
| `:diagnostics-core` | `dev.aarso:diagnostics-core` | pure JVM | On-device evidence: percentiles, verdicts, invariants, redaction, and Markdown report rendering — the source-agnostic engine every app-type profile shares. |
| `:diagnostics-android` | `dev.aarso:diagnostics-android` | Android library | MetricSource plugins, session lifecycle, export, crash link, ADB trigger. No network permission, ever — verifiable in the merged manifest. |
| `:diagnostics-overlay` | `dev.aarso:diagnostics-overlay` | Android library | The profile-aware floating bubble/panel. Plain Views, zero Compose/Material — same reasoning as `:crash-recovery`. |
| `:diagnostics-noop` | `dev.aarso:diagnostics-noop` | Android library | Release-variant substitute with an identical API surface to `:diagnostics-android`, every call a no-op. Parity enforced by `scripts/check-noop-parity.py`. |
| `:interaction-mode` | `dev.aarso:interaction-mode` | Android library | The shared "Regular" vs "asoc" interaction-mode choice — `InteractionMode`, `InteractionModeStore` + `PrefsInteractionModeStore`, and the pure `ModeDefaults` policy. No UI — the picker lives in Hyle. |

## Why this repo exists

Hyle-Design-System is the *design system*. Things that are shared but are **not** design-system
adoption were ending up there for want of anywhere else — which forced apps that must never
depend on Hyle (D-L: Animalcules, Clackpad) to carry the entire Hyle submodule to reach a module
that has nothing to do with Hyle. This is the neutral home for that category.

`:crash-recovery` moved here from Hyle-Design-System. See [MIGRATION.md](MIGRATION.md).

`:diagnostics-*` landed here directly (not relocated) for the same D-L reason `:crash-recovery`
moved: it is deliberately zero-Compose/zero-Material, so it belongs in the neutral repo, not in
the design system. See [docs/DIAGNOSTICS_MODULE_SPEC.md](docs/DIAGNOSTICS_MODULE_SPEC.md) for the
full design rationale and [docs/samples/](docs/samples/) for one example report per profile.

## `:interaction-mode`

The constellation-wide choice between two top-level interaction patterns (2026-09-15 ruling: the
rooms model remains somewhat experimental while it's being perfected, so every app offers the
choice rather than forcing everyone onto whichever is newest). UI-facing naming is exactly
**"Regular"** and **"asoc"** (lowercase) — do not use other casing or a synonym at the UI layer.

- **`InteractionMode.REGULAR`** — conventional navigation chrome: a traditional layout with
  visible tabs, buttons and menus. A screen may still offer gestures, but only as a supplement;
  everything reachable by gesture must also be reachable through a visible control.
- **`InteractionMode.ASOC`** — the spatial-rooms layout and its gesture grammar (edge scrub,
  word-wheel rail, room transitions, …). Experimental and evolving — expect its shape to shift
  between releases in ways `REGULAR` deliberately does not.

**Defaults policy** (`ModeDefaults`, pure and exhaustively tested): a fresh install with no
explicit choice and no legacy signal defaults to `REGULAR`. An app may pass `legacySignal = true`
to `PrefsInteractionModeStore` when an install predates the Regular/asoc bifurcation — i.e. the
user was already living in the rooms model without ever having "chosen" it — so that install
continues into `ASOC` instead of silently dropping an existing rooms user into an unfamiliar
layout. What counts as "predates the bifurcation" is entirely the calling app's judgment (e.g.
its own pre-existing first-run marker); `ModeDefaults` only encodes what to do with that answer,
and a legacy signal can never override an explicit choice the user has since made.

`InteractionModeStore` (interface) + `PrefsInteractionModeStore` (the `SharedPreferences`-backed
implementation, no other dependency) is the whole surface. **This module is deliberately
UI-free** — no Compose, no Views, no picker screen. The affordance that actually lets a user
choose "Regular" vs "asoc" lives in Hyle (`dev.aarso.hyle`) and calls through this interface.

## Consuming a module

```kotlin
// settings.gradle.kts
includeBuild("shared-libraries")
```

```kotlin
// app/build.gradle.kts
implementation("dev.aarso:search-core:0.1.0")
implementation("dev.aarso:crash-recovery:1.5.0")

// diagnostics: debug-only collector + overlay, release-only no-op, plus the safety guard that
// fails a release build if it ever resolves a real collector instead of the no-op.
debugImplementation("dev.aarso:diagnostics-android:0.2.0")
debugImplementation("dev.aarso:diagnostics-overlay:0.2.0")
releaseImplementation("dev.aarso:diagnostics-noop:0.2.0")
apply(from = "$rootDir/gradle/release-safety.gradle.kts")

implementation("dev.aarso:interaction-mode:0.1.0")
```

Gradle substitutes any `dev.aarso:<name>` dependency with the matching project in the included
build, so no Maven registry is involved.

```bash
git submodule add https://github.com/mbaliga/Shared-Libraries-asoc.git shared-libraries
git submodule update --init    # a plain clone will not populate it
```

## The AGP lockstep — read before adding a consumer

This build contains Android library modules, so its AGP version participates in every consumer's
composite build graph. **Consumers must pin the same AGP** (currently **8.9.1**) or Gradle
hard-fails with *"Using multiple versions of the Android Gradle plugin ... is not allowed"*
(D-Q). The Gradle wrapper must meet that AGP's floor — currently **8.14.3**.

This is the same constraint `hyle-design-system` already imposes, so an app that already
composites Hyle takes on nothing new. An app that does not will need its AGP and wrapper aligned
first. Known state at time of writing:

| Repo | AGP | Kotlin | Wrapper | Ready? |
|---|---|---|---|---|
| Android-IDE-core | 8.9.1 | 2.1.0 | 8.14.3 | ✅ |
| Foto-Xplorr | 8.9.1 | 2.1.0 | 8.14.3 | ✅ |
| Fyl-Manager | 8.9.1 | **2.1.20** | **none** | ⚠️ no wrapper checked in |
| BOS_launcher | **8.7.3** | 2.1.0 | **8.11.1** | ❌ needs alignment |
| hnm_playground | **8.7.3** | **2.1.21** | 8.14.3 | ❌ needs alignment |

## Versioning

Each module versions independently — they are separate coordinates with separate consumers, and
a search change must not force a crash-recovery bump. Versions are declared in each module's
`build.gradle.kts`.

Because composite-build substitution matches on `group:name` and **ignores the version**, the
declared version is documentation, not enforcement: consumers compile against whatever the
submodule pin contains. Keep the pin and the declared version honest with each other.
