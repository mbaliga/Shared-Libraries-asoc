package dev.aarso.evidence

/**
 * Validates a `platform-evidence/1` record against the bundled JSON Schema.
 *
 * This implements only the JSON Schema keywords that `platform-evidence.schema.json` uses
 * (type, enum, const, required, properties, additionalProperties, items, pattern, minLength,
 * minItems, minimum, format date-time, allOf, anyOf, if/then, local $ref). It is a data check:
 * it runs on the JVM anywhere and says nothing about whether any device behaviour is true.
 * An unsupported keyword in the schema fails loudly rather than being ignored.
 */
object PlatformEvidenceValidator {
    const val SCHEMA_RESOURCE = "evidence/v1/platform-evidence.schema.json"

    private val supported = setOf(
        "\$schema", "\$id", "title", "description", "\$defs", "\$ref", "type", "enum", "const",
        "required", "properties", "additionalProperties", "items", "pattern", "minLength",
        "minItems", "minimum", "format", "allOf", "anyOf", "if", "then",
    )

    private val dateTime =
        Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$")

    private val root: Map<String, Any?> by lazy {
        val text = requireNotNull(PlatformEvidenceValidator::class.java.classLoader.getResource(SCHEMA_RESOURCE)) {
            "missing $SCHEMA_RESOURCE"
        }.readText(Charsets.UTF_8)
        @Suppress("UNCHECKED_CAST")
        MiniJson.parse(text) as Map<String, Any?>
    }

    /** The parsed schema document, for tests that inspect it. */
    fun schema(): Map<String, Any?> = root

    /** Returns every violation as `path: message`; an empty list means the record is valid. */
    fun validate(recordJson: String): List<String> {
        val doc = try {
            MiniJson.parse(recordJson)
        } catch (e: IllegalArgumentException) {
            return listOf("\$: not valid JSON (${e.message})")
        } catch (e: IllegalStateException) {
            return listOf("\$: not valid JSON (${e.message})")
        }
        val errors = ArrayList<String>()
        check(doc, root, "\$", errors)
        return errors
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolve(ref: String): Map<String, Any?> {
        require(ref.startsWith("#/")) { "only local refs are supported: $ref" }
        var node: Any? = root
        for (part in ref.removePrefix("#/").split('/')) node = (node as Map<String, Any?>)[part]
        return requireNotNull(node as? Map<String, Any?>) { "unresolved ref $ref" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun check(v: Any?, schema: Map<String, Any?>, path: String, out: MutableList<String>) {
        val unknown = schema.keys - supported
        require(unknown.isEmpty()) { "unsupported schema keyword(s) $unknown at $path" }
        (schema["\$ref"] as? String)?.let { check(v, resolve(it), path, out) }
        schema["const"]?.let { if (v != it) out += "$path: must equal '$it'" }
        (schema["enum"] as? List<Any?>)?.let { if (v !in it) out += "$path: '$v' is not one of $it" }
        schema["type"]?.let { t ->
            val types = if (t is List<*>) t.map { it as String } else listOf(t as String)
            if (types.none { matchesType(v, it) }) out += "$path: expected type $types"
        }
        if (v is String) {
            (schema["minLength"] as? Double)?.let { if (v.length < it) out += "$path: shorter than $it" }
            (schema["pattern"] as? String)?.let { if (!Regex(it).containsMatchIn(v)) out += "$path: does not match /$it/" }
            if (schema["format"] == "date-time" && !dateTime.matches(v)) out += "$path: not an RFC 3339 date-time"
        }
        if (v is Double) (schema["minimum"] as? Double)?.let { if (v < it) out += "$path: below $it" }
        if (v is List<*>) {
            (schema["minItems"] as? Double)?.let { if (v.size < it) out += "$path: fewer than $it items" }
            (schema["items"] as? Map<String, Any?>)?.let { s -> v.forEachIndexed { i, e -> check(e, s, "$path[$i]", out) } }
        }
        if (v is Map<*, *>) {
            val m = v as Map<String, Any?>
            (schema["required"] as? List<String>)?.forEach { if (it !in m) out += "$path: missing required '$it'" }
            val props = schema["properties"] as? Map<String, Map<String, Any?>>
            props?.forEach { (k, s) -> if (k in m) check(m[k], s, "$path.$k", out) }
            if (schema["additionalProperties"] == false) {
                m.keys.filter { props == null || it !in props }.forEach { out += "$path: unknown property '$it'" }
            }
        }
        (schema["allOf"] as? List<Map<String, Any?>>)?.forEach { check(v, it, path, out) }
        (schema["anyOf"] as? List<Map<String, Any?>>)?.let { alts ->
            val ok = alts.any { alt -> ArrayList<String>().also { check(v, alt, path, it) }.isEmpty() }
            if (!ok) out += "$path: matches none of the allowed alternatives"
        }
        (schema["if"] as? Map<String, Any?>)?.let { cond ->
            val holds = ArrayList<String>().also { check(v, cond, path, it) }.isEmpty()
            if (holds) (schema["then"] as? Map<String, Any?>)?.let { check(v, it, path, out) }
        }
    }

    private fun matchesType(v: Any?, t: String): Boolean = when (t) {
        "object" -> v is Map<*, *>
        "array" -> v is List<*>
        "string" -> v is String
        "number" -> v is Double
        "integer" -> v is Double && v == Math.floor(v)
        "boolean" -> v is Boolean
        "null" -> v == null
        else -> error("unsupported type '$t'")
    }
}
