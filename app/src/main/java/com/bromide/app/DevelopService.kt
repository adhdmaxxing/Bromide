package com.bromide.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bromide.engine.RawDecoder
import com.bromide.engine.Stock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors

/** How big developed phone photos are. */
enum class PhoneSize(@StringRes val label: Int) {
    Full(R.string.size_full),
    /** Decoded at half resolution: about four times faster. */
    Half(R.string.size_half),
    /** Small enough to share anywhere. */
    Share(R.string.size_2048),
}

/** Where a batch of photos comes from, and where its results go. */
enum class BatchKind(
    val folder: String,
    val nameSuffix: String,
    @StringRes val title: Int,
    @StringRes val progress: Int,
    @StringRes val stopped: Int,
    @PluralsRes val done: Int,
) {
    /** Photos taken with this phone, picked from its library. */
    Phone("Pictures/Bromide/Phone", "", R.string.phone_notif_title, R.string.phone_notif_progress,
          R.string.phone_notif_stopped, R.plurals.phone_notif_done),
    /** Originals on a camera's memory card, developed at full quality. */
    Card("Pictures/Bromide/Full", "_full", R.string.card_notif_title, R.string.card_notif_progress,
         R.string.card_notif_stopped, R.plurals.card_notif_done),
    /** Camera photos kept while the free roll was finished, developed once Bromide is unlocked. */
    Latent("Pictures/Bromide", "", R.string.latent_notif_title, R.string.latent_notif_progress,
           R.string.latent_notif_stopped, R.plurals.latent_notif_done),
}

/**
 * One photo to develop, with its own film and settings. [newFrame]: a new camera photo, which
 * uses a frame of the free roll. [removeAfter]: a file to delete once it's developed.
 */
data class DevelopItem(
    val uri: Uri, val name: String, val stockId: String, val look: Look,
    val newFrame: Boolean = false, val removeAfter: java.io.File? = null,
)

/** Progress of the batch being developed (or the last one), shared between [DevelopService] and the UI. */
data class Batch(
    val kind: BatchKind = BatchKind.Phone,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** File name of the photo being developed. */
    val current: String = "",
    /** Film of the photo being developed (or the last one). */
    val stockName: String = "",
    /** Gallery photos made so far. */
    val results: List<Uri> = emptyList(),
    val errors: List<String> = emptyList(),
    /** New camera photos left undeveloped because the free roll ran out. */
    val rollOut: Int = 0,
)

object Developing {
    private val _state = MutableStateFlow(Batch())
    val state: StateFlow<Batch> = _state

    fun update(f: (Batch) -> Batch) = _state.update(f)
}

/**
 * Develops a batch of photos in the background with a progress notification, so you can
 * leave Bromide while it works: photos picked from the phone's library, or the originals
 * on a memory card. One batch at a time. Separate from [FilmService] (the camera session).
 */
class DevelopService : Service() {
    private class Request(val kind: BatchKind, val items: List<DevelopItem>, val outputSize: Int, val halfSize: Boolean)

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "bromide-batch") }
    private val enginePool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
    @Volatile private var cancelled = false
    /** A batch is being developed; one at a time. */
    @Volatile private var busy = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelled = true  // the photo in progress finishes, then the batch ends
            if (!busy) stopSelf()
            return START_NOT_STICKY
        }
        // Developing is media processing (Android 15+), but some Android 15 builds refuse that type
        // ("Starting FGS with type none", seen on the API 35 emulator) and the service would crash:
        // fall back to data sync (local file processing), which every version accepts.
        val notification = progressNotification(Developing.state.value)
        try {
            if (Build.VERSION.SDK_INT < 35) throw IllegalStateException("no media processing type before Android 15")
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } catch (_: RuntimeException) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        if (busy) return START_NOT_STICKY  // one batch at a time; the screens don't offer more
        val request = pending.also { pending = null }
        if (request == null || request.items.isEmpty()) {
            Developing.update { it.copy(running = false) }
            finish()
            return START_NOT_STICKY
        }

        cancelled = false
        busy = true
        Developing.update { Batch(kind = request.kind, running = true, total = request.items.size) }
        refreshNotification()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bromide:batch").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }

        worker.execute {
            val developer = Developer(this, enginePool)
            try {
                for ((i, item) in request.items.withIndex()) {
                    if (cancelled) break
                    if (item.newFrame && !Roll.take()) {
                        Developing.update { it.copy(rollOut = request.items.drop(i).count { it.newFrame }) }
                        break
                    }
                    val stock = Stock.byId(item.stockId) ?: Stock.defaultStock()
                    Developing.update { it.copy(current = item.name, stockName = stock.name) }
                    refreshNotification()
                    try {
                        val out = developOne(developer, request, item, stock)
                        Developing.update { it.copy(results = it.results + out) }
                    } catch (t: Throwable) {
                        if (item.newFrame) Roll.giveBack()
                        Developing.update { it.copy(errors = it.errors + getString(R.string.error_named, item.name, t.message ?: t.toString())) }
                    }
                    // Developer already retries at half size: a photo that still fails won't develop later either.
                    item.removeAfter?.delete()
                    Developing.update { it.copy(done = it.done + 1) }
                }
                if (request.kind == BatchKind.Latent) Roll.refreshLatent()
            } finally {
                busy = false
                Developing.update { it.copy(running = false, current = "") }
                postDone(Developing.state.value, cancelled)
                finish()
            }
        }
        return START_NOT_STICKY
    }

    private fun developOne(developer: Developer, request: Request, item: DevelopItem, stock: Stock): Uri {
        if (RawDecoder.isRaw(item.name) && !RawDecoder.available()) throw IllegalStateException(getString(R.string.error_no_raw))
        val ext = item.name.substringAfterLast('.', "jpg")
        val tmp = File(cacheDir, "batch_${System.nanoTime()}.$ext")
        try {
            contentResolver.openInputStream(item.uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                ?: throw IllegalStateException(getString(R.string.error_read_photo))
            return developer.develop(
                Developer.Job(
                    tmp, item.name, stock, item.look,
                    outputSize = request.outputSize, halfSize = request.halfSize,
                    folder = request.kind.folder, nameSuffix = request.kind.nameSuffix, savePreview = false,
                )
            ).uri
        } finally {
            tmp.delete()
        }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, progressNotification(Developing.state.value))
    }

    private fun progressNotification(b: Batch): Notification {
        val stop = PendingIntent.getService(
            this, 2, Intent(this, DevelopService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return builder()
            .setContentTitle(if (b.total > 0) getString(b.kind.progress, b.done + 1, b.total) else getString(b.kind.title))
            .setContentText(listOf(b.stockName, b.current).filter { it.isNotEmpty() }.joinToString(" · "))
            .setProgress(b.total, b.done, b.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun postDone(b: Batch, stopped: Boolean) {
        if (b.total == 0) return
        val developed = b.results.size
        val title = if (stopped) getString(b.kind.stopped, developed, b.total)
                    else resources.getQuantityString(b.kind.done, developed, developed)
        val text = when {
            b.rollOut > 0 -> resources.getQuantityString(R.plurals.roll_not_developed, b.rollOut, b.rollOut)
            b.errors.isEmpty() -> getString(R.string.batch_done_where, b.kind.folder)
            else -> resources.getQuantityString(R.plurals.phone_notif_failed, b.errors.size, b.errors.size)
        }
        getSystemService(NotificationManager::class.java).notify(DONE_ID,
            builder().setContentTitle(title).setContentText(text).setAutoCancel(true).build())
    }

    private fun builder(): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, BromideApp.PHONE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFFF5A2A.toInt()) // FB.Accent: the halation red-orange
            .setContentIntent(open)
    }

    private fun finish() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15 caps background media processing at 6 hours a day: stop cleanly when it's up. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelled = true
        finish()
    }

    override fun onDestroy() {
        cancelled = true
        // let the photo in progress finish with the engine pool before shutting it down
        worker.execute { enginePool.shutdown() }
        worker.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val DONE_ID = 3
        private const val ACTION_STOP = "com.bromide.app.STOP_BATCH"

        /**
         * The batch handed to the service. It runs in Bromide's own process, so the list is
         * passed here rather than in the Intent, which can't carry a whole memory card.
         */
        @Volatile private var pending: Request? = null

        /**
         * Develop [items] in the background. [outputSize] is the long edge in px (0 = full size,
         * or half with [halfSize]). Shown as running right away, so a second tap can't start
         * another batch.
         */
        fun start(context: Context, kind: BatchKind, items: List<DevelopItem>, outputSize: Int, halfSize: Boolean = false) {
            if (Developing.state.value.running || items.isEmpty()) return
            pending = Request(kind, items, outputSize, halfSize)
            Developing.update { Batch(kind = kind, running = true, total = items.size) }
            try {
                ContextCompat.startForegroundService(context, Intent(context, DevelopService::class.java))
            } catch (e: Exception) {
                pending = null
                Developing.update {
                    Batch(kind = kind, errors = listOf(context.getString(R.string.error_start_phone, e.message ?: e.toString())))
                }
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DevelopService::class.java).setAction(ACTION_STOP))
        }
    }
}
