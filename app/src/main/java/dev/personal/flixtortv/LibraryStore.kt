package dev.personal.flixtortv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LibraryStore(context: Context, profileId: String = "default") {
    private val prefs = context.getSharedPreferences("library_$profileId", Context.MODE_PRIVATE)
    private fun title(value: JSONObject) = Title(value.getString("id"), value.getString("path"),
        value.getString("name"), value.optString("poster"), value.optString("year"),
        value.optString("rating"), value.optString("genres"), value.optString("synopsis"), value.optString("backdrop"))
    private fun json(t: Title) = JSONObject().put("id", t.id).put("path", t.path).put("name", t.name)
        .put("poster", t.poster).put("year", t.year).put("rating", t.rating).put("genres", t.genres)
        .put("synopsis", t.synopsis.take(1000)).put("backdrop", t.backdrop)
    private fun entries(key: String): List<JSONObject> = try {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
    } catch (_: Exception) { emptyList() }
    fun favorites(): List<Title> = entries("favorites").mapNotNull { runCatching { title(it) }.getOrNull() }
    fun toggle(t: Title): List<Title> {
        val current = favorites().toMutableList()
        if (current.any { it.key == t.key }) current.removeAll { it.key == t.key } else current.add(0, t)
        val bounded = current.take(200)
        prefs.edit().putString("favorites", JSONArray(bounded.map(::json)).toString()).apply()
        return bounded
    }
    fun viewing(): List<Viewing> = entries("viewing").mapNotNull { v -> runCatching {
        val e = v.optJSONObject("episode")?.let { Episode(it.getInt("season"), it.getInt("number"), it.getString("name")) }
        Viewing(title(v.getJSONObject("title")), e, v.getLong("position"), v.getLong("duration"))
    }.getOrNull() }
    fun save(viewing: Viewing) {
        if (viewing.position < 1000) return
        val list = listOf(viewing) + viewing().filter { it.key != viewing.key }
        val values = list.take(100).map { v -> JSONObject().put("title", json(v.title))
            .put("position", v.position).put("duration", v.duration).also { obj ->
                v.episode?.let { obj.put("episode", JSONObject().put("season", it.season).put("number", it.number).put("name", it.name)) }
            }
        }
        prefs.edit().putString("viewing", JSONArray(values).toString()).apply()
    }
    fun clear() { prefs.edit().clear().apply() }
}
