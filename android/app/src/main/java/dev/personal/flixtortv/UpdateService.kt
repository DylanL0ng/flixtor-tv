package dev.personal.flixtortv

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

enum class UpdatePhase { IDLE, CHECKING, CURRENT, DOWNLOADING, READY, FAILED, INSTALLING, INCOMPATIBLE, SIGNER_MISMATCH }
internal class UpdateSignerException : IllegalStateException("Release signing certificate differs from installed app")
enum class ReportPhase { IDLE, SENDING, SENT, FAILED, UNCONFIGURED }
data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val version: String = "", val progress: Int = 0,
    val incidentKnown: Boolean = false, val incidentChecked: Boolean = false, val report: ReportPhase = ReportPhase.IDLE,
    val installationFailed: Boolean = false, val incidentFingerprint: String? = null)
data class ReleaseInfo(val versionCode: Int, val versionName: String, val minSdk: Int, val sha256: String, val url: String) {
    companion object {
        fun parse(json: JSONObject): ReleaseInfo {
            val release = ReleaseInfo(json.getInt("versionCode"), json.getString("versionName"), json.getInt("minSdk"),
                json.getString("sha256"), json.getString("apkUrl"))
            require(release.versionCode > 0 && release.versionName.length in 1..64 && release.minSdk >= 29)
            require(release.sha256.matches(Regex("[a-f0-9]{64}")))
            require(release.url.startsWith("https://github.com/DylanL0ng/flixtor-tv/releases/download/"))
            return release
        }
    }
}

class UpdateService(private val context: Context) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private val checkLock = Mutex()
    private val installLock = Mutex()
    private val apk = File(context.cacheDir, "updates/update.apk")
    private val root = "https://github.com/DylanL0ng/flixtor-tv/releases/latest/download/"
    private var readyRelease: ReleaseInfo? = null
    private var reportGeneration = 0
    private val installPrefs = context.getSharedPreferences("update_install", Context.MODE_PRIVATE)
    private val installListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "time") {
            val status = installPrefs.getInt("status", PackageInstaller.STATUS_FAILURE)
            change { it.copy(phase = if (status == PackageInstaller.STATUS_SUCCESS) UpdatePhase.CURRENT else UpdatePhase.READY,
                installationFailed = status != PackageInstaller.STATUS_SUCCESS) }
        }
    }
    init { installPrefs.registerOnSharedPreferenceChangeListener(installListener) }
    fun close() {
        installPrefs.unregisterOnSharedPreferenceChangeListener(installListener)
        http.closeAsync()
    }
    private fun change(block: (UpdateState) -> UpdateState) { mutable.update(block) }
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun request(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { response.close() } }
        })
    }
    private suspend fun json(url: String): JSONObject = withContext(Dispatchers.IO) {
        request(Request.Builder().url(url).build()).use { response ->
            check(response.isSuccessful)
            val source = response.body?.source() ?: error("Empty body")
            check(!source.request(65537))
            JSONObject(source.readUtf8())
        }
    }
    suspend fun check(fingerprint: String? = null, retryInstallation: Boolean = false) = checkLock.withLock {
        try {
            if (mutable.value.phase == UpdatePhase.INSTALLING &&
                (!retryInstallation || !recoverInstallation())) return@withLock
            change { it.copy(phase = UpdatePhase.CHECKING, incidentKnown = false, incidentChecked = false) }
            val release = ReleaseInfo.parse(json(root + "release.json"))
            if (release.versionCode > BuildConfig.VERSION_CODE && release.minSdk <= Build.VERSION.SDK_INT) {
                change { it.copy(phase = UpdatePhase.DOWNLOADING, version = release.versionName, progress = 0) }
                download(release)
                readyRelease = release
                change { it.copy(phase = UpdatePhase.READY, progress = 100) }
            } else if (release.versionCode > BuildConfig.VERSION_CODE) {
                change { it.copy(phase = UpdatePhase.INCOMPATIBLE, version = release.versionName) }
            } else {
                change { it.copy(phase = UpdatePhase.CURRENT, version = release.versionName) }
            }
            if (fingerprint != null) {
                try {
                    val incidents = json("https://raw.githubusercontent.com/DylanL0ng/flixtor-tv/main/incidents.json").getJSONArray("incidents")
                    val known = (0 until incidents.length()).any { index ->
                        val incident = incidents.getJSONObject(index)
                        incident.getString("fingerprint") == fingerprint && incident.optString("status") == "pending" &&
                            BuildConfig.VERSION_CODE in incident.getInt("minVersionCode")..incident.getInt("maxVersionCode")
                    }
                    change { it.copy(incidentKnown = known, incidentChecked = true, incidentFingerprint = fingerprint) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { change { it.copy(incidentChecked = false) } }
            }
        } catch (cancel: CancellationException) { change { it.copy(phase = UpdatePhase.IDLE) }; throw cancel }
        catch (_: UpdateSignerException) { change { it.copy(phase = UpdatePhase.SIGNER_MISMATCH) } }
        catch (_: Exception) { change { it.copy(phase = UpdatePhase.FAILED) } }
    }
    /** A dismissed/missing Android confirmation must not disable updates indefinitely. */
    internal suspend fun recoverInstallation(): Boolean = installLock.withLock {
        if (mutable.value.phase != UpdatePhase.INSTALLING) return@withLock true
        val installer = context.packageManager.packageInstaller
        val id = installPrefs.getInt("session", -1)
        val session = if (id >= 0) installer.getSessionInfo(id) else null
        // Never interrupt APK staging or an installation making forward progress.
        if (session?.isActive == true) return@withLock false
        installPrefs.edit().remove("session").apply()
        if (session != null) installer.abandonSession(id)
        change { it.copy(phase = UpdatePhase.READY, installationFailed = true) }
        true
    }
    private suspend fun download(release: ReleaseInfo) = withContext(Dispatchers.IO) {
        if (apk.isFile && runCatching { validate(apk, release) }.isSuccess) return@withContext
        apk.parentFile?.mkdirs()
        val temporary = File(apk.parentFile, "download.part")
        try {
            request(Request.Builder().url(release.url).build()).use { response ->
                check(response.isSuccessful)
                val body = response.body ?: error("Empty APK")
                val size = body.contentLength()
                require(size in 1..150L * 1024 * 1024)
                val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
                val volume = storage.getUuidForPath(context.cacheDir)
                val required = size + 10L * 1024 * 1024
                check(storage.getAllocatableBytes(volume) >= required)
                storage.allocateBytes(volume, required)
                body.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= size)
                        output.write(buffer, 0, count)
                        change { it.copy(progress = (total * 100 / size).toInt()) }
                    }
                    check(total == size)
                } }
            }
            validate(temporary, release)
            check(temporary.renameTo(apk))
        } finally { temporary.delete() }
    }
    @Suppress("DEPRECATION")
    internal fun validate(file: File, release: ReleaseInfo) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        check(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256)
        val manager = context.packageManager
        val downloaded = manager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Invalid APK")
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(downloaded.packageName == context.packageName && downloaded.longVersionCode == release.versionCode.toLong() &&
            downloaded.longVersionCode > installed.longVersionCode)
        check(downloaded.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } == true)
        val first = downloaded.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val second = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        if (first.isNullOrEmpty() || first != second) throw UpdateSignerException()
    }
    suspend fun install() = installLock.withLock { withContext(Dispatchers.IO) {
        if (mutable.value.phase != UpdatePhase.READY) return@withContext
        change { it.copy(phase = UpdatePhase.INSTALLING, installationFailed = false) }
        var sessionId: Int? = null
        try {
            check(apk.isFile)
            validate(apk, readyRelease ?: error("Missing release metadata"))
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            val id = installer.createSession(params)
            sessionId = id
            installPrefs.edit().putInt("session", id).apply()
            installer.openSession(id).use { session ->
                apk.inputStream().use { input -> session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output); session.fsync(output)
                } }
                val intent = Intent(context, UpdateReceiver::class.java).putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        } catch (cancel: CancellationException) {
            installPrefs.edit().remove("session").apply()
            sessionId?.let { context.packageManager.packageInstaller.abandonSession(it) }
            change { it.copy(phase = UpdatePhase.READY) }; throw cancel
        } catch (_: Exception) {
            installPrefs.edit().remove("session").apply()
            sessionId?.let { context.packageManager.packageInstaller.abandonSession(it) }
            change { it.copy(phase = if (apk.isFile) UpdatePhase.READY else UpdatePhase.FAILED, installationFailed = true) }
        }
    } }
    fun resetReport() { reportGeneration++; change { it.copy(report = ReportPhase.IDLE) } }
    suspend fun report(operation: String, fingerprint: String) {
        if (mutable.value.report in listOf(ReportPhase.SENDING, ReportPhase.SENT)) return
        if (BuildConfig.REPORT_URL.isBlank()) { change { it.copy(report = ReportPhase.UNCONFIGURED) }; return }
        change { it.copy(report = ReportPhase.SENDING) }
        val generation = reportGeneration
        try {
            require(BuildConfig.REPORT_URL.startsWith("https://"))
            val payload = JSONObject().put("versionCode", BuildConfig.VERSION_CODE).put("commit", BuildConfig.COMMIT_SHA)
                .put("operation", operation).put("fingerprint", fingerprint).put("category", "CHANGED")
            request(Request.Builder().url(BuildConfig.REPORT_URL)
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()).use { check(it.isSuccessful) }
            if (generation == reportGeneration) change { it.copy(report = ReportPhase.SENT) }
        } catch (cancel: CancellationException) {
            if (generation == reportGeneration) change { it.copy(report = ReportPhase.IDLE) }; throw cancel
        } catch (_: Exception) { if (generation == reportGeneration) change { it.copy(report = ReportPhase.FAILED) } }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("update_install", Context.MODE_PRIVATE)
        // A callback from an abandoned attempt cannot overwrite a newer check/install.
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (sessionId != prefs.getInt("session", -1) || sessionId < 0) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            try { context.startActivity(confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: error("Missing confirmation")) }
            catch (_: Exception) { prefs.edit()
                .remove("session").putInt("status", PackageInstaller.STATUS_FAILURE).putLong("time", System.currentTimeMillis()).apply() }
        } else prefs.edit()
            .remove("session").putInt("status", status).putLong("time", System.currentTimeMillis()).apply()
    }
}
