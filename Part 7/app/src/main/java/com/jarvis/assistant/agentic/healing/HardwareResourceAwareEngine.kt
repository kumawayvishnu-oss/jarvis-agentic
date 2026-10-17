package com.jarvis.assistant.agentic.healing

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.StatFs
import com.jarvis.assistant.agentic.router.ModelProvider

/**
 * Hardware & Resource-Aware Decision Engine + Safety Confirmation Gate.
 * - Monitors real-time RAM, storage space, and network connectivity.
 * - Always prioritizes FREE tools (DuckDuckGo Live Search, Direct Intents, Offline Vosk) before paid APIs.
 * - Enforces explicit user confirmation gates for sensitive actions (sending messages, payments, deleting data).
 */
data class DeviceResourceStatus(
    val availableRamMb: Long,
    val totalRamMb: Long,
    val isLowMemory: Boolean,
    val freeStorageMb: Long,
    val hasInternet: Boolean,
    val isUnmeteredWifi: Boolean
)

enum class SelectedExecutionStrategy {
    OFFLINE_LOCAL_ONLY,
    FREE_SEARCH_AND_INTENTS_FIRST,
    ULTRA_FAST_GROQ_LPU,
    FULL_MULTIMODAL_GEMINI
}

class HardwareResourceAwareEngine private constructor(private val context: Context) {

    companion object {
        private val PAYMENT_BLOCKED_PACKAGES = setOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app", // PhonePe
            "net.one97.paytm", // Paytm
            "in.org.npci.upiapp", // BHIM
            "com.sbi.lotusintouch" // YONO SBI
        )

        @Volatile
        private var INSTANCE: HardwareResourceAwareEngine? = null

        fun getInstance(context: Context): HardwareResourceAwareEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: HardwareResourceAwareEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Alias for getDeviceResourceStatus() used by probabilistic planner & SOUL research loop.
     */
    fun inspectHardware(): DeviceResourceStatus = getDeviceResourceStatus()

    /**
     * Inspects live phone RAM, storage, and internet state.
     */
    fun getDeviceResourceStatus(): DeviceResourceStatus {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024 * 1024)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val lowMem = memInfo.lowMemory || availMb < 350L

        val freeStorageMb = try {
            val stat = StatFs(Environment.getDataDirectory().path)
            (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
        } catch (_: Exception) {
            1024L
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = cm?.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val hasNet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        return DeviceResourceStatus(
            availableRamMb = availMb,
            totalRamMb = totalMb,
            isLowMemory = lowMem,
            freeStorageMb = freeStorageMb,
            hasInternet = hasNet,
            isUnmeteredWifi = isWifi
        )
    }

    /**
     * Selects the best execution strategy prioritizing FREE tools first and respecting phone resources.
     */
    fun chooseOptimalStrategy(goal: String): SelectedExecutionStrategy {
        val status = getDeviceResourceStatus()
        if (!status.hasInternet) {
            return SelectedExecutionStrategy.OFFLINE_LOCAL_ONLY
        }
        val lower = goal.lowercase()
        // Always prioritize zero-cost free tools (Intents / DuckDuckGo) when applicable
        if (lower.startsWith("open ") || lower.contains("kholo") ||
            lower.contains("news") || lower.contains("weather") ||
            lower.contains("mausam") || lower.contains("khabar")
        ) {
            return SelectedExecutionStrategy.FREE_SEARCH_AND_INTENTS_FIRST
        }
        if (status.isLowMemory || !status.isUnmeteredWifi) {
            return SelectedExecutionStrategy.ULTRA_FAST_GROQ_LPU
        }
        return SelectedExecutionStrategy.FULL_MULTIMODAL_GEMINI
    }

    /**
     * Picks the best starting LLM provider based on network and RAM state.
     */
    fun recommendProvider(): ModelProvider {
        val status = getDeviceResourceStatus()
        if (!status.hasInternet) return ModelProvider.OLLAMA_LOCAL
        return if (status.isLowMemory) ModelProvider.GROQ else ModelProvider.GEMINI
    }

    /**
     * Strict Safety Gate (if/else):
     * Checks if a goal involves sending external messages, payments/banking, or deleting data.
     */
    fun evaluateSafetyGate(goal: String, targetPackage: String? = null): SafetyVerdict {
        val lower = goal.lowercase()

        // 1. Hard block automated clicks inside banking/UPI PIN screens
        if (targetPackage != null && PAYMENT_BLOCKED_PACKAGES.contains(targetPackage)) {
            return SafetyVerdict(
                allowedImmediately = false,
                requiresConfirmation = true,
                reason = "सुरक्षा नियम: बैंकिंग/पेमेंट ऐप ($targetPackage) में पासवर्ड या पेमेंट ऑटोमेशन प्रतिबंधित है। कृपया स्वयं पुष्टि करें।"
            )
        }

        if (lower.contains("pay ") || lower.contains("transfer money") || lower.contains("upi") || lower.contains("paisa bhejo")) {
            return SafetyVerdict(
                allowedImmediately = false,
                requiresConfirmation = true,
                reason = "सुरक्षा पुष्टि आवश्यक: वित्तीय भुगतान (Payment) से पहले आपकी अनुमति अनिवार्य है।"
            )
        }

        if (lower.contains("delete ") || lower.contains("wipe") || lower.contains("clear all") || lower.contains("hatao") || lower.contains("mita do")) {
            return SafetyVerdict(
                allowedImmediately = false,
                requiresConfirmation = true,
                reason = "सुरक्षा पुष्टि आवश्यक: डेटा या संदेश हटाने (Delete) से पहले आपकी पुष्टि ज़रूरी है।"
            )
        }

        if (lower.contains("send message") || lower.contains("whatsapp bhejo") || lower.contains("sms bhejo")) {
            return SafetyVerdict(
                allowedImmediately = false,
                requiresConfirmation = true,
                reason = "सुरक्षा पुष्टि आवश्यक: बाहरी संदेश भेजने से पहले कृपया पुष्टि करें।"
            )
        }

        return SafetyVerdict(allowedImmediately = true, requiresConfirmation = false, reason = "Safe")
    }
}

data class SafetyVerdict(
    val allowedImmediately: Boolean,
    val requiresConfirmation: Boolean,
    val reason: String
)
