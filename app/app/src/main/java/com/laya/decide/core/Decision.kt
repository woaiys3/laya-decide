package com.laya.decide.core

/**
 * 决策数据模型 —— 纯 Kotlin，不依赖任何 Android 类，可以在 JVM 上直接单测。
 */

/** 后端返回的单个评分项。 */
data class ScoreAnswer(
    val score: Double,
    /** 模型给出的档位分布，用来算「它有多确定」。可能为空。 */
    val distribution: List<Double>? = null,
)

/** 排名里的一行。 */
data class RankedOption(
    val option: String,
    /** 在原选项列表里的下标，界面靠它对齐。 */
    val index: Int,
    val score: Double,
    /** 归一化到 0..1 的分数。 */
    val normalized: Double,
    /** 由分布算出的确定度 0..1。 */
    val confidence: Double,
)

/** 分差语义。 */
enum class MarginLabel {
    /** 只有一个选项。 */
    SINGLE,

    /** 前两名几乎持平，模型没有明显偏好。 */
    TIGHT,

    /** 有倾向但没拉开。 */
    LEANING,

    /** 明确倾向。 */
    CLEAR,
}

/** 一次完整决策的结果。 */
data class Decision(
    val ranked: List<RankedOption>,
    val pick: String?,
    /** 第一名与第二名的分差；单选项时为 null。 */
    val margin: Double?,
    val marginLabel: MarginLabel,
    val closeCall: Boolean,
    val note: String,
)
