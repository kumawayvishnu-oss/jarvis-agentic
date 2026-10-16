---
name: android-kotlin-architecture
description: "Android Kotlin 2.0 architecture patterns for Coroutines, StateFlow/SharedFlow, Room DAO thread-safety, and ViewBinding lifecycle safety."
---

# 🏗️ Android Kotlin 2.0 Architecture Skill (ViewBinding + Coroutines + Room)

## 1. Coroutines & Structured Concurrency
- Always scope UI collectors to `viewLifecycleOwner.lifecycleScope` with `repeatOnLifecycle(Lifecycle.State.STARTED)`.
- Never use `GlobalScope`; use `SupervisorJob() + Dispatchers.IO` in long-lived Foreground Services (`JarvisConversationService`) and cancel on `onDestroy()`.
- Run parallel independent agent/tool calls with `coroutineScope { tasks.map { async { ... } }.awaitAll() }` and wrap each with `withTimeoutOrNull(15_000L)`.

## 2. Room Database & DAO Rules
- Mark all write/query DAO methods `suspend` or return `Flow<List<T>>`.
- Add `@Index` on frequently queried columns (`category`, `key`, `taskId`) to keep retrieval under 5ms.
- Never block `Dispatchers.Main` with database reads.

## 3. ViewBinding Lifecycle Safety (No Compose)
- In `Fragment`, hold `private var _binding: FragmentXBinding? = null` and reset `_binding = null` in `onDestroyView()` to prevent memory leaks.
- Always check `_binding != null` before updating UI inside coroutine callbacks or `onResume()`.

## 4. Safety Gate Invariant
- Never execute external messages, payments, or deletions without explicit user confirmation via `HardwareResourceAwareEngine.evaluateSafetyGate()`.
