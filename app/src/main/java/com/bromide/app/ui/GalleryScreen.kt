package com.bromide.app.ui

import com.bromide.app.R
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bromide.app.Developer
import com.bromide.app.Developing
import com.bromide.app.ServiceState
import com.bromide.engine.Stock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class GalleryItem(val uri: Uri, val name: String, val date: Long, val modified: Long = 0, val path: String = "") {
    /** Taken with this phone, not a camera. */
    val fromPhone: Boolean get() = path.trimEnd('/').equals(PhoneLab.FOLDER, ignoreCase = true)
}

/** Camera photos and phone photos, shown together or apart. */
enum class GalleryFilter(@StringRes val label: Int) {
    All(R.string.filter_all),
    Camera(R.string.filter_camera),
    Phone(R.string.filter_phone);

    fun keep(item: GalleryItem) = when (this) {
        All -> true
        Camera -> !item.fromPhone
        Phone -> item.fromPhone
    }
}

/** Bromide photos in Pictures/Bromide, newest first. */
fun loadGallery(context: Context): List<GalleryItem> {
    val out = mutableListOf<GalleryItem>()
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                             MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_MODIFIED,
                             MediaStore.Images.Media.RELATIVE_PATH)
    try {
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/Bromide%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val date = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modified = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val path = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                out += GalleryItem(ContentUris.withAppendedId(collection, c.getLong(id)), c.getString(name), c.getLong(date) * 1000,
                                 c.getLong(modified), c.getString(path) ?: "")
            }
        }
    } catch (_: Exception) {
    }
    return out
}

@Composable
fun GalleryScreen(app: AppState, nav: (Route) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val status by ServiceState.status.collectAsStateWithLifecycle()
    val phoneBatch by Developing.state.collectAsStateWithLifecycle()  // phone or card: new photos to show
    val all by produceState(emptyList<GalleryItem>(), status.developed, phoneBatch.results) {
        value = withContext(Dispatchers.IO) { loadGallery(context) }
    }
    // Filters only show once there are phone photos; until then everything is a camera photo.
    val hasPhone = all.any { it.fromPhone }
    val filter = if (hasPhone) app.galleryFilter else GalleryFilter.All
    val items = all.filter { filter.keep(it) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.gallery), style = MaterialTheme.typography.titleLarge)
                Text(pluralStringResource(R.plurals.gallery_count, items.size, items.size), color = FB.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            if (filter == GalleryFilter.Phone) {
                TextButton(onClick = { nav(Route.PhonePhotos) }, modifier = Modifier.padding(end = 8.dp)) {
                    Text(stringResource(R.string.gallery_develop_phone))
                }
            } else {
                TextButton(onClick = { nav(Route.FromCard) }, modifier = Modifier.padding(end = 8.dp)) {
                    Text(stringResource(R.string.develop_from_card))
                }
            }
        }
        if (hasPhone) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { app.galleryFilter = f },
                        label = { Text(stringResource(f.label)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = FB.SurfaceHigh, selectedLabelColor = FB.Accent)
                    )
                }
            }
        }
        if (items.isEmpty()) {
            Text(
                stringResource(if (filter == GalleryFilter.Phone) R.string.gallery_empty_phone else R.string.gallery_empty),
                color = FB.Muted, modifier = Modifier.padding(24.dp)
            )
        }
        // cells are at least 110 dp; decode for a bit more so they stay sharp on wide screens
        val thumbPx = with(LocalDensity.current) { 160.dp.roundToPx() }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items, key = { it.uri.toString() }) { item ->
                UriImage(item.uri, thumbPx, version = item.modified, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { nav(Route.Viewer(item.uri.toString(), filter)) })
            }
        }
    }
}

@Composable
fun UriImage(uri: Uri, size: Int, modifier: Modifier, scale: ContentScale = ContentScale.Crop, background: Color = FB.Surface,
             version: Long = 0) {
    val context = LocalContext.current
    val img by produceState<ImageBitmap?>(null, uri, size, version) {
        value = withContext(Dispatchers.IO) {
            loadImage(context, uri, size, fill = scale == ContentScale.Crop, version = version)
        }
    }
    Box(modifier.background(background)) {
        img?.let { Image(it, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize()) }
    }
}

private val thumbCache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/**
 * Decodes the photo itself at about [size] px: the short edge when it will be cropped to
 * fill a square ([fill]), else the long edge. MediaStore's loadThumbnail looks soft because
 * it hands back its own small cached thumbnail whatever size is asked. Bump [version] when
 * the file at [uri] is rewritten. Blocking.
 */
fun loadImage(context: Context, uri: Uri, size: Int, fill: Boolean, version: Long = 0): ImageBitmap? {
    val key = "$uri|$version|$size|$fill"
    val cacheable = size <= 1024
    if (cacheable) thumbCache.get(key)?.let { return it }
    return try {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val s = size / (if (fill) min(w, h) else max(w, h)).toFloat()
            if (s < 1f) decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
        }.asImageBitmap().also { if (cacheable) thumbCache.put(key, it) }
    } catch (_: Exception) {
        null
    }
}

/** Full-screen, swipeable viewer with share and delete. */
@Composable
fun ViewerScreen(startUri: String, filter: GalleryFilter, onBack: () -> Unit) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val items by produceState<List<GalleryItem>?>(null, reload) {
        value = withContext(Dispatchers.IO) { loadGallery(context).filter { filter.keep(it) } }
    }
    val list = items
    if (list == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val start = list.indexOfFirst { it.uri.toString() == startUri }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { list.size }
    var pendingDelete by remember { mutableStateOf<Uri?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) reload++
        pendingDelete = null
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            // pinch or double-tap to zoom; a sharper copy is decoded once you zoom in
            var zoomed by remember { mutableStateOf(false) }
            Zoomable(Modifier.fillMaxSize(), maxScale = 6f, onZoomed = { zoomed = it }) {
                UriImage(list[page].uri, if (zoomed) 4096 else 2400, Modifier.fillMaxSize(), ContentScale.Fit, Color.Black,
                         list[page].modified)
            }
        }
        val current = list.getOrNull(pager.currentPage)
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White) }
            Column(Modifier.weight(1f)) {
                if (current != null) {
                    val info by produceState("", current.uri) { value = withContext(Dispatchers.IO) { describe(context, current) } }
                    Text(info, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
            IconButton(onClick = { current?.let { share(context, it.uri) } }) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share), tint = Color.White)
            }
            IconButton(onClick = {
                current?.let { item ->
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val req = MediaStore.createDeleteRequest(context.contentResolver, listOf(item.uri))
                            pendingDelete = item.uri
                            deleteLauncher.launch(IntentSenderRequest.Builder(req.intentSender).build())
                        } else {
                            context.contentResolver.delete(item.uri, null, null)
                            reload++
                        }
                    } catch (e: RecoverableSecurityException) {
                        deleteLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                    } catch (_: Exception) {
                    }
                }
            }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete), tint = Color.White) }
        }
    }
}

private fun describe(context: Context, item: GalleryItem): String {
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.date))
    return try {
        context.contentResolver.openInputStream(item.uri)?.use { input ->
            val desc = ExifInterface(input).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
            if (desc != null && desc.startsWith(Developer.RECIPE_TAG)) {
                val r = JSONObject(desc.removePrefix(Developer.RECIPE_TAG))
                val name = Stock.byId(r.optString("stock"))?.name ?: r.optString("stock")
                "$name · %+.1f EV\n$date".format(r.optDouble("print_ev", 0.0))
            } else date
        } ?: date
    } catch (_: Exception) {
        date
    }
}

private fun share(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_photo)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
