package lineage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ExportReplayTest {

    @TempDir
    lateinit var dir: Path

    private fun populatedPair(): Pair<Harness, RunRow> {
        val h = TestSupport.newHarness(dir, workMillis = 5L)
        val parent = TestSupport.freezeParent(h)
        TestSupport.advanceAll(h, parent.id)
        h.lineage.derive(
            DeriveRequest(
                parent.id, "child",
                """{"batch_tag":"tag-b"}""",
                mapOf("cluster.id" to "cluster-b"),
            )
        )
        return h to parent
    }

    @Test
    fun `导出不包含 secret 值，但包含脱敏声明`() {
        val (h, _) = populatedPair()
        val manifest = h.exportImport.export()
        assertTrue(manifest.contains("<redacted-secret>"))
        assertFalse(manifest.contains("sk-test-secret"), "secret 值不得出现在 manifest 中")
        assertTrue(manifest.contains("redactedSecretKeys"))
        assertTrue(manifest.contains("api_key"))
    }

    @Test
    fun `导出可在空库重新导入并验证谱系`() {
        val (h, parent) = populatedPair()
        val manifest = h.exportImport.export()

        val db2 = File(dir.toFile(), "replay.db").absolutePath
        val store2 = Store(db2)
        val ei2 = ExportImport(store2)
        val result = ei2.importManifest(manifest)
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.verifiable)
        assertEquals(1, result.importedTemplates)
        assertEquals(2, result.importedRuns)

        // 子运行的父指针在新库中仍然可追溯
        val lineage2 = Lineage(store2)
        val runs = store2.tx(immediate = false) { conn -> Repo(store2).listRuns(conn) }
        val child = runs.first { it.parentRunId != null }
        val chain = lineage2.lineageChain(child.id)
        assertEquals(2, chain.size)
        assertEquals(child.id, chain[0].id)
        assertEquals(parent.id, chain[1].id)

        // 父运行（根）全部阶段可从相同输入重算验证
        val verify = lineage2.verify(parent.id)
        assertTrue(verify.reproducible, verify.checks.joinToString("\n"))
    }

    @Test
    fun `重复导入幂等（追加式，不覆盖）`() {
        val (h, _) = populatedPair()
        val manifest = h.exportImport.export()
        val first = h.exportImport.importManifest(manifest)
        assertEquals(0, first.importedRuns)
        assertTrue(first.errors.isEmpty())
    }

    @Test
    fun `篡改产物摘要后导入验证失败`() {
        val (h, _) = populatedPair()
        val manifest = h.exportImport.export()
        // 找到一个产物短摘要并整体替换（破坏 artifact_digest 与实际产物的对应）
        // 篡改一个确定性产物字段（record_count），使其与 artifact_digest 不再相符
        // artifactText 在 manifest 中被 JSON 转义为 \"record_count\":2
        val regex = Regex("""(\\?"record_count\\?"\s*:\s*)2(?![0-9])""")
        val match = regex.find(manifest)
        org.junit.jupiter.api.Assertions.assertNotNull(match, "测试数据中应存在 record_count")
        val broken = manifest.replaceRange(match!!.range, match.groupValues[1] + "999")
        val result = h.exportImport.importManifest(broken)
        assertFalse(result.verifiable)
        assertTrue(result.errors.any { it.contains("产物与摘要不符") || it.contains("链") })
    }

    @Test
    fun `垃圾 JSON 导入被明确拒绝`() {
        val h = TestSupport.newHarness(dir)
        assertThrows(LineageException::class.java) {
            h.exportImport.importManifest("{ not json ")
        }
    }
}
