package lineage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

class Database(private val path: Path) {
    /**
     * SQLite 单连接在 JVM 层需要串行化；真实的并发冲突仍由乐观版本号
     * (UPDATE ... WHERE version = ?) 检测并抛出版本冲突。
     */
    private val writeLock = java.util.concurrent.locks.ReentrantLock()

    lateinit var connection: Connection
        private set

    fun connect() {
        if (path.parent != null) Files.createDirectories(path.parent)
        connection = DriverManager.getConnection("jdbc:sqlite:$path")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
        }
        connection.autoCommit = false
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("PRAGMA busy_timeout=10000")
        }
        connection.commit()
        migrate()
    }

    fun close() = connection.close()

    fun <T> tx(action: () -> T): T {
        writeLock.lock()
        try {
            val result = action()
            connection.commit()
            return result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            writeLock.unlock()
        }
    }

    private fun migrate() = tx {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS templates (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    param_fields_json TEXT NOT NULL,
                    stages_json TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    spec_hash TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS runs (
                    id TEXT PRIMARY KEY,
                    template_id TEXT NOT NULL,
                    template_version INTEGER NOT NULL,
                    display_name TEXT NOT NULL,
                    params_canonical TEXT NOT NULL,
                    params_hash TEXT NOT NULL,
                    env_canonical TEXT NOT NULL,
                    env_fingerprint TEXT NOT NULL,
                    input_canonical TEXT NOT NULL,
                    input_hash TEXT NOT NULL,
                    cursor INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    parent_run_id TEXT,
                    changed_params_json TEXT NOT NULL,
                    secret_digests_json TEXT NOT NULL DEFAULT '{}',
                    fail_stage TEXT,
                    fail_message TEXT,
                    idempotency_key TEXT,
                    created_at TEXT NOT NULL,
                    UNIQUE(idempotency_key)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS stages (
                    run_id TEXT NOT NULL,
                    ordinal INTEGER NOT NULL,
                    stage_name TEXT NOT NULL,
                    stage_version TEXT NOT NULL,
                    status TEXT NOT NULL,
                    intent_json TEXT,
                    artifact_id TEXT,
                    content_hash TEXT,
                    summary_json TEXT,
                    receipt_json TEXT,
                    failure_message TEXT,
                    declared_params_json TEXT NOT NULL,
                    declared_env_json TEXT NOT NULL,
                    stage_input_hash TEXT,
                    upstream_hash TEXT,
                    env_fingerprint_stage TEXT,
                    reuse_json TEXT NOT NULL,
                    started_at TEXT,
                    finished_at TEXT,
                    PRIMARY KEY (run_id, ordinal)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS artifacts (
                    id TEXT PRIMARY KEY,
                    content_hash TEXT NOT NULL UNIQUE,
                    summary_json TEXT NOT NULL,
                    receipt_json TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    fun now(): String = Instant.now().toString()
}

