---
name: android-accessibility-automation
description: "Token-efficient Android AccessibilityService DOM tree parsing, 100ms OEM-safe gesture taps, Intent-first routing, and self-healing UI navigation."
---

# 🤖 Android Accessibility Automation Skill

## 1. Execution Priority (1500+ Apps Without API)
1. **Tier 1 — Explicit Intent / Deep Link (`AppCategoryCatalog`):** Always fire native Android `Intent` or URI deep link first (zero screen tokens, instant launch).
2. **Tier 2 — Semantic Accessibility DOM (`AccessibilityNodeParser`):** Extract compact clickable/editable nodes (`[id, text, contentDescription, bounds]`) instead of sending heavy screenshots to preserve Gemini RPM/token limits.
3. **Tier 3 — Gesture Dispatch (`JarvisAccessibilityService`):**
   - Use `performAction(ACTION_CLICK)` on matching `AccessibilityNodeInfo` first.
   - For coordinate taps (`clickAt(x, y)`), use a **100ms** `GestureDescription.StrokeDescription` duration so Xiaomi/Samsung/Vivo/Oppo OEM touch filters register the tap reliably.
4. **Tier 4 — Shizuku Shell Fallback (`ShizukuBridge`):** Optional ADB-level fallback only when Accessibility is blocked.

## 2. Strict Safety Gate (Mandatory Confirmation)
- **NEVER** auto-tap "Send", "Pay", "Confirm Transfer", or "Delete" in banking/UPI apps (`GPay`, `PhonePe`, `Paytm`), messaging apps (`WhatsApp`, `SMS`), or file managers without explicit user confirmation.
