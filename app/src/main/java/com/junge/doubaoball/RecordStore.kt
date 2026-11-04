package com.junge.doubaoball

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 操作记录存储（本地JSON文件）
 * 支持：自动增加、查询全部、删除单条、清空、给记录加备注
 */
class RecordStore(context: Context) {

    data class Record(
        val id: Long,
        val time: String,
        val action: String,   // 动作：单击唤醒 / 暂停 / 继续 / 挂断 ...
        val result: String,   // 结果：成功 / 失败
        var note: String = "" // 备注（可编辑）
    )

    private val file: File = File(context.filesDir, "records.json")
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    @Synchronized
    fun add(action: String, result: String) {
        val list = queryAll().toMutableList()
        list.add(0, Record(System.currentTimeMillis(), fmt.format(Date()), action, result))
        // 最多保留500条，防止文件膨胀
        val trimmed = if (list.size > 500) list.subList(0, 500) else list
        saveAll(trimmed)
    }

    @Synchronized
    fun queryAll(): List<Record> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Record(
                    o.getLong("id"), o.getString("time"),
                    o.getString("action"), o.getString("result"),
                    o.optString("note", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun delete(id: Long) {
        saveAll(queryAll().filterNot { it.id == id })
    }

    @Synchronized
    fun updateNote(id: Long, note: String) {
        val list = queryAll()
        list.firstOrNull { it.id == id }?.note = note
        saveAll(list)
    }

    @Synchronized
    fun clear() = saveAll(emptyList())

    private fun saveAll(list: List<Record>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("time", r.time)
                put("action", r.action); put("result", r.result)
                put("note", r.note)
            })
        }
        file.writeText(arr.toString())
    }
}
