package lineage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class EnvAndNormalizationTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `等价参数文本冻结出同一摘要`() {
        val h = TestSupport.newHarness(dir)
        val r1 = h.engine.freeze(
            FreezeRequest(h.template.id, "r1", TestSupport.PARAMS, TestSupport.INPUT)
        ).first
        val r2 = h.engine.freeze(
            FreezeRequest(h.template.id, "r2", TestSupport.PARAMS_EQUIV, TestSupport.INPUT)
        ).first
        assertEquals(r1.paramsDigest, r2.paramsDigest)
        assertEquals(r1.inputDigest, r2.inputDigest)
        assertEquals(r1.envFingerprint, r2.envFingerprint)
    }

    @Test
    fun `不同环境声明产生不同指纹`() {
        val h = TestSupport.newHarness(dir)
        val a = h.engine.freeze(
            FreezeRequest(h.template.id, "a", TestSupport.PARAMS, TestSupport.INPUT, mapOf("cluster.id" to "cluster-a"))
        ).first
        val b = h.engine.freeze(
            FreezeRequest(h.template.id, "b", TestSupport.PARAMS, TestSupport.INPUT, mapOf("cluster.id" to "cluster-b"))
        ).first
        assertNotEquals(a.envFingerprint, b.envFingerprint)
    }

    @Test
    fun `缺少 secret 字段被拒绝`() {
        val h = TestSupport.newHarness(dir)
        val ex = assertThrows(LineageException::class.java) {
            h.engine.freeze(
                FreezeRequest(
                    h.template.id, "no-secret",
                    """{"sample_rate":0.1,"feature_set":"x","metric":"top1"}""",
                    TestSupport.INPUT,
                )
            )
        }
        assert(ex.message!!.contains("api_key"))
    }

    @Test
    fun `NaN 参数被拒绝`() {
        val h = TestSupport.newHarness(dir)
        assertThrows(LineageException::class.java) {
            h.engine.freeze(
                FreezeRequest(
                    h.template.id, "nan",
                    """{"sample_rate":NaN,"api_key":"k"}""",
                    TestSupport.INPUT,
                )
            )
        }
    }

    @Test
    fun `secret 键被阶段消费的模板拒绝创建`() {
        val h = TestSupport.newHarness(dir)
        assertThrows(LineageException::class.java) {
            h.engine.createTemplate(
                CreateTemplateRequest(
                    id = "tpl_bad", name = "bad",
                    stages = listOf(StageDef("leaky", 1, listOf("api_key"))),
                    secrets = listOf("api_key"),
                )
            )
        }
    }
}
