package com.mixcoara.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Secretos separados por bóveda y ligados criptográficamente a biometría fuerte. */
object VaultBiometrics {
    private const val PREFS = "mixcoara_biometrics_v221"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private fun token(scope: String): String = MessageDigest.getInstance("SHA-256")
        .digest(scope.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(scope: String) = "mixcoara_bio_" + token(scope)

    fun cloudScope(config: VaultConfig): String = "cloud|${config.url.trim().trimEnd('/')}|${config.username}|${config.folder}"
    fun localScope(path: String): String = "local|$path"
    private const val CHOICES = "mixcoara_biometric_choices_v240"
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    fun shouldOffer(context: Context, scope: String): Boolean =
        isAvailable(context) && !has(context, scope) &&
            !context.getSharedPreferences(CHOICES, Context.MODE_PRIVATE).getBoolean(token(scope), false)
    fun markOffered(context: Context, scope: String) {
        context.getSharedPreferences(CHOICES, Context.MODE_PRIVATE).edit().putBoolean(token(scope), true).apply()
    }

    fun has(context: Context, scope: String): Boolean {
        val id = token(scope)
        return prefs(context).contains("${id}_data") && prefs(context).contains("${id}_iv") &&
            runCatching { keys().containsAlias(alias(scope)) }.getOrDefault(false)
    }
    fun remove(context: Context, scope: String) {
        val id = token(scope)
        prefs(context).edit().remove("${id}_data").remove("${id}_iv").apply()
        runCatching { keys().deleteEntry(alias(scope)) }
    }
    private fun prompt(
        activity: FragmentActivity, cipher: Cipher, title: String,
        onSuccess: (Cipher) -> Unit, onError: (String) -> Unit,
    ) {
        val status = BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            onError("Configura un método biométrico fuerte compatible (huella o rostro) en los ajustes de Android. Mientras tanto usa la contraseña de la bóveda.")
            return
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authenticated = result.cryptoObject?.cipher
                if (authenticated == null) { onError("No se recibió autorización criptográfica."); return }
                runCatching { onSuccess(authenticated) }.onFailure {
                    onError("No se pudo usar la protección biométrica. Abre con la contraseña y vuelve a activar la biometría.")
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onError(errString.toString()) }
        })
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title)
            .setSubtitle("Autoriza el acceso a esta bóveda de Mixcoara")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancelar").build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
    fun enroll(activity: FragmentActivity, scope: String, verifiedPassword: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (verifiedPassword.isEmpty()) { onError("Abre primero esta bóveda con su contraseña actual."); return }
        runCatching {
            remove(activity, scope)
            val builder = KeyGenParameterSpec.Builder(alias(scope), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(builder.build())
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, gen.generateKey()) }
            prompt(activity, cipher, "Activar biometría para esta bóveda", { authorized ->
                authorized.updateAAD(scope.toByteArray(Charsets.UTF_8))
                val encrypted = authorized.doFinal(verifiedPassword.toByteArray(Charsets.UTF_8))
                val id = token(scope)
                prefs(activity).edit()
                    .putString("${id}_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString("${id}_iv", Base64.encodeToString(authorized.iv, Base64.NO_WRAP)).apply()
                onSuccess()
            }, onError)
        }.onFailure { onError("No se pudo activar la biometría. Comprueba que Android tenga biometría fuerte registrada y bloqueo de pantalla.") }
    }
    fun unlock(activity: FragmentActivity, scope: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        runCatching {
            val id = token(scope)
            val p = prefs(activity)
            val iv = p.getString("${id}_iv", null) ?: error("Sin registro biométrico")
            val payload = p.getString("${id}_data", null) ?: error("Sin registro biométrico")
            val key = keys().getKey(alias(scope), null) as? SecretKey ?: error("Llave no disponible")
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            prompt(activity, cipher, "Desbloquear con biometría", { authorized ->
                authorized.updateAAD(scope.toByteArray(Charsets.UTF_8))
                onSuccess(authorized.doFinal(Base64.decode(payload, Base64.NO_WRAP)).toString(Charsets.UTF_8))
            }, onError)
        }.onFailure { onError("La biometría no está disponible para esta bóveda. Usa su contraseña y vuelve a activarla.") }
    }
}
