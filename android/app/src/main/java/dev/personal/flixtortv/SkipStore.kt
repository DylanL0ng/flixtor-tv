package dev.personal.flixtortv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Device-shared ranges and type names; action preferences are isolated by profile.
 */
class SkipStore(context: Context) {
    private val prefs = context.getSharedPreferences("skip", Context.MODE_PRIVATE)

    // --- IMDb ids -------------------------------------------------------------

    /** The stored id for a title, or null. Never guesses; a wrong id returns wrong skips. */
    fun imdbId(title: Title): String? = prefs.getString(idKey(title.key), null)?.takeIf { it.isNotBlank() }

    fun setImdbId(title: Title, imdbId: String?) {
        val clean = imdbId?.trim().orEmpty()
        val key = idKey(title.key)
        if (clean.isEmpty()) prefs.edit().remove(key).apply() else prefs.edit().putString(key, clean).apply()
    }

    private fun idKey(titleKey: String) = "imdb:$titleKey"

    // --- Viewer-marked segments ----------------------------------------------

    fun segments(title: Title, episode: Episode?, version: AnimeVersion? = null): List<SkipSegment> {
        val raw = prefs.getString(markedKey(title, episode, version), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getJSONObject(it) }.mapNotNull { value ->
                val type = SegmentType.entries.firstOrNull { it.read == value.optString("type") } ?: return@mapNotNull null
                val start = value.optLong("start", -1); val end = value.optLong("end", -1)
                if (start < 0 || end <= start) return@mapNotNull null
                SkipSegment(type, start, end, value.optString("id").ifBlank { "local:${type.read}:$start:$end" },
                    value.optString("typeId").ifBlank { type.read },
                    SkipAction.entries.firstOrNull { it.name == value.optString("action") }, SegmentSource.LOCAL)
            }.sortedBy { it.startMs }
        }.getOrDefault(emptyList())
    }

    fun setSegments(title: Title, episode: Episode?, segments: List<SkipSegment>, version: AnimeVersion? = null) {
        val key = markedKey(title, episode, version)
        if (segments.isEmpty()) { prefs.edit().remove(key).apply(); return }
        val values = JSONArray(segments.map { JSONObject().put("type", it.type.read).put("start", it.startMs).put("end", it.endMs)
            .put("id", it.id).put("typeId", it.typeId).put("action", it.action?.name) })
        prefs.edit().putString(key, values.toString()).apply()
    }

    /** Replaces one segment of a type, or removes that type when the segment is null. */
    fun setSegment(title: Title, episode: Episode?, segment: SkipSegment, version: AnimeVersion? = null) {
        val current = segments(title, episode, version).filterNot { it.type == segment.type }
        setSegments(title, episode, (current + segment).sortedBy { it.startMs }, version)
    }

    fun saveRegion(title: Title, episode: Episode?, segment: SkipSegment, version: AnimeVersion?) {
        setSegments(title, episode, segments(title, episode, version).filterNot { it.id == segment.id } + segment, version)
    }

    fun customTypes(): List<CustomSegmentType> = runCatching {
        val values = JSONArray(prefs.getString("customTypes", "[]"))
        (0 until values.length()).map { values.getJSONObject(it) }
            .map { CustomSegmentType(it.getString("id"), it.getString("label")) }
    }.getOrDefault(emptyList())

    fun saveCustomType(label: String, id: String? = null): CustomSegmentType {
        val clean = label.trim()
        require(clean.isNotEmpty() && clean.length <= 30)
        val existing = customTypes()
        existing.firstOrNull { it.label.equals(clean, true) && it.id != id }?.let { return it }
        require(id != null || existing.size < 50)
        val type = CustomSegmentType(id ?: UUID.randomUUID().toString(), clean)
        prefs.edit().putString("customTypes", JSONArray((existing.filterNot { it.id == type.id } + type).map {
            JSONObject().put("id", it.id).put("label", it.label)
        }).toString()).apply()
        return type
    }

    fun action(profile: String, typeId: String, titleKey: String? = null): SkipAction? =
        SkipAction.entries.firstOrNull { it.name == prefs.getString(actionKey(profile, typeId, titleKey), null) }

    fun setAction(profile: String, typeId: String, action: SkipAction?, titleKey: String? = null) {
        val key = actionKey(profile, typeId, titleKey)
        if (action == null) prefs.edit().remove(key).apply() else prefs.edit().putString(key, action.name).apply()
    }
    private fun actionKey(profile: String, typeId: String, title: String?) = "action:$profile:${title.orEmpty()}:$typeId"
    fun clearProfile(profile: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("action:$profile:") }.forEach(editor::remove)
        editor.apply()
    }

    fun animeMapping(title: Title, episode: Episode?, provider: AnimeMapping?): AnimeMapping? {
        val mal = prefs.getString("mal:${title.key}", null)?.toIntOrNull()?.takeIf { it > 0 } ?: provider?.malId ?: return null
        val number = prefs.getString("animeEpisode:${title.key}:${episode?.key}", null)?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0 } ?: provider?.episode
            ?: episode?.displayNumber?.toIntOrNull()?.takeIf { it > 0 }?.toDouble() ?: return null
        return AnimeMapping(mal, number)
    }
    fun setAnimeMapping(title: Title, episode: Episode?, mal: String, number: String) {
        require(mal.isBlank() || mal.toIntOrNull()?.let { it > 0 } == true)
        require(number.isBlank() || number.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true)
        val editor = prefs.edit()
        if (mal.isBlank()) editor.remove("mal:${title.key}") else editor.putString("mal:${title.key}", mal)
        val key = "animeEpisode:${title.key}:${episode?.key}"
        if (number.isBlank()) editor.remove(key) else editor.putString(key, number)
        editor.apply()
    }

    private fun markedKey(title: Title, episode: Episode?, version: AnimeVersion?) =
        "mark:${title.key}:${episode?.key.orEmpty()}" + (version?.let { ":${it.wire}" } ?: "")
}

/**
 * The IntroDB key comes from the build environment (TV_INTRODB_API_KEY), never from the
 * viewer and never from the APK's preferences. It is blank in local builds, which only
 * disables contributing.
 */
internal fun introDbKey(): String = BuildConfig.INTRODB_API_KEY.trim()

/** True only when a build actually supplied a key. */
internal val introDbConfigured get() = introDbKey().isNotEmpty()
