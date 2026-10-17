package com.laya.decide.mcp

/**
 * MCP 相关的 SharedPreferences 键。
 *
 * MainActivity 和 McpForegroundService 都要读写这几个值，所以集中放这里，
 * 避免两边字符串写岔。
 */
object McpPrefs {
    /** 与 App 其他设置共用同一个 prefs 文件。 */
    const val FILE = "laya_decide_prefs"

    const val KEY_ENABLED = "mcp_enabled"
    const val KEY_LAN = "mcp_lan"
    const val KEY_PORT = "mcp_port"

    const val DEFAULT_PORT = 8940
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    fun isValidPort(port: Int?): Boolean = port != null && port in MIN_PORT..MAX_PORT
}
