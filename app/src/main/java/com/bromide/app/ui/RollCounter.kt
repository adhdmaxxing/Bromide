package com.bromide.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bromide.app.Billing
import com.bromide.app.R
import com.bromide.app.Roll

/** The last frames of the roll show in halation red, like a camera warning you near the end. */
private const val LOW = 6

// The strip is real film in both themes: orange-masked base, dark perforations.
private val StripBase = Night.accentDark
private val StripHole = Night.background

/**
 * The free roll, like a film camera's frame counter: how many of the 36 free frames are left, as
 * a glowing number and a strip of frames, lit while still free. Once it's finished: what's
 * waiting to be developed, and the unlock. Shows nothing once Bromide is unlocked.
 */
@Composable
fun RollCounter(roll: Roll.State, modifier: Modifier = Modifier) {
    if (roll.unlocked) return
    val unlock = rememberUnlock()
    val finished = roll.left == 0
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = if (finished) FB.HalationDark else FB.Surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 18.dp)) {
            if (finished) {
                Text(stringResource(R.string.roll_finished), style = MaterialTheme.typography.titleLarge)
                VSpace(6.dp)
                Text(
                    if (roll.latent > 0) pluralStringResource(R.plurals.roll_waiting, roll.latent, roll.latent)
                    else stringResource(R.string.roll_finished_text),
                    color = FB.Paper.copy(alpha = 0.85f)
                )
                VSpace(14.dp)
                FilmStrip(roll.used, Modifier.fillMaxWidth().height(30.dp))
                VSpace(16.dp)
                Button(
                    shape = ButtonShape,
                    onClick = unlock,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FB.Accent, contentColor = FB.OnAccent)
                ) {
                    Text(roll.price?.let { stringResource(R.string.roll_unlock_price, it) } ?: stringResource(R.string.roll_unlock_full))
                }
                VSpace(8.dp)
                Text(
                    stringResource(if (roll.pending) R.string.roll_pending else R.string.roll_terms),
                    style = MaterialTheme.typography.bodySmall, color = FB.Paper.copy(alpha = 0.7f)
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.roll_title), Modifier.weight(1f))
                    TextButton(onClick = unlock) {
                        Text(roll.price?.let { stringResource(R.string.roll_unlock_price, it) } ?: stringResource(R.string.roll_unlock),
                             color = FB.Accent)
                    }
                }
                // The counter window: a big glowing number, like the frame count on a film camera.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${roll.left}",
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp, shadow = HalationGlow),
                        color = if (roll.left <= LOW) FB.Accent else FB.Paper
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        pluralStringResource(R.plurals.roll_left_of, roll.left, Roll.FRAMES),
                        color = FB.Muted, modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                VSpace(10.dp)
                FilmStrip(roll.used, Modifier.fillMaxWidth().height(30.dp))
                VSpace(10.dp)
                Text(stringResource(R.string.roll_phone_free), style = MaterialTheme.typography.bodySmall, color = FB.Muted)
            }
        }
    }
}

/**
 * The roll as a strip of 35 mm film: orange-masked base, sprocket holes along both edges, one
 * frame per free photo. Frames still free are lit, used ones dark, and the next one glows.
 */
@Composable
fun FilmStrip(used: Int, modifier: Modifier = Modifier) {
    val left = (Roll.FRAMES - used).coerceAtLeast(0)
    val label = pluralStringResource(R.plurals.roll_strip_desc, left, left, Roll.FRAMES)
    Canvas(modifier.semantics { contentDescription = label }) {
        drawRoundRect(StripBase, cornerRadius = CornerRadius(2.dp.toPx()))
        val n = Roll.FRAMES
        val pad = 2.dp.toPx()
        val perf = size.height * 0.16f
        val pitch = (size.width - pad) / n
        val gap = (pitch * 0.22f).coerceAtLeast(1.dp.toPx())
        for (i in 0 until n) {
            val x = pad + i * pitch
            // sprocket holes, one above and one below each frame
            val hole = Size(pitch * 0.42f, perf * 0.62f)
            val hx = x + (pitch - gap - hole.width) / 2
            perforation(Offset(hx, perf * 0.19f), hole)
            perforation(Offset(hx, size.height - perf * 0.19f - hole.height), hole)
            val color = when {
                i < used -> StripHole.copy(alpha = 0.75f)
                i == used -> Night.accent
                left <= LOW -> Night.accent.copy(alpha = 0.75f)
                else -> Night.paper.copy(alpha = 0.9f)
            }
            val frameTop = perf + 1.dp.toPx()
            if (i == used) {
                // the next frame glows, a soft halo around it
                drawRoundRect(FB.Accent.copy(alpha = 0.35f), Offset(x - gap / 2, frameTop - 2.dp.toPx()),
                              Size(pitch, size.height - 2 * frameTop + 4.dp.toPx()), CornerRadius(2.dp.toPx()))
            }
            drawRoundRect(color, Offset(x, frameTop), Size(pitch - gap, size.height - 2 * frameTop), CornerRadius(1.dp.toPx()))
        }
    }
}

private fun DrawScope.perforation(at: Offset, size: Size) =
    drawRoundRect(StripHole, at, size, CornerRadius(size.height / 3))

/** Opens Play's purchase sheet, or says why it can't. */
@Composable
fun rememberUnlock(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        {
            val activity = context.findActivity()
            if (activity == null || !Billing.buy(activity)) {
                Toast.makeText(context, R.string.roll_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
