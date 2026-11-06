package com.beamdrop.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Accent colours the user can pick in Settings → Appearance. */
enum class Accent(val key: String, val label: String, val swatch: Color) {
    INDIGO("indigo", "Indigo", Color(0xFF4F46E5)),
    BLUE("blue", "Blue", Color(0xFF2563EB)),
    GREEN("green", "Green", Color(0xFF059669)),
    ORANGE("orange", "Orange", Color(0xFFEA580C)),
    PINK("pink", "Pink", Color(0xFFDB2777));

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: INDIGO
    }
}

/** One full set of colours. The app's colour names below read from the current one. */
class PaletteColors(
    val ink: Color, val inkDim: Color, val surfaceLow: Color, val surfaceContainer: Color,
    val surfaceHigh: Color, val surfaceHighest: Color, val onSurface: Color, val onSurfaceVariant: Color,
    val outline: Color, val outlineVariant: Color, val primary: Color, val primaryContainer: Color,
    val primaryDeep: Color, val violet: Color, val onPrimaryContainer: Color, val secondary: Color,
    val secondaryDeep: Color, val secondaryContainer: Color, val tertiary: Color, val tertiaryContainer: Color,
    val tertiaryDeep: Color, val error: Color, val errorContainer: Color, val sendGradient: List<Color>,
    val isDark: Boolean
)

object Palette {
    var current: PaletteColors = build(dark = true, accent = Accent.INDIGO)
        private set

    fun apply(dark: Boolean, accent: Accent) {
        current = build(dark, accent)
    }

    private fun build(dark: Boolean, accent: Accent): PaletteColors {
        // Accent: [container, light text on dark bg, dark text on light bg, gradient]
        val (container, onDark, onLight, gradient) = when (accent) {
            Accent.INDIGO -> Quad(Color(0xFF4F46E5), Color(0xFFC3C0FF), Color(0xFF4338CA), listOf(Color(0xFF4F46E5), Color(0xFF6D4BEA), Color(0xFF7C3AED)))
            Accent.BLUE -> Quad(Color(0xFF2563EB), Color(0xFFBFD4FF), Color(0xFF1D4ED8), listOf(Color(0xFF2563EB), Color(0xFF3B82F6), Color(0xFF0EA5E9)))
            Accent.GREEN -> Quad(Color(0xFF059669), Color(0xFFA7F3D0), Color(0xFF047857), listOf(Color(0xFF047857), Color(0xFF059669), Color(0xFF10B981)))
            Accent.ORANGE -> Quad(Color(0xFFEA580C), Color(0xFFFFD8B5), Color(0xFFC2410C), listOf(Color(0xFFEA580C), Color(0xFFF97316), Color(0xFFF59E0B)))
            Accent.PINK -> Quad(Color(0xFFDB2777), Color(0xFFFBCFE8), Color(0xFFBE185D), listOf(Color(0xFFDB2777), Color(0xFFEC4899), Color(0xFFA855F7)))
        }
        return if (dark) PaletteColors(
            ink = Color(0xFF0F131D), inkDim = Color(0xFF0A0E18), surfaceLow = Color(0xFF171B26),
            surfaceContainer = Color(0xFF1C1F2A), surfaceHigh = Color(0xFF262A35), surfaceHighest = Color(0xFF313540),
            onSurface = Color(0xFFDFE2F1), onSurfaceVariant = Color(0xFFC7C4D8), outline = Color(0xFF918FA1),
            outlineVariant = Color(0xFF464555), primary = onDark, primaryContainer = container,
            primaryDeep = gradient.first(), violet = gradient.last(), onPrimaryContainer = onDark,
            secondary = Color(0xFF4CD7F6), secondaryDeep = Color(0xFF06B6D4), secondaryContainer = Color(0xFF03B5D3),
            tertiary = Color(0xFF4EDEA3), tertiaryContainer = Color(0xFF006E4B), tertiaryDeep = Color(0xFF10B981),
            error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), sendGradient = gradient, isDark = true
        ) else PaletteColors(
            ink = Color(0xFFF5F6FA), inkDim = Color(0xFFECEEF4), surfaceLow = Color(0xFFFFFFFF),
            surfaceContainer = Color(0xFFEDEFF5), surfaceHigh = Color(0xFFE3E6EF), surfaceHighest = Color(0xFFD8DCE6),
            onSurface = Color(0xFF161A26), onSurfaceVariant = Color(0xFF4A4F63), outline = Color(0xFF7D8296),
            outlineVariant = Color(0xFFC6CAD6), primary = onLight, primaryContainer = container,
            primaryDeep = gradient.first(), violet = gradient.last(), onPrimaryContainer = Color.White,
            secondary = Color(0xFF0E7490), secondaryDeep = Color(0xFF0891B2), secondaryContainer = Color(0xFF06B6D4),
            tertiary = Color(0xFF047857), tertiaryContainer = Color(0xFFD1FAE5), tertiaryDeep = Color(0xFF059669),
            error = Color(0xFFB3261E), errorContainer = Color(0xFFF9DEDC), sendGradient = gradient, isDark = false
        )
    }

    private data class Quad(val a: Color, val b: Color, val c: Color, val d: List<Color>)
}

// The names used all over the UI. They follow the user's theme and accent.
val Ink: Color get() = Palette.current.ink
val InkDim: Color get() = Palette.current.inkDim
val SurfaceLow: Color get() = Palette.current.surfaceLow
val SurfaceContainer: Color get() = Palette.current.surfaceContainer
val SurfaceHigh: Color get() = Palette.current.surfaceHigh
val SurfaceHighest: Color get() = Palette.current.surfaceHighest

val OnSurface: Color get() = Palette.current.onSurface
val OnSurfaceVariant: Color get() = Palette.current.onSurfaceVariant
val Outline: Color get() = Palette.current.outline
val OutlineVariant: Color get() = Palette.current.outlineVariant

val Primary: Color get() = Palette.current.primary
val PrimaryContainer: Color get() = Palette.current.primaryContainer
val PrimaryDeep: Color get() = Palette.current.primaryDeep
val Violet: Color get() = Palette.current.violet
val OnPrimaryContainer: Color get() = Palette.current.onPrimaryContainer
val Secondary: Color get() = Palette.current.secondary
val SecondaryDeep: Color get() = Palette.current.secondaryDeep
val SecondaryContainer: Color get() = Palette.current.secondaryContainer
val Tertiary: Color get() = Palette.current.tertiary
val TertiaryContainer: Color get() = Palette.current.tertiaryContainer
val TertiaryDeep: Color get() = Palette.current.tertiaryDeep
val Error: Color get() = Palette.current.error
val ErrorContainer: Color get() = Palette.current.errorContainer

val SendGradient: List<Color> get() = Palette.current.sendGradient
val ReceiveGradient: List<Color> = listOf(Color(0xFF06B6D4), Color(0xFF0AB9C6), Color(0xFF10B981))
val BeamGradient: List<Color> get() = listOf(Secondary, PrimaryContainer, Primary)
val WarmGlow: Color get() = Palette.current.primary
