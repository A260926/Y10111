package lineage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

val systemJson = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class ParamFieldSpec(
    val name: String,
    val type: String, // string | int | double | bool | object | list
    val required: Boolean = true,
    val secret: Boolean = false,
)

@Serializable
data class StageSpec(
    val name: String,
    val version: String,
    val declaresParams: List<String> = emptyList(),
    val declaresEnv: List<String> = emptyList(),
)

@Serializable
data class TemplateSpec(
    val name: String,
    val description: String = "",
    val paramFields: List<ParamFieldSpec>,
    val stages: List<StageSpec>,
)

@Serializable
data class Template(
    val id: String,
    val name: String,
    val description: String,
    val paramFields: List<ParamFieldSpec>,
    val stages: List<StageSpec>,
    val version: Int,
    val specHash: String,
    val createdAt: String,
)

@Serializable
data class RunParams(
    val templateId: String,
    val displayName: String = "",
    val params: JsonObject,
    val env: JsonObject,
    val inputSummary: JsonObject,
    val idempotencyKey: String? = null,
)

@Serializable
data class DeriveParams(
    val parentRunId: String,
    val displayName: String = "",
    val changedParams: JsonObject = JsonObject(emptyMap()),
    val env: JsonObject? = null,
    val inputSummary: JsonObject? = null,
    val idempotencyKey: String? = null,
    val failStage: String? = null,
    val failMessage: String? = null,
)

@Serializable
data class AdvanceParams(
    val expectedVersion: Int? = null,
    val crashAfterIntent: Boolean = false,
)

@Serializable
data class InjectFailureParams(
    val message: String = "injected failure",
    val expectedVersion: Int? = null,
)

@Serializable
data class ReuseInfo(
    val reused: Boolean,
    val sourceRunId: String? = null,
    val sourceStage: String? = null,
    val reason: String? = null,
)

@Serializable
data class StageRecord(
    val stageName: String,
    val stageVersion: String,
    val ordinal: Int,
    val status: String, // pending | succeeded | failed
    val intentJson: String? = null,
    val artifactId: String? = null,
    val contentHash: String? = null,
    val summaryJson: String? = null,
    val receiptJson: String? = null,
    val failureMessage: String? = null,
    val declaredParams: List<String> = emptyList(),
    val declaredEnv: List<String> = emptyList(),
    val stageInputHash: String? = null,
    val upstreamHash: String? = null,
    val envFingerprintStage: String? = null,
    val reuse: ReuseInfo = ReuseInfo(false),
    val startedAt: String? = null,
    val finishedAt: String? = null,
)

@Serializable
data class Run(
    val id: String,
    val templateId: String,
    val templateVersion: Int,
    val displayName: String,
    val paramsCanonical: String,
    val paramsHash: String,
    val envCanonical: String,
    val envFingerprint: String,
    val inputCanonical: String,
    val inputHash: String,
    val cursor: Int,
    val status: String, // running | succeeded | failed
    val version: Int,
    val parentRunId: String? = null,
    val changedParams: Map<String, String> = emptyMap(),
    val secretDigests: Map<String, String> = emptyMap(),
    val failStage: String? = null,
    val failMessage: String? = null,
    val idempotencyKey: String? = null,
    val createdAt: String,
    val stages: List<StageRecord> = emptyList(),
)

@Serializable
data class VerifyReport(
    val runId: String,
    val reproducible: Boolean,
    val checks: List<StageCheck>,
)

@Serializable
data class StageCheck(
    val stageName: String,
    val ok: Boolean,
    val detail: String,
)

/** 原始 JsonElement -> 规范化 Any? 树（字符串空白折叠、数字等价、非有限值拒绝）。 */
fun elementToCanonical(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        element.isString -> Canonical.normalizeWhitespace(element.content)
        else -> {
            element.booleanOrNull
                ?: element.longOrNull
                ?: element.doubleOrNull
                ?: throw LineageException("不支持的 JSON 标量: ${element.content}")
        }
    }
    is JsonArray -> element.map { elementToCanonical(it) }
    is JsonObject -> element.toMap().mapValues { elementToCanonical(it.value) }
}

inline fun <reified T> encodeList(value: List<T>): String =
    systemJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<T>()), value)

inline fun <reified T> decodeList(text: String): List<T> =
    systemJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<T>()), text)

inline fun <reified T> encodeJson(value: T): String =
    systemJson.encodeToString(kotlinx.serialization.serializer<T>(), value)

inline fun <reified T> decodeJson(text: String): T =
    systemJson.decodeFromString(kotlinx.serialization.serializer<T>(), text)
