# :cell-shell-core

Pure-JVM (no Android, no Compose) layout, motion and detection maths for the constellation's
navigation shell: spatial settle/park geometry, edge-scrubber mapping, word-wheel falloff and the
shake-peak detector. It exists so this logic is testable on a bare JDK and can become `commonMain`
of a Kotlin Multiplatform `:cell-shell` (porting program item F3).

**Status: additive, not yet adopted.** `:cell-shell` (Android, Compose) is untouched and still
carries its own copies of these functions; see [MIGRATION.md](MIGRATION.md) for why and for the
steps that make `:cell-shell` depend on this module. Package is `dev.aarso.cellshell.core`, chosen
so both artefacts can sit on one classpath without duplicate classes.

Not claimed: nothing here has been run on a device, and the Compose side was not rebuilt (no
Android SDK in the authoring environment). Compose-typed bits (`Animatable`, `FontWeight`,
`CubicBezierEasing`) deliberately stay in `:cell-shell`; this module exposes their plain numbers.

```bash
./gradlew :cell-shell-core:test
```
