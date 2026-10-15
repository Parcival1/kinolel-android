package org.jellyfin.mobile.ui.utils

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = remember {
        @Suppress("MagicNumber")
        darkColors(
            primary = Color(0xFF9AA4FF), // KinoLel: Lavendel
            primaryVariant = Color(0xFF151B2A),
            background = Color(0xFF0B0F18),
            surface = Color(0xFF151B2A),
            error = Color(0xFFCF6679),
            onPrimary = Color(0xFF0B0F18),
            onSecondary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White,
            onError = Color.White,
        )
    }
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
