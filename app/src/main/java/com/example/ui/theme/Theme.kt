package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SoloSignalLightColorScheme = lightColorScheme(
    primary = InkBlack,
    onPrimary = CardWhite,
    primaryContainer = PastelMint,
    onPrimaryContainer = InkBlack,
    secondary = PastelLavender,
    onSecondary = InkBlack,
    secondaryContainer = PastelSky,
    onSecondaryContainer = InkBlack,
    tertiary = PastelPeach,
    onTertiary = InkBlack,
    tertiaryContainer = PastelPink,
    onTertiaryContainer = InkBlack,
    background = PaleLavenderBg,
    onBackground = InkBlack,
    surface = CardWhite,
    onSurface = InkBlack,
    surfaceVariant = SoftLavenderSurface,
    onSurfaceVariant = InkCharcoal,
    outline = SubtleBorder
)

val SoloSignalShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SoloSignalLightColorScheme,
        typography = Typography,
        shapes = SoloSignalShapes,
        content = content
    )
}

@Composable
fun SoloSignalTheme(
    content: @Composable () -> Unit
) {
    MyApplicationTheme(content = content)
}
