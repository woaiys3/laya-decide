package com.laya.decide.agent

import com.laya.decide.tokenizer.Tokenizer
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把 Kotlin 侧构造的真实序列 dump 成 JSON，供 tools/cross-check.mjs
 * 喂给真实的 int4 ONNX 模型做交叉验证。
 *
 * 这一步证的是「Kotlin 生成的序列能被真实模型正确接受并产出可用 logits」，
 * 而夹具测试证的是「和参考实现的 JS 逐 id 一致」。两个角度互补。
 *
 * 输出路径：可被 -Dlaya.dump.out=... 覆盖，默认写到模块 build 目录。
 */
class SequenceDumpTest {

    private fun loadTokenizer(): Tokenizer {
        val json = javaClass.classLoader!!.getResourceAsStream("tokenizer.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Tokenizer.fromJson(json)
    }

    @Test
    fun `dump 真实序列供交叉验证`() {
        val tok = loadTokenizer()
        val out = File(
            System.getProperty("laya.dump.out")
                ?: (System.getProperty("laya.dump.dir") ?: "build") + "/kotlin-sequence-dump.json",
        )
        out.parentFile?.mkdirs()

        val criteria = ScoreCriteria.DEFAULT

        data class Case(val name: String, val situation: String, val options: List<String>)

        val cases = listOf(
            Case(
                "中文3选项",
                "我拿到一个创业公司offer，薪资降30%，但有期权和很大话语权。现在在大厂做螺丝钉，房贷压力不小。",
                listOf("接受这个offer", "留在现在的大厂", "继续面其他公司"),
            ),
            Case(
                "中文5选项",
                "周末想出去玩，预算500，两个人，不想开车太远，想放松一下。",
                listOf("本市周边爬山", "去隔壁城市逛吃", "在家躺平看电影", "露营野餐", "逛博物馆"),
            ),
            Case(
                "英文3选项",
                "I got an offer from a startup: 30% pay cut but equity and a real say. I currently coast at a big company and have a mortgage.",
                listOf("Take the offer", "Stay", "Keep interviewing"),
            ),
            Case(
                "含特殊token字面量",
                "情境里带了 [MASK] 和 [CLS] 字面量，还有 [SEP]。",
                listOf("选项里有[MASK]", "干净选项"),
            ),
            Case(
                "短情境长选项",
                "选吧。",
                listOf("这是一个非常长的选项文本需要被截断处理".repeat(8), "短"),
            ),
        )

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"padId\": ").append(tok.padId).append(",\n")
        sb.append("  \"qtypeIndex\": 1,\n")
        sb.append("  \"criteria\": [").append(criteria.joinToString(", ") { "\"${esc(it)}\"" }).append("],\n")
        sb.append("  \"cases\": [\n")

        cases.forEachIndexed { ci, c ->
            sb.append("    {\n")
            sb.append("      \"name\": \"").append(esc(c.name)).append("\",\n")
            sb.append("      \"situation\": \"").append(esc(c.situation)).append("\",\n")
            sb.append("      \"options\": [")
                .append(c.options.joinToString(", ") { "\"${esc(it)}\"" }).append("],\n")
            sb.append("      \"sequences\": [\n")

            c.options.forEachIndexed { oi, opt ->
                val q = SequenceBuilder.Question(
                    type = SequenceBuilder.QType.SCORE,
                    instructions = "面对上述情境，选择「$opt」有多好？",
                    criteria = criteria,
                )
                val built = SequenceBuilder.build(tok, c.situation, q, 512, 192)

                sb.append("        {\"ids\": [").append(built.ids.joinToString(", "))
                    .append("], \"markers\": [").append(built.markers.joinToString(", "))
                    .append("]}").append(if (oi == c.options.lastIndex) "\n" else ",\n")
            }

            sb.append("      ]\n")
            sb.append("    }").append(if (ci == cases.lastIndex) "\n" else ",\n")
        }

        sb.append("  ]\n")
        sb.append("}\n")

        out.writeText(sb.toString(), Charsets.UTF_8)
        println("[dump] 写出 ${cases.size} 个用例 -> ${out.absolutePath}")

        assertTrue("dump 文件应已生成", out.isFile && out.length() > 1000)
    }

    private fun esc(s: String): String {
        val o = StringBuilder()
        for (c in s) {
            when (c) {
                '"' -> o.append("\\\"")
                '\\' -> o.append("\\\\")
                '\n' -> o.append("\\n")
                '\r' -> o.append("\\r")
                '\t' -> o.append("\\t")
                else -> if (c.code < 0x20) o.append("\\u%04x".format(c.code)) else o.append(c)
            }
        }
        return o.toString()
    }
}
