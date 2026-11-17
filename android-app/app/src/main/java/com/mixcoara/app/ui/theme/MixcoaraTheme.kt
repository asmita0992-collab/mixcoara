package com.mixcoara.app.ui.theme

import android.app.Activity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Esquema para Mixcoara (MIXCO) - Noche profunda, turquesa nube y oro sol
private val MixcoScheme =
    darkColorScheme(
        primary = Color(0xFF00E5FF),       // Turquoise Cloud
        onPrimary = Color(0xFF0B1021),     // Deep Night
        primaryContainer = Color(0xFF151E32), // Surface
        onPrimaryContainer = Color(0xFF00E5FF),
        secondary = Color(0xFFFFD700),      // Sun Gold
        onSecondary = Color(0xFF0B1021),
        secondaryContainer = Color(0xFF151E32),
        onSecondaryContainer = Color(0xFFFFD700),
        tertiary = Color(0xFF00E5FF),
        onTertiary = Color(0xFF0B1021),
        background = Color(0xFF0B1021),     // Deep Night
        onBackground = Color(0xFFFFFFFF),   // White
        surface = Color(0xFF151E32),        // Surface
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF0B1021),
        onSurfaceVariant = Color(0xFFA0A0A0), // Gray
        outline = Color(0xFF151E32),
        error = Color(0xFFFFB4AB),
    )

// Esquema para Mixco noche (BLUEPRINT) - Azul noche y oro sol
private val BlueprintScheme =
    darkColorScheme(
        primary = Color(0xFFFFD700),       // Sun Gold
        onPrimary = Color(0xFF151E32),     // Surface
        primaryContainer = Color(0xFF151E32),
        onPrimaryContainer = Color(0xFFFFD700),
        secondary = Color(0xFF00E5FF),     // Turquoise Cloud
        onSecondary = Color(0xFF151E32),
        secondaryContainer = Color(0xFF151E32),
        onSecondaryContainer = Color(0xFF00E5FF),
        tertiary = Color(0xFFFFD700),
        onTertiary = Color(0xFF151E32),
        background = Color(0xFF151E32),    // Surface
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF0B1021),       // Deep Night
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF151E32),
        onSurfaceVariant = Color(0xFFA0A0A0),
        outline = Color(0xFF0B1021),
        error = Color(0xFFFFB4AB),
    )

// Esquema para Mixco día (LIGHT) - Claro · lectura diurna con identidad Mixcoara
private val LightScheme =
    lightColorScheme(
        primary = Color(0xFF00B4D8),       // Turquesa ajustado para contraste
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDEF2F2),
        onPrimaryContainer = Color(0xFF0B1021),
        secondary = Color(0xFFFFD700),      // Sun Gold
        onSecondary = Color(0xFF0B1021),
        secondaryContainer = Color(0xFFD2E9ED),
        onSecondaryContainer = Color(0xFF151E32),
        tertiary = Color(0xFF00B4D8),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFEDF4F7),    // Fondo claro
        onBackground = Color(0xFF151E32),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF102D3C),
        surfaceVariant = Color(0xFFE1F1F2),
        onSurfaceVariant = Color(0xFF426075),
        outline = Color(0xFFB2CBD4),
        error = Color(0xFFB3261E),
    )

// Esquema para Mixco oscuridad (DARK) - Oscuro · turquesa nube, noche y oro
private val DarkScheme =
    darkColorScheme(
        primary = Color(0xFF00E5FF),       // Turquoise Cloud
        onPrimary = Color(0xFF0B1021),     // Deep Night
        primaryContainer = Color(0xFF151E32), // Surface
        onPrimaryContainer = Color(0xFF00E5FF),
        secondary = Color(0xFFFFD700),      // Sun Gold
        onSecondary = Color(0xFF0B1021),
        secondaryContainer = Color(0xFF151E32),
        onSecondaryContainer = Color(0xFFFFD700),
        tertiary = Color(0xFF00E5FF),
        onTertiary = Color(0xFF0B1021),
        background = Color(0xFF0B1021),     // Deep Night
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF151E32),        // Surface
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF0B1021),
        onSurfaceVariant = Color(0xFFA0A0A0),
        outline = Color(0xFF151E32),
        error = Color(0xFFFFB4AB),
    )

private val EmeraldScheme =
    darkColorScheme(
        primary = Color(0xFF34D399),
        onPrimary = Color(0xFF061510),
        primaryContainer = Color(0xFF1C563F),
        onPrimaryContainer = Color(0xFFE4FFEE),
        secondary = Color(0xFF6EE7B7),
        onSecondary = Color(0xFF06281E),
        secondaryContainer = Color(0xFF153F30),
        onSecondaryContainer = Color(0xFFF4FFF8),
        tertiary = Color(0xFF34D399),
        onTertiary = Color(0xFF061510),
        background = Color(0xFF061510),
        onBackground = Color(0xFFE4F7ED),
        surface = Color(0xFF0E251D),
        onSurface = Color(0xFFF4FFF8),
        surfaceVariant = Color(0xFF16372A),
        onSurfaceVariant = Color(0xFFA6C8B7),
        outline = Color(0xFF2B624A),
        error = Color(0xFFFFB4AB),
    )

private val AmberScheme =
    darkColorScheme(
        primary = Color(0xFFFBBF24),
        onPrimary = Color(0xFF13100B),
        primaryContainer = Color(0xFF5C401E),
        onPrimaryContainer = Color(0xFFFFF2D9),
        secondary = Color(0xFFF59E0B),
        onSecondary = Color(0xFF1F1406),
        secondaryContainer = Color(0xFF3B2B19),
        onSecondaryContainer = Color(0xFFFFF9EE),
        tertiary = Color(0xFFFBBF24),
        onTertiary = Color(0xFF13100B),
        background = Color(0xFF13100B),
        onBackground = Color(0xFFFFF0DB),
        surface = Color(0xFF2A2115),
        onSurface = Color(0xFFFFF9EE),
        surfaceVariant = Color(0xFF352819),
        onSurfaceVariant = Color(0xFFD0BDA5),
        outline = Color(0xFF685033),
        error = Color(0xFFFFB4AB),
    )

private val VioletScheme =
    darkColorScheme(
        primary = Color(0xFFC084FC),
        onPrimary = Color(0xFF0E0C19),
        primaryContainer = Color(0xFF4A3471),
        onPrimaryContainer = Color(0xFFFFF0FF),
        secondary = Color(0xFFD8B4FE),
        onSecondary = Color(0xFF1F1235),
        secondaryContainer = Color(0xFF3A2963),
        onSecondaryContainer = Color(0xFFFFF7FF),
        tertiary = Color(0xFFC084FC),
        onTertiary = Color(0xFF0E0C19),
        background = Color(0xFF0E0C19),
        onBackground = Color(0xFFF4EDFF),
        surface = Color(0xFF241D3D),
        onSurface = Color(0xFFFFF7FF),
        surfaceVariant = Color(0xFF2D2450),
        onSurfaceVariant = Color(0xFFC4B6E2),
        outline = Color(0xFF5C4686),
        error = Color(0xFFFFB4AB),
    )

private val MonochromeScheme =
    darkColorScheme(
        primary = Color(0xFFE2E8F0),
        onPrimary = Color(0xFF0A0E17),
        primaryContainer = Color(0xFF38465B),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFCBD5E1),
        onSecondary = Color(0xFF0F172A),
        secondaryContainer = Color(0xFF2D3949),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFF94A3B8),
        onTertiary = Color(0xFF0A0E17),
        background = Color(0xFF0A0E17),
        onBackground = Color(0xFFE2E8F0),
        surface = Color(0xFF1A2432),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF263346),
        onSurfaceVariant = Color(0xFFB4C0D0),
        outline = Color(0xFF526173),
        error = Color(0xFFFFB4AB),
    )

private fun schemeFor(themeId: MixcoaraThemeId): ColorScheme =
    when (themeId) {
        MixcoaraThemeId.MIXCO -> MixcoScheme
        MixcoaraThemeId.BLUEPRINT -> BlueprintScheme
        MixcoaraThemeId.LIGHT -> LightScheme
        MixcoaraThemeId.DARK -> DarkScheme
        MixcoaraThemeId.EMERALD -> EmeraldScheme
        MixcoaraThemeId.AMBER -> AmberScheme
        MixcoaraThemeId.VIOLET -> VioletScheme
        MixcoaraThemeId.MONOCHROME -> MonochromeScheme
    }

private val MixcoaraTypography =
    Typography().let { base ->
        base.copy(
            displaySmall = base.displaySmall.copy(fontFamily = FontFamily.SansSerif),
            headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.SansSerif),
            titleLarge = base.titleLarge.copy(fontFamily = FontFamily.SansSerif),
            bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
            bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
            labelLarge = base.labelLarge.copy(fontFamily = FontFamily.SansSerif),
        )
    }

@Composable
fun MixcoaraTheme(
    themeId: MixcoaraThemeId = MixcoaraThemeId.MIXCO,
    content: @Composable () -> Unit,
) {
    val colorScheme = schemeFor(themeId)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = themeId.isLight
            controller.isAppearanceLightNavigationBars = themeId.isLight
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MixcoaraTypography,
        shapes = MaterialTheme.shapes.copy(
            small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        ),
        content = content,
    )
}
