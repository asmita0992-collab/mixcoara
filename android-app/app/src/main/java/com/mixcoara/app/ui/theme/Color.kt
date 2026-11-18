package com.mixcoara.app.ui.theme

import androidx.compose.ui.graphics.Color

val AmberSecondary = Color(0xFFFFD700)
val CyanLight = Color(0xFF00E5FF)

enum class MixcoaraThemeId(
    val id: String,
    val title: String,
    val description: String,
    val previewBg: Color,
    val previewAccent: Color,
    val isLight: Boolean = false,
) {
    MIXCO(
        id = "mixco",
        title = "Mixcoara",
        description = "Noche profunda, turquesa nube y oro sol",
        previewBg = Color(0xFF0B1021),
        previewAccent = Color(0xFF00E5FF),
    ),
    BLUEPRINT(
        id = "blueprint",
        title = "Mixco noche",
        description = "Azul noche y oro sol",
        previewBg = Color(0xFF151E32),
        previewAccent = Color(0xFFFFD700),
    ),
    LIGHT(
        id = "light",
        title = "Mixco día",
        description = "Claro · lectura diurna con identidad Mixcoara",
        previewBg = Color(0xFFEDF4F7),
        previewAccent = Color(0xFF00B4D8),
        isLight = true,
    ),
    DARK(
        id = "dark",
        title = "Mixco oscuridad",
        description = "Oscuro · turquesa nube, noche y oro",
        previewBg = Color(0xFF0B1021),
        previewAccent = Color(0xFF00E5FF),
    ),
    EMERALD(
        id = "emerald",
        title = "Mixco esmeralda",
        description = "Oscuro · verde",
        previewBg = Color(0xFF061510),
        previewAccent = Color(0xFF34D399),
    ),
    AMBER(
        id = "amber",
        title = "Mixco ámbar",
        description = "Oscuro · dorado",
        previewBg = Color(0xFF13100B),
        previewAccent = Color(0xFFFBBF24),
    ),
    VIOLET(
        id = "violet",
        title = "Mixco violeta",
        description = "Oscuro · violeta",
        previewBg = Color(0xFF0E0C19),
        previewAccent = Color(0xFFC084FC),
    ),
    MONOCHROME(
        id = "monochrome",
        title = "Mixco grafito",
        description = "Oscuro · gris y plata",
        previewBg = Color(0xFF0A0E17),
        previewAccent = Color(0xFFE2E8F0),
    );

    companion object {
        fun fromId(value: String?): MixcoaraThemeId =
            entries.firstOrNull { it.id == value } ?: MIXCO
    }
}
