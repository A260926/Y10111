package lineage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RecoveryAndFailureTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `intent 后模拟崩溃，恢复后不重复产物且结果一致`() {
        val h = TestSupport.newHarness(dir, workMillis = 30L)
        val run = TestSupport.freezeParent(h)

        // 在阶段 0 的 intent 落盘后、物化前注入“进程崩溃”
        h.engine.attachInjection(run.id, 0, "test crash", crash = true)
        val crash = assertThrows(SimulatedCrashException::class.java) {
            h.engine.advance(run.id)
        }
        assertTrue(crash.message!!.contains("intent 已持久化"))

        // 模拟进程重启：所有对象都是无状态的，重新打开同一数据库文件即可
        val store2 = Store(h.dbPath)
        val engine2 = Engine(store2, workMillis = 30L)
        val repo = Repo(store2)
        val stuck = store2.tx(immediate = false) { conn -> repo.getStage(conn, run.id, 0) }!!
        assertEquals(StageStatus.INTENT, stuck.status)
        assertNull(stuck.artifactDigest, "崩溃点不应有产物")
        val intentBefore = stuck.intentId

        // 恢复推进：同一 intentId，物化恰好一次
        Simulator.resetCounters()
        engine2.advance(run.id)
        assertEquals(1L, Simulator.materializationCount(run.id), "恢复后物化必须恰好一次")

        val done = store2.tx(immediate = false) { conn ->
            val s = repo.getStage(conn, run.id, 0)!!
            val r = repo.getRun(conn, run.id)!!
            s to r
        }
        assertEquals(StageStatus.SUCCESS, done.first.status)
        assertEquals(intentBefore, done.first.intentId, "intent 身份在恢复前后保持稳定")
        assertEquals(1, done.second.cursor)
        assertEquals(1, done.first.attempt, "崩溃恢复（而非失败重试）保持 attempt=1")

        // 再推进一次阶段 0（游标已越过）不应重复物化
        // 完整跑完并验证确定性产物
        TestSupport.advanceAll(h, run.id, 2)
        val result = h.lineage.verify(run.id)
        assertTrue(result.reproducible, result.checks.joinToString("\n"))
    }

    @Test
    fun `失败检查点不会被当成成功缓存，重试后成功`() {
        val h = TestSupport.newHarness(dir, workMillis = 10L)
        val run = TestSupport.freezeParent(h)
        h.engine.advance(run.id) // 阶段 0 先成功

        h.engine.attachInjection(run.id, 1, "boom", crash = false)
        val (failedRun, failedStage) = h.engine.advance(run.id)
        assertEquals(StageStatus.FAILED, failedStage.status)
        assertEquals("failed", failedRun.status)
        assertNull(failedStage.artifactDigest)
        assertEquals(1, failedRun.cursor, "游标停在失败阶段，未越过")

        // 派生自该运行：FAILED 检查点不能作为成功缓存
        val child = h.lineage.derive(
            DeriveRequest(run.id, "child", """{"batch_tag":"other"}""")
        ).first
        val repo = Repo(h.store)
        val childStages = h.store.tx(immediate = false) { conn -> repo.listStages(conn, child.id) }
        assertEquals(StageStatus.PENDING, childStages[1].status)
        assertTrue(childStages[1].reason!!.contains("失败/意图检查点不能作为成功缓存"))

        // 重试原运行：attempt+1，随后成功，产物与“从未失败”的确定性结果一致
        val (_, retried) = h.engine.advance(run.id)
        assertEquals(StageStatus.SUCCESS, retried.status)
        assertEquals(2, retried.attempt)
        assertEquals(retried.intentId, failedStage.intentId)

        TestSupport.advanceAll(h, run.id, 1)
        val result = h.lineage.verify(run.id)
        assertTrue(result.reproducible)
    }
}
