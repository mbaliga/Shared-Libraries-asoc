package dev.aarso.feedback

// NOTE ON WHERE THIS FILE LIVES: the `:feedback` module exists on `main` but not yet on the
// `fonebrew-development-clzu43` line (the two lines have diverged and have not been merged).
// This test is therefore INERT on that branch - there is no `feedback/build.gradle.kts`, so Gradle
// never compiles it - and becomes live, with no further edit, when the lines merge. It was
// verified against `origin/main`'s `FeedbackDraft` in a scratch copy of that tree (PORTING_PLAN.md
// S0.2). Sitting in `feedback/src/test` keeps it additive: it collides with nothing on `main`.

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-file guard for `specs/feedback-draft.md`: the pure draft render and subject, as vectors
 * in `specs/fixtures/feedback-draft.v1.json` for any non-Kotlin implementation to reuse.
 *
 * The fixture is contract: it pins the no-hidden-data promise of the share payload. Rewrite it
 * only through the opt-in `SPEC_FIXTURES_REGENERATE=1` (with `--rerun`); that run still FAILS so
 * the result is reviewed with `git diff specs/fixtures` before the suite goes green again.
 */
class SpecFixturesTest {

    private val name = "feedback-draft.v1.json"

    private fun Map<String, Any?>.s(key: String): String = this[key] as String

    private fun draftOf(input: Map<String, Any?>) = FeedbackDraft(
        appName = input.s("app_name"),
        appVersion = input.s("app_version"),
        feature = input.s("feature"),
        message = input.s("message"),
        facts = SpecJson.arr(input["facts"]).map {
            val f = SpecJson.obj(it)
            FeedbackFact(f.s("label"), f.s("value"))
        },
    )

    @Test
    fun `render and subject match the fixture`() {
        val (file, root) = SpecJson.load(name)
        val cases = SpecJson.arr(SpecJson.obj(root)["render"]).map { SpecJson.obj(it) }
        if (SpecJson.regenerating()) {
            cases.forEach {
                val d = draftOf(SpecJson.obj(it["input"]))
                it["expect"] = linkedMapOf("render" to d.render(), "subject" to d.subject())
            }
            SpecJson.saveRegeneratedAndFail(file, root)
        }
        assertTrue("render: fixture has ${cases.size} cases, expected at least 5", cases.size >= 5)
        for (c in cases) {
            val d = draftOf(SpecJson.obj(c["input"]))
            val expect = SpecJson.obj(c["expect"])
            assertEquals("${c.s("name")}: render", expect["render"], d.render())
            assertEquals("${c.s("name")}: subject", expect["subject"], d.subject())
            // Determinism is part of the spec: same draft, same text.
            assertEquals("${c.s("name")}: deterministic", d.render(), draftOf(SpecJson.obj(c["input"])).render())
        }
    }

    @Test
    fun `refused drafts are refused`() {
        val (_, root) = SpecJson.load(name)
        val cases = SpecJson.arr(SpecJson.obj(root)["refused"]).map { SpecJson.obj(it) }
        assertTrue("refused: fixture has ${cases.size} cases, expected at least 3", cases.size >= 3)
        for (c in cases) {
            try {
                draftOf(SpecJson.obj(c["input"]))
            } catch (expected: IllegalArgumentException) {
                continue
            }
            throw AssertionError("${c.s("name")}: expected the draft to be refused")
        }
    }

    @Test
    fun `every rendered line comes from a supplied field or a fixed label`() {
        // The no-hidden-data promise in executable form, on the fixture's own inputs: strip every
        // supplied fragment and only the fixed labels and list punctuation may remain.
        val (_, root) = SpecJson.load(name)
        val allowed = setOf("—", "feedback", "on:", "Included", "details:", "-", ":")
        for (c in SpecJson.arr(SpecJson.obj(root)["render"]).map { SpecJson.obj(it) }) {
            val input = SpecJson.obj(c["input"])
            val d = draftOf(input)
            var residue = d.render()
            val fragments = mutableListOf(d.appName, d.appVersion, d.feature, d.message.trim())
            d.facts.forEach { fragments += it.label; fragments += it.value }
            for (f in fragments.filter { it.isNotEmpty() }.sortedByDescending { it.length }) residue = residue.replace(f, "")
            residue.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { token ->
                assertTrue("${c.s("name")}: unexpected content in render: '$token'", token in allowed)
            }
        }
    }

    @Test
    fun `the fixture identifies itself`() {
        val (_, root) = SpecJson.load(name)
        val m = SpecJson.obj(root)
        assertEquals("feedback-draft", m["spec"])
        assertEquals(1.0, m["version"] as Double, 0.0)
    }
}
