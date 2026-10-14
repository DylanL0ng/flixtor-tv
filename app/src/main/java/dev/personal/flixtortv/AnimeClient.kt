package dev.personal.flixtortv

import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
import org.jsoup.Jsoup

/** Built-in native adapter. Its clients never receive the FlixTor cookie jar. */
class AnimeClient internal constructor(mediaClient: OkHttpClient? = null,
    private val responseFixture: (suspend (String) -> String)? = null) {
    val mediaClient = mediaClient ?: OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    suspend fun browse(query: String, page: Int): List<Title> {
        require(query.length <= 64 && page in 1..10000)
        val url = (AnimeParser.BASE + if (query.isBlank()) "/most-popular" else "/search").toHttpUrl()
            .newBuilder().addQueryParameter("page", page.toString()).apply {
                if (query.isNotBlank()) addQueryParameter("keyword", query)
            }.build().toString()
        val html = raw(url)
        return withContext(Dispatchers.Default) { AnimeParser.cards(html) }
    }
    suspend fun detail(title: Title): Detail {
        require(title.source == ContentSource.HIANIME && title.id.all(Char::isDigit))
        val url = AnimeParser.firstParty(title.path)
        val html = raw(url)
        val episodes = raw(AnimeParser.BASE + "/api/theme/episode/list/${title.id}")
        return withContext(Dispatchers.Default) { AnimeParser.detail(html, title, AnimeParser.episodes(episodes)) }
    }
    suspend fun servers(episode: Episode): Map<AnimeVersion, String> {
        require(episode.providerId.isNotBlank() && episode.providerId.all(Char::isDigit))
        val response = raw(AnimeParser.BASE + "/api/theme/episode/servers?episodeId=${episode.providerId}")
        return withContext(Dispatchers.Default) { AnimeParser.servers(response) }
    }
    suspend fun playback(episode: Episode, version: AnimeVersion): Stream {
        val embed = servers(episode)[version] ?: throw SiteException(Failure.UNAVAILABLE)
        val html = raw(embed)
        return withContext(Dispatchers.Default) { AnimeParser.stream(html, embed) }
    }
    private suspend fun raw(url: String): String = withContext(Dispatchers.IO) {
        AnimeParser.safeUrl(url)
        responseFixture?.let { return@withContext it(url) }
        val request = Request.Builder().url(url).header("User-Agent", FlixtorClient.USER_AGENT)
            .header("Referer", AnimeParser.BASE + "/").build()
        await(mediaClient.newCall(request))
    }
    private fun readResponse(response: Response): String = response.use { r ->
            if (r.code in listOf(401, 403)) throw SiteException(Failure.ACCESS)
            if (r.code == 429 || r.code >= 500) throw SiteException(Failure.UNAVAILABLE)
            if (!r.isSuccessful) throw SiteException(Failure.CHANGED)
            val body = r.body ?: throw SiteException(Failure.CHANGED)
            val limit = 2 * 1024 * 1024
            if (body.contentLength() > limit) throw SiteException(Failure.CHANGED)
            val text = body.charStream().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > limit) throw SiteException(Failure.CHANGED)
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            if (text.contains("<title>Just a moment", true) || text.contains("cf-chl-"))
                throw SiteException(Failure.ACCESS)
            text
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun await(call: Call): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(SiteException(Failure.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                // Keep cancellation attached to the call until the bounded body is read.
                try { continuation.resume(readResponse(response)) }
                catch (error: Exception) {
                    if (!continuation.isCancelled) continuation.resumeWithException(
                        if (error is IOException) SiteException(Failure.NETWORK) else error)
                }
            }
        })
    }
    fun close() = mediaClient.closeAsync()
}

internal object AnimeParser {
    const val BASE = "https://hianime.at"
    fun safeUrl(value: String): String {
        val url = value.toHttpUrl()
        require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() && url.port == 443)
        require(url.host.contains('.') && !url.host.endsWith(".local") &&
            !url.host.matches(Regex("[0-9.]+")) && !url.host.contains(':'))
        return url.toString()
    }
    fun firstParty(path: String): String {
        val url = BASE.toHttpUrl().resolve(path) ?: throw SiteException(Failure.CHANGED)
        require(url.host == BASE.toHttpUrl().host)
        return safeUrl(url.toString())
    }
    fun cards(html: String): List<Title> {
        val doc = Jsoup.parse(html, BASE)
        if (doc.title().contains("Just a moment", true) || html.contains("cf-chl-")) throw SiteException(Failure.ACCESS)
        doc.select("#main-sidebar").remove()
        val titles = doc.select(".flw-item").mapNotNull { card ->
            val link = card.selectFirst(".film-name a") ?: return@mapNotNull null
            val path = runCatching { firstParty(link.attr("href")).toHttpUrl().encodedPath }.getOrNull() ?: return@mapNotNull null
            val id = Regex("-([0-9]+)$").find(path)?.groupValues?.get(1) ?: return@mapNotNull null
            val name = link.attr("title").ifBlank { link.text() }
            if (name.isBlank()) return@mapNotNull null
            val poster = card.selectFirst(".film-poster-img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }.orEmpty()
            val type = if (card.select(".fdi-item").any { it.text().equals("Movie", true) }) ContentType.MOVIE else ContentType.SERIES
            Title(id, path, name, poster.takeIf { it.isNotBlank() }?.let {
                runCatching { safeUrl(BASE.toHttpUrl().resolve(it)!!.toString()) }.getOrDefault("")
            }.orEmpty(),
                source = ContentSource.HIANIME, type = type)
        }.distinctBy(Title::key)
        if (titles.isEmpty() && (doc.selectFirst(".film_list-wrap") == null || doc.select(".flw-item").isNotEmpty()))
            throw SiteException(Failure.CHANGED)
        return titles
    }
    private fun fragment(raw: String): String {
        val data = JSONObject(raw)
        if (!data.optBoolean("status", true)) throw SiteException(Failure.UNAVAILABLE)
        return data.getString("html")
    }
    fun episodes(raw: String): List<Episode> = Jsoup.parse(fragment(raw)).select(".ep-item").mapIndexedNotNull { index, row ->
        val id = row.attr("data-id").takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: return@mapIndexedNotNull null
        val display = row.attr("data-number").ifBlank { (index + 1).toString() }
        Episode(1, display.toIntOrNull() ?: index + 1, row.attr("title").ifBlank { row.selectFirst(".ep-name")?.text().orEmpty() },
            id, display, index)
    }.distinctBy(Episode::key)

    fun detail(html: String, original: Title, episodes: List<Episode>): Detail {
        val doc = Jsoup.parse(html, BASE)
        if (doc.selectFirst(".anisc-detail") == null) throw SiteException(Failure.CHANGED)
        return Detail(original.copy(
            synopsis = doc.selectFirst(".film-description .text, .item-title .text")?.text().orEmpty(),
            poster = doc.selectFirst(".anisc-poster .film-poster-img")?.absUrl("src")?.takeIf { it.startsWith("https://") } ?: original.poster,
            genres = doc.select(".item-list a[href*=/genre/]").joinToString(", ") { it.text() },
        ), episodes)
    }
    fun servers(raw: String): Map<AnimeVersion, String> {
        val result = mutableMapOf<AnimeVersion, String>()
        Jsoup.parse(fragment(raw)).select("[data-server-name=ZokoAnime][data-hash]").forEach { row ->
            val mode = AnimeVersion.entries.firstOrNull { it.wire == row.attr("data-type") } ?: return@forEach
            runCatching { safeUrl(String(Base64.getDecoder().decode(row.attr("data-hash")), Charsets.UTF_8)) }
                .getOrNull()?.let { result.putIfAbsent(mode, it) }
        }
        return result
    }
    fun stream(html: String, embed: String): Stream {
        val encoded = Regex("window\\.__P\\s*=\\s*[\"']([^\"']+)[\"']").find(html)?.groupValues?.get(1)
            ?: throw SiteException(Failure.CHANGED)
        val blob = Base64.getDecoder().decode(encoded)
        val key = "otaku-embed-v1".toByteArray()
        val data = JSONObject(String(ByteArray(blob.size) { (blob[it].toInt() xor key[it % key.size].toInt()).toByte() }, Charsets.UTF_8))
        val uri = safeUrl(data.getString("src"))
        val captions = data.optJSONArray("subtitles")?.let { array ->
            (0 until array.length()).mapNotNull { i -> runCatching {
                val caption = array.getJSONObject(i)
                val src = safeUrl(embed.toHttpUrl().resolve(caption.getString("src"))!!.toString())
                val mime = if (src.toHttpUrl().encodedPath.endsWith(".srt", true)) "application/x-subrip" else "text/vtt"
                Caption(src, caption.optString("label", caption.optString("lang")), mime, caption.optBoolean("default"))
            }.getOrNull() }
        }.orEmpty()
        val referer = embed.toHttpUrl().newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
        return Stream(uri, captions, mapOf("Referer" to referer, "User-Agent" to FlixtorClient.USER_AGENT),
            if (uri.toHttpUrl().encodedPath.endsWith(".mp4", true)) "video/mp4" else "application/x-mpegURL",
            AniSkipClient.mapping(embed))
    }
}
