package dev.personal.flixtortv

import java.net.URI
import java.util.Base64
import org.jsoup.Jsoup
import org.json.JSONObject

object SiteParser {
    const val BASE = "https://flixtor.to"
    fun responseFailure(raw: String, status: Int = 200): Failure? {
        val text = raw.lowercase()
        val message = runCatching {
            val obj = JSONObject(raw)
            if (obj.has("file")) return null
            listOf("error", "message", "reason", "status").map { obj.optString(it) }.joinToString(" ").lowercase()
        }.getOrElse {
            val doc = Jsoup.parse(raw)
            // Catalogue and watch pages contain VIP marketing and sign-in navigation.
            // Only classify an error document, never those normal page elements.
            if (status in 200..299 && doc.select(".cflip, meta[property=og:type]").isNotEmpty()) return null
            doc.text().lowercase().take(4096)
        }
        return when {
            status == 429 || status >= 500 -> Failure.UNAVAILABLE
            "account is blocked" in message || "account blocked" in message -> Failure.BLOCKED
            "session expired" in message || "login expired" in message -> Failure.EXPIRED
            status == 401 || "please sign in" in message || "please log in" in message ||
                "login required" in message || "not logged in" in message || "<title>sign in" in text -> Failure.SIGNED_OUT
            status == 443 || "vip required" in message || "vip only" in message ||
                "vip members" in message || "active vip" in message -> Failure.VIP
            status == 403 || "access denied" in message || "verify your browser" in message -> Failure.ACCESS
            status !in 200..299 -> Failure.NETWORK
            else -> null
        }
    }
    private val watchPath = Regex("^/watch/(movie|tv)/(\\d+)/[^?#]+")
    fun safePath(path: String): String {
        val uri = URI(path)
        require(!uri.isAbsolute && uri.rawAuthority == null && path.startsWith("/") && !path.startsWith("//"))
        return path
    }
    fun secureUrl(value: String): String {
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
        return value
    }
    fun cards(html: String): List<Title> {
        responseFailure(html)?.let { throw SiteException(it) }
        val doc = Jsoup.parse(html, BASE)
        val result = doc.select(".cflip[data-href]").mapNotNull { card ->
            val path = card.attr("data-href")
            val match = watchPath.find(path) ?: return@mapNotNull null
            val image = card.selectFirst(".front img") ?: card.selectFirst("img")
            val name = card.selectFirst(".front .card-footer")?.text()
                ?: image?.attr("alt") ?: ""
            if (name.isBlank()) return@mapNotNull null
            Title(match.groupValues[2], path, name,
                image?.absUrl("src").orEmpty(),
                card.selectFirst(".front .card-badge.top .badge")?.text().orEmpty(),
                card.selectFirst(".front .card-badge.bottom .badge")?.text().orEmpty(),
                card.selectFirst(".genres")?.text().orEmpty())
        }.distinctBy { it.key }
        if (result.isEmpty() && !doc.select(".contentList").text().contains("No results", ignoreCase = true)) {
            throw SiteException(Failure.CHANGED)
        }
        return result
    }
    fun home(html: String): List<Shelf> {
        responseFailure(html)?.let { throw SiteException(it) }
        val doc = Jsoup.parse(html, BASE)
        return listOf("contentListR" to "popular", "contentListM" to "movies",
            "contentListT" to "shows", "contentListE" to "episodes").mapNotNull { (selector, name) ->
            val section = doc.selectFirst(".$selector") ?: return@mapNotNull null
            cards(section.outerHtml()).takeIf { it.isNotEmpty() }?.let { Shelf(name, it) }
        }.also { if (it.isEmpty()) throw SiteException(Failure.CHANGED) }
    }
    fun detail(html: String, original: Title): Detail {
        responseFailure(html)?.let { throw SiteException(it) }
        val doc = Jsoup.parse(html, BASE)
        if (doc.selectFirst("meta[property=og:type]") == null) throw SiteException(Failure.CHANGED)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val description = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        val backdrop = Regex("background-image\\s*:\\s*url\\(['\"]?(https://[^)'\"]+)" )
            .find(html)?.groupValues?.get(1).orEmpty()
        val episodes = doc.select(".eplist[data-pes][data-pep]").mapNotNull { row ->
            val season = row.attr("data-pes").toIntOrNull() ?: return@mapNotNull null
            val number = row.attr("data-pep").toIntOrNull() ?: return@mapNotNull null
            Episode(season, number, row.selectFirst(".epTitle")?.text().orEmpty())
        }.distinctBy { it.key }
        return Detail(original.copy(poster = poster.ifBlank { original.poster },
            synopsis = description, backdrop = backdrop), episodes)
    }
    fun account(html: String): Account = Account(Jsoup.parse(html).select(".logout").isNotEmpty())
    // This mirrors the public player's transport encoding; the server still enforces access.
    fun decodePlayback(raw: String): String {
        if (raw.trimStart().startsWith("{")) return raw
        val rotated = raw.trim().map { c ->
            when(c) {
                in 'a'..'z' -> ('a'.code + (c.code - 'a'.code + 13) % 26).toChar()
                in 'A'..'Z' -> ('A'.code + (c.code - 'A'.code + 13) % 26).toChar()
                else -> c
            }
        }.joinToString("")
        val decoded = String(Base64.getDecoder().decode(rotated), Charsets.UTF_8)
        return decoded.map { c -> if (c.code in 33..126) (33 + (c.code + 14) % 94).toChar() else c }.joinToString("")
    }
    fun stream(raw: String): Stream {
        responseFailure(raw)?.let { throw SiteException(it) }
        try {
            val decoded = decodePlayback(raw)
            responseFailure(decoded)?.let { throw SiteException(it) }
            val obj = JSONObject(decoded)
            val uri = secureUrl(obj.getString("file"))
            val tracks = obj.optJSONArray("tracks")
            val captions = (0 until minOf(tracks?.length() ?: 0, 64)).mapNotNull { index ->
                val track = tracks!!.optJSONObject(index) ?: return@mapNotNull null
                if (track.optString("kind", "captions") !in listOf("captions", "subtitles")) return@mapNotNull null
                val file = track.optString("file")
                if (file.isBlank()) return@mapNotNull null
                val url = runCatching { secureUrl(URI(BASE).resolve(file).toString()) }.getOrNull() ?: return@mapNotNull null
                Caption(url, track.optString("label"),
                    if (URI(url).path.endsWith(".srt")) "application/x-subrip" else "text/vtt",
                    track.optBoolean("default"))
            }
            return Stream(uri, captions)
        } catch (known: SiteException) { throw known }
        catch (_: Exception) { throw SiteException(Failure.CHANGED) }
    }
}
