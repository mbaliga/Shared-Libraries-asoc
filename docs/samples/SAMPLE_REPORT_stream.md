<!-- diag:schema=diag/2 -->
# Diagnostics report — test (`stream` profile)

<!-- diag:section=summary -->
## Summary

**Overall: ✓ PASS** — 0 fail, 0 warn, 10 pass, 2 not evaluated
**Profile: `stream`** — Sensor / BLE stream
**Confidence: HIGH** — no conditions found that would undermine these numbers.

| Metric | Value | Threshold | Verdict |
|---|---|---|---|
| Inter-sample interval — late sample rate | 0.00 % | ≤ 1.0 % of observations > 7.81 ms (2.00 x the 3.91 ms nominal interval — jitter around nominal is expected, a gap is not) | ✓ PASS |
| Inter-sample interval — p99 | 3.5 ms | ≤ 11.7 ms (3.0 × 3.91 ms (synthetic)) | ✓ PASS |
| DSP stage — over-budget rate | 0.00 % | ≤ 5.0 % of observations > 10.00 ms (the budget) | ✓ PASS |
| DSP stage — p99 | 9.0 ms | ≤ 20.0 ms (2.0 × 10.00 ms (synthetic)) | ✓ PASS |
| Memory — PSS total growth | 0.03 MB/min (R² 0.00) | R² < 0.70 — trend inconclusive | · N/A |
| Battery drain | not measured | needs ≥ 300 s on battery | · N/A |

<!-- diag:section=invariants -->
## Invariants

Structural claims that must hold for this app type. These catch what thresholds cannot — a delegate that silently fell back, a draw loop that never stopped, a privacy claim that stopped being true. **Not evaluated is not the same as passing.**

| Claim | Observed | Verdict |
|---|---|---|
| No samples were dropped. | stream.dropped = 0 | ✓ PASS |
| The BLE link held for the whole session. | stream.reconnects = 0 | ✓ PASS |
| Fewer than 0.1 % of packets failed integrity checks. | 0.02 % (max 0.1 %) | ✓ PASS |
| Device-to-phone clock drift stayed under 200 ppm. | 40 ppm (max 200) | ✓ PASS |
| No raw signal values appear in the report. | no matches | ✓ PASS |
| No microvolt-tagged sample arrays appear in the report. | no matches | ✓ PASS |

<!-- diag:section=caveats -->
## Caveats — read before the numbers

1. **Single run.** No variance information. Treat a FAIL as "worth investigating", not as a measured regression — a p99 can move substantially between runs.

Profile notes for `stream`:

- These reports are designed to be shared, and the underlying data is personal-state data. The payload rules above are enforcement, not guidance: integrity statistics may leave the device, signal values may not.
- Latency is nearly irrelevant here. Read the integrity invariants first and the percentiles second.

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

<!-- diag:section=series.stream.interval -->
## Inter-sample interval  ·  `stream.interval`

800 observations over 40.0 s · 20.0/s · budget **3.91 ms (synthetic)**

| | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|
| Whole session | 2.83 ms | 3.37 ms | 3.44 ms | 3.50 ms | 3.5 ms | 2.83 ms |

Late sample > 7.81 ms (2.00 x the 3.91 ms nominal interval — jitter around nominal is expected, a gap is not): **0 / 800 = 0.00 %**

### By Channel

| Channel | n | late sample rate | p95 | p99 | first |
|---|---|---|---|---|---|
| b0 | 400 | 0.00 % | 3.44 ms | 3.50 ms | — |
| b1 | 400 | 0.00 % | 3.43 ms | 3.49 ms | — |

### 10 worst observations

| # | t (s) | value |  | Channel |
|---|---|---|---|
| 1 | 38.60 | 3.5 |  | b0 |
| 2 | 36.35 | 3.5 |  | b1 |
| 3 | 3.75 | 3.5 |  | b1 |
| 4 | 6.90 | 3.5 |  | b0 |
| 5 | 38.40 | 3.5 |  | b0 |
| 6 | 8.75 | 3.5 |  | b1 |
| 7 | 14.25 | 3.5 |  | b1 |
| 8 | 27.00 | 3.5 |  | b0 |
| 9 | 15.00 | 3.5 |  | b0 |
| 10 | 31.75 | 3.5 |  | b1 |

All times in ms.

<!-- diag:section=series.dsp.stage -->
## DSP stage  ·  `dsp.stage`

800 observations over 40.0 s · 20.0/s · budget **10.00 ms (synthetic)**

| | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|
| Whole session | 7.24 ms | 8.61 ms | 8.80 ms | 8.97 ms | 9.0 ms | 7.23 ms |

Over-budget > 10.00 ms (the budget): **0 / 800 = 0.00 %**

### By Stage

| Stage | n | over-budget rate | p95 | p99 | first |
|---|---|---|---|---|---|
| b0 | 400 | 0.00 % | 8.83 ms | 8.96 ms | — |
| b1 | 400 | 0.00 % | 8.79 ms | 8.97 ms | — |

### 10 worst observations

| # | t (s) | value |  | Stage |
|---|---|---|---|
| 1 | 17.35 | 9.0 |  | b1 |
| 2 | 23.20 | 9.0 |  | b0 |
| 3 | 28.75 | 9.0 |  | b1 |
| 4 | 3.15 | 9.0 |  | b1 |
| 5 | 39.45 | 9.0 |  | b1 |
| 6 | 19.90 | 9.0 |  | b0 |
| 7 | 20.40 | 9.0 |  | b0 |
| 8 | 27.10 | 9.0 |  | b0 |
| 9 | 4.15 | 9.0 |  | b1 |
| 10 | 28.30 | 9.0 |  | b0 |

All times in ms.

<!-- diag:section=trends -->
## Trends

### Memory

Sampled every 500 ms · 81 samples

| | start | peak | end | slope | R² |
|---|---|---|---|---|---|
| PSS total | 300.2 MB | 300.3 MB | 300.1 MB | +0.03 MB/min | 0.00 |

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
10:00:00.000  I  Test      synthetic run for stream
```

Secrets, tokens, emails and user paths are replaced with typed placeholders such as `<redacted:token>` before the file is written.

<!-- diag:section=crash -->
## Crash link

No crash record found. `:crash-recovery` module present, last-crash file absent.

<!-- diag:section=notmeasured -->
## Not measured

Everything profile `stream` declares was collected.

*Absent because it was not collected — not because it was zero.*

<!-- diag:section=end -->
---
Generated by `dev.aarso:diagnostics` schema `diag/2` · profile `stream` · debug build only · no network permission · written to device storage and shared manually.
