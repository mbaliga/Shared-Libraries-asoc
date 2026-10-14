package dev.aarso.feedback

import java.io.File

/**
 * Test-only JSON reader/writer for the golden spec fixtures under `specs/fixtures/`
 * (PORTING_PLAN.md S0.2).
 *
 * Why this exists instead of a JSON library: the module build files must stay untouched in
 * Phase 0, and Android unit tests cannot use `org.json` (the platform stubs throw). The three
 * copies of this file (cell-shell, crash-recovery, feedback) are byte-identical except for the
 * `package` line; `specs/check-json-helper-sync.sh` enforces that in CI.
 *
 * Values are `Map<String, Any?>` (insertion-ordered), `List<Any?>`, `String`, `Double`,
 * `Boolean` and `null`.
 */
internal object SpecJson {

    // ---- locating the fixtures ------------------------------------------------------------

    /** `specs/fixtures/<name>` found by walking up from the working directory. */
    fun fixture(name: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "specs/fixtures/$name")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("spec fixture specs/fixtures/$name not found above ${System.getProperty("user.dir")}")
    }

    /** True when the opt-in switch for regenerating golden files is on. */
    fun regenerating(): Boolean = System.getenv("SPEC_FIXTURES_REGENERATE") == "1"

    /**
     * Writes a regenerated fixture and then FAILS the calling test on purpose, so the switch can
     * never produce a green run: a regenerated golden file has to be reviewed (`git diff
     * specs/fixtures`) and the suite rerun without the switch.
     */
    fun saveRegeneratedAndFail(file: File, root: Any?): Nothing {
        file.writeText(write(root) + "\n", Charsets.UTF_8)
        throw AssertionError(
            "SPEC_FIXTURES_REGENERATE=1: rewrote ${file.path}. Review the diff, then rerun WITHOUT the switch.",
        )
    }

    fun load(name: String): Pair<File, Any?> {
        val file = fixture(name)
        return file to parse(file.readText(Charsets.UTF_8))
    }

    // ---- typed access ---------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): MutableMap<String, Any?> = v as? MutableMap<String, Any?> ?: throw AssertionError("not an object: $v")

    @Suppress("UNCHECKED_CAST")
    fun arr(v: Any?): MutableList<Any?> = v as? MutableList<Any?> ?: throw AssertionError("not an array: $v")

    // ---- parser ---------------------------------------------------------------------------

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("trailing characters at ${p.pos}")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        private fun fail(msg: String): Nothing = throw IllegalArgumentException("$msg at $pos")
        private fun expect(c: Char) { if (pos >= s.length || s[pos] != c) fail("expected '$c'"); pos++ }

        fun value(): Any? {
            skipWs()
            if (atEnd()) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("expected $word")
            pos += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                val k = str()
                skipWs(); expect(':')
                m[k] = value()
                skipWs()
                if (s[pos] == ',') { pos++; continue }
                expect('}'); return m
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            val l = ArrayList<Any?>()
            skipWs()
            if (s[pos] == ']') { pos++; return l }
            while (true) {
                l.add(value())
                skipWs()
                if (s[pos] == ',') { pos++; continue }
                expect(']'); return l
            }
        }

        private fun str(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("bad escape")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> b.append(e)
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad \\u escape")
                                b.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun num(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: fail("bad number")
        }
    }

    // ---- writer (stable: insertion order, 2-space indent, UTF-8 left as-is) ---------------

    fun write(v: Any?): String = StringBuilder().also { emit(it, v, 0) }.toString()

    private fun emit(b: StringBuilder, v: Any?, depth: Int) {
        when (v) {
            null -> b.append("null")
            is Boolean -> b.append(v)
            is Number -> b.append(number(v.toDouble()))
            is String -> quote(b, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { b.append("{}"); return }
                b.append("{\n")
                var first = true
                for ((k, x) in v) {
                    if (!first) b.append(",\n")
                    first = false
                    indent(b, depth + 1); quote(b, k.toString()); b.append(": "); emit(b, x, depth + 1)
                }
                b.append('\n'); indent(b, depth); b.append('}')
            }
            is List<*> -> {
                if (v.isEmpty()) { b.append("[]"); return }
                b.append("[\n")
                v.forEachIndexed { i, x ->
                    if (i > 0) b.append(",\n")
                    indent(b, depth + 1); emit(b, x, depth + 1)
                }
                b.append('\n'); indent(b, depth); b.append(']')
            }
            else -> throw IllegalArgumentException("cannot serialise ${v::class}")
        }
    }

    private fun indent(b: StringBuilder, depth: Int) { repeat(depth) { b.append("  ") } }

    private fun number(d: Double): String =
        if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun quote(b: StringBuilder, s: String) {
        b.append('"')
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' -> b.append("\\u%04x".format(c.code))
            else -> b.append(c)
        }
        b.append('"')
    }

    /** A float constant as the short decimal a human would write (0.1f -> 0.1, not 0.10000000149). */
    fun clean(f: Float): Double = f.toString().toDouble()
}
