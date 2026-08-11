package com.pathpress.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.pathpress.config.Config
import com.pathpress.model.DistanceUnit
import com.pathpress.model.LocationCoords
import com.pathpress.model.RouteLeg
import com.pathpress.model.takeValidText
import com.pathpress.poi.RuleBasedCuration
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

/**
 * Base class for HTTP-based LLM providers (OpenAI, Gemini, Claude, Ollama).
 *
 * Provides shared prompt construction, grounding constraints, template method HTTP completion, and
 * resilient JSON parsing logic.
 */
abstract class HttpLlmProvider(val config: Config = Config()) : LlmProvider {
    protected val logger: Logger = LoggerFactory.getLogger(javaClass)
    protected val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(config.httpLlmConnectTimeout).build()
    protected val mapper = jacksonObjectMapper()

    companion object {
        // Bounds waypoint fan-out (Nominatim geocodes + GraphHopper probes) per the prompted 2-4
        // anchors.
        private const val MAX_LLM_WAYPOINTS = 4
    }

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
                val fallbackNarrative =
                    NoOpFallbackProvider()
                        .planTrip(startName, endName, startCoords, endCoords, days, userPrompt)
                        .narrative
                return sanitizeFalsifiableSpecifics(parseTripPlan(responseText), fallbackNarrative)
            }
        } catch (e: Exception) {
            logger.warn("LLM Provider warning: {}", e.message)
        }
        return NoOpFallbackProvider()
            .planTrip(startName, endName, startCoords, endCoords, days, userPrompt)
    }

    /**
     * Post-filter guarding against LLM-fabricated road/highway specifics that slipped past the
     * prompt's ban. Any narrative matching [FalsifiableSpecificsFilter] is dropped: narrative
     * degrades to [fallbackNarrative] instead.
     */
    protected fun sanitizeFalsifiableSpecifics(
        response: TripPlanResponse,
        fallbackNarrative: String,
    ): TripPlanResponse {
        val safeNarrative =
            if (FalsifiableSpecificsFilter.containsRoadReference(response.narrative)) {
                logger.warn(
                    "Dropping LLM narrative: contained a falsifiable road/highway reference"
                )
                fallbackNarrative
            } else response.narrative
        return response.copy(narrative = safeNarrative)
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
    ): String {
        val promptDetail =
            userPrompt ?: "Scenic road trip highlighting nature, coastal views, and local cafes."

        return """
            You are a master road trip planner. Design a $days-day road trip from $startName to $endName.
            Theme/Preferences: $promptDetail.
            CRITICAL ROUTING INSTRUCTION:
            1. Provide 2-4 intermediate spatial anchor towns/locations along the route in the "waypoints" array (e.g. ["Monterey, CA", "Pismo Beach, CA"]).
            2. Incorporate iconic regional landmarks and historical milestones into the overall narrative.
            3. Do NOT state or invent specific road names, route numbers, or highway designations anywhere in the response (e.g. no "I-5", "US-101", "Highway 1"). Refer to towns, regions, and landmarks only.

            Provide a JSON response with:
            {
              "waypoints": ["Monterey, CA", "Pismo Beach, CA"],
              "narrative": "<REQUIRED: 2 to 3 short, clear sentences (maximum 50 words total) summarizing the trip vibe and route from $startName to $endName.>"
            }
            Return ONLY valid raw JSON. The "narrative" field is mandatory and must not be null or empty.
        """
            .trimIndent()
    }

    /**
     * Parses raw LLM JSON response into a [TripPlanResponse]. Uses regex fallback
     * ([extractNarrativeFromRawJson]) to preserve narrative text if waypoint JSON deserialization
     * fails.
     */
    protected fun parseTripPlan(jsonText: String): TripPlanResponse {
        val cleanJson = jsonText.substringAfter("{").substringBeforeLast("}").let { "{$it}" }

        // Extract narrative first independently - it's cheap and must survive any parse failure
        val narrativeFallback = extractNarrativeFromRawJson(jsonText)

        return try {
            val map: Map<String, Any> = mapper.readValue(cleanJson)
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
                    .take(MAX_LLM_WAYPOINTS)
            TripPlanResponse(waypoints = waypoints, narrative = narrative)
        } catch (e: Exception) {
            logger.warn("Failed to parse LLM trip plan response: {}", e.message, e)
            TripPlanResponse(waypoints = emptyList(), narrative = narrativeFallback ?: "")
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
     * Curates POIs via rule-based OSM tag formatting. Leg storytelling is fully deterministic
     * ([RuleBasedCuration]); the LLM is not consulted for this path.
     */
    final override fun curateLegPois(
        leg: RouteLeg,
        userPrompt: String?,
        unit: DistanceUnit,
    ): CuratedLegResult = RuleBasedCuration.curate(leg, unit)
}
