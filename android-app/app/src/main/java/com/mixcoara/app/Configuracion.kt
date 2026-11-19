package com.mixcoara.app

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Configuración unificada de Mixcoara. Reúne en un solo lugar todo lo que antes
 * estaba repartido entre la pestaña de Seguridad, la conexión WebDAV y las
 * bóvedas locales.
 */
@Composable
fun ConfiguracionBasica(
    activity: FragmentActivity,
    model: MixcoaraViewModel,
    onEditarConexion: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val localModel: LocalDirectoryViewModel = viewModel()
    val localState by localModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        model.refreshBatteryOptOutState()
    }

    ScreenList {
        HeroCard(
            title = "Configuración",
            body = "Conexión a la nube, protección de la bóveda y bóvedas locales en un solo lugar.",
        )

        // ------------------------------------------------------------------
        // Conexión
        // ------------------------------------------------------------------
        SectionLabel("CONEXIÓN A LA NUBE")
        Card {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(state.config.url.ifBlank { "Sin configurar" }, fontWeight = FontWeight.Bold)
                Text(
                    listOf(state.config.username, state.config.folder)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { "Todavía no has configurado el servidor WebDAV." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onEditarConexion, modifier = Modifier.fillMaxWidth()) {
                    Text("Editar conexión WebDAV")
                }
            }
        }

        HorizontalDivider()

        // ------------------------------------------------------------------
        // Bóveda en la nube
        // ------------------------------------------------------------------
        SectionLabel("BÓVEDA EN LA NUBE")
        Card {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Acceso con huella o rostro", fontWeight = FontWeight.Bold)
                Text(
                    if (state.biometricEnabled) {
                        "Activo. La biometría autoriza el uso de la contraseña ya verificada de esta bóveda."
                    } else {
                        "Inactivo. Actívalo con la contraseña de esta bóveda, sin escribir una nueva."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = state.biometricEnabled,
                        onCheckedChange = { enabled ->
                            model.setBiometricEnabled(enabled, activity)
                        },
                        enabled = !state.busy && state.sessionId != null,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (state.sessionId == null) "Abre la bóveda para usar esta opción." else "Usar biometría para desbloquear",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // --- Reporte de bóveda ---
                HorizontalDivider()
                Text("Reporte de bóveda", fontWeight = FontWeight.Bold)
                Text(
                    "Genera un .txt con el árbol de carpetas y la tabla UUID → ruta " +
                        "de todo lo que hay en la nube. Se guarda en " +
                        "Descargas/Mixcoara/reportes y sirve para recordar dónde estaba " +
                        "cada archivo aunque sus nombres estén cifrados.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExportReportButton(
                    enabled = !state.busy && state.sessionId != null,
                    onExport = {
                        model.exportReportVault { path ->
                            val context = activity.applicationContext
                            Toast.makeText(
                                context,
                                if (path.isBlank()) {
                                    "Reporte generado en Descargas/Mixcoara/reportes"
                                } else {
                                    "Reporte guardado en: $path"
                                },
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
                if (state.sessionId == null) {
                    Text(
                        "Abre primero la bóveda WebDAV para poder exportar el reporte.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // --- Exención de optimización de batería (2.7.0) ---
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (state.batteryOptOut) Icons.Rounded.CheckCircle else Icons.Rounded.BatteryAlert,
                        contentDescription = null,
                        tint = if (state.batteryOptOut) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (state.batteryOptOut) "Transferencias en segundo plano: permitidas"
                        else "Transferencias en segundo plano: restringidas",
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    if (state.batteryOptOut) {
                        "Android no interrumpirá las subidas ni descargas largas cuando cambies " +
                            "de app o apagues la pantalla."
                    } else {
                        "Android puede interrumpir las transferencias largas si cambias de app " +
                            "o apagas la pantalla. Puedes permitirlo ahora: se abrirá el cuadro " +
                            "de confirmación del sistema."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.batteryOptOut) {
                    OutlinedButton(
                        onClick = { model.requestBatteryOptOut() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.BatteryAlert, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Permitir transferencias en segundo plano")
                    }
                    TextButton(
                        onClick = { model.openBatterySettings() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Abrir ajustes de batería del sistema")
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        if (state.sessionId != null) {
            CambiarFraseBoveda(
                biometriaActiva = state.biometricEnabled,
                onPassphraseChange = model::changePassphrase,
                onRegenerateRecoveryKey = model::regenerateRecoveryKey,
            )
        } else {
            Text("Abre primero tu bóveda WebDAV para modificar su frase o clave de recuperación.",
                style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()

        // ------------------------------------------------------------------
        // Bóvedas locales
        // ------------------------------------------------------------------
        SectionLabel("BÓVEDAS LOCALES")
        Card {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Bóveda local abierta", fontWeight = FontWeight.Bold)
                Text(
                    localState.path.ifBlank { "Ninguna bóveda local abierta en este momento." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = localState.biometric,
                        onCheckedChange = { enabled ->
                            localModel.setFingerprint(activity, enabled)
                        },
                        enabled = !localState.busy && localState.session.isNotBlank(),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (localState.session.isBlank()) {
                            "Abre una bóveda local para usar esta opción."
                        } else {
                            "Desbloquear esta bóveda local con biometría"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "La biometría de cada bóveda local es independiente de la de la nube y usa su propia contraseña.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Cambio de frase de la bóveda en la nube y regeneración de la clave de
 * recuperación. Se mantiene aparte para que la pantalla principal siga limpia.
 */
@Composable
private fun CambiarFraseBoveda(
    biometriaActiva: Boolean,
    onPassphraseChange: (current: String, newPass: String, repeatPass: String) -> Unit,
    onRegenerateRecoveryKey: (currentPass: String) -> Unit,
) {
    var actual by rememberSaveable { mutableStateOf("") }
    var nueva by rememberSaveable { mutableStateOf("") }
    var repetida by rememberSaveable { mutableStateOf("") }
    var claveActual by rememberSaveable { mutableStateOf("") }
    var abierta by rememberSaveable { mutableStateOf(false) }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Frase y clave de recuperación", fontWeight = FontWeight.Bold)
            Text(
                "Reenvuelve la llave maestra sin recifrar los archivos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!abierta) {
                OutlinedButton(onClick = { abierta = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Mostrar opciones de la frase")
                }
            } else {
                PasswordField(actual, { actual = it }, "Frase actual", true)
                PasswordField(nueva, { nueva = it }, "Frase nueva (mínimo 12 caracteres)", true)
                PasswordField(repetida, { repetida = it }, "Repite la frase nueva", true)
                Button(
                    onClick = {
                        onPassphraseChange(actual, nueva, repetida)
                        actual = ""
                        nueva = ""
                        repetida = ""
                    },
                    enabled = actual.isNotBlank() && nueva.length >= 12 && nueva == repetida,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Actualizar frase segura") }

                Spacer(Modifier.height(4.dp))
                Text(
                    "La clave de recuperación anterior dejará de servir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PasswordField(claveActual, { claveActual = it }, "Frase actual de la bóveda", true)
                OutlinedButton(
                    onClick = {
                        onRegenerateRecoveryKey(claveActual)
                        claveActual = ""
                    },
                    enabled = claveActual.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Generar nueva clave de recuperación")
                }
            }
        }
    }
}

@Composable
private fun ExportReportButton(
    enabled: Boolean,
    onExport: () -> Unit,
) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            runCatching { onExport() }.onFailure { ex ->
                Toast.makeText(
                    context,
                    "No se pudo exportar el reporte: ${ex.message ?: ex::class.simpleName}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Exportar reporte de bóveda (.txt)")
    }
}
