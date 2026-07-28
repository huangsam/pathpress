package com.pathpress.llm

import com.fasterxml.jackson.module.kotlin.readValue
import com.pathpress.config.Config
import com.pathpress.model.*
import com.pathpress.util.*
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(ClaudeProvider::class.java)

class ClaudeProvider(
    apiKey: String,
    config: Config = Config.current,
    val modelName: String = config.defaultClaudeModel,
) : HttpLlmProvider(config) {
    private val apiKey: String = apiKey.validateApiKey("claude")

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
                        "max_tokens" to 1024,
                        "messages" to listOf(mapOf("role" to "user", "content" to promptText)),
                    )
                )

            val uri = URI.create("https://api.anthropic.com/v1/messages")
            val request =
                HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val root: Map<String, Any> = mapper.readValue(response.body())
                val content = root["content"] as? List<*>
                val firstContent = content?.firstOrNull() as? Map<*, *>
                val text = firstContent?.get("text") as? String
                if (text.isNotBlankSafe()) {
                    return parseTripPlan(text, days)
                }
            }
        } catch (e: Exception) {
            logger.warn("Claude Provider warning: {}", e.message)
        }
        return NoOpFallbackProvider()
            .planTrip(startName, endName, startCoords, endCoords, days, userPrompt)
    }

    override fun curateLegPois(leg: RouteLeg, userPrompt: String?): CuratedLegResult {
        try {
            val promptText = buildCurationPrompt(leg, userPrompt)
            val requestBody =
                mapper.writeValueAsString(
                    mapOf(
                        "model" to modelName,
                        "max_tokens" to 1024,
                        "messages" to listOf(mapOf("role" to "user", "content" to promptText)),
                    )
                )
            val uri = URI.create("https://api.anthropic.com/v1/messages")
            val request =
                HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val root: Map<String, Any> = mapper.readValue(response.body())
                val content = root["content"] as? List<*>
                val firstContent = content?.firstOrNull() as? Map<*, *>
                val text = firstContent?.get("text") as? String
                if (text.isNotBlankSafe()) return parseCurationResponse(text, leg)
            }
        } catch (e: Exception) {
            logger.warn("Claude Curation warning: {}", e.message)
        }
        return NoOpFallbackProvider().curateLegPois(leg, userPrompt)
    }
}
