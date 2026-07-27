package com.pathpress.core

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class TripPlanResponse(
    val dayThemes: List<String>,
    val waypoints: List<LocationCoords>,
    val curatedPois: Map<Int, List<POI>> = emptyMap(),
    val narrative: String = ""
)

data class CuratedLegResult(
    val legStory: String,
    val curatedPois: List<POI>,
    val foodRecommendations: List<String>,
    val insiderTips: List<String>
)

/**
 * Interface for LLM providers (Gemini, Claude, OpenAI, Ollama, and Fallback).
 */
interface LlmProvider {
    fun planTrip(
        startName: String,
        endName: String,
        startCoords: LocationCoords,
        endCoords: LocationCoords,
        days: Int,
        userPrompt: String?
    ): TripPlanResponse

    fun curateLegPois(
        leg: RouteLeg,
        userPrompt: String?
    ): CuratedLegResult

    companion object {
        fun create(
            providerName: String,
            apiKey: String?,
            apiUrl: String?,
            modelName: String? = null
        ): LlmProvider {
            return when (providerName.lowercase()) {
                "gemini" -> GeminiProvider(apiKey ?: System.getenv("GEMINI_API_KEY") ?: "")
                "claude", "anthropic" -> ClaudeProvider(apiKey ?: System.getenv("ANTHROPIC_API_KEY") ?: "")
                "openai" -> OpenAiCompatibleProvider(
                    apiKey = apiKey ?: System.getenv("OPENAI_API_KEY") ?: "",
                    endpoint = apiUrl ?: "https://api.openai.com/v1/chat/completions"
                )
                "ollama" -> OllamaProvider(
                    endpoint = apiUrl ?: "http://localhost:11434/api/generate",
                    modelName = modelName ?: "qwen3.6:35b-mlx"
                )
                else -> NoOpFallbackProvider()
            }
        }
    }
}

/**
 * Fallback provider when no LLM is specified or available.
 */
class NoOpFallbackProvider : LlmProvider {
    override fun planTrip(
        startName: String,
        endName: String,
        startCoords: LocationCoords,
        endCoords: LocationCoords,
        days: Int,
        userPrompt: String?
    ): TripPlanResponse {
        val themes = (1..days).map { day -> "Day $day: Scenic Drive leg from $startName towards $endName" }
        return TripPlanResponse(
            dayThemes = themes,
            waypoints = emptyList(),
            narrative = "A custom road trip experience designed with PathPress."
        )
    }

    override fun curateLegPois(
        leg: RouteLeg,
        userPrompt: String?
    ): CuratedLegResult {
        val legTitle = leg.endTownName?.let { "Drive to $it" } ?: "Day ${leg.dayNumber} Scenic Leg"
        val story = "Day ${leg.dayNumber}: Enjoy a scenic drive along $legTitle, discovering vibrant local culture and natural landmarks."

        val updatedPois = leg.pois.map { poi ->
            val poiName = poi.name ?: "Point of Interest"
            val desc = when {
                poi.isFoodOrCoffee -> "A local favorite spot along your route offering coffee, fresh treats, and local food."
                poi.type in listOf("viewpoint", "attraction") -> "A scenic viewpoint providing sweeping views of the surrounding landscapes."
                poi.type in listOf("park", "nature_reserve", "beach") -> "A serene natural highlight ideal for a quick walk, fresh air, and relaxation."
                poi.type in listOf("museum", "historic", "monument") -> "A cultural landmark showcasing the rich heritage of the area."
                else -> "A recommended local stop conveniently located near your driving route."
            }
            val tip = if (poi.distanceFromRouteMeters != null) {
                "Located just ${String.format("%.1f", poi.distanceFromRouteMeters / 1000.0)} km off the route."
            } else "Easy access from the main road."

            poi.copy(description = desc, insiderTip = tip)
        }

        val foodPois = updatedPois.filter { it.isFoodOrCoffee }
        val foodRecs = if (foodPois.isNotEmpty()) {
            foodPois.take(2).map { "Stop by ${it.name} for local drinks and food recommendations." }
        } else {
            listOf("Keep an eye out for roadside farm stands and artisanal cafes near town centers.")
        }

        val insiderTips = listOf(
            "Plan your stops during mid-morning or golden hour for the best scenic views.",
            "Download offline maps for rural road segments along this leg."
        )

        return CuratedLegResult(
            legStory = story,
            curatedPois = updatedPois,
            foodRecommendations = foodRecs,
            insiderTips = insiderTips
        )
    }
}

abstract class HttpLlmProvider : LlmProvider {
    protected val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    protected val mapper = jacksonObjectMapper()

    protected fun buildPrompt(
        startName: String,
        endName: String,
        days: Int,
        userPrompt: String?
    ): String {
        val promptDetail = userPrompt ?: "Scenic road trip highlighting nature, coastal views, and local cafes."
        return """
            You are a master road trip planner. Design a $days-day road trip from $startName to $endName.
            Theme/Preferences: $promptDetail.

            Provide a JSON response with:
            {
              "dayThemes": ["Day 1 title", "Day 2 title", ...],
              "narrative": "A short summary paragraph of the trip vibe."
            }
            Return ONLY valid raw JSON.
        """.trimIndent()
    }

    protected fun buildCurationPrompt(leg: RouteLeg, userPrompt: String?): String {
        val poiDetails = leg.pois.joinToString("\n") { poi ->
            val distStr = poi.distanceFromRouteMeters?.let { " (${String.format("%.1f", it / 1000.0)} km off route)" } ?: ""
            "- ${poi.name} (type: ${poi.type}, tags: ${poi.tags})$distStr"
        }
        val theme = userPrompt ?: "scenic road trip with local highlights"

        return """
            You are an expert local tour guide and storyteller.
            Curate Day ${leg.dayNumber} of a road trip.
            User vibe/preference: $theme.
            Leg destination/town: ${leg.endTownName ?: "scenic leg destination"}.

            Real extracted OSM POIs:
            $poiDetails

            Output ONLY valid raw JSON with this exact structure:
            {
              "legStory": "1-2 sentence engaging description of driving this leg.",
              "poiDescriptions": {
                 "<POI Name>": "1-2 sentence engaging description and story for this spot."
              },
              "foodRecommendations": [
                 "Coffee/food recommendation line 1",
                 "Coffee/food recommendation line 2"
              ],
              "insiderTips": [
                 "Practical tip 1",
                 "Practical tip 2"
              ]
            }
        """.trimIndent()
    }

    protected fun parseCurationResponse(jsonText: String, leg: RouteLeg): CuratedLegResult {
        return try {
            val cleanJson = jsonText.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
            val map: Map<String, Any> = mapper.readValue(cleanJson)

            val legStory = map["legStory"]?.toString() ?: "Day ${leg.dayNumber}: Scenic drive through local landmarks."
            val poiDescMap = (map["poiDescriptions"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString().lowercase() to v.toString() } ?: emptyMap()
            val foodRecs = (map["foodRecommendations"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val insiderTips = (map["insiderTips"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

            val fallbackResult = NoOpFallbackProvider().curateLegPois(leg, null)

            val updatedPois = leg.pois.map { poi ->
                val nameKey = poi.name?.lowercase() ?: ""
                val customDesc = poiDescMap[nameKey] ?: poiDescMap.entries.firstOrNull { nameKey.contains(it.key) }?.value
                val desc = customDesc ?: fallbackResult.curatedPois.firstOrNull { it.id == poi.id }?.description
                val tip = poi.distanceFromRouteMeters?.let { "Just ${String.format("%.1f", it / 1000.0)} km off route." }
                poi.copy(description = desc, insiderTip = tip)
            }

            CuratedLegResult(
                legStory = legStory,
                curatedPois = updatedPois,
                foodRecommendations = foodRecs.ifEmpty { fallbackResult.foodRecommendations },
                insiderTips = insiderTips.ifEmpty { fallbackResult.insiderTips }
            )
        } catch (e: Exception) {
            NoOpFallbackProvider().curateLegPois(leg, null)
        }
    }
}

class GeminiProvider(private val apiKey: String) : HttpLlmProvider() {
    override fun planTrip(
        startName: String,
        endName: String,
        startCoords: LocationCoords,
        endCoords: LocationCoords,
        days: Int,
        userPrompt: String?
    ): TripPlanResponse {
        if (apiKey.isBlank()) return NoOpFallbackProvider().planTrip(startName, endName, startCoords, endCoords, days, userPrompt)

        try {
            val promptText = buildPrompt(startName, endName, days, userPrompt)
            val requestBody = mapper.writeValueAsString(
                mapOf(
                    "contents" to listOf(
                        mapOf("parts" to listOf(mapOf("text" to promptText)))
                    )
                )
            )

            val uri = URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
            val request = HttpRequest.newBuilder()
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

                if (!text.isNullOrBlank()) {
                    return parseJsonResponse(text, days)
                }
            }
        } catch (e: Exception) {
            println("Gemini Provider warning: ${e.message}")
        }
        return NoOpFallbackProvider().planTrip(startName, endName, startCoords, endCoords, days, userPrompt)
    }

    override fun curateLegPois(leg: RouteLeg, userPrompt: String?): CuratedLegResult {
        if (apiKey.isBlank()) return NoOpFallbackProvider().curateLegPois(leg, userPrompt)
        try {
            val promptText = buildCurationPrompt(leg, userPrompt)
            val requestBody = mapper.writeValueAsString(
                mapOf("contents" to listOf(mapOf("parts" to listOf(mapOf("text" to promptText)))))
            )
            val uri = URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
            val request = HttpRequest.newBuilder().uri(uri).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(requestBody)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val root: Map<String, Any> = mapper.readValue(response.body())
                val candidates = root["candidates"] as? List<*>
                val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
                val content = firstCandidate?.get("content") as? Map<*, *>
                val parts = content?.get("parts") as? List<*>
                val text = (parts?.firstOrNull() as? Map<*, *>)?.get("text") as? String
                if (!text.isNullOrBlank()) return parseCurationResponse(text, leg)
            }
        } catch (e: Exception) {
            println("Gemini Curation warning: ${e.message}")
        }
        return NoOpFallbackProvider().curateLegPois(leg, userPrompt)
    }

    private fun parseJsonResponse(rawText: String, days: Int): TripPlanResponse {
        val cleanJson = rawText.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
        return try {
            val map: Map<String, Any> = mapper.readValue(cleanJson)
            val themes = (map["dayThemes"] as? List<*>)?.mapNotNull { it.toString() } ?: (1..days).map { "Day $it" }
            val narrative = map["narrative"]?.toString() ?: ""
            TripPlanResponse(dayThemes = themes, waypoints = emptyList(), narrative = narrative)
        } catch (_: Exception) {
            TripPlanResponse(dayThemes = (1..days).map { "Day $it" }, waypoints = emptyList(), narrative = "")
        }
    }
}

class ClaudeProvider(private val apiKey: String) : HttpLlmProvider() {
    override fun planTrip(
        startName: String,
        endName: String,
        startCoords: LocationCoords,
        endCoords: LocationCoords,
        days: Int,
        userPrompt: String?
    ): TripPlanResponse {
        if (apiKey.isBlank()) return NoOpFallbackProvider().planTrip(startName, endName, startCoords, endCoords, days, userPrompt)
        try {
            val promptText = buildPrompt(startName, endName, days, userPrompt)
            val requestBody = mapper.writeValueAsString(
                mapOf(
                    "model" to "claude-3-haiku-20240307",
                    "max_tokens" to 1024,
                    "messages" to listOf(mapOf("role" to "user", "content" to promptText))
                )
            )

            val uri = URI.create("https://api.anthropic.com/v1/messages")
            val request = HttpRequest.newBuilder()
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
                if (!text.isNullOrBlank()) {
                    val cleanJson = text.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
                    val map: Map<String, Any> = mapper.readValue(cleanJson)
                    val themes = (map["dayThemes"] as? List<*>)?.mapNotNull { it.toString() } ?: (1..days).map { "Day $it" }
                    val narrative = map["narrative"]?.toString() ?: ""
                    return TripPlanResponse(dayThemes = themes, waypoints = emptyList(), narrative = narrative)
                }
            }
        } catch (e: Exception) {
            println("Claude Provider warning: ${e.message}")
        }
        return NoOpFallbackProvider().planTrip(startName, endName, startCoords, endCoords, days, userPrompt)
    }

    override fun curateLegPois(leg: RouteLeg, userPrompt: String?): CuratedLegResult {
        if (apiKey.isBlank()) return NoOpFallbackProvider().curateLegPois(leg, userPrompt)
        try {
            val promptText = buildCurationPrompt(leg, userPrompt)
            val requestBody = mapper.writeValueAsString(
                mapOf(
                    "model" to "claude-3-haiku-20240307",
                    "max_tokens" to 1024,
                    "messages" to listOf(mapOf("role" to "user", "content" to promptText))
                )
            )
            val uri = URI.create("https://api.anthropic.com/v1/messages")
            val request = HttpRequest.newBuilder().uri(uri).header("Content-Type", "application/json")
                .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val root: Map<String, Any> = mapper.readValue(response.body())
                val content = root["content"] as? List<*>
                val firstContent = content?.firstOrNull() as? Map<*, *>
                val text = firstContent?.get("text") as? String
                if (!text.isNullOrBlank()) return parseCurationResponse(text, leg)
            }
        } catch (e: Exception) {
            println("Claude Curation warning: ${e.message}")
        }
        return NoOpFallbackProvider().curateLegPois(leg, userPrompt)
    }
}

class OpenAiCompatibleProvider(
    private val apiKey: String,
    private val endpoint: String
) : HttpLlmProvider() {
    override fun planTrip(
        startName: String,
        endName: String,
        startCoords: LocationCoords,
        endCoords: LocationCoords,
        days: Int,
        userPrompt: String?
    ): TripPlanResponse {
        if (apiKey.isBlank() && !endpoint.contains("localhost")) {
            return NoOpFallbackProvider().planTrip(startName, endName, startCoords, endCoords, days, userPrompt)
        }
        try {
            val promptText = buildPrompt(startName, endName, days, userPrompt)
            val requestBody = mapper.writeValueAsString(
                mapOf(
                    "model" to "gpt-4o-mini",
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to "You are a helpful travel planner that outputs JSON."),
                        mapOf("role" to "user", "content" to promptText)
                    )
                )
            )

            val uri = URI.create(endpoint)
            val builder = HttpRequest.newBuilder().uri(uri).header("Content-Type", "application/json")
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
                    val cleanJson = text.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
                    val map: Map<String, Any> = mapper.readValue(cleanJson)
                    val themes = (map["dayThemes"] as? List<*>)?.mapNotNull { it.toString() } ?: (1..days).map { "Day $it" }
                    val narrative = map["narrative"]?.toString() ?: ""
                    return TripPlanResponse(dayThemes = themes, waypoints = emptyList(), narrative = narrative)
                }
            }
        } catch (e: Exception) {
            println("OpenAI Provider warning: ${e.message}")
        }
        return NoOpFallbackProvider().planTrip(startName, endName, startCoords, endCoords, days, userPrompt)
    }

    override fun curateLegPois(leg: RouteLeg, userPrompt: String?): CuratedLegResult {
        if (apiKey.isBlank() && !endpoint.contains("localhost")) return NoOpFallbackProvider().curateLegPois(leg, userPrompt)
        try {
            val promptText = buildCurationPrompt(leg, userPrompt)
            val requestBody = mapper.writeValueAsString(
                mapOf(
                    "model" to "gpt-4o-mini",
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to "You are a local travel guide that outputs raw JSON."),
                        mapOf("role" to "user", "content" to promptText)
                    )
                )
            )
            val uri = URI.create(endpoint)
            val builder = HttpRequest.newBuilder().uri(uri).header("Content-Type", "application/json")
            if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
            val request = builder.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val root: Map<String, Any> = mapper.readValue(response.body())
                val choices = root["choices"] as? List<*>
                val firstChoice = choices?.firstOrNull() as? Map<*, *>
                val message = firstChoice?.get("message") as? Map<*, *>
                val text = message?.get("content") as? String
                if (!text.isNullOrBlank()) return parseCurationResponse(text, leg)
            }
        } catch (e: Exception) {
            println("OpenAI Curation warning: ${e.message}")
        }
        return NoOpFallbackProvider().curateLegPois(leg, userPrompt)
    }
}

class OllamaProvider(
    private val endpoint: String,
    private val modelName: String = "qwen3.6:35b-mlx"
) : HttpLlmProvider() {
    override fun planTrip(
        startName: String,
        endName: String,
        startCoords: LocationCoords,
        endCoords: LocationCoords,
        days: Int,
        userPrompt: String?
    ): TripPlanResponse {
        try {
            val promptText = buildPrompt(startName, endName, days, userPrompt)
            val requestBody = mapper.writeValueAsString(
                mapOf(
                    "model" to modelName,
                    "prompt" to promptText,
                    "stream" to false
                )
            )

            val uri = URI.create(endpoint)
            val request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val root: Map<String, Any> = mapper.readValue(response.body())
                val responseText = root["response"]?.toString()
                if (!responseText.isNullOrBlank()) {
                    val cleanJson = responseText.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
                    val map: Map<String, Any> = mapper.readValue(cleanJson)
                    val themes = (map["dayThemes"] as? List<*>)?.mapNotNull { it.toString() } ?: (1..days).map { "Day $it" }
                    val narrative = map["narrative"]?.toString() ?: ""
                    return TripPlanResponse(dayThemes = themes, waypoints = emptyList(), narrative = narrative)
                }
            }
        } catch (e: Exception) {
            println("Ollama Provider warning: ${e.message}")
        }
        return NoOpFallbackProvider().planTrip(startName, endName, startCoords, endCoords, days, userPrompt)
    }

    override fun curateLegPois(leg: RouteLeg, userPrompt: String?): CuratedLegResult {
        try {
            val promptText = buildCurationPrompt(leg, userPrompt)
            val requestBody = mapper.writeValueAsString(
                mapOf(
                    "model" to modelName,
                    "prompt" to promptText,
                    "stream" to false
                )
            )
            val uri = URI.create(endpoint)
            val request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val root: Map<String, Any> = mapper.readValue(response.body())
                val responseText = root["response"]?.toString()
                if (!responseText.isNullOrBlank()) {
                    return parseCurationResponse(responseText, leg)
                }
            }
        } catch (e: Exception) {
            println("Ollama Curation warning: ${e.message}")
        }
        return NoOpFallbackProvider().curateLegPois(leg, userPrompt)
    }
}

