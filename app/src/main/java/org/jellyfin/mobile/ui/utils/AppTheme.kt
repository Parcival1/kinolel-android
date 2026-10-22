package org.jellyfin.mobile.ui.utils

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** KinoLel-Farben – eigene Funktion, damit AppThemeTest prüfen kann, dass Text auf Dialogen (surface) hell bleibt */
@Suppress("MagicNumber")
fun kinolelFarben() =
    darkColors(
        primary = Color(0xFF9AA4FF), // KinoLel: Lavendel
        // KinoLel (Lel 22.10.: Dialog „Download entfernen“ unlesbar): NICHT gleich surface wählen – contentColorFor(surface)
        // nimmt sonst onPrimary (dunkel) statt onSurface, Titel/Text von Dialogen wurden dunkelgrau auf Dunkelblau.
        primaryVariant = Color(0xFF6C72EE),
        background = Color(0xFF0B0F18),
        surface = Color(0xFF151B2A),
        error = Color(0xFFCF6679),
        onPrimary = Color(0xFF0B0F18),
        onSecondary = Color.White,
        onBackground = Color.White,
        onSurface = Color.White,
        onError = Color.White,
    )

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = remember { kinolelFarben() }
    MaterialTheme(
        colors = colors,
        shapes = Shapes(
            small = RoundedCornerShape(4.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(0.dp),
        ),
        content = content,
    )
}
