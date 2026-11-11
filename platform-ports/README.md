# :platform-ports

`dev.aarso:platform-ports` — a Kotlin Multiplatform expect/actual library for the host things
every app needs and every platform does differently. Foundation item F6 of the porting program
(gate OQ-22, ruled 2026-10-06). Every part that is not real on a host is a **stub that says so**:
`isStub = true` plus the sentence a UI shows. `PlatformPorts.stubNotices()` is the one place that
lists them all.

| Part | Common API | JVM (desktop) | Android | iOS |
|---|---|---|---|---|
| Secret storage with a reported tier | `SecretStore`, `KeyStorageTier`, `report()` | **Real:** passphrase-protected file (AES-256-GCM, PBKDF2-SHA256) and owner-only file (not encrypted). OS keychain is a **visible stub** | stub | stub |
| App directories | `resolveAppDirectories` (pure) | **Real** (XDG, `~/Library`, `%APPDATA%`) | real (from `Context`), unbuilt here | stub |
| File picker | `FilePicker` | Swing `JFileChooser`; headless returns Unavailable | stub | stub |
| Share sheet | `ShareSheet` | **Stub** (no desktop share sheet) | stub | stub |
| Power / thermal | `PowerProbe`, `ThermalProbe` | Linux sysfs, real; other OSes Unavailable | stub | stub |
| Metered network | `NetworkProbe` | **Stub** | stub | stub |
| Bluetooth RFCOMM | `RfcommTransport`, `BluetoothAddress` (pure) | **Stub** | stub | stub |

## Key storage tiers (OQ-22)

`HARDWARE_BACKED` > `OS_KEYCHAIN` > `PASSPHRASE_FILE` > `APP_PRIVATE_FILE` > `MEMORY_ONLY` >
`UNAVAILABLE`. Each has a word, a shape glyph and a guarantee sentence (colour is never the signal,
directive I-3). File tiers and below set `needsDisclosure`: the UI must show the weaker guarantee.
Nothing here ever sends a key anywhere; there is no network code and no telemetry in this module.
The default JVM store is the **app-private file** tier, which is **unencrypted**; pass a passphrase
(`JvmSecretStores.passphraseFile`) for the stronger file tier.

## Heuristics, not measurements

`thermalLevelFor` cut-offs (60/75/90 C) are our heuristic, not measured against a corpus; a UI that
shows the level should say so.

## Evidence (directive I-4)

- `CI (hosted VM)`-class only; this was written in a container with JDK 17, no display, no Android
  SDK, no Mac, no Windows. Label: `CONTAINER-BUILD-ONLY`.
- Verified: common + JVM tests (`./gradlew :platform-ports:jvmTest`), 57 tests.
- **Not verified:** the `androidMain` source and the guarded Android block in `build.gradle.kts` (never compiled);
  `iosMain` and the iOS targets (Kotlin/Native iOS is disabled on Linux); the Swing dialog path
  (needs a display); Windows/macOS behaviour of file permissions and sysfs probes (Linux only).
  Anything on a device is `NEEDS-DEVICE-VALIDATION`.

## Build

Plain `kotlin("multiplatform")`, no version in the module (taken from the root's Kotlin plugin
classpath). The Android target is added only when an Android SDK is found (`ANDROID_HOME`,
`ANDROID_SDK_ROOT` or `local.properties`), so the JVM tests run on a bare JDK.
