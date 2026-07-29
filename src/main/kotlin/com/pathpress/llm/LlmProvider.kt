package com.pathpress.llm

import com.pathpress.config.Config
import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import com.pathpress.model.RouteLeg

data class TripPlanResponse(
    val dayThemes: List<String>,
    val waypoints: List<LocationCoords>,
    val curatedPois: Map<Int, List<POI>> = emptyMap(),
    val narrative: String = "",
)

data class CuratedLegResult(val legStory: String, val curatedPois: List<POI>)

enum class LlmProviderType(val id: String, val requiresApiKey: Boolean, val envVarName: String?) {
    GEMINI("gemini", requiresApiKey = true, envVarName = "GEMINI_API_KEY"),
    CLAUDE("claude", requiresApiKey = true, envVarName = "ANTHROPIC_API_KEY"),
    OPENAI("openai", requiresApiKey = true, envVarName = "OPENAI_API_KEY"),
    OLLAMA("ollama", requiresApiKey = false, envVarName = null),
    NONE("none", requiresApiKey = false, envVarName = null);

    companion object {
        fun fromId(id: String?): LlmProviderType {
            if (id.isNullOrBlank()) return NONE
            return when (val cleanId = id.lowercase().trim()) {
                "anthropic" -> CLAUDE
                else -> entries.firstOrNull { it.id == cleanId } ?: NONE
            }
        }
    }
}

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
            val type = LlmProviderType.fromId(providerName)
            return when (type) {
                LlmProviderType.GEMINI ->
                    GeminiProvider(
                        apiKey = apiKey ?: System.getenv(LlmProviderType.GEMINI.envVarName) ?: "",
                        modelName = modelName ?: config.defaultGeminiModel,
                        config = config,
                    )
                LlmProviderType.CLAUDE ->
                    ClaudeProvider(
                        apiKey = apiKey ?: System.getenv(LlmProviderType.CLAUDE.envVarName) ?: "",
                        modelName = modelName ?: config.defaultClaudeModel,
                        config = config,
                    )
                LlmProviderType.OPENAI ->
                    OpenAiCompatibleProvider(
                        apiKey = apiKey ?: System.getenv(LlmProviderType.OPENAI.envVarName) ?: "",
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
