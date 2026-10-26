package dev.personal.flixtortv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LibraryStore(context: Context, profileId: String = "default") {
    private val prefs = context.getSharedPreferences("library_$profileId", Context.MODE_PRIVATE)
    private fun entries(key: String): List<JSONObject> = try {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
    } catch (_: Exception) { emptyList() }
    fun favorites(): List<Title> = entries("favorites").mapNotNull { runCatching { ContentJson.title(it) }.getOrNull() }
    fun toggle(t: Title): List<Title> {
        val current = favorites().toMutableList()
        if (current.any { it.key == t.key }) current.removeAll { it.key == t.key } else current.add(0, t)
        val bounded = current.take(200)
        prefs.edit().putString("favorites", JSONArray(bounded.map(ContentJson::title)).toString()).apply()
        return bounded
    }
    fun viewing(): List<Viewing> = entries("viewing").mapNotNull { runCatching { ContentJson.viewing(it) }.getOrNull() }
    fun save(viewing: Viewing) {
        if (viewing.position < 1000) return
        val list = listOf(viewing) + viewing().filter { it.key != viewing.key }
        val values = list.take(100).map(ContentJson::viewing)
        prefs.edit().putString("viewing", JSONArray(values).toString()).apply()
    }
    fun clear() { prefs.edit().clear().apply() }
}
