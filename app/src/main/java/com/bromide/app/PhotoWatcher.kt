package com.bromide.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Locale

/**
 * Develops photos that a camera maker's app (Creators' App, Camera Connect,
 * SnapBridge, XApp, LUMIX Sync…) saves on the phone.
 *
 * It watches the phone's photo library for new pictures and keeps the ones whose
 * EXIF make matches one of your "through the app" cameras, so photos from the
 * phone's own camera, screenshots and downloads are left alone. The originals
 * stay where the app saved them.
 */
class PhotoWatcher(
    private val context: Context,
    private val settings: Settings,
    private val inbox: File,
    private val onPhoto: (File, Camera) -> Unit,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var observer: ContentObserver? = null
    /** Size seen at the previous look, for files that may still be written. */
    private val lastSize = HashMap<Long, Long>()

    fun start() {
        val t = HandlerThread("bromide-watch").apply { start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        val now = System.currentTimeMillis() / 1000
        // Catch up on photos the app saved while Bromide was off, but not on a whole old library.
        if (settings.watchSince < now - CATCH_UP_S) settings.watchSince = now - CATCH_UP_S
        val obs = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean) = schedule(300)
        }
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs)
        observer = obs
        // Some apps don't trigger the observer reliably: also look every few seconds.
        h.post(object : Runnable {
            override fun run() {
                poll()
                h.postDelayed(this, POLL_MS)
            }
        })
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private val pollNow = Runnable { poll() }

    private fun schedule(delayMs: Long) {
        val h = handler ?: return
        h.removeCallbacks(pollNow)
        h.postDelayed(pollNow, delayMs)
    }

    private fun poll() {
        val cams = settings.cameras.filter { it.link == Link.App }
        if (cams.isEmpty()) return
        var since = settings.watchSince
        val seen = settings.watchSeen.toMutableList()
        val seenSet = seen.toHashSet()
        var changed = false
        val nowMs = System.currentTimeMillis()

        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_MODIFIED,
        )
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf((since - LOOKBACK_S).toString()),
                "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id in seenSet) continue
                    val name = c.getString(1) ?: continue
                    val path = c.getString(2) ?: ""
                    val size = c.getLong(3)
                    val added = c.getLong(4)
                    val modifiedMs = c.getLong(5) * 1000

                    fun done() {
                        seen += id
                        seenSet += id
                        lastSize.remove(id)
                        if (added > since) since = added
                        changed = true
                    }

                    if (isExcluded(path) || (!Developer.isImage(name) && !com.bromide.engine.RawDecoder.isRaw(name))) {
                        done(); continue
                    }
                    // Still being written? Wait until its size holds still.
                    if (size <= 0) continue
                    if (nowMs - modifiedMs < SETTLE_MS && lastSize[id] != size) {
                        lastSize[id] = size
                        schedule(SETTLE_MS)
                        continue
                    }

                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val camera = cameraFor(uri, cams, added)
                    if (camera == null) {
                        done(); continue
                    }
                    val file = try {
                        copyToInbox(uri, name)
                    } catch (e: Exception) {
                        ServiceState.log("couldn't read $name from $path: ${e.message}")
                        done(); continue
                    }
                    ServiceState.log("$name from ${path.trimEnd('/')} (${camera.name})")
                    done()
                    onPhoto(file, camera)
                }
            }
        } catch (e: SecurityException) {
            ServiceState.update { it.copy(needsPhotoAccess = true) }
        } catch (e: Exception) {
            ServiceState.log("photo watch: $e")
        }
        if (changed) {
            settings.watchSince = since
            settings.watchSeen = seen
        }
    }

    /** The "through the app" camera that took this photo, or null if it's not from one. */
    private fun cameraFor(uri: Uri, cams: List<Camera>, added: Long): Camera? {
        val exif = try {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        } catch (_: Exception) {
            null
        } ?: return null
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
        if (make.isNullOrEmpty()) return null  // screenshots, downloads, edits
        if (exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.startsWith("Bromide") == true) return null
        if (model != null && model.equals(Build.MODEL, ignoreCase = true)) return null  // this phone's camera
        // A camera only picks up photos from after it was added.
        return Camera.match(cams.filter { added >= it.addedAt - 5 }, make, model)
    }

    private fun copyToInbox(uri: Uri, name: String): File {
        val dir = File(inbox, "app").apply { mkdirs() }
        val dst = File(dir, name)
        // With media-location access, keep the GPS position the app may have added.
        val src = if (hasLocationAccess(context)) MediaStore.setRequireOriginal(uri) else uri
        context.contentResolver.openInputStream(src)?.use { input ->
            dst.outputStream().use { input.copyTo(it, 1 shl 16) }
        } ?: throw IllegalStateException("no data")
        return dst
    }

    companion object {
        private const val POLL_MS = 3000L
        private const val SETTLE_MS = 2000L
        /** Look a little before the last photo seen: some apps finish files out of order. */
        private const val LOOKBACK_S = 300L
        private const val CATCH_UP_S = 12 * 60 * 60L

        /** Folders that never hold photos from a camera app. */
        fun isExcluded(relativePath: String): Boolean {
            val p = relativePath.lowercase(Locale.ROOT)
            return p.startsWith("pictures/bromide") || p.startsWith("dcim/camera/") ||
                p.contains("screenshot") || p.contains("screen record")
        }

        /** The permission that lets Bromide see other apps' photos. */
        val photoPermission: String
            get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                    else Manifest.permission.READ_EXTERNAL_STORAGE

        /** What to ask for: on Android 14+ also the "selected photos" permission, so a partial grant can be told apart. */
        val photoPermissions: List<String>
            get() = if (Build.VERSION.SDK_INT >= 34) listOf(photoPermission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                    else listOf(photoPermission)

        fun hasPhotoAccess(context: Context) =
            ContextCompat.checkSelfPermission(context, photoPermission) == PackageManager.PERMISSION_GRANTED

        /**
         * Android 14+ "selected photos only": Bromide sees the few photos picked, never the new
         * ones a camera app saves, so the camera-app link can't work.
         */
        fun hasPartialAccess(context: Context) =
            Build.VERSION.SDK_INT >= 34 && !hasPhotoAccess(context) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                PackageManager.PERMISSION_GRANTED

        fun hasLocationAccess(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
