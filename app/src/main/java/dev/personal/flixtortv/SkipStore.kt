package dev.personal.flixtortv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local skip data the service does not supply: an IMDb id for a title, segments the
 * viewer marked by hand, and the viewer's own IntroDB API key for contributing them.
 *
 * Viewer-marked segments are authoritative locally, so a title with no community data
 * still skips. The API key is encrypted at rest with a device-bound Android Keystore key.
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
                SkipSegment(type, start, end)
            }.sortedBy { it.startMs }
        }.getOrDefault(emptyList())
    }

    fun setSegments(title: Title, episode: Episode?, segments: List<SkipSegment>, version: AnimeVersion? = null) {
        val key = markedKey(title, episode, version)
        if (segments.isEmpty()) { prefs.edit().remove(key).apply(); return }
        val values = JSONArray(segments.map { JSONObject().put("type", it.type.read).put("start", it.startMs).put("end", it.endMs) })
        prefs.edit().putString(key, values.toString()).apply()
    }

    /** Replaces one segment of a type, or removes that type when the segment is null. */
    fun setSegment(title: Title, episode: Episode?, segment: SkipSegment, version: AnimeVersion? = null) {
        val current = segments(title, episode, version).filterNot { it.type == segment.type }
        setSegments(title, episode, (current + segment).sortedBy { it.startMs }, version)
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
