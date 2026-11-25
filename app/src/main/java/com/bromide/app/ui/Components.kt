package com.bromide.app.ui

import com.bromide.app.categoryRes
import com.bromide.app.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bromide.app.Look
import com.bromide.app.PreviewSource
import com.bromide.app.Previews
import com.bromide.app.ServiceState
import com.bromide.engine.Stock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A film's look rendered on the chosen preview image, or on [photo] from the phone's library. */
@Composable
fun FilmPreview(
    stock: Stock,
    look: Look,
    source: PreviewSource,
    longEdge: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /** Show the picture without the film, to compare. */
    original: Boolean = false,
    photo: Uri? = null,
) {
    val context = LocalContext.current
    val version by ServiceState.status.collectAsStateWithLifecycle()
    val v = if (source == PreviewSource.LastPhoto) version.lastPhotoVersion else 0
    val bitmap by produceState<Bitmap?>(null, stock.id, look, source, longEdge, v, original, photo) {
        value = withContext(Dispatchers.Default) {
            when {
                photo != null && original -> Previews.originalPhoto(context, photo, longEdge)
                photo != null -> Previews.renderPhoto(context, photo, stock, look, longEdge)
                original -> Previews.original(context, source, longEdge)
                else -> Previews.render(context, stock, look, source, longEdge, v)
            }
        }
    }
    Box(modifier.background(FB.SurfaceHigh)) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = stock.name, contentScale = contentScale,
                  modifier = Modifier.fillMaxSize())
        }
    }
}

/** A film card: preview, name, speed, favorite heart; the loaded film gets an Adjust button. */
@Composable
fun FilmCard(
    stock: Stock,
    look: Look,
    source: PreviewSource,
    selected: Boolean,
    favorite: Boolean,
    width: Dp,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    onAdjust: (() -> Unit)? = null,
    /** Render on this phone photo instead of [source]. */
    photo: Uri? = null,
    /** What the selected card says it is. */
    selectedLabel: String = stringResource(R.string.card_loaded),
) {
    // A print on the light theme (white border, the loaded film's border in red), a rounded frame at night.
    val frame = if (FB.Dark) {
        Modifier.border(BorderStroke(2.dp, if (selected) FB.Accent else Color.Transparent), RoundedCornerShape(12.dp)).padding(3.dp)
    } else {
        Modifier.shadow(2.dp, RoundedCornerShape(3.dp))
            .background(if (selected) FB.Accent else Color.White, RoundedCornerShape(3.dp)).padding(5.dp)
    }
    Column(modifier.width(width).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(if (FB.Dark) 0.dp else 3.dp)) {
        Box(Modifier.fillMaxWidth().height(width * 0.8f).then(frame)) {
            FilmPreview(stock, look, source, 360, Modifier.fillMaxSize().clip(RoundedCornerShape(if (FB.Dark) 9.dp else 1.dp)), photo = photo)
            IconButton(onClick = onFavorite, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(FB.Scrim), contentAlignment = Alignment.Center) {
                    Icon(
                        if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add),
                        tint = if (favorite) FB.Accent else FB.OnPhoto,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (!look.isDefault) {
                EdgeTag(stringResource(R.string.card_tweaked), Modifier.align(Alignment.BottomStart).padding(6.dp))
            }
            if (selected && onAdjust != null) {
                IconButton(onClick = onAdjust, modifier = Modifier.align(Alignment.BottomEnd).size(44.dp)) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(FB.Accent), contentAlignment = Alignment.Center) {
                        Icon(FbIcons.Tune, contentDescription = stringResource(R.string.adjust_film, stock.name), tint = FB.OnAccent,
                             modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Column(Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp, bottom = 4.dp)) {
            Text(stock.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                 color = if (selected) FB.Paper else FB.Paper.copy(alpha = 0.85f))
            Text(
                // The loaded film says so; the others give their speed and kind.
                if (selected) "▸ $selectedLabel" else "${stock.iso} · ${stringResource(categoryRes(stock.category())).uppercase()}",
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) FB.Accent else FB.Muted,
                maxLines = 1
            )
        }
    }
}

/** A small label in edge-print style, on the image. */
@Composable
fun EdgeTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = FB.OnAccent,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(FB.Accent).padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/** "bromide", lower case and glowing with halation, as on the banner. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, size: TextUnit = 30.sp) {
    Text(
        "bromide",
        style = MaterialTheme.typography.headlineMedium.copy(
            fontSize = size, shadow = HalationGlow,
            // ink on prints needs the weight the glow gives it at night
            fontWeight = if (FB.Dark) FontWeight.Normal else FontWeight.Bold, letterSpacing = if (FB.Dark) (-0.04).em else (-0.05).em
        ),
        color = FB.Paper,
        modifier = modifier
    )
}

/** Section heading: small monospace caps, like the edge print on a film strip. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = FB.Muted,
        modifier = modifier
    )
}

/**
 * Pinch to zoom, drag to pan, double-tap to zoom in or back out. At 1x, single-finger drags
 * pass through (so a pager around it still swipes).
 */
@Composable
fun Zoomable(
    modifier: Modifier = Modifier,
    maxScale: Float = 5f,
    /** Called with true once zoomed in past 1.5x, false when back near 1x. */
    onZoomed: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1) / 2f
        val my = box.height * (s - 1) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    LaunchedEffect(scale > 1.5f) { onZoomed(scale > 1.5f) }
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        val s = 2.5f
                        val c = Offset(box.width / 2f, box.height / 2f)
                        offset = clamp((c - tap) * (s - 1), s)
                        scale = s
                    }
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val newScale = (scale * zoom).coerceIn(1f, maxScale)
                            if (centroid != Offset.Unspecified) {
                                // keep the point under the fingers in place while zooming
                                val c = centroid - Offset(box.width / 2f, box.height / 2f)
                                offset = clamp(c - (c - offset) * (newScale / scale) + pan, newScale)
                            }
                            scale = newScale
                            if (scale <= 1.01f) {
                                scale = 1f
                                offset = Offset.Zero
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
    ) { content() }
}

@Composable
fun PulseDot(color: Color, pulsing: Boolean, size: Dp = 12.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha"
    )
    Box(Modifier.size(size).alpha(if (pulsing) a else 1f).clip(CircleShape).background(color))
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = FB.Muted, modifier = Modifier.width(84.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

/** Bromide's page in Android's settings (permissions). */
fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
    }
}

fun openHotspotSettings(context: Context) {
    val intents = listOf(
        Intent("android.settings.TETHER_SETTINGS"),
        Intent(AndroidSettings.ACTION_WIRELESS_SETTINGS),
        Intent(AndroidSettings.ACTION_SETTINGS),
    )
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {
        }
    }
}
