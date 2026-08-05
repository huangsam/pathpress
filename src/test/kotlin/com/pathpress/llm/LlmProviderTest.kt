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
    fun `RuleBasedCuration buildFactGroundedStory includes distance, town, and POI facts`() {
        val leg =
            RouteLeg(
                startLat = 37.7,
                startLng = -122.4,
                endLat = 36.2,
                endLng = -121.8,
                dayNumber = 2,
                totalDays = 3,
                endTownName = "Monterey",
                distanceMeters = 120000.0,
                durationSeconds = 5400.0,
                pois =
                    listOf(
                        POI(
                            id = "p1",
                            name = "Big Sur Viewpoint",
                            lat = 36.4,
                            lng = -121.9,
                            tags = mapOf("tourism" to "viewpoint"),
                            type = "viewpoint",
                        ),
                        POI(
                            id = "p2",
                            name = "Coastal Cafe",
                            lat = 36.3,
                            lng = -121.85,
                            tags = mapOf("amenity" to "cafe"),
                            type = "cafe",
                        ),
                    ),
            )

        val metric = RuleBasedCuration.buildFactGroundedStory(leg, DistanceUnit.METRIC)
        assertTrue(metric.contains("Day 2"), "should include day number")
        assertTrue(metric.contains("120.0km"), "should include metric distance")
        assertTrue(metric.contains("Monterey"), "should include end town")
        assertTrue(metric.contains("Big Sur Viewpoint"), "should include first POI name")
        assertTrue(metric.contains("1 more stop"), "should count remaining POIs")
        assertFalse(
            FalsifiableSpecificsFilter.containsRoadReference(metric),
            "must not mention roads",
        )

        val imperial = RuleBasedCuration.buildFactGroundedStory(leg, DistanceUnit.IMPERIAL)
        assertTrue(imperial.contains("mi"), "should use miles for imperial")
        assertFalse(imperial.contains("km"), "should not use km for imperial")

        val noPoisLeg = leg.copy(pois = emptyList())
        val noPois = RuleBasedCuration.buildFactGroundedStory(noPoisLeg, DistanceUnit.METRIC)
        assertFalse(noPois.contains("passing"), "should omit poi clause when no POIs")

        val onePoiLeg = leg.copy(pois = leg.pois.take(1))
        val onePoi = RuleBasedCuration.buildFactGroundedStory(onePoiLeg, DistanceUnit.METRIC)
        assertTrue(onePoi.contains("Big Sur Viewpoint"), "should name the single POI")
        assertFalse(onePoi.contains("more stop"), "should not show 'more stops' for single POI")

        val withStart = leg.copy(startTownName = "San Jose")
        val fromTo = RuleBasedCuration.buildFactGroundedStory(withStart, DistanceUnit.METRIC)
        assertTrue(fromTo.contains("from San Jose"), "should include start town when present")
        assertTrue(fromTo.contains("to Monterey"), "should still include end town")

        val noStart = leg.copy(startTownName = null)
        val noFrom = RuleBasedCuration.buildFactGroundedStory(noStart, DistanceUnit.METRIC)
        assertFalse(noFrom.contains("from"), "should omit 'from' clause when startTownName is null")
    }

    @Test
    fun `HttpLlmProvider curateLegPois never calls the LLM`() {
        var completeCalled = false
        val provider =
            object : HttpLlmProvider() {
                override fun complete(prompt: String): String? {
                    completeCalled = true
                    error("complete() must never be called during curateLegPois!")
                }
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

        val result =
            provider.curateLegPois(
                leg,
                userPrompt = "Family coastal trip",
                unit = DistanceUnit.METRIC,
            )

        assertFalse(completeCalled, "complete() must not be called when curating POIs")
        assertTrue(result.legStory.contains("Monterey"))
    }

    @Test
    fun `HttpLlmProvider planTrip returns parsed response when complete succeeds`() {
        val validLlmJson =
            """
            {
              "waypoints": [
                {"name": "Big Sur, CA", "lat": 36.2704, "lng": -121.8081}
              ],
              "narrative": "LLM custom parsed narrative for coastal trip."
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
    }

    @Test
    fun `HttpLlmProvider planTrip drops narrative containing falsifiable road references`() {
        val taintedLlmJson =
            """
            {
              "waypoints": [],
              "narrative": "Take I-5 south then merge onto US-101 for a scenic coastal drive."
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

        val leg =
            RouteLeg(
                startLat = 36.5,
                startLng = -121.5,
                endLat = 36.7,
                endLng = -121.7,
                dayNumber = 1,
                totalDays = 1,
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
