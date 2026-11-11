package dev.aarso.evidence

/**
 * A small strict JSON reader: objects become [Map], arrays [List], numbers [Double], plus
 * [String], [Boolean] and null. It exists so the evidence module stays dependency-free (pure JVM,
 * no org.json, no Android) and can validate records off-device.
 */
internal object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        require(p.atEnd()) { "trailing characters at ${p.pos}" }
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun ws() {
            while (pos < s.length && s[pos] in " \t\r\n") pos++
        }

        fun value(): Any? {
            require(!atEnd()) { "unexpected end of input" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $pos")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return v
        }

        private fun num(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            return s.substring(start, pos).toDouble()
        }

        private fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                require(!atEnd()) { "unterminated string" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(!atEnd()) { "unterminated escape" }
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 <= s.length) { "short \\u escape" }
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("bad escape \\$e at $pos")
                        }
                    }
                    else -> {
                        require(c >= ' ') { "control character in string at $pos" }
                        sb.append(c)
                    }
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            ws()
            if (s.getOrNull(pos) == ']') { pos++; return out }
            while (true) {
                ws()
                out.add(value())
                ws()
                when (s.getOrNull(pos++)) {
                    ',' -> continue
                    ']' -> return out
                    else -> error("expected , or ] at ${pos - 1}")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (s.getOrNull(pos) == '}') { pos++; return out }
            while (true) {
                ws()
                require(s.getOrNull(pos) == '"') { "expected key at $pos" }
                val k = str()
                ws()
                require(s.getOrNull(pos++) == ':') { "expected : at ${pos - 1}" }
                ws()
                require(!out.containsKey(k)) { "duplicate key '$k'" }
                out[k] = value()
                ws()
                when (s.getOrNull(pos++)) {
                    ',' -> continue
                    '}' -> return out
                    else -> error("expected , or } at ${pos - 1}")
                }
            }
        }
    }
}
