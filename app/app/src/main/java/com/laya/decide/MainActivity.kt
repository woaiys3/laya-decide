package com.laya.decide

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.text.Editable
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

    /** 正在进行的模型下载。非空表示下载中，用来防止重复点击。 */
    private var downloadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        history = HistoryStore(this)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        setupInitialOptions()
        setupSettings()
        wireButtons()

        // 第一次用的时候把设置面板摊开，否则用户不知道要填服务地址。
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
     *     --es mode http \
     *     --es server 10.0.2.2:8931 \
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
                BackendMode.HTTP -> b.rbHttp.isChecked = true
                BackendMode.LOCAL -> b.rbLocal.isChecked = true
            }
        }

        i.getStringExtra("server")?.let { s ->
            b.etServer.setText(s)
            prefs.edit().putString(KEY_SERVER, s).commit()
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

    private fun addOptionRow(text: String = "") {
        if (optionRows.size >= DecisionEngine.MAX_OPTIONS) {
            toast("最多 ${DecisionEngine.MAX_OPTIONS} 个选项")
            return
        }

        val row = RowOptionBinding.inflate(LayoutInflater.from(this), b.llOptions, false)
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

        b.btnTest.setOnClickListener { testConnection() }

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
        val server = currentServer()

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
                runCatching { backendFor(mode, server, this@MainActivity).decide(situation, options) }
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

    private fun testConnection() {
        val server = currentServer()
        b.btnTest.text = getString(R.string.testing)
        b.btnTest.isEnabled = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { backendFor(BackendMode.HTTP, server, this@MainActivity).describe() }
            }
            b.btnTest.isEnabled = true
            b.btnTest.text = getString(R.string.btn_test)

            result.onSuccess { toast(it) }
                .onFailure { e ->
                    toast(
                        getString(
                            R.string.test_fail,
                            (e as? BackendException)?.message ?: e.message ?: "未知错误",
                        ),
                        long = true,
                    )
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
        val server = prefs.getString(KEY_SERVER, DEFAULT_SERVER).orEmpty()
        b.etServer.setText(server)

        val mode = BackendMode.fromKey(prefs.getString(KEY_MODE, null))
        when (mode) {
            BackendMode.DEMO -> b.rbDemo.isChecked = true
            BackendMode.HTTP -> b.rbHttp.isChecked = true
            BackendMode.LOCAL -> b.rbLocal.isChecked = true
        }

        b.rgBackend.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val picked = when (checkedId) {
                R.id.rbHttp -> BackendMode.HTTP
                R.id.rbLocal -> BackendMode.LOCAL
                else -> BackendMode.DEMO
            }
            prefs.edit().putString(KEY_MODE, picked.storageKey).apply()
            refreshStatusChip()
        }

        b.etServer.setOnFocusChangeListener { _: View, hasFocus: Boolean ->
            if (!hasFocus) saveServer()
        }

        b.btnDownloadModel.setOnClickListener { downloadModel() }
        updateModelStatus()
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
            else -> b.tvNote.visibility = View.GONE
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

    private fun saveServer() {
        prefs.edit().putString(KEY_SERVER, b.etServer.text.toString().trim()).apply()
        refreshStatusChip()
    }

    private fun currentMode() = BackendMode.fromKey(prefs.getString(KEY_MODE, null))

    private fun currentServer(): String {
        // 输入框可能还没失焦，直接读输入框更准。
        val typed = b.etServer.text.toString().trim()
        return typed.ifEmpty { prefs.getString(KEY_SERVER, DEFAULT_SERVER).orEmpty() }
    }

    private fun refreshStatusChip() {
        val label = when (currentMode()) {
            BackendMode.DEMO -> getString(R.string.backend_demo)
            BackendMode.HTTP -> getString(R.string.backend_http)
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
                BackendMode.HTTP -> "Laya"
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

    override fun onPause() {
        super.onPause()
        // 离开页面时把服务地址存下来。
        saveServer()
    }

    companion object {
        private const val PREFS = "laya_decide_prefs"
        private const val KEY_MODE = "backend_mode"
        private const val KEY_SERVER = "server_addr"
        private const val DEFAULT_SERVER = "192.168.1.100:8931"
    }
}
