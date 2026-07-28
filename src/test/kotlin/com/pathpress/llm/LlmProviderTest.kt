package com.pathpress.llm

import com.pathpress.export.*
import com.pathpress.model.*
import com.pathpress.poi.*
import com.pathpress.routing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LlmProviderTest {

    @Test
    fun `LlmProvider factory creates NoOpFallbackProvider for unknown provider`() {
        val provider = LlmProvider.create("unknown", null, null)
        assertIs<NoOpFallbackProvider>(provider)
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
}
