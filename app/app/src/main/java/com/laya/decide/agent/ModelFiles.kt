package com.laya.decide.agent

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 端侧模型文件的位置与获取。
 *
 * 模型 262MB，装不进 APK，所以放进 App 私有目录。查找顺序刻意设计成：
 *
 *   1. 外部私有目录   /sdcard/Android/data/<pkg>/files/models/
 *      —— **可以在不 root 的情况下用 adb push 推进去**，方便调试和离线部署
 *   2. 内部私有目录   /data/data/<pkg>/files/models/
 *      —— 卸载即清除，更干净
 *
 * 两个都没有才需要联网下载。下载走 hf-mirror.com（HuggingFace 官方站在国内不可达），
 * 失败再退回官方站。**不需要任何账号**。
 */
object ModelFiles {

    const val FILE_NAME = "model_int4.onnx"

    /** adb 部署时的文件名，见 [adbDir]。 */
    const val ADB_FILE_NAME = "laya-model.onnx"

    /** 262 MB。用于进度显示与完整性判断。 */
    const val EXPECTED_BYTES = 275_189_760L

    /** 下载源。镜像优先，因为官方站在国内基本连不通。 */
    private val SOURCES = listOf(
        "https://hf-mirror.com/techtheist/laya-onnx/resolve/main/en/$FILE_NAME",
        "https://huggingface.co/techtheist/laya-onnx/resolve/main/en/$FILE_NAME",
    )

    /** 可被 adb push 的目录。 */
    fun externalDir(context: Context): File =
        File(context.getExternalFilesDir(null), "models")

    /** 内部私有目录。 */
    fun internalDir(context: Context): File =
        File(context.filesDir, "models")

    /**
     * adb 部署落点。
     *
     * 为什么需要这个：Android 11+ 的 scoped storage 下，adb shell 既不能读写
     * `Android/data/<包名>/`（所以不能直接 push 进外部私有目录），而 `/sdcard`
     * 又归 shell 管、应用不该依赖。
     *
     * `/data/local/tmp` 是唯一同时满足「shell 可写」和「应用可读」的位置，
     * 所以它是用 adb 送模型进来的正确落点：
     *
     *   adb push model_int4.onnx /data/local/tmp/laya-model.onnx
     */
    fun adbDir(): File = File("/data/local/tmp")

    /**
     * 按优先级列出所有候选位置。
     *
     * 外部私有目录优先，因为那是 App 自己下载的落点（卸载即清理）；
     * 内部目录次之；adb 落点放最后，它属于"调试/离线部署"路径。
     */
    fun candidates(context: Context): List<File> = listOf(
        File(externalDir(context), FILE_NAME),
        File(internalDir(context), FILE_NAME),
        File(adbDir(), ADB_FILE_NAME),
    )

    /** 已经存在的模型文件；没有则返回 null。 */
    fun find(context: Context): File? =
        candidates(context).firstOrNull { it.isFile && it.length() > MIN_VALID_BYTES }

    /** 找到了但尺寸不对的候选文件，用于给出更准确的报错。 */
    fun partialCandidates(context: Context): List<File> =
        candidates(context).filter { it.isFile && it.length() <= MIN_VALID_BYTES }

    /** 下载目标：优先外部目录（可 adb push，方便用户自己放）。 */
    fun targetFile(context: Context): File {
        val dir = externalDir(context)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    /**
     * 比最小可用尺寸小就认为不是完整模型。
     * int4 量化后应该在 250MB 上下，这里放宽到 100MB 只用来挡明显不完整的文件。
     */
    private const val MIN_VALID_BYTES = 100L * 1024 * 1024

    /** 模型是否已就位。 */
    fun isReady(context: Context): Boolean = find(context) != null

    /** 当前模型占用体积，用于界面显示。 */
    fun sizeOnDisk(context: Context): Long = find(context)?.length() ?: 0L

    /**
     * 从 assets 读文本资源。tokenizer.json 和 rl_agent_config.json 都很小，直接打包进 APK。
     */
    fun readAsset(context: Context, name: String): String =
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    // -----------------------------------------------------------------------
    // 下载
    // -----------------------------------------------------------------------

    /**
     * 下载模型。返回落地的文件。
     *
     * 支持断点续传：如果目标文件已存在部分内容，会带 Range 头继续下载。
     * 下载中途失败不会留下半个文件冒充完整模型 —— 会重命名为 .part。
     */
    fun download(
        context: Context,
        onProgress: (downloaded: Long, total: Long, source: String) -> Unit = { _, _, _ -> },
    ): File {
        val target = targetFile(context)
        val part = File(target.parentFile, "$FILE_NAME.part")

        // 已下载的部分作为续传起点
        var existing = if (part.isFile) part.length() else 0L

        var lastError: Exception? = null

        for (source in SOURCES) {
            try {
                downloadFrom(source, part, existing, onProgress)

                // 下载完成后校验尺寸，再原子改名。
                if (part.length() < MIN_VALID_BYTES) {
                    throw IOException("下载的文件只有 ${part.length() / 1024 / 1024}MB，明显不完整")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                return target
            } catch (e: Exception) {
                lastError = e
                // 换个源重试时，已下载的部分仍然可用（如果服务器支持 Range）
                existing = if (part.isFile) part.length() else 0L
            }
        }

        throw IOException(
            "所有下载源都失败了${lastError?.let { "：${it.message}" } ?: ""}\n" +
                "你也可以手动把模型放进去（不需要 root）：\n" +
                "  adb push $FILE_NAME /data/local/tmp/$ADB_FILE_NAME\n" +
                "App 会自动从那里加载。",
            lastError,
        )
    }

    private fun downloadFrom(
        source: String,
        part: File,
        resumeFrom: Long,
        onProgress: (Long, Long, String) -> Unit,
    ) {
        val conn = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }

        try {
            val code = conn.responseCode
            // 206 = 支持续传；200 = 服务器忽略了 Range，得从头下
            val appending = code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code")
            }

            val total = conn.contentLengthLong.let {
                if (it > 0) it + (if (appending) resumeFrom else 0) else EXPECTED_BYTES
            }

            var done = if (appending) resumeFrom else 0L
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, appending).use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total, source)
                    }
                    output.flush()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // -----------------------------------------------------------------------

    /**
     * 模型文件的指纹，用于确认"我手机上这份"和"参考实现那份"是同一个文件。
     *
     * 只用长度 + 首尾各 1MB 的 SHA-256，避免把 262MB 全读一遍。
     */
    fun fingerprint(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(file.length().toString().toByteArray())
        file.inputStream().use { input ->
            val head = ByteArray(1 shl 20)
            val n = input.read(head)
            if (n > 0) md.update(head, 0, n)

            if (file.length() > (1 shl 21)) {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(file.length() - (1 shl 20))
                    val tail = ByteArray(1 shl 20)
                    raf.readFully(tail)
                    md.update(tail)
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}
