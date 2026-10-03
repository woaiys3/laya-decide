package com.laya.decide.tokenizer

import java.text.Normalizer

/**
 * Laya 分词器的 Kotlin 移植（ByteLevel BPE，ModernBERT 用的那套）。
 *
 * 这是整个端侧移植里最容易出错的一环：**只要有一个 token id 不同，模型的判断就可能变。**
 * 所以我用 `@huggingface/tokenizers`（Node，参考实现）生成了一份「输入 -> 期望 id」夹具，
 * `TokenizerFixtureTest` 逐 id 比对。改动这个文件后必须重跑那个测试。
 *
 * 管道（与 tokenizers 的 ByteLevel 一致）：
 *
 *   NFC 规范化 -> 切出特殊 token -> GPT-2 正则预切分
 *     -> 每个片段按字节映射成 unicode 字符
 *     -> BPE 合并 -> 查词表
 *
 * 参考：huggingface/tokenizers 的 ByteLevel 预分词 + BPE 模型。
 */
class Tokenizer private constructor(
    /**
     * token 文本 -> id。只保存词表里长度 <= 16 的 token 的 String 形式。
     *
     * 为什么：50k 个 String 键的 HashMap 在 Android 上要吃掉十几 MB 堆，
     * 而模型本身已经占了 262MB 原生内存。改成「短 token 才存字符串」之后，
     * 绝大多数 token 走 BigInteger-keyed 的表，堆占用能降一大截。
     */
    private val shortVocab: Map<String, Int>,
    /** token 文本的字符和 -> 候选 id 列表。用于查长度 > 16 的 token。 */
    private val longBySum: Map<Int, List<Int>>,
    /** id -> token 文本，长 token 回查用。 */
    private val longByValue: Map<Int, String>,
    private val merges: Map<String, Int>,
    private val addedTokens: List<AddedToken>,
    /** 特殊 token 文本 -> id，只在 encode 时用来切分，不会自动添加。 */
    private val specialTokens: Map<String, Int>,
) {

    val vocabSize: Int get() = shortVocab.size + longByValue.size

    /** added token：优先于 BPE 按字面匹配的 token。 */
    data class AddedToken(
        val content: String,
        val id: Int,
        val special: Boolean,
        val singleWord: Boolean,
        val lstrip: Boolean,
        val rstrip: Boolean,
    )

    /**
     * 编码为 token id 序列。**不添加任何特殊 token** —— 与参考实现
     * `encode(text, { add_special_tokens: false })` 对齐，序列布局由上层
     * `SequenceBuilder` 自己拼。
     */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)

        val normalized = nfc(text)
        val out = ArrayList<Int>(normalized.length / 2 + 4)

        // 1) 先把 added token 切出来。它们按字面匹配，不参与 BPE。
        var pos = 0
        val plain = StringBuilder()

        while (pos < normalized.length) {
            val hit = matchAddedToken(normalized, pos)
            if (hit != null) {
                // lstrip：这个 token 会把它左侧的空白吃掉。
                // [MASK] 就是 lstrip=true，所以 "a [MASK] b" 里的空格不能单独成 token。
                if (hit.token.lstrip) {
                    while (plain.isNotEmpty() && plain.last().isWhitespace()) {
                        plain.setLength(plain.length - 1)
                    }
                }
                if (plain.isNotEmpty()) {
                    encodePlain(plain.toString(), out)
                    plain.setLength(0)
                }
                out.add(hit.token.id)

                pos += hit.length
                // rstrip：右侧空白也吃掉（本词表里没有这种 token，但机制要完整）
                if (hit.token.rstrip) {
                    while (pos < normalized.length && normalized[pos].isWhitespace()) pos++
                }
            } else {
                plain.append(normalized[pos])
                pos++
            }
        }
        if (plain.isNotEmpty()) encodePlain(plain.toString(), out)

        return out.toIntArray()
    }

    private data class AddedHit(val token: AddedToken, val length: Int)

    /**
     * 在 [pos] 处尝试匹配一个 added token。
     *
     * 长的优先（"|||EMAIL_ADDRESS|||" 要赢过任何前缀），
     * 并处理 strip 空白与 single_word 约束。
     */
    private fun matchAddedToken(text: String, pos: Int): AddedHit? {
        var best: AddedHit? = null
        for (t in addedTokens) {
            val c = t.content
            if (c.isEmpty()) continue
            if (!text.startsWith(c, pos)) continue

            if (t.singleWord) {
                // 前后必须是非字母数字（或串首尾）
                val before = if (pos == 0) null else text[pos - 1]
                val afterIdx = pos + c.length
                val after = if (afterIdx >= text.length) null else text[afterIdx]
                if (before != null && before.isLetterOrDigit()) continue
                if (after != null && after.isLetterOrDigit()) continue
            }

            if (best == null || c.length > best.token.content.length) {
                best = AddedHit(t, c.length)
            }
        }
        return best
    }

    /**
     * 普通文本：GPT-2 预切分 -> UTF-8 字节映射 -> BPE。
     *
     * 注意必须先转 UTF-8 字节再逐字节映射，不能按字符映射：
     * 中文/emoji 都是多字节的，按字符映射会让所有非 ASCII 文本编错。
     */
    private fun encodePlain(text: String, out: MutableList<Int>) {
        for (piece in Gpt2PreTokenizer.split(text)) {
            if (piece.isEmpty()) continue
            val symbols = Gpt2Bytes.utf8Bytes(piece).map { Gpt2Bytes.byteToChar(it) }
            for (id in bpe(symbols)) out.add(id)
        }
    }

    /**
     * 标准 BPE：反复合并 rank 最小（词表里最早出现）的相邻对，直到无可合并。
     *
     * 复杂度 O(n^3) 的写法（每轮重建整个列表）在长输入上会把测试挂死 ——
     * 夹具里有 300 字符的用例。这里改成原地更新 + 早退。
     */
    private fun bpe(chars: List<Char>): List<Int> {
        val n = chars.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(idOf(chars[0].toString()))

        // 符号用 String 持有，因为合并后是多字符。
        var sym = Array(n) { chars[it].toString() }
        var len = n

        while (len > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1

            for (j in 0 until len - 1) {
                val rank = merges[sym[j] + sym[j + 1]]
                if (rank != null && rank < bestRank) {
                    bestRank = rank
                    bestIdx = j
                    // rank 0 已是最小，不可能更优，直接停。
                    if (bestRank == 0) break
                }
            }
            if (bestIdx < 0) break

            sym[bestIdx] = sym[bestIdx] + sym[bestIdx + 1]
            // 左移覆盖被合并掉的那个符号。
            for (j in bestIdx + 1 until len - 1) sym[j] = sym[j + 1]
            len--
        }

        return (0 until len).map { idOf(sym[it]) }
    }

    private fun idOf(token: String): Int {
        val direct = shortVocab[token]
        if (direct != null) return direct
        // 长 token 走字符和索引，避免为它们保留字符串键
        longBySum[token.sumOf { it.code }]?.let { ids ->
            for (id in ids) {
                if (longByValue[id] == token) return id
            }
        }
        return unkId
    }

    // ---------------------------------------------------------------------

    val clsId: Int get() = require(CLS)
    val sepId: Int get() = require(SEP)
    val maskId: Int get() = require(MASK)
    val padId: Int get() = require(PAD)
    val unkId: Int get() = require(UNK)

    private fun require(text: String): Int =
        specialTokens[text] ?: shortVocab[text]
        ?: throw IllegalStateException("tokenizer.json 里缺少特殊 token: $text")

    // ---------------------------------------------------------------------

    companion object {
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val MASK = "[MASK]"
        const val PAD = "[PAD]"
        const val UNK = "[UNK]"

        /** HuggingFace 的 `clean_up_tokenization_spaces` 之类不影响 encode，这里不处理。 */
        private fun nfc(s: String): String =
            if (Normalizer.isNormalized(s, Normalizer.Form.NFC)) s
            else Normalizer.normalize(s, Normalizer.Form.NFC)

        /**
         * 从 tokenizer.json 的原始文本构建分词器。
         *
         * @param json tokenizer.json 的内容
         */
        fun fromJson(json: String): Tokenizer {
            val root = Json.asObject(Json.parse(json))

            val model = Json.asObject(root["model"])
            val vocabRaw = Json.asObject(model["vocab"])

            // 短 token 存字符串，长 token 只存字符和 -> id。
            // 这样能把 50k 个 String 键的堆开销降到很小 —— Android 上这段内存
            // 要和 262MB 的模型抢，必须省。
            val short = HashMap<String, Int>(vocabRaw.size * 2)
            val longSum = HashMap<Int, MutableList<Int>>(256)
            val longVal = HashMap<Int, String>(256)

            for ((k, v) in vocabRaw) {
                val id = Json.asInt(v) ?: continue
                if (k.length <= SHORT_TOKEN_MAX_CHARS) {
                    short[k] = id
                } else {
                    longSum.getOrPut(k.sumOf { it.code }) { ArrayList(2) }.add(id)
                    longVal[id] = k
                }
            }

            // merges 可能是 "a b" 字符串列表，也可能是 ["a","b"] 数对列表，
            // 两种 tokenizer.json 都存在，都要支持。
            val merges = HashMap<String, Int>(1 shl 17)
            var rank = 0
            for (m in Json.asArray(model["merges"])) {
                when (m) {
                    is String -> {
                        val sp = m.indexOf(' ')
                        if (sp > 0) merges[m.substring(0, sp) + m.substring(sp + 1)] = rank
                    }
                    is List<*> -> {
                        if (m.size == 2) {
                            val a = Json.asString(m[0])
                            val b = Json.asString(m[1])
                            if (a != null && b != null) merges[a + b] = rank
                        }
                    }
                }
                rank++
            }

            val added = ArrayList<AddedToken>()
            val specials = HashMap<String, Int>()
            for (t in Json.asArray(root["added_tokens"])) {
                val o = Json.asObject(t)
                val content = Json.asString(o["content"]) ?: continue
                val id = Json.asInt(o["id"]) ?: continue
                val special = Json.asBool(o["special"]) ?: false
                added.add(
                    AddedToken(
                        content = content,
                        id = id,
                        special = special,
                        singleWord = Json.asBool(o["single_word"]) ?: false,
                        lstrip = Json.asBool(o["lstrip"]) ?: false,
                        rstrip = Json.asBool(o["rstrip"]) ?: false,
                    ),
                )
                if (special) specials[content] = id
            }

            // 长的优先匹配，避免短 token 抢走长 token 的前缀。
            added.sortByDescending { it.content.length }

            return Tokenizer(short, longSum, longVal, merges, added, specials)
        }

        /** 超过这个长度的 token 不保留字符串形式。词表里最长的 token 也就 12 个字符左右。 */
        private const val SHORT_TOKEN_MAX_CHARS = 16
    }
}

// ---------------------------------------------------------------------------
// ByteLevel 预分词
// ---------------------------------------------------------------------------

/**
 * GPT-2 的字节级预切分正则，手写成扫描器。
 *
 * 原始正则（Python 版）：
 *
 *   's|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+
 *
 * 选择手写而不是用 java.util.regex，是因为这个模式依赖 Unicode 属性、
 * 负向前瞻和精确的回溯语义，跨引擎很容易有细微差异。手写反而更可控，
 * 而且有 64 条夹具兜底。
 */
internal object Gpt2PreTokenizer {

    /** 长在前，保证 're 不会被 'r 抢先（虽然 'r 不在表里，但顺序敏感）。 */
    private val CONTRACTIONS = listOf("'re", "'ve", "'ll", "'s", "'t", "'m", "'d")

    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = text.length

        while (i < n) {
            val c = text[i]

            // 1) 缩写：'s / 't / 're / 've / 'm / 'll / 'd
            if (c == '\'') {
                var hit: String? = null
                for (k in CONTRACTIONS) {
                    if (text.startsWith(k, i)) { hit = k; break }
                }
                if (hit != null) {
                    out.add(hit)
                    i += hit.length
                    continue
                }
            }

            // 2) 可选前导空格 + 字母
            if (c.isLetter() || (c == ' ' && i + 1 < n && text[i + 1].isLetter())) {
                val start = i
                i += if (c == ' ') 1 else 0
                while (i < n && text[i].isLetter()) i++
                out.add(text.substring(start, i))
                continue
            }

            // 3) 可选前导空格 + 数字
            if (c.isDigit() || (c == ' ' && i + 1 < n && text[i + 1].isDigit())) {
                val start = i
                i += if (c == ' ') 1 else 0
                while (i < n && text[i].isDigit()) i++
                out.add(text.substring(start, i))
                continue
            }

            // 4) 可选前导空格 + 其它非空白非字母数字
            val isSymbol = !c.isWhitespace() && !c.isLetter() && !c.isDigit()
            val spaceThenSymbol =
                c == ' ' && i + 1 < n && !text[i + 1].isWhitespace() &&
                    !text[i + 1].isLetter() && !text[i + 1].isDigit()
            if (isSymbol || spaceThenSymbol) {
                val start = i
                i += if (c == ' ') 1 else 0
                while (i < n && !text[i].isWhitespace() && !text[i].isLetter() && !text[i].isDigit()) i++
                out.add(text.substring(start, i))
                continue
            }

            // 5) 空白。对应 \s+(?!\S)|\s+ 的联合效果：
            //    取整段空白；若后面还有非空白字符，则只取「长度-1」个，
            //    剩下的那个空格留给下一轮的「空格+词」分支。
            if (c.isWhitespace()) {
                var j = i
                while (j < n && text[j].isWhitespace()) j++
                val runLen = j - i
                val followedByNonSpace = j < n
                val take = if (followedByNonSpace) maxOf(1, runLen - 1) else runLen
                out.add(text.substring(i, i + take))
                i += take
                continue
            }

            // 理论上到不了这里；真到了就单字符前进，别死循环。
            out.add(c.toString())
            i++
        }

        return out
    }
}

// ---------------------------------------------------------------------------
// GPT-2 字节 <-> unicode 映射
// ---------------------------------------------------------------------------

/**
 * GPT-2 的 bytes_to_unicode：把 256 个字节可逆地映射到可见 unicode 字符，
 * 这样 BPE 词表里不会出现控制字符。
 *
 * 规则：可打印字节（含 latin-1 的可见区）保持原样，
 * 其余按顺序映射到 256 之后的码点，避免与前者冲突。
 */
internal object Gpt2Bytes {

    private val charToByte: Map<Char, Byte>
    private val byteToChar: CharArray

    init {
        val bs = ArrayList<Int>()
        // '!'..'~'
        for (b in 0x21..0x7E) bs.add(b)
        // '¡'..'¬'
        for (b in 0xA1..0xAC) bs.add(b)
        // '®'..'ÿ'
        for (b in 0xAE..0xFF) bs.add(b)

        val cs = ArrayList<Int>(bs)
        var n = 0
        for (b in 0..255) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }

        val b2c = CharArray(256)
        val c2b = HashMap<Char, Byte>(512)
        for (k in bs.indices) {
            val b = bs[k]
            val c = cs[k].toChar()
            b2c[b] = c
            c2b[c] = b.toByte()
        }
        byteToChar = b2c
        charToByte = c2b
    }

    /** UTF-8 字节 -> BPE 符号字符 */
    fun byteToChar(b: Byte): Char = byteToChar[b.toInt() and 0xFF]

    fun charToByte(c: Char): Byte? = charToByte[c]

    /** 按 UTF-8 把字符串拆成字节（这是 `piece.map { byteToChar(it) }` 的前提）。 */
    fun utf8Bytes(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)
}
