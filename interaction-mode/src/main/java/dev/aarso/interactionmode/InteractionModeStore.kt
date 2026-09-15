package dev.aarso.interactionmode

/**
 * Where a host app reads and writes its [InteractionMode] choice.
 *
 * Deliberately UI-free: no Compose, no Views, no picker. The affordance that lets a user
 * actually choose "Regular" vs "asoc" lives in Hyle (`dev.aarso.hyle`) and calls through this
 * interface — it does not live in this module. See this repo's README, "`:interaction-mode`"
 * section, for the full picture.
 */
interface InteractionModeStore {

    /**
     * The mode a host should render with right now: the user's explicit choice if there is one
     * ([hasExplicitChoice] true), otherwise a computed default (see [ModeDefaults]).
     *
     * This is a plain getter rather than a `Flow`, to keep this module dependency-free (no
     * `kotlinx-coroutines-core`) — matching this repo's other zero-runtime-dependency modules
     * (`:search-core`, `:modelbench`). NAMED FOLLOW-UP, not a silent gap: a reactive wrapper
     * (`Flow`, or a small listener callback) is a natural, source-compatible addition on top of
     * this interface once a real consumer needs to react to changes instead of re-reading;
     * deliberately not built ahead of that need.
     */
    val mode: InteractionMode

    /**
     * True once [setMode] has actually persisted a choice; false while [mode] is only reporting
     * a computed default. This is exactly what lets a host tell "the user chose this" apart from
     * "nothing has been chosen yet" — e.g. to decide whether a first-run mode picker still needs
     * to be shown.
     */
    val hasExplicitChoice: Boolean

    /** Persist the user's explicit choice. After this returns, [hasExplicitChoice] is true. */
    fun setMode(mode: InteractionMode)
}
