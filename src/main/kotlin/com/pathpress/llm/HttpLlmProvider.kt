package com.pathpress.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.pathpress.config.Config
import com.pathpress.model.DistanceUnit
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
                return sanitizeFalsifiableSpecifics(
                    parseTripPlan(responseText, days),
                    fallbackNarrative,
                )
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
            1. If the theme/preferences mention scenic regions, coastal highways, beaches, mountains, or specific regional preferences (e.g. 'coastal', 'beach', 'mountain', 'scenic'), you MUST provide 2-4 intermediate spatial anchor towns/locations along that specific scenic corridor in the "waypoints" array (e.g., ["Monterey, CA", "Pismo Beach, CA"]).
            2. Incorporate iconic regional landmarks and historical milestones along the route into the overall narrative.
            3. Do NOT state or invent specific road names, route numbers, or highway designations anywhere in the response (e.g. do NOT write "I-5", "US-101", "Highway 1", "Route 66"). Refer to towns, regions, and landmarks only — real road data comes exclusively from routing/mapping data, not from you.

            Provide a JSON response with:
            {
              "waypoints": [
                {"name": "Monterey, CA", "lat": 36.6002, "lng": -121.8947},
                {"name": "Pismo Beach, CA", "lat": 35.1428, "lng": -120.6412}
              ],
              "narrative": "<REQUIRED: 2 to 3 short, clear sentences (maximum 50 words total) summarizing the trip vibe and route from $startName to $endName. Keep sentences brief, direct, and easy to read. Do NOT write dense, multi-sentence purple prose or flowery fluff.>"
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
    protected fun parseTripPlan(jsonText: String, days: Int): TripPlanResponse {
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
     * Curates POIs via rule-based OSM tag formatting (never LLM-generated, per
     * [RuleBasedCuration]), then asks the LLM for a single fact-grounded legStory sentence built
     * ONLY from the leg's real distance, end town, and POI names. The response is regex-validated
     * ([FalsifiableSpecificsFilter]); a blank response, network/parse failure, or road/highway
     * reference drops back to [RuleBasedCuration]'s deterministic story.
     */
    final override fun curateLegPois(
        leg: RouteLeg,
        userPrompt: String?,
        unit: DistanceUnit,
    ): CuratedLegResult {
        val ruleBased = RuleBasedCuration.curate(leg)
        val namedPois = leg.pois.mapNotNull { it.name.takeValidText() }
        val firstPoiName = namedPois.firstOrNull()
        val poiCount = namedPois.size
        return try {
            val prompt =
                buildLegStoryPrompt(
                    dayNumber = leg.dayNumber,
                    distanceMeters = leg.distanceMeters ?: 0.0,
                    endTownName = leg.endTownName,
                    firstPoiName = firstPoiName,
                    totalPoiCount = poiCount,
                    unit = unit,
                )
            val story = complete(prompt)?.trim()?.trim('"', '\u201C', '\u201D')
            if (story.isNullOrBlank() || FalsifiableSpecificsFilter.containsRoadReference(story)) {
                logger.warn("Dropping LLM legStory: blank or falsifiable road/highway reference")
                ruleBased
            } else {
                ruleBased.copy(legStory = story)
            }
        } catch (e: Exception) {
            logger.warn("LLM leg-story generation failed: {}", e.message)
            ruleBased
        }
    }

    /**
     * Builds a fact-grounded prompt requesting exactly one sentence describing a day's drive, using
     * ONLY the real distance, end town, and optionally the first POI + a count of remaining POIs
     * extracted from OSM/GraphHopper.
     */
    protected fun buildLegStoryPrompt(
        dayNumber: Int,
        distanceMeters: Double,
        endTownName: String?,
        firstPoiName: String?,
        totalPoiCount: Int,
        unit: DistanceUnit,
    ): String {
        val distanceValue =
            if (unit == DistanceUnit.IMPERIAL) distanceMeters / 1609.344
            else distanceMeters / 1000.0
        val unitLabel = if (unit == DistanceUnit.IMPERIAL) "mi" else "km"
        val distanceStr = String.format(java.util.Locale.US, "%.1f", distanceValue)
        val townStr = endTownName ?: "the destination town"
        val poiClause =
            when (totalPoiCount) {
                0 -> ""
                1 -> " passing $firstPoiName"
                else -> " passing $firstPoiName and ${totalPoiCount - 1} more places"
            }

        return """
            Write 1 sentence describing day $dayNumber of a road trip: $distanceStr$unitLabel ending in $townStr$poiClause.
            Use ONLY these facts - never name a road, highway, or route number, and never add landmarks not listed.
            Return ONLY the sentence, no quotes, no JSON, no extra commentary.
        """
            .trimIndent()
    }
}
