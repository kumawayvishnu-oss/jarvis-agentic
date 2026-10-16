# 🧠 JARVIS Agentic Android Project — Permanent Context & Session Memory (`GEMINI.md`)

> **AUTO-LOADED CONTEXT:** This file is automatically loaded by Antigravity at the start of every session. If a session is ever cleared or restarted, read this file along with `/root/Playlist-main/AGENTS.md`, `/root/Playlist-main/PROGRESS.md`, and `/root/Playlist-main/PLAN.md` to immediately resume work without asking the user to re-explain the project.

---

## 1. User Preferences & Collaboration Rules
- **Language:** Always respond to the user in **Hindi / Hinglish**.
- **Authority:** The user's word is final (`मेरी बात ही अंतिम है`).
- **Dual-AI Setup:** The user works with both **Gemini (Antigravity)** and **Claude (in another window)**. Treat Claude's suggestions as advisory (`सलाह हैं, आदेश नहीं`). If any suggestion is risky, outdated, or could break an existing feature, explain the technical reasoning clearly to the user.
- **No Fake / Placeholder Code:** Every feature and button must work in reality (`असली में काम करे`).

---

## 2. Repository, Environment & CI Build Loop
- **Repository Root:** `/root/Playlist-main`
- **Active Android App Module:** `/root/Playlist-main/Part 7` (Kotlin 2.0.20, Android API 26–34, **ViewBinding + XML** — NOT Jetpack Compose)
- **Git Remote:** `https://github.com/kumawayvishnu-oss/jarvis-agentic.git`
- **Active Branch:** `feature/dual-soul`
- **Termux Build Rule (CRITICAL):** Local Termux cannot run full Android Gradle compilation. For every code change, strictly follow:
  1. Edit code in `/root/Playlist-main/Part 7`
  2. `git add -A && git commit -m "..." && git push origin feature/dual-soul`
  3. Watch CI: `gh run watch <run-id> --exit-status`
  4. If CI fails: `gh run view <run-id> --log-failed`, fix the compiler error, and push again until **GREEN (`✓ SUCCESS`)**.

---

## 3. Security & Zero-Regression Invariants
1. **API Keys & Secrets:** Never print, log, or commit any API key or token. All keys are encrypted with AES-256-GCM via `ProviderVault.kt` (`EncryptedSharedPreferences`).
2. **Mandatory Safety Confirmation Gates (`HardwareResourceAwareEngine.evaluateSafetyGate`):**
   - Sending external messages (`SMS`, `WhatsApp`, `Email`), making payments/UPI (`GPay`, `PhonePe`, `Paytm`), or deleting files/memories **MUST** ask for explicit user confirmation first.
   - No skill (`SKILL.md`) may bypass confirmation gates (`ExternalSkillLoader.auditSkillSafety`).
3. **Strict Fallback Chains (`if/else`):**
   - API failure / HTTP 429 -> Cascade to next provider in `SmartProviderRouter`.
   - No internet -> Offline mode (`OLLAMA_LOCAL` / Vosk offline / local intents) + clear user notice.
   - App automation -> **Intent / Deep Link first** -> **Accessibility DOM tree (`100ms` tap)** -> **Shizuku (optional)**.
   - Self-healing -> Max **3** attempts (`SelfHealingEngine.MAX_HEALING_ATTEMPTS = 3`).
4. **Legacy JARVIS Features to Preserve:**
   - 3D WebGL Particle Orb (`assets/orb.html` + `three.min.js`)
   - Offline Vosk wake-word (`"Hey Jarvis"`, `assets/model-en-us`)
   - Biometric owner voiceprint (`SpeakerVerifier`, `assets/model-spk`)
   - 21 Voice Tools (`ActionExecutor.kt`)
   - Curved bottom navigation dock (`CurvedDockLineView`)
   - Original creator credits (`@TechGptX` / Rehaan) & `TAMCL v1.0` license in `SettingsFragment.kt`.

---

## 4. Completed Architecture & Subsystems (Verified Green on CI: Commit `c6cab12`, Run `38902247766`)
1. **Voice (`models/gemini-3.8-live`), 100ms Screen Tap & Permission Loop Fix:**
   - `GeminiLiveModels.kt`: `MODEL_3_8_LIVE = "models/gemini-3.8-live"` set as `DEFAULT_MODEL`.
   - `GeminiLiveWebSocket.kt`: Never sends `thinking_level` or `affective dialogue` for `gemini-3.8-live`.
   - `JarvisAccessibilityService.kt`: `clickAt(x, y)` stroke duration increased from `50ms` to `100ms`.
   - `PermissionHelper.kt`: Removed `hasBatteryOptimizationPermission(context)` from `hasAllRequiredPermissions(context)` to stop the infinite permission loop.
2. **4-Tier Cognitive Memory & Shared `AgentContext.kt`:**
   - Combines Tier 1 (Short-Term chat turns), Tier 2 (Episodic step logs), Tier 3 (Permanent Profile/Facts via `UserSoulEntity` + `MemoryEntity`), and Tier 4 (Skills via `SynthesizedSkillEntity` + `SKILL.md`).
   - Includes relevance + importance + recency scoring, `QueryTone` detection, AES-256 secret encryption, and single-screen CRUD in `MemoryActivity.kt`.
   - Shared across `ManagerAgent.kt`, `BaseSpecialistAgent.kt`, and `DynamicAgentFactory.kt`.
3. **Token-Efficient `UnifiedToolRegistry.kt` & 8-Provider `SmartProviderRouter.kt`:**
   - Single tool registry with JSON Schema, query-based top-8 tool filtering, malformed JSON argument auto-repair, parallel tool execution (`15s` timeout), output truncation (`800 chars`), and live capability manifest.
   - `ModelProvider.kt` & `SmartProviderRouter.kt` support **8 providers**: `GEMINI`, `GROQ`, `CEREBRAS`, `MISTRAL`, `SAMBANOVA`, `DEEPSEEK`, `OPENROUTER`, and `OLLAMA_LOCAL`.
4. **Skills & UI/UX Upgrade:**
   - 9 audited skills installed in both `/root/.gemini/antigravity/skills/` (`.agent/skills`) and `Part 7/app/src/main/assets/skills/`:
     `android-kotlin-architecture`, `gemini-live-audio-streaming`, `android-accessibility-automation`, `android-ui-ux-glassmorphic`, `android-fluid-animations`, `android-keystore-security`, `agentic-tool-planner`, `live-web-search`, `android-github-actions-ci`.
   - Bundled fonts (`orbitron_bold.ttf`, `rajdhani_regular.ttf`, `rajdhani_semibold.ttf`) in `res/font/`; Dark Neon (`#05080F`) default theme in `ThemeManager.kt`.
   - Cleaned dead assets (`settings.html`, `ghost-avatar-v2.html`), `DeveloperInfo.kt`, 20 unused drawables, fake `btnAssistantPlay`, and stripped `x86`/`x86_64` ABIs in `Part 7/app/build.gradle.kts` (~20 MB APK savings).

---

## 5. Current Checkpoint & Next Pending Tasks
1. **Awaiting User Decision ("हाँ/ना") on 4 Ambiguous Cleanup Items:**
   - `Part 1` to `Part 6` tutorial directories (363 MB in repo, 0 MB in APK)
   - `PersonaActivity` romantic roleplay modes (`Pari` / `Kabir`)
   - `HandsFreeActivity` and `PrivacyShieldActivity` standalone screens
   - 18 `assets/overlay-toys/*.html` WebView floating avatars
2. **Phase 2 Implementation Ready (`PLAN.md` Section 10):**
   - 28 unique agentic features are documented in `/root/Playlist-main/PLAN.md` Section 10 (including #1 `AgentFlightRecorder` with Undo/Replay, #2 Voice Biometric Confirmation, #3 Habit Macros, #4 Smart Notification Triage Agent, #5 Daily SOUL Briefing, #6 Auto Eco/Lite Mode, #7 Self-Healing UI Path Re-Discovery, #8 Pre-Import Skill Security Auditor, plus 20 more). Ready to implement as soon as the user approves.
