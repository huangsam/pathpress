package com.pathpress.llm

import com.pathpress.config.Config
import com.pathpress.model.*

data class TripPlanResponse(
    val dayThemes: List<String>,
    val waypoints: List<LocationCoords>,
    val curatedPois: Map<Int, List<POI>> = emptyMap(),
    val narrative: String = "",
)

data class CuratedLegResult(val legStory: String, val curatedPois: List<POI>)

/** Interface for LLM providers (Gemini, Claude, OpenAI, Ollama, and Fallback). */
interface LlmProvider {
    fun planTrip(
        startName: String,
        endName: String,
        startCoords: LocationCoords,
        endCoords: LocationCoords,
        days: Int,
        userPrompt: String?,
    ): TripPlanResponse

    fun curateLegPois(leg: RouteLeg, userPrompt: String?): CuratedLegResult

    companion object {
        fun create(
            providerName: String,
            apiKey: String?,
            apiUrl: String?,
            modelName: String? = null,
            config: Config = Config.current,
        ): LlmProvider {
            return when (providerName.lowercase()) {
                "gemini" ->
                    GeminiProvider(
                        apiKey = apiKey ?: System.getenv("GEMINI_API_KEY") ?: "",
                        modelName = modelName ?: config.defaultGeminiModel,
                        config = config,
                    )
                "claude",
                "anthropic" ->
                    ClaudeProvider(
                        apiKey = apiKey ?: System.getenv("ANTHROPIC_API_KEY") ?: "",
                        modelName = modelName ?: config.defaultClaudeModel,
                        config = config,
                    )
                "openai" ->
                    OpenAiCompatibleProvider(
                        apiKey = apiKey ?: System.getenv("OPENAI_API_KEY") ?: "",
                        endpoint = apiUrl ?: "https://api.openai.com/v1/chat/completions",
                        modelName = modelName ?: config.defaultOpenAiModel,
                        config = config,
                    )
                "ollama" ->
                    OllamaProvider(
                        endpoint = apiUrl ?: "http://localhost:11434/api/chat",
                        modelName = modelName ?: config.defaultOllamaModel,
                        config = config,
                    )
                else -> NoOpFallbackProvider()
            }
        }
    }
}
