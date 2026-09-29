package lineage

import kotlinx.serialization.Serializable

/** 模板阶段定义（内置确定性阶段模拟器支持的阶段类型）。 */
@Serializable
data class StageDef(
    val name: String,
    val stageVersion: Int,
    /** 该阶段读取的顶层参数键（用于派生复用时判定参数差异是否影响本阶段）。 */
    val consumes: List<String> = emptyList(),
    /** 阶段产物是否随环境指纹变化；true 表示环境变化会阻止复用并给出原因。 */
    val envSensitive: Boolean = false,
)

/** 实验模板（模板版本固定后不可变）。 */
@Serializable
data class Template(
    val id: String,
    val templateVersion: Int,
    val name: String,
    val stages: List<StageDef>,
    /** 标记为 secret 的顶层参数键：其值永不写入导出 manifest。 */
    val secrets: List<String> = emptyList(),
    val createdAt: String,
)

enum class StageStatus { PENDING, INTENT, SUCCESS, FAILED }

/** 阶段检查点（一行完整记录一次阶段尝试的意图、产物与 receipt）。 */
data class StageRow(
    val id: Long,
    val runId: String,
    val stageIndex: Int,
    val stageName: String,
    val stageVersion: Int,
    val status: StageStatus,
    val attempt: Int,
    val intentId: String?,
    val inputDigest: String?,
    val paramsDigest: String?,
    val envFingerprint: String?,
    val upstreamDigest: String?,
    val artifactDigest: String?,
    val artifactText: String?,
    val receiptJson: String?,
    val reason: String?,
    val source: String,
    val createdAt: String,
    val updatedAt: String,
)

data class RunRow(
    val id: String,
    val templateId: String,
    val templateVersion: Int,
    val name: String,
    val cursor: Int,
    val cursorVersion: Long,
    val paramsCanonical: String,
    val paramsDigest: String,
    val inputText: String,
    val inputDigest: String,
    val env: String,
    val envFingerprint: String,
    val parentRunId: String?,
    val changedKeys: String,
    val idempotencyKey: String?,
    val status: String,
    val createdAt: String,
)

data class EventRow(
    val id: Long,
    val runId: String,
    val stageIndex: Int?,
    val kind: String,
    val detail: String,
    val at: String,
)

class LineageException(message: String) : RuntimeException(message)
class ConflictException(message: String) : RuntimeException(message)
class NotFoundException(message: String) : RuntimeException(message)

// ---------- API DTO ----------

@Serializable
data class CreateTemplateRequest(
    val name: String,
    val stages: List<StageDef>? = null,
    val secrets: List<String>? = null,
    val id: String? = null,
)

@Serializable
data class FreezeRequest(
    val templateId: String,
    val name: String,
    /** 原始参数 JSON 文本；空对象代表无参数。 */
    val params: String = "{}",
    /** 原始输入数据 JSON 文本。 */
    val input: String = "{}",
    /** 环境声明覆盖项（与模拟器内置声明合并）。 */
    val env: Map<String, String> = emptyMap(),
    val idempotencyKey: String? = null,
)

@Serializable
data class DeriveRequest(
    val parentRunId: String,
    val name: String,
    val params: String? = null,
    val env: Map<String, String> = emptyMap(),
    val input: String? = null,
    val idempotencyKey: String? = null,
)

@Serializable
data class RunResponse(
    val runId: String,
    val name: String,
    val templateId: String,
    val templateVersion: Int,
    val cursor: Int,
    val cursorVersion: Long,
    val status: String,
    val paramsDigest: String,
    val inputDigest: String,
    val envFingerprint: String,
    val parentRunId: String? = null,
    val changedKeys: List<String> = emptyList(),
    val idempotentReuse: Boolean = false,
    val createdAt: String,
)

@Serializable
data class StageResponse(
    val stageIndex: Int,
    val name: String,
    val stageVersion: Int,
    val status: String,
    val attempt: Int,
    val intentId: String? = null,
    val inputDigest: String? = null,
    val scopeParamsDigest: String? = null,
    val upstreamDigest: String? = null,
    val artifactDigest: String? = null,
    val artifactText: String? = null,
    val receipt: String? = null,
    val reason: String? = null,
    val source: String,
    val envFingerprint: String? = null,
    val updatedAt: String,
)

@Serializable
data class RunDetailResponse(
    val run: RunResponse,
    val stages: List<StageResponse>,
)

@Serializable
data class InjectionResponse(
    val injected: Boolean,
    val runId: String,
    val stage: Int,
    val crash: Boolean,
    val armed: Boolean,
)

@Serializable
data class AdvanceResponse(
    val run: RunResponse,
    val stage: StageResponse,
)

@Serializable
data class VerifyResponse(
    val runId: String,
    val reproducible: Boolean,
    val finalArtifact: String? = null,
    val checks: List<String>,
)

@Serializable
data class ImportResponse(
    val importedTemplates: Int,
    val importedRuns: Int,
    val verifiable: Boolean,
    val checks: List<String>,
    val errors: List<String>,
)

@Serializable
data class Manifest(
    val manifestVersion: Int = 1,
    val exportedAt: String,
    val templates: List<Template>,
    val runs: List<ManifestRun>,
)

@Serializable
data class ManifestRun(
    val run: RunResponse,
    val params: String,
    val input: String,
    val env: Map<String, String>,
    val redactedSecretKeys: List<String>,
    val stages: List<StageResponse>,
)
