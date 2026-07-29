package com.pathpress.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.pathpress.config.Config
import com.pathpress.model.*
import java.net.http.HttpClient

fun String?.validateApiKey(type: LlmProviderType): String {
    val resolvedKey =
        if (!this.isNullOrBlank()) this else type.envVarName?.let { System.getenv(it) }

    require(!resolvedKey.isNullOrBlank()) {
        "API key missing for ${type.id}. Set ${type.envVarName} or pass --llm-key"
    }
    return resolvedKey
}

fun String?.validateApiKey(provider: String): String =
    this.validateApiKey(LlmProviderType.fromId(provider))

abstract class HttpLlmProvider(val config: Config = Config.current) : LlmProvider {
    protected val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(config.httpLlmConnectTimeout).build()
    protected val mapper = jacksonObjectMapper()

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
            If the theme/preferences mention scenic regions, coastal highways, beaches, mountains, or specific regional preferences (e.g. 'coastal', 'beach', 'mountain', 'scenic'), you MUST provide 2-4 intermediate spatial anchor towns/locations along that specific scenic corridor in the "waypoints" array (e.g., ["Monterey, CA", "Pismo Beach, CA"]).

            Provide a JSON response with:
            {
              "dayThemes": ["Day 1 title", "Day 2 title", ...],
              "waypoints": [
                {"name": "Monterey, CA", "lat": 36.6002, "lng": -121.8947},
                {"name": "Pismo Beach, CA", "lat": 35.1428, "lng": -120.6412}
              ],
              "narrative": "<REQUIRED: 2-3 sentence evocative description of the overall trip vibe, landscape, and theme. Must be specific to $startName → $endName and the theme above. Do NOT use generic filler like 'a wonderful journey'. Write like a travel magazine editor.>"
            }
            Return ONLY valid raw JSON. The "narrative" field is mandatory and must not be null or empty.
        """
            .trimIndent()
    }

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

    protected fun parseTripPlan(jsonText: String, days: Int): TripPlanResponse {
        val cleanJson = jsonText.substringAfter("{").substringBeforeLast("}").let { "{$it}" }

        // Extract narrative first independently - it's cheap and must survive any parse failure
        val narrativeFallback = extractNarrativeFromRawJson(jsonText)

        return try {
            val map: Map<String, Any> = mapper.readValue(cleanJson)
            val themes =
                (map["dayThemes"] as? List<*>)?.mapNotNull { it.toString() }
                    ?: (1..days).map { "Day $it" }
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
            TripPlanResponse(dayThemes = themes, waypoints = waypoints, narrative = narrative)
        } catch (_: Exception) {
            TripPlanResponse(
                dayThemes = (1..days).map { "Day $it" },
                waypoints = emptyList(),
                narrative = narrativeFallback ?: "",
            )
        }
    }

    /**
     * Regex-based fallback to extract the narrative string directly from the raw LLM JSON text.
     * This is intentionally decoupled from full JSON parsing so the narrative is not lost if other
     * fields (e.g. waypoints) cause a parse exception.
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
