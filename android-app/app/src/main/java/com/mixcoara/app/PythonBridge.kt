package com.mixcoara.app

import android.content.Context
import android.os.Environment
import com.chaquo.python.Python
import org.json.JSONObject

data class BridgeResult(
    val ok: Boolean,
    val code: String,
    val message: String,
    val data: JSONObject?,
)

class PythonBridge(context: Context) {
    private val module = Python.getInstance().getModule("mixcoara_bridge")
    private val privateDir = context.filesDir.resolve("mixcoara").absolutePath
    private val downloadDir =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    fun selfTest(): BridgeResult =
        invoke("self_test", privateDir, downloadDir)

    fun checkVault(config: VaultConfig): BridgeResult =
        invoke("comprobar_boveda", config.toJson(), privateDir, downloadDir)

    fun unlock(config: VaultConfig, passphrase: String): BridgeResult =
        invoke("abrir_boveda", config.toJson(), passphrase, privateDir, downloadDir)

    fun create(config: VaultConfig, passphrase: String): BridgeResult =
        invoke("crear_boveda", config.toJson(), passphrase, privateDir, downloadDir)

    fun list(sessionId: String, directoryId: String = ""): BridgeResult =
        invoke("listar", sessionId, directoryId)

    fun uploadFile(
        sessionId: String,
        localPath: String,
        verify: Boolean,
        targetDirId: String,
        opId: String,
    ): BridgeResult =
        invoke("subir_archivo", sessionId, localPath, verify, targetDirId, opId)

    fun uploadFolder(
        sessionId: String,
        localPath: String,
        verify: Boolean,
        targetDirId: String,
        opId: String,
    ): BridgeResult =
        invoke("subir_carpeta", sessionId, localPath, verify, targetDirId, opId)

    fun downloadSelection(
        sessionId: String,
        entriesJson: String,
        outputDir: String,
        opId: String,
    ): BridgeResult =
        invoke("descargar_seleccion", sessionId, entriesJson, outputDir, opId)

    fun deleteSelection(sessionId: String, entriesJson: String): BridgeResult =
        invoke("borrar_seleccion", sessionId, entriesJson)

    fun downloadAll(sessionId: String, outputDir: String, opId: String): BridgeResult =
        invoke("descargar_todo", sessionId, outputDir, opId)

    fun encryptLocal(
        sessionId: String,
        sourcePath: String,
        targetPath: String,
        opId: String,
    ): BridgeResult =
        invoke("cifrar_local", sessionId, sourcePath, targetPath, opId)

    fun decryptLocal(
        sessionId: String,
        sourcePath: String,
        targetPath: String,
        opId: String,
    ): BridgeResult =
        invoke("descifrar_local", sessionId, sourcePath, targetPath, opId)

    fun pollProgress(opId: String): BridgeResult =
        invoke("obtener_progreso", opId)

    fun cancelOperation(opId: String): BridgeResult =
        invoke("cancelar_operacion", opId)

    fun changePassphrase(
        sessionId: String,
        currentPassphrase: String,
        newPassphrase: String,
    ): BridgeResult =
        invoke("cambiar_frase", sessionId, currentPassphrase, newPassphrase)

    fun regenerateRecoveryKey(sessionId: String, currentPassphrase: String): BridgeResult =
        invoke("regenerar_clave_recuperacion", sessionId, currentPassphrase)

    fun readLogs(maxLines: Int = 250): BridgeResult =
        invoke("leer_registro", maxLines)

    fun clearLogs(): BridgeResult =
        invoke("limpiar_registro")

    fun exportLogs(): BridgeResult =
        invoke("exportar_registro")

    fun exportReportVault(sessionId: String): BridgeResult =
        invoke("exportar_reporte_boveda", sessionId)

    fun close(sessionId: String) {
        invoke("cerrar_sesion", sessionId)
    }

    private fun invoke(name: String, vararg args: Any): BridgeResult {
        val raw = module.callAttr(name, *args).toString()
        val json = JSONObject(raw)
        return BridgeResult(
            ok = json.optBoolean("ok"),
            code = json.optString("code"),
            message = json.optString("message"),
            data = json.optJSONObject("data"),
        )
    }

    private fun VaultConfig.toJson(): String =
        JSONObject()
            .put("url", url)
            .put("usuario", username)
            .put("password", password)
            .put("carpeta", folder)
            .toString()
}
