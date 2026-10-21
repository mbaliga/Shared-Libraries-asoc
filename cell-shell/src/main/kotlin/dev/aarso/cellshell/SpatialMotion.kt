package dev.aarso.cellshell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Which room a settle should land in.
 *
 * A "room" is a surface parked off one edge of home. Rooms are not screens: switching to one
 * never pushes a back-stack entry — the home surface stays alive and visible behind it, and
 * dragging it back is the way out.
 */
enum class RoomEdge { HOME, LEFT, RIGHT, TOP, BOTTOM }

/**
 * The motion constants of the constellation's navigation.
 *
 * These are lifted verbatim from Android-IDE-core's `ui/spatial/SpatialRoot.kt`, the pattern's
 * reference implementation. They are a **contract, not defaults**: the owner asked for one
 * navigation feel across every app, so an app that quietly tunes these has broken the thing
 * this module exists to guarantee.
 */
object SpatialMotion {

    /**
     * The settle. Eased cubic-bezier, ~320ms, **no spring** — the reference implementation is
     * explicit that release must not overshoot: a room is a place you arrive at, not something
     * that bounces into position.
     */
    val settleSpec = tween<Float>(durationMillis = 320, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f))

    /** How far the parked home card shrinks at full open. */
    const val PARK_SCALE_DROP = 0.10f

    /** Shadow under the parked card at full open, in raw elevation units. */
    const val PARK_ELEVATION = 24f

    /** Corner radius the parked card rounds to, in dp at full open. */
    const val PARK_CORNER_DP = 20f

    /** Alpha of the accent ring drawn around the parked card at full open. */
    const val PARK_RING_ALPHA = 0.6f

    /** Scrim over the parked card at full open — quiets its content so it cannot read as overlap. */
    const val PARK_SCRIM_ALPHA = 0.62f

    /**
     * How far the card turns about its hinge edge at full open, in degrees, under
     * [ParkStyle.SWIVEL].
     *
     * Deliberately small. The reference (Honor's Magic Portal) reads as a door easing open, not
     * as a card thrown edge-on; and every degree here is bought out of the grab band, since a
     * turned surface presents less of itself to the screen (see [swivelForeshorten]).
     *
     * Raised 10 -> 22 degrees (owner, 2026-08-15: *"I want the swivel to be more pronounced -- at
     * rest the pane must be more tilted"*). Ten degrees read as depth only in motion and looked
     * nearly flat once parked, which is precisely where the tilt is meant to be legible.
     *
     * The angle costs the grab band nothing, because the card hinges on the band edge itself
     * ([hingePivot]) and that edge therefore never moves. The ceiling is the camera: past roughly
     * 32 degrees at [PARK_CAMERA_DISTANCE_CARDS] the far edge crosses behind the camera plane,
     * where pointer mapping stops being meaningful. 22 leaves a deliberate margin under that.
     */
    const val PARK_SWIVEL_DEG = 22f

    /**
     * Perspective strength for the swivel, expressed in **card widths**.
     *
     * Compose's `cameraDistance` is in units of 72 pixels — it is an inch measure, not a pixel
     * one — and its default of 8f therefore puts the camera 576px from a pane that may be 1080px
     * wide. At that distance a modest rotation throws the near edge into violent perspective, and
     * past roughly 32 degrees the far edge crosses behind the camera plane entirely, where
     * pointer mapping stops being meaningful. Expressing the distance relative to the card and
     * resolving it at the call site keeps the perspective honest on any screen.
     */
    const val PARK_CAMERA_DISTANCE_CARDS = 3f

    /** Past this fraction of travel, release completes the transition rather than retreating. */
    const val SETTLE_THRESHOLD = 0.5f

    /** A flick faster than this decides direction by itself, regardless of distance travelled. */
    const val FLICK_VELOCITY_PX = 2f

    /** Vertical travel is shorter than horizontal — a top/bottom room opens over 70% of height. */
    const val VERTICAL_TRAVEL_FRACTION = 0.7f

    /**
     * The most of the screen a bottom peek may reveal, as a fraction of the reveal axis.
     *
     * A peek is a strip, not a half-open room: a request for more than this is clamped, and a
     * peek anywhere near the full stop would leave the two stops indistinguishable under a
     * thumb. Also the ceiling [settleTargetStaged] trusts — past it the peek is ignored.
     */
    const val MAX_PEEK_REVEAL = 0.5f

    /** The smallest peek the staged settle will honour; below it the stops are one stop. */
    const val MIN_PEEK_REVEAL = 0.02f

    /**
     * Slack when asking whether a drag has reached the peek stop, as a fraction of the reveal
     * axis. A release that animated to the peek and was caught again must read as AT the peek,
     * not a hair short of it.
     */
    const val STOP_EPSILON = 0.01f
}

/**
 * Drives the spatial shell: two axes, each in `[-1, +1]`, where 0 is home.
 *
 * `h`: −1 = the RIGHT room open … 0 = home … +1 = the LEFT room open.
 * `v`: −1 = the BOTTOM room open … 0 = home … +1 = the TOP room open.
 *
 * The sign convention reads as "which way the home card was pushed": dragging from the left
 * edge pushes home to the right (+h) and reveals the left room behind it.
 *
 * Every drag is applied 1:1 with the finger via [Animatable.snapTo], so the surface tracks
 * touch exactly and a gesture can be reversed mid-flight; only release animates.
 */
@Stable
class SpatialController(private val scope: CoroutineScope) {

    // The Animatables are PRIVATE on purpose. Exposing them would let any caller drive the
    // shell directly — bypassing the direction clamps (which are what keep a drag out of an
    // edge the host left empty), the flick bookkeeping that settleTarget depends on, and the
    // motion contract this module exists to enforce. Intent-level methods below are the whole
    // supported surface; `hProgress`/`vProgress` are read-only projections for rendering.
    private val hAnim = Animatable(0f)
    private val vAnim = Animatable(0f)

    /** −1 = RIGHT room open … 0 = home … +1 = LEFT room open. */
    val hProgress: Float get() = hAnim.value

    /** −1 = BOTTOM room open … 0 = home … +1 = TOP room open. */
    val vProgress: Float get() = vAnim.value

    /**
     * The measured shell, in px. Snapshot-backed so a size change invalidates anything reading
     * it, and write-closed so only the shell (which is the only thing that knows the real
     * bounds) can set it — a stale or foreign viewport silently corrupts every drag, because
     * drag deltas are expressed as fractions of it.
     */
    var viewport: IntSize by mutableStateOf(IntSize(1, 1))
        private set

    internal fun onMeasured(size: IntSize) {
        if (size.width > 0 && size.height > 0) viewport = size
    }

    private var lastHDelta = 0f
    private var lastVDelta = 0f

    /**
     * Where the bottom room's peek stop sits, as a fraction of the reveal axis ([vProgress] = −x),
     * or 0 when the shell has no bottom peek. Written only by the shell, which is the only thing
     * that knows the measured height, the band and the navigation-bar inset it is derived from.
     * Snapshot-backed because [bottomStage] reads it in composition.
     */
    internal var bottomPeekReveal: Float by mutableStateOf(0f)

    /**
     * The BOTTOM room's stage: [RoomStage.CLOSED], a [RoomStage.PEEK] strip, or [RoomStage.FULL].
     *
     * It is the stage a slow release at the current position would settle to, so it is also
     * meaningful mid-drag: it flips at the same lines the settle does. Read-only — drive the
     * room with [open], [peekBottom], [closeAll] or a drag. Without a `bottomPeek` on the shell
     * there is no PEEK and this is CLOSED or FULL. Only the bottom axis is staged; a TOP room
     * at [vProgress] > 0 reads CLOSED here.
     */
    val bottomStage: RoomStage
        get() = roomStageFor(-vProgress.coerceAtMost(0f), bottomPeekReveal)

    val atHome: Boolean get() = abs(hProgress) < 0.01f && abs(vProgress) < 0.01f

    /** Which room is fully open, if any. A bottom peek is not fully open: it reads HOME here. */
    val openRoom: RoomEdge
        get() = when {
            hProgress >= 0.999f -> RoomEdge.LEFT
            hProgress <= -0.999f -> RoomEdge.RIGHT
            vProgress >= 0.999f -> RoomEdge.TOP
            vProgress <= -0.999f -> RoomEdge.BOTTOM
            else -> RoomEdge.HOME
        }

    /** True while any room is even partially revealed — the card is lifted. */
    val anyRoomVisible: Boolean get() = abs(hProgress) > 0.001f || abs(vProgress) > 0.001f

    /** 0 at home, 1 at full open on whichever axis has travelled furthest. */
    val lift: Float get() = maxOf(abs(hProgress), abs(vProgress))

    /**
     * Apply one frame of horizontal drag, 1:1 with the finger.
     *
     * @param min the most negative the axis may reach — pass 0f when there is no RIGHT room,
     *   which is what stops a drag revealing an edge the host never supplied.
     * @param max likewise the most positive; 0f when there is no LEFT room.
     */
    fun dragH(deltaPx: Float, min: Float, max: Float) {
        lastHDelta = deltaPx
        // coerceAtLeast(1) so a pre-measure drag divides by a sane extent rather than zero.
        val next = (hProgress + deltaPx / viewport.width.coerceAtLeast(1)).coerceIn(min, max)
        scope.launch { hAnim.snapTo(next) }
    }

    /** Vertical counterpart of [dragH]; `min`/`max` gate the BOTTOM and TOP rooms. */
    fun dragV(deltaPx: Float, min: Float, max: Float) {
        lastVDelta = deltaPx
        val extent = viewport.height.coerceAtLeast(1) * SpatialMotion.VERTICAL_TRAVEL_FRACTION
        val next = (vProgress + deltaPx / extent).coerceIn(min, max)
        scope.launch { vAnim.snapTo(next) }
    }

    /** Release the horizontal axis; it animates to whichever end [settleTarget] chooses. */
    fun settleH() {
        scope.launch { hAnim.animateTo(settleTarget(hProgress, lastHDelta), SpatialMotion.settleSpec) }
    }

    /**
     * Release the vertical axis. With a bottom peek configured the BOTTOM room has three stops,
     * so the release lands on the nearest of closed, peek and full ([settleTargetStaged]);
     * otherwise it is the two-stop [settleTarget] every consumer has always had.
     */
    fun settleV() {
        scope.launch {
            vAnim.animateTo(settleTargetStaged(vProgress, lastVDelta, bottomPeekReveal), SpatialMotion.settleSpec)
        }
    }

    /**
     * Open the BOTTOM room to its peek strip without a gesture. When the shell has no bottom peek
     * this is [open] with [RoomEdge.BOTTOM], so a host can call it unconditionally.
     */
    fun peekBottom() {
        val peek = bottomPeekReveal
        val target = if (isStagedPeek(peek)) -peek else -1f
        scope.launch { vAnim.animateTo(target, SpatialMotion.settleSpec) }
    }

    /** Open a room without a gesture — for a tap on a host's own affordance. */
    fun open(edge: RoomEdge) {
        scope.launch {
            when (edge) {
                RoomEdge.LEFT -> hAnim.animateTo(1f, SpatialMotion.settleSpec)
                RoomEdge.RIGHT -> hAnim.animateTo(-1f, SpatialMotion.settleSpec)
                RoomEdge.TOP -> vAnim.animateTo(1f, SpatialMotion.settleSpec)
                RoomEdge.BOTTOM -> vAnim.animateTo(-1f, SpatialMotion.settleSpec)
                RoomEdge.HOME -> closeAll()
            }
        }
    }

    /**
     * Return to home on both axes, from any stage — a peek or a full room alike. Also what a tap
     * on the parked card does.
     */
    fun closeAll() {
        scope.launch { hAnim.animateTo(0f, SpatialMotion.settleSpec) }
        scope.launch { vAnim.animateTo(0f, SpatialMotion.settleSpec) }
    }
}

/**
 * Where a released drag lands. Pure, so the feel of the gesture is unit-testable without a
 * device — which matters because "it settles wrong" is the kind of thing nobody writes a bug
 * report about, they just find the app annoying.
 *
 * A deliberate flick wins over distance: past [SpatialMotion.FLICK_VELOCITY_PX] the direction
 * of travel decides, so a fast short flick opens (or dismisses) rather than snapping back
 * because the finger did not cross the halfway line. Otherwise the nearer end wins.
 *
 * @param value where the axis currently sits, in [-1, 1].
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

/**
 * The stage of a staged room: shut, a strip showing, or all of it.
 *
 * Only the bottom room is staged, and only when the shell is given a `bottomPeek`; see
 * [SpatialController.bottomStage].
 */
enum class RoomStage { CLOSED, PEEK, FULL }

/** Whether [peek] is a usable peek stop. NaN and out-of-range values mean "no peek". */
internal fun isStagedPeek(peek: Float): Boolean =
    peek >= SpatialMotion.MIN_PEEK_REVEAL && peek <= SpatialMotion.MAX_PEEK_REVEAL

/**
 * [settleTarget] for an axis whose NEGATIVE side has a third stop: a peek at `-peek`.
 *
 * The stops are 0 (closed), `-peek` and −1 (full). The rules mirror the two-stop settle:
 *
 * - A deliberate flick (past [SpatialMotion.FLICK_VELOCITY_PX]) decides direction. Up — towards
 *   more reveal, so a negative [lastDelta] — lands on the next stop above where the finger is:
 *   the peek if it has not been reached, otherwise full. Down closes, from anywhere, as a flick
 *   down has always dismissed a room.
 * - A slow release snaps to the nearest stop. Each boundary is the midpoint between two stops
 *   and, as in [settleTarget], standing exactly on it retreats to the lower stop.
 *
 * A [peek] outside [SpatialMotion.MIN_PEEK_REVEAL]..[SpatialMotion.MAX_PEEK_REVEAL], and any
 * [value] on the positive side, fall straight through to [settleTarget], so a consumer with no
 * peek sees the settle it always had.
 *
 * @param value where the axis sits, in [-1, 1].
 * @param lastDelta the most recent frame's movement in px; negative is finger-up.
 * @param peek the peek stop as a positive fraction of the reveal axis.
 */
fun settleTargetStaged(value: Float, lastDelta: Float, peek: Float): Float {
    if (!isStagedPeek(peek) || value > -0.001f) return settleTarget(value, lastDelta)
    val reveal = -value
    val stop = if (abs(lastDelta) > SpatialMotion.FLICK_VELOCITY_PX) {
        when {
            lastDelta >= 0f -> 0f
            reveal < peek - SpatialMotion.STOP_EPSILON -> peek
            else -> 1f
        }
    } else {
        when {
            reveal > (1f + peek) / 2f -> 1f
            reveal > peek / 2f -> peek
            else -> 0f
        }
    }
    return -stop
}

/**
 * The stage a slow release at [reveal] would settle to — [settleTargetStaged] without the
 * velocity, so the two agree on every boundary.
 *
 * @param reveal how far the bottom room is out, 0..1 (negated [SpatialController.vProgress]).
 * @param peek the peek stop as a fraction of the reveal axis; unusable means no PEEK stage.
 */
fun roomStageFor(reveal: Float, peek: Float): RoomStage {
    val r = reveal.coerceIn(0f, 1f)
    return if (!isStagedPeek(peek)) {
        if (r > SpatialMotion.SETTLE_THRESHOLD) RoomStage.FULL else RoomStage.CLOSED
    } else {
        when {
            r > (1f + peek) / 2f -> RoomStage.FULL
            r > peek / 2f -> RoomStage.PEEK
            else -> RoomStage.CLOSED
        }
    }
}

/**
 * How much of the BOTTOM room shows below the parked card when it is [reveal] out — the height of
 * the strip a peek actually presents, in px.
 *
 * This is not `reveal * height`. The card is scaled about its centre and parked by
 * [parkDistance], and the shell reads `scale = 1 - drop * reveal`, so its lower edge sits at
 * `(1 - r) * h * (1 - drop * r / 2) + r * band`. The room's own top is hidden behind the card by
 * the difference, which is small but is a few dp of a strip only a few dozen dp tall.
 */
internal fun parkedStripPx(
    reveal: Float,
    heightPx: Float,
    bandPx: Float,
    scaleDrop: Float = SpatialMotion.PARK_SCALE_DROP,
): Float {
    val r = reveal.coerceIn(0f, 1f)
    val halfScale = 1f - scaleDrop * r / 2f
    val cardBottom = (1f - r) * heightPx * halfScale + r * bandPx
    return heightPx - cardBottom
}

/**
 * The reveal at which [parkedStripPx] equals [stripPx] — where the peek stop goes so that the
 * strip is the height the host asked for. Inverts [parkedStripPx] in closed form (it is a
 * quadratic in the reveal) and clamps to [SpatialMotion.MAX_PEEK_REVEAL]. Returns 0 — no peek —
 * for a non-positive strip or an unmeasured height.
 */
internal fun peekReveal(
    stripPx: Float,
    heightPx: Float,
    bandPx: Float,
    scaleDrop: Float = SpatialMotion.PARK_SCALE_DROP,
): Float {
    if (stripPx <= 0f || heightPx <= 1f) return 0f
    // strip(r) = -k*h*r^2 + (h*(1+k) - band)*r, with k = scaleDrop / 2.
    val k = scaleDrop / 2f
    val b = heightPx * (1f + k) - bandPx
    val a = k * heightPx
    val raw = if (a < 1e-6f) {
        stripPx / b
    } else {
        val disc = b * b - 4f * a * stripPx
        if (disc < 0f) SpatialMotion.MAX_PEEK_REVEAL else (b - sqrt(disc)) / (2f * a)
    }
    return raw.coerceIn(0f, SpatialMotion.MAX_PEEK_REVEAL)
}

/**
 * Whether a touch at [y] may start a TOP-edge pull: within [edgePx] below [topInsetPx], the
 * status bar and anything the host reserved. Pure so the zone arithmetic is testable.
 */
internal fun inTopEdgeZone(y: Float, topInsetPx: Float, edgePx: Float): Boolean =
    y >= topInsetPx && y <= topInsetPx + edgePx

/**
 * Whether a touch at [y] may start a BOTTOM-edge pull: the [edgePx] band that ENDS at the
 * navigation bar, `[height - bottomInsetPx - edgePx, height - bottomInsetPx]`.
 *
 * The bar itself is never a legal origin, for the same reason the status bar is not for the top
 * edge: the system owns those pixels (the 3-button bar, or the gesture-navigation home swipe) and
 * an app cannot out-consume it. Without the inset the zone was the window's last [edgePx], of
 * which a 48dp bar left an 8dp strip.
 */
internal fun inBottomEdgeZone(y: Float, heightPx: Float, bottomInsetPx: Float, edgePx: Float): Boolean {
    val floor = heightPx - bottomInsetPx.coerceAtLeast(0f)
    return y <= floor && y >= floor - edgePx
}

/**
 * How far the parked card travels so its trailing edge stays on screen as a grab band.
 *
 * The card is scaled down, so its half-width shrinks too; parking it by the full extent would
 * push it entirely off screen and leave no way back except a system gesture.
 *
 * @param extent the viewport's width or height in px.
 * @param scale the card's current scale.
 * @param bandPx how much of the card must remain visible.
 */
fun parkDistance(extent: Float, scale: Float, bandPx: Float): Float =
    extent * (1f + scale) / 2f - bandPx

/**
 * How the home card leaves the screen when a room opens.
 *
 * Both styles shrink the card — the shrink is what makes it read as a card rather than as the
 * screen sliding away, and it is common to both. They differ in whether the card also *turns*.
 *
 * @see SLIDE
 * @see SWIVEL
 */
enum class ParkStyle {
    /**
     * Shrink and slide. The card stays flat to the screen and translates towards its band.
     *
     * The default, and unchanged from what the shell has always done, so an app that says nothing
     * keeps exactly the motion it had.
     */
    SLIDE,

    /**
     * Shrink and swivel. The card additionally turns about the hinge edge that stays on screen,
     * so it reads as a panel swinging open rather than a rectangle sliding off — the Magic Portal
     * shape (owner, 2026-08-14: *"add the swivel while keeping the slight shrink"*).
     *
     * The hinge is always the edge that remains visible as the grab band, which is the edge the
     * user would physically be holding: opening the room on the right hinges the card's left
     * edge, and vice versa.
     */
    SWIVEL,
}

/**
 * Which edge of the parked card is the one still on screen — the grab band.
 *
 * Named START/END rather than left/right because it serves both axes: on the horizontal axis
 * START is the card's left edge, on the vertical its top.
 */
enum class BandEdge { START, END, NONE }

/**
 * The edge left on screen when an axis is pushed to [progress].
 *
 * Positive progress pushes the card towards the far end of the axis, so what stays behind is its
 * near ([BandEdge.START]) edge. This reads backwards at a glance, which is exactly why it lives
 * in one function: the shell needs this same answer in three places — where to draw the grip
 * pill, which edge to hinge the swivel on, and which way that hinge turns — and it previously
 * derived it independently in each. Two of the three agreed; the swivel did not, and hinged on
 * the edge that was off screen, which threw the visible sliver out through the perspective
 * divide and made the parked card look like it had vanished entirely.
 */
fun bandEdge(progress: Float): BandEdge = when {
    progress > 0.01f -> BandEdge.START
    progress < -0.01f -> BandEdge.END
    else -> BandEdge.NONE
}

/**
 * Where to put the swivel's hinge, as a `transformOrigin` fraction of the **un-scaled** card.
 *
 * The scale lives in its own layer inside the swivel one, so this fraction is measured against
 * the card's full extent while the card actually drawn is [scale] of it, centred. Hence the
 * `(1 ± scale) / 2` rather than a plain 0 or 1: those would put the hinge on the *box's* edge,
 * a half-shrink outside the card, and rotate the card about a line it does not touch.
 *
 * With the hinge on the visible edge, that edge sits at z = 0 through the whole rotation and the
 * projection leaves it exactly where it was — so the band the user sees stays exactly [BAND_DP]
 * wide at any swivel angle, and SLIDE and SWIVEL park to the identical depth. This is why the
 * shell needs no foreshortening compensation: hinge it correctly and there is nothing to
 * compensate for.
 */
fun hingePivot(edge: BandEdge, scale: Float): Float = when (edge) {
    BandEdge.START -> (1f - scale) / 2f
    BandEdge.END -> (1f + scale) / 2f
    BandEdge.NONE -> 0.5f
}

/** Remembers a [SpatialController] scoped to the composition. The shell's entry point. */
@Composable
fun rememberSpatialController(): SpatialController {
    val scope = rememberCoroutineScope()
    return remember(scope) { SpatialController(scope) }
}
