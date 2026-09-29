package lineage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DeriveReuseTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `环境变化阻止复用 evaluate，前两阶段复用，第三阶段重跑`() {
        val h = TestSupport.newHarness(dir, workMillis = 5L)
        val parent = TestSupport.freezeParent(h)
        TestSupport.advanceAll(h, parent.id)

        val beforeParentArtifacts = h.store.tx(immediate = false) { conn ->
            Repo(h.store).listStages(conn, parent.id).map { it.artifactDigest }
        }

        val child = h.lineage.derive(
            DeriveRequest(
                parentRunId = parent.id,
                name = "child-env-changed",
                // 仅改动一个不被任何阶段消费的参数
                params = """{"batch_tag":"tag-cluster-b"}""",
                env = mapOf("cluster.id" to "cluster-b"),
            )
        ).first

        val repo = Repo(h.store)
        val stages = h.store.tx(immediate = false) { conn -> repo.listStages(conn, child.id) }
        assertEquals(parent.id, child.parentRunId)
        assertEquals(2, child.cursor, "前两阶段复用，游标停在 2")

        assertEquals(StageStatus.SUCCESS, stages[0].status)
        assertEquals("reused:${parent.id}", stages[0].source)
        assertEquals(StageStatus.SUCCESS, stages[1].status)
        assertEquals("reused:${parent.id}", stages[1].source)
        assertEquals(StageStatus.PENDING, stages[2].status)
        assertNotNull(stages[2].reason)
        assertTrue(stages[2].reason!!.contains("环境指纹变化阻止复用"), stages[2].reason)

        // 复用的产物摘要与父运行一致
        assertEquals(beforeParentArtifacts[0], stages[0].artifactDigest)
        assertEquals(beforeParentArtifacts[1], stages[1].artifactDigest)
        assertNull(stages[2].artifactDigest)

        // 推进第三阶段：在新环境重跑，产物必然与父运行不同
        h.engine.advance(child.id)
        val rerun = h.store.tx(immediate = false) { conn -> repo.getStage(conn, child.id, 2)!! }
        assertEquals(StageStatus.SUCCESS, rerun.status)
        assertEquals("computed", rerun.source)
        assertEquals(beforeParentArtifacts[2] != rerun.artifactDigest, true)

        // 改动参数记录在案
        assertTrue(
            h.store.tx(immediate = false) { conn -> repo.getRun(conn, child.id)!! }.changedKeys.contains("batch_tag")
        )
    }

    @Test
    fun `消费参数变化在 transform 阶段就阻止复用`() {
        val h = TestSupport.newHarness(dir, workMillis = 5L)
        val parent = TestSupport.freezeParent(h)
        TestSupport.advanceAll(h, parent.id)

        val child = h.lineage.derive(
            DeriveRequest(parent.id, "child-param", """{"sample_rate":0.25}""")
        ).first
        val stages = h.store.tx(immediate = false) { conn -> Repo(h.store).listStages(conn, child.id) }
        // extract 消费 sample_rate -> 阻止；因此从阶段 0 起全部待跑
        assertEquals(StageStatus.PENDING, stages[0].status)
        assertTrue(stages[0].reason!!.contains("消费参数摘要不一致"))
        assertEquals(0, child.cursor)
    }

    @Test
    fun `输入变化阻止全部复用`() {
        val h = TestSupport.newHarness(dir, workMillis = 5L)
        val parent = TestSupport.freezeParent(h)
        TestSupport.advanceAll(h, parent.id)

        val child = h.lineage.derive(
            DeriveRequest(
                parent.id, "child-input", null, emptyMap(),
                input = """{"rows":[{"id":"different"}],"dataset":"unit-test"}""",
            )
        ).first
        val stages = h.store.tx(immediate = false) { conn -> Repo(h.store).listStages(conn, child.id) }
        stages.forEach { s ->
            assertEquals(StageStatus.PENDING, s.status)
            assertTrue(s.reason!!.contains("输入数据摘要不一致"))
        }
    }
}
