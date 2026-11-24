package com.zlatan.gamelens.security

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore AES/GCM encrypted storage for user API keys with legacy plaintext migration
 * and strict loopback/private LAN HTTP endpoint validation.
 */
object SecureApiKeyStorage {

    private const val TAG = "SecureApiKeyStorage"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "gamelens_api_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val PREFIX = "ENC:"

    // Fallback key for environments where AndroidKeyStore is not initialized (e.g. host unit tests)
    @Volatile
    private var isKeyStoreAvailable: Boolean = true

    private fun getSecretKey(): SecretKey? {
        if (!isKeyStoreAvailable) return null
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            } else {
                keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AndroidKeyStore unavailable: ${t.message}")
            isKeyStoreAvailable = false
            null
        }
    }

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val secretKey = getSecretKey() ?: return plaintext
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
            "$PREFIX$ivBase64:$cipherBase64"
        } catch (t: Throwable) {
            Log.e(TAG, "Encryption failed: ${t.message}", t)
            plaintext
        }
    }

    fun decrypt(encryptedStr: String): String {
        if (!encryptedStr.startsWith(PREFIX)) return encryptedStr
        val secretKey = getSecretKey() ?: return encryptedStr
        return try {
            val payload = encryptedStr.removePrefix(PREFIX)
            val parts = payload.split(":")
            if (parts.size != 2) return encryptedStr
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decryptedBytes = cipher.doFinal(cipherBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.e(TAG, "Decryption failed: ${t.message}", t)
            encryptedStr
        }
    }

    /**
     * Retrieves the API key from SharedPreferences with automatic transparent legacy migration.
     */
    fun getApiKey(prefs: SharedPreferences, key: String): String {
        val stored = prefs.getString(key, "") ?: ""
        if (stored.isBlank()) return ""

        if (stored.startsWith(PREFIX)) {
            return decrypt(stored)
        }

        // Legacy plaintext migration: encrypt and persist encrypted form
        val encrypted = encrypt(stored)
        if (encrypted.startsWith(PREFIX)) {
            prefs.edit().putString(key, encrypted).apply()
            Log.i(TAG, "Migrated legacy plaintext API key to AndroidKeyStore encrypted format")
        }
        return stored
    }

    /**
     * Encrypts and saves the API key to SharedPreferences.
     */
    fun saveApiKey(prefs: SharedPreferences, key: String, apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val encrypted = encrypt(trimmed)
        prefs.edit().putString(key, encrypted).apply()
    }

    /**
     * Validates that Custom AI / Local LLM Base URLs use HTTPS for public domains,
     * allowing HTTP ONLY for loopback and private LAN addresses (localhost, 127.0.0.1, 10.x, 192.168.x, 172.16-31.x).
     */
    fun isValidEndpointUrl(endpointUrl: String): Pair<Boolean, String?> {
        val trimmed = endpointUrl.trim()
        if (trimmed.isEmpty()) return Pair(false, "رابط خادم الذكاء الاصطناعي لا يمكن أن يكون فارغاً")

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return Pair(false, "صيغة الرابط غير صحيحة")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme == null || (scheme != "https" && scheme != "http")) {
            return Pair(false, "يجب أن يبدأ الرابط بـ https:// أو http://")
        }

        if (scheme == "https") {
            return Pair(true, null)
        }

        // Scheme is http: enforce strictly private / loopback hosts to protect API key
        val host = uri.host?.lowercase() ?: return Pair(false, "تعذر استخراج اسم المضيف من الرابط")

        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        val isPrivateIp = host.matches(Regex("^10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) ||
                host.matches(Regex("^192\\.168\\.\\d{1,3}\\.\\d{1,3}$")) ||
                host.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\.\\d{1,3}\\.\\d{1,3}$"))

        return if (isLoopback || isPrivateIp) {
            Pair(true, null)
        } else {
            Pair(
                false,
                "عناوين HTTP مسموحة فقط للخوادم المحلية (Localhost أو Private IP) لحماية بياناتك من التسريب"
            )
        }
    }
}
