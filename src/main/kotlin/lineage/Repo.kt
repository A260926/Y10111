package lineage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant

object JsonCodecs {
    val json = Json { ignoreUnknownKeys = true }
}

class Repo(private val store: Store) {

    fun now(): String = Instant.now().toString()

    fun putTemplate(conn: Connection, t: Template) {
        val stagesJson = JsonCodecs.json.encodeToString(ListSerializer(StageDef.serializer()), t.stages)
        val secretsJson = JsonCodecs.json.encodeToString(ListSerializer(String.serializer()), t.secrets)
        conn.prepareStatement(
            """INSERT INTO templates(id, template_version, name, stages_json, secrets_json, created_at)
               VALUES(?,?,?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET
                 template_version=excluded.template_version,
                 name=excluded.name,
                 stages_json=excluded.stages_json,
                 secrets_json=excluded.secrets_json"""
        ).use { ps: PreparedStatement ->
            ps.setString(1, t.id)
            ps.setInt(2, t.templateVersion)
            ps.setString(3, t.name)
            ps.setString(4, stagesJson)
            ps.setString(5, secretsJson)
            ps.setString(6, t.createdAt)
            ps.executeUpdate()
        }
    }

    fun getTemplate(conn: Connection, id: String): Template? {
        conn.prepareStatement("SELECT * FROM templates WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return mapTemplate(rs)
            }
        }
    }

    fun listTemplates(conn: Connection): List<Template> {
        conn.createStatement().executeQuery("SELECT * FROM templates ORDER BY created_at, id").use { rs ->
            val out = mutableListOf<Template>()
            while (rs.next()) out.add(mapTemplate(rs))
            return out
        }
    }

    private fun mapTemplate(rs: java.sql.ResultSet): Template {
        val stages = JsonCodecs.json.decodeFromString(
            ListSerializer(StageDef.serializer()),
            rs.getString("stages_json")
        )
        val secrets = JsonCodecs.json.decodeFromString(
            ListSerializer(String.serializer()),
            rs.getString("secrets_json")
        )
        return Template(
            id = rs.getString("id"),
            templateVersion = rs.getInt("template_version"),
            name = rs.getString("name"),
            stages = stages,
            secrets = secrets,
            createdAt = rs.getString("created_at"),
        )
    }

    fun insertRun(conn: Connection, r: RunRow) {
        conn.prepareStatement(
            """INSERT INTO runs(id, template_id, template_version, name, cursor, cursor_version,
                 params_canonical, params_digest, input_text, input_digest, env_json, env_fingerprint,
                 parent_run_id, changed_keys_json, idempotency_key, status, created_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
        ).use { ps ->
            var i = 1
            ps.setString(i++, r.id); ps.setString(i++, r.templateId); ps.setInt(i++, r.templateVersion)
            ps.setString(i++, r.name); ps.setInt(i++, r.cursor); ps.setLong(i++, r.cursorVersion)
            ps.setString(i++, r.paramsCanonical); ps.setString(i++, r.paramsDigest)
            ps.setString(i++, r.inputText); ps.setString(i++, r.inputDigest)
            ps.setString(i++, r.env); ps.setString(i++, r.envFingerprint)
            ps.setString(i++, r.parentRunId); ps.setString(i++, r.changedKeys)
            if (r.idempotencyKey == null) ps.setNull(i++, java.sql.Types.VARCHAR) else ps.setString(i++, r.idempotencyKey)
            ps.setString(i++, r.status); ps.setString(i++, r.createdAt)
            ps.executeUpdate()
        }
    }

    fun updateCursor(conn: Connection, runId: String, cursor: Int, status: String) {
        val n = conn.prepareStatement(
            "UPDATE runs SET cursor=?, cursor_version=cursor_version+1, status=? WHERE id=?"
        ).use { ps ->
            ps.setInt(1, cursor); ps.setString(2, status); ps.setString(3, runId)
            ps.executeUpdate()
        }
        if (n == 0) throw NotFoundException("运行不存在: $runId")
    }

    fun getRun(conn: Connection, id: String): RunRow? {
        conn.prepareStatement("SELECT * FROM runs WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return mapRun(rs)
            }
        }
    }

    fun findByIdempotencyKey(conn: Connection, key: String): RunRow? {
        conn.prepareStatement("SELECT * FROM runs WHERE idempotency_key=?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return mapRun(rs)
            }
        }
    }

    fun listRuns(conn: Connection): List<RunRow> {
        conn.createStatement().executeQuery("SELECT * FROM runs ORDER BY created_at, id").use { rs ->
            val out = mutableListOf<RunRow>()
            while (rs.next()) out.add(mapRun(rs))
            return out
        }
    }

    private fun mapRun(rs: java.sql.ResultSet): RunRow = RunRow(
        id = rs.getString("id"),
        templateId = rs.getString("template_id"),
        templateVersion = rs.getInt("template_version"),
        name = rs.getString("name"),
        cursor = rs.getInt("cursor"),
        cursorVersion = rs.getLong("cursor_version"),
        paramsCanonical = rs.getString("params_canonical"),
        paramsDigest = rs.getString("params_digest"),
        inputText = rs.getString("input_text"),
        inputDigest = rs.getString("input_digest"),
        env = rs.getString("env_json"),
        envFingerprint = rs.getString("env_fingerprint"),
        parentRunId = rs.getString("parent_run_id"),
        changedKeys = rs.getString("changed_keys_json"),
        idempotencyKey = rs.getString("idempotency_key"),
        status = rs.getString("status"),
        createdAt = rs.getString("created_at"),
    )

    fun insertStage(conn: Connection, s: StageRow) {
        conn.prepareStatement(
            """INSERT OR IGNORE INTO stages(run_id, stage_index, stage_name, stage_version, status, attempt,
                 intent_id, input_digest, params_digest, env_fingerprint, upstream_digest,
                 artifact_digest, artifact_text, receipt_json, reason, source, created_at, updated_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
        ).use { ps ->
            var i = 1
            ps.setString(i++, s.runId); ps.setInt(i++, s.stageIndex); ps.setString(i++, s.stageName)
            ps.setInt(i++, s.stageVersion); ps.setString(i++, s.status.name); ps.setInt(i++, s.attempt)
            ps.setString(i++, s.intentId); ps.setString(i++, s.inputDigest); ps.setString(i++, s.paramsDigest)
            ps.setString(i++, s.envFingerprint); ps.setString(i++, s.upstreamDigest)
            ps.setString(i++, s.artifactDigest); ps.setString(i++, s.artifactText); ps.setString(i++, s.receiptJson)
            ps.setString(i++, s.reason); ps.setString(i++, s.source); ps.setString(i++, s.createdAt); ps.setString(i++, s.updatedAt)
            ps.executeUpdate()
        }
    }

    fun stageExists(conn: Connection, runId: String, idx: Int): Boolean {
        conn.prepareStatement("SELECT 1 FROM stages WHERE run_id=? AND stage_index=?").use { ps ->
            ps.setString(1, runId); ps.setInt(2, idx)
            ps.executeQuery().use { return it.next() }
        }
    }

    fun updateStageSuccess(conn: Connection, s: StageRow) {
        conn.prepareStatement(
            """UPDATE stages SET status='SUCCESS', attempt=?, intent_id=?, input_digest=?, params_digest=?,
                 env_fingerprint=?, upstream_digest=?, artifact_digest=?, artifact_text=?, receipt_json=?,
                 reason=NULL, source=?, updated_at=?
               WHERE run_id=? AND stage_index=?"""
        ).use { ps ->
            var i = 1
            ps.setInt(i++, s.attempt); ps.setString(i++, s.intentId); ps.setString(i++, s.inputDigest)
            ps.setString(i++, s.paramsDigest); ps.setString(i++, s.envFingerprint); ps.setString(i++, s.upstreamDigest)
            ps.setString(i++, s.artifactDigest); ps.setString(i++, s.artifactText); ps.setString(i++, s.receiptJson)
            ps.setString(i++, s.source); ps.setString(i++, s.updatedAt)
            ps.setString(i++, s.runId); ps.setInt(i++, s.stageIndex)
            ps.executeUpdate()
        }
    }

    fun updateStageFailed(conn: Connection, s: StageRow) {
        conn.prepareStatement(
            """UPDATE stages SET status='FAILED', attempt=?, intent_id=?, input_digest=?, params_digest=?,
                 env_fingerprint=?, upstream_digest=?, artifact_digest=NULL, artifact_text=NULL, receipt_json=NULL,
                 reason=?, source=?, updated_at=?
               WHERE run_id=? AND stage_index=?"""
        ).use { ps ->
            var i = 1
            ps.setInt(i++, s.attempt); ps.setString(i++, s.intentId); ps.setString(i++, s.inputDigest)
            ps.setString(i++, s.paramsDigest); ps.setString(i++, s.envFingerprint); ps.setString(i++, s.upstreamDigest)
            ps.setString(i++, s.reason); ps.setString(i++, s.source); ps.setString(i++, s.updatedAt)
            ps.setString(i++, s.runId); ps.setInt(i++, s.stageIndex)
            ps.executeUpdate()
        }
    }

    fun getStage(conn: Connection, runId: String, idx: Int): StageRow? {
        conn.prepareStatement("SELECT * FROM stages WHERE run_id=? AND stage_index=?").use { ps ->
            ps.setString(1, runId); ps.setInt(2, idx)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return mapStage(rs)
            }
        }
    }

    fun listStages(conn: Connection, runId: String): List<StageRow> {
        conn.prepareStatement("SELECT * FROM stages WHERE run_id=? ORDER BY stage_index").use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<StageRow>()
                while (rs.next()) out.add(mapStage(rs))
                return out
            }
        }
    }

    private fun mapStage(rs: java.sql.ResultSet): StageRow = StageRow(
        id = rs.getLong("id"),
        runId = rs.getString("run_id"),
        stageIndex = rs.getInt("stage_index"),
        stageName = rs.getString("stage_name"),
        stageVersion = rs.getInt("stage_version"),
        status = StageStatus.valueOf(rs.getString("status")),
        attempt = rs.getInt("attempt"),
        intentId = rs.getString("intent_id"),
        inputDigest = rs.getString("input_digest"),
        paramsDigest = rs.getString("params_digest"),
        envFingerprint = rs.getString("env_fingerprint"),
        upstreamDigest = rs.getString("upstream_digest"),
        artifactDigest = rs.getString("artifact_digest"),
        artifactText = rs.getString("artifact_text"),
        receiptJson = rs.getString("receipt_json"),
        reason = rs.getString("reason"),
        source = rs.getString("source"),
        createdAt = rs.getString("created_at"),
        updatedAt = rs.getString("updated_at"),
    )

    fun addEvent(conn: Connection, runId: String, stageIndex: Int?, kind: String, detail: String, at: String = now()) {
        conn.prepareStatement(
            "INSERT INTO events(run_id, stage_index, kind, detail, at) VALUES(?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, runId)
            if (stageIndex == null) ps.setNull(2, java.sql.Types.INTEGER) else ps.setInt(2, stageIndex)
            ps.setString(3, kind); ps.setString(4, detail); ps.setString(5, at)
            ps.executeUpdate()
        }
    }

    fun listEvents(conn: Connection, runId: String): List<EventRow> {
        conn.prepareStatement("SELECT * FROM events WHERE run_id=? ORDER BY id").use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<EventRow>()
                while (rs.next()) {
                    val idxValue = rs.getInt("stage_index")
                    out.add(
                        EventRow(
                            id = rs.getLong("id"),
                            runId = rs.getString("run_id"),
                            stageIndex = if (rs.wasNull()) null else idxValue,
                            kind = rs.getString("kind"),
                            detail = rs.getString("detail"),
                            at = rs.getString("at"),
                        )
                    )
                }
                return out
            }
        }
    }

    fun getMeta(conn: Connection, k: String): String? {
        conn.prepareStatement("SELECT v FROM meta WHERE k=?").use { ps ->
            ps.setString(1, k)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getString(1) else null }
        }
    }

    fun putMeta(conn: Connection, k: String, v: String) {
        conn.prepareStatement(
            "INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v"
        ).use { ps ->
            ps.setString(1, k); ps.setString(2, v); ps.executeUpdate()
        }
    }
}
