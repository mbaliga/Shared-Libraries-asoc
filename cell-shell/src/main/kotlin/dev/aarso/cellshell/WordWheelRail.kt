package dev.aarso.cellshell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** One destination on a [WordWheelRail]: a stable [id] and the word shown for it. */
data class WheelItem(val id: String, val label: String)

/**
 * The constellation's navigation rail: a vertical **word wheel**, not a list.
 *
 * The surface in the owner's reference video. Room names are set large in a vertical run and
 * the wheel turns under the finger; the row crossing the horizontal focus line (the rail's
 * vertical centre) is the brightest and heaviest, and every other row fades progressively with
 * its distance from that line.
 *
 * **The fade is the position indicator.** That is the whole design: there is no scrollbar, no
 * highlight box and no selected background — a rail that has to draw a box around the current
 * row has admitted its type is not doing the work. Because of that, the fade cannot be
 * a two-state selected/unselected switch that flips on settle: weight and alpha are computed
 * from a *fractional* distance to the focus line and therefore interpolate continuously while
 * the finger is still down, which is what makes the run of words read as a wheel with mass
 * rather than a list that re-highlights.
 *
 * Beside the run, a [marker] marks the selected row — **and only the selected row.** That
 * restriction is the whole reason a marker is allowed here at all: one glyph names where you
 * are, whereas a glyph on every row would make this a list of buttons and the fade would have
 * nothing left to do. The marker is pinned to its *row*, not to the focus line, so turning the
 * wheel carries it away with the row it belongs to and a selection change makes it **travel**
 * back across the intervening rows rather than teleporting. Its travel is deliberately a little
 * slower than the wheel's settle so the lag is visible; a marker that arrives at exactly the
 * same instant as the text may as well have jumped.
 *
 * The marker used to be a dot that squashed and stretched along its path. It was a placeholder
 * (owner, 2026-08-09) and is a slot now, so hosts pass their destination's own icon; the travel
 * shows as a scale dip instead, because a deformed glyph reads as a rendering fault rather than
 * as speed.
 *
 * Foundation + animation + `ui` only — no material3, no material icons. Several apps in the
 * constellation are forbidden from depending on a design system at all, so the rail takes its
 * two colours as parameters and its marker as a slot instead of reading a theme or an icon set.
 *
 * @param items the destinations, top to bottom.
 * @param selectedId id of the item currently at the focus line. This is the source of truth:
 *   the rail scrolls to follow it, it does not hold its own selection.
 * @param onSelect fired when a row is tapped, and when a turn of the wheel settles on a new
 *   row. Callers are expected to feed the new id back in as [selectedId].
 * @param inkColor the colour of the words. Alpha is applied on top of it, so pass it opaque.
 * @param accentColor the colour of the default marker, when no [marker] is supplied.
 * @param marker what rides the gutter beside the selected row. **Only the selected row gets
 *   one** — the run of words is the thing being read, and a glyph on every row turns it into a
 *   list of buttons with the rail's whole argument removed. Hosts are expected to pass their
 *   destination's own icon; the default is the plain accent dot this rail shipped with, which
 *   was always a placeholder for exactly this (owner, 2026-08-09).
 * @param trailing optional content drawn after the selected row's word — a count, a spinner.
 *   Only the selected row gets it; on every other row it would compete with the words.
 * @param markerWidth the gutter reserved for the marker on every row, so the words keep one
 *   optical left edge whether or not a marker is beside them. Widen it when the marker is more
 *   than a glyph — a host showing a stack of covers needs room the default 30dp does not have.
 * @param markerHeight the marker's vertical extent, centred on its row.
 */
@Composable
fun WordWheelRail(
    items: List<WheelItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    inkColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier,
    marker: @Composable (WheelItem) -> Unit = { DefaultMarker(accentColor) },
    trailing: @Composable (WheelItem) -> Unit = {},
    markerWidth: Dp = BULLET_GUTTER.dp,
    markerHeight: Dp = MARKER_SIZE.dp,
) {
    if (items.isEmpty()) return

    val selectedIndex = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val scroll = rememberScrollState()

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current

        // Row pitch is derived from the line height in *sp*, not a fixed dp, so the wheel grows
        // with the user's font scale instead of clipping 34sp type into a 56dp box. Every row is
        // then pinned to exactly this height, which is what lets the focus-line geometry below be
        // pure arithmetic on the scroll offset rather than a pile of onGloballyPositioned reads.
        val pitchDp = with(density) { WHEEL_LINE_HEIGHT.sp.toDp() } + WHEEL_ROW_GAP.dp
        val pitchPx = with(density) { pitchDp.toPx() }

        // Half-a-viewport of blank above and below, so the first and last words can reach the
        // focus line like any other. An unbounded height has no centre to speak of; fall back to
        // one row so the maths stays finite rather than producing an infinite spacer.
        val viewportPx = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else pitchPx
        val padPx = ((viewportPx - pitchPx) / 2f).coerceAtLeast(0f)
        val padDp = with(density) { padPx.toDp() }
        val focusLinePx = padPx + pitchPx / 2f

        // With that padding, scrolling by exactly one pitch advances the focus line by exactly one
        // row — so `scroll.value / pitchPx` IS the wheel's position in row units, fractional part
        // and all. Everything visual downstream is a function of it.
        fun scrollRows(): Float = scroll.value / pitchPx

        // The bullet's row, in the same units. It trails [selectedIndex] rather than tracking the
        // focus line, which is what gives it its travel: while the wheel turns, selection has not
        // changed yet, so the bullet rides away with the row it still belongs to.
        val bulletRow = remember { Animatable(selectedIndex.toFloat()) }
        // Endpoints of the bullet's current journey, kept so the squash can peak mid-flight and
        // vanish at both ends (see [bulletSquash]).
        var travelFrom by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
        var travelTo by remember { mutableFloatStateOf(selectedIndex.toFloat()) }

        var placed by remember { mutableStateOf(false) }
        LaunchedEffect(selectedIndex, pitchPx) {
            val target = (selectedIndex * pitchPx).roundToInt()
            if (!placed) {
                // First composition: the selected room is simply already there. Animating into
                // position on open would announce a transition that never happened.
                placed = true
                scroll.scrollTo(target)
                bulletRow.snapTo(selectedIndex.toFloat())
                travelFrom = selectedIndex.toFloat()
                travelTo = selectedIndex.toFloat()
            } else {
                travelFrom = bulletRow.value
                travelTo = selectedIndex.toFloat()
                // The wheel settles on the shell's contract spec; the bullet uses the same curve
                // over a longer run so it visibly lags the words and catches up at the focus line.
                launch { scroll.animateScrollTo(target, SpatialMotion.settleSpec) }
                bulletRow.animateTo(
                    selectedIndex.toFloat(),
                    tween(BULLET_TRAVEL_MS, easing = FastOutSlowInEasing),
                )
            }
        }

        // A wheel does not stop between detents. When the finger (or the fling) lets go, snap to
        // whichever row is nearest the focus line and report it — turning the wheel IS selecting.
        val currentSelectedId by rememberUpdatedState(selectedId)
        val currentOnSelect by rememberUpdatedState(onSelect)
        val currentItems by rememberUpdatedState(items)
        LaunchedEffect(scroll, pitchPx) {
            var wasScrolling = false
            snapshotFlow { scroll.isScrollInProgress }.collect { scrolling ->
                // Only a true→false edge is a release. Collecting the initial `false` would snap
                // and fire onSelect before the user has touched anything.
                if (wasScrolling && !scrolling) {
                    val landing = currentItems
                    val nearest = (scroll.value / pitchPx).roundToInt().coerceIn(0, landing.lastIndex)
                    if (landing[nearest].id != currentSelectedId) currentOnSelect(landing[nearest].id)
                    // Only settle if we are actually off the detent. A programmatic settle is itself
                    // a scroll, so it comes back around through this same edge — without the guard
                    // the wheel would animate to where it already is, forever.
                    val detent = (nearest * pitchPx).roundToInt()
                    if (scroll.value != detent) scroll.animateScrollTo(detent, SpatialMotion.settleSpec)
                }
                wasScrolling = scrolling
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            Spacer(Modifier.height(padDp))
            items.forEachIndexed { index, item ->
                WheelRow(
                    item = item,
                    isSelected = item.id == selectedId,
                    rowHeight = pitchDp,
                    inkColor = inkColor,
                    // Deferred: read on the draw/composition pass, never captured as a value here,
                    // so turning the wheel does not recompose this loop.
                    distance = { index - scrollRows() },
                    onClick = { if (item.id != selectedId) onSelect(item.id) },
                    trailing = trailing,
                    gutter = markerWidth,
                )
            }
            Spacer(Modifier.height(padDp))
        }

        // The marker, riding the gutter the rows reserve for it. Only the selected row has one:
        // an icon on every row would be a list of buttons, and the run of words would stop being
        // the thing you read. Position, alpha and scale are all read inside deferred lambdas, so
        // it tracks a turn frame by frame without recomposing.
        val markerItem = items.getOrNull(selectedIndex)
        if (markerItem != null) {
            val markerHalfPx = with(density) { markerHeight.toPx() / 2f }
            Box(
                modifier = Modifier
                    .width(markerWidth)
                    .height(markerHeight)
                    .offset {
                        IntOffset(
                            0,
                            (focusLinePx + (bulletRow.value - scrollRows()) * pitchPx - markerHalfPx)
                                .roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        alpha = wheelAlpha(bulletRow.value - scrollRows())
                        // Motion still carries the state change, but an icon must not deform to
                        // do it -- a squashed glyph reads as a rendering fault, not as speed. So
                        // the travel shows as a scale dip that is deepest at mid-flight and gone
                        // at both ends, using the same parabola the stretched bullet used.
                        val dip = 1f - MARKER_TRAVEL_DIP *
                            bulletSquash(bulletRow.value, travelFrom, travelTo)
                        scaleX = dip
                        scaleY = dip
                    },
                contentAlignment = Alignment.Center,
            ) {
                marker(markerItem)
            }
        }
    }
}

/**
 * The marker a rail draws when its host supplies none: the plain accent dot this rail shipped
 * with before markers were a slot.
 *
 * Kept as the default so adopting the slot is opt-in — a host that has no icon for a
 * destination gets something honest rather than a hole in the gutter.
 */
@Composable
private fun DefaultMarker(accentColor: Color) {
    Box(
        Modifier
            .size(BULLET_SIZE.dp)
            .background(accentColor, RoundedCornerShape((BULLET_SIZE * BULLET_REST_ROUNDING / 2f).dp)),
    )
}

/**
 * One word in the run. A separate composable purely so it gets its own recomposition scope:
 * the font-weight read below has to invalidate this row and nothing else.
 */
@Composable
private fun WheelRow(
    item: WheelItem,
    isSelected: Boolean,
    rowHeight: Dp,
    inkColor: Color,
    distance: () -> Float,
    onClick: () -> Unit,
    trailing: @Composable (WheelItem) -> Unit,
    gutter: Dp,
) {
    // Weight is a text-layout property, so unlike alpha it cannot be deferred to the draw phase —
    // it costs a re-layout. [wheelFontWeight] quantises it so that cost is paid a handful of times
    // across a turn instead of on every frame, which is invisible: the eye cannot resolve a 25-unit
    // weight step, but it can absolutely resolve dropped frames.
    val currentDistance by rememberUpdatedState(distance)
    val weight by remember { derivedStateOf { wheelFontWeight(currentDistance()) } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            // Alpha in a graphics layer: the lambda re-runs on the draw pass when the scroll
            // offset changes, so the falloff is continuous under the finger for free.
            .graphicsLayer { alpha = wheelAlpha(distance()) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // No ripple. The rail is flat type on a flat ground; touch chrome would be the
                // only box on the surface.
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { selected = isSelected },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixed gutter so every word sits on one optical left edge whether or not the bullet is
        // beside it — the words must not shuffle sideways when selection moves.
        Spacer(Modifier.width(gutter))
        BasicText(
            text = item.label,
            style = TextStyle(
                color = inkColor,
                fontSize = WHEEL_FONT_SIZE.sp,
                lineHeight = WHEEL_LINE_HEIGHT.sp,
                fontWeight = weight,
                letterSpacing = (-0.5).sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (isSelected) {
            Box(Modifier.padding(start = 12.dp)) { trailing(item) }
        }
    }
}

/**
 * Opacity for a rail item [distance] rows from the selected one.
 *
 * Ported verbatim from Foto Xplorr's `hyle/DestinationRail.kt`, where it is already unit-tested
 * and owner-approved against the mockups: the selected row is fully opaque, its neighbours stay
 * clearly legible, and anything four or more rows out settles on a floor that is dim but still
 * readable rather than invisible. The floor is the load-bearing part — the extremes of the run
 * have to stay present, because a row that fades to nothing has left the wheel.
 *
 * Kept integer and pure so the falloff can be asserted in a test rather than eyeballed;
 * [wheelAlpha] is the fractional reading of this same curve that the rail actually draws.
 */
internal fun railItemAlpha(distance: Int, isSelected: Boolean): Float {
    if (isSelected) return 1f
    return (0.62f - 0.10f * distance.coerceAtMost(5)).coerceAtLeast(0.22f)
}

/**
 * [railItemAlpha] read at a fractional [distance] from the focus line.
 *
 * The rail cannot use the integer curve directly: mid-drag a row sits 1.4 rows out, and quantising
 * that to 1 makes the fade change in steps on settle — which is exactly the list-like re-highlight
 * the wheel exists to avoid. So the integer curve is sampled at the two bracketing distances and
 * interpolated, which reproduces [railItemAlpha] exactly at every whole row (including its floor,
 * since both endpoints are the floor once past it) and is smooth everywhere between.
 *
 * Distance 0 is the focus line's own occupant, so it takes the selected value: the row *at* the
 * focus line is the selected row, and on settle the two readings agree.
 */
internal fun wheelAlpha(distance: Float): Float {
    val d = abs(distance)
    val low = floor(d).toInt()
    val fraction = d - low
    val nearer = railItemAlpha(low, isSelected = low == 0)
    val further = railItemAlpha(low + 1, isSelected = false)
    return nearer + (further - nearer) * fraction
}

/**
 * Font weight for a row [distance] rows from the focus line: bold on the line, normal a row out
 * and beyond. Quantised to [WEIGHT_STEP] so that a turn of the wheel triggers a few text
 * re-layouts rather than one per frame — see the call site.
 */
internal fun wheelFontWeight(distance: Float): FontWeight {
    val focus = (1f - abs(distance)).coerceIn(0f, 1f)
    val raw = FontWeight.Normal.weight + (FontWeight.Bold.weight - FontWeight.Normal.weight) * focus
    val stepped = (raw / WEIGHT_STEP).roundToInt() * WEIGHT_STEP
    return FontWeight(stepped.coerceIn(1, 1000))
}

/**
 * How far through its journey the bullet is, expressed as squash: 0 at rest at either end, 1 at
 * the midpoint. A parabola rather than "distance remaining" so the deformation is symmetric —
 * it has to settle back to a dot on arrival, not merely shrink towards it.
 */
internal fun bulletSquash(current: Float, from: Float, to: Float): Float {
    val span = to - from
    if (abs(span) < 0.001f) return 0f
    val progress = ((current - from) / span).coerceIn(0f, 1f)
    return 4f * progress * (1f - progress)
}

private const val WHEEL_FONT_SIZE = 34
private const val WHEEL_LINE_HEIGHT = 40
private const val WHEEL_ROW_GAP = 16
private const val BULLET_GUTTER = 30
private const val BULLET_SIZE = 10
/** The box a [WordWheelRail] marker is centred in. Sized to sit inside [BULLET_GUTTER]. */
private const val MARKER_SIZE = 20
/** How far the marker shrinks at mid-flight. Small: this is a hint of speed, not a bounce. */
private const val MARKER_TRAVEL_DIP = 0.22f
/** Corner radius at rest, as a fraction of the capsule radius: a rounded square, not a circle. */
private const val BULLET_REST_ROUNDING = 0.3f
/** Longer than [SpatialMotion.settleSpec]'s 320ms so the bullet's lag behind the words is seen. */
private const val BULLET_TRAVEL_MS = 420
private const val WEIGHT_STEP = 25
