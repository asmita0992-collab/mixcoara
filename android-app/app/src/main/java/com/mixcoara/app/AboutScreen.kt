package com.mixcoara.app

import android.content.Context
import android.os.Environment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tlahuis.mixcoara.BuildConfig
import com.tlahuis.mixcoara.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InfoSection(val title: String, val content: String)
data class ChangelogEntry(val version: String, val date: String, val changes: List<String>)

val MixcoaraInfoSections = listOf(
    InfoSection("¿Qué es Mixcoara?", "Mixcoara es una bóveda cifrada personal para Android. Permite guardar directorios cifrados en el teléfono de forma independiente, o usar una bóveda en tu servidor WebDAV. Se inspira en Cryptomator, pero los formatos no son compatibles."),
    InfoSection("Bóvedas locales independientes", "Desde Inicio elige Local o WebDAV. Puedes crear una bóveda local, añadir una existente y abrirla con su propia contraseña, sin configurar la nube. Su clave de recuperación es independiente y debes guardarla al crearla. La navegación principal y las preferencias se comparten en toda la aplicación."),
    InfoSection("Importar carpetas y archivos", "Importar y cifrar carpeta completa conserva sus archivos, subcarpetas y directorios vacíos dentro del índice cifrado. También puedes indicar la ruta de un archivo o carpeta. Los originales no se borran. Si ya existe un elemento con el mismo nombre en el directorio de destino, se rechaza la importación para no sobrescribirlo. Los enlaces simbólicos no se admiten."),
    InfoSection("Explorar y exportar una bóveda local", "Abre las carpetas desde el explorador de Mixcoara. Exportar copia descifrada crea una copia fuera de la bóveda; esa copia queda SIN cifrar. Una carpeta se exporta con toda su estructura. No se reemplazan destinos existentes. Bloquear descarta la sesión local, no borra la bóveda."),
    InfoSection("Cómo protege tus datos", "La nube conserva su formato AES-256-GCM para contenido y AES-256-SIV para nombres. La nueva bóveda local usa el formato MIXCOARA-DIRECTORY-1: una llave maestra aleatoria de 256 bits, protegida mediante scrypt y AES-GCM, cifra los archivos y el índice que contiene sus nombres y estructura. Ni la contraseña ni la clave de recuperación se guardan en texto claro en la bóveda."),
    InfoSection("Biometría: no necesitas otra frase", "Al abrir una bóveda por primera vez se pregunta si quieres activar la biometría (huella o rostro compatibles con Android). No se pide una contraseña distinta; se protege la verificada en esa sesión mediante Android Keystore. Puedes cambiar tu elección en Configuración. Si deja de funcionar, usa la contraseña y activa de nuevo la biometría."),
    InfoSection("Recuperación de una bóveda local", "En Abrir bóveda local, elige Olvidé la contraseña. Introduce su clave de recuperación y establece una contraseña nueva. La clave debe corresponder a esa bóveda. La operación no recifra los archivos. La biometría debe activarse de nuevo después. Sin contraseña ni recuperación, no hay una puerta trasera para abrir los datos."),
    InfoSection("Privacidad de la pantalla principal", "Las vistas de las bóvedas en nube y local comienzan ocultas. El ojo muestra u oculta archivos y rutas. Ocultar solo protege frente a miradas casuales, no equivale a bloquear; utiliza Bloquear al terminar."),
    InfoSection("Subir a la nube", "Las subidas de archivos y carpetas conservan los selectores nativos, la alternativa de rutas manuales y la validación opcional. Los datos se envían a la carpeta de nube actualmente abierta."),
    InfoSection("Compatibilidad .mixcoara anterior", "La herramienta anterior para archivos individuales sigue en Local, dentro del área de nube, bajo Compatibilidad: archivos .mixcoara anteriores. Usa la llave de la bóveda de nube abierta, no la contraseña de una nueva bóveda local. Los archivos existentes no se convierten ni migran automáticamente."),
    InfoSection("Ubicaciones y copias de seguridad", "El selector de bóvedas y carpetas locales admite el almacenamiento interno principal. Conserva una copia íntegra del directorio: .mixcoara/metadata.enc, index.gcm y d/. La carpeta .mixcoara está oculta, pero una carpeta oculta no sustituye al cifrado. Las bóvedas antiguas con mixcoara.local.json se convierten al abrirlas correctamente. Protege las copias descifradas que exportes."),
    InfoSection("Progreso y bitácora", "El diálogo muestra la etapa y un porcentaje real cuando se conocen los bytes totales. La nube conserva su flujo de transferencias y su bitácora. El Registro de cambios describe versiones, no las operaciones realizadas con tus archivos."),
    InfoSection("Límites conocidos", "La bóveda local no es un disco virtual ni sincroniza automáticamente con carpetas externas: debes importar y exportar bajo demanda. El acceso nativo a proveedores en nube o USB desde el selector local no está implementado; usa almacenamiento interno o una ruta local directamente accesible. La nueva implementación requiere pruebas en tu dispositivo y no es compatible con bóvedas Cryptomator.")
)

private const val NOMBRE_REGISTRO = "registro_cambios.txt"

private val CABECERA_REGISTRO = Regex("""^\[\s*([^|\]]+?)\s*\|\s*([^\]]*?)\s*\]$""")

/**
 * Lee el registro de cambios. Primero intenta Descargas/Mixcoara/registro_cambios.txt
 * (editable por el usuario); si no existe o no se puede leer, usa assets/registro_cambios.txt.
 */
fun leerRegistroCambios(context: Context): List<ChangelogEntry> {
    val desdeDescargas = runCatching {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Mixcoara/$NOMBRE_REGISTRO")
            .takeIf { it.isFile && it.canRead() }
            ?.readText(Charsets.UTF_8)
    }.getOrNull()
    val texto = desdeDescargas ?: runCatching {
        context.assets.open(NOMBRE_REGISTRO).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrDefault("")
    return parsearRegistroCambios(texto)
}

/**
 * Convierte el texto del registro en entradas. Cabecera: [Versión | Fecha] (la fecha puede ir vacía).
 * Cada cambio empieza con "- ". Las demás líneas (comentarios, vacías) se ignoran.
 */
fun parsearRegistroCambios(texto: String): List<ChangelogEntry> {
    val entradas = mutableListOf<ChangelogEntry>()
    var versionActual: String? = null
    var fechaActual = ""
    var cambiosActuales = mutableListOf<String>()

    fun cerrarEntrada() {
        versionActual?.let { entradas.add(ChangelogEntry(it, fechaActual, cambiosActuales.toList())) }
    }

    for (linea in texto.lines()) {
        val limpia = linea.trim()
        val cabecera = CABECERA_REGISTRO.find(limpia)
        when {
            cabecera != null -> {
                cerrarEntrada()
                versionActual = cabecera.groupValues[1]
                fechaActual = cabecera.groupValues[2]
                cambiosActuales = mutableListOf()
            }
            limpia.startsWith("- ") && versionActual != null -> cambiosActuales.add(limpia.removePrefix("- ").trim())
        }
    }
    cerrarEntrada()
    return entradas
}

@Composable
fun AboutAndChangelogScreen() {
    var tab by rememberSaveable { mutableStateOf(0) }
    val context = LocalContext.current
    var entradasCambios by remember { mutableStateOf<List<ChangelogEntry>>(emptyList()) }
    var registroCargado by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entradasCambios = withContext(Dispatchers.IO) { leerRegistroCambios(context) }
        registroCargado = true
    }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.secondary) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Acerca de") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Registro de cambios") })
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tab == 0) {
                item(key = "version") {
                    Card {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Image(
                                painterResource(R.drawable.mixcoara_logo),
                                "Logotipo oficial de Mixcoara",
                                Modifier.fillMaxWidth().height(188.dp),
                                contentScale = ContentScale.Fit,
                            )
                            Text("Mixcoara", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                            Text("Bóvedas locales y WebDAV", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                items(MixcoaraInfoSections, key = { "info_${it.title}" }) { section ->
                    var expanded by rememberSaveable(section.title) { mutableStateOf(false) }
                    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(section.title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Contraer apartado" else "Expandir apartado")
                            }
                            AnimatedVisibility(visible = expanded) {
                                Column { Spacer(Modifier.height(10.dp)); Text(section.content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            } else {
                if (registroCargado && entradasCambios.isEmpty()) {
                    item(key = "sin_registro") {
                        Text("No se pudo leer el registro de cambios.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(entradasCambios, key = { "history_${it.version}" }) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("v${entry.version}", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                if (entry.date.isNotBlank()) Text(entry.date, style = MaterialTheme.typography.labelSmall)
                            }
                            entry.changes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
}
