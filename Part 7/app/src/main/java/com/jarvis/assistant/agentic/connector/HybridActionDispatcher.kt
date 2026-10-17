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
        val launchIntent = appCatalog.resolveLaunchIntent(appName, actionQuery, context)
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
            try {
                val rootNode = accessibilityService.getActiveRootNode() ?: accessibilityService.rootInActiveWindow
                if (rootNode != null) {
                    val targetNode = accessibilityParser.findNodeByTextOrDesc(rootNode, appName)
                    if (targetNode != null && (accessibilityService.clickNodeAndTap(targetNode) || targetNode.click())) {
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
            } catch (e: Exception) {
                agentSoulManager.postStepLog(
                    agentName = agentName,
                    action = "Tier2AccessibilityError",
                    detail = "Accessibility click error: ${e.message}",
                    level = StepLogLevel.WARNING,
                    taskId = taskId
                )
            }
        }

        // Tier 3: Try Shizuku elevated shell if available
        if (shizukuBridge.isAvailable(context)) {
            val pkg = appCatalog.lookupPackage(appName, context)
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

        // Fallback: Anonymous background research via FreeLiveSearchClient (NEVER pop open Chrome with user's Google ID)
        return@withContext try {
            val bgResults = com.jarvis.assistant.agentic.search.FreeLiveSearchClient().search(appName, maxResults = 3)
            if (bgResults.isNotEmpty()) {
                val summary = bgResults.joinToString("\n") { "• ${it.title}: ${it.snippet}" }
                HybridActionResult(
                    isSuccess = true,
                    executedTier = ExecutionTier.TIER1_INTENT,
                    message = "App '$appName' is not installed locally. Background Web Research:\n$summary"
                )
            } else {
                HybridActionResult(
                    isSuccess = false,
                    executedTier = ExecutionTier.FAILED,
                    message = "App '$appName' is not installed on this device."
                )
            }
        } catch (e: Exception) {
            HybridActionResult(
                isSuccess = false,
                executedTier = ExecutionTier.FAILED,
                message = "Could not open $appName: ${e.message}"
            )
        }
    }

    /**
     * Performs a click on an element identified by text, content description, or coordinates.
     */
    suspend fun clickElement(
        targetIdentifier: String,
        taskId: String? = null,
        agentName: String = "AppConnector"
    ): HybridActionResult = withContext(Dispatchers.Default) {
        // If coordinate format "x,y" or "x, y"
        val coordMatch = Regex("""^\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*$""").find(targetIdentifier)
        if (coordMatch != null) {
            val x = coordMatch.groupValues[1].toFloatOrNull()
            val y = coordMatch.groupValues[2].toFloatOrNull()
            if (x != null && y != null) {
                return@withContext tapCoordinates(x, y, taskId, agentName)
            }
        }

        // Tier 2: Accessibility
        val service = JarvisAccessibilityService.instance
        if (service != null) {
            try {
                val root = service.getActiveRootNode() ?: service.rootInActiveWindow
                if (root != null) {
                    val node = accessibilityParser.findNodeByTextOrDesc(root, targetIdentifier)
                    if (node != null && (service.clickNodeAndTap(node) || node.click())) {
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
            } catch (e: Exception) {
                agentSoulManager.postStepLog(
                    agentName = agentName,
                    action = "AccessibilityClickError",
                    detail = "Error clicking node: ${e.message}",
                    level = StepLogLevel.WARNING,
                    taskId = taskId
                )
            }
        }

        // Tier 3: Shizuku coordinate tap fallback if node bounds can be located
        if (shizukuBridge.isAvailable(context)) {
            try {
                val root = service?.getActiveRootNode() ?: service?.rootInActiveWindow
                val node = root?.let { accessibilityParser.findNodeByTextOrDesc(it, targetIdentifier) }
                if (node != null) {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        val cx = bounds.centerX().toFloat()
                        val cy = bounds.centerY().toFloat()
                        if (shizukuBridge.tap(cx, cy, context)) {
                            agentSoulManager.postStepLog(
                                agentName = agentName,
                                action = "Tier3ShizukuTapSuccess",
                                detail = "Tapped element '$targetIdentifier' at ($cx, $cy) via Shizuku.",
                                level = StepLogLevel.SUCCESS,
                                taskId = taskId
                            )
                            return@withContext HybridActionResult(
                                isSuccess = true,
                                executedTier = ExecutionTier.TIER3_SHIZUKU,
                                message = "Tapped element '$targetIdentifier' at ($cx, $cy) via Shizuku."
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                agentSoulManager.postStepLog(
                    agentName = agentName,
                    action = "ShizukuClickError",
                    detail = "Shizuku tap error: ${e.message}",
                    level = StepLogLevel.WARNING,
                    taskId = taskId
                )
            }
        }

        agentSoulManager.postStepLog(
            agentName = agentName,
            action = "ClickElementFailed",
            detail = "Could not locate or click '$targetIdentifier'.",
            level = StepLogLevel.WARNING,
            taskId = taskId
        )

        HybridActionResult(
            isSuccess = false,
            executedTier = ExecutionTier.FAILED,
            message = "Could not locate clickable element for '$targetIdentifier'."
        )
    }

    /**
     * Taps at specific screen coordinates (x, y).
     */
    suspend fun tapCoordinates(
        x: Float,
        y: Float,
        taskId: String? = null,
        agentName: String = "AppConnector"
    ): HybridActionResult = withContext(Dispatchers.Default) {
        val service = JarvisAccessibilityService.instance
        if (service != null && service.clickAt(x, y)) {
            agentSoulManager.postStepLog(
                agentName = agentName,
                action = "CoordinateClickSuccess",
                detail = "Tapped at ($x, $y) via Accessibility.",
                level = StepLogLevel.SUCCESS,
                taskId = taskId
            )
            return@withContext HybridActionResult(
                isSuccess = true,
                executedTier = ExecutionTier.TIER2_ACCESSIBILITY,
                message = "Tapped at ($x, $y) via Accessibility."
            )
        }

        if (shizukuBridge.isAvailable(context) && shizukuBridge.tap(x, y, context)) {
            agentSoulManager.postStepLog(
                agentName = agentName,
                action = "ShizukuTapSuccess",
                detail = "Tapped at ($x, $y) via Shizuku shell.",
                level = StepLogLevel.SUCCESS,
                taskId = taskId
            )
            return@withContext HybridActionResult(
                isSuccess = true,
                executedTier = ExecutionTier.TIER3_SHIZUKU,
                message = "Tapped at ($x, $y) via Shizuku."
            )
        }

        HybridActionResult(
            isSuccess = false,
            executedTier = ExecutionTier.FAILED,
            message = "Failed to tap at coordinates ($x, $y). Accessibility and Shizuku were unavailable."
        )
    }

    /**
     * Types text into the focused field or target element.
     */
    suspend fun typeText(
        text: String,
        targetIdentifier: String? = null,
        taskId: String? = null,
        agentName: String = "AppConnector"
    ): HybridActionResult = withContext(Dispatchers.Default) {
        val service = JarvisAccessibilityService.instance

        if (targetIdentifier != null && service != null) {
            val root = service.getActiveRootNode() ?: service.rootInActiveWindow
            val target = root?.let { accessibilityParser.findNodeByTextOrDesc(it, targetIdentifier) }
            target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
        }

        if (service != null && service.smartTypeText(text)) {
            agentSoulManager.postStepLog(
                agentName = agentName,
                action = "AccessibilityTypeTextSuccess",
                detail = "Typed text '$text' via Accessibility.",
                level = StepLogLevel.SUCCESS,
                taskId = taskId
            )
            return@withContext HybridActionResult(
                isSuccess = true,
                executedTier = ExecutionTier.TIER2_ACCESSIBILITY,
                message = "Typed '$text' via Accessibility."
            )
        }

        if (shizukuBridge.isAvailable(context) && shizukuBridge.inputText(text, context)) {
            agentSoulManager.postStepLog(
                agentName = agentName,
                action = "ShizukuTypeTextSuccess",
                detail = "Typed text via Shizuku shell.",
                level = StepLogLevel.SUCCESS,
                taskId = taskId
            )
            return@withContext HybridActionResult(
                isSuccess = true,
                executedTier = ExecutionTier.TIER3_SHIZUKU,
                message = "Typed '$text' via Shizuku."
            )
        }

        HybridActionResult(
            isSuccess = false,
            executedTier = ExecutionTier.FAILED,
            message = "Failed to type text. Neither Accessibility nor Shizuku available."
        )
    }

    /**
     * Performs a scroll in the specified direction ("up", "down", "left", "right").
     */
    suspend fun performScroll(
        direction: String,
        distance: String = "medium",
        taskId: String? = null,
        agentName: String = "AppConnector"
    ): HybridActionResult = withContext(Dispatchers.Default) {
        val service = JarvisAccessibilityService.instance
        if (service != null && service.performSmartScroll(direction, distance)) {
            return@withContext HybridActionResult(
                isSuccess = true,
                executedTier = ExecutionTier.TIER2_ACCESSIBILITY,
                message = "Scrolled $direction ($distance) via Accessibility."
            )
        }

        if (shizukuBridge.isAvailable(context)) {
            val startY = if (direction.equals("down", ignoreCase = true)) 1200f else 600f
            val endY = if (direction.equals("down", ignoreCase = true)) 600f else 1200f
            if (shizukuBridge.swipe(540f, startY, 540f, endY, 300L, context)) {
                return@withContext HybridActionResult(
                    isSuccess = true,
                    executedTier = ExecutionTier.TIER3_SHIZUKU,
                    message = "Scrolled $direction via Shizuku swipe."
                )
            }
        }

        HybridActionResult(
            isSuccess = false,
            executedTier = ExecutionTier.FAILED,
            message = "Could not perform scroll $direction."
        )
    }

    /**
     * Presses an Android hardware / system key (e.g. Back=4, Home=3, Recents=187).
     */
    suspend fun pressKey(
        keyCode: Int,
        taskId: String? = null,
        agentName: String = "AppConnector"
    ): HybridActionResult = withContext(Dispatchers.Default) {
        if (shizukuBridge.isAvailable(context) && shizukuBridge.pressKey(keyCode, context)) {
            return@withContext HybridActionResult(
                isSuccess = true,
                executedTier = ExecutionTier.TIER3_SHIZUKU,
                message = "Pressed keycode $keyCode via Shizuku."
            )
        }

        val service = JarvisAccessibilityService.instance
        if (service != null) {
            val actionSuccess = when (keyCode) {
                4 -> service.performSystemGesture("back")
                3 -> service.performSystemGesture("home")
                187 -> service.performSystemGesture("recents")
                else -> false
            }
            if (actionSuccess) {
                return@withContext HybridActionResult(
                    isSuccess = true,
                    executedTier = ExecutionTier.TIER2_ACCESSIBILITY,
                    message = "Dispatched system action for keycode $keyCode via Accessibility."
                )
            }
        }

        HybridActionResult(
            isSuccess = false,
            executedTier = ExecutionTier.FAILED,
            message = "Could not press keycode $keyCode."
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
