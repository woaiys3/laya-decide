package com.laya.decide.tokenizer

/**
 * 极简 JSON 解析器。
 *
 * 为什么要自己写：`org.json` 是 Android 平台类，JVM 单测里拿到的是只会抛异常的
 * stub，而分词器必须在 JVM 上可测（我没法在模拟器里逐 token 调试）。
 * 需求又很窄 —— 只解析 tokenizer.json —— 所以手写一个比引依赖划算。
 *
 * 只覆盖 tokenizer.json 用到的 JSON 子集，但字符串转义处理是完整的。
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun asObject(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun asArray(v: Any?): List<Any?> = v as? List<Any?> ?: emptyList()

    fun asString(v: Any?): String? = v as? String

    fun asInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Double -> v.toInt()
        else -> null
    }

    fun asBool(v: Any?): Boolean? = v as? Boolean

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("JSON 意外结束 @$i")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else
                    throw IllegalArgumentException("JSON 位置 $i 出现意外字符 '$c'")
            }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return m }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                expect(':')
                m[k] = value()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw IllegalArgumentException("JSON 对象位置 $i 期待 ',' 或 '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return out }
            while (true) {
                out.add(value())
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("JSON 数组位置 $i 期待 ',' 或 ']'")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("JSON 字符串未闭合")
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw IllegalArgumentException("JSON 转义未闭合")
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
                                if (i + 4 > s.length) throw IllegalArgumentException("JSON \\u 转义不完整")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("未知转义 \\$e @$i")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isDouble = false
            if (i < s.length && s[i] == '.') {
                isDouble = true
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isDouble = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val raw = s.substring(start, i)
            return if (isDouble) raw.toDouble() else raw.toLong()
        }

        private fun <T> lit(word: String, v: T): T {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("JSON 位置 $i 期待 '$word'")
            i += word.length
            return v
        }

        private fun peek(): Char =
            if (i < s.length) s[i] else throw IllegalArgumentException("JSON 意外结束 @$i")

        private fun expect(c: Char) {
            if (peek() != c) throw IllegalArgumentException("JSON 位置 $i 期待 '$c'，实际 '${s[i]}'")
            i++
        }
    }
}
