package com.pathpress.poi

import com.pathpress.model.POI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AddressResolverTest {

    @Test
    fun `resolveFromOsmTags builds physical street address when tags exist`() {
        val tags =
            mapOf(
                "addr:housenumber" to "2855",
                "addr:street" to "Stevens Creek Boulevard",
                "addr:city" to "Santa Clara",
                "addr:state" to "CA",
                "addr:postcode" to "95050",
            )
        val address = AddressResolver.resolveFromOsmTags(tags)
        assertEquals("2855 Stevens Creek Boulevard, Santa Clara CA 95050", address)
    }

    @Test
    fun `resolveFromOsmTags returns null if street is missing`() {
        val tags = mapOf("addr:city" to "Santa Clara", "addr:state" to "CA")
        val address = AddressResolver.resolveFromOsmTags(tags)
        assertNull(address)
    }

    @Test
    fun `resolveAddress uses OSM tags first`() {
        val poi =
            POI(
                id = "node/100",
                name = "Capital One 360 Cafe",
                lat = 37.3230,
                lng = -121.9482,
                tags =
                    mapOf(
                        "addr:housenumber" to "2855",
                        "addr:street" to "Stevens Creek Boulevard",
                        "addr:city" to "Santa Clara",
                        "addr:state" to "CA",
                        "addr:postcode" to "95050",
                    ),
                type = "cafe",
            )
        val resolved = AddressResolver.resolveAddress(poi)
        assertEquals("2855 Stevens Creek Boulevard, Santa Clara CA 95050", resolved)
    }

    @Test
    fun `resolveAddress falls back to formatted coordinates if Nominatim unavailable`() {
        val poi =
            POI(
                id = "node/101",
                name = "Unknown Spot",
                lat = 36.5552,
                lng = -121.9233,
                tags = emptyMap(),
                type = "viewpoint",
            )
        val resolved = AddressResolver.resolveAddress(poi)
        assertNotNull(resolved)
    }
}
