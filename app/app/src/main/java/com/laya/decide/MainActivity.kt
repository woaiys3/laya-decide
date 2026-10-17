package com.laya.decide

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.laya.decide.agent.ModelFiles
import com.laya.decide.agent.ModelManager
import com.laya.decide.backend.BackendException
import com.laya.decide.backend.BackendMode
import com.laya.decide.backend.backendFor
import com.laya.decide.core.Decision
import com.laya.decide.core.DecisionEngine
import com.laya.decide.core.MarginLabel
import com.laya.decide.core.RankedOption
import com.laya.decide.data.HistoryStore
import com.laya.decide.databinding.ActivityMainBinding
import com.laya.decide.databinding.RowHistoryBinding
import com.laya.decide.databinding.RowOptionBinding
import com.laya.decide.databinding.RowRankedBinding
import com.laya.decide.mcp.McpForegroundService
import com.laya.decide.mcp.McpHttpServer
import com.laya.decide.mcp.McpNetwork
import com.laya.decide.mcp.McpPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var history: HistoryStore
    private lateinit var prefs: android.content.SharedPreferences

    private val optionRows = mutableListOf<RowOptionBinding>()

    /** App 内置的 MCP 服务（给电脑上的 AI 客户端用）。 */
    private lateinit var mcpServer: McpHttpServer

    /** 当前可复制的 MCP 地址；服务没运行时为 null。 */
    private var mcpEndpoint: String? = null

    /** 防止代码改 Switch 状态时反过来触发监听器。 */
    private var mcpUiUpdating = false

    /** 正在进行的模型下载。非空表示下载中，用来防止重复点击。 */
    private var downloadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        history = HistoryStore(this)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mcpServer = McpHttpServer.get(this)

        setupInitialOptions()
        setupSituationInput()
        setupSettings()
        setupMcpSettings()
        wireButtons()

        // 第一次用的时候把设置面板摊开，否则用户不知道要选后端、下模型。
        if (history.count() == 0 && prefs.getString(KEY_MODE, null) == null) {
            b.llSettings.visibility = View.VISIBLE
        }

        refreshStatusChip()
        renderHistory()

        handleDebugIntent()
    }

    /**
     * 调试入口：用 adb 直接驱动 App，省得在模拟器里手打中文。
     *
     * 只在 debug 构建里生效。用法：
     *
     *   adb shell am start -n com.laya.decide/.MainActivity \
     *     --es situation "我拿到一个创业公司 offer，薪资降 30%" \
     *     --es options "接受这个 offer|留在现在的大厂|继续面其他公司" \
     *     --es mode local \
     *     --ez decide true
     *
     * options 用 | 分隔；decide 传 true 会自动触发一次决策。
     * 这不只是测试便利 —— 之后想批量验证不同提问写法，也能直接命令行跑。
     */
    @SuppressLint("ApplySharedPref")
    private fun handleDebugIntent() {
        if (!BuildConfig.DEBUG) return
        val i = intent ?: return

        i.getStringExtra("mode")?.let { m ->
            val parsed = BackendMode.fromKey(m)
            prefs.edit().putString(KEY_MODE, parsed.storageKey).commit()
            when (parsed) {
                BackendMode.DEMO -> b.rbDemo.isChecked = true
                BackendMode.LOCAL -> b.rbLocal.isChecked = true
            }
        }

        i.getStringExtra("situation")?.let { s ->
            b.etSituation.setText(s)
        }

        i.getStringExtra("options")?.let { raw ->
            val opts = raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (opts.isNotEmpty()) {
                b.llOptions.removeAllViews()
                optionRows.clear()
                opts.forEach { addOptionRow(it) }
                while (optionRows.size < DecisionEngine.MIN_OPTIONS) addOptionRow()
            }
        }

        refreshStatusChip()

        if (i.getBooleanExtra("decide", false)) {
            b.llResult.visibility = View.VISIBLE
            b.etSituation.post { runDecision() }
        }
    }

    // -----------------------------------------------------------------------
    // 选项行
    // -----------------------------------------------------------------------

    private fun setupInitialOptions() {
        repeat(3) { addOptionRow() }
    }

    /**
     * 情境输入：硬上限 + 实时计数。
     *
     * 模型只读得到约 314 个中文字，所以输入框直接卡在 300 ——
     * 宁可写不进去，也不要让用户以为写了 1200 字模型就看到了 1200 字。
     */
    private fun setupSituationInput() {
        val limit = DecisionEngine.SITUATION_CHAR_LIMIT
        b.etSituation.filters = arrayOf(InputFilter.LengthFilter(limit))
        b.tvSituationNote.text = getString(
            R.string.situation_limit_note,
            limit,
            DecisionEngine.OPTION_CHAR_LIMIT,
        )

        b.etSituation.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateSituationCount()
        })

        updateSituationCount()
    }

    private fun updateSituationCount() {
        val len = b.etSituation.text.length
        val limit = DecisionEngine.SITUATION_CHAR_LIMIT
        b.tvSituationCount.text = getString(R.string.situation_count, len, limit)

        // 快写满时变色，提示用户该收尾了。
        val nearLimit = len >= limit - 20
        b.tvSituationCount.setTextColor(
            ContextCompat.getColor(
                this,
                if (nearLimit) R.color.accent else R.color.text_secondary,
            ),
        )
    }

    private fun addOptionRow(text: String = "") {
        if (optionRows.size >= DecisionEngine.MAX_OPTIONS) {
            toast("最多 ${DecisionEngine.MAX_OPTIONS} 个选项")
            return
        }

        val row = RowOptionBinding.inflate(LayoutInflater.from(this), b.llOptions, false)
        // 选项上限同理：超过 85 字的部分模型看不到，直接不让输入。
        row.etOption.filters = arrayOf(InputFilter.LengthFilter(DecisionEngine.OPTION_CHAR_LIMIT))
        row.etOption.hint = getString(R.string.hint_option_limit, DecisionEngine.OPTION_CHAR_LIMIT)
        row.etOption.setText(text)

        // 文本变化时刷新计数，让用户看到自己填了几个。
        row.etOption.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateOptionCount()
        })

        row.btnRemove.setOnClickListener {
            if (optionRows.size <= DecisionEngine.MIN_OPTIONS) {
                toast("至少保留 ${DecisionEngine.MIN_OPTIONS} 个选项")
                return@setOnClickListener
            }
            b.llOptions.removeView(row.root)
            optionRows.remove(row)
            updateOptionCount()
        }

        b.llOptions.addView(row.root)
        optionRows.add(row)
        updateOptionCount()
    }

    private fun collectOptions(): List<String> =
        DecisionEngine.cleanOptions(optionRows.map { it.etOption.text.toString() })

    private fun updateOptionCount() {
        val filled = collectOptions().size
        b.tvOptionCount.text = "$filled / ${optionRows.size} 已填"
    }

    // -----------------------------------------------------------------------
    // 事件
    // -----------------------------------------------------------------------

    private fun wireButtons() {
        b.btnAddOption.setOnClickListener { addOptionRow() }

        b.btnDecide.setOnClickListener { runDecision() }

        b.btnClear.setOnClickListener {
            b.etSituation.setText("")
            b.llOptions.removeAllViews()
            optionRows.clear()
            setupInitialOptions()
            b.llResult.visibility = View.GONE
        }

        b.btnSettings.setOnClickListener {
            b.llSettings.visibility =
                if (b.llSettings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        b.btnClearHistory.setOnClickListener {
            history.clear()
            renderHistory()
            toast("历史已清空")
        }
    }

    private fun runDecision() {
        val situation = b.etSituation.text.toString().trim()
        val options = collectOptions()

        when (val v = DecisionEngine.validate(situation, options)) {
            is DecisionEngine.Validation.Fail -> {
                toast(v.message)
                return
            }
            DecisionEngine.Validation.Ok -> Unit
        }

        val mode = currentMode()

        // 端侧模式下模型没下载就别白跑一趟，直接引导去下载。
        if (mode == BackendMode.LOCAL && !ModelFiles.isReady(this)) {
            b.llSettings.visibility = View.VISIBLE
            updateModelStatus()
            toast(getString(R.string.note_local_missing), long = true)
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { backendFor(mode, this@MainActivity).decide(situation, options) }
            }

            setBusy(false)

            result.onSuccess { decision ->
                showResult(decision, mode)
                // 落库放到 IO 线程，别在主线程碰 SQLite。
                lifecycleScope.launch(Dispatchers.IO) {
                    history.add(situation, options, decision, mode.storageKey)
                }
                renderHistory()
                b.llResult.visibility = View.VISIBLE
                updateModelStatus()
            }.onFailure { e ->
                b.llResult.visibility = View.GONE
                val msg = (e as? BackendException)?.message ?: e.message ?: "未知错误"
                toast(getString(R.string.decide_failed, msg), long = true)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        b.btnDecide.isEnabled = !busy
        b.btnDecide.text = getString(if (busy) R.string.btn_deciding else R.string.btn_decide)
        b.btnDecide.alpha = if (busy) 0.6f else 1f
    }

    // -----------------------------------------------------------------------
    // 设置
    // -----------------------------------------------------------------------

    private fun setupSettings() {
        val mode = BackendMode.fromKey(prefs.getString(KEY_MODE, null))
        when (mode) {
            BackendMode.DEMO -> b.rbDemo.isChecked = true
            BackendMode.LOCAL -> b.rbLocal.isChecked = true
        }

        b.rgBackend.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val picked = when (checkedId) {
                R.id.rbLocal -> BackendMode.LOCAL
                else -> BackendMode.DEMO
            }
            prefs.edit().putString(KEY_MODE, picked.storageKey).apply()
            refreshStatusChip()
        }

        b.btnDownloadModel.setOnClickListener { downloadModel() }
        updateModelStatus()
    }

    // -----------------------------------------------------------------------
    // MCP 服务（给电脑上的 AI 客户端用）
    // -----------------------------------------------------------------------

    private fun setupMcpSettings() {
        val enabled = prefs.getBoolean(McpPrefs.KEY_ENABLED, false)
        val lan = prefs.getBoolean(McpPrefs.KEY_LAN, false)
        val port = prefs.getInt(McpPrefs.KEY_PORT, McpPrefs.DEFAULT_PORT)

        b.etMcpPort.setText(port.toString())
        b.swMcpLan.isChecked = lan
        b.swMcpEnabled.isChecked = enabled
        setMcpControlsEnabled(enabled)
        updateMcpStatus()

        b.swMcpEnabled.setOnCheckedChangeListener { _, checked ->
            if (mcpUiUpdating) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(McpPrefs.KEY_ENABLED, checked).apply()
            setMcpControlsEnabled(checked)
            if (checked) {
                startMcp(showToast = true)
            } else {
                stopMcp()
                toast("MCP 服务已关闭")
            }
        }

        b.swMcpLan.setOnCheckedChangeListener { _, checked ->
            if (mcpUiUpdating) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(McpPrefs.KEY_LAN, checked).apply()
            // 改绑地址要重启监听：127.0.0.1 ↔ 0.0.0.0。
            if (mcpServer.isRunning) startMcp(showToast = true)
        }

        b.etMcpPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyMcpPort(force = false)
        }
        b.btnMcpApply.setOnClickListener { applyMcpPort(force = true) }
        b.btnMcpCopy.setOnClickListener { copyMcpEndpoint() }
        // 地址那一行也可以长按复制，符合手机上的习惯。
        b.tvMcpStatus.setOnLongClickListener {
            copyMcpEndpoint()
            true
        }

        // 上次开着就自动接着跑（进程被系统杀掉后，重新打开 App 会恢复）。
        if (enabled && !mcpServer.isRunning) {
            startMcp(showToast = false)
        }
    }

    private fun setMcpControlsEnabled(enabled: Boolean) {
        b.swMcpLan.isEnabled = enabled
        b.etMcpPort.isEnabled = enabled
    }

    private fun startMcp(showToast: Boolean): Boolean {
        val port = b.etMcpPort.text.toString().trim().toIntOrNull()
        if (!McpPrefs.isValidPort(port)) {
            failMcp("端口要在 ${McpPrefs.MIN_PORT}–${McpPrefs.MAX_PORT} 之间")
            return false
        }

        val lan = b.swMcpLan.isChecked
        return try {
            if (!mcpServer.isRunning || mcpServer.port != port || mcpServer.lan != lan) {
                mcpServer.start(port!!, lan)
            }
            prefs.edit()
                .putBoolean(McpPrefs.KEY_ENABLED, true)
                .putBoolean(McpPrefs.KEY_LAN, lan)
                .putInt(McpPrefs.KEY_PORT, port)
                .apply()
            b.etMcpPort.setText(port.toString())
            ensureNotificationPermission()
            // 前台 Service 负责保活和通知；server 已经在上面同步起好了。
            McpForegroundService.start(this, port, lan)
            updateMcpStatus()
            if (showToast) toast("MCP 服务已启动")
            true
        } catch (e: Exception) {
            failMcp("启动失败：${e.message ?: "端口可能被占用"}")
            false
        }
    }

    private fun stopMcp() {
        mcpServer.stop()
        McpForegroundService.stop(this)
        updateMcpStatus()
    }

    private fun applyMcpPort(force: Boolean) {
        val raw = b.etMcpPort.text.toString().trim()
        val newPort = raw.toIntOrNull()
        val saved = prefs.getInt(McpPrefs.KEY_PORT, McpPrefs.DEFAULT_PORT)

        if (!McpPrefs.isValidPort(newPort)) {
            toast("端口要在 ${McpPrefs.MIN_PORT}–${McpPrefs.MAX_PORT} 之间", long = true)
            b.etMcpPort.setText(saved.toString())
            return
        }
        if (newPort == saved) {
            if (force) toast(if (mcpServer.isRunning) "端口没变" else "端口已保存")
            return
        }

        prefs.edit().putInt(McpPrefs.KEY_PORT, newPort!!).apply()
        if (mcpServer.isRunning || b.swMcpEnabled.isChecked) {
            startMcp(showToast = true)
        } else {
            updateMcpStatus()
            if (force) toast("端口已保存")
        }
    }

    /** 启动失败时把开关复位，别让界面显示「开」而实际没跑。 */
    private fun failMcp(message: String) {
        mcpServer.stop()
        McpForegroundService.stop(this)
        prefs.edit().putBoolean(McpPrefs.KEY_ENABLED, false).apply()
        mcpUiUpdating = true
        b.swMcpEnabled.isChecked = false
        mcpUiUpdating = false
        setMcpControlsEnabled(false)
        updateMcpStatus()
        toast(message, long = true)
    }

    private fun updateMcpStatus() {
        mcpEndpoint = if (mcpServer.isRunning) {
            McpNetwork.endpoint(mcpServer.port, mcpServer.lan)
        } else {
            null
        }

        b.btnMcpCopy.isEnabled = mcpEndpoint != null
        b.btnMcpCopy.alpha = if (mcpEndpoint != null) 1f else 0.45f

        if (!mcpServer.isRunning) {
            b.tvMcpStatus.text = getString(R.string.mcp_status_off)
            b.tvMcpStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            return
        }

        b.tvMcpStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
        b.tvMcpStatus.text = if (mcpEndpoint != null) {
            getString(R.string.mcp_status_running, mcpEndpoint!!)
        } else {
            getString(R.string.mcp_status_lan_no_ip)
        }
    }

    private fun copyMcpEndpoint() {
        val endpoint = mcpEndpoint
        if (endpoint == null) {
            toast(getString(R.string.mcp_copy_empty))
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MCP 地址", endpoint))
        toast(getString(R.string.mcp_copied))
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::mcpServer.isInitialized) return

        // 用户可能从通知栏点了「停止」，回前台时把界面同步过来。
        // 没变化就别动，避免把正在编辑的端口改回去。
        val running = mcpServer.isRunning
        val prefEnabled = prefs.getBoolean(McpPrefs.KEY_ENABLED, false)
        if (running == b.swMcpEnabled.isChecked && prefEnabled == b.swMcpEnabled.isChecked) return

        mcpUiUpdating = true
        b.swMcpEnabled.isChecked = running && prefEnabled
        b.swMcpLan.isChecked = prefs.getBoolean(McpPrefs.KEY_LAN, false)
        b.etMcpPort.setText(prefs.getInt(McpPrefs.KEY_PORT, McpPrefs.DEFAULT_PORT).toString())
        mcpUiUpdating = false
        setMcpControlsEnabled(b.swMcpEnabled.isChecked)
        updateMcpStatus()
    }

    // -----------------------------------------------------------------------
    // 端侧模型
    // -----------------------------------------------------------------------

    /** 刷新模型状态那一行，同时决定下载按钮的文案。 */
    private fun updateModelStatus() {
        val file = ModelFiles.find(this)
        when {
            file != null -> {
                val mb = file.length() / 1024.0 / 1024.0
                val loaded = if (ModelManager.isLoaded) "已在内存" else "未加载"
                b.tvModelStatus.text = getString(
                    R.string.model_ready,
                    String.format(Locale.US, "%.0f MB", mb),
                    loaded,
                )
                b.btnDownloadModel.text = getString(R.string.btn_redownload_model)
            }

            downloadJob != null -> Unit // 下载中，文案由进度回调负责

            else -> {
                b.tvModelStatus.text = getString(R.string.model_absent)
                b.btnDownloadModel.text = getString(R.string.btn_download_model)
            }
        }

        // 演示模式的黄色提示条复用同一个控件，按模式切文案。
        when (currentMode()) {
            BackendMode.DEMO -> {
                b.tvNote.visibility = View.VISIBLE
                b.tvNote.text = getString(R.string.note_demo)
            }
            BackendMode.LOCAL -> {
                if (file == null) {
                    b.tvNote.visibility = View.VISIBLE
                    b.tvNote.text = getString(R.string.note_local_missing)
                } else {
                    b.tvNote.visibility = View.GONE
                }
            }
        }
    }

    private fun downloadModel() {
        if (downloadJob?.isActive == true) {
            toast("正在下载中…")
            return
        }

        b.btnDownloadModel.isEnabled = false
        var lastShownPct = -1
        var lastSource = ""

        downloadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ModelFiles.download(this@MainActivity) { done, total, source ->
                        val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                        // 别刷太频繁，整百分比变化才更新界面
                        if (pct != lastShownPct || source != lastSource) {
                            lastShownPct = pct
                            lastSource = source
                            runOnUiThread {
                                b.tvModelStatus.text = getString(
                                    R.string.model_progress,
                                    humanSize(done),
                                    humanSize(total),
                                    pct,
                                )
                            }
                        }
                    }
                }
            }

            b.btnDownloadModel.isEnabled = true
            downloadJob = null

            result.onSuccess {
                toast(getString(R.string.model_download_done))
            }.onFailure { e ->
                toast(
                    getString(R.string.model_download_failed, e.message ?: "未知错误"),
                    long = true,
                )
            }
            updateModelStatus()
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / 1048576.0)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    private fun currentMode() = BackendMode.fromKey(prefs.getString(KEY_MODE, null))

    private fun refreshStatusChip() {
        val label = when (currentMode()) {
            BackendMode.DEMO -> getString(R.string.backend_demo)
            BackendMode.LOCAL -> getString(R.string.backend_local)
        }
        b.tvStatus.text = label

        // 提示条的文案按模式在 updateModelStatus 里统一决定，避免两处打架。
        updateModelStatus()
    }

    // -----------------------------------------------------------------------
    // 结果渲染
    // -----------------------------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun showResult(decision: Decision, mode: BackendMode) {
        b.tvPick.text = decision.pick ?: "—"

        val top = decision.ranked.firstOrNull()
        val maxScore = DecisionEngine.MAX_SCORE

        // 分数说明。刻意不写成"得分 2.36/3.00 = 比较合适"：
        // 当分布接近均匀时，"比较合适"这个档位标签是误导的 ——
        // 模型其实是"不知道"，而不是"觉得还不错"。
        val isDemo = mode == BackendMode.DEMO
        val sub = buildString {
            if (top != null) {
                if (isDemo) {
                    // 演示模式没有模型，绝不能说"模型比较有把握"。
                    append("演示模式：分数不是模型算的")
                } else {
                    append(
                        getString(
                            R.string.conf_line,
                            (top.confidence * 100).toInt(),
                            confidenceLabel(top.confidence),
                        ),
                    )
                }
                append("  ·  档位 ${fmt(top.score)} / ${fmt(maxScore)}")
            }
        }
        b.tvPickSub.text = sub

        b.tvNote2.visibility = if (decision.note.isBlank()) View.GONE else View.VISIBLE
        b.tvNote2.text = decision.note

        // 排序不可信时要点明。
        //
        // 不提示的话，用户会把 0.12 vs 0.12 这种并列当成"选出了第一名"。
        // 判据见 DecisionEngine.isRankingUnreliable —— 用极差而不是首名分差。
        val rankingUntrustworthy = DecisionEngine.isRankingUnreliable(decision.ranked)

        b.tvScaleCaveat.visibility = if (rankingUntrustworthy) View.VISIBLE else View.GONE
        if (rankingUntrustworthy) {
            b.tvScaleCaveat.text = getString(
                R.string.scale_caveat,
                top?.score ?: 0.0,
                maxScore,
            )
        }

        // 排名列表
        b.llRanked.removeAllViews()
        decision.ranked.forEachIndexed { i, r ->
            b.llRanked.addView(buildRankRow(i, r, isDemo))
        }

        // 底部元信息
        val parts = mutableListOf<String>()
        parts.add("${decision.ranked.size} 个选项")
        decision.margin?.let { parts.add("分差 ${fmt(it)}") }
        decision.marginLabel.takeIf { it != MarginLabel.SINGLE }?.let {
            parts.add(
                when (it) {
                    MarginLabel.TIGHT -> "胶着"
                    MarginLabel.LEANING -> "有倾向"
                    else -> "明确"
                },
            )
        }
        if (!isDemo) parts.add(getString(R.string.model_note))
        b.tvMeta.text = parts.joinToString("\n")
    }

    /**
     * 置信度的人话标签。
     *
     * 阈值按实测标定：基座模型在开放式选择上的置信度普遍落在 0.09-0.14，
     * 所以 25% 以上已经算它有把握了。用通用阈值（比如 70%）会把
     * 100% 的结果都标成"低置信"，反而失去区分意义。
     */
    private fun confidenceLabel(conf: Double): String = when {
        conf >= 0.25 -> getString(R.string.conf_high)
        conf >= 0.12 -> getString(R.string.conf_medium)
        else -> getString(R.string.conf_low)
    }

    @SuppressLint("SetTextI18n")
    private fun buildRankRow(position: Int, r: RankedOption, isDemo: Boolean): View {
        val row = RowRankedBinding.inflate(layoutInflater, b.llRanked, false)

        row.tvRank.text = "${position + 1}"
        row.tvOption.text = r.option
        row.tvScore.text = fmt(r.score)

        row.pbScore.progress = (r.normalized * 100).toInt().coerceIn(0, 100)

        // 第一名的徽章用强调色，其余用灰色，一眼能看出推荐项。
        val isTop = position == 0
        row.tvRank.setBackgroundResource(R.drawable.bg_pick)
        row.tvRank.setTextColor(
            ContextCompat.getColor(
                this,
                if (isTop) R.color.accent else R.color.rank_badge,
            ),
        )
        row.tvOption.setTypeface(null, if (isTop) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

        // 演示模式下不能说"模型比较有把握" —— 那根本没有模型。
        row.tvConf.text = if (isDemo) {
            "演示模式，非真实推理"
        } else {
            getString(
                R.string.conf_score_line,
                r.score,
                DecisionEngine.MAX_SCORE,
                confidenceLabel(r.confidence),
            )
        }
        row.tvConf.setTextColor(
            ContextCompat.getColor(this, R.color.text_secondary),
        )

        return row.root
    }

    // -----------------------------------------------------------------------
    // 历史
    // -----------------------------------------------------------------------

    private fun renderHistory() {
        val entries = history.latest(10)

        b.llHistory.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        b.llHistoryList.removeAllViews()

        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        for (e in entries) {
            val row = RowHistoryBinding.inflate(layoutInflater, b.llHistoryList, false)
            row.tvSituation.text = e.situation

            val pick = e.decision.pick ?: "—"
            val modeTag = when (BackendMode.fromKey(e.modeKey)) {
                BackendMode.DEMO -> "演示"
                BackendMode.LOCAL -> "端侧"
            }
            row.tvMeta.text = "$pick  ·  $modeTag  ·  ${sdf.format(Date(e.time))}"

            // 点历史回填输入，方便改一改再问一次。
            row.root.setOnClickListener {
                b.etSituation.setText(e.situation)
                b.llOptions.removeAllViews()
                optionRows.clear()
                e.options.forEach { addOptionRow(it) }
                while (optionRows.size < DecisionEngine.MIN_OPTIONS) addOptionRow()
                showResult(e.decision, BackendMode.fromKey(e.modeKey))
                b.llResult.visibility = View.VISIBLE
                toast("已载入历史，可以直接改再问")
            }

            b.llHistoryList.addView(row.root)
        }
    }

    // -----------------------------------------------------------------------

    private fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun fmt(x: Double) = String.format(Locale.US, "%.2f", x)

    companion object {
        private const val PREFS = "laya_decide_prefs"
        private const val KEY_MODE = "backend_mode"
        private const val REQUEST_NOTIFICATIONS = 42
    }
}
