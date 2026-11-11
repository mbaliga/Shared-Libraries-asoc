package dev.aarso.cellshell.core

import kotlin.math.abs

/** Which room a settle should land in. Mirrors `dev.aarso.cellshell.RoomEdge`. */
enum class RoomEdge { HOME, LEFT, RIGHT, TOP, BOTTOM }

/** How the home card leaves the screen when a room opens. Mirrors `dev.aarso.cellshell.ParkStyle`. */
enum class ParkStyle { SLIDE, SWIVEL }

/** Which edge of the parked card is still on screen (the grab band). START is left/top. */
enum class BandEdge { START, END, NONE }

/**
 * The motion constants of the constellation's navigation, as plain numbers. They are a contract,
 * not defaults: the Compose `SpatialMotion` in `:cell-shell` builds its settle spec from
 * [SETTLE_MS] and the easing control points below.
 */
object SpatialMotion {
    /** Settle duration in ms. Eased, no spring: a room is arrived at, it does not bounce. */
    const val SETTLE_MS = 320

    /** Settle easing: cubic-bezier(0.4, 0, 0.2, 1). */
    const val EASE_X1 = 0.4f
    const val EASE_Y1 = 0f
    const val EASE_X2 = 0.2f
    const val EASE_Y2 = 1f

    const val PARK_SCALE_DROP = 0.10f
    const val PARK_ELEVATION = 24f
    const val PARK_CORNER_DP = 20f
    const val PARK_RING_ALPHA = 0.6f
    const val PARK_SCRIM_ALPHA = 0.62f
    const val PARK_SWIVEL_DEG = 22f
    const val PARK_CAMERA_DISTANCE_CARDS = 3f

    /** Past this fraction of travel, release completes the transition rather than retreating. */
    const val SETTLE_THRESHOLD = 0.5f

    /** A flick faster than this (px per frame) decides direction by itself. */
    const val FLICK_VELOCITY_PX = 2f

    /** A top/bottom room opens over this fraction of the height. */
    const val VERTICAL_TRAVEL_FRACTION = 0.7f
}

/**
 * Where a released drag lands: a deliberate flick wins over distance, otherwise the nearer end.
 *
 * @param value where the axis sits, in [-1, 1].
 * @param lastDelta the most recent frame's movement in px; sign is direction.
 */
fun settleTarget(value: Float, lastDelta: Float): Float {
    if (value in -0.001f..0.001f) return 0f
    return if (abs(lastDelta) > SpatialMotion.FLICK_VELOCITY_PX) {
        if (lastDelta > 0) {
            if (value >= 0f) 1f else 0f
        } else {
            if (value <= 0f) -1f else 0f
        }
    } else {
        when {
            value > SpatialMotion.SETTLE_THRESHOLD -> 1f
            value < -SpatialMotion.SETTLE_THRESHOLD -> -1f
            else -> 0f
        }
    }
}

/** How far the parked card travels so its trailing edge stays on screen as a grab band. */
fun parkDistance(extent: Float, scale: Float, bandPx: Float): Float =
    extent * (1f + scale) / 2f - bandPx

/** The edge left on screen when an axis is pushed to [progress]. */
fun bandEdge(progress: Float): BandEdge = when {
    progress > 0.01f -> BandEdge.START
    progress < -0.01f -> BandEdge.END
    else -> BandEdge.NONE
}

/** The swivel hinge as a `transformOrigin` fraction of the un-scaled card. */
fun hingePivot(edge: BandEdge, scale: Float): Float = when (edge) {
    BandEdge.START -> (1f - scale) / 2f
    BandEdge.END -> (1f + scale) / 2f
    BandEdge.NONE -> 0.5f
}

/**
 * How closed the home face's veil is at [lift]: nothing until the settle threshold, then linear
 * to fully closed at full park. A pure function of the current lift, so nothing latches.
 */
fun parkVeil(lift: Float): Float {
    val onset = SpatialMotion.SETTLE_THRESHOLD
    val span = (1f - onset).coerceAtLeast(0.001f)
    return ((lift - onset) / span).coerceIn(0f, 1f)
}
