package com.jarvis.assistant.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.agentic.router.ModelProvider
import com.jarvis.assistant.agentic.router.ProviderVault
import com.jarvis.assistant.agentic.router.SmartProviderRouter
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.ActivityApiConnectionBinding
import kotlinx.coroutines.launch

class ApiConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApiConnectionBinding
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val vault by lazy { ProviderVault.getInstance(this) }
    private val router by lazy { SmartProviderRouter.getInstance(this) }

    // Dynamic input fields for the expanded free/developer providers
    private val extraProviderInputs = mutableMapOf<ModelProvider, EditText>()
    private val extraProvidersList = listOf(
        ModelProvider.GLM_ZHIPU,
        ModelProvider.SILICONFLOW,
        ModelProvider.NVIDIA_NIM,
        ModelProvider.GITHUB_MODELS,
        ModelProvider.HUGGINGFACE,
        ModelProvider.COHERE,
        ModelProvider.POLLINATIONS_FREE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityApiConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        setupUI()
        buildExtraProviderCards()
        loadCurrentPreferences()
        setupListeners()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    /**
     * Builds sleek glassmorphic cards for GLM (Zhipu Free), SiliconFlow (Free),
     * NVIDIA NIM (Free Dev Credits), GitHub Models (Free), HuggingFace (Free),
     * Cohere (Free Trial), and Pollinations (100% Free Zero-Key).
     */
    private fun buildExtraProviderCards() {
        val container = binding.layoutExtraProviders
        container.removeAllViews()
        extraProviderInputs.clear()

        val dp = resources.displayMetrics.density
        fun Int.toPx(): Int = (this * dp).toInt()

        extraProvidersList.forEach { provider ->
            // Section Header with explicit FREE / PAID badge
            val sectionHeader = TextView(this).apply {
                text = "${provider.displayName} • ${provider.pricingBadge}"
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, 6.toPx())
            }
            container.addView(sectionHeader)

            // Card Layout
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@ApiConnectionActivity, R.drawable.bg_jarvis_card)
                setPadding(18.toPx(), 16.toPx(), 18.toPx(), 16.toPx())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 18.toPx()
                layoutParams = lp
            }

            // Title + Model info
            val tvTitle = TextView(this).apply {
                text = "${provider.displayName} (Model: ${provider.defaultModel})"
                setTextColor(Color.WHITE)
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
            }
            card.addView(tvTitle)

            // Free/Paid Tier Details
            val tvTierDetail = TextView(this).apply {
                text = provider.pricingDetails
                setTextColor(
                    when {
                        provider.pricingBadge.contains("100% FREE") || provider.pricingBadge.contains("FREE") -> Color.parseColor("#4ADE80")
                        provider.pricingBadge.contains("PAID") -> Color.parseColor("#FBBF24")
                        else -> Color.parseColor("#94A3B8")
                    }
                )
                textSize = 11.5f
                setPadding(0, 4.toPx(), 0, 8.toPx())
            }
            card.addView(tvTierDetail)

            // Input EditText (if API key is required)
            val etKey = EditText(this).apply {
                hint = provider.keyHint
                setHintTextColor(Color.parseColor("#64748B"))
                setTextColor(Color.WHITE)
                textSize = 13.5f
                setSingleLine(true)
                background = ContextCompat.getDrawable(this@ApiConnectionActivity, R.drawable.bg_jarvis_icon_square)
                setPadding(12.toPx(), 10.toPx(), 12.toPx(), 10.toPx())
                if (provider.requiresApiKey) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    isEnabled = false
                    setText("Built-in Zero-Key Cloud Engine (Active Automatically)")
                    setTextColor(Color.parseColor("#22C55E"))
                }
            }
            if (provider.requiresApiKey) {
                extraProviderInputs[provider] = etKey
            }
            card.addView(etKey)

            // Status TextView
            val tvStatus = TextView(this).apply {
                text = if (!provider.requiresApiKey) {
                    "Status: ✅ Active 24/7 (Zero API Key Needed — 100% Free)"
                } else {
                    "Status: Not tested (${provider.pricingBadge})"
                }
                setTextColor(if (!provider.requiresApiKey) Color.parseColor("#22C55E") else Color.parseColor("#94A3B8"))
                textSize = 11.5f
                setPadding(0, 8.toPx(), 0, 0)
            }

            // Action Buttons Row (Paste / Clear / Test Ping)
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = 10.toPx()
                layoutParams = lp
            }

            if (provider.requiresApiKey) {
                val btnPaste = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Paste"
                    isAllCaps = false
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#38BDF8"))
                    strokeColor = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
                    strokeWidth = 1.toPx()
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 38.toPx())
                    lp.marginEnd = 8.toPx()
                    layoutParams = lp
                    setOnClickListener {
                        pasteToField(etKey, provider.displayName)
                        val k = etKey.text?.toString()?.trim().orEmpty()
                        if (k.isNotBlank()) {
                            tvStatus.text = "Status: 🔑 Key detected (${ inspectKeyTierHint(k, provider) })"
                            tvStatus.setTextColor(Color.parseColor("#38BDF8"))
                        }
                    }
                }
                btnRow.addView(btnPaste)

                val btnClear = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Clear"
                    isAllCaps = false
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#94A3B8"))
                    strokeColor = ColorStateList.valueOf(Color.parseColor("#64748B"))
                    strokeWidth = 1.toPx()
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 38.toPx())
                    lp.marginEnd = 8.toPx()
                    layoutParams = lp
                    setOnClickListener {
                        etKey.setText("")
                        vault.removeApiKey(provider)
                        tvStatus.text = "Status: Cleared"
                        updateOverallStatus()
                    }
                }
                btnRow.addView(btnClear)
            }

            val btnTest = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Test Ping"
                isAllCaps = false
                textSize = 11.5f
                setTextColor(Color.parseColor("#22C55E"))
                strokeColor = ColorStateList.valueOf(Color.parseColor("#22C55E"))
                strokeWidth = 1.toPx()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 38.toPx())
            }
            btnTest.setOnClickListener {
                if (provider.requiresApiKey) {
                    val key = etKey.text?.toString()?.trim().orEmpty()
                    if (key.isBlank()) {
                        tvStatus.text = "Status: ❌ Please enter ${provider.displayName} key"
                        tvStatus.setTextColor(Color.parseColor("#EF4444"))
                        return@setOnClickListener
                    }
                    vault.setApiKey(provider, key)
                }
                testProvider(provider, btnTest, tvStatus)
            }
            btnRow.addView(btnTest)

            card.addView(btnRow)
            card.addView(tvStatus)
            container.addView(card)
        }
    }

    /**
     * Inspects an API key string and tells the user its detected provider format & whether it is FREE or PAID.
     */
    private fun inspectKeyTierHint(key: String, provider: ModelProvider): String {
        val k = key.trim()
        return when {
            k.startsWith("AIzaSy") -> "Google AI Studio Key • 🟢 FREE TIER (15 RPM) / Paid Billing"
            k.startsWith("gsk_") -> "Groq LPU Key • 🟢 100% FREE TIER (14,400 Req/Day)"
            k.startsWith("nvapi-") -> "NVIDIA NIM Key • 🟢 FREE DEV CREDITS (Llama 3.3 70B)"
            k.startsWith("sk-or-") -> "OpenRouter Key • 🟡 FREE on ':free' models / Paid on Pro"
            k.startsWith("csk-") -> "Cerebras Key • 🟢 FREE TIER (1M Tokens/Day)"
            k.startsWith("ghp_") || k.startsWith("github_pat_") -> "GitHub PAT • 🟢 FREE GitHub Models (GPT-4o-mini)"
            k.startsWith("hf_") -> "HuggingFace Token • 🟢 FREE Serverless Inference"
            k.contains(".") && provider == ModelProvider.GLM_ZHIPU -> "Zhipu GLM Key • 🟢 100% FREE on glm-4-flash"
            provider == ModelProvider.SILICONFLOW && k.startsWith("sk-") -> "SiliconFlow Key • 🟢 FREE on Qwen2.5-7B & GLM-4-9B"
            provider == ModelProvider.DEEPSEEK && k.startsWith("sk-") -> "DeepSeek Key • 🔴 PAID API (Requires Balance)"
            else -> "${provider.pricingBadge} • Click 'Test Ping' to verify live quota"
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
        binding.etGroqKey.setText(vault.getApiKey(ModelProvider.GROQ).orEmpty())

        // 3. OpenRouter
        binding.etOpenRouterKey.setText(vault.getApiKey(ModelProvider.OPENROUTER).orEmpty())

        // 4. DeepSeek
        binding.etDeepSeekKey.setText(vault.getApiKey(ModelProvider.DEEPSEEK).orEmpty())

        // 5. Cerebras
        binding.etCerebrasKey.setText(vault.getApiKey(ModelProvider.CEREBRAS).orEmpty())

        // 6. Mistral
        binding.etMistralKey.setText(vault.getApiKey(ModelProvider.MISTRAL).orEmpty())

        // 7. SambaNova
        binding.etSambaNovaKey.setText(vault.getApiKey(ModelProvider.SAMBANOVA).orEmpty())

        // 8. Ollama
        binding.etOllamaUrl.setText(vault.getBaseUrl(ModelProvider.OLLAMA_LOCAL))

        // 9-15. Extra Free Providers (GLM, SiliconFlow, NVIDIA NIM, GitHub, HF, Cohere)
        extraProviderInputs.forEach { (provider, editText) ->
            editText.setText(vault.getApiKey(provider).orEmpty())
        }

        updateOverallStatus()
    }

    private fun updateOverallStatus() {
        val totalKeyProviders = ModelProvider.entries.count { it.requiresApiKey }
        val configuredCount = ModelProvider.entries.count { p ->
            p.requiresApiKey && vault.hasApiKey(p)
        } + if (preferences.apiKey.isNotBlank() && !vault.hasApiKey(ModelProvider.GEMINI)) 1 else 0

        if (configuredCount > 0) {
            binding.tvLiveStatus.text = "VAULT ACTIVE • $configuredCount/$totalKeyProviders KEYS + ZERO-KEY FREE CLOUD READY"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
        } else {
            binding.tvLiveStatus.text = "ZERO-KEY FREE AI ACTIVE • ADD KEYS FOR 15-PROVIDER BOOST"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
        }
    }

    private fun setupListeners() {
        // --- Gemini ---
        binding.btnPasteKey.setOnClickListener {
            pasteToField(binding.etApiKey, "Gemini Key")
            val k = binding.etApiKey.text?.toString()?.trim().orEmpty()
            if (k.isNotBlank()) binding.tvGeminiStatus.text = "Status: 🔑 ${inspectKeyTierHint(k, ModelProvider.GEMINI)}"
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
            val k = binding.etGroqKey.text?.toString()?.trim().orEmpty()
            if (k.isNotBlank()) binding.tvGroqStatus.text = "Status: 🔑 ${inspectKeyTierHint(k, ModelProvider.GROQ)}"
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
            val k = binding.etOpenRouterKey.text?.toString()?.trim().orEmpty()
            if (k.isNotBlank()) binding.tvOpenRouterStatus.text = "Status: 🔑 ${inspectKeyTierHint(k, ModelProvider.OPENROUTER)}"
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
            val k = binding.etDeepSeekKey.text?.toString()?.trim().orEmpty()
            if (k.isNotBlank()) binding.tvDeepSeekStatus.text = "Status: 🔑 ${inspectKeyTierHint(k, ModelProvider.DEEPSEEK)}"
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

        // --- Cerebras ---
        binding.btnPasteCerebras.setOnClickListener {
            pasteToField(binding.etCerebrasKey, "Cerebras Key")
            val k = binding.etCerebrasKey.text?.toString()?.trim().orEmpty()
            if (k.isNotBlank()) binding.tvCerebrasStatus.text = "Status: 🔑 ${inspectKeyTierHint(k, ModelProvider.CEREBRAS)}"
        }
        binding.btnClearCerebras.setOnClickListener {
            binding.etCerebrasKey.setText("")
        }
        binding.btnTestCerebras.setOnClickListener {
            val key = binding.etCerebrasKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                binding.tvCerebrasStatus.text = "Status: ❌ Please enter Cerebras key"
                return@setOnClickListener
            }
            vault.setApiKey(ModelProvider.CEREBRAS, key)
            testProvider(ModelProvider.CEREBRAS, binding.btnTestCerebras, binding.tvCerebrasStatus)
        }

        // --- Mistral ---
        binding.btnPasteMistral.setOnClickListener {
            pasteToField(binding.etMistralKey, "Mistral Key")
            val k = binding.etMistralKey.text?.toString()?.trim().orEmpty()
            if (k.isNotBlank()) binding.tvMistralStatus.text = "Status: 🔑 ${inspectKeyTierHint(k, ModelProvider.MISTRAL)}"
        }
        binding.btnClearMistral.setOnClickListener {
            binding.etMistralKey.setText("")
        }
        binding.btnTestMistral.setOnClickListener {
            val key = binding.etMistralKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                binding.tvMistralStatus.text = "Status: ❌ Please enter Mistral key"
                return@setOnClickListener
            }
            vault.setApiKey(ModelProvider.MISTRAL, key)
            testProvider(ModelProvider.MISTRAL, binding.btnTestMistral, binding.tvMistralStatus)
        }

        // --- SambaNova ---
        binding.btnPasteSambaNova.setOnClickListener {
            pasteToField(binding.etSambaNovaKey, "SambaNova Key")
            val k = binding.etSambaNovaKey.text?.toString()?.trim().orEmpty()
            if (k.isNotBlank()) binding.tvSambaNovaStatus.text = "Status: 🔑 ${inspectKeyTierHint(k, ModelProvider.SAMBANOVA)}"
        }
        binding.btnClearSambaNova.setOnClickListener {
            binding.etSambaNovaKey.setText("")
        }
        binding.btnTestSambaNova.setOnClickListener {
            val key = binding.etSambaNovaKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                binding.tvSambaNovaStatus.text = "Status: ❌ Please enter SambaNova key"
                return@setOnClickListener
            }
            vault.setApiKey(ModelProvider.SAMBANOVA, key)
            testProvider(ModelProvider.SAMBANOVA, binding.btnTestSambaNova, binding.tvSambaNovaStatus)
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

    private fun pasteToField(editText: EditText, label: String) {
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
        button: MaterialButton,
        statusText: TextView
    ) {
        button.isEnabled = false
        statusText.text = "Status: Testing ${provider.displayName} (${provider.pricingBadge})..."
        statusText.setTextColor(Color.parseColor("#3B82F6"))

        lifecycleScope.launch {
            val (success, detail) = router.testProvider(provider)
            button.isEnabled = true
            if (success) {
                statusText.text = "Status: ✅ Online ($detail) • ${provider.pricingBadge}"
                statusText.setTextColor(Color.parseColor("#22C55E"))
                Snackbar.make(binding.root, "${provider.displayName}: Verified! ($detail • ${provider.pricingBadge})", Snackbar.LENGTH_LONG).show()
            } else {
                val hint = if (detail.contains("402") || detail.contains("Insufficient", true)) {
                    "Paid balance required ($detail)"
                } else {
                    detail
                }
                statusText.text = "Status: ❌ $hint"
                statusText.setTextColor(Color.parseColor("#EF4444"))
                Snackbar.make(binding.root, "${provider.displayName} test failed: $hint", Snackbar.LENGTH_LONG).show()
            }
            updateOverallStatus()
        }
    }

    private fun saveAndApply() {
        val geminiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        val groqKey = binding.etGroqKey.text?.toString()?.trim() ?: ""
        val openRouterKey = binding.etOpenRouterKey.text?.toString()?.trim() ?: ""
        val deepSeekKey = binding.etDeepSeekKey.text?.toString()?.trim() ?: ""
        val cerebrasKey = binding.etCerebrasKey.text?.toString()?.trim() ?: ""
        val mistralKey = binding.etMistralKey.text?.toString()?.trim() ?: ""
        val sambaNovaKey = binding.etSambaNovaKey.text?.toString()?.trim() ?: ""
        val ollamaUrl = binding.etOllamaUrl.text?.toString()?.trim() ?: ""
        val customEp = binding.etCustomEndpoint.text?.toString()?.trim() ?: ""

        val selectedModel = when (binding.rgModelGroup.checkedRadioButtonId) {
            binding.rbModelFlash20.id -> GeminiConstants.MODEL_3_8_LIVE
            binding.rbModelFlash15.id -> GeminiConstants.MODEL_3_1_FLASH_AGENT
            else -> GeminiConstants.MODEL_3_1_FLASH
        }

        // Save primary 8 providers
        if (geminiKey.isNotBlank()) vault.setApiKey(ModelProvider.GEMINI, geminiKey) else vault.removeApiKey(ModelProvider.GEMINI)
        if (groqKey.isNotBlank()) vault.setApiKey(ModelProvider.GROQ, groqKey) else vault.removeApiKey(ModelProvider.GROQ)
        if (openRouterKey.isNotBlank()) vault.setApiKey(ModelProvider.OPENROUTER, openRouterKey) else vault.removeApiKey(ModelProvider.OPENROUTER)
        if (deepSeekKey.isNotBlank()) vault.setApiKey(ModelProvider.DEEPSEEK, deepSeekKey) else vault.removeApiKey(ModelProvider.DEEPSEEK)
        if (cerebrasKey.isNotBlank()) vault.setApiKey(ModelProvider.CEREBRAS, cerebrasKey) else vault.removeApiKey(ModelProvider.CEREBRAS)
        if (mistralKey.isNotBlank()) vault.setApiKey(ModelProvider.MISTRAL, mistralKey) else vault.removeApiKey(ModelProvider.MISTRAL)
        if (sambaNovaKey.isNotBlank()) vault.setApiKey(ModelProvider.SAMBANOVA, sambaNovaKey) else vault.removeApiKey(ModelProvider.SAMBANOVA)
        if (ollamaUrl.isNotBlank()) vault.setBaseUrl(ModelProvider.OLLAMA_LOCAL, ollamaUrl)

        // Save all extra free providers (GLM Zhipu, SiliconFlow, NVIDIA NIM, GitHub Models, HuggingFace, Cohere)
        extraProviderInputs.forEach { (provider, editText) ->
            val keyVal = editText.text?.toString()?.trim().orEmpty()
            if (keyVal.isNotBlank()) {
                vault.setApiKey(provider, keyVal)
            } else {
                vault.removeApiKey(provider)
            }
        }

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

        Toast.makeText(this, "All 15 AI Provider configurations saved securely!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
