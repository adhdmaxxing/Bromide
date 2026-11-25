package com.bromide.app.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bromide.app.R

/**
 * Bromide's two looks, following the phone's dark mode:
 * - **Night** (dark): night shadows on color negative (blue-black sinking into teal), warm paper
 *   whites, and the red-orange of halation around a light.
 * - **Print** (light): developed prints on a pale table, white borders, black ink, and the same
 *   halation red, deepened, kept for what matters.
 */
class Palette(
    val dark: Boolean,
    val background: Color,
    /** Cards and rows. */
    val surface: Color,
    /** Chips, tracks, selected rows. */
    val surfaceHigh: Color,
    val line: Color,
    /** Halation red-orange: the one accent. */
    val accent: Color,
    /** Text and icons on [accent]. */
    val onAccent: Color,
    /** A wash of the accent behind notices. */
    val accentDark: Color,
    /** The deeper red at the edge of a halo: alerts. */
    val halation: Color,
    val halationDark: Color,
    /** The main text color: paper white at night, ink on prints. */
    val paper: Color,
    val muted: Color,
    val ok: Color,
    /** The main button: halation at night, ink on prints. */
    val primary: Color,
    val onPrimary: Color,
)

val Night = Palette(
    dark = true,
    background = Color(0xFF0A1315),
    surface = Color(0xFF101D20),
    surfaceHigh = Color(0xFF172A2D),
    line = Color(0xFF223A3D),
    accent = Color(0xFFFF5A2A),
    onAccent = Color(0xFF140805),
    accentDark = Color(0xFF3A1A12),
    halation = Color(0xFFE5321B),
    halationDark = Color(0xFF34120E),
    paper = Color(0xFFEFE8DC),
    muted = Color(0xFF8C9D9B),
    ok = Color(0xFF6CCB9F),
    primary = Color(0xFFFF5A2A),
    onPrimary = Color(0xFF140805),
)

val Print = Palette(
    dark = false,
    background = Color(0xFFF3F2EF),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFE7E5E0),
    line = Color(0xFFDAD7D0),
    accent = Color(0xFFD9381E),
    onAccent = Color(0xFFFFFFFF),
    accentDark = Color(0xFFFBE6DF),
    halation = Color(0xFFC72A1A),
    halationDark = Color(0xFFF9DCD6),
    paper = Color(0xFF161514),
    muted = Color(0xFF706C65),
    ok = Color(0xFF1C8C52),
    primary = Color(0xFF161514),
    onPrimary = Color(0xFFFFFFFF),
)

/** The current palette's colors. Reads are tracked, so everything follows a dark-mode switch. */
object FB {
    var palette by mutableStateOf(Night)
        internal set
    val Dark get() = palette.dark
    val Background get() = palette.background
    val Surface get() = palette.surface
    val SurfaceHigh get() = palette.surfaceHigh
    val Line get() = palette.line
    val Accent get() = palette.accent
    val OnAccent get() = palette.onAccent
    val AccentDark get() = palette.accentDark
    val Halation get() = palette.halation
    val HalationDark get() = palette.halationDark
    val Paper get() = palette.paper
    val Muted get() = palette.muted
    val Ok get() = palette.ok
    val Primary get() = palette.primary
    val OnPrimary get() = palette.onPrimary
    /** Text and icons drawn over a photo, in either theme. */
    val OnPhoto = Color(0xFFF5F1EA)
    /** The dark scrim under them. */
    val Scrim = Color(0x66000000)
}

/** TeX Gyre Heros, a Helvetica clone: the banner's typeface. */
val Heros = FontFamily(
    Font(R.font.heros_regular, FontWeight.Normal),
    Font(R.font.heros_bold, FontWeight.Bold),
)

/** Text that glows like the banner's wordmark (at night; prints don't glow). */
val HalationGlow: Shadow?
    get() = if (FB.Dark) Shadow(color = FB.Accent.copy(alpha = 0.85f), offset = Offset.Zero, blurRadius = 22f) else null

private fun scheme(p: Palette): ColorScheme {
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentDark,
        onPrimaryContainer = p.paper,
        secondary = p.paper,
        onSecondary = p.background,
        secondaryContainer = p.surfaceHigh,
        onSecondaryContainer = p.paper,
        tertiary = p.accent,
        onTertiary = p.onAccent,
        background = p.background,
        onBackground = p.paper,
        surface = p.surface,
        onSurface = p.paper,
        surfaceVariant = p.surfaceHigh,
        onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.background,
        surfaceContainerLow = p.surface,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface,
        surfaceContainerHighest = p.surfaceHigh,
        inverseSurface = p.paper,
        inverseOnSurface = p.background,
        outline = p.line,
        outlineVariant = p.line,
        error = if (p.dark) Color(0xFFFF8A75) else Color(0xFFB3261E),
    )
}

// Helvetica for everything, tight and lower-key; small monospace caps for data, like
// the edge print on a film strip ("PORTRA 400 ▸ 24").
private val type = Typography(
    displaySmall = TextStyle(fontFamily = Heros, fontWeight = FontWeight.Normal, fontSize = 34.sp, letterSpacing = (-0.04).em),
    headlineMedium = TextStyle(fontFamily = Heros, fontWeight = FontWeight.Normal, fontSize = 30.sp, letterSpacing = (-0.04).em),
    titleLarge = TextStyle(fontFamily = Heros, fontWeight = FontWeight.Normal, fontSize = 22.sp, letterSpacing = (-0.02).em),
    titleMedium = TextStyle(fontFamily = Heros, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = (-0.01).em),
    titleSmall = TextStyle(fontFamily = Heros, fontWeight = FontWeight.Bold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = Heros, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Heros, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Heros, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Heros, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.01.em),
    labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 0.12.em),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 0.12.em),
)

/** Corners: soft, not pills. Cards 14, photos 10, chips and small things 8. */
val CardShape = RoundedCornerShape(14.dp)
val PhotoShape = RoundedCornerShape(10.dp)
val SmallShape = RoundedCornerShape(8.dp)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = SmallShape,
    medium = RoundedCornerShape(12.dp),
    large = CardShape,
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun BromideTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) Night else Print
    FB.palette = palette
    // Clock and battery icons dark on prints, light at night, also when dark mode is switched
    // while Bromide is open (the activity handles uiMode changes itself, so it isn't recreated).
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !palette.dark
            isAppearanceLightNavigationBars = !palette.dark
        }
    }
    MaterialTheme(colorScheme = scheme(palette), typography = type, shapes = shapes, content = content)
}

/** Buttons: rounded at night, pill-shaped prints-side. */
val ButtonShape: Shape
    get() = if (FB.Dark) RoundedCornerShape(12.dp) else RoundedCornerShape(50)

/**
 * A photo shown as a print: on the light theme, a white border and a soft shadow, like a print on
 * the table; at night, simply rounded corners.
 */
fun Modifier.print(border: Dp = 5.dp, shape: Shape = PhotoShape): Modifier =
    if (FB.Dark) clip(shape)
    else shadow(3.dp, RoundedCornerShape(3.dp), clip = false)
        .background(Color.White, RoundedCornerShape(3.dp))
        .padding(border)
        .clip(RoundedCornerShape(1.dp))

/**
 * The main button's colors: halation at night, ink on prints. (The color scheme's own primary
 * is the accent, so text buttons, switches and progress stay red in both themes.)
 */
@Composable
fun inkButton(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = FB.Primary, contentColor = FB.OnPrimary,
    disabledContainerColor = FB.SurfaceHigh, disabledContentColor = FB.Muted,
)
