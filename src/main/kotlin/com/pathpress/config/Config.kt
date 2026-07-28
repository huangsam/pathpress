package com.pathpress.config

import java.time.Duration

/**
 * Centralized application configuration.
 *
 * Provides default values for grid dimensions, POI limits, model names, and network timeouts, with
 * support for environment variable overrides.
 */
data class Config(
    val gridCellSizeDeg: Double = DEFAULT_GRID_CELL_SIZE_DEG,
    val defaultPoisPerLeg: Int = DEFAULT_POIS_PER_LEG,
    val defaultGeminiModel: String = DEFAULT_GEMINI_MODEL,
    val defaultClaudeModel: String = DEFAULT_CLAUDE_MODEL,
    val defaultOpenAiModel: String = DEFAULT_OPENAI_MODEL,
    val defaultOllamaModel: String = DEFAULT_OLLAMA_MODEL,
    val httpLlmTimeoutSeconds: Long = DEFAULT_HTTP_LLM_TIMEOUT_SECONDS,
    val geocoderTimeoutSeconds: Long = DEFAULT_GEOCODER_TIMEOUT_SECONDS,
) {
    val httpLlmConnectTimeout: Duration
        get() = Duration.ofSeconds(httpLlmTimeoutSeconds)

    val geocoderConnectTimeout: Duration
        get() = Duration.ofSeconds(geocoderTimeoutSeconds)

    companion object {
        const val DEFAULT_GRID_CELL_SIZE_DEG: Double = 0.05
        const val DEFAULT_POIS_PER_LEG: Int = 10
        const val DEFAULT_GEMINI_MODEL: String = "gemini-1.5-flash"
        const val DEFAULT_CLAUDE_MODEL: String = "claude-3-haiku-20240307"
        const val DEFAULT_OPENAI_MODEL: String = "gpt-4o-mini"
        const val DEFAULT_OLLAMA_MODEL: String = "gemma4:31b-mlx"
        const val DEFAULT_HTTP_LLM_TIMEOUT_SECONDS: Long = 15L
        const val DEFAULT_GEOCODER_TIMEOUT_SECONDS: Long = 10L

        /** Loads configuration populated with optional environment variable overrides. */
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            return Config(
                gridCellSizeDeg =
                    env["GRID_CELL_SIZE_DEG"]?.toDoubleOrNull() ?: DEFAULT_GRID_CELL_SIZE_DEG,
                defaultPoisPerLeg =
                    env["DEFAULT_POIS_PER_LEG"]?.toIntOrNull() ?: DEFAULT_POIS_PER_LEG,
                defaultGeminiModel =
                    env["DEFAULT_GEMINI_MODEL"] ?: env["GEMINI_MODEL"] ?: DEFAULT_GEMINI_MODEL,
                defaultClaudeModel =
                    env["DEFAULT_CLAUDE_MODEL"] ?: env["CLAUDE_MODEL"] ?: DEFAULT_CLAUDE_MODEL,
                defaultOpenAiModel =
                    env["DEFAULT_OPENAI_MODEL"] ?: env["OPENAI_MODEL"] ?: DEFAULT_OPENAI_MODEL,
                defaultOllamaModel =
                    env["DEFAULT_OLLAMA_MODEL"] ?: env["OLLAMA_MODEL"] ?: DEFAULT_OLLAMA_MODEL,
                httpLlmTimeoutSeconds =
                    env["HTTP_LLM_TIMEOUT_SECONDS"]?.toLongOrNull()
                        ?: DEFAULT_HTTP_LLM_TIMEOUT_SECONDS,
                geocoderTimeoutSeconds =
                    env["GEOCODER_TIMEOUT_SECONDS"]?.toLongOrNull()
                        ?: DEFAULT_GEOCODER_TIMEOUT_SECONDS,
            )
        }

        /** Default global configuration instance. */
        @Volatile var current: Config = fromEnv()
    }
}
