package com.jarvis.assistant.agentic.router

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap

/**
 * ProviderVault: Secure, zero-hardcode credential manager for LLM API keys.
 * Encrypts all keys using Android Keystore + AES256-GCM via EncryptedSharedPreferences.
 */
class ProviderVault private constructor(context: Context) {

    private val memoryFallback = ConcurrentHashMap<String, String>()
    private var securePrefs: SharedPreferences? = null

    init {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            securePrefs = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // In unit tests or devices without Keystore support, fallback to safe in-memory vault
            securePrefs = null
        }
    }

    /**
     * Retrieves the API key for a given provider.
     */
    fun getApiKey(provider: ModelProvider): String? {
        if (provider == ModelProvider.OLLAMA_LOCAL) {
            return "ollama-local-no-key"
        }
        val keyName = keyForProvider(provider)
        val stored = securePrefs?.getString(keyName, null) ?: memoryFallback[keyName]
        if (!stored.isNullOrBlank()) {
            return stored
        }
        // Check environment variable as fallback (e.g., GEMINI_API_KEY, GROQ_API_KEY)
        val envName = "${provider.name}_API_KEY"
        return System.getenv(envName)
    }

    /**
     * Stores an API key securely for a provider.
     */
    fun setApiKey(provider: ModelProvider, key: String) {
        val keyName = keyForProvider(provider)
        val trimmed = key.trim()
        memoryFallback[keyName] = trimmed
        securePrefs?.edit()?.putString(keyName, trimmed)?.apply()
    }

    /**
     * Checks if a valid key is configured for this provider.
     */
    fun hasApiKey(provider: ModelProvider): Boolean {
        if (provider == ModelProvider.OLLAMA_LOCAL) return true
        val key = getApiKey(provider)
        return !key.isNullOrBlank()
    }

    /**
     * Removes an API key for a provider.
     */
    fun removeApiKey(provider: ModelProvider) {
        val keyName = keyForProvider(provider)
        memoryFallback.remove(keyName)
        securePrefs?.edit()?.remove(keyName)?.apply()
    }

    private fun keyForProvider(provider: ModelProvider): String {
        return "api_key_${provider.id}"
    }

    companion object {
        private const val PREFS_FILE_NAME = "jarvis_secure_vault_prefs"

        @Volatile
        private var INSTANCE: ProviderVault? = null

        fun getInstance(context: Context): ProviderVault {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProviderVault(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
