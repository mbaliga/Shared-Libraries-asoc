<!-- diag:schema=diag/2 -->
# Diagnostics report — test (`audio` profile)

<!-- diag:section=summary -->
## Summary

**Overall: ✕ FAIL** — 1 fail, 0 warn, 9 pass, 2 not evaluated
**Profile: `audio`** — Audio / instrument companion
**Confidence: HIGH** — no conditions found that would undermine these numbers.

| Metric | Value | Threshold | Verdict |
|---|---|---|---|
| Audio callback — underrun rate | 0.00 % | ≤ 0.0 % of observations > 2.00 ms (the budget) | ✓ PASS |
| Audio callback — p99 | 1.8 ms | ≤ 1.0 ms (0.5 × 2.00 ms (synthetic)) | ✕ FAIL |
| Pitch detection — over-budget rate | 0.00 % | ≤ 5.0 % of observations > 2.00 ms (the budget) | ✓ PASS |
| Pitch detection — p99 | 1.8 ms | ≤ 4.0 ms (2.0 × 2.00 ms (synthetic)) | ✓ PASS |
| Memory — PSS total growth | 0.22 MB/min (R² 0.12) | R² < 0.70 — trend inconclusive | · N/A |
| Cold start → first frame | 700 ms | ≤ 800 ms | ✓ PASS |
| Warm start | 300 ms | ≤ 400 ms | ✓ PASS |
| Battery drain | not measured | needs ≥ 300 s on battery | · N/A |

<!-- diag:section=invariants -->
## Invariants

Structural claims that must hold for this app type. These catch what thresholds cannot — a delegate that silently fell back, a draw loop that never stopped, a privacy claim that stopped being true. **Not evaluated is not the same as passing.**

| Claim | Observed | Verdict |
|---|---|---|
| The low-latency audio path was granted. | audio.path = low-latency (expected low-latency) | ✓ PASS |
| No buffer underruns occurred. | audio.underrun = 0 | ✓ PASS |
| The stream runs at the device's native sample rate and buffer size. | rate 48000 vs native 48000 · buffer 96 vs native 96 | ✓ PASS |
| Audio callbacks were timed. | 800 observations (min 500) | ✓ PASS |

<!-- diag:section=caveats -->
## Caveats — read before the numbers

1. **Single run.** No variance information. Treat a FAIL as "worth investigating", not as a measured regression — a p99 can move substantially between runs.

Profile notes for `audio`:

- Round-trip latency (mic in → speaker out) is not measured here; it needs a loopback measurement with external hardware. Callback timing is the on-device proxy.
- Haptics share this profile: a haptic callback missing its deadline is the same object as an audio one, and audio/haptic sync shows up as drift between the two series.

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

<!-- diag:section=series.audio.callback -->
## Audio callback  ·  `audio.callback`

800 observations over 40.0 s · 20.0/s · budget **2.00 ms (synthetic)**

| | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|
| Whole session | 1.43 ms | 1.73 ms | 1.77 ms | 1.79 ms | 1.8 ms | 1.44 ms |

Underrun > 2.00 ms (the budget): **0 / 800 = 0.00 %**

### 10 worst observations

| # | t (s) | value |  | bucket |
|---|---|---|---|
| 1 | 25.50 | 1.8 |  | — |
| 2 | 12.60 | 1.8 |  | — |
| 3 | 23.95 | 1.8 |  | — |
| 4 | 2.25 | 1.8 |  | — |
| 5 | 17.60 | 1.8 |  | — |
| 6 | 36.15 | 1.8 |  | — |
| 7 | 24.80 | 1.8 |  | — |
| 8 | 3.25 | 1.8 |  | — |
| 9 | 32.45 | 1.8 |  | — |
| 10 | 26.65 | 1.8 |  | — |

All times in ms.

<!-- diag:section=series.pitch.detect -->
## Pitch detection  ·  `pitch.detect`

800 observations over 40.0 s · 20.0/s · budget **2.00 ms (synthetic)**

| | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|
| Whole session | 1.43 ms | 1.72 ms | 1.76 ms | 1.79 ms | 1.8 ms | 1.44 ms |

Over-budget > 2.00 ms (the budget): **0 / 800 = 0.00 %**

### 10 worst observations

| # | t (s) | value |  | bucket |
|---|---|---|---|
| 1 | 22.75 | 1.8 |  | — |
| 2 | 39.40 | 1.8 |  | — |
| 3 | 27.95 | 1.8 |  | — |
| 4 | 5.85 | 1.8 |  | — |
| 5 | 1.90 | 1.8 |  | — |
| 6 | 39.35 | 1.8 |  | — |
| 7 | 35.60 | 1.8 |  | — |
| 8 | 3.70 | 1.8 |  | — |
| 9 | 31.50 | 1.8 |  | — |
| 10 | 19.85 | 1.8 |  | — |

All times in ms.

<!-- diag:section=trends -->
## Trends

### Memory

Sampled every 500 ms · 81 samples

| | start | peak | end | slope | R² |
|---|---|---|---|---|---|
| PSS total | 300.0 MB | 300.3 MB | 300.2 MB | +0.22 MB/min | 0.12 |

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
10:00:00.000  I  Test      synthetic run for audio
```

Secrets, tokens, emails and user paths are replaced with typed placeholders such as `<redacted:token>` before the file is written.

<!-- diag:section=crash -->
## Crash link

No crash record found. `:crash-recovery` module present, last-crash file absent.

<!-- diag:section=notmeasured -->
## Not measured

Everything profile `audio` declares was collected.

*Absent because it was not collected — not because it was zero.*

<!-- diag:section=end -->
---
Generated by `dev.aarso:diagnostics` schema `diag/2` · profile `audio` · debug build only · no network permission · written to device storage and shared manually.
