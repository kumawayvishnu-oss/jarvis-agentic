# 🤖 JARVIS Agentic System — Agent Workstreams & Boundaries

> This document defines the decoupled modular domains, file ownership, and state tracking for multi-agent execution. No two agents or subagents should edit the same file simultaneously.

---

## 🧭 Domain Partitioning & File Ownership Matrix

| Domain | Responsible Files / Packages | Description & Guardrails |
|---|---|---|
| **CI / Build / Gradle** | `.github/workflows/build.yml`, `Part 7/build.gradle.kts`, `Part 7/app/build.gradle.kts` | Manages GitHub Actions APK build, SDK, dependencies, and signing configs. |
| **Dual-SOUL Memory** | `.../agentic/soul/*`, `.../data/memory/*` | Hermes cognitive memory, trait reinforcement, dynamic skills, agent blackboard. |
| **Smart Router & Search** | `.../agentic/router/*`, `.../agentic/search/*`, `.../agentic/healing/*` | Multi-LLM failover routing, encrypted API vault, free DuckDuckGo search, self-healing retries. |
| **Swarm & Manager** | `.../agentic/swarm/*` | ManagerAgent orchestration, specialist pool, dynamic agent creation, intercom. |
| **Connectors & Accessibility** | `.../agentic/connector/*`, `.../automation/accessibility/*` | 1500+ App Catalog, Semantic UI tree parser, HybridActionDispatcher (Intents, Accessibility, Shizuku). |
| **UI & Core Background** | `.../ui/*`, `.../background/*`, `.../JarvisApp.kt` | UpdatesFragment Mission Control, MainActivity, JarvisConversationService, Zero-regression core. |

---

## 🔒 Zero Regression Invariants
- **NEVER BREAK OR DELETE EXISTING FEATURES:**
  - 3D Particle Orb (WebGL/Three.js)
  - Gemini Live Bidi WebSocket audio streaming & barge-in
  - Offline Vosk wake-word ("Hey Jarvis") & owner biometric voiceprint (`model-spk`)
  - 21 Autonomous Tools
  - 17 Floating Avatar Overlays
  - Dual Persona (JARVIS cyan vs NAIRA rose gold)
  - CurvedDockLineView, weather cards, chat history
- **SECURITY & SAFETY:**
  - Never print or commit API keys or GitHub tokens.
  - Dangerous actions (sending external messages, financial payments, data wipe) require explicit user confirmation.

---

## 📊 Current Task State
- [x] Initial 6 Phases implemented on `feature/dual-soul`.
- [x] Context established via `ARCHITECTURE.md`, `PROGRESS.md`, `AGENTS.md`.
- [x] Fix 1: Resolve GitHub Actions SDK installation error (`android-actions/setup-android@v3`).
- [x] Fix 2: Resolve `UserSoulManager.kt` mismatch with `SoulDao` and `UserSoulEntity`.
- [x] Fix 3: Resolve `AccessibilityNodeParser.kt` and `HybridActionDispatcher.kt` API compatibility.
- [x] Fix 4: Resolve `ManagerAgent.kt` `taskId` type mismatch with `AgentSoulManager`.
- [x] Fix 5: Resolve `SpecialistAgentPool.kt` command execution via `HybridActionDispatcher`.
- [x] Fix 6: Resolve `AppCategoryCatalog.kt` nullability and missing lookup methods.
- [x] Fix 7: Eliminate all `catch (_: Exception)` across legacy code for Kotlin 2.0.20 compatibility.
- [x] CI Verification: Push and monitor GitHub Actions until build is green (**Run ID: 38522472876 - SUCCESS**).
- [x] Verify generated APK (`jarvis-debug-apk` artifact produced in 5m 49s).
- [x] End-to-end operational wiring across Swarm, Connectors, Memory, Router, and UI verified.
