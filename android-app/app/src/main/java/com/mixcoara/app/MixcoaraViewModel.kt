package com.mixcoara.app

import android.app.Application
import com.mixcoara.app.ui.theme.MixcoaraThemeId
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tlahuis.mixcoara.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class MixcoaraViewModel(application: Application) : AndroidViewModel(application) {
    private val bridge = PythonBridge(application)
    private val store = SecureConfigStore(application)
    private val _state = MutableStateFlow(MixcoaraUiState(themeId = store.loadTheme()))
    val state: StateFlow<MixcoaraUiState> = _state.asStateFlow()
    private var currentRootUuid: String = ""
    private var verifiedVaultPassword: String? = null
    private val localCancellation = mutableSetOf<String>()
    private var pendingSavedConnectionCheck = false

    private fun storageIsAuthorized(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Valida una conexión guardada solo cuando el usuario abre WebDAV con permisos. */
    fun resumeSavedConnectionIfPending() {
        if (!pendingSavedConnectionCheck || !storageIsAuthorized()) return
        pendingSavedConnectionCheck = false
        store.load()?.let { checkVault(it, persist = false) }
    }

    init {
        // Una actualización debe reiniciar el estado; si no, la app conserva
        // lo que quedó de la versión anterior.
        DetectorDeVersion.huboActualizacion(application, BuildConfig.VERSION_CODE)
        store.limpiarFormatoAntiguo()
        runSelfTest()
    }

    private fun runSelfTest() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { bridge.selfTest() }
            if (!result.ok) {
                _state.update {
                    it.copy(
                        busy = false,
                        cryptoReady = false,
                        error = result.message.ifBlank { "Falló la prueba criptográfica." },
                        message = "No se puede continuar con seguridad.",
                    )
                }
                return@launch
            }
            val saved = store.load()
            val hasBio = saved != null && VaultBiometrics.has(getApplication(), VaultBiometrics.cloudScope(saved))
            // No comprobar WebDAV al abrir la app en modo Local: sin tráfico de nube.
            // El usuario activa la comprobación al entrar en la pestaña Bóveda.
            pendingSavedConnectionCheck = saved != null
            _state.update {
                it.copy(
                    screen =
                        AppScreen.CONNECTION,
                    config = saved ?: VaultConfig(),
                    busy = false,
                    cryptoReady = true,
                    biometricEnabled = hasBio,
                    error = null,
                    message =
                        if (saved == null) "Configura tu servidor WebDAV."
                        else "Conexión guardada. Abre Bóveda para verificar WebDAV.",
                )
            }

        }
    }

    fun checkVault(config: VaultConfig, persist: Boolean = true) {
        if (
            config.url.isBlank() ||
                config.username.isBlank() ||
                config.password.isBlank() ||
                config.folder.isBlank()
        ) {
            _state.update { it.copy(error = "Completa todos los datos de conexión.") }
            return
        }
        _state.update {
            it.copy(
                config = config,
                biometricEnabled = VaultBiometrics.has(getApplication(), VaultBiometrics.cloudScope(config)),
                busy = true,
                error = null,
                message = "Conectando con WebDAV…",
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { bridge.checkVault(config) }
            if (!result.ok) {
                _state.update {
                    it.copy(
                        screen = AppScreen.CONNECTION,
                        busy = false,
                        error = result.message.ifBlank { "No se pudo conectar con WebDAV." },
                    )
                }
                return@launch
            }
            if (persist) store.save(config)
            val exists = result.data?.optBoolean("exists") == true
            _state.update {
                it.copy(
                    screen = if (exists) AppScreen.UNLOCK else AppScreen.CREATE,
                    busy = false,
                    vaultExists = exists,
                    error = null,
                    message =
                        if (exists) "La bóveda está lista para desbloquearse."
                        else "No hay una bóveda en esta carpeta.",
                )
            }
        }
    }

    fun unlock(passphrase: String) {
        if (passphrase.isBlank()) {
            _state.update { it.copy(error = "Escribe la frase de la bóveda.") }
            return
        }
        _state.update { it.copy(busy = true, error = null, message = "Derivando las llaves…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { bridge.unlock(_state.value.config, passphrase) }
            if (!result.ok) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = result.message.ifBlank { "No se pudo abrir la bóveda." },
                    )
                }
                return@launch
            }
            val sessionId = result.data?.optString("session_id").orEmpty()
            verifiedVaultPassword = passphrase
            _state.update {
                it.copy(
                    screen = AppScreen.VAULT,
                    busy = true,
                    sessionId = sessionId,
                    error = null,
                    message = "Leyendo la raíz cifrada…",
                )
            }
            loadRootDirectory(sessionId)
        }
    }

    fun create(passphrase: String, confirmation: String) {
        when {
            passphrase.length < 12 -> {
                _state.update { it.copy(error = "La frase debe tener al menos 12 caracteres.") }
                return
            }
            passphrase != confirmation -> {
                _state.update { it.copy(error = "Las frases no coinciden.") }
                return
            }
        }
        _state.update { it.copy(busy = true, error = null, message = "Creando la bóveda…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { bridge.create(_state.value.config, passphrase) }
            if (!result.ok) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = result.message.ifBlank { "No se pudo crear la bóveda." },
                    )
                }
                return@launch
            }
            verifiedVaultPassword = passphrase
            _state.update {
                it.copy(
                    screen = AppScreen.RECOVERY_KEY,
                    busy = false,
                    sessionId = result.data?.optString("session_id"),
                    recoveryKey = result.data?.optString("recovery_key"),
                    error = null,
                    message = "Guarda la clave de recuperación.",
                )
            }
        }
    }

    fun confirmRecoveryKey() {
        val sessionId = _state.value.sessionId ?: return
        _state.update {
            it.copy(
                screen = AppScreen.VAULT,
                recoveryKey = null,
                busy = true,
                message = "Leyendo la raíz cifrada…",
            )
        }
        loadRootDirectory(sessionId)
    }

    fun toggleVaultVisibility() {
        _state.update {
            it.copy(vaultContentVisible = !it.vaultContentVisible, selectedEntryIds = emptySet())
        }
    }

    fun switchTab(tab: AppTab) {
        _state.update {
            it.copy(
                activeTab = tab, error = null,
                vaultContentVisible = if (tab == AppTab.EXPLORER) it.vaultContentVisible else false,
                selectedEntryIds = if (tab == AppTab.EXPLORER) it.selectedEntryIds else emptySet(),
            )
        }
        if (tab == AppTab.LOGS) {
            loadLogs()
        }
    }

    fun currentDirectoryId(): String =
        _state.value.breadcrumbs.lastOrNull()?.id ?: currentRootUuid

    fun navigateToFolder(entry: VaultEntry) {
        if (!entry.isDirectory) return
        val sessionId = _state.value.sessionId ?: return
        val updatedCrumbs = _state.value.breadcrumbs + BreadcrumbNode(entry.id, entry.name)
        _state.update { it.copy(breadcrumbs = updatedCrumbs, selectedEntryIds = emptySet()) }
        loadDirectory(sessionId, entry.id)
    }

    fun navigateUp() {
        val current = _state.value.breadcrumbs
        if (current.size <= 1) return
        navigateToBreadcrumb(current.size - 2)
    }

    fun navigateToBreadcrumb(index: Int) {
        val current = _state.value.breadcrumbs
        if (index !in current.indices) return
        val sessionId = _state.value.sessionId ?: return
        val target = current[index]
        val truncated = current.subList(0, index + 1)
        _state.update { it.copy(breadcrumbs = truncated, selectedEntryIds = emptySet()) }
        loadDirectory(sessionId, target.id)
    }

    fun toggleSelect(entryId: String) {
        _state.update {
            val current = it.selectedEntryIds
            val next = if (current.contains(entryId)) current - entryId else current + entryId
            it.copy(selectedEntryIds = next)
        }
    }

    fun selectAll() {
        val allIds = _state.value.entries.map { it.id }.toSet()
        _state.update { it.copy(selectedEntryIds = allIds) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedEntryIds = emptySet()) }
    }

    fun refresh() {
        val sessionId = _state.value.sessionId ?: return
        loadDirectory(sessionId, currentDirectoryId())
    }

    fun editConnection() {
        verifiedVaultPassword = null
        _state.value.sessionId?.let(bridge::close)
        _state.update {
            it.copy(
                screen = AppScreen.CONNECTION,
                sessionId = null,
                vaultContentVisible = false,
                activeTab = AppTab.EXPLORER,
                recoveryKey = null,
                entries = emptyList(),
                busy = false,
                error = null,
                message = "Edita la conexión WebDAV.",
            )
        }
    }

    fun selectTheme(themeId: MixcoaraThemeId) {
        store.saveTheme(themeId)
        _state.update { it.copy(themeId = themeId) }
    }

    fun lock() {
        verifiedVaultPassword = null
        _state.value.sessionId?.let(bridge::close)
        _state.update {
            it.copy(
                screen = AppScreen.UNLOCK,
                sessionId = null,
                vaultContentVisible = false,
                activeTab = AppTab.EXPLORER,
                entries = emptyList(),
                breadcrumbs = emptyList(),
                selectedEntryIds = emptySet(),
                busy = false,
                error = null,
                message = "La bóveda está bloqueada.",
            )
        }
    }

    fun retrySelfTest() {
        verifiedVaultPassword = null
        _state.value.sessionId?.let(bridge::close)
        _state.value.let {
            _state.value = MixcoaraUiState(config = it.config)
        }
        runSelfTest()
    }

    private fun loadRootDirectory(sessionId: String) {
         viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "Leyendo la bóveda…") }
             val result = withContext(Dispatchers.IO) { bridge.list(sessionId) }
             if (!result.ok) {
                 _state.update {
                     it.copy(
                         busy = false,
                         error = result.message.ifBlank { "No se pudo leer la bóveda." },
                     )
                 }
                 return@launch
             }
            val dirId = result.data?.optString("directory_id").orEmpty()
            currentRootUuid = dirId
            val rootNode = BreadcrumbNode(dirId, _state.value.config.folder)
            _state.update {
                it.copy(
                    breadcrumbs = listOf(rootNode),
                    selectedEntryIds = emptySet(),
                )
            }
            parseAndSetEntries(result.data)
        }
    }

    private fun loadDirectory(sessionId: String, directoryId: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "Cargando carpeta…") }
            val result = withContext(Dispatchers.IO) { bridge.list(sessionId, directoryId) }
            if (!result.ok) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = result.message.ifBlank { "No se pudo leer la carpeta." },
                    )
                }
                return@launch
            }
            parseAndSetEntries(result.data)
        }
    }

    private fun parseAndSetEntries(data: JSONObject?) {
        val array = data?.optJSONArray("entries")
         val entries =
             buildList {
                 if (array != null) {
                     for (index in 0 until array.length()) {
                         val item = array.getJSONObject(index)
                         add(
                             VaultEntry(
                                 name = item.getString("name"),
                                 isDirectory = item.getBoolean("is_directory"),
                                 id = item.getString("id"),
                                 size = item.optLong("size"),
                                 parentId = item.optString("parent_id"),
                             ),
                         )
                     }
                 }
             }
         _state.update {
             it.copy(
                 screen = AppScreen.VAULT,
                 busy = false,
                 entries = entries,
                 error = null,
                 message =
                     if (entries.isEmpty()) "Esta carpeta está vacía."
                     else "${entries.size} elementos protegidos",
             )
         }
     }

    // -------------------------------------------------------------------------
    // Transferencias con progreso en segundo plano y servicio
    // -------------------------------------------------------------------------
    private fun executeTransfer(
        title: String,
        launchCall: suspend (opId: String) -> BridgeResult,
        cleanupAfter: suspend (opId: String) -> Unit = {},
        onCompleted: (JSONObject?) -> Unit,
    ) {
        val opId = UUID.randomUUID().toString()
        synchronized(localCancellation) {
            localCancellation.remove(opId)
        }
        _state.update {
            it.copy(
                activeProgress = TransferProgress(opId = opId, phase = title),
                error = null,
            )
        }
        MixcoaraForegroundService.update(
            getApplication(),
            title = title,
            text = "Iniciando transferencia…",
            progress = -1,
        )

        viewModelScope.launch {
            var doneResult: JSONObject? = null
            var doneError: String? = null
            var lastProgress = TransferProgress(opId = opId, phase = title)
            try {
                val initResult =
                    withContext(Dispatchers.IO) {
                        try {
                            launchCall(opId)
                        } catch (ex: Exception) {
                            BridgeResult(
                                ok = false,
                                code = "operation_start_failed",
                                message = ex.message ?: "${ex::class.java.simpleName}: $ex",
                                data = null,
                            )
                        }
                    }
                if (!initResult.ok) {
                    doneError = initResult.message.ifBlank { "No se pudo iniciar la operación." }
                    return@launch
                }

                var pollFailures = 0
                var finished = false
                while (isActive && !finished) {
                    val cancelled =
                        synchronized(localCancellation) {
                            localCancellation.remove(opId)
                        }
                    if (cancelled) {
                        doneError = "Operación cancelada."
                        break
                    }
                    delay(180)
                    val poll =
                        withContext(Dispatchers.IO) {
                            try {
                                bridge.pollProgress(opId)
                            } catch (_: Exception) {
                                null
                            }
                        }
                    if (poll == null || !poll.ok || poll.data == null) {
                        pollFailures++
                        if (pollFailures >= 8) {
                            doneError = "No se pudo leer el progreso de la operación."
                            break
                        }
                        continue
                    }
                    pollFailures = 0
                    val p = poll.data
                    val isDone = p.optBoolean("terminado")
                    val isCancelled = p.optBoolean("cancelado")
                    val err = parseProgressError(p)
                    val fraction = p.optDouble("fraccion", 0.0).toFloat().coerceIn(0f, 1f)
                    val percentInt = (fraction * 100).toInt().coerceIn(0, 100)
                    val phase = p.optString("fase")
                    val currentItem = p.optString("actual")

                    val progress =
                        TransferProgress(
                            opId = opId,
                            phase = phase,
                            state = p.optString("estado"),
                            currentItem = currentItem,
                            fraction = fraction,
                            filesDone = p.optInt("archivos_hechos"),
                            filesTotal = p.optInt("archivos_total"),
                            foldersDone = p.optInt("carpetas_hechas"),
                            foldersTotal = p.optInt("carpetas_total"),
                            bytesDone = p.optLong("bytes_hechos"),
                            bytesTotal = p.optLong("bytes_total"),
                            speedBytesPerSec = p.optDouble("velocidad", 0.0),
                            elapsedSec = p.optDouble("transcurrido", 0.0),
                            etaSec = if (p.has("eta") && !p.isNull("eta")) p.optDouble("eta") else null,
                            isCancelled = isCancelled,
                            isFinished = isDone,
                            error = err,
                            result = p.optJSONObject("resultado"),
                        )
                    lastProgress = progress
                    _state.update { it.copy(activeProgress = progress) }

                    MixcoaraForegroundService.update(
                        getApplication(),
                        title = phase.ifBlank { title },
                        text = progress.state.ifBlank { currentItem.ifBlank { "${progress.bytesDone} B procesados" } },
                        progress = if (progress.bytesTotal > 0) percentInt else -1,
                    )

                    if (isDone) {
                        doneResult = progress.result
                        doneError = err
                        finished = true
                    }
                }
            } catch (e: CancellationException) {
                doneError = "Operación cancelada."
                throw e
            } finally {
                _state.update {
                    it.copy(
                        activeProgress =
                            lastProgress.copy(
                                phase = "Limpieza de temporales",
                                state = "limpiando",
                                currentItem = "",
                                isFinished = false,
                            ),
                    )
                }
                MixcoaraForegroundService.update(
                    getApplication(),
                    title = "Limpieza de temporales",
                    text = "Finalizando operación…",
                    progress = -1,
                )
                runCatching {
                    withContext(Dispatchers.IO) { cleanupAfter(opId) }
                }
                MixcoaraForegroundService.stop(getApplication())
                _state.update { it.copy(activeProgress = null) }
                if (!doneError.isNullOrBlank()) {
                    _state.update { it.copy(error = doneError) }
                } else {
                    onCompleted(doneResult)
                    refresh()
                }
            }
        }
    }

    fun cancelActiveOperation() {
        val opId = _state.value.activeProgress?.opId ?: return
        synchronized(localCancellation) {
            localCancellation.add(opId)
        }
        viewModelScope.launch(Dispatchers.IO) {
            bridge.cancelOperation(opId)
        }
    }

    fun dismissTransferSummary() {
        _state.update { it.copy(transferSummary = null) }
    }

    fun uploadFile(localPath: String, verify: Boolean) {
        val sessionId = _state.value.sessionId ?: return
        val targetDir = currentDirectoryId()
        executeTransfer(
            title = "Subiendo archivo",
            launchCall = { opId ->
                bridge.uploadFile(sessionId, localPath, verify, targetDir, opId)
            },
            onCompleted = { res ->
                val errorsList = parseErrors(res?.optJSONArray("fallidos"))
                val summary =
                    TransferSummary(
                        title = "Subida de archivo completada",
                        filesCount = res?.optInt("ok") ?: 1,
                        foldersCount = 0,
                        bytesCount = res?.optLong("bytes") ?: 0L,
                        elapsedSec = res?.optDouble("segundos", 0.0) ?: 0.0,
                        destination = _state.value.config.folder,
                        errors = errorsList,
                    )
                _state.update { it.copy(transferSummary = summary) }
            },
        )
    }

    fun uploadPickedFile(uri: Uri, verify: Boolean) {
        val sessionId = _state.value.sessionId ?: return
        val targetDir = currentDirectoryId()
        var stagedFile: File? = null
        executeTransfer(
            title = "Preparando archivo",
            launchCall = { opId ->
                stagedFile = stageDocumentUri(uri, opId)
                bridge.uploadFile(sessionId, stagedFile!!.absolutePath, verify, targetDir, opId)
            },
            cleanupAfter = { _ ->
                stagedFile?.parentFile?.let { if (it.exists()) it.deleteRecursively() }
            },
            onCompleted = { res ->
                val errorsList = parseErrors(res?.optJSONArray("fallidos"))
                val summary =
                    TransferSummary(
                        title = "Subida de archivo completada",
                        filesCount = res?.optInt("ok") ?: 1,
                        foldersCount = 0,
                        bytesCount = res?.optLong("bytes") ?: 0L,
                        elapsedSec = res?.optDouble("segundos", 0.0) ?: 0.0,
                        destination = _state.value.config.folder,
                        errors = errorsList,
                    )
                _state.update { it.copy(transferSummary = summary) }
            },
        )
    }

    fun uploadFolder(localPath: String, verify: Boolean) {
        val sessionId = _state.value.sessionId ?: return
        val targetDir = currentDirectoryId()
        executeTransfer(
            title = "Subiendo carpeta",
            launchCall = { opId ->
                bridge.uploadFolder(sessionId, localPath, verify, targetDir, opId)
            },
            onCompleted = { res ->
                val errorsList = parseErrors(res?.optJSONArray("fallidos"))
                val summary =
                    TransferSummary(
                        title = "Subida de carpeta completada",
                        filesCount = res?.optInt("ok") ?: 0,
                        foldersCount = res?.optInt("carpetas") ?: 0,
                        bytesCount = res?.optLong("bytes") ?: 0L,
                        elapsedSec = res?.optDouble("segundos", 0.0) ?: 0.0,
                        destination = _state.value.config.folder,
                        errors = errorsList,
                    )
                _state.update { it.copy(transferSummary = summary) }
            },
        )
    }

    fun uploadPickedFolder(uri: Uri, verify: Boolean) {
        val sessionId = _state.value.sessionId ?: return
        val targetDir = currentDirectoryId()
        var stagingRoot: File? = null
        var stagedFolder: File? = null
        executeTransfer(
            title = "Preparando carpeta",
            launchCall = { opId ->
                val staged = stageTreeUri(uri, opId)
                stagingRoot = staged.first
                stagedFolder = staged.second
                bridge.uploadFolder(sessionId, stagedFolder!!.absolutePath, verify, targetDir, opId)
            },
            cleanupAfter = { _ ->
                stagingRoot?.let { if (it.exists()) it.deleteRecursively() }
            },
            onCompleted = { res ->
                val errorsList = parseErrors(res?.optJSONArray("fallidos"))
                val summary =
                    TransferSummary(
                        title = "Subida de carpeta completada",
                        filesCount = res?.optInt("ok") ?: 0,
                        foldersCount = res?.optInt("carpetas") ?: 0,
                        bytesCount = res?.optLong("bytes") ?: 0L,
                        elapsedSec = res?.optDouble("segundos", 0.0) ?: 0.0,
                        destination = _state.value.config.folder,
                        errors = errorsList,
                    )
                _state.update { it.copy(transferSummary = summary) }
            },
        )
    }

    fun downloadSelected(outputDir: String = "") {
        val sessionId = _state.value.sessionId ?: return
        val selected = _state.value.entries.filter { _state.value.selectedEntryIds.contains(it.id) }
        if (selected.isEmpty()) {
            _state.update { it.copy(error = "No has seleccionado ningún elemento.") }
            return
        }
        val array = JSONArray()
        for (entry in selected) {
            array.put(
                JSONObject()
                    .put("name", entry.name)
                    .put("is_directory", entry.isDirectory)
                    .put("id", entry.id)
                    .put("size", entry.size)
                    .put("parent_id", entry.parentId),
            )
        }
        val jsonStr = array.toString()
        executeTransfer(
            title = "Descargando selección (${selected.size})",
            launchCall = { opId ->
                bridge.downloadSelection(sessionId, jsonStr, outputDir, opId)
            },
            onCompleted = { res ->
                val errorsList = parseErrors(res?.optJSONArray("errores"))
                val summary =
                    TransferSummary(
                        title = "Descarga finalizada",
                        filesCount = res?.optInt("archivos") ?: selected.size,
                        foldersCount = res?.optInt("dirs") ?: 0,
                        bytesCount = res?.optLong("bytes") ?: 0L,
                        elapsedSec = res?.optDouble("segundos", 0.0) ?: 0.0,
                        destination = outputDir.ifBlank { "Descargas/Mixcoara/Descifrados" },
                        errors = errorsList,
                    )
                _state.update {
                    it.copy(
                        transferSummary = summary,
                        selectedEntryIds = emptySet(),
                    )
                }
            },
        )
    }

    fun downloadAll(outputDir: String = "") {
        val sessionId = _state.value.sessionId ?: return
        executeTransfer(
            title = "Restaurando bóveda completa",
            launchCall = { opId ->
                bridge.downloadAll(sessionId, outputDir, opId)
            },
            onCompleted = { res ->
                val errorsList = parseErrors(res?.optJSONArray("errores"))
                val summary =
                    TransferSummary(
                        title = "Bóveda completa restaurada",
                        filesCount = res?.optInt("archivos") ?: 0,
                        foldersCount = res?.optInt("dirs") ?: 0,
                        bytesCount = res?.optLong("bytes") ?: 0L,
                        elapsedSec = res?.optDouble("segundos", 0.0) ?: 0.0,
                        destination = outputDir.ifBlank { "Descargas/Mixcoara/Descifrados" },
                        errors = errorsList,
                    )
                _state.update { it.copy(transferSummary = summary) }
            },
        )
    }

    fun encryptLocal(sourcePath: String, targetPath: String = "") {
        val sessionId = _state.value.sessionId ?: return
        executeTransfer(
            title = "Cifrado local",
            launchCall = { opId ->
                bridge.encryptLocal(sessionId, sourcePath, targetPath, opId)
            },
            onCompleted = { res ->
                val dest = res?.optString("destino").orEmpty()
                val summary =
                    TransferSummary(
                        title = "Archivo cifrado localmente",
                        filesCount = 1,
                        foldersCount = 0,
                        bytesCount = res?.optLong("bytes") ?: 0L,
                        elapsedSec = 0.0,
                        destination = dest,
                    )
                _state.update { it.copy(transferSummary = summary) }
            },
        )
    }

    fun decryptLocal(sourcePath: String, targetPath: String = "") {
        val sessionId = _state.value.sessionId ?: return
        executeTransfer(
            title = "Descifrado local",
            launchCall = { opId ->
                bridge.decryptLocal(sessionId, sourcePath, targetPath, opId)
            },
            onCompleted = { res ->
                val dest = res?.optString("destino").orEmpty()
                val summary =
                    TransferSummary(
                        title = "Archivo descifrado localmente",
                        filesCount = 1,
                        foldersCount = 0,
                        bytesCount = res?.optLong("bytes") ?: 0L,
                        elapsedSec = 0.0,
                        destination = dest,
                    )
                _state.update { it.copy(transferSummary = summary) }
            },
        )
    }

    // -------------------------------------------------------------------------
    // Gestión de seguridad
    // -------------------------------------------------------------------------
    fun changePassphrase(current: String, newPass: String, repeatPass: String) {
        val sessionId = _state.value.sessionId ?: return
        when {
            newPass.length < 12 -> {
                _state.update { it.copy(error = "La nueva frase debe tener al menos 12 caracteres.") }
                return
            }
            newPass != repeatPass -> {
                _state.update { it.copy(error = "Las nuevas frases no coinciden.") }
                return
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "Cambiando frase de forma segura…") }
            val result =
                withContext(Dispatchers.IO) {
                    bridge.changePassphrase(sessionId, current, newPass)
                }
            _state.update {
                it.copy(
                    busy = false,
                    error = if (!result.ok) result.message else null,
                    message = if (result.ok) "Frase actualizada exitosamente." else it.message,
                )
            }
            if (result.ok) {
                verifiedVaultPassword = newPass
                VaultBiometrics.remove(getApplication(), VaultBiometrics.cloudScope(_state.value.config))
                _state.update { it.copy(biometricEnabled = false, message = "Contraseña actualizada. Vuelve a activar la biometría para esta contraseña.") }
            }
        }
    }

    fun regenerateRecoveryKey(currentPass: String) {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "Generando nueva clave de recuperación…") }
            val result =
                withContext(Dispatchers.IO) {
                    bridge.regenerateRecoveryKey(sessionId, currentPass)
                }
            _state.update {
                it.copy(
                    busy = false,
                    error = if (!result.ok) result.message else null,
                    recoveryKey = result.data?.optString("recovery_key"),
                    screen = if (result.ok) AppScreen.RECOVERY_KEY else it.screen,
                )
            }
        }
    }

    fun setBiometricEnabled(enabled: Boolean, activity: androidx.fragment.app.FragmentActivity) {
        if (_state.value.sessionId == null || _state.value.busy || _state.value.activeProgress != null) return
        val scope = VaultBiometrics.cloudScope(_state.value.config)
        if (!enabled) {
            VaultBiometrics.remove(getApplication(), scope)
            _state.update { it.copy(biometricEnabled = false) }
            return
        }
        val password = verifiedVaultPassword
        if (password == null) {
            _state.update { it.copy(error = "Abre de nuevo esta bóveda con su contraseña actual para activar la biometría. No necesitas crear otra contraseña.") }
            return
        }
        VaultBiometrics.enroll(activity, scope, password, {
            _state.update { it.copy(biometricEnabled = true, message = "Biometría activada para esta bóveda.") }
        }, { message -> _state.update { it.copy(error = message) } })
    }

    fun unlockWithBiometrics(activity: androidx.fragment.app.FragmentActivity) {
        if (_state.value.busy) return
        VaultBiometrics.unlock(activity, VaultBiometrics.cloudScope(_state.value.config),
            { password -> unlock(password) },
            { message -> _state.update { it.copy(error = message) } })
    }

    // -------------------------------------------------------------------------
    // Registro de actividad
    // -------------------------------------------------------------------------
    fun loadLogs() {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { bridge.readLogs(300) }
            if (res.ok) {
                val text = res.data?.optString("log_text").orEmpty()
                _state.update { it.copy(logText = text) }
            }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { bridge.clearLogs() }
            loadLogs()
        }
    }

    fun exportLogs(onDone: (path: String) -> Unit) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { bridge.exportLogs() }
            if (res.ok) {
                val path = res.data?.optString("export_path").orEmpty()
                onDone(path)
            }
        }
    }

    private fun parseErrors(array: JSONArray?): List<String> =
        buildList {
            if (array != null) {
                for (i in 0 until array.length()) {
                    val err = array.opt(i)
                    if (err is JSONArray && err.length() >= 2) {
                        add("${err.getString(0)}: ${err.getString(1)}")
                    } else if (err is JSONObject) {
                        add("${err.optString("item")}: ${err.optString("message")}")
                    } else {
                        add(err.toString())
                    }
                }
            }
        }

    private fun parseProgressError(progress: JSONObject): String? {
        return parseProgressErrorValue(progress)
    }

    private fun stageDocumentUri(uri: Uri, opId: String): File {
        val resolver = getApplication<Application>().contentResolver
        val source =
            DocumentFile.fromSingleUri(getApplication(), uri)
                ?: throw IOException("No se pudo abrir el archivo seleccionado.")
        val fileName = safeSegment(source.name ?: "archivo")
        val root = operationStagingDir(opId)
        val target = File(root, fileName)
        _state.update {
            it.copy(
                activeProgress =
                    it.activeProgress?.copy(
                        phase = "Preparando archivo",
                        state = "copiando",
                        currentItem = fileName,
                    ),
            )
        }
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: throw IOException("No se pudo leer el archivo seleccionado.")
        return target
    }

    private fun stageTreeUri(uri: Uri, opId: String): Pair<File, File> {
        val tree =
            DocumentFile.fromTreeUri(getApplication(), uri)
                ?: throw IOException("No se pudo abrir la carpeta seleccionada.")
        val rootName = safeSegment(tree.name ?: "carpeta")
        val stagingRoot = operationStagingDir(opId)
        val destinationRoot = File(stagingRoot, rootName)
        var copiedFiles = 0

        fun copyNode(node: DocumentFile, destination: File) {
            ensureNotCancelled(opId)
            val name = safeSegment(node.name ?: "sin_nombre")
            if (node.isDirectory) {
                val folder = File(destination, name)
                if (!folder.exists() && !folder.mkdirs()) {
                    throw IOException("No se pudo crear carpeta temporal: ${folder.absolutePath}")
                }
                node.listFiles().forEach { copyNode(it, folder) }
            } else if (node.isFile) {
                val resolver = getApplication<Application>().contentResolver
                _state.update {
                    it.copy(
                        activeProgress =
                            it.activeProgress?.copy(
                                phase = "Preparando carpeta",
                                state = "copiando",
                                currentItem = name,
                            ),
                    )
                }
                val outFile = File(destination, name)
                resolver.openInputStream(node.uri)?.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                } ?: throw IOException("No se pudo leer: $name")
                copiedFiles++
            }
        }

        if (!destinationRoot.mkdirs()) {
            throw IOException("No se pudo preparar el directorio temporal para la carpeta.")
        }
        tree.listFiles().forEach { copyNode(it, destinationRoot) }
        if (copiedFiles == 0) {
            throw IOException("La carpeta seleccionada está vacía.")
        }
        return stagingRoot to destinationRoot
    }

    private fun operationStagingDir(opId: String): File {
        val base =
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Mixcoara/.temporales/android_$opId",
            )
        if (!base.exists() && !base.mkdirs()) {
            throw IOException("No se pudo crear espacio temporal para la operación.")
        }
        return base
    }

    private fun safeSegment(name: String): String {
        val cleaned = name.replace('/', '_').replace('\\', '_').replace('\u0000', '_').trim()
        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") {
            throw IOException("Nombre inseguro detectado en la selección.")
        }
        return cleaned
    }

    private fun ensureNotCancelled(opId: String) {
        synchronized(localCancellation) {
            if (localCancellation.contains(opId)) {
                throw CancellationException("Operación cancelada.")
            }
        }
    }

    override fun onCleared() {
        verifiedVaultPassword = null
        _state.value.sessionId?.let(bridge::close)
        super.onCleared()
    }
}
