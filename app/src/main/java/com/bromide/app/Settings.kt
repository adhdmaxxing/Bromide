package com.bromide.app

import android.content.Context
import androidx.annotation.StringRes
import com.bromide.engine.FilmEngine
import com.bromide.engine.Stock

/** Per-film adjustments, like a film camera's picture settings. */
data class Look(
    val grain: Float = 1f,
    val halation: Float = 1f,
    val ev: Float = 0f,
    val lab: Float = 0.5f,
    /** 1 = the stock's own grain size; lower is finer, higher coarser. */
    val grainSize: Float = 1f,
    /** Print saturation, 1 = the stock's own. */
    val color: Float = 1f,
    /** White balance shift, -1..1: warmer (+) or cooler (-). */
    val warmth: Float = 0f,
    /** White balance shift, -1..1: magenta (+) or green (-). */
    val tint: Float = 0f,
) {
    val isDefault get() = this == Look()

    fun encode() = "$grain,$halation,$ev,$lab,$grainSize,$color,$warmth,$tint"

    fun toParams(p: FilmEngine.Params = FilmEngine.Params()) = p.also {
        it.grain = grain
        it.grainSize = grainSize
        it.halation = halation
        it.ev = ev
        it.lab = lab
        it.color = color
        it.warmth = warmth
        it.tint = tint
    }

    companion object {
        /** Reads looks saved by any version: older ones have only the first four values. */
        fun decode(s: String?): Look {
            val p = s?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: return Look()
            return when (p.size) {
                4 -> Look(p[0], p[1], p[2], p[3])
                8 -> Look(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7])
                else -> Look()
            }
        }
    }
}

/** Where film previews are rendered from. */
enum class PreviewSource(@StringRes val label: Int, val asset: String?) {
    Portrait(R.string.preview_portrait, "samples/portrait.jpg"),
    Dusk(R.string.preview_dusk, "samples/dusk.jpg"),
    Still(R.string.preview_still, "samples/still.jpg"),
    LastPhoto(R.string.preview_last_photo, null),
}

/** Which camera files to develop. */
enum class SourceMode(@StringRes val title: Int, @StringRes val subtitle: Int) {
    Auto(R.string.source_auto, R.string.source_auto_desc),
    JpegOnly(R.string.source_jpeg, R.string.source_jpeg_desc),
}

/** User settings, stored in SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("bromide", Context.MODE_PRIVATE)

    var stockId: String
        get() = prefs.getString("stock", Stock.defaultStock().id) ?: Stock.defaultStock().id
        set(v) = prefs.edit().putString("stock", v).apply()

    val stock: Stock get() = Stock.byId(stockId) ?: Stock.defaultStock()

    fun look(stockId: String): Look = Look.decode(prefs.getString("look_$stockId", null))

    fun setLook(stockId: String, look: Look) {
        if (look.isDefault) prefs.edit().remove("look_$stockId").apply()
        else prefs.edit().putString("look_$stockId", look.encode()).apply()
    }

    var favorites: Set<String>
        get() = prefs.getStringSet("favorites", setOf("portra400", "gold200", "cinestill800t", "trix400"))?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("favorites", v).apply()

    var previewSource: PreviewSource
        get() = runCatching { PreviewSource.valueOf(prefs.getString("preview", "Portrait")!!) }.getOrDefault(PreviewSource.Portrait)
        set(v) = prefs.edit().putString("preview", v.name).apply()

    var port: Int
        get() = prefs.getInt("port", 2121)
        set(v) = prefs.edit().putInt("port", v).apply()

    var user: String
        get() = prefs.getString("user", "film") ?: "film"
        set(v) = prefs.edit().putString("user", v).apply()

    /** Off by default: on your own hotspot, any password the camera sends is accepted. */
    var requirePassword: Boolean
        get() = prefs.getBoolean("require_password", false)
        set(v) = prefs.edit().putBoolean("require_password", v).apply()

    var password: String
        get() = prefs.getString("password", "film") ?: "film"
        set(v) = prefs.edit().putString("password", v).apply()

    /**
     * Called when an FTP camera is set up: the first one gets a random password, so nobody
     * else on the network can send photos. Setups from before this, and passwords picked in
     * Settings, are left as they are.
     */
    fun ensureFtpPassword() {
        if (prefs.contains("password") || prefs.contains("require_password")) return
        // No look-alike characters (0/o, 1/l/i): it's typed once on a camera's keyboard.
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = java.security.SecureRandom()
        password = (1..6).map { chars[random.nextInt(chars.length)] }.joinToString("")
        requirePassword = true
    }

    var sourceMode: SourceMode
        get() = runCatching { SourceMode.valueOf(prefs.getString("source_mode", "Auto")!!) }.getOrDefault(SourceMode.Auto)
        set(v) = prefs.edit().putString("source_mode", v.name).apply()

    /** Long edge of saved photos in px; 0 = same as the camera's JPEG. */
    var outputSize: Int
        get() = prefs.getInt("size", 2048)
        set(v) = prefs.edit().putInt("size", v).apply()

    /** Film for photos taken with this phone: its own pick, apart from the cameras'. Starts as the loaded film. */
    var phoneStockId: String
        get() = prefs.getString("phone_stock", null) ?: stockId
        set(v) = prefs.edit().putString("phone_stock", v).apply()

    var phoneSize: PhoneSize
        get() = runCatching { PhoneSize.valueOf(prefs.getString("phone_size_mode", "Full")!!) }.getOrDefault(PhoneSize.Full)
        set(v) = prefs.edit().putString("phone_size_mode", v.name).apply()

    /** Start receiving automatically when the app opens and a hotspot is on. */
    var autoStart: Boolean
        get() = prefs.getBoolean("auto_start", true)
        set(v) = prefs.edit().putBoolean("auto_start", v).apply()

    /** The phone address the camera last connected to (what's typed in the camera). */
    var cameraHost: String?
        get() = prefs.getString("camera_host", null)
        set(v) = prefs.edit().putString("camera_host", v).apply()

    var setupDone: Boolean
        get() = prefs.getBoolean("setup_done", false)
        set(v) = prefs.edit().putBoolean("setup_done", v).apply()

    /** Your cameras. Setups from before v0.4 (FTP only) come back as one FTP camera. */
    var cameras: List<Camera>
        get() {
            val stored = prefs.getString("cameras", null)
            if (stored == null && setupDone) {
                return listOf(Camera(name = "My camera", brand = Brand.Other, link = Link.Ftp)).also { cameras = it }
            }
            return Camera.listFromJson(stored)
        }
        set(v) = prefs.edit().putString("cameras", Camera.listToJson(v)).apply()

    fun camera(id: String?): Camera? = cameras.firstOrNull { it.id == id }

    fun saveCamera(camera: Camera) {
        val list = cameras
        cameras = if (list.any { it.id == camera.id }) list.map { if (it.id == camera.id) camera else it } else list + camera
    }

    /** Newest gallery photo already looked at by the app watcher (MediaStore DATE_ADDED, seconds). */
    var watchSince: Long
        get() = prefs.getLong("watch_since", 0)
        set(v) = prefs.edit().putLong("watch_since", v).apply()

    /** Gallery ids the app watcher already handled, around [watchSince]. */
    var watchSeen: List<Long>
        get() = prefs.getString("watch_seen", "")!!.split(',').mapNotNull { it.toLongOrNull() }
        set(v) = prefs.edit().putString("watch_seen", v.takeLast(300).joinToString(",")).apply()
}
