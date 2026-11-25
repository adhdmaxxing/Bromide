package com.bromide.app.ui

import androidx.compose.ui.platform.LocalResources
import com.bromide.app.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bromide.app.Brand
import com.bromide.app.Camera
import com.bromide.app.Link
import com.bromide.app.ServiceState
import com.bromide.engine.Stock

/** Your cameras: how each one connects, and the film it uses. */
@Composable
fun CamerasScreen(app: AppState, nav: (Route) -> Unit, onBack: () -> Unit) {
    val status by ServiceState.status.collectAsStateWithLifecycle()
    LaunchedEffect(status.developed) { app.reloadCameras() }  // models learned from new photos
    var renaming by remember { mutableStateOf<Camera?>(null) }
    var pickingFilm by remember { mutableStateOf<Camera?>(null) }
    var removing by remember { mutableStateOf<Camera?>(null) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(stringResource(R.string.my_cameras), style = MaterialTheme.typography.titleLarge)
        }
        Text(
            stringResource(R.string.cameras_intro),
            color = FB.Muted, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        if (app.cameras.isEmpty()) {
            Text(stringResource(R.string.cameras_none), modifier = Modifier.padding(20.dp))
        }
        app.cameras.forEach { cam ->
            CameraCard(
                cam,
                onRename = { renaming = cam },
                onFilm = { pickingFilm = cam },
                onGuide = { nav(Route.Onboarding(cam.id)) },
                onRemove = { removing = cam },
            )
        }

        Button(shape = ButtonShape, colors = inkButton(), onClick = { nav(Route.Onboarding()) }, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) {
            Text(stringResource(R.string.add_camera))
        }
    }

    renaming?.let { cam ->
        var name by remember(cam.id) { mutableStateOf(cam.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.camera_name)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) app.saveCamera(cam.copy(name = name.trim()))
                    renaming = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    pickingFilm?.let { cam ->
        AlertDialog(
            onDismissRequest = { pickingFilm = null },
            title = { Text(stringResource(R.string.camera_film_for, cam.name)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        FilmOption(stringResource(R.string.camera_film_home), cam.film == null) {
                            app.saveCamera(cam.copy(film = null)); pickingFilm = null
                        }
                    }
                    items(Stock.ALL, key = { it.id }) { s ->
                        FilmOption(s.name, cam.film == s.id) {
                            app.saveCamera(cam.copy(film = s.id)); pickingFilm = null
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingFilm = null }) { Text(stringResource(R.string.close)) } },
        )
    }

    removing?.let { cam ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.camera_remove_title, cam.name)) },
            text = { Text(stringResource(R.string.camera_remove_text)) },
            confirmButton = {
                TextButton(onClick = { app.removeCamera(cam); removing = null }) {
                    Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun CameraCard(cam: Camera, onRename: () -> Unit, onFilm: () -> Unit, onGuide: () -> Unit, onRemove: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(FB.Surface).padding(16.dp)
    ) {
        Text(cam.name, style = MaterialTheme.typography.titleLarge)
        val res = LocalResources.current
        val brand = cam.brand.label(res)
        Text(
            listOfNotNull(brand.takeIf { cam.name != it }, cam.model).joinToString(" · ").ifEmpty { brand },
            style = MaterialTheme.typography.labelMedium, color = FB.Accent
        )
        VSpace(10.dp)
        InfoRow(stringResource(R.string.camera_sends), when (cam.link) {
            Link.Ftp -> stringResource(R.string.camera_sends_ftp)
            Link.App -> if (cam.brand == Brand.Other) stringResource(R.string.camera_sends_app_other)
                        else stringResource(R.string.camera_sends_app, cam.brand.app(res))
            Link.Card -> stringResource(R.string.camera_sends_card)
        })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InfoRow(stringResource(R.string.camera_film), cam.film?.let { Stock.byId(it)?.name } ?: stringResource(R.string.camera_film_home_short))
            }
            TextButton(onClick = onFilm) { Text(stringResource(R.string.change)) }
        }
        VSpace(2.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = onRename) { Text(stringResource(R.string.rename)) }
            TextButton(onClick = onGuide) { Text(stringResource(R.string.setup_guide)) }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.remove), color = FB.Muted) }
        }
    }
}

@Composable
private fun FilmOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) FB.Accent else FB.Paper,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)
    )
}
