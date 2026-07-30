package com.pathpress.llm

import com.fasterxml.jackson.module.kotlin.readValue
import com.pathpress.config.Config
import com.pathpress.model.LocationCoords
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(OllamaProvider::class.java)

/**
 * [HttpLlmProvider] implementation targeting local Ollama REST instances
 * (`http://localhost:11434/api/chat`).
 *
 * Requests structured JSON responses using `"format": "json"` mode without requiring an API key.
 * Automatically falls back to [NoOpFallbackProvider] on network failures, non-200 responses, or
 * parse errors.
 */
class OllamaProvider(
    private val endpoint: String,
    config: Config = Config.current,
    val modelName: String = config.defaultOllamaModel,
) : HttpLlmProvider(config) {
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
                        "stream" to false,
                        "format" to "json",
                        "options" to mapOf("temperature" to 0.2),
                    )
                )

            val uri = URI.create(endpoint)
            val request =
                HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val root: Map<String, Any> = mapper.readValue(response.body())
                val message = root["message"] as? Map<*, *>
                val responseText = message?.get("content") as? String
                if (!responseText.isNullOrBlank()) {
                    return parseTripPlan(responseText, days)
                }
            }
        } catch (e: Exception) {
            logger.warn("Ollama Provider warning: {}", e.message)
        }
        return NoOpFallbackProvider()
            .planTrip(startName, endName, startCoords, endCoords, days, userPrompt)
    }
}
