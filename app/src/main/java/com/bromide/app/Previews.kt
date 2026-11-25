package com.bromide.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import com.bromide.engine.FilmEngine
import com.bromide.engine.Stock
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Renders small "what this film looks like" previews, cached in memory. */
object Previews {
    private class Source(val w: Int, val h: Int, val px: IntArray)

    /** Previews show grain and halation at the scale of a photo this many px across. */
    private const val PREVIEW_FILM_EDGE = 2048f

    private val sources = HashMap<String, Source>()
    /** Phone photos picked to develop: kept apart, and only a few, since there can be many. */
    private val photoSources = object : LruCache<String, Source>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Source) = value.px.size * 4
    }
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun lastPhotoFile(context: Context) = File(context.filesDir, "preview_last.jpg")

    fun hasLastPhoto(context: Context) = lastPhotoFile(context).exists()

    /** Save a downscaled copy of the newest camera photo, to preview films on your own picture. */
    fun saveLastPhoto(context: Context, bitmap: Bitmap) {
        val l = max(bitmap.width, bitmap.height)
        val s = 900f / l
        val small = if (s < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).roundToInt(),
                                                          (bitmap.height * s).roundToInt(), true) else bitmap
        val tmp = File(context.filesDir, "preview_last.tmp")
        tmp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tmp.renameTo(lastPhotoFile(context))
        if (small !== bitmap) small.recycle()
        synchronized(sources) { sources.keys.removeAll { it.startsWith("LastPhoto") } }
    }

    private fun source(context: Context, which: PreviewSource, longEdge: Int): Source? {
        val key = "${which.name}@$longEdge"
        synchronized(sources) { sources[key]?.let { return it } }
        val bmp = try {
            val raw = if (which.asset != null) {
                context.assets.open(which.asset).use { BitmapFactory.decodeStream(it) }
            } else {
                val f = lastPhotoFile(context)
                if (!f.exists()) return null
                BitmapFactory.decodeFile(f.path)
            } ?: return null
            val s = longEdge / max(raw.width, raw.height).toFloat()
            if (s < 1f) Bitmap.createScaledBitmap(raw, (raw.width * s).roundToInt(), (raw.height * s).roundToInt(), true)
                .also { if (it !== raw) raw.recycle() } else raw
        } catch (_: Exception) {
            return null
        }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val src = Source(bmp.width, bmp.height, px)
        bmp.recycle()
        synchronized(sources) { sources[key] = src }
        return src
    }

    /** The preview image as it is, without film, to compare. Blocking. */
    fun original(context: Context, which: PreviewSource, longEdge: Int): Bitmap? {
        val actual = if (which == PreviewSource.LastPhoto && !hasLastPhoto(context)) PreviewSource.Portrait else which
        val key = "original|${actual.name}|$longEdge"
        cache.get(key)?.let { return it }
        val src = source(context, actual, longEdge) ?: return null
        return Bitmap.createBitmap(src.px, src.w, src.h, Bitmap.Config.ARGB_8888).also { cache.put(key, it) }
    }

    /** Blocking; call off the main thread. Falls back to the portrait sample. */
    fun render(context: Context, stock: Stock, look: Look, which: PreviewSource, longEdge: Int, version: Int = 0): Bitmap? {
        val actual = if (which == PreviewSource.LastPhoto && !hasLastPhoto(context)) PreviewSource.Portrait else which
        val key = "${stock.id}|${look.encode()}|${actual.name}|$longEdge|$version"
        cache.get(key)?.let { return it }
        val src = source(context, actual, longEdge) ?: return null
        return develop(context, key, src, stock, look)
    }

    /** A film's look on a photo from the phone's library. Blocking. */
    fun renderPhoto(context: Context, uri: Uri, stock: Stock, look: Look, longEdge: Int): Bitmap? {
        val key = "${stock.id}|${look.encode()}|$uri|$longEdge"
        cache.get(key)?.let { return it }
        val src = photoSource(context, uri, longEdge) ?: return null
        return develop(context, key, src, stock, look)
    }

    /** A photo from the phone's library as it is, to compare. Blocking. */
    fun originalPhoto(context: Context, uri: Uri, longEdge: Int): Bitmap? {
        val key = "original|$uri|$longEdge"
        cache.get(key)?.let { return it }
        val src = photoSource(context, uri, longEdge) ?: return null
        return Bitmap.createBitmap(src.px, src.w, src.h, Bitmap.Config.ARGB_8888).also { cache.put(key, it) }
    }

    private fun photoSource(context: Context, uri: Uri, longEdge: Int): Source? {
        val key = "$uri@$longEdge"
        photoSources.get(key)?.let { return it }
        val bmp = try {
            // ImageDecoder applies the EXIF rotation, and reads HEIF and DNG too
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val s = longEdge / max(w, h).toFloat()
                if (s < 1f) decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
            }
        } catch (_: Exception) {
            return null
        }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val src = Source(bmp.width, bmp.height, px)
        bmp.recycle()
        photoSources.put(key, src)
        return src
    }

    private fun develop(context: Context, key: String, src: Source, stock: Stock, look: Look): Bitmap? {
        val px = src.px.copyOf()
        val params = look.toParams().apply {
            seed = 7
            threads = 1
            // Show grain and halation as big as on a full-size photo, so even a small
            // preview shows the film's texture (shrunk honestly, they'd vanish).
            filmScale = PREVIEW_FILM_EDGE / max(src.w, src.h).toFloat()
        }
        return try {
            FilmEngine.develop(px, src.w, src.h, stock, LutStore.get(context, stock), params, null)
            Bitmap.createBitmap(px, src.w, src.h, Bitmap.Config.ARGB_8888).also { cache.put(key, it) }
        } catch (_: Exception) {
            null
        }
    }
}
