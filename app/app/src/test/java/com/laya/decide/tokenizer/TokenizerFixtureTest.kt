package com.laya.decide.tokenizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分词器的逐 id 一致性测试。
 *
 * 夹具由参考实现生成，见 server/tools/gen-tokenizer-fixture.mjs：
 *
 *   cd D:\laya\server
 *   node tools/gen-tokenizer-fixture.mjs
 *
 * 夹具覆盖 App 会真实产生的文本形态（问题头、档位文本、选项、state）
 * 以及边界情况（空白、特殊 token、emoji、全角、超长串）。
 *
 * **改 Tokenizer.kt 之后必须重跑这个测试。** 一个 id 不对，模型判断就可能变。
 */
class TokenizerFixtureTest {

    private fun loadTokenizer(): Tokenizer {
        val json = javaClass.classLoader!!
            .getResourceAsStream("tokenizer.json")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return Tokenizer.fromJson(json)
    }

    private fun loadFixture(): List<Pair<String, List<Int>>> {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("tokenizer-fixture.json")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val root = Json.asObject(Json.parse(raw))
        return Json.asArray(root["fixtures"]).map { f ->
            val o = Json.asObject(f)
            val text = Json.asString(o["text"]) ?: ""
            val ids = Json.asArray(o["ids"]).map { Json.asInt(it) ?: -1 }
            text to ids
        }
    }

    @Test
    fun `分词结果与参考实现逐 id 一致`() {
        val tok = loadTokenizer()
        val fixtures = loadFixture()

        assertTrue("夹具不该是空的", fixtures.size > 30)

        val failures = ArrayList<String>()

        for ((text, expected) in fixtures) {
            val actual = tok.encode(text).toList()
            if (actual != expected) {
                failures.add(
                    buildString {
                        append("文本: ").append(quote(text)).append('\n')
                        append("  期望: [").append(expected.joinToString(", ")).append("]\n")
                        append("  实际: [").append(actual.joinToString(", ")).append("]\n")
                        // 标出第一个不同的位置，方便定位
                        val firstDiff = expected.indices.firstOrNull {
                            it >= actual.size || actual[it] != expected[it]
                        } ?: minOf(expected.size, actual.size)
                        append("  首个差异在下标 ").append(firstDiff)
                        if (firstDiff < expected.size) {
                            append("：期望 ").append(expected[firstDiff])
                        }
                        if (firstDiff < actual.size) {
                            append("，实际 ").append(actual[firstDiff])
                        }
                    },
                )
            }
        }

        if (failures.isNotEmpty()) {
            val msg = buildString {
                append("有 ").append(failures.size).append(" / ").append(fixtures.size)
                append(" 条夹具不一致：\n\n")
                failures.take(12).forEach { append(it).append("\n\n") }
                if (failures.size > 12) {
                    append("...还有 ").append(failures.size - 12).append(" 条\n")
                }
            }
            throw AssertionError(msg)
        }
    }

    @Test
    fun `分词器能从 tokenizer_json 正确加载`() {
        val tok = loadTokenizer()
        assertTrue("词表大小应该合理", tok.vocabSize > 40000)

        // 特殊 token 的 id 必须能取到，且互不相同。
        // 这几个值来自参考实现，是序列布局的基石。
        assertEquals(50281, tok.clsId)
        assertEquals(50282, tok.sepId)
        assertEquals(50283, tok.padId)
        assertEquals(50284, tok.maskId)
        assertEquals(50280, tok.unkId)

        val all = listOf(tok.clsId, tok.sepId, tok.padId, tok.maskId, tok.unkId)
        assertEquals("特殊 token 的 id 不能重复", all.size, all.toSet().size)
    }

    @Test
    fun `空串编码为空`() {
        val tok = loadTokenizer()
        assertEquals(0, tok.encode("").size)
    }

    @Test
    fun `编码是确定性的`() {
        val tok = loadTokenizer()
        val text = "在这个情境下，「接受这个 offer」这个选择有多合适？"
        val a = tok.encode(text).toList()
        val b = tok.encode(text).toList()
        assertEquals(a, b)
    }

    @Test
    fun `特殊 token 字面量被识别为单个 id`() {
        val tok = loadTokenizer()
        assertEquals(listOf(tok.maskId), tok.encode("[MASK]").toList())
        assertEquals(listOf(tok.clsId), tok.encode("[CLS]").toList())
        assertEquals(listOf(tok.sepId), tok.encode("[SEP]").toList())
        assertEquals(listOf(tok.padId), tok.encode("[PAD]").toList())
        assertEquals(listOf(tok.unkId), tok.encode("[UNK]").toList())

        // 夹在文本中间也要能切出来
        val mixed = tok.encode("a [MASK] b").toList()
        assertTrue("中间的特殊 token 必须是一个独立 id", mixed.contains(tok.maskId))
    }

    @Test
    fun `超长输入不抛异常`() {
        val tok = loadTokenizer()
        val long = "这是一个很长的句子，".repeat(200)
        val ids = tok.encode(long)
        assertTrue("应该有输出", ids.isNotEmpty())
    }

    @Test
    fun `所有 id 都在词表范围内`() {
        val tok = loadTokenizer()
        val samples = listOf(
            "score question: 评估这个选择",
            " level 3: 明显最佳，强烈推荐",
            " 接受这个offer",
            "我拿到一个创业公司offer，薪资降30%。",
            "😀 emoji test",
            "ｆｕｌｌｗｉｄｔｈ",
        )
        for (s in samples) {
            for (id in tok.encode(s)) {
                assertTrue("id $id 越界（文本：$s）", id in 0 until tok.vocabSize + 200)
                assertTrue("id 不该为负（文本：$s）", id >= 0)
            }
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '\n' -> sb.append("\\n")
                c == '\t' -> sb.append("\\t")
                c == '\r' -> sb.append("\\r")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    @Test
    fun `夹具文件可读且格式正确`() {
        val fixtures = loadFixture()
        assertNotNull(fixtures)
        for ((text, ids) in fixtures) {
            assertNotNull(text)
            for (id in ids) assertTrue("夹具里有非法 id", id >= 0)
        }
    }
}
