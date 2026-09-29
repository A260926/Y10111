package lineage

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Engine(
    private val store: Store,
    private val workMillis: Long = 600L,
) {
    private val repo = Repo(store)

    /** 一次性故障/崩溃注入：key = runId:stageIndex。 */
    private val injected = ConcurrentHashMap<String, InjectedFailure>()

    /** 进程内阶段抢占锁：同运行同阶段同时推进的第二个请求直接得到版本冲突。 */
    private val advancing = ConcurrentHashMap.newKeySet<String>()

    fun injectFailure(runId: String, stageIndex: Int, reason: String = "manual failure") {
        injected["$runId:$stageIndex"] = InjectedFailure(runId, stageIndex, reason, crashBeforeArtifact = true)
    }

    fun clearInjection(runId: String, stageIndex: Int) {
        injected.remove("$runId:$stageIndex")
    }

    /** Web/API 入口注入：crash=true 模拟进程在物化前退出，false 产生 FAILED 检查点。 */
    fun attachInjection(runId: String, stageIndex: Int, reason: String, crash: Boolean) {
        injected["$runId:$stageIndex"] = InjectedFailure(runId, stageIndex, reason, crashBeforeArtifact = crash)
    }

    // ---------- 模板 ----------

    fun createTemplate(req: CreateTemplateRequest): Template {
        val stages = req.stages ?: listOf(
            StageDef("extract", 1, listOf("sample_rate"), envSensitive = false),
            StageDef("transform", 2, listOf("sample_rate", "feature_set"), envSensitive = false),
            StageDef("evaluate", 3, listOf("feature_set", "metric"), envSensitive = true),
        )
        if (stages.isEmpty()) throw LineageException("模板至少需要一个阶段")
        stages.forEach { s ->
            if (s.name.isBlank()) throw LineageException("阶段名不能为空")
            if (s.stageVersion <= 0) throw LineageException("阶段版本必须为正整数: ${s.name}")
        }
        val names = stages.map { it.name }
        if (names.toSet().size != names.size) throw LineageException("阶段名必须唯一")
        val secrets = (req.secrets ?: emptyList()).distinct()
        stages.forEach { s ->
            val leaked = s.consumes.intersect(secrets.toSet())
            if (leaked.isNotEmpty()) {
                throw LineageException("secret 参数键不得被任何阶段消费（否则无法从导出中安全重放）: $leaked")
            }
        }
        val template = Template(
            id = req.id ?: "tpl_" + UUID.randomUUID().toString().take(8),
            templateVersion = 1,
            name = req.name.ifBlank { throw LineageException("模板名不能为空") },
            stages = stages,
            secrets = secrets,
            createdAt = repo.now(),
        )
        store.tx { conn ->
            if (repo.getTemplate(conn, template.id) != null) {
                throw ConflictException("模板 ID 已存在: ${template.id}（模板版本固定后不可修改）")
            }
            repo.putTemplate(conn, template)
            repo.addEvent(conn, "_template", null, "template_created", template.id)
        }
        return template
    }
    // ---------- 冻结运行 ----------

    /**
     * 冻结新运行：启动后固定模板版本、参数规范化结果、输入数据摘要与环境指纹。
     * 相同幂等键且内容一致时返回原运行；内容不一致返回 409。
     */
    fun freeze(req: FreezeRequest): Pair<RunRow, Boolean> {
        val canonical = canonicalParams(req.params)
        val inputElement = parseStrict(req.input, "input")
        val inputCanonical = Canonical.canonicalText(inputElement)
        val inputDigest = Canonical.sha256(inputCanonical)
        val (envText, envFp) = Simulator.envFingerprint(req.env)

        return store.tx { conn ->
            val template = repo.getTemplate(conn, req.templateId)
                ?: throw NotFoundException("模板不存在: ${req.templateId}")
            validateParamsAgainstTemplate(template, canonical.element as JsonObj)

            req.idempotencyKey?.let { key ->
                repo.findByIdempotencyKey(conn, key)?.let { existing ->
                    ensureSameFreeze(existing, template, canonical.text, inputCanonical, envText)
                    return@tx existing to true
                }
            }

            val run = RunRow(
                id = "run_" + UUID.randomUUID().toString().take(12),
                templateId = template.id,
                templateVersion = template.templateVersion,
                name = req.name.ifBlank { "run" },
                cursor = 0,
                cursorVersion = 1,
                paramsCanonical = canonical.text,
                paramsDigest = canonical.digest,
                inputText = inputCanonical,
                inputDigest = inputDigest,
                env = envText,
                envFingerprint = envFp,
                parentRunId = null,
                changedKeys = "[]",
                idempotencyKey = req.idempotencyKey,
                status = "frozen",
                createdAt = repo.now(),
            )
            repo.insertRun(conn, run)
            insertPendingStages(conn, run, template)
            repo.addEvent(conn, run.id, null, "run_frozen",
                "template=${template.id}@v${template.templateVersion} params=${Canonical.short(run.paramsDigest)} input=${Canonical.short(inputDigest)} env=${Canonical.short(envFp)}")
            run to false
        }
    }

    private fun ensureSameFreeze(
        existing: RunRow,
        template: Template,
        paramsCanonical: String,
        inputCanonical: String,
        envText: String,
    ) {
        val mismatch = buildList {
            if (existing.templateId != template.id || existing.templateVersion != template.templateVersion)
                add("template")
            if (existing.paramsCanonical != paramsCanonical) add("params")
            if (existing.inputText != inputCanonical) add("input")
            if (existing.env != envText) add("env")
        }
        if (mismatch.isNotEmpty()) {
            throw ConflictException("幂等键冲突：相同 idempotencyKey 但内容不同: $mismatch")
        }
    }

    private fun validateParamsAgainstTemplate(template: Template, params: JsonObj) {
        // 规范化阶段已拒绝 NaN/Infinity/重复键；此处校验结构。
        template.secrets.forEach { key ->
            // secret 键必须存在（缺字段必须拒绝；值允许为字符串/null，规范化保证其明确身份）。
            if (!params.containsKey(key)) {
                throw LineageException("缺少 secret 字段 \"$key\"：缺字段必须显式提供（可用字符串占位）")
            }
        }
        params.keys.forEach { key ->
            val v = params[key]!!
            if (v is JsonObj) {
                v.keys.forEach { nested ->
                    if (nested in template.secrets) {
                        throw LineageException("secret 仅支持顶层键: $key.$nested")
                    }
                }
            }
        }
    }

    private fun insertPendingStages(conn: java.sql.Connection, run: RunRow, template: Template) {
        val now = repo.now()
        template.stages.forEachIndexed { idx, stage ->
            repo.insertStage(conn, StageRow(
                id = 0, runId = run.id, stageIndex = idx, stageName = stage.name,
                stageVersion = stage.stageVersion, status = StageStatus.PENDING, attempt = 0,
                intentId = null, inputDigest = null, paramsDigest = null, envFingerprint = null,
                upstreamDigest = null, artifactDigest = null, artifactText = null, receiptJson = null,
                reason = null, source = "pending", createdAt = now, updatedAt = now,
            ))
        }
    }

    private data class CanonicalParams(val text: String, val digest: String, val element: JsonValue)

    private fun canonicalParams(raw: String): CanonicalParams {
        val element = parseStrict(raw, "params")
        if (element !is JsonObj) throw LineageException("params 必须是 JSON 对象")
        val text = Canonical.canonicalText(element)
        return CanonicalParams(text, Canonical.sha256(text), element)
    }

    private fun parseStrict(raw: String, field: String): JsonValue = try {
        Canonical.parse(raw)
    } catch (e: Canonical.CanonicalException) {
        throw LineageException("$field 规范化失败：${e.message}")
    }

    // ---------- 推进阶段（intent 先行 → 产物+receipt → 推进游标） ----------

    /**
     * 推进游标下一个阶段。顺序保证：
     * 1. 先写 INTENT（事务提交后崩溃也会留下意图）；
     * 2. 在事务外做确定性物化（真实系统中最耗时的部分）；
     * 3. 再写产物摘要与 receipt，最后推进游标并 bump cursor_version。
     * 任何位置退出后恢复：已 SUCCESS 的不重跑；残留 INTENT/FAILED 用同一 intentId 补完，
     * 物化产物确定性一致，不会产生重复产物。
     */
    fun advance(runId: String, expectedCursorVersion: Long? = null): Pair<RunRow, StageRow> {
        val idxKey = store.tx(immediate = false) { conn ->
            repo.getRun(conn, runId)?.cursor ?: throw NotFoundException("运行不存在: $runId")
        }
        val key = "$runId:$idxKey"
        if (!advancing.add(key)) {
            throw ConflictException("版本冲突：另一个页面/请求正在推进运行 $runId 的阶段 $idxKey，请刷新后重试")
        }
        try {
            return advanceInternal(runId, expectedCursorVersion)
        } finally {
            advancing.remove(key)
        }
    }

    private fun advanceInternal(runId: String, expectedCursorVersion: Long? = null): Pair<RunRow, StageRow> {
        // ---- 事务 A：抢占下一阶段并写 intent（悲观锁，同时推进两个页面会得到 409）----
        val claim = store.tx { conn ->
            val run = repo.getRun(conn, runId) ?: throw NotFoundException("运行不存在: $runId")
            if (expectedCursorVersion != null && expectedCursorVersion != run.cursorVersion) {
                throw ConflictException(
                    "版本冲突：页面持有的 cursorVersion=${expectedCursorVersion} 已过期，" +
                        "当前为 ${run.cursorVersion}，请刷新后重试"
                )
            }
            val template = repo.getTemplate(conn, run.templateId)
                ?: throw LineageException("运行引用的模板缺失: ${run.templateId}")
            val idx = run.cursor
            if (idx >= template.stages.size) {
                throw LineageException("所有阶段已完成，无可推进阶段")
            }
            val stageDef = template.stages[idx]
            val existing = repo.getStage(conn, runId, idx)
            val now = repo.now()

            if (existing != null && existing.status == StageStatus.SUCCESS) {
                // 游标与状态不一致的自愈：不重复产物
                repo.updateCursor(conn, runId, idx + 1, statusOf(conn, runId, template.stages.size))
                repo.addEvent(conn, runId, idx, "cursor_repaired",
                    "阶段 $idx 已成功，仅推进游标（不重复产物）")
                return@tx Claim(run, idx, stageDef, existing, skipped = true)
            }

            val upstream = if (idx == 0) null else repo.getStage(conn, runId, idx - 1)
            if (idx > 0 && (upstream == null || upstream.status != StageStatus.SUCCESS)) {
                throw ConflictException("阶段 $idx 的上游检查点未成功，不能推进")
            }
            val upstreamDigest = upstream?.artifactDigest
            val scopeDigest = scopeParamsDigest(run.paramsCanonical, stageDef.consumes)

            // 崩溃恢复（残留 INTENT）：保持同一 attempt；失败后重试：attempt+1。
            val attempt = when (existing?.status) {
                null -> 1
                StageStatus.INTENT -> existing.attempt.coerceAtLeast(1)
                StageStatus.FAILED -> existing.attempt + 1
                StageStatus.SUCCESS -> existing.attempt
                StageStatus.PENDING -> 1
            }
            val intentId = existing?.intentId
                ?: Canonical.sha256("intent|${run.id}|$idx|${run.inputDigest}|$scopeDigest|${run.envFingerprint}|${upstreamDigest ?: ""}|${stageDef.stageVersion}")

            val row = StageRow(
                id = 0, runId = runId, stageIndex = idx, stageName = stageDef.name,
                stageVersion = stageDef.stageVersion, status = StageStatus.INTENT, attempt = attempt,
                intentId = intentId, inputDigest = run.inputDigest, paramsDigest = scopeDigest,
                envFingerprint = run.envFingerprint, upstreamDigest = upstreamDigest,
                artifactDigest = null, artifactText = null, receiptJson = null,
                reason = null, source = existing?.source ?: "computed",
                createdAt = existing?.createdAt ?: now, updatedAt = now,
            )
            when {
                existing == null -> {
                    // 新阶段：必须先把 INTENT 持久化（随后即使进程崩溃，意图也在盘上）
                    conn.prepareStatement(
                        """INSERT INTO stages(run_id, stage_index, stage_name, stage_version, status, attempt,
                             intent_id, input_digest, params_digest, env_fingerprint, upstream_digest,
                             artifact_digest, artifact_text, receipt_json, reason, source, created_at, updated_at)
                           VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
                    ).use { ps ->
                        var k = 1
                        ps.setString(k++, row.runId); ps.setInt(k++, row.stageIndex)
                        ps.setString(k++, row.stageName); ps.setInt(k++, row.stageVersion)
                        ps.setString(k++, StageStatus.INTENT.name); ps.setInt(k++, row.attempt)
                        ps.setString(k++, row.intentId); ps.setString(k++, row.inputDigest)
                        ps.setString(k++, row.paramsDigest); ps.setString(k++, row.envFingerprint)
                        ps.setString(k++, row.upstreamDigest)
                        ps.setNull(k++, java.sql.Types.VARCHAR); ps.setNull(k++, java.sql.Types.VARCHAR)
                        ps.setNull(k++, java.sql.Types.VARCHAR); ps.setNull(k++, java.sql.Types.VARCHAR)
                        ps.setNull(k++, java.sql.Types.VARCHAR)
                        ps.setString(k++, "computed"); ps.setString(k++, row.createdAt); ps.setString(k++, row.updatedAt)
                        ps.executeUpdate()
                    }
                }
                existing.status == StageStatus.PENDING -> {
                    // 冻结时预建的 PENDING 行：写入意图身份并转为 INTENT
                    conn.prepareStatement(
                        """UPDATE stages SET status='INTENT', attempt=?, intent_id=?, input_digest=?,
                             params_digest=?, env_fingerprint=?, upstream_digest=?, source='computed', updated_at=?
                           WHERE run_id=? AND stage_index=?"""
                    ).use { ps ->
                        var k = 1
                        ps.setInt(k++, row.attempt); ps.setString(k++, row.intentId)
                        ps.setString(k++, row.inputDigest); ps.setString(k++, row.paramsDigest)
                        ps.setString(k++, row.envFingerprint); ps.setString(k++, row.upstreamDigest)
                        ps.setString(k++, row.updatedAt); ps.setString(k++, row.runId); ps.setInt(k++, row.stageIndex)
                        ps.executeUpdate()
                    }
                }
                existing.status == StageStatus.FAILED -> {
                    // 失败重试：重新进入 intent（intentId 保持不变，幂等身份稳定）
                    conn.prepareStatement(
                        "UPDATE stages SET status='INTENT', attempt=?, updated_at=? WHERE run_id=? AND stage_index=?"
                    ).use { ps ->
                        ps.setInt(1, attempt); ps.setString(2, now)
                        ps.setString(3, runId); ps.setInt(4, idx)
                        ps.executeUpdate()
                    }
                }
                // existing.status == INTENT：崩溃恢复，意图行原封不动，直接进入物化
            }
            repo.addEvent(conn, runId, idx, "intent_written",
                "intent=${Canonical.short(intentId)} attempt=$attempt scope_params=${Canonical.short(scopeDigest)}")
            Claim(run, idx, stageDef, row, skipped = false)
        }

        if (claim.skipped) {
            return store.tx { conn ->
                val r = repo.getRun(conn, runId)!!
                r to (repo.getStage(conn, runId, claim.idx)!!)
            }
        }

        // ---- 事务外物化（确定性模拟器）----
        val inputs = stageInputs(claim)
        val injection = injected.remove("${runId}:${claim.idx}")
        val material = try {
            Simulator.materialize(
                runId = runId,
                stageIndex = claim.idx,
                inputs = inputs,
                workMillis = workMillis,
                failOnce = injection,
            )
        } catch (crash: SimulatedCrashException) {
            // 模拟崩溃：不在此处写 FAILED/产物，异常向上抛（如同进程退出）。
            // 注入只消费一次；INTENT 行已由事务 A 提交，恢复时按同一 intentId 补完。
            throw crash
        } catch (e: InjectedFailureException) {
            // 注入失败：写 FAILED 检查点（绝不写产物），后续运行不会把它当成成功缓存。
            return store.tx { conn ->
                val template = repo.getTemplate(conn, claim.run.templateId)!!
                val now = repo.now()
                val failed = claim.row.copy(
                    status = StageStatus.FAILED,
                    updatedAt = now,
                    reason = e.message,
                    source = "failed",
                )
                repo.updateStageFailed(conn, failed)
                repo.updateCursor(conn, runId, claim.idx, "failed")
                repo.addEvent(conn, runId, claim.idx, "stage_failed", e.message ?: "injected failure")
                val r = repo.getRun(conn, runId)!!
                r to repo.getStage(conn, runId, claim.idx)!!
            }
        }

        // ---- 事务 C：写产物摘要与 receipt，最后推进游标 ----
        return store.tx { conn ->
            val current = repo.getStage(conn, runId, claim.idx)
                ?: throw LineageException("intent 检查点丢失，无法提交产物")
            if (current.status == StageStatus.SUCCESS && current.artifactDigest == Canonical.sha256(material.artifactText)) {
                // 极端竞态/重复提交：幂等，不重复产物
                repo.addEvent(conn, runId, claim.idx, "commit_deduped",
                    "产物已存在且一致，跳过重复写入")
                return@tx repo.getRun(conn, runId)!! to current
            }
            val now = repo.now()
            val success = current.copy(
                status = StageStatus.SUCCESS,
                attempt = current.attempt.coerceAtLeast(claim.row.attempt),
                intentId = claim.row.intentId,
                inputDigest = claim.row.inputDigest,
                paramsDigest = claim.row.paramsDigest,
                envFingerprint = claim.row.envFingerprint,
                upstreamDigest = claim.row.upstreamDigest,
                artifactDigest = Canonical.sha256(material.artifactText),
                artifactText = material.artifactText,
                receiptJson = material.receiptJson,
                reason = null,
                source = if (current.source == "pending" || current.source == "failed") "computed" else current.source,
                updatedAt = now,
            )
            repo.updateStageSuccess(conn, success)
            val template = repo.getTemplate(conn, claim.run.templateId)!!
            repo.updateCursor(conn, runId, claim.idx + 1, statusOf(conn, runId, template.stages.size))
            repo.addEvent(conn, runId, claim.idx, "stage_succeeded",
                "artifact=${Canonical.short(success.artifactDigest)} cursor→${claim.idx + 1}")
            repo.getRun(conn, runId)!! to repo.getStage(conn, runId, claim.idx)!!
        }
    }

    private data class Claim(
        val run: RunRow,
        val idx: Int,
        val stageDef: StageDef,
        val row: StageRow,
        val skipped: Boolean,
    )

    private fun stageInputs(claim: Claim): StageInputs {
        val paramsElement = Canonical.parse(claim.run.paramsCanonical)
        val inputElement = Canonical.parse(claim.run.inputText)
        val upstreamReceiptHash = if (claim.idx == 0) null else {
            store.tx(immediate = false) { conn ->
                val up = repo.getStage(conn, claim.run.id, claim.idx - 1)
                up?.receiptJson?.let { receiptHashOf(it) }
            }
        }
        return StageInputs(
            runId = claim.run.id,
            stageName = claim.stageDef.name,
            stageVersion = claim.stageDef.stageVersion,
            inputElement = inputElement,
            inputDigest = claim.run.inputDigest,
            paramsElement = paramsElement,
            paramsDigest = claim.row.paramsDigest!!,
            consumes = claim.stageDef.consumes.toSet(),
            envFingerprint = claim.run.envFingerprint,
            envSensitive = claim.stageDef.envSensitive,
            upstreamDigest = claim.row.upstreamDigest,
            upstreamReceiptHash = upstreamReceiptHash,
        )
    }

    private fun scopeParamsDigest(paramsCanonical: String, consumes: List<String>): String {
        val params = Canonical.parse(paramsCanonical) as JsonObj
        val scoped = JsonObj(consumes.sorted().mapNotNull { key ->
            params[key]?.let { key to it }
        }.toMap())
        return Canonical.sha256(Canonical.render(scoped))
    }

    private fun statusOf(conn: java.sql.Connection, runId: String, stageCount: Int): String {
        val stages = repo.listStages(conn, runId)
        return when {
            stages.any { it.status == StageStatus.FAILED } -> "failed"
            stages.count { it.status == StageStatus.SUCCESS } >= stageCount -> "completed"
            stages.any { it.status == StageStatus.INTENT } -> "running"
            else -> "in_progress"
        }
    }

    private fun receiptHashOf(receiptJson: String): String? = try {
        val obj = Canonical.parse(receiptJson) as JsonObj
        (obj["receipt_hash"] as? JsonStr)?.value
    } catch (_: Throwable) { null }

}
