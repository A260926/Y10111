package lineage

import java.sql.Connection

class Repository(private val db: Database) {

    internal val connection: Connection get() = db.connection

    // ---------- meta ----------

    fun getMeta(key: String): String? = db.tx {
        connection.prepareStatement("SELECT value FROM meta WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
    }

    fun setMeta(key: String, value: String) = connection
        .prepareStatement(
            """
            INSERT INTO meta(key, value) VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """.trimIndent(),
        )
        .use { ps ->
            ps.setString(1, key)
            ps.setString(2, value)
            ps.executeUpdate()
        }

    // ---------- templates ----------

    fun insertTemplate(template: Template) = connection
        .prepareStatement(
            """
            INSERT INTO templates(id, name, description, param_fields_json, stages_json,
                version, spec_hash, created_at)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        )
        .use { ps ->
            ps.setString(1, template.id)
            ps.setString(2, template.name)
            ps.setString(3, template.description)
            ps.setString(4, encodeList(template.paramFields))
            ps.setString(5, encodeList(template.stages))
            ps.setInt(6, template.version)
            ps.setString(7, template.specHash)
            ps.setString(8, template.createdAt)
            ps.executeUpdate()
        }

    fun findTemplate(id: String): Template? = db.tx {
        connection.prepareStatement("SELECT * FROM templates WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { if (it.next()) rowToTemplate(it) else null }
        }
    }

    fun listTemplates(): List<Template> = db.tx {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT * FROM templates ORDER BY created_at, id").use { rs ->
                buildList { while (rs.next()) add(rowToTemplate(rs)) }
            }
        }
    }

    private fun rowToTemplate(rs: java.sql.ResultSet) = Template(
        id = rs.getString("id"),
        name = rs.getString("name"),
        description = rs.getString("description"),
        paramFields = decodeList(rs.getString("param_fields_json")),
        stages = decodeList(rs.getString("stages_json")),
        version = rs.getInt("version"),
        specHash = rs.getString("spec_hash"),
        createdAt = rs.getString("created_at"),
    )

    // ---------- runs ----------

    fun insertRun(run: Run) = connection
        .prepareStatement(
            """
            INSERT INTO runs(id, template_id, template_version, display_name, params_canonical,
                params_hash, env_canonical, env_fingerprint, input_canonical, input_hash,
                cursor, status, version, parent_run_id, changed_params_json, secret_digests_json,
                fail_stage, fail_message, idempotency_key, created_at)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        )
        .use { ps ->
            ps.setString(1, run.id)
            ps.setString(2, run.templateId)
            ps.setInt(3, run.templateVersion)
            ps.setString(4, run.displayName)
            ps.setString(5, run.paramsCanonical)
            ps.setString(6, run.paramsHash)
            ps.setString(7, run.envCanonical)
            ps.setString(8, run.envFingerprint)
            ps.setString(9, run.inputCanonical)
            ps.setString(10, run.inputHash)
            ps.setInt(11, run.cursor)
            ps.setString(12, run.status)
            ps.setInt(13, run.version)
            ps.setString(14, run.parentRunId)
            ps.setString(15, encodeJson(run.changedParams))
            ps.setString(16, encodeJson(run.secretDigests))
            ps.setString(17, run.failStage)
            ps.setString(18, run.failMessage)
            ps.setString(19, run.idempotencyKey)
            ps.setString(20, run.createdAt)
            ps.executeUpdate()
        }

    fun findRun(id: String): Run? = db.tx {
        val hasRow = connection.prepareStatement("SELECT 1 FROM runs WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { it.next() }
        }
        if (!hasRow) return@tx null
        rowToRunById(id)
    }

    private fun rowToRunById(id: String): Run {
        lateinit var run: Run
        connection.prepareStatement("SELECT * FROM runs WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                rs.next()
                run = Run(
                    id = id,
                    templateId = rs.getString("template_id"),
                    templateVersion = rs.getInt("template_version"),
                    displayName = rs.getString("display_name"),
                    paramsCanonical = rs.getString("params_canonical"),
                    paramsHash = rs.getString("params_hash"),
                    envCanonical = rs.getString("env_canonical"),
                    envFingerprint = rs.getString("env_fingerprint"),
                    inputCanonical = rs.getString("input_canonical"),
                    inputHash = rs.getString("input_hash"),
                    cursor = rs.getInt("cursor"),
                    status = rs.getString("status"),
                    version = rs.getInt("version"),
                    parentRunId = rs.getString("parent_run_id"),
                    changedParams = decodeJson(rs.getString("changed_params_json")),
                    secretDigests = decodeJson(rs.getString("secret_digests_json")),
                    failStage = rs.getString("fail_stage"),
                    failMessage = rs.getString("fail_message"),
                    idempotencyKey = rs.getString("idempotency_key"),
                    createdAt = rs.getString("created_at"),
                    stages = listStages(id),
                )
            }
        }
        return run
    }

    fun findRunByIdempotencyKey(key: String): Run? = db.tx {
        val id = connection.prepareStatement("SELECT id FROM runs WHERE idempotency_key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
        id?.let { rowToRunById(it) }
    }

    fun listRuns(): List<Run> = db.tx {
        val ids = connection.createStatement().use { st ->
            st.executeQuery("SELECT id FROM runs ORDER BY created_at, id").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
        ids.map { rowToRunById(it) }
    }

    fun updateRunState(runId: String, cursor: Int, status: String, version: Int): Int =
        connection
            .prepareStatement(
                "UPDATE runs SET cursor = ?, status = ?, version = ? WHERE id = ? AND version = ?",
            )
            .use { ps ->
                ps.setInt(1, cursor)
                ps.setString(2, status)
                ps.setInt(3, version + 1)
                ps.setString(4, runId)
                ps.setInt(5, version)
                val updated = ps.executeUpdate()
                if (updated == 0) {
                    throw ConflictException("版本冲突：运行 $runId 已被其他请求推进，请刷新后重试")
                }
                updated
            }

    // ---------- stages ----------

    fun upsertStage(record: StageRecord, runId: String) = connection
        .prepareStatement(
            """
            INSERT INTO stages(run_id, ordinal, stage_name, stage_version, status, intent_json,
                artifact_id, content_hash, summary_json, receipt_json, failure_message,
                declared_params_json, declared_env_json, stage_input_hash, upstream_hash,
                env_fingerprint_stage, reuse_json, started_at, finished_at)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(run_id, ordinal) DO UPDATE SET
                stage_name = excluded.stage_name,
                stage_version = excluded.stage_version,
                status = excluded.status,
                intent_json = excluded.intent_json,
                artifact_id = excluded.artifact_id,
                content_hash = excluded.content_hash,
                summary_json = excluded.summary_json,
                receipt_json = excluded.receipt_json,
                failure_message = excluded.failure_message,
                declared_params_json = excluded.declared_params_json,
                declared_env_json = excluded.declared_env_json,
                stage_input_hash = excluded.stage_input_hash,
                upstream_hash = excluded.upstream_hash,
                env_fingerprint_stage = excluded.env_fingerprint_stage,
                reuse_json = excluded.reuse_json,
                started_at = excluded.started_at,
                finished_at = excluded.finished_at
            """.trimIndent(),
        )
        .use { ps ->
            ps.setString(1, runId)
            ps.setInt(2, record.ordinal)
            ps.setString(3, record.stageName)
            ps.setString(4, record.stageVersion)
            ps.setString(5, record.status)
            ps.setString(6, record.intentJson)
            ps.setString(7, record.artifactId)
            ps.setString(8, record.contentHash)
            ps.setString(9, record.summaryJson)
            ps.setString(10, record.receiptJson)
            ps.setString(11, record.failureMessage)
            ps.setString(12, encodeList(record.declaredParams))
            ps.setString(13, encodeList(record.declaredEnv))
            ps.setString(14, record.stageInputHash)
            ps.setString(15, record.upstreamHash)
            ps.setString(16, record.envFingerprintStage)
            ps.setString(17, encodeJson(record.reuse))
            ps.setString(18, record.startedAt)
            ps.setString(19, record.finishedAt)
            ps.executeUpdate()
        }

    fun listStages(runId: String): List<StageRecord> = db.tx {
        connection.prepareStatement("SELECT * FROM stages WHERE run_id = ? ORDER BY ordinal").use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rowToStage(rs)) }
            }
        }
    }

    private fun rowToStage(rs: java.sql.ResultSet): StageRecord = StageRecord(
        stageName = rs.getString("stage_name"),
        stageVersion = rs.getString("stage_version"),
        ordinal = rs.getInt("ordinal"),
        status = rs.getString("status"),
        intentJson = rs.getString("intent_json"),
        artifactId = rs.getString("artifact_id"),
        contentHash = rs.getString("content_hash"),
        summaryJson = rs.getString("summary_json"),
        receiptJson = rs.getString("receipt_json"),
        failureMessage = rs.getString("failure_message"),
        declaredParams = decodeList(rs.getString("declared_params_json")),
        declaredEnv = decodeList(rs.getString("declared_env_json")),
        stageInputHash = rs.getString("stage_input_hash"),
        upstreamHash = rs.getString("upstream_hash"),
        envFingerprintStage = rs.getString("env_fingerprint_stage"),
        reuse = decodeJson(rs.getString("reuse_json")),
        startedAt = rs.getString("started_at"),
        finishedAt = rs.getString("finished_at"),
    )

    // ---------- artifacts ----------

    fun insertArtifact(id: String, contentHash: String, summaryJson: String, receiptJson: String) =
        connection
            .prepareStatement(
                """
                INSERT INTO artifacts(id, content_hash, summary_json, receipt_json)
                VALUES(?, ?, ?, ?)
                ON CONFLICT(content_hash) DO NOTHING
                """.trimIndent(),
            )
            .use { ps ->
                ps.setString(1, id)
                ps.setString(2, contentHash)
                ps.setString(3, summaryJson)
                ps.setString(4, receiptJson)
                ps.executeUpdate()
            }

    fun artifactExists(contentHash: String): Boolean = db.tx {
        connection.prepareStatement("SELECT 1 FROM artifacts WHERE content_hash = ?").use { ps ->
            ps.setString(1, contentHash)
            ps.executeQuery().use { it.next() }
        }
    }

    data class ArtifactRow(val id: String, val contentHash: String, val summaryJson: String, val receiptJson: String)

    fun listAllArtifacts(): List<ArtifactRow> = db.tx {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT id, content_hash, summary_json, receipt_json FROM artifacts ORDER BY id").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(ArtifactRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                    }
                }
            }
        }
    }

    fun updateRunStateReuse(runId: String, cursor: Int, status: String): Int = connection
        .prepareStatement("UPDATE runs SET cursor = ?, status = ?, version = version + 1 WHERE id = ?")
        .use { ps ->
            ps.setInt(1, cursor)
            ps.setString(2, status)
            ps.setString(3, runId)
            ps.executeUpdate()
        }
}
