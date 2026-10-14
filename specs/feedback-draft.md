# Spec: feedback draft render and subject

Status: **DRAFT for owner review** (PORTING_PLAN.md S0.2; PD-4). Normative source: `feedback` 0.1.0,
`FeedbackDraft.kt` (pure; no `android.*`). That module lives on `main` and is not yet on the
`fonebrew-development-clzu43` line; the source text was read from `origin/main`.
Golden vectors: [`fixtures/feedback-draft.v1.json`](fixtures/feedback-draft.v1.json), asserted by
`feedback/.../SpecFixturesTest.kt` (inert until the two lines merge, see `README.md`).

MUST / SHOULD are used in the RFC 2119 sense.

## 1. What this spec is for

`feedback` is the constellation's opt-in feedback utility for experimental features. House doctrine
shapes it: **no backend, no telemetry, no phoning home.** It cannot send anything. It composes a draft
the user can *read in full* and hands it to a share chooser the **user** launches; delivery is theirs,
by whatever channel they pick, or not at all.

The draft text is therefore the whole payload, and it is pure and deterministic. A port - a QML page
on Ubuntu Touch handing the text to content-hub, a SwiftUI sheet handing it to the system share
sheet - conforms by producing byte-identical text and subject from the same fields, and by keeping
delivery user-launched. The opt-in ledger and the chooser are platform code and out of scope here.

## 2. Fields

| Field | Rule |
|---|---|
| `appName` | Required. Blank (empty or whitespace only) is **refused**. The app as the user knows it, never a package id |
| `appVersion` | The user-visible version string. Not validated; the app passes what it shows |
| `feature` | Required. Blank is **refused**. The feature as shown in the prompt |
| `message` | The user's own words, verbatim |
| `facts` | Zero or more `(label, value)` pairs, each explicitly supplied and shown to the user before sending. A fact whose `label` is blank is **refused** |

"Refused" means construction fails (the Kotlin type throws `IllegalArgumentException`); a port
refuses the same inputs in its own idiom. A fact the user was not shown cannot exist, by
construction: there is no device fingerprint, no identifier and no behind-the-scenes collection.

## 3. `render`

```
{appName} {appVersion} — feedback on: {feature}
<empty line>
{message with leading and trailing whitespace trimmed}
```

and, only when `facts` is non-empty, followed by:

```
<empty line>
Included details:
- {label}: {value}          <- one line per fact, in the order given
```

- Lines are separated by a single line feed `\n` on every platform (Kotlin `appendLine` is `\n`,
  not the OS line separator).
- The whole result then has **trailing whitespace removed** (`trimEnd`), so there is no final newline
  and a blank message renders the header line only.
- Only leading/trailing whitespace of `message` is trimmed. Inner characters and inner newlines are
  preserved verbatim, including `[ ] & < >`.
- The em dash is U+2014. UTF-8.
- **Deterministic**: the same draft always renders the same text. No clock, no randomness; a timestamp
  is the mail client's business and the user's.

### 3.1 The no-hidden-data property

Strip every supplied fragment (`appName`, `appVersion`, `feature`, the trimmed `message`, every fact
label and value) from a rendered draft and only these tokens may remain: `—`, `feedback`, `on:`,
`Included`, `details:`, `-`, `:`. The Kotlin test asserts this over the fixture's own inputs; a port
SHOULD assert it over the same inputs.

## 4. `subject`

`{appName} feedback: {feature}` - for mail-shaped deliveries only. No version, no message.

## 5. Delivery (platform-bound, not specified byte for byte)

| Platform | Mechanism | Evidence |
|---|---|---|
| Android | `Intent.ACTION_SEND` chooser, `text/plain`, subject + body | `CONTAINER-BUILD-ONLY` for the pure part; the chooser is `NEEDS-DEVICE-VALIDATION` |
| Ubuntu Touch | content-hub as the share channel (AppArmor confines sharing to it) | `NEEDS-DEVICE-VALIDATION`; no UT device on record (OQ-1) |
| iOS / iPadOS | `UIActivityViewController` | `NEEDS-DEVICE-VALIDATION` |
| Desktop | mail via the system handler, falling back to the clipboard | `NEEDS-DEVICE-VALIDATION` per OS |

Whatever the channel: the channel is the user's explicit pick every time, no default channel is
remembered anywhere, and a payload too long for the channel is never silently truncated (it goes to
the clipboard or a saved file, and the UI says so).

## 6. Fixture format

One JSON object: `spec`, `version`, `source`, `render` (cases with `input` = `app_name`,
`app_version`, `feature`, `message`, `facts[{label,value}]` and `expect` = `render` + `subject`), and
`refused` (inputs that must be refused). Strings compare exactly. The fixture is **golden** and is
rewritten only through the opt-in switch in [`README.md`](README.md).

## 7. Evidence

| Claim | Class |
|---|---|
| Kotlin implementation reproduces every fixture case | `CONTAINER-BUILD-ONLY`, verified in a scratch copy of `origin/main` (the module is not in this line's build) |
| A QML / Swift / other implementation conforms | `NOT-APPLICABLE` here; `NEEDS-DEVICE-VALIDATION` on a device, owned by the consumer |
