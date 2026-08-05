package dev.aarso.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from Android-IDE-core's `QueryParserTest`.
 *
 * ## Two mechanical adaptations, and one thing that moved
 *
 * **`Field.MODEL` becomes `"model"`.** The original grammar was nailed to a closed `Field` enum;
 * here the vocabulary is a [FieldRegistry] the host supplies, so every assertion that read
 * `facet.field == Field.MODEL` now reads `facet.key == "model"` resolved through
 * [TestVocabulary.registry] — the same 18 keys, rebuilt as data. See that file for why the
 * rebuild is itself the claim under test.
 *
 * **`ftsExpression` moved from a field to a call.** The original computed the FTS5 expression
 * eagerly inside `parse` and hung it on `ParsedQuery`; its only consumer ignored it and recompiled
 * with different options, so every keystroke walked the tree twice. The field is gone. The
 * assertions about it are *not* dropped, because they pin real grammar behaviour (facets never
 * reach FTS, a pure-facet query yields no expression, a quoted phrase is escaped) — they are
 * re-expressed against [QueryCompiler.compileFts], which is exactly where that computation went.
 * The one thing genuinely lost is the assertion that the expression is computed *at parse time*,
 * which is now false on purpose.
 */
class QueryParserTest {

    private val registry = TestVocabulary.registry

    private fun parse(text: String) =
        QueryParser.parse(text, registry, TestVocabulary.CTX, TestVocabulary.ZONE)

    private fun fts(text: String): String? = QueryCompiler.compileFts(parse(text).root)

    // ---- basics ----

    @Test fun `blank query parses to an empty, non-crashing result`() {
        val q = parse("   ")
        assertNull(q.root)
        assertNull(QueryCompiler.compileFts(q.root))
        assertTrue(q.facets.isEmpty())
        assertTrue(q.chips.isEmpty())
        assertTrue(q.diagnostics.isEmpty())
    }

    @Test fun `single term parses and compiles to a quoted fts match`() {
        val q = parse("gradle")
        assertEquals(QueryNode.Term("gradle", false), q.root)
        assertEquals("\"gradle\"", QueryCompiler.compileFts(q.root))
    }

    @Test fun `as-you-type compilation prefixes a bare term`() {
        // New in the port: the original's single eager expression could not serve both callers, so
        // its one consumer recompiled. Now the choice is a parameter.
        assertEquals("\"gradle\"*", QueryCompiler.compileFts(parse("gradle").root, prefixBareTerms = true))
    }

    @Test fun `juxtaposed terms are an implicit AND`() {
        val q = parse("gradle cache")
        assertEquals(QueryNode.And(listOf(QueryNode.Term("gradle", false), QueryNode.Term("cache", false))), q.root)
    }

    @Test fun `explicit AND keyword behaves the same as juxtaposition`() {
        assertEquals(parse("gradle cache").root, parse("gradle AND cache").root)
    }

    @Test fun `trailing star marks a term as an explicit prefix`() {
        assertEquals(QueryNode.Term("grad", true), parse("grad*").root)
    }

    @Test fun `OR and pipe both parse to Or`() {
        assertEquals(parse("gradle OR maven").root, parse("gradle | maven").root)
        assertTrue(parse("gradle OR maven").root is QueryNode.Or)
    }

    @Test fun `leading dash negates a term`() {
        assertEquals(QueryNode.Not(QueryNode.Term("image", false)), parse("-image").root)
    }

    @Test fun `NOT keyword negates the following primary`() {
        assertEquals(QueryNode.Not(QueryNode.Term("image", false)), parse("NOT image").root)
    }

    @Test fun `parentheses group an OR inside a larger AND`() {
        val q = parse("is:starred (gradle OR maven) -has:image")
        val expected = QueryNode.And(
            listOf(
                QueryNode.Facet("is", Op.EQ, "starred"),
                QueryNode.Or(listOf(QueryNode.Term("gradle", false), QueryNode.Term("maven", false))),
                QueryNode.Not(QueryNode.Facet("has", Op.EQ, "image")),
            ),
        )
        assertEquals(expected, q.root)
    }

    @Test fun `quoted phrase is a Phrase node and an fts phrase match`() {
        val q = parse("\"build cache\"")
        assertEquals(QueryNode.Phrase("build cache"), q.root)
        assertEquals("\"build cache\"", QueryCompiler.compileFts(q.root))
    }

    @Test fun `regex literal parses with the trailing i flag`() {
        assertEquals(QueryNode.Regex("use.*State", true), parse("/use.*State/i").root)
        assertEquals(QueryNode.Regex("use.*State", false), parse("/use.*State/").root)
    }

    @Test fun `semantic operator captures a bare word`() {
        assertEquals(QueryNode.Semantic("gradle"), parse("?gradle").root)
    }

    @Test fun `semantic operator captures a quoted phrase`() {
        assertEquals(QueryNode.Semantic("gradle build cache"), parse("?\"gradle build cache\"").root)
    }

    @Test fun `semantic text is surfaced separately for the AI-unavailable degrade path`() {
        val q = parse("?gradle")
        assertEquals("gradle", q.semanticText)
        // Hard rule 1: the raw query still runs lexically even when semantic search can't.
        assertNotNull(QueryCompiler.compileFts(q.root))
    }

    // ---- facets: every field, every operator ----

    @Test fun `every field key parses to a facet the registry recognizes`() {
        val fixtures = listOf(
            "in:trash", "is:starred", "has:image", "project:Aarso", "model:opus", "tag:idea",
            "room:chats", "file:build.gradle.kts", "tool:bash", "build:fail", "branch:>3",
            "turns:>10", "cost:>0.50", "before:2026-01-01", "after:-7d", "during:last-week",
            "lang:hi", "loop:abc123",
        )
        assertEquals("the original vocabulary had 18 keys", 18, fixtures.size)
        for (text in fixtures) {
            val facet = parse(text).root as? QueryNode.Facet
            assertNotNull("expected a Facet for '$text'", facet)
            // The key is kept exactly as typed — chips round-trip back into the search box, and
            // silently rewriting an alias mid-edit moves the user's cursor out from under them.
            assertEquals(text, text.substringBefore(':'), facet!!.key)
            assertNotNull("registry must resolve '${facet.key}'", registry[facet.key])
        }
    }

    @Test fun `an alias resolves to its canonical field without being rewritten`() {
        val facet = parse("in:Hyle").root as QueryNode.Facet
        assertEquals("in", facet.key)
        assertEquals("project", registry[facet.key]!!.key)
        // Aliases are resolvable but not advertised, so they never show up in "did you mean".
        assertFalse(registry.keys.contains("in"))
    }

    @Test fun `comparison operators parse and strip from the value`() {
        assertEquals(QueryNode.Facet("cost", Op.GT, "0.50"), parse("cost:>0.50").root)
        assertEquals(QueryNode.Facet("cost", Op.GTE, "0.50"), parse("cost:>=0.50").root)
        assertEquals(QueryNode.Facet("turns", Op.LT, "5"), parse("turns:<5").root)
        assertEquals(QueryNode.Facet("turns", Op.LTE, "5"), parse("turns:<=5").root)
        assertEquals(QueryNode.Facet("turns", Op.NE, "5"), parse("turns:!=5").root)
    }

    /**
     * The original had a sixth operator, `Op.RANGE`. [Op] is a fixed contract in this module and has
     * no such value — [FacetField.matches] receives exactly one `(op, value)` pair, and a range is
     * not a comparison. A range is therefore [Op.EQ] with the `..` left in the value, recovered
     * through [QueryNode.Facet.rangeBounds]. The wire shape is unchanged: `cost:0.10..1.00` still
     * renders and reparses identically.
     */
    @Test fun `a range is Op EQ carrying its bounds, not a sixth operator`() {
        val facet = parse("cost:0.10..1.00").root as QueryNode.Facet
        assertEquals(Op.EQ, facet.op)
        assertEquals("0.10..1.00", facet.value)
        assertTrue(facet.isRange)
        assertEquals("0.10" to "1.00", facet.rangeBounds)
    }

    @Test fun `a comparison operator cannot also be a range`() {
        // `>=10..50` is not a thing anyone means, and the accessor must not pretend otherwise.
        assertNull((parse("cost:>=10..50").root as QueryNode.Facet).rangeBounds)
    }

    @Test fun `quoted facet value is unwrapped`() {
        assertEquals(QueryNode.Facet("project", Op.EQ, "my project"), parse("project:\"my project\"").root)
    }

    @Test fun `an operator survives being quoted along with its value`() {
        // The parser strips quotes *after* operator detection, which is what makes
        // QueryCompiler.renderCanonical's `key:">=a b"` shape round-trip.
        assertEquals(QueryNode.Facet("project", Op.GTE, "a b"), parse("project:\">=a b\"").root)
    }

    @Test fun `negated facet wraps in Not`() {
        assertEquals(QueryNode.Not(QueryNode.Facet("has", Op.EQ, "image")), parse("-has:image").root)
    }

    // ---- diagnostics: degrade, never throw ----

    @Test fun `unknown field degrades to a plain term with a did-you-mean diagnostic`() {
        val q = parse("mdel:opus")
        assertEquals(QueryNode.Term("mdel:opus", false), q.root)
        val diag = q.diagnostics.filterIsInstance<Diagnostic.UnknownField>().single()
        assertEquals("mdel", diag.typed)
        assertEquals("model", diag.suggestion)
    }

    @Test fun `an unknown field with nothing close by suggests nothing rather than guessing`() {
        val diag = parse("zzzzzzzz:x").diagnostics.filterIsInstance<Diagnostic.UnknownField>().single()
        assertNull(diag.suggestion)
    }

    @Test fun `unindexed facet still parses but is flagged`() {
        val q = parse("tool:bash")
        assertEquals(QueryNode.Facet("tool", Op.EQ, "bash"), q.root)
        val diag = q.diagnostics.filterIsInstance<Diagnostic.UnindexedFacet>().single()
        assertEquals("tool", diag.key)
        assertEquals("bash", diag.value)
    }

    @Test fun `backed facet value produces no unindexed diagnostic`() {
        assertTrue(parse("is:starred").diagnostics.filterIsInstance<Diagnostic.UnindexedFacet>().isEmpty())
    }

    @Test fun `is field splits backed and unindexed by value`() {
        assertTrue(parse("is:starred").diagnostics.filterIsInstance<Diagnostic.UnindexedFacet>().isEmpty())
        assertFalse(parse("is:unread").diagnostics.filterIsInstance<Diagnostic.UnindexedFacet>().isEmpty())
    }

    /**
     * The diagnostic half of the `!=` fix. [Diagnostic.UnindexedFacet] means "an honest zero — no
     * results, because nothing has that value". Under [Op.NE] an unbacked value is the *opposite*
     * of a zero: nothing carries it, so every subject satisfies "is not it" (see
     * [FacetEvaluator.matchesFacet]). Announcing "no results" beside a filter that matches
     * everything is worse than announcing nothing.
     */
    @Test fun `an unbacked value under != is not reported as an unindexed dead end`() {
        assertFalse(parse("tool:bash").diagnostics.filterIsInstance<Diagnostic.UnindexedFacet>().isEmpty())
        assertTrue(parse("tool:!=bash").diagnostics.filterIsInstance<Diagnostic.UnindexedFacet>().isEmpty())
        // The node itself is unchanged — only the diagnostic is withheld.
        assertEquals(QueryNode.Facet("tool", Op.NE, "bash"), parse("tool:!=bash").root)
    }

    @Test fun `unterminated quote is treated as closed at end of input`() {
        val q = parse("\"build cache")
        assertEquals(QueryNode.Phrase("build cache"), q.root)
        assertTrue(q.diagnostics.any { it is Diagnostic.UnterminatedQuote })
    }

    @Test fun `unterminated regex degrades to a plain term`() {
        val q = parse("/use.*State")
        assertTrue(q.root is QueryNode.Term)
        assertTrue(q.diagnostics.any { it is Diagnostic.UnterminatedRegex })
    }

    @Test fun `invalid date value is flagged but still parses as a facet`() {
        val q = parse("before:not-a-real-date")
        assertEquals(QueryNode.Facet("before", Op.EQ, "not-a-real-date"), q.root)
        assertTrue(q.diagnostics.any { it is Diagnostic.InvalidDate })
    }

    @Test fun `valid relative date produces no InvalidDate diagnostic`() {
        assertTrue(parse("after:-7d").diagnostics.filterIsInstance<Diagnostic.InvalidDate>().isEmpty())
        assertTrue(parse("before:2026-01-01").diagnostics.filterIsInstance<Diagnostic.InvalidDate>().isEmpty())
        assertTrue(parse("during:last-week").diagnostics.filterIsInstance<Diagnostic.InvalidDate>().isEmpty())
    }

    /**
     * `-(\d+)([dwm])` accepts an **unbounded** digit run, and every character of it is one a user
     * can produce by leaning on a key. Feeding that to `toLong()` and to `LocalDate.minusDays`
     * threw `NumberFormatException` / `DateTimeException` straight out of [QueryParser.parse] —
     * from the one function in this module that contracts never to throw. It is a diagnostic now,
     * like every other date that isn't one.
     */
    @Test fun `a relative offset too large to be a date is a diagnostic, not an exception`() {
        for (value in listOf("-99999999999999999999d", "-9223372036854775807d", "-9223372036854775807w")) {
            val q = parse("before:$value")
            assertEquals(QueryNode.Facet("before", Op.EQ, value), q.root)
            assertTrue("expected InvalidDate for '$value'", q.diagnostics.any { it is Diagnostic.InvalidDate })
        }
    }

    // ---- host callbacks are on the parse path, and still may not throw ----

    private fun parseHostile(text: String) =
        QueryParser.parse(text, HostileVocabulary.registry, TestVocabulary.CTX, TestVocabulary.ZONE)

    /**
     * `Model.kt` uses `size:>10mb` as its own worked example of a facet term, and `value.toInt()`
     * is the first thing anyone writes for a numeric [FacetField.isBacked]. Together they were a
     * search box that threw on the way to a value that was going to be fine — a hazard the
     * original could not have, because `isBacked` was module code over a closed enum there.
     */
    @Test fun `a FacetField whose isBacked throws does not throw out of parse`() {
        val q = parseHostile("size:>10mb")
        assertEquals(QueryNode.Facet("size", Op.GT, "10mb"), q.root)
        // Degrades to *no* diagnostic, never to a wrong one: claiming "nothing has that value"
        // about a field whose backing could not be established would be a confident lie, and
        // FacetEvaluator reads a throw the same way, so the two answers cannot diverge.
        assertTrue(q.diagnostics.filterIsInstance<Diagnostic.UnindexedFacet>().isEmpty())
    }

    @Test fun `a FacetField whose valueKind throws does not throw out of parse`() {
        val q = parseHostile("kind:whatever")
        assertEquals(QueryNode.Facet("kind", Op.EQ, "whatever"), q.root)
        // No kind could be read, so no value-shape claim is made either — not InvalidDate, not
        // InvalidNumber. A missing check, never an invented verdict.
        assertTrue(q.diagnostics.isEmpty())
    }

    @Test fun `every facet shape against a throwing registry parses without throwing`() {
        val shapes = listOf(
            "size:1", "size:>10mb", "size:>=1.5s", "size:!=x", "size:1..5", "size:", "size:\"a b\"",
            "kind:a", "kind:1..5", "kind:!=a", "-kind:a",
            "hostile:x", "-hostile:x", "hostile:1..5",
            "size:>10mb kind:a (hostile:x OR size:2)",
        )
        for (text in shapes) {
            val result = try {
                parseHostile(text)
            } catch (t: Throwable) {
                throw AssertionError("parse threw for '$text'", t)
            }
            assertNotNull(result)
            // Compiling must be as total as parsing — a half-typed query reaches the compiler too.
            QueryCompiler.compileFts(result.root)
            result.root?.let(QueryCompiler::renderCanonical)
        }
    }

    // ---- the facet-free entry point is reachable only by name ----

    /**
     * `registry` has no default. There is no runtime assertion for that — the property *is* that an
     * unadapted `QueryParser.parse(text)` no longer compiles — so what this pins is the other half:
     * that the facet-free behaviour still exists, under a name a call site cannot arrive at by
     * forgetting something, and that it really is a different parse.
     */
    @Test fun `parsePlainText degrades every facet to literal text, and says so`() {
        val plain = QueryParser.parsePlainText("model:opus gradle")
        assertEquals(
            QueryNode.And(listOf(QueryNode.Term("model:opus", false), QueryNode.Term("gradle", false))),
            plain.root,
        )
        assertTrue(plain.facets.isEmpty())
        assertEquals(listOf(ChipKind.TERM, ChipKind.TERM), plain.chips.map { it.kind })
        assertEquals("model", plain.diagnostics.filterIsInstance<Diagnostic.UnknownField>().single().typed)

        // The same text against a real registry is a different tree, different diagnostics and a
        // different chip kind — the whole of what a defaulted `registry` used to hide.
        val withFields = parse("model:opus gradle")
        assertEquals(ChipKind.FACET, withFields.chips.first().kind)
        assertEquals(1, withFields.facets.size)
        assertTrue(withFields.diagnostics.filterIsInstance<Diagnostic.UnknownField>().isEmpty())
    }

    /**
     * New in the port. `Model.kt` justifies [FacetField.valueKind] existing at all by saying it
     * "lets the parser reject `turns:yesterday` at parse time"; nothing upstream did that, so it is
     * pinned here. Deliberately loose — `10mb` and `1.5s` pass, because unit suffixes belong to the
     * host's [FacetField.matches] and a parser that rejected `size:>10mb` would be worse than one
     * that checked nothing.
     */
    @Test fun `a non-numeric value on a numeric field is flagged at parse time`() {
        val q = parse("turns:yesterday")
        assertEquals(QueryNode.Facet("turns", Op.EQ, "yesterday"), q.root)
        val diag = q.diagnostics.filterIsInstance<Diagnostic.InvalidNumber>().single()
        assertEquals("turns", diag.key)
        assertEquals("yesterday", diag.raw)
    }

    @Test fun `numeric validation accepts unit suffixes and signs`() {
        for (value in listOf("10", "-3", "1.5", "0.50", "10..50")) {
            assertTrue(
                "expected '$value' to pass numeric validation",
                parse("cost:$value").diagnostics.filterIsInstance<Diagnostic.InvalidNumber>().isEmpty(),
            )
        }
    }

    // ---- the 37-case malformed-input corpus: never throws ----

    @Test fun `malformed input corpus never throws and always returns a result`() {
        val malformed = listOf(
            "", "   ", "\t\n", "(", ")", "((()))", "\"", "\"\"\"", "/", "//", "///",
            "?", "?\"", "AND", "OR", "NOT", "OR OR OR", "-", "--", "---foo",
            "field:", ":value", "mdel:opus", "cost:>", "before:", "gradle OR",
            "OR gradle", "((gradle)", "gradle))", "is:", "has::image", "?????",
            "😀 emoji query", "a".repeat(5000),
            // Three added in the port: `\d+` in RelativeDate's offset pattern is unbounded, so a
            // held-down key produces a well-shaped date expression that is not a number, or is a
            // number that runs off the end of the proleptic calendar.
            "before:-99999999999999999999d", "after:-9223372036854775807w",
            "during:-" + "9".repeat(400) + "m",
        )
        for (input in malformed) {
            val result = try {
                parse(input)
            } catch (t: Throwable) {
                throw AssertionError("parse threw for input: ${input.take(50)}", t)
            }
            assertNotNull(result)
            assertNotNull(result.chips)
            assertNotNull(result.diagnostics)
            // Compiling must be as total as parsing: a half-typed query reaches the compiler too.
            QueryCompiler.compileFts(result.root)
            result.root?.let(QueryCompiler::renderCanonical)
            QueryCompiler.lexicalTerms(result.root)
        }
    }

    // ---- boolean facet composition ----

    @Test fun `facets compose with boolean structure correctly`() {
        val q = parse("is:starred (gradle OR maven) -has:image")
        val root = q.root as QueryNode.And
        assertEquals(3, root.children.size)
        assertTrue(root.children[0] is QueryNode.Facet)
        assertTrue(root.children[1] is QueryNode.Or)
        assertTrue(root.children[2].let { it is QueryNode.Not && it.child is QueryNode.Facet })
        // facets are flattened out of the tree regardless of nesting depth
        assertEquals(2, q.facets.size)
        assertTrue(q.facets.any { it.key == "is" })
        assertTrue(q.facets.any { it.key == "has" })
    }

    @Test fun `deeply nested boolean composition still flattens facets correctly`() {
        val q = parse("(is:starred OR is:archived) AND (project:Aarso OR model:opus) gradle")
        assertEquals(4, q.facets.size)
    }

    // ---- the 47-fixture chip round-trip ----

    @Test fun `chip round-trip is stable across every fixture`() {
        val fixtures = listOf(
            "gradle", "gradle cache", "gradle AND cache", "gradle OR maven", "gradle | maven",
            "-gradle", "NOT gradle", "grad*", "\"build cache\"", "\"exact phrase here\"",
            "/use.*State/", "/use.*State/i", "?gradle", "?\"gradle build\"",
            "is:starred", "is:archived", "is:orphan", "is:unread", "has:image", "has:code",
            "has:attachment", "in:trash", "project:Aarso", "project:\"my project\"", "model:opus",
            "tag:idea", "room:chats", "file:build.gradle.kts", "tool:bash", "build:fail",
            "branch:3", "branch:>3", "turns:10", "turns:<=5", "cost:0.50", "cost:>0.50",
            "cost:0.10..1.00", "before:2026-01-01", "after:-7d", "during:last-week", "lang:hi",
            "loop:abc123", "is:starred gradle", "-has:image gradle", "is:starred (gradle OR maven)",
            "is:starred (gradle OR maven) -has:image", "(a OR b) (c OR d)",
        )
        assertTrue("need at least 40 fixtures, have ${fixtures.size}", fixtures.size >= 40)

        for (fixture in fixtures) {
            val first = parse(fixture)
            val reconstructed = first.chips.toQueryText()
            val second = parse(reconstructed)
            assertEquals(
                "round-trip unstable for fixture '$fixture' (reconstructed as '$reconstructed')",
                first.chips,
                second.chips,
            )
        }
    }

    /**
     * The chip fixtures above all start from typed text, which can never *produce* a negated
     * non-leaf other than `-(…)` — so the one shape that lost its round trip could not be reached
     * from that side. This starts from the tree instead, which is the side a host builds from when
     * it composes a saved search or inverts a chip programmatically.
     *
     * `-a b` reparses as "not a, AND b" — already handled. `--a` is worse and was not: `-` is a
     * word character mid-run, so the tokenizer reads one word `--a`, the parser strips one dash,
     * and `Not(Not(Term("a")))` comes back as `Not(Term("-a"))` — a search for the literal text
     * `-a`, silently.
     */
    @Test fun `a negated non-leaf is parenthesised so the tree round-trips`() {
        val a = QueryNode.Term("a", false)
        val b = QueryNode.Term("b", false)
        val fixtures = listOf(
            QueryNode.Not(QueryNode.Not(a)),
            QueryNode.Not(QueryNode.Not(QueryNode.Not(a))),
            QueryNode.Not(QueryNode.And(listOf(a, b))),
            QueryNode.Not(QueryNode.Or(listOf(a, b))),
            QueryNode.Not(QueryNode.Not(QueryNode.Or(listOf(a, b)))),
            QueryNode.Not(QueryNode.Not(QueryNode.And(listOf(a, b)))),
            QueryNode.Not(QueryNode.Not(QueryNode.Facet("is", Op.EQ, "starred"))),
            QueryNode.Not(QueryNode.Not(QueryNode.Phrase("build cache"))),
        )
        for (node in fixtures) {
            val rendered = QueryCompiler.renderCanonical(node)
            assertEquals("round trip lost, rendered as '$rendered'", node, parse(rendered).root)
        }

        // The exact shape that was wrong, spelled out so the regression stays legible.
        assertEquals("-(-a)", QueryCompiler.renderCanonical(QueryNode.Not(QueryNode.Not(a))))
        assertEquals(QueryNode.Not(QueryNode.Term("-a", false)), parse("--a").root)

        // An Or renders its own parentheses, so it is not double-wrapped: a chip a user reads
        // should not gain a layer of noise for a property it already had.
        assertEquals("-(a OR b)", QueryCompiler.renderCanonical(QueryNode.Not(QueryNode.Or(listOf(a, b)))))
    }

    @Test fun `chips are removable and independent`() {
        val q = parse("is:starred gradle has:image")
        assertEquals(3, q.chips.size)
        val withoutMiddle = q.chips.filterIndexed { i, _ -> i != 1 }
        assertEquals("is:starred has:image", withoutMiddle.toQueryText())
    }

    @Test fun `a facet chip carries its key so a host can style it`() {
        val chip = parse("ext:pdf is:starred").chips.last()
        assertEquals(ChipKind.FACET, chip.kind)
        assertEquals("is", chip.key)
        assertFalse(chip.negated)

        val negated = parse("-has:image").chips.single()
        assertEquals(ChipKind.FACET, negated.kind)
        assertEquals("has", negated.key)
        assertTrue(negated.negated)
    }

    // ---- fts compilation shape ----

    @Test fun `facets never appear in the fts expression`() {
        val expression = fts("is:starred gradle has:image cache")!!
        assertFalse(expression.contains("starred"))
        assertFalse(expression.contains("image"))
        assertTrue(expression.contains("gradle"))
        assertTrue(expression.contains("cache"))
    }

    @Test fun `pure facet query has no fts expression`() {
        assertNull(fts("is:starred"))
    }

    @Test fun `query syntax characters inside a quoted phrase are escaped for fts`() {
        val expression = fts("\"say \"\"hello\"\"\"")
        // Whatever the phrase parses to, compiling it must not produce an unescaped bare quote.
        assertNotNull(expression)
        assertTrue("unbalanced quoting in '$expression'", expression!!.count { it == '"' } % 2 == 0)
    }

    @Test fun `a negation inside an AND compiles to FTS5 binary NOT`() {
        assertEquals("\"gradle\" NOT (\"maven\")", fts("gradle -maven"))
    }

    @Test fun `a top-level negation is dropped rather than emitting invalid FTS5`() {
        // FTS5 has no standalone negation form, so `-maven` alone cannot be expressed. Dropping it
        // is not losing it: evaluate QueryNode.Not against the result set.
        assertNull(fts("-maven"))
    }
}
