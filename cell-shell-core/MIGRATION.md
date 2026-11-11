# Migrating :cell-shell onto :cell-shell-core

Why `:cell-shell` was left untouched: it cannot be compiled here (no Android SDK), and the swap
edits public-API-adjacent Android sources. So the logic is currently **duplicated**; the existing
`:cell-shell` tests still pin the Android copy and `:cell-shell-core` tests pin the core copy
(same cases, ported). Until the swap, a change to one must be made in both.

## Mechanical swap (do on a machine with the Android SDK)

1. `settings.gradle.kts`: `include(":cell-shell-core")`.
2. `cell-shell/build.gradle.kts`: `api(project(":cell-shell-core"))` (use `api` so consumers keep
   seeing the types).
3. Delete the pure definitions from `cell-shell` and re-export them under the old names, e.g.
   `typealias BandEdge = dev.aarso.cellshell.core.BandEdge`, `fun settleTarget(...) =
   core.settleTarget(...)`. Keep `internal` visibility where it was; keep `SpatialMotion.settleSpec`
   built from `SpatialMotionConstants` (`CubicBezierEasing(B1X, B1Y, B2X, B2Y)`, `SETTLE_MS`).
   `wheelFontWeight` becomes `FontWeight(core.wheelFontWeightValue(d))`.
4. Run the existing `cell-shell` tests unchanged; they must pass. Then delete the duplicated
   tests only if you want a single copy.
5. Public API check: `ScrubberStop`, `RoomEdge`, `ParkStyle`, `BandEdge` keep their
   `dev.aarso.cellshell` names via typealias; verify with an API dump (binary-compat validator)
   or accept the one-time recompile for consumers.

## KMP step (owner-gated, OQ-17)

Convert `cell-shell-core` to `kotlin("multiplatform")` (`jvm()` first) by moving `src/main` to
`src/commonMain` and `src/test` to `src/commonTest` (swap JUnit for `kotlin.test`).
