package com.bromide.app.ui

import androidx.compose.ui.platform.LocalResources
import com.bromide.app.categoryRes
import com.bromide.app.blurbRes
import com.bromide.app.R
import androidx.annotation.StringRes
import android.content.res.Resources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.unit.dp
import com.bromide.app.Look
import com.bromide.engine.Stock
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One film: big live preview, its settings, favorite, "Use this film". With [forPhone], it's
 * previewed on the phone photo being developed, and the button picks it for phone photos only.
 */
@Composable
fun FilmScreen(app: AppState, stockId: String, forPhone: Boolean, onBack: () -> Unit) {
    val stock = Stock.byId(stockId) ?: Stock.defaultStock()
    var look by remember(stockId) { mutableStateOf(app.look(stock)) }
    // the preview follows the sliders with a short delay, so dragging stays smooth
    var shownLook by remember(stockId) { mutableStateOf(look) }
    LaunchedEffect(look) {
        delay(120)
        shownLook = look
        app.setLook(stock, look)
    }
    var compare by remember { mutableStateOf(false) }
    val favorite = stock.id in app.favorites
    val inUse = stock.id == (if (forPhone) app.phone.stockId else app.stockId)
    val photo = if (forPhone) app.phone.photos.getOrNull(app.phone.selected)?.uri else null
    val res = LocalResources.current

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(stock.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { app.toggleFavorite(stock) }) {
                Icon(
                    if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = stringResource(R.string.favorite), tint = if (favorite) FB.Accent else FB.OnPhoto
                )
            }
        }

        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp))) {
            Zoomable(Modifier.fillMaxSize()) {
                FilmPreview(stock, shownLook, app.previewSource, 1080, Modifier.fillMaxSize(),
                            contentScale = if (photo != null) ContentScale.Fit else ContentScale.Crop,
                            original = compare, photo = photo)
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
            stringResource(R.string.film_zoom_hint),
            style = MaterialTheme.typography.bodySmall, color = FB.Muted,
            modifier = Modifier.padding(start = 20.dp, top = 6.dp)
        )
        if (photo == null) {
            VSpace(8.dp)
            PreviewSourceChips(app)
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("${stock.iso} · ${stringResource(categoryRes(stock.category())).uppercase()}", style = MaterialTheme.typography.labelMedium, color = FB.Accent)
            VSpace(6.dp)
            Text(stringResource(stock.blurbRes()), style = MaterialTheme.typography.bodyLarge)
            VSpace(4.dp)
            Text(
                stringResource(if (stock.mono) R.string.film_about_bw else R.string.film_about_color),
                style = MaterialTheme.typography.bodyMedium, color = FB.Muted
            )
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (!look.isDefault) TextButton(onClick = { look = Look() }) { Text(stringResource(R.string.film_reset_all)) }
            }
            Text(
                stringResource(if (forPhone) R.string.film_saved_phone else R.string.film_saved),
                style = MaterialTheme.typography.bodyMedium, color = FB.Muted
            )
            val d = Look()

            Group(stringResource(R.string.look_grain))
            LookSlider(stringResource(R.string.look_amount), look.grain, d.grain, 0f..2f, times(res, look.grain)) {
                look = look.copy(grain = it)
            }
            LookSlider(stringResource(R.string.look_size), look.grainSize, d.grainSize, 0.5f..2f, sizeLabel(res, look.grainSize)) {
                look = look.copy(grainSize = it)
            }

            Group(stringResource(R.string.look_light))
            LookSlider(stringResource(R.string.look_exposure), look.ev, d.ev, -2f..2f, res.getString(R.string.value_ev, look.ev), steps = 11) {
                look = look.copy(ev = (it * 3).roundToInt() / 3f)
            }
            LookSlider(stringResource(R.string.look_halation), look.halation, d.halation, 0f..3f, times(res, look.halation)) {
                look = look.copy(halation = it)
            }
            Hint(stringResource(R.string.look_halation_hint))

            Group(stringResource(R.string.look_color_group))
            if (stock.mono) {
                LookSlider(stringResource(R.string.look_filter), look.warmth, d.warmth, -1f..1f, signed(res, look.warmth, R.string.look_orange, R.string.look_blue)) {
                    look = look.copy(warmth = snap(it))
                }
                Hint(stringResource(R.string.look_filter_hint))
            } else {
                LookSlider(stringResource(R.string.look_color), look.color, d.color, 0f..2f, res.getString(R.string.value_times, look.color)) {
                    look = look.copy(color = it)
                }
                LookSlider(stringResource(R.string.look_warmth), look.warmth, d.warmth, -1f..1f, signed(res, look.warmth, R.string.look_warm, R.string.look_cool)) {
                    look = look.copy(warmth = snap(it))
                }
                LookSlider(stringResource(R.string.look_tint), look.tint, d.tint, -1f..1f, signed(res, look.tint, R.string.look_magenta, R.string.look_green)) {
                    look = look.copy(tint = snap(it))
                }
            }

            Group(stringResource(R.string.look_lab))
            LookSlider(stringResource(R.string.look_auto_print), look.lab, d.lab, 0f..1f,
                       if (look.lab < 0.05f) res.getString(R.string.value_off) else res.getString(R.string.value_percent, look.lab * 100)) {
                look = look.copy(lab = it)
            }
            Hint(stringResource(R.string.look_lab_hint))
        }

        VSpace(24.dp)
        Button(
            shape = ButtonShape,
            colors = inkButton(),
            onClick = { if (forPhone) app.phone.select(stock) else app.select(stock); onBack() },
            enabled = !inUse,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp)
        ) {
            Text(stringResource(when {
                forPhone && inUse -> R.string.film_chosen_phone
                forPhone -> R.string.film_use_phone
                inUse -> R.string.film_loaded
                else -> R.string.film_use
            }))
        }
    }
}

/** One line describing a film's settings, e.g. "GRAIN 1.4× · WARM +0.30 · +0.7 EV". */
fun Look.summary(stock: Stock, res: Resources): String {
    val d = Look()
    val parts = mutableListOf<String>()
    if (grain != d.grain) parts += if (grain < 0.05f) res.getString(R.string.summary_no_grain) else res.getString(R.string.summary_grain, grain)
    if (grainSize != d.grainSize) parts += res.getString(R.string.summary_grain_size, sizeLabel(res, grainSize))
    if (ev != d.ev) parts += res.getString(R.string.value_ev, ev)
    if (halation != d.halation) {
        parts += if (halation < 0.05f) res.getString(R.string.summary_no_halation) else res.getString(R.string.summary_halation, halation)
    }
    if (color != d.color) parts += res.getString(R.string.summary_color, color)
    if (warmth != d.warmth) {
        parts += if (stock.mono) signed(res, warmth, R.string.look_orange, R.string.look_blue)
                 else signed(res, warmth, R.string.look_warm, R.string.look_cool)
    }
    if (tint != d.tint) parts += signed(res, tint, R.string.look_magenta, R.string.look_green)
    if (lab != d.lab) parts += if (lab < 0.05f) res.getString(R.string.summary_lab_off) else res.getString(R.string.summary_lab, lab * 100)
    val line = if (parts.isEmpty()) res.getString(R.string.summary_stock, stock.iso) else parts.joinToString(" · ")
    return line.uppercase()
}

/** "1.4×", or "off" near zero. */
private fun times(res: Resources, v: Float) =
    if (v < 0.05f) res.getString(R.string.value_off) else res.getString(R.string.value_times, v)

private fun sizeLabel(res: Resources, v: Float) = when {
    abs(v - 1f) < 0.05f -> res.getString(R.string.grain_stock)
    v < 1f -> res.getString(R.string.grain_fine, v)
    else -> res.getString(R.string.grain_coarse, v)
}

private fun signed(res: Resources, v: Float, @StringRes plus: Int, @StringRes minus: Int) = when {
    abs(v) < 0.025f -> res.getString(R.string.look_neutral)
    else -> res.getString(R.string.value_signed, res.getString(if (v > 0) plus else minus), v)
}

/** Snap to 0 near the middle, so neutral is easy to hit. */
private fun snap(v: Float) = if (abs(v) < 0.04f) 0f else (v * 20).roundToInt() / 20f

@Composable
private fun Group(title: String) {
    VSpace(18.dp)
    SectionTitle(title)
    VSpace(2.dp)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = FB.Muted, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun LookSlider(
    label: String, value: Float, default: Float, range: ClosedFloatingPointRange<Float>, display: String,
    steps: Int = 0, onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            val changed = value != default
            Text(
                display.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = if (changed) FB.Accent else FB.Muted,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = changed) { onChange(default) }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            modifier = Modifier.height(32.dp),
            colors = SliderDefaults.colors(
                thumbColor = FB.Paper, activeTrackColor = FB.Accent, inactiveTrackColor = FB.Line,
                activeTickColor = FB.Background, inactiveTickColor = FB.Muted
            )
        )
    }
}
