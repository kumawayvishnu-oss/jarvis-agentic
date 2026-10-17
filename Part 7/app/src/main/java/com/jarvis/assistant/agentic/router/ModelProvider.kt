package com.jarvis.assistant.agentic.router

/**
 * Supported LLM Providers for JARVIS Autonomous Super-System.
 * Provides resilient, automatic 15-provider cascading failover with explicit
 * FREE vs PAID tier intelligence.
 */
enum class ModelProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val isLocal: Boolean = false,
    val requiresApiKey: Boolean = !isLocal,
    val defaultRpmLimit: Int = 60,
    val pricingBadge: String = "◈ [FREE TIER]",
    val pricingDetails: String = "Free developer tier available",
    val keyHint: String = "API Key..."
) {
    GEMINI(
        id = "gemini",
        displayName = "Google AI Studio (Gemini 2.5)",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        defaultModel = "gemini-2.5-flash",
        defaultRpmLimit = 15,
        pricingBadge = "◈ [FREE TIER + PAID]",
        pricingDetails = "FREE: 15 RPM / 1,500 Req/Day on Google AI Studio (aistudio.google.com) // Best for Live Voice & Agent",
        keyHint = "AIzaSy..."
    ),
    GLM_ZHIPU(
        id = "glm",
        displayName = "GLM Zhipu AI (BigModel)",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-4-flash",
        defaultRpmLimit = 60,
        pricingBadge = "◈ [100% FREE API]",
        pricingDetails = "100% FREE: glm-4-flash model is completely free on open.bigmodel.cn / z.ai with high concurrency",
        keyHint = "id.secret (Zhipu GLM Key)..."
    ),
    SILICONFLOW(
        id = "siliconflow",
        displayName = "SiliconFlow Cloud",
        baseUrl = "https://api.siliconflow.cn/v1",
        defaultModel = "Qwen/Qwen2.5-7B-Instruct",
        defaultRpmLimit = 60,
        pricingBadge = "◈ [FREE MODELS + PAID]",
        pricingDetails = "FREE TIER: Qwen2.5-7B-Instruct, THUDM/glm-4-9b-chat & DeepSeek-R1-Distill-Qwen-7B are 100% Free",
        keyHint = "sk-... (SiliconFlow Key)"
    ),
    NVIDIA_NIM(
        id = "nvidia_nim",
        displayName = "NVIDIA NIM Developer API",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        defaultModel = "meta/llama-3.3-70b-instruct",
        defaultRpmLimit = 40,
        pricingBadge = "◈ [FREE DEV CREDITS]",
        pricingDetails = "FREE TIER: 1,000 to 5,000 free API credits on build.nvidia.com // Runs Llama 3.3 70B & DeepSeek R1",
        keyHint = "nvapi-..."
    ),
    GROQ(
        id = "groq",
        displayName = "Groq Cloud LPU (~250ms)",
        baseUrl = "https://api.groq.com/openai/v1",
        defaultModel = "llama-3.3-70b-versatile",
        defaultRpmLimit = 30,
        pricingBadge = "◈ [FREE TIER]",
        pricingDetails = "FREE TIER: 30 RPM / 14,400 Req/Day on console.groq.com // Ultra-fast LPU hardware inference",
        keyHint = "gsk_..."
    ),
    CEREBRAS(
        id = "cerebras",
        displayName = "Cerebras Wafer-Scale (~2000 tok/s)",
        baseUrl = "https://api.cerebras.ai/v1",
        defaultModel = "llama-3.3-70b",
        defaultRpmLimit = 30,
        pricingBadge = "◈ [FREE TIER]",
        pricingDetails = "FREE TIER: 30 RPM / 60,000 tok/min / 1M tokens/day free on cloud.cerebras.ai",
        keyHint = "csk-..."
    ),
    MISTRAL(
        id = "mistral",
        displayName = "Mistral AI (La Plateforme)",
        baseUrl = "https://api.mistral.ai/v1",
        defaultModel = "mistral-small-latest",
        defaultRpmLimit = 30,
        pricingBadge = "◈ [FREE TIER]",
        pricingDetails = "FREE TIER: Free 'Experiment' plan on console.mistral.ai // 500K tokens/min & 1B tokens/month",
        keyHint = "Mistral Key..."
    ),
    SAMBANOVA(
        id = "sambanova",
        displayName = "SambaNova Cloud RDU",
        baseUrl = "https://api.sambanova.ai/v1",
        defaultModel = "Meta-Llama-3.3-70B-Instruct",
        defaultRpmLimit = 20,
        pricingBadge = "◈ [FREE TIER]",
        pricingDetails = "FREE TIER: Free Developer tier on cloud.sambanova.ai // 20 RPM on Llama-3.3-70B-Instruct",
        keyHint = "SambaNova UUID Key..."
    ),
    GITHUB_MODELS(
        id = "github_models",
        displayName = "GitHub Models (Azure AI)",
        baseUrl = "https://models.inference.ai.azure.com",
        defaultModel = "gpt-4o-mini",
        defaultRpmLimit = 15,
        pricingBadge = "◈ [FREE WITH GITHUB]",
        pricingDetails = "FREE TIER: Free with any GitHub Personal Access Token (PAT) // 15 RPM / 150 Req/Day on GPT-4o-mini",
        keyHint = "ghp_... or github_pat_..."
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter Matrix",
        baseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "meta-llama/llama-3.3-70b-instruct:free",
        defaultRpmLimit = 20,
        pricingBadge = "◆ [FREE + PAID]",
        pricingDetails = "FREE + PAID: ':free' models give 20 RPM / 200 Req/Day free; Paid credits unlock 300+ Pro models",
        keyHint = "sk-or-v1-..."
    ),
    HUGGINGFACE(
        id = "huggingface",
        displayName = "HuggingFace Serverless Inference",
        baseUrl = "https://router.huggingface.co/v1",
        defaultModel = "Qwen/Qwen2.5-72B-Instruct",
        defaultRpmLimit = 30,
        pricingBadge = "◈ [FREE TIER]",
        pricingDetails = "FREE TIER: Free with HuggingFace Read/Inference Token (hf_...) // Runs Qwen2.5-72B & Llama-3",
        keyHint = "hf_..."
    ),
    COHERE(
        id = "cohere",
        displayName = "Cohere Command-R Trial API",
        baseUrl = "https://api.cohere.com/compatibility/v1",
        defaultModel = "command-r-08-2024",
        defaultRpmLimit = 20,
        pricingBadge = "◈ [FREE TRIAL TIER]",
        pricingDetails = "FREE TIER: 20 RPM / 1,000 API calls/month free on dashboard.cohere.com",
        keyHint = "Cohere Trial Key..."
    ),
    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek V3 / R1 Official",
        baseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        defaultRpmLimit = 60,
        pricingBadge = "▪ [PAID API]",
        pricingDetails = "PAID API: Requires balance top-up on platform.deepseek.com (For FREE DeepSeek, use SiliconFlow or OpenRouter)",
        keyHint = "sk-..."
    ),
    POLLINATIONS_FREE(
        id = "pollinations",
        displayName = "Pollinations Zero-Key Free Cloud",
        baseUrl = "https://text.pollinations.ai/openai",
        defaultModel = "openai",
        isLocal = false,
        requiresApiKey = false,
        defaultRpmLimit = 30,
        pricingBadge = "◈ [100% FREE // ZERO-KEY]",
        pricingDetails = "ZERO-KEY FREE: Built-in autonomous cloud AI fallback — works immediately without any API key!",
        keyHint = "No API Key Required (Auto-Active)"
    ),
    OLLAMA_LOCAL(
        id = "ollama",
        displayName = "Local Ollama / Termux Engine",
        baseUrl = "http://127.0.0.1:11434/v1",
        defaultModel = "qwen2.5:3b",
        isLocal = true,
        requiresApiKey = false,
        defaultRpmLimit = 1000,
        pricingBadge = "◈ [100% FREE OFFLINE]",
        pricingDetails = "100% FREE LOCAL: Runs offline on Termux or local PC LAN server with zero cloud cost",
        keyHint = "http://127.0.0.1:11434/v1"
    );

    val isFreeTier: Boolean
        get() = pricingBadge.contains("FREE", ignoreCase = true)

    companion object {
        fun fromId(id: String): ModelProvider? {
            return entries.find { it.id.equals(id, ignoreCase = true) }
        }
    }
}
