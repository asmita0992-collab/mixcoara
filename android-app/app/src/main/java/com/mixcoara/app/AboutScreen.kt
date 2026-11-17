package com.mixcoara.app

import android.content.Context
import android.os.Environment
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tlahuis.mixcoara.BuildConfig
import com.tlahuis.mixcoara.R
import java.io.File

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
    InfoSection("Seguridad", "El cambio de frase reenvuelve la misma llave maestra sin recifrar los archivos. Generar otra clave de recuperación sustituye la anterior en el encabezado actual. La sección de seguridad permite desbloquear mediante biometría (huella o rostro) o mediante el PIN, patrón, NIP o contraseña del dispositivo."),
    InfoSection("Dónde se guarda cada cosa", "Configuración: almacenamiento privado de la aplicación. Registro: mixcoara.log. Registro de cambios editable: Descargas/Mixcoara/registro_cambios.txt (y assets/registro_cambios.txt). Temporales y resultados: Descargas/Mixcoara."),
    InfoSection("Progreso, tiempos separados y bitácora", "Las acciones previas a la subida (copia temporal y cifrado AES-GCM/SIV) tienen su propio contador de tiempo independiente, de modo que el tiempo de subida mide exclusivamente la transferencia a la nube. En la Bitácora ambos tiempos aparecen desglosados con claridad."),
    InfoSection("Límites conocidos", "No sincroniza automáticamente carpetas: la subida y descarga se realizan bajo demanda. La velocidad depende del teléfono, la red y el servidor WebDAV. Ocultar el contenido en pantalla no impide que una persona con acceso a una sesión abierta vuelva a mostrarlo.")
)

val MixcoaraChangelog = listOf(
    ChangelogEntry("2.5.0", "2026-11-17", listOf(
        "Incorporación de los 8 temas visuales oficiales (Mixcoara, Mixco noche, Mixco día, Mixco oscuridad, Mixco esmeralda, Mixco ámbar, Mixco violeta y Mixco grafito).",
        "Soporte completo para Pantalla en Pantalla (PiP) automática y manual durante subidas y descargas.",
        "Corrección de permisos de notificaciones (Android 13+), exención de batería y bloqueo de suspensión (WakeLock/WifiLock) para transferencias continuas en segundo plano.",
        "Separación del cronómetro de preparación/cifrado y del cronómetro exclusivo de subida a la nube, con reporte detallado en la bitácora.",
        "Desbloqueo mediante biometría (huella o rostro) o credencial del dispositivo (PIN, patrón, NIP o contraseña).",
        "Lectura dinámica del historial de versiones desde el archivo de texto editable registro_cambios.txt."
    )),
    ChangelogEntry("2.4.0", "2026-11-12", listOf(
        "Ícono oficial sin recreaciones vectoriales y logotipo oficial en Acerca de; navegación inferior uniforme.",
        "Inicio disponible sin exigir permisos previos; elección directa entre WebDAV y bóvedas locales.",
        "Importación local de archivo o carpeta mediante diálogos, con selectores y ruta manual; pantalla local despejada.",
        "Visibilidad de archivos locales oculta por defecto y control para mostrar u ocultar.",
        "Biometría opcional ofrecida al desbloquear por primera vez, con cambios posteriores desde Configuración.",
        "Metadatos locales cifrados en .mixcoara/metadata.enc; migración de bóvedas existentes tras abrirlas con su contraseña.",
        "Registro de versiones revisado; correcciones de textos de seguridad y compatibilidad."
    )),
    ChangelogEntry("2.2.1", "2026-11-08", listOf(
        "Bóvedas locales independientes con creación, apertura, contraseña y recuperación propias; acceso desde Inicio sin WebDAV.",
        "Importación cifrada de directorios, explorador local y exportación de copias descifradas; conservación de archivos originales.",
        "Protección biométrica por bóveda con llaves de Android Keystore que requieren autenticación.",
        "Activación de huella con la contraseña ya verificada, sin inventar ni escribir una frase distinta.",
        "Compatibilidad conservada para archivos .mixcoara de la herramienta anterior."
    )),
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
        "La configuración pasó a la carpeta privada ~/config_mixcoara/privado/ y se cifra con una clave guardada en el Keystore de Android. La copia vieja de Descargas se migra y se borra.",
        "El paquete de certificados TLS se verifica antes de cada transferencia y de cada petición; si no se puede preparar, la app lo informa en lugar de fallar en silencio.",
        "El cifrado y el descifrado ya no cuentan como inactividad; el aviso «sin avance» sale a los 45 s.",
        "Los temporales se eliminan también cuando una tarea falla o se cancela, y al abrir la app. Nuevo botón para limpiarlos desde Ajustes.",
        "Nuevo registro de actividad con errores detallados (excepción, traceback, HTTP, rutas, tamaños, espacio libre), con visor, exportación y borrado.",
        "Nuevas pantallas «Acerca de Mixcoara» y «Registro de cambios».",
        "Los mensajes de error de subida y descarga ahora incluyen la causa (código HTTP o excepción).",
        "Corregido: una cancelación durante el cifrado de una carpeta podía perderse y la subida seguía."
    )),
    ChangelogEntry("1.4.0 a 1.6.0", "2026-10 / 2026-11", listOf(
        "Se usa requests en lugar de urllib para hablar con WebDAV.",
        "El mensaje de ayuda de la pantalla de conexión se ve completo.",
        "Temporales y caché en Descargas/Mixcoara; paquete de certificados en una ruta estable.",
        "La validación posterior a la subida es opcional.",
        "La app no elimina nada del dispositivo.",
        "La medición y el porcentaje se calculan por separado en subidas y en descargas.",
        "Nueva herramienta de cifrado local (bóveda local) y menú de Ajustes reorganizado.",
        "Compilación con el parche de desugaring para las notificaciones."
    )),
    ChangelogEntry("1.3.0", "2026-10-29", listOf(
        "Acceso biométrico con la frase guardada en el Keystore de Android.",
        "Permisos de segundo plano: ignorar optimización de batería, notificaciones y superposición.",
        "Servicio en primer plano durante transferencias y aviso de resultado si la app está en segundo plano."
    )),
    ChangelogEntry("1.2.0", "anterior a 2026-10", listOf(
        "Versión base: bóveda cifrada sobre WebDAV, explorador, descarga selectiva, cambio de frase y clave de recuperación."
    ))
)

fun parseChangelogText(rawText: String): List<ChangelogEntry> {
    val entries = mutableListOf<ChangelogEntry>()
    var currentVersion: String? = null
    var currentDate = ""
    var currentChanges = mutableListOf<String>()

    fun flushCurrent() {
        val ver = currentVersion
        if (!ver.isNullOrBlank() && currentChanges.isNotEmpty()) {
            entries.add(ChangelogEntry(ver, currentDate, currentChanges.toList()))
        }
        currentVersion = null
        currentDate = ""
        currentChanges = mutableListOf()
    }

    for (rawLine in rawText.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        if (line.startsWith("[") && line.endsWith("]")) {
            flushCurrent()
            val header = line.substring(1, line.length - 1)
            val parts = header.split("|", limit = 2)
            currentVersion = parts[0].trim().removePrefix("v").removePrefix("V")
            currentDate = if (parts.size > 1) parts[1].trim() else ""
        } else if (line.startsWith("-") || line.startsWith("•") || line.startsWith("*")) {
            val item = line.drop(1).trim()
            if (item.isNotEmpty()) {
                if (currentVersion == null) {
                    currentVersion = BuildConfig.VERSION_NAME
                }
                currentChanges.add(item)
            }
        } else if (currentVersion != null) {
            if (currentChanges.isEmpty()) {
                currentChanges.add(line)
            } else {
                val lastIdx = currentChanges.lastIndex
                currentChanges[lastIdx] = "${currentChanges[lastIdx]} $line"
            }
        }
    }
    flushCurrent()
    return entries
}

fun formatChangelogToText(entries: List<ChangelogEntry>): String =
    buildString {
        appendLine("# Archivo editable de Registro de Cambios de Mixcoara")
        appendLine("# Edita este archivo (Descargas/Mixcoara/registro_cambios.txt o assets/registro_cambios.txt)")
        appendLine("# Formato de cabecera: [Versión | Fecha]")
        appendLine("# Formato de cada cambio: - Descripción del cambio")
        appendLine()
        for (entry in entries) {
            appendLine("[${entry.version} | ${entry.date}]")
            for (change in entry.changes) {
                appendLine("- $change")
            }
            appendLine()
        }
    }

fun loadChangelogFromTxt(context: Context): Pair<List<ChangelogEntry>, String> {
    val externalDir =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Mixcoara",
        )
    val externalFile = File(externalDir, "registro_cambios.txt")

    val assetText =
        runCatching {
            context.assets.open("registro_cambios.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()

    if (!externalFile.exists()) {
        runCatching {
            externalDir.mkdirs()
            val initialContent = assetText ?: formatChangelogToText(MixcoaraChangelog)
            externalFile.writeText(initialContent, Charsets.UTF_8)
        }
    }

    val rawToParse =
        runCatching {
            if (externalFile.isFile && externalFile.canRead()) {
                externalFile.readText(Charsets.UTF_8)
            } else {
                assetText
            }
        }.getOrNull() ?: assetText

    if (!rawToParse.isNullOrBlank()) {
        val parsed = parseChangelogText(rawToParse)
        if (parsed.isNotEmpty()) {
            return parsed to externalFile.absolutePath
        }
    }
    return MixcoaraChangelog to externalFile.absolutePath
}

@Composable
fun AboutAndChangelogScreen() {
    val context = LocalContext.current
    var selectedSubTab by rememberSaveable { mutableStateOf(0) }
    var reloadKey by remember { mutableStateOf(0) }
    val (changelogEntries, changelogFilePath) =
        remember(reloadKey) { loadChangelogFromTxt(context) }

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
                item(key = "changelog_source_card") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Archivo de texto del registro de cambios",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Puedes escribir o editar tus cambios en:\n$changelogFilePath\n(o en assets/registro_cambios.txt antes de compilar).",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = { reloadKey++ },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Recargar archivo registro_cambios.txt")
                            }
                        }
                    }
                }
                items(changelogEntries, key = { "history_${it.version}_${it.date}" }) { entry ->
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
