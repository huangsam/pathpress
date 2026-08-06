package com.pathpress.routing

import com.pathpress.model.LocationCoords
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WaypointValidatorTest {

    private val sanJose = LocationCoords(37.3382, -121.8863, "San Jose, CA")
    private val sanDiego = LocationCoords(32.7157, -117.1611, "San Diego, CA")
    private val monterey = LocationCoords(36.6002, -121.8947, "Monterey, CA")

    @Test
    fun testEmptyWaypoints_ReturnsValid() {
        val result = WaypointValidator.validateWaypoints(emptyList(), sanJose, sanDiego)
        assertTrue(result.isValid)
        assertTrue(result.validWaypoints.isEmpty())
        assertTrue(result.rejectedWaypoints.isEmpty())
    }

    @Test
    fun testCoastalWaypoints_AcceptedForSJtoSD() {
        val santaBarbara = LocationCoords(34.4208, -119.6982, "Santa Barbara, CA")
        val sanLuisObispo = LocationCoords(35.2828, -120.6596, "San Luis Obispo, CA")

        val result =
            WaypointValidator.validateWaypoints(
                listOf(sanLuisObispo, santaBarbara),
                sanJose,
                sanDiego,
            )

        assertTrue(result.isValid, "Coastal waypoints SB and SLO should be accepted")
        assertEquals(2, result.validWaypoints.size)
        assertTrue(result.rejectedWaypoints.isEmpty())
    }

    @Test
    fun testOutOfCorridorWaypoints_Rejected() {
        // Sacramento is far north of San Jose
        val sacramento = LocationCoords(38.5816, -121.4944, "Sacramento, CA")
        // Las Vegas is far east of the route
        val lasVegas = LocationCoords(36.1716, -115.1391, "Las Vegas, NV")

        val result =
            WaypointValidator.validateWaypoints(listOf(sacramento, lasVegas), sanJose, sanDiego)

        assertFalse(result.isValid, "Sacramento and Las Vegas should be rejected for SJ->SD trip")
        assertEquals(2, result.rejectedWaypoints.size)
        assertTrue(result.reason?.contains("Sacramento") == true)
    }

    @Test
    fun testZeroCoords_Rejected() {
        val invalidWp = LocationCoords(0.0, 0.0, "Invalid Town")
        val result = WaypointValidator.validateWaypoints(listOf(invalidWp), sanJose, sanDiego)

        assertFalse(result.isValid)
        assertEquals(1, result.rejectedWaypoints.size)
    }

    @Test
    fun testShortTrip_StrictCorridor() {
        // Short trip: San Jose -> Monterey (~100 km)
        // Fresno is far inland (~180 km away)
        val fresno = LocationCoords(36.7468, -119.7726, "Fresno, CA")

        val result = WaypointValidator.validateWaypoints(listOf(fresno), sanJose, monterey)

        assertFalse(result.isValid, "Fresno should be rejected for SJ->Monterey trip")
        assertEquals(1, result.rejectedWaypoints.size)
    }

    @Test
    fun testOutOfOrderWaypoints_SortedMonotonically() {
        val santaBarbara = LocationCoords(34.4208, -119.6982, "Santa Barbara, CA")
        val sanLuisObispo = LocationCoords(35.2828, -120.6596, "San Luis Obispo, CA")

        // Pass Santa Barbara BEFORE San Luis Obispo (out of order for Southbound SJ -> SD trip)
        val result =
            WaypointValidator.validateWaypoints(
                listOf(santaBarbara, sanLuisObispo, monterey),
                sanJose,
                sanDiego,
            )

        assertTrue(result.isValid)
        assertEquals(3, result.validWaypoints.size)

        // Expected monotonic sequence from SJ -> SD: Monterey -> San Luis Obispo -> Santa Barbara
        assertEquals("Monterey, CA", result.validWaypoints[0].name)
        assertEquals("San Luis Obispo, CA", result.validWaypoints[1].name)
        assertEquals("Santa Barbara, CA", result.validWaypoints[2].name)
    }
}
