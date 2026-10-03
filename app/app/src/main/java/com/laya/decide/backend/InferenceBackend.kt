package com.laya.decide.backend

import com.laya.decide.core.Decision
import com.laya.decide.core.DecisionEngine
import com.laya.decide.core.DemoScorer
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** 推理后端。以后要加端侧 ONNX，只需要再写一个实现类，上层不用改。 */
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

    /** 供「测试连接」按钮使用。 */
    fun describe(): String
}

enum class BackendMode(val storageKey: String) {
    /** 本地启发式，不联网、不是 AI。 */
    DEMO("demo"),

    /** 连 PC 上的 Laya 服务，真实推理。 */
    HTTP("http"),

    /** 端侧模型，暂未实现。 */
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

    override fun describe() = "演示模式（本地启发式，不是 AI）"
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

    override fun describe(): String {
        if (!com.laya.decide.agent.ModelFiles.isReady(context)) {
            return "端侧模式：还没下载模型"
        }
        return if (com.laya.decide.agent.ModelManager.isLoaded) {
            "端侧模式：模型已加载，可离线使用"
        } else {
            "端侧模式：模型已就位（首次推理时会加载）"
        }
    }
}

// ---------------------------------------------------------------------------

/**
 * PC 服务后端。
 *
 * 通信协议见 server/server.mjs；用 HttpURLConnection 而不是 OkHttp，
 * 少一个依赖就少一处构建失败的可能。
 */
class HttpLayaBackend(private val rawBase: String) : InferenceBackend {
    override val mode = BackendMode.HTTP

    private val base: String = normalize(rawBase)

    override fun decide(situation: String, options: List<String>): Decision {
        val payload = JSONObject().apply {
            put("situation", situation)
            put("options", JSONArray(options))
        }

        val resp = post("/decide", payload.toString())

        if (!resp.optBoolean("ok", false)) {
            throw BackendException(resp.optString("error", "服务返回了错误"))
        }

        val decision = resp.optJSONObject("decision")
            ?: throw BackendException("服务返回里没有 decision 字段")

        val rankedJson = decision.optJSONArray("ranked") ?: JSONArray()
        val ranked = (0 until rankedJson.length()).map { i ->
            val o = rankedJson.getJSONObject(i)
            com.laya.decide.core.RankedOption(
                option = o.optString("option"),
                index = o.optInt("index", i),
                score = o.optDouble("score", 0.0),
                normalized = o.optDouble("normalized", 0.0),
                confidence = o.optDouble("confidence", 0.0),
            )
        }

        return Decision(
            ranked = ranked,
            pick = if (decision.isNull("pick")) null else decision.optString("pick"),
            margin = if (decision.isNull("margin")) null else decision.optDouble("margin"),
            marginLabel = parseLabel(decision.optString("marginLabel", "")),
            closeCall = decision.optBoolean("closeCall", false),
            note = decision.optString("note", ""),
        )
    }

    override fun describe(): String {
        val resp = get("/health")
        val mode = resp.optString("mode", "?")
        return "连接成功｜模式：$mode"
    }

    private fun parseLabel(raw: String) = when (raw) {
        "single" -> com.laya.decide.core.MarginLabel.SINGLE
        "胶着" -> com.laya.decide.core.MarginLabel.TIGHT
        "有倾向" -> com.laya.decide.core.MarginLabel.LEANING
        else -> com.laya.decide.core.MarginLabel.CLEAR
    }

    // -----------------------------------------------------------------------

    private fun get(path: String): JSONObject {
        val conn = open(path, "GET")
        return try {
            readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, body: String): JSONObject {
        val conn = open(path, "POST")
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val url = try {
            URL(base + path)
        } catch (e: Exception) {
            throw BackendException("服务地址不合法：$base")
        }

        return try {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8_000
                // 真实模型首次调用要下载/加载，8 秒不够；加载完之后约 140ms。
                readTimeout = 180_000
                setRequestProperty("Accept", "application/json")
            }
        } catch (e: Exception) {
            throw BackendException("连不上 $base —— ${e.message ?: "网络错误"}")
        }
    }

    private fun readBody(conn: HttpURLConnection): JSONObject {
        val code = try {
            conn.responseCode
        } catch (e: Exception) {
            throw BackendException("连不上 ${base} —— ${e.message ?: "网络错误"}")
        }

        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

        if (text.isBlank()) {
            throw BackendException("服务返回为空（HTTP $code）")
        }

        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw BackendException("服务返回的不是合法 JSON（HTTP $code）")
        }

        if (code !in 200..299) {
            throw BackendException(json.optString("error", "HTTP $code"))
        }
        return json
    }

    companion object {
        /** 允许用户只填 IP，自动补上 http:// 和端口。 */
        fun normalize(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) s = "127.0.0.1:8931"
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            while (s.endsWith("/")) s = s.dropLast(1)
            // 没写端口就补默认端口。
            val afterScheme = s.substringAfter("://")
            if (!afterScheme.contains(":")) s = "$s:8931"
            return s
        }
    }
}

// ---------------------------------------------------------------------------

fun backendFor(
    mode: BackendMode,
    server: String,
    context: android.content.Context,
): InferenceBackend = when (mode) {
    BackendMode.DEMO -> DemoBackend()
    BackendMode.HTTP -> HttpLayaBackend(server)
    BackendMode.LOCAL -> LocalBackend(context.applicationContext)
}
