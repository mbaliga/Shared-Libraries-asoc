package dev.aarso.search

import java.time.ZoneId

/**
 * Turns a raw search-box string into a [ParsedQuery].
 *
 * **Never throws.** Every malformed input degrades to a best-effort parse plus a [Diagnostic] —
 * see [Diagnostic]'s KDoc for why that is non-negotiable in an as-you-type box.
 *
 * ## Shape
 *
 * Two stages. [tokenize] flattens the raw string into a token list, tolerating the same malformed
 * input the parser does (an unterminated quote is treated as closing at end-of-input). Then a
 * small recursive-descent [Parser] walks that list with the grammar's precedence: `OR` lowest,
 * then `AND`/juxtaposition, then unary `NOT`/`-`, then primaries.
 *
 * ## Where the field vocabulary comes from
 *
 * The original resolved `key:` through a closed `Field` enum compiled into the parser. It now
 * resolves through a [FieldRegistry] the *host* supplies, which is the whole reason this module
 * is reusable. Everything the parser needs from a field is on that contract:
 * [FieldRegistry.get] (is this a field at all — one lookup answers recognition *and* hands back
 * the field, so the parser never asks [FieldRegistry.recognizes] separately and cannot get
 * different answers to the two questions), [FacetField.valueKind] (may this field be given this
 * shape of value), [FacetField.isBacked] (could anything match this value), and
 * [FieldRegistry.suggest] (did you mean). No enum, no `when`, no exhaustiveness to break when a
 * host adds a field.
 */
object QueryParser {

    /**
     * @param registry the host's facet vocabulary. Defaults to [NO_FACET_FIELDS], under which
     *   every `key:value` honestly degrades to literal text — right for a pure-text search box.
     * @param ctx supplies the clock that relative dates resolve against. It defaults to epoch,
     *   which is *not* a bug: the parser only uses the resolution to decide whether a date
     *   expression is well-formed at all, and well-formedness does not depend on the date. A
     *   caller that will evaluate the query should still pass its real context, because the
     *   evaluator resolves the same strings again and must agree with this parse.
     * @param zone the zone "today" is a question about. Explicit rather than
     *   [ZoneId.systemDefault] so that a parse is reproducible off the machine that ran it.
     */
    fun parse(
        rawText: String,
        registry: FieldRegistry<*> = NO_FACET_FIELDS,
        ctx: EvalContext = EvalContext(nowMillis = 0L),
        zone: ZoneId = ZoneId.of("UTC"),
    ): ParsedQuery {
        val diagnostics = mutableListOf<Diagnostic>()
        val tokens = tokenize(rawText, diagnostics)
        val root = Parser(tokens, diagnostics, registry, ctx, zone).parseQuery()
        return ParsedQuery(
            rawText = rawText,
            root = root,
            facets = collectFacets(root),
            semanticText = collectFirstSemantic(root),
            diagnostics = diagnostics,
            chips = ChipBuilder.build(root),
        )
    }

    /** Every facet anywhere in the tree, flattened — see [ParsedQuery.facets] for the caveat. */
    private fun collectFacets(node: QueryNode?): List<QueryNode.Facet> {
        if (node == null) return emptyList()
        return when (node) {
            is QueryNode.Facet -> listOf(node)
            is QueryNode.And -> node.children.flatMap(::collectFacets)
            is QueryNode.Or -> node.children.flatMap(::collectFacets)
            is QueryNode.Not -> collectFacets(node.child)
            else -> emptyList()
        }
    }

    private fun collectFirstSemantic(node: QueryNode?): String? {
        if (node == null) return null
        return when (node) {
            is QueryNode.Semantic -> node.text
            is QueryNode.And -> node.children.firstNotNullOfOrNull(::collectFirstSemantic)
            is QueryNode.Or -> node.children.firstNotNullOfOrNull(::collectFirstSemantic)
            is QueryNode.Not -> collectFirstSemantic(node.child)
            else -> null
        }
    }
}

// ============================== tokenizer ==============================

private data class Token(val kind: Kind, val text: String, val start: Int) {
    enum class Kind { WORD, FACET, PHRASE, REGEX, SEMANTIC, LPAREN, RPAREN, AND, OR, NOT }
}

/** Anything that is not whitespace, a paren, or a quote extends the current run. Note `:` is a
 *  word char — `foo:bar` has to survive as one run so an unknown field can degrade to a term. */
private fun isWordChar(c: Char) = !c.isWhitespace() && c != '(' && c != ')' && c != '"'

private fun tokenize(input: String, diagnostics: MutableList<Diagnostic>): List<Token> {
    val tokens = ArrayList<Token>()
    var i = 0
    val n = input.length
    while (i < n) {
        val c = input[i]
        when {
            c.isWhitespace() -> i++
            c == '(' -> { tokens.add(Token(Token.Kind.LPAREN, "(", i)); i++ }
            c == ')' -> { tokens.add(Token(Token.Kind.RPAREN, ")", i)); i++ }
            // A `-` directly before a phrase / regex / group / semantic term negates it, e.g.
            // `-"build cache"`, `-/draft/`, `-(a OR b)`. A `-` before a word or a `field:value`
            // is NOT handled here: scanWordOrFacet has to read the whole run before it can tell
            // a negated facet from a negated word, so it owns that case (and emits the `-` as
            // part of the token it produces).
            c == '-' && i + 1 < n &&
                (input[i + 1] == '"' || input[i + 1] == '/' || input[i + 1] == '(' || input[i + 1] == '?') -> {
                tokens.add(Token(Token.Kind.NOT, "-", i))
                i++
            }
            c == '"' -> {
                val start = i
                i++
                val sb = StringBuilder()
                var terminated = false
                while (i < n) {
                    if (input[i] == '"') { terminated = true; i++; break }
                    sb.append(input[i]); i++
                }
                if (!terminated) diagnostics.add(Diagnostic.UnterminatedQuote(start))
                tokens.add(Token(Token.Kind.PHRASE, sb.toString(), start))
            }
            c == '/' -> {
                val start = i
                i++
                val sb = StringBuilder()
                var terminated = false
                // A regex may not span whitespace: `/a b/` would otherwise swallow the rest of
                // the query on every intermediate keystroke of a path someone is typing.
                while (i < n && !input[i].isWhitespace()) {
                    if (input[i] == '/') { terminated = true; i++; break }
                    sb.append(input[i]); i++
                }
                if (terminated) {
                    var ignoreCase = false
                    if (i < n && input[i] == 'i') { ignoreCase = true; i++ }
                    tokens.add(Token(Token.Kind.REGEX, encodeRegex(sb.toString(), ignoreCase), start))
                } else {
                    diagnostics.add(Diagnostic.UnterminatedRegex(start))
                    // Degrade: the run we consumed becomes a plain term, never dropped.
                    tokens.add(Token(Token.Kind.WORD, "/" + sb.toString(), start))
                }
            }
            c == '?' -> {
                val start = i
                i++
                if (i < n && input[i] == '"') {
                    i++
                    val sb = StringBuilder()
                    var terminated = false
                    while (i < n) {
                        if (input[i] == '"') { terminated = true; i++; break }
                        sb.append(input[i]); i++
                    }
                    if (!terminated) diagnostics.add(Diagnostic.UnterminatedQuote(start))
                    tokens.add(Token(Token.Kind.SEMANTIC, sb.toString(), start))
                } else {
                    val sb = StringBuilder()
                    while (i < n && isWordChar(input[i])) { sb.append(input[i]); i++ }
                    tokens.add(Token(Token.Kind.SEMANTIC, sb.toString(), start))
                }
            }
            else -> i = scanWordOrFacet(input, i, tokens, diagnostics)
        }
    }
    return tokens
}

/**
 * Handles bare words, `AND`/`OR`/`|`/`NOT` keywords, `field:value` facets (quoted or bare value),
 * and a leading `-` negating either of the above. Returns the new scan position.
 *
 * Note this stage does not consult the [FieldRegistry] at all: it decides only that something is
 * *shaped* like `ident:value`. Whether `ident` is a real field is the parser's question, because
 * the answer changes the node type (facet vs. literal term) and only the parser builds nodes.
 */
private fun scanWordOrFacet(
    input: String,
    start: Int,
    tokens: MutableList<Token>,
    diagnostics: MutableList<Diagnostic>,
): Int {
    val n = input.length
    var i = start
    val negated = input[i] == '-' && i + 1 < n && (input[i + 1].isLetterOrDigit() || input[i + 1] == '_')
    if (negated) i++

    val ident = StringBuilder()
    while (i < n && (input[i].isLetterOrDigit() || input[i] == '_')) { ident.append(input[i]); i++ }

    if (ident.isNotEmpty() && i < n && input[i] == ':') {
        val field = ident.toString()
        i++
        val valueRaw: String
        if (i < n && input[i] == '"') {
            i++
            val sb = StringBuilder()
            var terminated = false
            while (i < n) {
                if (input[i] == '"') { terminated = true; i++; break }
                sb.append(input[i]); i++
            }
            if (!terminated) diagnostics.add(Diagnostic.UnterminatedQuote(start))
            // The quotes are kept here and stripped in parseFacetBody, *after* operator
            // detection, so that a quoted `key:">=a b"` still yields Op.GTE — see
            // QueryCompiler.renderCanonical, which quotes operator-and-value together for
            // exactly this round trip.
            valueRaw = "\"" + sb + "\""
        } else {
            val sb = StringBuilder()
            while (i < n && isWordChar(input[i])) { sb.append(input[i]); i++ }
            valueRaw = sb.toString()
        }
        val prefix = if (negated) "-" else ""
        tokens.add(Token(Token.Kind.FACET, "$prefix$field:$valueRaw", start))
        return i
    }

    // Not a facet after all: rewind and consume as a plain word (dash included, if any).
    i = start
    val sb = StringBuilder()
    while (i < n && isWordChar(input[i])) { sb.append(input[i]); i++ }
    if (sb.isEmpty()) return start + 1 // guard against an infinite loop on an unexpected char
    val word = sb.toString()
    when {
        // `ignoreCase` here is ASCII-only by construction and deliberately not locale-aware:
        // these are grammar keywords, not user text, and a Turkish locale lowercasing `AND`
        // to `ınd` would make the operator unusable for one locale's users only.
        word.equals("AND", ignoreCase = true) -> tokens.add(Token(Token.Kind.AND, word, start))
        word.equals("OR", ignoreCase = true) || word == "|" -> tokens.add(Token(Token.Kind.OR, word, start))
        word.equals("NOT", ignoreCase = true) -> tokens.add(Token(Token.Kind.NOT, word, start))
        else -> tokens.add(Token(Token.Kind.WORD, word, start))
    }
    return i
}

// A regex token has to carry two things through a single-string Token; a trailing marker that
// cannot occur inside a regex literal (it contains a space, and regex literals stop at
// whitespace) is cheaper than a second token field used by one kind out of ten.
private const val IGNORE_CASE_MARKER = " i"
private fun encodeRegex(pattern: String, ignoreCase: Boolean) =
    if (ignoreCase) pattern + IGNORE_CASE_MARKER else pattern
private fun decodeRegexPattern(encoded: String) = encoded.removeSuffix(IGNORE_CASE_MARKER)
private fun decodeRegexIgnoreCase(encoded: String) = encoded.endsWith(IGNORE_CASE_MARKER)

// ============================== parser ==============================

private class Parser(
    private val tokens: List<Token>,
    private val diagnostics: MutableList<Diagnostic>,
    private val registry: FieldRegistry<*>,
    private val ctx: EvalContext,
    private val zone: ZoneId,
) {
    private var pos = 0
    private fun peek(): Token? = tokens.getOrNull(pos)
    private fun advance(): Token? = tokens.getOrNull(pos++)

    fun parseQuery(): QueryNode? = if (tokens.isEmpty()) null else parseOr()

    private fun parseOr(): QueryNode? {
        val first = parseAnd() ?: return null
        val children = mutableListOf(first)
        while (peek()?.kind == Token.Kind.OR) {
            advance()
            parseAnd()?.let(children::add)
        }
        return if (children.size == 1) children[0] else QueryNode.Or(children)
    }

    private fun parseAnd(): QueryNode? {
        val first = parseUnary() ?: return null
        val children = mutableListOf(first)
        while (true) {
            val t = peek() ?: break
            if (t.kind == Token.Kind.OR || t.kind == Token.Kind.RPAREN) break
            if (t.kind == Token.Kind.AND) advance() // explicit AND; juxtaposition (no token) also ANDs
            val next = parseUnary() ?: break
            children.add(next)
        }
        return if (children.size == 1) children[0] else QueryNode.And(children)
    }

    private fun parseUnary(): QueryNode? {
        val t = peek() ?: return null
        if (t.kind == Token.Kind.NOT) {
            advance()
            return parseUnary()?.let(QueryNode::Not)
        }
        if (t.kind == Token.Kind.WORD && t.text.length > 1 && t.text[0] == '-') {
            advance()
            val bare = t.text.substring(1)
            val prefix = bare.endsWith("*")
            return QueryNode.Not(QueryNode.Term(if (prefix) bare.dropLast(1) else bare, prefix))
        }
        if (t.kind == Token.Kind.FACET && t.text.startsWith("-")) {
            advance()
            return QueryNode.Not(parseFacetBody(t.text.substring(1)))
        }
        return parsePrimary()
    }

    private fun parsePrimary(): QueryNode? {
        val t = peek() ?: return null
        return when (t.kind) {
            Token.Kind.LPAREN -> {
                advance()
                val inner = parseOr()
                if (peek()?.kind == Token.Kind.RPAREN) advance() // tolerate a missing close paren
                inner
            }
            Token.Kind.PHRASE -> { advance(); QueryNode.Phrase(t.text) }
            Token.Kind.REGEX -> {
                advance()
                QueryNode.Regex(decodeRegexPattern(t.text), decodeRegexIgnoreCase(t.text))
            }
            Token.Kind.SEMANTIC -> { advance(); QueryNode.Semantic(t.text) }
            Token.Kind.FACET -> { advance(); parseFacetBody(t.text) }
            Token.Kind.WORD -> {
                advance()
                val prefix = t.text.endsWith("*")
                QueryNode.Term(if (prefix) t.text.dropLast(1) else t.text, prefix)
            }
            // A stray close-paren, or a keyword in a position that isn't valid grammar (e.g. two
            // ORs in a row) — nothing more to parse here; the caller's loop just stops. Never throws.
            Token.Kind.RPAREN, Token.Kind.AND, Token.Kind.OR, Token.Kind.NOT -> null
        }
    }

    /**
     * `key:value` → a [QueryNode.Facet], or a literal [QueryNode.Term] when the host's registry
     * does not know the key.
     *
     * Degrading an unknown field to a term rather than dropping it is the diagnostic model in
     * miniature: `ratio:3` in an app with no `ratio` field still searches for the text `ratio:3`,
     * which is occasionally even what the user wanted, and is never nothing.
     */
    private fun parseFacetBody(raw: String): QueryNode {
        val colon = raw.indexOf(':')
        val keyRaw = raw.substring(0, colon)
        var valueRaw = raw.substring(colon + 1)
        if (valueRaw.length >= 2 && valueRaw.startsWith("\"") && valueRaw.endsWith("\"")) {
            valueRaw = valueRaw.substring(1, valueRaw.length - 1)
        }

        val field = registry[keyRaw]
        if (field == null) {
            diagnostics.add(Diagnostic.UnknownField(keyRaw, registry.suggest(keyRaw)))
            return QueryNode.Term("$keyRaw:$valueRaw", prefix = false)
        }

        // Operator stripping. Longest-first, or `>=5` parses as GT of `=5`.
        var op = Op.EQ
        var value = valueRaw
        when {
            value.startsWith(">=") -> { op = Op.GTE; value = value.removePrefix(">=") }
            value.startsWith("<=") -> { op = Op.LTE; value = value.removePrefix("<=") }
            value.startsWith("!=") -> { op = Op.NE; value = value.removePrefix("!=") }
            value.startsWith(">") -> { op = Op.GT; value = value.removePrefix(">") }
            value.startsWith("<") -> { op = Op.LT; value = value.removePrefix("<") }
            // A range stays Op.EQ with the `..` intact — see QueryNode.Facet.rangeBounds for
            // why this module does not have (and does not want) an Op.RANGE.
        }

        validateValueKind(field, keyRaw, op, value)

        // An unbacked value is an honest zero, announced rather than silently returning nothing.
        // Checked against the operator-stripped value, so `size:>10mb` asks the host about
        // `10mb`, not about `>10mb`.
        if (!field.isBacked(value)) {
            diagnostics.add(Diagnostic.UnindexedFacet(keyRaw, value))
        }
        return QueryNode.Facet(keyRaw, op, value)
    }

    /**
     * The parse-time half of [FacetField.valueKind]: catch `turns:yesterday` here, where it can be
     * shown next to the search box, instead of at evaluation time where it is indistinguishable
     * from "no results".
     *
     * Only DATE and NUMBER are checked. ENUM and BOOL values are already covered by
     * [FacetField.isBacked] — the host knows its own enumerations, and a second opinion here
     * would be a second source of truth that can disagree. TEXT accepts anything by definition.
     *
     * A failed check is a diagnostic, never a rejection: the node is still built, because the host
     * may recognise a value shape this module doesn't (a locale date format, a unit suffix).
     */
    private fun validateValueKind(field: FacetField<*>, key: String, op: Op, value: String) {
        val parts = if (op == Op.EQ) value.split(QueryNode.Facet.RANGE_SEPARATOR, limit = 2) else listOf(value)
        when (field.valueKind) {
            FacetValueKind.DATE ->
                if (parts.any { RelativeDate.resolveRange(it, ctx, zone) == null }) {
                    diagnostics.add(Diagnostic.InvalidDate(key, value))
                }
            FacetValueKind.NUMBER ->
                if (parts.any { !looksNumeric(it) }) {
                    diagnostics.add(Diagnostic.InvalidNumber(key, value))
                }
            FacetValueKind.ENUM, FacetValueKind.TEXT, FacetValueKind.BOOL -> Unit
        }
    }
}

/**
 * Loose enough to accept `10`, `-3`, `1.5`, `10mb`, `250ms`, `80%`; strict enough to reject
 * `yesterday` and `pdf`.
 *
 * Deliberately not `toDoubleOrNull()`: `Model.kt` uses `size:>10mb` as its own worked example of a
 * facet term, so unit suffixes are part of the contract and belong to the host's
 * [FacetField.matches]. All this check asserts is "a number was at least attempted".
 */
private fun looksNumeric(raw: String): Boolean {
    val s = raw.trim()
    if (s.isEmpty()) return false
    var i = 0
    if (s[i] == '+' || s[i] == '-') i++
    val digitsStart = i
    while (i < s.length && s[i].isDigit()) i++
    if (i == digitsStart) return false
    if (i < s.length && s[i] == '.') {
        i++
        while (i < s.length && s[i].isDigit()) i++
    }
    // Whatever trails must be a plain unit token, not more punctuation or another number.
    return s.substring(i).all { it.isLetter() || it == '%' }
}
