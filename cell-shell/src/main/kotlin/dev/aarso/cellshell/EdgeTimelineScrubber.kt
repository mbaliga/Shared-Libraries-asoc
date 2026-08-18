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

/** Width of the touch strip. Wide enough to hit without looking, narrow enough to ignore. */
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

/** Length of a stop's tick, drawn leftward from the track. */
private const val TICK_LENGTH_DP = 5f

/** Length of the marker showing the current position — longer than a tick so it reads as "you". */
private const val MARKER_LENGTH_DP = 13f

/** Thickness of that marker. */
private const val MARKER_WIDTH_DP = 2.5f

/**
 * Minimum vertical distance between two drawn labels.
 *
 * This is the whole anti-overlap mechanism: a label is drawn only if it clears the last one
 * drawn by this much, so it must stay comfortably above [LABEL_SP]'s line height.
 */
private const val LABEL_PITCH_DP = 14f

/** Where labels end, measured in from the strip's right edge — clear of the track and its ticks. */
private const val LABEL_INSET_DP = 15f

/** Label size. Deliberately small: the strip is a map of the list, not a second list. */
private const val LABEL_SP = 9f

/** Alpha of a tick belonging to a stop whose label was thinned away. */
private const val TICK_ALPHA = 0.55f

/** Gap between the strip's left edge and the bubble that floats beside the finger. */
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
 * The Niagara-style edge scrubber: a narrow strip down the right edge that sweeps the whole
 * list, with a bubble naming where the finger is.
 *
 * The strip is generic over what the stops mean, because the two hosts key it differently and
 * one of them changes its mind at runtime: Foto Xplorr's grid is keyed by date, while Fylz
 * re-keys the same list by letter, date or size as the sort changes. Nothing here knows which —
 * a stop is a label and the index it starts at, and everything else follows from that.
 *
 * ### The mapping
 *
 * Position maps to index linearly over the strip's height ([indexForFraction]) — the top of the
 * strip is item 0, the bottom is the last item, regardless of how the stops are distributed.
 * Labels are then placed at the y their own `itemIndex` maps back to, so the two agree: putting
 * the finger on "Mar" lands on March. The alternative — spacing labels evenly — reads more
 * tidily and lies, because a month with 400 photos would occupy the same travel as one with 3.
 *
 * ### Not overlapping
 *
 * That truthful placement means labels can bunch arbitrarily tightly, so they are thinned by a
 * greedy pass ([thinnedLabels]) that drops any label which would come within [LABEL_PITCH_DP] of
 * the last one drawn. Every stop still gets a tick on the track, so a thinned-away stop is
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
 * regardless of how tall this strip runs — see [EXCLUSION_SPAN_DP].
 *
 * @param stops the labelled stops, **ascending by [ScrubberStop.itemIndex]**.
 * @param itemCount how many items the list holds; the strip's height maps onto `0 until itemCount`.
 * @param currentIndex the list's actual first-visible index — where the marker rests when nobody
 *   is touching the strip.
 * @param onScrubTo fired as the finger moves, once per index change (not per frame), so the host
 *   can scroll to that item. The host is expected to jump, not animate: this is finger-tracking.
 * @param inkColor the track, ticks and labels — the strip's quiet ink.
 * @param accentColor the bubble's fill and the current stop's highlight.
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

    val pressedState = remember { mutableStateOf(false) }
    var pressed by pressedState
    // Finger y in px. Held as a state object rather than a `by` delegate because it is read only
    // from layout and draw lambdas — the marker and the bubble follow the finger every frame
    // without recomposing anything.
    val touchY = remember { mutableFloatStateOf(0f) }
    // The index the finger is currently over. Written every time the finger crosses an item
    // boundary, which on a full-library sweep is many times per frame — so it is deliberately
    // NOT read in composition anywhere.
    val scrubIndex = remember { mutableIntStateOf(0) }

    val strength by animateFloatAsState(
        // At rest the strip is an edge affordance like the shell's peeks, so it uses their alpha.
        targetValue = if (pressed) 1f else EDGE_REST_ALPHA,
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
            .fillMaxHeight()
            .width(STRIP_WIDTH_DP.dp)
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    // Only gestures that BEGIN here, and only ones nobody upstream has taken:
                    // awaitFirstDown inside this modifier's own pointer scope guarantees the
                    // down is in our bounds, and requireUnconsumed yields to the shell's
                    // Initial-pass edge drag.
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val heightPx = size.height.toFloat()

                    // Nothing is claimed and nothing moves until the finger proves intent by
                    // travelling past slop, vertically. Until then this loop is a bystander.
                    var claimed = false
                    // The stop under the finger when the scrub began. Seeded, not ticked: the
                    // haptic marks crossings, and buzzing on the first frame would fire on every
                    // gesture regardless of whether one was crossed.
                    var lastStopKey: Int? = null
                    var primed = false

                    fun scrubTo(y: Float) {
                        touchY.floatValue = y
                        val index = indexForFraction(y / heightPx, countState.value)
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
                            val dy = change.position.y - down.position.y
                            val dx = change.position.x - down.position.x
                            // Sideways: not a scrub. Leave without consuming so the gesture is
                            // still available to whatever wanted it.
                            if (abs(dx) > slop && abs(dx) > abs(dy)) break
                            if (abs(dy) <= slop) continue
                            claimed = true
                            pressed = true
                            lastStopKey = stopForIndex(
                                stopsState.value,
                                indexForFraction(down.position.y / heightPx, countState.value),
                            )?.itemIndex
                        }

                        scrubTo(change.position.y)
                        change.consume()
                    }
                    if (claimed) pressed = false
                }
            },
    ) {
        // Same edge-ownership problem as SpatialShell's rooms, on whichever edge the host places
        // this strip: without it, Android's own edge-swipe claims the touch before the
        // pointerInput above ever sees it. Sized and centred rather than applied to the strip's
        // own fillMaxHeight bounds -- see EXCLUSION_SPAN_DP's own KDoc for why the whole strip
        // would ask for more than the platform's 200dp-per-edge cap grants.
        Box(
            Modifier
                .align(Alignment.Center)
                .width(STRIP_WIDTH_DP.dp)
                .height(EXCLUSION_SPAN_DP)
                .systemGestureExclusion(),
        )

        val density = LocalDensity.current
        val heightPx = constraints.maxHeight.toFloat()
        val widthPx = constraints.maxWidth.toFloat()
        val pitchPx = with(density) { LABEL_PITCH_DP.dp.toPx() }
        val labelRightPx = widthPx - with(density) { LABEL_INSET_DP.dp.toPx() }

        // Thinning depends only on the geometry, not on the finger, so it survives every frame of
        // a drag untouched.
        val labels = remember(stops, itemCount, heightPx, pitchPx) {
            thinnedLabels(stops, itemCount, heightPx, pitchPx)
        }

        Box(
            Modifier
                .fillMaxSize()
                // Alpha applied in the layer lambda rather than as a composed argument: the
                // wake-up fade then runs entirely in the draw phase.
                .graphicsLayer { alpha = strength },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val trackX = size.width - TRACK_INSET_DP.dp.toPx()
                drawLine(
                    color = inkColor.copy(alpha = TICK_ALPHA),
                    start = Offset(trackX, 0f),
                    end = Offset(trackX, size.height),
                    strokeWidth = TRACK_WIDTH_DP.dp.toPx(),
                )
                // Every stop gets a tick, including the ones whose label was thinned away — the
                // run of ticks is what makes an unlabelled stretch read as "more of the same"
                // rather than as a gap in the timeline.
                val tickLength = TICK_LENGTH_DP.dp.toPx()
                for (stop in stops) {
                    val y = itemCenterY(stop.itemIndex, itemCount, size.height)
                    drawLine(
                        color = inkColor.copy(alpha = TICK_ALPHA),
                        start = Offset(trackX - tickLength, y),
                        end = Offset(trackX, y),
                        strokeWidth = TRACK_WIDTH_DP.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                // The marker. Reading touchY here — in the draw lambda — is what lets it track the
                // finger continuously between index changes instead of stepping item by item.
                val markerY = if (pressed) {
                    touchY.floatValue.coerceIn(0f, size.height)
                } else {
                    itemCenterY(currentIndex, itemCount, size.height)
                }
                drawLine(
                    color = accentColor,
                    start = Offset(trackX - MARKER_LENGTH_DP.dp.toPx(), markerY),
                    end = Offset(trackX, markerY),
                    strokeWidth = MARKER_WIDTH_DP.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            for (stop in labels) {
                val active = stop.itemIndex == shownStop?.itemIndex
                val centerY = itemCenterY(stop.itemIndex, itemCount, heightPx)
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
                    // Placed by hand: the label's own height is not known until it is measured,
                    // and it has to be centred on the y its index maps to. Reporting a 0×0 size
                    // keeps the labels out of the parent's sizing entirely — they are an overlay
                    // on the strip, not its contents.
                    modifier = Modifier.layout { measurable, _ ->
                        val placeable = measurable.measure(
                            Constraints(maxWidth = labelRightPx.roundToInt().coerceAtLeast(0)),
                        )
                        layout(0, 0) {
                            val top = (centerY - placeable.height / 2f).roundToInt()
                            placeable.place(
                                x = labelRightPx.roundToInt() - placeable.width,
                                // The first and last stops sit half a row past the ends, so clamp
                                // rather than let them hang off the strip.
                                y = top.coerceIn(
                                    0,
                                    (heightPx.roundToInt() - placeable.height).coerceAtLeast(0),
                                ),
                            )
                        }
                    },
                )
            }
        }

        // ── The bubble ────────────────────────────────────────────────────────────────────
        // Only while touched, and only when there is something truthful to name: a scrub above
        // the first stop has no label, and an empty bubble is worse than none.
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
                            val top = (touchY.floatValue - placeable.height / 2f).roundToInt()
                            placeable.place(
                                x = -placeable.width - gapPx.roundToInt(),
                                y = top.coerceIn(
                                    0,
                                    (heightPx.roundToInt() - placeable.height).coerceAtLeast(0),
                                ),
                            )
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
 * The y a given item index sits at on the strip, in px — the inverse of [indexForFraction],
 * taken at the centre of the item's band so the first and last stops are not flush with the ends.
 *
 * Takes an *item* index, not a stop: it places the resting marker at [EdgeTimelineScrubber]'s
 * `currentIndex` as well as placing each stop's tick and label. That is the same coordinate for
 * both by construction — a stop is drawn at the y of the item it begins at — which is what makes
 * the labels agree with the drag mapping.
 */
internal fun itemCenterY(itemIndex: Int, itemCount: Int, heightPx: Float): Float {
    if (itemCount <= 0) return 0f
    return ((itemIndex + 0.5f) / itemCount).coerceIn(0f, 1f) * heightPx
}

/**
 * The stops whose labels there is room to draw, in order.
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
