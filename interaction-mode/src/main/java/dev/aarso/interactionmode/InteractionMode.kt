package dev.aarso.interactionmode

/**
 * The constellation-wide choice of interaction pattern for a host app's top-level chrome.
 *
 * Per the 2026-09-15 ruling: the "rooms" spatial pattern remains somewhat experimental while
 * it's being perfected, so every constellation app lets the user choose between a conventional
 * layout and the experimental one, rather than defaulting everyone onto whichever is newest.
 * The UI-facing names are exactly "Regular" and "asoc" (lowercase) — this enum is the
 * machine-facing pair those names map to. Where that choice is picked and rendered is
 * deliberately **not** this module's concern; see [InteractionModeStore] and this repo's
 * README for where the picker itself lives (Hyle).
 */
enum class InteractionMode {
    /**
     * Conventional navigation chrome: a traditional layout with visible tabs, buttons and
     * menus. A screen MAY still offer gestures, but only ever as a supplement — everything
     * reachable by gesture must also be reachable through a visible control.
     */
    REGULAR,

    /**
     * The spatial-rooms layout and its gesture grammar (edge scrub, word-wheel rail, room
     * transitions, and the rest of the constellation's motion vocabulary) —
     * **experimental and evolving**. This is the mode the ruling calls out as "still being
     * perfected"; expect its shape to shift between releases in ways [REGULAR] deliberately
     * does not.
     */
    ASOC,
}
