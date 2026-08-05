# Migrating to `mbaliga/Shared-Libraries-asoc`

## `dev.aarso:crash-recovery` has moved here from `mbaliga/Hyle-Design-System`

**Old home:** `mbaliga/Hyle-Design-System`, module `:crash-recovery` (Personal-Tracker `DECISIONS.md` D-O)
**New home:** `mbaliga/Shared-Libraries-asoc`, module `:crash-recovery`
**Coordinate is unchanged:** `dev.aarso:crash-recovery`
**Version:** `1.1.0` → `1.2.0`

### Why it moved

The utility was built with **zero dependency on `:hyle`** on purpose (plain `android.widget`
views, no Compose, no Material, colours as plain `@ColorInt Int`), precisely so that apps
forbidden from depending on Hyle — Animalcules and Clackpad, under D-L — could still use it.

But leaving it inside the Hyle repo meant those same apps had to add the **entire
Hyle-Design-System submodule** to reach a module that deliberately has nothing to do with Hyle.
D-O's reasoning was sound when there was only one shared repo; this is the neutral home that
removes the contradiction.

### What you have to change

Two lines, in two files.

**1. `settings.gradle.kts`** — swap which build you composite:

```diff
-includeBuild("hyle-design-system")
+includeBuild("hyle-design-system")     // keep this ONLY if you also use dev.aarso:hyle
+includeBuild("shared-libraries")
```

If your app took the Hyle submodule *only* for crash-recovery (BOS_launcher, Clackpad and
hnm_playground all did), remove `hyle-design-system` entirely and replace the submodule:

```bash
git submodule deinit -f hyle-design-system
git rm -f hyle-design-system
git submodule add https://github.com/mbaliga/Shared-Libraries-asoc.git shared-libraries
```

**2. Your module's `build.gradle.kts`** — bump the version:

```diff
-implementation("dev.aarso:crash-recovery:1.1.0")
+implementation("dev.aarso:crash-recovery:1.2.0")
```

The `group:name` coordinate is unchanged, so Gradle's composite substitution keeps working with
no other edits. **No import changes** — the package is still `dev.aarso.crashrecovery` and the
API is source-compatible.

**3. CI** — if your checkout step lists submodule paths explicitly, add the new one. If it uses
`submodules: recursive`, nothing to do.

### What happens if you don't

`hyle-design-system` keeps a **tombstone** at `:crash-recovery` declaring the same
`dev.aarso:crash-recovery` coordinate, so composite substitution still resolves and you do *not*
get an unhelpful `Could not find dev.aarso:crash-recovery`. Instead every call site fails to
compile with a message naming this repo.

The tombstone deliberately does **not** fail at Gradle configuration time. Gradle configures
every project in an `includeBuild`, so a configuration-time failure would break consumers that
use `:hyle` and never touch crash-recovery. A compile-time error only reaches actual users.

### What changed in 1.2.0

`1.2.0` is `hyle-design-system@c586f8f` (which declared `1.1.0`) **plus** `previewIntent` /
`samplePreview` merged forward.

Those two histories had **diverged, and neither was a superset**:

| | `c586f8f` (Hyle `main`) | `33b0faa` (never merged) |
|---|---|---|
| Version declared | 1.1.0 | 1.0.0 |
| Richer recovery Activity, crash-mark drawables | ✅ | ❌ |
| `previewIntent` / `samplePreview` | ❌ | ✅ |

Android-IDE-core pinned its Hyle submodule to `33b0faa` — an unmerged branch commit — and calls
`CrashRecovery.previewIntent` from `SettingsRoom.kt`. So it compiled, but only against work that
never landed, and it was missing every later improvement on `main`. Anyone "tidying" that pin
onto `main` would have broken IDE-core's build.

`1.2.0` ends that split: it takes `main`'s evolved implementation and re-applies `previewIntent`
on top, rebuilt against the richer `CrashReport.Decoded` that `main` introduced.

Preview safety properties, pinned by tests in `CrashReportTest`:

- "PREVIEW" appears in the headline, the plain-language summary **and** the shareable full
  report, so a screenshot or an accidentally-shared preview can never be mistaken for a real crash.
- Every device/app metadata field is `null` — a preview cannot leak real device data.
- **Reset is inert** — previewing can never wipe app data.
- **Continue is inert** beyond closing the screen — previewing never relaunches the app.
- The report is synthesized, never read from disk, so previewing works with no captured crash
  and cannot consume a real pending one.
