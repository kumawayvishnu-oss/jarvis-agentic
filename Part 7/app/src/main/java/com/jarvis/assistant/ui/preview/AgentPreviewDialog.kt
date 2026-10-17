package com.jarvis.assistant.ui.preview

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.agentic.control.JarvisSelfControlBridge
import com.jarvis.assistant.agentic.shell.AgentShellEngine
import com.jarvis.assistant.util.OppoA11kRamOptimizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Professional Terminal, Telemetry & Viewport Workstation (`AgentPreviewDialog`).
 *
 * Designed with Terminal-HUD Typography Architecture (Zero cartoon emojis; crisp geometric
 * indicators `◈`, `▸`, `❯`, `┌──`, `└──`, and OPPO A11k low-RAM WebView lifecycle management).
 */
object AgentPreviewDialog {

    @Volatile
    private var activeDialog: Dialog? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun show(
        context: Context,
        scope: CoroutineScope,
        initialArtifact: AgentShellEngine.AgentPreviewArtifact? = null,
        initialTab: JarvisSelfControlBridge.WorkstationTab? = null
    ) {
        try {
            activeDialog?.dismiss()
        } catch (_: Exception) {
        }

        val shell = AgentShellEngine.getInstance(context)
        val artifact = initialArtifact ?: shell.latestPreview.value
        val orbitronFont = runCatching { ResourcesCompat.getFont(context, R.font.orbitron_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        val rajdhaniBold = runCatching { ResourcesCompat.getFont(context, R.font.rajdhani_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD

        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        activeDialog = dialog
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#F704070D")))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 12))
            alpha = 0f
            translationY = 18f
        }

        // ── 1. Top Precision HUD Header ─────────────────────────────────────────
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val ramSnap = OppoA11kRamOptimizer.captureTelemetry(context)
        val badgeTv = TextView(context).apply {
            text = "◈ JARVIS // SHELL & VIEWPORT WORKSTATION  |  RAM: ${ramSnap.availableRamMb}MB FREE"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 9.5f
            typeface = orbitronFont
            letterSpacing = 0.09f
        }

        val titleTv = TextView(context).apply {
            text = artifact?.title ?: "ROOT@JARVIS-LINUX :: AUTONOMOUS CONSOLE"
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 16f
            typeface = rajdhaniBold
        }

        val subTitleTv = TextView(context).apply {
            text = "session: ${shell.workspaceDir.absolutePath}  //  status: ${shell.activeStatus.value}"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
        }

        titleColumn.addView(badgeTv)
        titleColumn.addView(titleTv)
        titleColumn.addView(subTitleTv)

        val closeBtn = Button(context).apply {
            text = "[ X  CLOSE ]"
            isAllCaps = false
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#E2E8F0"))
            background = panelDrawable("#0F172A", "#334155", cornerRadiusDp = 8f)
            setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 4))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                dialog.dismiss()
            }
        }

        headerRow.addView(titleColumn)
        headerRow.addView(closeBtn)
        root.addView(headerRow)

        // ── 2. Precision Navigation Tabs (Zero Cartoon Emojis) ──────────────────
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 10)
                bottomMargin = dp(context, 8)
            }
        }

        val btnTerminalTab = createTabButton(context, "[ TERMINAL ]", false)
        val btnViewportTab = createTabButton(context, "[ VIEWPORT ]", false)
        val btnSourceTab = createTabButton(context, "[ SOURCE ]", false)
        val btnHtopTab = createTabButton(context, "[ HTOP // SYS ]", false)

        tabRow.addView(btnTerminalTab, LinearLayout.LayoutParams(0, dp(context, 34), 1f).apply { rightMargin = dp(context, 5) })
        tabRow.addView(btnViewportTab, LinearLayout.LayoutParams(0, dp(context, 34), 1f).apply { rightMargin = dp(context, 5) })
        tabRow.addView(btnSourceTab, LinearLayout.LayoutParams(0, dp(context, 34), 1f).apply { rightMargin = dp(context, 5) })
        tabRow.addView(btnHtopTab, LinearLayout.LayoutParams(0, dp(context, 34), 1f))
        root.addView(tabRow)

        // ── 3. Main Workstation Viewport Frame ──────────────────────────────────
        val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            background = panelDrawable("#050811", "#1E293B", cornerRadiusDp = 10f, strokeWidthPx = 2)
            setPadding(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
        }

        // Terminal Prompt Banner inside Console
        val consoleBanner = TextView(context).apply {
            text = "┌──(root@jarvis-linux)-[~/workspace]──[TOOLS: htop | postman | curl | netstat | git | agy | validate]"
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.parseColor("#09101E"))
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
        }
        contentContainer.addView(consoleBanner)

        val webView = WebView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#050811"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            OppoA11kRamOptimizer.optimizeWebViewForLowRam(this, isLocalAssetView = true)
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            visibility = View.GONE
        }

        val codeScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#04070D"))
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 12))
        }

        val codeText = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setLineSpacing(dp(context, 3).toFloat(), 1.0f)
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextIsSelectable(true)
        }
        codeScroll.addView(codeText)

        contentContainer.addView(webView)
        contentContainer.addView(codeScroll)
        root.addView(contentContainer)

        fun formatTerminalStream(): String {
            val logs = shell.terminalLogs.value
            val t = OppoA11kRamOptimizer.captureTelemetry(context)
            val header = buildString {
                appendLine("JARVIS EMBEDDED LINUX & TERMUX WORKSTATION [v2.7-ARM64]")
                appendLine("DEVICE PROFILE : ${t.deviceModel} | FREE RAM: ${t.availableRamMb}MB / ${t.totalRamMb}MB | JVM: ${t.jvmUsedMb}MB/${t.jvmMaxMb}MB")
                appendLine("BUILTIN TOOLS  : htop, postman, curl, wget, netstat, ping, git clone, agy, validate, search")
                appendLine("────────────────────────────────────────────────────────────────────────")
            }
            if (logs.isEmpty()) {
                return header + "\n[SESSION READY] Type any command below or tap a quick-exec chip ([HTOP], [VALIDATE], [POSTMAN], [NETSTAT]).\n└─❯ _"
            }
            val body = logs.takeLast(18).joinToString("\n\n") { entry ->
                val statusTag = if (entry.exitCode == 0) "[OK // EXIT:0]" else "[ERR // EXIT:${entry.exitCode}]"
                "┌──[${entry.timestamp}]──[${entry.caller} :: ${entry.backend.name}]──[${entry.durationMs}ms]──$statusTag\n" +
                    "└─❯ ${entry.command}\n" +
                    entry.output.trimEnd()
            }
            return "$header\n$body\n\n└─❯ _"
        }

        fun renderArtifactInWebView(art: AgentShellEngine.AgentPreviewArtifact?) {
            if (art == null) {
                val emptyHtml = """
                    <!DOCTYPE html><html><body style="background:#050811;color:#E2E8F0;font-family:monospace;padding:24px;">
                    <div style="border:1px solid #1E293B;background:#09101E;padding:18px;border-radius:8px;">
                      <div style="color:#00E5FF;font-size:12px;letter-spacing:1px;">◈ VIEWPORT STANDBY // NO HTML ARTIFACT LOADED</div>
                      <h3 style="margin:10px 0 6px 0;color:#F8FAFC;">Autonomous App & Tool Viewport</h3>
                      <p style="color:#94A3B8;font-size:12px;line-height:1.5;">
                        Run <b>r install-agy</b>, <b>htop</b>, <b>validate</b>, or <b>agy &lt;goal&gt;</b> below to render live output here.
                      </p>
                    </div>
                    </body></html>
                """.trimIndent()
                webView.loadDataWithBaseURL(null, emptyHtml, "text/html", "UTF-8", null)
                return
            }
            titleTv.text = art.title
            subTitleTv.text = "${art.description}  //  ${art.primaryCodePath.substringAfterLast("/")}"
            val htmlFile = art.htmlFilePath?.let { File(it) }
            if (htmlFile != null && htmlFile.exists()) {
                webView.loadDataWithBaseURL(
                    "file://${htmlFile.parentFile?.absolutePath}/",
                    art.codeContent,
                    "text/html",
                    "UTF-8",
                    null
                )
            } else {
                webView.loadDataWithBaseURL(null, art.codeContent, "text/html", "UTF-8", null)
            }
        }

        var currentTab = initialTab ?: if (artifact != null) {
            JarvisSelfControlBridge.WorkstationTab.VIEWPORT
        } else {
            JarvisSelfControlBridge.WorkstationTab.TERMINAL
        }

        fun selectWorkstationTab(tab: JarvisSelfControlBridge.WorkstationTab) {
            currentTab = tab
            val allBtns = listOf(
                JarvisSelfControlBridge.WorkstationTab.TERMINAL to btnTerminalTab,
                JarvisSelfControlBridge.WorkstationTab.VIEWPORT to btnViewportTab,
                JarvisSelfControlBridge.WorkstationTab.SOURCE to btnSourceTab,
                JarvisSelfControlBridge.WorkstationTab.HTOP to btnHtopTab
            )
            allBtns.forEach { (t, btn) ->
                val active = t == tab
                btn.background = if (active) {
                    panelDrawable("#0D2137", "#00E5FF", cornerRadiusDp = 7f, strokeWidthPx = 2)
                } else {
                    panelDrawable("#090F1C", "#1E293B", cornerRadiusDp = 7f, strokeWidthPx = 1)
                }
                btn.setTextColor(if (active) Color.parseColor("#00E5FF") else Color.parseColor("#94A3B8"))
            }

            when (tab) {
                JarvisSelfControlBridge.WorkstationTab.TERMINAL -> {
                    OppoA11kRamOptimizer.hibernateWebView(webView)
                    webView.visibility = View.GONE
                    codeScroll.visibility = View.VISIBLE
                    consoleBanner.text = "┌──(root@jarvis-linux)-[~/workspace]──[STREAM: LIVE SHELL LOGS & STDOUT]"
                    codeText.setTextColor(Color.parseColor("#E2E8F0"))
                    codeText.text = formatTerminalStream()
                    codeScroll.post { codeScroll.fullScroll(View.FOCUS_DOWN) }
                }
                JarvisSelfControlBridge.WorkstationTab.VIEWPORT -> {
                    codeScroll.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    OppoA11kRamOptimizer.wakeWebView(webView)
                    consoleBanner.text = "┌──(root@jarvis-linux)-[~/workspace]──[VIEWPORT: LIVE INTERACTIVE RENDER]"
                    renderArtifactInWebView(shell.latestPreview.value ?: artifact)
                }
                JarvisSelfControlBridge.WorkstationTab.SOURCE -> {
                    OppoA11kRamOptimizer.hibernateWebView(webView)
                    webView.visibility = View.GONE
                    codeScroll.visibility = View.VISIBLE
                    consoleBanner.text = "┌──(root@jarvis-linux)-[~/workspace]──[INSPECTOR: GENERATED SOURCE CODE]"
                    codeText.setTextColor(Color.parseColor("#7DD3FC"))
                    val cur = shell.latestPreview.value ?: artifact
                    codeText.text = cur?.codeContent ?: "// No source code artifact generated yet.\n// Use 'agy <goal>' or 'r install-agy' to generate code."
                }
                JarvisSelfControlBridge.WorkstationTab.HTOP -> {
                    OppoA11kRamOptimizer.hibernateWebView(webView)
                    webView.visibility = View.GONE
                    codeScroll.visibility = View.VISIBLE
                    consoleBanner.text = "┌──(root@jarvis-linux)-[~/workspace]──[HTOP // SYSTEM, RAM & NETWORK MONITOR]"
                    codeText.setTextColor(Color.parseColor("#6EE7B7"))
                    codeText.text = "Running live htop & OPPO A11k hardware telemetry..."
                    scope.launch {
                        val res = shell.executeCommand("htop", AgentShellEngine.CallerRole.SYSTEM_DIAGNOSTIC)
                        withContext(Dispatchers.Main) {
                            codeText.text = res.compactOutput
                        }
                    }
                }
            }
        }

        btnTerminalTab.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            selectWorkstationTab(JarvisSelfControlBridge.WorkstationTab.TERMINAL)
        }
        btnViewportTab.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            selectWorkstationTab(JarvisSelfControlBridge.WorkstationTab.VIEWPORT)
        }
        btnSourceTab.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            selectWorkstationTab(JarvisSelfControlBridge.WorkstationTab.SOURCE)
        }
        btnHtopTab.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            selectWorkstationTab(JarvisSelfControlBridge.WorkstationTab.HTOP)
        }

        // ── 4. Quick-Exec Telemetry & Tool Chips Bar ────────────────────────────
        val chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 8)
            }
        }

        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // Forward declaration of command runner
        lateinit var runWorkstationCommand: (String) -> Unit

        val quickCommands = listOf(
            "[ HTOP ]" to "htop",
            "[ VALIDATE ]" to "r validate",
            "[ POSTMAN API ]" to "postman test-providers",
            "[ NETSTAT ]" to "netstat",
            "[ AGY SUITE ]" to "r install-agy",
            "[ RAM BOOST ]" to "r ram-boost",
            "[ SYSINFO ]" to "r sysinfo",
            "[ SELF-AUDIT ]" to "self-inspect"
        )

        quickCommands.forEach { (label, cmdStr) ->
            val chip = Button(context).apply {
                text = label
                isAllCaps = false
                textSize = 10.5f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#00E5FF"))
                background = panelDrawable("#091326", "#1E3A5F", cornerRadiusDp = 6f)
                setPadding(dp(context, 10), dp(context, 2), dp(context, 10), dp(context, 2))
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    runWorkstationCommand(cmdStr)
                }
            }
            chipRow.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 30)).apply {
                    rightMargin = dp(context, 6)
                }
            )
        }
        chipScroll.addView(chipRow)
        root.addView(chipScroll)

        // ── 5. Bottom Command & AI Build Input Bar ──────────────────────────────
        val buildRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 8)
            }
        }

        val promptPrefix = TextView(context).apply {
            text = "❯"
            typeface = Typeface.MONOSPACE
            textSize = 15f
            setTextColor(Color.parseColor("#00E5FF"))
            setPadding(dp(context, 6), 0, dp(context, 8), 0)
        }

        val goalInput = EditText(context).apply {
            hint = "htop, postman GET <url>, curl, netstat, validate, or agy <goal>..."
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.parseColor("#F8FAFC"))
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setSingleLine(true)
            background = panelDrawable("#09101E", "#1E293B", cornerRadiusDp = 8f)
            setPadding(dp(context, 12), dp(context, 9), dp(context, 12), dp(context, 9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(context, 8)
            }
        }

        val runBuildBtn = Button(context).apply {
            text = "[ ▸ EXEC ]"
            isAllCaps = false
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(Color.parseColor("#04070D"))
            background = panelDrawable("#00E5FF", "#00E5FF", cornerRadiusDp = 8f)
            setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9))
        }

        runWorkstationCommand = { rawInput ->
            val input = rawInput.trim()
            if (input.isNotBlank()) {
                runBuildBtn.isEnabled = false
                runBuildBtn.text = "[ WAIT ]"
                subTitleTv.text = "executing: $input ..."
                scope.launch {
                    if (input.equals("self-inspect", ignoreCase = true)) {
                        val report = JarvisSelfControlBridge.inspectSelfState(context)
                        withContext(Dispatchers.Main) {
                            runBuildBtn.isEnabled = true
                            runBuildBtn.text = "[ ▸ EXEC ]"
                            selectWorkstationTab(JarvisSelfControlBridge.WorkstationTab.TERMINAL)
                            codeText.text = report
                        }
                    } else {
                        val shellCmd = if (
                            input.startsWith("agy ", ignoreCase = true) ||
                            input.startsWith("r ", ignoreCase = true) ||
                            input.startsWith("perm ", ignoreCase = true) ||
                            input.startsWith("htop", ignoreCase = true) ||
                            input.startsWith("top", ignoreCase = true) ||
                            input.startsWith("postman", ignoreCase = true) ||
                            input.startsWith("api-test", ignoreCase = true) ||
                            input.startsWith("curl ", ignoreCase = true) ||
                            input.startsWith("wget ", ignoreCase = true) ||
                            input.startsWith("netstat", ignoreCase = true) ||
                            input.startsWith("ping ", ignoreCase = true) ||
                            input.startsWith("validate", ignoreCase = true) ||
                            input.startsWith("git ", ignoreCase = true) ||
                            input.startsWith("search ", ignoreCase = true) ||
                            input.startsWith("research ", ignoreCase = true) ||
                            input.startsWith("ls", ignoreCase = true) ||
                            input.startsWith("cat ", ignoreCase = true) ||
                            input.startsWith("echo ", ignoreCase = true) ||
                            input.startsWith("uname", ignoreCase = true) ||
                            input.startsWith("df", ignoreCase = true) ||
                            input.startsWith("free", ignoreCase = true) ||
                            input.startsWith("ps", ignoreCase = true)
                        ) {
                            input
                        } else if (input.contains("antigravity", ignoreCase = true) || input.contains("anti gravity", ignoreCase = true)) {
                            "r install-agy"
                        } else {
                            "agy $input"
                        }

                        val res = shell.executeCommand(shellCmd, AgentShellEngine.CallerRole.SHELL_CODING_SPECIALIST)
                        withContext(Dispatchers.Main) {
                            runBuildBtn.isEnabled = true
                            runBuildBtn.text = "[ ▸ EXEC ]"
                            val newRam = OppoA11kRamOptimizer.captureTelemetry(context)
                            badgeTv.text = "◈ JARVIS // SHELL & VIEWPORT WORKSTATION  |  RAM: ${newRam.availableRamMb}MB FREE"
                            subTitleTv.text = "last: $shellCmd (exit=${res.exitCode})"
                            if (res.previewArtifact != null && shellCmd.startsWith("agy ", ignoreCase = true)) {
                                renderArtifactInWebView(res.previewArtifact)
                                selectWorkstationTab(JarvisSelfControlBridge.WorkstationTab.VIEWPORT)
                            } else if (shellCmd.startsWith("htop", ignoreCase = true)) {
                                selectWorkstationTab(JarvisSelfControlBridge.WorkstationTab.HTOP)
                            } else {
                                selectWorkstationTab(JarvisSelfControlBridge.WorkstationTab.TERMINAL)
                            }
                        }
                    }
                }
            }
        }

        runBuildBtn.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val text = goalInput.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                goalInput.setText("")
                runWorkstationCommand(text)
            }
        }

        buildRow.addView(promptPrefix)
        buildRow.addView(goalInput)
        buildRow.addView(runBuildBtn)
        root.addView(buildRow)

        selectWorkstationTab(currentTab)

        dialog.setOnDismissListener {
            OppoA11kRamOptimizer.hibernateWebView(webView)
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
            if (activeDialog === dialog) {
                activeDialog = null
            }
        }

        dialog.setContentView(root)
        dialog.show()

        root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(210L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun createTabButton(context: Context, title: String, active: Boolean): Button {
        return Button(context).apply {
            text = title
            isAllCaps = false
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(if (active) Color.parseColor("#00E5FF") else Color.parseColor("#94A3B8"))
            background = if (active) {
                panelDrawable("#0D2137", "#00E5FF", cornerRadiusDp = 7f, strokeWidthPx = 2)
            } else {
                panelDrawable("#090F1C", "#1E293B", cornerRadiusDp = 7f, strokeWidthPx = 1)
            }
            setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2))
        }
    }

    private fun panelDrawable(
        fillHex: String,
        strokeHex: String,
        cornerRadiusDp: Float = 10f,
        strokeWidthPx: Int = 2
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusDp * 2.5f
            setColor(Color.parseColor(fillHex))
            setStroke(strokeWidthPx, Color.parseColor(strokeHex))
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
