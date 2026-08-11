package com.pathpress.poi

import com.pathpress.config.Config
import com.pathpress.model.POI
import com.pathpress.routing.MockHttpClient
import com.pathpress.routing.MockHttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.parallel.ResourceLock

@ResourceLock("com.pathpress.poi.AddressResolver")
class AddressResolverTest {

    private val originalHttpClient = AddressResolver.httpClient

    @BeforeEach
    fun setUp() {
        AddressResolver.clearCache()
    }

    @AfterEach
    fun tearDown() {
        AddressResolver.httpClient = originalHttpClient
    }

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
    fun `resolveAddress uses substituted httpClient and parses Nominatim reverse response`() {
        val nominatimJson =
            """
            {
              "address": {
                "house_number": "1600",
                "road": "Amphitheatre Pkwy",
                "city": "Mountain View",
                "state": "California",
                "postcode": "94043"
              }
            }
            """
                .trimIndent()

        var capturedUri: String? = null
        var capturedTimeout: Duration? = null

        AddressResolver.httpClient = MockHttpClient { req ->
            capturedUri = req.uri().toString()
            capturedTimeout = req.timeout().orElse(null)
            MockHttpResponse(nominatimJson, 200)
        }

        val poi =
            POI(
                id = "node/102",
                name = "Googleplex",
                lat = 37.4220,
                lng = -122.0841,
                tags = emptyMap(),
                type = "office",
            )

        val customConfig = Config(geocoderTimeoutSeconds = 25L)
        val resolved = AddressResolver.resolveAddress(poi, config = customConfig)

        val uri = capturedUri
        assertNotNull(uri)
        assert(uri.contains("nominatim.openstreetmap.org/reverse"))
        assert(uri.contains("lat=37.422000"))
        assert(uri.contains("lon=-122.084100"))
        assertEquals(Duration.ofSeconds(25L), capturedTimeout)
    }

    @Test
    fun `resolveAddress falls back to display_name when structured address fields are missing`() {
        val nominatimJson =
            """
            {
              "display_name": "Yosemite National Park, Tioga Pass Road, Tuolumne County, California, USA"
            }
            """
                .trimIndent()

        AddressResolver.httpClient = MockHttpClient { req -> MockHttpResponse(nominatimJson, 200) }

        val poi =
            POI(
                id = "node/103",
                name = "Yosemite Viewpoint",
                lat = 37.8651,
                lng = -119.5383,
                tags = emptyMap(),
                type = "viewpoint",
            )

        val resolved = AddressResolver.resolveAddress(poi)
        assertEquals("Yosemite National Park, Tioga Pass Road, Tuolumne County", resolved)
    }

    @Test
    fun `resolveAddress falls back to formatted coordinates on HTTP error`() {
        AddressResolver.httpClient = MockHttpClient { req ->
            MockHttpResponse("Internal Server Error", 500)
        }

        val poi =
            POI(
                id = "node/104",
                name = "Remote Peak",
                lat = 36.5552,
                lng = -121.9233,
                tags = emptyMap(),
                type = "viewpoint",
            )

        val resolved = AddressResolver.resolveAddress(poi)
        assertEquals("36.5552, -121.9233", resolved)
    }

    @Test
    fun `createHttpClient honors Config connect timeout`() {
        val customConfig = Config(geocoderTimeoutSeconds = 37L)
        val client = AddressResolver.createHttpClient(customConfig)
        assertEquals(Duration.ofSeconds(37L), client.connectTimeout().orElse(null))
    }

    @Test
    @Tag("network")
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
