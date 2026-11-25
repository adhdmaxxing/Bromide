package com.bromide.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.bromide.engine.RawDecoder
import com.bromide.engine.Stock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale

/**
 * The free roll: the first [FRAMES] new photos from your cameras are developed for free, then a
 * one-time purchase ([Billing]) unlocks Bromide for good. Photos taken with the phone itself are
 * always free, and so are a RAW replacing its JPEG and full-size card copies of developed photos.
 *
 * Camera photos that arrive once the roll is finished aren't lost: they're kept as latent images
 * (exposed, not developed yet), up to one more roll, and developed as soon as Bromide is unlocked.
 *
 * Early users ([BuildConfig.EARLY_ACCESS] builds) are founders: Bromide is free for them for good,
 * no roll, no purchase. The flag lives in the roll prefs, which Android backs up to the user's
 * Google account, so it comes back after a reinstall or on a new phone. Builds made once early
 * access is over no longer make new founders, but keep the ones already there.
 */
object Roll {
    const val FRAMES = 36
    /** The one-time product in Play Console. */
    const val PRODUCT_ID = "unlock"
    /** Kept photos, at most: one more roll. The camera's card still has every original. */
    private const val MAX_LATENT = 36

    data class State(
        val used: Int = 0,
        /** The unlock was bought on Play. */
        val bought: Boolean = false,
        /** Installed during early access: free for good. */
        val founder: Boolean = false,
        /** Photos waiting for the unlock to be developed. */
        val latent: Int = 0,
        /** The unlock's price from Play, once known. */
        val price: String? = null,
        /** A purchase is waiting for its payment (cash, bank transfer...). */
        val pending: Boolean = false,
    ) {
        /** Every photo is free: bought, or a founder. */
        val unlocked get() = bought || founder
        val left get() = (FRAMES - used).coerceAtLeast(0)
        val canDevelop get() = unlocked || used < FRAMES
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private lateinit var prefs: SharedPreferences
    private lateinit var latentDir: File

    fun init(context: Context) {
        prefs = context.getSharedPreferences("roll", Context.MODE_PRIVATE)
        latentDir = File(context.filesDir, "latent")
        // Only once: a founder flag set to false (debug test controls) stays false.
        if (BuildConfig.EARLY_ACCESS && !prefs.contains("founder")) prefs.edit().putBoolean("founder", true).apply()
        _state.value = State(used = prefs.getInt("used", 0), bought = prefs.getBoolean("unlocked", false),
                             founder = prefs.getBoolean("founder", false), latent = latentFiles().size)
    }

    /** A new camera photo is about to be developed: uses a frame, or false when the roll is finished. */
    @Synchronized
    fun take(): Boolean {
        val s = _state.value
        if (s.unlocked) return true
        if (s.used >= FRAMES) return false
        setUsed(s.used + 1)
        return true
    }

    /** The photo a frame was taken for couldn't be developed: give the frame back. */
    @Synchronized
    fun giveBack() {
        val s = _state.value
        if (!s.unlocked && s.used > 0) setUsed(s.used - 1)
    }

    fun setUsed(used: Int) {
        prefs.edit().putInt("used", used.coerceIn(0, FRAMES)).apply()
        _state.update { it.copy(used = used.coerceIn(0, FRAMES)) }
    }

    /** The Play purchase, as [Billing] reads it. */
    fun setUnlocked(bought: Boolean) {
        prefs.edit().putBoolean("unlocked", bought).apply()
        _state.update { it.copy(bought = bought, pending = if (bought) false else it.pending) }
    }

    /** Debug test controls only: founders are made by early-access builds. */
    fun setFounder(founder: Boolean) {
        prefs.edit().putBoolean("founder", founder).apply()
        _state.update { it.copy(founder = founder) }
    }

    fun setPrice(price: String?) = _state.update { it.copy(price = price) }

    fun setPending(pending: Boolean) = _state.update { it.copy(pending = pending) }

    // ---------------------------------------------------------------------------------
    // Latent images: camera photos kept until the unlock
    // ---------------------------------------------------------------------------------

    private class Latent(val file: File, val name: String, val cameraId: String?, val user: String?)

    /** Keep a photo the roll had no frame for; [cameraId] and [user] are what it came with. False if not kept. */
    @Synchronized
    fun keep(file: File, name: String, cameraId: String?, user: String?): Boolean {
        if (latentFiles().size >= MAX_LATENT) return false
        latentDir.mkdirs()
        fun clean(s: String?) = s.orEmpty().replace('~', '_')
        val target = File(latentDir, "${System.nanoTime()}~${clean(cameraId)}~${clean(user)}~$name")
        val kept = file.renameTo(target) || runCatching { file.copyTo(target); file.delete() }.isSuccess
        _state.update { it.copy(latent = latentFiles().size) }
        return kept
    }

    private fun latentFiles(): List<Latent> =
        latentDir.listFiles()?.mapNotNull { f ->
            val p = f.name.split('~', limit = 4)
            if (p.size == 4) Latent(f, p[3], p[1].ifEmpty { null }, p[2].ifEmpty { null }) else null
        }?.sortedBy { it.file.name }.orEmpty()

    /**
     * The kept photos as a batch for [DevelopService], each with the film it would have had:
     * the FTP user name's film, else its camera's, else the loaded one. One per shot, RAW
     * preferred: a RAW+JPEG pair's JPEG is dropped.
     */
    @Synchronized
    fun latentBatch(settings: Settings): List<DevelopItem> {
        val shots = latentFiles().groupBy { it.cameraId to it.name.substringBeforeLast('.').uppercase(Locale.ROOT) }.values
        val items = shots.map { group ->
            val pick = group.firstOrNull { RawDecoder.isRaw(it.name) } ?: group.first()
            group.filter { it !== pick }.forEach { it.file.delete() }
            val stock = pick.user?.let { Stock.byId(it) } ?: settings.camera(pick.cameraId)?.film?.let { Stock.byId(it) }
                ?: settings.stock
            DevelopItem(Uri.fromFile(pick.file), pick.name, stock.id, settings.look(stock.id), removeAfter = pick.file)
        }
        _state.update { it.copy(latent = latentFiles().size) }
        return items
    }

    /** A kept photo was developed (or discarded). */
    @Synchronized
    fun refreshLatent() = _state.update { it.copy(latent = latentFiles().size) }

    @Synchronized
    fun discardLatent() {
        latentDir.listFiles()?.forEach { it.delete() }
        refreshLatent()
    }
}
