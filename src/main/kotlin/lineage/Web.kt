package lineage

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
    // 支持 --port 5241 / --port=5241 / -port 5241 等写法，其余参数原样交给 Ktor EngineMain。
    val ktorArgs = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        val mPort = Regex("^--?port(?:=(.+))?$").matchEntire(a)
        if (mPort != null) {
            val port = mPort.groupValues[1].ifEmpty {
                require(i + 1 < args.size) { "--port 需要端口号" }
                args[++i]
            }
            System.setProperty("PORT", port)
        } else {
            ktorArgs.add(a)
        }
        i++
    }
    EngineMain.main(ktorArgs.toTypedArray())
}

private var services: Services? = null

data class Services(
    val store: Store,
    val engine: Engine,
    val lineage: Lineage,
    val exportImport: ExportImport,
)

@Suppress("unused")
fun Application.module() {
    val portArg = environment.config.propertyOrNull("ktor.deployment.port")?.getString()
    val dbPath = environment.config.propertyOrNull("app.dbPath")?.getString()
        ?: System.getProperty("lineage.db", "data/lineage.db")
    val workMillis = environment.config.propertyOrNull("app.workMillis")?.getString()?.toLongOrNull() ?: 600L

    val store = Store(dbPath)
    val engine = Engine(store, workMillis = workMillis)
    val lineage = Lineage(store)
    val exportImport = ExportImport(store)
    Seeder.seed(engine, lineage, store)
    val svc = Services(store, engine, lineage, exportImport)
    services = svc
    log.info("实验运行谱系库已启动 db={} port={}", dbPath, portArg ?: "8080")

    install(ContentNegotiation) {
        json(Json { prettyPrint = true; encodeDefaults = true })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (cause.message ?: "not found")))
        }
        exception<ConflictException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, mapOf("error" to (cause.message ?: "conflict")))
        }
        exception<LineageException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
        }
        exception<Canonical.CanonicalException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "参数/输入规范化失败: ${cause.message}"))
        }
    }

    routing {
        webRoutes(svc)
        apiRoutes(svc)
    }
}

