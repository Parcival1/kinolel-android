package org.jellyfin.mobile.ui.utils

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.R

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

/** Schrift der Weboberfläche (Inter, res/font) */
val KinoLelSchrift = FontFamily(Font(R.font.inter_regular, FontWeight.Normal), Font(R.font.inter_semibold, FontWeight.SemiBold))

/** Dialoge wie die Menüs der Weboberfläche (Lel 22.10.2026) */
val KinoLelDialogGrund = Color(0xFF1B1D2A)
val KinoLelDialogRand = Color(0x14FFFFFF)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = remember { kinolelFarben() }
    MaterialTheme(
        colors = colors,
        typography = Typography(defaultFontFamily = KinoLelSchrift),
        shapes = Shapes(
            small = RoundedCornerShape(4.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(0.dp),
        ),
        content = content,
    )
}
