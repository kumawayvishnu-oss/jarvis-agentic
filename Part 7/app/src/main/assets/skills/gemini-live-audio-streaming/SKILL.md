---
name: gemini-live-audio-streaming
description: "Gemini Live Bidi WebSocket (v1alpha) real-time 16kHz PCM audio input, 24kHz audio playback, barge-in handling, and strict model payload rules."
---

# 🎙️ Gemini Live Audio Streaming Skill (`models/gemini-3.8-live`)

## 1. Model & Setup Payload Rules
- Always use `models/gemini-3.8-live` as the default Live WebSocket model on `v1alpha` (`BidiGenerateContent`).
- **CRITICAL:** Never include `thinking_level`, `thinkingConfig`, or `enableAffectiveDialog` in the setup message for `models/gemini-3.8-live` — unsupported fields cause immediate WebSocket handshake failure or silent audio drop.
- Request `responseModalities: ["AUDIO"]` with `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName`.

## 2. Audio Pipeline (16kHz In / 24kHz Out)
- **Input (`AudioRecorder`):** `VOICE_COMMUNICATION` source, 16,000 Hz, `CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT`. Enable hardware `AcousticEchoCanceler` and `NoiseSuppressor` when available.
- **Output (`AudioPlayer`):** 24,000 Hz `AudioTrack` (`USAGE_ASSISTANT`, `CONTENT_TYPE_SPEECH`).
- **Barge-In:** When user speech exceeds RMS threshold while assistant is speaking, immediately flush `AudioPlayer` queue and send client audio chunks.

## 3. Safety Gate Invariant
- Voice tool calls returned via `toolCall.functionCalls` must pass through `HardwareResourceAwareEngine.evaluateSafetyGate()` before executing sensitive actions (messages, payments, deletions).
