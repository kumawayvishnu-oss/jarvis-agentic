# 📋 JARVIS Agentic System — Progress & Roadmap

> **Target Platform:** Android 8.0+ (API 26-34) | **Language:** Kotlin 2.0.20 | **Target Module:** Part 7  
> **Active Branch:** `feature/dual-soul`  
> **CI/CD:** GitHub Actions (`.github/workflows/build.yml`) on `jarvis-agentic` repo  
> **Last Updated:** 2026-10-14

---

## 🔍 Architecture & Context Overview
Based on `ARCHITECTURE.md` and `git log`:
- Master Production Codebase is in **`Part 7`** (`com.jarvis.assistant`).
- Built upon a hybrid architecture:
  1. Google Gemini Live Bidi WebSocket API (audio-to-audio streaming, barge-in, 21 tools).
  2. Offline Vosk Speech Engine ("Hey Jarvis", speaker voiceprint `model-spk`).
  3. Android Accessibility Engine (clicks, gestures, 21 tools).
  4. Real-time Screen Vision (MediaProjection).
  5. 3D Particle Orb (WebGL/Three.js) + 17 Floating Overlays.
  6. Dual Persona Core (JARVIS & NAIRA).
  7. Room Database Persistent Memory.

---

## 🚀 6 New Agentic Phases Added (on `feature/dual-soul`)
1. **Dual-SOUL Memory Core:**
   - `UserSoulManager.kt` & `UserSoulEntity.kt`: Self-learning Hermes-style cognitive user memory and dynamic skill synthesis.
   - `AgentSoulManager.kt` & `AgentSoulEntity.kt`: Multi-agent shared blackboard, mission status, and step logs.
   - Integrated into `JarvisConversationService.kt` and `MemoryDatabase.kt`.
2. **Multi-Provider Smart Router & Vault:**
   - `SmartProviderRouter.kt`, `ProviderVault.kt`, `ModelProvider.kt`, `GeminiRestClient.kt`, `OpenAICompatibleClient.kt`.
3. **Multi-Agent Swarm Engine:**
   - `ManagerAgent.kt`, `SpecialistAgentPool.kt`, `DynamicAgentFactory.kt`, `BaseSpecialistAgent.kt`, `AgentInterCom.kt`.
4. **1500+ App Hybrid Connector & Accessibility Parser:**
   - `AccessibilityNodeParser.kt`, `HybridActionDispatcher.kt`, `ShizukuBridge.kt`, `AppCategoryCatalog.kt`.
5. **Zero-Cost Live Search & Self-Healing:**
   - `FreeLiveSearchClient.kt` (DuckDuckGo instant answers + HTML parsing fallback).
   - `SelfHealingEngine.kt` (Dynamic error recovery and fallback strategies).
6. **Mission Control UI:**
   - `UpdatesFragment.kt` (Tab 3/4) converted to live visual dashboard showing SOUL traits, active swarm missions, and provider health.

---

## ⚠️ Known Compile Errors & Issues (From 7-Agent Audit & GitHub CI)

1. **GitHub Actions CI (`.github/workflows/build.yml`):**
   - **Root Cause:** Uses deprecated `android-actions/setup-android@v3` which fails with `sdkmanager tools` exit code 1.
   - **Status:** [IN PROGRESS] Need to remove `android-actions/setup-android@v3` as Android SDK is pre-installed on `ubuntu-latest`.

2. **Dual-SOUL Memory (`UserSoulManager.kt`):**
   - **Root Cause:** Calls non-existent `soulDao.upsertUserSoul(...)` and non-existent enum `SoulCategory`, mismatches `UserSoulEntity` constructor fields (`key`/`value`/`source` vs `traitKey`/`traitValue`/`evidenceSummary`).
   - **Status:** [PENDING FIX] Align `UserSoulManager.kt` to `SoulDao` (`insertOrUpdateUserSoul`) and `UserSoulEntity` (`traitKey`, `traitValue`, `category`).

3. **Hybrid Connector & Accessibility (`HybridActionDispatcher.kt` / `AccessibilityNodeParser.kt`):**
   - **Root Cause:** `HybridActionDispatcher` calls `AccessibilityNodeParser.getInstance(context)` (parser is an `object`), calls `findNodeByTextOrDesc` (method is `findByQuery`), and calls `.click()` on `AccessibilityNodeInfo` (missing extension function).
   - **Status:** [PENDING FIX] Add `getInstance()`, `findNodeByTextOrDesc()`, and `click()` extension function.

4. **Zero-Cost Search Client (`FreeLiveSearchClient.kt`):**
   - **Status:** Fixed unnamed catch variables in commit `34a4cbf`. Verify during CI build.

5. **Legacy 7-Part Code Alignment:**
   - Ensure `JarvisApp.kt`, `JarvisConversationService.kt`, `UpdatesFragment.kt` compile cleanly with no broken references.

---

## 🎯 Plan & Workflow
1. **Fix GitHub Actions CI Workflow:** Remove failing SDK action, verify runner executes `./gradlew assembleDebug`.
2. **Fix Compile Errors Sequentially (Atomic Commits):**
   - Commit 1: CI Workflow fix.
   - Commit 2: `UserSoulManager.kt` DAO and Entity alignment.
   - Commit 3: `AccessibilityNodeParser.kt` / `HybridActionDispatcher.kt` API compatibility.
3. **Verify Build Green in GitHub Actions:** Use `gh run list` and `gh run view --log-failed` (max 15 attempts).
4. **Feature Integration & Polish:** Complete skeleton implementations, preserve all original JARVIS features (orb, voice, wake-word, 21 tools, overlays).
