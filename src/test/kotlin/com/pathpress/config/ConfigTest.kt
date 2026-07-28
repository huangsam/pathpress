package com.pathpress.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigTest {

    @Test
    fun `default Config contains expected constant default values`() {
        val config = Config()
        assertEquals(0.05, config.gridCellSizeDeg)
        assertEquals(10, config.defaultPoisPerLeg)
        assertEquals("gemini-1.5-flash", config.defaultGeminiModel)
        assertEquals("claude-3-haiku-20240307", config.defaultClaudeModel)
        assertEquals("gpt-4o-mini", config.defaultOpenAiModel)
        assertEquals("gemma4:12b-mlx", config.defaultOllamaModel)
        assertEquals(15L, config.httpLlmTimeoutSeconds)
        assertEquals(10L, config.geocoderTimeoutSeconds)
        assertEquals(Duration.ofSeconds(15), config.httpLlmConnectTimeout)
        assertEquals(Duration.ofSeconds(10), config.geocoderConnectTimeout)
    }

    @Test
    fun `fromEnv overrides configuration when environment variables are present`() {
        val customEnv =
            mapOf(
                "GRID_CELL_SIZE_DEG" to "0.1",
                "DEFAULT_POIS_PER_LEG" to "25",
                "DEFAULT_GEMINI_MODEL" to "gemini-2.0-flash-custom",
                "CLAUDE_MODEL" to "claude-3-5-sonnet-custom",
                "OPENAI_MODEL" to "gpt-4o-custom",
                "OLLAMA_MODEL" to "llama3:8b-custom",
                "HTTP_LLM_TIMEOUT_SECONDS" to "30",
                "GEOCODER_TIMEOUT_SECONDS" to "20",
            )

        val config = Config.fromEnv(customEnv)
        assertEquals(0.1, config.gridCellSizeDeg)
        assertEquals(25, config.defaultPoisPerLeg)
        assertEquals("gemini-2.0-flash-custom", config.defaultGeminiModel)
        assertEquals("claude-3-5-sonnet-custom", config.defaultClaudeModel)
        assertEquals("gpt-4o-custom", config.defaultOpenAiModel)
        assertEquals("llama3:8b-custom", config.defaultOllamaModel)
        assertEquals(30L, config.httpLlmTimeoutSeconds)
        assertEquals(20L, config.geocoderTimeoutSeconds)
        assertEquals(Duration.ofSeconds(30), config.httpLlmConnectTimeout)
        assertEquals(Duration.ofSeconds(20), config.geocoderConnectTimeout)
    }

    @Test
    fun `fromEnv falls back to defaults when env map is empty or invalid`() {
        val invalidEnv =
            mapOf(
                "GRID_CELL_SIZE_DEG" to "invalid-double",
                "DEFAULT_POIS_PER_LEG" to "not-an-int",
                "HTTP_LLM_TIMEOUT_SECONDS" to "nan",
                "GEOCODER_TIMEOUT_SECONDS" to "foo",
            )

        val config = Config.fromEnv(invalidEnv)
        assertEquals(Config.DEFAULT_GRID_CELL_SIZE_DEG, config.gridCellSizeDeg)
        assertEquals(Config.DEFAULT_POIS_PER_LEG, config.defaultPoisPerLeg)
        assertEquals(Config.DEFAULT_HTTP_LLM_TIMEOUT_SECONDS, config.httpLlmTimeoutSeconds)
        assertEquals(Config.DEFAULT_GEOCODER_TIMEOUT_SECONDS, config.geocoderTimeoutSeconds)
    }
}
