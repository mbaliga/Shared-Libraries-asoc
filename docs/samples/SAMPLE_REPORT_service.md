<!-- diag:schema=diag/2 -->
# Diagnostics report — test (`service` profile)

<!-- diag:section=summary -->
## Summary

**Overall: ✓ PASS** — 0 fail, 0 warn, 11 pass, 2 not evaluated
**Profile: `service`** — Headless service / inference daemon
**Confidence: HIGH** — no conditions found that would undermine these numbers.

| Metric | Value | Threshold | Verdict |
|---|---|---|---|
| Request latency — slow request rate | 0.00 % | ≤ 5.0 % of observations > 2000.00 ms (the budget) | ✓ PASS |
| Request latency — p99 | 1793.6 ms | ≤ 4000.0 ms (2.0 × 2000.00 ms (synthetic)) | ✓ PASS |
| Request latency — stalled (> 30000 ms) | 0 | 0 | ✓ PASS |
| Time to first token — slow start rate | 0.00 % | ≤ 10.0 % of observations > 1500.00 ms (the budget) | ✓ PASS |
| Time to first token — p99 | 1342.8 ms | ≤ 3000.0 ms (2.0 × 1500.00 ms (synthetic)) | ✓ PASS |
| Model load — slow load rate | 0.00 % | ≤ 5.0 % of observations > 10000.00 ms (the budget) | ✓ PASS |
| Model load — p99 | 8968.7 ms | ≤ 20000.0 ms (2.0 × 10000.00 ms (synthetic)) | ✓ PASS |
| Memory — PSS total growth | 0.28 MB/min (R² 0.19) | R² < 0.70 — trend inconclusive | · N/A |
| Battery drain | not measured | needs ≥ 600 s on battery | · N/A |

<!-- diag:section=invariants -->
## Invariants

Structural claims that must hold for this app type. These catch what thresholds cannot — a delegate that silently fell back, a draw loop that never stopped, a privacy claim that stopped being true. **Not evaluated is not the same as passing.**

| Claim | Observed | Verdict |
|---|---|---|
| The process was never killed for memory. | oom = 0 | ✓ PASS |
| The session ended normally rather than being recovered from a journal. | ended normally | ✓ PASS |
| Fewer than 1 % of requests returned an error. | 0.25 % (max 1.0 %) | ✓ PASS |
| The request queue never exceeded a depth of 4. | queue.max_depth = 2 (max 4) | ✓ PASS |

<!-- diag:section=caveats -->
## Caveats — read before the numbers

1. **Single run.** No variance information. Treat a FAIL as "worth investigating", not as a measured regression — a p99 can move substantially between runs.

Profile notes for `service`:

- Memory is the headline metric here, not latency — this is the one app where PSS genuinely approaches the device ceiling.
- Sustained inference is the canonical thermal case. Expect the throttle split to matter in every long run.

<!-- diag:section=context -->
## Context

**Session** `diag_test` · label `fixture` · started 2026-08-07 14:23:11 IST · duration 240.0 s · trigger `adb-broadcast`

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

<!-- diag:section=series.request.latency -->
## Request latency  ·  `request.latency`

800 observations over 40.0 s · 20.0/s · budget **2000.00 ms (synthetic)**

| | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|
| Whole session | 1451.69 ms | 1730.92 ms | 1768.60 ms | 1793.56 ms | 1798.3 ms | 1461.14 ms |

Slow request > 2000.00 ms (the budget): **0 / 800 = 0.00 %**
Stalled (> 30000 ms): **0**

### By Endpoint

| Endpoint | n | slow request rate | p95 | p99 | first |
|---|---|---|---|---|---|
| b0 | 400 | 0.00 % | 1780.79 ms | 1795.03 ms | — |
| b1 | 400 | 0.00 % | 1760.06 ms | 1787.74 ms | — |

### 10 worst observations

| # | t (s) | value |  | Endpoint |
|---|---|---|---|
| 1 | 29.90 | 1798.3 |  | b0 |
| 2 | 0.70 | 1798.3 |  | b0 |
| 3 | 14.80 | 1797.9 |  | b0 |
| 4 | 1.75 | 1797.8 |  | b1 |
| 5 | 11.70 | 1795.9 |  | b0 |
| 6 | 5.60 | 1795.0 |  | b0 |
| 7 | 31.25 | 1794.5 |  | b1 |
| 8 | 20.20 | 1794.3 |  | b0 |
| 9 | 21.50 | 1793.6 |  | b0 |
| 10 | 18.50 | 1792.6 |  | b0 |

All times in ms.

<!-- diag:section=series.ttft -->
## Time to first token  ·  `ttft`

800 observations over 40.0 s · 20.0/s · budget **1500.00 ms (synthetic)**

| | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|
| Whole session | 1072.87 ms | 1298.62 ms | 1322.04 ms | 1342.80 ms | 1349.8 ms | 1082.96 ms |

Slow start > 1500.00 ms (the budget): **0 / 800 = 0.00 %**

### By Model

| Model | n | slow start rate | p95 | p99 | first |
|---|---|---|---|---|---|
| b0 | 400 | 0.00 % | 1327.26 ms | 1344.29 ms | — |
| b1 | 400 | 0.00 % | 1312.41 ms | 1338.94 ms | — |

### 10 worst observations

| # | t (s) | value |  | Model |
|---|---|---|---|
| 1 | 9.95 | 1349.8 |  | b1 |
| 2 | 23.40 | 1349.4 |  | b0 |
| 3 | 21.80 | 1348.2 |  | b0 |
| 4 | 7.35 | 1347.9 |  | b1 |
| 5 | 1.30 | 1346.8 |  | b0 |
| 6 | 28.00 | 1345.6 |  | b0 |
| 7 | 14.00 | 1344.3 |  | b0 |
| 8 | 7.60 | 1342.9 |  | b0 |
| 9 | 37.20 | 1342.8 |  | b0 |
| 10 | 9.10 | 1342.2 |  | b0 |

All times in ms.

<!-- diag:section=series.model.load -->
## Model load  ·  `model.load`

800 observations over 40.0 s · 20.0/s · budget **10000.00 ms (synthetic)**

| | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|
| Whole session | 7189.72 ms | 8653.46 ms | 8843.94 ms | 8968.73 ms | 8992.2 ms | 7223.27 ms |

Slow load > 10000.00 ms (the budget): **0 / 800 = 0.00 %**

### By Model

| Model | n | slow load rate | p95 | p99 | first |
|---|---|---|---|---|---|
| b0 | 400 | 0.00 % | 8843.94 ms | 8968.73 ms | — |
| b1 | 400 | 0.00 % | 8842.61 ms | 8962.59 ms | — |

### 10 worst observations

| # | t (s) | value |  | Model |
|---|---|---|---|
| 1 | 35.20 | 8992.2 |  | b0 |
| 2 | 11.70 | 8989.4 |  | b0 |
| 3 | 22.10 | 8987.3 |  | b0 |
| 4 | 0.85 | 8985.5 |  | b1 |
| 5 | 13.80 | 8981.6 |  | b0 |
| 6 | 13.45 | 8973.1 |  | b1 |
| 7 | 26.75 | 8972.6 |  | b1 |
| 8 | 21.65 | 8968.8 |  | b1 |
| 9 | 39.10 | 8968.7 |  | b0 |
| 10 | 27.70 | 8964.9 |  | b0 |

All times in ms.

<!-- diag:section=trends -->
## Trends

### Memory

Sampled every 500 ms · 81 samples

| | start | peak | end | slope | R² |
|---|---|---|---|---|---|
| PSS total | 300.1 MB | 300.3 MB | 300.0 MB | +0.28 MB/min | 0.19 |

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
Drain: not estimated — needs ≥ 600 s on battery

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
10:00:00.000  I  Test      synthetic run for service
```

Secrets, tokens, emails and user paths are replaced with typed placeholders such as `<redacted:token>` before the file is written.

<!-- diag:section=crash -->
## Crash link

No crash record found. `:crash-recovery` module present, last-crash file absent.

<!-- diag:section=notmeasured -->
## Not measured

Everything profile `service` declares was collected.

*Absent because it was not collected — not because it was zero.*

<!-- diag:section=end -->
---
Generated by `dev.aarso:diagnostics` schema `diag/2` · profile `service` · debug build only · no network permission · written to device storage and shared manually.
