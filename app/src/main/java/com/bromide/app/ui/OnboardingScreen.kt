package com.bromide.app.ui

import androidx.compose.ui.platform.LocalResources
import com.bromide.app.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bromide.app.Brand
import com.bromide.app.Camera
import com.bromide.app.Developed
import com.bromide.app.FilmService
import com.bromide.app.Link
import com.bromide.app.PhotoWatcher
import com.bromide.app.ServiceState
import com.bromide.engine.Stock

private enum class Step { Welcome, Brand, Method, Hotspot, Join, Ftp, Access, PairApp, Card, FirstPhoto }

/**
 * Camera setup as a wizard: which brand, how it sends photos, then the steps for
 * that way. Each step checks itself where it can: the hotspot is detected, the
 * camera is detected when it connects, and the first photo is shown when it
 * arrives.
 *
 * With [cameraId], replays the guide for one of your cameras; otherwise adds a
 * camera (and welcomes you on first run).
 */
@Composable
fun OnboardingScreen(app: AppState, cameraId: String?, onDone: () -> Unit, onFromCard: () -> Unit, onPhone: () -> Unit) {
    val context = LocalContext.current
    val status by ServiceState.status.collectAsStateWithLifecycle()
    val addresses by rememberAddresses()
    val settings = app.settings
    val host = addresses.firstOrNull()?.ip
    val guideFor = remember(cameraId) { cameraId?.let { settings.camera(it) } }
    val firstRun = remember { !settings.setupDone && guideFor == null }

    var brand by rememberSaveable { mutableStateOf(guideFor?.brand) }
    var link by rememberSaveable { mutableStateOf(guideFor?.link) }
    /** The camera this setup is about (created once a way to connect is picked). */
    var camId by rememberSaveable { mutableStateOf(guideFor?.id) }

    val steps = buildList {
        if (firstRun) add(Step.Welcome)
        if (guideFor == null) { add(Step.Brand); add(Step.Method) }
        when (link) {
            Link.Ftp -> addAll(listOf(Step.Hotspot, Step.Join, Step.Ftp, Step.FirstPhoto))
            Link.App -> addAll(listOf(Step.Access, Step.PairApp, Step.FirstPhoto))
            Link.Card -> add(Step.Card)
            null -> {}
        }
    }
    var step by rememberSaveable { mutableStateOf(steps.first()) }
    val index = steps.indexOf(step).coerceAtLeast(0)
    fun next() { steps.getOrNull(index + 1)?.let { step = it } }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        FilmService.start(context)
    }
    fun ensureRunning() {
        if (status.running) return
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else FilmService.start(context)
    }

    var photoAccess by remember { mutableStateOf(PhotoWatcher.hasPhotoAccess(context)) }
    var askedAccess by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { photoAccess = PhotoWatcher.hasPhotoAccess(context) }
    val accessPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedAccess = true
        photoAccess = PhotoWatcher.hasPhotoAccess(context)
        if (photoAccess) FilmService.start(context)  // starts the watcher, or adds it to a running session
    }
    fun askAccess() {
        val perms = (PhotoWatcher.photoPermissions + Manifest.permission.ACCESS_MEDIA_LOCATION).toMutableList()
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        accessPermission.launch(perms.toTypedArray())
    }

    val res = LocalResources.current
    fun choose(l: Link) {
        val b = brand ?: return
        val existing = camId?.let { settings.camera(it) }
        val others = app.cameras.filter { it.id != existing?.id && it.brand == b }
        val name = if (b == Brand.Other) res.getString(R.string.camera_default_name) else b.label(res)
        val cam = (existing ?: Camera(name = name, brand = b, link = l))
            .copy(brand = b, link = l, name = if (others.isEmpty()) name else "$name ${others.size + 1}")
        camId = cam.id
        link = l
        // The first FTP camera gets a password; later ones share it (they log in to the same server).
        if (l == Link.Ftp && app.cameras.none { it.link == Link.Ftp && it.id != cam.id }) settings.ensureFtpPassword()
        app.saveCamera(cam)
        step = when (l) {
            Link.Ftp -> Step.Hotspot
            Link.App -> Step.Access
            Link.Card -> Step.Card
        }
    }

    // This camera's newest photo. Over FTP any new upload proves the link works, even if
    // Bromide credits it to another FTP camera (same brand, model not learned yet).
    val newestAtStart = remember { status.recent.firstOrNull()?.uri }
    val mine = status.recent.firstOrNull { it.cameraId == camId }
        ?: status.recent.firstOrNull()?.takeIf { link == Link.Ftp && it.uri != newestAtStart }
    val ftpSeenAtStart = remember { status.cameraSeen }
    val cameraConnected = status.cameraSeen && !ftpSeenAtStart

    // auto-advance when each step's condition is met
    LaunchedEffect(step, host != null) { if (step == Step.Hotspot && host != null) ensureRunning() }
    LaunchedEffect(cameraConnected) { if (cameraConnected && step in listOf(Step.Join, Step.Ftp)) step = Step.FirstPhoto }
    LaunchedEffect(mine) { if (mine != null && step in listOf(Step.Join, Step.Ftp, Step.PairApp)) step = Step.FirstPhoto }

    fun markDone() {
        settings.cameras = app.cameras  // stores the list even when empty (skipped)
        settings.setupDone = true
    }
    val finish = { markDone(); onDone() }
    val b = brand ?: Brand.Other

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (index > 0) {
                IconButton(onClick = { step = steps[index - 1] }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = FB.Muted)
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // the step count isn't known until a way to connect is picked
                val total = if (link == null) steps.size + 3 else steps.size
                for (i in 0 until total) {
                    Box(Modifier.height(4.dp).width(if (i == index) 24.dp else 12.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (i <= index) FB.Accent else FB.Line))
                }
            }
            TextButton(onClick = finish) { Text(stringResource(if (firstRun) R.string.skip else R.string.close), color = FB.Muted) }
        }

        AnimatedContent(targetState = step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "step",
                        modifier = Modifier.weight(1f)) { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
                when (s) {
                    Step.Welcome -> Welcome()
                    Step.Brand -> BrandStep(brand) { brand = it; step = Step.Method }
                    Step.Method -> MethodStep(b, link, ::choose)
                    Step.Hotspot -> HotspotStep(host)
                    Step.Join -> JoinStep(b)
                    Step.Ftp -> FtpStep(b, host, status.port, settings.user, settings.password.takeIf { settings.requirePassword }, status.running)
                    Step.Access -> AccessStep(b, photoAccess, askedAccess || PhotoWatcher.hasPartialAccess(context))
                    Step.PairApp -> PairAppStep(b)
                    Step.Card -> CardStep()
                    Step.FirstPhoto -> FirstPhotoStep(b, link ?: Link.Ftp, mine, cameraConnected)
                }
            }
        }

        // bottom action
        var label = stringResource(R.string.next)
        var enabled = true
        var action: () -> Unit = { next() }
        when (step) {
            Step.Welcome -> label = stringResource(R.string.onboard_lets_go)
            Step.Brand, Step.Method -> label = ""  // picking an option moves on
            Step.Hotspot -> {
                label = if (host == null) stringResource(R.string.onboard_waiting_hotspot) else stringResource(R.string.next)
                enabled = host != null
                action = { ensureRunning(); next() }
            }
            Step.Join -> label = stringResource(R.string.onboard_camera_joined)
            Step.Ftp -> {
                label = if (cameraConnected) stringResource(R.string.next) else stringResource(R.string.onboard_waiting_camera)
                enabled = cameraConnected
            }
            Step.Access -> {
                if (photoAccess) action = { FilmService.start(context); next() }
                else { label = stringResource(R.string.onboard_allow_photos); action = ::askAccess }
            }
            Step.PairApp -> action = { ensureRunning(); next() }
            Step.Card -> { label = stringResource(R.string.done); action = finish }
            Step.FirstPhoto -> {
                label = if (mine != null) stringResource(R.string.onboard_start_shooting) else stringResource(R.string.onboard_waiting_photo)
                enabled = mine != null
                action = finish
            }
        }
        Column(Modifier.padding(16.dp)) {
            if (label.isNotEmpty()) {
                Button(shape = ButtonShape, onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().height(54.dp),
                       colors = inkButton()) {
                    Text(label)
                }
            }
            when {
                step == Step.Welcome ->
                    TextButton(onClick = { markDone(); onPhone() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.onboard_phone_only), color = FB.Muted)
                    }
                step == Step.Ftp && !cameraConnected ->
                    TextButton(onClick = { step = Step.FirstPhoto }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.onboard_skip_check), color = FB.Muted)
                    }
                step == Step.Card ->
                    TextButton(onClick = { markDone(); onFromCard() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.onboard_card_now))
                    }
                step == Step.FirstPhoto && mine == null ->
                    TextButton(onClick = finish, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.onboard_finish_no_test), color = FB.Muted)
                    }
            }
        }
    }
}

@Composable
private fun StepTitle(kicker: String, title: String) {
    Text(kicker.uppercase(), style = MaterialTheme.typography.labelMedium, color = FB.Accent)
    VSpace(6.dp)
    Text(title, style = MaterialTheme.typography.headlineMedium)
    VSpace(12.dp)
}

@Composable
private fun Welcome() {
    VSpace(40.dp)
    Wordmark(size = 48.sp)
    VSpace(10.dp)
    Text(stringResource(R.string.onboard_welcome_title), style = MaterialTheme.typography.titleLarge)
    VSpace(18.dp)
    Text(
        stringResource(R.string.onboard_welcome_text),
        color = FB.Muted
    )
    VSpace(24.dp)
    Text(stringResource(R.string.onboard_welcome_need), style = MaterialTheme.typography.titleMedium)
    VSpace(8.dp)
    Bullet(stringResource(R.string.onboard_welcome_camera))
    Bullet(stringResource(R.string.onboard_welcome_no_wifi))
    Bullet(stringResource(R.string.onboard_welcome_no_camera))
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("•  ", color = FB.Accent)
        Text(text)
    }
}

@Composable
private fun Numbered(n: Int, text: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text("$n", color = FB.Accent, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(24.dp))
        Text(text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BrandStep(selected: Brand?, onPick: (Brand) -> Unit) {
    StepTitle(stringResource(R.string.onboard_kicker_camera), stringResource(R.string.onboard_brand_title))
    Text(stringResource(R.string.onboard_brand_text), color = FB.Muted)
    VSpace(16.dp)
    Brand.entries.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { brand ->
                val on = brand == selected
                Box(
                    Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) FB.SurfaceHigh else FB.Surface)
                        .border(BorderStroke(if (on) 2.dp else 1.dp, if (on) FB.Accent else FB.Line), RoundedCornerShape(12.dp))
                        .clickable { onPick(brand) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(brand.label(LocalResources.current), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun MethodStep(brand: Brand, selected: Link?, onPick: (Link) -> Unit) {
    val res = LocalResources.current
    StepTitle(brand.label(res), stringResource(R.string.onboard_method_title))
    Choice(
        title = stringResource(R.string.camera_sends_app, brand.app(res)),
        tag = stringResource(R.string.onboard_tag_most),
        text = stringResource(R.string.onboard_method_app),
        selected = selected == Link.App,
    ) { onPick(Link.App) }
    Choice(
        title = stringResource(R.string.onboard_method_ftp_title),
        tag = stringResource(R.string.onboard_tag_fastest),
        text = if (brand.ftpExamples != null) stringResource(R.string.onboard_method_ftp_examples, brand.ftpExamples)
               else stringResource(R.string.onboard_method_ftp_few, brand.label(res)),
        selected = selected == Link.Ftp,
    ) { onPick(Link.Ftp) }
    Choice(
        title = stringResource(R.string.camera_sends_card),
        tag = stringResource(R.string.onboard_tag_no_wifi),
        text = stringResource(R.string.onboard_method_card),
        selected = selected == Link.Card,
    ) { onPick(Link.Card) }
    VSpace(8.dp)
    Text(stringResource(R.string.onboard_method_not_sure),
         color = FB.Muted, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Choice(title: String, tag: String, text: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) FB.SurfaceHigh else FB.Surface)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) FB.Accent else FB.Line), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Text(tag.uppercase(), style = MaterialTheme.typography.labelMedium, color = FB.Accent)
        VSpace(4.dp)
        Text(title, style = MaterialTheme.typography.titleMedium)
        VSpace(4.dp)
        Text(text, color = FB.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HotspotStep(host: String?) {
    val context = LocalContext.current
    StepTitle(stringResource(R.string.onboard_kicker_wifi), stringResource(R.string.onboard_hotspot_title))
    Text(stringResource(R.string.onboard_hotspot_text), color = FB.Muted)
    VSpace(20.dp)
    if (host == null) {
        Button(shape = ButtonShape, onClick = { openHotspotSettings(context) }, modifier = Modifier.fillMaxWidth().height(52.dp),
               colors = ButtonDefaults.buttonColors(containerColor = FB.Halation, contentColor = FB.OnPhoto)) {
            Text(stringResource(R.string.onboard_open_hotspot))
        }
        VSpace(12.dp)
        Text(stringResource(R.string.onboard_hotspot_tip),
             color = FB.Muted, style = MaterialTheme.typography.bodyMedium)
    } else {
        Check(stringResource(R.string.onboard_hotspot_on))
    }
}

@Composable
private fun JoinStep(brand: Brand) {
    StepTitle(stringResource(R.string.onboard_kicker_wifi), stringResource(R.string.onboard_join_title))
    Text(stringResource(R.string.onboard_join_text), color = FB.Muted)
    VSpace(16.dp)
    if (brand.joinPath != null) {
        MenuPath(brand.label(LocalResources.current), stringResource(brand.joinPath))
    } else {
        Text(stringResource(R.string.onboard_join_generic), color = FB.Muted)
    }
    VSpace(8.dp)
    Text(stringResource(R.string.onboard_join_pick), color = FB.Muted)
}

@Composable
private fun FtpStep(brand: Brand, host: String?, port: Int, user: String, password: String?, running: Boolean) {
    StepTitle("FTP", stringResource(R.string.onboard_ftp_title))
    if (brand.ftpPath != null) {
        MenuPath(brand.label(LocalResources.current), stringResource(brand.ftpPath))
        Text(stringResource(R.string.onboard_menu_vary), color = FB.Muted, style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(stringResource(R.string.onboard_ftp_generic),
             color = FB.Muted)
    }
    VSpace(8.dp)
    Text(stringResource(R.string.onboard_ftp_enter), style = MaterialTheme.typography.titleMedium)
    VSpace(8.dp)
    BigValue(stringResource(R.string.onboard_ftp_host), host ?: "…")
    BigValue(stringResource(R.string.settings_port), port.toString())
    if (password != null) {
        BigValue(stringResource(R.string.onboard_ftp_user_label), user)
        BigValue(stringResource(R.string.onboard_ftp_password), password)
    }
    VSpace(10.dp)
    Text(stringResource(R.string.onboard_ftp_and_set), style = MaterialTheme.typography.titleMedium)
    VSpace(4.dp)
    Bullet(stringResource(R.string.onboard_ftp_passive))
    Bullet(stringResource(R.string.onboard_ftp_secure))
    if (password == null) Bullet(stringResource(R.string.onboard_ftp_user, user))
    VSpace(10.dp)
    Text(stringResource(brand.ftpFinish), color = FB.Muted)
    VSpace(8.dp)
    Text(stringResource(R.string.onboard_ftp_raw), color = FB.Muted)
    VSpace(18.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulseDot(FB.Accent, pulsing = true)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(if (running) R.string.onboard_waiting_connect else R.string.onboard_starting), color = FB.Muted)
    }
}

@Composable
private fun AccessStep(brand: Brand, granted: Boolean, asked: Boolean) {
    val context = LocalContext.current
    val res = LocalResources.current
    StepTitle(stringResource(R.string.onboard_kicker_app), stringResource(R.string.onboard_access_title))
    Text(if (brand == Brand.Other) stringResource(R.string.onboard_access_text_other)
         else stringResource(R.string.onboard_access_text, brand.app(res)), color = FB.Muted)
    VSpace(12.dp)
    Text(
        if (brand == Brand.Other) stringResource(R.string.onboard_access_only_other)
        else stringResource(R.string.onboard_access_only, brand.label(res)),
        color = FB.Muted
    )
    VSpace(20.dp)
    if (granted) {
        Check(stringResource(R.string.onboard_access_ok))
    } else if (asked) {
        Text(stringResource(R.string.onboard_access_partial), color = MaterialTheme.colorScheme.error)
        VSpace(8.dp)
        OutlinedButton(shape = ButtonShape, onClick = { openAppSettings(context) }) { Text(stringResource(R.string.onboard_open_app_settings), color = FB.Paper) }
    }
}

@Composable
private fun PairAppStep(brand: Brand) {
    val context = LocalContext.current
    val other = brand == Brand.Other
    val res = LocalResources.current
    StepTitle(stringResource(R.string.onboard_kicker_app), if (other) stringResource(R.string.onboard_pair_title_other) else stringResource(R.string.onboard_pair_title, brand.app(res)))
    Numbered(1, if (other) stringResource(R.string.onboard_pair_install_other)
                else stringResource(R.string.onboard_pair_install, brand.appLong(res)))
    if (!other) {
        TextButton(onClick = {
            val q = Uri.encode(brand.app(res))
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$q&c=apps")))
            }
        }, modifier = Modifier.padding(start = 16.dp)) { Text(stringResource(R.string.onboard_find_app, brand.app(res))) }
    }
    Numbered(2, stringResource(brand.autoSend))
    Numbered(3, stringResource(R.string.onboard_pair_size))
    Numbered(4, stringResource(R.string.onboard_pair_keep))
}

@Composable
private fun CardStep() {
    StepTitle(stringResource(R.string.onboard_kicker_card), stringResource(R.string.onboard_card_title))
    Text(stringResource(R.string.onboard_card_text), color = FB.Muted)
    VSpace(12.dp)
    Text(stringResource(R.string.onboard_card_text2), color = FB.Muted)
    VSpace(12.dp)
    Text(stringResource(R.string.onboard_card_later),
         color = FB.Muted, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun FirstPhotoStep(brand: Brand, link: Link, latest: Developed?, cameraConnected: Boolean) {
    StepTitle(stringResource(R.string.onboard_kicker_test), if (latest != null) stringResource(R.string.onboard_it_works) else stringResource(R.string.onboard_take_photo))
    if (latest != null) {
        Thumb(latest, 1080, Modifier.fillMaxWidth().aspectRatio(3f / 2f).clip(RoundedCornerShape(12.dp)), ContentScale.Crop)
        VSpace(10.dp)
        Text(stringResource(R.string.onboard_developed_as, latest.stockName), color = FB.Muted)
        VSpace(10.dp)
        if (link == Link.Ftp) {
            Text(stringResource(R.string.onboard_from_now_ftp), color = FB.Muted)
            VSpace(10.dp)
            Text(stringResource(R.string.onboard_tip_ftp_film, Stock.ALL.take(4).joinToString { it.id }),
                 style = MaterialTheme.typography.bodyMedium, color = FB.Muted)
        } else {
            Text(stringResource(R.string.onboard_from_now_app), color = FB.Muted)
        }
        VSpace(10.dp)
        Text(stringResource(R.string.onboard_tip_camera_film),
             style = MaterialTheme.typography.bodyMedium, color = FB.Muted)
    } else {
        if (cameraConnected) Check(stringResource(R.string.home_connected))
        VSpace(8.dp)
        Text(
            stringResource(if (link == Link.Ftp) R.string.onboard_press_shutter else R.string.onboard_send_from_app),
            color = FB.Muted
        )
        VSpace(18.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(FB.Accent, pulsing = true)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.onboard_waiting_photo), color = FB.Muted)
        }
        if (link == Link.App) {
            VSpace(18.dp)
            Text(if (brand == Brand.Other) stringResource(R.string.onboard_nothing_yet_other)
                 else stringResource(R.string.onboard_nothing_yet, brand.label(LocalResources.current)), style = MaterialTheme.typography.bodyMedium, color = FB.Muted)
        }
    }
}

@Composable
private fun Check(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = FB.Ok, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MenuPath(brand: String, path: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(FB.Surface).padding(12.dp)) {
        Text(brand, style = MaterialTheme.typography.labelMedium, color = FB.Muted)
        Text(path, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun BigValue(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(FB.SurfaceHigh)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = FB.Muted, style = MaterialTheme.typography.bodyMedium)
            Text(value, fontFamily = FontFamily.Monospace, fontSize = 26.sp, color = FB.Paper)
        }
        TextButton(onClick = { clipboard.setText(AnnotatedString(value)) }) { Text(stringResource(R.string.copy)) }
    }
}
