package dev.personal.flixtortv

import android.app.Activity
import androidx.webkit.WebViewAssetLoader
import android.webkit.WebResourceResponse
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.io.File

internal class NativeCapabilities(private val activity: Activity, private val player: NativePlayer,
    private val emit: (String, JSONObject) -> Unit) {
    private val pool = Executors.newFixedThreadPool(4)
    private val calls = ConcurrentHashMap<String, Call>()
    private var sessions = SessionStore(activity)
    private val public = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).dns(NetworkPolicy)
        .addNetworkInterceptor { chain -> NetworkPolicy.url(chain.request().url.toString()); chain.proceed(chain.request()) }
        .followRedirects(false).followSslRedirects(false).build()
    private val artworkCache = Cache(File(activity.cacheDir, "artwork"), 64L * 1024 * 1024)
    private val artworkHttp = public.newBuilder().cache(artworkCache).addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.header("Cache-Control") == null && response.header("Content-Type")?.startsWith("image/") == true)
            response.newBuilder().header("Cache-Control", "public, max-age=604800").build()
        else response
    }.build()
    private var authenticated = public.newBuilder().cookieJar(sessions).build()
    private val prefs = activity.getSharedPreferences("solidtv", 0)
    private val credentials = prefs
    private val installer = NativeInstaller(activity) { emit("installer", it) }
    private val artworkUrls = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 256
    })
    init { player.client = { (if (it == "flixtor") authenticated else public).newBuilder().followRedirects(true).build() } }
    val artwork = WebViewAssetLoader.PathHandler { path ->
        val url = artworkUrls[path]
        try {
            if (url == null) null else {
                var target = validateUrl(url)
                for (redirect in 0..5) {
                    val response = artworkHttp.newCall(Request.Builder().url(target).build()).execute()
                    if (response.code in 300..399) {
                        val location = response.header("Location")
                        response.close()
                        if (location == null || redirect == 5) return@PathHandler null
                        target = validateUrl(target.resolve(location)?.toString() ?: return@PathHandler null)
                        continue
                    }
                    val resource = response.use { r ->
                        val body = r.body ?: return@use null
                        val mime = r.header("Content-Type", "image/jpeg")!!.substringBefore(';')
                        if (!r.isSuccessful || !mime.startsWith("image/") || body.contentLength() > 4_000_000) return@use null
                        val bytes = body.source().let { if (it.request(4_000_001)) null else it.readByteArray() } ?: return@use null
                        WebResourceResponse(mime, null, bytes.inputStream())
                    }
                    return@PathHandler resource
                }
                null
            }
        } catch (_: Exception) { null }
    }
    fun call(id: String, method: String, args: JSONObject, reply: (JSONObject) -> Unit) {
        fun done(value: Any? = JSONObject.NULL, error: String? = null) {
            activity.runOnUiThread { reply(JSONObject().put("v", 1).put("id", id).apply {
                if (error != null) put("error", error) else put("value", value)
            }) }
        }
        if (method == "cancel") { calls.remove(args.optString("id"))?.cancel(); done(); return }
        if (method == "player.load" || method == "player.command") {
            try { if (method == "player.load") player.load(args) else player.command(args); done() }
            catch (_: Exception) { done(error = "MEDIA") }; return
        }
        pool.execute {
            try {
                val result: Any? = when (method) {
                    "ready" -> JSONObject().put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE).put("sdk", android.os.Build.VERSION.SDK_INT)
                        .put("commit", BuildConfig.COMMIT_SHA).put("reportUrl", BuildConfig.REPORT_URL)
                        .put("contribution", BuildConfig.INTRODB_API_KEY.isNotBlank()).put("platform", "android")
                    "http" -> http(id, args)
                    "storage.get" -> prefs.getString(validKey(args), null) ?: JSONObject.NULL
                    "storage.set" -> { val value = args.getString("value"); require(value.length <= 2_000_000); check(prefs.edit().putString(validKey(args), value).commit()); true }
                    "storage.remove" -> { check(prefs.edit().remove(validKey(args)).commit()); true }
                    "legacy" -> legacy()
                    "installer.prepare" -> installer.prepare(args)
                    "installer.install" -> installer.install()
                    "credentials.verify" -> {
                        val key = args.getString("profile"); val pin = args.getString("pin")
                        val digest = credentials.getString("pin:$key", "").orEmpty()
                        digest.isEmpty() || MessageDigest.isEqual(digest.toByteArray(), digest(key, pin).toByteArray())
                    }
                    "credentials.commit" -> {
                        val key = args.getString("profile"); val pin = args.getString("pin")
                        require(pin.isEmpty() || pin.matches(Regex("[0-9]{4}")))
                        val value = args.getString("value"); require(value.length <= 2_000_000)
                        check(credentials.edit().putString("pin:$key", if (pin.isEmpty()) "" else digest(key, pin)).putString("app", value).commit()); true
                    }
                    "session.clear" -> {
                        calls.values.filter { it.request().url.host == "flixtor.to" }.forEach(Call::cancel)
                        sessions.clear(); sessions = SessionStore(activity)
                        authenticated = public.newBuilder().cookieJar(sessions).build(); true
                    }
                    "art" -> {
                        val url = args.getString("url"); validateUrl(url)
                        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                        artworkUrls[key] = url; "https://appassets.androidplatform.net/art/$key"
                    }
                    "exit" -> { activity.runOnUiThread { activity.finishAndRemoveTask() }; true }
                    else -> throw IllegalArgumentException("Unknown capability")
                }
                done(result)
            } catch (_: Exception) { done(error = if (method == "http") "NETWORK" else "INVALID") }
        }
    }
    private fun validKey(args: JSONObject) = args.getString("key").also { require(it in listOf("app", "checkpoint", "querySnapshot")) }
    private fun digest(id: String, pin: String) = MessageDigest.getInstance("SHA-256").digest("$id:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
    private fun validateUrl(value: String): HttpUrl {
        return NetworkPolicy.url(value)
    }
    private fun http(id: String, args: JSONObject): JSONObject {
        if (BuildConfig.DEBUG && activity.intent.getBooleanExtra("solidQA", false)) {
            val fixtures = JSONObject(activity.assets.open("http.json").bufferedReader().use { it.readText() })
            val path = android.net.Uri.parse(args.getString("url")).path.orEmpty()
            val key = when {
                args.optString("client") == "anime" -> when {
                    path.contains("/episode/list/") -> "animeEpisodes"
                    path.contains("/episode/servers") -> "animeServers"
                    path.contains("/mal/") -> "animeEmbed"
                    path == "/most-popular" || path == "/search" -> "anime"
                    else -> "animeDetail"
                }
                fixtures.has(path) -> path
                path.startsWith("/ajax/v4/") -> "stream"
                path.startsWith("/watch/") -> "detail"
                path.startsWith("/show/") || path.startsWith("/search/") -> "catalog"
                path == "/most-popular" -> "anime"
                else -> ""
            }
            return JSONObject().put("status", 200).put("body", if (key.isEmpty()) "{}" else fixtures.getString(key)).put("location", "")
        }
        val url = validateUrl(args.getString("url"))
        val scope = args.getString("client")
        require(scope in listOf("flixtor", "anime", "introdb", "aniskip", "updates", "report", "public"))
        if (scope == "flixtor") require(url.host == "flixtor.to")
        if (scope == "introdb") require(url.host == "api.introdb.app")
        if (scope == "aniskip") require(url.host == "api.aniskip.com")
        if (scope == "report") require(BuildConfig.REPORT_URL.isNotBlank() && url.toString() == BuildConfig.REPORT_URL)
        val builder = Request.Builder().url(url)
        val headers = args.optJSONObject("headers") ?: JSONObject()
        headers.keys().forEach { name -> require(name.lowercase() !in listOf("cookie", "host", "authorization", "set-cookie")); builder.header(name, headers.getString(name)) }
        if (scope == "introdb" && url.encodedPath == "/submit") {
            require(BuildConfig.INTRODB_API_KEY.isNotBlank()); builder.header("X-API-Key", BuildConfig.INTRODB_API_KEY)
        }
        val method = args.optString("method", "GET"); require(method in listOf("GET", "POST"))
        if (method == "POST") builder.post(args.optString("body").toRequestBody(headers.optString("Content-Type", "application/json").toMediaType()))
        val call = (if (scope == "flixtor") authenticated else public).newCall(builder.build()); calls[id] = call
        try { return call.execute().use { r ->
            val body = r.body ?: throw IllegalStateException("Missing body")
            require(body.contentLength() <= 8_388_608)
            val source = body.source(); require(!source.request(8_388_609))
            JSONObject().put("status", r.code).put("body", source.readUtf8())
                .put("location", r.header("Location") ?: "")
        } } finally { calls.remove(id) }
    }
    private fun legacy(): JSONObject {
        val result = JSONObject()
        val profiles = activity.getSharedPreferences("profiles", 0)
        val items = JSONArray(profiles.getString("items", "[]"))
        for (i in 0 until items.length()) {
            val profile = items.getJSONObject(i); val id = profile.getString("id"); val hash = profile.optString("pin")
            if (hash.isNotEmpty() && !credentials.contains("pin:$id")) check(credentials.edit().putString("pin:$id", hash).commit())
            profile.remove("pin"); profile.put("locked", hash.isNotEmpty())
            val library = activity.getSharedPreferences("library_$id", 0)
            result.put("library:$id", JSONObject().put("favorites", JSONArray(library.getString("favorites", "[]")))
                .put("viewing", JSONArray(library.getString("viewing", "[]"))))
        }
        result.put("profiles", items)
        listOf("skip_segments", "screen_fit", "library", "skip", "skip_preferences").forEach { name ->
            result.put(name, JSONObject(activity.getSharedPreferences(name, 0).all))
        }
        result.put("checkpoint", prefs.getString("checkpoint", null)?.let(::JSONObject) ?: JSONObject.NULL)
        return result
    }
    fun close() { calls.values.forEach(Call::cancel); calls.clear(); pool.shutdownNow(); public.dispatcher.cancelAll(); authenticated.dispatcher.cancelAll(); runCatching { artworkCache.close() } }
}
