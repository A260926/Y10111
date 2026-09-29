package lineage

import kotlinx.serialization.Serializable

@Serializable
data class LineageNode(
    val runId: String,
    val displayName: String,
    val parentRunId: String?,
    val changedParams: Map<String, String>,
    val inputHash: String,
    val envFingerprint: String,
    val status: String,
    val finalArtifactId: String?,
    val finalContentHash: String?,
    val stages: List<StageRecord>,
)

@Serializable
data class LineageTrace(
    val rootRunId: String,
    val chain: List<LineageNode>,
)

class LineageQuery(private val repo: Repository) {

    /** 从最终结果沿 parentRunId 回到每个祖先（含自身）。 */
    fun trace(runId: String): LineageTrace {
        val nodes = mutableListOf<LineageNode>()
        var current: Run? = repo.findRun(runId) ?: throw LineageException("运行不存在: $runId")
        val guard = mutableSetOf<String>()
        while (current != null) {
            if (!guard.add(current.id)) throw LineageException("谱系存在环: ${current.id}")
            val finalStage = current.stages.lastOrNull { it.status == "succeeded" }
            nodes.add(
                LineageNode(
                    runId = current.id,
                    displayName = current.displayName,
                    parentRunId = current.parentRunId,
                    changedParams = current.changedParams,
                    inputHash = current.inputHash,
                    envFingerprint = current.envFingerprint,
                    status = current.status,
                    finalArtifactId = finalStage?.artifactId,
                    finalContentHash = finalStage?.contentHash,
                    stages = current.stages,
                ),
            )
            current = current.parentRunId?.let { repo.findRun(it) }
        }
        return LineageTrace(runId, nodes)
    }
}
