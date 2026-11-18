package com.mixcoara.app

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.DriveFolderUpload
import androidx.compose.material3.Icon
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chaquo.python.Python
import com.tlahuis.mixcoara.BuildConfig
import com.tlahuis.mixcoara.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

data class DirectoryItem(val id: String, val parent: String, val name: String, val directory: Boolean, val size: Long)
data class LocalDirectoryState(
    val session: String = "", val path: String = "", val parent: String = "",
    val crumbs: List<Pair<String, String>> = emptyList(), val entries: List<DirectoryItem> = emptyList(),
    val busy: Boolean = false, val message: String = "", val error: String = "", val recovery: String = "",
    val phase: String = "", val current: String = "", val done: Long = 0, val total: Long = 0,
    val biometric: Boolean = false,
)

class LocalDirectoryViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(LocalDirectoryState())
    val state = mutable.asStateFlow()
    private var verifiedPassword: String? = null
    private var operation: String = ""
    private val recent = application.getSharedPreferences("mixcoara_local_directories", 0)

    init {
        // Una actualización reinicia el estado heredado de la versión anterior.
        DetectorDeVersion.huboActualizacion(application, BuildConfig.VERSION_CODE)
    }

    fun recentPaths(): List<String> = recent.getStringSet("paths", emptySet())!!.toList().sorted()
    fun reportError(text: String) { mutable.update { it.copy(error = text) } }
    fun dismissMessage() { mutable.update { it.copy(error = "", message = "") } }
    fun confirmRecovery() { mutable.update { it.copy(recovery = "") } }
    private fun call(action: String, data: JSONObject = JSONObject()): JSONObject {
        val json = JSONObject(Python.getInstance().getModule("mixcoara_local_vault").callAttr("dispatch", action, data.toString()).toString())
        if (!json.optBoolean("ok")) error(json.optString("message", "No se pudo completar la operación."))
        return json.optJSONObject("data") ?: JSONObject()
    }
    fun open(path: String, password: String, create: Boolean = false, recovery: String = "") {
        if (mutable.value.busy || mutable.value.session.isNotBlank()) return
        mutable.update { it.copy(busy = true, error = "", phase = "Abriendo bóveda local") }
        viewModelScope.launch {
            try {
                val actual = File(path.trim()).canonicalPath
                val data = withContext(Dispatchers.IO) {
                    when {
                        recovery.isNotBlank() -> call("recover", JSONObject().put("path", actual).put("recovery", recovery).put("new_password", password))
                        create -> call("create", JSONObject().put("path", actual).put("password", password))
                        else -> call("open", JSONObject().put("path", actual).put("password", password))
                    }
                }
                verifiedPassword = password
                val storedPath = data.getString("path")
                recent.edit().putStringSet("paths", recentPaths().toSet() + storedPath).apply()
                if (recovery.isNotBlank()) VaultBiometrics.remove(getApplication(), VaultBiometrics.localScope(storedPath))
                mutable.update { LocalDirectoryState(session = data.getString("session"), path = storedPath,
                    recovery = data.optString("recovery", ""), biometric = VaultBiometrics.has(getApplication(), VaultBiometrics.localScope(storedPath))) }
                reload()
            } catch (e: Exception) { mutable.update { it.copy(busy = false, phase = "", error = e.message ?: "No se pudo abrir la bóveda local.") } }
        }
    }
    fun openWithFingerprint(activity: FragmentActivity, path: String) {
        if (mutable.value.busy) return
        val actual = runCatching { File(path.trim()).canonicalPath }.getOrElse { reportError("Ruta no válida."); return }
        VaultBiometrics.unlock(activity, VaultBiometrics.localScope(actual), { pass -> open(actual, pass) }, ::reportError)
    }
    fun setFingerprint(activity: FragmentActivity, enable: Boolean) {
        val current = mutable.value
        if (current.busy || current.session.isBlank()) return
        val scope = VaultBiometrics.localScope(current.path)
        if (!enable) {
            VaultBiometrics.remove(activity, scope)
            mutable.update { it.copy(biometric = false) }
            return
        }
        val pass = verifiedPassword
        if (pass == null) { reportError("Bloquea y abre esta bóveda con su contraseña actual para activar la biometría."); return }
        VaultBiometrics.enroll(activity, scope, pass, {
            mutable.update { it.copy(biometric = true, message = "Biometría activada para esta bóveda local.") }
        }, ::reportError)
    }
    fun lock(after: () -> Unit = {}) {
        if (mutable.value.busy) return
        val sid = mutable.value.session
        if (sid.isBlank()) { after(); return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { call("close", JSONObject().put("sid", sid)) }
                verifiedPassword = null
                mutable.value = LocalDirectoryState()
                after()
            } catch (e: Exception) { reportError(e.message ?: "No se pudo bloquear.") }
        }
    }
    fun navigate(item: DirectoryItem) {
        if (!item.directory || mutable.value.busy) return
        mutable.update { it.copy(parent = item.id, crumbs = it.crumbs + (item.id to item.name)) }; reload()
    }
    fun up() {
        val crumbs = mutable.value.crumbs.dropLast(1)
        mutable.update { it.copy(parent = crumbs.lastOrNull()?.first ?: "", crumbs = crumbs) }; reload()
    }
    private suspend fun readEntries() {
        val current = mutable.value
        if (current.session.isBlank()) return
        val data = withContext(Dispatchers.IO) { call("list", JSONObject().put("sid", current.session).put("parent", current.parent)) }
        val array = data.getJSONArray("entries")
        val entries = (0 until array.length()).map { i ->
            val n = array.getJSONObject(i)
            DirectoryItem(n.getString("id"), n.getString("parent"), n.getString("name"), n.getBoolean("dir"), n.getLong("size"))
        }
        mutable.update { it.copy(entries = entries) }
    }
    fun reload() {
        if (mutable.value.busy || mutable.value.session.isBlank()) return
        viewModelScope.launch { runCatching { readEntries() }.onFailure { reportError(it.message ?: "No se pudo leer el directorio.") } }
    }
    fun importPath(path: String) { transfer("import", path, "", "") }
    fun importFile(path: String) { transfer("import", path, "", "") }
    fun export(item: DirectoryItem, directory: String) { transfer("export", "", item.id, directory) }
    private fun transfer(kind: String, source: String, entry: String, output: String) {
        val current = mutable.value
        if (current.busy || current.session.isBlank()) return
        mutable.update { it.copy(busy = true, error = "", message = "", phase = "Preparando", done = 0, total = 0) }
        viewModelScope.launch {
            var backendFinished = false
            try {
                MixcoaraForegroundService.update(getApplication(), "Bóveda local", "Preparando operación", -1)
                val start = withContext(Dispatchers.IO) { call("start", JSONObject().put("sid", current.session).put("kind", kind)
                    .put("source", source.trim()).put("parent", current.parent).put("entry", entry).put("output", output.trim())) }
                operation = start.getString("operation")
                var finished = false
                while (!finished) {
                    delay(200)
                    val p = withContext(Dispatchers.IO) { call("poll", JSONObject().put("oid", operation)) }
                    finished = p.optBoolean("finished")
                    backendFinished = finished
                    val total = p.optLong("total"); val done = p.optLong("done")
                    mutable.update { it.copy(phase = p.optString("phase"), current = p.optString("current"), done = done, total = total) }
                    MixcoaraForegroundService.update(getApplication(), p.optString("phase"), p.optString("current").ifBlank { "Bóveda local" },
                        if (total > 0) ((done.toDouble() / total) * 100).toInt().coerceIn(0, 100) else -1)
                    if (finished) {
                        if (p.optString("error").isNotEmpty()) error(p.optString("error"))
                        mutable.update { it.copy(message = p.optJSONObject("result")?.optString("message") ?: "Operación terminada.") }
                    }
                }
                readEntries()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportError(e.message ?: "Falló la operación local.") }
            finally {
                withContext(NonCancellable) {
                    val oid = operation
                    if (oid.isNotBlank()) withContext(Dispatchers.IO) {
                        if (!backendFinished) {
                            runCatching { call("cancel", JSONObject().put("oid", oid)) }
                            var attempts = 0
                            while (!backendFinished && attempts < 300) {
                                backendFinished = runCatching { call("poll", JSONObject().put("oid", oid)).optBoolean("finished") }.getOrDefault(false)
                                if (!backendFinished) Thread.sleep(100)
                                attempts++
                            }
                        }
                        runCatching { call("forget", JSONObject().put("oid", oid)) }
                    }
                    operation = ""
                    runCatching { MixcoaraForegroundService.stop(getApplication()) }
                    mutable.update { it.copy(busy = false, phase = "", current = "") }
                }
            }
        }
    }
    fun cancel() {
        val id = operation
        if (id.isNotBlank()) viewModelScope.launch(Dispatchers.IO) { runCatching { call("cancel", JSONObject().put("oid", id)) } }
    }
    override fun onCleared() {
        verifiedPassword = null
        val sid = mutable.value.session
        val oid = operation
        Thread {
            if (oid.isNotBlank()) {
                runCatching { call("cancel", JSONObject().put("oid", oid)) }
                repeat(300) {
                    val done = runCatching { call("poll", JSONObject().put("oid", oid)).optBoolean("finished") }.getOrDefault(true)
                    if (done) { runCatching { call("close", JSONObject().put("sid", sid)) }; return@Thread }
                    Thread.sleep(100)
                }
            } else if (sid.isNotBlank()) runCatching { call("close", JSONObject().put("sid", sid)) }
        }.start()
        super.onCleared()
    }
}

/** Solo se resuelve el proveedor local primary, nunca un content:// arbitrario. */
private fun localTreePath(uri: Uri): String {
    require(uri.authority == "com.android.externalstorage.documents") { "Selecciona una carpeta del almacenamiento interno, no un proveedor en la nube." }
    val id = DocumentsContract.getTreeDocumentId(uri)
    val parts = id.split(":", limit = 2)
    require(parts.size == 2 && parts[0] == "primary") { "Por ahora las bóvedas locales usan el almacenamiento interno principal. Para otra ubicación usa una ruta local accesible." }
    val base = Environment.getExternalStorageDirectory().canonicalFile
    val result = File(base, parts[1]).canonicalFile
    require(result == base || result.path.startsWith(base.path + File.separator)) { "Ruta local no válida." }
    require(result.isDirectory) { "La carpeta no es accesible. Revisa el permiso de archivos." }
    return result.path
}

/** Resuelve un documento individual (no un árbol) a su ruta en el almacenamiento interno. */
private fun localFilePath(uri: Uri): String {
    require(uri.authority == "com.android.externalstorage.documents") { "Selecciona un archivo del almacenamiento interno, no un proveedor en la nube." }
    val id = DocumentsContract.getDocumentId(uri)
    val parts = id.split(":", limit = 2)
    require(parts.size == 2 && parts[0] == "primary") { "Por ahora la bóveda local solo importa archivos del almacenamiento interno principal." }
    val base = Environment.getExternalStorageDirectory().canonicalFile
    val result = File(base, parts[1]).canonicalFile
    require(result.path.startsWith(base.path + File.separator)) { "Ruta local no válida." }
    require(result.isFile) { "El archivo no es accesible. Revisa el permiso de archivos." }
    return result.path
}

@Composable
private fun LocalPassword(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
fun LocalDirectoryScreen(
    activity: FragmentActivity,
    onExit: (() -> Unit)? = null,
    legacyContent: (@Composable () -> Unit)? = null,
) {
    val model: LocalDirectoryViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    var selectedPath by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var recovering by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var repeatPassword by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("MiBovedaLocal") }
    var manualPath by remember { mutableStateOf("") }
    var importPath by remember { mutableStateOf("") }
    var importKind by remember { mutableStateOf<String?>(null) }
    var contentVisible by rememberSaveable { mutableStateOf(false) }
    var showManualLocation by rememberSaveable { mutableStateOf(false) }
    var showBiometricOffer by remember { mutableStateOf(false) }
    LaunchedEffect(state.session, state.path) {
        contentVisible = false
        showBiometricOffer = state.session.isNotBlank() &&
            VaultBiometrics.shouldOffer(activity, VaultBiometrics.localScope(state.path))
    }
    var exportItem by remember { mutableStateOf<DirectoryItem?>(null) }
    var exporting by remember { mutableStateOf<DirectoryItem?>(null) }
    var exportPath by remember { mutableStateOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).resolve("Mixcoara/Descifrados").absolutePath) }
    var legacy by remember { mutableStateOf(false) }
    fun pathResult(uri: Uri?, action: (String) -> Unit) {
        if (uri != null) runCatching { localTreePath(uri) }.onSuccess(action).onFailure { model.reportError(it.message ?: "No se pudo acceder a la carpeta.") }
    }
    val vaultPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pathResult(uri) { selectedPath = it; password = ""; repeatPassword = ""; recovery = ""; showDialog = true }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pathResult(uri) { model.importPath(it) }
    }
    val importFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { localFilePath(uri) }
            .onSuccess { model.importFile(it) }
            .onFailure { model.reportError(it.message ?: "No se pudo acceder al archivo.") }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val chosen = exportItem; exportItem = null
        if (chosen != null) pathResult(uri) { model.export(chosen, it) }
    }
    if (showBiometricOffer && state.session.isNotBlank() && state.recovery.isBlank()) {
        val scope = VaultBiometrics.localScope(state.path)
        AlertDialog(
            onDismissRequest = {
                VaultBiometrics.markOffered(activity, scope)
                showBiometricOffer = false
            },
            title = { Text("¿Activar acceso biométrico?") },
            text = { Text("¿Quieres usar huella o reconocimiento facial compatible con Android para abrir esta bóveda local? La contraseña permanece protegida con Android Keystore. Podrás modificarlo después en Configuración.") },
            confirmButton = {
                Button(onClick = {
                    VaultBiometrics.markOffered(activity, scope)
                    showBiometricOffer = false
                    model.setFingerprint(activity, true)
                }) { Text("Activar biometría") }
            },
            dismissButton = {
                TextButton(onClick = {
                    VaultBiometrics.markOffered(activity, scope)
                    showBiometricOffer = false
                }) { Text("Ahora no") }
            },
        )
    }
    importKind?.let { kind ->
        AlertDialog(
            onDismissRequest = { importKind = null; importPath = "" },
            title = { Text(if (kind == "file") "Añadir archivo a la bóveda local" else "Añadir carpeta a la bóveda local") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("El original no se elimina. El contenido se cifrará dentro de la carpeta actual de la bóveda.")
                    OutlinedButton(onClick = {
                        importKind = null
                        if (kind == "file") importFilePicker.launch(arrayOf("*/*")) else importPicker.launch(null)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (kind == "file") "Seleccionar archivo" else "Seleccionar carpeta")
                    }
                    OutlinedTextField(
                        value = importPath, onValueChange = { importPath = it },
                        label = { Text("O escribe una ruta local") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(enabled = importPath.isNotBlank(), onClick = {
                    val path = importPath.trim()
                    importKind = null
                    importPath = ""
                    val source = File(path)
                    if ((kind == "file" && source.isFile) || (kind == "folder" && source.isDirectory)) {
                        model.importPath(source.path)
                    } else model.reportError("Selecciona una ruta de ${if (kind == "file") "archivo" else "carpeta"} existente.")
                }) { Text("Importar ruta") }
            },
            dismissButton = { TextButton(onClick = { importKind = null; importPath = "" }) { Text("Cancelar") } },
        )
    }
    if (state.recovery.isNotBlank()) {
        var confirmed by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = {}, title = { Text("Guarda tu clave de recuperación local") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Esta clave pertenece solo a esta bóveda local. Sin contraseña ni clave no podrás recuperar sus archivos.")
                androidx.compose.foundation.text.selection.SelectionContainer { Text(state.recovery, fontWeight = FontWeight.Bold) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = confirmed, onCheckedChange = { confirmed = it }); Text("Ya la guardé en un lugar seguro") }
            } }, confirmButton = { Button(onClick = model::confirmRecovery, enabled = confirmed) { Text("Continuar") } })
    }
    if (showDialog) {
        AlertDialog(onDismissRequest = { showDialog = false; password = ""; repeatPassword = ""; recovery = "" },
            title = { Text(if (recovering) "Recuperar bóveda local" else if (creating) "Crear bóveda local" else "Abrir bóveda local") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(selectedPath, style = MaterialTheme.typography.bodySmall)
                if (creating) OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Nombre de la carpeta nueva") }, singleLine = true)
                if (recovering) LocalPassword(recovery, { recovery = it }, "Clave de recuperación de esta bóveda")
                LocalPassword(password, { password = it }, if (creating || recovering) "Contraseña nueva de la bóveda local (mínimo 12)" else "Contraseña actual de esta bóveda local")
                if (creating || recovering) LocalPassword(repeatPassword, { repeatPassword = it }, "Repite la contraseña nueva")
                if (!creating && !recovering) {
                    Text("Es la contraseña con la que se creó esta bóveda, no la de WebDAV.", style = MaterialTheme.typography.bodySmall)
                    if (VaultBiometrics.has(activity, VaultBiometrics.localScope(selectedPath))) {
                        OutlinedButton(onClick = { showDialog = false; password = ""; model.openWithFingerprint(activity, selectedPath) }) { Text("Abrir con biometría") }
                    }
                    TextButton(onClick = { recovering = true; password = "" }) { Text("Olvidé la contraseña") }
                }
            } },
            confirmButton = { Button(enabled = password.isNotEmpty() && (!(creating || recovering) || (password.length >= 12 && password == repeatPassword)), onClick = {
                val name = newName.trim()
                if (creating && (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\'))) {
                    model.reportError("Usa un nombre de carpeta válido.")
                } else {
                    val path = if (creating) File(selectedPath, name).path else selectedPath
                    model.open(path, password, creating, if (recovering) recovery else "")
                    showDialog = false; password = ""; repeatPassword = ""; recovery = ""
                }
            }) { Text(if (creating) "Crear" else if (recovering) "Recuperar" else "Abrir") } },
            dismissButton = { TextButton(onClick = { showDialog = false; password = ""; repeatPassword = ""; recovery = "" }) { Text("Cancelar") } })
    }
    exporting?.let { item ->
        AlertDialog(onDismissRequest = { exporting = null }, title = { Text("Exportar copia descifrada") },
            text = { Column {
                Text("${item.name}: la copia exportada quedará sin cifrar fuera de la bóveda.")
                OutlinedTextField(value = exportPath, onValueChange = { exportPath = it }, label = { Text("Carpeta de destino existente") })
                OutlinedButton(onClick = { exportItem = item; exporting = null; exportPicker.launch(null) }) { Text("Seleccionar destino") }
            } }, confirmButton = { Button(onClick = {
                runCatching { val folder = File(exportPath.trim()); if (!folder.isDirectory) require(folder.mkdirs()) { "No se pudo crear el destino." }; model.export(item, folder.path) }
                    .onFailure { model.reportError(it.message ?: "Destino no accesible.") }
                exporting = null
            }) { Text("Exportar a esta ruta") } }, dismissButton = { TextButton(onClick = { exporting = null }) { Text("Cancelar") } })
    }
    if (state.error.isNotBlank() || state.message.isNotBlank()) {
        AlertDialog(onDismissRequest = model::dismissMessage, title = { Text(if (state.error.isNotBlank()) "No se completó la operación" else "Mixcoara Local") },
            text = { Text(state.error.ifBlank { state.message }) }, confirmButton = { TextButton(onClick = model::dismissMessage) { Text("Entendido") } })
    }
    if (state.busy) {
        AlertDialog(onDismissRequest = {}, title = { Text(state.phase.ifBlank { "Procesando bóveda local" }) }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.current)
            if (state.total > 0) {
                val fraction = (state.done.toDouble() / state.total).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text("${(fraction * 100).toInt()}% · ${state.done} de ${state.total} bytes")
            } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Los archivos originales no se borran.")
        } }, confirmButton = { TextButton(onClick = model::cancel) { Text("Cancelar operación de archivos") } })
    }
    if (legacy && legacyContent != null) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { legacy = false }) { Text("Volver a bóvedas locales independientes") }
            Box(Modifier.weight(1f)) { legacyContent() }
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (onExit != null) TextButton(onClick = { model.lock(onExit) }, enabled = !state.busy) { Text("Volver al inicio") }
            Card {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.mixcoara_icon), "Ícono oficial de Mixcoara", Modifier.size(54.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Bóvedas locales", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Directorios cifrados con contraseña propia. Sin nube ni WebDAV.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (state.session.isBlank()) {
            item {
                Button(onClick = { creating = true; recovering = false; vaultPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Crear bóveda local nueva") }
                OutlinedButton(onClick = { creating = false; recovering = false; vaultPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Añadir / abrir bóveda local existente") }
                Text("El selector admite carpetas del almacenamiento interno principal. También puedes indicar una ruta local accesible.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                TextButton(onClick = { showManualLocation = !showManualLocation }) {
                    Text(if (showManualLocation) "Ocultar entrada manual" else "Usar ruta local manual")
                }
                if (showManualLocation) {
                    OutlinedTextField(value = manualPath, onValueChange = { manualPath = it }, label = { Text("Ruta local de carpeta") }, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(enabled = manualPath.isNotBlank(), onClick = { selectedPath = manualPath.trim(); creating = true; recovering = false; showDialog = true }) { Text("Crear dentro") }
                        TextButton(enabled = manualPath.isNotBlank(), onClick = { selectedPath = manualPath.trim(); creating = false; recovering = false; showDialog = true }) { Text("Abrir esta bóveda") }
                    }
                }
            }
            items(model.recentPaths(), key = { it }) { path ->
                Card(Modifier.fillMaxWidth().clickable { selectedPath = path; creating = false; recovering = false; showDialog = true }) {
                    Text(path, Modifier.padding(16.dp))
                }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Bóveda local activa", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { contentVisible = !contentVisible }, enabled = !state.busy) {
                        Icon(if (contentVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (contentVisible) "Ocultar archivos cifrados" else "Mostrar archivos cifrados")
                    }
                    TextButton(onClick = { model.lock() }, enabled = !state.busy) { Text("Bloquear") }
                }
                if (contentVisible) {
                    Text(state.path, style = MaterialTheme.typography.bodySmall)
                    Text("Raíz" + state.crumbs.joinToString("") { " / ${it.second}" })
                } else {
                    Text("Contenido oculto. Toca el ojo para ver archivos y carpetas.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.crumbs.isNotEmpty()) TextButton(onClick = { model.up(); contentVisible = false }, enabled = !state.busy) { Text("Subir nivel") }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { importKind = "file" }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Añadir archivo")
                    }
                    OutlinedButton(onClick = { importKind = "folder" }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.DriveFolderUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Añadir carpeta")
                    }
                }
                if (contentVisible && state.entries.isEmpty()) Text("Esta carpeta de la bóveda está vacía.")
            }
            if (contentVisible) {
                items(state.entries, key = { it.id }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(item.name, fontWeight = FontWeight.Bold)
                            Text(if (item.directory) "Carpeta" else "${item.size} bytes", style = MaterialTheme.typography.bodySmall)
                            Row {
                                if (item.directory) TextButton(onClick = { model.navigate(item); contentVisible = false }, enabled = !state.busy) { Text("Abrir") }
                                TextButton(onClick = { exporting = item }, enabled = !state.busy) { Text("Exportar copia") }
                            }
                        }
                    }
                }
            }
        }
        if (legacyContent != null && !state.busy && state.session.isBlank()) item {
            TextButton(onClick = { legacy = true }) { Text("Compatibilidad: archivos .mixcoara anteriores") }
        }
    }
}
