# Shared Libraries — a system of cells

Cross-app libraries for the constellation. Each module is an independent Maven coordinate under
`dev.aarso`, consumed the same way Hyle is: a **git submodule pinned by the consumer** plus
`includeBuild(...)` (Personal-Tracker `DECISIONS.md` D-A).

| Module | Coordinate | Platform | What it is |
|---|---|---|---|
| `:search-core` | `dev.aarso:search-core` | pure JVM | On-device search: query language, facet evaluation, ranking. No Android, no storage engine, no coroutines. |
| `:search-testkit` | `dev.aarso:search-testkit` | pure JVM | Conformance fixtures and golden-corpus helpers for anything implementing the search contracts. |
| `:crash-recovery` | `dev.aarso:crash-recovery` | Android library | Capture an uncaught crash to a file; show a recovery screen on the next launch instead of bricking. Zero Hyle dependency. |

## Why this repo exists

Hyle-Design-System is the *design system*. Things that are shared but are **not** design-system
adoption were ending up there for want of anywhere else — which forced apps that must never
depend on Hyle (D-L: Animalcules, Clackpad) to carry the entire Hyle submodule to reach a module
that has nothing to do with Hyle. This is the neutral home for that category.

`:crash-recovery` moved here from Hyle-Design-System. See [MIGRATION.md](MIGRATION.md).

## Consuming a module

```kotlin
// settings.gradle.kts
includeBuild("shared-libraries")
```

```kotlin
// app/build.gradle.kts
implementation("dev.aarso:search-core:0.1.0")
implementation("dev.aarso:crash-recovery:1.2.0")
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
