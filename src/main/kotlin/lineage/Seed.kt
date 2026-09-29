package lineage

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object Seed {

    const val TEMPLATE_ID = "tpl-demo-standard"
    const val PARENT_RUN_ID = "run-demo-parent"
    const val CHILD_RUN_ID = "run-demo-child-env"

    fun templateSpec(): TemplateSpec = TemplateSpec(
        name = "标准三阶段实验",
        description = "ingest → transform → aggregate；第三阶段声明依赖 BLAS 环境",
        paramFields = listOf(
            ParamFieldSpec("dataset", "string"),
            ParamFieldSpec("learningRate", "double"),
            ParamFieldSpec("epochs", "int"),
            ParamFieldSpec("dropout", "double", required = false),
            ParamFieldSpec("token", "string", secret = true),
        ),
        stages = listOf(
            StageSpec(
                name = "ingest",
                version = "ingest-1.2.0",
                declaresParams = listOf("dataset"),
                declaresEnv = emptyList(),
            ),
            StageSpec(
                name = "transform",
                version = "transform-2.0.1",
                declaresParams = listOf("learningRate", "dropout", "token"),
                declaresEnv = emptyList(),
            ),
            StageSpec(
                name = "aggregate",
                version = "aggregate-0.9.4",
                declaresParams = listOf("epochs"),
                declaresEnv = listOf("BLAS_VERSION"),
            ),
        ),
    )

    fun parentEnv() = buildJsonObject {
        put("OS", "linux-6.2")
        put("BLAS_VERSION", "openblas-0.3.21")
        put("CUDA", "12.4")
    }

    fun childEnv() = buildJsonObject {
        put("OS", "linux-6.2")
        put("BLAS_VERSION", "openblas-0.3.27")
        put("CUDA", "12.4")
    }

    fun inputSummary() = buildJsonObject {
        put("dataset", "cifar-demo-v3")
        put("rows", 60000)
        put("sha256", "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
        put("columns", buildJsonArray {
            add("image")
            add("label")
        })
    }

    fun parentParams() = buildJsonObject {
        put("dataset", "  cifar-demo-v3  ")
        put("learningRate", 0.00100)
        put("epochs", 12)
        put("token", "s3cret-demo-token")
    }

    fun seedIfEmpty(db: Database, repo: Repository, service: LineageService) {
        if (repo.getMeta("seeded") == "1") return
        db.tx { ensureTemplate(repo) }
        ensureRuns(repo, service)
        db.tx { repo.setMeta("seeded", "1") }
    }

    private fun ensureTemplate(repo: Repository) {
        if (repo.findTemplate(TEMPLATE_ID) != null) return
        val spec = templateSpec()
        val specText = Canonical.render(
            linkedMapOf(
                "name" to spec.name,
                "description" to spec.description,
                "paramFields" to spec.paramFields.map {
                    linkedMapOf("name" to it.name, "type" to it.type, "required" to it.required, "secret" to it.secret)
                },
                "stages" to spec.stages.map {
                    linkedMapOf(
                        "name" to it.name,
                        "version" to it.version,
                        "declaresParams" to it.declaresParams,
                        "declaresEnv" to it.declaresEnv,
                    )
                },
            ),
        )
        repo.insertTemplate(
            Template(
                id = TEMPLATE_ID,
                name = spec.name,
                description = spec.description,
                paramFields = spec.paramFields,
                stages = spec.stages,
                version = 1,
                specHash = Canonical.sha256(specText),
                createdAt = "2026-09-30T00:00:00Z",
            ),
        )
    }

    private fun ensureRuns(repo: Repository, service: LineageService) {
        if (repo.findRun(PARENT_RUN_ID) == null) {
            val created = service.freezeRun(
                RunParams(
                    templateId = TEMPLATE_ID,
                    displayName = "预置父运行（基线）",
                    params = parentParams(),
                    env = parentEnv(),
                    inputSummary = inputSummary(),
                ),
            )
            renameRun(repo, created.id, PARENT_RUN_ID, "2026-09-30T00:05:00Z")
        }
        repeat(3) { service.advance(PARENT_RUN_ID, AdvanceParams()) }

        if (repo.findRun(CHILD_RUN_ID) == null) {
            val derived = service.derive(
                DeriveParams(
                    parentRunId = PARENT_RUN_ID,
                    displayName = "预置子运行（BLAS 升级）",
                    env = childEnv(),
                ),
            )
            renameRun(repo, derived.id, CHILD_RUN_ID, "2026-09-30T00:10:00Z")
        }
        val child = repo.findRun(CHILD_RUN_ID)!!
        repeat(child.stages.size - child.cursor) {
            service.advance(CHILD_RUN_ID, AdvanceParams())
        }
    }

    /** 将哈希运行 ID 替换为稳定演示 ID（仅用于预置数据，空库执行）。 */
    private fun renameRun(repo: Repository, oldId: String, newId: String, createdAt: String) {
        if (oldId == newId) return
        val connection = repo.connection
        connection.prepareStatement("UPDATE runs SET id = ?, created_at = ? WHERE id = ?").use { ps ->
            ps.setString(1, newId)
            ps.setString(2, createdAt)
            ps.setString(3, oldId)
            ps.executeUpdate()
        }
        connection.prepareStatement("UPDATE stages SET run_id = ? WHERE run_id = ?").use { ps ->
            ps.setString(1, newId)
            ps.setString(2, oldId)
            ps.executeUpdate()
        }
    }
}
