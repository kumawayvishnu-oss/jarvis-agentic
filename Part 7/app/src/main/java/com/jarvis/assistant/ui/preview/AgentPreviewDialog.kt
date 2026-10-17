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
import android.view.animation.OvershootInterpolator
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jarvis.assistant.agentic.shell.AgentShellEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Interactive Live Preview Window (`AgentPreviewDialog`) for viewing web apps, dashboards,
 * scripts, and tools built autonomously by JARVIS's AI Terminal (`AgentShellEngine`).
 *
 * Notice: The user does NOT type raw terminal commands here; the terminal is strictly
 * AI-controlled. The user can inspect the Live Rendered WebView, inspect the generated source
 * code, or give the AI Agent a high-level build/refinement goal that the AI executes in its terminal.
 */
object AgentPreviewDialog {

    @SuppressLint("SetJavaScriptEnabled")
    fun show(
        context: Context,
        scope: CoroutineScope,
        initialArtifact: AgentShellEngine.AgentPreviewArtifact? = null
    ) {
        val shell = AgentShellEngine.getInstance(context)
        val artifact = initialArtifact ?: shell.latestPreview.value

        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#F204070D")))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(context, 16), dp(context, 18), dp(context, 16), dp(context, 16))
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
        }

        // Top HUD Header
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

        val badgeTv = TextView(context).apply {
            text = "⚡ AI TERMINAL LIVE PREVIEW • READ-ONLY SANDBOX"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }

        val titleTv = TextView(context).apply {
            text = artifact?.title ?: "AUTONOMOUS WORKSPACE PREVIEW"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }

        val subTitleTv = TextView(context).apply {
            text = artifact?.description ?: "Ask JARVIS to build any app, calculator, dashboard, or script"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
        }

        titleColumn.addView(badgeTv)
        titleColumn.addView(titleTv)
        titleColumn.addView(subTitleTv)

        val closeBtn = Button(context).apply {
            text = "✕ Close"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.parseColor("#F8FAFC"))
            background = pillDrawable("#1E293B", "#334155")
            setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                dialog.dismiss()
            }
        }

        headerRow.addView(titleColumn)
        headerRow.addView(closeBtn)
        root.addView(headerRow)

        // Mode Switch Tabs: [🌐 Live Render] [💻 Source Code] [📜 AI Terminal Stream]
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 12)
                bottomMargin = dp(context, 10)
            }
        }

        val btnLiveTab = createTabButton(context, "🌐 Live Render", true)
        val btnCodeTab = createTabButton(context, "💻 Source Code", false)
        val btnLogsTab = createTabButton(context, "📜 Agent Terminal", false)

        tabRow.addView(btnLiveTab, LinearLayout.LayoutParams(0, dp(context, 38), 1f).apply { rightMargin = dp(context, 6) })
        tabRow.addView(btnCodeTab, LinearLayout.LayoutParams(0, dp(context, 38), 1f).apply { rightMargin = dp(context, 6) })
        tabRow.addView(btnLogsTab, LinearLayout.LayoutParams(0, dp(context, 38), 1f))
        root.addView(tabRow)

        // Content Frame
        val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            background = pillDrawable("#0A0F1D", "#00E5FF", cornerRadiusDp = 16f, strokeWidthPx = 2)
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
        }

        val webView = WebView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#060A12"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
        }

        val codeScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        }

        val codeText = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(Color.parseColor("#A5F3FC"))
            setTextIsSelectable(true)
        }
        codeScroll.addView(codeText)

        fun renderArtifact(art: AgentShellEngine.AgentPreviewArtifact?) {
            if (art == null) {
                val emptyHtml = """
                    <!DOCTYPE html><html><body style="background:#060A12;color:#F8FAFC;font-family:sans-serif;padding:24px;text-align:center;">
                    <h3 style="color:#00E5FF;">No AI Preview Generated Yet</h3>
                    <p style="color:#94A3B8;font-size:13px;">Give the AI Agent a build goal below (e.g. "Build a neon crypto & EMI calculator" or "Create a Pomodoro timer app") and watch the AI build it autonomously in its Linux terminal.</p>
                    </body></html>
                """.trimIndent()
                webView.loadDataWithBaseURL(null, emptyHtml, "text/html", "UTF-8", null)
                codeText.text = "// Workspace is ready at: ${shell.workspaceDir.absolutePath}\n// Ask the AI Agent below to autonomously code & preview a project."
                return
            }
            titleTv.text = art.title
            subTitleTv.text = "${art.description} • ${art.primaryCodePath.substringAfterLast("/")}"
            codeText.text = art.codeContent

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

        renderArtifact(artifact)

        contentContainer.addView(webView)
        contentContainer.addView(codeScroll)
        root.addView(contentContainer)

        // Tab switching logic
        btnLiveTab.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            webView.visibility = View.VISIBLE
            codeScroll.visibility = View.GONE
            btnLiveTab.background = pillDrawable("#112240", "#00E5FF")
            btnCodeTab.background = pillDrawable("#0F172A", "#1E293B")
            btnLogsTab.background = pillDrawable("#0F172A", "#1E293B")
        }

        btnCodeTab.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            webView.visibility = View.GONE
            codeScroll.visibility = View.VISIBLE
            val cur = shell.latestPreview.value ?: artifact
            codeText.text = cur?.codeContent ?: "// No source code generated yet."
            btnCodeTab.background = pillDrawable("#112240", "#00E5FF")
            btnLiveTab.background = pillDrawable("#0F172A", "#1E293B")
            btnLogsTab.background = pillDrawable("#0F172A", "#1E293B")
        }

        btnLogsTab.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            webView.visibility = View.GONE
            codeScroll.visibility = View.VISIBLE
            val logs = shell.terminalLogs.value
            codeText.text = if (logs.isEmpty()) {
                "=== AI TERMINAL READ-ONLY CONSOLE ===\nWorkspace: ${shell.workspaceDir.absolutePath}\nNo commands executed by AI agent in this session yet."
            } else {
                logs.reversed().joinToString("\n\n────────────────────────\n\n") { entry ->
                    "[${entry.timestamp}] (${entry.caller} • ${entry.backend.name} • ${entry.durationMs}ms • exit=${entry.exitCode})\n" +
                        "agent@jarvis-linux:~/workspace$ ${entry.command}\n${entry.output}"
                }
            }
            btnLogsTab.background = pillDrawable("#112240", "#00E5FF")
            btnLiveTab.background = pillDrawable("#0F172A", "#1E293B")
            btnCodeTab.background = pillDrawable("#0F172A", "#1E293B")
        }

        // Bottom Bar: Delegate Goal to Autonomous AI Builder (Strictly AI-controlled terminal)
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 12) }
        }

        val goalInput = EditText(context).apply {
            hint = "Tell AI Agent what to build & preview (e.g. 'Neon Scientific Calculator')..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13f
            background = pillDrawable("#0F172A", "#334155")
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(context, 8)
            }
        }

        val buildBtn = Button(context).apply {
            text = "🤖 AI Build"
            isAllCaps = false
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#04070D"))
            background = pillDrawable("#00E5FF", "#00E5FF")
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
        }

        buildBtn.setOnClickListener {
            val goal = goalInput.text?.toString()?.trim().orEmpty()
            if (goal.isBlank()) return@setOnClickListener
            buildBtn.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            buildBtn.isEnabled = false
            buildBtn.text = "⏳ AI Building..."
            subTitleTv.text = "AI Agent is autonomously coding & verifying in agent_linux/workspace..."

            scope.launch {
                val res = shell.autonomousBuildAndPreview(
                    goal = goal,
                    rawCommand = "agy \"$goal\"",
                    caller = AgentShellEngine.CallerRole.SHELL_CODING_SPECIALIST
                )
                withContext(Dispatchers.Main) {
                    buildBtn.isEnabled = true
                    buildBtn.text = "🤖 AI Build"
                    goalInput.setText("")
                    renderArtifact(res.previewArtifact ?: shell.latestPreview.value)
                    webView.visibility = View.VISIBLE
                    codeScroll.visibility = View.GONE
                }
            }
        }

        bottomRow.addView(goalInput)
        bottomRow.addView(buildBtn)
        root.addView(bottomRow)

        dialog.setContentView(root)
        dialog.show()

        // 60fps physics entry animation
        root.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(280L)
            .setInterpolator(OvershootInterpolator(1.08f))
            .start()
    }

    private fun createTabButton(context: Context, label: String, active: Boolean): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (active) Color.parseColor("#00E5FF") else Color.parseColor("#94A3B8"))
            background = if (active) pillDrawable("#112240", "#00E5FF") else pillDrawable("#0F172A", "#1E293B")
            setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
        }
    }

    private fun pillDrawable(
        fillHex: String,
        strokeHex: String,
        cornerRadiusDp: Float = 12f,
        strokeWidthPx: Int = 2
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusDp * 2.5f
            setColor(Color.parseColor(fillHex))
            setStroke(strokeWidthPx, Color.parseColor(strokeHex))
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
