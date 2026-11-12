package dev.aarso.viz

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The shapes are viz.js's; a feed that drives the web kit must parse here unchanged. */
@RunWith(RobolectricTestRunner::class)
class VizJsonTest {
    // Verbatim from web/viz-kit/demo.html.
    private val builds = """[{ "label": "Failed", "value": 1 }, { "label": "Flaky", "value": 3 }, { "label": "Running", "value": 14 }, { "label": "Passed", "value": 94 }]"""
    private val storage = """[{ "label": "Photos", "value": 61, "fill": "fade" }, { "label": "Video", "value": 38, "fill": "dots" }, { "label": "Apps", "value": 23, "fill": "hatch" }, { "label": "Cache", "value": 12, "fill": "solid" }]"""

    @Test fun datumsParseWithAndWithoutFill() {
        val d = VizJson.data(builds)
        assertEquals(listOf("Failed", "Flaky", "Running", "Passed"), d.map { it.label })
        assertEquals(listOf(1.0, 3.0, 14.0, 94.0), d.map { it.value })
        assertTrue(d.all { it.fill == null })
        assertEquals(listOf(Texture.FADE, Texture.DOTS, Texture.HATCH, Texture.SOLID), VizJson.data(storage).map { it.fill })
    }

    @Test fun datumsRoundTrip() {
        val d = VizJson.data(storage)
        assertEquals(d, VizJson.data(VizJson.toJson(d)))
        assertEquals(d, VizJson.data(VizJson.toJson(d).toString()))
    }

    @Test fun readingIsAsForgivingAsVizJs() {
        val d = VizJson.data("""[{"label":"a","value":"12"},{"label":"b","value":"x"},{"label":"c"},{"label":"d","value":7,"fill":"nonsense"},{"value":1}]""")
        assertEquals(listOf(12.0, 0.0, 0.0, 7.0, 1.0), d.map { it.value })
        assertEquals(Texture.SOLID, d[3].fill)
        assertEquals("", d[4].label)
    }

    @Test fun aBareNumberListIsOneUnlabelledSeries() {
        val s = VizJson.series("[98.1, 98.4, null, 99.0]")
        assertEquals(1, s.size)
        assertNull(s[0].label)
        assertEquals(listOf(98.1, 98.4, null, 99.0), s[0].values)
    }

    @Test fun seriesKeepLabelsGapsAndFills() {
        val j = """[{"label":"main","values":[4,6,null,9]},{"label":"branches","values":[9,8,10,7],"fill":"dots"}]"""
        val s = VizJson.series(j)
        assertEquals(listOf("main", "branches"), s.map { it.label })
        assertEquals(listOf(4.0, 6.0, null, 9.0), s[0].values)
        assertEquals(Texture.DOTS, s[1].fill)
        assertEquals(s, VizJson.series(VizJson.seriesToJson(s)))
    }

    @Test fun matrixRoundTrips() {
        val m = VizJson.matrix("""{"rows":["Mon","Tue"],"cols":["00","01","02"],"values":[[0,1,2],[3,4,5]]}""")
        assertEquals(listOf("Mon", "Tue"), m.rows)
        assertEquals(listOf("00", "01", "02"), m.cols)
        assertEquals(listOf(listOf(0.0, 1.0, 2.0), listOf(3.0, 4.0, 5.0)), m.values)
        assertEquals(m, VizJson.matrix(VizJson.toJson(m)))
        val bare = VizJson.matrix("""{"values":[[1,2]]}""")
        assertNull(bare.rows); assertNull(bare.cols)
    }

    @Test fun meterDefaultsMaxTo100() {
        assertEquals(Meter(412.0, 500.0, "beta seats"), VizJson.meter("""{"value":412,"max":500,"label":"beta seats"}"""))
        assertEquals(Meter(40.0, 100.0, null), VizJson.meter("""{"value":40}"""))
        val m = VizJson.meter("""{"value":412,"max":500,"label":"beta seats"}""")
        assertEquals(m, VizJson.meter(VizJson.toJson(m)))
    }

    @Test fun statTakesANumberOrAString() {
        val s = VizJson.stat("""{"label":"Crash-free sessions","value":"99.2%","delta":0.4,"deltaUnit":" pts","vs":"vs last week"}""")
        assertEquals("99.2%", s.valueText); assertNull(s.value)
        assertEquals(0.4, s.delta!!, 0.0); assertEquals(" pts", s.deltaUnit); assertEquals("vs last week", s.vs)
        assertEquals(s, VizJson.stat(VizJson.toJson(s)))
        val n = VizJson.stat("""{"label":"Seats","value":412}""")
        assertEquals(412.0, n.value!!, 0.0); assertNull(n.valueText); assertNull(n.delta)
        assertEquals(n, VizJson.stat(VizJson.toJson(n)))
    }

    @Test fun legendKeepsDashAndValue() {
        val l = VizJson.legend("""[{"label":"main","dash":""},{"label":"branches","dash":"7 5","value":3}]""")
        assertNull(l[0].dashArray()); assertEquals("", l[0].dash)
        assertEquals(listOf(7f, 5f), l[1].dashArray()!!.toList())
        assertEquals(3.0, l[1].value!!, 0.0)
        assertEquals(l, VizJson.legend(VizJson.legendToJson(l)))
    }

    @Test fun optionsUseVizJsNames() {
        val o = VizJson.options("""{"sweep":90,"inner":0.5,"callout":"134 GB","caption":"used","x":["a","b"],"zero":false,"smooth":false,"fill":"each","unit":"MB","plain":true,"height":44,"responsive":true,"on":{}}""")
        assertEquals(90, o.sweep); assertEquals(0.5f, o.inner)
        assertEquals("134 GB", o.callout); assertTrue(o.showCallout)
        assertEquals(listOf("a", "b"), o.x); assertFalse(o.zero); assertFalse(o.smooth); assertTrue(o.area)
        assertTrue(o.fillEach); assertNull(o.fill); assertEquals("MB", o.unit); assertTrue(o.plain); assertEquals(44f, o.height)
        assertFalse(VizJson.options("""{"callout":false}""").showCallout)
        assertEquals(Texture.HATCH, VizJson.options("""{"fill":"hatch"}""").fill)
        assertEquals(ChartOptions(), VizJson.options("{}"))
    }

    @Test fun themeFromToneOrColours() {
        assertEquals(VizTone.NIGHT.ground, VizJson.theme("""{"tone":"night"}""").ground)
        assertEquals(VizTone.NIGHT.accent, VizJson.theme("""{"tone":"Night"}""").accent)
        val t = VizJson.theme("""{"tone":"sun","ink":"#fff","ground":"#102030","rem":18}""")
        assertEquals(0xFFFFFFFF.toInt(), t.ink); assertEquals(0xFF102030.toInt(), t.ground); assertEquals(18f, t.rem)
    }

    @Test fun aWrongShapeIsRefusedWithAPlace() {
        for ((name, f) in listOf<Pair<String, () -> Any>>(
            "data" to { VizJson.data("""{"a":1}""") },
            "data[0]" to { VizJson.data("[1,2]") },
            "matrix.values" to { VizJson.matrix("""{"rows":["a"]}""") },
            "meter" to { VizJson.meter("[1]") },
            "series[0].values" to { VizJson.series("""[{"label":"x"}]""") },
            "not JSON" to { VizJson.data("[1,") },
        )) {
            try { f(); fail("accepted $name") } catch (e: IllegalArgumentException) { assertTrue("${e.message}", e.message!!.contains(name.substringBefore(' ').substringBefore('.').substringBefore('[')) || name == "not JSON") }
        }
    }

    @Test fun jsonArraysAreAcceptedDirectly() {
        assertEquals(2, VizJson.data(JSONArray().put(JSONObject().put("label", "a").put("value", 1)).put(JSONObject().put("label", "b").put("value", 2))).size)
    }
}
