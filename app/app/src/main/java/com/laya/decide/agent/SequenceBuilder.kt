package com.laya.decide.agent

import com.laya.decide.tokenizer.Tokenizer

/**
 * 评分档位与温度分桶。
 *
 * 温度必须用**校准后的**分布算置信度 —— 参考实现是先把 logits 除以温度再 softmax 再算熵。
 * 直接从原始 logits 算熵会得到错误的置信度。
 */
object ScoreCriteria {

    /** 4 档。改这里会同时影响提问文本和归一化，App 的分数范围也随之变化。 */
    val DEFAULT = listOf(
        "很不合适，弊大于利",
        "不太合适，勉强可以",
        "比较合适，值得考虑",
        "明显最佳，强烈推荐",
    )

    /**
     * 温度分桶 key，对应 rl_common.temp_bucket：
     * 2 / 3-5 / 6-10 / 11+，再拼上题型。
     */
    fun bucket(qtypeName: String, k: Int): String {
        val size = when {
            k <= 2 -> "2"
            k <= 5 -> "3-5"
            k <= 10 -> "6-10"
            else -> "11+"
        }
        return "$qtypeName:$size"
    }
}

/**
 * 模型配置，来自 rl_agent_config.json。
 */
data class LayaConfig(
    val maxLen: Int = 512,
    val headMaxLen: Int = 192,
    val temperature: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0),
    val temperatureByOptions: Map<String, Double> = emptyMap(),
) {
    /** 按（题型, 选项数）取温度，取不到就退回按题型的温度。 */
    fun temperatureFor(qtypeIndex: Int, k: Int, qtypeName: String): Double {
        temperatureByOptions[ScoreCriteria.bucket(qtypeName, k)]?.let { return it }
        return temperature.getOrElse(qtypeIndex) { 1.0 }
    }

    companion object {
        /**
         * 从 rl_agent_config.json 解析。
         *
         * temperature 是长度 3 的数组（choice/score/noul），
         * temperature_by_options 是 "score:3-5" 这样的键。
         */
        fun fromJson(json: String): LayaConfig {
            val root = com.laya.decide.tokenizer.Json.asObject(
                com.laya.decide.tokenizer.Json.parse(json),
            )
            val J = com.laya.decide.tokenizer.Json

            val temps = J.asArray(root["temperature"]).map { v ->
                when (v) {
                    is Double -> v
                    is Long -> v.toDouble()
                    else -> 1.0
                }
            }.let { if (it.size == 3) it.toDoubleArray() else doubleArrayOf(1.0, 1.0, 1.0) }

            val byOpts = LinkedHashMap<String, Double>()
            for ((k, v) in J.asObject(root["temperature_by_options"])) {
                val d = when (v) {
                    is Double -> v
                    is Long -> v.toDouble()
                    else -> null
                }
                if (d != null) byOpts[k] = d
            }

            return LayaConfig(
                maxLen = J.asInt(root["max_len"]) ?: 512,
                headMaxLen = J.asInt(root["head_max_len"]) ?: 192,
                temperature = temps,
                temperatureByOptions = byOpts,
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is LayaConfig && maxLen == other.maxLen && headMaxLen == other.headMaxLen

    override fun hashCode(): Int = maxLen * 31 + headMaxLen
}

/**
 * 序列构造 —— `rl_common.build_sequence` / `dist/sequence.js` 的 Kotlin 移植。
 *
 *   [CLS] <type> question: <instructions> [SEP] [MASK] opt₀ [MASK] opt₁ … [SEP] <state> [SEP]
 *
 * 返回 input_ids 和每个选项 [MASK] 标记的位置。
 *
 * 这个类在 JVM 上有逐 id 夹具测试（SequenceBuilderTest），
 * 夹具由参考实现生成：server/tools/gen-sequence-fixture.mjs。
 */
object SequenceBuilder {

    /** 单个选项文本最多取 48 个 token。 */
    private const val OPTION_TOKEN_CAP = 48

    /** head 里至少留给问题本身的 token 数。 */
    private const val MIN_HEAD_TOKENS = 8

    /** 选项总预算低于这个值就整体收缩。 */
    private const val MIN_OPTION_BUDGET = 16

    /** 收缩时每个选项至少保留的 token 数。 */
    private const val MIN_PER_OPTION = 4

    /**
     * 题型。索引与模型约定一致：choice=0, score=1, noul=2。
     */
    enum class QType(val index: Int, val wireName: String) {
        CHOICE(0, "choice"),
        SCORE(1, "score"),
        NOUL(2, "noul"),
    }

    data class Question(
        val type: QType,
        val instructions: String,
        /** score 用档位列表；choice 用「标签 -> 说明或 null」；noul 一般不用。 */
        val criteria: List<String>,
    )

    data class Built(val ids: IntArray, val markers: IntArray)

    /**
     * 渲染选项文本。必须与参考实现逐字一致。
     *
     * - score：`level 0: 很不合适，弊大于利`
     */
    fun renderOptions(q: Question): List<String> = when (q.type) {
        QType.SCORE -> q.criteria.mapIndexed { i, c -> "level $i: $c" }
        QType.CHOICE -> q.criteria
        QType.NOUL -> listOf(
            "false: no, the statement does not hold",
            "true: yes, the statement holds",
        )
    }

    /**
     * 构造序列。
     *
     * @param state 情境文本（已是字符串，不再做 JSON 序列化）
     */
    fun build(
        tok: Tokenizer,
        state: String,
        q: Question,
        maxLen: Int,
        headMaxLen: Int,
    ): Built {
        // 问题/选项/情境里的 [MASK] 字面量要替换成空格，否则会被当成标记。
        val scrub = { s: String -> s.replace(Tokenizer.MASK, " ") }

        val opts = renderOptions(q)

        var headIds = tok.encode("${q.type.wireName} question: ${scrub(q.instructions)}")

        var optIds: List<IntArray> = opts.map { o ->
            val body = tok.encode(" " + scrub(o))
            val capped = if (body.size > OPTION_TOKEN_CAP) body.copyOf(OPTION_TOKEN_CAP) else body
            IntArray(capped.size + 1).also {
                it[0] = tok.maskId
                System.arraycopy(capped, 0, it, 1, capped.size)
            }
        }

        var optBudget = headMaxLen - optIds.sumOf { it.size }
        if (optBudget < MIN_OPTION_BUDGET) {
            // 选项太多或太长：均匀收缩每个选项的文本
            val per = maxOf(MIN_PER_OPTION, (headMaxLen - MIN_OPTION_BUDGET) / maxOf(1, optIds.size))
            optIds = optIds.map { if (it.size > per) it.copyOf(per) else it }
            optBudget = headMaxLen - optIds.sumOf { it.size }
        }

        val headTake = minOf(headIds.size, maxOf(MIN_HEAD_TOKENS, optBudget))
        if (headIds.size > headTake) headIds = headIds.copyOf(headTake)

        // 组装：[CLS] + head + [SEP] + 各选项 + [SEP]
        val seq = ArrayList<Int>(headIds.size + optIds.sumOf { it.size } + 8)
        seq.add(tok.clsId)
        headIds.forEach { seq.add(it) }
        seq.add(tok.sepId)

        val markers = ArrayList<Int>(optIds.size)
        for (o in optIds) {
            markers.add(seq.size)
            o.forEach { seq.add(it) }
        }
        seq.add(tok.sepId)

        // 情境：截到剩余空间
        val room = maxOf(0, maxLen - seq.size - 1)
        var st = tok.encode(scrub(state))
        if (st.size > room) st = st.copyOf(room)
        st.forEach { seq.add(it) }
        seq.add(tok.sepId)

        // 整体截断到 maxLen
        val finalIds = if (seq.size > maxLen) {
            IntArray(maxLen) { seq[it] }
        } else {
            IntArray(seq.size) { seq[it] }
        }

        val finalMarkers = markers.filter { it < maxLen }.toIntArray()

        return Built(finalIds, finalMarkers)
    }
}
