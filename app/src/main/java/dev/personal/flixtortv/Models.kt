package dev.personal.flixtortv

enum class ContentSource { FLIXTOR, HIANIME }
enum class ContentType { MOVIE, SERIES }
enum class AnimeVersion(val wire: String) { DUB("dub"), SUB("sub") }

data class Title(
    val id: String, val path: String, val name: String,
    val poster: String = "", val year: String = "", val rating: String = "",
    val genres: String = "", val synopsis: String = "", val backdrop: String = "",
    val imdb: String = "",
    val source: ContentSource = ContentSource.FLIXTOR, val type: ContentType? = null,
) {
    val isSeries get() = type?.let { it == ContentType.SERIES } ?: path.startsWith("/watch/tv/")
    val key get() = (if (source == ContentSource.FLIXTOR) "" else "hianime:") + "${if (isSeries) "t" else "m"}:$id"
}
data class Shelf(val label: String, val titles: List<Title>)
data class Episode(val season: Int, val number: Int, val name: String,
    val providerId: String = "", val displayNumber: String = number.toString(), val order: Int? = null) {
    val key get() = if (providerId.isBlank()) "$season:$number" else "anime:$providerId"
}
data class Detail(val title: Title, val episodes: List<Episode>) {
    /** Canonical watch order. The service lists newest first; playback must not. */
    val ordered: List<Episode> get() = episodes.sortedWith(compareBy<Episode> { it.season }.thenBy { it.order ?: it.number })
    fun next(episode: Episode?): Episode? {
        val list = ordered
        val index = if (episode == null) -1 else list.indexOfFirst { it.key == episode.key }
        return if (index >= 0) list.getOrNull(index + 1) else null
    }
}
data class Caption(val uri: String, val label: String, val mime: String, val default: Boolean)
data class Stream(val uri: String, val captions: List<Caption>,
    val headers: Map<String, String> = mapOf("Referer" to SiteParser.BASE + "/"),
    val mime: String = "application/x-mpegURL")
data class PairCode(val code: String, val url: String)
enum class AccountStatus { CHECKING, GUEST, SIGNED_IN, VIP, EXPIRED, UNAVAILABLE }
data class Account(val status: AccountStatus) {
    constructor(signedIn: Boolean) : this(if (signedIn) AccountStatus.SIGNED_IN else AccountStatus.GUEST)
    val signedIn get() = status == AccountStatus.SIGNED_IN || status == AccountStatus.VIP
}
data class Viewing(val title: Title, val episode: Episode?, val position: Long, val duration: Long,
    val version: AnimeVersion? = null) {
    val key get() = playbackKey(title, episode, version)
    /** Watched fraction, 0f when the duration is still unknown and 1f once finished. */
    val fraction get() = if (duration <= 0) 0f else if (position >= duration - 30000) 1f
        else (position.toFloat() / duration).coerceIn(0f, 1f)
    /** The last 30 seconds count as finished, matching resume behaviour. */
    val finished get() = duration > 0 && position >= duration - 30000
}
enum class Failure { NETWORK, ACCESS, VIP, EXPIRED, BLOCKED, CHANGED, MEDIA, SIGNED_OUT, UNAVAILABLE, UNSUPPORTED, CAPACITY }
class SiteException(val failure: Failure) : Exception(failure.name)

data class Profile(val id: String, val name: String, val avatar: Int = 0, val quality: Int = 0,
    val autoplay: Boolean = true, val pin: String = "") {
    /** A PIN protects selection; the stored value is a digest, never the digits. */
    val locked get() = pin.isNotEmpty()
}

/**
 * Skip segments, keyed by the wire value IntroDB uses to read them. IntroDB has no
 * `preview` type, so a next-episode preview cannot be represented here.
 */
enum class SegmentType(val read: String, val submit: String) {
    INTRO("intro", "intro"),
    RECAP("recap", "recap"),
    CREDITS("outro", "outro"),
    POST_CREDITS("post_credits", "post-credits")
}

data class SkipSegment(val type: SegmentType, val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs > startMs) { "A segment must end after it starts" } }
    val durationMs get() = endMs - startMs
    fun contains(positionMs: Long) = positionMs in startMs..endMs
}

/**
 * Every known segment for one episode or film. A media item with no data is empty,
 * which is distinct from an unreachable service.
 */
data class Segments(val imdbId: String, val items: List<SkipSegment> = emptyList()) {
    val none get() = items.isEmpty()
    /** The segment covering this position, or null. Later segments win a tie. */
    fun at(positionMs: Long): SkipSegment? = items.filter { it.contains(positionMs) }.maxByOrNull { it.startMs }
    /** Positions as 0f..1f of the media, for timeline markers. */
    fun markers(durationMs: Long): List<Pair<SegmentType, Pair<Float, Float>>> {
        if (durationMs <= 0) return emptyList()
        return items.map { it.type to ((it.startMs.toFloat() / durationMs).coerceIn(0f, 1f) to
            (it.endMs.toFloat() / durationMs).coerceIn(0f, 1f)) }
    }
}
data class PlaybackIdentity(val profileId: String, val title: Title, val episode: Episode?, val version: AnimeVersion? = null)
internal fun playbackKey(title: Title, episode: Episode?, version: AnimeVersion?) =
    title.key + (episode?.let { ":${it.key}" } ?: "") + (version?.let { ":${it.wire}" } ?: "")
