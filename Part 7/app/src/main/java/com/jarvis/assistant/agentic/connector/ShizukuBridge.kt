package com.jarvis.assistant.agentic.connector

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Result of a shell command execution.
 */
data class ShellResult(
    val isSuccessful: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

/**
 * Operational status of Shizuku on the host device.
 */
enum class ShizukuStatus {
    NOT_INSTALLED,
    INSTALLED_NOT_RUNNING,
    RUNNING_NO_PERMISSION,
    ACTIVE_AND_AUTHORIZED
}

/**
 * ShizukuBridge: Optional Shizuku IPC provider for elevated permissions
 * (input tap, keyevent, app force-stop, permission grants) without device root.
 *
 * Safely detects whether Shizuku is installed and running; if not, gracefully
 * falls back to the native Accessibility Service with zero crashes.
 */
object ShizukuBridge {
    private const val TAG = "ShizukuBridge"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val SHIZUKU_PROVIDER_URI = "content://moe.shizuku.privileged.api.provider"

    // Cached reflection references
    private var shizukuClass: Class<*>? = null
    private var pingBinderMethod: Method? = null
    private var checkSelfPermissionMethod: Method? = null
    private var newProcessMethod: Method? = null
    private var isReflectionInitialized = false

    init {
        initReflection()
    }

    private fun initReflection() {
        if (isReflectionInitialized) return
        try {
            shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            pingBinderMethod = shizukuClass?.getMethod("pingBinder")
            checkSelfPermissionMethod = shizukuClass?.getMethod("checkSelfPermission")
            newProcessMethod = shizukuClass?.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            Log.d(TAG, "Shizuku reflection initialized successfully.")
        } catch (_: ClassNotFoundException) {
            Log.d(TAG, "Shizuku SDK library not bundled in classpath; using ContentProvider and shell fallback.")
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku reflection initialization error: ${e.message}")
        } finally {
            isReflectionInitialized = true
        }
    }

    /**
     * Checks if the Shizuku Manager app is installed on the device.
     */
    fun isInstalled(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(SHIZUKU_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(SHIZUKU_PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.d(TAG, "Error checking Shizuku installation: ${e.message}")
            false
        }
    }

    /**
     * Checks if the Shizuku IPC service daemon is running.
     */
    fun isRunning(context: Context): Boolean {
        if (!isInstalled(context)) return false

        // 1. Check via reflection if SDK is loaded
        try {
            val ping = pingBinderMethod?.invoke(null) as? Boolean
            if (ping == true) return true
        } catch (e: Exception) {
            Log.d(TAG, "Shizuku.pingBinder failed: ${e.message}")
        }

        // 2. Check via ContentProvider call
        try {
            val uri = Uri.parse(SHIZUKU_PROVIDER_URI)
            val bundle = context.contentResolver.call(uri, "ping", null, null)
            if (bundle != null) return true
        } catch (e: Exception) {
            Log.d(TAG, "Shizuku provider ping failed: ${e.message}")
        }

        return false
    }

    /**
     * Checks if this app has been granted Shizuku authorization.
     */
    fun hasPermission(context: Context): Boolean {
        if (!isRunning(context)) return false

        try {
            val result = checkSelfPermissionMethod?.invoke(null) as? Int
            if (result == PackageManager.PERMISSION_GRANTED) return true
        } catch (e: Exception) {
            Log.d(TAG, "Shizuku.checkSelfPermission failed: ${e.message}")
        }

        return false
    }

    /**
     * Returns the comprehensive operational status of Shizuku.
     */
    fun getStatus(context: Context): ShizukuStatus {
        if (!isInstalled(context)) return ShizukuStatus.NOT_INSTALLED
        if (!isRunning(context)) return ShizukuStatus.INSTALLED_NOT_RUNNING
        if (!hasPermission(context)) return ShizukuStatus.RUNNING_NO_PERMISSION
        return ShizukuStatus.ACTIVE_AND_AUTHORIZED
    }

    /**
     * Executes a privileged shell command via Shizuku if available,
     * otherwise falls back to local Runtime exec without crashing.
     */
    fun executeShell(command: String, timeoutMs: Long = 5000L): ShellResult {
        // Attempt execution through Shizuku newProcess if active
        if (newProcessMethod != null) {
            try {
                val cmdArray = arrayOf("/system/bin/sh", "-c", command)
                val process = newProcessMethod?.invoke(null, cmdArray, null, null) as? Process
                if (process != null) {
                    val stdout = StringBuilder()
                    val stderr = StringBuilder()

                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val errReader = BufferedReader(InputStreamReader(process.errorStream))

                    val finished = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    } else {
                        process.waitFor()
                        true
                    }

                    reader.forEachLine { stdout.append(it).append("\n") }
                    errReader.forEachLine { stderr.append(it).append("\n") }

                    val exitCode = if (finished) process.exitValue() else -1
                    return ShellResult(
                        isSuccessful = exitCode == 0,
                        exitCode = exitCode,
                        stdout = stdout.toString().trim(),
                        stderr = stderr.toString().trim()
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Privileged execution via Shizuku failed: ${e.message}")
            }
        }

        // Local Runtime.getRuntime().exec fallback
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))

            val finished = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                process.waitFor()
                true
            }

            reader.forEachLine { stdout.append(it).append("\n") }
            errReader.forEachLine { stderr.append(it).append("\n") }

            val exitCode = if (finished) process.exitValue() else -1
            ShellResult(
                isSuccessful = exitCode == 0,
                exitCode = exitCode,
                stdout = stdout.toString().trim(),
                stderr = stderr.toString().trim()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Local shell execution fallback error: ${e.message}")
            ShellResult(
                isSuccessful = false,
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Execution failed"
            )
        }
    }

    // -------------------------------------------------------------
    // High-Level Elevated Actions with Zero-Crash Accessibility Fallback
    // -------------------------------------------------------------

    /**
     * Dispatches a tap event at screen coordinates.
     * Uses Shizuku shell 'input tap' if available; otherwise falls back to AccessibilityService.clickAt.
     */
    fun tap(x: Float, y: Float, context: Context? = null): Boolean {
        if (context != null && hasPermission(context)) {
            val res = executeShell("input tap ${x.toInt()} ${y.toInt()}")
            if (res.isSuccessful) return true
        }

        // Graceful Fallback: Native Accessibility Gesture
        val a11y = JarvisAccessibilityService.instance
        return a11y?.clickAt(x, y) ?: false
    }

    /**
     * Dispatches a swipe gesture between coordinates.
     * Uses Shizuku shell 'input swipe' if available; otherwise falls back to AccessibilityService.
     */
    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300L,
        context: Context? = null
    ): Boolean {
        if (context != null && hasPermission(context)) {
            val res = executeShell("input swipe ${startX.toInt()} ${startY.toInt()} ${endX.toInt()} ${endY.toInt()} $durationMs")
            if (res.isSuccessful) return true
        }

        // Graceful Fallback: Accessibility Smart Scroll / Gesture
        val a11y = JarvisAccessibilityService.instance
        return if (a11y != null) {
            val direction = if (endY < startY) "down" else "up"
            a11y.performSmartScroll(direction, "medium")
        } else {
            false
        }
    }

    /**
     * Dispatches an Android keyevent (e.g. Back = 4, Home = 3, Enter = 66, Power = 26).
     * Uses Shizuku shell 'input keyevent' if available; otherwise falls back to AccessibilityService global actions.
     */
    fun pressKey(keyCode: Int, context: Context? = null): Boolean {
        if (context != null && hasPermission(context)) {
            val res = executeShell("input keyevent $keyCode")
            if (res.isSuccessful) return true
        }

        // Graceful Fallback: Accessibility Global Action
        val a11y = JarvisAccessibilityService.instance ?: return false
        return when (keyCode) {
            4 -> a11y.performSystemGesture("back")
            3 -> a11y.performSystemGesture("home")
            187 -> a11y.performSystemGesture("recents")
            else -> false
        }
    }

    /**
     * Types text using shell 'input text' (or fallback to AccessibilityService.smartTypeText).
     */
    fun inputText(text: String, context: Context? = null): Boolean {
        if (text.isBlank()) return false

        if (context != null && hasPermission(context)) {
            // Escape special shell characters
            val escaped = text.replace(" ", "%s")
                .replace("&", "\\&")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("(", "\\(")
                .replace(")", "\\)")
            val res = executeShell("input text $escaped")
            if (res.isSuccessful) return true
        }

        // Graceful Fallback: Accessibility smart typing
        val a11y = JarvisAccessibilityService.instance
        return a11y?.smartTypeText(text) ?: false
    }

    /**
     * Force-stops a package instantly without root using 'am force-stop'.
     */
    fun forceStopApp(packageName: String, context: Context? = null): Boolean {
        if (packageName.isBlank()) return false

        if (context != null && hasPermission(context)) {
            val res = executeShell("am force-stop $packageName")
            if (res.isSuccessful) {
                Log.i(TAG, "Force stopped $packageName via Shizuku")
                return true
            }
        }

        // Fallback: If Shizuku is unavailable, return false gracefully
        Log.w(TAG, "Shizuku not available to force-stop $packageName")
        return false
    }

    /**
     * Grants a runtime permission to an application via 'pm grant'.
     */
    fun grantRuntimePermission(packageName: String, permission: String, context: Context? = null): Boolean {
        if (context != null && hasPermission(context)) {
            val res = executeShell("pm grant $packageName $permission")
            return res.isSuccessful
        }
        return false
    }
}
