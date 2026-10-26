package dev.personal.flixtortv

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.json.JSONObject

class FlixtorClient internal constructor(private val sessions: SessionStore,
    httpClient: OkHttpClient? = null,
    private val responseFixture: (suspend (String) -> String)? = null) {
    companion object { const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36" }
    val mediaClient = (httpClient?.newBuilder() ?: OkHttpClient.Builder()).cookieJar(sessions).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()
    private val client = mediaClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val bootstrapLock = Mutex()
    @Volatile private var csrf = ""
    private suspend fun raw(path: String, data: JSONObject? = null, redirects: Int = 0, allowCapacity: Boolean = false): String = withContext(Dispatchers.IO) {
        if (redirects > 5) throw SiteException(Failure.CHANGED)
        responseFixture?.let { fixture ->
            SiteParser.safePath(path)
            val text = fixture(path)
            SiteParser.responseFailure(text)?.let { if (!(allowCapacity && it == Failure.CAPACITY)) throw SiteException(it) }
            return@withContext text
        }
        val url = (SiteParser.BASE + SiteParser.safePath(path)).toHttpUrl()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .header("Referer", SiteParser.BASE + "/home").header("X-Requested-With", "XMLHttpRequest")
        if (data != null) {
            request.header("Origin", SiteParser.BASE).header("X-CSRF-Token", csrf)
                .post(data.toString().toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
        }
        val response = await(client.newCall(request.build()))
        response.use { r ->
            if (r.code in listOf(301, 302, 303, 307, 308)) {
                val next = r.header("Location")?.let { url.resolve(it) } ?: throw SiteException(Failure.CHANGED)
                if (next.scheme != "https" || next.host != url.host) throw SiteException(Failure.ACCESS)
                return@withContext raw(next.encodedPath + (next.encodedQuery?.let { "?$it" } ?: ""),
                    if (r.code == 307 || r.code == 308) data else null, redirects + 1, allowCapacity)
            }
            val text = readBoundedBody(r)
            SiteParser.responseFailure(text, r.code)?.let { if (!(allowCapacity && it == Failure.CAPACITY)) throw SiteException(it) }
            return@withContext text
        }
    }
    private fun readBoundedBody(response: Response): String {
        val body = response.body ?: return ""
        val limit = 8 * 1024 * 1024
        if (body.contentLength() > limit) throw SiteException(Failure.CHANGED)
        return body.charStream().use { reader ->
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
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(SiteException(Failure.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { response.close() }
            }
        })
    }
    suspend fun bootstrap() = bootstrapLock.withLock {
        if (csrf.isBlank()) {
            val html = raw("/home", allowCapacity = true)
            csrf = Jsoup.parse(html).selectFirst("meta[name=csrf-token]")?.attr("content").orEmpty()
            if (csrf.isBlank()) throw SiteException(Failure.ACCESS)
        }
    }
    suspend fun home(): List<Shelf> {
        bootstrap(); val html = raw("/home", allowCapacity = true)
        return withContext(Dispatchers.Default) { SiteParser.home(html) }
    }
    suspend fun browse(series: Boolean, page: Int, sort: String): List<Title> {
        bootstrap()
        require(page in 1..10000 && sort in listOf("latest", "best", "name"))
        val html = raw("/show/${if (series) "tvshows" else "movies"}?ajax=1&page=$page&sort=$sort")
        return withContext(Dispatchers.Default) { SiteParser.cards(html) }
    }
    suspend fun search(query: String, page: Int): List<Title> {
        bootstrap()
        require(query.length in 1..64 && page > 0)
        val encoded = (SiteParser.BASE + "/").toHttpUrl().newBuilder().addPathSegment(query).build().encodedPath
        val html = raw("/search$encoded?ajax=1&page=$page")
        return withContext(Dispatchers.Default) { SiteParser.cards(html) }
    }
    suspend fun detail(title: Title): Detail {
        bootstrap(); val html = raw(title.path)
        return withContext(Dispatchers.Default) { SiteParser.detail(html, title) }
    }
    suspend fun account(): Account {
        bootstrap(); val html = raw("/home", allowCapacity = true)
        val session = withContext(Dispatchers.Default) {
            Jsoup.parse(html).selectFirst("meta[name=csrf-token]")?.attr("content")?.takeIf { it.isNotBlank() }?.let { csrf = it }
            SiteParser.account(html)
        }
        if (session.status != AccountStatus.SIGNED_IN) return session
        val membership = raw("/account", allowCapacity = true)
        return withContext(Dispatchers.Default) { SiteParser.account(membership) }
    }
    suspend fun login(email: String, password: String): Account {
        bootstrap()
        raw("/ajax/viplogin", JSONObject().put("uname", email).put("passw", password))
        return account().also { if (!it.signedIn) throw SiteException(Failure.VIP) }
    }
    suspend fun pair(): PairCode {
        bootstrap()
        val data = JSONObject(raw("/ajax/pair/new", JSONObject()))
        val url = data.getString("url")
        val parsed = url.toHttpUrl()
        if (parsed.scheme != "https" || parsed.host != "flixtor.to" || !parsed.encodedPath.startsWith("/pair/")) throw SiteException(Failure.CHANGED)
        return PairCode(data.getString("code"), url)
    }
    suspend fun poll(code: String): String {
        val data = JSONObject(raw("/ajax/pair/poll", JSONObject().put("code", code)))
        if (data.optBoolean("expired")) throw SiteException(Failure.EXPIRED)
        return data.getString("state")
    }
    suspend fun playback(title: Title, episode: Episode?): Stream {
        bootstrap()
        require(title.id.all(Char::isDigit))
        if (title.isSeries && episode == null) throw SiteException(Failure.CHANGED)
        val path = if (episode != null) "/ajax/v4/e/${title.id}/${episode.season}/${episode.number}" else "/ajax/v4/m/${title.id}"
        return SiteParser.stream(raw(path))
    }
    fun clearSession() { csrf = ""; sessions.clear() }
    fun close() { mediaClient.closeAsync() }
}

// TLS shutdown can write to a socket. Activity/ViewModel cleanup runs on the main thread.
internal fun OkHttpClient.closeAsync() {
    val client = this
    Thread({ client.dispatcher.cancelAll(); client.connectionPool.evictAll() }, "flixtor-http-close")
        .apply { isDaemon = true }.start()
}
