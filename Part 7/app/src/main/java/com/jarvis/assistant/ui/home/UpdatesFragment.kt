package com.jarvis.assistant.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.agentic.soul.StepLogLevel
import com.jarvis.assistant.agentic.soul.TaskStatus
import com.jarvis.assistant.agentic.swarm.AgentLiveStatus
import com.jarvis.assistant.agentic.swarm.AgentState
import com.jarvis.assistant.databinding.FragmentUpdatesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdatesFragment : Fragment() {

    private var _binding: FragmentUpdatesBinding? = null
    private val binding get() = _binding!!

    private var dynamicAgentsLayout: LinearLayout? = null
    private var dynamicTasksLayout: LinearLayout? = null
    private var dynamicSoulLayout: LinearLayout? = null
    private var dynamicLogsLayout: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUpdatesBinding.inflate(inflater, container, false)
        setupTabs()
        renderMissionControl()
        renderFeatureUpdates()
        observeLiveState()
        return binding.root
    }

    private fun setupTabs() {
        binding.tabMissionControl.setOnClickListener {
            binding.scrollMissionControl.visibility = View.VISIBLE
            binding.scrollAnnouncements.visibility = View.GONE
            binding.tabMissionControl.setTextColor(themeColor(R.attr.themeAccent))
            binding.tabMissionControl.setBackgroundColor(themeColor(R.attr.themeSurface))
            binding.tabAnnouncements.setTextColor(themeColor(R.attr.themeTextMuted))
            binding.tabAnnouncements.background = null
        }

        binding.tabAnnouncements.setOnClickListener {
            binding.scrollMissionControl.visibility = View.GONE
            binding.scrollAnnouncements.visibility = View.VISIBLE
            binding.tabAnnouncements.setTextColor(themeColor(R.attr.themeAccent))
            binding.tabAnnouncements.setBackgroundColor(themeColor(R.attr.themeSurface))
            binding.tabMissionControl.setTextColor(themeColor(R.attr.themeTextMuted))
            binding.tabMissionControl.background = null
        }
    }

    private fun renderMissionControl() {
        val container = binding.missionControlContainer
        container.removeAllViews()

        // 0. Quick Mission Dispatcher Card (लिखकर task देना)
        val dispatcherCard = createCard().apply {
            addView(TextView(requireContext()).apply {
                text = "🚀 SWARM MISSION DISPATCHER"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeAccent))
            })
            addView(TextView(requireContext()).apply {
                text = "Direct Goal & Task Execution"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeTextPrimary))
                setPadding(0, dp(4), 0, dp(6))
            })

            val etGoal = EditText(requireContext()).apply {
                hint = "Enter task (e.g. 'Turn on flashlight', 'Search AI news', 'Open WhatsApp')..."
                setHintTextColor(Color.parseColor("#64748B"))
                setTextColor(themeColor(R.attr.themeTextPrimary))
                textSize = 13f
                setBackgroundResource(R.drawable.bg_jarvis_card)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            addView(etGoal)

            // Horizontal layout for action chips
            val chipRow = HorizontalScrollView(requireContext()).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            val chipLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val quickGoals = listOf(
                "💡 Torch On" to "Turn on flashlight",
                "🔍 Search News" to "Search latest tech news",
                "📱 WhatsApp" to "Open WhatsApp",
                "🧠 Complex Goal" to "Search Kotlin version and then turn on torch"
            )
            quickGoals.forEach { (label, goalText) ->
                val chip = TextView(requireContext()).apply {
                    text = label
                    textSize = 11f
                    setTextColor(themeColor(R.attr.themeAccent))
                    setBackgroundResource(R.drawable.bg_jarvis_chip)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(6) }
                    layoutParams = lp
                    setOnClickListener {
                        etGoal.setText(goalText)
                        etGoal.setSelection(goalText.length)
                    }
                }
                chipLayout.addView(chip)
            }
            chipRow.addView(chipLayout)
            addView(chipRow)

            val btnLaunch = Button(requireContext()).apply {
                text = "⚡ LAUNCH MISSION"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.bg_jarvis_pill_button)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(40)
                )
                setOnClickListener {
                    val goal = etGoal.text?.toString()?.trim() ?: ""
                    if (goal.isBlank()) {
                        Toast.makeText(requireContext(), "Please enter a task or goal", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    etGoal.text?.clear()
                    val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(windowToken, 0)

                    Toast.makeText(requireContext(), "Mission launched: '$goal'", Toast.LENGTH_SHORT).show()
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val result = JarvisApp.instance.managerAgent.executeGoal(goal)
                            withContext(Dispatchers.Main) {
                                if (isAdded) {
                                    Toast.makeText(requireContext(), "Mission Done: ${result.take(50)}", Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                if (isAdded) {
                                    Toast.makeText(requireContext(), "Mission error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
            addView(btnLaunch)
        }
        container.addView(dispatcherCard)

        // 1. Active Swarm Agents Card (चलते agents की स्थिति)
        val agentsCard = createCard().apply {
            addView(TextView(requireContext()).apply {
                text = "👥 ACTIVE SWARM AGENTS & STATUS"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeAccent))
            })
            addView(TextView(requireContext()).apply {
                text = "Specialist Agent Registry & Roster"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeTextPrimary))
                setPadding(0, dp(4), 0, dp(6))
            })
            dynamicAgentsLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(dynamicAgentsLayout)
        }
        container.addView(agentsCard)

        // 2. Multi-Agent Swarm Missions Card (Task की स्थिति)
        val swarmCard = createCard().apply {
            addView(TextView(requireContext()).apply {
                text = "⚡ MULTI-AGENT SWARM MISSIONS"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeAccent))
            })
            addView(TextView(requireContext()).apply {
                text = "Active & Recent Executed Tasks"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeTextPrimary))
                setPadding(0, dp(4), 0, dp(6))
            })
            dynamicTasksLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(dynamicTasksLayout)
        }
        container.addView(swarmCard)

        // 3. Real-Time Blackboard Step Logs Card
        val logsCard = createCard().apply {
            addView(TextView(requireContext()).apply {
                text = "📜 REAL-TIME BLACKBOARD STEP LOGS"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeAccent))
            })
            addView(TextView(requireContext()).apply {
                text = "Live inter-agent communication & progress ledger"
                textSize = 12f
                setTextColor(themeColor(R.attr.themeTextMuted))
                setPadding(0, dp(2), 0, dp(6))
            })
            dynamicLogsLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(dynamicLogsLayout)
        }
        container.addView(logsCard)

        // 4. Dual-SOUL Memory Card
        val soulCard = createCard().apply {
            addView(TextView(requireContext()).apply {
                text = "🧠 DUAL-SOUL MEMORY MATRIX · LIVE"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeAccent))
            })
            addView(TextView(requireContext()).apply {
                text = "User SOUL & Hermes Self-Learning"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeTextPrimary))
                setPadding(0, dp(4), 0, dp(4))
            })
            dynamicSoulLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(dynamicSoulLayout)
        }
        container.addView(soulCard)

        // 5. Multi-Provider Router Card
        val routerCard = createCard().apply {
            addView(TextView(requireContext()).apply {
                text = "🌐 MULTI-PROVIDER SMART ROUTER"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeAccent))
            })
            addView(TextView(requireContext()).apply {
                text = "Auto-Failover Matrix Status"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeTextPrimary))
                setPadding(0, dp(4), 0, dp(4))
            })
            val vault = com.jarvis.assistant.agentic.router.ProviderVault.getInstance(requireContext())
            val geminiReady = vault.hasApiKey(com.jarvis.assistant.agentic.router.ModelProvider.GEMINI)
            val groqReady = vault.hasApiKey(com.jarvis.assistant.agentic.router.ModelProvider.GROQ)
            val openRouterReady = vault.hasApiKey(com.jarvis.assistant.agentic.router.ModelProvider.OPENROUTER)
            val deepSeekReady = vault.hasApiKey(com.jarvis.assistant.agentic.router.ModelProvider.DEEPSEEK)

            val statusText = buildString {
                append("• Gemini: ${if (geminiReady) "✅ Ready" else "⚪ No Key"}\n")
                append("• Groq LPU: ${if (groqReady) "✅ Ready" else "⚪ No Key"}\n")
                append("• OpenRouter: ${if (openRouterReady) "✅ Ready" else "⚪ No Key"}\n")
                append("• DeepSeek: ${if (deepSeekReady) "✅ Ready" else "⚪ Optional"}\n")
                append("• Local Ollama: ⚡ Ready\n")
                append("Failover Cascade: Active (RPM 429 Protected)")
            }

            addView(TextView(requireContext()).apply {
                text = statusText
                textSize = 12f
                setTextColor(themeColor(R.attr.themeTextMuted))
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
        container.addView(routerCard)
    }

    private fun observeLiveState() {
        val app = JarvisApp.instance

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 1. Observe and refresh Active Agents status periodically
                launch {
                    while (true) {
                        try {
                            val statuses = app.specialistAgentPool.getAllAgentStatuses()
                            renderAgentStatuses(statuses)
                        } catch (e: Exception) {}
                        delay(1200)
                    }
                }

                // 2. Observe Active and Recent Missions from AgentSoulManager
                launch {
                    try {
                        app.agentSoulManager.observeAllTasks(limit = 8).collectLatest { tasks ->
                            val ctx = context ?: return@collectLatest
                            val layout = dynamicTasksLayout ?: return@collectLatest
                            layout.removeAllViews()
                            if (tasks.isEmpty()) {
                                layout.addView(TextView(ctx).apply {
                                    text = "No swarm tasks yet. Dispatch a mission above or speak to JARVIS."
                                    textSize = 12f
                                    setTextColor(themeColor(R.attr.themeTextMuted))
                                })
                            } else {
                                tasks.forEach { task ->
                                    val taskView = LinearLayout(ctx).apply {
                                        orientation = LinearLayout.VERTICAL
                                        setBackgroundResource(R.drawable.bg_jarvis_card)
                                        setPadding(dp(10), dp(8), dp(10), dp(8))
                                        val lp = LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                        ).apply { bottomMargin = dp(6) }
                                        layoutParams = lp
                                    }

                                    // Header row: Badge + Assigned Agent
                                    val headerRow = LinearLayout(ctx).apply {
                                        orientation = LinearLayout.HORIZONTAL
                                        gravity = Gravity.CENTER_VERTICAL
                                    }

                                    val badgeColor = when (task.status) {
                                        TaskStatus.IN_PROGRESS -> Color.parseColor("#06B6D4")
                                        TaskStatus.COMPLETED -> Color.parseColor("#10B981")
                                        TaskStatus.FAILED -> Color.parseColor("#EF4444")
                                        else -> Color.parseColor("#94A3B8")
                                    }
                                    val badgeText = when (task.status) {
                                        TaskStatus.IN_PROGRESS -> "▶ RUNNING ⏳"
                                        TaskStatus.COMPLETED -> "✓ COMPLETED ✅"
                                        TaskStatus.FAILED -> "✕ FAILED ❌"
                                        else -> task.status.name
                                    }

                                    headerRow.addView(TextView(ctx).apply {
                                        text = badgeText
                                        textSize = 10f
                                        setTypeface(typeface, Typeface.BOLD)
                                        setTextColor(badgeColor)
                                        setBackgroundResource(R.drawable.bg_jarvis_chip)
                                        setPadding(dp(6), dp(2), dp(6), dp(2))
                                    })

                                    headerRow.addView(TextView(ctx).apply {
                                        text = "  [${task.assignedTo}]"
                                        textSize = 11f
                                        setTextColor(themeColor(R.attr.themeTextMuted))
                                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                    })

                                    taskView.addView(headerRow)

                                    // Task Title / Goal
                                    taskView.addView(TextView(ctx).apply {
                                        text = task.title
                                        textSize = 13f
                                        setTypeface(typeface, Typeface.BOLD)
                                        setTextColor(themeColor(R.attr.themeTextPrimary))
                                        setPadding(0, dp(4), 0, dp(2))
                                    })

                                    // Result Summary (if present)
                                    if (!task.resultSummary.isNullOrBlank()) {
                                        taskView.addView(TextView(ctx).apply {
                                            text = "Result: ${task.resultSummary}"
                                            textSize = 11f
                                            setTextColor(Color.parseColor("#94A3B8"))
                                            maxLines = 3
                                            ellipsize = TextUtils.TruncateAt.END
                                        })
                                    }

                                    layout.addView(taskView)
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }

                // 3. Observe Recent Step Logs from AgentSoulManager
                launch {
                    try {
                        app.agentSoulManager.observeRecentLogs(8).collectLatest { logs ->
                            val ctx = context ?: return@collectLatest
                            val layout = dynamicLogsLayout ?: return@collectLatest
                            layout.removeAllViews()
                            if (logs.isEmpty()) {
                                layout.addView(TextView(ctx).apply {
                                    text = "Blackboard standby."
                                    textSize = 12f
                                    setTextColor(themeColor(R.attr.themeTextMuted))
                                })
                            } else {
                                logs.forEach { log ->
                                    val color = when (log.level) {
                                        StepLogLevel.SUCCESS -> Color.parseColor("#10B981")
                                        StepLogLevel.ERROR -> Color.parseColor("#EF4444")
                                        StepLogLevel.WARNING -> Color.parseColor("#F59E0B")
                                        else -> themeColor(R.attr.themeAccent)
                                    }
                                    layout.addView(TextView(ctx).apply {
                                        text = "• [${log.agentName}] ${log.action}: ${log.detail}"
                                        textSize = 11f
                                        setTextColor(color)
                                        setPadding(0, dp(2), 0, dp(2))
                                    })
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }

                // 4. Observe User SOUL attributes
                launch {
                    try {
                        app.userSoulManager.userSoulFlow.collectLatest { list ->
                            val ctx = context ?: return@collectLatest
                            val layout = dynamicSoulLayout ?: return@collectLatest
                            layout.removeAllViews()
                            if (list.isEmpty()) {
                                layout.addView(TextView(ctx).apply {
                                    text = "Listening and learning user habits, personality, and recurring skills..."
                                    textSize = 12f
                                    setTextColor(themeColor(R.attr.themeTextMuted))
                                })
                            } else {
                                list.take(4).forEach { item ->
                                    layout.addView(TextView(ctx).apply {
                                        text = "• [${item.category}] ${item.traitKey}: ${item.traitValue.take(60)}"
                                        textSize = 12f
                                        setTextColor(themeColor(R.attr.themeTextPrimary))
                                        setPadding(0, dp(2), 0, dp(2))
                                    })
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        }
    }

    private fun renderAgentStatuses(statuses: List<AgentLiveStatus>) {
        val ctx = context ?: return
        val layout = dynamicAgentsLayout ?: return
        layout.removeAllViews()

        statuses.forEach { agent ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_jarvis_card)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
                layoutParams = lp
            }

            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(ctx).apply {
                text = agent.name
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeTextPrimary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val isWorking = agent.state == AgentState.WORKING
            val tvBadge = TextView(ctx).apply {
                text = if (isWorking) "⚡ WORKING" else if (agent.state == AgentState.ERROR) "❌ ERROR" else "⚪ IDLE"
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (isWorking) Color.parseColor("#06B6D4") else if (agent.state == AgentState.ERROR) Color.parseColor("#EF4444") else Color.parseColor("#94A3B8"))
                setBackgroundResource(R.drawable.bg_jarvis_chip)
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }

            top.addView(tvName)
            top.addView(tvBadge)
            row.addView(top)

            val tvDetail = TextView(ctx).apply {
                text = "${agent.role} · ${agent.lastAction}"
                textSize = 11f
                setTextColor(if (isWorking) themeColor(R.attr.themeAccent) else themeColor(R.attr.themeTextMuted))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            row.addView(tvDetail)

            layout.addView(row)
        }
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_jarvis_card)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
    }

    private data class FeatureNotice(val feature: String, val title: String, val details: String, val kind: String = "FEATURE")

    private fun renderFeatureUpdates() {
        val features = listOf(
            FeatureNotice("Agent tasks", "Verified actions and recovery", "JARVIS observes the current screen, checks action results, tracks progress, and stops safely when the UI is unclear."),
            FeatureNotice("Floating avatar", "Always-on or working-only display", "Choose an avatar, double-tap for compact task progress, drag it anywhere on screen, and choose a dock edge in Settings."),
            FeatureNotice("Task progress", "Four-step compact status", "The task HUD shows the goal, recent steps, a running indicator, completed checks, and a subdued dark progress line."),
            FeatureNotice("Silent execution", "Quiet task automation", "When enabled, task narration is muted while ordinary conversation stays audible."),
            FeatureNotice("WhatsApp", "Saved contact lookup", "Contact aliases are supported. Grant Contacts permission first; JARVIS will report a missing permission separately from a name mismatch."),
            FeatureNotice("Phone calls", "Permission-aware calling", "Normal calls require Contacts and Phone permissions. WhatsApp calls use WhatsApp and do not place a cellular call."),
            FeatureNotice("Web Intelligence", "Zero-Cost Live Web Research", "JARVIS uses DuckDuckGo and multi-tier scraping to research live facts, weather, and news without consuming API quotas."),
            FeatureNotice("App Automation", "Multi-Tier Hybrid Execution", "Automates 1500+ apps via deep links, Accessibility semantic tree parsing, and rootless Shizuku fallback."),
            FeatureNotice("Spotify", "Song search and playback checks", "Searches can inspect results and verify playback before reporting success; physical-device behavior still depends on the Spotify screen and account."),
            FeatureNotice("Permissions", "Per-feature access status", "The System Authorizations page lists microphone, location, notifications, overlay, Accessibility, contacts, phone, camera, Bluetooth, battery, and brightness access."),
            FeatureNotice("AI persona", "Operator profile preview", "Preview your selected assistant voice and the name it will use before activating the persona."),
            FeatureNotice("Appearance", "Sunset theme by default", "Fresh installs start with the sunset theme; you can still select other themes in Settings.")
        )
        val list = binding.updatesList
        list.removeAllViews()
        features.forEach { feature ->
            val card = createCard()
            card.addView(TextView(requireContext()).apply {
                text = feature.feature.uppercase() + if (feature.kind == "DIAGNOSTIC") "  ·  DIAGNOSTIC" else ""
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeAccent))
            })
            card.addView(TextView(requireContext()).apply {
                text = feature.title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeTextPrimary))
                setPadding(0, dp(8), 0, dp(5))
            })
            card.addView(TextView(requireContext()).apply {
                text = feature.details
                textSize = 13f
                setTextColor(themeColor(R.attr.themeTextMuted))
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            list.addView(card)
        }
    }

    private fun themeColor(attribute: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dynamicAgentsLayout = null
        dynamicSoulLayout = null
        dynamicTasksLayout = null
        dynamicLogsLayout = null
        _binding = null
    }
}
