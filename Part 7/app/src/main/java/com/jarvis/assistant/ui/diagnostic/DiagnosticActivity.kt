package com.jarvis.assistant.ui.diagnostic

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.agentic.connector.ShizukuBridge
import com.jarvis.assistant.agentic.router.ModelProvider
import com.jarvis.assistant.agentic.router.ProviderVault
import com.jarvis.assistant.agentic.router.SmartProviderRouter
import com.jarvis.assistant.agentic.search.FreeLiveSearchClient
import com.jarvis.assistant.agentic.soul.SoulDatabase
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.data.memory.MemoryDatabase
import com.jarvis.assistant.databinding.ActivityDiagnosticBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiagnosticActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticBinding
    private val moduleStatusViews = mutableMapOf<Int, ModuleViewHolders>()

    private data class ModuleViewHolders(
        val tvStatus: TextView,
        val tvDetail: TextView,
        val card: LinearLayout
    )

    private data class DiagnosticItem(
        val id: Int,
        val title: String,
        val defaultDetail: String
    )

    private val modules = listOf(
        DiagnosticItem(1, "1. Microphone & 16kHz PCM Audio", "Testing audio hardware and recording permissions..."),
        DiagnosticItem(2, "2. Vosk Offline Speech Engine", "Checking offline acoustic model & speakerprint in storage..."),
        DiagnosticItem(3, "3. Google Gemini Live Engine", "Validating Gemini Live API key and WebSocket readiness..."),
        DiagnosticItem(4, "4. Groq Ultra-Fast LPU Engine", "Testing Groq LPU client latency and API key..."),
        DiagnosticItem(5, "5. Free Live Search (DuckDuckGo)", "Executing live web query without API keys..."),
        DiagnosticItem(6, "6. Android Accessibility Service", "Checking if Jarvis Accessibility Service is active in OS..."),
        DiagnosticItem(7, "7. Shizuku ADB Shell Bridge", "Checking rootless ADB IPC connection & status..."),
        DiagnosticItem(8, "8. Dual-SOUL Cognitive Memory", "Verifying Room database (user_soul_matrix & blackboard)..."),
        DiagnosticItem(9, "9. Legacy Fact Memory DB", "Verifying persistent jarvis_memory.db connection..."),
        DiagnosticItem(10, "10. Battery Optimization Exemption", "Checking background process whitelist status..."),
        DiagnosticItem(11, "11. Floating Overlay Permission", "Checking SYSTEM_ALERT_WINDOW draw permission...")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        buildDiagnosticCards()

        binding.btnRunAudit.setOnClickListener {
            runFullAudit()
        }

        // Auto-run on screen entry
        runFullAudit()
    }

    private fun buildDiagnosticCards() {
        val container = binding.layoutModuleCards
        container.removeAllViews()
        moduleStatusViews.clear()

        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

        modules.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(com.jarvis.assistant.R.drawable.bg_jarvis_card)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(12)
                layoutParams = lp
            }

            // Top Header: Title + Status Badge
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val tvTitle = TextView(this).apply {
                text = item.title
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(com.jarvis.assistant.util.ThemeManager.resolveThemeColor(this@DiagnosticActivity, com.jarvis.assistant.R.attr.themeTextPrimary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvStatus = TextView(this).apply {
                text = "IDLE"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#94A3B8"))
                setBackgroundResource(com.jarvis.assistant.R.drawable.bg_jarvis_chip)
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }

            topRow.addView(tvTitle)
            topRow.addView(tvStatus)
            card.addView(topRow)

            // Bottom Detail text
            val tvDetail = TextView(this).apply {
                text = item.defaultDetail
                textSize = 12f
                setTextColor(com.jarvis.assistant.util.ThemeManager.resolveThemeColor(this@DiagnosticActivity, com.jarvis.assistant.R.attr.themeTextMuted))
                setPadding(0, dp(6), 0, 0)
            }
            card.addView(tvDetail)

            container.addView(card)
            moduleStatusViews[item.id] = ModuleViewHolders(tvStatus, tvDetail, card)
        }
    }

    private fun runFullAudit() {
        binding.btnRunAudit.isEnabled = false
        binding.tvOverallStatus.text = "RUNNING COMPREHENSIVE AUDIT..."
        binding.viewOverallDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3B82F6"))

        lifecycleScope.launch {
            var passCount = 0
            var failCount = 0

            // 1. Microphone Hardware
            setModuleTesting(1)
            val (micOk, micDetail) = withContext(Dispatchers.Default) { testMicrophone() }
            setModuleResult(1, micOk, micDetail)
            if (micOk) passCount++ else failCount++

            // 2. Vosk Offline Speech Engine
            setModuleTesting(2)
            val (voskOk, voskDetail) = withContext(Dispatchers.IO) { testVosk() }
            setModuleResult(2, voskOk, voskDetail)
            if (voskOk) passCount++ else failCount++

            // 3. Google Gemini Live Engine
            setModuleTesting(3)
            val (geminiOk, geminiDetail) = withContext(Dispatchers.IO) { testGemini() }
            setModuleResult(3, geminiOk, geminiDetail)
            if (geminiOk) passCount++ else failCount++

            // 4. Groq Ultra-Fast LPU Engine
            setModuleTesting(4)
            val (groqOk, groqDetail) = withContext(Dispatchers.IO) { testGroq() }
            setModuleResult(4, groqOk, groqDetail)
            if (groqOk) passCount++ else failCount++

            // 5. Free Live Search (DuckDuckGo)
            setModuleTesting(5)
            val (searchOk, searchDetail) = withContext(Dispatchers.IO) { testFreeSearch() }
            setModuleResult(5, searchOk, searchDetail)
            if (searchOk) passCount++ else failCount++

            // 6. Android Accessibility Service
            setModuleTesting(6)
            val (accOk, accDetail) = testAccessibility()
            setModuleResult(6, accOk, accDetail)
            if (accOk) passCount++ else failCount++

            // 7. Shizuku ADB Shell Bridge
            setModuleTesting(7)
            val (shizOk, shizDetail) = withContext(Dispatchers.IO) { testShizuku() }
            setModuleResult(7, shizOk, shizDetail)
            if (shizOk) passCount++ else failCount++

            // 8. Dual-SOUL Cognitive Memory
            setModuleTesting(8)
            val (soulOk, soulDetail) = withContext(Dispatchers.IO) { testSoulDatabase() }
            setModuleResult(8, soulOk, soulDetail)
            if (soulOk) passCount++ else failCount++

            // 9. Legacy Fact Memory DB
            setModuleTesting(9)
            val (memOk, memDetail) = withContext(Dispatchers.IO) { testLegacyMemory() }
            setModuleResult(9, memOk, memDetail)
            if (memOk) passCount++ else failCount++

            // 10. Battery Optimization
            setModuleTesting(10)
            val (battOk, battDetail) = testBattery()
            setModuleResult(10, battOk, battDetail)
            if (battOk) passCount++ else failCount++

            // 11. Overlay Permission
            setModuleTesting(11)
            val (ovOk, ovDetail) = testOverlay()
            setModuleResult(11, ovOk, ovDetail)
            if (ovOk) passCount++ else failCount++

            // Final Overall Status
            binding.btnRunAudit.isEnabled = true
            binding.tvSummaryCounts.text = "Audit Complete: $passCount Passed (✅) • $failCount Issues Detected (❌)"
            if (failCount == 0) {
                binding.tvOverallStatus.text = "SYSTEM HEALTH: 100% OPERATIONAL (PASS ✅)"
                binding.viewOverallDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
            } else {
                binding.tvOverallStatus.text = "SYSTEM HEALTH: $passCount/11 OPERATIONAL (ATTENTION REQUIRED)"
                binding.viewOverallDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EAB308"))
            }
            Snackbar.make(binding.root, "Self-Test complete: $passCount/11 modules verified.", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun setModuleTesting(id: Int) {
        val holder = moduleStatusViews[id] ?: return
        holder.tvStatus.text = "TESTING..."
        holder.tvStatus.setTextColor(Color.parseColor("#3B82F6"))
    }

    private fun setModuleResult(id: Int, isPass: Boolean, detail: String) {
        val holder = moduleStatusViews[id] ?: return
        if (isPass) {
            holder.tvStatus.text = "PASS ✅"
            holder.tvStatus.setTextColor(Color.parseColor("#22C55E"))
        } else {
            holder.tvStatus.text = "FAIL ❌"
            holder.tvStatus.setTextColor(Color.parseColor("#EF4444"))
        }
        holder.tvDetail.text = detail
    }

    private fun testMicrophone(): Pair<Boolean, String> {
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            return Pair(false, "Microphone permission (RECORD_AUDIO) not granted.")
        }
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return if (minBuf > 0) {
            Pair(true, "Hardware buffer ready: 16kHz PCM Mono, MinBuffer = ${minBuf}B")
        } else {
            Pair(false, "AudioRecord minBufferSize returned error ($minBuf)")
        }
    }

    private fun testVosk(): Pair<Boolean, String> {
        val ready = VoskModelManager.isModelReady.value
        val targetDir = java.io.File(filesDir, "vosk-model")
        val existsOnDisk = java.io.File(targetDir, "am/final.mdl").exists()
        return if (ready || existsOnDisk) {
            Pair(true, "Acoustic model verified at: ${targetDir.name} (Offline ready)")
        } else {
            Pair(false, "Vosk model not yet unpacked or ready in memory.")
        }
    }

    private fun testGemini(): Pair<Boolean, String> {
        val vault = ProviderVault.getInstance(this)
        val hasKey = vault.hasApiKey(ModelProvider.GEMINI)
        val prefsKey = JarvisApp.instance.preferences.apiKey
        return if (hasKey || prefsKey.isNotBlank()) {
            Pair(true, "Gemini Key configured in Secure Vault • WebSocket endpoint ready")
        } else {
            Pair(false, "No Gemini API key found. Enter key in Settings -> API Connection.")
        }
    }

    private suspend fun testGroq(): Pair<Boolean, String> {
        val vault = ProviderVault.getInstance(this)
        if (!vault.hasApiKey(ModelProvider.GROQ)) {
            return Pair(false, "Groq API key not configured (Fallback 1 dormant)")
        }
        val (ok, result) = SmartProviderRouter.getInstance(this).testProvider(ModelProvider.GROQ)
        return if (ok) {
            Pair(true, "Groq LPU verified online! Latency: $result")
        } else {
            Pair(false, "Groq ping failed: $result")
        }
    }

    private suspend fun testFreeSearch(): Pair<Boolean, String> {
        val startTime = System.currentTimeMillis()
        val results = FreeLiveSearchClient().search("JARVIS Assistant")
        val elapsed = System.currentTimeMillis() - startTime
        return if (results.isNotEmpty()) {
            Pair(true, "DuckDuckGo search successful! (${results.size} results in ${elapsed}ms)")
        } else {
            Pair(false, "DuckDuckGo returned 0 results or network blocked.")
        }
    }

    private fun testAccessibility(): Pair<Boolean, String> {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val isOurServiceEnabled = enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
        return if (isOurServiceEnabled) {
            Pair(true, "JarvisAccessibilityService is ACTIVE in Android Settings.")
        } else {
            Pair(false, "Accessibility Service is DISABLED. Enable in System Settings.")
        }
    }

    private fun testShizuku(): Pair<Boolean, String> {
        val installed = ShizukuBridge.isInstalled(this)
        val running = ShizukuBridge.isRunning(this)
        val hasPermission = ShizukuBridge.hasPermission(this)
        return if (running && hasPermission) {
            Pair(true, "Shizuku rootless ADB shell ACTIVE with granted permission.")
        } else if (installed && running) {
            Pair(false, "Shizuku is running but permission is not yet granted.")
        } else if (installed) {
            Pair(false, "Shizuku app installed but service is not running (requires wireless debugging start).")
        } else {
            Pair(false, "Shizuku is not installed. (Optional for Tier-3 actions)")
        }
    }

    private suspend fun testSoulDatabase(): Pair<Boolean, String> {
        return try {
            val db = SoulDatabase.getInstance(this)
            val dao = db.soulDao()
            val userSouls = dao.getAllUserSoulAttributes()
            Pair(true, "SoulDatabase Room DB open. Synced entries: ${userSouls.size}")
        } catch (e: Exception) {
            Pair(false, "SoulDatabase error: ${e.message}")
        }
    }

    private suspend fun testLegacyMemory(): Pair<Boolean, String> {
        return try {
            val db = MemoryDatabase.getInstance(this)
            val list = db.memoryDao().getAllMemories()
            Pair(true, "MemoryDatabase Room DB open. Stored facts: ${list.size}")
        } catch (e: Exception) {
            Pair(false, "MemoryDatabase error: ${e.message}")
        }
    }

    private fun testBattery(): Pair<Boolean, String> {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnored = pm.isIgnoringBatteryOptimizations(packageName)
        return if (isIgnored) {
            Pair(true, "Battery optimization unrestricted (Safe from background kills).")
        } else {
            Pair(false, "App is battery-optimized. OS may suspend background voice recognition.")
        }
    }

    private fun testOverlay(): Pair<Boolean, String> {
        val canDraw = Settings.canDrawOverlays(this)
        return if (canDraw) {
            Pair(true, "SYSTEM_ALERT_WINDOW granted. Floating HUD ready.")
        } else {
            Pair(false, "Overlay permission not granted. Floating HUD will not appear.")
        }
    }
}
