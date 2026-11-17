package com.mixcoara.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mixcoara.app.ui.theme.MixcoaraThemeId
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureConfigStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("mixcoara_secure_config", Context.MODE_PRIVATE)

    fun saveTheme(themeId: MixcoaraThemeId) {
        preferences.edit().putString("theme_id", themeId.id).apply()
    }

    fun loadTheme(): MixcoaraThemeId =
        MixcoaraThemeId.fromId(preferences.getString("theme_id", MixcoaraThemeId.APP.id))

    fun saveBiometricPassphrase(passphrase: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
        preferences
            .edit()
            .putString("bio_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("bio_payload", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun loadBiometricPassphrase(): String? {
        val iv = preferences.getString("bio_iv", null) ?: return null
        val payload = preferences.getString("bio_payload", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            cipher
                .doFinal(Base64.decode(payload, Base64.NO_WRAP))
                .toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun clearBiometricPassphrase() {
        preferences.edit().remove("bio_iv").remove("bio_payload").apply()
    }

    fun hasBiometricPassphrase(): Boolean =
        preferences.contains("bio_iv") && preferences.contains("bio_payload")

    fun save(config: VaultConfig) {
        val plain =
            JSONObject()
                .put("url", config.url)
                .put("username", config.username)
                .put("password", config.password)
                .put("folder", config.folder)
                .toString()
                .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain)
        preferences
            .edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(): VaultConfig? {
        val iv = preferences.getString("iv", null) ?: return null
        val payload = preferences.getString("payload", null) ?: return null
        return runCatching {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
                )
                val json =
                    JSONObject(
                        cipher
                            .doFinal(Base64.decode(payload, Base64.NO_WRAP))
                            .toString(Charsets.UTF_8),
                    )
                VaultConfig(
                    url = json.optString("url"),
                    username = json.optString("username"),
                    password = json.optString("password"),
                    folder = json.optString("folder", "Mixcoara"),
                )
            }
            .getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "mixcoara_config_v2"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

