package lineage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant

class Store(dbPath: String) {
    private val url: String = if (dbPath == ":memory:") {
        "jdbc:sqlite::memory:"
    } else {
        val path = Path.of(dbPath).toAbsolutePath()
        Files.createDirectories(path.parent)
        "jdbc:sqlite:${path.toAbsolutePath()}"
    }

    fun <T> tx(immediate: Boolean = true, block: (Connection) -> T): T {
        val conn = open()
        try {
            conn.createStatement().use { st ->
                st.execute(if (immediate) "BEGIN IMMEDIATE" else "BEGIN")
            }
            val result = block(conn)
            conn.createStatement().use { it.execute("COMMIT") }
            return result
        } catch (t: Throwable) {
            try { conn.createStatement().use { it.execute("ROLLBACK") } } catch (_: Throwable) {}
            throw t
        } finally {
            conn.close()
        }
    }

    fun open(): Connection {
        val conn = java.sql.DriverManager.getConnection(url)
        conn.autoCommit = true
        return conn
    }

    init {
        Class.forName("org.sqlite.JDBC")
        open().use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA foreign_keys=ON")
                st.execute("PRAGMA busy_timeout=10000")
                schema(st)
            }
        }
    }

    private fun schema(st: Statement) {
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS templates (
                id TEXT PRIMARY KEY,
                template_version INTEGER NOT NULL,
                name TEXT NOT NULL,
                stages_json TEXT NOT NULL,
                secrets_json TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """.trimIndent()
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS runs (
                id TEXT PRIMARY KEY,
                template_id TEXT NOT NULL,
                template_version INTEGER NOT NULL,
                name TEXT NOT NULL,
                cursor INTEGER NOT NULL,
                cursor_version INTEGER NOT NULL,
                params_canonical TEXT NOT NULL,
                params_digest TEXT NOT NULL,
                input_text TEXT NOT NULL,
                input_digest TEXT NOT NULL,
                env_json TEXT NOT NULL,
                env_fingerprint TEXT NOT NULL,
                parent_run_id TEXT,
                changed_keys_json TEXT NOT NULL,
                idempotency_key TEXT,
                status TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """.trimIndent()
        )
        st.executeUpdate(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_runs_idempotency
            ON runs(idempotency_key) WHERE idempotency_key IS NOT NULL
            """.trimIndent()
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS stages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id TEXT NOT NULL REFERENCES runs(id),
                stage_index INTEGER NOT NULL,
                stage_name TEXT NOT NULL,
                stage_version INTEGER NOT NULL,
                status TEXT NOT NULL,
                attempt INTEGER NOT NULL,
                intent_id TEXT,
                input_digest TEXT,
                params_digest TEXT,
                env_fingerprint TEXT,
                upstream_digest TEXT,
                artifact_digest TEXT,
                artifact_text TEXT,
                receipt_json TEXT,
                reason TEXT,
                source TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                UNIQUE(run_id, stage_index)
            )
            """.trimIndent()
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id TEXT NOT NULL,
                stage_index INTEGER,
                kind TEXT NOT NULL,
                detail TEXT NOT NULL,
                at TEXT NOT NULL
            )
            """.trimIndent()
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS meta (
                k TEXT PRIMARY KEY,
                v TEXT NOT NULL
            )
            """.trimIndent()
        )
    }
}
