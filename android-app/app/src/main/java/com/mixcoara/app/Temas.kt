package com.mixcoara.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mixcoara.app.ui.theme.MixcoaraThemeId

/**
 * Pantalla de Configuración con dos pestañas: la configuración de siempre
 * y los temas visuales.
 */
@Composable
fun ConfiguracionScreen(
    activity: FragmentActivity,
    model: MixcoaraViewModel,
    onEditarConexion: () -> Unit,
) {
    var pestana by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = pestana) {
            Tab(
                selected = pestana == 0,
                onClick = { pestana = 0 },
                text = { Text("Configuración") },
            )
            Tab(
                selected = pestana == 1,
                onClick = { pestana = 1 },
                text = { Text("Temas") },
            )
        }
        when (pestana) {
            0 -> ConfiguracionBasica(activity, model, onEditarConexion)
            else -> TemasScreen(model)
        }
    }
}

@Composable
fun TemasScreen(model: MixcoaraViewModel) {
    val state by model.state.collectAsStateWithLifecycle()

    ScreenList {
        HeroCard(
            title = "Temas",
            body = "Elige el aspecto de Mixcoara. El cambio se aplica al instante y se recuerda.",
        )
        SectionLabel("TEMAS VISUALES")
        MixcoaraThemeId.entries.forEach { tema ->
            TemaCard(
                tema = tema,
                activo = tema == state.themeId,
                onClick = { model.selectTheme(tema) },
            )
        }
    }
}

@Composable
private fun TemaCard(
    tema: MixcoaraThemeId,
    activo: Boolean,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(28.dp).background(tema.previewBg, CircleShape))
                Box(Modifier.size(28.dp).background(tema.previewAccent, CircleShape))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tema.title, fontWeight = FontWeight.Bold)
                Text(
                    tema.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (activo) {
                Text(
                    "Activo",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
