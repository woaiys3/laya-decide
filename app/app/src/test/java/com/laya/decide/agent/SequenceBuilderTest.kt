package com.laya.decide.agent

import com.laya.decide.tokenizer.Json
import com.laya.decide.tokenizer.Tokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 序列构造的逐 id 一致性测试。
 *
 * 夹具由参考实现（@receptron/laya 的 dist/sequence.js）生成：
 *
 *   cd D:\laya\server
 *   node tools/gen-sequence-fixture.mjs
 *
 * 覆盖的分支：正常序列、max_len 截断、单选项 48-token 截断、
 * head 预算收缩（20 选项）、[MASK] 字面量 scrub、不同档位数。
 *
 * **改 SequenceBuilder.kt 之后必须重跑这个测试。**
 */
class SequenceBuilderTest {

    private class Fixture(
        val maxLen: Int,
        val headMaxLen: Int,
        val cases: List<Case>,
    )

    private class Case(
        val name: String,
        val situation: String,
        val options: List<String>,
        val criteria: List<String>,
        val markersPerOption: List<List<Int>>,
        val inputIds: List<List<Int>>,
    )

    private fun loadTokenizer(): Tokenizer {
        val json = javaClass.classLoader!!.getResourceAsStream("tokenizer.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Tokenizer.fromJson(json)
    }

    private fun loadFixture(): Fixture {
        val raw = javaClass.classLoader!!.getResourceAsStream("sequence-fixture.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = Json.asObject(Json.parse(raw))
        val cfg = Json.asObject(root["config"])

        val cases = Json.asArray(root["cases"]).map { c ->
            val o = Json.asObject(c)
            Case(
                name = Json.asString(o["name"]) ?: "?",
                situation = Json.asString(o["situation"]) ?: "",
                options = Json.asArray(o["options"]).map { Json.asString(it) ?: "" },
                criteria = Json.asArray(o["criteria"]).map { Json.asString(it) ?: "" },
                markersPerOption = Json.asArray(o["markersPerOption"]).map { m ->
                    Json.asArray(m).map { Json.asInt(it) ?: -1 }
                },
                inputIds = Json.asArray(o["inputIds"]).map { s ->
                    Json.asArray(s).map { Json.asInt(it) ?: -1 }
                },
            )
        }
        return Fixture(
            maxLen = Json.asInt(cfg["maxLen"]) ?: 512,
            headMaxLen = Json.asInt(cfg["headMaxLen"]) ?: 192,
            cases = cases,
        )
    }

    @Test
    fun `序列构造与参考实现逐 id 一致`() {
        val tok = loadTokenizer()
        val fx = loadFixture()
        val failures = ArrayList<String>()

        for (case in fx.cases) {
            case.options.forEachIndexed { i, option ->
                val q = SequenceBuilder.Question(
                    type = SequenceBuilder.QType.SCORE,
                    // ⚠️ 必须与 LayaAgent.scoreOptions 的措辞逐字一致，
                    //    也必须与 gen-sequence-fixture.mjs 的 scoreQ 一致。
                    instructions = "面对上述情境，选择「$option」有多好？",
                    criteria = case.criteria,
                )
                val built = SequenceBuilder.build(tok, case.situation, q, fx.maxLen, fx.headMaxLen)

                val expIds = case.inputIds[i]
                val expMarkers = case.markersPerOption[i]

                val idsOk = built.ids.toList() == expIds
                val markersOk = built.markers.toList() == expMarkers

                if (!idsOk || !markersOk) {
                    failures.add(
                        buildString {
                            append("用例「").append(case.name).append("」选项 ").append(i)
                            append("（").append(option).append("）\n")
                            if (!idsOk) {
                                append("  input_ids 不一致：期望长度 ").append(expIds.size)
                                append("，实际 ").append(built.ids.size).append('\n')
                                val d = expIds.indices.firstOrNull {
                                    it >= built.ids.size || built.ids[it] != expIds[it]
                                }
                                if (d != null) {
                                    append("  首个差异在下标 ").append(d).append("：")
                                    append("期望 ").append(expIds.getOrNull(d))
                                    append("，实际 ").append(built.ids.getOrNull(d)).append('\n')
                                }
                            }
                            if (!markersOk) {
                                append("  markers 不一致：期望 ").append(expMarkers)
                                append("，实际 ").append(built.markers.toList()).append('\n')
                            }
                        },
                    )
                }
            }
        }

        if (failures.isNotEmpty()) {
            val total = fx.cases.sumOf { it.options.size }
            throw AssertionError(
                buildString {
                    append("有 ").append(failures.size).append(" / ").append(total)
                    append(" 个序列不一致：\n\n")
                    failures.take(8).forEach { append(it).append('\n') }
                    if (failures.size > 8) append("...还有 ").append(failures.size - 8).append(" 个\n")
                },
            )
        }
    }

    @Test
    fun `夹具里的截断分支确实被触发`() {
        val fx = loadFixture()

        // 这些断言是为了确保夹具本身有效 —— 如果哪天夹具退化成只有简单用例，
        // 上面那个一致性测试会变成"通过但没测到东西"。
        val longSituation = fx.cases.first { it.name.contains("超长情境") }
        assertEquals("超长情境应该被截到 max_len", fx.maxLen, longSituation.inputIds[0].size)

        val manyOptions = fx.cases.first { it.name.contains("20选项") }
        assertEquals("20 选项的用例该有 20 个选项", 20, manyOptions.options.size)

        val maskCase = fx.cases.first { it.name.contains("特殊token") }
        assertTrue("情境里确实有 [MASK] 字面量", maskCase.situation.contains("[MASK]"))
    }

    private fun <T> List<T>.take8(): List<T> = take(8)

    /** 序列必须以 [CLS] 开头、以 [SEP] 结尾，这是模型的结构性要求。 */
    @Test
    fun `序列首尾是 CLS 与 SEP`() {
        val tok = loadTokenizer()
        val q = SequenceBuilder.Question(
            type = SequenceBuilder.QType.SCORE,
            instructions = "在这个情境下，「甲」这个选择有多合适？",
            criteria = ScoreCriteria.DEFAULT,
        )
        val built = SequenceBuilder.build(tok, "情境内容", q, 512, 192)

        assertEquals("首 token 应为 [CLS]", tok.clsId, built.ids.first())
        assertEquals("末 token 应为 [SEP]", tok.sepId, built.ids.last())
        assertEquals("4 个档位应有 4 个 marker", 4, built.markers.size)
        for (m in built.markers) {
            assertTrue("marker 位置越界: $m", m in built.ids.indices)
            assertEquals("marker 位置上应是 [MASK]", tok.maskId, built.ids[m])
        }
    }

    @Test
    fun `markers 严格递增`() {
        val tok = loadTokenizer()
        val q = SequenceBuilder.Question(
            type = SequenceBuilder.QType.SCORE,
            instructions = "评估这个选项",
            criteria = ScoreCriteria.DEFAULT,
        )
        val built = SequenceBuilder.build(tok, "情境", q, 512, 192)
        assertTrue("marker 应严格递增", built.markers.toList().zipWithNext().all { (a, b) -> a < b })
    }
}
