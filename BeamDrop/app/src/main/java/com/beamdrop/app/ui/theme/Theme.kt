package com.beamdrop.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val ColorOnSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF00424E)

private fun beamColors() = if (Palette.current.isDark) darkColorScheme(
    primary = Primary, onPrimary = PrimaryContainer, primaryContainer = PrimaryContainer, onPrimaryContainer = OnPrimaryContainer,
    inversePrimary = Primary, secondary = Secondary, onSecondary = InkDim, secondaryContainer = SecondaryContainer,
    onSecondaryContainer = ColorOnSecondaryContainer, tertiary = Tertiary, onTertiary = InkDim, tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = Tertiary, background = Ink, onBackground = OnSurface, surface = Ink, onSurface = OnSurface,
    surfaceVariant = SurfaceHighest, onSurfaceVariant = OnSurfaceVariant, surfaceTint = Primary, inverseSurface = OnSurface,
    inverseOnSurface = Ink, error = Error, onError = InkDim, errorContainer = ErrorContainer, onErrorContainer = Error,
    outline = Outline, outlineVariant = OutlineVariant, scrim = InkDim
) else lightColorScheme(
    primary = Primary, onPrimary = androidx.compose.ui.graphics.Color.White, primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer, secondary = Secondary, onSecondary = androidx.compose.ui.graphics.Color.White,
    tertiary = Tertiary, background = Ink, onBackground = OnSurface, surface = Ink, onSurface = OnSurface,
    surfaceVariant = SurfaceHighest, onSurfaceVariant = OnSurfaceVariant, surfaceTint = Primary, error = Error,
    errorContainer = ErrorContainer, outline = Outline, outlineVariant = OutlineVariant
)

private val BeamShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun BeamDropTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = beamColors(),
        typography = if (com.beamdrop.app.i18n.I18n.persian) PersianTypography else BeamTypography,
        shapes = BeamShapes,
        content = content
    )
}
