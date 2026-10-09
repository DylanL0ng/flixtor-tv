package dev.personal.flixtortv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ProfileStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    fun all(): List<Profile> = try {
        val array = JSONArray(prefs.getString("items", "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.map {
            Profile(it.getString("id"), it.getString("name"), it.optInt("avatar"), it.optInt("quality"))
        }
    } catch (_: Exception) { emptyList() }
    fun initialize(): List<Profile> {
        if (all().isEmpty()) write(listOf(Profile("default", context.getString(R.string.default_profile))))
        // Commit destination before marking migration complete. Repeating this copy is safe.
        if (!prefs.getBoolean("migrated", false)) {
            val old = context.getSharedPreferences("library", Context.MODE_PRIVATE)
            val destination = context.getSharedPreferences("library_default", Context.MODE_PRIVATE)
            check(destination.edit().putString("favorites", old.getString("favorites", "[]"))
                .putString("viewing", old.getString("viewing", "[]")).commit())
            check(prefs.edit().putBoolean("migrated", true).commit())
        }
        return all()
    }
    private fun write(profiles: List<Profile>) {
        check(prefs.edit().putString("items", JSONArray(profiles.map {
            JSONObject().put("id", it.id).put("name", it.name).put("avatar", it.avatar).put("quality", it.quality)
        }).toString()).commit())
    }
    fun save(id: String?, name: String, avatar: Int): List<Profile> {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 30 && avatar in 0..6)
        val profiles = all()
        require(id == null && profiles.size < 7 || profiles.any { it.id == id })
        write(if (id == null) profiles + Profile(UUID.randomUUID().toString(), clean, avatar)
            else profiles.map { if (it.id == id) it.copy(name = clean, avatar = avatar) else it })
        return all()
    }
    fun quality(id: String, height: Int): List<Profile> {
        require(height in listOf(0, 720, 1080, 2160))
        write(all().map { if (it.id == id) it.copy(quality = height) else it })
        return all()
    }
    fun delete(id: String): List<Profile> {
        val profiles = all()
        require(profiles.size > 1 && profiles.any { it.id == id })
        write(profiles.filterNot { it.id == id })
        context.getSharedPreferences("library_$id", Context.MODE_PRIVATE).edit().clear().commit()
        return all()
    }
}
