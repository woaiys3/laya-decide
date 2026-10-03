package com.laya.decide.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.laya.decide.core.Decision
import com.laya.decide.core.MarginLabel
import com.laya.decide.core.RankedOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * 历史记录 —— 直接用 SQLite，没上 Room。
 *
 * 理由：这个项目只存一张表，Room 会平白引入注解处理器（kapt/KSP）这一层
 * 构建复杂度。等表结构复杂到值得用 Room 的时候再换。
 */
class HistoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID       INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIME     INTEGER NOT NULL,
                $COL_SITUATION TEXT NOT NULL,
                $COL_OPTIONS  TEXT NOT NULL,
                $COL_PICK     TEXT,
                $COL_RANKED   TEXT NOT NULL,
                $COL_NOTE     TEXT,
                $COL_MARGIN   REAL,
                $COL_LABEL    TEXT,
                $COL_MODE     TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_time ON $TABLE($COL_TIME DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 演示项目：结构变了就重建。真上线要写迁移。
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun add(
        situation: String,
        options: List<String>,
        decision: Decision,
        modeKey: String,
    ): Long {
        val values = ContentValues().apply {
            put(COL_TIME, System.currentTimeMillis())
            put(COL_SITUATION, situation)
            put(COL_OPTIONS, JSONArray(options).toString())
            put(COL_PICK, decision.pick)
            put(COL_RANKED, rankedToJson(decision.ranked))
            put(COL_NOTE, decision.note)
            decision.margin?.let { put(COL_MARGIN, it) }
            put(COL_LABEL, decision.marginLabel.name)
            put(COL_MODE, modeKey)
        }
        return writableDatabase.insert(TABLE, null, values)
    }

    fun latest(limit: Int = 20): List<HistoryEntry> {
        val out = ArrayList<HistoryEntry>()
        readableDatabase.query(
            TABLE,
            null,
            null,
            null,
            null,
            null,
            "$COL_TIME DESC",
            limit.toString(),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    HistoryEntry(
                        id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
                        time = c.getLong(c.getColumnIndexOrThrow(COL_TIME)),
                        situation = c.getString(c.getColumnIndexOrThrow(COL_SITUATION)),
                        options = jsonToStringList(c.getString(c.getColumnIndexOrThrow(COL_OPTIONS))),
                        decision = Decision(
                            ranked = jsonToRanked(c.getString(c.getColumnIndexOrThrow(COL_RANKED))),
                            pick = c.getString(c.getColumnIndexOrThrow(COL_PICK)),
                            margin = c.getColumnIndexOrThrow(COL_MARGIN)
                                .let { if (c.isNull(it)) null else c.getDouble(it) },
                            marginLabel = runCatching {
                                MarginLabel.valueOf(c.getString(c.getColumnIndexOrThrow(COL_LABEL)))
                            }.getOrDefault(MarginLabel.CLEAR),
                            closeCall = false,
                            note = c.getString(c.getColumnIndexOrThrow(COL_NOTE)).orEmpty(),
                        ),
                        modeKey = c.getString(c.getColumnIndexOrThrow(COL_MODE)).orEmpty(),
                    ),
                )
            }
        }
        return out
    }

    fun clear() {
        writableDatabase.delete(TABLE, null, null)
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    // -----------------------------------------------------------------------

    private fun rankedToJson(ranked: List<RankedOption>): String {
        val arr = JSONArray()
        for (r in ranked) {
            arr.put(
                JSONObject().apply {
                    put("option", r.option)
                    put("index", r.index)
                    put("score", r.score)
                    put("normalized", r.normalized)
                    put("confidence", r.confidence)
                },
            )
        }
        return arr.toString()
    }

    private fun jsonToRanked(text: String?): List<RankedOption> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RankedOption(
                    option = o.optString("option"),
                    index = o.optInt("index", i),
                    score = o.optDouble("score", 0.0),
                    normalized = o.optDouble("normalized", 0.0),
                    confidence = o.optDouble("confidence", 0.0),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun jsonToStringList(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).map { arr.optString(it) }
        }.getOrDefault(emptyList())
    }

    data class HistoryEntry(
        val id: Long,
        val time: Long,
        val situation: String,
        val options: List<String>,
        val decision: Decision,
        val modeKey: String,
    )

    companion object {
        private const val DB_NAME = "laya_decide.db"
        private const val DB_VERSION = 1
        private const val TABLE = "decisions"
        private const val COL_ID = "id"
        private const val COL_TIME = "created_at"
        private const val COL_SITUATION = "situation"
        private const val COL_OPTIONS = "options"
        private const val COL_PICK = "pick"
        private const val COL_RANKED = "ranked"
        private const val COL_NOTE = "note"
        private const val COL_MARGIN = "margin"
        private const val COL_LABEL = "margin_label"
        private const val COL_MODE = "mode"
    }
}
