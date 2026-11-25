package com.bromide.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.bromide.engine.FtpServer
import com.bromide.engine.RawDecoder
import com.bromide.engine.Stock
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service: receives photos from your cameras and develops each one
 * as it arrives, one at a time. Photos come in two ways, depending on how each
 * camera is set up: uploaded to Bromide's FTP server over the phone's hotspot,
 * or saved on the phone by the camera maker's app (see [PhotoWatcher]).
 */
class FilmService : Service() {

    private var server: FtpServer? = null
    private var watcher: PhotoWatcher? = null
    private val queue = Executors.newSingleThreadExecutor { Thread(it, "bromide-develop") }
    private val enginePool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
    private val pending = AtomicInteger()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var developer: Developer
    private val settings get() = (application as BromideApp).settings
    private val inbox by lazy { File(filesDir, "inbox") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        developer = Developer(this, enginePool)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(getString(R.string.service_starting)), type)
        syncSources()
        return START_STICKY
    }

    /** Run the FTP server and/or the app watcher, as your cameras need. Called again when they change. */
    private fun syncSources() {
        val fresh = server == null && watcher == null
        if (fresh) {
            inbox.mkdirs()
            inbox.listFiles()?.forEach { it.deleteRecursively() }  // leftovers from a previous run
            ServiceState.update {
                it.copy(cameraSeen = false, cameraHost = null, received = 0, developed = 0, queued = 0, error = null)
            }
        }
        val cams = settings.cameras
        val wantFtp = cams.any { it.link == Link.Ftp }
        val wantWatch = cams.any { it.link == Link.App }

        // A password set or changed since the server started (first FTP camera): restart it.
        if (server != null && serverPassword != ftpPassword()) {
            server?.stop()
            server = null
        }
        if (wantFtp && server == null) startFtp()
        if (!wantFtp && server != null) {
            server?.stop()
            server = null
        }
        if (wantWatch && watcher == null) {
            if (PhotoWatcher.hasPhotoAccess(this)) {
                watcher = PhotoWatcher(this, settings, inbox) { file, camera -> onPhoto(file, null, camera) }.also { it.start() }
            } else {
                ServiceState.log("camera app: Bromide can't see the phone's photos yet")
            }
        }
        if (!wantWatch && watcher != null) {
            watcher?.stop()
            watcher = null
        }
        ServiceState.update {
            it.copy(ftpOn = server != null, watchOn = watcher != null,
                    needsPhotoAccess = wantWatch && watcher == null)
        }

        if (server == null && watcher == null) {
            if (!wantFtp && !wantWatch) {
                ServiceState.update { it.copy(error = getString(R.string.error_no_camera)) }
            }
            stopEverything()
            return
        }
        acquireLocks()
        ServiceState.update { it.copy(running = true) }
        refreshNotification()
    }

    private var serverPassword: String? = null

    private fun ftpPassword() = if (settings.requirePassword) settings.password else null

    private fun startFtp() {
        val port = settings.port
        val password = ftpPassword()
        serverPassword = password
        // Its own folder: photos the app watcher copies (inbox/app) stay out of the FTP server's reach.
        val ftp = FtpServer(port, File(inbox, "ftp"), password, object : FtpServer.Listener {
            override fun onFileReceived(file: File, user: String?) = onPhoto(file, user, null)

            override fun onClientConnected(remote: String, local: String) {
                val host = local.removePrefix("::ffff:")
                settings.cameraHost = host
                ServiceState.update { it.copy(cameraSeen = true, cameraHost = host) }
                ServiceState.log("camera connected from $remote (to $host)")
                refreshNotification()
            }

            override fun onLog(message: String) = ServiceState.log(message)
        })
        try {
            ftp.start()
        } catch (e: Exception) {
            ServiceState.update { it.copy(error = getString(R.string.error_port, port, e.message ?: e.toString())) }
            return
        }
        server = ftp
        // the hotspot may be switched on after Start: keep the shown address fresh
        Thread({
            while (server === ftp) {
                val now = Network.addresses()
                if (now != ServiceState.status.value.addresses) {
                    ServiceState.update { it.copy(addresses = now) }
                    refreshNotification()
                }
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "bromide-net").apply { isDaemon = true }.start()
        ServiceState.update { it.copy(port = port, addresses = Network.addresses()) }
    }

    /**
     * Which camera an FTP upload came from: the only FTP camera, or the one whose
     * brand and model match the photo's EXIF. Remembers each camera's model.
     */
    private fun ftpCamera(file: File): Camera? {
        val cams = settings.cameras.filter { it.link == Link.Ftp }
        if (cams.isEmpty()) return null
        val (make, model) = try {
            ExifInterface(file.path).let { it.getAttribute(ExifInterface.TAG_MAKE)?.trim() to it.getAttribute(ExifInterface.TAG_MODEL)?.trim() }
        } catch (_: Exception) {
            null to null
        }
        val cam = (if (cams.size == 1) cams[0] else Camera.match(cams, make, model)) ?: cams[0]
        return learnModel(cam, model)
    }

    private fun learnModel(cam: Camera, model: String?): Camera {
        if (model.isNullOrEmpty() || cam.model != null) return cam
        if (settings.cameras.any { it.model == model }) return cam
        return cam.copy(model = model).also { settings.saveCamera(it) }
    }

    /** Developed this session, by camera + file name without extension. */
    private val fromJpeg = HashMap<String, Developed>()
    private val fromRaw = HashSet<String>()

    /** A photo arrived. [user] is the FTP user name (may name a film); [from] the camera, if already known. */
    private fun onPhoto(file: File, user: String?, from: Camera?) {
        val name = file.name
        val isRaw = RawDecoder.isRaw(name)
        if (!isRaw && !Developer.isImage(name)) {
            ServiceState.log("skipped $name (not a photo)")
            file.delete()
            return
        }
        if (isRaw && settings.sourceMode == SourceMode.JpegOnly) {
            ServiceState.log("skipped $name (JPEG-only mode)")
            file.delete()
            return
        }
        if (isRaw && !RawDecoder.available()) {
            ServiceState.update { it.copy(error = getString(R.string.error_no_raw_camera)) }
            file.delete()
            return
        }
        // Move it aside right away so a re-sent file with the same name can't clash.
        val held = File(file.parentFile, ".${System.nanoTime()}_$name")
        if (!file.renameTo(held)) return
        val camera = from?.let { learnModel(it, exifModel(held)) } ?: ftpCamera(held)
        // FTP user name naming a film > the camera's own film > the film picked in Bromide
        val stock = user?.let { Stock.byId(it) } ?: camera?.film?.let { Stock.byId(it) } ?: settings.stock
        val base = name.substringBeforeLast('.').uppercase(Locale.ROOT)
        val key = "${camera?.id}:$base"
        pending.incrementAndGet()
        ServiceState.update { it.copy(received = it.received + 1, queued = pending.get(), cameraUser = user) }
        refreshNotification()
        queue.execute {
            // A new photo uses a frame of the free roll; the RAW version of one already developed doesn't.
            var frame = false
            try {
                // RAW+JPEG: the JPEG is shown first; the RAW version then replaces it.
                // If the RAW came first, the JPEG isn't needed.
                if (!isRaw && key in fromRaw) {
                    ServiceState.log("$name: RAW already developed, skipping the JPEG")
                    return@execute
                }
                val previous = if (isRaw) fromJpeg[key] else null
                if (previous == null) {
                    frame = Roll.take()
                    if (!frame) {
                        val kept = Roll.keep(held, name, camera?.id, user)
                        ServiceState.log("$name: free roll finished, " + if (kept) "kept until Bromide is unlocked" else "not kept (enough are waiting)")
                        return@execute
                    }
                }
                val out = developer.develop(
                    Developer.Job(held, name, stock, settings.look(stock.id), settings.outputSize, replace = previous?.uri)
                )
                val d = Developed(out.uri, out.displayName, stock.name, out.printEv, out.millis, out.fromRaw,
                                  version = (previous?.version ?: -1) + 1, cameraId = camera?.id, cameraName = camera?.name)
                if (isRaw) fromRaw += key else fromJpeg[key] = d
                ServiceState.update { st ->
                    val replaced = previous != null && st.recent.any { it.uri == d.uri }
                    st.copy(
                        developed = if (replaced) st.developed else st.developed + 1,
                        lastPhotoVersion = st.lastPhotoVersion + 1,
                        recent = if (replaced) st.recent.map { if (it.uri == d.uri) d else it }
                                 else (listOf(d) + st.recent).take(48),
                    )
                }
                ServiceState.log("developed $name as ${stock.name} in ${out.millis} ms")
            } catch (t: Throwable) {
                if (frame) Roll.giveBack()
                ServiceState.update { it.copy(error = getString(R.string.error_develop, name, t.message ?: t.toString())) }
                ServiceState.log("failed on $name: $t")
            } finally {
                held.delete()
                ServiceState.update { it.copy(queued = pending.decrementAndGet()) }
                refreshNotification()
            }
        }
    }

    private fun exifModel(file: File): String? =
        try { ExifInterface(file.path).getAttribute(ExifInterface.TAG_MODEL)?.trim() } catch (_: Exception) { null }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bromide:server").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
        if (wifiLock == null && server != null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Bromide:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun refreshNotification() {
        val s = ServiceState.status.value
        val roll = Roll.state.value
        val text = when {
            !s.running -> getString(R.string.service_stopped)
            !roll.canDevelop && roll.latent > 0 -> resources.getQuantityString(R.plurals.roll_notif_waiting, roll.latent, roll.latent)
            !roll.canDevelop -> getString(R.string.roll_finished)
            s.queued > 0 -> getString(R.string.service_developing, s.developed, s.queued)
            s.ftpOn && !s.watchOn && s.addresses.isEmpty() -> getString(R.string.service_hotspot_off)
            s.developed > 0 -> resources.getQuantityString(R.plurals.service_developed, s.developed, s.developed, settings.stock.name)
            s.cameraSeen -> getString(R.string.service_connected, settings.stock.name)
            s.ftpOn && s.addresses.isNotEmpty() -> getString(R.string.service_waiting_ftp, s.addresses.first().ip, s.port)
            else -> getString(R.string.service_waiting_app)
        } + if (s.running && !roll.unlocked && roll.canDevelop) " · " + getString(R.string.roll_notif_left, roll.left, Roll.FRAMES) else ""
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FilmService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, BromideApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFFF5A2A.toInt()) // FB.Accent: the halation red-orange
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun releaseAll() {
        server?.stop()
        server = null
        watcher?.stop()
        watcher = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun stopEverything() {
        releaseAll()
        ServiceState.update { it.copy(running = false, queued = 0, ftpOn = false, watchOn = false) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseAll()
        queue.shutdown()
        enginePool.shutdown()
        ServiceState.update { it.copy(running = false, ftpOn = false, watchOn = false) }
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.bromide.app.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FilmService::class.java))
        }

        /** Apply camera changes to a running session. */
        fun refresh(context: Context) {
            if (ServiceState.status.value.running) start(context)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FilmService::class.java).setAction(ACTION_STOP))
        }
    }
}
