package com.smartstudent.planner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Lavender background, white cards, one purple accent, near-black pills.
private val LightColors = lightColorScheme(
    primary = Color(0xFF6C69C8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDAD8F8),
    onPrimaryContainer = Color(0xFF1C1B2E),
    secondaryContainer = Color(0xFFF0D8C0),   // peach tile
    onSecondaryContainer = Color(0xFF3A2A1C),
    tertiaryContainer = Color(0xFFDCE8F6),    // light blue tile
    onTertiaryContainer = Color(0xFF1B2A3C),
    background = Color(0xFFBDBBF2),
    onBackground = Color(0xFF1C1B2E),
    surface = Color.White,
    onSurface = Color(0xFF1C1B2E),
    surfaceVariant = Color(0xFFEFEDF7),       // light pills
    onSurfaceVariant = Color(0xFF6E6C7E),
    inverseSurface = Color(0xFF1E1D26),       // dark pills
    inverseOnSurface = Color.White,
    outline = Color(0xFFC9C7DD),
    error = Color(0xFFC0392B),
    errorContainer = Color(0xFFF8D7D2),
    onErrorContainer = Color(0xFF5A1A12),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9C99F2),
    onPrimary = Color(0xFF16153A),
    primaryContainer = Color(0xFF3A3770),
    onPrimaryContainer = Color(0xFFEDEBFF),
    secondaryContainer = Color(0xFF4B3C33),
    onSecondaryContainer = Color(0xFFF4E4D6),
    tertiaryContainer = Color(0xFF2D3D55),
    onTertiaryContainer = Color(0xFFDCE8F6),
    background = Color(0xFF15142B),
    onBackground = Color(0xFFEDEBFF),
    surface = Color(0xFF232142),
    onSurface = Color(0xFFEDEBFF),
    surfaceVariant = Color(0xFF2E2B52),
    onSurfaceVariant = Color(0xFFB4B1CE),
    inverseSurface = Color(0xFFE8E6F5),
    inverseOnSurface = Color(0xFF1E1D26),
    outline = Color(0xFF4A4778),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF5A2A26),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val PlannerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun PlannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = PlannerShapes,
        content = content,
    )
}
