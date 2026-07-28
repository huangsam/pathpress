package com.pathpress.llm

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
        const val DEFAULT_GEMINI_MODEL = "gemini-1.5-flash"
        const val DEFAULT_CLAUDE_MODEL = "claude-3-haiku-20240307"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        const val DEFAULT_OLLAMA_MODEL = "gemma4:12b-mlx"

        fun create(
            providerName: String,
            apiKey: String?,
            apiUrl: String?,
            modelName: String? = null,
        ): LlmProvider {
            return when (providerName.lowercase()) {
                "gemini" ->
                    GeminiProvider(
                        apiKey = apiKey ?: System.getenv("GEMINI_API_KEY") ?: "",
                        modelName = modelName ?: DEFAULT_GEMINI_MODEL,
                    )
                "claude",
                "anthropic" ->
                    ClaudeProvider(
                        apiKey = apiKey ?: System.getenv("ANTHROPIC_API_KEY") ?: "",
                        modelName = modelName ?: DEFAULT_CLAUDE_MODEL,
                    )
                "openai" ->
                    OpenAiCompatibleProvider(
                        apiKey = apiKey ?: System.getenv("OPENAI_API_KEY") ?: "",
                        endpoint = apiUrl ?: "https://api.openai.com/v1/chat/completions",
                        modelName = modelName ?: DEFAULT_OPENAI_MODEL,
                    )
                "ollama" ->
                    OllamaProvider(
                        endpoint = apiUrl ?: "http://localhost:11434/api/chat",
                        modelName = modelName ?: DEFAULT_OLLAMA_MODEL,
                    )
                else -> NoOpFallbackProvider()
            }
        }
    }
}
