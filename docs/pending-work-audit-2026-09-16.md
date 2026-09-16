# Pending-work audit — 2026-09-16

This audit reconciles every open pull request into one consolidated branch and
records what remains genuinely pending. Verification claims are limited to
what was re-run on 2026-09-16 in a clean Linux container (JDK 21, Gradle
8.14.3, Android SDK platform 36 / build-tools 36.0.0).

## How the open work was reconciled

| PR | What it carried | Disposition |
| --- | --- | --- |
| #10 | Five-line comment recording why the band grip reads its edge off `bandEdge()` | **Merged.** |
| #5 | The diagnostics reconciliation onto main: the four `:diagnostics-*` modules at 0.3.0, redaction fixes, ClackMetric interop, and the no-network-permission CI guard | **Merged.** This was already the canonical diagnostics line — it had cherry-picked #3's two diagnostics commits and improved on them. |
| #3 | The original fonebrew development branch: diagnostics (older state) plus `:modelbench`, `:modelbench-ui`, `:interaction-mode`, crash-recovery 1.5.0, and two CI fixes | **Unique parts cherry-picked** (modelbench, modelbench-ui, interaction-mode, crash-recovery 1.5.0, `setup-android@v4` + packages pin, CI wiring + adoption-boundary record). Its diagnostics content is superseded by #5. Nothing unique remains on the branch. |
| #7 | crash-recovery 1.5.0: the "Continue" relaunch fix and the non-destructive quarantine/salvage reset | **Absorbed byte-for-byte** via #3's fold-in commit — `crash-recovery/` on the consolidated branch is identical to #7's head. |
| #9 | The three data-only asset packages: `word-graph/`, `multilang-dict/`, `stickers/` | **Merged.** Note: Clackpad currently pins its submodule to this PR's branch; after this consolidation lands on `main`, repoint that pin to `main`. |
| #1 | The original scaffold + crash-recovery relocation | **Fully superseded by `main`** long ago (its tree is a strict ancestor state: crash-recovery 1.1.0, no cell-shell/feedback/evidence-schema). Recommend closing without merging. |

Every open PR is therefore either contained in this branch or (for #1)
already contained in `main`.

## Verified on the consolidated branch (2026-09-16, clean container)

- `:search-core:test`, `:search-testkit:test`, `:evidence-schema:test`,
  `:diagnostics-core:test`, `:modelbench:test` — pass (pure-JVM suites).
- Full `./gradlew build` with a real Android SDK — all modules compile,
  all unit-test suites pass, all AARs assemble (see the consolidation PR for
  the run summary).
- `scripts/check-noop-parity.py` — diagnostics no-op/android API parity holds.
- `scripts/check-no-internet-permission.py` — zero permissions in the merged
  debug and release manifests of `:diagnostics-android`.

## What is done

Thirteen Gradle modules and three asset packages, one coherent posture
(offline, no telemetry, no network, D-L-safe neutrality):

- **Search**: `:search-core` 0.2.0 with the natural-language query layer;
  `:search-testkit` conformance fixtures.
- **Reliability**: `:crash-recovery` **1.5.0** — "Continue" genuinely
  relaunches the app, and reset is non-destructive (O(1) quarantine of state
  directories, auto-purged after two healthy launches, with an opt-in
  salvage/export bridge). There is no "erase everything" left in the UI.
- **Navigation/motion**: `:cell-shell` with the parked-surface chrome fix and
  the `bandEdge()` reasoning recorded next to the code.
- **Feedback**: `:feedback` — opt-in, user-readable drafts, delivery only
  through a user-launched chooser.
- **Evidence exchange**: `:evidence-schema` portable JSON schemas v1.
- **Diagnostics**: the four-module `:diagnostics-*` suite at 0.3.0 — 248
  core checks, redaction closed over every free-text path including mark and
  custom-span names, ClackMetric interop, and "no network permission, ever"
  enforced against the merged manifest in CI.
- **Interaction mode**: `:interaction-mode` — the Regular/asoc choice with
  the pure `ModeDefaults` policy (legacy rooms installs stay in asoc; an
  explicit user choice always wins) and the 2026-09-15 adoption-boundary
  decision recorded in the README.
- **Model benchmarking**: `:modelbench` (report grammar v1, engine-adapter
  seam) and `:modelbench-ui` (two host-agnostic Compose screens).
- **Asset packages**: `word-graph/` (G6 + WordNet-derived data, licence
  shipped), `multilang-dict/` (eight languages + KanjiVG), `stickers/`
  (Bao/Fauna die-cuts + optimised `android-assets/` tree + the tools that
  produced them) — deliberately not Gradle modules so consumers avoid the
  AGP lockstep.
- **CI**: the whole matrix gates pushes, including the `setup-android@v4` +
  `packages: platform-tools` pin that unbreaks SDK setup after Google
  removed the legacy `tools` package upstream (2026-09-15).

## What is pending

1. **Land this consolidation, then repoint Clackpad.** Its submodule pins
   PR #9's branch; after merge it should pin `main` and drop the stale
   branch.
2. **On-device verification** (owner-gated, no emulator in CI): the
   crash-recovery 1.5.0 salvage/restore flow, diagnostics-android
   collectors/overlay under a real process kill, and cell-shell motion on
   hardware. Every JVM-checkable property is already covered; these are the
   residual device-only behaviours.
3. **Consumer alignment for the AGP lockstep** (README table): Fyl-Manager
   has no wrapper checked in; BOS_launcher and hnm_playground still pin
   AGP 8.7.3 and cannot composite this build until aligned.
4. **Port the "Continue" relaunch fix to Hyle-Design-System's 1.1.0 copy**
   of crash-recovery (PR #7 offered this; still undone in that repo).
5. **`:modelbench-ui` has no consumer wired yet** — deliberate, but worth
   remembering it is unproven in a host.
6. **Reserved, intentionally empty**: `local-session-core` (Bocal's
   transport lands once, from the authoritative session) and `stickers/flora/`
   (awaiting artwork).
7. **Branch hygiene after merge**: close #1 (superseded); #3, #5, #7, #9,
   #10 become redundant once this lands; `claude/multilang-dict-assets`,
   `claude/repo-status-overview-2c0rgm`, `claude/feedback-utility`,
   `claude/fotoz-ui-interactions-bxvgbw` are fully merged or contained and
   can be deleted.

## Usability notes

The user-facing wins in this consolidation are concentrated in failure and
consent flows:

- A crash no longer risks a user's data: recovery actually relaunches, and
  the reset path quarantines instead of wiping — with restore offered in the
  UI whenever a quarantine is waiting. For the data-heavy apps this library
  serves, this converts the worst moment in the product into a recoverable
  one.
- The diagnostics overlay no longer swallows every touch on screen, and the
  report can no longer leak caller-supplied names past redaction — the
  privacy promise is now CI-enforced rather than asserted.
- The Regular/asoc split gives experimental navigation an explicit, revocable
  opt-in instead of forcing the rooms model on everyone; legacy rooms users
  are not silently relocated.
- Sticker/dictionary/word-graph packages ship as assets, so Clackpad can
  adopt them without an AGP migration it cannot currently make.
