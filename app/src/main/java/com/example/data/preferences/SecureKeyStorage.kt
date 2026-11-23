package com.example.data.preferences

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiKeyStatus
import com.example.domain.model.ApiProvider
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.secureKeysDataStore by preferencesDataStore(name = "bsub_secure_keys")

class SecureKeyStorage(private val context: Context) {

  private val encryptedKeysPref = stringPreferencesKey("encrypted_api_keys_payload")
  private val activeKeyIndexPref = intPreferencesKey("active_rotation_key_index")
  private val activeGrokIndexPref = intPreferencesKey("active_rotation_key_index_grok")
  private val activeDeepSeekIndexPref = intPreferencesKey("active_rotation_key_index_deepseek")

  private fun activeIndexPrefFor(provider: ApiProvider) =
    when (provider) {
      ApiProvider.GEMINI -> activeKeyIndexPref
      ApiProvider.GROK -> activeGrokIndexPref
      ApiProvider.DEEPSEEK -> activeDeepSeekIndexPref
    }

  val apiKeysFlow: Flow<List<ApiKeyEntry>> =
    context.secureKeysDataStore.data.map { prefs ->
      val payload = prefs[encryptedKeysPref] ?: return@map emptyList()
      decodeEncryptedPayload(payload)
    }

  val activeKeyIndexFlow: Flow<Int> =
    context.secureKeysDataStore.data.map { prefs ->
      (prefs[activeKeyIndexPref] ?: 0).coerceAtLeast(0)
    }

  suspend fun getKeysSnapshot(): List<ApiKeyEntry> {
    return apiKeysFlow.first()
  }

  suspend fun getKeysSnapshot(provider: ApiProvider): List<ApiKeyEntry> {
    return apiKeysFlow.first().filter { it.provider == provider }
  }

  suspend fun getActiveKeyIndex(provider: ApiProvider = ApiProvider.GEMINI): Int {
    val pref = activeIndexPrefFor(provider)
    return context.secureKeysDataStore.data.map { prefs ->
      (prefs[pref] ?: 0).coerceAtLeast(0)
    }.first()
  }

  suspend fun setActiveKeyIndex(index: Int, provider: ApiProvider = ApiProvider.GEMINI) {
    val pref = activeIndexPrefFor(provider)
    context.secureKeysDataStore.edit { prefs ->
      prefs[pref] = index.coerceAtLeast(0)
    }
  }

  suspend fun addBulkKeys(
    rawInput: String,
    provider: ApiProvider = ApiProvider.GEMINI,
  ): Int {
    val parsedKeys = parseBulkKeys(rawInput)
    if (parsedKeys.isEmpty()) return 0

    var addedCount = 0
    updateKeys { existing ->
      val existingValuesForProvider =
        existing.filter { it.provider == provider }.map { it.key }.toHashSet()
      val mutable = existing.toMutableList()
      var providerCount = existing.count { it.provider == provider }
      for (keyVal in parsedKeys) {
        if (existingValuesForProvider.add(keyVal)) {
          providerCount++
          mutable.add(
            ApiKeyEntry(
              id = UUID.randomUUID().toString(),
              key = keyVal,
              label = "Key #$providerCount",
              status = ApiKeyStatus.ACTIVE,
              provider = provider,
            )
          )
          addedCount++
        }
      }
      mutable
    }
    return addedCount
  }

  suspend fun addSingleKey(
    keyValue: String,
    label: String,
    provider: ApiProvider = ApiProvider.GEMINI,
  ) {
    val trimmed = keyValue.trim()
    if (trimmed.isBlank()) return
    updateKeys { existing ->
      if (existing.any { it.provider == provider && it.key == trimmed }) {
        existing
      } else {
        val countForProvider = existing.count { it.provider == provider }
        val resolvedLabel = label.trim().ifBlank { "Key #${countForProvider + 1}" }
        existing +
          ApiKeyEntry(
            id = UUID.randomUUID().toString(),
            key = trimmed,
            label = resolvedLabel,
            status = ApiKeyStatus.ACTIVE,
            provider = provider,
          )
      }
    }
  }

  suspend fun editKey(id: String, newKeyValue: String, newLabel: String) {
    val trimmedKey = newKeyValue.trim()
    if (trimmedKey.isBlank()) return
    updateKeys { existing ->
      existing.map { entry ->
        if (entry.id == id) {
          entry.copy(
            key = trimmedKey,
            label = newLabel.trim().ifBlank { entry.label },
            status = ApiKeyStatus.ACTIVE,
            lastError = null,
          )
        } else {
          entry
        }
      }
    }
  }

  suspend fun removeKey(id: String) {
    updateKeys { existing -> existing.filterNot { it.id == id } }
  }

  suspend fun clearAllKeys(provider: ApiProvider? = null) {
    if (provider == null) {
      context.secureKeysDataStore.edit { prefs ->
        prefs[encryptedKeysPref] = encodeEncryptedPayload(emptyList())
        prefs[activeKeyIndexPref] = 0
        prefs[activeGrokIndexPref] = 0
        prefs[activeDeepSeekIndexPref] = 0
      }
    } else {
      updateKeys { existing -> existing.filterNot { it.provider == provider } }
      setActiveKeyIndex(0, provider)
    }
  }

  suspend fun updateKeyStatus(id: String, status: ApiKeyStatus, lastError: String? = null) {
    updateKeys { existing ->
      existing.map { entry ->
        if (entry.id == id) entry.copy(status = status, lastError = lastError) else entry
      }
    }
  }

  suspend fun resetExhaustedAndCooldownKeys(provider: ApiProvider? = null) {
    updateKeys { existing ->
      existing.map { entry ->
        if (
          (provider == null || entry.provider == provider) &&
            (entry.status == ApiKeyStatus.QUOTA_EXHAUSTED || entry.status == ApiKeyStatus.COOLDOWN)
        ) {
          entry.copy(status = ApiKeyStatus.ACTIVE, lastError = null)
        } else {
          entry
        }
      }
    }
  }

  suspend fun resetAllKeyStatuses(provider: ApiProvider? = null) {
    updateKeys { existing ->
      existing.map { entry ->
        if (provider == null || entry.provider == provider) {
          entry.copy(status = ApiKeyStatus.ACTIVE, lastError = null)
        } else {
          entry
        }
      }
    }
  }

  /**
   * Clears bogus "No Gemini API keys configured" / "All configured Gemini API keys" errors
   * previously persisted onto Grok/DeepSeek keys by older builds where the Gemini interceptor
   * was shared across providers.
   */
  suspend fun sanitizeFalseGeminiErrorsOnNonGeminiKeys() {
    context.secureKeysDataStore.edit { prefs ->
      val encoded = prefs[encryptedKeysPref] ?: return@edit
      val current = decodeEncryptedPayload(encoded, sanitizeLegacyErrors = false)
      val sanitized = current.map(::healIfFalseGeminiError)
      if (sanitized != current) {
        prefs[encryptedKeysPref] = encodeEncryptedPayload(sanitized)
      }
    }
  }

  private fun healIfFalseGeminiError(entry: ApiKeyEntry): ApiKeyEntry {
    return if (isFalseGeminiErrorOnOtherProvider(entry.provider, entry.status, entry.lastError)) {
      entry.copy(status = ApiKeyStatus.ACTIVE, lastError = null)
    } else {
      entry
    }
  }

  private fun isFalseGeminiErrorOnOtherProvider(
    provider: ApiProvider,
    status: ApiKeyStatus,
    lastError: String?,
  ): Boolean {
    if (provider == ApiProvider.GEMINI || status != ApiKeyStatus.INVALID || lastError.isNullOrBlank()) {
      return false
    }
    return lastError.contains("No Gemini API keys configured", ignoreCase = true) ||
      lastError.contains("All configured Gemini API keys", ignoreCase = true)
  }

  private suspend fun updateKeys(transform: (List<ApiKeyEntry>) -> List<ApiKeyEntry>) {
    context.secureKeysDataStore.edit { prefs ->
      val current =
        prefs[encryptedKeysPref]?.let { decodeEncryptedPayload(it) } ?: emptyList()
      val updated = transform(current)
      prefs[encryptedKeysPref] = encodeEncryptedPayload(updated)
      for (provider in ApiProvider.entries) {
        val pref = activeIndexPrefFor(provider)
        val providerCount = updated.count { it.provider == provider }
        val currentIdx = prefs[pref] ?: 0
        if (providerCount == 0 || currentIdx >= providerCount || currentIdx < 0) {
          prefs[pref] = 0
        }
      }
    }
  }

  private fun encodeEncryptedPayload(keys: List<ApiKeyEntry>): String {
    val jsonArray = JSONArray()
    keys.forEach { entry ->
      val obj =
        JSONObject().apply {
          put("id", entry.id)
          put("key", entry.key)
          put("label", entry.label)
          put("status", entry.status.name)
          put("lastError", entry.lastError ?: "")
          put("provider", entry.provider.name)
        }
      jsonArray.put(obj)
    }
    val plaintextBytes = jsonArray.toString().toByteArray(Charsets.UTF_8)
    return encryptBytes(plaintextBytes)
  }

  private fun decodeEncryptedPayload(
    encoded: String,
    sanitizeLegacyErrors: Boolean = true,
  ): List<ApiKeyEntry> {
    return try {
      val decryptedBytes = decryptBytes(encoded) ?: return emptyList()
      val jsonArray = JSONArray(String(decryptedBytes, Charsets.UTF_8))
      val result = ArrayList<ApiKeyEntry>(jsonArray.length())
      for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        val statusName = obj.optString("status", ApiKeyStatus.ACTIVE.name)
        val rawStatus =
          runCatching { ApiKeyStatus.valueOf(statusName) }.getOrDefault(ApiKeyStatus.ACTIVE)
        val providerName = obj.optString("provider", ApiProvider.GEMINI.name)
        val provider =
          runCatching { ApiProvider.valueOf(providerName) }.getOrDefault(ApiProvider.GEMINI)
        val rawLastErr = obj.optString("lastError", "").ifBlank { null }
        val entry =
          ApiKeyEntry(
            id = obj.optString("id", UUID.randomUUID().toString()),
            key = obj.getString("key"),
            label = obj.optString("label", "Key #${i + 1}"),
            status = rawStatus,
            lastError = rawLastErr,
            provider = provider,
          )
        result.add(if (sanitizeLegacyErrors) healIfFalseGeminiError(entry) else entry)
      }
      result
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun encryptBytes(plain: ByteArray): String {
    return try {
      val secretKey = getOrCreateKeystoreSecretKey()
      val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, secretKey)
      val iv = cipher.iv
      val ciphertext = cipher.doFinal(plain)
      val combined = ByteArray(1 + iv.size + ciphertext.size)
      combined[0] = iv.size.toByte()
      System.arraycopy(iv, 0, combined, 1, iv.size)
      System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
      "KS1:" + Base64.encodeToString(combined, Base64.NO_WRAP)
    } catch (_: Exception) {
      // Fallback only if AndroidKeyStore is unavailable (e.g. JVM unit test host)
      "PLAIN:" + Base64.encodeToString(plain, Base64.NO_WRAP)
    }
  }

  private fun decryptBytes(encoded: String): ByteArray? {
    return try {
      when {
        encoded.startsWith("KS1:") -> {
          val raw = Base64.decode(encoded.removePrefix("KS1:"), Base64.NO_WRAP)
          if (raw.isEmpty()) return null
          val ivLen = raw[0].toInt() and 0xFF
          if (raw.size <= 1 + ivLen) return null
          val iv = raw.copyOfRange(1, 1 + ivLen)
          val ciphertext = raw.copyOfRange(1 + ivLen, raw.size)
          val secretKey = getOrCreateKeystoreSecretKey()
          val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
          cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
          cipher.doFinal(ciphertext)
        }
        encoded.startsWith("PLAIN:") -> {
          Base64.decode(encoded.removePrefix("PLAIN:"), Base64.NO_WRAP)
        }
        else -> null
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun getOrCreateKeystoreSecretKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
    if (existing != null) return existing.secretKey

    val keyGenerator =
      KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val spec =
      KeyGenParameterSpec.Builder(
          KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    keyGenerator.init(spec)
    return keyGenerator.generateKey()
  }

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "bsub_gemini_api_keys_aes_gcm"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

    fun parseBulkKeys(rawInput: String): List<String> {
      return rawInput
        .split(Regex("""[\s,;=]+"""))
        .map { it.trim().trim('"', '\'', '`', '[', ']', '{', '}') }
        .filter { it.length >= 16 }
        .distinct()
    }

    fun maskKey(key: String): String {
      if (key.length <= 10) return "••••••••"
      return "${key.take(6)}••••••${key.takeLast(4)}"
    }
  }
}
