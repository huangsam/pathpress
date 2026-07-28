package com.pathpress.llm

import com.fasterxml.jackson.module.kotlin.readValue
import com.pathpress.config.Config
import com.pathpress.model.*
import com.pathpress.util.*
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(GeminiProvider::class.java)

class GeminiProvider(
    private val apiKey: String,
    config: Config = Config.current,
    val modelName: String = config.defaultGeminiModel,
) : HttpLlmProvider(config) {
    override fun planTrip(
        startName: String,
        endName: String,
        startCoords: LocationCoords,
        endCoords: LocationCoords,
        days: Int,
        userPrompt: String?,
    ): TripPlanResponse {
        if (apiKey.isBlank())
            return NoOpFallbackProvider()
                .planTrip(startName, endName, startCoords, endCoords, days, userPrompt)

        try {
            val promptText = buildPrompt(startName, endName, days, userPrompt)
            val requestBody =
                mapper.writeValueAsString(
                    mapOf(
                        "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to promptText))))
                    )
                )

            val uri =
                URI.create(
                    "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                )
            val request =
                HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val root: Map<String, Any> = mapper.readValue(response.body())
                val candidates = root["candidates"] as? List<*>
                val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
                val content = firstCandidate?.get("content") as? Map<*, *>
                val parts = content?.get("parts") as? List<*>
                val text = (parts?.firstOrNull() as? Map<*, *>)?.get("text") as? String

                if (text.isNotBlankSafe()) {
                    return parseTripPlan(text, days)
                }
            }
        } catch (e: Exception) {
            logger.warn("Gemini Provider warning: {}", e.message)
        }
        return NoOpFallbackProvider()
            .planTrip(startName, endName, startCoords, endCoords, days, userPrompt)
    }

    override fun curateLegPois(leg: RouteLeg, userPrompt: String?): CuratedLegResult {
        if (apiKey.isBlank()) return NoOpFallbackProvider().curateLegPois(leg, userPrompt)
        try {
            val promptText = buildCurationPrompt(leg, userPrompt)
            val requestBody =
                mapper.writeValueAsString(
                    mapOf(
                        "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to promptText))))
                    )
                )
            val uri =
                URI.create(
                    "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                )
            val request =
                HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val root: Map<String, Any> = mapper.readValue(response.body())
                val candidates = root["candidates"] as? List<*>
                val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
                val content = firstCandidate?.get("content") as? Map<*, *>
                val parts = content?.get("parts") as? List<*>
                val text = (parts?.firstOrNull() as? Map<*, *>)?.get("text") as? String
                if (text.isNotBlankSafe()) return parseCurationResponse(text, leg)
            }
        } catch (e: Exception) {
            logger.warn("Gemini Curation warning: {}", e.message)
        }
        return NoOpFallbackProvider().curateLegPois(leg, userPrompt)
    }
}
