package com.bromide.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bromide.app.BatchKind
import com.bromide.app.DevelopItem
import com.bromide.app.DevelopService
import com.bromide.app.Developing
import com.bromide.app.R
import com.bromide.app.Roll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.bromide.app.Developer
import com.bromide.app.Look
import com.bromide.engine.RawDecoder
import com.bromide.engine.Stock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/** A camera file found on the card. */
private data class CardFile(val uri: Uri, val name: String, val base: String, val raw: Boolean)

/** A film recipe stored in a phone photo's EXIF; its look pins the exact print exposure (lab off). */
private data class Recipe(val stock: Stock, val look: Look)

private enum class FilmChoice { SameAsPhone, OneFilm }

/**
 * Develop the originals on a camera card (plugged in with a USB-C reader) at full
 * resolution, with the same film recipe each photo got on the phone, or one film for all.
 */
@Composable
fun FromCardScreen(app: AppState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Developing runs in DevelopService, so it goes on when you leave this screen or Bromide.
    val anyBatch by Developing.state.collectAsStateWithLifecycle()
    val batch = anyBatch.takeIf { it.kind == BatchKind.Card }
    val busy = anyBatch.running
    val roll by Roll.state.collectAsStateWithLifecycle()

    var folder by remember { mutableStateOf<Uri?>(null) }
    var files by remember { mutableStateOf<List<CardFile>?>(null) }
    var recipes by remember { mutableStateOf<Map<String, Recipe>>(emptyMap()) }
    var scanning by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf(FilmChoice.SameAsPhone) }
    var oneFilm by remember { mutableStateOf(app.stock) }
    var size by remember { mutableIntStateOf(0) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            folder = uri
            scanning = true
            scope.launch {
                val found = withContext(Dispatchers.IO) { scanTree(context, uri) }
                val known = withContext(Dispatchers.IO) { loadRecipes(context) }
                files = found
                recipes = known
                choice = if (found.any { it.base in known }) FilmChoice.SameAsPhone else FilmChoice.OneFilm
                scanning = false
            }
        }
    }

    // the photos to develop: one per shot, RAW preferred
    val shots = remember(files) {
        files.orEmpty().groupBy { it.base }.values.map { group -> group.firstOrNull { it.raw } ?: group.first() }
            .sortedBy { it.name }
    }
    val todo = remember(shots, choice, recipes) {
        if (choice == FilmChoice.SameAsPhone) shots.filter { it.base in recipes } else shots
    }
    val start = {
        // Each photo's film and settings: its recipe from the phone (which pins the exact print
        // exposure), or the chosen film with that film's own settings.
        val items = todo.map { cf ->
            val recipe = if (choice == FilmChoice.SameAsPhone) recipes[cf.base] else null
            val stock = recipe?.stock ?: oneFilm
            // Photos already developed on the phone used their frame then; the others are new.
            DevelopItem(cf.uri, cf.name, stock.id, recipe?.look ?: app.look(stock), newFrame = cf.base !in recipes)
        }
        DevelopService.start(context, BatchKind.Card, items, outputSize = size)
    }
    // Progress shows in a notification: ask to show it, then go either way.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    val develop = {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else start()
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(stringResource(R.string.develop_from_card), style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                stringResource(R.string.card_intro),
                color = FB.Muted
            )
            if (!RawDecoder.available()) {
                VSpace(8.dp)
                Text(stringResource(R.string.card_no_raw), color = MaterialTheme.colorScheme.error)
            }
            VSpace(16.dp)
            OutlinedButton(shape = ButtonShape, onClick = { picker.launch(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(if (folder == null) R.string.card_choose else R.string.card_choose_other), color = FB.Paper)
            }

            if (scanning) {
                VSpace(16.dp)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = FB.Accent, trackColor = FB.SurfaceHigh)
                Text(stringResource(R.string.card_scanning), color = FB.Muted, modifier = Modifier.padding(top = 6.dp))
            }

            val f = files
            if (f != null && !scanning) {
                VSpace(16.dp)
                val raws = shots.count { it.raw }
                Text(pluralStringResource(R.plurals.card_found, shots.size, shots.size, raws, shots.size - raws), style = MaterialTheme.typography.titleMedium)
                VSpace(12.dp)

                SectionTitle(stringResource(R.string.camera_film))
                ChoiceRow(choice == FilmChoice.SameAsPhone, stringResource(R.string.card_same_film),
                          stringResource(R.string.card_same_film_desc, shots.count { it.base in recipes })) {
                    choice = FilmChoice.SameAsPhone
                }
                ChoiceRow(choice == FilmChoice.OneFilm, stringResource(R.string.card_one_film), stringResource(R.string.card_one_film_desc)) {
                    choice = FilmChoice.OneFilm
                }
                if (choice == FilmChoice.OneFilm) {
                    var open by remember { mutableStateOf(false) }
                    Row(Modifier.padding(start = 48.dp)) {
                        TextButton(onClick = { open = true }) { Text(oneFilm.name + "  ▾") }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            app.orderedStocks().forEach { s ->
                                DropdownMenuItem(text = { Text(s.name) }, onClick = { oneFilm = s; open = false })
                            }
                        }
                    }
                }

                VSpace(12.dp)
                SectionTitle(stringResource(R.string.look_size))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf(0 to R.string.size_full_short, 3000 to R.string.size_3000, 2048 to R.string.size_2048).forEach { (v, label) ->
                        FilterChip(selected = size == v, onClick = { size = v }, label = { Text(stringResource(label)) })
                    }
                }
                Text(stringResource(R.string.card_saved_to, "Pictures/Bromide/Full"), color = FB.Muted, style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.padding(top = 6.dp))

                val newShots = todo.count { it.base !in recipes }
                if (!roll.unlocked && newShots > 0) {
                    VSpace(16.dp)
                    FilmStrip(roll.used, Modifier.fillMaxWidth().height(26.dp))
                    Text(pluralStringResource(R.plurals.roll_card_new, newShots, newShots, roll.left),
                         color = if (newShots > roll.left) FB.Accent else FB.Muted, style = MaterialTheme.typography.bodyMedium,
                         modifier = Modifier.padding(top = 8.dp))
                    if (newShots > roll.left) UnlockButton(roll)
                }

                VSpace(20.dp)
                if (!busy) {
                    Button(
                        shape = ButtonShape,
                        colors = inkButton(),
                        onClick = develop,
                        enabled = todo.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(54.dp)
                    ) { Text(if (todo.isEmpty()) stringResource(R.string.card_nothing) else pluralStringResource(R.plurals.develop_n, todo.size, todo.size)) }
                }
            }

            if (busy && batch == null) {
                VSpace(16.dp)
                Text(stringResource(R.string.batch_busy_phone), color = FB.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            if (batch != null && batch.total > 0) {
                VSpace(16.dp)
                if (batch.running) {
                    LinearProgressIndicator(
                        progress = { batch.done / batch.total.toFloat() },
                        modifier = Modifier.fillMaxWidth(), color = FB.Accent, trackColor = FB.SurfaceHigh
                    )
                    VSpace(8.dp)
                    Text(stringResource(R.string.progress_of, batch.done, batch.total) +
                             (if (batch.current.isNotEmpty()) " · ${batch.current}" else ""), color = FB.Muted)
                    Text(stringResource(R.string.phone_background), color = FB.Muted, style = MaterialTheme.typography.bodyMedium)
                    VSpace(10.dp)
                    OutlinedButton(shape = ButtonShape, onClick = { DevelopService.stop(context) }) {
                        Text(stringResource(R.string.stop), color = FB.Paper)
                    }
                } else {
                    Text(pluralStringResource(R.plurals.card_done, batch.results.size, batch.results.size),
                         style = MaterialTheme.typography.titleMedium, color = FB.Ok)
                }
                if (batch.rollOut > 0 && !roll.unlocked) {
                    Text(pluralStringResource(R.plurals.roll_not_developed, batch.rollOut, batch.rollOut),
                         color = FB.Accent, style = MaterialTheme.typography.bodyMedium)
                    UnlockButton(roll)
                }
                batch.errors.take(8).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun UnlockButton(roll: Roll.State) {
    TextButton(onClick = rememberUnlock()) {
        Text(roll.price?.let { stringResource(R.string.roll_unlock_price, it) } ?: stringResource(R.string.roll_unlock_full), color = FB.Accent)
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = FB.Muted)
        }
    }
}

/** All camera files (RAW, JPEG, HEIF) under a picked folder. */
private fun scanTree(context: Context, tree: Uri): List<CardFile> {
    val out = mutableListOf<CardFile>()
    val resolver = context.contentResolver
    val stack = ArrayDeque<String>()
    stack.add(DocumentsContract.getTreeDocumentId(tree))
    val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                       DocumentsContract.Document.COLUMN_MIME_TYPE)
    var visited = 0
    while (stack.isNotEmpty() && visited < 5000) {
        val docId = stack.removeLast()
        visited++
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        try {
            resolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        stack.add(id)
                    } else if (!name.startsWith(".")) {
                        val raw = RawDecoder.isRaw(name)
                        if (raw || Developer.isImage(name)) {
                            out += CardFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), name,
                                            name.substringBeforeLast('.').uppercase(Locale.ROOT), raw)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
    return out
}

/** Film recipes stored in the Bromide photos on this phone, by camera file name. */
private fun loadRecipes(context: Context): Map<String, Recipe> {
    val out = HashMap<String, Recipe>()
    for (item in loadGallery(context)) {
        try {
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                val desc = ExifInterface(input).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION) ?: return@use
                if (!desc.startsWith(Developer.RECIPE_TAG)) return@use
                val r = JSONObject(desc.removePrefix(Developer.RECIPE_TAG))
                val stock = Stock.byId(r.optString("stock")) ?: return@use
                val source = r.optString("source").uppercase(Locale.ROOT)
                if (source.isNotEmpty() && source !in out) {
                    fun f(key: String, default: Double) = r.optDouble(key, default).toFloat()
                    out[source] = Recipe(stock, Look(
                        grain = f("grain", 1.0), halation = f("halation", 1.0), ev = f("print_ev", 0.0), lab = 0f,
                        grainSize = f("grain_size", 1.0), color = f("color", 1.0),
                        warmth = f("warmth", 0.0), tint = f("tint", 0.0),
                    ))
                }
            }
        } catch (_: Exception) {
        }
    }
    return out
}
