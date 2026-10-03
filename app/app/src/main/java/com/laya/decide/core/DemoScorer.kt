package com.laya.decide.core

import kotlin.math.exp
import kotlin.math.pow

/**
 * 演示模式打分器 —— 是 server/inference.mjs 里 mockScore 的 Kotlin 版本。
 *
 * ⚠️ 这不是 AI。它只是让 App 在没有 PC 服务时也能走通全流程，顺便让你看到
 *    界面长什么样。真实推理请切到「PC 服务」模式。
 *
 * 打分依据：选项与情境的中文 bigram 重合度 + 情境里的倾向词 + 一点确定性扰动，
 * 最后做一次对比度拉伸，让「明显更贴合情境」的选项真的能拉开差距。
 */
object DemoScorer {

    private val POSITIVE = listOf("想", "希望", "喜欢", "重要", "优先", "愿意", "倾向", "值得", "适合")
    private val NEGATIVE = listOf("担心", "风险", "不想", "害怕", "顾虑", "压力", "不能", "避免")

    /** 与 Node 端使用同一个字符串哈希，保证两端演示结果一致。 */
    private fun hash(s: String): Long {
        var h = 2166136261L
        for (c in s) {
            h = h xor c.code.toLong()
            h = (h * 16777619L) and 0xFFFFFFFFL
        }
        return h
    }

    /**
     * 相邻两字符一组。
     *
     * 中文用单字重合会把「的 / 我 / 在」这类高频字算进去，导致所有选项分数
     * 都差不多，看不出区分度。bigram 能抓到真正的词。
     */
    private fun bigrams(s: String): List<String> {
        val filtered = s.filter { it.isLetterOrDigit() }
        if (filtered.isEmpty()) return emptyList()
        if (filtered.length == 1) return listOf(filtered)
        return (0 until filtered.length - 1).map { filtered.substring(it, it + 2) }
    }

    /** 把 0..MAX 的分数向两端推，中间区域变化最剧烈。 */
    private fun contrast(x: Double): Double {
        val c = DecisionEngine.MAX_SCORE / 2.0
        val k = 1.7
        val stretched = c + (x - c) * k
        return stretched.coerceIn(0.0, DecisionEngine.MAX_SCORE)
    }

    fun score(situation: String, option: String): ScoreAnswer {
        val s = situation.lowercase()
        val o = option.lowercase()

        val a = bigrams(o)
        val b = bigrams(s).toHashSet()
        val hits = a.count { it in b }
        val overlap = if (a.isNotEmpty()) hits.toDouble() / a.size else 0.0

        val posCount = POSITIVE.count { s.contains(it) }
        val negCount = NEGATIVE.count { s.contains(it) }

        // 确定性扰动：同一个输入每次结果一致。
        val jitter = (hash(o) % 1000).toDouble() / 1000.0

        var raw = overlap * 4.0 + (posCount - negCount) * 0.15 + jitter * 0.6
        raw = raw.coerceIn(0.0, DecisionEngine.MAX_SCORE)
        raw = contrast(raw)

        // 造一个围绕 raw 的分布，让 confidence 有东西可算。
        val sigma = 0.5
        val dist = DecisionEngine.SCORE_CRITERIA.indices.map { k ->
            val d = (k - raw) / sigma
            exp(-0.5 * d.pow(2))
        }
        val sum = dist.sum()
        val normalized = if (sum > 0) dist.map { it / sum } else dist

        // 期望值 = Σ k·p(k)，与真实 score 原语的语义一致。
        val expected = normalized.foldIndexed(0.0) { k, acc, p -> acc + k * p }

        return ScoreAnswer(score = expected, distribution = normalized)
    }

    fun scoreAll(situation: String, options: List<String>): Map<String, ScoreAnswer> =
        options.mapIndexed { i, o -> DecisionEngine.scoreKey(i) to score(situation, o) }.toMap()
}
