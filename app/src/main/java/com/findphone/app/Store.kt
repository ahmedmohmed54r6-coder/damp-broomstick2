package com.findphone.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Store(c: Context) {
    private val p = c.getSharedPreferences("fp", Context.MODE_PRIVATE)
    var myName: String
        get() = p.getString("name", "") ?: ""
        set(v) { p.edit().putString("name", v).apply() }
    var group: String
        get() = p.getString("group", "") ?: ""
        set(v) { p.edit().putString("group", v).apply() }

    var keyword: String
        get() = p.getString("kw", "") ?: ""
        set(v) { p.edit().putString("kw", v).apply() }
    fun lastPlain() = p.getLong("lastplain", 0)
    fun setLastPlain(v: Long) { p.edit().putLong("lastplain", v).apply() }

    fun members(): List<Pair<String, String>> {
        val a = JSONArray(p.getString("members", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("n") to o.getString("p") } }
    }
    fun saveMembers(l: List<Pair<String, String>>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("n", it.first).put("p", it.second)) }
        p.edit().putString("members", a.toString()).apply()
    }
    fun memberByNumber(num: String) = members().firstOrNull { same(it.second, num) }

    fun lastTs(num: String, type: String) = p.getLong("ts_${key(num)}_$type", 0)
    fun setLastTs(num: String, type: String, ts: Long) { p.edit().putLong("ts_${key(num)}_$type", ts).apply() }

    fun saveResult(o: JSONObject) { p.edit().putString("result", o.toString()).apply() }
    fun result(): JSONObject? = p.getString("result", null)?.let { JSONObject(it) }

    companion object {
        fun key(n: String) = n.filter { it.isDigit() }.takeLast(8)
        fun same(a: String, b: String) = key(a).length >= 7 && key(a) == key(b)
    }
}
