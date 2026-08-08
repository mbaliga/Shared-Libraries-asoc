package dev.aarso.diagnostics

import dev.aarso.diagnostics.core.Profile
import dev.aarso.diagnostics.core.Profiles
import dev.aarso.diagnostics.core.Redactor

/**
 * Per-app configuration. The one field that matters most is [profile]: it decides what is measured,
 * what the budgets mean, and which invariants must hold. Choosing the wrong one is the difference
 * between a report about your app and a report about a screen your app happens to have.
 */
data class Config(
    /**
     * Pick by what the app IS, not by what it is written in:
     *   Profiles.ui()             Activity-hosted apps — Fonebrew, Nooz, Crocodyl's shell
     *   Profiles.ime()            input methods — Clackpad
     *   Profiles.wallpaper()      WallpaperService engines — Animalcules
     *   Profiles.audio()          instrument/audio apps — Bocal, the haptics workbench
     *   Profiles.visionPipeline() camera + inference — Crocodyl
     *   Profiles.stream()         BLE sensor streams — the EEG work
     *   Profiles.service()        headless daemons — ASOM
     */
    val profile: Profile = Profiles.ui(),

    /** Memory/thermal sampling period. 500 ms is ~2 samples/s: enough for a trend, cheap enough to ignore. */
    val sampleIntervalMs: Long = 500,

    /** How often aggregates are written to the journal, so a killed process still yields evidence. */
    val journalIntervalMs: Long = 10_000,
    val journalEnabled: Boolean = true,

    val logRingCapacity: Int = 512,

    /**
     * Per-series observation capacity. ~20 000 is about 3 minutes of 120 Hz frames, but an audio
     * callback at 500 Hz or an EEG stream at 256 Hz fills it far faster — size it against the
     * profile's actual rate, and note the report declares how many observations aged out.
     */
    val observationCapacity: Int = 20_000,

    val autoSession: Boolean = true,
    val retainReports: Int = 20,
    val overlayOnLaunch: Boolean = false,

    /** Set false for a launcher or IME, where a stray broadcast mid-measurement matters. */
    val registerAdbReceiver: Boolean = true,

    val redactor: Redactor = Redactor.default(),
)
