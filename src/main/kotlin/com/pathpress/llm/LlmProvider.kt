package com.pathpress.llm

import com.pathpress.config.Config
import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import com.pathpress.model.RouteLeg

/**
 * Encapsulates high-level itinerary planning response returned by an LLM provider.
 *
 * @property waypoints Intermediate spatial anchor towns or coordinates suggested by the LLM.
 * @property narrative Summary narrative describing the overall trip experience and landscape.
 */
data class TripPlanResponse(
    val waypoints: List<LocationCoords>,
    val narrative: String = "",
    val legStories: List<String> = emptyList(),
)

/**
 * Result of POI narration curation for a single route leg.
 *
 * @property legStory Engaging narrative summary describing the drive for this leg.
 * @property curatedPois List of POIs with LLM-enhanced or fallback descriptions and insider tips.
 */
data class CuratedLegResult(val legStory: String, val curatedPois: List<POI>)

/** Supported LLM provider backends and their API key requirements. */
enum class LlmProviderType(val id: String, val requiresApiKey: Boolean, val envVarName: String?) {
    GEMINI("gemini", requiresApiKey = true, envVarName = "GEMINI_API_KEY"),
    CLAUDE("claude", requiresApiKey = true, envVarName = "ANTHROPIC_API_KEY"),
    OPENAI("openai", requiresApiKey = true, envVarName = "OPENAI_API_KEY"),
    OLLAMA("ollama", requiresApiKey = false, envVarName = null),
    NONE("none", requiresApiKey = false, envVarName = null);

    /**
     * Resolves the API key from [providedKey] or falls back to environment variable [envVarName].
     */
    fun resolveApiKey(providedKey: String?): String? =
        providedKey.takeIf { !it.isNullOrBlank() } ?: envVarName?.let { System.getenv(it) }

    companion object {
        /** Map string provider identifiers (case-insensitive) to [LlmProviderType]. */
        fun fromId(id: String?): LlmProviderType {
            if (id.isNullOrBlank()) return NONE
            return when (val cleanId = id.lowercase().trim()) {
                "anthropic" -> CLAUDE
                else -> entries.firstOrNull { it.id == cleanId } ?: NONE
            }
        }
    }
}

/** Interface for LLM providers generating trip plans, waypoints, and POI narratives. */
interface LlmProvider {
    /**
     * Generate high-level trip plan with day themes, intermediate waypoints, and narrative.
     *
     * @param recommendedTowns Real, verified overnight-town names ranked along the direct
     *   start-to-end corridor (see [com.pathpress.poi.TownScorer]). Callers must compute these from
     *   an actual route polyline *before* invoking planTrip, since no route exists yet at this
     *   point in the pipeline. Grounding the LLM in these names steers it away from inventing
     *   waypoints that [com.pathpress.routing.WaypointValidator] would later reject.
     */
    fun planTrip(
        startName: String,
        endName: String,
        startCoords: LocationCoords,
        endCoords: LocationCoords,
        days: Int,
        userPrompt: String?,
        recommendedTowns: List<String> = emptyList(),
    ): TripPlanResponse

    /** Generate leg stories and curated POI descriptions grounded in real OSM tags. */
    fun curateLegPois(leg: RouteLeg, userPrompt: String?): CuratedLegResult

    companion object {
        /**
         * Factory function instantiating the requested [LlmProvider] instance (Gemini, Claude,
         * OpenAI, Ollama, or NoOpFallback).
         */
        fun create(
            providerName: String,
            apiKey: String?,
            apiUrl: String?,
            modelName: String? = null,
            config: Config = Config.current,
        ): LlmProvider {
            return when (val type = LlmProviderType.fromId(providerName)) {
                LlmProviderType.GEMINI ->
                    GeminiProvider(
                        apiKey = type.resolveApiKey(apiKey) ?: "",
                        modelName = modelName ?: config.defaultGeminiModel,
                        config = config,
                    )
                LlmProviderType.CLAUDE ->
                    ClaudeProvider(
                        apiKey = type.resolveApiKey(apiKey) ?: "",
                        modelName = modelName ?: config.defaultClaudeModel,
                        config = config,
                    )
                LlmProviderType.OPENAI ->
                    OpenAiCompatibleProvider(
                        apiKey = type.resolveApiKey(apiKey) ?: "",
                        endpoint = apiUrl ?: "https://api.openai.com/v1/chat/completions",
                        modelName = modelName ?: config.defaultOpenAiModel,
                        config = config,
                    )
                LlmProviderType.OLLAMA ->
                    OllamaProvider(
                        endpoint = apiUrl ?: "http://localhost:11434/api/chat",
                        modelName = modelName ?: config.defaultOllamaModel,
                        config = config,
                    )
                LlmProviderType.NONE -> NoOpFallbackProvider()
            }
        }
    }
}
