package com.laya.decide.backend

import com.laya.decide.core.Decision
import com.laya.decide.core.DecisionEngine
import com.laya.decide.core.DemoScorer

/** 推理后端。端侧模型和演示模式各一个实现，上层不用改。 */
interface InferenceBackend {
    val mode: BackendMode

    /**
     * 对 options 里的每个选项评估「在 situation 下有多合适」，返回排名。
     *
     * 注意：这里是「每个选项独立打一个序数分再排序」，不是多选题。
     * 理由见 PROJECT.md 第一节 —— choice 找的是语义最相近的标签，
     * 而决策要的是「哪一个最有利」，两者不是一回事。
     */
    fun decide(situation: String, options: List<String>): Decision
}

enum class BackendMode(val storageKey: String) {
    /** 本地启发式，不联网、不是 AI。 */
    DEMO("demo"),

    /** 端侧 ONNX 模型，完全离线。 */
    LOCAL("local");

    companion object {
        fun fromKey(key: String?): BackendMode =
            entries.firstOrNull { it.storageKey == key } ?: DEMO
    }
}

/** 请求失败时抛出，带一句能直接显示给用户的话。 */
class BackendException(message: String) : Exception(message)

// ---------------------------------------------------------------------------

/** 演示后端：纯本地，不联网。 */
class DemoBackend : InferenceBackend {
    override val mode = BackendMode.DEMO

    override fun decide(situation: String, options: List<String>): Decision {
        val answers = DemoScorer.scoreAll(situation, options)
        return DecisionEngine.assemble(options, answers)
    }
}

// ---------------------------------------------------------------------------

/**
 * 端侧后端：模型跑在手机上，完全离线。
 *
 * 需要 Context 才能定位模型文件；由 [backendFor] 从界面层注入。
 */
class LocalBackend(private val context: android.content.Context) : InferenceBackend {
    override val mode = BackendMode.LOCAL

    override fun decide(situation: String, options: List<String>): Decision {
        val agent = try {
            com.laya.decide.agent.ModelManager.acquire(context)
        } catch (e: IllegalStateException) {
            throw BackendException(e.message ?: "端侧模型不可用")
        }

        return try {
            agent.decide(situation, options)
        } catch (e: OutOfMemoryError) {
            throw BackendException("推理时内存不足，试试减少选项数量，或关掉后台应用。")
        } catch (e: Exception) {
            throw BackendException("端侧推理失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
}

// ---------------------------------------------------------------------------

fun backendFor(
    mode: BackendMode,
    context: android.content.Context,
): InferenceBackend = when (mode) {
    BackendMode.DEMO -> DemoBackend()
    BackendMode.LOCAL -> LocalBackend(context.applicationContext)
}
