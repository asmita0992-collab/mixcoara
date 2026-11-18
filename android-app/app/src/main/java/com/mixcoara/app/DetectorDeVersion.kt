package com.mixcoara.app

import android.content.Context

/**
 * Detecta una actualización de la aplicación y descarta el estado que quedó
 * de la versión anterior.
 *
 * Android puede reiniciar la actividad conservando el estado guardado en
 * pantalla (`rememberSaveable`) y el proceso puede sobrevivir a la instalación
 * de un APK nuevo. Sin esta comprobación, la app seguiría mostrando la
 * interfaz de la versión previa aunque el código ya sea otro.
 */
object DetectorDeVersion {

    private const val PREFERENCIAS = "mixcoara_version"

    private fun preferencias(context: Context) =
        context.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE)

    /**
     * Compara la versión instalada con la que quedó registrada la última vez.
     * Devuelve `true` cuando la aplicación se acaba de actualizar, en cuyo
     * caso ya limpió el estado heredado.
     */
    fun huboActualizacion(context: Context, versionCode: Int): Boolean {
        val previas = preferencias(context)
        val anterior = previas.getInt("version_code", 0)
        if (anterior == versionCode) return false
        limpiar(context)
        previas.edit().putInt("version_code", versionCode).apply()
        return anterior != 0
    }

    /**
     * Conserva las rutas de bóvedas y las preferencias criptográficas persistentes.
     * La sesión activa se reinicia al iniciar el nuevo proceso, sin eliminar datos.
     */
    private fun limpiar(context: Context) {
        SecureConfigStore(context).limpiarFormatoAntiguo()
        // No borrar "paths": las bóvedas antiguas deben seguir localizables. 
    }
}
