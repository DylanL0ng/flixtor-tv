package dev.personal.flixtortv

import org.json.JSONObject

/** One codec for durable library entries and evicted catalog pages. */
internal object ContentJson {
    fun title(t: Title) = JSONObject().put("id", t.id).put("path", t.path).put("name", t.name)
        .put("poster", t.poster).put("year", t.year).put("rating", t.rating).put("genres", t.genres)
        .put("synopsis", t.synopsis.take(1000)).put("backdrop", t.backdrop).put("imdb", t.imdb)
        .put("source", t.source.name).put("type", t.type?.name)

    fun title(v: JSONObject) = Title(v.getString("id"), v.getString("path"), v.getString("name"),
        v.optString("poster"), v.optString("year"), v.optString("rating"), v.optString("genres"),
        v.optString("synopsis"), v.optString("backdrop"), v.optString("imdb"),
        if (!v.has("source")) ContentSource.FLIXTOR else ContentSource.valueOf(v.getString("source")),
        v.optString("type").takeIf { it.isNotBlank() }?.let(ContentType::valueOf))

    fun episode(e: Episode) = JSONObject().put("season", e.season).put("number", e.number).put("name", e.name)
        .put("providerId", e.providerId).put("displayNumber", e.displayNumber).put("order", e.order)

    fun episode(v: JSONObject) = Episode(v.getInt("season"), v.getInt("number"), v.getString("name"),
        v.optString("providerId"), v.optString("displayNumber", v.getInt("number").toString()),
        if (v.has("order") && !v.isNull("order")) v.getInt("order") else null)

    fun viewing(v: Viewing) = JSONObject().put("title", title(v.title)).put("episode", v.episode?.let(::episode))
        .put("position", v.position).put("duration", v.duration).put("version", v.version?.name)

    fun viewing(v: JSONObject) = Viewing(title(v.getJSONObject("title")), v.optJSONObject("episode")?.let(::episode),
        v.getLong("position"), v.getLong("duration"),
        v.optString("version").takeIf { it.isNotBlank() }?.let(AnimeVersion::valueOf))
}
