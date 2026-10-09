package dev.personal.flixtortv

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Session-scoped disk pages. Only eight decoded pages are retained; keys stay stable. */
internal class CatalogPages(root: File) {
    private val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
    private val keys = mutableListOf<String>()
    private val seen = HashSet<String>()
    private val locations = mutableListOf<Pair<Int, Int>>()
    private val cache = object : LinkedHashMap<Int, List<Title>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Title>>) = size > 8
    }
    private var page = 0
    private var snapshot: Snapshot? = null
    @Synchronized fun append(titles: List<Title>): List<Title> {
        val unique = titles.filter { it.key !in seen }.distinctBy(Title::key)
        if (unique.isNotEmpty()) {
            val number = page
            val json = JSONArray(unique.map { t -> JSONObject().put("id", t.id).put("path", t.path).put("name", t.name)
                .put("poster", t.poster).put("year", t.year).put("rating", t.rating).put("genres", t.genres) })
            File(directory, "$number.json").writeText(json.toString())
            page++
            cache[number] = unique
            unique.forEachIndexed { index, title -> seen.add(title.key); keys.add(title.key); locations.add(number to index) }
        }
        return snapshot?.takeIf { it.size == keys.size } ?: Snapshot(keys.size).also { snapshot = it }
    }
    @Synchronized private fun getTitle(index: Int): Title {
        val (number, offset) = locations[index]
        val titles = cache[number] ?: JSONArray(File(directory, "$number.json").readText()).let { array ->
            (0 until array.length()).map { i -> array.getJSONObject(i).let { t ->
                Title(t.getString("id"), t.getString("path"), t.getString("name"), t.optString("poster"),
                    t.optString("year"), t.optString("rating"), t.optString("genres"))
            } }.also { cache[number] = it }
        }
        return titles[offset]
    }
    @Synchronized fun warm(index: Int) {
        if (locations.isEmpty()) return
        val number = locations[index.coerceIn(locations.indices)].first
        locations.indices.filter { locations[it].first in (number - 2)..(number + 2) }
            .distinctBy { locations[it].first }.forEach { getTitle(it) }
    }
    @Synchronized fun close() { directory.deleteRecursively() }
    inner class Snapshot(override val size: Int) : AbstractList<Title>() {
        override fun get(index: Int): Title { require(index in indices); return getTitle(index) }
        fun key(index: Int) = keys[index]
    }
}
