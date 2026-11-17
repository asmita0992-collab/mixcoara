package com.mixcoara.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val MixcoaraColors =
    darkColorScheme(
        primary = Color(0xFF00E5FF),
        onPrimary = Color(0xFF001014),
        primaryContainer = Color(0xFF004B57),
        onPrimaryContainer = Color(0xFFB6F5FF),
        secondary = Color(0xFFFFD700),
        onSecondary = Color(0xFF241F00),
        secondaryContainer = Color(0xFF514500),
        onSecondaryContainer = Color(0xFFFFF1A6),
        tertiary = Color(0xFFFFD700),
        background = Color(0xFF0B1021),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF151E32),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF202B43),
        onSurfaceVariant = Color(0xFFA0A0A0),
        error = Color(0xFFFFB4AB),
    )

private val MixcoaraTypography =
    Typography().let { base ->
        base.copy(
            displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif),
            headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif),
            titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif),
            bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
            bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
            labelLarge = base.labelLarge.copy(fontFamily = FontFamily.SansSerif),
        )
    }

@Composable
fun MixcoaraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MixcoaraColors,
        typography = MixcoaraTypography,
        shapes = MaterialTheme.shapes.copy(
            small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        ),
        content = content,
    )
}
