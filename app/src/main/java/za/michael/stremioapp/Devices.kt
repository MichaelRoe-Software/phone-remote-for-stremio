package za.michael.stremioapp

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A Stremio install (or the Linux HTPC backend) this phone can control. */
data class Device(
    val id: String,
    val name: String,
    val url: String,
    val token: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url).put("token", token)

    companion object {
        fun fromJson(o: JSONObject) = Device(
            o.getString("id"), o.getString("name"), o.getString("url"), o.getString("token"),
        )
    }
}

/** Saved devices plus the last one used, which the app reconnects to on launch. */
class DeviceStore(context: Context) {
    private val sp = context.getSharedPreferences("devices", Context.MODE_PRIVATE)

    fun all(): List<Device> {
        val raw = sp.getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Device.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Device>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("list", arr.toString()).apply()
    }

    fun get(id: String?): Device? = all().firstOrNull { it.id == id }

    var lastId: String?
        get() = sp.getString("last", null)
        set(v) = sp.edit().putString("last", v).apply()

    /** The device to control now: the last one used, else the first saved one. */
    fun current(): Device? = get(lastId) ?: all().firstOrNull()

    /**
     * Adds a device, or refreshes the token of the one already saved at that
     * address (re-pairing after "New pairing code"), keeping its custom name.
     */
    fun addOrUpdate(name: String, url: String, token: String): Device {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.url == url }
        val device = if (i >= 0) {
            list[i].copy(token = token).also { list[i] = it }
        } else {
            Device(UUID.randomUUID().toString(), name, url, token).also { list.add(it) }
        }
        save(list)
        lastId = device.id
        return device
    }

    fun rename(id: String, name: String) =
        save(all().map { if (it.id == id) it.copy(name = name) else it })

    fun delete(id: String) {
        save(all().filter { it.id != id })
        if (lastId == id) lastId = all().firstOrNull()?.id
    }

    var volumeKeys: Boolean
        get() = sp.getBoolean("volume_keys", true)
        set(v) = sp.edit().putBoolean("volume_keys", v).apply()
}

/** What a pairing QR code (or pairing link) carries. */
data class Pairing(val name: String, val url: String, val token: String)

object PairingParser {
    /**
     * Accepts the Stremio QR payload `{"name":…,"url":…,"token":…}`, a pairing
     * link `stremioapp://pair?data=<json>` (or `?name=&url=&token=`), and the old
     * HTPC remote link `http://host:8080/?t=TOKEN`.
     */
    fun parse(text: String): Pairing? {
        val s = text.trim()
        if (s.startsWith("{")) {
            return try {
                val o = JSONObject(s)
                pairing(o.optString("name"), o.optString("url"), o.optString("token"))
            } catch (e: Exception) {
                null
            }
        }
        val uri = try { Uri.parse(s) } catch (e: Exception) { return null }
        if (uri.scheme == "stremioapp") {
            uri.getQueryParameter("data")?.let { return parse(it) }
            return pairing(uri.getQueryParameter("name") ?: "", uri.getQueryParameter("url") ?: "",
                uri.getQueryParameter("token") ?: "")
        }
        if (uri.scheme == "http" || uri.scheme == "https") {
            val token = uri.getQueryParameter("t") ?: return null
            return pairing(uri.host ?: "", "${uri.scheme}://${uri.encodedAuthority}", token)
        }
        return null
    }

    fun pairing(name: String, url: String, token: String): Pairing? {
        val u = normalizeUrl(url) ?: return null
        if (token.isBlank()) return null
        return Pairing(name.trim().ifEmpty { Uri.parse(u).host ?: "Stremio" }, u, token.trim())
    }

    fun normalizeUrl(url: String): String? {
        var u = url.trim().trimEnd('/')
        if (u.isEmpty()) return null
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return if (Uri.parse(u).host.isNullOrEmpty()) null else u
    }
}
