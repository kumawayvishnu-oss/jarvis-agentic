package com.jarvis.assistant.agentic.shell

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log

/**
 * Receives asynchronous command execution results from Termux `RUN_COMMAND_SERVICE`
 * via PendingIntent and forwards stdout, stderr, and exitCode to [AgentShellEngine].
 */
class TermuxResultService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (intent != null) {
                val commandId = intent.getStringExtra(EXTRA_COMMAND_ID) ?: "termux_cmd"
                val resultBundle: Bundle? = intent.getBundleExtra(TERMUX_PLUGIN_RESULT_BUNDLE)
                if (resultBundle != null) {
                    val stdout = resultBundle.getString(EXTRA_STDOUT, "") ?: ""
                    val stderr = resultBundle.getString(EXTRA_STDERR, "") ?: ""
                    val exitCode = resultBundle.getInt(EXTRA_EXIT_CODE, 0)
                    val errmsg = resultBundle.getString(EXTRA_ERRMSG, "") ?: ""
                    val combinedErr = if (errmsg.isNotBlank() && stderr.isNotBlank()) {
                        "$stderr\n$errmsg"
                    } else if (errmsg.isNotBlank()) {
                        errmsg
                    } else {
                        stderr
                    }
                    AgentShellEngine.onTermuxCommandResult(
                        commandId = commandId,
                        stdout = stdout,
                        stderr = combinedErr,
                        exitCode = exitCode
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Termux result intent: ${e.message}", e)
        } finally {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "TermuxResultService"
        const val EXTRA_COMMAND_ID = "com.jarvis.assistant.TERMUX_COMMAND_ID"
        const val TERMUX_PLUGIN_RESULT_BUNDLE = "result"
        const val EXTRA_STDOUT = "stdout"
        const val EXTRA_STDERR = "stderr"
        const val EXTRA_EXIT_CODE = "exitCode"
        const val EXTRA_ERRMSG = "errmsg"
    }
}
