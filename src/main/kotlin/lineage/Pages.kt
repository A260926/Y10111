package lineage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.request.receiveMultipart
import io.ktor.http.content.PartData
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.readText
import kotlinx.serialization.json.Json

private const val TITLE = "实验运行谱系库"

private fun page(title: String, body: String): String = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$title · $TITLE</title>
<style>
:root { --bg:#f5f7fa; --card:#fff; --ink:#1f2933; --muted:#64717f; --line:#dce3ea;
  --blue:#2563eb; --green:#15803d; --red:#b91c1c; --amber:#a16207; }
* { box-sizing:border-box; }
body { margin:0; font-family:-apple-system,"PingFang SC","Microsoft YaHei",Segoe UI,sans-serif;
  background:var(--bg); color:var(--ink); line-height:1.55; }
header { background:#0f2742; color:#fff; padding:14px 24px; }
header h1 { margin:0; font-size:20px; }
header a { color:#bcd4f2; text-decoration:none; margin-right:14px; font-size:14px; }
main { max-width:1080px; margin:24px auto; padding:0 16px; }
.card { background:var(--card); border:1px solid var(--line); border-radius:10px;
  padding:16px 18px; margin-bottom:18px; }
h2 { margin:0 0 12px; font-size:17px; }
h3 { margin:14px 0 8px; font-size:15px; }
table { border-collapse:collapse; width:100%; font-size:13px; }
th,td { border-bottom:1px solid var(--line); padding:7px 8px; text-align:left; vertical-align:top; }
th { color:var(--muted); font-weight:600; white-space:nowrap; }
code,.mono { font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:12px;
  background:#f0f3f7; padding:1px 5px; border-radius:4px; word-break:break-all; }
.badge { display:inline-block; padding:1px 8px; border-radius:99px; font-size:12px; }
.b-SUCCESS,.b-completed,.b-ok { background:#dcfce7; color:var(--green); }
.b-FAILED,.b-failed,.b-err { background:#fee2e2; color:var(--red); }
.b-INTENT,.b-running { background:#fef3c7; color:var(--amber); }
.b-PENDING,.b-frozen,.b-in_progress { background:#e5eaf0; color:var(--muted); }
.b-reused { background:#e0e7ff; color:#3730a3; }
button { background:var(--blue); color:#fff; border:0; border-radius:7px; padding:7px 14px;
  font-size:13px; cursor:pointer; }
button.secondary { background:#475569; }
button.danger { background:var(--red); }
input,textarea,select { width:100%; padding:7px 9px; border:1px solid var(--line);
  border-radius:7px; font-size:13px; font-family:inherit; }
label { display:block; font-size:12px; color:var(--muted); margin:8px 0 3px; }
.row { display:flex; gap:12px; flex-wrap:wrap; }
.row > div { flex:1; min-width:200px; }
.flash { background:#ecfdf5; border:1px solid #86efac; color:#166534; padding:8px 12px;
  border-radius:8px; margin-bottom:14px; font-size:13px; }
.errorbox { background:#fef2f2; border:1px solid #fca5a5; color:#991b1b; padding:8px 12px;
  border-radius:8px; margin-bottom:14px; font-size:13px; }
.muted { color:var(--muted); font-size:12px; }
pre { background:#0f2742; color:#d8e6f8; padding:12px; border-radius:8px; overflow:auto;
  font-size:12px; max-height:360px; }
form.inline { display:inline; }
.small { font-size:12px; }
</style>
</head>
<body>
<header>
  <h1>$TITLE</h1>
  <nav style="margin-top:6px">
    <a href="/">运行总览</a>
    <a href="/templates">实验模板</a>
    <a href="/api/export">导出 manifest</a>
    <a href="/import">导入 / 重放验证</a>
  </nav>
</header>
<main>
$body
</main>
</body>
</html>"""

private fun esc(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;")

private fun urlEnc(s: String): String =
    java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

private fun badge(status: String): String {
    val cls = when {
        status.startsWith("reused") -> "b-reused"
        else -> "b-$status"
    }
    return """<span class="badge $cls">${esc(status)}</span>"""
}

fun Routing.webRoutes(svc: Services) {
    val repo = Repo(svc.store)

    get("/") {
        val runs = svc.store.tx(immediate = false) { conn -> repo.listRuns(conn) }
        val templates = svc.store.tx(immediate = false) { conn -> repo.listTemplates(conn) }
        val tplName = templates.associate { it.id to it.name }
        val rows = runs.joinToString("\n") { r ->
            val parent = if (r.parentRunId != null)
                """<a href="/runs/${esc(r.parentRunId)}">${esc(r.parentRunId.take(14))}</a>"""
            else "<span class=\"muted\">—</span>"
            """<tr>
              <td><a href="/runs/${esc(r.id)}"><code>${esc(r.id)}</code></a><br>
                <span class="muted">${esc(r.name)}</span></td>
              <td>${esc(tplName[r.templateId] ?: r.templateId)}<br><span class="muted">v${r.templateVersion}</span></td>
              <td>${badge(r.status)}<br><span class="muted">游标 ${r.cursor} · cv ${r.cursorVersion}</span></td>
              <td><code>${esc(Canonical.short(r.paramsDigest))}</code></td>
              <td><code>${esc(Canonical.short(r.inputDigest))}</code></td>
              <td><code>${esc(Canonical.short(r.envFingerprint))}</code></td>
              <td>$parent</td>
            </tr>"""
        }
        val body = """
        <div class="card">
          <h2>创建实验模板</h2>
          <form method="post" action="/ui/templates">
            <div class="row">
              <div><label>模板名称</label><input name="name" value="自定义实验模板" required></div>
              <div><label>secret 参数键（逗号分隔，不被阶段消费）</label><input name="secrets" value="api_key"></div>
            </div>
            <div class="muted" style="margin-top:6px">不填阶段则使用内置三阶段：extract / transform / evaluate（仅 evaluate 环境敏感）。</div>
            <div style="margin-top:10px"><button type="submit">创建模板（冻结版本 v1）</button></div>
          </form>
        </div>
        <div class="card">
          <h2>冻结新运行</h2>
          <form method="post" action="/ui/runs/freeze">
            <div class="row">
              <div><label>模板</label><select name="templateId">
                ${templates.joinToString("") { """<option value="${esc(it.id)}">${esc(it.name)} (${esc(it.id)} v${it.templateVersion})</option>""" }}
              </select></div>
              <div><label>运行名称</label><input name="name" value="新实验运行" required></div>
              <div><label>幂等键（可选）</label><input name="idempotencyKey" placeholder="相同键+相同内容返回原运行"></div>
            </div>
            <label>参数 JSON（NaN / Infinity 会被拒绝；键序与空白不影响身份；1.0 与 1 等价）</label>
            <textarea name="params" rows="4">{ "sample_rate": 0.10, "feature_set": "resnet-base", "metric": "top1", "api_key": "sk-change-me", "batch_tag": "manual-001" }</textarea>
            <label>输入数据 JSON（冻结后计算摘要）</label>
            <textarea name="input" rows="3">{ "rows": [ {"id": "img-0001"}, {"id": "img-0002"} ], "dataset": "demo-images" }</textarea>
            <label>环境声明覆盖（每行 key=value，如 cluster.id=cluster-a）</label>
            <input name="env" value="cluster.id=cluster-a">
            <div style="margin-top:10px"><button type="submit">冻结运行</button></div>
          </form>
        </div>
        <div class="card">
          <h2>运行（${runs.size}）</h2>
          <table>
            <tr><th>运行</th><th>模板</th><th>状态</th><th>参数摘要</th><th>输入摘要</th><th>环境指纹</th><th>父运行</th></tr>
            $rows
          </table>
        </div>"""
        call.respondText(page("运行总览", body), io.ktor.http.ContentType.Text.Html)
    }

    get("/templates") {
        val templates = svc.store.tx(immediate = false) { conn -> repo.listTemplates(conn) }
        val cards = templates.joinToString("\n") { t ->
            val stages = t.stages.mapIndexed { si, s -> si to s }.joinToString("") { (si, s) ->
                """<tr><td>$si</td><td><code>${esc(s.name)}</code></td>
                <td>v${s.stageVersion}</td>
                <td>${s.consumes.joinToString { "<code>${esc(it)}</code>" }.ifEmpty { "<span class=muted>无</span>" }}</td>
                <td>${if (s.envSensitive) "是" else "否"}</td></tr>"""
            }
            """<div class="card">
              <h2>${esc(t.name)} <span class="muted"><code>${esc(t.id)}</code> · 模板版本 v${t.templateVersion}</span></h2>
              <p class="muted">secret 键：${t.secrets.joinToString { "<code>${esc(it)}</code>" }.ifEmpty { "无" }}（其值不会进入导出 manifest）</p>
              <table>
                <tr><th>#</th><th>阶段</th><th>阶段版本</th><th>消费参数</th><th>环境敏感</th></tr>
                $stages
              </table>
            </div>"""
        }
        call.respondText(page("实验模板", cards.ifEmpty { "<div class='card'>暂无模板</div>" }),
            io.ktor.http.ContentType.Text.Html)
    }

    post("/ui/templates") {
        val form = call.receiveParameters()
        val secrets = form["secrets"].orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val t = svc.engine.createTemplate(
            CreateTemplateRequest(name = form["name"]!!, secrets = secrets)
        )
        call.respondRedirect("/templates?msg=${urlEnc("已创建模板 ${t.id}（版本已固定）")}")
    }

    post("/ui/runs/freeze") {
        val form = call.receiveParameters()
        val env = parseEnvLines(form["env"].orEmpty())
        val (run, reused) = svc.engine.freeze(
            FreezeRequest(
                templateId = form["templateId"]!!,
                name = form["name"]!!,
                params = form["params"].orEmpty().ifBlank { "{}" },
                input = form["input"].orEmpty().ifBlank { "{}" },
                env = env,
                idempotencyKey = form["idempotencyKey"]?.takeIf { it.isNotBlank() },
            )
        )
        val msg = if (reused) "幂等键命中：返回原运行（未创建新运行）" else "运行已冻结：模板版本/参数/输入/环境均已固定"
        call.respondRedirect("/runs/${run.id}?msg=${urlEnc(msg)}")
    }

    get("/runs/{id}") {
        val runId = call.parameters["id"]!!
        val msg = call.request.queryParameters["msg"]
        val err = call.request.queryParameters["err"]
        val data = svc.store.tx(immediate = false) { conn ->
            val run = repo.getRun(conn, runId) ?: throw NotFoundException("运行不存在: $runId")
            val template = repo.getTemplate(conn, run.templateId)
            Triple(run, template, repo.listStages(conn, runId))
        }
        val (run, template, stages) = data
        val paramsEl = Canonical.parse(run.paramsCanonical) as JsonObj
        val secretKeys = template?.secrets.orEmpty().toSet()
        val paramRows = paramsEl.entries.toSortedMap().entries.joinToString("") { (k, v) ->
            val secret = k in secretKeys
            val shown = when {
                secret -> "••••••（导出时脱敏）"
                v is JsonStr -> "\"${esc(v.value)}\""
                else -> esc(Canonical.render(v))
            }
            """<tr><td><code>${esc(k)}</code>${if (secret) " <span class='badge b-reused'>secret</span>" else ""}</td>
            <td><code>$shown</code></td></tr>"""
        }
        val changedKeys = Json { ignoreUnknownKeys = true }.decodeFromString(
            ListSerializer(String.serializer()),
            run.changedKeys,
        )

        val stageRows = stages.joinToString("") { st ->
            val sourceBadge = when {
                st.source.startsWith("reused:") -> """ <span class="badge b-reused">复用</span>"""
                st.source == "computed" -> """ <span class="badge b-PENDING">本运行计算</span>"""
                st.source == "failed" -> """ <span class="badge b-FAILED">失败</span>"""
                else -> ""
            }
            """<tr>
              <td>${st.stageIndex}</td>
              <td><code>${esc(st.stageName)}</code> v${st.stageVersion}$sourceBadge</td>
              <td>${badge(st.status.name)} <span class="muted">尝试 ${st.attempt}</span></td>
              <td>${st.intentId?.let { "<code>${esc(Canonical.short(it))}</code>" } ?: "<span class=muted>—</span>"}</td>
              <td>${st.artifactDigest?.let { "<code>${esc(Canonical.short(it))}</code>" } ?: "<span class=muted>—</span>"}</td>
              <td>${esc(st.reason ?: "")}</td>
              <td class="small">
                <form class="inline" method="post" action="/ui/runs/${esc(run.id)}/advance?cv=${run.cursorVersion}">
                  <button type="submit" ${if (st.status == StageStatus.SUCCESS || st.stageIndex != run.cursor) "disabled style='opacity:.5'" else ""}>推进</button>
                </form>
                <form class="inline" method="post" action="/ui/runs/${esc(run.id)}/inject?stage=${st.stageIndex}&crash=false">
                  <button class="danger" type="submit" ${if (st.stageIndex != run.cursor) "disabled style='opacity:.5'" else ""}>注失败</button>
                </form>
                <form class="inline" method="post" action="/ui/runs/${esc(run.id)}/inject?stage=${st.stageIndex}&crash=true">
                  <button class="secondary" type="submit" ${if (st.stageIndex != run.cursor) "disabled style='opacity:.5'" else ""}>注崩溃</button>
                </form>
              </td>
            </tr>"""
        }

        val verify = try { svc.lineage.verify(runId) } catch (e: Throwable) { null }
        val chain = svc.lineage.lineageChain(runId)
        val chainItems = chain.drop(1).map { c ->
            val ck = Json { ignoreUnknownKeys = true }.decodeFromString(
                ListSerializer(String.serializer()),
                c.changedKeys,
            )
            val diffText = if (ck.isNotEmpty())
                ", 改动: " + ck.joinToString { k -> "<code>" + esc(k) + "</code>" } else ""
            """<a href="/runs/${esc(c.id)}">${esc(c.name)}</a> """ +
                """<span class="muted">(${esc(c.id.take(12))}$diffText)</span>"""
        }
        val lineageHtml = if (chainItems.isNotEmpty()) {
            "<div class=\"card\"><h2>谱系回溯（沿最终结果回到每个父运行）</h2>" +
                "<p>当前运行 ← " + chainItems.joinToString(" ← ") + "</p></div>"
        } else "<div class=\"card\"><h2>谱系回溯</h2><p class=\"muted\">这是根运行，无父运行。可在下方派生新运行。</p></div>"

        val verifyHtml = verify?.let { v ->
            val checks = v.checks.joinToString("") { "<li>${esc(it)}</li>" }
            """<div class="card"><h2>重现性验证</h2>
            <p>${if (v.reproducible) """<span class="badge b-ok">可重现</span>""" else """<span class="badge b-err">不可重现</span>"""}
            <strong>${esc(v.verdict)}</strong></p><ul class="small">$checks</ul></div>"""
        } ?: ""

        val body = """
        ${if (msg != null) "<div class='flash'>${esc(msg)}</div>" else ""}
        ${if (err != null) "<div class='errorbox'>${esc(err)}</div>" else ""}
        <div class="card">
          <h2>${esc(run.name)} <span class="muted"><code>${esc(run.id)}</code></span></h2>
          <p>
            模板 <code>${esc(run.templateId)}</code> v${run.templateVersion} ·
            状态 ${badge(run.status)} · 游标 ${run.cursor}/${template?.stages?.size ?: "?"} ·
            cursorVersion ${run.cursorVersion}<br>
            参数摘要 <code>${esc(run.paramsDigest)}</code><br>
            输入摘要 <code>${esc(run.inputDigest)}</code><br>
            环境指纹 <code>${esc(run.envFingerprint)}</code><br>
            父运行 ${run.parentRunId?.let { """<a href="/runs/${esc(it)}"><code>${esc(it)}</code></a>""" } ?: "<span class=muted>无（根运行）</span>"}
            ${if (changedKeys.isNotEmpty()) "<br>相对父运行的改动：" + changedKeys.joinToString { "<code>${esc(it)}</code>" } else ""}
          </p>
        </div>
        $verifyHtml
        $lineageHtml
        <div class="card">
          <h2>阶段检查点（intent → 产物摘要 + receipt → 推进游标）</h2>
          <table>
            <tr><th>#</th><th>阶段</th><th>状态</th><th>intent</th><th>产物摘要</th><th>原因/复用说明</th><th>操作</th></tr>
            $stageRows
          </table>
          <p class="muted">「注失败」产生 FAILED 检查点，后续重试不会把它当成功缓存；
          「注崩溃」模拟进程在 intent 落盘后退出，再次点击「推进」将从同一 intent 恢复且不重复产物。</p>
        </div>
        <div class="card">
          <h2>参数（冻结时规范化，键序/空白/浮点等价已收敛）</h2>
          <table><tr><th>键</th><th>规范化值</th></tr>$paramRows</table>
        </div>
        <div class="card">
          <h2>派生新运行</h2>
          <form method="post" action="/ui/runs/${esc(run.id)}/derive">
            <div class="row">
              <div><label>新运行名称</label><input name="name" value="${esc(run.name)}（派生）"></div>
              <div><label>幂等键（可选）</label><input name="idempotencyKey"></div>
            </div>
            <label>改动参数（JSON 对象，仅写要覆盖的顶层键；NaN/Infinity 拒绝）</label>
            <textarea name="params" rows="3">{ "batch_tag": "rerun-002" }</textarea>
            <label>环境声明覆盖（每行 key=value；环境敏感阶段指纹变化会阻止复用）</label>
            <input name="env" value="cluster.id=cluster-b">
            <div style="margin-top:10px"><button type="submit">派生（自动复用一致检查点）</button></div>
          </form>
        </div>"""
        call.respondText(page(run.name, body), io.ktor.http.ContentType.Text.Html)
    }

    post("/ui/runs/{id}/advance") {
        val runId = call.parameters["id"]!!
        val cv = call.request.queryParameters["cv"]?.toLongOrNull()
        try {
            svc.engine.advance(runId, cv)
            call.respondRedirect("/runs/$runId?msg=${urlEnc("阶段已推进（intent→产物/receipt→游标）")}")
        } catch (e: ConflictException) {
            call.respondRedirect("/runs/$runId?err=${urlEnc(e.message ?: "版本冲突")}")
        } catch (e: SimulatedCrashException) {
            call.respondRedirect("/runs/$runId?err=${urlEnc(e.message!!)}")
        }
    }

    post("/ui/runs/{id}/inject") {
        val runId = call.parameters["id"]!!
        val stage = call.request.queryParameters["stage"]!!.toInt()
        val crash = call.request.queryParameters["crash"]?.toBooleanStrictOrNull() ?: true
        val reason = if (crash) "页面注入：模拟进程崩溃" else "页面注入：阶段失败"
        try {
            svc.engine.attachInjection(runId, stage, reason, crash)
            svc.engine.advance(runId)
            call.respondRedirect("/runs/$runId?msg=${urlEnc("注入的失败已发生，检查点标记为 FAILED")}")
        } catch (e: SimulatedCrashException) {
            call.respondRedirect("/runs/$runId?err=${urlEnc(e.message!! + " — 重新打开此页并点击推进即可从 intent 恢复")}")
        }
    }

    post("/ui/runs/{id}/derive") {
        val parentId = call.parameters["id"]!!
        val form = call.receiveParameters()
        try {
            val (child, reused) = svc.lineage.derive(
                DeriveRequest(
                    parentRunId = parentId,
                    name = form["name"]!!,
                    params = form["params"]?.takeIf { it.isNotBlank() },
                    env = parseEnvLines(form["env"].orEmpty()),
                    idempotencyKey = form["idempotencyKey"]?.takeIf { it.isNotBlank() },
                )
            )
            call.respondRedirect("/runs/${child.id}?msg=${urlEnc(
                if (reused) "幂等键命中：返回原派生运行" else "派生完成：一致检查点已复用，其余待推进"
            )}")
        } catch (e: Exception) {
            call.respondRedirect("/runs/$parentId?err=${urlEnc(e.message ?: "派生失败")}")
        }
    }

    get("/import") {
        val body = """
        <div class="card">
          <h2>导入 manifest 并重放验证</h2>
          <p class="muted">从 <a href="/api/export">/api/export</a> 下载的 manifest 不包含 secret 参数值；
          导入会校验输入摘要、环境指纹、产物摘要链与 receipt 链。</p>
          <form method="post" action="/ui/import" enctype="multipart/form-data">
            <input type="file" name="manifest" accept="application/json,.json" required>
            <div style="margin-top:10px"><button type="submit">导入并验证</button></div>
          </form>
        </div>"""
        call.respondText(page("导入验证", body), io.ktor.http.ContentType.Text.Html)
    }

    post("/ui/import") {
        val multipart = call.receiveMultipart()
        var raw: String? = null
        while (true) {
            val part = multipart.readPart() ?: break
            try {
                if (part is PartData.FileItem) {
                    raw = part.provider().readRemaining().readText()
                }
            } finally {
                part.dispose.invoke()
            }
        }
        val result = try {
            svc.exportImport.importManifest(raw ?: throw LineageException("未收到 manifest 文件"))
        } catch (e: Exception) {
            val body = "<div class='errorbox'>${esc(e.message)}</div><p><a href='/import'>返回</a></p>"
            call.respondText(page("导入验证", body), io.ktor.http.ContentType.Text.Html)
            return@post
        }
        val checks = result.checks.joinToString("") { "<li>${esc(it)}</li>" }
        val errors = result.errors.joinToString("") { "<li>${esc(it)}</li>" }
        val body = """
        <div class="card">
          <h2>导入结果 ${if (result.verifiable) """<span class="badge b-ok">验证通过</span>""" else """<span class="badge b-err">存在问题</span>"""}</h2>
          <p>新导入模板 ${result.importedTemplates} 个，新导入运行 ${result.importedRuns} 个。</p>
          <h3>校验项</h3><ul class="small">$checks</ul>
          ${if (errors.isNotEmpty()) "<h3>错误</h3><ul class='small'>$errors</ul>" else ""}
          <p><a href="/">返回运行总览</a></p>
        </div>"""
        call.respondText(page("导入验证", body), io.ktor.http.ContentType.Text.Html)
    }
}

private fun parseEnvLines(raw: String): Map<String, String> =
    raw.split("\n", "，", ",", ";").mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val idx = trimmed.indexOf('=')
        if (idx <= 0) throw LineageException("环境声明格式应为 key=value：\"$trimmed\"")
        trimmed.substring(0, idx).trim() to trimmed.substring(idx + 1).trim()
    }.toMap()
