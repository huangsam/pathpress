package com.pathpress.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.pathpress.config.Config
import com.pathpress.model.LocationCoords
import com.pathpress.model.RouteLeg
import com.pathpress.model.takeValidText
import java.net.http.HttpClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Extension helper to resolve and validate an API key for a given [LlmProviderType]. */
fun String?.validateApiKey(type: LlmProviderType): String {
    val resolvedKey = type.resolveApiKey(this)

    require(!resolvedKey.isNullOrBlank()) {
        "API key missing for ${type.id}. Set ${type.envVarName} or pass --llm-key"
    }
    return resolvedKey
}

/** Extension helper to resolve and validate an API key for a provider name string. */
fun String?.validateApiKey(provider: String): String =
    this.validateApiKey(LlmProviderType.fromId(provider))

/**
 * Base class for HTTP-based LLM providers (OpenAI, Gemini, Claude, Ollama).
 *
 * Provides shared prompt construction, grounding constraints, template method HTTP completion, and
 * resilient JSON parsing logic.
 */
abstract class HttpLlmProvider(val config: Config = Config.current) : LlmProvider {
    protected val logger: Logger = LoggerFactory.getLogger(javaClass)
    protected val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(config.httpLlmConnectTimeout).build()
    protected val mapper = jacksonObjectMapper()

    /**
     * Executes the provider-specific HTTP POST request for a given prompt string. Returns raw
     * string response text from the LLM provider, or null on HTTP/network error.
     */
    protected abstract fun complete(prompt: String): String?

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
            val responseText = complete(promptText)
            if (!responseText.isNullOrBlank()) {
                return parseTripPlan(responseText, days)
            }
        } catch (e: Exception) {
            logger.warn("LLM Provider warning: {}", e.message)
        }
        return NoOpFallbackProvider()
            .planTrip(startName, endName, startCoords, endCoords, days, userPrompt)
    }

    /**
     * Builds structured prompt for high-level trip planning. Instructs the LLM to output day
     * themes, intermediate waypoint anchors, and a trip narrative.
     */
    protected fun buildPrompt(
        startName: String,
        endName: String,
        days: Int,
        userPrompt: String?,
        recommendedTowns: List<String> = emptyList(),
    ): String {
        val promptDetail =
            userPrompt ?: "Scenic road trip highlighting nature, coastal views, and local cafes."
        val townRecsStr =
            if (recommendedTowns.isNotEmpty()) {
                "\nRECOMMENDED OVERNIGHT STOPS (ranked by verified family & lodging amenity density):\n" +
                    recommendedTowns.joinToString(", ") +
                    "\n"
            } else ""

        return """
            You are a master road trip planner. Design a $days-day road trip from $startName to $endName.
            Theme/Preferences: $promptDetail.$townRecsStr

            CRITICAL ROUTING INSTRUCTION:
            1. If the theme/preferences mention scenic regions, coastal highways, beaches, mountains, or specific regional preferences (e.g. 'coastal', 'beach', 'mountain', 'scenic'), you MUST provide 2-4 intermediate spatial anchor towns/locations along that specific scenic corridor in the "waypoints" array (e.g., ["Monterey, CA", "Pismo Beach, CA"]).
            2. Incorporate iconic regional landmarks and historical milestones along the route into the day-by-day narratives ("legStories") and overall narrative.

            Provide a JSON response with:
            {
              "legStories": [
                "1 concise sentence engaging description of driving Day 1.",
                "1 concise sentence engaging description of driving Day 2."
              ],
              "waypoints": [
                {"name": "Monterey, CA", "lat": 36.6002, "lng": -121.8947},
                {"name": "Pismo Beach, CA", "lat": 35.1428, "lng": -120.6412}
              ],
              "narrative": "<REQUIRED: 2 to 3 short, clear sentences (maximum 50 words total) summarizing the trip vibe and route from $startName to $endName. Keep sentences brief, direct, and easy to read. Do NOT write dense, multi-sentence purple prose or flowery fluff.>"
            }
            Return ONLY valid raw JSON. The "narrative" and "legStories" fields are mandatory and must not be null or empty.
        """
            .trimIndent()
    }

    /**
     * Parses raw LLM JSON response into a [TripPlanResponse]. Uses regex fallback
     * ([extractNarrativeFromRawJson]) to preserve narrative text if waypoint JSON deserialization
     * fails.
     */
    protected fun parseTripPlan(jsonText: String, days: Int): TripPlanResponse {
        val cleanJson = jsonText.substringAfter("{").substringBeforeLast("}").let { "{$it}" }

        // Extract narrative first independently - it's cheap and must survive any parse failure
        val narrativeFallback = extractNarrativeFromRawJson(jsonText)

        return try {
            val map: Map<String, Any> = mapper.readValue(cleanJson)
            val legStories =
                (map["legStories"] as? List<*>)?.mapNotNull { it.toString() } ?: emptyList()
            val narrative = map["narrative"]?.toString().takeValidText() ?: narrativeFallback ?: ""
            val waypoints =
                (map["waypoints"] as? List<*>)
                    ?.mapNotNull { w ->
                        when (w) {
                            is String -> {
                                val cleanName = w.trim()
                                if (cleanName.isNotBlank()) LocationCoords(0.0, 0.0, cleanName)
                                else null
                            }
                            is Map<*, *> -> {
                                val lat = (w["lat"] as? Number)?.toDouble() ?: 0.0
                                val lng = (w["lng"] as? Number)?.toDouble() ?: 0.0
                                val name =
                                    w["name"]?.toString()
                                        ?: w["location"]?.toString()
                                        ?: w["town"]?.toString()
                                if (lat != 0.0 || lng != 0.0 || !name.isNullOrBlank()) {
                                    LocationCoords(lat, lng, name)
                                } else null
                            }
                            else -> null
                        }
                    }
                    .orEmpty()
            TripPlanResponse(waypoints = waypoints, narrative = narrative, legStories = legStories)
        } catch (e: Exception) {
            logger.warn("Failed to parse LLM trip plan response: {}", e.message, e)
            TripPlanResponse(
                waypoints = emptyList(),
                narrative = narrativeFallback ?: "",
                legStories = emptyList(),
            )
        }
    }

    /**
     * Regex-based fallback to extract the narrative string directly from raw LLM JSON output.
     * Decoupled from Jackson JSON parsing so the narrative is preserved even if other fields fail
     * parsing.
     */
    private fun extractNarrativeFromRawJson(jsonText: String): String? {
        val match = Regex(""""narrative"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(jsonText) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\n", " ")
            .replace("\\\\", "\\")
            .trim()
            .takeValidText()
    }

    /**
     * Curates POIs using rule-based OSM metadata formatting rather than calling the LLM.
     *
     * Product Decision: HttpLlmProvider intentionally does not invoke the LLM for POI curation.
     * Rule-based curation via [RuleBasedCuration] eliminates LLM hallucinated facts, avoids
     * unnecessary API latency and cost, and guarantees 100% factual accuracy grounded in OSM tags.
     */
    final override fun curateLegPois(leg: RouteLeg, userPrompt: String?): CuratedLegResult {
        return RuleBasedCuration.curate(leg)
    }
}
