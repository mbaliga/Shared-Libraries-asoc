package dev.aarso.modelbench.json

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonValueTest {

    @Test
    fun `renders primitives`() {
        assertEquals("\"hi\"", jStr("hi").render())
        assertEquals("null", jStrOrNull(null).render())
        assertEquals("42", jInt(42).render())
        assertEquals("null", jIntOrNull(null).render())
        assertEquals("7", jLongOrNull(7L).render())
        assertEquals("1.5", jDouble(1.5).render())
    }

    @Test
    fun `escapes control characters and quotes`() {
        assertEquals("\"a\\\"b\\\\c\\nd\\te\"", jStr("a\"b\\c\nd\te").render())
        assertEquals("\"\\u0001\"", jStr("\u0001").render())
    }

    @Test
    fun `objects preserve declared key order`() {
        val json = jObj("b" to jInt(2), "a" to jInt(1)).render()
        assertEquals("{\"b\":2,\"a\":1}", json)
    }

    @Test
    fun `arrays render items in order, comma separated`() {
        val json = jArr(listOf(jInt(1), jInt(2), jInt(3))).render()
        assertEquals("[1,2,3]", json)
    }

    @Test
    fun `nested object-array round trips structurally`() {
        val json = jObj(
            "runs" to jArr(listOf(jObj("n" to jInt(1)), jObj("n" to jInt(2)))),
        ).render()
        assertEquals("{\"runs\":[{\"n\":1},{\"n\":2}]}", json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-finite double is rejected rather than emitted as invalid JSON`() {
        jDouble(Double.NaN)
    }
}
