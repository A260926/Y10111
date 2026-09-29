package lineage

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 拒绝 JSON 文本层面的 NaN / Infinity 等非有限数值标记。 */
fun rejectNonFiniteLiterals(raw: String) {
    val forbidden = listOf("NaN", "Infinity")
    for (token in forbidden) {
        if (Regex("(?<![A-Za-z0-9_])$token(?![A-Za-z0-9_])").containsMatchIn(raw)) {
            throw LineageException("参数文本包含禁止的数值标记 $token")
        }
    }
}

data class NormalizedParams(
    val canonical: String,
    val hash: String,
    val secretDigests: Map<String, String>,
)

/**
 * 按模板字段声明校验并规范化参数：
 * - 未知字段拒绝；
 * - 必填缺失拒绝；可选缺失显式编码为 null；
 * - 类型不符拒绝；NaN/Infinity 拒绝；
 * - 字符串做空白规范，浮点做文本等价规范；
 * - secret 字段在对外规范化文本中以 {"secretDigest": sha256(规范化值文本)} 编码。
 */
object Normalizer {

    fun normalizeParams(fields: List<ParamFieldSpec>, raw: JsonObject): NormalizedParams {
        val fieldByName = fields.associateBy { it.name }
        val unknown = raw.keys - fieldByName.keys
        if (unknown.isNotEmpty()) {
            throw LineageException("参数包含未声明字段: ${unknown.sorted().joinToString(", ")}")
        }
        val normalized = LinkedHashMap<String, Any?>()
        val secretDigests = LinkedHashMap<String, String>()
        for (field in fields.sortedBy { it.name }) {
            val present = raw.containsKey(field.name) && raw[field.name] !is JsonNull
            if (!present) {
                if (field.required) {
                    throw LineageException("缺少必填参数: ${field.name}")
                }
                normalized[field.name] = null
                continue
            }
            val rawValue = raw.getValue(field.name)
            if (field.secret && rawValue is JsonObject && "secretDigest" in rawValue) {
                // 派生运行沿用父运行已编码的 secret 占位（规范化参数不依赖该字段身份，
                // 阶段输入哈希只通过 secretDigest 间接一致）。
                val digest = (rawValue.getValue("secretDigest") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: throw LineageException("secretDigest 编码无效")
                secretDigests[field.name] = digest
                normalized[field.name] = "__secret__"
            } else {
                val value = coerceType(field, rawValue)
                if (field.secret) {
                    val valueText = Canonical.render(value)
                    secretDigests[field.name] = Canonical.sha256("secret:$valueText")
                    normalized[field.name] = "__secret__"
                } else {
                    normalized[field.name] = value
                }
            }
        }
        val canonical = Canonical.render(normalized)
        return NormalizedParams(canonical, Canonical.sha256(canonical), secretDigests)
    }

    private fun coerceType(field: ParamFieldSpec, element: JsonElement): Any? {
        val canonical = elementToCanonical(element)
        return when (field.type) {
            "string" -> {
                if (canonical !is String) throw LineageException("参数 ${field.name} 应为 string")
                canonical
            }
            "int" -> when (canonical) {
                is Long -> canonical
                is Double -> {
                    if (canonical % 1.0 == 0.0 && canonical.isFinite()) canonical.toLong()
                    else throw LineageException("参数 ${field.name} 应为 int，收到非整数值")
                }
                else -> throw LineageException("参数 ${field.name} 应为 int")
            }
            "double" -> when (canonical) {
                is Double -> canonical
                is Long -> canonical.toDouble()
                is String -> canonical.toDoubleOrNull()
                    ?: throw LineageException("参数 ${field.name} 应为 double 数值文本")
                else -> throw LineageException("参数 ${field.name} 应为 double")
            }
            "bool" -> {
                if (canonical !is Boolean) throw LineageException("参数 ${field.name} 应为 bool")
                canonical
            }
            "object" -> {
                if (canonical !is Map<*, *>) throw LineageException("参数 ${field.name} 应为 object")
                canonical
            }
            "list" -> {
                if (canonical !is List<*>) throw LineageException("参数 ${field.name} 应为 list")
                canonical
            }
            else -> throw LineageException("未知参数类型: ${field.type}")
        }
    }

    /** 环境声明：map 键排序，值转为字符串并做空白规范；缺键拒绝。 */
    fun normalizeEnv(raw: JsonObject): Pair<String, String> {
        if (raw.keys.any { raw[it] is JsonArray || raw[it] is JsonObject }) {
            throw LineageException("环境指纹只接受字符串/数字/布尔标量值")
        }
        val map = raw.toMap()
            .mapValues { (_, v) ->
                val c = elementToCanonical(v)
                Canonical.normalizeWhitespace(c?.toString() ?: "null")
            }
            .toSortedMap()
        val canonical = Canonical.render(map)
        return canonical to Canonical.sha256("env:$canonical")
    }

    /** 输入数据摘要：允许任意嵌套结构，统一规范化取指纹。 */
    fun normalizeInput(raw: JsonObject): Pair<String, String> {
        val canonical = Canonical.render(elementToCanonical(raw))
        return canonical to Canonical.sha256("input:$canonical")
    }
}
