package com.simplecast.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = LgRedAccent,
    secondary = NeonCyan,
    tertiary = ElectricPurple,
    background = MidnightBackground,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onPrimary = TextPrimary,
    onSecondary = MidnightBackground,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun SimpleCastTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
