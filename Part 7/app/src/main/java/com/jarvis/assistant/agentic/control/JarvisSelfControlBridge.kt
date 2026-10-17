package com.jarvis.assistant.agentic.control

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.agentic.router.ModelProvider
import com.jarvis.assistant.agentic.router.ProviderVault
import com.jarvis.assistant.agentic.shell.AgentShellEngine
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.ui.diagnostic.DiagnosticActivity
import com.jarvis.assistant.ui.diagnostic.LiveDebugActivity
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.ui.preview.AgentPreviewDialog
import com.jarvis.assistant.ui.settings.ApiConnectionActivity
import com.jarvis.assistant.ui.settings.OverlayAvatarActivity
import com.jarvis.assistant.ui.settings.PersonaActivity
import com.jarvis.assistant.util.OppoA11kRamOptimizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Gives JARVIS full self-accessibility, UI navigation, live terminal display, and internal introspection
 * over its own Android application.
 *
 * Whenever the user asks:
 * - "Mujhe dikhao terminal mein kya ho raha hai" / "Show terminal"
 * - "Live preview dikhao" / "Source code dikhao" / "htop system monitor dikhao"
 * - "API Vault kholo" / "Diagnostics kholo" / "Memory kholo" / "Updates tab kholo"
 * - "Apne andar check karo kya problem hai"
 * JARVIS uses this bridge to directly control, navigate, and display its own internal screens and terminal.
 */
object JarvisSelfControlBridge {

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class WorkstationTab {
        VIEWPORT,
        TERMINAL,
        SOURCE,
        HTOP
    }

    /**
     * Immediately pops open the Fullscreen AI Terminal & Viewport Workstation on the user's screen
     * at the requested tab (`TERMINAL`, `VIEWPORT`, `SOURCE`, or `HTOP`).
     */
    fun showWorkstationOnScreen(
        context: Context,
        initialTab: WorkstationTab = WorkstationTab.TERMINAL
    ): String {
        mainHandler.post {
            val resumed = JarvisApp.instance.currentResumedActivity
            if (resumed != null && !resumed.isFinishing) {
                val scope = (resumed as? AppCompatActivity)?.lifecycleScope ?: bridgeScope
                AgentPreviewDialog.show(
                    context = resumed,
                    scope = scope,
                    initialArtifact = AgentShellEngine.getInstance(context).latestPreview.value,
                    initialTab = initialTab
                )
            } else {
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("OPEN_WORKSTATION_TAB", initialTab.name)
                }
                context.startActivity(intent)
            }
        }
        val shell = AgentShellEngine.getInstance(context)
        val recentLogs = shell.terminalLogs.value.takeLast(4)
        val logSnippet = if (recentLogs.isEmpty()) {
            "No terminal commands executed yet in this session (Workspace ready at ${shell.workspaceDir.absolutePath})."
        } else {
            recentLogs.joinToString("\n") { entry ->
                "  [${entry.timestamp}] $ ${entry.command} -> exit=${entry.exitCode} (${entry.durationMs}ms): ${entry.output.lines().firstOrNull()?.take(90) ?: "OK"}"
            }
        }
        return "[SELF-UI // WORKSTATION OPENED: ${initialTab.name}]\n" +
            "Terminal Status: ${shell.activeStatus.value}\n" +
            "Recent Terminal Activity:\n$logSnippet"
    }

    /**
     * Navigates directly to any internal JARVIS screen, tab, or workstation view.
     */
    fun navigateToInternalScreen(context: Context, targetRaw: String): String {
        val target = targetRaw.trim().lowercase()
        return when {
            target.contains("terminal") || target.contains("shell") || target.contains("console") -> {
                showWorkstationOnScreen(context, WorkstationTab.TERMINAL)
            }
            target.contains("htop") || target.contains("monitor") || target.contains("ram") -> {
                showWorkstationOnScreen(context, WorkstationTab.HTOP)
            }
            target.contains("source") || target.contains("code") -> {
                showWorkstationOnScreen(context, WorkstationTab.SOURCE)
            }
            target.contains("preview") || target.contains("viewport") || target.contains("render") -> {
                showWorkstationOnScreen(context, WorkstationTab.VIEWPORT)
            }
            target.contains("api") || target.contains("vault") || target.contains("provider") -> {
                launchActivity(context, ApiConnectionActivity::class.java, "15-Provider AI Vault (ApiConnectionActivity)")
            }
            target.contains("diag") || target.contains("self_test") || target.contains("test") || target.contains("audit") -> {
                launchActivity(context, DiagnosticActivity::class.java, "12-Module System Diagnostics (DiagnosticActivity)")
            }
            target.contains("mem") || target.contains("soul") -> {
                launchActivity(context, MemoryActivity::class.java, "4-Tier Dual-SOUL Memory Matrix (MemoryActivity)")
            }
            target.contains("debug") || target.contains("logcat") -> {
                launchActivity(context, LiveDebugActivity::class.java, "Live Pipeline Debugger (LiveDebugActivity)")
            }
            target.contains("persona") || target.contains("naira") -> {
                launchActivity(context, PersonaActivity::class.java, "Persona Selector (PersonaActivity)")
            }
            target.contains("avatar") || target.contains("overlay") -> {
                launchActivity(context, OverlayAvatarActivity::class.java, "Floating Overlay Avatars (OverlayAvatarActivity)")
            }
            target.contains("update") || target.contains("swarm") -> {
                switchMainTab(context, MainActivity.TAB_UPDATES, "Updates & Swarm Console Tab")
            }
            target.contains("chat") -> {
                switchMainTab(context, MainActivity.TAB_CHAT, "Multi-Agent Chat Tab")
            }
            target.contains("setting") -> {
                switchMainTab(context, MainActivity.TAB_SETTINGS, "System Settings Tab")
            }
            target.contains("home") || target.contains("orb") -> {
                switchMainTab(context, MainActivity.TAB_HOME, "Home 3D Orb & HUD Tab")
            }
            else -> {
                showWorkstationOnScreen(context, WorkstationTab.TERMINAL)
            }
        }
    }

    private fun switchMainTab(context: Context, tabId: Int, label: String): String {
        mainHandler.post {
            val resumed = JarvisApp.instance.currentResumedActivity
            if (resumed is MainActivity && !resumed.isFinishing) {
                resumed.selectTab(tabId)
            } else {
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("SELECT_TAB_ID", tabId)
                }
                context.startActivity(intent)
            }
        }
        return "[SELF-UI // NAVIGATED] Switched JARVIS screen to: $label"
    }

    private fun launchActivity(context: Context, cls: Class<*>, label: String): String {
        mainHandler.post {
            val resumed = JarvisApp.instance.currentResumedActivity
            if (resumed != null && !resumed.isFinishing) {
                resumed.startActivity(Intent(resumed, cls))
            } else {
                val intent = Intent(context, cls).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
        return "[SELF-UI // OPENED] Launched internal JARVIS screen: $label"
    }

    /**
     * Full self-introspection of JARVIS's own architecture, active screen, RAM, providers,
     * terminal state, and real warnings/issues.
     */
    fun inspectSelfState(context: Context): String {
        val ram = OppoA11kRamOptimizer.captureTelemetry(context)
        val vault = ProviderVault.getInstance(context)
        val shell = AgentShellEngine.getInstance(context)
        val configuredProviders = ModelProvider.entries.filter { provider ->
            !provider.requiresApiKey || !vault.getApiKey(provider).isNullOrBlank()
        }
        val missingKeyProviders = ModelProvider.entries.filter { provider ->
            provider.requiresApiKey && vault.getApiKey(provider).isNullOrBlank()
        }
        val a11yEnabled = JarvisAccessibilityService.isRunning()
        val voiceState = JarvisConversationService.connectionStatus.value
        val activeScreen = JarvisApp.instance.currentResumedActivity?.javaClass?.simpleName ?: "BackgroundService"
        val logs = shell.terminalLogs.value
        val agyInstalled = java.io.File(shell.binDir, "agy").exists()

        val issues = mutableListOf<String>()
        if (!a11yEnabled) {
            issues.add("[WARN] Android Accessibility Service (`JarvisAccessibilityService`) is currently OFF. Screen tap/UI automation requires turning it ON via `perm accessibility`.")
        }
        if (ram.isLowMemory) {
            issues.add("[WARN] Free RAM is low (${ram.availableRamMb}MB). OPPO A11k RAM Optimizer is active (`r ram-boost` available).")
        }
        if (configuredProviders.size <= 2) {
            issues.add("[INFO] Only zero-key providers (`Pollinations Free` & `Local Ollama`) are active. Add free keys (`GLM Zhipu`, `Groq`, `Google AI Studio`, `SiliconFlow`, `NVIDIA NIM`) in API Vault for faster multi-provider routing.")
        }

        return buildString {
            appendLine("[JARVIS SELF-INSPECTION // INTERNAL DIAGNOSTIC REPORT]")
            appendLine("┌── [01] DEVICE & OPPO A11K RAM PROFILE")
            appendLine("│   Model: ${ram.deviceModel} (OPPO A11k Optimizer: ${if (ram.isOppoOptimizedProfile) "ACTIVE" else "STANDBY"})")
            appendLine("│   System RAM: ${ram.availableRamMb} MB free / ${ram.totalRamMb} MB total (${ram.usedRamPercent}% used)")
            appendLine("│   JVM Heap: ${ram.jvmUsedMb} MB used / ${ram.jvmMaxMb} MB max | Active Threads: ${ram.activeThreads}")
            appendLine("├── [02] SELF-UI & ACCESSIBILITY STATE")
            appendLine("│   Current Active Screen: $activeScreen | Voice Session: $voiceState")
            appendLine("│   Accessibility DOM Bridge: ${if (a11yEnabled) "[ONLINE]" else "[OFFLINE - Enable in Settings]"}")
            appendLine("├── [03] AUTONOMOUS TERMINAL (`AgentShellEngine`)")
            appendLine("│   Status: ${shell.activeStatus.value}")
            appendLine("│   Built-in Tools: [htop] [postman] [curl] [netstat] [ping] [git] [agy] [validate] [search]")
            appendLine("│   Antigravity Suite: ${if (agyInstalled) "[INSTALLED in bin/agy]" else "[READY TO INSTALL via 'r install-agy']"}")
            appendLine("│   Session Commands Run: ${logs.size}")
            if (logs.isNotEmpty()) {
                logs.takeLast(3).forEach { l ->
                    appendLine("│     ❯ ${l.command} -> exit=${l.exitCode} (${l.durationMs}ms)")
                }
            }
            appendLine("├── [04] 15-PROVIDER AI VAULT STATUS")
            appendLine("│   Active/Ready Providers (${configuredProviders.size}/15): ${configuredProviders.joinToString(", ") { it.displayName }}")
            if (missingKeyProviders.isNotEmpty()) {
                appendLine("│   Unconfigured Optional Keys (${missingKeyProviders.size}): ${missingKeyProviders.take(6).joinToString(", ") { it.name }}")
            }
            appendLine("└── [05] DETECTED WARNINGS / HONEST DIAGNOSTICS")
            if (issues.isEmpty()) {
                appendLine("    [OK] All core subsystems (Terminal, Network, Accessibility, Providers, RAM) are nominal.")
            } else {
                issues.forEach { appendLine("    $it") }
            }
        }.trimEnd()
    }
}
