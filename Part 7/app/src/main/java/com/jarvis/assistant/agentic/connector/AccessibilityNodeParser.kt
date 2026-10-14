package com.jarvis.assistant.agentic.connector

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Clean, lightweight semantic representation of an Android Accessibility Node.
 */
data class SemanticNode(
    val index: Int,
    val role: String,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val bounds: Rect,
    val centerX: Int = bounds.centerX(),
    val centerY: Int = bounds.centerY(),
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isEnabled: Boolean = true,
    val isFocused: Boolean = false,
    val isVisible: Boolean = true,
    val nodeInfo: AccessibilityNodeInfo? = null,
    val children: MutableList<SemanticNode> = mutableListOf()
) {
    /**
     * Compact string representation for LLM prompt ingestion.
     */
    fun toLlmLine(): String {
        val parts = mutableListOf<String>()
        parts.add("[$index]")
        parts.add(role)

        if (!text.isNullOrBlank()) {
            val sanitized = text.replace("\n", " ").trim()
            val truncated = if (sanitized.length > 50) sanitized.take(47) + "..." else sanitized
            parts.add("text=\"$truncated\"")
        }

        if (!contentDescription.isNullOrBlank() && contentDescription != text) {
            val sanitized = contentDescription.replace("\n", " ").trim()
            val truncated = if (sanitized.length > 40) sanitized.take(37) + "..." else sanitized
            parts.add("desc=\"$truncated\"")
        }

        if (!viewId.isNullOrBlank()) {
            val shortId = if (viewId.contains(":id/")) viewId.substringAfter(":id/") else viewId
            parts.add("id=\"$shortId\"")
        }

        parts.add("[${bounds.left}, ${bounds.top}, ${bounds.right}, ${bounds.bottom}]")

        val stateTags = mutableListOf<String>()
        if (isClickable) stateTags.add("clickable")
        if (isEditable) stateTags.add("editable")
        if (isScrollable) stateTags.add("scrollable")
        if (isCheckable) stateTags.add(if (isChecked) "checked" else "unchecked")
        if (isFocused) stateTags.add("focused")
        if (!isEnabled) stateTags.add("disabled")

        if (stateTags.isNotEmpty()) {
            parts.add("(${stateTags.joinToString(",")})")
        }

        return parts.joinToString(" ")
    }
}

/**
 * Container holding the parsed UI tree state and fast lookup indices.
 */
data class CompactUiTree(
    val packageName: String,
    val screenTitle: String? = null,
    val nodes: List<SemanticNode> = emptyList(),
    val nodeIndexMap: Map<Int, SemanticNode> = emptyMap(),
    val rawElementCount: Int = 0
) {
    /**
     * Generates an ultra-compact formatted string representing the screen state for LLMs.
     * Consumes minimal tokens (approx 100-300 tokens) with zero video/screenshot streaming.
     */
    fun toFormattedLlmTree(maxNodes: Int = 80): String {
        val sb = StringBuilder()
        sb.append("=== Screen: $packageName")
        if (!screenTitle.isNullOrBlank()) {
            sb.append(" (\"$screenTitle\")")
        }
        sb.append(" | Actionable Elements: ${nodes.size} ===\n")

        val displayNodes = nodes.take(maxNodes)
        for (node in displayNodes) {
            sb.append(node.toLlmLine()).append("\n")
        }

        if (nodes.size > maxNodes) {
            sb.append("... [${nodes.size - maxNodes} more elements omitted]\n")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Finds a node by its sequential flat index.
     */
    fun getNode(index: Int): SemanticNode? = nodeIndexMap[index]
}

/**
 * AccessibilityNodeParser: High-performance parser that crawls the Android
 * AccessibilityNodeInfo hierarchy to locate elements and generates compact semantic
 * UI representations for LLM decision making.
 */
object AccessibilityNodeParser {
    private const val TAG = "AccessibilityNodeParser"

    fun getInstance(context: Context? = null): AccessibilityNodeParser = this

    fun findNodeByTextOrDesc(root: AccessibilityNodeInfo?, textQuery: String): AccessibilityNodeInfo? {
        if (root == null || textQuery.isBlank()) return null
        return findByQuery(root, textQuery)
    }

    fun parseNodeHierarchy(root: AccessibilityNodeInfo?, maxNodes: Int = 120): List<SemanticNode> {
        if (root == null) return emptyList()
        return parseTree(root, maxNodes).nodes
    }

    fun formatForPrompt(root: AccessibilityNodeInfo?, maxNodes: Int = 80): String {
        if (root == null) return "Screen is empty or inaccessible"
        return parseTree(root, maxNodes).toFormattedLlmTree(maxNodes)
    }

    /**
     * Parses an active root AccessibilityNodeInfo into a compact, filtered semantic UI tree.
     */
    fun parseTree(root: AccessibilityNodeInfo?, maxNodes: Int = 120): CompactUiTree {
        if (root == null) {
            return CompactUiTree(packageName = "unknown")
        }

        val pkgName = root.packageName?.toString() ?: "unknown"
        val nodesList = mutableListOf<SemanticNode>()
        val indexCounter = intArrayOf(0)
        var totalRawElements = 0

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            totalRawElements++
            if (nodesList.size >= maxNodes || depth > 25) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val isVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0
            } else {
                bounds.width() > 0 && bounds.height() > 0
            }

            // Check if this node is semantically meaningful for the LLM
            val hasText = !node.text.isNullOrBlank()
            val hasDesc = !node.contentDescription.isNullOrBlank()
            val isActionable = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
            val isInformative = hasText || hasDesc || isActionable || !node.viewIdResourceName.isNullOrBlank()

            if (isVisible && isInformative) {
                val index = indexCounter[0]++
                val role = simplifyRole(node.className?.toString())
                val cleanViewId = node.viewIdResourceName

                val semanticNode = SemanticNode(
                    index = index,
                    role = role,
                    text = node.text?.toString()?.trim(),
                    contentDescription = node.contentDescription?.toString()?.trim(),
                    viewId = cleanViewId,
                    bounds = bounds,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isCheckable = node.isCheckable,
                    isChecked = node.isChecked,
                    isEnabled = node.isEnabled,
                    isFocused = node.isFocused,
                    isVisible = isVisible,
                    nodeInfo = node
                )
                nodesList.add(semanticNode)
            }

            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (e: Exception) {
                    null
                }
                if (child != null) {
                    traverse(child, depth + 1)
                }
            }
        }

        try {
            traverse(root, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing accessibility node hierarchy: ${e.message}", e)
        }

        val indexMap = nodesList.associateBy { it.index }
        return CompactUiTree(
            packageName = pkgName,
            nodes = nodesList,
            nodeIndexMap = indexMap,
            rawElementCount = totalRawElements
        )
    }

    /**
     * Locates all accessibility nodes matching the specified text.
     */
    fun findByText(
        root: AccessibilityNodeInfo?,
        text: String,
        exactMatch: Boolean = false
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        if (root == null) return results
        val target = text.trim().lowercase(Locale.ROOT)
        if (target.isEmpty()) return results

        fun search(node: AccessibilityNodeInfo) {
            val nodeText = node.text?.toString()?.trim()?.lowercase(Locale.ROOT)
            if (nodeText != null) {
                val isMatch = if (exactMatch) nodeText == target else nodeText.contains(target)
                if (isMatch) results.add(node)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                search(child)
            }
        }

        search(root)
        return results
    }

    /**
     * Locates all accessibility nodes matching the content description.
     */
    fun findByContentDescription(
        root: AccessibilityNodeInfo?,
        desc: String,
        exactMatch: Boolean = false
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        if (root == null) return results
        val target = desc.trim().lowercase(Locale.ROOT)
        if (target.isEmpty()) return results

        fun search(node: AccessibilityNodeInfo) {
            val nodeDesc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT)
            if (nodeDesc != null) {
                val isMatch = if (exactMatch) nodeDesc == target else nodeDesc.contains(target)
                if (isMatch) results.add(node)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                search(child)
            }
        }

        search(root)
        return results
    }

    /**
     * Locates all accessibility nodes matching the specified view-id resource name.
     */
    fun findByViewId(
        root: AccessibilityNodeInfo?,
        viewId: String
    ): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return try {
            // First attempt Android's built-in fast indexed query
            val list = root.findAccessibilityNodeInfosByViewId(viewId)
            if (list.isNotEmpty()) return list

            // Fallback manual crawl for partial ID matches (e.g., 'send' or 'button1')
            val results = mutableListOf<AccessibilityNodeInfo>()
            val target = viewId.lowercase(Locale.ROOT)

            fun search(node: AccessibilityNodeInfo) {
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT)
                if (id != null && (id == target || id.endsWith("/$target") || id.contains(target))) {
                    results.add(node)
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    search(child)
                }
            }

            search(root)
            results
        } catch (e: Exception) {
            Log.w(TAG, "Error finding nodes by view ID: ${e.message}")
            emptyList()
        }
    }

    /**
     * Locates all accessibility nodes matching a specific UI role (e.g. Button, EditText).
     */
    fun findByRole(
        root: AccessibilityNodeInfo?,
        role: String
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        if (root == null) return results
        val targetRole = role.trim().lowercase(Locale.ROOT)

        fun search(node: AccessibilityNodeInfo) {
            val nodeRole = simplifyRole(node.className?.toString()).lowercase(Locale.ROOT)
            if (nodeRole == targetRole || nodeRole.contains(targetRole)) {
                results.add(node)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                search(child)
            }
        }

        search(root)
        return results
    }

    /**
     * Comprehensive multi-field query matching text, content description, and view ID.
     * Returns the best candidate node for execution.
     */
    fun findByQuery(
        root: AccessibilityNodeInfo?,
        query: String
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val clean = query.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return null

        var bestMatch: AccessibilityNodeInfo? = null
        var highestScore = 0

        fun evaluate(node: AccessibilityNodeInfo) {
            var score = 0
            val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT)
            val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT)
            val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT)

            if (text == clean) score += 100
            else if (text?.contains(clean) == true) score += 60

            if (desc == clean) score += 95
            else if (desc?.contains(clean) == true) score += 55

            if (viewId?.endsWith("/$clean") == true) score += 85
            else if (viewId?.contains(clean) == true) score += 40

            if (node.isClickable) score += 20
            if (node.isEditable) score += 15

            if (score > highestScore) {
                highestScore = score
                bestMatch = node
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                evaluate(child)
            }
        }

        evaluate(root)
        return bestMatch
    }

    /**
     * Finds all clickable nodes in the subtree.
     */
    fun findClickableNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        if (root == null) return list
        fun search(node: AccessibilityNodeInfo) {
            if (node.isClickable) list.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                search(child)
            }
        }
        search(root)
        return list
    }

    /**
     * Finds all editable (input text) nodes in the subtree.
     */
    fun findEditableNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        if (root == null) return list
        fun search(node: AccessibilityNodeInfo) {
            if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
                list.add(node)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                search(child)
            }
        }
        search(root)
        return list
    }

    /**
     * Finds all scrollable container nodes in the subtree.
     */
    fun findScrollableNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        if (root == null) return list
        fun search(node: AccessibilityNodeInfo) {
            if (node.isScrollable) list.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                search(child)
            }
        }
        search(root)
        return list
    }

    /**
     * Simplifies raw Android class names into concise, human-readable semantic roles.
     */
    private fun simplifyRole(className: String?): String {
        if (className == null) return "View"
        return when {
            className.contains("Button", ignoreCase = true) -> "Button"
            className.contains("EditText", ignoreCase = true) -> "EditText"
            className.contains("TextView", ignoreCase = true) -> "TextView"
            className.contains("CheckBox", ignoreCase = true) -> "CheckBox"
            className.contains("Switch", ignoreCase = true) -> "Switch"
            className.contains("RadioButton", ignoreCase = true) -> "RadioButton"
            className.contains("ImageView", ignoreCase = true) -> "Image"
            className.contains("ImageButton", ignoreCase = true) -> "ImageButton"
            className.contains("ProgressBar", ignoreCase = true) -> "ProgressBar"
            className.contains("SeekBar", ignoreCase = true) -> "SeekBar"
            className.contains("RecyclerView", ignoreCase = true) -> "RecyclerView"
            className.contains("ListView", ignoreCase = true) -> "ListView"
            className.contains("ScrollView", ignoreCase = true) -> "ScrollView"
            className.contains("WebView", ignoreCase = true) -> "WebView"
            className.contains("ViewGroup", ignoreCase = true) -> "ViewGroup"
            className.contains("Layout", ignoreCase = true) -> "Layout"
            else -> className.substringAfterLast('.')
        }
    }
}

/**
 * Extension helper to safely click an AccessibilityNodeInfo or its nearest clickable parent.
 */
fun AccessibilityNodeInfo.click(): Boolean {
    if (this.isClickable) {
        return this.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    var current: AccessibilityNodeInfo? = this.parent
    var depth = 0
    while (current != null && depth < 5) {
        if (current.isClickable) {
            return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        current = current.parent
        depth++
    }
    return false
}

