package dev.aarso.interactionmode

/**
 * The constellation's pure policy for what [InteractionMode] to report before the user has made
 * an explicit choice (see [InteractionModeStore.hasExplicitChoice]).
 *
 * Policy (2026-09-15 ruling):
 *  - A fresh install with no explicit choice and no legacy signal defaults to
 *    [InteractionMode.REGULAR].
 *  - An app MAY pass `legacySignal = true` when the install predates the Regular/asoc
 *    bifurcation — i.e. the user has already been living in the rooms model without ever having
 *    "chosen" it — so that install continues into [InteractionMode.ASOC] instead of silently
 *    dropping an existing rooms user into an unfamiliar layout. What counts as "predates the
 *    bifurcation" is app-specific (e.g. a pre-existing first-run marker) and is entirely the
 *    caller's judgment to make; this function only encodes what to *do* with that answer.
 *
 * [defaultFor] is pure and total over all four combinations of its two booleans — including
 * `hasExplicitChoice = true`, which in normal use a caller should never need (an explicit choice
 * should be read straight from the store, not recomputed here). That branch is still defined,
 * and pinned by test, for a reason worth stating explicitly: **a legacy signal must never
 * override an explicit user choice.** Passing `hasExplicitChoice = true` therefore always
 * yields [InteractionMode.REGULAR] regardless of `legacySignal` — a safe, predictable result if
 * a caller ever mis-wires this rather than reading the store directly.
 */
object ModeDefaults {

    fun defaultFor(hasExplicitChoice: Boolean, legacySignal: Boolean): InteractionMode =
        if (!hasExplicitChoice && legacySignal) InteractionMode.ASOC else InteractionMode.REGULAR
}
