package com.example.tvremote.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurePairingStore(context: Context) {
    private val preferences = context.getSharedPreferences("secure_pairing", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun getClientKey(host: String): String? = decrypt(preferences.getString("client_${id(host)}", null))
    fun saveClientKey(host: String, key: String) = save("client_${id(host)}", key)
    fun getCertificatePin(host: String): String? = decrypt(preferences.getString("cert_${id(host)}", null))
    fun saveCertificatePin(host: String, pin: String) = save("cert_${id(host)}", pin)

    fun forget(host: String) {
        preferences.edit().remove("client_${id(host)}").remove("cert_${id(host)}").apply()
    }

    private fun id(host: String) = host.lowercase().replace(Regex("[^a-z0-9]"), "_")

    private fun save(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        preferences.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    private fun decrypt(value: String?): String? = runCatching {
        if (value == null) return null
        val packed = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "tv_remote_pairing_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
