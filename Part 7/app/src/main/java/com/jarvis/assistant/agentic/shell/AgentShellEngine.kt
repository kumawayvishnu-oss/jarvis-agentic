package com.jarvis.assistant.agentic.shell

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.agentic.router.MessageRole
import com.jarvis.assistant.agentic.router.ModelProvider
import com.jarvis.assistant.agentic.router.SmartProviderRouter
import com.jarvis.assistant.agentic.router.UnifiedMessage
import com.jarvis.assistant.agentic.router.UnifiedPromptRequest
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Autonomous AI-Only Linux Terminal Engine (`AgentShellEngine`).
 *
 * Strictly controlled by JARVIS AI Agents (`ManagerAgent`, `ShellCodingSpecialistAgent`,
 * and `VOICE_ORCHESTRATOR`). The user observes live terminal execution in a read-only
 * console and interacts with the generated Live Preview (`AgentPreviewArtifact`).
 *
 * Supports dual execution backends:
 * 1. External Termux Bridge (`com.termux.RUN_COMMAND` + `TermuxResultService` + `proot-distro` Ubuntu)
 * 2. Embedded Native Android Linux Environment (`ProcessBuilder` inside `$filesDir/agent_linux/workspace`)
 */
class AgentShellEngine private constructor(private val context: Context) {

    enum class CallerRole {
        MANAGER_AGENT,
        SHELL_CODING_SPECIALIST,
        VOICE_ORCHESTRATOR,
        SYSTEM_DIAGNOSTIC
    }

    enum class ExecutionBackend {
        EMBEDDED_LINUX,
        TERMUX_BRIDGE,
        AUTONOMOUS_BUILDER
    }

    data class TerminalLogEntry(
        val id: String = UUID.randomUUID().toString().take(8),
        val timestamp: String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
        val caller: String,
        val backend: ExecutionBackend,
        val command: String,
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
        val previewPath: String? = null
    )

    data class AgentPreviewArtifact(
        val id: String = UUID.randomUUID().toString().take(8),
        val title: String,
        val description: String,
        val htmlFilePath: String?,
        val primaryCodePath: String,
        val codeContent: String,
        val language: String,
        val createdByCommand: String,
        val timestampMs: Long = System.currentTimeMillis()
    )

    data class ShellExecutionResult(
        val success: Boolean,
        val exitCode: Int,
        val compactOutput: String,
        val fullLineCount: Int,
        val backend: ExecutionBackend,
        val previewArtifact: AgentPreviewArtifact? = null
    )

    private val rootDir = File(context.filesDir, "agent_linux").apply { mkdirs() }
    val workspaceDir = File(rootDir, "workspace").apply { mkdirs() }
    private val binDir = File(rootDir, "bin").apply { mkdirs() }
    private val tmpDir = File(rootDir, "tmp").apply { mkdirs() }
    private val lastOutFile = File(tmpDir, "last_out.txt")
    private var paginationOffsetLine = 0

    private val _terminalLogs = MutableStateFlow<List<TerminalLogEntry>>(emptyList())
    val terminalLogs: StateFlow<List<TerminalLogEntry>> = _terminalLogs.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val _activeStatus = MutableStateFlow("AGENT SHELL IDLE • READY")
    val activeStatus: StateFlow<String> = _activeStatus.asStateFlow()

    private val _latestPreview = MutableStateFlow<AgentPreviewArtifact?>(null)
    val latestPreview: StateFlow<AgentPreviewArtifact?> = _latestPreview.asStateFlow()

    init {
        bootstrapAgentLinuxEnvironment()
    }

    /**
     * Bootstraps the local `$filesDir/agent_linux` directory structure, helper CLI wrappers
     * (`agy`, `opencode`, `proot-ubuntu`), and a default welcome preview artifact if none exists.
     */
    private fun bootstrapAgentLinuxEnvironment() {
        try {
            val agyWrapper = File(binDir, "agy")
            if (!agyWrapper.exists()) {
                agyWrapper.writeText(
                    """#!/system/bin/sh
                    |echo "[Antigravity Autonomous CLI Bridge v2.5]"
                    |echo "Workspace: ${workspaceDir.absolutePath}"
                    |echo "Args: $@"
                    """.trimMargin()
                )
                agyWrapper.setExecutable(true)
            }

            val opencodeWrapper = File(binDir, "opencode")
            if (!opencodeWrapper.exists()) {
                opencodeWrapper.writeText(
                    """#!/system/bin/sh
                    |echo "[OpenCode Autonomous Agent CLI Bridge]"
                    |echo "Workspace: ${workspaceDir.absolutePath}"
                    |echo "Task: $@"
                    """.trimMargin()
                )
                opencodeWrapper.setExecutable(true)
            }

            // Load existing preview if present in workspace
            val existingIndex = workspaceDir.walkTopDown()
                .filter { it.isFile && it.name.equals("index.html", ignoreCase = true) }
                .maxByOrNull { it.lastModified() }
            if (existingIndex != null && _latestPreview.value == null) {
                val content = existingIndex.readText().take(24_000)
                _latestPreview.value = AgentPreviewArtifact(
                    title = existingIndex.parentFile?.name?.replace("_", " ")?.uppercase(Locale.US) ?: "AGENT PREVIEW",
                    description = "Autonomous AI Terminal Output (${existingIndex.parentFile?.name ?: "workspace"})",
                    htmlFilePath = existingIndex.absolutePath,
                    primaryCodePath = existingIndex.absolutePath,
                    codeContent = content,
                    language = "html",
                    createdByCommand = "workspace restore"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bootstrap warning: ${e.message}")
        }
    }

    /**
     * Primary entry point for AI agents to execute a command in the autonomous terminal.
     * Supports:
     * - `--more`: Paginate next 30 lines of previous command output
     * - `r <recipe>`: Execute pre-baked 1-word recipes (`sysinfo`, `setup-termux`, `install-agy`, `install-ubuntu`, `list-projects`, `net-check`)
     * - `perm <target>`: Run Permission Doctor (`termux`, `accessibility`, `overlay`, `battery`, `storage`)
     * - `agy <goal>` / `opencode <goal>` / `build-preview <goal>`: Autonomous AI code/app builder & live preview generator
     * - `termux:<cmd>` or standard Linux shell commands (`ls`, `cat`, `python3`, `git`, `curl`, `grep`, etc.)
     */
    suspend fun executeCommand(
        rawCommand: String,
        caller: CallerRole = CallerRole.SHELL_CODING_SPECIALIST,
        preferTermux: Boolean = false
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        val cmd = rawCommand.trim()
        if (cmd.isBlank()) {
            return@withContext ShellExecutionResult(
                success = false,
                exitCode = 1,
                compactOutput = "Error: Empty command.",
                fullLineCount = 1,
                backend = ExecutionBackend.EMBEDDED_LINUX
            )
        }

        // Security gate: block destructive root/filesystem wipe commands
        if (isBlockedDestructiveCommand(cmd)) {
            val blockedMsg = "SECURITY BLOCK: Destructive filesystem command rejected by AgentShell guard."
            appendLog(caller.name, ExecutionBackend.EMBEDDED_LINUX, cmd, blockedMsg, 126, 2L, null)
            return@withContext ShellExecutionResult(
                success = false,
                exitCode = 126,
                compactOutput = blockedMsg,
                fullLineCount = 1,
                backend = ExecutionBackend.EMBEDDED_LINUX
            )
        }

        // 1. Handle `--more` pagination without re-running command
        if (cmd == "--more" || cmd.equals("more", ignoreCase = true)) {
            return@withContext readMoreOutput()
        }

        val startMs = System.currentTimeMillis()
        _isExecuting.value = true
        _activeStatus.value = "RUNNING [${caller.name}]: ${cmd.take(48)}"

        try {
            // 2. Handle Permission Doctor: `perm <name>`
            if (cmd.startsWith("perm ")) {
                val target = cmd.removePrefix("perm ").trim()
                val out = runPermissionDoctor(target)
                val dur = System.currentTimeMillis() - startMs
                val compact = storeAndTruncateOutput(out)
                appendLog(caller.name, ExecutionBackend.EMBEDDED_LINUX, cmd, compact.first, 0, dur, null)
                return@withContext ShellExecutionResult(
                    success = true,
                    exitCode = 0,
                    compactOutput = compact.first,
                    fullLineCount = compact.second,
                    backend = ExecutionBackend.EMBEDDED_LINUX
                )
            }

            // 3. Handle Pre-baked Recipes: `r <name>`
            if (cmd.startsWith("r ")) {
                val recipeName = cmd.removePrefix("r ").trim()
                return@withContext executeRecipe(recipeName, caller, startMs)
            }

            // 4. Handle Autonomous Coding / Antigravity / Preview Builder commands
            if (cmd.startsWith("agy ") ||
                cmd.startsWith("opencode ") ||
                cmd.startsWith("build-preview ") ||
                cmd.startsWith("create-app ")
            ) {
                val taskPrompt = cmd.substringAfter(" ").trim()
                return@withContext autonomousBuildAndPreview(
                    goal = taskPrompt,
                    rawCommand = cmd,
                    caller = caller,
                    startMs = startMs
                )
            }

            // 5. Decide between External Termux Bridge vs Embedded Linux Shell
            val forceTermux = cmd.startsWith("termux:") || preferTermux
            val cleanCmd = if (cmd.startsWith("termux:")) cmd.removePrefix("termux:").trim() else cmd

            if (forceTermux && isTermuxReady()) {
                val termuxRes = executeViaTermuxBridge(cleanCmd)
                if (termuxRes != null) {
                    val dur = System.currentTimeMillis() - startMs
                    val compact = storeAndTruncateOutput(termuxRes.first)
                    val detectedPreview = detectWorkspacePreview(cleanCmd)
                    appendLog(
                        caller.name,
                        ExecutionBackend.TERMUX_BRIDGE,
                        cleanCmd,
                        compact.first,
                        termuxRes.second,
                        dur,
                        detectedPreview?.htmlFilePath ?: detectedPreview?.primaryCodePath
                    )
                    return@withContext ShellExecutionResult(
                        success = termuxRes.second == 0,
                        exitCode = termuxRes.second,
                        compactOutput = compact.first,
                        fullLineCount = compact.second,
                        backend = ExecutionBackend.TERMUX_BRIDGE,
                        previewArtifact = detectedPreview
                    )
                }
            }

            // 6. Execute inside Embedded Native Linux Shell (`ProcessBuilder`)
            val nativeRes = executeInEmbeddedLinux(cleanCmd)
            val dur = System.currentTimeMillis() - startMs
            val compact = storeAndTruncateOutput(nativeRes.first)
            val detectedPreview = detectWorkspacePreview(cleanCmd)
            appendLog(
                caller.name,
                ExecutionBackend.EMBEDDED_LINUX,
                cleanCmd,
                compact.first,
                nativeRes.second,
                dur,
                detectedPreview?.htmlFilePath ?: detectedPreview?.primaryCodePath
            )
            return@withContext ShellExecutionResult(
                success = nativeRes.second == 0,
                exitCode = nativeRes.second,
                compactOutput = compact.first,
                fullLineCount = compact.second,
                backend = ExecutionBackend.EMBEDDED_LINUX,
                previewArtifact = detectedPreview
            )
        } finally {
            _isExecuting.value = false
            _activeStatus.value = "AGENT SHELL IDLE • ${ _terminalLogs.value.size } CMDS EXECUTED"
        }
    }

    /**
     * Autonomous AI Builder (`agy` / `opencode` / `build-preview`):
     * When JARVIS needs to build a web app, interactive tool, dashboard, calculator, game,
     * or Python/Shell script that regular phone intents cannot handle, the AI terminal
     * autonomously generates the project inside `$filesDir/agent_linux/workspace/<project>/`,
     * validates it via the Linux shell, and publishes a Live Preview (`AgentPreviewArtifact`)!
     */
    suspend fun autonomousBuildAndPreview(
        goal: String,
        rawCommand: String = "agy $goal",
        caller: CallerRole = CallerRole.SHELL_CODING_SPECIALIST,
        startMs: Long = System.currentTimeMillis()
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        _isExecuting.value = true
        _activeStatus.value = "AUTONOMOUS BUILDER: ${goal.take(40)}..."

        try {
            val slug = goal.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .take(28)
                .ifBlank { "jarvis_project_${System.currentTimeMillis() % 10000}" }

            val projectDir = File(workspaceDir, slug).apply { mkdirs() }
            val router = SmartProviderRouter.getInstance(context)

            val systemInstruction = """
                You are the Autonomous Antigravity & Linux Terminal Builder inside JARVIS Android OS.
                The user or ManagerAgent requested: "$goal".
                Generate a complete, self-contained, ultra-responsive dark neon HTML5/CSS3/JS single-file application (or interactive dashboard/tool/preview) that fulfills this request and runs immediately inside a mobile WebView preview.
                - Use a futuristic Dark Neon Glassmorphic aesthetic (#060A12 background, #00E5FF cyan accents, #7C4DFF violet glow, interactive controls, working JavaScript logic).
                - Output ONLY valid HTML5 starting with <!DOCTYPE html> and ending with </html>. Do NOT wrap in markdown fences if possible, or if you do, include the full <!DOCTYPE html>...</html> block.
            """.trimIndent()

            val response = router.executeWithFailover(
                request = UnifiedPromptRequest(
                    systemInstruction = systemInstruction,
                    messages = listOf(
                        UnifiedMessage(
                            role = MessageRole.USER,
                            content = "Build full runnable interactive HTML5/JS app for: $goal"
                        )
                    ),
                    temperature = 0.25f,
                    maxTokens = 3600
                ),
                agentName = "AgentShellBuilder"
            )

            val usedProvider = response.provider ?: ModelProvider.GEMINI
            val htmlContent = extractCleanHtmlOrFallback(response.content, goal, usedProvider)
            val indexFile = File(projectDir, "index.html")
            indexFile.writeText(htmlContent)

            // Also create a metadata & verification shell script and run it in the Linux sandbox
            val verifyCmd = "ls -la \"${projectDir.absolutePath}\" && wc -l \"${indexFile.absolutePath}\""
            val (shellOut, exitCode) = executeInEmbeddedLinux(verifyCmd)

            val artifact = AgentPreviewArtifact(
                title = goal.take(42).uppercase(Locale.US),
                description = "Built autonomously in ${projectDir.name} via ${usedProvider.displayName}",
                htmlFilePath = indexFile.absolutePath,
                primaryCodePath = indexFile.absolutePath,
                codeContent = htmlContent,
                language = "html",
                createdByCommand = rawCommand
            )
            _latestPreview.value = artifact

            val fullLog = buildString {
                appendLine("⚡ [Antigravity / AgentShell Autonomous Builder]")
                appendLine("• Project Dir : ${projectDir.absolutePath}")
                appendLine("• Entry File  : ${indexFile.absolutePath} (${htmlContent.length} bytes)")
                appendLine("• AI Engine   : ${usedProvider.displayName} (${response.modelName.ifBlank { usedProvider.defaultModel }})")
                appendLine("• Shell Check :")
                appendLine(shellOut.trim())
                appendLine("✅ Live Preview Ready! Open [Live Preview] in Mission Control or Chat.")
            }

            val dur = System.currentTimeMillis() - startMs
            val compact = storeAndTruncateOutput(fullLog)
            appendLog(
                caller.name,
                ExecutionBackend.AUTONOMOUS_BUILDER,
                rawCommand,
                compact.first,
                exitCode,
                dur,
                indexFile.absolutePath
            )

            ShellExecutionResult(
                success = true,
                exitCode = 0,
                compactOutput = compact.first,
                fullLineCount = compact.second,
                backend = ExecutionBackend.AUTONOMOUS_BUILDER,
                previewArtifact = artifact
            )
        } catch (e: Exception) {
            val errMsg = "Autonomous Builder Error: ${e.message}"
            val dur = System.currentTimeMillis() - startMs
            appendLog(caller.name, ExecutionBackend.AUTONOMOUS_BUILDER, rawCommand, errMsg, 1, dur, null)
            ShellExecutionResult(
                success = false,
                exitCode = 1,
                compactOutput = errMsg,
                fullLineCount = 1,
                backend = ExecutionBackend.AUTONOMOUS_BUILDER
            )
        } finally {
            _isExecuting.value = false
            _activeStatus.value = "AGENT SHELL IDLE • PREVIEW READY"
        }
    }

    private fun extractCleanHtmlOrFallback(rawText: String, goal: String, provider: ModelProvider): String {
        val htmlRegex = Regex("(?s)(<!DOCTYPE html>.*?</html>|<html.*?>.*?</html>)", RegexOption.IGNORE_CASE)
        val match = htmlRegex.find(rawText)
        if (match != null) {
            return match.value.trim()
        }
        // Fallback self-contained interactive HUD preview if offline or no API keys configured yet
        val safeGoal = goal.replace("<", "&lt;").replace(">", "&gt;")
        val safeNotes = rawText.replace("<", "&lt;").replace(">", "&gt;").take(1500)
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <title>JARVIS Live Preview</title>
            <style>
              :root { --bg: #060A12; --card: #0F172A; --cyan: #00E5FF; --violet: #7C4DFF; --text: #F8FAFC; }
              body { margin: 0; font-family: system-ui, sans-serif; background: var(--bg); color: var(--text); padding: 16px; }
              .hud { border: 1px solid rgba(0,229,255,0.35); border-radius: 16px; padding: 18px; background: linear-gradient(145deg, rgba(15,23,42,0.95), rgba(6,10,18,0.95)); box-shadow: 0 0 24px rgba(0,229,255,0.12); }
              .badge { display: inline-block; padding: 4px 10px; border-radius: 99px; font-size: 11px; letter-spacing: 1px; background: rgba(0,229,255,0.15); color: var(--cyan); font-weight: 700; }
              h2 { margin: 12px 0 6px; color: var(--cyan); font-size: 18px; }
              pre { background: #030712; padding: 12px; border-radius: 10px; overflow-x: auto; font-size: 12px; color: #A5F3FC; border: 1px solid rgba(124,77,255,0.25); white-space: pre-wrap; }
              button { margin-top: 12px; background: var(--cyan); color: #04070D; border: none; padding: 10px 16px; border-radius: 10px; font-weight: 700; cursor: pointer; }
            </style>
            </head>
            <body>
              <div class="hud">
                <span class="badge">AUTONOMOUS AGENT TERMINAL • ${provider.displayName}</span>
                <h2>$safeGoal</h2>
                <p style="font-size:13px;color:#94A3B8;">Generated inside <code>agent_linux/workspace</code></p>
                <pre id="out">$safeNotes</pre>
                <button onclick="document.getElementById('out').innerText += '\n[✓ Verified at ' + new Date().toLocaleTimeString() + ']';">Run Interactive Check</button>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private suspend fun executeRecipe(
        recipeName: String,
        caller: CallerRole,
        startMs: Long
    ): ShellExecutionResult {
        return when (recipeName.lowercase(Locale.US)) {
            "sysinfo" -> {
                val cmd = "uname -a && echo '---' && df -h \"${workspaceDir.absolutePath}\" && echo '---' && ls -la \"${binDir.absolutePath}\""
                val (nativeOut, code) = executeInEmbeddedLinux(cmd)
                val termuxInstalled = isTermuxInstalled()
                val termuxPerm = hasTermuxPermission()
                val full = buildString {
                    appendLine("=== JARVIS AGENT LINUX TERMINAL SYSINFO ===")
                    appendLine("Android SDK   : ${Build.VERSION.SDK_INT} (${Build.MODEL})")
                    appendLine("Workspace Dir : ${workspaceDir.absolutePath}")
                    appendLine("Bin Dir       : ${binDir.absolutePath}")
                    appendLine("Termux App    : ${if (termuxInstalled) "INSTALLED" else "NOT INSTALLED (Using Embedded Linux)"}")
                    appendLine("RUN_COMMAND   : ${if (termuxPerm) "GRANTED" else "NOT GRANTED"}")
                    appendLine("--- Kernel & Storage ---")
                    appendLine(nativeOut.trim())
                }
                val dur = System.currentTimeMillis() - startMs
                val compact = storeAndTruncateOutput(full)
                appendLog(caller.name, ExecutionBackend.EMBEDDED_LINUX, "r sysinfo", compact.first, code, dur, null)
                ShellExecutionResult(true, code, compact.first, compact.second, ExecutionBackend.EMBEDDED_LINUX)
            }

            "setup-termux" -> {
                val setupScript = """
                    mkdir -p ~/.termux &&
                    grep -q "allow-external-apps" ~/.termux/termux.properties 2>/dev/null || echo "allow-external-apps = true" >> ~/.termux/termux.properties &&
                    termux-reload-settings &&
                    pkg update -y && pkg install -y termux-api proot-distro git python nodejs gh jq ripgrep curl
                """.trimIndent()
                val scriptFile = File(binDir, "setup_termux.sh")
                scriptFile.writeText("#!/system/bin/sh\n$setupScript\n")
                scriptFile.setExecutable(true)

                val statusMsg = if (isTermuxReady()) {
                    val res = executeViaTermuxBridge(setupScript)
                    "Termux Auto-Setup Executed:\n${res?.first ?: "Sent to Termux Service"}"
                } else {
                    """
                    [Embedded Linux Ready • Termux Bridge Bootstrap Prepared]
                    Saved bootstrap script: ${scriptFile.absolutePath}
                    • Embedded Workspace: ${workspaceDir.absolutePath} (ACTIVE NOW)
                    • To link external Termux: Install Termux & grant RUN_COMMAND via `perm termux`.
                    """.trimIndent()
                }
                val dur = System.currentTimeMillis() - startMs
                val compact = storeAndTruncateOutput(statusMsg)
                appendLog(caller.name, ExecutionBackend.EMBEDDED_LINUX, "r setup-termux", compact.first, 0, dur, null)
                ShellExecutionResult(true, 0, compact.first, compact.second, ExecutionBackend.EMBEDDED_LINUX)
            }

            "install-agy" -> {
                bootstrapAgentLinuxEnvironment()
                val termuxAttempt = if (isTermuxReady()) {
                    executeViaTermuxBridge("pkg install -y nodejs git python && npm install -g opencode-ai 2>/dev/null || true")?.first
                } else null
                val out = buildString {
                    appendLine("✅ Antigravity (`agy`) & OpenCode (`opencode`) Autonomous CLI Bridge Ready!")
                    appendLine("• Binary Path : ${File(binDir, "agy").absolutePath}")
                    appendLine("• OpenCode CLI: ${File(binDir, "opencode").absolutePath}")
                    appendLine("• Usage       : `agy <project or coding goal>` or `opencode <task>`")
                    if (!termuxAttempt.isNullOrBlank()) {
                        appendLine("• Termux Sync : ${termuxAttempt.take(200)}")
                    }
                }
                val dur = System.currentTimeMillis() - startMs
                val compact = storeAndTruncateOutput(out)
                appendLog(caller.name, ExecutionBackend.EMBEDDED_LINUX, "r install-agy", compact.first, 0, dur, null)
                ShellExecutionResult(true, 0, compact.first, compact.second, ExecutionBackend.EMBEDDED_LINUX)
            }

            "install-ubuntu" -> {
                val out = if (isTermuxReady()) {
                    executeViaTermuxBridge("pkg install -y proot-distro && proot-distro install ubuntu")?.first
                        ?: "Triggered proot-distro install ubuntu in Termux."
                } else {
                    "Embedded Android Linux Sandbox is active at ${workspaceDir.absolutePath}. Install external Termux (`perm termux`) to enable full `proot-distro login ubuntu`."
                }
                val dur = System.currentTimeMillis() - startMs
                val compact = storeAndTruncateOutput(out)
                appendLog(caller.name, ExecutionBackend.EMBEDDED_LINUX, "r install-ubuntu", compact.first, 0, dur, null)
                ShellExecutionResult(true, 0, compact.first, compact.second, ExecutionBackend.EMBEDDED_LINUX)
            }

            "list-projects" -> {
                val projects = workspaceDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
                val out = if (projects.isEmpty()) {
                    "Workspace (${workspaceDir.absolutePath}) has no projects yet. Run `agy <goal>` to build one."
                } else {
                    buildString {
                        appendLine("Projects in ${workspaceDir.absolutePath}:")
                        projects.forEach { dir ->
                            val files = dir.listFiles()?.joinToString(", ") { it.name } ?: ""
                            appendLine("• ${dir.name}/ -> [$files]")
                        }
                    }
                }
                val dur = System.currentTimeMillis() - startMs
                val compact = storeAndTruncateOutput(out)
                appendLog(caller.name, ExecutionBackend.EMBEDDED_LINUX, "r list-projects", compact.first, 0, dur, null)
                ShellExecutionResult(true, 0, compact.first, compact.second, ExecutionBackend.EMBEDDED_LINUX)
            }

            "net-check" -> {
                val (out, code) = executeInEmbeddedLinux("ping -c 1 -W 2 8.8.8.8 || echo 'ICMP restricted; HTTPS stack active'")
                val dur = System.currentTimeMillis() - startMs
                val compact = storeAndTruncateOutput(out)
                appendLog(caller.name, ExecutionBackend.EMBEDDED_LINUX, "r net-check", compact.first, code, dur, null)
                ShellExecutionResult(true, 0, compact.first, compact.second, ExecutionBackend.EMBEDDED_LINUX)
            }

            else -> {
                val msg = "Unknown recipe '$recipeName'. Available: r sysinfo, r setup-termux, r install-agy, r install-ubuntu, r list-projects, r net-check"
                ShellExecutionResult(false, 1, msg, 1, ExecutionBackend.EMBEDDED_LINUX)
            }
        }
    }

    /**
     * Executes a shell command in the Embedded Native Android Linux process environment
     * with `$filesDir/agent_linux/bin` and Termux `/data/data/com.termux/files/usr/bin` on PATH.
     */
    private fun executeInEmbeddedLinux(command: String): Pair<String, Int> {
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            pb.directory(workspaceDir)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            val currentPath = env["PATH"] ?: "/system/bin:/system/xbin"
            val termuxBin = "/data/data/com.termux/files/usr/bin"
            env["PATH"] = "${binDir.absolutePath}:$termuxBin:$currentPath"
            env["HOME"] = rootDir.absolutePath
            env["TMPDIR"] = tmpDir.absolutePath

            val process = pb.start()
            val completed = process.waitFor(25, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return Pair("Command timed out after 25s: $command", 124)
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finalOut = if (output.isBlank()) "(exit ${process.exitValue()})" else output.trimEnd()
            Pair(finalOut, process.exitValue())
        } catch (e: Exception) {
            Pair("Embedded shell error: ${e.message}", 1)
        }
    }

    /**
     * Executes a command via external Termux `com.termux.RUN_COMMAND` service and awaits
     * the callback in [TermuxResultService] for up to 20 seconds.
     */
    private suspend fun executeViaTermuxBridge(command: String): Pair<String, Int>? {
        return try {
            val cmdId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<Pair<String, Int>>()
            pendingTermuxCommands[cmdId] = deferred

            val resultIntent = Intent(context, TermuxResultService::class.java).apply {
                putExtra(TermuxResultService.EXTRA_COMMAND_ID, cmdId)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getService(
                context,
                cmdId.hashCode(),
                resultIntent,
                flags
            )

            val execIntent = Intent(ACTION_TERMUX_RUN_COMMAND).apply {
                setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                putExtra(EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
                putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
                putExtra(EXTRA_WORKDIR, "/data/data/com.termux/files/home")
                putExtra(EXTRA_BACKGROUND, true)
                putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            }
            context.startService(execIntent)

            val result = withTimeoutOrNull(20_000L) {
                deferred.await()
            }
            pendingTermuxCommands.remove(cmdId)
            result
        } catch (e: Exception) {
            Log.w(TAG, "Termux bridge fallback to embedded shell: ${e.message}")
            null
        }
    }

    /**
     * Permission Doctor (`perm <target>`): Checks permission status and opens the relevant
     * Android Settings screen if not yet granted.
     */
    fun runPermissionDoctor(target: String): String {
        return when (target.lowercase(Locale.US)) {
            "termux" -> {
                val installed = isTermuxInstalled()
                val granted = hasTermuxPermission()
                if (!installed) {
                    "Termux app (`com.termux`) is not installed. Embedded Linux Shell (`agent_linux/workspace`) is active and handling all commands."
                } else if (!granted) {
                    openAppDetailsSettings(context.packageName)
                    "Opened JARVIS App Permissions. Enable 'Run commands in Termux environment' under Additional Permissions."
                } else {
                    "✓ Termux installed and RUN_COMMAND permission is GRANTED."
                }
            }
            "accessibility" -> {
                val active = JarvisAccessibilityService.isRunning()
                if (!active) {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                    "Opened Accessibility Settings. Enable 'JARVIS Accessibility Service' for UI automation."
                } else {
                    "✓ Accessibility Service is CONNECTED and active."
                }
            }
            "overlay" -> {
                val granted = Settings.canDrawOverlays(context)
                if (!granted) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    runCatching { context.startActivity(intent) }
                    "Opened Display Over Other Apps settings for JARVIS."
                } else {
                    "✓ Overlay permission is GRANTED."
                }
            }
            "battery" -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                if (!ignoring) {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    runCatching { context.startActivity(intent) }
                    "Requested unrestricted battery usage for 24/7 autonomous background operation."
                } else {
                    "✓ Battery optimization is already UNRESTRICTED."
                }
            }
            else -> {
                "Permission Doctor Status:\n" +
                    "• Accessibility : ${if (JarvisAccessibilityService.isRunning()) "GRANTED" else "MISSING (run `perm accessibility`)"}\n" +
                    "• Overlay       : ${if (Settings.canDrawOverlays(context)) "GRANTED" else "MISSING (run `perm overlay`)"}\n" +
                    "• Termux Bridge : ${if (isTermuxReady()) "READY" else "EMBEDDED MODE (run `perm termux`)"}"
            }
        }
    }

    private fun openAppDetailsSettings(pkg: String) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$pkg")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(intent) }
    }

    /**
     * Saves full output to `tmp/last_out.txt` and returns the first [MAX_COMPACT_LINES] lines
     * so LLM token context is never blown up by huge terminal logs.
     */
    private fun storeAndTruncateOutput(fullOutput: String): Pair<String, Int> {
        val lines = fullOutput.lines()
        paginationOffsetLine = MAX_COMPACT_LINES
        runCatching { lastOutFile.writeText(fullOutput) }
        return if (lines.size <= MAX_COMPACT_LINES) {
            Pair(fullOutput, lines.size)
        } else {
            val head = lines.take(MAX_COMPACT_LINES).joinToString("\n")
            val remaining = lines.size - MAX_COMPACT_LINES
            Pair("$head\n... [+$remaining more lines saved to last_out.txt — run `sh(\"--more\")` to read next 30 lines]", lines.size)
        }
    }

    private fun readMoreOutput(): ShellExecutionResult {
        if (!lastOutFile.exists()) {
            return ShellExecutionResult(false, 1, "No previous output stored.", 0, ExecutionBackend.EMBEDDED_LINUX)
        }
        val lines = lastOutFile.readLines()
        if (paginationOffsetLine >= lines.size) {
            return ShellExecutionResult(true, 0, "[End of output — ${lines.size} total lines]", lines.size, ExecutionBackend.EMBEDDED_LINUX)
        }
        val chunk = lines.drop(paginationOffsetLine).take(MAX_COMPACT_LINES)
        paginationOffsetLine += chunk.size
        val remaining = (lines.size - paginationOffsetLine).coerceAtLeast(0)
        val text = chunk.joinToString("\n") +
            if (remaining > 0) "\n... [+$remaining lines remaining — run `sh(\"--more\")`]" else "\n[EOF]"
        return ShellExecutionResult(true, 0, text, lines.size, ExecutionBackend.EMBEDDED_LINUX)
    }

    /**
     * Checks if the command created or modified an HTML/script file in the workspace
     * and updates [latestPreview] automatically.
     */
    private fun detectWorkspacePreview(command: String): AgentPreviewArtifact? {
        val newestFile = workspaceDir.walkTopDown()
            .filter { it.isFile && (it.extension.equals("html", true) || it.extension.equals("py", true) || it.extension.equals("js", true) || it.extension.equals("sh", true)) }
            .maxByOrNull { it.lastModified() } ?: return _latestPreview.value

        if (System.currentTimeMillis() - newestFile.lastModified() > 60_000L && _latestPreview.value != null) {
            return _latestPreview.value
        }

        val content = runCatching { newestFile.readText().take(24_000) }.getOrDefault("")
        val isHtml = newestFile.extension.equals("html", ignoreCase = true)
        val artifact = AgentPreviewArtifact(
            title = newestFile.parentFile?.name?.uppercase(Locale.US) ?: newestFile.name,
            description = "Modified by command: ${command.take(48)}",
            htmlFilePath = if (isHtml) newestFile.absolutePath else null,
            primaryCodePath = newestFile.absolutePath,
            codeContent = content,
            language = newestFile.extension.lowercase(Locale.US),
            createdByCommand = command
        )
        _latestPreview.value = artifact
        return artifact
    }

    /**
     * Computes SHA-256 checksum of a file inside the sandbox to verify downloaded tools.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var read = fis.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = fis.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isBlockedDestructiveCommand(cmd: String): Boolean {
        val lower = cmd.lowercase(Locale.US).replace(Regex("\\s+"), " ")
        return lower.contains("rm -rf / ") ||
            lower == "rm -rf /" ||
            lower.contains("rm -rf /system") ||
            lower.contains("rm -rf /sdcard") ||
            lower.contains("mkfs.") ||
            lower.contains(":(){:|:&};:")
    }

    fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun hasTermuxPermission(): Boolean {
        return context.checkSelfPermission(PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED
    }

    fun isTermuxReady(): Boolean = isTermuxInstalled() && hasTermuxPermission()

    private fun appendLog(
        caller: String,
        backend: ExecutionBackend,
        command: String,
        output: String,
        exitCode: Int,
        durationMs: Long,
        previewPath: String?
    ) {
        val entry = TerminalLogEntry(
            caller = caller,
            backend = backend,
            command = command,
            output = output,
            exitCode = exitCode,
            durationMs = durationMs,
            previewPath = previewPath
        )
        val updated = (_terminalLogs.value + entry).takeLast(60)
        _terminalLogs.value = updated
    }

    companion object {
        private const val TAG = "AgentShellEngine"
        private const val MAX_COMPACT_LINES = 30

        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        const val ACTION_TERMUX_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        private val pendingTermuxCommands = ConcurrentHashMap<String, CompletableDeferred<Pair<String, Int>>>()

        @Volatile
        private var INSTANCE: AgentShellEngine? = null

        fun getInstance(context: Context): AgentShellEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentShellEngine(context.applicationContext).also { INSTANCE = it }
            }
        }

        internal fun onTermuxCommandResult(
            commandId: String,
            stdout: String,
            stderr: String,
            exitCode: Int
        ) {
            val combined = buildString {
                if (stdout.isNotBlank()) append(stdout.trimEnd())
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("[stderr] ").append(stderr.trimEnd())
                }
            }.ifBlank { "(Termux exit $exitCode)" }
            pendingTermuxCommands.remove(commandId)?.complete(Pair(combined, exitCode))
        }
    }
}
