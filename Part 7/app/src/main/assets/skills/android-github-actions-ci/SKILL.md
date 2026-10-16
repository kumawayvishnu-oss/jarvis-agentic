---
name: android-github-actions-ci
description: "Termux-friendly Android CI/CD loop via GitHub Actions: edit, commit, push, gh run watch, --log-failed root-cause diagnosis, and APK artifact verification."
---

# 🚀 Android GitHub Actions CI & Debugging Skill

## 1. Remote Build & Verification Cycle (Termux Workflow)
Since local Termux environments cannot execute full Android SDK/aapt2/Gradle builds:
1. Verify syntax and imports locally via `grep` and file inspection.
2. Commit atomic changes and push to `origin/feature/dual-soul`.
3. Watch the workflow in real time: `gh run watch <run-id> --exit-status`.
4. If the build fails, immediately inspect exact Kotlin/Gradle compiler errors with `gh run view <run-id> --log-failed`, fix the root cause, and re-push until green (`✓ SUCCESS`).

## 2. Live On-Device Telemetry (`LiveDebug` & `DiagnosticActivity`)
- Instrument critical boundaries (`WebSocket`, `AudioRecorder`, `AudioPlayer`, `AccessibilityService`, `SmartProviderRouter`) using `LiveDebug.log()`.
- Provide one-tap **Copy Report** in `LiveDebugActivity` with masked secrets so users can share real-time device diagnostics without ADB.
