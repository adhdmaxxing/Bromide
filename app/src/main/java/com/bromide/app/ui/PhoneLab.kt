package com.bromide.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bromide.app.Batch
import com.bromide.app.BatchKind
import com.bromide.app.DevelopItem
import com.bromide.app.DevelopService
import com.bromide.app.Developing
import com.bromide.app.PhoneSize
import com.bromide.app.Settings
import com.bromide.engine.Stock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A photo picked from the phone's library. */
data class PhonePhoto(val uri: Uri, val name: String)

/**
 * Photos taken with the phone itself, picked by hand. Kept apart from the camera
 * session: its own film, its own folder (Pictures/Bromide/Phone), and it never
 * touches "Just developed" or the "your last photo" preview. Lives in [AppState], so
 * the picked photos survive a trip to the film settings. Developing itself runs in
 * [DevelopService], so it goes on when you leave Bromide.
 */
class PhoneLab(private val context: Context, private val settings: Settings) {
    var photos by mutableStateOf<List<PhonePhoto>>(emptyList())
        private set
    /** The photo shown big, with the film on it. */
    var selected by mutableIntStateOf(0)

    var stockId by mutableStateOf(settings.phoneStockId)
        private set
    val stock: Stock get() = Stock.byId(stockId) ?: Stock.defaultStock()

    var size by mutableStateOf(settings.phoneSize)
        private set

    private val scope = MainScope()

    /** Progress of the phone photos being developed (or the last batch of them); empty while a card develops. */
    val batch: StateFlow<Batch> = Developing.state
        .map { if (it.kind == BatchKind.Phone) it else Batch() }
        .stateIn(scope, SharingStarted.Eagerly, Batch())

    fun select(stock: Stock) {
        stockId = stock.id
        settings.phoneStockId = stock.id
    }

    fun chooseSize(s: PhoneSize) {
        size = s
        settings.phoneSize = s
    }

    /** Replace the picked photos (a new pick starts over). */
    fun pick(uris: List<Uri>) {
        if (uris.isEmpty() || Developing.state.value.running) return
        scope.launch {
            photos = withContext(Dispatchers.IO) { uris.distinct().mapIndexed { i, u -> PhonePhoto(u, displayName(u, i)) } }
            selected = 0
            Developing.update { Batch() }  // the last batch's results go with the last pick
        }
    }

    /** Develop every picked photo with the chosen film and that film's settings, in the background. */
    fun develop() {
        val look = settings.look(stock.id)
        DevelopService.start(
            context, BatchKind.Phone, photos.map { DevelopItem(it.uri, it.name, stock.id, look) },
            outputSize = if (size == PhoneSize.Share) 2048 else 0, halfSize = size == PhoneSize.Half,
        )
    }

    fun stop() = DevelopService.stop(context)

    private fun displayName(uri: Uri, index: Int): String {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
        return name?.takeIf { it.isNotBlank() } ?: "PHONE_${System.currentTimeMillis() / 1000}_$index.jpg"
    }

    companion object {
        val FOLDER = BatchKind.Phone.folder
    }
}
