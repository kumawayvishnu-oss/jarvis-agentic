package com.jarvis.assistant.agentic.router

import com.jarvis.assistant.agentic.soul.AgentContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProviderCatalogTest {

    @Test
    fun allFifteenProvidersAreConfiguredWithAccurateFreeVsPaidClassification() {
        val entries = ModelProvider.entries
        assertEquals(15, entries.size)

        // Zero-Key 100% Free Providers
        assertFalse(ModelProvider.POLLINATIONS_FREE.requiresApiKey)
        assertTrue(ModelProvider.POLLINATIONS_FREE.isFreeTier)
        assertFalse(ModelProvider.OLLAMA_LOCAL.requiresApiKey)
        assertTrue(ModelProvider.OLLAMA_LOCAL.isFreeTier)

        // Key-Based Free Tier Providers requested by user
        assertTrue(ModelProvider.GEMINI.isFreeTier)
        assertTrue(ModelProvider.GLM_ZHIPU.isFreeTier)
        assertEquals("glm-4-flash", ModelProvider.GLM_ZHIPU.defaultModel)
        assertTrue(ModelProvider.SILICONFLOW.isFreeTier)
        assertTrue(ModelProvider.NVIDIA_NIM.isFreeTier)
        assertTrue(ModelProvider.GROQ.isFreeTier)
        assertTrue(ModelProvider.CEREBRAS.isFreeTier)
        assertTrue(ModelProvider.MISTRAL.isFreeTier)
        assertTrue(ModelProvider.SAMBANOVA.isFreeTier)
        assertTrue(ModelProvider.GITHUB_MODELS.isFreeTier)
        assertTrue(ModelProvider.OPENROUTER.isFreeTier)
        assertTrue(ModelProvider.HUGGINGFACE.isFreeTier)
        assertTrue(ModelProvider.COHERE.isFreeTier)

        // Paid Provider
        assertFalse(ModelProvider.DEEPSEEK.isFreeTier)
        assertTrue(ModelProvider.DEEPSEEK.pricingBadge.contains("PAID"))
    }

    @Test
    fun selfAwarenessManifestIncludesTerminalPlannerAndZeroGoogleIdSearch() {
        val manifest = AgentContext.getSelfAwarenessManifest("Creator & Owner: Lakki")
        assertTrue(manifest.contains("Creator & Owner: Lakki"))
        assertTrue(manifest.contains("AgentShellEngine"))
        assertTrue(manifest.contains("Antigravity"))
        assertTrue(manifest.contains("Probabilistic Multi-Agent Swarm"))
        assertTrue(manifest.contains("30-Minute Deep User & Topic Research Engine"))
        assertTrue(manifest.contains("15-Provider Free + Paid AI Router"))
        assertTrue(manifest.contains("NEVER opens Chrome with the user's personal Google ID"))
    }
}
