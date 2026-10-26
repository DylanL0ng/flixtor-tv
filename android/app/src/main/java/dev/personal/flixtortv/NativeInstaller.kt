package dev.personal.flixtortv

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Download and installation enforcement; release discovery and scheduling live in TypeScript. */
internal class NativeInstaller(private val activity: Activity, private val emit: (JSONObject) -> Unit) {
    private val apk = File(activity.cacheDir, "solidtv-update.apk")
    private var metadata: JSONObject? = null
    @Synchronized fun prepare(release: JSONObject): JSONObject {
        val url = release.getString("apkUrl")
        require(url.startsWith("https://github.com/DylanL0ng/flixtor-tv/releases/download/") && url.endsWith("/app-release.apk"))
        require(release.getString("sha256").matches(Regex("[a-fA-F0-9]{64}")))
        val temp = File(activity.cacheDir, "solidtv-update.part")
        try {
            OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { r ->
                check(r.isSuccessful); val body = r.body ?: error("Missing APK")
                val length = body.contentLength(); require(length in 1..150L * 1024 * 1024)
                check(activity.cacheDir.usableSpace > length + 10 * 1024 * 1024)
                body.byteStream().use { input -> temp.outputStream().use { output ->
                    val bytes = ByteArray(65536); var total = 0L
                    while (true) { val count = input.read(bytes); if (count < 0) break; total += count; check(total <= length); output.write(bytes, 0, count); emit(JSONObject().put("progress", total * 100 / length)) }
                    check(total == length)
                } }
            }
            validate(temp, release); check(temp.renameTo(apk)); metadata = JSONObject(release.toString())
            return JSONObject().put("ready", true)
        } finally { temp.delete() }
    }
    @Suppress("DEPRECATION")
    internal fun validate(file: File, release: JSONObject) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) } }
        check(digest.digest().joinToString("") { "%02x".format(it) }.equals(release.getString("sha256"), true))
        val pm = activity.packageManager
        val candidate = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Invalid APK")
        val installed = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(candidate.packageName == activity.packageName && candidate.longVersionCode == release.getLong("versionCode") && candidate.longVersionCode > installed.longVersionCode)
        check(candidate.applicationInfo?.minSdkVersion?.let { it <= android.os.Build.VERSION.SDK_INT } == true)
        val first = candidate.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val second = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        check(!first.isNullOrEmpty() && first == second)
    }
    @Synchronized fun install(): JSONObject {
        val release = metadata ?: error("Download an update first"); validate(apk, release)
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.runOnUiThread { activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${activity.packageName}"))) }
            return JSONObject().put("permission", false)
        }
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(activity.packageName)
            if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            activity.getSharedPreferences("update_install", 0).edit().putInt("session", id).commit()
            installer.openSession(id).use { session ->
                apk.inputStream().use { input -> session.openWrite("base.apk", 0, apk.length()).use { output -> input.copyTo(output); session.fsync(output) } }
                val callback = Intent(activity, UpdateReceiver::class.java).putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
                session.commit(PendingIntent.getBroadcast(activity, id, callback, PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)).intentSender)
            }
        } catch (e: Exception) { installer.abandonSession(id); throw e }
        return JSONObject().put("permission", true)
    }
}
