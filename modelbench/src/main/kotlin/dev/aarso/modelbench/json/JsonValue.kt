package dev.aarso.modelbench.json

/**
 * A tiny hand-rolled JSON value model + renderer — deliberately no external dependency, same
 * zero-runtime-dependency posture as `:search-core`/`:diagnostics-core`. It exists to give
 * [dev.aarso.modelbench.ModelBenchReport] a single place that controls key order (matching
 * `schema/modelbench-report.v1.schema.json`'s property order) and escaping, instead of manual
 * string concatenation scattered across the report serializer.
 *
 * This is a writer only. Nothing in this module parses JSON back — a consumer that needs to read
 * a `modelbench-report.v1` file brings its own JSON library; `:modelbench`'s own round-trip test
 * uses one test-scoped dependency for that (see the module's build.gradle.kts comment).
 */
internal sealed class JsonValue {
    data class JObject(val entries: List<Pair<String, JsonValue>>) : JsonValue()
    data class JArray(val items: List<JsonValue>) : JsonValue()
    data class JString(val value: String) : JsonValue()

    /** [literal] is a pre-formatted JSON number token — callers go through [jInt]/[jLong]/[jDouble]. */
    data class JNumber(val literal: String) : JsonValue()
    data class JBool(val value: Boolean) : JsonValue()
    object JNull : JsonValue()
}

internal fun JsonValue.render(): String = StringBuilder().also { renderInto(it) }.toString()

private fun JsonValue.renderInto(sb: StringBuilder) {
    when (this) {
        is JsonValue.JObject -> {
            sb.append('{')
            entries.forEachIndexed { i, (key, value) ->
                if (i > 0) sb.append(',')
                sb.append(jsonEscapedString(key))
                sb.append(':')
                value.renderInto(sb)
            }
            sb.append('}')
        }
        is JsonValue.JArray -> {
            sb.append('[')
            items.forEachIndexed { i, value ->
                if (i > 0) sb.append(',')
                value.renderInto(sb)
            }
            sb.append(']')
        }
        is JsonValue.JString -> sb.append(jsonEscapedString(value))
        is JsonValue.JNumber -> sb.append(literal)
        is JsonValue.JBool -> sb.append(if (value) "true" else "false")
        JsonValue.JNull -> sb.append("null")
    }
}

private fun jsonEscapedString(s: String): String = buildString {
    append('"')
    for (c in s) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) {
                append("\\u").append(c.code.toString(16).padStart(4, '0'))
            } else {
                append(c)
            }
        }
    }
    append('"')
}

internal fun jObj(vararg entries: Pair<String, JsonValue>): JsonValue.JObject = JsonValue.JObject(entries.toList())
internal fun jArr(items: List<JsonValue>): JsonValue.JArray = JsonValue.JArray(items)
internal fun jStr(s: String): JsonValue.JString = JsonValue.JString(s)
internal fun jStrOrNull(s: String?): JsonValue = if (s == null) JsonValue.JNull else JsonValue.JString(s)
internal fun jInt(n: Int): JsonValue.JNumber = JsonValue.JNumber(n.toString())
internal fun jIntOrNull(n: Int?): JsonValue = if (n == null) JsonValue.JNull else jInt(n)
internal fun jLongOrNull(n: Long?): JsonValue = if (n == null) JsonValue.JNull else JsonValue.JNumber(n.toString())

internal fun jDouble(n: Double): JsonValue.JNumber {
    require(n.isFinite()) { "JSON number must be finite, got $n" }
    return JsonValue.JNumber(n.toString())
}

internal fun jDoubleOrNull(n: Double?): JsonValue = if (n == null) JsonValue.JNull else jDouble(n)
