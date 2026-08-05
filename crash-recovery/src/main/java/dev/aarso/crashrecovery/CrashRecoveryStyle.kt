package dev.aarso.crashrecovery

import android.graphics.Color

/**
 * The one thing a host app overrides: its accent. Everything else — the paper/ink neutral
 * surface, in light and dark — the module ships, so the recovery screen looks considered on
 * its own and identical across apps that don't care to theme it.
 *
 * A host supplies an accent (and its contrasting on-accent), optionally a different pair for
 * dark mode; pass nothing for the neutral default (accent = ink). Deliberately not tied to
 * any design system's token type, so a Hyle consumer and a non-Hyle app (Animalcules,
 * Horizkeeb — see D-L) can both pass plain `@ColorInt Int`s without taking a dependency on
 * anything beyond this module. Light vs dark is resolved at display time from the device's
 * night setting (see [resolve]).
 */
data class CrashRecoveryStyle(
    val accentLight: Int? = null,
    val onAccentLight: Int? = null,
    val accentDark: Int? = null,
    val onAccentDark: Int? = null,
) : java.io.Serializable {

    /** A fully-resolved set of colours for one mode — what the screen actually paints with. */
    data class Palette(
        val paper: Int,
        val ink: Int,
        val inkSoft: Int,
        val line: Int,
        val card: Int,
        val accent: Int,
        val onAccent: Int,
        val monoBg: Int,
        val monoInk: Int,
        val danger: Int,
    )

    /** The neutral palette for [night], with the host's accent pair applied if supplied. */
    fun resolve(night: Boolean): Palette =
        if (night) {
            Palette(
                paper = 0xFF131210.toInt(),
                ink = 0xFFECE8DF.toInt(),
                inkSoft = 0xFFA39C8E.toInt(),
                line = 0x29ECE8DF,
                card = 0x0EECE8DF,
                accent = accentDark ?: 0xFFECE8DF.toInt(),
                onAccent = onAccentDark ?: 0xFF131210.toInt(),
                monoBg = 0xFF0A0908.toInt(),
                monoInk = 0xFFD8D2C6.toInt(),
                danger = 0xFFE0796B.toInt(),
            )
        } else {
            Palette(
                paper = 0xFFF7F4EE.toInt(),
                ink = 0xFF191611.toInt(),
                inkSoft = 0xFF5C564B.toInt(),
                line = 0x24191611,
                card = 0x0B191611,
                accent = accentLight ?: 0xFF191611.toInt(),
                onAccent = onAccentLight ?: 0xFFF7F4EE.toInt(),
                monoBg = 0xFF14120E.toInt(),
                monoInk = 0xFFE8E3D8.toInt(),
                danger = 0xFFC0453A.toInt(),
            )
        }

    companion object {
        /** Neutral: the accent is the ink itself, in both modes. */
        val Default = CrashRecoveryStyle()

        /**
         * A host accent. Supply a light pair; the same is reused for dark unless a dark pair
         * is given. Colours are plain `@ColorInt` — e.g. `Color.parseColor("#5E48E8")`.
         */
        fun accent(
            light: Int,
            onLight: Int,
            dark: Int = light,
            onDark: Int = onLight,
        ): CrashRecoveryStyle = CrashRecoveryStyle(light, onLight, dark, onDark)

        /** Convenience for callers holding hex strings. */
        fun accent(light: String, onLight: String, dark: String = light, onDark: String = onLight): CrashRecoveryStyle =
            accent(Color.parseColor(light), Color.parseColor(onLight), Color.parseColor(dark), Color.parseColor(onDark))
    }
}
