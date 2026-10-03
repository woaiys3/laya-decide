package com.laya.decide.agent

import android.content.Context
import java.io.File

/**
 * 端侧模型的加载管理器。
 *
 * 关心三件事：
 *   1. **只加载一次** —— 262MB 的模型解一次要好几秒，绝不能每次决策都重来
 *   2. **线程安全** —— 推理在 IO 线程池跑，可能并发
 *   3. **失败要说人话** —— 模型缺失、文件损坏、内存不足，分别给不同提示
 *
 * 不在这里做下载：下载有进度和 UI 交互，属于界面层的事。
 */
object ModelManager {

    private const val TAG = "LayaModel"

    @Volatile
    private var agent: LayaAgent? = null

    @Volatile
    private var loadError: String? = null

    private val lock = Any()

    /** 模型是否已经在内存里。 */
    val isLoaded: Boolean get() = agent != null

    /** 上次加载失败的原因，成功则为 null。 */
    val lastError: String? get() = loadError

    /**
     * 取得可用的 agent，必要时加载。
     *
     * @throws IllegalStateException 模型文件缺失或加载失败
     */
    fun acquire(context: Context): LayaAgent {
        agent?.let { return it }

        synchronized(lock) {
            agent?.let { return it }

            val modelFile = ModelFiles.find(context)
                ?: throw IllegalStateException(
                    "还没找到模型文件。点「下载模型」获取，或把 ${ModelFiles.FILE_NAME} " +
                        "手动放到 ${ModelFiles.targetFile(context).parentFile?.absolutePath}",
                )

            try {
                val tok = ModelFiles.readAsset(context, "tokenizer.json")
                val cfg = ModelFiles.readAsset(context, "rl_agent_config.json")

                android.util.Log.i(
                    TAG,
                    "开始加载模型: ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024} MB), " +
                        "堆上限=${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB, " +
                        "当前堆用量=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024}MB, " +
                        "ONNX 版本=${ai.onnxruntime.OrtEnvironment.getEnvironment().version}",
                )

                val t0 = System.currentTimeMillis()
                val loaded = LayaAgent.load(modelFile, tok, cfg)
                android.util.Log.i(TAG, "模型加载完成，用时 ${System.currentTimeMillis() - t0}ms")

                agent = loaded
                loadError = null
                return loaded
            } catch (e: OutOfMemoryError) {
                android.util.Log.e(TAG, "加载模型内存不足", e)
                loadError = "内存不足，加载模型失败。关掉一些后台应用再试。"
                throw IllegalStateException(loadError, e)
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "模型加载失败", e)
                loadError = "模型加载失败：${e.javaClass.simpleName}: ${e.message}"
                throw IllegalStateException(loadError, e)
            }
        }
    }

    /** 释放模型占用的内存。切到别的后端时调用。 */
    fun release() {
        synchronized(lock) {
            agent?.close()
            agent = null
        }
    }
}
