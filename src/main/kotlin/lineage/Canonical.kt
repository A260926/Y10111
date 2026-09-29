package lineage

import java.math.BigDecimal
import java.security.MessageDigest

/**
 * 自洽的规范化 JSON 值模型（不使用 kotlinx.serialization 的 JsonElement，
 * 以便精确保留 BigDecimal 文本而不被 Double 损失精度）。
 */
sealed interface JsonValue
data class JsonObj(val entries: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = entries[key]
    fun containsKey(key: String): Boolean = entries.containsKey(key)
    val keys: Set<String> get() = entries.keys
}
data class JsonArr(val items: List<JsonValue>) : JsonValue
data class JsonStr(val value: String) : JsonValue
data class JsonNum(val text: String) : JsonValue
data class JsonBool(val value: Boolean) : JsonValue
data object JsonNil : JsonValue

/**
 * 规范化（canonicalization）规范：
 *
 * 1. 仅接受标准 JSON：对象 / 数组 / 字符串 / 数字 / true / false / null。
 *    `NaN`、`Infinity`、`-Infinity` 一律拒绝（必须显式编码为字符串或 null，不允许静默通过）。
 * 2. 对象键按 UTF-16 码元字典序排序；同一对象内重复键拒绝。解析时的键顺序不影响身份。
 * 3. 规范化文本不含任何无关空白（冒号、逗号两侧无空格），因此空白差异不影响身份。
 * 4. 数字一律以 BigDecimal 解析并去掉多余的尾随 0 后输出 plain string：
 *    `1`、`1.0`、`1.00`、`1e0` 规范化后等价；`0.0` 与 `-0` 规范化为 `0`。
 *    因此浮点文本等价不会产生不同参数身份。
 * 5. 身份 = 规范化 UTF-8 字节的 SHA-256 十六进制摘要。
 */
object Canonical {

    class CanonicalException(message: String, val pos: Int = -1) :
        IllegalArgumentException(if (pos >= 0) "$message (at position $pos)" else message)

    fun parse(raw: String): JsonValue = StrictParser(raw).parseDocument()

    fun canonicalText(raw: String): String = render(parse(raw))

    fun canonicalText(element: JsonValue): String = render(element)

    fun digest(raw: String): String = sha256(canonicalText(raw))

    fun digest(element: JsonValue): String = sha256(canonicalText(element))

    fun sha256(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun short(hash: String?): String = if (hash.isNullOrEmpty()) "-" else hash.take(10)

    fun render(element: JsonValue, sb: StringBuilder = StringBuilder()): String {
        when (element) {
            is JsonObj -> {
                sb.append('{')
                element.entries.keys.sorted().forEachIndexed { i, key ->
                    if (i > 0) sb.append(',')
                    renderString(key, sb)
                    sb.append(':')
                    render(element.entries.getValue(key), sb)
                }
                sb.append('}')
            }
            is JsonArr -> {
                sb.append('[')
                element.items.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    render(v, sb)
                }
                sb.append(']')
            }
            is JsonStr -> renderString(element.value, sb)
            is JsonNum -> sb.append(element.text)
            is JsonBool -> sb.append(element.value.toString())
            JsonNil -> sb.append("null")
        }
        return sb.toString()
    }

    private fun renderString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    private class StrictParser(private val s: String) {
        private var i = 0
        private val numberRegex = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

        fun parseDocument(): JsonValue {
            skipWs()
            val value = readValue()
            skipWs()
            if (i != s.length) throw CanonicalException("文档结束后存在多余字符", i)
            return value
        }

        private fun readValue(): JsonValue {
            if (i >= s.length) throw CanonicalException("意外的文档结束", i)
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonStr(readString())
                't' -> readLiteral("true", JsonBool(true))
                'f' -> readLiteral("false", JsonBool(false))
                'n' -> readLiteral("null", JsonNil)
                'N' -> throw CanonicalException("不允许 NaN：请显式编码为字符串或 null", i)
                'I' -> throw CanonicalException("不允许 Infinity/-Infinity：请显式编码为字符串或 null", i)
                '-', in '0'..'9' -> readNumber()
                else -> throw CanonicalException("非法的 JSON 值起始字符 '${s[i]}'", i)
            }
        }

        private fun readLiteral(literal: String, node: JsonValue): JsonValue {
            if (!s.startsWith(literal, i)) throw CanonicalException("非法字面量", i)
            i += literal.length
            return node
        }

        private fun readNumber(): JsonNum {
            val match = numberRegex.matchAt(s, i) ?: throw CanonicalException("非法数字", i)
            val raw = match.value
            i = match.range.last + 1
            val decimal = try {
                BigDecimal(raw)
            } catch (e: NumberFormatException) {
                throw CanonicalException("无法解析的数字 $raw", i)
            }
            val canonical = if (decimal.signum() == 0) "0" else decimal.stripTrailingZeros().toPlainString()
            return JsonNum(canonical)
        }

        private fun readObject(): JsonObj {
            expect('{')
            skipWs()
            val map = linkedMapOf<String, JsonValue>()
            if (peek() == '}') { i++; return JsonObj(map) }
            while (true) {
                skipWs()
                if (peek() != '"') throw CanonicalException("对象键必须是字符串", i)
                val key = readString()
                skipWs()
                expect(':')
                skipWs()
                val value = readValue()
                if (map.put(key, value) != null) {
                    throw CanonicalException("对象内存在重复键 \"$key\"：键顺序/重复键不得产生不同身份", i)
                }
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> break
                    else -> throw CanonicalException("对象中需要 ',' 或 '}'，实际为 '$c'", i)
                }
            }
            return JsonObj(map)
        }

        private fun readArray(): JsonArr {
            expect('[')
            skipWs()
            val list = mutableListOf<JsonValue>()
            if (peek() == ']') { i++; return JsonArr(list) }
            while (true) {
                skipWs()
                list.add(readValue())
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> break
                    else -> throw CanonicalException("数组中需要 ',' 或 ']'，实际为 '$c'", i)
                }
            }
            return JsonArr(list)
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw CanonicalException("字符串未闭合", i)
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw CanonicalException("转义未结束", i)
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw CanonicalException("\\u 转义不完整", i)
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: throw CanonicalException("非法 \\u 转义 $hex", i)
                                i += 4
                                sb.append(code.toChar())
                            }
                            else -> throw CanonicalException("非法转义字符 '\\$e'", i)
                        }
                    }
                    else -> {
                        if (c.code < 0x20) throw CanonicalException("字符串中存在未转义控制字符", i - 1)
                        sb.append(c)
                    }
                }
            }
        }

        private fun skipWs() {
            while (i < s.length && s[i] in " \t\n\r") i++
        }

        private fun peek(): Char {
            if (i >= s.length) throw CanonicalException("意外的文档结束", i)
            return s[i]
        }

        private fun next(): Char {
            if (i >= s.length) throw CanonicalException("意外的文档结束", i)
            return s[i++]
        }

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) throw CanonicalException("期望 '$c'", i)
            i++
        }
    }
}
