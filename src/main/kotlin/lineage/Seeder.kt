package lineage

/**
 * 预置演示谱系：
 *  - 模板「图像分类管线 v1」三阶段：extract / transform（环境不敏感）、evaluate（环境敏感）；
 *  - 父运行在 cluster-a 上完整跑完三阶段；
 *  - 子运行改了一个不被任何阶段消费的参数 batch_tag，并把 cluster.id 变为 cluster-b：
 *    前两阶段复用父检查点，第三阶段因环境指纹变化被阻止复用并重跑。
 */
object Seeder {
    const val TEMPLATE_ID = "tpl_image_pipeline"
    const val PARENT_ID = "run_parent_demo"
    const val CHILD_ID = "run_child_demo"

    fun seed(engine: Engine, lineage: Lineage, store: Store) {
        val repo = Repo(store)
        val already = store.tx(immediate = false) { conn -> repo.getMeta(conn, "seeded") != null }
        if (already) return

        val template = engine.createTemplate(
            CreateTemplateRequest(
                id = TEMPLATE_ID,
                name = "图像分类管线",
                stages = listOf(
                    StageDef("extract", 1, listOf("sample_rate"), envSensitive = false),
                    StageDef("transform", 2, listOf("sample_rate", "feature_set"), envSensitive = false),
                    StageDef("evaluate", 3, listOf("feature_set", "metric"), envSensitive = true),
                ),
                secrets = listOf("api_key"),
            )
        )

        val params = """
            { "sample_rate": 0.10, "feature_set": "resnet-base", "metric": "top1",
              "api_key": "sk-demo-secret-value", "batch_tag": "nightly-001" }
        """.trimIndent()
        val input = """
            { "rows": [ {"id": "img-0001"}, {"id": "img-0002"}, {"id": "img-0003"} ],
              "dataset": "demo-images", "split": "validation" }
        """.trimIndent()

        val (parent, reusedParent) = engine.freeze(
            FreezeRequest(
                templateId = template.id,
                name = "基线运行（cluster-a）",
                params = params,
                input = input,
                env = mapOf("cluster.id" to "cluster-a"),
                idempotencyKey = "seed-parent",
            )
        )
        renameRun(store, parent.id, PARENT_ID)

        repeat(3) { engine.advance(PARENT_ID) }

        val (child, reusedChild) = lineage.derive(
            DeriveRequest(
                parentRunId = PARENT_ID,
                name = "派生运行（cluster-b，仅环境变化）",
                params = """{ "batch_tag": "nightly-002-cluster-b" }""",
                env = mapOf("cluster.id" to "cluster-b"),
                idempotencyKey = "seed-child",
            )
        )
        renameRun(store, child.id, CHILD_ID)

        // 子运行游标停在第 3 阶段（前两阶段复用），推进一次即重跑环境敏感的 evaluate。
        engine.advance(CHILD_ID)

        store.tx { conn -> repo.putMeta(conn, "seeded", "1") }
    }

    private fun renameRun(store: Store, oldId: String, newId: String) {
        if (oldId == newId) return
        store.tx { conn ->
            conn.createStatement().executeUpdate("UPDATE runs SET id='$newId' WHERE id='$oldId'")
            conn.createStatement().executeUpdate("UPDATE stages SET run_id='$newId' WHERE run_id='$oldId'")
            conn.createStatement().executeUpdate("UPDATE events SET run_id='$newId' WHERE run_id='$oldId'")
        }
    }
}
