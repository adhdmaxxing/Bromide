package com.bromide.app.ui

import com.bromide.app.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.foundation.layout.height
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bromide.app.Billing
import com.bromide.app.BuildConfig
import com.bromide.app.Roll

@Composable
fun SettingsScreen(app: AppState, nav: (Route) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = app.settings
    var size by remember { mutableIntStateOf(settings.outputSize) }
    var mode by remember { mutableStateOf(settings.sourceMode) }
    var autoStart by remember { mutableStateOf(settings.autoStart) }
    var requirePassword by remember { mutableStateOf(settings.requirePassword) }
    var password by remember { mutableStateOf(settings.password) }
    var port by remember { mutableStateOf(settings.port.toString()) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
        }

        Group(stringResource(R.string.settings_camera)) {
            TextButton(onClick = { nav(Route.Cameras) }) { Text(stringResource(R.string.settings_my_cameras, app.cameras.size)) }
            TextButton(onClick = { nav(Route.Onboarding()) }) { Text(stringResource(R.string.add_camera)) }
            ToggleRow(stringResource(R.string.settings_autostart), stringResource(R.string.settings_autostart_desc), autoStart) {
                autoStart = it; settings.autoStart = it
            }
            Text(stringResource(R.string.settings_per_film),
                 color = FB.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }

        Group(stringResource(R.string.settings_camera_files)) {
            com.bromide.app.SourceMode.entries.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    androidx.compose.material3.RadioButton(selected = mode == m, onClick = { mode = m; settings.sourceMode = m })
                    Column(Modifier.weight(1f).padding(top = 10.dp)) {
                        Text(stringResource(m.title), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(m.subtitle), style = MaterialTheme.typography.bodyMedium, color = FB.Muted)
                    }
                }
            }
            TextButton(onClick = { nav(Route.FromCard) }) { Text(stringResource(R.string.settings_from_card)) }
        }

        Group(stringResource(R.string.settings_saved)) {
            Text(stringResource(R.string.settings_size), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf(2048 to R.string.size_2048, 3000 to R.string.size_3000, 0 to R.string.size_full_short).forEach { (v, label) ->
                    FilterChip(selected = size == v, onClick = { size = v; settings.outputSize = v }, label = { Text(stringResource(label)) })
                }
            }
            Text(
                stringResource(R.string.settings_size_desc),
                color = FB.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)
            )
        }

        Group(stringResource(R.string.settings_ftp)) {
            ToggleRow(stringResource(R.string.settings_password), stringResource(R.string.settings_password_desc), requirePassword) {
                requirePassword = it; settings.requirePassword = it
            }
            if (requirePassword) {
                OutlinedTextField(
                    value = password, onValueChange = { password = it; if (it.isNotBlank()) settings.password = it },
                    label = { Text(stringResource(R.string.settings_ftp_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
            }
            OutlinedTextField(
                value = port,
                onValueChange = { v ->
                    port = v.filter { it.isDigit() }.take(5)
                    port.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { settings.port = it }
                },
                label = { Text(stringResource(R.string.settings_port)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Text(stringResource(R.string.settings_ftp_apply), color = FB.Muted,
                 style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }

        val roll by Roll.state.collectAsStateWithLifecycle()
        Group(stringResource(R.string.settings_unlock)) {
            if (roll.founder && !roll.bought) {
                // Early user: free for good. Buying the unlock is only a way to support Bromide.
                Text(stringResource(R.string.settings_founder), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_founder_support), color = FB.Muted, style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = rememberUnlock()) {
                    Text(roll.price?.let { stringResource(R.string.settings_support_price, it) } ?: stringResource(R.string.settings_support))
                }
            } else if (roll.unlocked) {
                Text(stringResource(R.string.settings_unlocked), style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(pluralStringResource(R.plurals.roll_left_settings, roll.left, roll.left, Roll.FRAMES), style = MaterialTheme.typography.bodyLarge)
                FilmStrip(roll.used, Modifier.fillMaxWidth().padding(top = 8.dp).height(26.dp))
                Text(stringResource(R.string.roll_terms), color = FB.Muted, style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.padding(top = 8.dp))
                Row {
                    TextButton(onClick = rememberUnlock()) {
                        Text(roll.price?.let { stringResource(R.string.roll_unlock_price, it) } ?: stringResource(R.string.roll_unlock_full))
                    }
                    TextButton(onClick = {
                        Billing.refresh()
                        Toast.makeText(context, R.string.settings_restoring, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.settings_restore)) }
                }
                if (roll.latent > 0) {
                    Text(pluralStringResource(R.plurals.settings_latent, roll.latent, roll.latent), color = FB.Muted,
                         style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    TextButton(onClick = { Roll.discardLatent() }) { Text(stringResource(R.string.settings_discard_latent)) }
                }
            }
            if (BuildConfig.DEBUG) {
                // Debug builds aren't signed for Play, so the purchase can't run: test the roll from here.
                Text("Debug build: test the free roll", color = FB.Muted, style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.padding(top = 8.dp))
                Row {
                    TextButton(onClick = { Roll.setUsed(roll.used + 10) }) { Text("+10 frames") }
                    TextButton(onClick = { Roll.setUsed(0) }) { Text("Reset") }
                    TextButton(onClick = { Roll.setUnlocked(!roll.bought) }) { Text(if (roll.bought) "Lock" else "Unlock") }
                }
                TextButton(onClick = { Roll.setFounder(!roll.founder) }) { Text(if (roll.founder) "Not an early user" else "Early user") }
            }
        }

        Group(stringResource(R.string.settings_about)) {
            Text("Bromide ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_credits),
                color = FB.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                stringResource(R.string.settings_gpl),
                color = FB.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)
            )
            Row {
                TextButton(onClick = { open(context, SOURCE_URL) }) { Text(stringResource(R.string.source_code)) }
                TextButton(onClick = { open(context, "$SOURCE_URL/blob/main/PRIVACY.md") }) { Text(stringResource(R.string.privacy)) }
                TextButton(onClick = { open(context, "https://github.com/andreavolpato/spektrafilm") }) { Text("spektrafilm") }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        SectionTitle(title, Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = FB.Muted)
        }
        Switch(checked = value, onCheckedChange = onChange,
               colors = SwitchDefaults.colors(checkedTrackColor = FB.Accent, checkedThumbColor = FB.OnAccent))
    }
}

private const val SOURCE_URL = "https://github.com/adhdmaxxing/Bromide"

private fun open(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}
