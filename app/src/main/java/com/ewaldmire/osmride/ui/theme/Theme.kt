package com.ewaldmire.osmride.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BikeGreen = Color(0xFF2E7D32)
private val BikeGreenDark = Color(0xFF8BC34A)
private val RoadOrange = Color(0xFFEF6C00)

private val LightColors = lightColorScheme(
    primary = BikeGreen,
    secondary = RoadOrange,
)

private val DarkColors = darkColorScheme(
    primary = BikeGreenDark,
    secondary = RoadOrange,
)

@Composable
fun OsmRideTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
