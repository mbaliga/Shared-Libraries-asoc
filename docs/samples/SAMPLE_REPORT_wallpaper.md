<!-- diag:schema=diag/2 -->
# Diagnostics report — test (`wallpaper` profile)

<!-- diag:section=summary -->
## Summary

**Overall: ✕ FAIL** — 1 fail, 2 warn, 7 pass, 2 not evaluated
**Profile: `wallpaper`** — Live wallpaper
**Confidence: HIGH** — no conditions found that would undermine these numbers.

**Invariants violated — read these first.** A broken structural claim outranks any percentile: it is a defect, not a degradation.

- ! WARN The session was long enough to estimate battery drain. *(observed: 120 s (min 300 s))*
- ✕ FAIL Draw-loop frames were reported by the engine. *(observed: 800 observations (min 1200))*

| Metric | Value | Threshold | Verdict |
|---|---|---|---|
| Draw-loop frames — jank rate | 0.00 % | ≤ 5.0 % of observations > 8.33 ms (the budget) | ✓ PASS |
| Draw-loop frames — p99 | 7.5 ms | ≤ 16.7 ms (2.0 × 8.33 ms (synthetic)) | ✓ PASS |
| Draw-loop frames — frozen (> 500 ms) | 0 | 0 | ✓ PASS |
| Memory — PSS total growth | 0.18 MB/min (R² 0.08) | R² < 0.70 — trend inconclusive | · N/A |
| Cold start → first frame | 700 ms | ≤ 600 ms | ! WARN |
| Warm start | 300 ms | ≤ 300 ms | ✓ PASS |
| Battery drain | not measured | needs ≥ 300 s on battery | · N/A |

<!-- diag:section=invariants -->
## Invariants

Structural claims that must hold for this app type. These catch what thresholds cannot — a delegate that silently fell back, a draw loop that never stopped, a privacy claim that stopped being true. **Not evaluated is not the same as passing.**

| Claim | Observed | Verdict |
|---|---|---|
| The engine draws nothing while the wallpaper is not visible. | frames.while_hidden = 0 | ✓ PASS |
| No garbage collection was triggered inside the draw loop. | gc.during_draw = 0 | ✓ PASS |
| The APK ships no native libraries. | native.libs = none (expected none) | ✓ PASS |
| The session was long enough to estimate battery drain. | 120 s (min 300 s) | ! WARN |
| Draw-loop frames were reported by the engine. | 800 observations (min 1200) | ✕ FAIL |

**! WARN — The session was long enough to estimate battery drain.** (`wp.long-enough-for-drain`)
A wallpaper runs for hours; its real cost is drain, not p99. A two-minute capture cannot speak to that.

**✕ FAIL — Draw-loop frames were reported by the engine.** (`wp.frames-collected`)
A wallpaper has no Window, so nothing arrives unless the engine calls Diagnostics.frame() itself. An empty series here means uninstrumented, not idle.

<!-- diag:section=caveats -->
## Caveats — read before the numbers

1. **Single run.** No variance information. Treat a FAIL as "worth investigating", not as a measured regression — a p99 can move substantially between runs.

Profile notes for `wallpaper`:

- Run long. A wallpaper's failure modes are drain, thermal creep and slow memory growth — all of which need tens of minutes, not two.
- The same HTML/Canvas spec drives the Android engine and the desktop port, so the same series ids can be emitted from both and compared directly.

<!-- diag:section=context -->
## Context

**Session** `diag_test` · label `fixture` · started 2026-08-07 14:23:11 IST · duration 120.0 s · trigger `adb-broadcast`

**App** `com.asystemofcells.test` 0.1.0 (1) · buildType `debug` · debuggable true · git `a3f81c2` (dirty)

**Device** RedMagic 11 Pro (nubia NX789J) · SoC `SM8850` · Android 16 (API 36) · arm64-v8a
RAM 24 576 MB total / 17 204 MB available at session start · rooted false
Display 2480 × 1116 · 480 dpi · refresh **120.0 Hz** (panel max 120.0 Hz) → vsync budget **8.33 ms**

### Environment facts

Discrete facts the invariants assert over. A fact that silently changed value is the usual cause of a regression no timing metric explains.

| Fact | Value |
|---|---|
| `audio.buffer_frames` | 96 |
| `audio.native_buffer_frames` | 96 |
| `audio.native_sample_rate` | 48000 |
| `audio.path` | low-latency |
| `audio.sample_rate` | 48000 |
| `byo_ai_enabled` | false |
| `ime.window_attached` | true |
| `inference.delegate` | gpu |
| `native.libs` | none |
| `stream.drift_ppm` | 40 |

<!-- diag:section=series.frames -->
## Draw-loop frames  ·  `frames`

800 observations over 40.0 s · 20.0/s · budget **8.33 ms (synthetic)**

| | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|
| Whole session | 5.99 ms | 7.21 ms | 7.36 ms | 7.48 ms | 7.5 ms | 6.03 ms |

Jank > 8.33 ms (the budget): **0 / 800 = 0.00 %**
Frozen (> 500 ms): **0**

### By Scene

| Scene | n | jank rate | p95 | p99 | first |
|---|---|---|---|---|---|
| b0 | 400 | 0.00 % | 7.30 ms | 7.46 ms | — |
| b1 | 400 | 0.00 % | 7.40 ms | 7.48 ms | — |

### Phase means over the worst non-frozen observations

| Phase | mean |
|---|---|
| input | 1.2 ms |
| animation | 1.2 ms |
| layout | 1.2 ms |
| draw | 1.2 ms |
| sync | 1.2 ms |
| commandIssue | 1.2 ms |
| swap | 1.2 ms |

Severe observations are excluded here and reported individually — one outlier otherwise rewrites the attribution for everything else.

### 10 worst observations

| # | t (s) | value | input | animation | layout | draw | sync | commandIssue | swap | Scene |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 5.40 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b0 |
| 2 | 32.55 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |
| 3 | 6.15 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |
| 4 | 13.40 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b0 |
| 5 | 32.95 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |
| 6 | 7.35 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |
| 7 | 8.30 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b0 |
| 8 | 23.05 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |
| 9 | 39.25 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |
| 10 | 0.55 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |

All times in ms.

<!-- diag:section=trends -->
## Trends

### Memory

Sampled every 500 ms · 81 samples

| | start | peak | end | slope | R² |
|---|---|---|---|---|---|
| PSS total | 300.1 MB | 300.3 MB | 300.3 MB | +0.18 MB/min | 0.08 |

A slope is treated as a finding only when R² ≥ 0.70; steep-but-noisy is reported as inconclusive rather than raised as a leak.

<!-- diag:section=lifecycle -->
## Lifecycle and start-up

**Cold start** (process start → first frame): **700 ms**

| Phase | Δ | cumulative |
|---|---|---|
| → first frame | 700 ms | 700 ms |

**Warm start** 300 ms · **hot start** 120 ms

<!-- diag:section=thermal -->
## Thermal and power

| t | Status | Duration |
|---|---|---|
| 0 s | NONE | 40 s |

Battery 80 % → 79 % · discharging · power-save off
Drain: not estimated — needs ≥ 300 s on battery

<!-- diag:section=counters -->
## Counters

| Counter | Value |
|---|---|
| `audio.underrun` | 0 |
| `camera.delivered` | 800 |
| `camera.dropped` | 8 |
| `errors` | 1 |
| `frames.while_hidden` | 0 |
| `gc.during_draw` | 0 |
| `net.requests` | 0 |
| `oom` | 0 |
| `queue.max_depth` | 2 |
| `requests` | 400 |
| `stream.crc_errors` | 2 |
| `stream.dropped` | 0 |
| `stream.reconnects` | 0 |
| `stream.samples` | 10 240 |

<!-- diag:section=logs -->
## Log ring — last 1 of 512 entries

```
10:00:00.000  I  Test      synthetic run for wallpaper
```

Secrets, tokens, emails and user paths are replaced with typed placeholders such as `<redacted:token>` before the file is written.

<!-- diag:section=crash -->
## Crash link

No crash record found. `:crash-recovery` module present, last-crash file absent.

<!-- diag:section=notmeasured -->
## Not measured

Everything profile `wallpaper` declares was collected.

*Absent because it was not collected — not because it was zero.*

<!-- diag:section=end -->
---
Generated by `dev.aarso:diagnostics` schema `diag/2` · profile `wallpaper` · debug build only · no network permission · written to device storage and shared manually.
