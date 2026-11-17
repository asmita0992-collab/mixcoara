package com.mixcoara.app

import com.tlahuis.mixcoara.R

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFolderUpload
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mixcoara.app.ui.theme.MixcoaraTheme
import com.mixcoara.app.ui.theme.MixcoaraThemeId
import androidx.activity.compose.setContent
import com.tlahuis.mixcoara.BuildConfig
import java.io.File
import java.io.FileOutputStream

class MainActivity : FragmentActivity() {
    private val viewModel: MixcoaraViewModel by viewModels()
    private var isInPipModeState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.activeProgress != null) {
                updatePipParams(state.activeProgress != null)
            }
            MixcoaraTheme(themeId = state.themeId) {
                if (isInPipModeState) {
                    PipTransferOverlay(progress = state.activeProgress)
                } else {
                    StoragePermissionGate {
                        MixcoaraApp(
                            activity = this@MainActivity,
                            viewModel = viewModel,
                            onEnterPip = { enterTransferPip(force = true) },
                        )
                    }
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (viewModel.state.value.activeProgress != null) {
            enterTransferPip(force = true)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState = isInPictureInPictureMode
    }

    private fun updatePipParams(hasActiveTransfer: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val builder =
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(hasActiveTransfer)
                    builder.setSeamlessResizeEnabled(true)
                }
                setPictureInPictureParams(builder.build())
            }
        }
    }

    fun enterTransferPip(force: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!force && viewModel.state.value.activeProgress == null) return
            runCatching {
                val params =
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                enterPictureInPictureMode(params)
            }.onFailure {
                Toast.makeText(
                    this,
                    "No se pudo activar Pantalla en Pantalla (revisa permisos de PiP).",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}

@Composable
private fun PipTransferOverlay(progress: TransferProgress?) {
    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (progress == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MixcoaraMark(28)
                Text(
                    "Mixcoara · Operación finalizada",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        } else {
            val pct = (progress.fraction * 100).toInt().coerceIn(0, 100)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        progress.phase.ifBlank { "Transfiriendo…" },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "$pct%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (progress.bytesTotal > 0) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    progress.currentItem.ifBlank { progress.state.ifBlank { "Procesando…" } },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        if (progress.speedBytesPerSec > 0) "${formatBytes(progress.speedBytesPerSec.toLong())}/s"
                        else "Prep: ${formatSeconds(progress.prepElapsedSec.toLong())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Subida: ${formatSeconds(progress.uploadElapsedSec.toLong())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StoragePermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(hasStorageAccess(context)) }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val legacyLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { hasAccess = hasStorageAccess(context) }
    val settingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            hasAccess = hasStorageAccess(context)
        }

    LaunchedEffect(hasAccess) {
        if (hasAccess) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            requestBatteryOptimizationExemptionOnce(context)
        }
    }

    if (hasAccess) {
        content()
        return
    }

    PermissionScreen(
        onGrant = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent =
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${BuildConfig.APPLICATION_ID}"),
                    )
                settingsLauncher.launch(intent)
            } else {
                legacyLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                )
            }
        },
    )
}

private fun requestBatteryOptimizationExemptionOnce(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val prefs = context.getSharedPreferences("mixcoara_bg_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            prefs.edit().putBoolean("battery_asked", true).apply()
            runCatching {
                val intent =
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                context.startActivity(intent)
            }
        }
    }
}

private fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            MixcoaraMark(72)
            Text(
                "Acceso estable a Descargas",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                "Mixcoara guarda certificados, temporales y resultados en " +
                    "Descargas/Mixcoara para evitar que Android elimine una tarea larga. " +
                    "Esta compilación es privada y requiere acceso directo a los archivos.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onGrant) {
                Text("Conceder acceso")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MixcoaraApp(
    activity: FragmentActivity,
    viewModel: MixcoaraViewModel,
    onEnterPip: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it) }
    }

    if (showThemeDialog) {
        ThemeSelectorDialog(
            currentTheme = state.themeId,
            onSelectTheme = {
                viewModel.selectTheme(it)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
    }

    state.activeProgress?.let { progress ->
        TransferProgressDialog(
            progress = progress,
            onCancel = viewModel::cancelActiveOperation,
            onEnterPip = onEnterPip,
        )
    }

    state.transferSummary?.let { summary ->
        TransferSummaryDialog(
            summary = summary,
            onDismiss = viewModel::dismissTransferSummary,
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.screen != AppScreen.CHECKING) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(R.drawable.mixcoara_icon),
                                contentDescription = "Logo de Mixcoara",
                                modifier = Modifier.size(38.dp),
                                contentScale = ContentScale.Fit,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Mixcoara", fontWeight = FontWeight.Bold)
                                Text(
                                    if (state.screen == AppScreen.VAULT) tabSubtitle(state.activeTab)
                                    else screenSubtitle(state.screen),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (
                            state.screen == AppScreen.UNLOCK ||
                                state.screen == AppScreen.CREATE ||
                                (state.screen == AppScreen.VAULT && state.breadcrumbs.size > 1)
                        ) {
                            IconButton(
                                onClick =
                                    if (state.screen == AppScreen.VAULT) viewModel::navigateUp
                                    else viewModel::editConnection,
                                enabled = !state.busy,
                            ) {
                                Icon(Icons.Rounded.ArrowBack, contentDescription = "Volver")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showThemeDialog = true }) {
                            Icon(Icons.Rounded.Palette, contentDescription = "Cambiar tema visual")
                        }
                        if (state.screen == AppScreen.VAULT) {
                            if (state.activeTab == AppTab.EXPLORER) {
                                IconButton(onClick = viewModel::toggleVaultVisibility) {
                                    Icon(
                                        if (state.vaultContentVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = if (state.vaultContentVisible) "Ocultar contenido de la bóveda" else "Mostrar contenido de la bóveda",
                                    )
                                }
                            }
                            IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Actualizar")
                            }
                            IconButton(onClick = viewModel::lock, enabled = !state.busy) {
                                Icon(Icons.Rounded.Lock, contentDescription = "Bloquear")
                            }
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                )
            }
        },
        bottomBar = {
            if (state.screen == AppScreen.VAULT) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    NavigationBarItem(
                        selected = state.activeTab == AppTab.EXPLORER,
                        onClick = { viewModel.switchTab(AppTab.EXPLORER) },
                        icon = { MixcoaraMark(24) },
                        label = { Text("Bóveda") },
                        colors =
                            NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                    )
                    NavigationBarItem(
                        selected = state.activeTab == AppTab.LOCAL_VAULT,
                        onClick = { viewModel.switchTab(AppTab.LOCAL_VAULT) },
                        icon = { Text("L", fontWeight = FontWeight.Bold) },
                        label = { Text("Local") },
                    )
                    NavigationBarItem(
                        selected = state.activeTab == AppTab.SECURITY,
                        onClick = { viewModel.switchTab(AppTab.SECURITY) },
                        icon = { Text("S", fontWeight = FontWeight.Bold) },
                        label = { Text("Seguridad") },
                    )
                    NavigationBarItem(
                        selected = state.activeTab == AppTab.LOGS,
                        onClick = { viewModel.switchTab(AppTab.LOGS) },
                        icon = { Text("B", fontWeight = FontWeight.Bold) },
                        label = { Text("Bitácora") },
                    )
                    NavigationBarItem(
                        selected = state.activeTab == AppTab.ABOUT,
                        onClick = { viewModel.switchTab(AppTab.ABOUT) },
                        icon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                        label = { Text("Acerca de") },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = state.screen,
            label = "screen",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { screen ->
            when (screen) {
                AppScreen.CHECKING ->
                    CheckingScreen(
                        state = state,
                        onRetry = viewModel::retrySelfTest,
                        onEditConnection = viewModel::editConnection,
                    )
                AppScreen.CONNECTION ->
                    ConnectionScreen(
                        initial = state.config,
                        busy = state.busy,
                        onSubmit = viewModel::checkVault,
                    )
                AppScreen.UNLOCK ->
                    UnlockScreen(
                        activity = activity,
                        folder = state.config.folder,
                        busy = state.busy,
                        biometricEnabled = state.biometricEnabled,
                        onUnlock = viewModel::unlock,
                        onBiometricRequested = {
                            showBiometricOrCredentialPrompt(activity) { pass ->
                                viewModel.unlock(pass)
                            }
                        },
                    )
                AppScreen.CREATE ->
                    CreateVaultScreen(
                        folder = state.config.folder,
                        busy = state.busy,
                        onCreate = viewModel::create,
                    )
                AppScreen.RECOVERY_KEY ->
                    RecoveryKeyScreen(
                        key = state.recoveryKey.orEmpty(),
                        onConfirm = viewModel::confirmRecoveryKey,
                    )
                AppScreen.VAULT ->
                    when (state.activeTab) {
                        AppTab.EXPLORER ->
                            VaultExplorerScreen(
                                state = state,
                                onFolderClick = viewModel::navigateToFolder,
                                onBreadcrumbClick = viewModel::navigateToBreadcrumb,
                                onToggleSelect = viewModel::toggleSelect,
                                onSelectAll = viewModel::selectAll,
                                onClearSelection = viewModel::clearSelection,
                                onShowContent = viewModel::toggleVaultVisibility,
                                onUploadFile = viewModel::uploadFile,
                                onUploadPickedFile = viewModel::uploadPickedFile,
                                onUploadFolder = viewModel::uploadFolder,
                                onUploadPickedFolder = viewModel::uploadPickedFolder,
                                onDownloadSelected = { viewModel.downloadSelected() },
                                onDownloadAll = { viewModel.downloadAll() },
                            )
                        AppTab.LOCAL_VAULT ->
                            LocalVaultScreen(
                                onEncrypt = viewModel::encryptLocal,
                                onDecrypt = viewModel::decryptLocal,
                            )
                        AppTab.SECURITY ->
                            SecurityScreen(
                                biometricEnabled = state.biometricEnabled,
                                currentTheme = state.themeId,
                                onSelectTheme = viewModel::selectTheme,
                                onPassphraseChange = viewModel::changePassphrase,
                                onRegenerateRecoveryKey = viewModel::regenerateRecoveryKey,
                                onBiometricToggle = viewModel::setBiometricEnabled,
                                onEnterPip = onEnterPip,
                            )
                        AppTab.ABOUT -> AboutAndChangelogScreen()
                        AppTab.LOGS ->
                            LogsScreen(
                                logText = state.logText,
                                onRefresh = viewModel::loadLogs,
                                onClear = viewModel::clearLogs,
                                onExport = {
                                    viewModel.exportLogs { path ->
                                        Toast.makeText(context, "Bitácora exportada en: $path", Toast.LENGTH_LONG).show()
                                    }
                                },
                            )
                    }
            }
        }
    }
}

private fun screenSubtitle(screen: AppScreen): String =
    when (screen) {
        AppScreen.CHECKING -> "Diagnóstico"
        AppScreen.CONNECTION -> "Conexión WebDAV"
        AppScreen.UNLOCK -> "Acceso seguro"
        AppScreen.CREATE -> "Nueva bóveda"
        AppScreen.RECOVERY_KEY -> "Recuperación"
        AppScreen.VAULT -> "Bóveda cifrada"
    }

private fun tabSubtitle(tab: AppTab): String =
    when (tab) {
        AppTab.EXPLORER -> "Explorador en la nube"
        AppTab.LOCAL_VAULT -> "Bóveda local portátil"
        AppTab.SECURITY -> "Seguridad, temas y segundo plano"
        AppTab.LOGS -> "Registro de actividad"
        AppTab.ABOUT -> "Acerca de y cambios"
    }

@Composable
private fun ThemeSelectorDialog(
    currentTheme: MixcoaraThemeId,
    onSelectTheme: (MixcoaraThemeId) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Temas visuales", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MixcoaraThemeId.entries.forEach { option ->
                    val selected = option == currentTheme
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        border =
                            if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            else null,
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable { onSelectTheme(option) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier.size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(option.previewBg)
                                        .border(1.dp, option.previewAccent, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier =
                                        Modifier.size(16.dp)
                                            .clip(CircleShape)
                                            .background(option.previewAccent),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(option.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    option.description + if (selected) " · Aplicado" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        },
    )
}

@Composable
private fun UnlockScreen(
    activity: FragmentActivity,
    folder: String,
    busy: Boolean,
    biometricEnabled: Boolean,
    onUnlock: (String) -> Unit,
    onBiometricRequested: () -> Unit,
) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    ScreenList {
        HeroCard(
            title = folder,
            body = "La frase se transforma con scrypt para abrir la llave maestra de la bóveda.",
        )
        SectionLabel("ACCESO SEGURO")
        PasswordField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = "Frase de la bóveda",
            enabled = !busy,
            onDone = { onUnlock(passphrase) },
        )
        PrimaryAction(
            text = if (busy) "Abriendo bóveda…" else "Abrir bóveda",
            busy = busy,
            onClick = { onUnlock(passphrase) },
        )
        if (biometricEnabled) {
            OutlinedButton(
                onClick = onBiometricRequested,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Rounded.Fingerprint, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Desbloquear con biometría o PIN/patrón", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun showBiometricOrCredentialPrompt(
    activity: FragmentActivity,
    onSuccess: (passphrase: String) -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt =
        BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val store = SecureConfigStore(activity)
                    store.loadBiometricPassphrase()?.let(onSuccess)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                            errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        Toast.makeText(activity, errString.toString(), Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )

    val allowedAuthenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    val promptInfo =
        runCatching {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Desbloquea tu bóveda Mixcoara")
                .setSubtitle("Usa tu huella, rostro, PIN, patrón, NIP o contraseña del dispositivo")
                .setAllowedAuthenticators(allowedAuthenticators)
                .build()
        }.getOrElse {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Desbloquea tu bóveda Mixcoara")
                .setSubtitle("Confirma tu identidad o bloqueo de pantalla")
                .setDeviceCredentialAllowed(true)
                .build()
        }

    prompt.authenticate(promptInfo)
}

@Composable
private fun VaultExplorerScreen(
    state: MixcoaraUiState,
    onFolderClick: (VaultEntry) -> Unit,
    onBreadcrumbClick: (Int) -> Unit,
    onToggleSelect: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onShowContent: () -> Unit,
    onUploadFile: (path: String, verify: Boolean) -> Unit,
    onUploadPickedFile: (uri: Uri, verify: Boolean) -> Unit,
    onUploadFolder: (path: String, verify: Boolean) -> Unit,
    onUploadPickedFolder: (uri: Uri, verify: Boolean) -> Unit,
    onDownloadSelected: () -> Unit,
    onDownloadAll: () -> Unit,
) {
    val context = LocalContext.current
    var showUploadFileDialog by remember { mutableStateOf(false) }
    var showUploadFolderDialog by remember { mutableStateOf(false) }
    var pendingUploadFileVerify by remember { mutableStateOf(false) }
    var pendingUploadFolderVerify by remember { mutableStateOf(false) }

    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                onUploadPickedFile(it, pendingUploadFileVerify)
            } ?: run {
                Toast.makeText(context, "Selección de archivo cancelada.", Toast.LENGTH_SHORT).show()
            }
        }

    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                showUploadFolderDialog = false
                onUploadPickedFolder(it, pendingUploadFolderVerify)
            } ?: run {
                Toast.makeText(context, "Selección de carpeta cancelada.", Toast.LENGTH_SHORT).show()
            }
        }

    if (showUploadFileDialog) {
        UploadFileDialog(
            onDismiss = { showUploadFileDialog = false },
            onSelectWithPicker = { verify ->
                showUploadFileDialog = false
                pendingUploadFileVerify = verify
                filePicker.launch(arrayOf("*/*"))
            },
            onSubmitPath = { path, verify ->
                showUploadFileDialog = false
                onUploadFile(path, verify)
            },
        )
    }

    if (showUploadFolderDialog) {
        UploadFolderDialog(
            onDismiss = { showUploadFolderDialog = false },
            onSelectWithPicker = { verify ->
                pendingUploadFolderVerify = verify
                folderPicker.launch(null)
            },
            onSubmit = { path, verify ->
                showUploadFolderDialog = false
                onUploadFolder(path, verify)
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.vaultContentVisible) state.breadcrumbs.forEachIndexed { index, node ->
                    val isLast = index == state.breadcrumbs.lastIndex
                    AssistChip(
                        onClick = { onBreadcrumbClick(index) },
                        label = {
                            Text(
                                node.name,
                                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        colors =
                            AssistChipDefaults.assistChipColors(
                                containerColor =
                                    if (isLast) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    )
                    if (!isLast) {
                        Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { showUploadFileDialog = true },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                ) {
                    Text("Subir archivo")
                }
                OutlinedButton(
                    onClick = { showUploadFolderDialog = true },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Subir carpeta")
                }
            }
        }

        if (state.vaultContentVisible && state.selectedEntryIds.isNotEmpty()) {
            item {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {                        
                    Text("${state.selectedEntryIds.size} seleccionados",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onClearSelection) {
                            Icon(Icons.Rounded.Clear, contentDescription = "Limpiar selección")
                        }
                        IconButton(onClick = onSelectAll) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = "Seleccionar todo")
                        }
                        Button(
                            onClick = onDownloadSelected,
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary,
                                ),
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Descargar")
                        }
                    }
                }
            }
        }

        if (state.busy) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }

        if (!state.vaultContentVisible) {
            item {
                HeroCard(
                    title = "Contenido de la bóveda protegido",
                    body = "Los nombres y la lista están ocultos en esta pantalla. Usa el botón del ojo para mostrarlos. Esto no borra ni modifica tus archivos.",
                    accent = MaterialTheme.colorScheme.secondary,
                )
                OutlinedButton(onClick = onShowContent, modifier = Modifier.fillMaxWidth()) {
                    Text("Mostrar contenido")
                }
            }
        }

        if (state.vaultContentVisible && !state.busy && state.entries.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MixcoaraMark(56)
                        Text("Carpeta vacía", fontWeight = FontWeight.Bold)
                        Text(
                            "Sube archivos o carpetas usando los botones superiores.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        items(if (state.vaultContentVisible) state.entries else emptyList(), key = { it.id }) { entry ->
            val isSelected = state.selectedEntryIds.contains(entry.id)
            VaultEntryCard(
                entry = entry,
                isSelected = isSelected,
                onSelectToggle = { onToggleSelect(entry.id) },
                onClick = {
                    if (entry.isDirectory) {
                        onFolderClick(entry)
                    } else {
                        onToggleSelect(entry.id)
                    }
                },
            )
        }

        if (state.vaultContentVisible && state.entries.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDownloadAll,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Descargar todo (restaurar bóveda completa)")
                }
            }
        }
    }
}

@Composable
private fun VaultEntryCard(
    entry: VaultEntry,
    isSelected: Boolean,
    onSelectToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surfaceVariant,
            ),
        modifier =
            Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelectToggle() },
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier =
                    Modifier.size(42.dp)
                        .background(
                            if (entry.isDirectory) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(12.dp),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description,
                    contentDescription = null,
                    tint =
                        if (entry.isDirectory) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (entry.isDirectory) "Carpeta" else formatBytes(entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LocalVaultScreen(
    onEncrypt: (source: String, target: String) -> Unit,
    onDecrypt: (source: String, target: String) -> Unit,
) {
    val context = LocalContext.current
    var filePath by rememberSaveable { mutableStateOf("") }

    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                val resolved = copyUriToStaging(context, it)
                if (resolved != null) filePath = resolved
            }
        }

    ScreenList {
        HeroCard(
            title = "Bóveda local portátil",
            body = "Cifra o descifra cualquier archivo en tu teléfono con la llave AES-256-GCM sin subirlo a la nube.",
            accent = MaterialTheme.colorScheme.tertiary,
        )
        SectionLabel("ARCHIVO A PROCESAR")
        OutlinedTextField(
            value = filePath,
            onValueChange = { filePath = it },
            label = { Text("Ruta del archivo") },
            placeholder = { Text("/storage/emulated/0/Download/Mixcoara/…") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { filePicker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Elegir archivo con selector nativo…")
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Destinos automáticos:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                Text("Cifrado: Descargas/Mixcoara/BovedaLocal/<archivo>.mixcoara", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Descifrado: Descargas/Mixcoara/Descifrados/<archivo>", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(
            onClick = { onEncrypt(filePath.trim(), "") },
            enabled = filePath.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary),
        ) {
            Text("Cifrar a Bóveda Local (.mixcoara)", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = { onDecrypt(filePath.trim(), "") },
            enabled = filePath.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Descifrar archivo", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SecurityScreen(
    biometricEnabled: Boolean,
    currentTheme: MixcoaraThemeId,
    onSelectTheme: (MixcoaraThemeId) -> Unit,
    onPassphraseChange: (current: String, newPass: String, repeatPass: String) -> Unit,
    onRegenerateRecoveryKey: (currentPass: String) -> Unit,
    onBiometricToggle: (enabled: Boolean, pass: String) -> Unit,
    onEnterPip: () -> Unit,
) {
    val context = LocalContext.current
    var currentPass by rememberSaveable { mutableStateOf("") }
    var newPass by rememberSaveable { mutableStateOf("") }
    var repeatPass by rememberSaveable { mutableStateOf("") }
    var recCurrentPass by rememberSaveable { mutableStateOf("") }
    var bioPass by rememberSaveable { mutableStateOf("") }

    val notifLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(
                context,
                if (granted) "Permiso de notificaciones concedido." else "Permiso de notificaciones no concedido.",
                Toast.LENGTH_SHORT,
            ).show()
        }

    ScreenList {
        HeroCard(
            title = "Seguridad, temas y segundo plano",
            body = "Administra temas visuales, biometría/PIN, permisos de segundo plano, PiP y llaves.",
        )

        SectionLabel("APARIENCIA · TEMAS VISUALES")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Tema activo: ${currentTheme.title}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                MixcoaraThemeId.entries.forEach { option ->
                    val selected = option == currentTheme
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                                )
                                .clickable { onSelectTheme(option) }
                                .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier.size(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(option.previewBg)
                                    .border(1.dp, option.previewAccent, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier =
                                    Modifier.size(12.dp)
                                        .clip(CircleShape)
                                        .background(option.previewAccent),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(option.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text(option.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        SectionLabel("NOTIFICACIONES, SEGUNDO PLANO Y PIP")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Permisos para transferencias continuas",
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Activa las notificaciones y el uso de batería sin restricciones para que las subidas y descargas continúen aunque apagues la pantalla o uses otra aplicación.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            val intent =
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                            runCatching { context.startActivity(intent) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Notifications, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Permitir notificaciones de Mixcoara")
                }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val intent =
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                )
                            context.startActivity(intent)
                        }.onFailure {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Batería sin restricciones (segundo plano)")
                }
                OutlinedButton(
                    onClick = onEnterPip,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Probar Pantalla en Pantalla (PiP)")
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        SectionLabel("BIOMETRÍA Y BLOQUEO DEL DISPOSITIVO")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Huella, rostro, PIN, patrón, NIP o contraseña", fontWeight = FontWeight.Bold)
                        Text(
                            if (biometricEnabled) "Activo: puedes desbloquear con biometría o con el PIN, patrón, NIP o contraseña del teléfono."
                            else "Inactivo: debes escribir la frase de la bóveda manualmente al abrir.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = biometricEnabled,
                        onCheckedChange = { checked ->
                            if (!checked) {
                                onBiometricToggle(false, "")
                            }
                        },
                    )
                }
                if (!biometricEnabled) {
                    PasswordField(
                        value = bioPass,
                        onValueChange = { bioPass = it },
                        label = "Frase a sellar en Keystore",
                        enabled = true,
                    )
                    Button(
                        onClick = {
                            onBiometricToggle(true, bioPass)
                            bioPass = ""
                        },
                        enabled = bioPass.length >= 12,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Activar acceso por biometría / PIN / patrón")
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        SectionLabel("CAMBIAR FRASE DE LA BÓVEDA")
        PasswordField(value = currentPass, onValueChange = { currentPass = it }, label = "Frase actual", enabled = true)
        PasswordField(value = newPass, onValueChange = { newPass = it }, label = "Frase nueva (mínimo 12 caracteres)", enabled = true)
        PasswordField(value = repeatPass, onValueChange = { repeatPass = it }, label = "Repite la frase nueva", enabled = true)
        Button(
            onClick = {
                onPassphraseChange(currentPass, newPass, repeatPass)
                currentPass = ""
                newPass = ""
                repeatPass = ""
            },
            enabled = currentPass.isNotBlank() && newPass.length >= 12 && newPass == repeatPass,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text("Actualizar frase segura")
        }

        Spacer(Modifier.height(8.dp))
        SectionLabel("CLAVE DE RECUPERACIÓN")
        Text(
            "Genera una nueva clave Base32. La clave de recuperación anterior dejará de servir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PasswordField(value = recCurrentPass, onValueChange = { recCurrentPass = it }, label = "Frase actual de la bóveda", enabled = true)
        OutlinedButton(
            onClick = {
                onRegenerateRecoveryKey(recCurrentPass)
                recCurrentPass = ""
            },
            enabled = recCurrentPass.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Generar nueva clave de recuperación")
        }
    }
}

@Composable
private fun LogsScreen(
    logText: String,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    ScreenList {
        HeroCard(
            title = "Bitácora de actividad",
            body = "Registro técnico rotativo con tiempos separados de preparación/cifrado y de subida exclusiva. Contraseñas y frases se censuran.",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Refrescar")
            }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(logText))
                    Toast.makeText(context, "Bitácora copiada", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copiar")
            }
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Exportar")
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF030A0C)),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = logText.ifBlank { "No hay registros disponibles aún." },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Color(0xFF82E5DF),
                modifier = Modifier.fillMaxWidth().padding(14.dp),
            )
        }
        OutlinedButton(
            onClick = onClear,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Delete, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Limpiar bitácora")
        }
    }
}

@Composable
private fun TransferProgressDialog(
    progress: TransferProgress,
    onCancel: () -> Unit,
    onEnterPip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    progress.phase.ifBlank { "Transfiriendo…" },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onEnterPip) {
                    Icon(
                        Icons.Rounded.PictureInPictureAlt,
                        contentDescription = "Minimizar a Pantalla en Pantalla (PiP)",
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (progress.currentItem.isNotBlank()) {
                    Text(
                        progress.currentItem,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress.state.isNotBlank()) {
                    Text(
                        "Etapa: ${progress.state}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (progress.bytesTotal > 0) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Etapa ${(progress.fraction * 100).toInt()}% • ${formatBytes(progress.bytesDone)} de ${formatBytes(progress.bytesTotal)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Etapa sin total medible por ahora.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (progress.speedBytesPerSec > 0) {
                        Text(
                            "Velocidad: ${formatBytes(progress.speedBytesPerSec.toLong())}/s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (progress.etaSec != null && progress.etaSec > 0) {
                        Text(
                            "Faltan: ${formatSeconds(progress.etaSec.toLong())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (progress.filesTotal > 0) {
                    Text(
                        "Archivos: ${progress.filesDone}/${progress.filesTotal}" +
                            if (progress.foldersTotal > 0) " • Carpetas: ${progress.foldersDone}/${progress.foldersTotal}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Tiempo de preparación/cifrado: ${formatSecondsDecimal(progress.prepElapsedSec)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Tiempo exclusivo de transferencia: ${formatSecondsDecimal(progress.uploadElapsedSec)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onEnterPip) {
                Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("PiP")
            }
        },
        dismissButton = {
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun TransferSummaryDialog(
    summary: TransferSummary,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (summary.errors.isEmpty()) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = if (summary.errors.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp),
            )
        },
        title = { Text(summary.title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Elementos procesados: ${summary.filesCount} archivos" + if (summary.foldersCount > 0) ", ${summary.foldersCount} carpetas" else "")
                if (summary.bytesCount > 0) {
                    Text("Volumen transferido: ${formatBytes(summary.bytesCount)}")
                }
                if (summary.prepElapsedSec > 0) {
                    Text("Tiempo previo (preparación y cifrado): ${formatSecondsDecimal(summary.prepElapsedSec)}")
                }
                if (summary.elapsedSec > 0) {
                    Text(
                        "Tiempo exclusivo de transferencia: ${formatSecondsDecimal(summary.elapsedSec)}",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text("Ubicación: ${summary.destination}")
                if (summary.errors.isNotEmpty()) {
                    Text(
                        "Incidencias (${summary.errors.size}):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    summary.errors.take(4).forEach { err ->
                        Text("• $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Entendido")
            }
        },
    )
}

@Composable
private fun UploadFileDialog(
    onDismiss: () -> Unit,
    onSelectWithPicker: (verify: Boolean) -> Unit,
    onSubmitPath: (path: String, verify: Boolean) -> Unit,
) {
    var manualPath by rememberSaveable { mutableStateOf("") }
    var verify by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subir archivo a la bóveda", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onSelectWithPicker(verify) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Elegir con selector de Android")
                }
                Text("O ingresa la ruta directa:", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = manualPath,
                    onValueChange = { manualPath = it },
                    placeholder = { Text("/storage/emulated/0/Download/…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = verify, onCheckedChange = { verify = it })
                    Text("Validar por separado (descargar y verificar GCM)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmitPath(manualPath.trim(), verify) },
                enabled = manualPath.isNotBlank(),
            ) {
                Text("Subir ruta directa")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
private fun UploadFolderDialog(
    onDismiss: () -> Unit,
    onSelectWithPicker: (verify: Boolean) -> Unit,
    onSubmit: (path: String, verify: Boolean) -> Unit,
) {
    var path by rememberSaveable { mutableStateOf("/storage/emulated/0/Download/") }
    var verify by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subir carpeta completa", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Cifra y replica la jerarquía de directorios conservando nombres cifrados con AES-SIV.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Ruta local de la carpeta") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = verify, onCheckedChange = { verify = it })
                    Text("Validar por separado después de subir", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { onSelectWithPicker(verify) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Seleccionar carpeta con Android")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(path.trim(), verify) }, enabled = path.isNotBlank()) {
                Text("Escanear y subir")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

private fun copyUriToStaging(context: Context, uri: Uri): String? {
    return runCatching {
        val stagingDir = File("/storage/emulated/0/Download/Mixcoara/.temporales").apply { mkdirs() }
        val fileName = "upload_${System.currentTimeMillis()}"
        val targetFile = File(stagingDir, fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        targetFile.absolutePath
    }.getOrNull()
}

private fun formatSeconds(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

private fun formatSecondsDecimal(seconds: Double): String {
    if (seconds < 60.0) return "%.2f s".format(seconds)
    val totalSec = seconds.toLong()
    val m = totalSec / 60
    val s = seconds - (m * 60)
    return "${m}m %.1fs".format(s)
}

@Composable
private fun CheckingScreen(
    state: MixcoaraUiState,
    onRetry: () -> Unit,
    onEditConnection: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.mixcoara_icon),
                contentDescription = "Ícono de Mixcoara",
                modifier = Modifier.size(56.dp),
                contentScale = ContentScale.Fit,
            )
            if (state.busy) {
                CircularProgressIndicator()
                Text(
                    state.message,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!state.cryptoReady) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text("Diagnóstico fallido", style = MaterialTheme.typography.headlineSmall)
                Text(
                    state.error.orEmpty(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onRetry) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Repetir prueba")
                }
            } else {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(state.message, textAlign = TextAlign.Center)
                OutlinedButton(onClick = onEditConnection) { Text("Editar conexión") }
            }
        }
    }
}

@Composable
private fun ConnectionScreen(
    initial: VaultConfig,
    busy: Boolean,
    onSubmit: (VaultConfig) -> Unit,
) {
    var url by rememberSaveable(initial.url) { mutableStateOf(initial.url) }
    var username by rememberSaveable(initial.username) { mutableStateOf(initial.username) }
    var password by rememberSaveable(initial.password) { mutableStateOf(initial.password) }
    var folder by rememberSaveable(initial.folder) { mutableStateOf(initial.folder) }

    ScreenList {
        HeroCard(
            title = "Conecta tu nube",
            body = "Tus credenciales se cifran con una llave exclusiva del Keystore de Android.",
        )
        SectionLabel("SERVIDOR WEBDAV")
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("URL de WebDAV") },
            placeholder = { Text("https://servidor/remote.php/dav/files/…") },
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Usuario") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = "Contraseña WebDAV",
            enabled = !busy,
        )
        OutlinedTextField(
            value = folder,
            onValueChange = { folder = it },
            label = { Text("Carpeta de la bóveda") },
            supportingText = { Text("Usa otro nombre para crear una bóveda independiente.") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryAction(
            text = if (busy) "Conectando…" else "Guardar y continuar",
            busy = busy,
            onClick = {
                onSubmit(
                    VaultConfig(
                        url = url.trim(),
                        username = username.trim(),
                        password = password,
                        folder = folder.trim(),
                    ),
                )
            },
        )
    }
}

@Composable
private fun CreateVaultScreen(
    folder: String,
    busy: Boolean,
    onCreate: (String, String) -> Unit,
) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    ScreenList {
        HeroCard(
            title = "Crear $folder",
            body = "Se generará una nueva llave maestra y una clave de recuperación.",
        )
        SectionLabel("PROTECCIÓN DE LA BÓVEDA")
        PasswordField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = "Frase nueva, mínimo 12 caracteres",
            enabled = !busy,
        )
        PasswordField(
            value = confirmation,
            onValueChange = { confirmation = it },
            label = "Repite la frase",
            enabled = !busy,
            onDone = { onCreate(passphrase, confirmation) },
        )
        PrimaryAction(
            text = if (busy) "Creando…" else "Crear bóveda",
            busy = busy,
            onClick = { onCreate(passphrase, confirmation) },
        )
    }
}

@Composable
private fun RecoveryKeyScreen(key: String, onConfirm: () -> Unit) {
    var confirmed by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    ScreenList {
        HeroCard(
            title = "Guarda esta clave ahora",
            body = "Sin la frase y sin esta clave, los archivos no se pueden recuperar.",
            accent = MaterialTheme.colorScheme.secondary,
        )
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    key,
                    fontSize = 19.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(key)) }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copiar")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
            Text("Ya la guardé en un lugar seguro")
        }
        Button(
            onClick = onConfirm,
            enabled = confirmed,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Continuar")
        }
    }
}

@Composable
private fun ScreenList(content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
private fun MixcoaraMark(size: Int = 56) {
    Image(
        painter = painterResource(R.drawable.mixcoara_icon),
        contentDescription = "Ícono de Mixcoara",
        modifier = Modifier.size(size.dp),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun HeroCard(
    title: String,
    body: String,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MixcoaraMark(60)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = accent)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    onDone: (() -> Unit)? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (visible) "Ocultar" else "Mostrar",
                )
            }
        },
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (onDone == null) ImeAction.Next else ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PrimaryAction(
    text: String,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(9.dp))
        }
        Text(text, fontWeight = FontWeight.Bold)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1024.0
        unit++
    } while (value >= 1024 && unit < units.lastIndex)
    return "%.1f %s".format(value, units[unit])
}
