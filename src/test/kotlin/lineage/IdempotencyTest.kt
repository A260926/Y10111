package lineage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class IdempotencyTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `相同幂等键与内容返回原运行`() {
        val h = TestSupport.newHarness(dir)
        val req = FreezeRequest(
            h.template.id, "idem", TestSupport.PARAMS, TestSupport.INPUT,
            mapOf("cluster.id" to "cluster-a"), idempotencyKey = "key-123",
        )
        val (r1, reused1) = h.engine.freeze(req)
        val (r2, reused2) = h.engine.freeze(req.copy(name = "different-name"))
        assertEquals(r1.id, r2.id)
        assertEquals(false, reused1)
        assertEquals(true, reused2)
        assertEquals("idem", r2.name, "返回的是原运行（名称也是原名称）")
    }

    @Test
    fun `相同幂等键不同内容冲突`() {
        val h = TestSupport.newHarness(dir)
        h.engine.freeze(
            FreezeRequest(h.template.id, "a", TestSupport.PARAMS, TestSupport.INPUT,
                idempotencyKey = "dup-key")
        )
        assertThrows(ConflictException::class.java) {
            h.engine.freeze(
                FreezeRequest(h.template.id, "b", TestSupport.PARAMS.replace("top1", "top5"),
                    TestSupport.INPUT, idempotencyKey = "dup-key")
            )
        }
    }

    @Test
    fun `派生运行相同幂等键返回原运行`() {
        val h = TestSupport.newHarness(dir, workMillis = 5L)
        val parent = TestSupport.freezeParent(h)
        TestSupport.advanceAll(h, parent.id)
        val req = DeriveRequest(parent.id, "child", """{"batch_tag":"z"}""",
            mapOf("cluster.id" to "cluster-b"), idempotencyKey = "derive-key")
        val (c1, r1) = h.lineage.derive(req)
        val (c2, r2) = h.lineage.derive(req)
        assertEquals(c1.id, c2.id)
        assertEquals(false, r1)
        assertEquals(true, r2)
    }
}
