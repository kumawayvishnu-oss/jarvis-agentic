# 🗑️ REMOVED.md — Pruned Files, Features, Links & Assets Ledger

> This file tracks every item removed during cleanup waves (with its purpose and commit category) so that any item can be restored via `git checkout <commit>~1 -- <path>` if ever needed.

---

## Wave 1 & Pre-Phase A Cleanup (Commits `8ae0936`, `36fc562`)
- **Orphaned Chat Adapters & Layouts:**
  - `Part 7/app/src/main/java/com/jarvis/assistant/ui/chat/ChatAdapter.kt`
  - `Part 7/app/src/main/java/com/jarvis/assistant/ui/chat/NairaChatAdapter.kt`
  - `Part 7/app/src/main/java/com/jarvis/assistant/ui/chat/ChatHistoryBottomSheet.kt`
  - `Part 7/app/src/main/res/layout/item_naira_chat_message.xml`
  - `Part 7/app/src/main/res/layout/item_chat_turn.xml`
  - `Part 7/app/src/main/res/layout/sheet_chat_history.xml`
- **Orphaned Settings Activity & HUD Assets:**
  - `Part 7/app/src/main/java/com/jarvis/assistant/ui/settings/SettingsActivity.kt`
  - `Part 7/app/src/main/res/layout/activity_settings.xml`
  - `Part 7/app/src/main/java/com/jarvis/assistant/ui/orb/OrbHelper.kt`
  - `Part 7/app/src/main/assets/hud/index.html`
  - `Part 7/app/src/main/assets/settings/settings.html`
  - `Part 7/app/src/main/assets/ghost-avatar-v2.html`
  - `Part 7/app/src/main/java/com/jarvis/assistant/security/DeveloperInfo.kt`
- **Unused Drawables (20 files) & Fake UI Controls:**
  - 20 unreferenced vector drawables in `Part 7/app/src/main/res/drawable/`
  - Fake `btnAssistantPlay` Toast-only button in `item_jarvis_chat_message.xml` and `JarvisChatAdapter.kt`
- **Desktop Emulator Native Binaries:**
  - Stripped `x86` and `x86_64` ABI binaries via `abiFilters += listOf("arm64-v8a", "armeabi-v7a")` in `Part 7/app/build.gradle.kts` (~20 MB savings).

---

## Phase A Cleanup (Separate Atomic Commits per Removal Category)

### 1. Commit Category A1 — Legacy Tutorial Folders (`Part 1` to `Part 6`)
- `Part 1/` (Legacy tutorial snapshot)
- `Part 2/` (Legacy tutorial snapshot)
- `Part 3/` (Legacy tutorial snapshot)
- `Part 4/` (Legacy tutorial snapshot)
- `Part 5/` (Legacy tutorial snapshot)
- `Part 6/` (Legacy tutorial snapshot)

### 2. Commit Category A2 — Standalone Promo Screens (`HandsFreeActivity` & `PrivacyShieldActivity`)
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/settings/HandsFreeActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/settings/PrivacyShieldActivity.kt`
- `Part 7/app/src/main/res/layout/activity_hands_free.xml`
- `Part 7/app/src/main/res/layout/activity_privacy_shield.xml`
- Removed `<activity>` entries for `HandsFreeActivity` and `PrivacyShieldActivity` from `Part 7/app/src/main/AndroidManifest.xml`.

### 3. Commit Category A3 — Promotional Links, Social Icons & Hardcoded Creator Strings
- `Part 7/app/src/main/res/drawable/ic_social_telegram.xml`
- `Part 7/app/src/main/res/drawable/ic_social_instagram.xml`
- `Part 7/app/src/main/res/drawable/ic_social_youtube.xml`
- `Part 7/app/src/main/res/drawable/ic_settings_apps.xml` (unreferenced after `rowApplications` removal)
- `Part 7/app/src/main/res/drawable/ic_settings_lock.xml` (unreferenced after `rowPrivacyPolicy` removal)
- Removed `rowApplications`, `rowPrivacyPolicy`, `rowCredits`, `btnSocialTelegram`, `btnSocialInstagram`, `btnSocialYoutube`, `showCreditsDialog()`, and `openUrl()` from `SettingsFragment.kt` and `fragment_settings.xml`.
- Removed hardcoded creator/promo strings and URLs from `HomeFragment.kt`, `fragment_home.xml`, `activity_permission.xml`, `AppPreferences.kt`, and `SecurityManager.kt` (`LICENSE` file preserved untouched).

### 4. Commit Category A4 — Extra Roleplay & Persona Modes (Kept Only `JARVIS` & `NAIRA`)
- Removed `PERSONALITY_GIRLFRIEND` (`Pari`), `PERSONALITY_BOYFRIEND` (`Kabir`), `PERSONALITY_TECHGPT_X`, `PERSONALITY_COMPANION`, `PERSONALITY_PROFESSIONAL`, and `PERSONALITY_FOCUS` from:
  - `Part 7/app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt`
  - `Part 7/app/src/main/java/com/jarvis/assistant/ui/settings/PersonaActivity.kt`
  - `Part 7/app/src/main/res/layout/activity_persona.xml`
  - `Part 7/app/src/main/java/com/jarvis/assistant/ui/chat/ChatFragment.kt`
  - `Part 7/app/src/main/java/com/jarvis/assistant/util/PromptGenerator.kt` (also removed hardcoded creator praise block and promotional website examples).

### 5. Commit Category A5 — Floating Avatars Pruning (Kept 6 Core + 11 On-Demand Lazy Load)
- Removed 11 redundant static HTML files from `Part 7/app/src/main/assets/overlay-toys/` (routed dynamically via `droid.html?type=<avatar>` and lazy-loaded on demand in `gallery.html`):
  - `blob.html`, `cat.html`, `cloud.html`, `drop.html`, `flower.html`, `pebble.html`, `pill.html`, `puddle.html`, `square.html`, `star.html`, `triangle.html`
- Kept 6 core avatar HTML files: `droid.html`, `mech.html`, `ghost.html`, `alien.html`, `circle.html`, `hexagon.html` (plus `gallery.html`).

### 6. Commit Category A6 — Safe R8 Code & Resource Shrinking
- Enabled safe R8 shrinking (`isMinifyEnabled = true`, `isShrinkResources = true`) with `-dontobfuscate` and comprehensive `-keep` rules in `Part 7/app/build.gradle.kts` and `Part 7/app/proguard-rules.pro`.
