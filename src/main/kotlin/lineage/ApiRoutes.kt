package lineage

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Routing.apiRoutes(svc: Services) {
    val repo = Repo(svc.store)

    get("/api/templates") {
        val templates = svc.store.tx(immediate = false) { conn -> repo.listTemplates(conn) }
        call.respond(templates)
    }

    post("/api/templates") {
        val req = call.receive<CreateTemplateRequest>()
        call.respond(HttpStatusCode.Created, svc.engine.createTemplate(req))
    }

    post("/api/runs/freeze") {
        val req = call.receive<FreezeRequest>()
        val (run, reused) = svc.engine.freeze(req)
        call.respond(
            if (reused) HttpStatusCode.OK else HttpStatusCode.Created,
            ExportImport.toRunResponse(run, idempotentReuse = reused),
        )
    }

    post("/api/runs/derive") {
        val req = call.receive<DeriveRequest>()
        val (run, reused) = svc.lineage.derive(req)
        call.respond(
            if (reused) HttpStatusCode.OK else HttpStatusCode.Created,
            ExportImport.toRunResponse(run, idempotentReuse = reused),
        )
    }

    post("/api/runs/{id}/advance") {
        val runId = call.parameters["id"]!!
        val expectedVersion = call.request.queryParameters["cursorVersion"]?.toLongOrNull()
        val (run, stage) = svc.engine.advance(runId, expectedVersion)
        call.respond(
            AdvanceResponse(
                run = ExportImport.toRunResponse(run),
                stage = ExportImport.toStageResponse(stage),
            )
        )
    }

    post("/api/runs/{id}/inject-failure") {
        val runId = call.parameters["id"]!!
        val idx = call.request.queryParameters["stage"]?.toIntOrNull()
            ?: throw LineageException("需要查询参数 stage=<阶段下标>")
        val crash = call.request.queryParameters["crash"]?.toBooleanStrictOrNull() ?: true
        val reason = call.request.queryParameters["reason"] ?: "manual injection"
        val armOnly = call.request.queryParameters["armOnly"]?.toBooleanStrictOrNull() ?: true
        // armOnly=true（默认）：仅武装注入，下一次推进时触发；
        // armOnly=false：立刻推进一次（武装一次性，失败/崩溃在该次推进发生）。
        svc.engine.attachInjection(runId, idx, reason, crash)
        if (!armOnly) {
            val outcome = try {
                svc.engine.advance(runId)
            } catch (_: SimulatedCrashException) {
                null
            }
            if (outcome == null) {
                call.respond(InjectionResponse(true, runId, idx, crash, armed = false))
            } else {
                val (run, stage) = outcome
                call.respond(
                    AdvanceResponse(
                        run = ExportImport.toRunResponse(run),
                        stage = ExportImport.toStageResponse(stage),
                    )
                )
            }
        } else {
            call.respond(InjectionResponse(true, runId, idx, crash, armed = true))
        }
    }

    get("/api/runs/{id}/verify") {
        val result = svc.lineage.verify(call.parameters["id"]!!)
        call.respond(
            VerifyResponse(
                runId = call.parameters["id"]!!,
                reproducible = result.reproducible,
                finalArtifact = result.finalArtifact,
                checks = listOf(result.verdict) + result.checks,
            )
        )
    }

    get("/api/runs/{id}/lineage") {
        val chain = svc.lineage.lineageChain(call.parameters["id"]!!)
        call.respond(chain.map { ExportImport.toRunResponse(it) })
    }

    get("/api/runs") {
        val runs = svc.store.tx(immediate = false) { conn -> repo.listRuns(conn) }
        call.respond(runs.map { ExportImport.toRunResponse(it) })
    }

    get("/api/runs/{id}") {
        val run = svc.store.tx(immediate = false) { conn -> repo.getRun(conn, call.parameters["id"]!!) }
            ?: throw NotFoundException("运行不存在: ${call.parameters["id"]}")
        val stages = svc.store.tx(immediate = false) { conn -> repo.listStages(conn, run.id) }
        call.respond(
            RunDetailResponse(
                run = ExportImport.toRunResponse(run),
                stages = stages.map { ExportImport.toStageResponse(it) },
            )
        )
    }

    get("/api/export") {
        val manifest = svc.exportImport.export()
        call.response.headers.append(
            io.ktor.http.HttpHeaders.ContentDisposition,
            "attachment; filename=lineage-manifest.json",
        )
        call.respondText(manifest, ContentType.Application.Json)
    }

    post("/api/import") {
        val raw = call.receiveText()
        val result = svc.exportImport.importManifest(raw)
        val status = if (result.verifiable) HttpStatusCode.OK else HttpStatusCode.Conflict
        call.respond(
            status,
            ImportResponse(
                importedTemplates = result.importedTemplates,
                importedRuns = result.importedRuns,
                verifiable = result.verifiable,
                checks = result.checks,
                errors = result.errors,
            )
        )
    }
}
