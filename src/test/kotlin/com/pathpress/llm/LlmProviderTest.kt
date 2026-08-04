package com.pathpress.llm

import com.pathpress.config.Config
import com.pathpress.model.DistanceUnit
import com.pathpress.model.LocationCoords
import com.pathpress.model.POI
import com.pathpress.model.RouteLeg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LlmProviderTest {

    @Test
    fun `LlmProvider factory creates NoOpFallbackProvider for unknown provider`() {
        val provider = LlmProvider.create("unknown", null, null)
        assertIs<NoOpFallbackProvider>(provider)
    }

    @Test
    fun `LlmProvider factory configures default and custom models for providers`() {
        val defaultGemini = LlmProvider.create("gemini", apiKey = "test-key", apiUrl = null)
        assertIs<GeminiProvider>(defaultGemini)
        assertEquals(Config.current.defaultGeminiModel, defaultGemini.modelName)

        val customGemini =
            LlmProvider.create(
                "gemini",
                apiKey = "test-key",
                apiUrl = null,
                modelName = "gemini-2.0-flash",
            )
        assertIs<GeminiProvider>(customGemini)
        assertEquals("gemini-2.0-flash", customGemini.modelName)

        val customClaude =
            LlmProvider.create(
                "claude",
                apiKey = "test-key",
                apiUrl = null,
                modelName = "claude-3-5-sonnet",
            )
        assertIs<ClaudeProvider>(customClaude)
        assertEquals("claude-3-5-sonnet", customClaude.modelName)

        val customOpenAi =
            LlmProvider.create("openai", apiKey = "test-key", apiUrl = null, modelName = "gpt-4o")
        assertIs<OpenAiCompatibleProvider>(customOpenAi)
        assertEquals("gpt-4o", customOpenAi.modelName)

        val customOllama =
            LlmProvider.create(
                "ollama",
                apiKey = null,
                apiUrl = "http://localhost:11434/api/chat",
                modelName = "llama3:8b",
            )
        assertIs<OllamaProvider>(customOllama)
        assertEquals("llama3:8b", customOllama.modelName)
    }

    @Test
    fun `NoOpFallbackProvider planTrip creates narrative`() {
        val provider = NoOpFallbackProvider()
        val response =
            provider.planTrip(
                startName = "San Francisco",
                endName = "Los Angeles",
                startCoords = LocationCoords(37.7749, -122.4194),
                endCoords = LocationCoords(34.0522, -118.2437),
                days = 3,
                userPrompt = "Scenic coastal route",
            )

        assertTrue(response.narrative.isNotBlank())
    }

    @Test
    fun `NoOpFallbackProvider curateLegPois enhances POI descriptions`() {
        val provider = NoOpFallbackProvider()
        val poi1 =
            POI(
                id = "1",
                name = "Coastal Cafe",
                lat = 37.0,
                lng = -122.0,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
                isFoodOrCoffee = true,
            )
        val poi2 =
            POI(
                id = "2",
                name = "Big Sur Viewpoint",
                lat = 36.2,
                lng = -121.8,
                tags = mapOf("tourism" to "viewpoint"),
                type = "viewpoint",
                isFoodOrCoffee = false,
            )

        val leg =
            RouteLeg(
                startLat = 37.7,
                startLng = -122.4,
                endLat = 36.2,
                endLng = -121.8,
                dayNumber = 1,
                totalDays = 3,
                endTownName = "Monterey",
                distanceMeters = 180000.0,
                durationSeconds = 7200.0,
                pois = listOf(poi1, poi2),
            )

        val curated = provider.curateLegPois(leg, userPrompt = null, unit = DistanceUnit.METRIC)
        assertEquals(2, curated.curatedPois.size)
        assertTrue(
            curated.curatedPois[0].description!!.contains("coffee", ignoreCase = true) ||
                curated.curatedPois[0].description!!.contains("local", ignoreCase = true)
        )
        assertTrue(
            curated.curatedPois[1].description!!.contains("scenic viewpoint", ignoreCase = true)
        )
        assertTrue(curated.legStory.contains("Monterey"))
    }

    @Test
    fun `HttpLlmProvider curateLegPois keeps POI descriptions rule-based but uses LLM for legStory`() {
        val provider =
            object : HttpLlmProvider() {
                override fun complete(prompt: String): String =
                    "A short scenic drive into Monterey."
            }

        val poi =
            POI(
                id = "poi-1",
                name = "Coastal Viewpoint",
                lat = 36.6,
                lng = -121.9,
                tags = mapOf("tourism" to "viewpoint", "description" to "Scenic ocean overlook"),
                type = "viewpoint",
            )

        val leg =
            RouteLeg(
                startLat = 37.7,
                startLng = -122.4,
                endLat = 36.2,
                endLng = -121.8,
                dayNumber = 1,
                totalDays = 1,
                endTownName = "Monterey",
                distanceMeters = 50000.0,
                durationSeconds = 3600.0,
                pois = listOf(poi),
            )

        val result =
            provider.curateLegPois(
                leg,
                userPrompt = "Family coastal trip with toddlers",
                unit = DistanceUnit.METRIC,
            )
        val expectedRuleBased = RuleBasedCuration.curate(leg)

        // POI descriptions are always rule-based, never touched by the LLM or user prompt
        assertEquals(expectedRuleBased.curatedPois, result.curatedPois)
        assertEquals("Scenic ocean overlook", result.curatedPois[0].description)

        // legStory comes from the LLM's fact-grounded sentence
        assertEquals("A short scenic drive into Monterey.", result.legStory)
    }

    @Test
    fun `HttpLlmProvider curateLegPois falls back to RuleBasedCuration when LLM legStory is blank`() {
        val provider =
            object : HttpLlmProvider() {
                override fun complete(prompt: String): String = "   "
            }

        val leg =
            RouteLeg(
                startLat = 37.7,
                startLng = -122.4,
                endLat = 36.2,
                endLng = -121.8,
                dayNumber = 1,
                totalDays = 1,
                endTownName = "Monterey",
                distanceMeters = 50000.0,
                durationSeconds = 3600.0,
                pois = emptyList(),
            )

        val result = provider.curateLegPois(leg, userPrompt = null, unit = DistanceUnit.METRIC)
        val expectedRuleBased = RuleBasedCuration.curate(leg)

        assertEquals(expectedRuleBased.legStory, result.legStory)
    }

    @Test
    fun `HttpLlmProvider curateLegPois falls back to RuleBasedCuration when LLM legStory contains a road reference`() {
        val provider =
            object : HttpLlmProvider() {
                override fun complete(prompt: String): String =
                    "Drive south on Highway 1 into Monterey."
            }

        val leg =
            RouteLeg(
                startLat = 37.7,
                startLng = -122.4,
                endLat = 36.2,
                endLng = -121.8,
                dayNumber = 1,
                totalDays = 1,
                endTownName = "Monterey",
                distanceMeters = 50000.0,
                durationSeconds = 3600.0,
                pois = emptyList(),
            )

        val result = provider.curateLegPois(leg, userPrompt = null, unit = DistanceUnit.METRIC)
        val expectedRuleBased = RuleBasedCuration.curate(leg)

        assertEquals(
            expectedRuleBased.legStory,
            result.legStory,
            "Tainted LLM legStory must be dropped in favor of RuleBasedCuration fallback",
        )
        assertFalse(FalsifiableSpecificsFilter.containsRoadReference(result.legStory))
    }

    @Test
    fun `HttpLlmProvider curateLegPois falls back to RuleBasedCuration when complete throws`() {
        val provider =
            object : HttpLlmProvider() {
                override fun complete(prompt: String): String? = error("network failure")
            }

        val leg =
            RouteLeg(
                startLat = 37.7,
                startLng = -122.4,
                endLat = 36.2,
                endLng = -121.8,
                dayNumber = 1,
                totalDays = 1,
                endTownName = "Monterey",
                distanceMeters = 50000.0,
                durationSeconds = 3600.0,
                pois = emptyList(),
            )

        val result = provider.curateLegPois(leg, userPrompt = null, unit = DistanceUnit.METRIC)
        val expectedRuleBased = RuleBasedCuration.curate(leg)

        assertEquals(expectedRuleBased, result)
    }

    @Test
    fun `HttpLlmProvider buildLegStoryPrompt includes only real facts and unit-aware distance`() {
        val dummyProvider =
            object : HttpLlmProvider() {
                override fun complete(prompt: String): String? = null

                fun testPrompt(
                    dayNumber: Int,
                    distanceMeters: Double,
                    endTownName: String?,
                    poiName: String?,
                    unit: DistanceUnit,
                ) = buildLegStoryPrompt(dayNumber, distanceMeters, endTownName, poiName, unit)
            }

        val metricPrompt =
            dummyProvider.testPrompt(
                2,
                50000.0,
                "Monterey",
                "Big Sur Viewpoint",
                DistanceUnit.METRIC,
            )
        assertTrue(metricPrompt.contains("50.0km"))
        assertTrue(metricPrompt.contains("Monterey"))
        assertTrue(metricPrompt.contains("Big Sur Viewpoint"))
        assertTrue(
            metricPrompt.contains("never name a road, highway, or route number", ignoreCase = true)
        )

        val imperialPrompt =
            dummyProvider.testPrompt(
                2,
                50000.0,
                "Monterey",
                "Big Sur Viewpoint",
                DistanceUnit.IMPERIAL,
            )
        assertTrue(imperialPrompt.contains("mi"))
        assertTrue(!imperialPrompt.contains("50.0km"))

        val noPoiPrompt =
            dummyProvider.testPrompt(1, 30000.0, "San Luis Obispo", null, DistanceUnit.METRIC)
        assertTrue(noPoiPrompt.contains("30.0km"))
        assertTrue(noPoiPrompt.contains("San Luis Obispo"))
        assertFalse(noPoiPrompt.contains("passing"))
    }

    @Test
    fun `HttpLlmProvider planTrip returns parsed response when complete succeeds`() {
        val validLlmJson =
            """
            {
              "waypoints": [
                {"name": "Big Sur, CA", "lat": 36.2704, "lng": -121.8081}
              ],
              "narrative": "LLM custom parsed narrative for coastal trip.",
              "legStories": ["Day 1: Drive along the cliffside coast, stopping in Big Sur."]
            }
            """
                .trimIndent()

        val provider =
            object : HttpLlmProvider() {
                override fun complete(prompt: String): String = validLlmJson
            }

        val response =
            provider.planTrip(
                startName = "San Francisco",
                endName = "Los Angeles",
                startCoords = LocationCoords(37.7749, -122.4194),
                endCoords = LocationCoords(34.0522, -118.2437),
                days = 2,
                userPrompt = "Scenic",
            )

        assertEquals("LLM custom parsed narrative for coastal trip.", response.narrative)
        assertEquals(1, response.waypoints.size)
        assertEquals("Big Sur, CA", response.waypoints[0].name)
        assertEquals(36.2704, response.waypoints[0].lat)
        assertEquals(
            listOf("Day 1: Drive along the cliffside coast, stopping in Big Sur."),
            response.legStories,
        )
    }

    @Test
    fun `HttpLlmProvider planTrip drops narrative and legStories containing falsifiable road references`() {
        val taintedLlmJson =
            """
            {
              "waypoints": [],
              "narrative": "Take I-5 south then merge onto US-101 for a scenic coastal drive.",
              "legStories": ["Day 1: Head south on Highway 1 through Big Sur."]
            }
            """
                .trimIndent()

        val provider =
            object : HttpLlmProvider() {
                override fun complete(prompt: String): String = taintedLlmJson
            }

        val response =
            provider.planTrip(
                startName = "San Francisco",
                endName = "Los Angeles",
                startCoords = LocationCoords(37.7749, -122.4194),
                endCoords = LocationCoords(34.0522, -118.2437),
                days = 2,
                userPrompt = "Scenic",
            )

        assertFalse(
            FalsifiableSpecificsFilter.containsRoadReference(response.narrative),
            "Narrative must not leak road/highway specifics: ${response.narrative}",
        )
        assertEquals(listOf(""), response.legStories, "Tainted legStory must be dropped")

        val leg =
            RouteLeg(
                startLat = 36.5,
                startLng = -121.5,
                endLat = 36.7,
                endLng = -121.7,
                dayNumber = 1,
                totalDays = 1,
                legStory = response.legStories[0].ifBlank { null },
            )
        val curated = RuleBasedCuration.curate(leg)
        assertFalse(
            FalsifiableSpecificsFilter.containsRoadReference(curated.legStory),
            "RuleBasedCuration fallback must not leak road/highway specifics: ${curated.legStory}",
        )
    }

    @Test
    fun `HttpLlmProvider planTrip falls back to NoOpFallbackProvider when complete returns null`() {
        var completeCalled = false
        val provider =
            object : HttpLlmProvider() {
                override fun complete(prompt: String): String? {
                    completeCalled = true
                    return null
                }
            }

        val response =
            provider.planTrip(
                startName = "San Francisco",
                endName = "Los Angeles",
                startCoords = LocationCoords(37.7749, -122.4194),
                endCoords = LocationCoords(34.0522, -118.2437),
                days = 2,
                userPrompt = "Scenic",
            )

        assertTrue(completeCalled)
        assertEquals(
            "A 2-day road trip experience from San Francisco to Los Angeles tailored for: Scenic.",
            response.narrative,
        )
        assertTrue(response.waypoints.isEmpty())
        assertTrue(response.legStories.isEmpty())
    }

    @Test
    fun `validateApiKey throws IllegalArgumentException when API key is blank`() {
        val exception = assertFailsWith<IllegalArgumentException> { "".validateApiKey("gemini") }
        assertTrue(exception.message!!.contains("API key missing for gemini"))
        assertTrue(exception.message!!.contains("Set GEMINI_API_KEY or pass --llm-key"))
    }

    @Test
    fun `validateApiKey returns valid key`() {
        assertEquals("valid-key", "valid-key".validateApiKey("gemini"))
    }

    @Test
    fun `GeminiProvider constructor throws IllegalArgumentException on blank API key`() {
        assertFailsWith<IllegalArgumentException> { GeminiProvider(apiKey = "") }
    }

    @Test
    fun `ClaudeProvider constructor throws IllegalArgumentException on blank API key`() {
        assertFailsWith<IllegalArgumentException> { ClaudeProvider(apiKey = "") }
    }

    @Test
    fun `OpenAiCompatibleProvider constructor throws IllegalArgumentException on blank API key when not localhost`() {
        assertFailsWith<IllegalArgumentException> {
            OpenAiCompatibleProvider(
                apiKey = "",
                endpoint = "https://api.openai.com/v1/chat/completions",
            )
        }
    }

    @Test
    fun `OpenAiCompatibleProvider constructor succeeds on blank API key when endpoint is localhost`() {
        val provider =
            OpenAiCompatibleProvider(
                apiKey = "",
                endpoint = "http://localhost:11434/v1/chat/completions",
            )
        assertIs<OpenAiCompatibleProvider>(provider)
    }

    @Test
    fun `LlmProviderType fromId resolves provider types correctly including aliases`() {
        assertEquals(LlmProviderType.GEMINI, LlmProviderType.fromId("gemini"))
        assertEquals(LlmProviderType.CLAUDE, LlmProviderType.fromId("claude"))
        assertEquals(LlmProviderType.CLAUDE, LlmProviderType.fromId("anthropic"))
        assertEquals(LlmProviderType.OPENAI, LlmProviderType.fromId("openai"))
        assertEquals(LlmProviderType.OLLAMA, LlmProviderType.fromId("ollama"))
        assertEquals(LlmProviderType.NONE, LlmProviderType.fromId("unknown"))
        assertEquals(LlmProviderType.NONE, LlmProviderType.fromId(null))
    }

    @Test
    fun `HttpLlmProvider parseTripPlan handles string list waypoints and object list waypoints`() {
        val dummyProvider =
            object : HttpLlmProvider() {
                override fun complete(prompt: String): String? = null

                fun testParse(json: String, days: Int) = parseTripPlan(json, days)
            }

        val stringWaypointsJson =
            """
            {
              "waypoints": ["Monterey, CA", "Pismo Beach, CA"],
              "narrative": "A scenic coastal voyage along the Pacific Coast Highway."
            }
            """
                .trimIndent()

        val stringResult = dummyProvider.testParse(stringWaypointsJson, 2)
        assertEquals(2, stringResult.waypoints.size)
        assertEquals("Monterey, CA", stringResult.waypoints[0].name)
        assertEquals(0.0, stringResult.waypoints[0].lat)
        assertEquals("Pismo Beach, CA", stringResult.waypoints[1].name)

        val objectWaypointsJson =
            """
            {
              "waypoints": [
                {"name": "Monterey, CA", "lat": 36.6002, "lng": -121.8947},
                {"name": "Pismo Beach, CA", "lat": 35.1428, "lng": -120.6412}
              ],
              "narrative": "A scenic trip."
            }
            """
                .trimIndent()

        val objectResult = dummyProvider.testParse(objectWaypointsJson, 2)
        assertEquals(2, objectResult.waypoints.size)
        assertEquals(36.6002, objectResult.waypoints[0].lat)
        assertEquals(-121.8947, objectResult.waypoints[0].lng)
        assertEquals("Monterey, CA", objectResult.waypoints[0].name)
    }

    @Test
    fun `HttpLlmProvider parseTripPlan caps oversized waypoint list`() {
        val dummyProvider =
            object : HttpLlmProvider() {
                override fun complete(prompt: String): String? = null

                fun testParse(json: String, days: Int) = parseTripPlan(json, days)
            }

        val oversizedWaypointsJson =
            """
            {
              "waypoints": [
                "Town 1", "Town 2", "Town 3", "Town 4", "Town 5", "Town 6", "Town 7", "Town 8"
              ],
              "narrative": "A scenic trip with too many stops."
            }
            """
                .trimIndent()

        val result = dummyProvider.testParse(oversizedWaypointsJson, 2)
        assertEquals(
            4,
            result.waypoints.size,
            "Waypoints must be capped to bound geocoding/routing fan-out",
        )
        assertEquals("Town 1", result.waypoints[0].name)
        assertEquals("Town 4", result.waypoints[3].name)
    }

    @Test
    fun `HttpLlmProvider buildPrompt includes critical routing instructions for waypoints`() {
        val dummyProvider =
            object : HttpLlmProvider() {
                override fun complete(prompt: String): String? = null

                fun testPrompt(start: String, end: String, days: Int, prompt: String?) =
                    buildPrompt(start, end, days, prompt)
            }

        val prompt = dummyProvider.testPrompt("San Jose", "Los Angeles", 2, "coastal scenic points")
        assertTrue(prompt.contains("CRITICAL ROUTING INSTRUCTION"))
        assertTrue(prompt.contains("waypoints"))
    }

    @Test
    fun `NoOpFallbackProvider generates clean description for POI with historic yes tag`() {
        val provider = NoOpFallbackProvider()
        val poi =
            POI(
                id = "100",
                name = "Old Mission Jail",
                lat = 36.6,
                lng = -121.6,
                tags = mapOf("name" to "Old Mission Jail", "historic" to "yes"),
                type = "historic",
            )
        val leg =
            RouteLeg(
                startLat = 36.5,
                startLng = -121.5,
                endLat = 36.7,
                endLng = -121.7,
                dayNumber = 1,
                totalDays = 1,
                pois = listOf(poi),
            )
        val curated = provider.curateLegPois(leg, null)
        val desc = curated.curatedPois[0].description!!
        assertFalse(
            desc.contains("Historic yes", ignoreCase = true),
            "Description should not contain 'Historic yes': $desc",
        )
        assertTrue(
            desc.startsWith("Historic landmark showcasing"),
            "Description should start with 'Historic landmark showcasing': $desc",
        )
    }
}
