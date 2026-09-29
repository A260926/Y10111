package lineage

import java.util.concurrent.ConcurrentHashMap

/**
 * 内置确定性阶段模拟器。不执行真实重计算：
 * 同一 (阶段名, 阶段版本, 输入摘要, 参数摘要, 上游摘要, 环境指纹) 永远产出同一产物与 receipt。
 */
object Simulator {
    const val SIMULATOR_VERSION = "sim-1"

    private val materializations = ConcurrentHashMap<String, Long>()

    fun materializationCount(runId: String): Long = materializations[runId] ?: 0L

    fun resetCounters() = materializations.clear()

    fun materialize(
        runId: String,
        stageIndex: Int,
        inputs: StageInputs,
        workMillis: Long,
        failOnce: InjectedFailure? = null,
    ): StageMaterial {
        if (failOnce != null && failOnce.runId == runId && failOnce.stageIndex == stageIndex) {
            if (failOnce.crashBeforeArtifact) {
                throw SimulatedCrashException(
                    "模拟进程崩溃：运行 ${runId} 阶段 ${stageIndex}（${failOnce.reason}）；intent 已持久化"
                )
            }
            throw InjectedFailureException(
                "已注入失败：运行 ${runId} 阶段 ${stageIndex}（${failOnce.reason}）"
            )
        }
        if (workMillis > 0) Thread.sleep(workMillis)
        materializations.merge(runId, 1L) { a, b -> a + b }
        return material(stageIndex, inputs)
    }

    /** 纯确定性计算：无副作用，供重现性验证重算。 */
    fun material(stageIndex: Int, inputs: StageInputs): StageMaterial {
        val artifact = computeArtifact(stageIndex, inputs)
        val receipt = buildReceipt(stageIndex, inputs, artifact)
        return StageMaterial(artifactText = artifact, receiptJson = receipt)
    }

    fun computeArtifact(stageIndex: Int, inputs: StageInputs): String {
        val name = inputs.stageName
        val obj = buildMap<String, JsonValue> {
            put("simulator", JsonStr(SIMULATOR_VERSION))
            put("stage_index", JsonNum(stageIndex.toString()))
            put("stage_name", JsonStr(name))
            put("stage_version", JsonNum(inputs.stageVersion.toString()))
            put("input_digest", JsonStr(inputs.inputDigest))
            put("params_digest", JsonStr(inputs.paramsDigest))
            put("upstream_digest", JsonStr(inputs.upstreamDigest ?: ""))
            if (inputs.envSensitive) put("env_fingerprint", JsonStr(inputs.envFingerprint))
            when (name) {
                "extract" -> {
                    put("record_count", JsonNum(inputRecordCount(inputs).toString()))
                    put("bytes_digest", JsonStr(Canonical.sha256(
                        Canonical.canonicalText(inputs.inputElement) + ":extract")))
                }
                "transform" -> {
                    put("feature_count", JsonNum(consumedParamKeys(inputs).size.coerceAtLeast(1).toString()))
                    put("pipeline_digest", JsonStr(Canonical.sha256(
                        inputs.inputDigest + "|" + inputs.paramsDigest + "|transform"
                    )))
                }
                "evaluate" -> {
                    put("metric", JsonStr(deterministicMetric(inputs)))
                    put("evaluation_digest", JsonStr(Canonical.sha256(
                        (inputs.upstreamDigest ?: "") + "|" + inputs.paramsDigest
                            + "|" + inputs.envFingerprint + "|evaluate"
                    )))
                }
                else -> {
                    put("generic_digest", JsonStr(Canonical.sha256(
                        listOf(
                            inputs.inputDigest,
                            inputs.paramsDigest,
                            inputs.upstreamDigest ?: "",
                            if (inputs.envSensitive) inputs.envFingerprint else "",
                            name,
                            inputs.stageVersion.toString(),
                        ).joinToString("|")
                    )))
                }
            }
        }
        return Canonical.render(JsonObj(obj))
    }

    private fun consumedParamKeys(inputs: StageInputs): List<String> {
        val params = inputs.paramsElement as? JsonObj ?: return emptyList()
        return params.keys.filter { it in inputs.consumes }.sorted()
    }

    private fun inputRecordCount(inputs: StageInputs): Int {
        val obj = inputs.inputElement as? JsonObj
        val rows = obj?.get("rows") as? JsonArr
        return rows?.items?.size ?: 1
    }

    private fun deterministicMetric(inputs: StageInputs): String {
        return Canonical.sha256(
            listOf(
                inputs.inputDigest,
                inputs.paramsDigest,
                inputs.upstreamDigest ?: "",
                inputs.envFingerprint,
                inputs.stageVersion.toString(),
            ).joinToString("#")
        ).take(16)
    }

    private fun buildReceipt(stageIndex: Int, inputs: StageInputs, artifactText: String): String {
        val artifactDigest = Canonical.sha256(artifactText)
        val fields = sortedMapOf(
            "receipt_version" to JsonNum("1"),
            "simulator" to JsonStr(SIMULATOR_VERSION),
            "run_id" to JsonStr(inputs.runId),
            "stage_index" to JsonNum(stageIndex.toString()),
            "stage_name" to JsonStr(inputs.stageName),
            "stage_version" to JsonNum(inputs.stageVersion.toString()),
            "input_digest" to JsonStr(inputs.inputDigest),
            "params_digest" to JsonStr(inputs.paramsDigest),
            "upstream_digest" to JsonStr(inputs.upstreamDigest ?: ""),
            "env_fingerprint" to JsonStr(inputs.envFingerprint),
            "env_sensitive" to JsonBool(inputs.envSensitive),
            "artifact_digest" to JsonStr(artifactDigest),
            "prev_artifact_digest" to JsonStr(inputs.upstreamDigest ?: ""),
        )
        val text = Canonical.render(JsonObj(fields))
        val receiptHash = Canonical.sha256(text + "|" + (inputs.upstreamReceiptHash ?: ""))
        fields["receipt_hash"] = JsonStr(receiptHash)
        fields["prev_receipt_hash"] = JsonStr(inputs.upstreamReceiptHash ?: "")
        return Canonical.render(JsonObj(fields))
    }

    /** 内置环境声明：固定键集合；用户声明覆盖内置值，最终规范化取指纹。 */
    fun envFingerprint(overrides: Map<String, String>): Pair<String, String> {
        val base = linkedMapOf(
            "simulator" to SIMULATOR_VERSION,
            "os.name" to System.getProperty("os.name", "unknown"),
            "os.arch" to System.getProperty("os.arch", "unknown"),
            "java.vm.name" to System.getProperty("java.vm.name", "unknown"),
            "java.version" to System.getProperty("java.version", "unknown"),
            "lineage.schema" to "v1",
            "cluster.id" to "cluster-a",
            "compute.profile" to "cpu-standard",
        )
        overrides.toSortedMap().forEach { (k, v) -> base[k] = v }
        val obj = JsonObj(base.toSortedMap().mapValues { JsonStr(it.value) })
        val text = Canonical.render(obj)
        return text to Canonical.sha256(text)
    }
}

data class StageInputs(
    val runId: String,
    val stageName: String,
    val stageVersion: Int,
    val inputElement: JsonValue,
    val inputDigest: String,
    val paramsElement: JsonValue,
    val paramsDigest: String,
    val consumes: Set<String>,
    val envFingerprint: String,
    val envSensitive: Boolean,
    val upstreamDigest: String?,
    val upstreamReceiptHash: String?,
)

data class StageMaterial(
    val artifactText: String,
    val receiptJson: String,
)

data class InjectedFailure(
    val runId: String,
    val stageIndex: Int,
    val reason: String,
    val crashBeforeArtifact: Boolean = true,
)

class InjectedFailureException(message: String) : RuntimeException(message)

/** 模拟进程在物化前退出：异常必须穿透 advance，使 INTENT 行保留在数据库中。 */
class SimulatedCrashException(message: String) : RuntimeException(message)
