<!-- diag:schema=diag/2 -->
# Diagnostics report — test (`vision-pipeline` profile)

<!-- diag:section=summary -->
## Summary

**Overall: ✓ PASS** — 0 fail, 0 warn, 16 pass, 2 not evaluated
**Profile: `vision-pipeline`** — Camera / inference pipeline
**Confidence: HIGH** — no conditions found that would undermine these numbers.

| Metric | Value | Threshold | Verdict |
|---|---|---|---|
| Camera frame inter-arrival — late frame rate | 0.00 % | ≤ 2.0 % of observations > 50.00 ms (1.50 x the 33.33 ms nominal interval — jitter around nominal is expected, a gap is not) | ✓ PASS |
| Camera frame inter-arrival — p99 | 29.9 ms | ≤ 66.7 ms (2.0 × 33.33 ms (synthetic)) | ✓ PASS |
| Pose inference — over-budget rate | 0.00 % | ≤ 2.0 % of observations > 33.33 ms (the budget) | ✓ PASS |
| Pose inference — p99 | 29.9 ms | ≤ 66.7 ms (2.0 × 33.33 ms (synthetic)) | ✓ PASS |
| Capture → analysed — over-budget rate | 0.00 % | ≤ 5.0 % of observations > 33.33 ms (the budget) | ✓ PASS |
| Capture → analysed — p99 | 29.9 ms | ≤ 66.7 ms (2.0 × 33.33 ms (synthetic)) | ✓ PASS |
| Capture → analysed — stalled (> 1000 ms) | 0 | 0 | ✓ PASS |
| UI frames — jank rate | 0.00 % | ≤ 5.0 % of observations > 8.33 ms (the budget) | ✓ PASS |
| UI frames — p99 | 7.4 ms | ≤ 16.7 ms (2.0 × 8.33 ms (synthetic)) | ✓ PASS |
| UI frames — frozen (> 700 ms) | 0 | 0 | ✓ PASS |
| Memory — PSS total growth | 0.25 MB/min (R² 0.15) | R² < 0.70 — trend inconclusive | · N/A |
| Cold start → first frame | 700 ms | ≤ 800 ms | ✓ PASS |
| Warm start | 300 ms | ≤ 400 ms | ✓ PASS |
| Battery drain | not measured | needs ≥ 300 s on battery | · N/A |

<!-- diag:section=invariants -->
## Invariants

Structural claims that must hold for this app type. These catch what thresholds cannot — a delegate that silently fell back, a draw loop that never stopped, a privacy claim that stopped being true. **Not evaluated is not the same as passing.**

| Claim | Observed | Verdict |
|---|---|---|
| Pose inference ran on an accelerated delegate. | inference.delegate = gpu (allowed: gpu/nnapi) | ✓ PASS |
| Fewer than 5 % of camera frames were dropped before analysis. | 1.00 % (max 5.0 %) | ✓ PASS |
| Analysis keeps up with capture. | inference p95 29.47 ms vs capture period 33.33 ms | ✓ PASS |
| Inference timings were collected. | 800 observations (min 100) | ✓ PASS |

<!-- diag:section=caveats -->
## Caveats — read before the numbers

1. **Single run.** No variance information. Treat a FAIL as "worth investigating", not as a measured regression — a p99 can move substantially between runs.

Profile notes for `vision-pipeline`:

- Thermal matters more here than anywhere else in the portfolio — sustained camera plus inference is the hottest thing a phone does, and a long recording session will throttle. Read the pre/post-throttle split, not the whole-session aggregate.

<!-- diag:section=context -->
## Context

**Session** `diag_test` · label `fixture` · started 2026-08-07 14:23:11 IST · duration 60.0 s · trigger `adb-broadcast`

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

<!-- diag:section=series.camera.arrival -->
## Camera frame inter-arrival  ·  `camera.arrival`

800 observations over 40.0 s · 20.0/s · budget **33.33 ms (synthetic)**

| | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|
| Whole session | 24.03 ms | 28.57 ms | 29.29 ms | 29.86 ms | 30.0 ms | 24.09 ms |

Late frame > 50.00 ms (1.50 x the 33.33 ms nominal interval — jitter around nominal is expected, a gap is not): **0 / 800 = 0.00 %**

### By Resolution

| Resolution | n | late frame rate | p95 | p99 | first |
|---|---|---|---|---|---|
| b0 | 400 | 0.00 % | 29.37 ms | 29.84 ms | — |
| b1 | 400 | 0.00 % | 29.22 ms | 29.86 ms | — |

### 10 worst observations

| # | t (s) | value |  | Resolution |
|---|---|---|---|
| 1 | 4.80 | 30.0 |  | b0 |
| 2 | 4.00 | 30.0 |  | b0 |
| 3 | 39.95 | 30.0 |  | b1 |
| 4 | 34.75 | 30.0 |  | b1 |
| 5 | 3.70 | 29.9 |  | b0 |
| 6 | 32.75 | 29.9 |  | b1 |
| 7 | 9.65 | 29.9 |  | b1 |
| 8 | 34.40 | 29.9 |  | b0 |
| 9 | 10.05 | 29.9 |  | b1 |
| 10 | 7.40 | 29.8 |  | b0 |

All times in ms.

<!-- diag:section=series.pose.inference -->
## Pose inference  ·  `pose.inference`

800 observations over 40.0 s · 20.0/s · budget **33.33 ms (synthetic)**

| | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|
| Whole session | 24.30 ms | 29.01 ms | 29.47 ms | 29.93 ms | 30.0 ms | 24.26 ms |

Over-budget > 33.33 ms (the budget): **0 / 800 = 0.00 %**

### By Delegate

| Delegate | n | over-budget rate | p95 | p99 | first |
|---|---|---|---|---|---|
| b0 | 400 | 0.00 % | 29.45 ms | 29.92 ms | — |
| b1 | 400 | 0.00 % | 29.52 ms | 29.94 ms | — |

### 10 worst observations

| # | t (s) | value |  | Delegate |
|---|---|---|---|
| 1 | 27.90 | 30.0 |  | b0 |
| 2 | 0.65 | 30.0 |  | b1 |
| 3 | 33.35 | 30.0 |  | b1 |
| 4 | 0.55 | 30.0 |  | b1 |
| 5 | 34.70 | 29.9 |  | b0 |
| 6 | 17.25 | 29.9 |  | b1 |
| 7 | 0.95 | 29.9 |  | b1 |
| 8 | 32.00 | 29.9 |  | b0 |
| 9 | 8.75 | 29.9 |  | b1 |
| 10 | 33.20 | 29.9 |  | b0 |

All times in ms.

<!-- diag:section=series.pipeline.e2e -->
## Capture → analysed  ·  `pipeline.e2e`

800 observations over 40.0 s · 20.0/s · budget **33.33 ms (synthetic)**

| | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|
| Whole session | 24.30 ms | 28.81 ms | 29.47 ms | 29.86 ms | 30.0 ms | 24.15 ms |

Over-budget > 33.33 ms (the budget): **0 / 800 = 0.00 %**
Stalled (> 1000 ms): **0**

### Phase means over the worst non-stalled observations

| Phase | mean |
|---|---|
| camera | 8.3 ms |
| inference | 8.3 ms |
| dsp | 8.3 ms |
| render | 8.3 ms |

Severe observations are excluded here and reported individually — one outlier otherwise rewrites the attribution for everything else.

### 10 worst observations

| # | t (s) | value | camera | inference | dsp | render | bucket |
|---|---|---|---|---|---|---|---|
| 1 | 6.40 | 30.0 | 8.3 | 8.3 | 8.3 | 8.3 | — |
| 2 | 37.15 | 30.0 | 8.3 | 8.3 | 8.3 | 8.3 | — |
| 3 | 37.45 | 29.9 | 8.3 | 8.3 | 8.3 | 8.3 | — |
| 4 | 8.40 | 29.9 | 8.3 | 8.3 | 8.3 | 8.3 | — |
| 5 | 7.05 | 29.9 | 8.3 | 8.3 | 8.3 | 8.3 | — |
| 6 | 10.35 | 29.9 | 8.3 | 8.3 | 8.3 | 8.3 | — |
| 7 | 20.60 | 29.9 | 8.3 | 8.3 | 8.3 | 8.3 | — |
| 8 | 10.55 | 29.9 | 8.3 | 8.3 | 8.3 | 8.3 | — |
| 9 | 29.70 | 29.9 | 8.3 | 8.3 | 8.3 | 8.3 | — |
| 10 | 19.50 | 29.9 | 8.3 | 8.3 | 8.3 | 8.3 | — |

All times in ms.

<!-- diag:section=series.frames -->
## UI frames  ·  `frames`

800 observations over 40.0 s · 20.0/s · budget **8.33 ms (synthetic)**

| | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|
| Whole session | 6.04 ms | 7.21 ms | 7.33 ms | 7.44 ms | 7.5 ms | 6.06 ms |

Jank > 8.33 ms (the budget): **0 / 800 = 0.00 %**
Frozen (> 700 ms): **0**

### By Screen

| Screen | n | jank rate | p95 | p99 | first |
|---|---|---|---|---|---|
| b0 | 400 | 0.00 % | 7.34 ms | 7.44 ms | — |
| b1 | 400 | 0.00 % | 7.31 ms | 7.46 ms | — |

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

| # | t (s) | value | input | animation | layout | draw | sync | commandIssue | swap | Screen |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 28.95 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |
| 2 | 30.05 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |
| 3 | 30.65 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |
| 4 | 0.85 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |
| 5 | 22.20 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b0 |
| 6 | 33.45 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |
| 7 | 15.55 | 7.4 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |
| 8 | 24.90 | 7.4 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b0 |
| 9 | 29.10 | 7.4 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b0 |
| 10 | 39.40 | 7.4 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b0 |

All times in ms.

<!-- diag:section=trends -->
## Trends

### Memory

Sampled every 500 ms · 81 samples

| | start | peak | end | slope | R² |
|---|---|---|---|---|---|
| PSS total | 300.1 MB | 300.3 MB | 300.3 MB | +0.25 MB/min | 0.15 |

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
10:00:00.000  I  Test      synthetic run for vision-pipeline
```

Secrets, tokens, emails and user paths are replaced with typed placeholders such as `<redacted:token>` before the file is written.

<!-- diag:section=crash -->
## Crash link

No crash record found. `:crash-recovery` module present, last-crash file absent.

<!-- diag:section=notmeasured -->
## Not measured

Everything profile `vision-pipeline` declares was collected.

*Absent because it was not collected — not because it was zero.*

<!-- diag:section=end -->
---
Generated by `dev.aarso:diagnostics` schema `diag/2` · profile `vision-pipeline` · debug build only · no network permission · written to device storage and shared manually.
