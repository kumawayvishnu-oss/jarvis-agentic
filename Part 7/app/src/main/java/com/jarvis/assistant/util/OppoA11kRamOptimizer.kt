package com.jarvis.assistant.util

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Dedicated Hardware & RAM Optimizer tailored for OPPO A11k (CPH2071 / MediaTek Helio P35 / PowerVR GE8320)
 * and 2GB/3GB/4GB RAM Android devices.
 *
 * Design Invariant:
 * - 100% of JARVIS features remain active and fast.
 * - Eliminates duplicate OkHttp thread/connection pools, caps WebView Chromium native memory,
 *   avoid offscreen hardware layer bitmap bloat, and reclaims idle heap on memory pressure.
 */
object OppoA11kRamOptimizer {

    private const val TAG = "OppoA11kOptimizer"

    /**
     * Shared ConnectionPool & Dispatcher across AI routers, search clients, and shell polyfills
     * so we don't spawn 5 separate OkHttp thread pools on a 2GB/3GB/4GB RAM phone.
     */
    val sharedConnectionPool = ConnectionPool(3, 15, TimeUnit.SECONDS)

    val sharedDispatcher = Dispatcher().apply {
        maxRequests = 16
        maxRequestsPerHost = 6
    }

    val sharedBaseHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .dispatcher(sharedDispatcher)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Detects whether the device is an OPPO A11k (CPH2071) or a constrained <= 4GB RAM device.
     */
    fun isOppoA11kOrLowRamDevice(context: Context): Boolean {
        val model = "${Build.MANUFACTURER} ${Build.MODEL} ${Build.DEVICE}".lowercase()
        if (model.contains("a11k") || model.contains("cph2071") || model.contains("oppo")) {
            return true
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        return am?.isLowRamDevice == true || totalMb <= 4200L
    }

    data class RamTelemetrySnapshot(
        val deviceModel: String,
        val isOppoOptimizedProfile: Boolean,
        val availableRamMb: Long,
        val totalRamMb: Long,
        val usedRamPercent: Int,
        val jvmUsedMb: Long,
        val jvmMaxMb: Long,
        val activeThreads: Int,
        val isLowMemory: Boolean
    )

    fun captureTelemetry(context: Context): RamTelemetrySnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val availMb = (memInfo.availMem / (1024 * 1024)).coerceAtLeast(1L)
        val totalMb = (memInfo.totalMem / (1024 * 1024)).coerceAtLeast(1L)
        val usedPercent = (((totalMb - availMb) * 100L) / totalMb).toInt().coerceIn(1, 99)

        val rt = Runtime.getRuntime()
        val jvmUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val jvmMaxMb = (rt.maxMemory() / (1024 * 1024)).coerceAtLeast(1L)
        val threads = Thread.activeCount()

        return RamTelemetrySnapshot(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            isOppoOptimizedProfile = isOppoA11kOrLowRamDevice(context),
            availableRamMb = availMb,
            totalRamMb = totalMb,
            usedRamPercent = usedPercent,
            jvmUsedMb = jvmUsedMb,
            jvmMaxMb = jvmMaxMb,
            activeThreads = threads,
            isLowMemory = memInfo.lowMemory || availMb < 380L
        )
    }

    /**
     * Tunes an Android WebView so Chromium uses minimal native RAM on OPPO A11k / PowerVR GE8320
     * without losing 60fps rendering or WebGL capability.
     */
    fun optimizeWebViewForLowRam(webView: WebView, isLocalAssetView: Boolean = true) {
        try {
            webView.settings.apply {
                if (isLocalAssetView) {
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                databaseEnabled = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
            }
            // Avoid double offscreen bitmap allocation; let SurfaceFlinger composite directly
            webView.setLayerType(View.LAYER_TYPE_NONE, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebView optimization note: ${e.message}")
        }
    }

    /**
     * Hibernates a WebView when its Fragment/Dialog is hidden so it consumes ~0% CPU and minimal GPU RAM.
     */
    fun hibernateWebView(webView: WebView?) {
        if (webView == null) return
        try {
            webView.onPause()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Wakes a hibernated WebView when its Fragment/Dialog becomes visible again.
     */
    fun wakeWebView(webView: WebView?) {
        if (webView == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }
            webView.onResume()
        } catch (_: Exception) {
        }
    }

    /**
     * Reclaims idle connections, trims ring buffers, and compacts JVM heap without interrupting active tasks.
     */
    fun performSmartRamTrim(context: Context, level: Int = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW): String {
        val before = captureTelemetry(context)
        try {
            sharedConnectionPool.evictAll()
            LiveDebug.trimToMaxEntries(30)
            System.runFinalization()
            System.gc()
        } catch (e: Exception) {
            Log.w(TAG, "RAM trim warning: ${e.message}")
        }
        val after = captureTelemetry(context)
        val freedJvm = (before.jvmUsedMb - after.jvmUsedMb).coerceAtLeast(0L)
        return "[RAM-OPTIMIZER // ${before.deviceModel}] Free System RAM: ${after.availableRamMb}MB / ${after.totalRamMb}MB | JVM Heap: ${after.jvmUsedMb}MB / ${after.jvmMaxMb}MB (Reclaimed ~${freedJvm}MB, Idle HTTP sockets evicted, Level=$level)"
    }
}
