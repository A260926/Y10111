package lineage

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

object TestSupport {
    const val PARAMS = """
        { "sample_rate": 0.10, "feature_set": "resnet-base", "metric": "top1",
          "api_key": "sk-test-secret", "batch_tag": "tag-001" }
    """
    const val PARAMS_EQUIV = """
        {   "batch_tag" : "tag-001",
            "metric":"top1","feature_set":"resnet-base",
            "sample_rate" : 1e-1,
            "api_key": "sk-test-secret" }
    """
    const val INPUT = """
        { "rows": [ {"id": "a"}, {"id": "b"} ], "dataset": "unit-test" }
    """

    fun newHarness(dir: Path, workMillis: Long = 20L): Harness {
        val db = File(dir.toFile(), "lineage-test.db").absolutePath
        val store = Store(db)
        val engine = Engine(store, workMillis = workMillis)
        val lineage = Lineage(store)
        val exportImport = ExportImport(store)
        val template = engine.createTemplate(
            CreateTemplateRequest(
                id = "tpl_test",
                name = "测试管线",
                stages = listOf(
                    StageDef("extract", 1, listOf("sample_rate"), envSensitive = false),
                    StageDef("transform", 2, listOf("sample_rate", "feature_set"), envSensitive = false),
                    StageDef("evaluate", 3, listOf("feature_set", "metric"), envSensitive = true),
                ),
                secrets = listOf("api_key"),
            )
        )
        return Harness(store, engine, lineage, exportImport, template, db)
    }

    fun freezeParent(h: Harness, env: Map<String, String> = mapOf("cluster.id" to "cluster-a")): RunRow {
        return h.engine.freeze(
            FreezeRequest(
                templateId = h.template.id,
                name = "parent",
                params = PARAMS,
                input = INPUT,
                env = env,
            )
        ).first
    }

    fun advanceAll(h: Harness, runId: String, n: Int = 3) {
        repeat(n) { h.engine.advance(runId) }
    }
}

data class Harness(
    val store: Store,
    val engine: Engine,
    val lineage: Lineage,
    val exportImport: ExportImport,
    val template: Template,
    val dbPath: String,
)
