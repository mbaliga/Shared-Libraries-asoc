# Spec: crash report format and the PREVIEW safety properties

Status: **DRAFT for owner review** (PORTING_PLAN.md S0.2; PD-4). Normative source: `crash-recovery`
1.5.0, `CrashReport.kt` (the pure part - no `android.*`) and the "Preview safety properties" list in
`MIGRATION.md`. Golden vectors: [`fixtures/crash-report-format.v1.json`](fixtures/crash-report-format.v1.json),
asserted against the Kotlin implementation by `crash-recovery/.../SpecFixturesTest.kt`.

MUST / SHOULD are used in the RFC 2119 sense.

## 1. What this spec is for

`crash-recovery` captures an uncaught crash to a file and shows a recovery screen on the next launch
instead of leaving the app bricked. The capture hook and the screen are platform code (Android
`Activity`, a Swing window, a UIKit view, a QML page). The **report** - what is written, what the
screen shows, and the exact text a user shares - is pure data and text, and it is the part worth
sharing. A non-Kotlin implementation (the QML recovery page of an Ubuntu Touch app, a SwiftUI view
reading the same report) conforms by producing the same text and honouring the same safety
properties.

Doctrine that governs every implementation (these are not negotiable here):

- **Nothing is ever sent anywhere.** The report lives in the app's private storage until the *user*
  explicitly shares or copies it. No network, no identifier, no queue (program rule I-1).
- **The shared text is the displayed text, word for word.** What the screen shows and what Share
  sends are the same string (`render`).
- **The recovery screen depends on nothing that may have crashed** (D-O: zero-dependency screen).
- A crash-recovery claim "isn't real until it's been watched recovering from an actual crash on a
  real device" (D-P). Nothing in this document or its fixtures is such evidence.

## 2. Data model

A report has these fields (names as in the Kotlin `CrashReport`; the fixture uses snake_case):

| Field | Type | Notes |
|---|---|---|
| `appLabel` | string | The app as the user knows it |
| `whenMillis` | integer | Epoch milliseconds; `0` means unknown |
| `threadName` | string | `""` renders as `?` |
| `excType` | string | Simple type name of the failure (JVM: `Class.getSimpleName`, falling back to the full name when blank). Other platforms use their native failure type name |
| `excMessage` | string or null | Blank is treated as absent |
| `plainLanguage` | string | One calm, non-technical sentence (section 4) |
| `device` | DeviceInfo | Section 2.1 |
| `trace` | string | The raw multi-line stack trace |

### 2.1 DeviceInfo

`appVersionName` (string or null), `appVersionCode` (integer or null), `osSdkInt` (integer),
`deviceManufacturer`, `deviceModel`, `packageName` (default `""`), `installSource` (string or null),
`freeMemMb`, `totalMemMb` (integers or null). On a platform where a field has no meaning, the
implementation fills the documented "unknown" value (`?`, `0`, `""`, null) rather than inventing one.
No identifier of the user or the device (serial number, advertising id, account) is ever a field.

### 2.2 Headline

`headline` = `excType + ": " + excMessage` when `excMessage` is non-blank, else `excType`.

## 3. The shared text (`render`)

Exactly the following, where `\n` is a line feed (U+000A) on every platform and `{...}` is a field:

```
=== Crash report — {appLabel} ===
Generated on-device. No identifiers included.

What happened: {plainLanguage}

Error:   {excType}
Message: {excMessage, or "(none)" when absent or blank}
Thread:  {threadName, or "?" when blank}
When:    {whenText} (device time)

App:     {appLabel} {appVersionName or "?"} ({appVersionCode or "?"})
Package: {packageName, or "?" when blank}
Source:  {installSource, or "Unknown"}

Device:  {deviceManufacturer} {deviceModel}, Android SDK {osSdkInt}
Memory:  {freeMemMb} MB free of {totalMemMb} MB at crash        <- only when BOTH are present

{trace}
```

- `{whenText}` is `yyyy-MM-dd HH:mm:ss` in the **device's default time zone**, ASCII digits (the Kotlin
  implementation formats with `Locale.US`), or `unknown` when `whenMillis` is not positive. The fixture pins the zone to UTC; an
  implementation MUST format in its own local zone in production and in UTC under the fixture.
- The `—` in the first line is U+2014; the file is UTF-8.
- The text ends with `trace` verbatim (no added newline).
- "Android SDK" is the literal label even off Android; a non-Android port MAY substitute its
  platform's label *only* by a spec revision (it would change the fixture), not unilaterally.

## 4. Plain-language summary

A fixed mapping from failure kind to one sentence, with a generic fallback; deliberately
app-agnostic (it never guesses what the app was doing, only what the platform did about it).
First match wins, in this order, after unwrapping (below):

| Kind | Sentence |
|---|---|
| out of memory | The app needed more memory than your device could give it, and Android had to stop it. |
| stack overflow | The app got stuck repeating itself until it ran out of room, so Android stopped it. |
| null dereference | The app expected something to be there that wasn't, and had to close. |
| timeout | Part of the app took too long to respond, so it was stopped. |
| I/O failure | The app had trouble reading or writing data and had to close. |
| security / permission | The app tried to do something it didn't have permission for, and had to close. |
| any other low-level error | The app hit a low-level error it couldn't recover from and had to close. |
| anything else | The app ran into an unexpected error and had to close. |

**Unwrapping.** While the failure is exactly the platform's bare generic runtime wrapper (JVM:
`java.lang.RuntimeException`, *not* a subclass) and has a cause, replace it by its cause. A wrapper
subclass is not unwrapped (the fixture pins both).

Non-JVM ports map their own failure kinds onto these rows where a row has a meaning, and use the
last row otherwise. That mapping is the port's, and is `NEEDS-OWNER-VALIDATION` until the owner has
read it.

## 5. Persistence encoding (v2) and decoding

`encode` writes UTF-8 text:

```
CRASHv2
appLabel<TAB>{value}
whenMillis<TAB>{value}
threadName<TAB>{value}
excType<TAB>{value}
excMessage<TAB>{value}
plainLanguage<TAB>{value}
versionName<TAB>{value}
versionCode<TAB>{value}
packageName<TAB>{value}
installSource<TAB>{value}
osSdkInt<TAB>{value}
manufacturer<TAB>{value}
model<TAB>{value}
freeMemMb<TAB>{value}
totalMemMb<TAB>{value}
---TRACE---
{trace}
```

One `key<TAB>value` line per field in exactly this order; an absent value is the empty string; any
line feed inside a *value* is replaced by a space (the trace, after the marker, is raw). `decode`
rebuilds every structured field from this, recomputes the shared text with `render` (so the file never
stores prose that could drift), and treats an empty value as absent.

**Decoding is best-effort and MUST NOT throw or hide a real crash behind a blank screen.** Text that
does not start with `CRASHv2` is handled as: split at the first blank line (`\n\n`) into
`headline` + body (legacy shape); with no blank line, the first line is the headline and the whole
text the body. The fixture pins foreign text, empty text, and the legacy shape.

## 6. Crash streaks

`next_streak_count(prev_count, prev_millis, now_millis, window_ms = 60000)` is `prev_count + 1` when
`prev_count > 0`, `prev_millis > 0` and `0 <= now_millis - prev_millis <= window_ms` (inclusive), and
`1` otherwise. A backwards clock is a fresh incident, never a continuation. The streak is kept
separately from the report so that dismissing the report ("Continue") leaves the streak intact; that
is what lets a crash loop be detected across the continue-relaunch-crash cycle.

## 7. Deaths the language handler cannot see

`ofExitDeath` builds a report for a process death the in-process handler never observed (a native
crash or ANR kill, read back from the OS exit history on the next launch). There is no stack for
these by definition, so the `trace` field says so honestly instead of faking frames:

```
No Java stack trace exists for this kind of death.
Recorded by Android (ApplicationExitInfo):
  reason: {reasonLabel}
  detail: {description, or "(none recorded)" when absent or blank}
```

`threadName` is `?`; `excType` is the reason label; `plainLanguage` is keyed on the label prefix
(`Native...`, `App not responding...`, otherwise "`{appLabel}` was closed by a crash."). This is an
**Android-only capability**: `ApplicationExitInfo` has no analogue on desktop JVM, iOS or Ubuntu
Touch, so off Android it is a documented feature gap, not a defect (PORTING_PLAN.md section 2).

## 8. PREVIEW safety properties

The recovery screen can be previewed without a real crash. Five properties keep a preview from ever
being mistaken for, or acting as, a real crash. They come from `MIGRATION.md` and are numbered here
for the fixture:

| Id | Property | Checked by |
|---|---|---|
| P1 | The word PREVIEW appears in the headline, the plain-language summary **and** the shareable full report, and the full report states that no crash occurred - a screenshot or an accidentally shared preview cannot be mistaken for a real crash | fixture, `CONTAINER-BUILD-ONLY` |
| P2 | Every device and app metadata field is null - a preview cannot leak real device data | fixture, `CONTAINER-BUILD-ONLY` |
| P3 | **Reset is inert** in a preview - previewing can never wipe app data | `NEEDS-DEVICE-VALIDATION` (behaviour of the screen, not of the pure report) |
| P4 | **Continue is inert** beyond closing the screen - previewing never relaunches the app | `NEEDS-DEVICE-VALIDATION` (as P3) |
| P5 | The preview report is synthesized from literals and the caller's label only, never read from disk - previewing works with no captured crash and cannot consume a real pending one | purity half: fixture; "cannot consume a pending report": `NEEDS-DEVICE-VALIDATION` |

The fixture carries the exact preview text (`trace_template`, `full_report_template`), the required
markers, the ten metadata fields that must be null, and these five properties with which are
machine-checked. A port's recovery page MUST implement P3 and P4 as behaviour; the plan assigns the
device check to the consumer.

## 9. Fixture format and tolerances

One JSON object: `spec`, `version`, `source`, `constants`, `devices` (named DeviceInfo values the
cases refer to), and the case lists `headline`, `plain_language`, `streak`, `report`, `exit_death`,
`decode_fallback`, plus the `preview` object. Strings are compared exactly (including the `\n` and
`\t` characters, and U+2014). Numbers are doubles; every integer here is exactly representable. A
case's `expect` is what the Kotlin implementation produces today.

The fixture is **golden**: it is rewritten only by the explicit opt-in switch described in
[`README.md`](README.md), and a changed expected value changes what users see and share - an owner
decision, not a test fix.

## 10. Platform notes (none verified on a device)

- **Ubuntu Touch.** There is no `ApplicationExitInfo` analogue (section 7). AppArmor confines where
  a report may be written and how it may be shared (content-hub only), so the QML page writes under
  the app's own data directory and shares through content-hub; both are `NEEDS-DEVICE-VALIDATION`.
  A JVM-cored click may use `crash-recovery`'s headless capture path once S1.2 exists (it does not
  yet).
- **iOS.** The unhandled-exception hook sees uncaught Kotlin exceptions only, not Swift traps,
  signals or jetsam kills; the report for those is a feature gap.
- **Desktop.** Exceptions on a UI event thread may behave differently; unverified.

## 11. Not covered (named follow-ups)

The recovery `Activity`'s layout and flow, the non-destructive quarantine (`Quarantine` /
`QuarantineTest`) and the `CrashSalvager` bridge are Android-bound and not specified here. The
quarantine's stash/restore/purge semantics are a candidate for a later revision.

## 12. Evidence

| Claim | Class |
|---|---|
| Kotlin implementation reproduces every fixture case | `CONTAINER-BUILD-ONLY` (JVM unit tests in `:crash-recovery:test`) |
| A QML / Swift / Swing implementation conforms | `NOT-APPLICABLE` here; `NEEDS-DEVICE-VALIDATION` on a device, owned by the consumer |
| Recovery from a real crash on any host | `NEEDS-DEVICE-VALIDATION` (D-P), owner on device |
