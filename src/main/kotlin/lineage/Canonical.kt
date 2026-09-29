package lineage

import java.math.BigDecimal
import java.security.MessageDigest

/**
 * 规范化（canonicalization）规则：
 *
 * 1. map 键按 Unicode 码位排序后递归输出；
 * 2. 字符串：去首尾空白，内部连续空白折叠为单个空格；
 * 3. 浮点文本等价：BigDecimal 归一化（去多余零、纯小数形式），-0.0 -> 0；
 * 4. NaN / Infinity / -Infinity：拒绝（含 JSON 文本扫描与数字类型两道防线）；
 * 5. 缺字段：必填拒绝，可选显式编码为 null；
 * 6. 顶层声明之外的多余字段：拒绝。
 *
 * 规范化结果是紧凑 JSON 文本，其 SHA-256 即为参数/环境/摘要的身份。
 */
object Canonical {

    private val whitespace = Regex("\\s+")

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun shortHash(text: String): String = sha256(text).take(16)

    /** 规范化空白：去首尾、折叠内部连续空白。 */
    fun normalizeWhitespace(raw: String): String =
        raw.replace(whitespace, " ").trim()

    /**
     * 将 Double 转为规范化十进制文本。
     * -0.0 归一为 0；整数以纯整数形式书写；其余使用 stripTrailingZeros 的纯小数形式。
     */
    fun doubleToText(value: Double): String {
        if (value.isNaN() || value.isInfinite()) {
            throw LineageException("NaN 与 Infinity 不允许进入规范化参数")
        }
        if (value == 0.0) return "0"
        // 最短往返十进制文本：同一浮点的所有等价写法（0.0010、1e-3、多余尾零）归一。
        val shortest = BigDecimal(value)
        // BigDecimal(Double) 给出二进制全精度；改用 Double.toString 的最短表示后再标准化。
        val candidate = BigDecimal(value.toString()).stripTrailingZeros()
        if (candidate.toDouble() == value) return candidate.toPlainString()
        return shortest.stripTrailingZeros().toPlainString()
    }

    /** 将一个“已经过字段规则处理”的 Any? 树渲染为规范化 JSON 文本（键排序、紧凑）。 */
    fun render(value: Any?): String {
        val sb = StringBuilder()
        renderInto(sb, value)
        return sb.toString()
    }

    private fun renderInto(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int -> sb.append(value.toString())
            is Long -> sb.append(value.toString())
            is Double -> sb.append(doubleToText(value))
            is BigDecimal -> sb.append(value.stripTrailingZeros().toPlainString())
            is String -> writeJsonString(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                val entries = value.entries
                    .map { (k, v) -> (k as String) to v }
                    .sortedBy { it.first }
                entries.forEachIndexed { index, (key, v) ->
                    if (index > 0) sb.append(',')
                    writeJsonString(sb, key)
                    sb.append(':')
                    renderInto(sb, v)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    renderInto(sb, item)
                }
                sb.append(']')
            }
            else -> throw LineageException("不支持的规范化值类型: ${value::class.simpleName}")
        }
    }

    private fun writeJsonString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (char in text) {
            when (char) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (char.code < 0x20) {
                        sb.append("\\u%04x".format(char.code))
                    } else {
                        sb.append(char)
                    }
                }
            }
        }
        sb.append('"')
    }
}

class LineageException(message: String) : RuntimeException(message)
