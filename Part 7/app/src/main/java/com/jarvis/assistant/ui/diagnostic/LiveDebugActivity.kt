package com.jarvis.assistant.ui.diagnostic

import android.Manifest
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.util.LiveDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * LIVE DEBUG: real, continuously refreshed state of every stage of the voice + automation
 * pipeline, plus the last ~100 log lines of this process. Designed to be screenshotted.
 * Every row is computed from a real system API or a real code path counter — nothing is faked.
 */
class LiveDebugActivity : AppCompatActivity() {

    private val bg = Color.parseColor("#05080F")
    private val card = Color.parseColor("#0D1522")
    private val cyan = Color.parseColor("#00E5FF")
    private val textMain = Color.parseColor("#E6F7FF")
    private val textDim = Color.parseColor("#7FA3B5")

    private lateinit var statusContainer: LinearLayout
    private lateinit var logView: TextView
    private lateinit var probeView: TextView
    private lateinit var tapTarget: Button
    private var refreshJob: Job? = null
    private var probeResult: String = "Not run yet. Tap PROBE MODEL to ask Google which Live models your key can use."
    @Volatile private var tapReceivedAt = 0L
    private var tapRequestedAt = 0L

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(12), dp(16), dp(12), dp(8))
        }
        root.addView(TextView(this).apply {
            text = "◉ LIVE DEBUG"
            setTextColor(cyan); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Real-time pipeline state. Screenshot this screen when something fails."
            setTextColor(textDim); textSize = 12f
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(btn("PROBE MODEL") { probeModels() })
        buttons.addView(btn("TEST TAP") { testTap() })
        buttons.addView(btn("COPY LOGS") { copyAll() })
        buttons.addView(btn("RESET") { LiveDebug.resetCounters(); refreshNow() })
        buttons.addView(btn("ACCESSIBILITY") { openSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        buttons.addView(btn("APP INFO") {
            openSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        root.addView(HorizontalScrollView(this).apply { addView(buttons) })

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(statusContainer)

        tapTarget = Button(this).apply {
            text = "TAP TARGET (pressed by Accessibility during TEST TAP)"
            setTextColor(textMain); textSize = 11f
            background = rounded(Color.parseColor("#14263A"))
            setOnClickListener {
                tapReceivedAt = System.currentTimeMillis()
                LiveDebug.log("LiveDebug", "TAP TARGET received click")
                refreshNow()
            }
        }
        content.addView(tapTarget, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })

        content.addView(section("MODEL PROBE (REST v1beta)"))
        probeView = mono()
        content.addView(probeView)
        content.addView(section("LAST ~100 LOG LINES (this app)"))
        logView = mono()
        content.addView(logView)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshJob = lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                renderStatuses()
                if (tick % 2 == 0) {
                    val lines = withContext(Dispatchers.IO) { LiveDebug.readOwnLogcat(100) }
                    logView.text = lines.joinToString("\n")
                }
                tick++
                delay(1000)
            }
        }
    }

    override fun onPause() {
        refreshJob?.cancel()
        super.onPause()
    }

    private fun refreshNow() = renderStatuses()

    // ------------------------------------------------------------------ rows

    private data class Row(val label: String, val ok: Boolean?, val detail: String)

    private fun collectRows(): List<Row> {
        val rows = mutableListOf<Row>()
        val prefs = AppPreferences(this)

        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        rows += Row("Mic permission", micGranted, if (micGranted) "RECORD_AUDIO granted" else "RECORD_AUDIO NOT granted - voice cannot work")

        val rec = LiveDebug.get(LiveDebug.MIC_RECORDER)
        rows += Row("Mic recorder", rec?.ok, (rec?.detail ?: "AudioRecord not started yet (start a session)") + " | frames read=${LiveDebug.micFramesRead.get()}")

        val sent = LiveDebug.audioFramesSent.get()
        val dropped = LiveDebug.audioFramesDropped.get()
        rows += Row("Audio frames → Gemini", if (sent > 0) true else if (dropped > 0) false else null,
            "sent=$sent dropped=$dropped" + if (LiveDebug.lastDropReason.isNotBlank()) " (last drop: ${LiveDebug.lastDropReason})" else "")

        val key = prefs.apiKey
        rows += Row("Gemini API key", key.isNotBlank(), if (key.isBlank()) "missing - set it in API Connection" else "set (${key.length} chars, starts ${key.take(4)}…)")

        val model = LiveDebug.get(LiveDebug.MODEL)
        rows += Row("Live model", model?.ok, (model?.detail ?: "not sent yet") + " | pref=${prefs.aiModel}")

        rows += statusRow("WebSocket", LiveDebug.WS_STATE, "never connected in this process")
        rows += statusRow("Setup handshake", LiveDebug.WS_SETUP, "no setup sent yet")
        val close = LiveDebug.get(LiveDebug.WS_CLOSE)
        rows += Row("Last close/failure", close?.ok, close?.detail ?: "none")
        val apiErr = LiveDebug.get(LiveDebug.API_ERROR)
        rows += Row("API error", if (apiErr == null) true else apiErr.ok, apiErr?.detail?.let { "${ago(apiErr.at)}: $it" } ?: "none reported")

        val chunks = LiveDebug.audioChunksReceived.get()
        rows += Row("Audio from Gemini", if (chunks > 0) true else if (sent > 0) false else null,
            if (chunks > 0) LiveDebug.get(LiveDebug.AUDIO_IN)?.detail ?: "$chunks chunks" else "0 chunks received" +
                    " | server msgs=${LiveDebug.serverMessages.get()}")

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val pb = LiveDebug.get(LiveDebug.PLAYBACK)
        val pbOk = when {
            vol == 0 -> false
            pb != null -> pb.ok
            chunks > 0 -> false
            else -> null
        }
        rows += Row("Playback (24kHz)", pbOk,
            (pb?.detail ?: if (chunks > 0) "audio received but NEVER written to AudioTrack (suppressed/silent mode?)" else "nothing to play yet") +
                    " | media vol=$vol/$maxVol" + (if (vol == 0) " ← VOLUME IS ZERO" else "") +
                    " | write errors=${LiveDebug.playbackWriteErrors.get()}")
        rows += Row("Silent execution mode", !prefs.isSilentExecutionMode,
            if (prefs.isSilentExecutionMode) "ON - task narration audio is muted" else "off")

        val tool = LiveDebug.get(LiveDebug.TOOL_CALL)
        rows += Row("Tool calls", if (tool != null) true else null,
            "count=${LiveDebug.toolCalls.get()}" + (tool?.let { " last ${ago(it.at)}: ${it.detail}" } ?: ""))

        val enabledInSettings = isOurA11yEnabledInSettings()
        val connected = JarvisAccessibilityService.instance != null
        rows += Row("Accessibility enabled (Settings)", enabledInSettings,
            if (enabledInSettings) "listed in ENABLED_ACCESSIBILITY_SERVICES" else "NOT enabled in system Accessibility settings")
        rows += Row("Accessibility connected", connected,
            when {
                connected -> "service bound (onServiceConnected ran)"
                enabledInSettings -> "enabled but NOT bound - toggle it OFF and ON, or restart phone"
                else -> LiveDebug.get(LiveDebug.ACCESSIBILITY)?.detail ?: "not bound"
            })
        rows += restrictedSettingsRow()
        val act = LiveDebug.get(LiveDebug.ACCESSIBILITY_ACTION)
        val tapState = when {
            tapRequestedAt == 0L -> null
            tapReceivedAt >= tapRequestedAt -> true
            System.currentTimeMillis() - tapRequestedAt > 2500 -> false
            else -> null
        }
        rows += Row("Test tap (gesture → app)", tapState ?: act?.ok,
            when (tapState) {
                true -> "gesture reached TAP TARGET ✓ (clicks work)"
                false -> "gesture did NOT reach TAP TARGET. ${act?.detail ?: ""}"
                else -> act?.detail ?: "press TEST TAP"
            })

        val overlay = Settings.canDrawOverlays(this)
        rows += Row("Overlay permission", overlay, if (overlay) "granted" else "not granted (floating HUD disabled)")
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batt = pm.isIgnoringBatteryOptimizations(packageName)
        rows += Row("Battery unrestricted", batt, if (batt) "ignoring battery optimizations" else "optimized - background service may be killed")
        if (Build.VERSION.SDK_INT >= 33) {
            val notif = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            rows += Row("Notifications", notif, if (notif) "granted" else "not granted (foreground service notice hidden)")
        }
        val on = JarvisConversationService.isSessionOn.value
        val standby = JarvisConversationService.isStandby.value
        rows += Row("Conversation service", if (on || standby) true else null,
            "sessionOn=$on standby=$standby")
        return rows
    }

    private fun statusRow(label: String, key: String, empty: String): Row {
        val s = LiveDebug.get(key)
        return Row(label, s?.ok, s?.let { "${it.detail} (${ago(it.at)})" } ?: empty)
    }

    private fun isOurA11yEnabledInSettings(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val ours = ComponentName(this, JarvisAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
    }

    /** Android 13+ blocks Accessibility for sideloaded APKs until "Allow restricted settings" is tapped in App info. */
    private fun restrictedSettingsRow(): Row {
        if (Build.VERSION.SDK_INT < 33) return Row("Restricted settings", true, "not applicable (Android < 13)")
        val source = try {
            val info = packageManager.getInstallSourceInfo(packageName)
            "installer=${info.installingPackageName ?: "unknown"} source=${info.packageSource}"
        } catch (e: Exception) { "install source unknown (${e.message})" }
        val mode = try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.unsafeCheckOpNoThrow("android:access_restricted_settings", applicationInfo.uid, packageName)
        } catch (e: Exception) {
            LiveDebug.log("LiveDebug", "restricted-settings op check failed: ${e.message}")
            -1
        }
        val restricted = mode == AppOpsManager.MODE_ERRORED || mode == AppOpsManager.MODE_IGNORED
        val detail = when {
            restricted -> "BLOCKED (sideloaded). Fix: APP INFO → ⋮ menu (top-right) → \"Allow restricted settings\" → then enable Accessibility. [$source]"
            mode == AppOpsManager.MODE_ALLOWED -> "allowed [$source]"
            else -> "op mode=$mode (cannot read) [$source]"
        }
        return Row("Restricted settings (sideload)", if (mode == -1) null else !restricted, detail)
    }

    private fun renderStatuses() {
        val rows = collectRows()
        statusContainer.removeAllViews()
        rows.forEach { r ->
            val badge = when (r.ok) { true -> "✅"; false -> "❌"; null -> "⏳" }
            statusContainer.addView(TextView(this).apply {
                text = "$badge  ${r.label}\n     ${r.detail}"
                setTextColor(if (r.ok == false) Color.parseColor("#FF6B81") else textMain)
                textSize = 12f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = rounded(card)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        probeView.text = probeResult
    }

    // --------------------------------------------------------------- actions

    private fun probeModels() {
        val key = AppPreferences(this).apiKey
        if (key.isBlank()) { probeResult = "❌ No API key saved."; refreshNow(); return }
        val target = (LiveDebug.get(LiveDebug.MODEL)?.detail ?: AppPreferences(this).aiModel).removePrefix("models/")
        probeResult = "⏳ probing…"; refreshNow()
        lifecycleScope.launch {
            probeResult = withContext(Dispatchers.IO) {
                val sb = StringBuilder()
                try {
                    val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$target?key=$key").build()
                    http.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val methods = JSONObject(body).optJSONArray("supportedGenerationMethods")?.toString() ?: "[]"
                            val bidi = methods.contains("bidiGenerateContent")
                            sb.append(if (bidi) "✅" else "❌").append(" models/$target HTTP ${resp.code} methods=$methods\n")
                            if (!bidi) sb.append("   → this model does NOT support Live (bidiGenerateContent)\n")
                        } else {
                            sb.append("❌ models/$target HTTP ${resp.code}: ${body.take(300)}\n")
                        }
                    }
                    val listReq = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key=$key").build()
                    http.newCall(listReq).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val arr = JSONObject(body).optJSONArray("models")
                            val live = mutableListOf<String>()
                            if (arr != null) for (i in 0 until arr.length()) {
                                val m = arr.getJSONObject(i)
                                if (m.optJSONArray("supportedGenerationMethods")?.toString()?.contains("bidiGenerateContent") == true) {
                                    live += m.optString("name")
                                }
                            }
                            sb.append("Live-capable models for this key (${live.size}):\n")
                            live.forEach { sb.append("   • ").append(it).append('\n') }
                        } else {
                            sb.append("❌ list models HTTP ${resp.code}: ${body.take(300)}\n")
                        }
                    }
                } catch (e: Exception) {
                    sb.append("❌ probe failed: ${e.javaClass.simpleName}: ${e.message}")
                }
                sb.toString()
            }
            LiveDebug.log("LiveDebug", "model probe: ${probeResult.lines().firstOrNull()}")
            refreshNow()
        }
    }

    private fun testTap() {
        val svc = JarvisAccessibilityService.instance
        if (svc == null) {
            LiveDebug.set(LiveDebug.ACCESSIBILITY_ACTION, false, "service not connected - cannot tap")
            refreshNow(); return
        }
        val loc = IntArray(2)
        tapTarget.getLocationOnScreen(loc)
        val x = loc[0] + tapTarget.width / 2f
        val y = loc[1] + tapTarget.height / 2f
        tapRequestedAt = System.currentTimeMillis()
        LiveDebug.log("LiveDebug", "TEST TAP requested at ($x,$y)")
        tapTarget.postDelayed({ svc.clickAt(x, y) }, 300)
        tapTarget.postDelayed({ refreshNow() }, 2800)
    }

    private fun copyAll() {
        val sb = StringBuilder("JARVIS LIVE DEBUG ${java.util.Date()}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}\n\n")
        collectRows().forEach { r -> sb.append(when (r.ok) { true -> "✅"; false -> "❌"; null -> "⏳" }).append(' ').append(r.label).append(": ").append(r.detail).append('\n') }
        sb.append("\n").append(probeResult).append("\n\n").append(logView.text)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("jarvis-debug", sb.toString()))
        Toast.makeText(this, "Debug report copied", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- helpers

    private fun openSafely(intent: Intent) {
        try { startActivity(intent) } catch (e: Exception) {
            LiveDebug.log("LiveDebug", "cannot open ${intent.action}: ${e.message}", isError = true)
        }
    }

    private fun ago(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000
        return if (s < 60) "${s}s ago" else "${s / 60}m ago"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 11f; setTextColor(bg); isAllCaps = true
        background = rounded(cyan)
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { setMargins(0, dp(8), dp(6), dp(8)) }
    }

    private fun section(title: String) = TextView(this).apply {
        text = title; setTextColor(cyan); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun mono() = TextView(this).apply {
        typeface = Typeface.MONOSPACE; textSize = 10f; setTextColor(textDim)
        setTextIsSelectable(true)
        gravity = Gravity.START
        background = rounded(card)
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
}
