package dev.personal.flixtortv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

class SessionStore(context: Context) : CookieJar {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val alias = "flixtortv.session"
    private val cookies = mutableListOf<Cookie>()
    init {
        try {
            val encoded = prefs.getString("cookies", null)
            if (encoded != null) {
                val parts = encoded.split(":")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
                val values = JSONArray(String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8))
                for (i in 0 until values.length()) {
                    val v = values.getJSONObject(i)
                    val builder = Cookie.Builder().name(v.getString("name")).value(v.getString("value"))
                        .path(v.getString("path")).expiresAt(v.getLong("expires"))
                    if (v.getBoolean("hostOnly")) builder.hostOnlyDomain(v.getString("domain")) else builder.domain(v.getString("domain"))
                    if (v.getBoolean("secure")) builder.secure()
                    if (v.getBoolean("httpOnly")) builder.httpOnly()
                    builder.build().takeIf { it.expiresAt > System.currentTimeMillis() }?.let(cookies::add)
                }
            }
        } catch (_: Exception) { prefs.edit().clear().apply(); cookies.clear() }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        this.cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        cookies.forEach { incoming ->
            this.cookies.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
            if (incoming.expiresAt > System.currentTimeMillis()) this.cookies.add(incoming)
        }
        while (this.cookies.size > 128) this.cookies.removeAt(0)
        persist()
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    @Synchronized fun clear() { cookies.clear(); prefs.edit().clear().apply() }
    private fun persist() {
        val values = JSONArray()
        cookies.forEach { c -> values.put(JSONObject().put("name", c.name).put("value", c.value)
            .put("domain", c.domain).put("path", c.path).put("hostOnly", c.hostOnly)
            .put("secure", c.secure).put("httpOnly", c.httpOnly)
            .put("expires", if (c.persistent) c.expiresAt else System.currentTimeMillis() + 30L * 86400000)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.doFinal(values.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("cookies", Base64.getEncoder().encodeToString(cipher.iv) + ":" + Base64.getEncoder().encodeToString(bytes)).apply()
    }
}
