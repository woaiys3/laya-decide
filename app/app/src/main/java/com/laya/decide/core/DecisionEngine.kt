package com.laya.decide.core

import kotlin.math.exp
import kotlin.math.ln

/**
 * 决策核心逻辑 —— 与 server/laya-core.mjs 一一对应。
 *
 * 为什么两端各有一份：手机端要用它做即时校验和演示模式的打分；服务端要用它
 * 做同样的事。两边逻辑必须一致，所以 core/DecisionEngineTest.kt 里的用例
 * 与 server/test/core.test.mjs 是同一套。
 */
object DecisionEngine {

    /** 序数评分档位。改这里会同时影响提问和归一化。 */
    val SCORE_CRITERIA = listOf(
        "很不合适，弊大于利",
        "不太合适，勉强可以",
        "比较合适，值得考虑",
        "明显最佳，强烈推荐",
    )

    val MAX_SCORE: Double = (SCORE_CRITERIA.size - 1).toDouble() // 3.0

    /** 编码器 512 token 上下文，中文按字符保守估。 */
    const val SITUATION_CHAR_LIMIT = 1200

    /** 单个选项的 token 预算（head_max_len = 192）。 */
    const val OPTION_CHAR_LIMIT = 60

    const val MIN_OPTIONS = 2

    /** 模型自己建议单个 choice 问题不超过约 20 个选项。 */
    const val MAX_OPTIONS = 20

    fun scoreKey(index: Int) = "opt_$index"

    /**
     * 把模型返回的评分整理成排名。
     *
     * 排序规则：分数降序；同分时保持用户输入顺序，避免并列的选项在界面上
     * 随机跳动。
     */
    fun rank(options: List<String>, answers: Map<String, ScoreAnswer>): List<RankedOption> {
        val rows = options.mapIndexed { index, option ->
            val a = answers[scoreKey(index)]
            val raw = a?.score?.takeIf { it.isFinite() } ?: 0.0
            val score = raw.coerceIn(0.0, MAX_SCORE)
            RankedOption(
                option = option,
                index = index,
                score = score,
                normalized = if (MAX_SCORE > 0) score / MAX_SCORE else 0.0,
                confidence = confidenceFrom(a?.distribution),
            )
        }

        return rows.sortedWith(
            compareByDescending<RankedOption> { it.score }.thenBy { it.index },
        )
    }

    /**
     * 用分布算「这个评分有多确定」：归一化熵，1 = 完全确定，0 = 完全均匀。
     *
     * 这是 score 原语相对 choice 的一个额外好处 —— 每一项都能拿到自己的
     * 确定度，而不是选项之间互相 softmax 出来的虚高概率。
     */
    fun confidenceFrom(distribution: List<Double>?): Double {
        if (distribution == null || distribution.isEmpty()) return 0.0

        val clean = distribution.map { if (it.isFinite() && it > 0) it else 0.0 }
        val sum = clean.sum()
        if (sum <= 0.0) return 0.0

        val n = clean.size
        if (n <= 1) return 1.0

        var h = 0.0
        for (v in clean) {
            val p = v / sum
            if (p > 0.0) h -= p * ln(p)
        }
        val hMax = ln(n.toDouble())
        val certainty = if (hMax > 0.0) 1.0 - h / hMax else 1.0
        return certainty.coerceIn(0.0, 1.0)
    }

    /**
     * 排名是否不可信 —— 需要向用户点明"这等于没选出来"。
     *
     * 判据：最高分与最低分之差 < [UNRELIABLE_SPREAD]。
     *
     * 为什么不能只看"分差"（第一名 vs 第二名）：三项 2.30/2.25/2.20 的
     * 首名分差很小，但整体确实分出了层次；而三项 0.12/0.12/0.12 才是
     * 真的什么都没分出来。用极差更贴合"这次排序有没有信息量"。
     */
    fun isRankingUnreliable(ranked: List<RankedOption>): Boolean {
        if (ranked.size < 2) return false
        val spread = ranked.first().score - ranked.last().score
        return spread < UNRELIABLE_SPREAD
    }

    /**
     * 低于这个极差就认为排序没有信息量。
     *
     * 0.10 是实测标定的：基座模型在它真正能分辨的场景里极差通常在
     * 0.3 以上（如 2.58 vs 2.29 = 0.29），而分不出来时极差普遍在 0.05 以下。
     * 取 0.10 落在两者之间。
     */
    const val UNRELIABLE_SPREAD = 0.10

    /** 把所有选项的分数整理成"这个情境下最合适的是哪个"。 */
    fun assemble(options: List<String>, answers: Map<String, ScoreAnswer>): Decision {
        val ranked = rank(options, answers)

        if (ranked.isEmpty()) {
            return Decision(
                ranked = emptyList(),
                pick = null,
                margin = null,
                marginLabel = MarginLabel.SINGLE,
                closeCall = false,
                note = "没有选项。",
            )
        }

        val pick = ranked[0].option
        val margin = if (ranked.size > 1) ranked[0].score - ranked[1].score else null

        // 按归一化分差判断，跟具体档位数解耦。
        val normMargin = margin?.div(MAX_SCORE)
        val label = when {
            margin == null -> MarginLabel.SINGLE
            normMargin!! < 0.10 -> MarginLabel.TIGHT
            normMargin < 0.25 -> MarginLabel.LEANING
            else -> MarginLabel.CLEAR
        }

        return Decision(
            ranked = ranked,
            pick = pick,
            margin = margin,
            marginLabel = label,
            closeCall = label == MarginLabel.TIGHT,
            note = buildNote(ranked, label),
        )
    }

    private fun buildNote(ranked: List<RankedOption>, label: MarginLabel): String {
        val first = ranked[0]
        if (ranked.size == 1) {
            return "只有一个选项，模型给出 ${fmt(first.score)} / ${fmt(MAX_SCORE)}。"
        }
        val second = ranked[1]
        return when (label) {
            MarginLabel.TIGHT ->
                "「${first.option}」和「${second.option}」分数几乎持平" +
                    "（${fmt(first.score)} vs ${fmt(second.score)}）。" +
                    "模型在这两个之间没有明显偏好，建议按你自己的风险偏好来定。"

            MarginLabel.LEANING ->
                "模型略偏向「${first.option}」（${fmt(first.score)} vs ${fmt(second.score)}），" +
                    "但没有拉开明显差距。"

            else ->
                "模型明确倾向「${first.option}」（${fmt(first.score)} vs ${fmt(second.score)}）。"
        }
    }

    private fun fmt(x: Double) = String.format(java.util.Locale.US, "%.2f", x)

    // -----------------------------------------------------------------------
    // 输入校验
    // -----------------------------------------------------------------------

    sealed interface Validation {
        data object Ok : Validation
        data class Fail(val message: String) : Validation
    }

    fun validate(situation: String, options: List<String>): Validation {
        if (situation.isBlank()) return Validation.Fail("请先填写情境描述。")
        if (situation.length > SITUATION_CHAR_LIMIT) {
            return Validation.Fail("情境描述过长（${situation.length} 字），请压缩到 $SITUATION_CHAR_LIMIT 字以内。")
        }

        val cleaned = cleanOptions(options)
        if (cleaned.size < MIN_OPTIONS) return Validation.Fail("至少需要 $MIN_OPTIONS 个选项。")
        if (cleaned.size > MAX_OPTIONS) return Validation.Fail("最多支持 $MAX_OPTIONS 个选项。")

        cleaned.firstOrNull { it.length > OPTION_CHAR_LIMIT }?.let {
            return Validation.Fail(
                "选项「${it.take(12)}…」太长（${it.length} 字），请压缩到 $OPTION_CHAR_LIMIT 字以内。",
            )
        }

        return Validation.Ok
    }

    /** 去空白、去空项、去重且保序。 */
    fun cleanOptions(options: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (o in options) {
            val s = o.trim()
            if (s.isNotEmpty()) seen.add(s)
        }
        return seen.toList()
    }
}
