package com.bromide.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.bromide.engine.FilmEngine
import com.bromide.engine.RawDecoder
import com.bromide.engine.Stock
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Turns a camera file (JPEG/HEIF or RAW) into a film photo in the gallery. */
class Developer(private val context: Context, private val pool: ExecutorService) {

    data class Output(val uri: Uri, val displayName: String, val printEv: Float, val millis: Long, val fromRaw: Boolean)

    data class Job(
        val src: File,
        /** The camera's file name, e.g. DSC01234.ARW. */
        val sourceName: String,
        val stock: Stock,
        val look: Look,
        /** Long edge of the result in px, 0 = full size (or half, with [halfSize]). */
        val outputSize: Int,
        /** Overwrite this gallery item (e.g. replace the JPEG version with the RAW one). */
        val replace: Uri? = null,
        val folder: String = "Pictures/Bromide",
        val nameSuffix: String = "",
        /** Keep a small copy as "your last photo" for film previews. */
        val savePreview: Boolean = true,
        /** With [outputSize] 0: half the full size, decoded at half resolution (much faster). */
        val halfSize: Boolean = false,
    )

    /**
     * Develops [job]; if the phone runs out of memory on a very large photo (61 MP RAW,
     * 200 MP phone shots), develops it again at half resolution rather than failing.
     */
    fun develop(job: Job): Output = try {
        developAt(job)
    } catch (t: Throwable) {
        val smaller = job.copy(outputSize = if (job.outputSize > 0) min(job.outputSize, 2048) else 0, halfSize = true)
        if (!isOutOfMemory(t) || smaller == job) throw t
        developAt(smaller)
    }

    private fun isOutOfMemory(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.take(5).any { it is OutOfMemoryError }

    private fun developAt(job: Job): Output {
        val t0 = System.currentTimeMillis()
        val isRaw = RawDecoder.isRaw(job.sourceName)
        val base = job.sourceName.substringBeforeLast('.').uppercase(Locale.ROOT)
        val params = job.look.toParams().apply {
            seed = CRC32().apply { update(base.toByteArray()) }.value
        }
        val lut = LutStore.get(context, job.stock)

        val out: IntArray
        val w: Int
        val h: Int
        val result: FilmEngine.Result
        if (isRaw) {
            // half-size decoding skips demosaicing: much faster, and plenty for phone-sized photos
            val half = job.outputSize in 1..2400 || (job.outputSize == 0 && job.halfSize)
            val raw = RawDecoder.decode(job.src.path, half)
            val thumb = raw.thumbnail?.let { decodeThumb(it, raw.thumbFlip) }
            val gain = if (thumb != null) {
                val tp = IntArray(thumb.width * thumb.height)
                thumb.getPixels(tp, 0, thumb.width, 0, 0, thumb.width, thumb.height)
                FilmEngine.matchExposure(raw.rgb, raw.width, raw.height, tp, thumb.width, thumb.height)
            } else 2f
            if (job.savePreview && thumb != null) runCatching { Previews.saveLastPhoto(context, thumb) }
            thumb?.recycle()
            val sized = FilmEngine.downscale(raw.rgb, raw.width, raw.height, job.outputSize)
            w = sized.w
            h = sized.h
            out = IntArray(w * h)
            result = FilmEngine.develop(FilmEngine.Source.raw(sized.rgb, w, h, gain), out, job.stock, lut, params, pool)
        } else {
            val bitmap = decode(job.src, job.outputSize, job.halfSize)
            if (job.savePreview) runCatching { Previews.saveLastPhoto(context, bitmap) }
            w = bitmap.width
            h = bitmap.height
            out = IntArray(w * h)
            bitmap.getPixels(out, 0, w, 0, 0, w, h)
            bitmap.recycle()
            result = FilmEngine.develop(out, w, h, job.stock, lut, params, pool)
        }

        val bmp = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val tmp = File(context.cacheDir, "develop_${System.nanoTime()}.jpg")
        try {
            tmp.outputStream().buffered().use { bmp.compress(Bitmap.CompressFormat.JPEG, 93, it) }
            bmp.recycle()
            copyExif(job.src, tmp, base, job.stock, result.printEv, job.look, isRaw)
            val name = "${base}_${job.stock.id}${job.nameSuffix}.jpg"
            val uri = job.replace?.let { replaceInGallery(it, tmp) } ?: saveToGallery(tmp, name, job.src, job.folder)
            return Output(uri, name, result.printEv, System.currentTimeMillis() - t0, isRaw)
        } finally {
            tmp.delete()
        }
    }

    private fun decodeThumb(bytes: ByteArray, flip: Int): Bitmap? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (flip == 0) return bmp
        val m = Matrix().apply { postRotate(flip.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    /** Decode a JPEG/HEIF, downscale to the requested long edge (0 = keep size, or halve it), apply EXIF rotation. */
    private fun decode(src: File, maxLong: Int, half: Boolean = false): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        if (bounds.outWidth <= 0) throw IllegalArgumentException("not a readable image")
        val long = max(bounds.outWidth, bounds.outHeight)
        var sample = if (maxLong == 0 && half) 2 else 1
        if (maxLong > 0) while (long / (sample * 2) >= maxLong) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(src.path, opts) ?: throw IllegalArgumentException("decode failed")
        val l = max(bmp.width, bmp.height)
        if (maxLong in 1 until l) {
            val s = maxLong / l.toFloat()
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true)
            if (scaled != bmp) bmp.recycle()
            bmp = scaled
        }
        val rotation = try {
            ExifInterface(src.path).rotationDegrees
        } catch (_: Exception) {
            0
        }
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            bmp = rotated
        }
        return bmp
    }

    private fun copyExif(src: File, dst: File, base: String, stock: Stock, printEv: Float, look: Look, raw: Boolean) {
        try {
            val b = ExifInterface(dst.path)
            try {
                val a = ExifInterface(src.path)  // also reads ARW, NEF, CR2, DNG, RAF...
                for (tag in COPY_TAGS) a.getAttribute(tag)?.let { b.setAttribute(tag, it) }
            } catch (_: Exception) {
            }
            b.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            b.setAttribute(ExifInterface.TAG_SOFTWARE, "Bromide ${BuildConfig.VERSION_NAME} (${stock.name})")
            // The recipe lets Bromide rebuild a full-size version from the camera card later.
            val recipe = JSONObject()
                .put("source", base).put("stock", stock.id).put("print_ev", (printEv * 1000).roundToInt() / 1000.0)
                .put("grain", look.grain.toDouble()).put("halation", look.halation.toDouble())
                .put("grain_size", look.grainSize.toDouble()).put("color", look.color.toDouble())
                .put("warmth", look.warmth.toDouble()).put("tint", look.tint.toDouble()).put("raw", raw)
            b.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "$RECIPE_TAG$recipe")
            b.saveAttributes()
        } catch (_: Exception) {
            // EXIF is nice to have; never lose the photo over it
        }
    }

    private fun saveToGallery(file: File, name: String, src: File, folder: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            dateTaken(src)?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("couldn't create gallery entry")
        try {
            resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
                ?: throw IllegalStateException("couldn't write to gallery")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Overwrite an existing gallery photo (keeps its place and name). */
    private fun replaceInGallery(uri: Uri, file: File): Uri? = try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { o -> file.inputStream().use { it.copyTo(o) } }
        uri
    } catch (_: Exception) {
        null  // e.g. the user deleted it: save as a new photo instead
    }

    private fun dateTaken(src: File): Long? = try {
        val s = ExifInterface(src.path).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        s?.let { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val RECIPE_TAG = "Bromide recipe "

        private val COPY_TAGS = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        )

        /** Camera image files Bromide can develop, besides RAW. */
        fun isImage(name: String): Boolean {
            val n = name.lowercase(Locale.ROOT)
            return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".heif") || n.endsWith(".heic") || n.endsWith(".hif")
        }
    }
}
