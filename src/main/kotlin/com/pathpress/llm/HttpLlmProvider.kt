package com.pathpress.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.pathpress.config.Config
import com.pathpress.model.LocationCoords
import com.pathpress.model.RouteLeg
import com.pathpress.model.takeValidText
import java.net.http.HttpClient

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
 * Provides shared prompt construction, grounding constraints, and resilient JSON parsing logic.
 */
abstract class HttpLlmProvider(val config: Config = Config.current) : LlmProvider {
    protected val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(config.httpLlmConnectTimeout).build()
    protected val mapper = jacksonObjectMapper()

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
            If the theme/preferences mention scenic regions, coastal highways, beaches, mountains, or specific regional preferences (e.g. 'coastal', 'beach', 'mountain', 'scenic'), you MUST provide 2-4 intermediate spatial anchor towns/locations along that specific scenic corridor in the "waypoints" array (e.g., ["Monterey, CA", "Pismo Beach, CA"]).

            Provide a JSON response with:
            {
              "legStories": [
                "1-2 sentence engaging description of driving Day 1.",
                "1-2 sentence engaging description of driving Day 2."
              ],
              "waypoints": [
                {"name": "Monterey, CA", "lat": 36.6002, "lng": -121.8947},
                {"name": "Pismo Beach, CA", "lat": 35.1428, "lng": -120.6412}
              ],
              "narrative": "<REQUIRED: 2-3 sentence evocative description of the overall trip vibe, landscape, and theme. Must be specific to $startName → $endName and the theme above. Do NOT use generic filler like 'a wonderful journey'. Write like a travel magazine editor.>"
            }
            Return ONLY valid raw JSON. The "narrative" and "legStories" fields are mandatory and must not be null or empty.
        """
            .trimIndent()
    }

    /**
     * Builds structured prompt for POI curation and leg story narration. Enforces strict grounding:
     * no hallucinated POIs, dates, or historical claims not found in OSM tags.
     */
    protected fun buildCurationPrompt(leg: RouteLeg, userPrompt: String?): String {
        val poiDetails =
            leg.pois.joinToString("\n") { poi ->
                val distStr =
                    poi.distanceFromRouteMeters?.let {
                        " (${String.format("%.1f", it / 1000.0)} km off route)"
                    } ?: ""
                val relevantTags =
                    poi.tags.filterKeys { key ->
                        key in
                            listOf(
                                "amenity",
                                "tourism",
                                "natural",
                                "historic",
                                "leisure",
                                "cuisine",
                                "opening_hours",
                                "addr:city",
                                "addr:street",
                                "addr:housenumber",
                                "website",
                                "phone",
                                "description",
                                "operator",
                                "brand",
                                "wheelchair",
                                "outdoor_seating",
                            )
                    }
                "- ${poi.name} | type: ${poi.type} | coords: (${poi.lat}, ${poi.lng})$distStr | osm_tags: $relevantTags"
            }
        val theme = userPrompt ?: "scenic road trip with local highlights"
        val legRegion = leg.endTownName ?: "the destination"

        return """
            You are an expert local tour guide writing copy for a real road trip itinerary.
            Curate Day ${leg.dayNumber} of a road trip ending near $legRegion.
            User vibe/preference: $theme.

            REAL OSM-VERIFIED POINTS OF INTEREST (do NOT replace these with other places):
            $poiDetails

            STRICT GROUNDING RULES — violations will confuse real travelers:
            1. Write ONLY about the exact POIs listed above. Do not suggest or invent other places.
            2. Do NOT invent founding dates, historical facts, menu items, or any detail not present in the osm_tags above.
            3. Do NOT apply coastal, ocean, or beach imagery to landlocked locations. Use the coords to infer geography.
            4. Descriptions must be atmosphere and vibe only — not factual claims about history or specific offerings unless confirmed in osm_tags.
            5. ACCESSIBILITY & SUITABILITY: Explicitly evaluate POI accessibility based on the user's trip prompt/vibe. If the user prompt mentions toddlers, kids, or family, reject any POIs requiring strenuous hiking, high elevation climbs, or non-child-friendly venues.

            Output ONLY valid raw JSON with this exact structure:
            {
              "legStory": "1-2 sentence engaging description of driving this leg toward $legRegion.",
              "poiDescriptions": {
                 "<POI Name>": "1-2 sentence atmosphere/vibe description. No invented facts."
              }
            }
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
        } catch (_: Exception) {
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

    override fun curateLegPois(leg: RouteLeg, userPrompt: String?): CuratedLegResult {
        return NoOpFallbackProvider().curateLegPois(leg, userPrompt)
    }

    /**
     * Parses raw LLM POI curation JSON response and maps description text to candidate [POI] items.
     * Falls back to [NoOpFallbackProvider] if JSON parsing fails.
     */
    protected fun parseCurationResponse(jsonText: String, leg: RouteLeg): CuratedLegResult {
        return try {
            val cleanJson = jsonText.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
            val map: Map<String, Any> = mapper.readValue(cleanJson)

            val legStory =
                map.getOrElse("legStory") {
                        "Day ${leg.dayNumber}: Scenic drive through local landmarks."
                    }
                    .toString()
            val poiDescMap =
                (map["poiDescriptions"] as? Map<*, *>)?.entries?.associate { (k, v) ->
                    k.toString().lowercase() to v.toString()
                } ?: emptyMap()

            val fallbackResult = NoOpFallbackProvider().curateLegPois(leg, null)

            val updatedPois =
                leg.pois.map { poi ->
                    val nameKey = poi.name?.lowercase()?.trim() ?: ""
                    val customDesc =
                        poiDescMap[nameKey]
                            ?: poiDescMap.entries
                                .firstOrNull { k ->
                                    val keyStr = k.key.trim()
                                    nameKey.contains(keyStr) || keyStr.contains(nameKey)
                                }
                                ?.value
                    val desc =
                        customDesc
                            ?: fallbackResult.curatedPois
                                .firstOrNull { it.id == poi.id }
                                ?.description
                    val tip =
                        poi.distanceFromRouteMeters?.let {
                            "Just ${String.format("%.1f", it / 1000.0)} km off route."
                        }
                    poi.copy(description = desc, insiderTip = tip)
                }

            CuratedLegResult(legStory = legStory, curatedPois = updatedPois)
        } catch (e: Exception) {
            NoOpFallbackProvider().curateLegPois(leg, null)
        }
    }
}
