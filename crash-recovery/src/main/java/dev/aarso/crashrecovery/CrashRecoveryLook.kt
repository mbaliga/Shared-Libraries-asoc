package dev.aarso.crashrecovery

/**
 * The recovery screen's design scale.
 *
 * Every size, gap and radius on the screen comes from here rather than being chosen at the call
 * site. That sounds like bookkeeping and is not: the screen was built with ad-hoc numbers —
 * 23sp beside 13sp beside 11.5sp, gaps of 3, 5, 6, 7, 8, 10, 18 and 22dp — and the result read
 * as slightly wrong in a way that is hard to point at and easy to feel. This is the surface a
 * user meets immediately after their app died. It has one job beyond informing them, which is to
 * look like something that is still working properly.
 *
 * Plain constants, no dependency on anything. The module's zero-dependency guarantee (see
 * [CrashRecoveryActivity]) is load-bearing: whatever broke the host app must not be able to
 * break the screen that explains it.
 */
internal object Look {

    /**
     * Spacing, on a 4dp grid.
     *
     * A grid rather than free choice because the eye reads consistent rhythm as competence, and
     * inconsistent rhythm as carelessness — even when it cannot say why.
     */
    object Space {
        const val HAIR = 2
        const val XS = 4
        const val S = 8
        const val M = 12
        const val L = 16
        const val XL = 24

        /** The screen's left/right margin. One value, so every edge lines up down the page. */
        const val GUTTER = 24
    }

    /**
     * Type, as a small scale with real steps between sizes.
     *
     * Sizes an eighth apart (13 vs 12.5) are not two roles, they are one role rendered
     * carelessly. Five roles is all this screen has: a headline, a section title, body copy,
     * secondary copy, and an all-caps eyebrow.
     */
    object Type {
        const val DISPLAY = 26f
        const val TITLE = 20f
        const val BODY = 14f
        const val SECONDARY = 13f
        const val CAPTION = 12f
        const val EYEBROW = 11f
        const val MONO = 11.5f

        /**
         * Multiplier for [android.widget.TextView.setLineSpacing]'s `mult` argument. One value:
         * every block of prose on this screen is something the user is meant to actually read,
         * so none of it gets the tighter leading that suits a label.
         */
        const val LEADING_BODY = 1.35f

        /** Tracking for the all-caps eyebrows; caps need air between letters to stay legible. */
        const val EYEBROW_TRACKING = 0.1f
    }

    /** Corner radii. Cards and fields share one; pills are fully round. */
    object Radius {
        const val CARD = 14
        const val FIELD = 12
        const val PILL = 999
    }

    /**
     * Motion.
     *
     * The same 320ms eased curve the rest of the constellation settles on (`:cell-shell`'s
     * `SpatialMotion.settleSpec`, ported from Android-IDE-core). Restated here as plain numbers
     * rather than shared, because sharing it would mean taking a dependency, and this module's
     * whole promise is that it has none. The curve is a contract in both places: a crash screen
     * that moves like the app it belongs to is one less thing that feels wrong at the worst
     * possible moment.
     */
    object Motion {
        const val SETTLE_MS = 320L

        /** Cubic-bezier control points of the settle. No spring — nothing here should bounce. */
        const val EASE_X1 = 0.4f
        const val EASE_Y1 = 0f
        const val EASE_X2 = 0.2f
        const val EASE_Y2 = 1f

        /** The entry fade for the main pane's rows. Shorter than a settle; it is a reveal. */
        const val ENTER_MS = 260L

        /** How far each row rises into place, in dp. Small — a suggestion of arrival, not a slide. */
        const val ENTER_RISE_DP = 10

        /** Gap between one row starting its entry and the next. */
        const val ENTER_STAGGER_MS = 45L

        /** Rows past this index all start together, so a long screen does not crawl in. */
        const val ENTER_MAX_STAGGERED = 6
    }

    /** The crash mark's drawn size, in dp. Keeps its 132:183 aspect from the source asset. */
    object Mark {
        const val WIDTH = 116
        const val HEIGHT = 161
    }

    /** Alpha applied to the accent to make a press highlight that works on any host colour. */
    const val RIPPLE_ALPHA = 0x40
}

/**
 * Which colour each part of a pill button is painted.
 *
 * Pulled out of the view builder and made pure so it can be tested, because this is where a real
 * defect lived and the defect was invisible in code review: the outlined variant took a `bg`
 * parameter, ignored it, hardcoded its fill to the paper colour, and still painted its label the
 * caller's `fg` — which for `pillButton("View the full report", ink, paper, filled = false)` was
 * also paper. Paper on paper. The button shipped, reached a device, and showed an outlined pill
 * with nothing in it, on the screen whose entire job is looking trustworthy.
 *
 * Stated as a rule instead: a pill has ONE colour, its tint. Filled, the tint is the fill and
 * `onTint` is the label. Outlined, the tint is the label *and* the stroke, and the fill is the
 * surface behind it. `onTint` has no meaning in the outlined variant at all — which is exactly
 * the fact the old signature obscured.
 */
internal object PillColors {

    /** The label colour. Never the same as [fill]'s result for the same inputs. */
    fun label(tint: Int, onTint: Int, filled: Boolean): Int = if (filled) onTint else tint

    /** The fill colour. [paper] is the surface the outlined variant sits on. */
    fun fill(tint: Int, paper: Int, filled: Boolean): Int = if (filled) tint else paper

    /** The stroke colour, or null when the variant has no stroke. */
    fun stroke(tint: Int, filled: Boolean): Int? = if (filled) null else tint
}
