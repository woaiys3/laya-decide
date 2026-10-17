package com.laya.decide.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.laya.decide.MainActivity
import com.laya.decide.R

/**
 * MCP 服务的前台 Service —— 一个常驻通知，换进程不被系统随便回收。
 *
 * 真正的监听 socket 在 [McpHttpServer] 单例里，Activity 负责同步地启停它
 * （这样端口占用能立刻提示）；这个 Service 只做两件事：
 *   1. 把进程提到前台，App 切后台后服务还活着；
 *   2. 显示可点开、可停止的通知。
 *
 * Service 被系统重启时（START_STICKY，intent 为 null）会按 SharedPreferences
 * 里的开关和端口把 server 重新拉起来。
 */
class McpForegroundService : Service() {

    companion object {
        private const val TAG = "LayaMcpService"
        private const val CHANNEL_ID = "laya_mcp_service"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.laya.decide.mcp.action.START"
        const val ACTION_STOP = "com.laya.decide.mcp.action.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_LAN = "lan"

        fun start(context: Context, port: Int, lan: Boolean) {
            val intent = Intent(context, McpForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_LAN, lan)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, McpForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var server: McpHttpServer
    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        server = McpHttpServer.get(this)
        prefs = getSharedPreferences(McpPrefs.FILE, Context.MODE_PRIVATE)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything(disablePref = true)
            return START_NOT_STICKY
        }

        val enabled = prefs.getBoolean(McpPrefs.KEY_ENABLED, false)
        // intent 为 null = 系统重启了 Service；只有在开关还开着时才继续。
        if (intent == null && !enabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { McpPrefs.isValidPort(it) }
            ?: prefs.getInt(McpPrefs.KEY_PORT, McpPrefs.DEFAULT_PORT)
        val lan = if (intent?.hasExtra(EXTRA_LAN) == true) {
            intent.getBooleanExtra(EXTRA_LAN, false)
        } else {
            prefs.getBoolean(McpPrefs.KEY_LAN, false)
        }

        // startForegroundService 之后必须尽快 startForeground，先挂一个"启动中"。
        // Android 14 起对 FGS 类型/权限要求更严，失败就老实退出。
        try {
            promoteToForeground(port, lan)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return try {
            if (!server.isRunning || server.port != port || server.lan != lan) {
                server.start(port, lan)
            }
            prefs.edit()
                .putBoolean(McpPrefs.KEY_ENABLED, true)
                .putInt(McpPrefs.KEY_PORT, port)
                .putBoolean(McpPrefs.KEY_LAN, lan)
                .apply()
            updateNotification(port, lan)
            START_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "前台服务启动 MCP 失败", e)
            updateNotificationError(e.message)
            // onDestroy 会停掉 server；开关由 Activity 负责复位。
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        if (server.isRunning) server.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -----------------------------------------------------------------------
    // 通知
    // -----------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mcp_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.mcp_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun promoteToForeground(port: Int, lan: Boolean) {
        val notification = buildNotification(port, lan, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(port: Int, lan: Boolean) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(port, lan, null))
    }

    private fun updateNotificationError(message: String?) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(0, false, message ?: "端口可能被占用"),
        )
    }

    private fun buildNotification(port: Int, lan: Boolean, error: String?): Notification {
        val endpoint = if (error == null && port > 0) {
            McpNetwork.endpoint(port, lan) ?: getString(R.string.mcp_status_lan_no_ip)
        } else {
            null
        }

        val title = if (error != null) {
            getString(R.string.mcp_notification_failed)
        } else {
            getString(R.string.mcp_notification_title)
        }
        val text = error ?: endpoint ?: getString(R.string.mcp_notification_starting)

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, McpForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mcp)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (error != null) {
                        "$error\n在 App 设置里可以重试。"
                    } else {
                        "$text\n${getString(R.string.mcp_notification_hint)}"
                    },
                ),
            )
            .setContentIntent(openIntent)
            .setOngoing(error == null)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (error == null) {
            builder.addAction(0, getString(R.string.mcp_stop), stopIntent)
        } else {
            builder.setOngoing(false)
        }

        return builder.build()
    }

    private fun stopEverything(disablePref: Boolean) {
        if (disablePref) {
            prefs.edit().putBoolean(McpPrefs.KEY_ENABLED, false).apply()
        }
        server.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
