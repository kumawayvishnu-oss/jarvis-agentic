package com.jarvis.assistant.agentic.router

/**
 * Supported LLM Providers for JARVIS Autonomous Super-System.
 * Provides resilient, automatic multi-provider cascading failover.
 */
enum class ModelProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val isLocal: Boolean = false,
    val defaultRpmLimit: Int = 60
) {
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini 1.5",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        defaultModel = "gemini-1.5-flash",
        defaultRpmLimit = 15
    ),
    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek V3 / R1",
        baseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        defaultRpmLimit = 60
    ),
    GROQ(
        id = "groq",
        displayName = "Groq LPU (Ultra-Fast)",
        baseUrl = "https://api.groq.com/openai/v1",
        defaultModel = "llama-3.3-70b-versatile",
        defaultRpmLimit = 30
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter Matrix",
        baseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "meta-llama/llama-3.3-70b-instruct:free",
        defaultRpmLimit = 20
    ),
    OLLAMA_LOCAL(
        id = "ollama",
        displayName = "Local Ollama Engine",
        baseUrl = "http://127.0.0.1:11434/v1",
        defaultModel = "qwen2.5:3b",
        isLocal = true,
        defaultRpmLimit = 1000
    );

    companion object {
        fun fromId(id: String): ModelProvider? {
            return entries.find { it.id.equals(id, ignoreCase = true) }
        }
    }
}
