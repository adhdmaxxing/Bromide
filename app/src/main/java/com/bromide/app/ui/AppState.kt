package com.bromide.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import com.bromide.app.Camera
import com.bromide.app.FilmService
import com.bromide.app.LocalAddress
import com.bromide.app.Look
import com.bromide.app.Network
import com.bromide.app.PreviewSource
import com.bromide.app.Settings
import com.bromide.engine.Stock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** UI state backed by Settings, so screens recompose when it changes. */
class AppState(val settings: Settings, private val context: Context) {
    var stockId by mutableStateOf(settings.stockId)
        private set
    var favorites by mutableStateOf(settings.favorites)
        private set
    var previewSource by mutableStateOf(settings.previewSource)
        private set
    /** Bumped when any film's look changes, so previews re-render. */
    var looksVersion by mutableIntStateOf(0)
        private set

    var cameras by mutableStateOf(settings.cameras)
        private set

    /** Photos taken with this phone, developed on request. */
    val phone = PhoneLab(context, settings)

    /** Which photos the gallery shows; kept while you open one and come back. */
    var galleryFilter by mutableStateOf(GalleryFilter.All)

    val stock: Stock get() = Stock.byId(stockId) ?: Stock.defaultStock()

    fun select(stock: Stock) {
        stockId = stock.id
        settings.stockId = stock.id
    }

    fun toggleFavorite(stock: Stock) {
        favorites = if (stock.id in favorites) favorites - stock.id else favorites + stock.id
        settings.favorites = favorites
    }

    fun setPreview(source: PreviewSource) {
        previewSource = source
        settings.previewSource = source
    }

    fun look(stock: Stock): Look = settings.look(stock.id)

    fun setLook(stock: Stock, look: Look) {
        settings.setLook(stock.id, look)
        looksVersion++
    }

    fun saveCamera(camera: Camera) {
        settings.saveCamera(camera)
        cameras = settings.cameras
        FilmService.refresh(context)
    }

    fun removeCamera(camera: Camera) {
        settings.cameras = settings.cameras.filter { it.id != camera.id }
        cameras = settings.cameras
        FilmService.refresh(context)
    }

    /** Re-read cameras the service may have updated (it learns models from photos). */
    fun reloadCameras() {
        cameras = settings.cameras
    }

    /** Favorites first, then the rest, in catalog order. */
    fun orderedStocks(): List<Stock> =
        Stock.ALL.filter { it.id in favorites } + Stock.ALL.filter { it.id !in favorites }
}

/** The phone's reachable addresses, refreshed every 2 seconds while on screen. */
@Composable
fun rememberAddresses(): State<List<LocalAddress>> = produceState(initialValue = Network.addresses()) {
    while (true) {
        value = withContext(Dispatchers.IO) { Network.addresses() }
        delay(2000)
    }
}

@Composable
fun OnChange(key: Any?, block: suspend () -> Unit) {
    LaunchedEffect(key) { block() }
}
