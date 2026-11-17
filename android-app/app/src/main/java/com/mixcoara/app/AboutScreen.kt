package com.mixcoara.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tlahuis.mixcoara.BuildConfig
import com.tlahuis.mixcoara.R

data class InfoSection(val title: String, val content: String)
data class ChangelogEntry(val version: String, val date: String, val changes: List<String>)

val MixcoaraInfoSections = listOf(
    InfoSection("¿Qué es Mixcoara?", "Mixcoara es una bóveda cifrada personal para Android y WebDAV. Cifra archivos en el teléfono y los almacena en tu servidor. Se inspira en el esquema de Cryptomator, pero no es compatible con él."),
    InfoSection("Cómo protege tus datos", "La frase de la bóveda se transforma con scrypt para abrir la llave maestra. El contenido utiliza AES-256-GCM y los nombres AES-256-SIV. La clave de recuperación permite recuperar el acceso: sin la frase y sin esa clave, los archivos no se pueden recuperar. La configuración se conserva en el almacenamiento privado de la aplicación, protegida mediante Android KeyStore."),
    InfoSection("Privacidad de la pantalla principal", "El contenido de la bóveda se oculta al bloquear o cambiar de conexión y comienza oculto en una nueva sesión de la aplicación. El botón del ojo permite mostrar u ocultar la lista. Al ocultarla también se retira la selección visible. Esta opción no borra archivos, no cambia su cifrado y no equivale a bloquear la bóveda. Los nombres tampoco aparecen en la ruta de navegación mientras la lista está oculta."),
    InfoSection("Subir carpeta", "Usa «Subir carpeta» y «Seleccionar carpeta con Android», o escribe una ruta local. La selección nativa permite autorizar acceso a una carpeta y sus subcarpetas. La preparación usa copias temporales para que el núcleo pueda procesarlas. La validación posterior es opcional. El selector de Android puede restringir ciertas ubicaciones."),
    InfoSection("Subir archivo", "Selecciona un archivo con Android o escribe su ruta. La opción de validación se conserva tanto para selección nativa como para ruta manual. La subida se dirige a la carpeta de la bóveda que tengas abierta."),
    InfoSection("Explorar y descargar", "Muestra el contenido con el botón del ojo para navegar carpetas, seleccionar elementos y descargarlos. «Descargar todo» restaura la bóveda completa. Las descargas y el descifrado conservan el flujo de la versión anterior."),
    InfoSection("Cifrado local (Bóveda Local)", "Cifra o descifra un archivo sin subirlo a la nube, usando la llave de contenido de tu bóveda. Los cifrados se guardan en Descargas/Mixcoara/BovedaLocal y los descifrados en Descargas/Mixcoara/Descifrados."),
    InfoSection("Seguridad", "El cambio de frase reenvuelve la misma llave maestra sin recifrar los archivos. Generar otra clave de recuperación sustituye la anterior en el encabezado actual. La sección de seguridad conserva las opciones de acceso biométrico de la aplicación."),
    InfoSection("Dónde se guarda cada cosa", "Configuración: almacenamiento privado de la aplicación. Registro: mixcoara.log. Temporales y resultados: Descargas/Mixcoara. Los temporales son copias de trabajo; los resultados cifrados y descifrados son archivos distintos de los originales."),
    InfoSection("Progreso y bitácora", "El diálogo de operación muestra la etapa y, cuando existe un total medible, el porcentaje, bytes y contadores. La Bitácora es el registro técnico de actividad. El Registro de cambios de esta pantalla describe la evolución de versiones; no es el registro de tus operaciones."),
    InfoSection("Límites conocidos", "No sincroniza automáticamente carpetas: la subida y descarga se realizan bajo demanda. La velocidad depende del teléfono, la red y el servidor WebDAV. Ocultar el contenido en pantalla no impide que una persona con acceso a una sesión abierta vuelva a mostrarlo.")
)

val MixcoaraChangelog = listOf(
    ChangelogEntry("2.2.0", "2026-11-08", listOf(
        "Restauración del acceso a Acerca de y Registro de cambios desde la navegación principal.",
        "Restauración de mostrar/ocultar contenido; nombres y selección no se componen mientras la vista está oculta.",
        "Vista de bóveda oculta por defecto y al bloquear o cambiar de conexión.",
        "Ícono de Mixcoara en tarjetas y cabecera; eliminación de imágenes decorativas genéricas y de la marca duplicada en tarjetas.",
        "Versión informativa tomada de BuildConfig, con versión de compilación 2.2.0."
    )),
    ChangelogEntry("2.1.0", "", listOf(
        "Selección nativa de carpetas con OpenDocumentTree y conservación de la ruta manual.",
        "Preparación de selecciones nativas en segundo plano y temporales por operación.",
        "Ajustes de progreso por etapas, cancelación, limpieza y tratamiento de errores nulos."
    )),
    ChangelogEntry("2.0.0", "", listOf(
        "Migración de la interfaz a Android nativo con Jetpack Compose y puente al núcleo Python mediante Chaquopy.",
        "Continuidad de la bóveda WebDAV, cifrado local, seguridad y bitácora."
    )),
    ChangelogEntry("1.7.0", "2026-11-05", listOf(
        "Migración y blindaje de la configuración cifrada en KeyStore de Android.",
        "Limpieza automática de temporales tras cancelación, fallo o inicio de app.",
        "Registro de actividad rotativo con detalle de excepciones y redacción de contraseñas.",
        "Pantallas integradas de Acerca de y Registro de cambios.",
        "Validación TLS protegida."
    )),
    ChangelogEntry("1.4.0 a 1.6.0", "2026-10 / 2026-11", listOf(
        "Uso de requests robusto sobre WebDAV.",
        "Cifrado local con formato MIXCOARA-LOCAL-1.",
        "Medición de velocidad y telemetría por separado."
    )),
    ChangelogEntry("1.3.0", "2026-10-29", listOf(
        "Acceso biométrico con BiometricPrompt y KeyStore.",
        "Servicio en primer plano durante transferencias pesadas."
    )),
    ChangelogEntry("1.2.0", "Base", listOf(
        "Esquema criptográfico base AES-256-GCM y AES-256-SIV sobre WebDAV."
    ))
)

@Composable
fun AboutAndChangelogScreen() {
    var selectedSubTab by rememberSaveable { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedSubTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.secondary,
        ) {
            Tab(selected = selectedSubTab == 0, onClick = { selectedSubTab = 0 }, text = { Text("Acerca de") })
            Tab(selected = selectedSubTab == 1, onClick = { selectedSubTab = 1 }, text = { Text("Registro de cambios") })
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectedSubTab == 0) {
                item(key = "version") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(R.drawable.mixcoara_icon),
                                contentDescription = "Ícono de Mixcoara",
                                modifier = Modifier.size(64.dp).padding(end = 12.dp),
                                contentScale = ContentScale.Fit,
                            )
                            Column(Modifier.weight(1f)) {
                                Text("Mixcoara v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                Text("Bóveda cifrada personal para Android y WebDAV", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                items(MixcoaraInfoSections, key = { "info_${it.title}" }) { section ->
                    var expanded by rememberSaveable(section.title) { mutableStateOf(false) }
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(section.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                Icon(
                                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = if (expanded) "Contraer apartado" else "Expandir apartado",
                                )
                            }
                            AnimatedVisibility(visible = expanded) {
                                Column {
                                    Spacer(Modifier.height(10.dp))
                                    Text(section.content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            } else {
                items(MixcoaraChangelog, key = { "history_${it.version}" }) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("v${entry.version}", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                if (entry.date.isNotBlank()) Text(entry.date, style = MaterialTheme.typography.labelSmall)
                            }
                            entry.changes.forEach { change ->
                                Text("• $change", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
