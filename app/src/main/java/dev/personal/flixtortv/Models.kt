package dev.personal.flixtortv

data class Title(
    val id: String, val path: String, val name: String,
    val poster: String = "", val year: String = "", val rating: String = "",
    val genres: String = "", val synopsis: String = "", val backdrop: String = "",
) {
    val isSeries get() = path.startsWith("/watch/tv/")
    val key get() = "${if (isSeries) "t" else "m"}:$id"
}
data class Shelf(val label: String, val titles: List<Title>)
data class Episode(val season: Int, val number: Int, val name: String) {
    val key get() = "$season:$number"
}
data class Detail(val title: Title, val episodes: List<Episode>)
data class Caption(val uri: String, val label: String, val mime: String, val default: Boolean)
data class Stream(val uri: String, val captions: List<Caption>)
data class PairCode(val code: String, val url: String)
enum class AccountStatus { CHECKING, GUEST, SIGNED_IN, VIP, EXPIRED, UNAVAILABLE }
data class Account(val status: AccountStatus) {
    constructor(signedIn: Boolean) : this(if (signedIn) AccountStatus.SIGNED_IN else AccountStatus.GUEST)
    val signedIn get() = status == AccountStatus.SIGNED_IN || status == AccountStatus.VIP
}
data class Viewing(val title: Title, val episode: Episode?, val position: Long, val duration: Long) {
    val key get() = title.key + (episode?.let { ":${it.key}" } ?: "")
}
enum class Failure { NETWORK, ACCESS, VIP, EXPIRED, BLOCKED, CHANGED, MEDIA, SIGNED_OUT, UNAVAILABLE, UNSUPPORTED }
class SiteException(val failure: Failure) : Exception(failure.name)

data class Profile(val id: String, val name: String, val avatar: Int = 0, val quality: Int = 0)
data class PlaybackIdentity(val profileId: String, val title: Title, val episode: Episode?)
