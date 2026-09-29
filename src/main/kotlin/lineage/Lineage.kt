package lineage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

import java.util.UUID

/** 派生、复用判定与重现性验证。 */
class Lineage(private val store: Store) {
    private val repo = Repo(store)

    /**
     * 从父运行派生新运行。仅复用满足全部条件的检查点：
     *  - 输入数据摘要一致；
     *  - 阶段名与阶段版本一致；
     *  - 阶段消费的参数（作用域参数摘要）一致；
     *  - 上游产物摘要一致（逐阶段链条）；
     *  - 环境敏感阶段的环境指纹一致（不一致阻止复用并给出原因）。
     * FAILED / INTENT 检查点永远不会作为成功缓存复用。
     */
    fun derive(req: DeriveRequest): Pair<RunRow, Boolean> {
        return store.tx { conn ->
            val parent = repo.getRun(conn, req.parentRunId)
                ?: throw NotFoundException("父运行不存在: ${req.parentRunId}")
            val template = repo.getTemplate(conn, parent.templateId)
                ?: throw LineageException("父运行模板缺失: ${parent.templateId}")

            val mergedParams = mergeParams(parent.paramsCanonical, req.params)
            val (mergedInput, inputDigest) = if (req.input != null) {
                val el = Canonical.parse(req.input)
                Canonical.canonicalText(el) to Canonical.sha256(Canonical.canonicalText(el))
            } else {
                parent.inputText to parent.inputDigest
            }
            val parentEnv = Canonical.parse(parent.env) as JsonObj
            val envOverrides = LinkedHashMap<String, String>()
            parentEnv.entries.forEach { (k, v) -> envOverrides[k] = (v as JsonStr).value }
            req.env.forEach { (k, v) -> envOverrides[k] = v }
            val (envText, envFp) = Simulator.envFingerprint(envOverrides)

            val changedKeys = diffTopLevelKeys(parent.paramsCanonical, mergedParams) +
                (if (envFp != parent.envFingerprint) listOf("__env__") else emptyList()) +
                (if (inputDigest != parent.inputDigest) listOf("__input__") else emptyList())

            req.idempotencyKey?.let { key ->
                repo.findByIdempotencyKey(conn, key)?.let { existing ->
                    return@tx existing to true
                }
            }

            val child = RunRow(
                id = "run_" + UUID.randomUUID().toString().take(12),
                templateId = parent.templateId,
                templateVersion = parent.templateVersion,
                name = req.name.ifBlank { parent.name + " (派生)" },
                cursor = 0,
                cursorVersion = 1,
                paramsCanonical = mergedParams,
                paramsDigest = Canonical.sha256(mergedParams),
                inputText = mergedInput,
                inputDigest = inputDigest,
                env = envText,
                envFingerprint = envFp,
                parentRunId = parent.id,
                changedKeys = JsonCodecs.json.encodeToString(ListSerializer(String.serializer()), changedKeys.distinct()),
                idempotencyKey = req.idempotencyKey,
                status = "frozen",
                createdAt = repo.now(),
            )
            repo.insertRun(conn, child)

            val parentStages = repo.listStages(conn, parent.id)
            val now = repo.now()
            var expectedUpstream: String? = null
            var reusedCount = 0

            template.stages.forEachIndexed { idx, stageDef ->
                val pStage = parentStages.getOrNull(idx)
                val pending = StageRow(
                    id = 0, runId = child.id, stageIndex = idx, stageName = stageDef.name,
                    stageVersion = stageDef.stageVersion, status = StageStatus.PENDING, attempt = 0,
                    intentId = null, inputDigest = null, paramsDigest = null, envFingerprint = null,
                    upstreamDigest = null, artifactDigest = null, artifactText = null, receiptJson = null,
                    reason = null, source = "pending", createdAt = now, updatedAt = now,
                )

                if (pStage == null || pStage.status != StageStatus.SUCCESS) {
                    val why = if (pStage == null) "父运行无此检查点"
                    else "父检查点状态为 ${pStage.status.name}，失败/意图检查点不能作为成功缓存"
                    repo.insertStage(conn, pending.copy(reason = why))
                    return@forEachIndexed
                }

                val reasons = mutableListOf<String>()
                if (pStage.stageName != stageDef.name || pStage.stageVersion != stageDef.stageVersion)
                    reasons.add("阶段版本不一致（父=${pStage.stageName}@v${pStage.stageVersion}）")
                if (pStage.inputDigest != child.inputDigest)
                    reasons.add("输入数据摘要不一致（父=${Canonical.short(pStage.inputDigest)} 子=${Canonical.short(child.inputDigest)}）")
                val childScope = scopeDigest(child.paramsCanonical, stageDef.consumes)
                if (pStage.paramsDigest != childScope)
                    reasons.add("本阶段消费参数摘要不一致（keys=${stageDef.consumes}）")
                if (pStage.upstreamDigest != expectedUpstream)
                    reasons.add("上游产物摘要链不一致")
                if (stageDef.envSensitive && pStage.envFingerprint != child.envFingerprint)
                    reasons.add("环境指纹变化阻止复用（父=${Canonical.short(pStage.envFingerprint)} 子=${Canonical.short(child.envFingerprint)}）")

                if (reasons.isEmpty()) {
                    val reused = pStage.copy(
                        id = 0, runId = child.id,
                        status = StageStatus.SUCCESS, attempt = pStage.attempt,
                        source = "reused:${parent.id}",
                        reason = "复用自父运行 ${parent.id} 阶段 $idx",
                        createdAt = now, updatedAt = now,
                    )
                    repo.insertStage(conn, reused)
                    expectedUpstream = pStage.artifactDigest
                    reusedCount++
                } else {
                    repo.insertStage(conn, pending.copy(reason = "阻止复用：" + reasons.joinToString("；")))
                }
            }

            repo.updateCursor(conn, child.id, reusedCount, runStatus(conn, child.id, template.stages.size))
            repo.addEvent(conn, child.id, null, "run_derived",
                "parent=${parent.id} reused=$reusedCount/${template.stages.size} changed=${child.changedKeys} env=${if (envFp == parent.envFingerprint) "same" else "changed"}")
            child.copy(cursor = reusedCount) to false
        }
    }

    private fun runStatus(conn: java.sql.Connection, runId: String, stageCount: Int): String {
        val stages = repo.listStages(conn, runId)
        return when {
            stages.count { it.status == StageStatus.SUCCESS } >= stageCount -> "completed"
            else -> "in_progress"
        }
    }

    private fun mergeParams(parentCanonical: String, overrideRaw: String?): String {
        val parent = Canonical.parse(parentCanonical) as JsonObj
        if (overrideRaw == null) return Canonical.render(parent)
        val override = try {
            Canonical.parse(overrideRaw)
        } catch (e: Canonical.CanonicalException) {
            throw LineageException("派生参数规范化失败：${e.message}")
        }
        if (override !is JsonObj) throw LineageException("派生参数必须是 JSON 对象")
        val merged = LinkedHashMap<String, JsonValue>()
        merged.putAll(parent.entries)
        merged.putAll(override.entries)
        return Canonical.render(JsonObj(merged))
    }

    private fun diffTopLevelKeys(parentCanonical: String, childCanonical: String): List<String> {
        val parent = Canonical.parse(parentCanonical) as JsonObj
        val child = Canonical.parse(childCanonical) as JsonObj
        val keys = parent.keys + child.keys
        return keys.filter { k ->
            val a = parent[k]; val b = child[k]
            a == null || b == null || Canonical.render(a) != Canonical.render(b)
        }.sorted()
    }

    private fun scopeDigest(paramsCanonical: String, consumes: List<String>): String {
        val params = Canonical.parse(paramsCanonical) as JsonObj
        val scoped = JsonObj(consumes.sorted().mapNotNull { key ->
            params[key]?.let { key to it }
        }.toMap())
        return Canonical.sha256(Canonical.render(scoped))
    }

    // ---------- 重现性验证：从固定输入重算确定性产物并比对 ----------

    fun verify(runId: String): VerifyResult {
        return store.tx(immediate = false) { conn ->
            val run = repo.getRun(conn, runId) ?: throw NotFoundException("运行不存在: $runId")
            val template = repo.getTemplate(conn, run.templateId)
                ?: throw LineageException("模板缺失: ${run.templateId}")
            val stages = repo.listStages(conn, runId)
            val checks = mutableListOf<String>()
            var ok = true
            var prevDigest: String? = null
            var prevReceiptHash: String? = null
            // 重算输入用：上一阶段检查点收据自身的 hash（独立于结构校验过程）
            var prevRowReceiptHashForRecompute: String? = null
            var finalArtifact: String? = null

            template.stages.forEachIndexed { idx, def ->
                val row = stages.getOrNull(idx)
                if (row == null || row.status != StageStatus.SUCCESS) {
                    checks.add("阶段 $idx（${def.name}）: 未成功完成，当前 ${row?.status?.name ?: "缺失"}")
                    ok = false
                    return@forEachIndexed
                }
                val problems = mutableListOf<String>()
                if (row.stageVersion != def.stageVersion || row.stageName != def.name)
                    problems.add("阶段定义不匹配")
                if (row.inputDigest != run.inputDigest) problems.add("输入摘要不匹配")
                if (row.upstreamDigest != prevDigest) problems.add("receipt 链断裂（上游产物摘要不匹配）")
                if (row.receiptJson == null) {
                    problems.add("缺少 receipt")
                } else {
                    val receipt = Canonical.parse(row.receiptJson) as JsonObj
                    if ((receipt["prev_receipt_hash"] as? JsonStr)?.value.orEmpty() != (prevReceiptHash ?: ""))
                        problems.add("receipt 哈希链断裂")
                    val artifactDigest = (receipt["artifact_digest"] as? JsonStr)?.value
                    if (artifactDigest != row.artifactDigest) problems.add("receipt 记录的产物摘要不一致")
                    prevReceiptHash = (receipt["receipt_hash"] as? JsonStr)?.value
                }

                if (row.source.startsWith("reused:")) {
                    checks.add("阶段 $idx（${def.name}）: 复用检查点，源=${row.source}；" +
                        "结构校验${if (problems.isEmpty()) "通过" else "发现问题: $problems"}")
                    if (problems.isNotEmpty()) ok = false
                } else {
                    // 与推进时完全一致的作用域参数摘要（阶段只读取它 consumes 的键）
                    val scopeDigest = scopeDigest(run.paramsCanonical, def.consumes)
                    val inputs = StageInputs(
                        runId = run.id, stageName = def.name, stageVersion = def.stageVersion,
                        inputElement = Canonical.parse(run.inputText), inputDigest = run.inputDigest,
                        paramsElement = Canonical.parse(run.paramsCanonical), paramsDigest = scopeDigest,
                        consumes = def.consumes.toSet(), envFingerprint = run.envFingerprint,
                        envSensitive = def.envSensitive,
                        upstreamDigest = prevDigest,
                        upstreamReceiptHash = prevRowReceiptHashForRecompute,
                    )
                    val expected = Simulator.material(idx, inputs)
                    if (Canonical.sha256(expected.artifactText) != row.artifactDigest) {
                        problems.add("从相同输入重算的确定性产物与检查点不一致")
                    }
                    if (expected.receiptJson != row.receiptJson) {
                        problems.add("从相同输入重算的 receipt 与检查点不一致")
                    }
                    checks.add("阶段 $idx（${def.name}）: 确定性重算${if (problems.isEmpty()) "一致 ✓" else "不一致: $problems"}")
                    if (problems.isNotEmpty()) ok = false
                }
                prevDigest = row.artifactDigest
                prevRowReceiptHashForRecompute = row.receiptJson?.let {
                    ((Canonical.parse(it) as JsonObj)["receipt_hash"] as? JsonStr)?.value
                }
                finalArtifact = row.artifactText
            }
            checks.add(0, "参数规范化摘要 ${Canonical.short(run.paramsDigest)}；输入摘要 ${Canonical.short(run.inputDigest)}；环境指纹 ${Canonical.short(run.envFingerprint)}")
            val verdict = if (ok && stages.count { it.status == StageStatus.SUCCESS } == template.stages.size)
                "结果可以从相同输入重现" else "结果当前无法从相同输入重现"
            VerifyResult(ok && stages.count { it.status == StageStatus.SUCCESS } == template.stages.size, verdict, finalArtifact, checks)
        }
    }

    fun lineageChain(runId: String, maxDepth: Int = 32): List<RunRow> {
        return store.tx(immediate = false) { conn ->
            val chain = mutableListOf<RunRow>()
            var current = repo.getRun(conn, runId)
            var depth = 0
            while (current != null && depth < maxDepth) {
                chain.add(current)
                current = current.parentRunId?.let { repo.getRun(conn, it) }
                depth++
            }
            chain
        }
    }
}

data class VerifyResult(
    val reproducible: Boolean,
    val verdict: String,
    val finalArtifact: String?,
    val checks: List<String>,
)
