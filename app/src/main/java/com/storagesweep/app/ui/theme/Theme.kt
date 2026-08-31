package com.storagesweep.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    background = SweepBackground,
    surface = SweepSurface,
    surfaceVariant = SweepSurfaceElevated,
    primary = SweepAccent,
    onBackground = SweepOnBackground,
    onSurface = SweepOnBackground,
    onSurfaceVariant = SweepOnSurfaceMuted
)

private val LightColors = lightColorScheme(
    primary = SweepAccent
)

@Composable
fun StorageSweepTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SweepTypography,
        content = content
    )
}
