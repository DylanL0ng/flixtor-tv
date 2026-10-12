package dev.personal.flixtortv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Read-only IntroDB client. Segments are keyed by IMDb id, which this service does not
 * provide; callers must resolve one before requesting.
 *
 * Reads are public. Writes need the viewer's own API key, which never ships in the APK.
 */
class IntroDbClient(private val http: OkHttpClient = defaultHttp()) {

    /** Fetches every segment for one episode, or null when the service cannot answer. */
    suspend fun segments(imdbId: String, season: Int, episode: Int): Segments? {
        val query = "imdb_id=$imdbId&season=$season&episode=$episode"
        val body = get("/segments?$query") ?: return null
        return withContext(Dispatchers.Default) { parseSegments(body, imdbId) }
    }

    /** Fetches every segment for a film. */
    suspend fun segments(imdbId: String): Segments? {
        val body = get("/segments?imdb_id=$imdbId&is_movie=true") ?: return null
        return withContext(Dispatchers.Default) { parseSegments(body, imdbId) }
    }

    /**
     * Contributes one segment using the viewer's own API key. Returns true when the
     * service accepted it. A shared application key is never used for writes.
     */
    suspend fun submit(body: JSONObject, apiKey: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$BASE/submit")
            .header("X-API-Key", apiKey)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(json))
            .build()
        try {
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (_: IOException) { false } catch (_: CancellationException) { false }
    }

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BASE + path).header("Accept", "application/json").build()
        try {
            http.newCall(request).execute().use { response ->
                // 400 means the query was rejected; anything else is a transport or service problem.
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        } catch (_: IOException) { null } catch (_: CancellationException) { null }
    }

    companion object {
        const val BASE = "https://api.introdb.app"
        private val json = "application/json; charset=utf-8".toMediaType()

        /**
         * Parses a `/segments` response. Every segment is optional and may carry either
         * `start_ms`/`end_ms` or `start_sec`/`end_sec`; seconds may be fractional.
         */
        fun parseSegments(raw: String, imdbId: String): Segments {
            val json = runCatching { JSONObject(raw) }.getOrElse { return Segments(imdbId) }
            val items = SegmentType.entries.mapNotNull { type ->
                val segment = json.optJSONObject(type.read) ?: return@mapNotNull null
                val start = segment.millis("start_ms") ?: segment.seconds("start_sec")
                val end = segment.millis("end_ms") ?: segment.seconds("end_sec")
                // A segment must end after it starts, or the skip would seek backwards.
                if (start == null || end == null || end <= start) return@mapNotNull null
                SkipSegment(type, start, end)
            }.sortedBy { it.startMs }
            return Segments(imdbId, items)
        }

        /** A positive whole-millisecond field, or null when absent, zero or fractional. */
        private fun JSONObject.millis(key: String): Long? {
            if (!has(key) || isNull(key)) return null
            val value = optDouble(key, -1.0)
            return if (value > 0 && value % 1.0 == 0.0) value.toLong() else null
        }

        /** A non-negative seconds field converted to milliseconds, or null when absent. */
        private fun JSONObject.seconds(key: String): Long? {
            if (!has(key) || isNull(key)) return null
            val value = optDouble(key, -1.0)
            return if (value >= 0) (value * 1000).toLong() else null
        }

        /** Builds a submission body. Timestamps are seconds, matching the documented form. */
        fun submitBody(segment: SkipSegment, imdbId: String, season: Int, episode: Int): JSONObject =
            JSONObject().put("imdb_id", imdbId)
                .put("segment_type", segment.type.submit)
                .put("season", season).put("episode", episode)
                .put("start_sec", segment.startMs / 1000)
                .put("end_sec", segment.endMs / 1000)

        fun submitBody(segment: SkipSegment, imdbId: String): JSONObject =
            JSONObject().put("imdb_id", imdbId)
                .put("is_movie", true)
                .put("segment_type", segment.type.submit)
                .put("start_sec", segment.startMs / 1000)
                .put("end_sec", segment.endMs / 1000)

        private fun defaultHttp() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).build()
    }
}
