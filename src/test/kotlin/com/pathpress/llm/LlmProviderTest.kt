package com.pathpress.llm

import com.pathpress.config.Config
import com.pathpress.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `NoOpFallbackProvider planTrip creates day themes and narrative`() {
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

        assertEquals(3, response.dayThemes.size)
        assertTrue(response.dayThemes[0].contains("Day 1"))
        assertTrue(response.dayThemes[0].contains("San Francisco"))
        assertTrue(response.dayThemes[2].contains("Day 3"))
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

        val curated = provider.curateLegPois(leg, userPrompt = null)
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
    fun `HttpLlmProvider buildCurationPrompt includes explicit accessibility instructions`() {
        val dummyProvider =
            object : HttpLlmProvider() {
                public fun testCurationPrompt(leg: RouteLeg, userPrompt: String?) =
                    buildCurationPrompt(leg, userPrompt)

                override fun planTrip(
                    startName: String,
                    endName: String,
                    startCoords: LocationCoords,
                    endCoords: LocationCoords,
                    days: Int,
                    userPrompt: String?,
                ): TripPlanResponse = error("Not implemented")

                override fun curateLegPois(leg: RouteLeg, userPrompt: String?): CuratedLegResult =
                    error("Not implemented")
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

        val prompt = dummyProvider.testCurationPrompt(leg, "Family trip with toddlers")
        assertTrue(prompt.contains("ACCESSIBILITY & SUITABILITY"))
        assertTrue(prompt.contains("toddlers, kids, or family"))
        assertTrue(prompt.contains("reject any POIs requiring strenuous hiking"))
    }

    @Test
    fun `validateApiKey throws IllegalArgumentException when API key is blank`() {
        val exception = assertFailsWith<IllegalArgumentException> { "".validateApiKey("gemini") }
        assertTrue(exception.message!!.contains("API key missing for gemini"))
        assertTrue(
            exception.message!!.contains(
                "Set GEMINI_API_KEY/ANTHROPIC_API_KEY/OPENAI_API_KEY or pass --llm-key"
            )
        )
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
}
