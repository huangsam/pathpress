package com.pathpress.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigTest {

    @Test
    fun `default Config contains expected constant default values`() {
        val config = Config()
        assertEquals(6, config.defaultPoisPerLeg)
        assertEquals("gemini-1.5-flash", config.defaultGeminiModel)
        assertEquals("claude-3-haiku-20240307", config.defaultClaudeModel)
        assertEquals("gpt-4o-mini", config.defaultOpenAiModel)
        assertEquals("qwen3.6:35b-mlx", config.defaultOllamaModel)
        assertEquals(10L, config.httpLlmConnectTimeoutSeconds)
        assertEquals(60L, config.httpLlmRequestTimeoutSeconds)
        assertEquals(10L, config.geocoderTimeoutSeconds)
        assertEquals(Duration.ofSeconds(10), config.httpLlmConnectTimeout)
        assertEquals(Duration.ofSeconds(60), config.httpLlmRequestTimeout)
        assertEquals(Duration.ofSeconds(10), config.geocoderConnectTimeout)
    }

    @Test
    fun `fromEnv overrides configuration when environment variables are present`() {
        val customEnv =
            mapOf(
                "DEFAULT_POIS_PER_LEG" to "25",
                "DEFAULT_GEMINI_MODEL" to "gemini-2.0-flash-custom",
                "CLAUDE_MODEL" to "claude-3-5-sonnet-custom",
                "OPENAI_MODEL" to "gpt-4o-custom",
                "OLLAMA_MODEL" to "llama3:8b-custom",
                "HTTP_LLM_CONNECT_TIMEOUT_SECONDS" to "5",
                "HTTP_LLM_REQUEST_TIMEOUT_SECONDS" to "45",
                "GEOCODER_TIMEOUT_SECONDS" to "20",
            )

        val config = Config.fromEnv(customEnv)
        assertEquals(25, config.defaultPoisPerLeg)
        assertEquals("gemini-2.0-flash-custom", config.defaultGeminiModel)
        assertEquals("claude-3-5-sonnet-custom", config.defaultClaudeModel)
        assertEquals("gpt-4o-custom", config.defaultOpenAiModel)
        assertEquals("llama3:8b-custom", config.defaultOllamaModel)
        assertEquals(5L, config.httpLlmConnectTimeoutSeconds)
        assertEquals(45L, config.httpLlmRequestTimeoutSeconds)
        assertEquals(Duration.ofSeconds(5), config.httpLlmConnectTimeout)
        assertEquals(Duration.ofSeconds(45), config.httpLlmRequestTimeout)
        assertEquals(20L, config.geocoderTimeoutSeconds)
        assertEquals(Duration.ofSeconds(20), config.geocoderConnectTimeout)
    }

    @Test
    fun `fromEnv falls back to defaults when env map is empty or invalid`() {
        val invalidEnv =
            mapOf(
                "DEFAULT_POIS_PER_LEG" to "not-an-int",
                "HTTP_LLM_TIMEOUT_SECONDS" to "nan",
                "GEOCODER_TIMEOUT_SECONDS" to "foo",
            )

        val config = Config.fromEnv(invalidEnv)
        assertEquals(
            Config.DEFAULT_HTTP_LLM_REQUEST_TIMEOUT_SECONDS,
            config.httpLlmRequestTimeoutSeconds,
        )
        assertEquals(
            Config.DEFAULT_HTTP_LLM_CONNECT_TIMEOUT_SECONDS,
            config.httpLlmConnectTimeoutSeconds,
        )
        assertEquals(Config.DEFAULT_GEOCODER_TIMEOUT_SECONDS, config.geocoderTimeoutSeconds)
    }
}
