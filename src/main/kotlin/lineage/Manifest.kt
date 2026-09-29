package lineage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ManifestStage(
    val stageName: String,
    val stageVersion: String,
    val ordinal: Int,
    val status: String,
    val artifactId: String? = null,
    val contentHash: String? = null,
    val summaryJson: String? = null,
    val receiptJson: String? = null,
    val failureMessage: String? = null,
    val stageInputHash: String? = null,
    val upstreamHash: String? = null,
    val envFingerprintStage: String? = null,
    val reuse: ReuseInfo,
)

@Serializable
data class ManifestRun(
    val id: String,
    val templateId: String,
    val templateVersion: Int,
    val displayName: String,
    val paramsCanonical: String,
    val paramsHash: String,
    val secretDigests: Map<String, String>,
    val redactedSecrets: List<String>,
    val envCanonical: String,
    val envFingerprint: String,
    val inputCanonical: String,
    val inputHash: String,
    val cursor: Int,
    val status: String,
    val parentRunId: String? = null,
    val changedParams: Map<String, String>,
    val stages: List<ManifestStage>,
    val createdAt: String,
)

@Serializable
data class ManifestArtifact(
    val id: String,
    val contentHash: String,
    val summaryJson: String,
    val receiptJson: String,
)

@Serializable
data class Manifest(
    val format: String = "experiment-lineage-manifest/v1",
    val exportedAt: String,
    val templates: List<Template>,
    val runs: List<ManifestRun>,
    val artifacts: List<ManifestArtifact>,
)

@Serializable
data class ImportReport(
    val importedTemplates: Int,
    val importedRuns: Int,
    val importedArtifacts: Int,
    val verification: List<VerifyReport>,
    val skipped: List<String>,
)

class ManifestService(private val db: Database, private val repo: Repository, private val service: LineageService) {

    fun export(runIds: List<String>? = null): Manifest {
        val wanted = runIds?.toSet()
        val runs = repo.listRuns().filter { wanted == null || it.id in wanted }
        val runIdsInScope = runs.map { it.id }.toSet()
        val templates = runs.map { repo.findTemplate(it.templateId)!! }.distinctBy { it.id }
        val artifactHashes = runs.flatMap { run ->
            run.stages.mapNotNull { it.contentHash }
        }.toSet()
        val artifacts = repo.listAllArtifacts()
            .filter { it.contentHash in artifactHashes }
            .map { ManifestArtifact(it.id, it.contentHash, it.summaryJson, it.receiptJson) }

        val manifestRuns = runs.map { run ->
            val template = repo.findTemplate(run.templateId)!!
            val secretDigests = template.paramFields
                .filter { it.secret }
                .mapNotNull { field ->
                    val params = decodeJson<JsonObject>(run.paramsCanonical)
                    val entry = params[field.name]
                    val digest = (entry as? JsonObject)?.get("secretDigest")?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }
                    digest?.let { field.name to it }
                }
                .toMap()
            ManifestRun(
                id = run.id,
                templateId = run.templateId,
                templateVersion = run.templateVersion,
                displayName = run.displayName,
                paramsCanonical = run.paramsCanonical,
                paramsHash = run.paramsHash,
                secretDigests = secretDigests,
                redactedSecrets = template.paramFields.filter { it.secret }.map { it.name },
                envCanonical = run.envCanonical,
                envFingerprint = run.envFingerprint,
                inputCanonical = run.inputCanonical,
                inputHash = run.inputHash,
                cursor = run.cursor,
                status = run.status,
                parentRunId = run.parentRunId?.takeIf { it in runIdsInScope },
                changedParams = run.changedParams,
                stages = run.stages.map { stage ->
                    ManifestStage(
                        stageName = stage.stageName,
                        stageVersion = stage.stageVersion,
                        ordinal = stage.ordinal,
                        status = stage.status,
                        artifactId = stage.artifactId,
                        contentHash = stage.contentHash,
                        summaryJson = stage.summaryJson,
                        receiptJson = stage.receiptJson,
                        failureMessage = stage.failureMessage,
                        stageInputHash = stage.stageInputHash,
                        upstreamHash = stage.upstreamHash,
                        envFingerprintStage = stage.envFingerprintStage,
                        reuse = stage.reuse,
                    )
                },
                createdAt = run.createdAt,
            )
        }
        return Manifest(
            exportedAt = db.now(),
            templates = templates,
            runs = manifestRuns,
            artifacts = artifacts,
        )
    }

    fun reimport(manifest: Manifest, verify: Boolean = true): ImportReport = db.tx {
        val skipped = mutableListOf<String>()
        var templates = 0
        manifest.templates.forEach { incoming ->
            val existing = repo.findTemplate(incoming.id)
            when {
                existing == null -> {
                    repo.insertTemplate(incoming)
                    templates++
                }
                existing.specHash == incoming.specHash -> skipped.add("模板 ${incoming.id} 已存在且内容一致")
                else -> throw LineageException("模板 ${incoming.id} 已存在但 specHash 不一致，拒绝覆盖")
            }
        }
        var artifacts = 0
        manifest.artifacts.forEach { artifact ->
            if (!repo.artifactExists(artifact.contentHash)) {
                repo.insertArtifact(
                    artifact.id,
                    artifact.contentHash,
                    artifact.summaryJson.ifBlank { artifact.receiptJson },
                    artifact.receiptJson,
                )
                artifacts++
            }
        }
        var runs = 0
        for (incoming in manifest.runs.sortedBy { run -> run.stages.count { it.reuse.reused } }) {
            if (repo.findRun(incoming.id) != null) {
                skipped.add("运行 ${incoming.id} 已存在（幂等跳过）")
                continue
            }
            val run = Run(
                id = incoming.id,
                templateId = incoming.templateId,
                templateVersion = incoming.templateVersion,
                displayName = incoming.displayName,
                paramsCanonical = incoming.paramsCanonical,
                paramsHash = incoming.paramsHash,
                envCanonical = incoming.envCanonical,
                envFingerprint = incoming.envFingerprint,
                inputCanonical = incoming.inputCanonical,
                inputHash = incoming.inputHash,
                cursor = incoming.cursor,
                status = incoming.status,
                version = 1,
                parentRunId = incoming.parentRunId,
                changedParams = incoming.changedParams,
                idempotencyKey = null,
                createdAt = incoming.createdAt,
            )
            repo.insertRun(run)
            incoming.stages.forEach { stage ->
                repo.upsertStage(
                    StageRecord(
                        stageName = stage.stageName,
                        stageVersion = stage.stageVersion,
                        ordinal = stage.ordinal,
                        status = stage.status,
                        intentJson = null,
                        artifactId = stage.artifactId,
                        contentHash = stage.contentHash,
                        summaryJson = stage.summaryJson,
                        receiptJson = stage.receiptJson,
                        failureMessage = stage.failureMessage,
                        declaredParams = emptyList(),
                        declaredEnv = emptyList(),
                        stageInputHash = stage.stageInputHash,
                        upstreamHash = stage.upstreamHash,
                        envFingerprintStage = stage.envFingerprintStage,
                        reuse = stage.reuse,
                    ),
                    run.id,
                )
            }
            runs++
        }
        val reports = if (verify) manifest.runs.map { service.verify(it.id) } else emptyList()
        ImportReport(templates, runs, artifacts, reports, skipped)
    }
}
