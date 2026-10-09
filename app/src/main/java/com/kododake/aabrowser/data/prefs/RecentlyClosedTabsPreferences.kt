package com.kododake.aabrowser.data.prefs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object RecentlyClosedTabsPreferences {
    const val MAX_ENTRIES = 9

    private const val PREFS_NAME = TabSessionPreferences.PREFS_NAME
    private const val KEY_RECENTLY_CLOSED = "recently_closed_tabs"

    data class Entry(val url: String, val title: String)

    fun get(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECENTLY_CLOSED, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = obj.optString("url").trim()
                    if (UrlFormatter.isHttpOrHttps(url)) add(Entry(url, obj.optString("title").trim()))
                }
            }.take(MAX_ENTRIES)
        }.getOrDefault(emptyList())
    }

    /** Records a closed tab at the front of the list, dropping any older entry for the same URL. */
    fun add(context: Context, url: String, title: String) {
        val trimmed = url.trim()
        if (!UrlFormatter.isHttpOrHttps(trimmed)) return
        val updated = (listOf(Entry(trimmed, title.trim())) + get(context).filter { it.url != trimmed })
            .take(MAX_ENTRIES)
        save(context, updated)
    }

    fun remove(context: Context, url: String) {
        save(context, get(context).filter { it.url != url })
    }

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("url", it.url).put("title", it.title)) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENTLY_CLOSED, array.toString())
            .apply()
    }
}
