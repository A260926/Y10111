package lineage

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Path
import kotlin.io.path.Path

fun main(args: Array<String>) {
    var port = 8080
    var dbPath = Path("lineage.db")
    var seed = true
    var index = 0
    while (index < args.size) {
        when (val arg = args[index]) {
            "--port" -> {
                port = args.getOrNull(index + 1)?.toIntOrNull()
                    ?: throw IllegalArgumentException("--port 需要一个整数")
                index += 2
            }
            "--db" -> {
                dbPath = Path(args.getOrNull(index + 1) ?: throw IllegalArgumentException("--db 需要路径"))
                index += 2
            }
            "--no-seed" -> {
                seed = false
                index += 1
            }
            else -> throw IllegalArgumentException("未知参数: $arg")
        }
    }

    val database = Database(dbPath)
    database.connect()
    val repository = Repository(database)
    val service = LineageService(database, repository)
    val manifests = ManifestService(database, repository, service)
    val lineageQuery = LineageQuery(repository)
    if (seed) Seed.seedIfEmpty(database, repository, service)

    embeddedServer(Netty, port = port) {
        configureWeb(WebContext(database, repository, service, manifests, lineageQuery))
    }.start(wait = true)
}
