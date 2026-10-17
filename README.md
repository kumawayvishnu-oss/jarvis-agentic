# 🧠 JARVIS — Autonomous Multi-Agent Android Operating System

JARVIS is an autonomous, voice-first Android agentic operating system powered by **Gemini Live Bidi WebSocket (`v1alpha`)**, an **8-Provider Smart LLM Router**, a **4-Tier Dual-SOUL Memory Engine**, a **Self-Healing Accessibility & Intent App Dispatcher**, and an **Autonomous AI-Controlled Linux Terminal (`AgentShell`) with Live Preview**.

---

## ✨ Core Architecture

### 1. Real-Time Bidi Voice & Multimodal Intelligence
- **Gemini Live WebSocket (`v1alpha`)**: Low-latency 16kHz PCM audio input and 24kHz native voice output with instant barge-in interruption and auto-reconnect.
- **On-Device Wake Word (`"Hey Jarvis"`)**: Background foreground service with voice activity detection and seamless live session handoff.
- **22 Live Voice Tools**: Real-time tool execution during voice conversations, including device control, WhatsApp automation, web search, multi-agent task delegation, and autonomous terminal execution.

### 2. Autonomous AI-Only Linux Terminal (`AgentShell`) & Live Preview
- **Strictly AI-Controlled Terminal**: Designed exclusively for JARVIS agents (`ManagerAgent` and `ShellCodingSpecialistAgent`). Users watch live command streams in a read-only neon console while the AI autonomously installs tools, runs scripts, and builds projects.
- **Dual Execution Engine**:
  - **Embedded Native Android Linux Shell**: Immediate zero-setup execution inside an isolated workspace (`$filesDir/agent_linux/workspace`) with automatic Termux binary PATH discovery (`/data/data/com.termux/files/usr/bin`).
  - **External Termux Bridge (`RUN_COMMAND` Intent + `TermuxResultService`)**: Direct background execution inside Termux and Ubuntu `proot-distro` when installed.
- **Autonomous CLI & Coding Delegation**: Can autonomously bootstrap and invoke developer CLIs (`agy` / Antigravity CLI, `opencode`, `gh`, `git`, `python3`, `node`, `curl`) to generate full web apps, scripts, or diagnostics and render them immediately in an interactive **Live Preview Window** (HTML5 WebView + syntax-highlighted source).

### 3. Multi-Agent Swarm (`ManagerAgent` & Specialist Pool)
- **DAG Task Decomposition**: Breaks complex multi-step user goals into parallel dependency graphs.
- **4 Specialized Autonomous Agents**:
  1. `CognitiveSpecialistAgent` — Deep reasoning, research synthesis, and structured tool calling via `UnifiedToolRegistry`.
  2. `AppControlSpecialistAgent` — Intent-first Android app automation with Accessibility UI tree fallback.
  3. `MemorySpecialistAgent` — Long-term factual recall, user profile synthesis, and episode search.
  4. `ShellCodingSpecialistAgent` — Autonomous Linux terminal execution, CLI tool management, code generation, and live preview creation.

### 4. 8-Provider Smart LLM Router (`SmartProviderRouter`)
- Cascading multi-provider failover with real-time latency tracking, rate-limit cooldowns, and hardware-backed `EncryptedSharedPreferences` key storage:
  1. **Google Gemini** (`gemini-2.5-flash`)
  2. **Groq LPU** (`llama-3.3-70b-versatile`)
  3. **Cerebras Wafer-Scale** (`llama-3.3-70b`)
  4. **Mistral AI** (`mistral-small-latest`)
  5. **SambaNova Cloud** (`Meta-Llama-3.3-70B-Instruct`)
  6. **DeepSeek** (`deepseek-chat`)
  7. **OpenRouter** (`meta-llama/llama-3.3-70b-instruct:free`)
  8. **Local Ollama** (`http://127.0.0.1:11434`)

### 5. 4-Tier Dual-SOUL Memory & Self-Healing Engine
- **4-Tier Context Hierarchy**:
  - **Tier 0 (`SOUL.md` & `USER_SOUL.md`)**: Immutable agent identity and encrypted evolving user profile.
  - **Tier 1 (Working RAM)**: Active session context and live task DAG state.
  - **Tier 2 (Episodic SQLite + FTS4)**: Sub-millisecond full-text search across past conversations and task outcomes.
  - **Tier 3 (Semantic Knowledge Graph)**: Entity-relation graph with multi-hop graph traversal.
- **Self-Healing Watchdog**: Persistent crash recovery (`CrashWatchdog`), exponential backoff circuit breakers, and autonomous diagnostic verification.

---

## 🛠️ Building the APK

### GitHub Actions Cloud Build
Every push to `main` or `feature/*` automatically triggers `.github/workflows/android-build.yml` on `ubuntu-latest` with JDK 17 and Android SDK 34, producing `jarvis-debug-apk`.

### Local Gradle Build
```bash
cd "Part 7"
./gradlew assembleDebug --stacktrace
```
The compiled APK is generated at:
`Part 7/app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 License
Licensed under the [MIT License](LICENSE).
