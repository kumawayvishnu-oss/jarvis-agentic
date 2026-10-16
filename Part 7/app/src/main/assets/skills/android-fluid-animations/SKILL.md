---
name: android-fluid-animations
description: "High-performance Android physics-based motion, 60fps view transitions, pulse glow animators, and haptic feedback."
---

# ⚡ Android Fluid Animations Skill

## 1. Motion Principles
- **Duration:** 150ms-250ms for micro-transitions (taps, icon state changes), 300ms-400ms for sheet/dialog transitions.
- **Interpolators:** FastOutSlowInInterpolator (`DecelerateInterpolator(1.5f)`) for natural organic deceleration.
- **Hardware Acceleration:** Always animate `translationX`, `translationY`, `scaleX`, `scaleY`, and `alpha` to avoid layout passes.

## 2. Dynamic Orb & Pulse Animations
- ValueAnimator running infinite ping-pong breathing animation on glowing borders.
- Smooth volume visualizer bars with staggered interpolations.
- ViewPropertyAnimator chaining with `withEndAction` for seamless step-by-step agent task progression.

## 3. Haptic Integration
- Subtle `HapticFeedbackConstants.VIRTUAL_KEY` on button touch.
- `HapticFeedbackConstants.LONG_PRESS` on critical state triggers (e.g. Swarm goal activation, Voiceprint confirmation).
