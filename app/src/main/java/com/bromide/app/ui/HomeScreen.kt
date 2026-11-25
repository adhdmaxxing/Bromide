package com.bromide.app.ui

import androidx.compose.ui.platform.LocalResources
import com.bromide.app.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bromide.app.BatchKind
import com.bromide.app.Camera
import com.bromide.app.Developed
import com.bromide.app.DevelopService
import com.bromide.app.Developing
import com.bromide.app.FilmService
import com.bromide.app.Roll
import com.bromide.app.Link
import com.bromide.app.LocalAddress
import com.bromide.app.PhotoWatcher
import com.bromide.app.PreviewSource
import com.bromide.app.Previews
import com.bromide.app.ServiceState
import com.bromide.app.Status
import com.bromide.engine.Stock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(app: AppState, nav: (Route) -> Unit) {
    val context = LocalContext.current
    val status by ServiceState.status.collectAsStateWithLifecycle()
    val addresses by rememberAddresses()
    val settings = app.settings
    val live = app.cameras.filter { it.live }
    val hasFtp = live.any { it.link == Link.Ftp }
    val hasApp = live.any { it.link == Link.App }
    var photoAccess by remember { mutableStateOf(PhotoWatcher.hasPhotoAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { photoAccess = PhotoWatcher.hasPhotoAccess(context) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        photoAccess = PhotoWatcher.hasPhotoAccess(context)
        FilmService.start(context)
    }
    /** Start (or refresh) receiving, asking first for what it needs: notifications, and photos for app cameras. */
    val start = {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        if (hasApp && !photoAccess) perms += PhotoWatcher.photoPermissions + Manifest.permission.ACCESS_MEDIA_LOCATION
        if (perms.isEmpty()) FilmService.start(context) else permissions.launch(perms.toTypedArray())
    }

    // Open the app ready to go: no Start button needed.
    val canListen = (hasFtp && addresses.isNotEmpty()) || (hasApp && photoAccess)
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(canListen, status.running) {
        if (!autoStarted && settings.setupDone && settings.autoStart && canListen && !status.running) {
            autoStarted = true
            start()
        }
    }
    LaunchedEffect(status.developed) { app.reloadCameras() }

    val roll by Roll.state.collectAsStateWithLifecycle()
    val batch by Developing.state.collectAsStateWithLifecycle()
    // Unlocked: develop the photos kept while the free roll was finished.
    LaunchedEffect(roll.unlocked, roll.latent, batch.running) {
        if (roll.unlocked && roll.latent > 0 && !batch.running) {
            DevelopService.start(context, BatchKind.Latent, Roll.latentBatch(settings), settings.outputSize)
        }
    }
    val phoneBatch by app.phone.batch.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Wordmark(Modifier.weight(1f))
            IconButton(onClick = { nav(Route.Gallery) }) {
                Icon(FbIcons.Gallery, contentDescription = stringResource(R.string.gallery), tint = FB.Paper)
            }
            IconButton(onClick = { nav(Route.Settings) }) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings), tint = FB.Muted)
            }
        }

        // What you came for, top to bottom: is the camera on, what just came in, which film is loaded.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            ConnectionCard(
                status = status,
                addresses = addresses,
                cameras = app.cameras,
                stock = app.stock,
                cameraHost = settings.cameraHost,
                photoAccess = photoAccess,
                onStart = start,
                onStop = { FilmService.stop(context) },
                nav = nav,
            )

            RollCounter(roll)

            if (status.recent.isNotEmpty()) {
                LatestPhotos(status.recent, showCamera = live.size > 1,
                             onOpen = { i -> nav(Route.Viewer(status.recent[i].uri.toString(), GalleryFilter.Camera)) },
                             onAll = { nav(Route.Gallery) })
            }

            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.camera_film), Modifier.weight(1f))
                TextButton(onClick = { nav(Route.Library) }) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = FB.Accent)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.home_all_films, Stock.ALL.size), color = FB.Accent)
                }
            }
            FilmCarousel(app, onOpen = { nav(Route.Film(it.id)) })
            VSpace(10.dp)
            PreviewSourceChips(app)
        }

        Dock(
            phoneRunning = phoneBatch.running,
            onPhone = { nav(Route.PhonePhotos) },
            onAdjust = { nav(Route.Film(app.stockId)) },
        )
    }
}

/** The two everyday actions, always in reach: phone photos, and the loaded film's settings. */
@Composable
private fun Dock(phoneRunning: Boolean, onPhone: () -> Unit, onAdjust: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(FB.Background).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onPhone,
            shape = ButtonShape,
            modifier = Modifier.weight(1f).height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (FB.Dark) FB.SurfaceHigh else FB.Surface,
                contentColor = if (phoneRunning) FB.Accent else FB.Paper
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = if (FB.Dark) 0.dp else 2.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Icon(FbIcons.Phone, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (phoneRunning) R.string.developing else R.string.phone_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(
            onClick = onAdjust,
            shape = ButtonShape,
            colors = inkButton(),
            modifier = Modifier.weight(1f).height(50.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Icon(FbIcons.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.home_adjust_film), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------------------------
// Connection status: no camera / hotspot off / ready / waiting / connected / developing
// ---------------------------------------------------------------------------------

@Composable
private fun ConnectionCard(
    status: Status,
    addresses: List<LocalAddress>,
    cameras: List<Camera>,
    stock: Stock,
    cameraHost: String?,
    photoAccess: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    nav: (Route) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val live = cameras.filter { it.live }
    val hasFtp = live.any { it.link == Link.Ftp }
    val hasApp = live.any { it.link == Link.App }
    val hotspotOff = addresses.isEmpty()
    // Only FTP cameras need the hotspot; with only those, nothing works without it.
    val blockedByHotspot = hasFtp && !hasApp && hotspotOff
    val current = addresses.firstOrNull()?.ip
    val hostChanged = hasFtp && cameraHost != null && current != null && cameraHost != current && !status.cameraSeen
    val needsAccess = hasApp && (!photoAccess || status.needsPhotoAccess)

    // Setting up or blocked: a full card with its one action. Ready or running: one compact status
    // line (tap it for your cameras), with notices under it only when something needs you.
    val compact = live.isNotEmpty() && !blockedByHotspot
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = if (blockedByHotspot) FB.HalationDark else FB.Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (FB.Dark) 0.dp else 1.dp),
        shape = CardShape
    ) {
        Column(if (compact) Modifier.clickable { nav(Route.Cameras) }.padding(horizontal = 16.dp, vertical = 12.dp) else Modifier.padding(20.dp)) {
            when {
                live.isEmpty() && cameras.isNotEmpty() -> {
                    Text(stringResource(R.string.home_card_title), style = MaterialTheme.typography.titleLarge)
                    VSpace(6.dp)
                    Text(stringResource(R.string.home_card_text), color = FB.Muted)
                    VSpace(16.dp)
                    Button(shape = ButtonShape, colors = inkButton(), onClick = { nav(Route.FromCard) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(stringResource(R.string.develop_from_card))
                    }
                    TextButton(onClick = { nav(Route.Onboarding()) }) { Text(stringResource(R.string.home_add_wifi_camera)) }
                }

                live.isEmpty() -> {
                    Text(stringResource(R.string.home_setup_title), style = MaterialTheme.typography.titleLarge)
                    VSpace(6.dp)
                    Text(stringResource(R.string.home_setup_text), color = FB.Muted)
                    VSpace(16.dp)
                    Button(shape = ButtonShape, colors = inkButton(), onClick = { nav(Route.Onboarding()) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(stringResource(R.string.home_setup_button))
                    }
                }

                blockedByHotspot -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(FbIcons.Hotspot, contentDescription = null, tint = FB.Halation, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.hotspot_off), style = MaterialTheme.typography.titleLarge)
                    }
                    VSpace(6.dp)
                    Text(
                        stringResource(if (status.running) R.string.home_hotspot_text_running else R.string.home_hotspot_text),
                        color = FB.Paper.copy(alpha = 0.8f)
                    )
                    VSpace(16.dp)
                    Button(
                        shape = ButtonShape,
                        onClick = { openHotspotSettings(context) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FB.Halation, contentColor = FB.OnPhoto)
                    ) { Text(stringResource(R.string.home_turn_on_hotspot)) }
                    TextButton(onClick = { nav(Route.Cameras) }) { Text(stringResource(R.string.my_cameras)) }
                }

                !status.running -> {
                    StatusLine(
                        dot = FB.Muted, pulsing = false,
                        title = stringResource(R.string.home_ready),
                        lines = listOf(stringResource(R.string.home_tap_start)) + cameraLines(live, stock),
                    ) {
                        Button(shape = ButtonShape, colors = inkButton(), onClick = onStart, contentPadding = PaddingValues(horizontal = 22.dp)) {
                            Text(stringResource(R.string.start))
                        }
                    }
                    if (needsAccess) {
                        VSpace(8.dp)
                        Text(stringResource(R.string.home_will_ask_photos),
                             color = FB.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    status.error?.let { VSpace(8.dp); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }

                else -> {
                    val seen = status.cameraSeen || status.developed > 0
                    StatusLine(
                        dot = if (seen) FB.Ok else FB.Accent,
                        pulsing = !seen || status.queued > 0,
                        title = when {
                            status.queued > 0 -> stringResource(R.string.developing)
                            status.cameraSeen -> stringResource(R.string.home_connected)
                            else -> stringResource(R.string.home_waiting)
                        },
                        lines = listOf(
                            when {
                                status.developed > 0 -> stringResource(R.string.home_developed_session, status.developed) +
                                    if (status.queued > 0) " · " + stringResource(R.string.home_in_queue, status.queued) else ""
                                status.cameraSeen -> stringResource(R.string.home_take_picture)
                                hasFtp && current != null -> stringResource(R.string.home_camera_host, current, status.port)
                                else -> stringResource(R.string.home_take_picture_app)
                            }
                        ) + cameraLines(live, stock),
                    ) {
                        TextButton(onClick = onStop) { Text(stringResource(R.string.stop), color = FB.Accent) }
                    }
                    if (status.queued > 0) {
                        VSpace(10.dp)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape), color = FB.Accent, trackColor = FB.SurfaceHigh)
                    }
                    if (hasFtp && hotspotOff) {
                        Notice(stringResource(R.string.hotspot_off), stringResource(R.string.home_hotspot_notice)) {
                            TextButton(onClick = { openHotspotSettings(context) }) { Text(stringResource(R.string.turn_on), color = FB.Accent) }
                        }
                    }
                    if (hostChanged) {
                        Notice(stringResource(R.string.home_host_changed), stringResource(R.string.home_host_changed_text, current ?: "", cameraHost ?: "")) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(current ?: "")) }) { Text(stringResource(R.string.copy), color = FB.Accent) }
                        }
                    }
                    if (needsAccess && PhotoWatcher.hasPartialAccess(context)) {
                        Notice(stringResource(R.string.home_partial_access), stringResource(R.string.home_partial_access_text)) {
                            TextButton(onClick = { openAppSettings(context) }) { Text(stringResource(R.string.settings), color = FB.Accent) }
                        }
                    } else if (needsAccess) {
                        Notice(stringResource(R.string.home_no_photo_access), stringResource(R.string.home_no_photo_access_text)) {
                            TextButton(onClick = onStart) { Text(stringResource(R.string.allow), color = FB.Accent) }
                        }
                    }
                    status.error?.let {
                        VSpace(8.dp)
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** The compact status: a light, what's happening, the details under it, and one action on the right. */
@Composable
private fun StatusLine(dot: Color, pulsing: Boolean, title: String, lines: List<String>, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulseDot(dot, pulsing = pulsing, size = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp))
            lines.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = FB.Muted,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        action()
    }
}

/** One line per camera: name, how it sends, its film. */
@Composable
private fun cameraLines(cameras: List<Camera>, stock: Stock): List<String> {
    val res = LocalResources.current
    return cameras.map { cam ->
        val via = if (cam.link == Link.Ftp) "FTP" else cam.brand.appShort(res)
        val film = cam.film?.let { Stock.byId(it)?.name } ?: stock.name
        "${cam.name} · $via · $film"
    }
}

@Composable
private fun Notice(title: String, text: String, action: @Composable () -> Unit) {
    VSpace(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FB.AccentDark).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = FB.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = FB.Paper.copy(alpha = 0.85f))
        }
        action()
    }
}

// ---------------------------------------------------------------------------------
// Latest photos: big hero of the newest + a strip that jumps to new arrivals
// ---------------------------------------------------------------------------------

@Composable
private fun LatestPhotos(items: List<Developed>, showCamera: Boolean, onOpen: (Int) -> Unit, onAll: () -> Unit) {
    val newest = items.first()
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(stringResource(R.string.home_just_developed), Modifier.weight(1f))
        TextButton(onClick = onAll) { Text(stringResource(R.string.gallery), color = FB.Accent) }
    }
    AnimatedContent(
        targetState = newest,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "hero"
    ) { d ->
        Column(Modifier.padding(horizontal = 16.dp)) {
            Thumb(d, 1080, Modifier.fillMaxWidth().print(border = 8.dp, shape = CardShape).aspectRatio(3f / 2f)
                                   .clickable { onOpen(0) }, ContentScale.Crop)
            // The recipe, in edge print: film, print exposure, what it was developed from.
            Text(
                ((if (showCamera && d.cameraName != null) "${d.cameraName} · " else "") +
                    "${d.stockName} · " + stringResource(R.string.value_ev, d.printEv) + " · " +
                    stringResource(if (d.fromRaw) R.string.from_raw else R.string.from_jpeg)).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = FB.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp, start = 4.dp)
            )
        }
    }
    if (items.size > 1) {
        val state = rememberLazyListState()
        LaunchedEffect(newest.uri) { state.animateScrollToItem(0) }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items.size, key = { items[it].uri.toString() }) { i ->
                Thumb(items[i], 300, Modifier.size(68.dp).print(border = 3.dp, shape = SmallShape).clickable { onOpen(i) }, ContentScale.Crop)
            }
        }
    }
}

@Composable
fun Thumb(d: Developed, size: Int, modifier: Modifier, scale: ContentScale) {
    val context = LocalContext.current
    val img by produceState<ImageBitmap?>(null, d.uri, d.version, size) {
        value = withContext(Dispatchers.IO) { loadImage(context, d.uri, size, fill = scale == ContentScale.Crop, version = d.version.toLong()) }
    }
    Box(modifier.background(FB.Surface)) {
        img?.let { Image(it, contentDescription = d.name, contentScale = scale, modifier = Modifier.fillMaxSize()) }
    }
}

// ---------------------------------------------------------------------------------
// Film carousel
// ---------------------------------------------------------------------------------

@Composable
private fun FilmCarousel(app: AppState, onOpen: (Stock) -> Unit) {
    // Favorites come first, but the order is fixed while you're on this screen: tapping a
    // heart never moves the card out from under your finger. It's re-sorted next time.
    val stocks = remember { app.orderedStocks() }
    val state = rememberLazyListState()
    LaunchedEffect(Unit) {
        val i = stocks.indexOfFirst { it.id == app.stockId }
        if (i > 0) state.scrollToItem(i)
    }
    val looksVersion = app.looksVersion  // re-read looks when one is edited
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(stocks, key = { it.id }) { stock ->
            val selected = stock.id == app.stockId
            FilmCard(
                stock = stock,
                look = remember(stock.id, looksVersion) { app.look(stock) },
                source = app.previewSource,
                selected = selected,
                favorite = stock.id in app.favorites,
                width = 156.dp,
                onClick = { if (selected) onOpen(stock) else app.select(stock) },
                onFavorite = { app.toggleFavorite(stock) },
                onAdjust = { onOpen(stock) },
            )
        }
    }
}

@Composable
fun PreviewSourceChips(app: AppState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by ServiceState.status.collectAsStateWithLifecycle()
    val hasLast = remember(status.lastPhotoVersion) { Previews.hasLastPhoto(context) }
    Row(
        modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.home_preview_on), style = MaterialTheme.typography.bodyMedium, color = FB.Muted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(PreviewSource.entries.filter { it != PreviewSource.LastPhoto || hasLast }) { s ->
                FilterChip(
                    selected = app.previewSource == s,
                    onClick = { app.setPreview(s) },
                    label = { Text(stringResource(s.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FB.SurfaceHigh,
                        selectedLabelColor = FB.Accent
                    )
                )
            }
        }
    }
}
