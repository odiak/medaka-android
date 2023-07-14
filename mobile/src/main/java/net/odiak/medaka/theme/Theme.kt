package net.odiak.medaka.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color


private val lightColorScheme = lightColorScheme(
    primary = Purple200,
    secondary = Teal200,
    /* Other default co lors to override
    background = Color.White,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color.Black,
    onSurface = Color.Black,
    */
)

private val darkColorScheme = darkColorScheme(
    primary = Purple700,
    secondary = Teal200,
    onPrimary = Color.White
)

@Composable
fun MedakaTheme(
    content: @Composable () -> Unit
) {
    val colorPalette = if (isSystemInDarkTheme()) {
        darkColorScheme
    } else {
        lightColorScheme
    }

    MaterialTheme(
        colorScheme = colorPalette,
        typography = Typography,
        // For shapes, we generally recommend using the default Material Wear shapes which are
        // optimized for round and non-round devices.
        content = content
    )
}