package lineage

import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 导出/导入 manifest。
 *
 * 安全规则：被模板标记为 secret 的顶层参数键，其值永不导出——
 * manifest 中对应键值替换为固定标记 "<redacted-secret>"，并在 redactedSecretKeys 中声明。
 * 导入时用脱敏参数重新计算参数摘要，与随 manifest 携带的 paramsDigest 比对：
 * secret 键被显式排除在阶段消费集合之外（模板创建时强制），因此谱系结构仍可验证；
 * 脱敏导致的摘要差异会被明确标注，而不是冒充完全可重算。
 */
class ExportImport(private val store: Store) {
    private val repo = Repo(store)
    private val json = Json { prettyPrint = true }
    private val stringListSer = ListSerializer(String.serializer())
    private val redaction = JsonStr("<redacted-secret>")

    fun export(): String {
        return store.tx(immediate = false) { conn ->
            val templates = repo.listTemplates(conn)
            val runs = repo.listRuns(conn).map { run ->
                val template = templates.first { it.id == run.templateId }
                val redactedParams = redactParams(run.paramsCanonical, template.secrets)
                val env = (Canonical.parse(run.env) as JsonObj).entries.mapValues { (it.value as JsonStr).value }
                ManifestRun(
                    run = toRunResponse(run, false),
                    params = redactedParams,
                    input = run.inputText,
                    env = env,
                    redactedSecretKeys = template.secrets,
                    stages = repo.listStages(conn, run.id).map { toStageResponse(it) },
                )
            }
            val manifest = Manifest(
                exportedAt = repo.now(),
                templates = templates,
                runs = runs,
            )
            json.encodeToString(Manifest.serializer(), manifest)
        }
    }

    private fun redactParams(paramsCanonical: String, secrets: List<String>): String {
        val params = Canonical.parse(paramsCanonical) as JsonObj
        if (secrets.none { params.containsKey(it) }) return paramsCanonical
        val mutable = params.entries.toMutableMap()
        secrets.forEach { if (mutable.containsKey(it)) mutable[it] = redaction }
        return Canonical.render(JsonObj(mutable.toSortedMap()))
    }

    fun importManifest(raw: String): ImportResult {
        val manifest = try {
            Json { ignoreUnknownKeys = true }.decodeFromString(Manifest.serializer(), raw)
        } catch (e: Throwable) {
            throw LineageException("manifest 解析失败：${e.message}")
        }

        val checks = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var importedTemplates = 0
        var importedRuns = 0

        store.tx { conn ->
            manifest.templates.forEach { t ->
                val existing = repo.getTemplate(conn, t.id)
                if (existing == null) {
                    repo.putTemplate(conn, t)
                    importedTemplates++
                } else {
                    if (existing.templateVersion != t.templateVersion) {
                        errors.add("模板 ${t.id} 版本冲突：现有 v${existing.templateVersion}，manifest v${t.templateVersion}")
                    }
                }
            }

            // 父运行先于子运行导入（拓扑顺序：根运行在前）
            val byId = manifest.runs.associateBy { it.run.runId }
            val ordered = mutableListOf<ManifestRun>()
            val visiting = mutableSetOf<String>()
            fun visit(mr: ManifestRun) {
                if (mr.run.runId in visiting) throw LineageException("manifest 中存在谱系环")
                if (ordered.any { it.run.runId == mr.run.runId }) return
                visiting.add(mr.run.runId)
                mr.run.parentRunId?.let { pid -> byId[pid]?.let { visit(it) } }
                visiting.remove(mr.run.runId)
                ordered.add(mr)
            }
            manifest.runs.forEach { visit(it) }
            ordered.forEach { mr ->
                val template = manifest.templates.firstOrNull { it.id == mr.run.templateId }
                    ?: repo.getTemplate(conn, mr.run.templateId)
                if (template == null) {
                    errors.add("运行 ${mr.run.runId} 缺少模板 ${mr.run.templateId}，跳过")
                    return@forEach
                }

                val recomputedParamsDigest = Canonical.sha256(Canonical.render(Canonical.parse(mr.params)))
                if (recomputedParamsDigest != mr.run.paramsDigest) {
                    if (mr.redactedSecretKeys.isEmpty()) {
                        errors.add("运行 ${mr.run.runId} 参数摘要不匹配（无 secret 声明，疑似被篡改）")
                    } else {
                        checks.add("运行 ${mr.run.runId}：参数摘要因 ${mr.redactedSecretKeys.size} 个 secret 脱敏不可逐字节重算（符合导出策略，secret 从不被任何阶段消费）")
                    }
                }
                val inputDigest = Canonical.sha256(Canonical.render(Canonical.parse(mr.input)))
                if (inputDigest != mr.run.inputDigest) errors.add("运行 ${mr.run.runId} 输入摘要不匹配")
                val (_, envFp) = Simulator.envFingerprint(mr.env)
                if (envFp != mr.run.envFingerprint) errors.add("运行 ${mr.run.envFingerprint} 环境指纹不匹配")

                if (repo.getRun(conn, mr.run.runId) == null) {
                    val run = RunRow(
                        id = mr.run.runId,
                        templateId = mr.run.templateId,
                        templateVersion = mr.run.templateVersion,
                        name = mr.run.name,
                        cursor = mr.run.cursor,
                        cursorVersion = mr.run.cursorVersion,
                        paramsCanonical = mr.params,
                        paramsDigest = mr.run.paramsDigest,
                        inputText = mr.input,
                        inputDigest = mr.run.inputDigest,
                        env = Canonical.render(
                            JsonObj(mr.env.toSortedMap().mapValues { JsonStr(it.value) })
                        ),
                        envFingerprint = mr.run.envFingerprint,
                        parentRunId = mr.run.parentRunId,
                        changedKeys = json.encodeToString(stringListSer, mr.run.changedKeys),
                        idempotencyKey = null,
                        status = mr.run.status,
                        createdAt = mr.run.createdAt,
                    )
                    if (run.parentRunId != null && repo.getRun(conn, run.parentRunId) == null) {
                        errors.add("运行 ${run.id} 的父运行 ${run.parentRunId} 不在 manifest 中且库内不存在")
                    }
                    repo.insertRun(conn, run)
                    mr.stages.forEach { sr ->
                        repo.insertStage(conn, stageFromResponse(run.id, sr))
                    }
                    importedRuns++
                } else {
                    checks.add("运行 ${mr.run.runId} 已存在，未覆盖（manifest 导入是追加式的）")
                }

                // 谱系内部一致性：receipt 链、产物摘要链
                var prevArtifact: String? = null
                var prevReceiptHash: String? = null
                mr.stages.sortedBy { it.stageIndex }.forEach { sr ->
                    val localProblems = mutableListOf<String>()
                    if (sr.status == "SUCCESS") {
                        val artifactOk = sr.artifactText != null &&
                            Canonical.sha256(Canonical.canonicalText(sr.artifactText)) == sr.artifactDigest
                        if (!artifactOk) localProblems.add("产物与摘要不符")
                        sr.receipt?.let { rcptRaw ->
                            val rcpt = Canonical.parse(rcptRaw) as JsonObj
                            if ((rcpt["prev_artifact_digest"] as? JsonStr)?.value.orEmpty() != (prevArtifact ?: ""))
                                localProblems.add("产物链断裂 @阶段${sr.stageIndex}")
                            if ((rcpt["prev_receipt_hash"] as? JsonStr)?.value.orEmpty() != (prevReceiptHash ?: ""))
                                localProblems.add("receipt 链断裂 @阶段${sr.stageIndex}")
                            prevReceiptHash = (rcpt["receipt_hash"] as? JsonStr)?.value
                        }
                        prevArtifact = sr.artifactDigest
                    }
                    if (localProblems.isNotEmpty()) errors.add("运行 ${mr.run.runId}：${localProblems.joinToString("，")}")
                }
                val successCount = mr.stages.count { it.status == "SUCCESS" }
                checks.add(
                    "运行 ${mr.run.runId}（${mr.run.name}）: $successCount/${template.stages.size} 阶段成功，" +
                        "状态=${mr.run.status}，父=${mr.run.parentRunId ?: "无"}"
                )
            }
        }

        return ImportResult(
            importedTemplates = importedTemplates,
            importedRuns = importedRuns,
            verifiable = errors.isEmpty(),
            checks = checks,
            errors = errors,
        )
    }

    private fun stageFromResponse(runId: String, sr: StageResponse): StageRow {
        val now = repo.now()
        return StageRow(
            id = 0, runId = runId, stageIndex = sr.stageIndex, stageName = sr.name,
            stageVersion = sr.stageVersion, status = StageStatus.valueOf(sr.status),
            attempt = sr.attempt, intentId = sr.intentId,
            inputDigest = sr.inputDigest, paramsDigest = sr.scopeParamsDigest,
            envFingerprint = sr.envFingerprint,
            upstreamDigest = sr.upstreamDigest,
            artifactDigest = sr.artifactDigest, artifactText = sr.artifactText,
            receiptJson = sr.receipt,
            reason = sr.reason, source = sr.source, createdAt = now, updatedAt = sr.updatedAt,
        )
    }

    companion object {
        private val stringListSer = ListSerializer(String.serializer())

        fun toRunResponse(r: RunRow, idempotentReuse: Boolean = false): RunResponse {
            val keys = Json { ignoreUnknownKeys = true }
                .decodeFromString(stringListSer, r.changedKeys)
            return RunResponse(
                runId = r.id, name = r.name, templateId = r.templateId,
                templateVersion = r.templateVersion, cursor = r.cursor,
                cursorVersion = r.cursorVersion, status = r.status,
                paramsDigest = r.paramsDigest, inputDigest = r.inputDigest,
                envFingerprint = r.envFingerprint,
                parentRunId = r.parentRunId, changedKeys = keys,
                idempotentReuse = idempotentReuse, createdAt = r.createdAt,
            )
        }

        fun toStageResponse(s: StageRow): StageResponse = StageResponse(
            stageIndex = s.stageIndex, name = s.stageName, stageVersion = s.stageVersion,
            status = s.status.name, attempt = s.attempt, intentId = s.intentId,
            inputDigest = s.inputDigest, scopeParamsDigest = s.paramsDigest,
            upstreamDigest = s.upstreamDigest,
            artifactDigest = s.artifactDigest, artifactText = s.artifactText,
            receipt = s.receiptJson, reason = s.reason, source = s.source,
            envFingerprint = s.envFingerprint, updatedAt = s.updatedAt,
        )
    }
}

data class ImportResult(
    val importedTemplates: Int,
    val importedRuns: Int,
    val verifiable: Boolean,
    val checks: List<String>,
    val errors: List<String>,
)
