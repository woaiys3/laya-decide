package com.laya.decide.agent

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.laya.decide.core.Decision
import com.laya.decide.core.DecisionEngine
import com.laya.decide.core.MarginLabel
import com.laya.decide.core.RankedOption
import com.laya.decide.core.ScoreAnswer
import com.laya.decide.tokenizer.Json
import com.laya.decide.tokenizer.Tokenizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.ln

/**
 * 真正的端侧 Laya 推理。
 *
 * 完整复刻参考实现（`rl_common.build_sequence` + `rl_agent_api.RLAgent.system_one`）：
 *
 *   1. 每个选项构造一个 `score` 问题（一个 [MASK] 一个档位）
 *   2. 一次前向传播把该批所有问题一起算完
 *   3. logits 除以该（题型, 选项数）桶的温度，softmax
 *   4. 期望分 Σ i·p(i) 就是该选项的得分，归一化熵就是确定度
 *
 * 任何一步与参考实现不一致都会改变结果 —— 所以分词和序列构造都有逐 id 夹具测试。
 */
class LayaAgent private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tok: Tokenizer,
    private val config: LayaConfig,
) {

    /** 每个选项的原始推理结果。 */
    data class OptionScore(
        val option: String,
        val expected: Double,
        val probabilities: DoubleArray,
        val confidence: Double,
    )

    data class Outcome(
        val optionScores: List<OptionScore>,
        /** 使用的温度，便于排查"分数怎么这么平"这类问题。 */
        val temperature: Double,
        /** 实际送入模型的 token 总数（含 padding 前的真实长度）。 */
        val inputTokens: Int,
        val latencyMs: Long,
    )

    /**
     * 对每个选项评估「在该情境下有多合适」，返回排名。
     *
     * 注意这是**打分排序**而不是多选题：每个选项独立评估其适配程度。
     * 理由见 PROJECT.md —— choice 找的是语义最相近的标签，而决策要的是
     * 「哪一个最有利」，两者不是一回事。
     */
    fun decide(situation: String, options: List<String>): Decision {
        val outcome = scoreOptions(situation, options)
        val answers = outcome.optionScores.mapIndexed { i, s ->
            DecisionEngine.scoreKey(i) to ScoreAnswer(s.expected, s.probabilities.toList())
        }.toMap()
        return DecisionEngine.assemble(options, answers)
    }

    fun scoreOptions(
        situation: String,
        options: List<String>,
        criteria: List<String> = ScoreCriteria.DEFAULT,
    ): Outcome {
        require(options.isNotEmpty()) { "至少要有一个选项" }

        val t0 = System.currentTimeMillis()

        // 1) 每个选项构造一个 score 问题
        //
        // 措辞经过实测挑选：在真实模型上比较了 5 种提问方式，
        // 「选择「X」有多好？」这种直白措辞的跨选项区分度最好
        // （spread 0.428 vs 原先「适配程度」措辞的 0.303）。
        // 见 server/tools/prompt-experiment.mjs —— 要改措辞请先重跑那个实验。
        val questions = options.map { opt ->
            SequenceBuilder.Question(
                type = SequenceBuilder.QType.SCORE,
                instructions = "面对上述情境，选择「$opt」有多好？",
                criteria = criteria,
            )
        }

        val built = questions.map { q ->
            SequenceBuilder.build(tok, situation, q, config.maxLen, config.headMaxLen)
        }

        // 参考实现要求 marker 数等于档位数，否则说明选项塞不进 head 预算，
        // 此时给的是被截断的答案空间，必须报错而不是硬算。
        built.forEachIndexed { i, b ->
            if (b.markers.size != criteria.size) {
                throw IllegalStateException(
                    "选项「${options[i]}」的档位标记放不进 head_max_len=${config.headMaxLen}，请缩短选项文本或减少档位数",
                )
            }
        }

        val n = built.size
        val maxL = built.maxOf { it.ids.size }
        val k = criteria.size

        // 2) 组装 batch（右填充）
        val inputIds = LongArray(n * maxL) { tok.padId.toLong() }
        val attention = LongArray(n * maxL)
        val markerPos = LongArray(n * k)
        val markerMask = ByteArray(n * k)
        val qtype = LongArray(n) { SequenceBuilder.QType.SCORE.index.toLong() }

        var realTokens = 0
        built.forEachIndexed { r, b ->
            b.ids.forEachIndexed { j, v ->
                inputIds[r * maxL + j] = v.toLong()
                attention[r * maxL + j] = 1L
            }
            realTokens += b.ids.size
            b.markers.forEachIndexed { j, m ->
                markerPos[r * k + j] = m.toLong()
                markerMask[r * k + j] = 1
            }
        }

        // 3) 前向传播
        val shape2 = longArrayOf(n.toLong(), maxL.toLong())
        val shapeK = longArrayOf(n.toLong(), k.toLong())
        val shape1 = longArrayOf(n.toLong())

        var logits: FloatArray? = null

        OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape2).use { tIds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(attention), shape2).use { tAtt ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(markerPos), shapeK).use { tPos ->
                    // marker_mask 在模型里是 tensor(bool)。必须显式指定 OnnxJavaType.BOOL：
                    // 三参数的 ByteBuffer 重载会推断成 int8，然后被模型拒绝：
                    //   ORT_INVALID_ARGUMENT - Unexpected input data type.
                    //   Actual: (tensor(int8)) , expected: (tensor(bool))
                    OnnxTensor.createTensor(
                        env,
                        ByteBuffer.wrap(markerMask),
                        shapeK,
                        OnnxJavaType.BOOL,
                    ).use { tMask ->
                        OnnxTensor.createTensor(env, LongBuffer.wrap(qtype), shape1).use { tQtype ->
                            val inputs = mapOf(
                                "input_ids" to tIds,
                                "attention_mask" to tAtt,
                                "marker_pos" to tPos,
                                "marker_mask" to tMask,
                                "qtype" to tQtype,
                            )
                            session.run(inputs).use { result ->
                                val out = result.get("logits")
                                    .orElseThrow { IllegalStateException("模型没有输出 logits") }
                                @Suppress("UNCHECKED_CAST")
                                val buf = (out as OnnxTensor).floatBuffer
                                val arr = FloatArray(buf.remaining())
                                buf.get(arr)
                                logits = arr
                            }
                        }
                    }
                }
            }
        }

        val raw = logits ?: throw IllegalStateException("推理没有产生 logits")

        // 4) 温度 + softmax + 期望分
        val temp = config.temperatureFor(
            SequenceBuilder.QType.SCORE.index,
            k,
            SequenceBuilder.QType.SCORE.wireName,
        )

        val scores = options.mapIndexed { r, opt ->
            val p = softmaxWithTemperature(raw, r * k, k, temp)
            val expected = p.indices.fold(0.0) { acc, i -> acc + i * p[i] }
            OptionScore(
                option = opt,
                expected = expected,
                probabilities = p,
                // 置信度必须用**校准后**的分布算 —— 参考实现就是先除温度再算熵。
                confidence = confidenceFromProbs(p),
            )
        }

        return Outcome(
            optionScores = scores,
            temperature = temp,
            inputTokens = realTokens,
            latencyMs = System.currentTimeMillis() - t0,
        )
    }

    /** 先除以温度再 softmax。这一步决定概率分布，进而决定分数和置信度。 */
    private fun softmaxWithTemperature(logits: FloatArray, offset: Int, k: Int, temp: Double): DoubleArray {
        var zmax = Double.NEGATIVE_INFINITY
        val z = DoubleArray(k)
        for (i in 0 until k) {
            z[i] = logits[offset + i] / temp
            if (z[i] > zmax) zmax = z[i]
        }
        var sum = 0.0
        val e = DoubleArray(k)
        for (i in 0 until k) {
            e[i] = exp(z[i] - zmax)
            sum += e[i]
        }
        if (sum <= 0.0) return DoubleArray(k) { 1.0 / k }
        return DoubleArray(k) { e[it] / sum }
    }

    /**
     * Jev 风格的置信度：1 - 归一化熵。
     * 对应 rl_common.confidence_from_probs。
     */
    private fun confidenceFromProbs(p: DoubleArray): Double {
        val k = p.size
        if (k < 2) return 1.0
        var ent = 0.0
        for (x in p) ent -= x * ln(if (x < 1e-12) 1e-12 else x)
        return (1.0 - ent / ln(k.toDouble())).coerceIn(0.0, 1.0)
    }

    fun close() {
        runCatching { session.close() }
    }

    // -----------------------------------------------------------------------

    companion object {
        /**
         * 加载模型。会同时读 tokenizer 与配置，并在真正开始前做一次冒烟推理 ——
         * 宁可在加载阶段失败，也不要等用户点了按钮才发现模型不对。
         *
         * @param tokenizerJson tokenizer.json 的内容
         * @param configJson rl_agent_config.json 的内容
         * @param modelFile ONNX 模型文件
         * @param threads 推理线程数，默认取 CPU 核数（上限 4，再多收益递减）
         */
        fun load(
            modelFile: File,
            tokenizerJson: String,
            configJson: String,
            threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            warmUp: Boolean = true,
        ): LayaAgent {
            require(modelFile.isFile) { "模型文件不存在: ${modelFile.absolutePath}" }

            val tok = Tokenizer.fromJson(tokenizerJson)
            val cfg = LayaConfig.fromJson(configJson)

            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            val session = env.createSession(modelFile.absolutePath, opts)
            val agent = LayaAgent(env, session, tok, cfg)

            if (warmUp) {
                // 冒烟测试：跑一次最短的推理，确认输入输出名字和张量类型都对得上。
                // 这一步能把"模型文件损坏 / 用了不兼容的导出"这类问题挡在加载阶段。
                agent.scoreOptions("测试。", listOf("甲", "乙"))
            }
            return agent
        }
    }
}
