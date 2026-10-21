package dev.aarso.cellshell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt

/** [SpatialMotion.BAND_DP], which carries the documentation: public so hosts need not mirror it. */
private const val BAND_DP = SpatialMotion.BAND_DP

/** How close to an edge a touch must start to be read as an edge drag, in dp. */
private const val EDGE_DP = 56f

/**
 * Height of each vertical edge's gesture-exclusion band, in dp.
 *
 * 200dp is Android's own ceiling for `systemGestureExclusion` per edge — anything requested past
 * it is silently dropped, so asking for more would just be a claim the platform ignores. That
 * makes it a band rather than the full edge, which is why it needs centring: see
 * [EdgeGestureExclusion].
 */
private val EXCLUSION_SPAN = 200.dp

/** Inset of the grip pill from the parked card's exposed edge, in dp — centred in the band. */
private const val GRIP_INSET_DP = 34f

/** Alpha of the grip pill at full open; it fades in with the lift so it never blinks on. */
private const val GRIP_ALPHA = 0.9f

/**
 * The pixel value of one unit of Compose's `cameraDistance`.
 *
 * `cameraDistance` is documented in inches at a nominal 72 pixels per inch, NOT in pixels, and
 * that is the whole reason its default of 8f is unusable for a full-screen pane: it places the
 * camera 576px away. Named rather than inlined because a bare `/ 72f` next to a rotation reads
 * like an arbitrary fudge and is exactly the kind of line someone deletes while simplifying.
 */
private const val DEFAULT_CAMERA_DISTANCE_PX = 72f

/**
 * Alpha of an edge affordance at rest — loud enough to be noticed once, quiet enough to ignore
 * afterwards.
 *
 * Shared with [EdgeTimelineScrubber], which fades its whole strip to this when untouched. One
 * number on purpose: the peek and the strip make the same promise ("there is something on this
 * edge, it is not urgent") on the same edges of the same screen, and two independently chosen
 * values a hundredth apart would be indistinguishable to the eye while quietly making the shell
 * two designs instead of one.
 */
internal const val EDGE_REST_ALPHA = 0.35f

/**
 * How opaque the parked card's own face is at a given [lift] — 0 shows home through it, 1 hides
 * home entirely and leaves a blank card.
 *
 * The shell's scrim was always meant to stop the parked card being "misread as live overlap", and
 * on its own it does not: [SpatialMotion.PARK_SCRIM_ALPHA] is a *tint*, drawn in the same colour
 * the host hands over for the room floor, so 38% of home's ink survives it. Across a 72dp band
 * that is enough to read a column of filenames clipped mid-word, or a whole slab of bottom chrome,
 * next to a room that is also asking to be read. Two UIs at once is what the owner reported.
 *
 * The face therefore closes on top of the scrim, and it closes late: nothing at all until the
 * lift passes [SpatialMotion.SETTLE_THRESHOLD], then linearly to fully closed at full park. That
 * boundary is not a taste call — it is the same line a release settles on, so home stays legible
 * for exactly as long as letting go would bring it back, and only goes blank once the gesture has
 * committed to the room. A drag reversed mid-flight re-opens the face on the way back, because
 * this is a pure function of the current lift and nothing latches.
 *
 * Pure so the curve can be asserted rather than eyeballed; see `ParkVeilTest`.
 */
internal fun parkVeil(lift: Float): Float {
    val onset = SpatialMotion.SETTLE_THRESHOLD
    // Defensive: the contract pins the threshold at 0.5, but a threshold of 1 would divide by 0.
    val span = (1f - onset).coerceAtLeast(0.001f)
    return ((lift - onset) / span).coerceIn(0f, 1f)
}

/**
 * The spatial shell: one home surface with up to four rooms parked off its edges.
 *
 * Home is always the thing on screen. A room is revealed by dragging in from the matching
 * screen edge; the home surface does not go away, it **lifts and parts** — scaling down,
 * gaining a shadow and rounded corners, and sliding aside until only a grab band of it
 * remains. That parked card is the way back: tap it or drag it, and home returns. Nothing is
 * pushed onto a back stack, because nothing was ever left.
 *
 * Home stays live and composed the whole time, but past the halfway line it stops being *shown*:
 * the card's face closes over its content ([parkVeil]) so the band is the blank edge of a card
 * and not a legible offcut of the previous screen sitting beside a room. The band is 72dp of
 * whichever edge of home the card parked away from, so leaving it bare means whatever the host
 * pinned to that edge — a tab strip, a toolbar, the left margin of a list — is what the user is
 * left reading. See [BAND_DP].
 *
 * Motion comes entirely from [SpatialMotion] and [SpatialController], which are a contract
 * shared across the constellation — see their documentation before reaching for a tuning knob.
 *
 * ### Slots
 *
 * [left], [right], [top] and [bottom] are the rooms. **A null slot means that edge has no
 * room**, and the shell then refuses drags from it and draws no peek there — an app with only
 * left and right rooms (Foto Xplorr) must not open a void when the user swipes up. Give the
 * shell only the edges you actually populate.
 *
 * ### What this composable deliberately does not do
 *
 * - **No design system.** Colours arrive as parameters ([accentColor], [scrimColor], [cardColor])
 *   because some hosts are forbidden from depending on one. Nothing here reads a theme.
 * - **No inset consumption.** The shell fills whatever it is given; the host decides whether room
 *   content sits under the system bars. It READS the status-bar and navigation-bar insets, but
 *   only to keep the top and bottom acquisition zones out of pixels the system owns (see
 *   [inTopEdgeZone] and [inBottomEdgeZone]); it never consumes them, so the host's own insets
 *   handling is untouched.
 * - **No back handling.** That needs `activity-compose`, which this module does not depend on.
 *   Hosts should wire their own: `BackHandler(enabled = controller.anyRoomVisible) {
 *   controller.closeAll() }`.
 * - **No announcements.** Only the host knows what its rooms are called, so screen-reader
 *   labelling belongs on the slot content.
 *
 * @param controller the two-axis state driving the shell; see [rememberSpatialController].
 * @param accentColor the ring around the parked card and the colour of the grip and edge peeks.
 * @param scrimColor the room floor behind everything, and the scrim that quiets the parked card.
 * @param cardColor the raised surface of the home card, so it reads as above the room floor —
 *   and, once parked, the face that closes over home's content in the exposed band.
 * @param parkStyle whether the parked card merely slides away or also turns about its hinge
 *   edge. Both shrink it; see [ParkStyle]. Defaults to [ParkStyle.SLIDE], which is the motion
 *   this shell has always had, so an app that says nothing sees no change.
 * @param topReserve a strip below the status bar that the HOST owns, which the top-edge gesture
 *   must start beneath. Zero by default. The case it exists for: a host that reveals its own
 *   pull-down surface at the top of the pane — a status band, a notification layer — would
 *   otherwise have that surface's own drag swallowed by the shell's top-room gesture, since both
 *   live in the same 56dp of screen and the shell claims pointers on the Initial pass. Reserving
 *   the strip hands those pixels back, and the top room stays reachable directly beneath it.
 * @param bottomReserve a strip above the navigation bar that the HOST owns, which the bottom-edge
 *   gesture must start above — [topReserve]'s mirror. Zero by default, which is today's zone. The
 *   bottom zone sits on the 56dp ending at the navigation bar, and a host with its own scrubber
 *   or transport there (a filmstrip, a video seek bar) would otherwise have a vertical-first start
 *   on it open the bottom room instead of scrubbing. The zone becomes
 *   `[h - inset - reserve - edge, h - inset - reserve]` and the edge hint moves up with it.
 * @param bottomPeek turns the BOTTOM room into a two-stage reveal: a strip this many dp tall,
 *   then the whole room. Null (the default) keeps the single-stage room every consumer has always
 *   had. A release in the lower part of the pull settles on the strip, a pull past it carries on to
 *   full, a flick up from the strip opens the room and a flick down closes it;
 *   [SpatialController.bottomStage] says which stage is showing, and [SpatialController.peekBottom]
 *   opens straight to the strip.
 *
 *   **What the number means.** The room arrives TOP-first and is inset by [SpatialMotion.BAND_DP]
 *   at its head, so the strip is the first `bottomPeek` of the room's own content, below that
 *   band. The shell adds the band itself (and the navigation bar's height, which covers the foot of
 *   the revealed area), so a host passes just the height of the strip it wants to show and
 *   anchors it to the TOP of the room's content: `Modifier.height(bottomPeek)` at the head of the
 *   room is exactly what is on screen at the peek, clear of the nav bar. Content lower down
 *   arrives as the pull continues, and the room is not told which stage it is in.
 *
 *   **When it does not fit.** On a window too short for the strip (it would need more than
 *   [SpatialMotion.MAX_PEEK_REVEAL] of the height) there is no peek: the room is single-stage and
 *   [SpatialController.hasBottomPeek] is false — hide the strip then. Whenever the stop moves
 *   (rotation, insets, density, a new `bottomPeek`) a room parked on the old one follows it; if
 *   the peek goes away the room closes.
 * @param home the surface the app lives on. Always composed, always alive — even while parked.
 */
@Composable
fun SpatialShell(
    controller: SpatialController,
    accentColor: Color,
    scrimColor: Color,
    cardColor: Color,
    modifier: Modifier = Modifier,
    left: (@Composable () -> Unit)? = null,
    right: (@Composable () -> Unit)? = null,
    top: (@Composable () -> Unit)? = null,
    bottom: (@Composable () -> Unit)? = null,
    parkStyle: ParkStyle = ParkStyle.SLIDE,
    topReserve: Dp = 0.dp,
    bottomPeek: Dp? = null,
    bottomReserve: Dp = 0.dp,
    home: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val bandPx = with(density) { BAND_DP.dp.toPx() }
    val edgePx = with(density) { EDGE_DP.dp.toPx() }
    // The status bar strip is not ours to claim, however edge-to-edge our own window is.
    // SystemUI's notification-shade gesture sits in a window layer above the app and takes
    // first touch on the physical status bar regardless of what the app draws there or how
    // widely its own pointerInput listens -- an app cannot out-consume it. Left unaccounted
    // for, EDGE_DP's top zone spans BOTH that dead strip and the live app content below it,
    // so a top-edge pull is a coin flip: land in the strip and the OS shade opens instead of
    // the room; land just below it and the room opens as designed. Starting the top zone
    // after the status bar removes the coin flip entirely, and it costs the gesture nothing
    // real: the strip was never reachable to begin with.
    // Plus whatever strip the host has claimed for a surface of its own at the top of the pane
    // (see topReserve). Same reasoning as the status bar: a zone that overlaps something else's
    // gesture is a coin flip, and moving ours below it costs the top room nothing.
    val topInsetPx = WindowInsets.statusBars.getTop(density).toFloat() +
        with(density) { topReserve.toPx() }
    // The bottom zone is the mirror image, and matters more: the bottom room is the primary one in
    // some hosts. Both activities are edge-to-edge, so the window's last EDGE_DP is partly the
    // navigation bar -- with a 48dp 3-button bar only an 8dp strip of the zone was reachable, and
    // under gesture navigation the system home swipe sits over the lowest ~32dp. The zone is
    // therefore the EDGE_DP that ENDS where the system's pixels begin. systemGestures is read as
    // well as navigationBars because the gesture inset is what actually reserves the home swipe
    // (and is the larger of the two on some devices); the bigger one wins so the zone and the
    // hint, which sits on top of it, stay clear of whichever is taller.
    val bottomInsetPx = maxOf(
        WindowInsets.navigationBars.getBottom(density),
        WindowInsets.systemGestures.getBottom(density),
    ).toFloat()
    val bottomInset = with(density) { bottomInsetPx.toDp() }
    // Plus whatever the host owns above the bar (see bottomReserve): the zone is the edge band
    // that ends where the host's own surface begins.
    val bottomReservePx = with(density) { bottomReserve.toPx() }

    // Read the axes in composition: the conditional room subtrees below depend on them, so the
    // shell has to recompose as they move anyway. The card's own translation is still deferred
    // to layout via offset { } so the hot part of the frame stays out of composition.
    val hProgress = controller.hProgress
    val vProgress = controller.vProgress
    val lift = controller.lift
    val anyRoom = controller.anyRoomVisible

    // The peek stop, as a reveal fraction. The navigation bar covers the foot of what is revealed,
    // so the strip the host asked for has to clear it -- hence the bar alone, not the (possibly
    // larger) gesture inset -- and peekReveal adds the head band, which is the room's own empty
    // inset. Pushed to the controller as an effect: the controller owns the settle (and re-settles
    // a room parked on the previous stop), the shell owns the measurements the stop comes from.
    val navBarPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val peekStripPx = if (bottom != null && bottomPeek != null) {
        navBarPx + with(density) { bottomPeek.toPx() }
    } else {
        0f
    }
    val peekAt = peekReveal(peekStripPx, controller.viewport.height.toFloat(), bandPx)
    SideEffect { controller.updateBottomPeek(peekAt) }

    Box(
        modifier = modifier
            .fillMaxSize()
            // The room floor. Rooms draw on top of it, and it is what shows through in the
            // sliver beside a partly-open room.
            .background(scrimColor)
            .onSizeChanged { controller.onMeasured(it) }
            .spatialEdgeDrag(
                controller = controller,
                edgePx = edgePx,
                topInsetPx = topInsetPx,
                bottomInsetPx = bottomInsetPx,
                bottomReservePx = bottomReservePx,
                hasLeft = left != null,
                hasRight = right != null,
                hasTop = top != null,
                hasBottom = bottom != null,
            ),
    ) {
        // ── Gesture exclusion ─────────────────────────────────────────────────────────
        // Unconditional on `atHome`, unlike everything below: a swipe that starts on a populated
        // edge must not fall through to the system even while a room is parked, not only while
        // idle at rest.
        if (left != null) EdgeGestureExclusion(Alignment.CenterStart)
        if (right != null) EdgeGestureExclusion(Alignment.CenterEnd)

        val w = controller.viewport.width.toFloat()
        val hgt = controller.viewport.height.toFloat()
        val scale = 1f - SpatialMotion.PARK_SCALE_DROP * lift

        // ── Rooms, beneath the home card ──────────────────────────────────────────────
        // Each room is composed only while it is at least slightly revealed, so a closed room
        // costs nothing, and each insets itself by the band on the side the card parks to. The
        // inset is the room's, not the card's, because only the room knows to keep its titles
        // and actions clear of a surface that will sit on top of them.
        if (left != null && hProgress > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset((-w * (1f - hProgress)).roundToInt(), 0) }
                    .padding(end = BAND_DP.dp),
            ) { left() }
        }
        if (right != null && hProgress < -0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset((w * (1f + hProgress)).roundToInt(), 0) }
                    .padding(start = BAND_DP.dp),
            ) { right() }
        }
        // +v slides the top room down over the screen and parks the card downward, so the band
        // it leaves behind is at the bottom.
        if (top != null && vProgress > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, (-hgt * (1f - vProgress)).roundToInt()) }
                    .padding(bottom = BAND_DP.dp),
            ) { top() }
        }
        if (bottom != null && vProgress < -0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, (hgt * (1f + vProgress)).roundToInt()) }
                    .padding(top = BAND_DP.dp),
            ) { bottom() }
        }

        // ── The home card: live, lifted and parked while a room is open ───────────────
        // Which edge stays on screen is decided ONCE, here, and every consumer below reads it:
        // the swivel's hinge, the direction it turns, and the grip pill that marks the band. They
        // used to each work it out for themselves and the swivel got it backwards.
        val hBand = bandEdge(hProgress)
        val vBand = bandEdge(vProgress)
        val swivelDeg = if (parkStyle == ParkStyle.SWIVEL) SpatialMotion.PARK_SWIVEL_DEG * lift else 0f
        // No foreshortening compensation: the card hinges on the band edge, so that edge sits at
        // z = 0 throughout the rotation and the projection leaves it exactly where it was. The
        // band stays BAND_DP wide at any angle, and SLIDE and SWIVEL park to the same depth.
        val tx = (hProgress * parkDistance(w, scale, bandPx)).roundToInt()
        val ty = (vProgress * parkDistance(hgt, scale, bandPx)).roundToInt()
        val cardShape = RoundedCornerShape((SpatialMotion.PARK_CORNER_DP * lift).dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                // A PLACEMENT offset, not a graphicsLayer translation. The distinction is the
                // whole reason the parked card still works as a target: layer translation moves
                // pixels but leaves hit-testing where the card used to be, so the card would
                // draw over there and answer touches over here.
                .offset { IntOffset(tx, ty) }
                // The swivel is its OWN layer, ahead of the scale/shape one below, and the two
                // must not be merged. transformOrigin is per-layer: hinging the rotation on an
                // edge would drag the *scale* pivot onto that edge too, and parkDistance's
                // arithmetic assumes the card scales about its centre. Two layers keeps each
                // transform on the origin it was designed around.
                //
                // Pointer input survives this: Compose maps pointers back through the full layer
                // matrix (NodeCoordinator.fromParentPosition -> OwnedLayer.mapOffset(inverse)),
                // perspective divide included, so the card answers touches where it is drawn. The
                // guard that matters is cameraDistance -- at Compose's default the far edge
                // crosses behind the camera at large angles, where that mapping stops being
                // meaningful. PARK_SWIVEL_DEG is nowhere near it, and the distance below keeps
                // the margin proportional to the card rather than fixed in pixels.
                .then(
                    if (swivelDeg == 0f) {
                        Modifier
                    } else {
                        Modifier.graphicsLayer {
                            // Hinge on the edge that stays on screen as the band -- the edge the
                            // user is effectively holding -- so the card swings away like a door
                            // whose hinges you can see. Hinging on the far edge instead swings
                            // the *visible* sliver through the perspective divide and throws it
                            // off screen, which is what "the pane goes away completely" was.
                            transformOrigin = TransformOrigin(
                                pivotFractionX = hingePivot(hBand, scale),
                                pivotFractionY = hingePivot(vBand, scale),
                            )
                            // The card turns to FACE the room it just revealed (owner,
                            // 2026-08-18: *"it has to swivel to point to the interactive content
                            // (nav, settings, info, etc)"*). Not away from it -- that reads as
                            // the pane turning its back on the thing the user just opened.
                            //
                            // Which way that is, without having to re-derive it at a whiteboard:
                            // Compose's rotationY takes the face normal (0,0,1) to
                            // (sin θ, 0, cos θ), so a POSITIVE angle aims the face at +X, to the
                            // right. hProgress > 0 means the LEFT room is open, so the face must
                            // aim at -X, so the angle is negative -- hence the minus.
                            // rotationX takes the normal to (0, -sin θ, cos θ) and Compose's Y
                            // axis points DOWN, so a positive angle aims the face UP, which is
                            // what a top room (vProgress > 0) wants. Hence no minus there.
                            //
                            // Facing the room also removes the camera-plane hazard rather than
                            // flirting with it: the far edge now swings TOWARDS the viewer, to at
                            // most sin(θ) of a card width, and the camera sits three card widths
                            // out. It is the receding direction that could cross the camera
                            // plane, and this is no longer it.
                            rotationY = -swivelDeg * hProgress
                            rotationX = swivelDeg * vProgress
                            cameraDistance = size.width.coerceAtLeast(1f) *
                                SpatialMotion.PARK_CAMERA_DISTANCE_CARDS / DEFAULT_CAMERA_DISTANCE_PX
                        }
                    },
                )
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = SpatialMotion.PARK_ELEVATION * lift
                    shape = cardShape
                    // Clipping costs a layer, so only pay for it once the corners exist.
                    clip = lift > 0.001f
                }
                // The ring appears as the card lifts: on a dark room floor a shadow alone gives
                // no contrast, and without a visible boundary the card reads as part of the room.
                .border(1.dp, accentColor.copy(alpha = lift * SpatialMotion.PARK_RING_ALPHA), cardShape)
                // A raised surface so the card is plainly above the room, not a hole in it.
                .background(cardColor, cardShape),
        ) {
            home()

            // While parked, the whole card is one return affordance. It sits above home at a
            // high zIndex and swallows every pointer event, so a control the finger happens to
            // land on cannot fire — the user is aiming at "back", not at whatever is under it.
            //
            // Two coats, in draw order, doing two different jobs: the scrim quiets the card
            // against the room floor, and over it the card's own face ([parkVeil]) closes over
            // home's content so the exposed band reads as the edge of a card rather than as a
            // strip of the previous screen. The grip pill then marks that band as the thing to
            // grab — which is also why it has to be on the edge that is actually on screen.
            if (anyRoom) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(10f)
                        .returnDrag(
                            controller = controller,
                            hasLeft = left != null,
                            hasRight = right != null,
                            hasTop = top != null,
                            hasBottom = bottom != null,
                        )
                        .background(scrimColor.copy(alpha = SpatialMotion.PARK_SCRIM_ALPHA * lift))
                        // Over the scrim, not under it: at full park the band is the card's own
                        // surface colour, so it stays plainly distinct from the room floor the
                        // scrim is tinted with instead of dissolving into it.
                        .background(cardColor.copy(alpha = parkVeil(lift))),
                ) {
                    val grip = Modifier.background(
                        accentColor.copy(alpha = GRIP_ALPHA * lift),
                        RoundedCornerShape(2.dp),
                    )
                    // Same [bandEdge] answer the hinge above uses, so the pill can never again
                    // end up marking a different edge than the one the card actually turns on.
                    when {
                        hBand == BandEdge.START -> Box(
                            Modifier.align(Alignment.CenterStart).padding(start = GRIP_INSET_DP.dp)
                                .size(width = 4.dp, height = 48.dp).then(grip),
                        )
                        hBand == BandEdge.END -> Box(
                            Modifier.align(Alignment.CenterEnd).padding(end = GRIP_INSET_DP.dp)
                                .size(width = 4.dp, height = 48.dp).then(grip),
                        )
                        // Card parked DOWN (top room open): the sliver still visible along the
                        // screen's bottom is the card's TOP edge, and the grip belongs on it.
                        vBand == BandEdge.START -> Box(
                            Modifier.align(Alignment.TopCenter).padding(top = GRIP_INSET_DP.dp)
                                .size(width = 48.dp, height = 4.dp).then(grip),
                        )
                        // Card parked UP (bottom room open): band = the card's BOTTOM edge.
                        else -> Box(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = GRIP_INSET_DP.dp)
                                .size(width = 48.dp, height = 4.dp).then(grip),
                        )
                    }
                }
            }
        }

        // ── Edge peek at rest ─────────────────────────────────────────────────────────
        // The structure should be seen, not memorized: a sliver on each edge that has
        // somewhere to go. Only at home — mid-drag they would compete with the motion.
        if (controller.atHome) {
            if (left != null) EdgePeek(Alignment.CenterStart, accentColor)
            if (right != null) EdgePeek(Alignment.CenterEnd, accentColor)
            // Below the status bar, same reasoning as topInsetPx above: a hint drawn where the
            // gesture cannot actually be claimed teaches the wrong spot to pull from.
            if (top != null) EdgePeek(Alignment.TopCenter, accentColor, vertical = true, belowStatusBar = true)
            // Above the navigation bar and the host's reserve, same reasoning: the zone starts
            // there, so the hint does.
            if (bottom != null) EdgePeek(
                Alignment.BottomCenter, accentColor, vertical = true,
                bottomInset = bottomInset + bottomReserve,
            )
        }
    }
}

/**
 * Claims a band of one vertical edge back from Android's own edge-swipe gestures.
 *
 * Without this, [spatialEdgeDrag] never wins the race on gesture navigation, no matter how it is
 * tuned: the system's back/home swipe owns the outermost pixels of every screen edge
 * unconditionally, and it resolves before a single pointer event reaches Compose — the drag is
 * simply cancelled (`pressed` goes `false`, the bail-out already in [spatialEdgeDrag]'s loops)
 * rather than losing a race Compose could see happen. [EXCLUSION_SPAN] is the platform's own
 * per-edge cap, so the band is only ever a slice of the edge — centred on [EdgePeek] so the one
 * part of the edge that visibly promises a drag is the part that is actually reachable. Top and
 * bottom edges get no equivalent: Android's own gesture bar lives on the vertical edges only.
 */
@Composable
private fun BoxScope.EdgeGestureExclusion(alignment: Alignment) {
    Box(
        Modifier
            .align(alignment)
            .size(width = EDGE_DP.dp, height = EXCLUSION_SPAN)
            .systemGestureExclusion(),
    )
}

/** The at-rest hint on one edge. [vertical] means the edge is horizontal, so the pill lies flat. */
@Composable
private fun BoxScope.EdgePeek(
    alignment: Alignment,
    accentColor: Color,
    vertical: Boolean = false,
    belowStatusBar: Boolean = false,
    bottomInset: Dp = 0.dp,
) {
    Box(
        modifier = Modifier
            .align(alignment)
            .then(if (belowStatusBar) Modifier.statusBarsPadding() else Modifier)
            .padding(bottom = bottomInset)
            .padding(8.dp)
            .then(
                if (vertical) Modifier.size(width = 56.dp, height = 4.dp)
                else Modifier.size(width = 4.dp, height = 56.dp),
            )
            .background(accentColor.copy(alpha = EDGE_REST_ALPHA), RoundedCornerShape(2.dp)),
    )
}

/**
 * Edge-origin drag: the gesture that opens a room.
 *
 * Runs on [PointerEventPass.Initial] so it sees the pointer before whatever home puts under it
 * — a list that starts scrolling first would win the gesture and the edge would feel dead.
 * Being first is not the same as being greedy, though: nothing is consumed until an axis has
 * actually been claimed, so a touch that starts near an edge and then scrolls a list passes
 * through untouched.
 *
 * The claim itself is a **slop race**. Both axes accumulate; whichever crosses touch slop first
 * takes the gesture, but only if an edge on that axis was the origin — otherwise the gesture is
 * abandoned entirely rather than held. That is what keeps the corners honest: a downward drag
 * starting in the top-left corner of a left-room-only shell must scroll, not stall.
 *
 * [hasLeft]/[hasRight]/[hasTop]/[hasBottom] are the host's populated edges. A missing edge is
 * never an origin, so its drag is refused outright — and because the direction clamps below are
 * derived from the same flags, even a diagonal that engages the other way clamps that axis to 0.
 *
 * [topInsetPx] moves the top edge's acquisition zone below the status bar rather than
 * shrinking it: the zone stays [EDGE_DP] tall, it just starts where a touch can actually
 * reach the app. The status bar strip itself is never a legal origin — see the KDoc on the
 * call site in [SpatialShell] for why competing with SystemUI there cannot be won.
 * [bottomInsetPx] does the same for the bottom edge against the navigation bar or gesture bar:
 * the zone is the [EDGE_DP] ending at the inset, not the window's last [EDGE_DP]. [bottomReservePx]
 * lifts it further, over a surface of the host's own (see `bottomReserve` on [SpatialShell]).
 *
 * Releases pass the lift's event time, so a hold between the last move and the lift is not read as
 * the velocity the finger had before it paused ([releaseDelta]).
 */
private fun Modifier.spatialEdgeDrag(
    controller: SpatialController,
    edgePx: Float,
    topInsetPx: Float,
    bottomInsetPx: Float,
    bottomReservePx: Float,
    hasLeft: Boolean,
    hasRight: Boolean,
    hasTop: Boolean,
    hasBottom: Boolean,
): Modifier = pointerInput(
    controller, edgePx, topInsetPx, bottomInsetPx, bottomReservePx, hasLeft, hasRight, hasTop, hasBottom,
) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
        // Only home opens rooms. Once one is open, the parked card's return-drag owns the shell.
        if (!controller.atHome) return@awaitEachGesture
        val fromLeft = hasLeft && down.position.x <= edgePx
        val fromRight = hasRight && down.position.x >= size.width - edgePx
        val fromTop = hasTop && inTopEdgeZone(down.position.y, topInsetPx, edgePx)
        val fromBottom = hasBottom &&
            inBottomEdgeZone(down.position.y, size.height.toFloat(), bottomInsetPx, edgePx, bottomReservePx)
        if (!fromLeft && !fromRight && !fromTop && !fromBottom) return@awaitEachGesture

        var dx = 0f
        var dy = 0f
        var axis = 0 // 0 = undecided, 1 = horizontal, 2 = vertical
        while (axis == 0) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            dx += change.positionChange().x
            dy += change.positionChange().y
            if (abs(dx) > viewConfiguration.touchSlop && abs(dx) >= abs(dy)) {
                if (fromLeft || fromRight) axis = 1 else return@awaitEachGesture
            } else if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) {
                if (fromTop || fromBottom) axis = 2 else return@awaitEachGesture
            }
        }

        if (axis == 1) {
            // Clamp to the half we came from: a left-edge drag can only push home right (+h),
            // so an overshoot back past 0 cannot flip into the opposite room mid-gesture.
            val min = if (fromRight) -1f else 0f
            val max = if (fromLeft) 1f else 0f
            var releasedAt = -1L
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    releasedAt = change.uptimeMillis
                    break
                }
                controller.dragH(change.positionChange().x, min, max, change.uptimeMillis)
                // Consume now that the axis is claimed, so anything downstream stays still.
                change.consume()
            }
            controller.settleH(releasedAt)
        } else {
            // Top edge pulls the top room down (v → +1); bottom edge pushes it up (v → −1).
            val min = if (fromBottom) -1f else 0f
            val max = if (fromTop) 1f else 0f
            var releasedAt = -1L
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    releasedAt = change.uptimeMillis
                    break
                }
                controller.dragV(change.positionChange().y, min, max, change.uptimeMillis)
                change.consume()
            }
            controller.settleV(releasedAt)
        }
    }
}

/**
 * The parked card's gesture: tap or drag it back home, and swallow everything else.
 *
 * No slop race here and no edge test — the card is entirely a return affordance while parked,
 * so the down is consumed immediately. The axis is whichever one is open, not whichever way the
 * finger moves, so a sloppy diagonal still drags the card along its actual travel.
 *
 * "Whichever one is open" is a comparison between the axes, not a fixed threshold on one of them.
 * Asking only whether `|h| > 0.5` mislabelled every horizontal room below half travel: a left room
 * dragged back to 40% and then touched again answered "vertical", so the next drag moved the
 * vertical axis and opened a top or bottom room out of a gesture that was closing a side one.
 *
 * The clamps open past 0 rather than stopping at the half the current room lives in: dragging a
 * room shut and straight on into the opposite room is one continuous motion, and stopping dead
 * at 0 would feel like hitting a wall mid-screen. They open only as far as a room that actually
 * EXISTS, though — without that, closing the left room with momentum drove `h` negative and
 * "opened" a right room the host never supplied, which is precisely the empty void the shell's
 * KDoc promises a missing edge cannot produce.
 *
 * A touch only stops being a tap once it has travelled past touch slop in total ([beyondTouchSlop]),
 * not at the first sub-pixel of jitter: a finger resting on the parked peek card wobbles, and that
 * is a request to go home, not a drag to settle where it happens to stand. The card still follows
 * the finger throughout, so the first real drag does not jump.
 */
private fun Modifier.returnDrag(
    controller: SpatialController,
    hasLeft: Boolean,
    hasRight: Boolean,
    hasTop: Boolean,
    hasBottom: Boolean,
): Modifier =
    pointerInput(controller, hasLeft, hasRight, hasTop, hasBottom) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val horizontal = abs(controller.hProgress) >= abs(controller.vProgress)
            val hMin = if (hasRight) -1f else 0f
            val hMax = if (hasLeft) 1f else 0f
            val vMin = if (hasBottom) -1f else 0f
            val vMax = if (hasTop) 1f else 0f
            var moved = false
            var releasedAt = -1L
            var totalDx = 0f
            var totalDy = 0f
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    releasedAt = change.uptimeMillis
                    break
                }
                val delta = change.positionChange()
                totalDx += delta.x
                totalDy += delta.y
                if (!moved && beyondTouchSlop(totalDx, totalDy, viewConfiguration.touchSlop)) moved = true
                if (horizontal) {
                    controller.dragH(delta.x, min = hMin, max = hMax, uptimeMillis = change.uptimeMillis)
                } else {
                    controller.dragV(delta.y, min = vMin, max = vMax, uptimeMillis = change.uptimeMillis)
                }
                change.consume()
            }
            when {
                // A tap that never moved past slop is not a drag to settle — it is a request to
                // go home, and settling would just leave the room open.
                !moved -> controller.closeAll()
                horizontal -> controller.settleH(releasedAt)
                else -> controller.settleV(releasedAt)
            }
        }
    }
