package com.bromide.app.ui

import androidx.compose.ui.platform.LocalResources
import com.bromide.app.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bromide.app.BatchKind
import com.bromide.app.Developing
import com.bromide.app.PhoneSize
import com.bromide.app.ui.PhoneLab.Companion.FOLDER

/**
 * Develop photos taken with this phone: pick them from the library, choose a film
 * (shown on your own photo), develop. Separate from the cameras on purpose: its own
 * film, its own folder, and the camera session is never touched.
 */
@Composable
fun PhonePhotosScreen(app: AppState, nav: (Route) -> Unit, onBack: () -> Unit) {
    val lab = app.phone
    val context = LocalContext.current
    val batch by lab.batch.collectAsStateWithLifecycle()
    // The system photo picker: no permission needed, the person picks exactly what Bromide sees.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { lab.pick(it) }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    // Developing runs in the background with a progress notification: ask to show it, then go either way.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { lab.develop() }
    val develop = {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else lab.develop()
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(stringResource(R.string.phone_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (lab.photos.isNotEmpty() && !batch.running) TextButton(onClick = pick) { Text(stringResource(R.string.phone_choose_others)) }
        }

        val photos = lab.photos
        if (photos.isEmpty()) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    stringResource(R.string.phone_intro),
                    color = FB.Muted
                )
                VSpace(20.dp)
                Button(shape = ButtonShape, colors = inkButton(), onClick = pick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.phone_choose))
                }
                VSpace(10.dp)
                Text(stringResource(R.string.phone_where, FOLDER),
                     color = FB.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        val photo = photos[lab.selected.coerceIn(0, photos.lastIndex)]
        val stock = lab.stock
        val looksVersion = app.looksVersion  // re-read looks when one is edited
        val look = remember(stock.id, looksVersion) { app.look(stock) }
        var compare by remember { mutableStateOf(false) }

        // The selected photo, with the film on it
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))) {
            Zoomable(Modifier.fillMaxSize()) {
                FilmPreview(stock, look, app.previewSource, 1080, Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit, original = compare, photo = photo.uri)
            }
            Text(
                stringResource(if (compare) R.string.film_original else R.string.film_hold_compare),
                style = MaterialTheme.typography.labelSmall,
                color = if (compare) FB.Background else FB.Paper,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (compare) FB.Paper else FB.Background.copy(alpha = 0.6f))
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            compare = true
                            tryAwaitRelease()
                            compare = false
                        })
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        Text(
            "${photo.name} · " + pluralStringResource(R.plurals.phone_picked, photos.size, photos.size),
            style = MaterialTheme.typography.bodySmall, color = FB.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp)
        )
        if (photos.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(photos, key = { _, p -> p.uri.toString() }) { i, p ->
                    val shape = RoundedCornerShape(9.dp)
                    UriImage(
                        p.uri, 200,
                        Modifier.size(64.dp).clip(shape)
                            .then(if (i == lab.selected) Modifier.border(2.dp, FB.Accent, shape) else Modifier)
                            .clickable { lab.selected = i }
                    )
                }
            }
        }

        // Film: the phone's own pick, previewed on the photo above
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.camera_film), Modifier.weight(1f))
            Text(stringResource(R.string.phone_film_separate), style = MaterialTheme.typography.labelMedium, color = FB.Muted,
                 modifier = Modifier.padding(end = 12.dp))
        }
        VSpace(8.dp)
        PhoneFilmCarousel(app, lab, photo, nav)
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stock.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(look.summary(stock, LocalResources.current), style = MaterialTheme.typography.labelMedium, color = FB.Muted,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                shape = ButtonShape,
                onClick = { nav(Route.Film(stock.id, forPhone = true)) },
                border = BorderStroke(1.dp, FB.Accent),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Icon(FbIcons.Tune, contentDescription = null, tint = FB.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.adjust), color = FB.Accent)
            }
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            VSpace(18.dp)
            SectionTitle(stringResource(R.string.look_size))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                PhoneSize.entries.forEach { s ->
                    FilterChip(selected = lab.size == s, onClick = { lab.chooseSize(s) }, enabled = !batch.running,
                               label = { Text(stringResource(s.label)) })
                }
            }
            Text(
                stringResource(when (lab.size) {
                    PhoneSize.Full -> R.string.phone_size_full
                    PhoneSize.Half -> R.string.phone_size_half
                    PhoneSize.Share -> R.string.phone_size_share
                }, FOLDER),
                color = FB.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)
            )

            VSpace(20.dp)
            // One batch at a time: a memory card being developed holds phone photos back.
            val cardBusy = Developing.state.collectAsStateWithLifecycle().value.let { it.running && it.kind == BatchKind.Card }
            if (!batch.running) {
                if (cardBusy) {
                    Text(stringResource(R.string.batch_busy_card), color = FB.Muted, style = MaterialTheme.typography.bodyMedium)
                    VSpace(8.dp)
                }
                Button(shape = ButtonShape, colors = inkButton(), onClick = develop, enabled = !cardBusy, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Text(if (photos.size == 1) stringResource(R.string.phone_develop_one) else pluralStringResource(R.plurals.develop_n, photos.size, photos.size))
                }
            } else {
                LinearProgressIndicator(
                    progress = { if (batch.total == 0) 0f else batch.done / batch.total.toFloat() },
                    modifier = Modifier.fillMaxWidth(), color = FB.Accent, trackColor = FB.SurfaceHigh
                )
                VSpace(8.dp)
                Text(stringResource(R.string.progress_of, batch.done, batch.total) + (if (batch.current.isNotEmpty()) " · ${batch.current}" else ""),
                     color = FB.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.phone_background),
                     color = FB.Muted, style = MaterialTheme.typography.bodyMedium)
                VSpace(10.dp)
                OutlinedButton(shape = ButtonShape, onClick = { lab.stop() }) { Text(stringResource(R.string.stop), color = FB.Paper) }
            }
        }

        // Results show up as they're developed
        val results = batch.results
        if (results.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.phone_developed, results.size), style = MaterialTheme.typography.titleMedium,
                     color = if (batch.running) FB.Paper else FB.Ok, modifier = Modifier.weight(1f))
                TextButton(onClick = { app.galleryFilter = GalleryFilter.Phone; nav(Route.Gallery) }) { Text(stringResource(R.string.gallery)) }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(results, key = { it.toString() }) { uri ->
                    UriImage(uri, 300, Modifier.size(84.dp).clip(RoundedCornerShape(9.dp))
                        .clickable { nav(Route.Viewer(uri.toString(), GalleryFilter.Phone)) })
                }
            }
        }
        if (batch.errors.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                batch.errors.take(8).forEach {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Every film, rendered on the selected phone photo; tap to choose, tap again to adjust. */
@Composable
private fun PhoneFilmCarousel(app: AppState, lab: PhoneLab, photo: PhonePhoto, nav: (Route) -> Unit) {
    // Fixed order while on screen, as on the home screen: a heart never moves the card away.
    val stocks = remember { app.orderedStocks() }
    val state = rememberLazyListState()
    LaunchedEffect(Unit) {
        val i = stocks.indexOfFirst { it.id == lab.stockId }
        if (i > 0) state.scrollToItem(i)
    }
    val looksVersion = app.looksVersion
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(stocks, key = { it.id }) { s ->
            val selected = s.id == lab.stockId
            FilmCard(
                stock = s,
                look = remember(s.id, looksVersion) { app.look(s) },
                source = app.previewSource,
                selected = selected,
                favorite = s.id in app.favorites,
                width = 132.dp,
                onClick = { if (selected) nav(Route.Film(s.id, forPhone = true)) else lab.select(s) },
                onFavorite = { app.toggleFavorite(s) },
                onAdjust = { nav(Route.Film(s.id, forPhone = true)) },
                photo = photo.uri,
                selectedLabel = stringResource(R.string.card_chosen),
            )
        }
    }
}
