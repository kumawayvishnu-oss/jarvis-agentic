package com.jarvis.assistant.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide live debug state used by LiveDebugActivity.
 *
 * Every pipeline stage (mic -> WebSocket -> Gemini -> playback -> tools -> accessibility)
 * reports its real state here, so the user can screenshot exactly where the chain breaks.
 * Nothing here is simulated: values only change when the real code path runs.
 */
object LiveDebug {

    data class Status(val ok: Boolean?, val detail: String, val at: Long)

    // Stage keys (stable; used by LiveDebugActivity)
    const val MIC_RECORDER = "mic_recorder"
    const val WS_STATE = "ws_state"
    const val WS_SETUP = "ws_setup"
    const val WS_CLOSE = "ws_close"
    const val MODEL = "model"
    const val API_ERROR = "api_error"
    const val AUDIO_IN = "audio_in"
    const val PLAYBACK = "playback"
    const val TOOL_CALL = "tool_call"
    const val ACCESSIBILITY = "accessibility"
    const val ACCESSIBILITY_ACTION = "accessibility_action"

    private val statuses = ConcurrentHashMap<String, Status>()

    // Counters
    val micFramesRead = AtomicLong(0)
    val audioFramesSent = AtomicLong(0)
    val audioFramesDropped = AtomicLong(0)
    val audioChunksReceived = AtomicLong(0)
    val audioBytesReceived = AtomicLong(0)
    val playbackWrites = AtomicLong(0)
    val playbackWriteErrors = AtomicLong(0)
    val toolCalls = AtomicLong(0)
    val serverMessages = AtomicLong(0)

    @Volatile var lastDropReason: String = ""

    private const val MAX_LINES = 80
    private val ring = ArrayDeque<String>(MAX_LINES)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun set(key: String, ok: Boolean?, detail: String) {
        statuses[key] = Status(ok, detail, System.currentTimeMillis())
    }

    fun get(key: String): Status? = statuses[key]

    /** Logs to logcat AND the in-app ring buffer. */
    fun log(tag: String, msg: String, isError: Boolean = false) {
        if (isError) Log.e(tag, msg) else Log.i(tag, msg)
        val line = "${timeFmt.format(Date())} ${if (isError) "E" else "I"}/$tag: $msg"
        synchronized(ring) {
            if (ring.size >= MAX_LINES) ring.pollFirst()
            ring.addLast(line)
        }
    }

    fun trimToMaxEntries(max: Int = 30) {
        synchronized(ring) {
            while (ring.size > max.coerceAtLeast(10)) {
                ring.pollFirst()
            }
        }
    }

    fun recentLines(max: Int = 80): List<String> = synchronized(ring) {
        ring.toList().takeLast(max)
    }

    /**
     * Reads this process's own logcat (allowed without READ_LOGS for own pid).
     * Falls back to the in-app ring buffer if logcat is unavailable.
     */
    fun readOwnLogcat(lines: Int = 80): List<String> {
        return try {
            val pid = android.os.Process.myPid()
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", lines.toString(), "--pid=$pid"))
            val out = proc.inputStream.bufferedReader().readLines()
            proc.destroy()
            if (out.isEmpty()) recentLines(lines) else out.takeLast(lines)
        } catch (e: Exception) {
            Log.w("LiveDebug", "logcat read failed: ${e.message}")
            recentLines(lines)
        }
    }

    fun resetCounters() {
        listOf(micFramesRead, audioFramesSent, audioFramesDropped, audioChunksReceived,
            audioBytesReceived, playbackWrites, playbackWriteErrors, toolCalls, serverMessages).forEach { it.set(0) }
        lastDropReason = ""
    }
}
