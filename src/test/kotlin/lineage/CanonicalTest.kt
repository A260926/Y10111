package lineage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class CanonicalTest {

    @Test
    fun `键顺序不影响身份`() {
        val a = Canonical.digest("""{"b":1,"a":2}""")
        val b = Canonical.digest("""{"a":2,"b":1}""")
        assertEquals(a, b)
    }

    @Test
    fun `无关空白不影响身份`() {
        val a = Canonical.digest("{\n  \"x\": [1, 2],\n  \"y\": \"z\"\n}")
        val b = Canonical.digest("""{"x":[1,2],"y":"z"}""")
        assertEquals(a, b)
    }

    @Test
    fun `浮点文本等价收敛为同一身份`() {
        val base = Canonical.digest("""{"rate":1}""")
        listOf("1.0", "1.00", "1e0", "1.0E0").forEach { text ->
            assertEquals(base, Canonical.digest("""{"rate":$text}"""), "应与 1 等价: $text")
        }
        assertEquals(Canonical.digest("""{"x":0.0}"""), Canonical.digest("""{"x":-0}"""))
        // 不相等的值仍然不同
        assertNotEquals(base, Canonical.digest("""{"rate":1.5}"""))
    }

    @Test
    fun `NaN 与 Infinity 被拒绝`() {
        listOf("NaN", "-NaN", "Infinity", "-Infinity").forEach { token ->
            assertThrows(Canonical.CanonicalException::class.java) {
                Canonical.parse("""{"x":$token}""")
            }
        }
    }

    @Test
    fun `重复键被拒绝`() {
        assertThrows(Canonical.CanonicalException::class.java) {
            Canonical.parse("""{"a":1,"a":2}""")
        }
    }

    @Test
    fun `数组顺序有意义`() {
        assertNotEquals(Canonical.digest("[1,2]"), Canonical.digest("[2,1]"))
    }

    @Test
    fun `显式编码的字符串形式 NaN 合法`() {
        // 显式编码为字符串是允许的逃生舱
        assertDoesNotThrow { Canonical.parse("""{"x":"NaN"}""") }
    }
}
