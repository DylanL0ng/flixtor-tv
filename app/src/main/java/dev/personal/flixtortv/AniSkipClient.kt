package dev.personal.flixtortv

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** Public, read-only anime timestamps. No service login or media cookies. */
class AniSkipClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS).build()) {
    suspend fun segments(mapping: AnimeMapping, durationMs: Long): List<SkipSegment>? {
        val number = if (mapping.episode % 1.0 == 0.0) mapping.episode.toLong().toString() else mapping.episode.toString()
        val url = "https://api.aniskip.com/v2/skip-times/${mapping.malId}/$number".toHttpUrl().newBuilder()
            .addQueryParameter("episodeLength", (durationMs / 1000.0).toString())
        listOf("op", "ed", "recap", "mixed-op", "mixed-ed").forEach { url.addQueryParameter("types", it) }
        val request = Request.Builder().url(url.build()).build()
        val raw = withContext(Dispatchers.IO) { read(http.newCall(request)) } ?: return null
        return withContext(Dispatchers.Default) {
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if (json?.opt("found") !is Boolean || json.optBoolean("found") && json.optJSONArray("results") == null) null
            else parse(raw, durationMs)
        }
    }

    private suspend fun read(call: Call): String? = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val raw = runCatching { response.use { r ->
                    if (r.code == 404) return@use "{\"found\":false}"
                    if (!r.isSuccessful) return@use null
                    val body = r.body ?: return@use null
                    val input = body.source()
                    if (input.request(262145)) return@use null
                    input.readUtf8()
                } }.getOrNull()
                if (continuation.isActive) continuation.resume(raw)
            }
        })
    }
    fun close() = http.closeAsync()

    companion object {
        fun mapping(embed: String): AnimeMapping? = runCatching {
            val parts = embed.toHttpUrl().pathSegments
            val i = parts.indexOf("mal")
            if (i < 0) return@runCatching null
            val id = parts.getOrNull(i + 1)?.toIntOrNull()?.takeIf { it > 0 } ?: return@runCatching null
            val episode = parts.getOrNull(i + 2)?.toIntOrNull()?.takeIf { it > 0 } ?: return@runCatching null
            AnimeMapping(id, episode.toDouble())
        }.getOrNull()

        fun parse(raw: String, durationMs: Long): List<SkipSegment> = runCatching {
            val json = JSONObject(raw)
            if (!json.optBoolean("found") || durationMs <= 0) return@runCatching emptyList()
            val results = json.optJSONArray("results") ?: return@runCatching emptyList()
            (0 until results.length().coerceAtMost(100)).mapNotNull { i ->
                val value = results.optJSONObject(i) ?: return@mapNotNull null
                val type = when (value.optString("skipType")) {
                    "op" -> SegmentType.INTRO
                    "ed" -> SegmentType.CREDITS
                    "recap" -> SegmentType.RECAP
                    "mixed-op" -> SegmentType.MIXED_INTRO
                    "mixed-ed" -> SegmentType.MIXED_CREDITS
                    else -> return@mapNotNull null
                }
                val interval = value.optJSONObject("interval") ?: return@mapNotNull null
                val from = interval.optDouble("startTime", Double.NaN) * 1000
                val to = interval.optDouble("endTime", Double.NaN) * 1000
                if (!from.isFinite() || !to.isFinite() || from < 0 || to <= from || to > durationMs) return@mapNotNull null
                val start = from.toLong(); val end = to.toLong()
                if (end <= start) return@mapNotNull null
                SkipSegment(type, start, end,
                    "aniskip:" + value.optString("skipId").ifBlank { "${type.read}:$start:$end" },
                    source = SegmentSource.ANISKIP)
            }.distinctBy { it.id }.sortedBy { it.startMs }
        }.getOrDefault(emptyList())
    }
}
