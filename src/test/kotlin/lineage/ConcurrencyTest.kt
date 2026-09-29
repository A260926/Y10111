package lineage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ConcurrencyTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `两个页面同时推进同一阶段，恰好一个成功且另一个版本冲突`() = runBlocking {
        // workMillis 足够大，保证两个请求在物化窗口内重叠
        val h = TestSupport.newHarness(dir, workMillis = 500L)
        val run = TestSupport.freezeParent(h)

        val results = (1..2).map {
            async(Dispatchers.IO) {
                runCatching { h.engine.advance(run.id) }
            }
        }.awaitAll()

        val successes = results.count { it.isSuccess }
        val conflicts = results.count { it.exceptionOrNull() is ConflictException }
        assertEquals(1, successes, "恰好一个推进成功: $results")
        assertEquals(1, conflicts, "另一个必须是版本冲突: ${results.map { it.exceptionOrNull()?.message }}")
        val ex = results.firstNotNullOf { it.exceptionOrNull() }
        assertTrue(ex.message!!.contains("版本冲突"))

        val stage = h.store.tx(immediate = false) { conn -> Repo(h.store).getStage(conn, run.id, 0)!! }
        assertEquals(StageStatus.SUCCESS, stage.status)
        assertEquals(1, stage.attempt)
        assertNotNull(stage.artifactDigest)
    }

    @Test
    fun `带过期 cursorVersion 的推进被拒绝（乐观版本冲突）`() {
        val h = TestSupport.newHarness(dir, workMillis = 5L)
        val run = TestSupport.freezeParent(h)
        h.engine.advance(run.id) // cursorVersion 已从 1 变为 2
        val ex = runCatching { h.engine.advance(run.id, expectedCursorVersion = 1L) }.exceptionOrNull()
        assertTrue(ex is ConflictException)
        assertTrue(ex!!.message!!.contains("cursorVersion"))
    }
}
