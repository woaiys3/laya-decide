package com.laya.decide.mcp

import android.content.Context
import android.util.Log
import com.laya.decide.agent.ModelFiles
import com.laya.decide.agent.ModelManager
import com.laya.decide.backend.BackendException
import com.laya.decide.backend.LocalBackend
import com.laya.decide.core.Decision
import com.laya.decide.core.DecisionEngine
import com.laya.decide.core.MarginLabel
import com.laya.decide.tokenizer.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * App 内置的 MCP 服务（Streamable HTTP，单端点 POST /mcp）。
 *
 * 为什么不用 stdio：MCP 客户端在电脑上，够不到手机进程，只能走局域网。
 * 所以这里用最简单的 HTTP + JSON-RPC：客户端 POST，服务端回 JSON。
 * 不提供 SSE 流（GET /mcp 返回 405），MCP 规范允许。
 *
 * 默认只绑 127.0.0.1；用户在设置里打开「允许局域网访问」才绑 0.0.0.0。
 * 没有任何鉴权 —— 界面里必须写清楚。
 *
 * 服务跑在 App 进程里，App 被杀就没了；不做前台 Service（这个玩具不值得）。
 */
class McpHttpServer private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "LayaMcp"

        const val DEFAULT_PORT = 8940

        /** 协议版本白名单，和 SDK 1.32 的 SUPPORTED_PROTOCOL_VERSIONS 对齐。 */
        private val SUPPORTED_PROTOCOLS = setOf(
            "2025-11-25",
            "2025-06-18",
            "2025-03-26",
            "2024-11-05",
            "2024-10-07",
        )

        @Volatile
        private var instance: McpHttpServer? = null

        fun get(context: Context): McpHttpServer =
            instance ?: synchronized(this) {
                instance ?: McpHttpServer(context.applicationContext).also { instance = it }
            }
    }

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var port = 0
        private set

    /** true = 绑 0.0.0.0（局域网可访问），false = 只绑 127.0.0.1。 */
    @Volatile
    var lan = false
        private set

    private val sessionId = UUID.randomUUID().toString()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pool: ExecutorService? = null

    /** 起服务。端口被占用会抛异常，由界面提示。 */
    @Synchronized
    fun start(port: Int, lan: Boolean) {
        stop()

        val address = InetAddress.getByName(if (lan) "0.0.0.0" else "127.0.0.1")
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(address, port), 32)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }

        val executor = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "laya-mcp-conn").apply { isDaemon = true }
        }
        serverSocket = socket
        pool = executor
        this.port = port
        this.lan = lan
        isRunning = true

        acceptThread = Thread({ acceptLoop(socket, executor) }, "laya-mcp-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "MCP 服务已启动: ${if (lan) "0.0.0.0" else "127.0.0.1"}:$port (session=$sessionId)")
    }

    @Synchronized
    fun stop() {
        val wasRunning = isRunning
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        pool?.shutdownNow()
        pool = null
        port = 0
        lan = false
        if (wasRunning) Log.i(TAG, "MCP 服务已停止")
    }

    fun endpoint(host: String): String = "http://$host:$port/mcp"

    // -----------------------------------------------------------------------
    // 连接处理
    // -----------------------------------------------------------------------

    private fun acceptLoop(server: ServerSocket, executor: ExecutorService) {
        while (isRunning && !server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            try {
                executor.execute { handle(client) }
            } catch (e: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 20_000
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())

                val request = readRequest(input)
                if (request == null) {
                    writeResponse(output, 400, null)
                    return
                }

                when {
                    request.method == "OPTIONS" -> writeResponse(output, 204, null)
                    request.path == "/health" ->
                        writeResponse(output, 200, """{"ok":true,"service":"laya-mcp-android"}""")
                    request.path != "/mcp" -> writeResponse(output, 404, null)
                    request.method == "POST" -> handlePost(output, request.body)
                    // Streamable HTTP 允许服务端不提供 SSE 流，此时 GET 返回 405。
                    request.method == "GET" -> writeResponse(output, 405, null)
                    request.method == "DELETE" -> writeResponse(output, 204, null)
                    else -> writeResponse(output, 405, null)
                }
            } catch (e: Exception) {
                // 客户端断开/超时是常态，不往上抛。
                Log.w(TAG, "MCP 连接处理失败: ${e.message}")
            }
        }
    }

    private fun handlePost(output: BufferedOutputStream, body: String) {
        val reply = try {
            handleRpc(body)
        } catch (e: Exception) {
            Log.w(TAG, "JSON-RPC 解析失败: ${e.message}")
            RpcReply(200, errorJson(null, -32700, "请求不是合法 JSON：${e.message ?: "解析失败"}"))
        }
        writeResponse(output, reply.status, reply.body)
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readAsciiLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.US)
        val path = parts[1].substringBefore('?')

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase(Locale.US)] =
                    line.substring(idx + 1).trim()
            }
        }

        // 我们不支持 chunked；MCP 客户端的 JSON 请求都带 Content-Length。
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return null
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > 2 * 1024 * 1024) return null

        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) break
            offset += read
        }

        return HttpRequest(
            method = method,
            path = path,
            headers = headers,
            body = String(buffer, 0, offset, Charsets.UTF_8),
        )
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun writeResponse(output: BufferedOutputStream, status: Int, body: String?) {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "OK"
        }

        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            if (bytes != null && bytes.isNotEmpty()) {
                append("Content-Type: application/json; charset=utf-8\r\n")
            }
            append("Content-Length: ").append(bytes?.size ?: 0).append("\r\n")
            append("Mcp-Session-Id: ").append(sessionId).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Allow-Methods: POST, GET, DELETE, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }

        output.write(head.toByteArray(Charsets.US_ASCII))
        if (bytes != null && bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    // -----------------------------------------------------------------------
    // JSON-RPC / MCP
    // -----------------------------------------------------------------------

    private data class RpcReply(val status: Int, val body: String?)

    private fun handleRpc(body: String): RpcReply {
        val root = Json.asObject(Json.parse(body))
        val method = root["method"] as? String
            ?: return RpcReply(200, errorJson(root["id"], -32600, "缺少 method"))
        val id = root["id"]
        val params = Json.asObject(root["params"])

        // 通知没有 id：按规范只回 202，不返回 JSON-RPC 响应。
        if (!root.containsKey("id")) {
            return RpcReply(202, null)
        }

        return when (method) {
            "initialize" -> {
                val requested = params["protocolVersion"] as? String
                val version = if (requested != null && requested in SUPPORTED_PROTOCOLS) {
                    requested
                } else {
                    "2025-06-18"
                }
                val result = """{"protocolVersion":${jstr(version)},"capabilities":{"tools":{}},"serverInfo":{"name":"laya-decide-android","version":"1.0.0"}}"""
                RpcReply(200, resultJson(id, result))
            }

            "ping" -> RpcReply(200, resultJson(id, "{}"))
            "tools/list" -> RpcReply(200, resultJson(id, toolsListJson()))
            "tools/call" -> callTool(id, params)
            else -> RpcReply(200, errorJson(id, -32601, "未知方法：$method"))
        }
    }

    private fun callTool(id: Any?, params: Map<String, Any?>): RpcReply {
        val name = params["name"] as? String
            ?: return RpcReply(200, errorJson(id, -32602, "缺少工具名"))
        val args = Json.asObject(params["arguments"])

        return when (name) {
            "decide" -> callDecide(id, args)
            "list_backends" -> callListBackends(id)
            else -> RpcReply(200, errorJson(id, -32602, "未知工具：$name"))
        }
    }

    private fun callDecide(id: Any?, args: Map<String, Any?>): RpcReply {
        val situation = args["situation"] as? String ?: ""
        val options = Json.asArray(args["options"]).mapNotNull { it as? String }

        when (val check = DecisionEngine.validate(situation, options)) {
            is DecisionEngine.Validation.Fail ->
                return toolError(id, check.message)
            DecisionEngine.Validation.Ok -> Unit
        }

        if (!ModelFiles.isReady(appContext)) {
            return toolError(id, "端侧模型还没下载。先在 App 设置里点「下载模型（约 262 MB）」。")
        }

        val cleaned = DecisionEngine.cleanOptions(options)
        val decision: Decision = try {
            LocalBackend(appContext).decide(situation, cleaned)
        } catch (e: BackendException) {
            return toolError(id, e.message ?: "推理失败")
        } catch (e: Exception) {
            Log.e(TAG, "decide 失败", e)
            return toolError(id, "推理失败：${e.message ?: e.javaClass.simpleName}")
        }

        return RpcReply(200, toolResult(id, formatDecision(decision), structuredDecision(decision)))
    }

    private fun callListBackends(id: Any?): RpcReply {
        val file = ModelFiles.find(appContext)
        val ready = file != null
        val mb = if (file != null) (file.length() / 1024.0 / 1024.0).toInt() else null
        val loaded = ModelManager.isLoaded

        val note = if (ready) {
            "真实 int4 模型${if (loaded) "，已加载" else "，首次调用 decide 时加载（约 6 秒）"}。未微调，判断力有限。"
        } else {
            "端侧模型还没下载，先在 App 设置里下载模型。"
        }

        val text = buildString {
            append("模式：端侧（real）\n")
            append("模型：").append(if (ready) "$mb MB" else "未下载").append('\n')
            append(note)
        }

        val json = """{"mode":"real","configured":"app","ready":$ready,"loaded":$loaded,"modelPath":${file?.absolutePath?.let { jstr(it) } ?: "null"},"modelMB":${mb ?: "null"},"note":${jstr(note)}}"""
        return RpcReply(200, toolResult(id, text, json))
    }

    // -----------------------------------------------------------------------
    // 工具定义与结果
    // -----------------------------------------------------------------------

    private fun toolsListJson(): String = """{"tools":[${decideToolJson()},${statusToolJson()}]}"""

    private fun decideToolJson(): String {
        val situationDesc = "情境/背景描述。要写清楚约束、顾虑、已知条件。上限 ${DecisionEngine.SITUATION_CHAR_LIMIT} 字，超出会被拒绝。"
        val optionsDesc = "候选选项，${DecisionEngine.MIN_OPTIONS}-${DecisionEngine.MAX_OPTIONS} 个，每个不超过 ${DecisionEngine.OPTION_CHAR_LIMIT} 字。"
        return """{"name":"decide","title":"在几个选项里做抉择","description":"在给定情境下评估多个选项并排序，返回最推荐的一个。用于「我该不该…」「选哪个好」这类需要权衡的决策。注意：模型判断力有限，结果应作为参考而非结论；如果所有选项分数几乎一样，说明它没分辨出来，排序不可信。","inputSchema":{"type":"object","properties":{"situation":{"type":"string","description":${jstr(situationDesc)}},"options":{"type":"array","items":{"type":"string"},"description":${jstr(optionsDesc)}}},"required":["situation","options"]}}"""
    }

    private fun statusToolJson(): String =
        """{"name":"list_backends","title":"查看当前推理后端","description":"告诉 AI 现在用的是真实模型还是演示模式、模型在不在。不会加载模型，也不会下载任何东西。","inputSchema":{"type":"object","properties":{}}}"""

    private fun toolResult(id: Any?, text: String, structured: String): String {
        val content = """{"content":[{"type":"text","text":${jstr(text)}}],"structuredContent":$structured}"""
        return resultJson(id, content)
    }

    private fun toolError(id: Any?, message: String): RpcReply {
        val body = """{"content":[{"type":"text","text":${jstr(message)}}],"isError":true}"""
        return RpcReply(200, resultJson(id, body))
    }

    // -----------------------------------------------------------------------
    // 结果文本（与 server/mcp.mjs 保持一致）
    // -----------------------------------------------------------------------

    private fun formatDecision(decision: Decision): String {
        val rows = decision.ranked
        val top = rows.firstOrNull() ?: return "没有选项。"

        val sb = StringBuilder()
        sb.append("推荐：").append(decision.pick ?: "—").append('\n')
        sb.append("档位 ").append(fmt2(top.score))
            .append(" / ").append(fmt2(DecisionEngine.MAX_SCORE))
            .append(" · ").append(confidenceLabel(top.confidence))
        decision.margin?.let {
            sb.append(" · 分差 ").append(fmt2(it))
                .append("（").append(marginLabelText(decision.marginLabel)).append("）")
        }
        sb.append("\n\n排名：\n")
        rows.forEachIndexed { i, r ->
            sb.append("  ").append(i + 1).append(". ").append(r.option)
                .append("   ").append(fmt2(r.score))
                .append("   确定度 ").append((r.confidence * 100).toInt()).append("%\n")
        }

        if (DecisionEngine.isRankingUnreliable(rows)) {
            sb.append("\n注意：所有选项得分极差 < 0.10，说明模型没分辨出高低，这个排序基本是噪声，建议换个说法再问一次。\n")
        }
        if (decision.note.isNotBlank()) {
            sb.append('\n').append(decision.note).append('\n')
        }
        sb.append("\n提醒：这是未微调的基座模型，判断力有限；结果只作参考，不要当成结论。")
        return sb.toString()
    }

    private fun structuredDecision(decision: Decision): String {
        val rows = decision.ranked.mapIndexed { i, r ->
            """{"rank":${i + 1},"option":${jstr(r.option)},"score":${fmt4(r.score)},"normalized":${fmt4(r.normalized)},"confidence":${fmt4(r.confidence)}}"""
        }.joinToString(",")

        val spread = if (decision.ranked.size > 1) {
            decision.ranked.first().score - decision.ranked.last().score
        } else {
            null
        }

        return """{"mode":"real","pick":${decision.pick?.let { jstr(it) } ?: "null"},"margin":${decision.margin?.let { fmt4(it) } ?: "null"},"marginLabel":${jstr(marginLabelText(decision.marginLabel))},"closeCall":${decision.closeCall},"note":${jstr(decision.note)},"spread":${spread?.let { fmt4(it) } ?: "null"},"ranked":[$rows],"usage":{"inputTokens":null,"latencyMs":null,"temperature":null}}"""
    }

    private fun confidenceLabel(conf: Double): String = when {
        conf >= 0.25 -> "模型比较有把握"
        conf >= 0.12 -> "模型倾向不明显"
        else -> "模型基本在猜"
    }

    private fun marginLabelText(label: MarginLabel): String = when (label) {
        MarginLabel.SINGLE -> "单选项"
        MarginLabel.TIGHT -> "胶着"
        MarginLabel.LEANING -> "有倾向"
        MarginLabel.CLEAR -> "明确"
    }

    private fun fmt2(x: Double) = String.format(Locale.US, "%.2f", x)
    private fun fmt4(x: Double) = String.format(Locale.US, "%.4f", x)

    // -----------------------------------------------------------------------
    // JSON 小工具
    // -----------------------------------------------------------------------

    private fun resultJson(id: Any?, result: String) =
        """{"jsonrpc":"2.0","id":${jvalue(id)},"result":$result}"""

    private fun errorJson(id: Any?, code: Int, message: String) =
        """{"jsonrpc":"2.0","id":${jvalue(id)},"error":{"code":$code,"message":${jstr(message)}}}"""

    private fun jvalue(value: Any?): String = when (value) {
        null -> "null"
        is String -> jstr(value)
        is Boolean -> value.toString()
        is Long -> value.toString()
        is Int -> value.toString()
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        else -> jstr(value.toString())
    }

    private fun jstr(value: String?): String {
        if (value == null) return "null"
        val sb = StringBuilder(value.length + 16)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append(String.format(Locale.US, "\\u%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
