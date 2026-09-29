package lineage

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 内置确定性阶段模拟器：不执行真实重计算。
 * 产物完全由“阶段输入（阶段版本 + 规范化参数子集 + 环境子集）+ 上游产物链”决定。
 */
object Simulator {

    data class StageInput(
        val stageName: String,
        val stageVersion: String,
        val paramsSubset: Map<String, Any?>,
        val envSubset: Map<String, String>,
    )

    fun selectSubset(
        run: Run,
        template: Template,
        stage: StageSpec,
    ): StageInput {
        val allParams = systemJson.decodeFromString(
            MapSerializer(String.serializer(), kotlinx.serialization.json.JsonElement.serializer()),
            run.paramsCanonical,
        ).mapValues { elementToCanonical(it.value) }
        val allEnv = systemJson.decodeFromString(
            MapSerializer(String.serializer(), kotlinx.serialization.json.JsonElement.serializer()),
            run.envCanonical,
        ).mapValues { it.value.toString() }

        val missingParams = stage.declaresParams.filter { it !in allParams }
        if (missingParams.isNotEmpty()) {
            throw LineageException("阶段 ${stage.name} 声明的参数缺失: ${missingParams.joinToString()}")
        }
        val missingEnv = stage.declaresEnv.filter { it !in allEnv }
        if (missingEnv.isNotEmpty()) {
            throw LineageException("阶段 ${stage.name} 声明的环境变量缺失: ${missingEnv.joinToString()}")
        }
        return StageInput(
            stageName = stage.name,
            stageVersion = stage.version,
            paramsSubset = allParams.filterKeys { it in stage.declaresParams }.toSortedMap(),
            envSubset = allEnv.filterKeys { it in stage.declaresEnv }.toSortedMap(),
        )
    }

    fun stageInputText(input: StageInput): String {
        return Canonical.render(
            mapOf(
                "stageName" to input.stageName,
                "stageVersion" to input.stageVersion,
                "params" to input.paramsSubset,
                "env" to input.envSubset,
            ),
        )
    }

    fun stageInputHash(input: StageInput, inputDataHash: String): String =
        Canonical.sha256("${stageInputText(input)}|input:$inputDataHash")

    /** 上游产物链：空链固定为 genesis。 */
    fun upstreamHash(previous: StageRecord?): String =
        previous?.contentHash?.let { Canonical.sha256("chain:$it") }
            ?: Canonical.sha256("chain:genesis")

    data class Produced(
        val artifactId: String,
        val contentHash: String,
        val summaryJson: String,
        val receiptJson: String,
        val deterministicAt: String,
    )

    fun produce(input: StageInput, inputDataHash: String, previous: StageRecord?, ordinal: Int): Produced {
        val inputHash = stageInputHash(input, inputDataHash)
        val upstream = upstreamHash(previous)
        val contentHash = Canonical.sha256("content:${input.stageVersion}|$inputHash|$upstream")
        val artifactId = "art-${contentHash.take(20)}"
        val deterministicAt = "L${ordinal + 1}:${contentHash.take(10)}"
        val summary = linkedMapOf(
            "stage" to input.stageName,
            "stageVersion" to input.stageVersion,
            "ordinal" to ordinal,
            "inputHash" to inputHash,
            "upstreamHash" to upstream,
            "contentHash" to contentHash,
            "metrics" to linkedMapOf(
                "bytes" to (contentHash.substring(0, 8).toLong(16) % 1_000_000L),
                "checksum" to contentHash.take(12),
            ),
            "simulated" to true,
        )
        val receipt = linkedMapOf(
            "artifactId" to artifactId,
            "stageName" to input.stageName,
            "stageVersion" to input.stageVersion,
            "ordinal" to ordinal,
            "stageInputHash" to inputHash,
            "upstreamHash" to upstream,
            "contentHash" to contentHash,
            "simulator" to "deterministic-stage-simulator",
            "simulatorVersion" to "1.0.0",
            "logicalTime" to deterministicAt,
        )
        return Produced(
            artifactId = artifactId,
            contentHash = contentHash,
            summaryJson = Canonical.render(summary),
            receiptJson = Canonical.render(receipt),
            deterministicAt = deterministicAt,
        )
    }
}
