package com.pathpress.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.pathpress.config.Config
import com.pathpress.model.*
import com.pathpress.util.*
import java.net.http.HttpClient

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

            Provide a JSON response with:
            {
              "dayThemes": ["Day 1 title", "Day 2 title", ...],
              "waypoints": [{"lat": 40.7128, "lng": -74.0060, "name": "New York"}],
              "narrative": "A short summary paragraph of the trip vibe."
            }
            Return ONLY valid raw JSON.
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
        return try {
            val map: Map<String, Any> = mapper.readValue(cleanJson)
            val themes =
                (map["dayThemes"] as? List<*>)?.mapNotNull { it.toString() }
                    ?: (1..days).map { "Day $it" }
            val narrative = map.getOrDefault("narrative") { "" }.toString()
            val waypoints =
                (map["waypoints"] as? List<*>)
                    ?.mapNotNull { w ->
                        (w as? Map<*, *>)?.let {
                            val lat = (it["lat"] as? Number)?.toDoubleSafe()
                            val lng = (it["lng"] as? Number)?.toDoubleSafe()
                            val name = it["name"]?.toString()
                            if (lat != null && lng != null) LocationCoords(lat, lng, name) else null
                        }
                    }
                    .orEmptyList()
            TripPlanResponse(dayThemes = themes, waypoints = waypoints, narrative = narrative)
        } catch (_: Exception) {
            TripPlanResponse(
                dayThemes = (1..days).map { "Day $it" },
                waypoints = emptyList(),
                narrative = "",
            )
        }
    }

    protected fun parseCurationResponse(jsonText: String, leg: RouteLeg): CuratedLegResult {
        return try {
            val cleanJson = jsonText.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
            val map: Map<String, Any> = mapper.readValue(cleanJson)

            val legStory =
                map.getOrDefault("legStory") {
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
