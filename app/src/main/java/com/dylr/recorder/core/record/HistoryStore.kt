package com.dylr.recorder.core.record

import android.content.Context
import com.dylr.recorder.core.RecordItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * List<RecordItem> 的持久化：SharedPreferences 里存一段 JSON 字符串。
 * 所有读写都在同一把锁内，避免录制线程与 UI 线程并发写坏数据。
 */
class HistoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun load(): List<RecordItem> = synchronized(lock) {
        val raw = prefs.getString(KEY, null)
        if (raw.isNullOrEmpty()) return@synchronized emptyList()
        val items = ArrayList<RecordItem>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                items.add(parse(obj))
            }
        } catch (ignored: Throwable) {
            return@synchronized emptyList()
        }
        items
    }

    fun add(item: RecordItem) {
        synchronized(lock) {
            val items = ArrayList<RecordItem>(load())
            items.add(0, item)
            persist(items)
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            val items = ArrayList<RecordItem>(load())
            val kept = ArrayList<RecordItem>(items.size)
            for (item in items) {
                if (item.id != id) kept.add(item)
            }
            persist(kept)
        }
    }

    private fun parse(obj: JSONObject): RecordItem = RecordItem(
        id = obj.optString("id", ""),
        roomId = obj.optString("roomId", ""),
        title = obj.optString("title", ""),
        path = obj.optString("path", ""),
        sizeBytes = obj.optLong("sizeBytes", 0L),
        createdAt = obj.optLong("createdAt", 0L),
        success = obj.optBoolean("success", false)
    )

    private fun persist(items: List<RecordItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("roomId", item.roomId)
            obj.put("title", item.title)
            obj.put("path", item.path)
            obj.put("sizeBytes", item.sizeBytes)
            obj.put("createdAt", item.createdAt)
            obj.put("success", item.success)
            array.put(obj)
        }
        try {
            prefs.edit().putString(KEY, array.toString()).apply()
        } catch (ignored: Throwable) {
            // 写盘失败不影响录制流程
        }
    }

    private companion object {
        const val PREFS_NAME = "dylr_record_history"
        const val KEY = "history.json"
    }
}
