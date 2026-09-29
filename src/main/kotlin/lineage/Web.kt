package lineage

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

class WebContext(
    val db: Database,
    val repo: Repository,
    val service: LineageService,
    val manifests: ManifestService,
    val lineageQuery: LineageQuery,
)

private suspend inline fun RoutingContext.formAction(crossinline action: suspend () -> String) {
    try {
        val target = action()
        call.respondRedirect(target)
    } catch (error: ConflictException) {
        call.respondText(
            Pages.errorPage("版本冲突", error.message ?: "运行已被另一页面推进，请返回刷新重试"),
            ContentType.Text.Html,
            status = HttpStatusCode.Conflict,
        )
    } catch (error: LineageException) {
        call.respondText(
            Pages.errorPage("请求被拒绝", error.message ?: "无效请求"),
            ContentType.Text.Html,
            status = HttpStatusCode.BadRequest,
        )
    }
}

private fun jsonStringPlain(value: String): String =
    buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

private suspend fun RoutingContext.jsonError(status: HttpStatusCode, message: String) {
    call.respondText(
        """{"error":${jsonStringPlain(message)}}""",
        ContentType.Application.Json,
        status,
    )
}

fun Application.configureWeb(context: WebContext) {
    install(ContentNegotiation) {
        json(systemJson)
    }
    install(StatusPages) {
        exception<LineageException> { call, cause ->
            call.respondText(
                """{"error":${jsonStringPlain(cause.message ?: "请求被拒绝")}}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
        }
    }
    routing {
        get("/") {
            call.respondText(Pages.home(context.repo), ContentType.Text.Html)
        }
        get("/runs/{id}") {
            val id = call.parameters["id"]!!
            val run = context.repo.findRun(id)
            if (run == null) {
                call.respondText("运行不存在", status = HttpStatusCode.NotFound)
            } else {
                val trace = context.lineageQuery.trace(id)
                val report = context.service.verify(id)
                call.respondText(Pages.runDetail(run, trace, report, context.repo), ContentType.Text.Html)
            }
        }

        post("/api/templates") {
            val raw = call.receiveText()
            rejectNonFiniteLiterals(raw)
            call.respond(context.service.createTemplate(decodeJson(raw)))
        }
        get("/api/templates") { call.respond(context.repo.listTemplates()) }

        post("/api/runs") {
            val raw = call.receiveText()
            rejectNonFiniteLiterals(raw)
            call.respond(context.service.freezeRun(decodeJson<RunParams>(raw)))
        }
        get("/api/runs") { call.respond(context.repo.listRuns()) }
        get("/api/runs/{id}") {
            val run = context.repo.findRun(call.parameters["id"]!!)
            if (run == null) call.respondText("运行不存在", status = HttpStatusCode.NotFound)
            else call.respond(run)
        }
        post("/api/runs/{id}/advance") {
            val raw = call.receiveText()
            val request = if (raw.isBlank()) AdvanceParams() else decodeJson<AdvanceParams>(raw)
            try {
                call.respond(context.service.advance(call.parameters["id"]!!, request))
            } catch (error: ConflictException) {
                jsonError(HttpStatusCode.Conflict, error.message ?: "版本冲突")
            }
        }
        post("/api/runs/{id}/fail") {
            val raw = call.receiveText()
            val request = if (raw.isBlank()) InjectFailureParams() else decodeJson<InjectFailureParams>(raw)
            try {
                call.respond(context.service.injectFailure(call.parameters["id"]!!, request))
            } catch (error: ConflictException) {
                jsonError(HttpStatusCode.Conflict, error.message ?: "版本冲突")
            }
        }
        post("/api/runs/derive") {
            val raw = call.receiveText()
            rejectNonFiniteLiterals(raw)
            call.respond(context.service.derive(decodeJson<DeriveParams>(raw)))
        }
        get("/api/runs/{id}/verify") {
            call.respond(context.service.verify(call.parameters["id"]!!))
        }
        get("/api/runs/{id}/lineage") {
            call.respond(context.lineageQuery.trace(call.parameters["id"]!!))
        }
        get("/api/manifest/export") {
            val ids = call.request.queryParameters.getAll("runId")
            call.respond(context.manifests.export(ids))
        }
        post("/api/manifest/import") {
            val raw = call.receiveText()
            rejectNonFiniteLiterals(raw)
            call.respond(context.manifests.reimport(decodeJson<Manifest>(raw)))
        }

        post("/forms/templates") {
            formAction {
                val params = call.receiveParameters()
                val raw = params["json"] ?: throw LineageException("缺少模板 JSON")
                rejectNonFiniteLiterals(raw)
                context.service.createTemplate(decodeJson<TemplateSpec>(raw))
                "/"
            }
        }
        post("/forms/runs") {
            formAction {
                val params = call.receiveParameters()
                val raw = params["json"] ?: throw LineageException("缺少运行 JSON")
                rejectNonFiniteLiterals(raw)
                val run = context.service.freezeRun(decodeJson<RunParams>(raw))
                "/runs/${run.id}"
            }
        }
        post("/forms/runs/{id}/advance") {
            val id = call.parameters["id"]!!
            formAction {
                val params = call.receiveParameters()
                val expectedVersion = params["expectedVersion"]?.toIntOrNull()
                val crash = params["crashAfterIntent"] == "1"
                context.service.advance(id, AdvanceParams(expectedVersion, crash))
                "/runs/$id"
            }
        }
        post("/forms/runs/{id}/fail") {
            val id = call.parameters["id"]!!
            formAction {
                val params = call.receiveParameters()
                val expectedVersion = params["expectedVersion"]?.toIntOrNull()
                context.service.injectFailure(id, InjectFailureParams(expectedVersion = expectedVersion))
                "/runs/$id"
            }
        }
        post("/forms/derive") {
            formAction {
                val params = call.receiveParameters()
                val raw = params["json"] ?: throw LineageException("缺少派生 JSON")
                rejectNonFiniteLiterals(raw)
                val run = context.service.derive(decodeJson<DeriveParams>(raw))
                "/runs/${run.id}"
            }
        }
        post("/forms/manifest/import") {
            formAction {
                val params = call.receiveParameters()
                val raw = params["manifest"] ?: throw LineageException("缺少 manifest 内容")
                rejectNonFiniteLiterals(raw)
                context.manifests.reimport(decodeJson<Manifest>(raw))
                "/"
            }
        }
    }
}