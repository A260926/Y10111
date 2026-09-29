package lineage

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalTest {

    @Test
    fun `map keys sorted recursively regardless of insertion order`() {
        val a = Canonical.render(mapOf("b" to 1, "a" to mapOf("z" to 1, "y" to 2)))
        val b = Canonical.render(mapOf("a" to mapOf("y" to 2, "z" to 1), "b" to 1))
        assertEquals(a, b)
        assertEquals("""{"a":{"y":2,"z":1},"b":1}""", a)
    }

    @Test
    fun `whitespace trimmed and collapsed`() {
        assertEquals("a b c", Canonical.normalizeWhitespace("  a\n\t b   c "))
    }

    @Test
    fun `floating point text equivalents canonicalize identically`() {
        assertEquals("0.001", Canonical.doubleToText(0.001))
        assertEquals("0.001", Canonical.doubleToText(0.00100))
        assertEquals("10", Canonical.doubleToText(1e1))
        assertEquals("0", Canonical.doubleToText(-0.0))
    }

    @Test
    fun `nan and infinity rejected`() {
        assertFailsWith<LineageException> { Canonical.doubleToText(Double.NaN) }
        assertFailsWith<LineageException> { Canonical.doubleToText(Double.POSITIVE_INFINITY) }
        assertFailsWith<LineageException> { Canonical.doubleToText(Double.NEGATIVE_INFINITY) }
        assertFailsWith<LineageException> { rejectNonFiniteLiterals("""{"x":Infinity}""") }
        assertFailsWith<LineageException> { rejectNonFiniteLiterals("1.0 NaN") }
    }

    @Test
    fun `sha256 stable and hex`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Canonical.sha256(""),
        )
    }
}
