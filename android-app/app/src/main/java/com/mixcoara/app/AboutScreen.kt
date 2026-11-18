package com.mixcoara.app

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tlahuis.mixcoara.BuildConfig
import com.tlahuis.mixcoara.R

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

val MixcoaraChangelog = listOf(
    ChangelogEntry("2.6.0", "2026-11-17", listOf(
        "Configuración en dos pestañas: Configuración y Temas.",
        "Ocho temas visuales oficiales (Mixcoara, Mixco noche, Mixco día, Mixco oscuridad, Mixco esmeralda, Mixco ámbar, Mixco violeta y Mixco grafito), con vista previa y selección inmediata.",
        "Pantalla en Pantalla (PiP) automática durante subidas y descargas, con el progreso visible en la ventana pequeña.",
        "Bloqueo de suspensión (WakeLock y WifiLock) para que las transferencias sigan en segundo plano.",
        "Subida más rápida: envío en paralelo de hasta 3 archivos y partes de 25 MB para archivos grandes.",
        "Cronómetros separados de preparación (cifrado) y de subida a la nube.",
        "La app ya no solicita la exención de optimización de batería.",
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
    ChangelogEntry("2.3.0", "", listOf(
        "Se identificó una compilación APK previa 2.3.0 (versionCode 24), pero el proyecto no conserva un detalle verificable de sus cambios; se registra sin atribuir funciones no documentadas."
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
        "Versión informativa tomada de BuildConfig."
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
    ChangelogEntry("1.6.0", "2026-10 / 2026-11", listOf("Parte de la etapa 1.4–1.6: robustez de WebDAV, cifrado local y telemetría. No se conserva el desglose exacto por versión.")),
    ChangelogEntry("1.5.0", "2026-10 / 2026-11", listOf("Etapa de mejoras de WebDAV, cifrado local y progreso; desglose específico no documentado.")),
    ChangelogEntry("1.4.0", "2026-10 / 2026-11", listOf("Etapa de mejoras de WebDAV, cifrado local y progreso; desglose específico no documentado.")),
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
    var tab by rememberSaveable { mutableStateOf(0) }
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
                items(MixcoaraChangelog, key = { "history_${it.version}" }) { entry ->
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
