---
name: android-keystore-security
description: "Android Keystore AES-256-GCM encryption, EncryptedSharedPreferences vault management, zero key logging, and runtime safety confirmation gates."
---

# 🔐 Android Keystore & Security Vault Skill

## 1. API Key & Secret Storage (`ProviderVault`)
- Store all LLM provider API keys (`Gemini`, `Groq`, `OpenRouter`, `DeepSeek`, `Cerebras`, `Mistral`) and sensitive user facts inside `EncryptedSharedPreferences` backed by Android Keystore (`MasterKey.KeyScheme.AES256_GCM`).
- **NEVER** hardcode API keys in Kotlin/XML/Gradle, never commit keys to Git, and never print keys in `Logcat` or `LiveDebug` logs (always mask keys as `AIza••••••`).

## 2. Mandatory Action Confirmation (`if/else` Safety Gates)
- **External Messages (SMS / WhatsApp / Email):** Require explicit user confirmation before sending.
- **Financial / Payment / UPI Actions:** Strictly require confirmation; block autonomous PIN entry.
- **Destructive Actions (Delete File / Wipe Memory):** Require explicit confirmation before deletion.
- **Skill Import Audit:** Scan any external `SKILL.md` for shell injection, unauthorized network exfiltration, or unconfirmed destructive commands before loading.
