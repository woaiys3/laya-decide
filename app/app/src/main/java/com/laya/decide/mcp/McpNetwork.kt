package com.laya.decide.mcp

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** 找本机局域网 IPv4，用来显示/复制 MCP 地址。不需要权限。 */
object McpNetwork {

    fun localIpv4(): String? = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    /** 给人看的完整地址；局域网模式但没连 Wi-Fi 时返回 null。 */
    fun endpoint(port: Int, lan: Boolean): String? {
        if (!lan) return "http://127.0.0.1:$port/mcp"
        val ip = localIpv4() ?: return null
        return "http://$ip:$port/mcp"
    }
}
