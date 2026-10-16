---
name: agentic-tool-planner
description: "Strict JSON schema tool registry, adaptive tool filtering, argument auto-repair, parallel execution, and resilient cascading failovers."
---

# 🛠️ Agentic Tool Planner Skill

## 1. Tool Design & Registry Standards
- Every tool must expose:
  - Canonical unique identifier (`toolName`).
  - Strict JSON schema parameter specification (`type`, `description`, `required`, optional `enum`).
  - Clear human-readable description (< 120 chars) to minimize prompt overhead.
- **Selective Filtering:** Never send all 50+ tools in one request. Dynamically filter tools matching user intent domain (Device, Web Search, App Automation, Media).

## 2. Argument Validation & Auto-Repair
- If an LLM returns malformed JSON arguments, catch `JsonSyntaxException` and attempt heuristic repair (fixing unescaped quotes, trailing commas, missing braces).
- If validation fails, provide a compact repair prompt feedback loop (max 2 retries).

## 3. Parallel Swarm Execution
- Non-dependent sub-tasks run concurrently via `kotlinx.coroutines.async` / `awaitAll`.
- All outputs synchronize to the shared Blackboard (`AgentSoulManager`) with atomic updates.
