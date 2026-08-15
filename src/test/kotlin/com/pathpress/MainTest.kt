package com.pathpress

import com.github.ajalt.clikt.core.parse
import com.pathpress.config.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MainTest {

    private class TestPathPressCommand : PathPressCommand() {
        override fun run() {
            // No-op for option parsing unit tests (avoids loading OSM PBF data)
        }
    }

    @Test
    fun `llmModel defaults to null when --llm-model option is not passed`() {
        val command = TestPathPressCommand()
        command.parse(listOf("--start", "SF", "--end", "LA"))
        assertNull(command.llmModel)
    }

    @Test
    fun `llmModel is populated when --llm-model option is passed`() {
        val command = TestPathPressCommand()
        command.parse(listOf("--start", "SF", "--end", "LA", "--llm-model", "custom-model"))
        assertEquals("custom-model", command.llmModel)
    }

    @Test
    fun `empty waypoints for coastal prompt does not inject hardcoded fallback waypoints`() {
        val startGeo =
            com.pathpress.routing.GeocodedLocation(
                com.pathpress.model.LocationCoords(37.7749, -122.4194),
                "San Francisco, CA",
            )
        val endGeo =
            com.pathpress.routing.GeocodedLocation(
                com.pathpress.model.LocationCoords(34.0522, -118.2437),
                "Los Angeles, CA",
            )
        val emptyTripPlan =
            com.pathpress.llm.TripPlanResponse(narrative = "Coastal trip", waypoints = emptyList())
        val llm = com.pathpress.llm.NoOpFallbackProvider()

        val resolution =
            resolveAndValidateWaypoints(
                initialTripPlan = emptyTripPlan,
                startGeo = startGeo,
                endGeo = endGeo,
                days = 2,
                prompt = "coastal highway 1 trip",
                llm = llm,
                geocoder = { null },
            )

        kotlin.test.assertTrue(
            resolution.waypoints.isEmpty(),
            "Expected empty waypoints but got hardcoded injection: ${resolution.waypoints.map { it.name }}",
        )
    }

    @Test
    fun `PathPressCommand exits with GEOCODING_ERROR when geocoding fails`() {
        val orchestrator = TripPlannerOrchestrator(config = Config(), geocoder = { null })
        val command = PathPressCommand(orchestrator = orchestrator)
        val ex =
            kotlin.test.assertFailsWith<com.github.ajalt.clikt.core.ProgramResult> {
                command.parse(listOf("--start", "UnknownStart", "--end", "UnknownEnd"))
            }
        assertEquals(ExitCode.GEOCODING_ERROR, ex.statusCode)
    }

    @Test
    fun `PathPressCommand exits with INTERNAL_ERROR on unexpected exception`() {
        val orchestrator =
            TripPlannerOrchestrator(
                config = Config(),
                geocoder = { throw RuntimeException("Simulated unexpected disk/IO error") },
            )
        val command = PathPressCommand(orchestrator = orchestrator)
        val ex =
            kotlin.test.assertFailsWith<com.github.ajalt.clikt.core.ProgramResult> {
                command.parse(listOf("--start", "SF", "--end", "LA"))
            }
        assertEquals(ExitCode.INTERNAL_ERROR, ex.statusCode)
    }
}
