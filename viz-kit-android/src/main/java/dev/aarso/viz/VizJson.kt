package dev.aarso.viz

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses and writes the kit's data contract (CONTRACT.md). The shapes are viz.js's, so a JSON feed
 * that drives `ASOC.viz.steps(el, data, opts)` on the web parses here unchanged.
 *
 * Reading is as forgiving as viz.js: a value that is not a number reads as 0 (`+v || 0`), an unknown
 * texture name reads as solid, a missing label is empty. What it refuses is a wrong *shape* (an object
 * where a list is wanted, no `values` in a matrix) with an [IllegalArgumentException] naming the place.
 */
object VizJson {
    // ---- reading ---------------------------------------------------------------------------------

    /** `[{label, value, fill?}]` */
    fun data(json: String): List<Datum> = data(array(json, "data"))
    fun data(a: JSONArray): List<Datum> = (0 until a.length()).map { datum(obj(a.opt(it), "data[$it]")) }

    /** `[{label?, values, fill?}]`, or a bare `number[]` for one unlabelled series. */
    fun series(json: String): List<Series> = series(array(json, "series"))
    fun series(a: JSONArray): List<Series> {
        if (a.length() == 0) return emptyList()
        val first = a.opt(0)
        if (first is Number || first == JSONObject.NULL) return listOf(Series(values = values(a, "series")))
        return (0 until a.length()).map {
            val o = obj(a.opt(it), "series[$it]")
            Series(o.optStringOrNull("label"), values(o.optJSONArray("values") ?: bad("series[$it].values must be a list"), "series[$it].values"), fill(o))
        }
    }

    /** `{rows?, cols?, values: number[][]}` */
    fun matrix(json: String): Matrix = matrix(obj(parse(json), "matrix"))
    fun matrix(o: JSONObject): Matrix {
        val v = o.optJSONArray("values") ?: bad("matrix.values must be a list of rows")
        val rows = (0 until v.length()).map { r ->
            val row = v.optJSONArray(r) ?: bad("matrix.values[$r] must be a list")
            (0 until row.length()).map { c -> num(row.opt(c)) }
        }
        return Matrix(rows, o.optJSONArray("rows")?.let(::strings), o.optJSONArray("cols")?.let(::strings))
    }

    /** `{value, max?, label?}` */
    fun meter(json: String): Meter = meter(obj(parse(json), "meter"))
    fun meter(o: JSONObject): Meter = Meter(num(o.opt("value")), if (o.has("max") && num(o.opt("max")) != 0.0) num(o.opt("max")) else 100.0, o.optStringOrNull("label"))

    /** `{value, label, delta?, deltaUnit?, vs?}`; `value` is a number or an already-formatted string. */
    fun stat(json: String): Stat = stat(obj(parse(json), "stat"))
    fun stat(o: JSONObject): Stat {
        val v = o.opt("value")
        return Stat(
            label = o.optString("label", ""),
            value = if (v is Number) v.toDouble() else null,
            valueText = if (v is String) v else if (v == null || v == JSONObject.NULL || v is Number) null else v.toString(),
            delta = if (o.has("delta") && !o.isNull("delta")) num(o.opt("delta")) else null,
            deltaUnit = o.optStringOrNull("deltaUnit"),
            vs = o.optStringOrNull("vs"),
        )
    }

    /** `[{label, fill?, dash?, value?}]` */
    fun legend(json: String): List<LegendItem> = legend(array(json, "legend"))
    fun legend(a: JSONArray): List<LegendItem> = (0 until a.length()).map {
        val o = obj(a.opt(it), "legend[$it]")
        LegendItem(o.optString("label", ""), fill(o), o.optStringOrNull("dash"), if (o.has("value") && !o.isNull("value")) num(o.opt("value")) else null)
    }

    /** The options viz.js takes besides data, by their web names. Unknown keys are ignored. */
    fun options(json: String): ChartOptions = options(obj(parse(json), "options"))
    fun options(o: JSONObject): ChartOptions {
        val fill = o.opt("fill")
        val callout = o.opt("callout")
        return ChartOptions(
            sweep = if (o.has("sweep")) num(o.opt("sweep")).toInt() else 180,
            inner = if (o.has("inner")) num(o.opt("inner")).toFloat() else null,
            callout = if (callout is String) callout else null,
            showCallout = callout != false,
            caption = o.optStringOrNull("caption"),
            x = o.optJSONArray("x")?.let(::strings) ?: emptyList(),
            xTitle = o.optStringOrNull("xTitle"),
            zero = o.optBoolean("zero", true), area = o.optBoolean("area", true), grid = o.optBoolean("grid", false),
            smooth = o.optBoolean("smooth", true),
            unit = o.optStringOrNull("unit"),
            fill = if (fill is String && fill != "each") Texture.of(fill) ?: Texture.SOLID else null,
            fillEach = fill == "each",
            plain = o.optBoolean("plain", false),
            cell = if (o.has("cell")) num(o.opt("cell")).toFloat() else null,
            thickness = if (o.has("thickness")) num(o.opt("thickness")).toFloat() else null,
            label = o.optStringOrNull("label"),
            height = if (o.has("height")) num(o.opt("height")).toFloat() else null,
            row = o.optBoolean("row", false), values = o.optBoolean("values", false),
            lines = o.optBoolean("lines", false), shades = o.optBoolean("shades", false),
            size = if (o.has("size")) num(o.opt("size")).toFloat() else null,
        )
    }

    /**
     * A theme from `{tone}` (a [VizTone] name, case-insensitive) or `{ink, ground, accent}` as `#rgb` /
     * `#rrggbb` strings; given colours win over the tone, as in viz.js. `rem`, `weight` and `scale` are read too.
     */
    fun theme(json: String, base: VizTheme = VizTheme()): VizTheme {
        val o = obj(parse(json), "theme")
        var t = base
        o.optStringOrNull("tone")?.let { name -> VizTone.values().firstOrNull { it.name.equals(name, true) }?.let { t = it.theme().copy(rem = t.rem, weight = t.weight, textureScale = t.textureScale, font = t.font, format = t.format) } }
        o.optStringOrNull("ink")?.let { t = t.copy(ink = colour(it)) }
        o.optStringOrNull("ground")?.let { t = t.copy(ground = colour(it)) }
        o.optStringOrNull("accent")?.let { t = t.copy(accent = colour(it)) }
        if (o.has("rem")) t = t.copy(rem = num(o.opt("rem")).toFloat())
        if (o.has("weight")) t = t.copy(weight = num(o.opt("weight")).toInt())
        if (o.has("scale")) t = t.copy(textureScale = num(o.opt("scale")).toFloat())
        return t
    }

    // ---- writing (the round trip) ----------------------------------------------------------------

    fun toJson(d: List<Datum>): JSONArray = JSONArray().also { a -> d.forEach { a.put(JSONObject().put("label", it.label).put("value", it.value).also { o -> it.fill?.let { f -> o.put("fill", f.key) } }) } }
    fun seriesToJson(s: List<Series>): JSONArray = JSONArray().also { a ->
        s.forEach { se ->
            a.put(JSONObject().also { o ->
                se.label?.let { o.put("label", it) }
                o.put("values", JSONArray().also { v -> se.values.forEach { x -> v.put(x ?: JSONObject.NULL) } })
                se.fill?.let { o.put("fill", it.key) }
            })
        }
    }
    fun toJson(m: Matrix): JSONObject = JSONObject().also { o ->
        m.rows?.let { o.put("rows", JSONArray(it)) }
        m.cols?.let { o.put("cols", JSONArray(it)) }
        o.put("values", JSONArray().also { a -> m.values.forEach { r -> a.put(JSONArray().also { b -> r.forEach { x -> b.put(x) } }) } })
    }
    fun toJson(m: Meter): JSONObject = JSONObject().put("value", m.value).put("max", m.max).also { o -> m.label?.let { o.put("label", it) } }
    fun toJson(s: Stat): JSONObject = JSONObject().also { o ->
        o.put("label", s.label)
        if (s.value != null) o.put("value", s.value) else o.put("value", s.valueText ?: "")
        s.delta?.let { o.put("delta", it) }
        s.deltaUnit?.let { o.put("deltaUnit", it) }
        s.vs?.let { o.put("vs", it) }
    }
    fun legendToJson(l: List<LegendItem>): JSONArray = JSONArray().also { a ->
        l.forEach { i -> a.put(JSONObject().put("label", i.label).also { o -> i.fill?.let { o.put("fill", it.key) }; i.dash?.let { o.put("dash", it) }; i.value?.let { o.put("value", it) } }) }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun datum(o: JSONObject) = Datum(o.optString("label", ""), num(o.opt("value")), fill(o))
    private fun fill(o: JSONObject): Texture? = o.optStringOrNull("fill")?.let { Texture.of(it) ?: Texture.SOLID }
    private fun values(a: JSONArray, where: String): List<Double?> = (0 until a.length()).map { val v = a.opt(it); if (v == null || v == JSONObject.NULL) null else num(v) }
    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.opt(it).let { v -> if (v == null || v == JSONObject.NULL) "" else v.toString() } }

    /** JavaScript's `+v || 0`: numbers as they are, numeric strings parsed, anything else (and NaN) zero. */
    internal fun num(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull() ?: 0.0
        is Boolean -> if (v) 1.0 else 0.0
        else -> 0.0
    }.let { if (it.isNaN() || it.isInfinite()) 0.0 else it }

    private fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) opt(k).toString() else null

    private fun colour(s: String): Int {
        val h = s.trim().removePrefix("#")
        val v = when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("")
            6 -> h
            else -> bad("colour must be #rgb or #rrggbb: $s")
        }
        return (0xFF000000 or (v.toLongOrNull(16) ?: bad("colour must be hex: $s"))).toInt()
    }

    private fun parse(json: String): Any = try { org.json.JSONTokener(json).nextValue() } catch (e: JSONException) { bad("not JSON: ${e.message}") }
    private fun array(json: String, where: String): JSONArray = parse(json) as? JSONArray ?: bad("$where must be a list")
    private fun obj(v: Any?, where: String): JSONObject = v as? JSONObject ?: bad("$where must be an object")
    private fun bad(msg: String): Nothing = throw IllegalArgumentException(msg)
}
