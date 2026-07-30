package com.pathpress.llm

import com.fasterxml.jackson.module.kotlin.readValue
import com.pathpress.config.Config
import com.pathpress.model.LocationCoords
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(OpenAiCompatibleProvider::class.java)

/**
 * [HttpLlmProvider] implementation targeting OpenAI Chat Completions API (`/v1/chat/completions`)
 * or OpenAI-compatible local/remote servers (e.g. vLLM, LM Studio, LocalAI).
 *
 * Configures `Authorization: Bearer <key>` header when key is non-blank. Automatically falls back
 * to [NoOpFallbackProvider] on network failures, non-200 responses, or parse errors.
 */
class OpenAiCompatibleProvider(
    apiKey: String,
    private val endpoint: String,
    config: Config = Config.current,
    val modelName: String = config.defaultOpenAiModel,
) : HttpLlmProvider(config) {
    private val apiKey: String =
        if (endpoint.contains("localhost")) apiKey
        else apiKey.validateApiKey(LlmProviderType.OPENAI)

    override fun planTrip(
        startName: String,
        endName: String,
        startCoords: LocationCoords,
        endCoords: LocationCoords,
        days: Int,
        userPrompt: String?,
    ): TripPlanResponse {
        try {
            val promptText = buildPrompt(startName, endName, days, userPrompt)
            val requestBody =
                mapper.writeValueAsString(
                    mapOf(
                        "model" to modelName,
                        "messages" to
                            listOf(
                                mapOf(
                                    "role" to "system",
                                    "content" to
                                        "You are a helpful travel planner that outputs JSON.",
                                ),
                                mapOf("role" to "user", "content" to promptText),
                            ),
                    )
                )

            val uri = URI.create(endpoint)
            val builder =
                HttpRequest.newBuilder().uri(uri).header("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                builder.header("Authorization", "Bearer $apiKey")
            }
            val request = builder.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val root: Map<String, Any> = mapper.readValue(response.body())
                val choices = root["choices"] as? List<*>
                val firstChoice = choices?.firstOrNull() as? Map<*, *>
                val message = firstChoice?.get("message") as? Map<*, *>
                val text = message?.get("content") as? String
                if (!text.isNullOrBlank()) {
                    return parseTripPlan(text, days)
                }
            }
        } catch (e: Exception) {
            logger.warn("OpenAI Provider warning: {}", e.message)
        }
        return NoOpFallbackProvider()
            .planTrip(startName, endName, startCoords, endCoords, days, userPrompt)
    }
}
