package com.pathpress.config

import java.time.Duration

/**
 * Centralized application configuration.
 *
 * Provides parameters for spatial grid partitioning, town scoring algorithms, LLM model defaults,
 * and network timeouts, with support for environment variable overrides via [fromEnv].
 *
 * @property gridCellSizeDeg Spatial indexing cell dimension in degrees (~0.05° is roughly 5.5 km or
 *   3.4 miles).
 * @property defaultPoisPerLeg Maximum number of POIs selected per leg segment.
 * @property defaultGeminiModel Default model identifier for Google Gemini API calls.
 * @property defaultClaudeModel Default model identifier for Anthropic Claude API calls.
 * @property defaultOpenAiModel Default model identifier for OpenAI API calls.
 * @property defaultOllamaModel Default model identifier for local Ollama API calls.
 * @property httpLlmTimeoutSeconds Network timeout in seconds for HTTP LLM requests.
 * @property geocoderTimeoutSeconds Network timeout in seconds for geocoding services.
 * @property townScoringRadiusMiles Geographic search radius (in miles) around candidate towns to
 *   count amenities.
 * @property townProgressWindowFraction Fraction of total leg distance used to form a search window
 *   for overnight stay candidates.
 * @property hotelWeight Relative scoring weight multiplier for hotel/lodging amenities.
 * @property familyWeight Relative scoring weight multiplier for family-friendly attractions.
 * @property diningWeight Relative scoring weight multiplier for dining options.
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
    val townScoringRadiusMiles: Double = DEFAULT_TOWN_SCORING_RADIUS_MILES,
    val townProgressWindowFraction: Double = DEFAULT_TOWN_PROGRESS_WINDOW_FRACTION,
    val hotelWeight: Int = DEFAULT_HOTEL_WEIGHT,
    val familyWeight: Int = DEFAULT_FAMILY_WEIGHT,
    val diningWeight: Int = DEFAULT_DINING_WEIGHT,
) {
    val httpLlmConnectTimeout: Duration
        get() = Duration.ofSeconds(httpLlmTimeoutSeconds)

    val geocoderConnectTimeout: Duration
        get() = Duration.ofSeconds(geocoderTimeoutSeconds)

    companion object {
        /** Default spatial grid bucket size in lat/lng degrees (0.05° ≈ 5.5 km / 3.4 miles). */
        const val DEFAULT_GRID_CELL_SIZE_DEG: Double = 0.05

        /** Default maximum POIs returned per route leg. */
        const val DEFAULT_POIS_PER_LEG: Int = 7

        /** Default Gemini model identifier. */
        const val DEFAULT_GEMINI_MODEL: String = "gemini-1.5-flash"

        /** Default Claude model identifier. */
        const val DEFAULT_CLAUDE_MODEL: String = "claude-3-haiku-20240307"

        /** Default OpenAI model identifier. */
        const val DEFAULT_OPENAI_MODEL: String = "gpt-4o-mini"

        /** Default local Ollama model identifier. */
        const val DEFAULT_OLLAMA_MODEL: String = "gemma4:31b-mlx"

        /** Default network timeout in seconds for HTTP LLM requests. */
        const val DEFAULT_HTTP_LLM_TIMEOUT_SECONDS: Long = 15L

        /** Default network timeout in seconds for geocoding requests. */
        const val DEFAULT_GEOCODER_TIMEOUT_SECONDS: Long = 10L

        /**
         * Search radius in miles around an overnight candidate town when counting local amenities.
         */
        const val DEFAULT_TOWN_SCORING_RADIUS_MILES: Double = 3.0

        /**
         * Fractional window (10% of route length) around target leg completion distance to evaluate
         * candidate towns.
         */
        const val DEFAULT_TOWN_PROGRESS_WINDOW_FRACTION: Double = 0.10

        /** Scoring multiplier for lodging amenities when evaluating overnight towns. */
        const val DEFAULT_HOTEL_WEIGHT: Int = 5

        /** Scoring multiplier for family activities when evaluating overnight towns. */
        const val DEFAULT_FAMILY_WEIGHT: Int = 3

        /** Scoring multiplier for dining options when evaluating overnight towns. */
        const val DEFAULT_DINING_WEIGHT: Int = 1

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
                townScoringRadiusMiles =
                    env["TOWN_SCORING_RADIUS_MILES"]?.toDoubleOrNull()
                        ?: DEFAULT_TOWN_SCORING_RADIUS_MILES,
                townProgressWindowFraction =
                    env["TOWN_PROGRESS_WINDOW_FRACTION"]?.toDoubleOrNull()
                        ?: DEFAULT_TOWN_PROGRESS_WINDOW_FRACTION,
                hotelWeight = env["TOWN_HOTEL_WEIGHT"]?.toIntOrNull() ?: DEFAULT_HOTEL_WEIGHT,
                familyWeight = env["TOWN_FAMILY_WEIGHT"]?.toIntOrNull() ?: DEFAULT_FAMILY_WEIGHT,
                diningWeight = env["TOWN_DINING_WEIGHT"]?.toIntOrNull() ?: DEFAULT_DINING_WEIGHT,
            )
        }

        /** Default global configuration instance. */
        @Volatile var current: Config = fromEnv()
    }
}
