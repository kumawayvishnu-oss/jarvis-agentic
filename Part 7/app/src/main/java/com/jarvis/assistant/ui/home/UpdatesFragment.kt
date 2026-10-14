package com.jarvis.assistant.ui.home

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.agentic.soul.TaskStatus
import com.jarvis.assistant.databinding.FragmentUpdatesBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UpdatesFragment : Fragment() {

    private var _binding: FragmentUpdatesBinding? = null
    private val binding get() = _binding!!

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
            binding.tabMissionControl.setBackgroundColor(themeColor(R.attr.themeBgSecondary))
            binding.tabAnnouncements.setTextColor(themeColor(R.attr.themeTextMuted))
            binding.tabAnnouncements.background = null
        }

        binding.tabAnnouncements.setOnClickListener {
            binding.scrollMissionControl.visibility = View.GONE
            binding.scrollAnnouncements.visibility = View.VISIBLE
            binding.tabAnnouncements.setTextColor(themeColor(R.attr.themeAccent))
            binding.tabAnnouncements.setBackgroundColor(themeColor(R.attr.themeBgSecondary))
            binding.tabMissionControl.setTextColor(themeColor(R.attr.themeTextMuted))
            binding.tabMissionControl.background = null
        }
    }

    private fun renderMissionControl() {
        val container = binding.missionControlContainer
        container.removeAllViews()

        // 1. Dual-SOUL Memory Card
        val soulCard = createCard().apply {
            addView(TextView(requireContext()).apply {
                text = "🧠 DUAL-SOUL MEMORY MATRIX · LIVE"
                textSize = 11sp()
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeAccent))
            })
            addView(TextView(requireContext()).apply {
                text = "User SOUL & Hermes Self-Learning"
                textSize = 14sp()
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeTextPrimary))
                setPadding(0, dp(6), 0, dp(4))
            })
            dynamicSoulLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(dynamicSoulLayout)
        }
        container.addView(soulCard)

        // 2. Multi-Provider Router Card
        val routerCard = createCard().apply {
            addView(TextView(requireContext()).apply {
                text = "🌐 MULTI-PROVIDER SMART ROUTER"
                textSize = 11sp()
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeAccent))
            })
            addView(TextView(requireContext()).apply {
                text = "Auto-Failover Matrix Active"
                textSize = 14sp()
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeTextPrimary))
                setPadding(0, dp(6), 0, dp(4))
            })
            addView(TextView(requireContext()).apply {
                text = "Primary: Gemini 1.5 Live/Flash\nFallback 1: DeepSeek V3/R1\nFallback 2: Groq LPU (Ultra-Fast)\nFallback 3: OpenRouter Free Matrix\nFallback 4: Local Ollama\nProtection: RPM 429 Circuit Breakers Active"
                textSize = 12sp()
                setTextColor(themeColor(R.attr.themeTextMuted))
            })
        }
        container.addView(routerCard)

        // 3. Multi-Agent Swarm Blackboard Card
        val swarmCard = createCard().apply {
            addView(TextView(requireContext()).apply {
                text = "⚡ MULTI-AGENT SWARM BLACKBOARD"
                textSize = 11sp()
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeAccent))
            })
            addView(TextView(requireContext()).apply {
                text = "Active Swarm Tasks"
                textSize = 14sp()
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeTextPrimary))
                setPadding(0, dp(6), 0, dp(4))
            })
            dynamicTasksLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(dynamicTasksLayout)
        }
        container.addView(swarmCard)

        // 4. Live Step Logs Card
        val logsCard = createCard().apply {
            addView(TextView(requireContext()).apply {
                text = "📜 REAL-TIME STEP LOGS"
                textSize = 11sp()
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeAccent))
            })
            dynamicLogsLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            addView(dynamicLogsLayout)
        }
        container.addView(logsCard)
    }

    private fun observeLiveState() {
        val app = JarvisApp.instance

        // Observe User SOUL attributes
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                app.userSoulManager.cachedUserSoul.collectLatest { list ->
                    dynamicSoulLayout?.removeAllViews()
                    if (list.isEmpty()) {
                        dynamicSoulLayout?.addView(TextView(requireContext()).apply {
                            text = "Listening and learning user habits, personality, and recurring skills..."
                            textSize = 12sp()
                            setTextColor(themeColor(R.attr.themeTextMuted))
                        })
                    } else {
                        list.take(4).forEach { item ->
                            dynamicSoulLayout?.addView(TextView(requireContext()).apply {
                                text = "• [${item.category}] ${item.key}: ${item.value.take(60)}"
                                textSize = 12sp()
                                setTextColor(themeColor(R.attr.themeTextPrimary))
                                setPadding(0, dp(2), 0, dp(2))
                            })
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Observe active tasks from AgentSoulManager
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                app.agentSoulManager.observeTasksByStatus(TaskStatus.IN_PROGRESS).collectLatest { tasks ->
                    dynamicTasksLayout?.removeAllViews()
                    if (tasks.isEmpty()) {
                        dynamicTasksLayout?.addView(TextView(requireContext()).apply {
                            text = "No active swarm tasks running. Ready for autonomous goals."
                            textSize = 12sp()
                            setTextColor(themeColor(R.attr.themeTextMuted))
                        })
                    } else {
                        tasks.forEach { task ->
                            dynamicTasksLayout?.addView(TextView(requireContext()).apply {
                                text = "▶ [${task.assignedTo}] ${task.title} (${task.priority})"
                                textSize = 12sp()
                                setTextColor(themeColor(R.attr.themeAccent))
                                setPadding(0, dp(3), 0, dp(3))
                            })
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Observe recent step logs
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                app.agentSoulManager.observeRecentLogs(4).collectLatest { logs ->
                    dynamicLogsLayout?.removeAllViews()
                    if (logs.isEmpty()) {
                        dynamicLogsLayout?.addView(TextView(requireContext()).apply {
                            text = "Blackboard standby."
                            textSize = 12sp()
                            setTextColor(themeColor(R.attr.themeTextMuted))
                        })
                    } else {
                        logs.forEach { log ->
                            dynamicLogsLayout?.addView(TextView(requireContext()).apply {
                                text = "[${log.agentName}] ${log.action}: ${log.detail.take(50)}"
                                textSize = 11sp()
                                setTextColor(themeColor(R.attr.themeTextMuted))
                                setPadding(0, dp(1), 0, dp(1))
                            })
                        }
                    }
                }
            } catch (_: Exception) {}
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
            FeatureNotice("Google Flow / AI Studio", "Browser input diagnostics", "The captured Flow run could not verify the prompt input in Chrome. Some editor fields are not exposed to Accessibility; screen sharing must be active and have a fresh frame for visual targeting. The captured logs did not show a Google AI Studio page, so that failure still needs a fresh log capture.", "DIAGNOSTIC"),
            FeatureNotice("Instagram", "Bulk action safety", "Bulk tasks track list progress and require end-of-list evidence. The captured run repeatedly reached the action limit and did not establish completion; avoid treating an unverified result as done.", "DIAGNOSTIC"),
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
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(R.attr.themeAccent))
            })
            card.addView(TextView(requireContext()).apply {
                text = feature.title
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
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
    private fun sp() = 12f

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
        _binding = null
    }
}
