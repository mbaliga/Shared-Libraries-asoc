package dev.aarso.interactionmode

import android.content.SharedPreferences

/**
 * [InteractionModeStore] backed by [SharedPreferences] — no other dependency. Construct with a
 * prefs instance the host already owns:
 * ```
 * PrefsInteractionModeStore(context.getSharedPreferences("interaction_mode", Context.MODE_PRIVATE))
 * ```
 *
 * @param legacySignal the app's own answer to "does this install predate the Regular/asoc
 * bifurcation" (2026-09-15 ruling) — pass `true` only when the app can actually tell (e.g. it
 * already stores its own first-run/first-version marker). See [ModeDefaults] for exactly how
 * this is used, and note it only ever affects [mode] before the first [setMode] call.
 */
class PrefsInteractionModeStore(
    private val prefs: SharedPreferences,
    private val legacySignal: Boolean = false,
) : InteractionModeStore {

    override val hasExplicitChoice: Boolean
        get() = prefs.contains(KEY_MODE)

    override val mode: InteractionMode
        get() {
            val stored = prefs.getString(KEY_MODE, null)?.let { raw ->
                runCatching { InteractionMode.valueOf(raw) }.getOrNull()
            }
            // Falls through to the pure policy both for "never set" and for a corrupted/
            // unrecognized stored value (e.g. written by a future app version) — either way
            // hasExplicitChoice is passed through honestly, so ModeDefaults' "never let a
            // legacy signal override an explicit choice" guarantee still holds for corrupted
            // data, not just for the empty case.
            return stored ?: ModeDefaults.defaultFor(
                hasExplicitChoice = hasExplicitChoice,
                legacySignal = legacySignal,
            )
        }

    override fun setMode(mode: InteractionMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    private companion object {
        const val KEY_MODE = "interaction_mode"
    }
}
