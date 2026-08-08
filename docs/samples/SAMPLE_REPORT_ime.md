<!-- diag:schema=diag/2 -->
# Diagnostics report — test (`ime` profile)

<!-- diag:section=summary -->
## Summary

**Overall: ✕ FAIL** — 1 fail, 1 warn, 11 pass, 2 not evaluated
**Profile: `ime`** — IME (input method)
**Confidence: HIGH** — no conditions found that would undermine these numbers.

| Metric | Value | Threshold | Verdict |
|---|---|---|---|
| IME window frames — jank rate | 0.00 % | ≤ 5.0 % of observations > 8.33 ms (the budget) | ✓ PASS |
| IME window frames — p99 | 7.5 ms | ≤ 16.7 ms (2.0 × 8.33 ms (synthetic)) | ✓ PASS |
| IME window frames — frozen (> 700 ms) | 0 | 0 | ✓ PASS |
| Key-down → glyph visible — slow key rate | 0.00 % | ≤ 1.0 % of observations > 32.00 ms (the budget) | ✓ PASS |
| Key-down → glyph visible — p99 | 28.7 ms | ≤ 64.0 ms (2.0 × 32.00 ms (synthetic)) | ✓ PASS |
| Key-down → glyph visible — stalled key (> 150 ms) | 0 | 0 | ✓ PASS |
| WebView rAF frame — jank rate | 0.00 % | ≤ 5.0 % of observations > 8.33 ms (the budget) | ✓ PASS |
| WebView rAF frame — p99 | 7.5 ms | ≤ 16.7 ms (2.0 × 8.33 ms (synthetic)) | ✓ PASS |
| Memory — PSS total growth | 0.24 MB/min (R² 0.13) | R² < 0.70 — trend inconclusive | · N/A |
| Cold start → first frame | 700 ms | ≤ 300 ms | ✕ FAIL |
| Warm start | 300 ms | ≤ 150 ms | ! WARN |
| Battery drain | not measured | needs ≥ 300 s on battery | · N/A |

<!-- diag:section=invariants -->
## Invariants

Structural claims that must hold for this app type. These catch what thresholds cannot — a delegate that silently fell back, a draw loop that never stopped, a privacy claim that stopped being true. **Not evaluated is not the same as passing.**

| Claim | Observed | Verdict |
|---|---|---|
| The frame collector attached to the IME window. | ime.window_attached = true (expected true) | ✓ PASS |
| No network requests occur while the bring-your-own AI endpoint is disabled. | net.requests = 0 | ✓ PASS |
| Key-to-glyph latency was measured. | 800 observations (min 50) | ✓ PASS |

<!-- diag:section=caveats -->
## Caveats — read before the numbers

1. **Single run.** No variance information. Treat a FAIL as "worth investigating", not as a measured regression — a p99 can move substantially between runs.

Profile notes for `ime`:

- The floating overlay is unusable here — it covers the surface being measured and steals input. Use the ADB broadcast trigger for IME work.
- IME processes are short-lived and recreated often, so sessions are fragmentary by nature; compare distributions across several runs rather than trusting one.

<!-- diag:section=context -->
## Context

**Session** `diag_test` · label `fixture` · started 2026-08-07 14:23:11 IST · duration 40.0 s · trigger `adb-broadcast`

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
## IME window frames  ·  `frames`

800 observations over 40.0 s · 20.0/s · budget **8.33 ms (synthetic)**

| | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|
| Whole session | 6.04 ms | 7.27 ms | 7.40 ms | 7.48 ms | 7.5 ms | 6.07 ms |

Jank > 8.33 ms (the budget): **0 / 800 = 0.00 %**
Frozen (> 700 ms): **0**

### By Layer

| Layer | n | jank rate | p95 | p99 | first |
|---|---|---|---|---|---|
| b0 | 400 | 0.00 % | 7.38 ms | 7.49 ms | — |
| b1 | 400 | 0.00 % | 7.40 ms | 7.47 ms | — |

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

| # | t (s) | value | input | animation | layout | draw | sync | commandIssue | swap | Layer |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 28.70 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b0 |
| 2 | 12.90 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b0 |
| 3 | 22.60 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b0 |
| 4 | 31.30 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b0 |
| 5 | 17.70 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b0 |
| 6 | 34.05 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |
| 7 | 29.40 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b0 |
| 8 | 2.20 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b0 |
| 9 | 1.85 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |
| 10 | 26.45 | 7.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | b1 |

All times in ms.

<!-- diag:section=series.input.latency -->
## Key-down → glyph visible  ·  `input.latency`

800 observations over 40.0 s · 20.0/s · budget **32.00 ms (synthetic)**

| | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|
| Whole session | 23.45 ms | 27.90 ms | 28.40 ms | 28.66 ms | 28.8 ms | 23.40 ms |

Slow key > 32.00 ms (the budget): **0 / 800 = 0.00 %**
Stalled key (> 150 ms): **0**

### 10 worst observations

| # | t (s) | value |  | bucket |
|---|---|---|---|
| 1 | 11.40 | 28.8 |  | — |
| 2 | 31.70 | 28.7 |  | — |
| 3 | 22.70 | 28.7 |  | — |
| 4 | 22.60 | 28.7 |  | — |
| 5 | 10.05 | 28.7 |  | — |
| 6 | 25.90 | 28.7 |  | — |
| 7 | 29.20 | 28.7 |  | — |
| 8 | 23.85 | 28.7 |  | — |
| 9 | 31.30 | 28.7 |  | — |
| 10 | 14.10 | 28.6 |  | — |

All times in ms.

<!-- diag:section=series.webview.raf -->
## WebView rAF frame  ·  `webview.raf`

800 observations over 40.0 s · 20.0/s · budget **8.33 ms (synthetic)**

| | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|
| Whole session | 5.97 ms | 7.23 ms | 7.36 ms | 7.47 ms | 7.5 ms | 6.02 ms |

Jank > 8.33 ms (the budget): **0 / 800 = 0.00 %**

### By View

| View | n | jank rate | p95 | p99 | first |
|---|---|---|---|---|---|
| b0 | 400 | 0.00 % | 7.41 ms | 7.47 ms | — |
| b1 | 400 | 0.00 % | 7.35 ms | 7.46 ms | — |

### 10 worst observations

| # | t (s) | value |  | View |
|---|---|---|---|
| 1 | 9.50 | 7.5 |  | b0 |
| 2 | 30.20 | 7.5 |  | b0 |
| 3 | 38.90 | 7.5 |  | b0 |
| 4 | 14.05 | 7.5 |  | b1 |
| 5 | 37.95 | 7.5 |  | b1 |
| 6 | 38.55 | 7.5 |  | b1 |
| 7 | 7.60 | 7.5 |  | b0 |
| 8 | 23.40 | 7.5 |  | b0 |
| 9 | 13.15 | 7.5 |  | b1 |
| 10 | 35.80 | 7.5 |  | b0 |

All times in ms.

<!-- diag:section=trends -->
## Trends

### Memory

Sampled every 500 ms · 81 samples

| | start | peak | end | slope | R² |
|---|---|---|---|---|---|
| PSS total | 300.0 MB | 300.3 MB | 300.2 MB | +0.24 MB/min | 0.13 |

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
10:00:00.000  I  Test      synthetic run for ime
```

Secrets, tokens, emails and user paths are replaced with typed placeholders such as `<redacted:token>` before the file is written.

<!-- diag:section=crash -->
## Crash link

No crash record found. `:crash-recovery` module present, last-crash file absent.

<!-- diag:section=notmeasured -->
## Not measured

Everything profile `ime` declares was collected.

*Absent because it was not collected — not because it was zero.*

<!-- diag:section=end -->
---
Generated by `dev.aarso:diagnostics` schema `diag/2` · profile `ime` · debug build only · no network permission · written to device storage and shared manually.
