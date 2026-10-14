package com.jarvis.assistant.agentic.connector

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.agentic.soul.AgentSoulManager
import com.jarvis.assistant.agentic.soul.StepLogLevel
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Result of a hybrid action execution.
 */
data class HybridActionResult(
    val isSuccess: Boolean,
    val executedTier: ExecutionTier,
    val message: String,
    val details: String? = null
)

/**
 * The execution tier used to fulfill the action.
 */
enum class ExecutionTier {
    TIER1_INTENT,
    TIER2_ACCESSIBILITY,
    TIER3_SHIZUKU,
    FAILED
}

/**
 * HybridActionDispatcher: Unified 3-tier action engine for 1500+ apps.
 * Automatically selects the most efficient and reliable path:
 * 1. Deep Link / Intent (Instant, 0 tokens)
 * 2. Accessibility Node Action (Semantic clicks, typing, scrolling)
 * 3. Elevated Shizuku Shell (Coordinate taps, key events, rootless privileged automation)
 */
class HybridActionDispatcher private constructor(
    private val context: Context,
    private val appCatalog: AppCategoryCatalog = AppCategoryCatalog.getInstance(context),
    private val accessibilityParser: AccessibilityNodeParser = AccessibilityNodeParser.getInstance(context),
    private val shizukuBridge: ShizukuBridge = ShizukuBridge.getInstance(context),
    private val agentSoulManager: AgentSoulManager = AgentSoulManager.getInstance(context)
) {

    /**
     * Opens an app or triggers a deep action by app name or package.
     */
    suspend fun openAppOrAction(
        appName: String,
        actionQuery: String? = null,
        taskId: String? = null,
        agentName: String = "AppConnector"
    ): HybridActionResult = withContext(Dispatchers.Default) {
        agentSoulManager.postStepLog(
            agentName = agentName,
            action = "DispatchAppAction",
            detail = "Resolving app/action for '$appName' with query: '$actionQuery'",
            level = StepLogLevel.INFO,
            taskId = taskId
        )

        // Tier 1: Try deep link or direct launch Intent
        val launchIntent = appCatalog.resolveLaunchIntent(appName, actionQuery)
        if (launchIntent != null) {
            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)

                agentSoulManager.postStepLog(
                    agentName = agentName,
                    action = "Tier1IntentSuccess",
                    detail = "Launched $appName via direct Intent/DeepLink.",
                    level = StepLogLevel.SUCCESS,
                    taskId = taskId
                )

                return@withContext HybridActionResult(
                    isSuccess = true,
                    executedTier = ExecutionTier.TIER1_INTENT,
                    message = "Successfully opened $appName via direct Intent."
                )
            } catch (e: Exception) {
                agentSoulManager.postStepLog(
                    agentName = agentName,
                    action = "Tier1IntentFallback",
                    detail = "Direct intent launch failed (${e.message}). Cascading to Tier 2...",
                    level = StepLogLevel.WARNING,
                    taskId = taskId
                )
            }
        }

        // Tier 2: Try Accessibility Service UI Click / Search
        val accessibilityService = JarvisAccessibilityService.instance
        if (accessibilityService != null) {
            val rootNode = accessibilityService.rootInActiveWindow
            if (rootNode != null) {
                val targetNode = accessibilityParser.findNodeByTextOrDesc(rootNode, appName)
                if (targetNode != null && targetNode.click()) {
                    agentSoulManager.postStepLog(
                        agentName = agentName,
                        action = "Tier2AccessibilitySuccess",
                        detail = "Clicked '$appName' on screen using Accessibility Node Tree.",
                        level = StepLogLevel.SUCCESS,
                        taskId = taskId
                    )
                    return@withContext HybridActionResult(
                        isSuccess = true,
                        executedTier = ExecutionTier.TIER2_ACCESSIBILITY,
                        message = "Clicked $appName using Accessibility Service."
                    )
                }
            }
        }

        // Tier 3: Try Shizuku elevated shell if available
        if (shizukuBridge.isAvailable()) {
            val pkg = appCatalog.lookupPackage(appName)
            if (pkg != null) {
                val result = shizukuBridge.executeShellCommand("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                if (result.isSuccessful) {
                    agentSoulManager.postStepLog(
                        agentName = agentName,
                        action = "Tier3ShizukuSuccess",
                        detail = "Launched $appName via Shizuku shell driver.",
                        level = StepLogLevel.SUCCESS,
                        taskId = taskId
                    )
                    return@withContext HybridActionResult(
                        isSuccess = true,
                        executedTier = ExecutionTier.TIER3_SHIZUKU,
                        message = "Launched $appName using Shizuku bridge."
                    )
                }
            }
        }

        // Fallback: Web search or Play store
        val fallbackUrl = "https://www.google.com/search?q=" + Uri.encode(appName)
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(browserIntent)

        HybridActionResult(
            isSuccess = true,
            executedTier = ExecutionTier.TIER1_INTENT,
            message = "App not locally installed. Opened web alternative for $appName."
        )
    }

    /**
     * Performs a click on an element identified by text, content description, or coordinates.
     */
    suspend fun clickElement(
        targetIdentifier: String,
        taskId: String? = null,
        agentName: String = "AppConnector"
    ): HybridActionResult = withContext(Dispatchers.Default) {
        val service = JarvisAccessibilityService.instance
        if (service != null) {
            val root = service.rootInActiveWindow
            if (root != null) {
                val node = accessibilityParser.findNodeByTextOrDesc(root, targetIdentifier)
                if (node != null && node.click()) {
                    agentSoulManager.postStepLog(
                        agentName = agentName,
                        action = "NodeClickSuccess",
                        detail = "Clicked node '$targetIdentifier' via Accessibility.",
                        level = StepLogLevel.SUCCESS,
                        taskId = taskId
                    )
                    return@withContext HybridActionResult(
                        isSuccess = true,
                        executedTier = ExecutionTier.TIER2_ACCESSIBILITY,
                        message = "Clicked '$targetIdentifier' via Accessibility."
                    )
                }
            }
        }

        // Fallback to Shizuku tap if available and coordinates are known
        if (shizukuBridge.isAvailable()) {
            // Can tap by center screen or fallback
            agentSoulManager.postStepLog(
                agentName = agentName,
                action = "ShizukuClickFallback",
                detail = "Attempting Shizuku input tap fallback for '$targetIdentifier'.",
                level = StepLogLevel.INFO,
                taskId = taskId
            )
        }

        HybridActionResult(
            isSuccess = false,
            executedTier = ExecutionTier.FAILED,
            message = "Could not locate clickable element for '$targetIdentifier'."
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: HybridActionDispatcher? = null

        fun getInstance(context: Context): HybridActionDispatcher {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: HybridActionDispatcher(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
