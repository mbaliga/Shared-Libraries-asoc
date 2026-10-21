package dev.aarso.cellshell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Thickness of the touch strip -- its width on [ScrubberEdge.LEFT] / [ScrubberEdge.RIGHT], its
 * height on [ScrubberEdge.TOP] / [ScrubberEdge.BOTTOM]. Wide enough to hit without looking,
 * narrow enough to ignore.
 */
private const val STRIP_WIDTH_DP = 44f

/**
 * Height of the strip's gesture-exclusion band, in dp.
 *
 * 200dp is Android's own ceiling for `systemGestureExclusion` per edge -- anything requested past
 * it is silently dropped, honoured only from the bottom of the request upward. The strip itself
 * runs the caller's full height (easily 400-600dp on a phone), so excluding the whole strip would
 * ask for far more than the cap and leave most of its own length unreachable regardless -- the
 * same reason SpatialShell centres its own edge exclusion in a fixed band rather than its full
 * edge. Centring a band this size on the strip keeps the request within budget everywhere a scrub
 * can start, instead of granting reachability only near whichever end the platform happens to keep.
 */
private val EXCLUSION_SPAN_DP = 200.dp

/** How far the hairline track sits in from the screen edge. */
private const val TRACK_INSET_DP = 7f

/** Thickness of the hairline track the ticks live on. */
private const val TRACK_WIDTH_DP = 1.5f

/** Length of a stop's tick, drawn from the track toward the inside of the screen. */
private const val TICK_LENGTH_DP = 5f

/** Length of the marker showing the current position — longer than a tick so it reads as "you". */
private const val MARKER_LENGTH_DP = 13f

/** Thickness of that marker. */
private const val MARKER_WIDTH_DP = 2.5f

/**
 * Minimum distance between two drawn labels along a vertical strip ([ScrubberEdge.LEFT] /
 * [ScrubberEdge.RIGHT]).
 *
 * This is the whole anti-overlap mechanism: a label is drawn only if it clears the last one
 * drawn by this much, so it must stay comfortably above [LABEL_SP]'s line height.
 */
private const val LABEL_PITCH_DP = 14f

/**
 * The same minimum for a horizontal strip ([ScrubberEdge.TOP] / [ScrubberEdge.BOTTOM]), where
 * the thing that must not overlap is label *width*, not line height: `"Mar"` or `"2024"` at
 * [LABEL_SP] is about 22-26dp wide, so 40dp keeps a visible gap between neighbours.
 */
private const val LABEL_PITCH_H_DP = 40f

/**
 * Where labels sit, measured in from the strip's outer edge -- clear of the track and its ticks.
 * (On a vertical strip that is the right or left edge; on a horizontal one, the bottom or top.)
 */
private const val LABEL_INSET_DP = 15f

/** Label size. Deliberately small: the strip is a map of the list, not a second list. */
private const val LABEL_SP = 9f

/** Alpha of a tick belonging to a stop whose label was thinned away. */
private const val TICK_ALPHA = 0.55f

/** Gap between the strip's inner edge and the bubble that floats beside the finger. */
private const val BUBBLE_GAP_DP = 12f

private const val BUBBLE_PAD_H_DP = 12f
private const val BUBBLE_PAD_V_DP = 7f
private const val BUBBLE_CORNER_DP = 10f
private const val BUBBLE_SP = 15f

/**
 * One labelled stop on the strip: a month, a letter, a size band.
 *
 * @param label what the bubble and the strip show for this stop — keep it short (`"Mar"`,
 *   `"2024"`, `"G"`, `"1 GB"`); the strip is [STRIP_WIDTH_DP]dp wide and long labels ellipsize.
 * @param itemIndex the index in the host's list where this stop begins.
 */
data class ScrubberStop(val label: String, val itemIndex: Int)

/**
 * The edge a [EdgeTimelineScrubber] sits on. [LEFT] and [RIGHT] strips run vertically (the full
 * height of their parent, [STRIP_WIDTH_DP]dp wide) and are scrubbed up and down; [TOP] and
 * [BOTTOM] strips run horizontally (the full width of their parent, [STRIP_WIDTH_DP]dp tall) and
 * are scrubbed left to right. In every case the start of the list is at the top / left end and
 * the last item at the bottom / right end.
 *
 * The enum names the strip's *shape and sides*, not its position: the host still places the
 * composable (`Modifier.align(...)`, padding for the insets) -- this only says which way it runs,
 * which side the track hugs, and which side the bubble opens toward.
 */
enum class ScrubberEdge { LEFT, RIGHT, TOP, BOTTOM }

/**
 * The Niagara-style edge scrubber: a narrow strip down an edge (the right one unless [edge] says
 * otherwise) that sweeps the whole list, with a bubble naming where the finger is.
 *
 * The strip is generic over what the stops mean, because the two hosts key it differently and
 * one of them changes its mind at runtime: Foto Xplorr's grid is keyed by date, while Fylz
 * re-keys the same list by letter, date or size as the sort changes. Nothing here knows which —
 * a stop is a label and the index it starts at, and everything else follows from that.
 *
 * ### The mapping
 *
 * Position maps to index linearly over the strip's length ([indexForFraction]) — the top of a
 * vertical strip (the left end of a horizontal one) is item 0, the bottom (right end) is the last
 * item, regardless of how the stops are distributed. Labels are then placed at the position
 * their own `itemIndex` maps back to, so the two agree: putting
 * the finger on "Mar" lands on March. The alternative — spacing labels evenly — reads more
 * tidily and lies, because a month with 400 photos would occupy the same travel as one with 3.
 *
 * ### Not overlapping
 *
 * That truthful placement means labels can bunch arbitrarily tightly, so they are thinned by a
 * greedy pass ([thinnedLabels]) that drops any label which would come within [LABEL_PITCH_DP] of
 * the last one drawn ([LABEL_PITCH_H_DP] on a horizontal strip, where labels collide by width
 * rather than by height). Every stop still gets a tick on the track, so a thinned-away stop is
 * visible as structure even when there is no room to name it.
 *
 * ### Gesture ownership
 *
 * The strip runs on the default (Main) pass, so [SpatialShell]'s edge drag — which runs on the
 * Initial pass because it competes for the same pixels as the content — gets first refusal, and
 * a change it has already consumed ends the scrub rather than being scrubbed *and* dragged.
 *
 * Within what is left, the strip does not claim a touch until the finger has travelled past the
 * platform's touch slop, and gives the gesture up entirely if that travel turns out to be
 * horizontal. Both matter because the strip is [STRIP_WIDTH_DP]dp wide and the full height of
 * the screen — a band that size catches plenty of touches that were never meant for it. So a
 * tap does nothing (it is not a scrub of zero length; it is a tap that missed), and a sideways
 * drag near the edge still reaches the shell. A deliberate vertical drag inside the band is the
 * one thing the strip takes, and it takes that one completely: the list underneath never
 * follows a scrub.
 *
 * None of the above matters on gesture navigation unless the strip also excludes itself from
 * Android's own edge swipe: a `systemGestureExclusion` band centred on the strip is what lets a
 * deliberate drag reach [pointerInput] at all instead of being read as "go back" before it gets
 * here, the same reachability problem [SpatialShell]'s rooms have on their edges. It is a band,
 * not the strip's full bounds, because the platform caps what any one edge can claim at 200dp
 * regardless of how tall this strip runs — see [EXCLUSION_SPAN_DP]. A [ScrubberEdge.TOP] or
 * [ScrubberEdge.BOTTOM] strip requests no exclusion at all: Android's edge swipes are the side
 * ones, so there is nothing to claim, and the host keeps a bottom strip clear of the gesture bar
 * with its own padding.
 *
 * @param edge which edge the strip sits on; see [ScrubberEdge]. The default [ScrubberEdge.RIGHT]
 *   is the strip as it was before this parameter existed, and behaves identically.
 * @param stops the labelled stops, **ascending by [ScrubberStop.itemIndex]**.
 * @param itemCount how many items the list holds; the strip's height maps onto `0 until itemCount`.
 * @param currentIndex the list's actual first-visible index — where the marker rests when nobody
 *   is touching the strip.
 * @param onScrubTo fired as the finger moves, once per index change (not per frame), so the host
 *   can scroll to that item. The host is expected to jump, not animate: this is finger-tracking.
 * @param inkColor the track, ticks and labels — the strip's quiet ink.
 * @param accentColor the bubble's fill and the current stop's highlight.
 * @param restAlpha how visible the strip is when untouched. Defaults to the constellation's
 *   shared edge-affordance alpha; pass a smaller value (or 0f) where the strip should stay out of
 *   the way until touched or until the list it describes is moving. It never affects hit testing
 *   -- the strip stays grabbable at any alpha, which is the point.
 * @param bubbleTextColor the label drawn on top of [accentColor]; the host owns this because only
 *   it knows what is readable on its own accent.
 */
@Composable
fun EdgeTimelineScrubber(
    stops: List<ScrubberStop>,
    itemCount: Int,
    currentIndex: Int,
    onScrubTo: (itemIndex: Int) -> Unit,
    inkColor: Color,
    accentColor: Color,
    bubbleTextColor: Color,
    modifier: Modifier = Modifier,
    restAlpha: Float = EDGE_REST_ALPHA,
    edge: ScrubberEdge = ScrubberEdge.RIGHT,
) {
    // Nothing to scrub: an empty list, or a list nobody has indexed yet. Drawing a live-looking
    // strip over it would be an affordance that does nothing.
    if (itemCount <= 0 || stops.isEmpty()) return

    val haptics = LocalHapticFeedback.current

    // The gesture below is installed once and must never be torn down mid-drag. Sort changes in
    // Fylz swap the entire stop list while a finger may be down, so the handler reads these
    // through rememberUpdatedState instead of capturing them as pointerInput keys. Kept as State
    // objects rather than `by` values because the derivation further down reads them too, and it
    // must observe them rather than close over one recomposition's snapshot.
    val stopsState = rememberUpdatedState(stops)
    val countState = rememberUpdatedState(itemCount)
    val currentIndexState = rememberUpdatedState(currentIndex)
    val onScrubToState = rememberUpdatedState(onScrubTo)
    // Read per gesture, never captured: the gesture is installed once (see above), and a host
    // may move the strip to another edge while the composable stays in the tree.
    val edgeState = rememberUpdatedState(edge)

    val pressedState = remember { mutableStateOf(false) }
    var pressed by pressedState
    // Finger position along the strip in px (y on a vertical strip, x on a horizontal one). Held
    // as a state object rather than a `by` delegate because it is read only from layout and draw
    // lambdas — the marker and the bubble follow the finger every frame without recomposing
    // anything.
    val touchAlong = remember { mutableFloatStateOf(0f) }
    // The index the finger is currently over. Written every time the finger crosses an item
    // boundary, which on a full-library sweep is many times per frame — so it is deliberately
    // NOT read in composition anywhere.
    val scrubIndex = remember { mutableIntStateOf(0) }

    val strength by animateFloatAsState(
        // At rest the strip is an edge affordance like the shell's peeks, so it defaults to their
        // alpha. A host may pass a lower [restAlpha] -- 0f included -- to keep the strip off a
        // busy surface until it is wanted.
        //
        // The press branch is what makes a zero rest alpha safe. Fading the strip out from
        // OUTSIDE, with a graphicsLayer on the caller's side, would hide the pixels while leaving
        // the 44dp touch target fully live: a drag down the right edge would then scrub a control
        // that never becomes visible, because the caller's alpha does not know about the press.
        // Handing the rest value in here keeps the two facts in one place.
        targetValue = if (pressed) 1f else restAlpha,
        // The constellation's settle curve, so the strip waking up feels like the same app as
        // the rooms opening. It is a fade, not a motion, but the timing is the family resemblance.
        animationSpec = SpatialMotion.settleSpec,
        label = "scrubberStrength",
    )

    // Which stop the strip is naming.
    //
    // While the finger is down the strip trusts itself, not the host: onScrubTo may be applied
    // asynchronously (a LazyListState scroll is a suspend call), and a bubble that lagged the
    // finger by a frame or two would feel broken even though the list is keeping up.
    //
    // Derived rather than computed inline so recomposition tracks the *stop*, not the index. A
    // sweep down a 20,000-item library changes the index hundreds of times per second and the
    // stop a dozen times in total; reading the index here would rebuild the whole strip — every
    // label, its layout, its measure — on essentially every frame of the drag.
    val shownStop by remember {
        derivedStateOf {
            val index = if (pressedState.value) scrubIndex.intValue else currentIndexState.value
            stopForIndex(stopsState.value, index)
        }
    }

    BoxWithConstraints(
        // The caller's modifier goes first so a host can override the width or the alignment;
        // our sizing is a default, not a rule.
        modifier = modifier
            .then(
                if (edge.runsVertically) {
                    Modifier.fillMaxHeight().width(STRIP_WIDTH_DP.dp)
                } else {
                    Modifier.fillMaxWidth().height(STRIP_WIDTH_DP.dp)
                },
            )
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    // Only gestures that BEGIN here, and only ones nobody upstream has taken:
                    // awaitFirstDown inside this modifier's own pointer scope guarantees the
                    // down is in our bounds, and requireUnconsumed yields to the shell's
                    // Initial-pass edge drag.
                    val down = awaitFirstDown(requireUnconsumed = true)
                    // `along` is the axis the strip runs on and `across` the one it is thin on:
                    // y and x on a vertical strip, the other way round on a horizontal one.
                    val vertical = edgeState.value.runsVertically
                    val lengthPx = (if (vertical) size.height else size.width).toFloat()
                    fun along(p: Offset) = if (vertical) p.y else p.x
                    fun across(p: Offset) = if (vertical) p.x else p.y

                    // Nothing is claimed and nothing moves until the finger proves intent by
                    // travelling past slop, vertically. Until then this loop is a bystander.
                    var claimed = false
                    // The stop under the finger when the scrub began. Seeded, not ticked: the
                    // haptic marks crossings, and buzzing on the first frame would fire on every
                    // gesture regardless of whether one was crossed.
                    var lastStopKey: Int? = null
                    var primed = false

                    fun scrubTo(pos: Float) {
                        touchAlong.floatValue = pos
                        val index = indexForFraction(pos / lengthPx, countState.value)
                        if (index != scrubIndex.intValue || !primed) {
                            scrubIndex.intValue = index
                            onScrubToState.value(index)
                        }
                        val key = stopForIndex(stopsState.value, index)?.itemIndex
                        // One tick per boundary crossed, never per frame: a finger resting inside
                        // a month is silent no matter how long it hovers or how it jitters.
                        if (primed && key != lastStopKey) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        lastStopKey = key
                        primed = true
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // Someone earlier in the pass took this pointer — most likely the shell
                        // deciding the finger is opening a room. Scrubbing on it too would move
                        // the list under a card that is already sliding away.
                        if (change.isConsumed) break
                        if (!change.pressed) break

                        if (!claimed) {
                            val dAlong = along(change.position) - along(down.position)
                            val dAcross = across(change.position) - across(down.position)
                            // Sideways: not a scrub. Leave without consuming so the gesture is
                            // still available to whatever wanted it.
                            if (abs(dAcross) > slop && abs(dAcross) > abs(dAlong)) break
                            if (abs(dAlong) <= slop) continue
                            claimed = true
                            pressed = true
                            lastStopKey = stopForIndex(
                                stopsState.value,
                                indexForFraction(along(down.position) / lengthPx, countState.value),
                            )?.itemIndex
                        }

                        scrubTo(along(change.position))
                        change.consume()
                    }
                    if (claimed) pressed = false
                }
            },
    ) {
        // Same edge-ownership problem as SpatialShell's rooms, on whichever side edge the host
        // places this strip: without it, Android's own edge-swipe claims the touch before the
        // pointerInput above ever sees it. Sized and centred rather than applied to the strip's
        // own fillMaxHeight bounds -- see EXCLUSION_SPAN_DP's own KDoc for why the whole strip
        // would ask for more than the platform's 200dp-per-edge cap grants. A top or bottom strip
        // has no such swipe to beat, so it asks for nothing.
        if (edge.excludesSystemGestures) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(STRIP_WIDTH_DP.dp)
                    .height(EXCLUSION_SPAN_DP)
                    .systemGestureExclusion(),
            )
        }

        val density = LocalDensity.current
        val vertical = edge.runsVertically
        // The strip's long axis and its thin one. For the vertical strips this is the height and
        // width the code used before [edge] existed.
        val lengthPx = (if (vertical) constraints.maxHeight else constraints.maxWidth).toFloat()
        val thicknessPx = (if (vertical) constraints.maxWidth else constraints.maxHeight).toFloat()
        val pitchPx = with(density) { labelPitchDp(edge).dp.toPx() }
        val labelInsetPx = with(density) { LABEL_INSET_DP.dp.toPx() }

        // Thinning depends only on the geometry, not on the finger, so it survives every frame of
        // a drag untouched.
        val labels = remember(stops, itemCount, lengthPx, pitchPx) {
            thinnedLabels(stops, itemCount, lengthPx, pitchPx)
        }

        Box(
            Modifier
                .fillMaxSize()
                // Alpha applied in the layer lambda rather than as a composed argument: the
                // wake-up fade then runs entirely in the draw phase.
                .graphicsLayer { alpha = strength },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val length = if (vertical) size.height else size.width
                val thickness = if (vertical) size.width else size.height
                // A point given as (position along the strip, position across it).
                fun at(along: Float, across: Float) =
                    if (vertical) Offset(across, along) else Offset(along, across)
                val track = trackAcrossPx(edge, thickness, TRACK_INSET_DP.dp.toPx())
                // +1 draws toward larger coordinates, -1 toward smaller: always toward the inside
                // of the screen, away from the edge the track hugs.
                val inward = edge.inwardSign
                drawLine(
                    color = inkColor.copy(alpha = TICK_ALPHA),
                    start = at(0f, track),
                    end = at(length, track),
                    strokeWidth = TRACK_WIDTH_DP.dp.toPx(),
                )
                // Every stop gets a tick, including the ones whose label was thinned away — the
                // run of ticks is what makes an unlabelled stretch read as "more of the same"
                // rather than as a gap in the timeline.
                val tickLength = TICK_LENGTH_DP.dp.toPx()
                for (stop in stops) {
                    val pos = itemCenterY(stop.itemIndex, itemCount, length)
                    drawLine(
                        color = inkColor.copy(alpha = TICK_ALPHA),
                        start = at(pos, track + inward * tickLength),
                        end = at(pos, track),
                        strokeWidth = TRACK_WIDTH_DP.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                // The marker. Reading touchAlong here — in the draw lambda — is what lets it
                // track the finger continuously between index changes instead of stepping item
                // by item.
                val markerPos = if (pressed) {
                    touchAlong.floatValue.coerceIn(0f, length)
                } else {
                    itemCenterY(currentIndex, itemCount, length)
                }
                drawLine(
                    color = accentColor,
                    start = at(markerPos, track + inward * MARKER_LENGTH_DP.dp.toPx()),
                    end = at(markerPos, track),
                    strokeWidth = MARKER_WIDTH_DP.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            for (stop in labels) {
                val active = stop.itemIndex == shownStop?.itemIndex
                val centerAlong = itemCenterY(stop.itemIndex, itemCount, lengthPx)
                BasicText(
                    text = stop.label,
                    style = TextStyle(
                        color = if (active) accentColor else inkColor,
                        fontSize = LABEL_SP.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    // Placed by hand: the label's own size is not known until it is measured, and it
                    // has to be centred on the position its index maps to. Reporting a 0×0 size
                    // keeps the labels out of the parent's sizing entirely — they are an overlay
                    // on the strip, not its contents.
                    modifier = Modifier.layout { measurable, _ ->
                        val placeable = measurable.measure(
                            Constraints(
                                maxWidth = labelMaxWidthPx(edge, thicknessPx, labelInsetPx, pitchPx),
                            ),
                        )
                        layout(0, 0) {
                            // The first and last stops sit half a row past the ends, so the
                            // placement clamps rather than letting them hang off the strip.
                            val at = labelPlacement(
                                edge = edge,
                                labelWidth = placeable.width,
                                labelHeight = placeable.height,
                                centerAlong = centerAlong,
                                lengthPx = lengthPx,
                                thicknessPx = thicknessPx,
                                insetPx = labelInsetPx,
                            )
                            placeable.place(x = at.x, y = at.y)
                        }
                    },
                )
            }
        }

        // ── The bubble ────────────────────────────────────────────────────────────────────
        // Only while touched, and only when there is something truthful to name: a scrub above
        // the first stop has no label, and an empty bubble is worse than none. It opens toward
        // the inside of the screen, opposite the side the track hugs.
        val bubbleStop = shownStop
        if (pressed && bubbleStop != null) {
            val gapPx = with(density) { BUBBLE_GAP_DP.dp.toPx() }
            Box(
                Modifier
                    // Same hand-placement trick as the labels, for the same reason plus one: the
                    // bubble sits to the LEFT of the strip, outside this composable's bounds, so
                    // it must not contribute to the strip's size. (Nothing clips it — the strip is
                    // an overlay on the host's list, and the bubble simply draws over the list.)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            Constraints(maxWidth = constraints.maxWidth * 6),
                        )
                        layout(0, 0) {
                            val at = bubblePlacement(
                                edge = edge,
                                bubbleWidth = placeable.width,
                                bubbleHeight = placeable.height,
                                touchAlong = touchAlong.floatValue,
                                lengthPx = lengthPx,
                                thicknessPx = thicknessPx,
                                gapPx = gapPx,
                            )
                            placeable.place(x = at.x, y = at.y)
                        }
                    }
                    .background(accentColor, RoundedCornerShape(BUBBLE_CORNER_DP.dp))
                    .padding(horizontal = BUBBLE_PAD_H_DP.dp, vertical = BUBBLE_PAD_V_DP.dp),
            ) {
                BasicText(
                    text = bubbleStop.label,
                    style = TextStyle(
                        color = bubbleTextColor,
                        fontSize = BUBBLE_SP.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * Which item a fraction of the way down the strip refers to.
 *
 * Each item owns an equal band of travel, so `fraction` is floored into `0 until itemCount`.
 * Out-of-range fractions clamp instead of wrapping or throwing: a finger that slides off the top
 * or bottom of the strip should pin to the end of the list, which is what it looks like it is
 * doing. An empty list has no item to name, and answers 0.
 *
 * Pure so the feel of the scrub is testable without a device.
 */
internal fun indexForFraction(fraction: Float, itemCount: Int): Int {
    if (itemCount <= 0) return 0
    // A zero-height strip would divide by zero upstream; take the top rather than propagate NaN.
    if (fraction.isNaN()) return 0
    val clamped = fraction.coerceIn(0f, 1f)
    return (clamped * itemCount).toInt().coerceIn(0, itemCount - 1)
}

/**
 * The stop that an item belongs to: the **last** stop that begins at or before [index].
 *
 * Deliberately not the nearest one. A stop labels the run of items that starts at it, so an item
 * two thirds of the way through March belongs to March even though April's stop is closer — the
 * bubble must name where the finger is, not what it is approaching.
 *
 * Returns null when [stops] is empty, or when [index] falls before the first stop begins.
 *
 * @param stops ascending by [ScrubberStop.itemIndex]; scanned from the end, so an unsorted list
 *   yields the positionally-last match rather than a meaningful one.
 */
internal fun stopForIndex(stops: List<ScrubberStop>, index: Int): ScrubberStop? =
    stops.lastOrNull { it.itemIndex <= index }

/**
 * The position (y on a vertical strip, x on a horizontal one) a given item index sits at on the
 * strip, in px — the inverse of [indexForFraction],
 * taken at the centre of the item's band so the first and last stops are not flush with the ends.
 *
 * Takes an *item* index, not a stop: it places the resting marker at [EdgeTimelineScrubber]'s
 * `currentIndex` as well as placing each stop's tick and label. That is the same coordinate for
 * both by construction — a stop is drawn at the y of the item it begins at — which is what makes
 * the labels agree with the drag mapping.
 */
internal fun itemCenterY(itemIndex: Int, itemCount: Int, heightPx: Float): Float {
    // `heightPx` is the strip's length along its running axis, whichever edge it is on.
    if (itemCount <= 0) return 0f
    return ((itemIndex + 0.5f) / itemCount).coerceIn(0f, 1f) * heightPx
}

/**
 * The stops whose labels there is room to draw, in order. [heightPx] is the strip's length along
 * its running axis and [minGapPx] the pitch ([labelPitchDp]) -- the pass itself is axis-blind.
 *
 * A greedy pass down the strip: keep a label, then skip every following one that would land
 * within [minGapPx] of it. This thins by density rather than by a fixed stride, which matters
 * because stops are placed by item index and so bunch wherever the list does — a stride would
 * still overlap inside a busy month while wastefully dropping labels in a quiet year.
 *
 * The cost is that the very last stop is only labelled if it happens to clear the one before it.
 * That is the right way round: dropping the bottom label is invisible, and a collision there is
 * not.
 */
internal fun thinnedLabels(
    stops: List<ScrubberStop>,
    itemCount: Int,
    heightPx: Float,
    minGapPx: Float,
): List<ScrubberStop> {
    if (stops.isEmpty() || itemCount <= 0 || heightPx <= 0f) return emptyList()
    val kept = ArrayList<ScrubberStop>(stops.size)
    var lastY = Float.NEGATIVE_INFINITY
    for (stop in stops) {
        val y = itemCenterY(stop.itemIndex, itemCount, heightPx)
        if (y - lastY < minGapPx) continue
        kept += stop
        lastY = y
    }
    return kept
}

// ── Edge geometry ─────────────────────────────────────────────────────────────────────────
// Everything below is pure, so which side a label, tick or bubble lands on is testable without a
// device. "Along" is the axis the strip runs on, "across" the one it is thin on. For RIGHT the
// results are exactly the numbers the strip used before [ScrubberEdge] existed.

/** Whether the strip runs top to bottom ([ScrubberEdge.LEFT], [ScrubberEdge.RIGHT]). */
internal val ScrubberEdge.runsVertically: Boolean
    get() = this == ScrubberEdge.LEFT || this == ScrubberEdge.RIGHT

/** Whether the track sits at the larger-coordinate side of the strip (right or bottom). */
internal val ScrubberEdge.trackOnFarSide: Boolean
    get() = this == ScrubberEdge.RIGHT || this == ScrubberEdge.BOTTOM

/**
 * Which way ticks and the marker point from the track, as a multiplier on the across axis:
 * -1 for [ScrubberEdge.RIGHT] / [ScrubberEdge.BOTTOM] (toward smaller coordinates, i.e. inward),
 * +1 for [ScrubberEdge.LEFT] / [ScrubberEdge.TOP].
 */
internal val ScrubberEdge.inwardSign: Float
    get() = if (trackOnFarSide) -1f else 1f

/**
 * Only the side edges have a system swipe to beat. Android's back gesture comes from the left and
 * right; a top or bottom strip has nothing to exclude itself from.
 */
internal val ScrubberEdge.excludesSystemGestures: Boolean
    get() = runsVertically

/** Minimum gap between drawn labels for this edge, in dp: height-driven or width-driven. */
internal fun labelPitchDp(edge: ScrubberEdge): Float =
    if (edge.runsVertically) LABEL_PITCH_DP else LABEL_PITCH_H_DP

/**
 * Where the track runs across the strip: [insetPx] in from the outer edge it hugs. For
 * [ScrubberEdge.RIGHT] that is `thickness - inset`, today's `size.width - TRACK_INSET`.
 */
internal fun trackAcrossPx(edge: ScrubberEdge, thicknessPx: Float, insetPx: Float): Float =
    if (edge.trackOnFarSide) thicknessPx - insetPx else insetPx

/** A pixel position in the strip's own (x, y) coordinates. */
internal data class StripPlacement(val x: Int, val y: Int)

/**
 * The widest a label may measure. A vertical strip gives a label the room between the strip's
 * inner edge and its inset (a label is right-anchored there for RIGHT, left-anchored for LEFT);
 * a horizontal strip gives it one pitch, which is also what guarantees thinned labels never meet.
 */
internal fun labelMaxWidthPx(
    edge: ScrubberEdge,
    thicknessPx: Float,
    insetPx: Float,
    pitchPx: Float,
): Int =
    if (edge.runsVertically) (thicknessPx - insetPx).roundToInt().coerceAtLeast(0)
    else pitchPx.roundToInt().coerceAtLeast(0)

/**
 * Top-left of a measured label, in the strip's coordinates.
 *
 * The label is centred on [centerAlong] along the strip, then clamped inside it. Across the
 * strip it sits [insetPx] in from the edge the track hugs: right-anchored for RIGHT (the old
 * placement), left-anchored for LEFT, bottom-anchored for BOTTOM, top-anchored for TOP.
 */
internal fun labelPlacement(
    edge: ScrubberEdge,
    labelWidth: Int,
    labelHeight: Int,
    centerAlong: Float,
    lengthPx: Float,
    thicknessPx: Float,
    insetPx: Float,
): StripPlacement = when (edge) {
    ScrubberEdge.RIGHT -> StripPlacement(
        x = (thicknessPx - insetPx).roundToInt() - labelWidth,
        y = clampAlong(centerAlong - labelHeight / 2f, labelHeight, lengthPx),
    )
    ScrubberEdge.LEFT -> StripPlacement(
        x = insetPx.roundToInt(),
        y = clampAlong(centerAlong - labelHeight / 2f, labelHeight, lengthPx),
    )
    ScrubberEdge.BOTTOM -> StripPlacement(
        x = clampAlong(centerAlong - labelWidth / 2f, labelWidth, lengthPx),
        y = (thicknessPx - insetPx).roundToInt() - labelHeight,
    )
    ScrubberEdge.TOP -> StripPlacement(
        x = clampAlong(centerAlong - labelWidth / 2f, labelWidth, lengthPx),
        y = insetPx.roundToInt(),
    )
}

/**
 * Top-left of the scrub bubble, in the strip's coordinates (it lies outside them across the
 * strip): beside the finger, [gapPx] clear of the strip, on the side away from the track --
 * left of RIGHT, right of LEFT, below TOP, above BOTTOM -- and clamped along the strip's length.
 */
internal fun bubblePlacement(
    edge: ScrubberEdge,
    bubbleWidth: Int,
    bubbleHeight: Int,
    touchAlong: Float,
    lengthPx: Float,
    thicknessPx: Float,
    gapPx: Float,
): StripPlacement = when (edge) {
    ScrubberEdge.RIGHT -> StripPlacement(
        x = -bubbleWidth - gapPx.roundToInt(),
        y = clampAlong(touchAlong - bubbleHeight / 2f, bubbleHeight, lengthPx),
    )
    ScrubberEdge.LEFT -> StripPlacement(
        x = thicknessPx.roundToInt() + gapPx.roundToInt(),
        y = clampAlong(touchAlong - bubbleHeight / 2f, bubbleHeight, lengthPx),
    )
    ScrubberEdge.BOTTOM -> StripPlacement(
        x = clampAlong(touchAlong - bubbleWidth / 2f, bubbleWidth, lengthPx),
        y = -bubbleHeight - gapPx.roundToInt(),
    )
    ScrubberEdge.TOP -> StripPlacement(
        x = clampAlong(touchAlong - bubbleWidth / 2f, bubbleWidth, lengthPx),
        y = thicknessPx.roundToInt() + gapPx.roundToInt(),
    )
}

/** Rounds [start] and keeps a [size]-px item inside `0..length`; pins to 0 if it cannot fit. */
private fun clampAlong(start: Float, size: Int, lengthPx: Float): Int =
    start.roundToInt().coerceIn(0, (lengthPx.roundToInt() - size).coerceAtLeast(0))
