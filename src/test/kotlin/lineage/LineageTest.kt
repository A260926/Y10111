package lineage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LineageTest {

    @TempDir
    lateinit var tempDir: Path

    private fun harness(dbName: String = "test.db"): Harness {
        val db = Database(tempDir.resolve(dbName))
        db.connect()
        val repo = Repository(db)
        val service = LineageService(db, repo)
        val manifests = ManifestService(db, repo, service)
        return Harness(db, repo, service, manifests, LineageQuery(repo))
    }

    class Harness(
        val db: Database,
        val repo: Repository,
        val service: LineageService,
        val manifests: ManifestService,
        val query: LineageQuery,
    )

    private fun twoStageTemplate(secret: Boolean = false) = TemplateSpec(
        name = "两阶段模板",
        paramFields = listOf(
            ParamFieldSpec("rate", "double"),
            ParamFieldSpec("label", "string"),
            ParamFieldSpec("note", "string", required = false),
            ParamFieldSpec("token", "string", secret = secret),
        ),
        stages = listOf(
            StageSpec("prepare", "prepare-1.0.0", declaresParams = listOf("rate", "token"), declaresEnv = emptyList()),
            StageSpec("finish", "finish-1.0.0", declaresParams = listOf("label", "note"), declaresEnv = listOf("BLAS_VERSION")),
        ),
    )

    private fun params(
        rate: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonPrimitive(0.00100),
        label: String = "  hello   world  ",
        token: String = "tok",
    ) = buildJsonObject {
        put("rate", rate)
        put("label", label)
        put("token", token)
    }

    private fun env(blas: String = "openblas-0.3.21") = buildJsonObject {
        put("BLAS_VERSION", blas)
        put("OS", "linux")
    }

    private val input = buildJsonObject {
        put("dataset", "ds-1")
        put("rows", 100)
    }

    // ---------- 1. 参数规范化 ----------

    @Test
    fun `map 键顺序、无关空白和浮点文本等价不产生不同身份`() {
        val h = harness("a.db")
        val tpl = h.service.createTemplate(twoStageTemplate())

        val a = h.service.freezeRun(
            RunParams(tpl.id, "A", params(rate = kotlinx.serialization.json.JsonPrimitive(0.001)), env(), input),
        )
        val b = h.service.freezeRun(
            RunParams(
                tpl.id,
                "B",
                buildJsonObject {
                    put("token", "tok")
                    put("label", "hello world")
                    put("rate", kotlinx.serialization.json.JsonPrimitive("0.0010"))
                },
                buildJsonObject {
                    put("OS", "linux")
                    put("BLAS_VERSION", "openblas-0.3.21")
                },
                input,
            ),
        )
        assertEquals(a.paramsHash, b.paramsHash)
        assertEquals(a.envFingerprint, b.envFingerprint)
        assertEquals(a.id, b.id)
        assertContains(a.paramsCanonical, """"label":"hello world"""")
        assertContains(a.paramsCanonical, """"rate":0.001""")
    }

    @Test
    fun `NaN Infinity 与缺字段被拒绝，可选缺字段显式编码为 null`() {
        val h = harness("b.db")
        val tpl = h.service.createTemplate(twoStageTemplate())
        assertFailsWith<LineageException> {
            h.service.freezeRun(
                RunParams(
                    tpl.id,
                    "x",
                    buildJsonObject {
                        put("rate", kotlinx.serialization.json.JsonPrimitive(Double.NaN))
                        put("label", "l")
                        put("token", "t")
                    },
                    env(),
                    input,
                ),
            )
        }
        assertFailsWith<LineageException> {
            h.service.freezeRun(
                RunParams(tpl.id, "x", buildJsonObject { put("label", "l") }, env(), input),
            )
        }
        assertFailsWith<LineageException> {
            rejectNonFiniteLiterals("""{"rate": NaN}""")
        }
        val run = h.service.freezeRun(
            RunParams(
                tpl.id,
                "x",
                buildJsonObject {
                    put("rate", 1.0)
                    put("label", "l")
                    put("token", "t")
                },
                env(),
                input,
            ),
        )
        assertContains(run.paramsCanonical, """"note":null""")
    }

    // ---------- 2. 环境变化阻止复用并给出原因 ----------

    @Test
    fun `环境指纹变化阻止声明该环境的阶段复用`() {
        val h = harness("c.db")
        val tpl = h.service.createTemplate(twoStageTemplate())
        val parent = h.service.freezeRun(RunParams(tpl.id, "父", params(), env("v1"), input))
        h.service.advance(parent.id, AdvanceParams())
        h.service.advance(parent.id, AdvanceParams())
        assertEquals("succeeded", h.repo.findRun(parent.id)!!.status)

        val child = h.service.derive(DeriveParams(parent.id, "子", env = env("v2")))
        var childRun = h.repo.findRun(child.id)!!
        val stages = childRun.stages
        assertTrue(stages[0].reuse.reused, "第一阶段不依赖变化的环境，应复用")
        assertFalse(stages[1].reuse.reused)
        assertContains(stages[1].reuse.reason ?: "", "环境指纹变化")
        assertEquals(1, childRun.cursor)
        h.service.advance(child.id, AdvanceParams())
        val report = h.service.verify(child.id)
        assertTrue(report.reproducible)
        childRun = h.repo.findRun(child.id)!!
        val parentNow = h.repo.findRun(parent.id)!!
        assertNotEquals(childRun.stages[1].contentHash, parentNow.stages[1].contentHash)
    }

    // ---------- 3. intent 后恢复不重复产物 ----------

    @Test
    fun `intent 写入后崩溃，恢复后不重复 intent 且产物只写一次`() {
        val h = harness("d.db")
        val tpl = h.service.createTemplate(twoStageTemplate())
        val run = h.service.freezeRun(RunParams(tpl.id, "崩溃运行", params(), env(), input))

        val afterCrash = h.service.advance(run.id, AdvanceParams(crashAfterIntent = true))
        assertEquals(0, afterCrash.cursor)
        val intentAfterCrash = afterCrash.stages[0].intentJson
        assertNotNullValue(intentAfterCrash)
        assertNullValue(afterCrash.stages[0].artifactId)

        // 恢复：再次推进不应重新生成 intent，最终成功写产物并推进游标。
        val recovered = h.service.advance(run.id, AdvanceParams())
        assertEquals(1, recovered.cursor)
        assertEquals(intentAfterCrash, recovered.stages[0].intentJson)
        assertNotNullValue(recovered.stages[0].artifactId)

        val artifactIdsBefore = h.repo.findRun(run.id)!!.stages.mapNotNull { it.artifactId }.toSet()
        h.service.advance(run.id, AdvanceParams(crashAfterIntent = true))
        val again = h.service.advance(run.id, AdvanceParams())
        assertEquals("succeeded", again.status)
        // 恢复路径不重复产物：第二阶段只新增一个此前不存在的检查点产物
        val artifactIdsAfter = h.repo.findRun(run.id)!!.stages.mapNotNull { it.artifactId }.toSet()
        assertEquals(artifactIdsBefore + setOf(again.stages[1].artifactId), artifactIdsAfter)
        assertEquals(2, artifactIdsAfter.size)
    }

    private fun assertNotNullValue(value: Any?) {
        assertTrue(value != null)
    }

    private fun assertNullValue(value: Any?) {
        assertTrue(value == null)
    }

    // ---------- 4. 失败检查点不能作为成功缓存 ----------

    @Test
    fun `失败检查点不会被派生运行复用`() {
        val h = harness("e.db")
        val tpl = h.service.createTemplate(twoStageTemplate())
        val run = h.service.freezeRun(RunParams(tpl.id, "失败运行", params(), env(), input))
        h.service.advance(run.id, AdvanceParams())
        h.service.injectFailure(run.id, InjectFailureParams("boom"))
        val failed = h.repo.findRun(run.id)!!
        assertEquals("failed", failed.status)
        assertEquals("failed", failed.stages[1].status)

        val child = h.service.derive(DeriveParams(run.id, "子"))
        val childRun = h.repo.findRun(child.id)!!
        assertTrue(childRun.stages[0].reuse.reused)
        assertFalse(childRun.stages[1].reuse.reused)
        assertContains(childRun.stages[1].reuse.reason ?: "", "失败")
    }

    // ---------- 5. 派生复用 + 血缘回溯 ----------

    @Test
    fun `相同输入派生复用全部成功检查点，参数变化则首段起重跑`() {
        val h = harness("f.db")
        val tpl = h.service.createTemplate(twoStageTemplate())
        val parent = h.service.freezeRun(RunParams(tpl.id, "父", params(), env(), input))
        h.service.advance(parent.id, AdvanceParams())
        h.service.advance(parent.id, AdvanceParams())

        val identical = h.service.derive(DeriveParams(parent.id, "相同子"))
        assertEquals("succeeded", h.repo.findRun(identical.id)!!.status)
        assertTrue(h.repo.findRun(identical.id)!!.stages.all { it.reuse.reused })

        val changed = h.service.derive(
            DeriveParams(parent.id, "改参数", changedParams = buildJsonObject { put("rate", 0.02) }),
        )
        val changedRun = h.repo.findRun(changed.id)!!
        assertFalse(changedRun.stages[0].reuse.reused)
        assertFalse(changedRun.stages[1].reuse.reused)
        assertContains(changedRun.stages[0].reuse.reason ?: "", "参数不同")

        val trace = h.query.trace(changed.id)
        assertEquals(listOf(changed.id, parent.id), trace.chain.map { it.runId })
        assertContains(Canonical.render(trace.chain[0].changedParams), "0.001")
    }

    // ---------- 6. 并发推进版本冲突 ----------

    @Test
    fun `两个页面同时推进只有一个成功，另一个收到版本冲突`() = runBlocking {
        val h = harness("g.db")
        val tpl = h.service.createTemplate(twoStageTemplate())
        val run = h.service.freezeRun(RunParams(tpl.id, "并发", params(), env(), input))

        val results = (1..2).map {
            async(Dispatchers.IO) {
                runCatching { h.service.advance(run.id, AdvanceParams(expectedVersion = 1)) }
            }
        }.awaitAll()

        val successes = results.count { it.isSuccess && it.getOrNull()!!.cursor == 1 }
        val conflicts = results.count { it.exceptionOrNull() is ConflictException }
        assertEquals(1, successes)
        assertEquals(1, conflicts)
        val stored = h.repo.findRun(run.id)!!
        assertEquals(2, stored.version)
        assertEquals(1, stored.cursor)
    }

    @Test
    fun `相同幂等键与内容返回原运行`() {
        val h = harness("h.db")
        val tpl = h.service.createTemplate(twoStageTemplate())
        val request = RunParams(tpl.id, "幂等", params(), env(), input, idempotencyKey = "key-1")
        val first = h.service.freezeRun(request)
        val second = h.service.freezeRun(request.copy(displayName = "不同名字"))
        assertEquals(first.id, second.id)
        assertEquals(1, h.repo.listRuns().size)
        assertFailsWith<ConflictException> {
            h.service.freezeRun(
                request.copy(
                    params = buildJsonObject {
                        put("rate", 0.02)
                        put("label", "hello world")
                        put("token", "tok")
                    },
                    env = buildJsonObject {
                        put("BLAS_VERSION", "openblas-9.9.9")
                        put("OS", "linux")
                    },
                ),
            )
        }
    }

    // ---------- 7. 导出重放 ----------

    @Test
    fun `manifest 导出重放并验证谱系，且不包含 secret 值`() {
        val h = harness("i.db")
        val h2 = harness("j.db")
        val tpl = h.service.createTemplate(twoStageTemplate(secret = true))
        val parent = h.service.freezeRun(
            RunParams(tpl.id, "父", params(token = "super-secret-value"), env(), input),
        )
        h.service.advance(parent.id, AdvanceParams())
        h.service.advance(parent.id, AdvanceParams())
        val child = h.service.derive(DeriveParams(parent.id, "子", env = env("v2")))
        h.service.advance(child.id, AdvanceParams())

        val manifest = h.manifests.export()
        val text = encodeJson(manifest)
        assertFalse(text.contains("super-secret-value"))

        val report = h2.manifests.reimport(decodeJson<Manifest>(text))
        assertEquals(1, report.importedTemplates)
        assertEquals(2, report.importedRuns)
        report.verification.forEach { runReport ->
            if (!runReport.checks.all { it.ok }) {
                throw AssertionError("${runReport.runId}: " + runReport.checks.filter { !it.ok }.joinToString { it.detail })
            }
        }

        // 再次导入幂等跳过
        val second = h2.manifests.reimport(decodeJson<Manifest>(text))
        assertEquals(0, second.importedRuns)
        assertTrue(second.skipped.any { it.contains(child.id) })
    }

    @Test
    fun `预置演示父子运行复用前两阶段并因环境变化重跑第三阶段`() {
        val h = harness("demo.db")
        Seed.seedIfEmpty(h.db, h.repo, h.service)
        val child = h.repo.findRun(Seed.CHILD_RUN_ID)!!
        val parent = h.repo.findRun(Seed.PARENT_RUN_ID)!!
        assertEquals("succeeded", parent.status)
        assertEquals("succeeded", child.status)
        assertTrue(child.stages[0].reuse.reused)
        assertTrue(child.stages[1].reuse.reused)
        assertFalse(child.stages[2].reuse.reused)
        assertContains(child.stages[2].reuse.reason ?: "", "环境指纹变化")
        assertNotEquals(parent.stages[2].contentHash, child.stages[2].contentHash)
        assertTrue(h.service.verify(child.id).reproducible)
    }
}
