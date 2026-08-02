# Shared-Libraries-asoc

Shared library modules consumed by other `mbaliga/*` apps via git submodule + Gradle
`includeBuild` -- the one sharing mechanism used across this constellation (see
`mbaliga/Personal-Tracker`'s `DECISIONS.md`, D-A).

## Modules

| Module | Coordinate | What |
|---|---|---|
| [`crash-recovery`](./crash-recovery) | `dev.aarso:crash-recovery:1.1.0` | A shared crash-recovery utility: capture an uncaught crash to a file, show a recovery screen on the next launch instead of bricking. Zero dependency on any design-system module, so apps with their own visual identity can use it too. |

## Provenance

`crash-recovery` was relocated here from `mbaliga/Hyle-Design-System`, where it originally
lived as a self-contained module deliberately outside the design system's own module (see
Personal-Tracker `DECISIONS.md` D-O). The Maven coordinate, package (`dev.aarso.crashrecovery`),
and public API are unchanged by the move, so no consumer's dependency declaration needs to
change, only which repo its submodule points at.

**This move is not finished.** Per
[`snapshots/shared-libraries-reorg-proposal-2026-08-02.md`](https://github.com/mbaliga/Personal-Tracker/blob/main/snapshots/shared-libraries-reorg-proposal-2026-08-02.md)
in `mbaliga/Personal-Tracker`, the sequencing is: (1) scaffold this repo with the module
(done, this commit), (2) publish here under the same coordinate (done), (3) repoint each of the
six consuming repos' submodule/`includeBuild` reference here, one PR per repo, timed after the
in-flight device-verification round on the original module finishes, (4) remove
`crash-recovery/` from `Hyle-Design-System` once every consumer is repointed. Steps 3 and 4 are
still pending -- `Hyle-Design-System` still carries its own copy of this module today, and no
consumer has been repointed yet.

## Build

```bash
gradle :crash-recovery:test              # JVM tests, no Android SDK needed
gradle :crash-recovery:assembleRelease   # release AAR
```

(`gradle`, not `./gradlew` -- this repo's Gradle wrapper jar isn't committed yet. The
wrapper scripts and `gradle-wrapper.properties` are here, pinned to Gradle 8.14.3, but the
binary launcher jar itself needs one local run to populate:
`gradle wrapper --gradle-version 8.14.3`, using any Gradle install you already have
for example Android Studio's bundled one, `sdk install gradle`, or Homebrew's `gradle`.
After that, `./gradlew` works normally. CI does not need this step; its workflow calls
`gradle` directly.)
