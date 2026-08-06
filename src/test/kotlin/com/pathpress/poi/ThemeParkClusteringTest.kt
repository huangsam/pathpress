package com.pathpress.poi

import com.pathpress.model.POI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeParkClusteringTest {

    @Test
    fun `isThemeParkNode identifies theme park nodes by attraction tags`() {
        val ridePoi =
            POI(
                id = "1",
                name = "Viper",
                lat = 34.425,
                lng = -118.597,
                tags = mapOf("attraction" to "roller_coaster"),
                type = "roller_coaster",
            )
        val slidePoi =
            POI(
                id = "2",
                name = "Python Plunge",
                lat = 34.426,
                lng = -118.598,
                tags = mapOf("attraction" to "water_slide"),
                type = "water_slide",
            )

        assertTrue(ThemeParkClustering.isThemeParkNode(ridePoi))
        assertTrue(ThemeParkClustering.isThemeParkNode(slidePoi))
    }

    @Test
    fun `isThemeParkNode identifies theme park nodes by tourism leisure amenity tags`() {
        val themeParkPoi =
            POI(
                id = "3",
                name = "Disneyland",
                lat = 33.812,
                lng = -117.919,
                tags = mapOf("tourism" to "theme_park"),
                type = "theme_park",
            )
        val amusementParkPoi =
            POI(
                id = "4",
                name = "Fun Town",
                lat = 33.813,
                lng = -117.920,
                tags = mapOf("leisure" to "amusement_park"),
                type = "amusement_park",
            )

        assertTrue(ThemeParkClustering.isThemeParkNode(themeParkPoi))
        assertTrue(ThemeParkClustering.isThemeParkNode(amusementParkPoi))
    }

    @Test
    fun `isThemeParkNode identifies theme park nodes by known domain`() {
        val sixFlagsPoi =
            POI(
                id = "5",
                name = "Magic Mountain",
                lat = 34.425,
                lng = -118.597,
                tags = mapOf("website" to "https://www.sixflags.com/magicmountain"),
                type = "attraction",
            )

        assertTrue(ThemeParkClustering.isThemeParkNode(sixFlagsPoi))
    }

    @Test
    fun `isThemeParkNode returns false for regular non theme park POIs`() {
        val cafePoi =
            POI(
                id = "6",
                name = "Local Cafe",
                lat = 37.7749,
                lng = -122.4194,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
            )

        assertFalse(ThemeParkClustering.isThemeParkNode(cafePoi))
    }

    @Test
    fun `getThemeParkDomain extracts domain from website tag`() {
        val disneyPoi =
            POI(
                id = "7",
                name = "Disney California Adventure",
                lat = 33.808,
                lng = -117.919,
                tags = mapOf("website" to "https://disney.com/dca"),
                type = "theme_park",
            )
        val seaWorldPoi =
            POI(
                id = "8",
                name = "SeaWorld San Diego",
                lat = 32.764,
                lng = -117.226,
                tags = mapOf("website" to "HTTP://SEAWORLD.COM"),
                type = "theme_park",
            )
        val genericPoi =
            POI(
                id = "9",
                name = "City Park",
                lat = 37.0,
                lng = -120.0,
                tags = mapOf("website" to "https://example.com"),
                type = "park",
            )

        assertEquals("disney.com", ThemeParkClustering.getThemeParkDomain(disneyPoi))
        assertEquals("seaworld.com", ThemeParkClustering.getThemeParkDomain(seaWorldPoi))
        assertNull(ThemeParkClustering.getThemeParkDomain(genericPoi))
    }

    @Test
    fun `deduplicateThemeParks clusters nearby theme park POIs and picks closest to route`() {
        val rideFarFromRoute =
            POI(
                id = "10",
                name = "Goliath Roller Coaster",
                lat = 34.4251,
                lng = -118.5971,
                tags = mapOf("attraction" to "roller_coaster"),
                type = "roller_coaster",
                distanceFromRouteMeters = 500.0,
            )
        val mainEntranceCloseToRoute =
            POI(
                id = "11",
                name = "Six Flags Magic Mountain Entrance",
                lat = 34.4253,
                lng = -118.5973,
                tags =
                    mapOf(
                        "tourism" to "theme_park",
                        "website" to "https://www.sixflags.com/magicmountain",
                    ),
                type = "theme_park",
                distanceFromRouteMeters = 50.0,
            )
        val cafePoi =
            POI(
                id = "12",
                name = "Roadside Diner",
                lat = 34.500,
                lng = -118.600,
                tags = mapOf("amenity" to "restaurant"),
                type = "restaurant",
                distanceFromRouteMeters = 10.0,
            )

        val candidates = listOf(rideFarFromRoute, mainEntranceCloseToRoute, cafePoi)
        val deduplicated = ThemeParkClustering.deduplicateThemeParks(candidates)

        // 1 non-theme park POI + 1 representative theme park POI
        assertEquals(2, deduplicated.size)
        assertTrue(deduplicated.contains(cafePoi))
        assertTrue(deduplicated.contains(mainEntranceCloseToRoute))
    }

    @Test
    fun `deduplicateThemeParks returns candidates unchanged if 1 or fewer theme park POIs`() {
        val themeParkPoi =
            POI(
                id = "13",
                name = "Knott's Berry Farm",
                lat = 33.844,
                lng = -118.000,
                tags = mapOf("tourism" to "theme_park"),
                type = "theme_park",
            )
        val cafePoi =
            POI(
                id = "14",
                name = "Diner",
                lat = 33.845,
                lng = -118.001,
                tags = mapOf("amenity" to "cafe"),
                type = "cafe",
            )

        val candidates = listOf(themeParkPoi, cafePoi)
        val result = ThemeParkClustering.deduplicateThemeParks(candidates)

        assertEquals(2, result.size)
        assertEquals(candidates, result)
    }
}
