package com.jarvis.assistant.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.agentic.router.ModelProvider
import com.jarvis.assistant.agentic.router.ProviderVault
import com.jarvis.assistant.agentic.router.SmartProviderRouter
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.ActivityApiConnectionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApiConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApiConnectionBinding
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val vault by lazy { ProviderVault.getInstance(this) }
    private val router by lazy { SmartProviderRouter.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityApiConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        setupUI()
        loadCurrentPreferences()
        setupListeners()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadCurrentPreferences() {
        // 1. Google Gemini
        val geminiKey = vault.getApiKey(ModelProvider.GEMINI) ?: preferences.apiKey
        binding.etApiKey.setText(geminiKey)
        binding.etCustomEndpoint.setText(preferences.customEndpoint)

        when (preferences.aiModel) {
            GeminiConstants.MODEL_3_8_LIVE -> binding.rbModelFlash20.isChecked = true
            GeminiConstants.MODEL_3_1_FLASH_AGENT -> binding.rbModelFlash15.isChecked = true
            else -> binding.rbModelFlashThinking.isChecked = true
        }

        // 2. Groq
        val groqKey = vault.getApiKey(ModelProvider.GROQ).orEmpty()
        binding.etGroqKey.setText(groqKey)

        // 3. OpenRouter
        val openRouterKey = vault.getApiKey(ModelProvider.OPENROUTER).orEmpty()
        binding.etOpenRouterKey.setText(openRouterKey)

        // 4. DeepSeek
        val deepSeekKey = vault.getApiKey(ModelProvider.DEEPSEEK).orEmpty()
        binding.etDeepSeekKey.setText(deepSeekKey)

        // 5. Ollama
        val ollamaUrl = vault.getBaseUrl(ModelProvider.OLLAMA_LOCAL)
        binding.etOllamaUrl.setText(ollamaUrl)

        updateOverallStatus()
    }

    private fun updateOverallStatus() {
        val hasGemini = !vault.getApiKey(ModelProvider.GEMINI).isNullOrBlank() || preferences.apiKey.isNotBlank()
        val hasGroq = vault.hasApiKey(ModelProvider.GROQ)
        val hasOpenRouter = vault.hasApiKey(ModelProvider.OPENROUTER)

        if (hasGemini || hasGroq || hasOpenRouter) {
            binding.tvLiveStatus.text = "VAULT ACTIVE • READY"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
        } else {
            binding.tvLiveStatus.text = "NO KEYS CONFIGURED • PLEASE ENTER KEYS"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EAB308"))
        }
    }

    private fun setupListeners() {
        // --- Gemini ---
        binding.btnPasteKey.setOnClickListener {
            pasteToField(binding.etApiKey, "Gemini Key")
        }
        binding.btnClearKey.setOnClickListener {
            binding.etApiKey.setText("")
        }
        binding.btnTestConnection.setOnClickListener {
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                binding.tvGeminiStatus.text = "Status: ❌ Please enter Gemini key"
                return@setOnClickListener
            }
            vault.setApiKey(ModelProvider.GEMINI, key)
            testProvider(ModelProvider.GEMINI, binding.btnTestConnection, binding.tvGeminiStatus)
        }

        // --- Groq ---
        binding.btnPasteGroq.setOnClickListener {
            pasteToField(binding.etGroqKey, "Groq Key")
        }
        binding.btnClearGroq.setOnClickListener {
            binding.etGroqKey.setText("")
        }
        binding.btnTestGroq.setOnClickListener {
            val key = binding.etGroqKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                binding.tvGroqStatus.text = "Status: ❌ Please enter Groq key"
                return@setOnClickListener
            }
            vault.setApiKey(ModelProvider.GROQ, key)
            testProvider(ModelProvider.GROQ, binding.btnTestGroq, binding.tvGroqStatus)
        }

        // --- OpenRouter ---
        binding.btnPasteOpenRouter.setOnClickListener {
            pasteToField(binding.etOpenRouterKey, "OpenRouter Key")
        }
        binding.btnClearOpenRouter.setOnClickListener {
            binding.etOpenRouterKey.setText("")
        }
        binding.btnTestOpenRouter.setOnClickListener {
            val key = binding.etOpenRouterKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                binding.tvOpenRouterStatus.text = "Status: ❌ Please enter OpenRouter key"
                return@setOnClickListener
            }
            vault.setApiKey(ModelProvider.OPENROUTER, key)
            testProvider(ModelProvider.OPENROUTER, binding.btnTestOpenRouter, binding.tvOpenRouterStatus)
        }

        // --- DeepSeek ---
        binding.btnPasteDeepSeek.setOnClickListener {
            pasteToField(binding.etDeepSeekKey, "DeepSeek Key")
        }
        binding.btnClearDeepSeek.setOnClickListener {
            binding.etDeepSeekKey.setText("")
        }
        binding.btnTestDeepSeek.setOnClickListener {
            val key = binding.etDeepSeekKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                binding.tvDeepSeekStatus.text = "Status: ❌ Please enter DeepSeek key"
                return@setOnClickListener
            }
            vault.setApiKey(ModelProvider.DEEPSEEK, key)
            testProvider(ModelProvider.DEEPSEEK, binding.btnTestDeepSeek, binding.tvDeepSeekStatus)
        }

        // --- Ollama ---
        binding.btnTestOllama.setOnClickListener {
            val url = binding.etOllamaUrl.text?.toString()?.trim() ?: ""
            if (url.isNotBlank()) {
                vault.setBaseUrl(ModelProvider.OLLAMA_LOCAL, url)
            }
            testProvider(ModelProvider.OLLAMA_LOCAL, binding.btnTestOllama, binding.tvOllamaStatus)
        }

        // --- Save All ---
        binding.btnSaveConnection.setOnClickListener {
            saveAndApply()
        }
    }

    private fun pasteToField(editText: android.widget.EditText, label: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)
        val pasted = item?.text?.toString()?.trim() ?: ""
        if (pasted.isNotBlank()) {
            editText.setText(pasted)
            Toast.makeText(this, "$label pasted from clipboard", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testProvider(
        provider: ModelProvider,
        button: com.google.android.material.button.MaterialButton,
        statusText: android.widget.TextView
    ) {
        button.isEnabled = false
        statusText.text = "Status: Testing ${provider.displayName}..."
        statusText.setTextColor(Color.parseColor("#3B82F6"))

        lifecycleScope.launch {
            val (success, detail) = router.testProvider(provider)
            button.isEnabled = true
            if (success) {
                statusText.text = "Status: ✅ Online ($detail)"
                statusText.setTextColor(Color.parseColor("#22C55E"))
                Snackbar.make(binding.root, "${provider.displayName}: Verified! Latency: $detail", Snackbar.LENGTH_LONG).show()
            } else {
                statusText.text = "Status: ❌ $detail"
                statusText.setTextColor(Color.parseColor("#EF4444"))
                Snackbar.make(binding.root, "${provider.displayName} test failed: $detail", Snackbar.LENGTH_LONG).show()
            }
            updateOverallStatus()
        }
    }

    private fun saveAndApply() {
        val geminiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        val groqKey = binding.etGroqKey.text?.toString()?.trim() ?: ""
        val openRouterKey = binding.etOpenRouterKey.text?.toString()?.trim() ?: ""
        val deepSeekKey = binding.etDeepSeekKey.text?.toString()?.trim() ?: ""
        val ollamaUrl = binding.etOllamaUrl.text?.toString()?.trim() ?: ""
        val customEp = binding.etCustomEndpoint.text?.toString()?.trim() ?: ""

        val selectedModel = when (binding.rgModelGroup.checkedRadioButtonId) {
            binding.rbModelFlash20.id -> GeminiConstants.MODEL_3_8_LIVE
            binding.rbModelFlash15.id -> GeminiConstants.MODEL_3_1_FLASH_AGENT
            else -> GeminiConstants.MODEL_3_1_FLASH
        }

        // Save to Secure Vault
        if (geminiKey.isNotBlank()) vault.setApiKey(ModelProvider.GEMINI, geminiKey) else vault.removeApiKey(ModelProvider.GEMINI)
        if (groqKey.isNotBlank()) vault.setApiKey(ModelProvider.GROQ, groqKey) else vault.removeApiKey(ModelProvider.GROQ)
        if (openRouterKey.isNotBlank()) vault.setApiKey(ModelProvider.OPENROUTER, openRouterKey) else vault.removeApiKey(ModelProvider.OPENROUTER)
        if (deepSeekKey.isNotBlank()) vault.setApiKey(ModelProvider.DEEPSEEK, deepSeekKey) else vault.removeApiKey(ModelProvider.DEEPSEEK)
        if (ollamaUrl.isNotBlank()) vault.setBaseUrl(ModelProvider.OLLAMA_LOCAL, ollamaUrl)

        // Save legacy preferences for backward compatibility
        try {
            preferences.apiKey = geminiKey
        } catch (e: Exception) {
            // Secure storage fallback
        }
        preferences.aiModel = selectedModel
        preferences.customEndpoint = customEp

        com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
        com.jarvis.assistant.background.JarvisConversationService.restartSession()

        Toast.makeText(this, "All AI Provider settings saved securely!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
