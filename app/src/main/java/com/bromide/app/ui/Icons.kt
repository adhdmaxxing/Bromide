package com.bromide.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A few icons not in material-icons-core, drawn as simple 24dp outlines. */
object FbIcons {
    private fun outline(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    /** A grid of photos. */
    val Gallery: ImageVector = outline("Gallery") {
        moveTo(4f, 4f); lineTo(10.5f, 4f); lineTo(10.5f, 10.5f); lineTo(4f, 10.5f); close()
        moveTo(13.5f, 4f); lineTo(20f, 4f); lineTo(20f, 10.5f); lineTo(13.5f, 10.5f); close()
        moveTo(4f, 13.5f); lineTo(10.5f, 13.5f); lineTo(10.5f, 20f); lineTo(4f, 20f); close()
        moveTo(13.5f, 13.5f); lineTo(20f, 13.5f); lineTo(20f, 20f); lineTo(13.5f, 20f); close()
    }

    /** Radio waves: the hotspot. */
    val Hotspot: ImageVector = outline("Hotspot") {
        moveTo(12f, 13f); lineTo(12f, 20f)
        moveTo(8.5f, 10.5f); curveTo(8.5f, 8.5f, 10f, 7f, 12f, 7f); curveTo(14f, 7f, 15.5f, 8.5f, 15.5f, 10.5f)
        moveTo(5.5f, 10.5f); curveTo(5.5f, 6.9f, 8.4f, 4f, 12f, 4f); curveTo(15.6f, 4f, 18.5f, 6.9f, 18.5f, 10.5f)
    }

    /** Sliders: adjustments. */
    val Tune: ImageVector = outline("Tune") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 17f); lineTo(20f, 17f)
        moveTo(9f, 5f); lineTo(9f, 9f)
        moveTo(15f, 15f); lineTo(15f, 19f)
    }

    /** A phone: photos taken with this phone. */
    val Phone: ImageVector = outline("Phone") {
        moveTo(7f, 3f); lineTo(17f, 3f); lineTo(17f, 21f); lineTo(7f, 21f); close()
        moveTo(11f, 18f); lineTo(13f, 18f)
    }

    /** A film canister. */
    val Film: ImageVector = outline("Film") {
        moveTo(7f, 5f); lineTo(15f, 5f); lineTo(15f, 19f); lineTo(7f, 19f); close()
        moveTo(15f, 8f); lineTo(20f, 8f); lineTo(20f, 16f); lineTo(15f, 16f)
        moveTo(9f, 3f); lineTo(13f, 3f)
    }
}
