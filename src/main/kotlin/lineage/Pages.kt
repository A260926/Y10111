package lineage

object Pages {

    private fun escape(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private const val STYLE = """
        body { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
               margin: 24px; color: #1f2933; background: #fafafa; }
        h1 { font-size: 24px; } h2 { margin-top: 28px; font-size: 18px; }
        table { border-collapse: collapse; background: white; margin: 8px 0; }
        th, td { border: 1px solid #d6dae0; padding: 6px 10px; font-size: 13px;
                 vertical-align: top; text-align: left; }
        th { background: #eef2f6; }
        pre { background: #f0f2f5; padding: 8px; border-radius: 6px;
              font-size: 12px; overflow-x: auto; white-space: pre-wrap; word-break: break-all; }
        textarea { width: 100%; max-width: 760px; height: 180px; font-family: ui-monospace, monospace; }
        .ok { color: #1a7f37; font-weight: 600; }
        .bad { color: #c0392b; font-weight: 600; }
        .muted { color: #697586; }
        .tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 12px; }
        .tag-succeeded { background: #d9f2e0; color: #1a7f37; }
        .tag-failed { background: #fbe1de; color: #c0392b; }
        .tag-running { background: #e4ecfb; color: #2b50aa; }
        .tag-pending { background: #eceff3; color: #697586; }
        .card { background: white; border: 1px solid #d6dae0; border-radius: 8px; padding: 16px; margin: 12px 0; }
        button { padding: 6px 14px; margin-right: 8px; }
        a { color: #2b50aa; }
    """

    fun home(repo: Repository): String {
        val templates = repo.listTemplates()
        val runs = repo.listRuns()
        return """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
        <title>实验运行谱系库</title><style>$STYLE</style></head><body>
        <h1>实验运行谱系库</h1>
        <p class="muted">记录数据集摘要、参数规范化、环境指纹、阶段检查点与派生关系；回答“结果能否从相同输入重现”。</p>

        <h2>实验模板（创建时冻结版本）</h2>
        <table>
          <tr><th>ID</th><th>名称</th><th>版本</th><th>阶段</th><th>specHash</th></tr>
          ${
            templates.joinToString("") { t ->
                """<tr><td>${escape(t.id)}</td><td>${escape(t.name)}</td><td>v${t.version}</td>
                <td>${t.stages.joinToString("<br>") { escape("${it.name}@${it.version}") }}</td>
                <td><code>${escape(t.specHash.take(16))}</code></td></tr>"""
            }.ifEmpty { "<tr><td colspan=5 class=muted>暂无模板</td></tr>" }
          }
        </table>
        <div class="card">
          <h3>创建实验模板</h3>
          <form method="post" action="/forms/templates">
            <textarea name="json">{
  "name": "示例模板",
  "description": "两阶段",
  "paramFields": [
    {"name": "alpha", "type": "double"},
    {"name": "note", "type": "string", "required": false}
  ],
  "stages": [
    {"name": "prepare", "version": "prepare-1.0.0", "declaresParams": ["alpha"]},
    {"name": "report", "version": "report-1.0.0", "declaresParams": ["note"]}
  ]
}</textarea><br>
            <button type="submit">创建模板</button>
          </form>
        </div>

        <h2>运行</h2>
        <table>
          <tr><th>运行</th><th>模板</th><th>状态</th><th>游标</th><th>父运行</th><th>环境指纹</th></tr>
          ${
            runs.joinToString("") { r ->
                """<tr><td><a href="/runs/${escape(r.id)}">${escape(r.displayName)}</a>
                  <div class=muted><code>${escape(r.id)}</code></div></td>
                  <td>${escape(r.templateId)}<br>v${r.templateVersion}</td>
                  <td><span class="tag tag-${escape(r.status)}">${escape(r.status)}</span></td>
                  <td>${r.cursor}</td>
                  <td>${r.parentRunId?.let { "<a href='/runs/${escape(it)}'>${escape(it)}</a>" } ?: "—"}</td>
                  <td><code>${escape(r.envFingerprint.take(16))}</code></td></tr>"""
            }.ifEmpty { "<tr><td colspan=6 class=muted>暂无运行</td></tr>" }
          }
        </table>

        <div class="card">
          <h3>冻结新运行</h3>
          <form method="post" action="/forms/runs">
            <textarea name="json">{
  "templateId": "${templates.firstOrNull()?.id ?: "tpl-id"}",
  "displayName": "手动运行",
  "params": {"dataset": "demo", "learningRate": 0.01, "epochs": 3, "token": "secret-value"},
  "env": {"OS": "linux", "BLAS_VERSION": "openblas-0.3.21"},
  "inputSummary": {"dataset": "demo", "rows": 1000},
  "idempotencyKey": "optional-key"
}</textarea><br>
            <button type="submit">冻结运行</button>
          </form>
        </div>

        <div class="card">
          <h3>派生新运行（只复用输入摘要、阶段版本、声明环境与上游产物都一致的检查点）</h3>
          <form method="post" action="/forms/derive">
            <textarea name="json">{
  "parentRunId": "${runs.firstOrNull()?.id ?: "run-id"}",
  "displayName": "派生运行",
  "changedParams": {"epochs": 20},
  "env": {"OS": "linux", "BLAS_VERSION": "openblas-0.3.27"}
}</textarea><br>
            <button type="submit">派生</button>
          </form>
        </div>

        <div class="card">
          <h3>导出 / 重新导入 manifest（secret 值不会出现）</h3>
          <p><a href="/api/manifest/export" target="_blank">导出当前全部谱系 manifest</a></p>
          <form method="post" action="/forms/manifest/import">
            <textarea name="manifest" placeholder="粘贴 manifest JSON 后导入并验证"></textarea><br>
            <button type="submit">导入并验证</button>
          </form>
        </div>
        </body></html>
        """.trimIndent()
    }

    fun runDetail(run: Run, trace: LineageTrace, report: VerifyReport, @Suppress("UNUSED_PARAMETER") repo: Repository): String {
        return """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
        <title>运行 ${escape(run.id)} · 实验运行谱系库</title><style>$STYLE</style></head><body>
        <p><a href="/">← 返回实验运行谱系库</a></p>
        <h1>${escape(run.displayName)}</h1>
        <p class="muted"><code>${escape(run.id)}</code> · 模板 <code>${escape(run.templateId)}</code> v${run.templateVersion}
           · 乐观版本 <b>${run.version}</b></p>

        <div class="card">
          <strong>启动时冻结的身份信息</strong>
          <table>
            <tr><th>状态</th><td><span class="tag tag-${escape(run.status)}">${escape(run.status)}</span> 游标 ${run.cursor}</td></tr>
            <tr><th>参数规范化</th><td><pre>${escape(run.paramsCanonical)}</pre>
                参数哈希 <code>${escape(run.paramsHash)}</code></td></tr>
            <tr><th>环境指纹</th><td><pre>${escape(run.envCanonical)}</pre>
                指纹 <code>${escape(run.envFingerprint)}</code></td></tr>
            <tr><th>输入数据摘要</th><td><pre>${escape(run.inputCanonical)}</pre>
                摘要哈希 <code>${escape(run.inputHash)}</code></td></tr>
          </table>
        </div>

        ${
            if (run.parentRunId != null) {
                """<div class="card"><strong>派生改动参数</strong>
                <table><tr><th>参数</th><th>旧值 → 新值</th></tr>
                ${run.changedParams.entries.joinToString("") { (k, v) ->
                    "<tr><td>${escape(k)}</td><td>${escape(v)}</td></tr>"
                }.ifEmpty { "<tr><td colspan=2 class=muted>无参数改动（可能仅环境变化）</td></tr>" }}
                </table>
                <p>父运行：<a href="/runs/${escape(run.parentRunId)}">${escape(run.parentRunId)}</a></p></div>"""
            } else ""
        }

        <h2>阶段检查点（先 intent，再产物摘要与 receipt，最后推进游标）</h2>
        <table>
          <tr><th>#</th><th>阶段</th><th>状态</th><th>intent</th><th>复用</th><th>产物摘要 / receipt</th></tr>
          ${
            run.stages.joinToString("") { stage ->
                val reuseCell = when {
                    stage.reuse.reused ->
                        "<span class=ok>复用</span><br><span class=muted>来源 ${escape(stage.reuse.sourceRunId ?: "")} / ${escape(stage.reuse.sourceStage ?: "")}</span>"
                    stage.reuse.reason != null ->
                        "<span class=bad>重跑</span><br><span class=muted>${escape(stage.reuse.reason)}</span>"
                    else -> "—"
                }
                val artifactCell = buildString {
                    if (stage.status == "succeeded") {
                        append("<code>${escape(stage.artifactId ?: "")}</code>")
                        append("<pre>${escape(stage.summaryJson ?: "")}</pre>")
                        append("<pre>${escape(stage.receiptJson ?: "")}</pre>")
                    } else if (stage.status == "failed") {
                        append("<span class=bad>失败：${escape(stage.failureMessage ?: "")}</span>")
                    } else {
                        append("<span class=muted>pending</span>")
                    }
                }
                """<tr><td>${stage.ordinal + 1}</td>
                  <td>${escape(stage.stageName)}<br><span class=muted>${escape(stage.stageVersion)}</span></td>
                  <td><span class="tag tag-${escape(stage.status)}">${escape(stage.status)}</span></td>
                  <td>${if (stage.intentJson != null) "<pre>${escape(stage.intentJson)}</pre>" else "<span class=muted>未写</span>"}</td>
                  <td>$reuseCell</td><td>$artifactCell</td></tr>"""
            }
          }
        </table>

        ${
            if (run.status == "running") {
                """<div class="card">
                <form method="post" action="/forms/runs/${escape(run.id)}/advance" style="display:inline">
                  <input type="hidden" name="expectedVersion" value="${run.version}">
                  <button type="submit">推进一个阶段</button>
                  <label><input type="checkbox" name="crashAfterIntent" value="1"> 模拟 intent 后进程退出</label>
                </form>
                <form method="post" action="/forms/runs/${escape(run.id)}/fail" style="display:inline;margin-left:24px">
                  <input type="hidden" name="expectedVersion" value="${run.version}">
                  <button type="submit">向当前阶段注入失败</button>
                </form>
                <p class="muted">两个页面同时推进时，携带旧 expectedVersion 的请求会收到 409 版本冲突。</p>
                </div>"""
            } else ""
        }

        <h2>能否从相同输入重现</h2>
        <p>总体结论：<span class="${if (report.reproducible) "ok" else "bad"}">${if (report.reproducible) "可以重现" else "无法确认重现"}</span></p>
        <table>
          <tr><th>阶段</th><th>校验</th><th>说明</th></tr>
          ${
            report.checks.joinToString("") { check ->
                """<tr><td>${escape(check.stageName)}</td>
                <td class="${if (check.ok) "ok" else "bad"}">${if (check.ok) "通过" else "不一致"}</td>
                <td>${escape(check.detail)}</td></tr>"""
            }
          }
        </table>

        <h2>派生谱系（沿最终结果回溯）</h2>
        <ol>
          ${
            trace.chain.joinToString("") { node ->
                """<li><a href="/runs/${escape(node.runId)}">${escape(node.displayName)}</a>
                <span class=muted>（${escape(node.runId)}）</span>
                最终产物 <code>${escape(node.finalArtifactId ?: "—")}</code>
                ${
                    if (node.changedParams.isNotEmpty()) {
                        "<br>参数差异：<pre>" + escape(Canonical.render(node.changedParams)) + "</pre>"
                    } else ""
                }
                ${
                    if (node.runId != run.id) {
                        "<br>环境指纹：<code>" + escape(node.envFingerprint.take(24)) + "</code>" +
                        "<br>产物摘要：" + node.stages.filter { it.status == "succeeded" }.joinToString("<br>") {
                            "<pre>" + escape(it.summaryJson ?: "") + "</pre>"
                        }
                    } else ""
                }
                </li>"""
            }
          }
        </ol>
        <p class="muted">JSON：<a href="/api/runs/${escape(run.id)}/verify">可重现性校验</a> ·
           <a href="/api/runs/${escape(run.id)}/lineage">谱系</a> ·
           <a href="/api/manifest/export?runId=${escape(run.id)}">导出 manifest</a></p>
        </body></html>
        """.trimIndent()
    }

    fun errorPage(title: String, message: String): String = """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
        <title>$title · 实验运行谱系库</title><style>$STYLE</style></head><body>
        <p><a href="/">← 返回实验运行谱系库</a></p>
        <h1 class="bad">${escape(title)}</h1>
        <p>${escape(message)}</p>
        </body></html>
    """.trimIndent()
}
