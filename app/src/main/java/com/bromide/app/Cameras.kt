package com.bromide.app

import android.content.res.Resources
import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** How a camera gets its photos to Bromide. */
enum class Link {
    /** The camera uploads straight to Bromide's FTP server, over the phone's hotspot. */
    Ftp,
    /** The brand's own app receives the photos; Bromide develops each new one it saves. */
    App,
    /** No live link: develop from the memory card afterwards. */
    Card,
}

/**
 * Camera makers, with what the setup guide needs to know about each. Menu names
 * differ between models and firmware, so the guide words them as hints. Brand and
 * app names are proper nouns; the rest is a string resource.
 */
enum class Brand(
    /** The brand's name; null for "another brand". */
    private val brandName: String?,
    /** Lowercase pieces of the EXIF Make tag this brand writes. */
    val makes: List<String>,
    /** The maker's phone app that receives photos from the camera; null for another brand. */
    private val appName: String?,
    /** Which models use another app, if the maker has two ("Imaging Edge Mobile on older models"). */
    @StringRes val otherApp: Int? = null,
    /** Models with built-in FTP upload, as examples; null if the brand rarely has it. */
    val ftpExamples: String? = null,
    /** Where to join a Wi-Fi network for FTP, if known. */
    @StringRes val joinPath: Int? = null,
    /** Where the FTP server settings live, if known. */
    @StringRes val ftpPath: Int? = null,
    /** How to turn on automatic sending to the phone. */
    @StringRes val autoSend: Int,
    /** The last FTP setting: switching uploads on. */
    @StringRes val ftpFinish: Int = R.string.brand_ftp_finish,
) {
    Sony(
        "Sony", listOf("sony"), "Creators' App", otherApp = R.string.brand_sony_other_app,
        ftpExamples = "a7 IV, a7S III, a7R V, a1, FX3",
        joinPath = R.string.brand_sony_join_path,
        ftpPath = R.string.brand_sony_ftp_path,
        autoSend = R.string.brand_sony_auto_send,
        ftpFinish = R.string.brand_sony_ftp_finish,
    ),
    Canon(
        "Canon", listOf("canon"), "Canon Camera Connect",
        ftpExamples = "EOS R5, R5 Mark II, R6 Mark II, R3, R1",
        ftpPath = R.string.brand_canon_ftp_path,
        autoSend = R.string.brand_canon_auto_send,
        ftpFinish = R.string.brand_canon_ftp_finish,
    ),
    Nikon(
        "Nikon", listOf("nikon"), "SnapBridge",
        ftpExamples = "Z9, Z8, Z6III",
        ftpPath = R.string.brand_nikon_ftp_path,
        autoSend = R.string.brand_nikon_auto_send,
        ftpFinish = R.string.brand_nikon_ftp_finish,
    ),
    Fujifilm(
        "Fujifilm", listOf("fujifilm"), "FUJIFILM XApp", otherApp = R.string.brand_fujifilm_other_app,
        ftpExamples = "GFX100 II, X-H2, X-H2S (FT-XH grip)",
        autoSend = R.string.brand_fujifilm_auto_send,
    ),
    Panasonic(
        "Panasonic Lumix", listOf("panasonic"), "LUMIX Lab", otherApp = R.string.brand_panasonic_other_app,
        autoSend = R.string.brand_panasonic_auto_send,
    ),
    Om(
        "OM System / Olympus", listOf("olympus", "om digital"), "OM Image Share",
        autoSend = R.string.brand_om_auto_send,
    ),
    Ricoh(
        "Ricoh / Pentax", listOf("ricoh", "pentax"), "GR WORLD", otherApp = R.string.brand_ricoh_other_app,
        autoSend = R.string.brand_ricoh_auto_send,
    ),
    Leica(
        "Leica", listOf("leica"), "Leica FOTOS",
        autoSend = R.string.brand_leica_auto_send,
    ),
    Other(
        null, emptyList(), null,
        autoSend = R.string.brand_other_auto_send,
    );

    /** "Sony", or "Another brand". */
    fun label(res: Resources): String = brandName ?: res.getString(R.string.brand_other)

    /** The maker's app ("Creators' App"), or "your camera's app" for another brand. */
    fun app(res: Resources): String = appName ?: res.getString(R.string.brand_other_app)

    /** The app, as a short label ("camera app" for another brand). */
    fun appShort(res: Resources): String = appName ?: res.getString(R.string.brand_other_app_short)

    /** "Creators' App (Imaging Edge Mobile on older models)". */
    fun appLong(res: Resources): String =
        otherApp?.let { res.getString(R.string.brand_app_long, app(res), res.getString(it)) } ?: app(res)

    fun matchesMake(make: String?): Boolean {
        if (make.isNullOrBlank()) return false
        val m = make.lowercase(Locale.ROOT)
        return makes.any { m.contains(it) }
    }

    companion object {
        fun forMake(make: String?): Brand? = entries.firstOrNull { it != Other && it.matchesMake(make) }
    }
}

/** One of your cameras. */
data class Camera(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val brand: Brand,
    val link: Link,
    /** Film this camera always uses; null = the film picked on the home screen. */
    val film: String? = null,
    /** EXIF model, learned from its first photo. Tells cameras of the same brand apart. */
    val model: String? = null,
    /** When it was added (epoch seconds). */
    val addedAt: Long = System.currentTimeMillis() / 1000,
) {
    val live: Boolean get() = link != Link.Card

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("brand", brand.name).put("link", link.name)
        .put("film", film ?: JSONObject.NULL).put("model", model ?: JSONObject.NULL).put("added", addedAt)

    companion object {
        fun fromJson(o: JSONObject) = Camera(
            id = o.getString("id"),
            name = o.getString("name"),
            brand = runCatching { Brand.valueOf(o.getString("brand")) }.getOrDefault(Brand.Other),
            link = runCatching { Link.valueOf(o.getString("link")) }.getOrDefault(Link.Ftp),
            film = o.optString("film").takeIf { !o.isNull("film") && it.isNotEmpty() },
            model = o.optString("model").takeIf { !o.isNull("model") && it.isNotEmpty() },
            addedAt = o.optLong("added", 0),
        )

        fun listToJson(list: List<Camera>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String?): List<Camera> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                val a = JSONArray(s)
                (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }

        /**
         * Which of [candidates] took a photo with this EXIF make/model: the camera
         * with that model, else one of that brand not yet tied to a model, else
         * one of that brand, else a camera set up as "another brand".
         */
        fun match(candidates: List<Camera>, make: String?, model: String?): Camera? {
            if (model != null) candidates.firstOrNull { it.model == model }?.let { return it }
            val brand = Brand.forMake(make)
            val sameBrand = candidates.filter { it.brand == brand }
            return sameBrand.firstOrNull { it.model == null }
                ?: sameBrand.firstOrNull()
                ?: candidates.firstOrNull { it.brand == Brand.Other }
        }
    }
}
