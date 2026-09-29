package lineage

import kotlinx.serialization.json.JsonObject

/** 并发推进的版本冲突。 */
class ConflictException(message: String) : RuntimeException(message)

class LineageService(private val db: Database, private val repo: Repository) {

    // ---------- 模板 ----------

    fun createTemplate(spec: TemplateSpec): Template {
        validateTemplateSpec(spec)
        val specText = Canonical.render(
            linkedMapOf(
                "name" to spec.name,
                "description" to spec.description,
                "paramFields" to spec.paramFields.map { field ->
                    linkedMapOf(
                        "name" to field.name,
                        "type" to field.type,
                        "required" to field.required,
                        "secret" to field.secret,
                    )
                },
                "stages" to spec.stages.map { stage ->
                    linkedMapOf(
                        "name" to stage.name,
                        "version" to stage.version,
                        "declaresParams" to stage.declaresParams,
                        "declaresEnv" to stage.declaresEnv,
                    )
                },
            ),
        )
        val specHash = Canonical.sha256(specText)
        return db.tx {
            val existing = repo.listTemplates().lastOrNull { it.name == spec.name && it.specHash == specHash }
            if (existing != null) return@tx existing
            val sameName = repo.listTemplates().filter { it.name == spec.name }.maxByOrNull { it.version }
            val version = (sameName?.version ?: 0) + 1
            val template = Template(
                id = "tpl-${(sameName?.let { Canonical.shortHash(it.specHash) } ?: "v$version")}-${Canonical.shortHash(specHash)}",
                name = spec.name,
                description = spec.description,
                paramFields = spec.paramFields,
                stages = spec.stages,
                version = version,
                specHash = specHash,
                createdAt = db.now(),
            )
            repo.insertTemplate(template)
            template
        }
    }

    private fun validateTemplateSpec(spec: TemplateSpec) {
        if (spec.name.isBlank()) throw LineageException("模板名称不能为空")
        if (spec.stages.isEmpty()) throw LineageException("模板至少包含一个阶段")
        val fieldNames = spec.paramFields.map { it.name }
        if (fieldNames.toSet().size != fieldNames.size) throw LineageException("参数字段名称重复")
        if (spec.stages.map { it.name }.toSet().size != spec.stages.size) {
            throw LineageException("阶段名称重复")
        }
        val knownFields = fieldNames.toSet()
        spec.stages.forEach { stage ->
            stage.declaresParams.forEach { param ->
                if (param !in knownFields) throw LineageException("阶段 ${stage.name} 声明了不存在的参数 $param")
            }
        }
    }

    fun getTemplate(id: String): Template =
        repo.findTemplate(id) ?: throw LineageException("模板不存在: $id")

    // ---------- 冻结运行 ----------

    fun freezeRun(request: RunParams): Run = db.tx {
        val template = getTemplate(request.templateId)
        normalizeAndInsertRun(
            template = template,
            params = request.params,
            env = request.env,
            inputSummary = request.inputSummary,
            displayName = request.displayName.ifBlank { "run-${db.now().take(19)}" },
            parentRunId = null,
            changedParams = emptyMap(),
            idempotencyKey = request.idempotencyKey,
            failStage = null,
            failMessage = null,
        )
    }

    private fun normalizeAndInsertRun(
        template: Template,
        params: JsonObject,
        env: JsonObject,
        inputSummary: JsonObject,
        displayName: String,
        parentRunId: String?,
        changedParams: Map<String, String>,
        idempotencyKey: String?,
        failStage: String?,
        failMessage: String?,
    ): Run {
        val normalizedParams = Normalizer.normalizeParams(template.paramFields, params)
        val (envCanonical, envFingerprint) = Normalizer.normalizeEnv(env)
        val (inputCanonical, inputHash) = Normalizer.normalizeInput(inputSummary)
        if (idempotencyKey != null) {
            val existing = repo.findRunByIdempotencyKey(idempotencyKey)
            if (existing != null) {
                val sameContent = existing.templateId == template.id &&
                    existing.paramsHash == normalizedParams.hash &&
                    existing.envFingerprint == envFingerprint &&
                    existing.inputHash == inputHash
                if (sameContent) return existing
                throw ConflictException("幂等键 $idempotencyKey 已用于不同内容，拒绝创建")
            }
        }
        if (failStage != null && template.stages.none { it.name == failStage }) {
            throw LineageException("failStage 不在模板阶段列表中: $failStage")
        }
        val identity = Canonical.sha256(
            listOf(
                template.id,
                template.version.toString(),
                normalizedParams.hash,
                inputHash,
                envFingerprint,
                parentRunId ?: "",
                failStage ?: "",
            ).joinToString("|"),
        )
        val run = Run(
            id = "run-${identity.take(16)}",
            templateId = template.id,
            templateVersion = template.version,
            displayName = displayName,
            paramsCanonical = normalizedParams.canonical,
            paramsHash = normalizedParams.hash,
            envCanonical = envCanonical,
            envFingerprint = envFingerprint,
            inputCanonical = inputCanonical,
            inputHash = inputHash,
            cursor = 0,
            status = "running",
            version = 1,
            parentRunId = parentRunId,
            changedParams = changedParams,
            secretDigests = normalizedParams.secretDigests,
            failStage = failStage,
            failMessage = failMessage,
            idempotencyKey = idempotencyKey,
            createdAt = db.now(),
        )
        val sameId = repo.findRun(run.id)
        if (sameId != null) {
            if (idempotencyKey != null && sameId.idempotencyKey != null && sameId.idempotencyKey != idempotencyKey) {
                throw ConflictException("幂等键 $idempotencyKey 已被不同请求使用，拒绝创建")
            }
            return sameId
        }
        repo.insertRun(run)
        template.stages.forEachIndexed { ordinal, stage ->
            repo.upsertStage(
                StageRecord(
                    stageName = stage.name,
                    stageVersion = stage.version,
                    ordinal = ordinal,
                    status = "pending",
                    declaredParams = stage.declaresParams,
                    declaredEnv = stage.declaresEnv,
                    reuse = ReuseInfo(false),
                ),
                run.id,
            )
        }
        return run
    }

    // ---------- 推进（intent -> 产物/receipt -> 游标） ----------

    fun advance(runId: String, request: AdvanceParams): Run {
        // 事务一：校验版本、写 intent。
        db.tx {
            val run = repo.findRun(runId) ?: throw LineageException("运行不存在: $runId")
            if (request.expectedVersion != null && request.expectedVersion != run.version) {
                throw ConflictException("版本冲突：当前版本 ${run.version}，客户端期望 ${request.expectedVersion}")
            }
            if (run.status == "failed") throw LineageException("运行已失败，无法继续推进")
            val template = getTemplate(run.templateId)
            if (run.cursor >= template.stages.size || run.status == "succeeded") return@tx
            val ordinal = run.cursor
            val stage = template.stages[ordinal]
            val existing = run.stages[ordinal]
            if (existing.intentJson != null) return@tx // intent 已持久化，恢复时不重复写
            val input = Simulator.selectSubset(run, template, stage)
            val stageInputHash = Simulator.stageInputHash(input, run.inputHash)
            val upstream = Simulator.upstreamHash(run.stages.getOrNull(ordinal - 1))
            val envStage = stageEnvFingerprint(run, stage)
            val intentJson = Canonical.render(
                linkedMapOf(
                    "runId" to run.id,
                    "stageName" to stage.name,
                    "stageVersion" to stage.version,
                    "ordinal" to ordinal,
                    "stageInputHash" to stageInputHash,
                    "upstreamHash" to upstream,
                    "envFingerprintStage" to envStage,
                ),
            )
            repo.upsertStage(
                existing.copy(
                    intentJson = intentJson,
                    stageInputHash = stageInputHash,
                    upstreamHash = upstream,
                    envFingerprintStage = envStage,
                    startedAt = db.now(),
                ),
                run.id,
            )
        }

        // 模拟“进程在 intent 之后、产物之前退出”：intent 已落盘，直接返回进行中状态。
        if (request.crashAfterIntent) {
            return repo.findRun(runId)!!
        }

        // 事务二：写产物摘要 + receipt，最后推进游标。
        db.tx {
            val run = repo.findRun(runId) ?: throw LineageException("运行不存在: $runId")
            if (request.expectedVersion != null && request.expectedVersion != run.version) {
                throw ConflictException("版本冲突：当前版本 ${run.version}，客户端期望 ${request.expectedVersion}")
            }
            if (run.status == "succeeded") return@tx
            val template = getTemplate(run.templateId)
            if (run.cursor >= template.stages.size) return@tx
            val ordinal = run.cursor
            val stageRecord = run.stages[ordinal]
            if (stageRecord.intentJson == null) {
                throw LineageException("缺少已持久化的 intent，不允许写产物")
            }
            finishCurrentStage(run, template, ordinal, stageRecord.startedAt ?: db.now())
        }
        return repo.findRun(runId)!!
    }

    /** 写产物摘要与 receipt，再推进游标。失败时落失败检查点，绝不进入成功缓存。 */
    private fun finishCurrentStage(run: Run, template: Template, ordinal: Int, startedAt: String) {
        val stage = template.stages[ordinal]
        val existing = run.stages[ordinal]
        val finishedAt = db.now()

        if (run.failStage == stage.name) {
            repo.upsertStage(
                existing.copy(
                    status = "failed",
                    failureMessage = run.failMessage ?: "injected failure",
                    finishedAt = finishedAt,
                ),
                run.id,
            )
            repo.updateRunState(run.id, ordinal, "failed", run.version)
            return
        }

        val input = Simulator.selectSubset(run, template, stage)
        val previous = run.stages.getOrNull(ordinal - 1)?.takeIf { it.status == "succeeded" }
        val produced = Simulator.produce(input, run.inputHash, previous, ordinal)
        repo.insertArtifact(produced.artifactId, produced.contentHash, produced.summaryJson, produced.receiptJson)
        repo.upsertStage(
            existing.copy(
                status = "succeeded",
                artifactId = produced.artifactId,
                contentHash = produced.contentHash,
                summaryJson = produced.summaryJson,
                receiptJson = produced.receiptJson,
                failureMessage = null,
                finishedAt = finishedAt,
                startedAt = startedAt,
            ),
            run.id,
        )
        val nextCursor = ordinal + 1
        val newStatus = if (nextCursor >= template.stages.size) "succeeded" else "running"
        repo.updateRunState(run.id, nextCursor, newStatus, run.version)
    }

    private fun stageEnvFingerprint(run: Run, stage: StageSpec): String {
        val envMap = decodeJson<Map<String, String>>(run.envCanonical)
        val subset = envMap.filterKeys { it in stage.declaresEnv }.toSortedMap()
        return Canonical.sha256("stage-env:${Canonical.render(subset)}")
    }

    fun injectFailure(runId: String, request: InjectFailureParams) = db.tx {
        val run = repo.findRun(runId) ?: throw LineageException("运行不存在: $runId")
        if (request.expectedVersion != null && request.expectedVersion != run.version) {
            throw ConflictException("版本冲突：当前版本 ${run.version}，客户端期望 ${request.expectedVersion}")
        }
        if (run.status != "running") throw LineageException("只有进行中的运行可以注入失败")
        val template = getTemplate(run.templateId)
        val ordinal = run.cursor
        if (ordinal >= template.stages.size) throw LineageException("运行已无待执行阶段")
        val stage = template.stages[ordinal]
        val startedAt = run.stages[ordinal].startedAt ?: db.now()
        val finishedAt = db.now()
        val existing = run.stages[ordinal]
        repo.upsertStage(
            existing.copy(
                status = "failed",
                failureMessage = request.message,
                startedAt = startedAt,
                finishedAt = finishedAt,
            ),
            run.id,
        )
        repo.updateRunState(run.id, ordinal, "failed", run.version)
        repo.findRun(runId)!!
    }

    // ---------- 派生运行与检查点复用 ----------

    fun derive(request: DeriveParams): Run = db.tx {
        val parent = repo.findRun(request.parentRunId)
            ?: throw LineageException("父运行不存在: ${request.parentRunId}")
        val template = getTemplate(parent.templateId)

        val parentParams = decodeJson<JsonObject>(parent.paramsCanonical)
        val merged = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        parentParams.forEach { (key, value) -> merged[key] = value }
        request.changedParams.forEach { (key, value) -> merged[key] = value }
        // 未改动的 secret 参数在父规范化文本中是 {"secretDigest": ...}，派生时保留该编码，
        // 规范化器对 secret 字段跳过类型强转。
        val secretFieldNames = template.paramFields.filter { it.secret }.map { it.name }.toSet()
        secretFieldNames.forEach { name ->
            if (name !in request.changedParams && parentParams[name] is JsonObject) {
                // 保持父级编码
            }
        }
        val unknown = request.changedParams.keys - template.paramFields.map { it.name }.toSet()
        if (unknown.isNotEmpty()) {
            throw LineageException("改动参数包含未声明字段: ${unknown.sorted().joinToString()}")
        }

        val changedDescriptions = request.changedParams.map { (key, value) ->
            val before = parentParams[key]?.let { Canonical.render(elementToCanonical(it)) }
            key to "${before ?: "<缺省>"} -> ${Canonical.render(elementToCanonical(value))}"
        }.toMap()

        val child = normalizeAndInsertRun(
            template = template,
            params = JsonObject(merged),
            env = request.env ?: decodeJson(parent.envCanonical),
            inputSummary = request.inputSummary ?: decodeJson(parent.inputCanonical),
            displayName = request.displayName.ifBlank { "${parent.displayName} 的派生" },
            parentRunId = parent.id,
            changedParams = changedDescriptions,
            idempotencyKey = request.idempotencyKey,
            failStage = request.failStage,
            failMessage = request.failMessage,
        )

        val persistedChild = repo.findRun(child.id)!!
        planReuse(persistedChild, parent, template)
        repo.findRun(child.id)!!
    }

    /**
     * 复用条件（必须同时满足）：
     * 1. 输入数据摘要一致；
     * 2. 阶段名与阶段版本一致；
     * 3. 上游产物链一致（因此一旦某阶段重跑，其后阶段也不能复用）；
     * 4. 阶段声明的环境子集指纹一致；
     * 5. 阶段声明的参数子集一致；
     * 6. 父检查点必须是成功检查点（失败检查点永远不进缓存）。
     */
    private fun planReuse(child: Run, parent: Run, template: Template) {
        var cursor = 0
        for (ordinal in template.stages.indices) {
            val stage = template.stages[ordinal]
            val childRecord = child.stages[ordinal]
            val parentRecord = parent.stages.getOrNull(ordinal)
            val reason = when {
                child.inputHash != parent.inputHash ->
                    "输入数据摘要不同（${child.inputHash.take(10)} != ${parent.inputHash.take(10)}）"
                parentRecord == null -> "父运行缺少该阶段检查点"
                parentRecord.status != "succeeded" -> "父检查点状态为 ${parentRecord.status}，失败/未完成检查点不可复用"
                parentRecord.stageName != stage.name || parentRecord.stageVersion != stage.version ->
                    "阶段名称或版本不同（${parentRecord.stageName}@${parentRecord.stageVersion}）"
                ordinal > 0 && cursor != ordinal -> "上游阶段已重跑，产物链不同"
                else -> {
                    val childInput = Simulator.selectSubset(child, template, stage)
                    val parentInput = Simulator.selectSubset(parent, template, stage)
                    val childInputHash = Simulator.stageInputHash(childInput, child.inputHash)
                    val parentInputHash = Simulator.stageInputHash(parentInput, parent.inputHash)
                    when {
                        stageEnvFingerprint(child, stage) != stageEnvFingerprint(parent, stage) ->
                            "环境指纹变化：声明的环境变量 ${stage.declaresEnv.joinToString()} 不一致，阻止复用"
                        childInputHash != parentInputHash ->
                            "阶段声明参数不同（阶段输入哈希 ${childInputHash.take(10)} != ${parentInputHash.take(10)}）"
                        parentRecord.contentHash == null -> "父检查点缺少产物哈希"
                        else -> null
                    }
                }
            }

            if (reason == null && parentRecord != null) {
                val reused = childRecord.copy(
                    status = "succeeded",
                    intentJson = parentRecord.intentJson,
                    artifactId = parentRecord.artifactId,
                    contentHash = parentRecord.contentHash,
                    summaryJson = parentRecord.summaryJson,
                    receiptJson = parentRecord.receiptJson,
                    declaredParams = parentRecord.declaredParams,
                    declaredEnv = parentRecord.declaredEnv,
                    stageInputHash = parentRecord.stageInputHash,
                    upstreamHash = parentRecord.upstreamHash,
                    envFingerprintStage = parentRecord.envFingerprintStage,
                    reuse = ReuseInfo(
                        reused = true,
                        sourceRunId = parent.id,
                        sourceStage = parentRecord.stageName,
                        reason = "输入摘要、阶段版本、上游产物与声明环境全部一致，复用成功检查点",
                    ),
                    startedAt = parentRecord.startedAt,
                    finishedAt = parentRecord.finishedAt,
                )
                repo.upsertStage(reused, child.id)
                cursor = ordinal + 1
            } else {
                val blocked = childRecord.copy(
                    reuse = ReuseInfo(false, reason = reason),
                )
                repo.upsertStage(blocked, child.id)
            }
        }
        val status = if (cursor >= template.stages.size) "succeeded" else "running"
        repo.updateRunStateReuse(child.id, cursor, status)
    }

    fun verify(runId: String): VerifyReport = db.tx {
        val run = repo.findRun(runId) ?: throw LineageException("运行不存在: $runId")
        val template = getTemplate(run.templateId)
        val checks = mutableListOf<StageCheck>()
        var allOk = true
        run.stages.forEachIndexed { ordinal, record ->
            val stage = template.stages.getOrNull(ordinal)
            val detail: String
            var ok = true
            if (stage == null || stage.name != record.stageName || stage.version != record.stageVersion) {
                ok = false
                detail = "阶段定义与模板不一致"
            } else if (record.reuse.reused) {
                val source = record.reuse.sourceRunId?.let { repo.findRun(it) }
                val sourceRecord = source?.stages?.getOrNull(ordinal)
                if (source == null || sourceRecord == null || sourceRecord.status != "succeeded") {
                    ok = false
                    detail = "复用来源不存在或不是成功检查点"
                } else if (sourceRecord.contentHash != record.contentHash) {
                    ok = false
                    detail = "复用检查点的产物哈希与来源不一致"
                } else if (stageEnvFingerprint(run, stage) != record.envFingerprintStage) {
                    ok = false
                    detail = "当前环境指纹与检查点记录不一致"
                } else {
                    detail = "复用自 ${source.id} 的成功检查点，产物 ${record.artifactId} 可追溯"
                }
            } else if (record.status == "succeeded") {
                detail = if (record.declaredParams.isEmpty() && record.declaredEnv.isEmpty() &&
                    record.stageInputHash != null) {
                    // 从 manifest 导入的检查点：以记录的哈希链做谱系验证（不重放真实计算）。
                    val expectedUpstream = Simulator.upstreamHash(run.stages.getOrNull(ordinal - 1))
                    when {
                        !repo.artifactExists(record.contentHash!!) -> {
                            ok = false
                            "导入检查点的产物在产物库中缺失"
                        }
                        record.upstreamHash != expectedUpstream -> {
                            ok = false
                            "导入检查点的上游产物链与谱系不一致"
                        }
                        else -> "导入检查点：阶段输入哈希 ${record.stageInputHash.take(16)} 与产物链一致"
                    }
                } else {
                    val input = Simulator.selectSubset(run, template, stage)
                    val previous = run.stages.getOrNull(ordinal - 1)?.takeIf { it.status == "succeeded" }
                    val produced = Simulator.produce(input, run.inputHash, previous, ordinal)
                    when {
                        produced.contentHash != record.contentHash -> {
                            ok = false
                            "重算产物哈希与检查点不一致（无法从相同输入重现）"
                        }
                        !repo.artifactExists(record.contentHash!!) -> {
                            ok = false
                            "产物在产物库中缺失"
                        }
                        stageEnvFingerprint(run, stage) != record.envFingerprintStage -> {
                            ok = false
                            "环境指纹与记录不一致"
                        }
                        else -> "以相同输入重算得到相同产物哈希 ${produced.contentHash.take(16)}"
                    }
                }
            } else if (record.status == "failed") {
                detail = "失败检查点（不参与可重现性判定）：${record.failureMessage ?: "无说明"}"
            } else {
                ok = false
                detail = "阶段尚未执行"
            }
            if (!ok) allOk = false
            checks.add(StageCheck(record.stageName, ok, detail))
        }
        VerifyReport(runId, allOk && run.status == "succeeded", checks)
    }
}
