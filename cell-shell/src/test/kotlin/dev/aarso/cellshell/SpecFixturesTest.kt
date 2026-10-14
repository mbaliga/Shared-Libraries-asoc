package dev.aarso.cellshell

import androidx.compose.animation.core.CubicBezierEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-file guard for `specs/cell-shell-motion.md` (PORTING_PLAN.md S0.2).
 *
 * The vectors live in `specs/fixtures/cell-shell-motion.v1.json` so that a platform
 * implementation that is not Kotlin (a QML app, a Swift port) can run the same cases against
 * its own code. This class asserts that THIS implementation still produces them, which is what
 * lets the existing CI steps guard drift in the spec with no CI edit.
 *
 * The fixture is contract, not scratch: a changed expected value is a break of the motion
 * contract (see [SpatialMotion]) and must never be "fixed" here. It is rewritten only by an
 * explicit opt-in: run with `SPEC_FIXTURES_REGENERATE=1` and `--rerun`; the run still FAILS so
 * the new file has to be reviewed with `git diff specs/fixtures` and the suite rerun without the
 * switch.
 */
class SpecFixturesTest {

    private val name = "cell-shell-motion.v1.json"

    private fun Map<String, Any?>.f(key: String): Float = (this[key] as Double).toFloat()
    private fun Map<String, Any?>.i(key: String): Int = (this[key] as Double).toInt()
    private fun Map<String, Any?>.d(key: String): Double = this[key] as Double
    private fun Map<String, Any?>.s(key: String): String = this[key] as String

    /** Runs [check] on every case of [section]; in regenerate mode rewrites `expect` instead. */
    private fun forEachCase(
        section: String,
        minCases: Int,
        actual: (Map<String, Any?>) -> Any?,
        check: (label: String, expected: Any?, actual: Any?) -> Unit,
    ) {
        val (file, root) = SpecJson.load(name)
        val cases = SpecJson.arr(SpecJson.obj(root)[section]).map { SpecJson.obj(it) }
        if (SpecJson.regenerating()) {
            cases.forEach { it["expect"] = actual(it) }
            SpecJson.saveRegeneratedAndFail(file, root)
        }
        assertTrue("$section: fixture has ${cases.size} cases, expected at least $minCases", cases.size >= minCases)
        cases.forEach { c -> check("$section / ${c.s("name")}", c["expect"], actual(c)) }
    }

    private fun assertNumber(label: String, expected: Any?, actual: Any?, tolerance: Double) {
        assertEquals(label, expected as Double, (actual as Number).toDouble(), tolerance)
    }

    // ---- the pure decisions ------------------------------------------------------------------

    @Test
    fun `settleTarget matches the fixture`() = forEachCase(
        "settle_target", minCases = 16,
        actual = { c -> settleTarget(c.f("value"), c.f("last_delta")).toDouble() },
        check = { l, e, a -> assertNumber(l, e, a, 1e-6) },
    )

    @Test
    fun `parkDistance matches the fixture`() = forEachCase(
        "park_distance", minCases = 6,
        actual = { c -> parkDistance(c.f("extent"), c.f("scale"), c.f("band")).toDouble() },
        check = { l, e, a -> assertNumber(l, e, a, 1e-2) },
    )

    @Test
    fun `the parked card leaves exactly its grab band on screen for every fixture case`() {
        // The property the spec states, not just the numbers: whatever the scale, the band's
        // width of card is still visible after parking.
        val (_, root) = SpecJson.load(name)
        SpecJson.arr(SpecJson.obj(root)["park_distance"]).map { SpecJson.obj(it) }.forEach { c ->
            val extent = c.d("extent")
            val scale = c.d("scale")
            val band = c.d("band")
            val travelled = parkDistance(extent.toFloat(), scale.toFloat(), band.toFloat()).toDouble()
            val nearEdge = extent * (1.0 - scale) / 2.0 + travelled
            assertEquals("${c.s("name")}: visible band", band, extent - nearEdge, 0.01)
        }
    }

    @Test
    fun `indexForFraction matches the fixture`() = forEachCase(
        "index_for_fraction", minCases = 20,
        actual = { c -> indexForFraction(c.f("fraction"), c.i("item_count")).toDouble() },
        check = { l, e, a -> assertNumber(l, e, a, 0.0) },
    )

    @Test
    fun `indexForFraction never leaves the list for any fixture count`() {
        val (_, root) = SpecJson.load(name)
        val counts = SpecJson.arr(SpecJson.obj(root)["index_for_fraction"])
            .map { SpecJson.obj(it).i("item_count") }.filter { it > 0 }.distinct()
        assertTrue("fixture should carry several positive counts", counts.size >= 3)
        for (count in counts) for (step in -5..25) {
            val index = indexForFraction(step / 20f, count)
            assertTrue("fraction ${step / 20f} of $count items gave $index", index in 0 until count)
        }
    }

    @Test
    fun `stopForIndex matches the fixture`() {
        val (_, root) = SpecJson.load(name)
        val sets = SpecJson.obj(SpecJson.obj(root)["stop_sets"])
        fun stops(setName: String): List<ScrubberStop> = SpecJson.arr(sets[setName]).map {
            val m = SpecJson.obj(it)
            ScrubberStop(m.s("label"), m.i("item_index"))
        }
        forEachCase(
            "stop_for_index", minCases = 18,
            actual = { c -> stopForIndex(stops(c.s("stops")), c.i("index"))?.label },
            check = { l, e, a -> if (e == null) assertNull(l, a) else assertEquals(l, e, a) },
        )
    }

    // ---- the constants ----------------------------------------------------------------------

    @Test
    fun `motion constants match the fixture`() {
        val (file, root) = SpecJson.load(name)
        val constants = SpecJson.obj(SpecJson.obj(root)["constants"])
        val bezier = listOf(0.4f, 0f, 0.2f, 1f)
        // The easing is only reachable as an object, so the fixture's four numbers are compared by
        // building the curve they describe and asking whether the spec's easing equals it.
        val actual = linkedMapOf<String, Any?>(
            "settle_duration_ms" to SpatialMotion.settleSpec.durationMillis.toDouble(),
            "settle_delay_ms" to SpatialMotion.settleSpec.delay.toDouble(),
            "settle_easing_cubic_bezier" to (constants["settle_easing_cubic_bezier"]),
            "park_scale_drop" to SpecJson.clean(SpatialMotion.PARK_SCALE_DROP),
            "park_elevation" to SpecJson.clean(SpatialMotion.PARK_ELEVATION),
            "park_corner_dp" to SpecJson.clean(SpatialMotion.PARK_CORNER_DP),
            "park_ring_alpha" to SpecJson.clean(SpatialMotion.PARK_RING_ALPHA),
            "park_scrim_alpha" to SpecJson.clean(SpatialMotion.PARK_SCRIM_ALPHA),
            "settle_threshold" to SpecJson.clean(SpatialMotion.SETTLE_THRESHOLD),
            "flick_velocity_px" to SpecJson.clean(SpatialMotion.FLICK_VELOCITY_PX),
            "vertical_travel_fraction" to SpecJson.clean(SpatialMotion.VERTICAL_TRAVEL_FRACTION),
        )
        if (SpecJson.regenerating()) {
            // The easing's four control points cannot be read back from the object, so a
            // regeneration keeps the fixture's own list; the equality assertion below still guards it.
            constants.putAll(actual)
            SpecJson.saveRegeneratedAndFail(file, root)
        }
        assertEquals("constants in fixture", actual.keys, constants.keys)
        for ((key, want) in actual) {
            if (key == "settle_easing_cubic_bezier") continue
            assertEquals("constant $key", constants.d(key), (want as Double), 1e-6)
        }
        val curve = SpecJson.arr(constants["settle_easing_cubic_bezier"]).map { (it as Double).toFloat() }
        assertEquals("easing has four control points", 4, curve.size)
        assertEquals("easing control points", bezier, curve)
        assertEquals(
            "SpatialMotion.settleSpec.easing is the fixture's cubic-bezier",
            CubicBezierEasing(curve[0], curve[1], curve[2], curve[3]),
            SpatialMotion.settleSpec.easing,
        )
    }

    @Test
    fun `the fixture identifies itself`() {
        val (_, root) = SpecJson.load(name)
        val m = SpecJson.obj(root)
        assertEquals("cell-shell-motion", m["spec"])
        assertEquals(1.0, m["version"] as Double, 0.0)
    }
}
