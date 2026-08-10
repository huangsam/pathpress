package com.pathpress.routing

import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock

@ResourceLock("com.pathpress.routing.Geocoder")
class GeocoderTest {

    private val originalHttpClient = Geocoder.httpClient

    @BeforeEach
    fun setUp() {
        Geocoder.clearCache()
    }

    @AfterEach
    fun tearDown() {
        Geocoder.httpClient = originalHttpClient
    }

    @Test
    fun `test direct coordinate geocoding`() {
        val result = Geocoder.geocode("37.7749, -122.4194")
        assertNotNull(result)
        assertEquals(37.7749, result.coords.lat)
        assertEquals(-122.4194, result.coords.lng)
    }

    @Test
    fun `test Photon resolves valid US city directly`() {
        val photonUsJson =
            """
            {
              "features": [
                {
                  "geometry": { "coordinates": [-122.4194, 37.7749] },
                  "properties": {
                    "name": "San Francisco",
                    "state": "California",
                    "countrycode": "US",
                    "type": "city",
                    "osm_key": "place"
                  }
                }
              ]
            }
            """
                .trimIndent()

        Geocoder.httpClient = MockHttpClient { req ->
            val uri = req.uri().toString()
            if (uri.contains("photon.komoot.io")) {
                MockHttpResponse(photonUsJson, 200)
            } else {
                MockHttpResponse("", 500)
            }
        }

        val result = Geocoder.geocode("San Francisco")
        assertNotNull(result)
        assertEquals(37.7749, result.coords.lat)
        assertEquals(-122.4194, result.coords.lng)
        assertEquals("San Francisco, California", result.displayName)
    }

    @Test
    fun `test Photon rejects non-US result and falls back to Nominatim`() {
        // Mock Photon returning a French feature and Nominatim returning a valid US result
        val photonFrenchJson =
            """
            {
              "features": [
                {
                  "geometry": { "coordinates": [2.3522, 48.8566] },
                  "properties": {
                    "name": "Paris",
                    "countrycode": "FR",
                    "type": "city"
                  }
                }
              ]
            }
            """
                .trimIndent()

        val nominatimUsJson =
            """
            [
              {
                "lat": "33.6609",
                "lon": "-95.5555",
                "display_name": "Paris, Lamar County, Texas, USA",
                "class": "place",
                "type": "city"
              }
            ]
            """
                .trimIndent()

        Geocoder.httpClient = MockHttpClient { req ->
            val uri = req.uri().toString()
            if (uri.contains("photon.komoot.io")) {
                MockHttpResponse(photonFrenchJson, 200)
            } else if (uri.contains("nominatim.openstreetmap.org")) {
                MockHttpResponse(nominatimUsJson, 200)
            } else {
                MockHttpResponse("", 404)
            }
        }

        val result = Geocoder.geocode("Paris")
        assertNotNull(result)
        // Should have rejected French result (lat 48.8566) and used Nominatim US result (lat
        // 33.6609)
        assertEquals(33.6609, result.coords.lat)
        assertEquals(-95.5555, result.coords.lng)
    }

    @Test
    fun `test Nominatim returns null when coordinates are non-numeric instead of defaulting to 0 0`() {
        val photonEmptyJson = """{"features": []}"""
        val nominatimInvalidCoordsJson =
            """
            [
              {
                "lat": "not_a_number",
                "lon": "invalid_lon",
                "display_name": "Bogus Town, CA, USA",
                "class": "place",
                "type": "city"
              }
            ]
            """
                .trimIndent()

        Geocoder.httpClient = MockHttpClient { req ->
            val uri = req.uri().toString()
            if (uri.contains("photon.komoot.io")) {
                MockHttpResponse(photonEmptyJson, 200)
            } else if (uri.contains("nominatim.openstreetmap.org")) {
                MockHttpResponse(nominatimInvalidCoordsJson, 200)
            } else {
                MockHttpResponse("", 404)
            }
        }

        val result = Geocoder.geocode("Bogus Town")
        assertNull(
            result,
            "Geocoder should return null when Nominatim coordinates cannot be parsed as Double, instead of defaulting to (0.0, 0.0)",
        )
    }

    @Test
    fun `test Photon prioritizes US settlement over secondary US feature`() {
        val photonJson =
            """
            {
              "features": [
                {
                  "geometry": { "coordinates": [-89.6500, 39.7800] },
                  "properties": {
                    "name": "Springfield Park",
                    "countrycode": "US",
                    "osm_key": "leisure",
                    "osm_value": "park",
                    "type": "park"
                  }
                },
                {
                  "geometry": { "coordinates": [-89.6501, 39.7817] },
                  "properties": {
                    "name": "Springfield",
                    "state": "Illinois",
                    "countrycode": "US",
                    "osm_key": "place",
                    "osm_value": "city",
                    "type": "city"
                  }
                }
              ]
            }
            """
                .trimIndent()

        Geocoder.httpClient = MockHttpClient { req ->
            val uri = req.uri().toString()
            if (uri.contains("photon.komoot.io")) {
                MockHttpResponse(photonJson, 200)
            } else {
                MockHttpResponse("", 500)
            }
        }

        val result = Geocoder.geocode("Springfield")
        assertNotNull(result)
        assertEquals("Springfield, Illinois", result.displayName)
        assertEquals(39.7817, result.coords.lat)
        assertEquals(-89.6501, result.coords.lng)
    }

    @Test
    fun `test Photon second query variant does not invoke Nominatim`() {
        val photonChicagoUsJson =
            """
            {
              "features": [
                {
                  "geometry": { "coordinates": [-87.6298, 41.8781] },
                  "properties": {
                    "name": "Chicago",
                    "state": "Illinois",
                    "countrycode": "US",
                    "type": "city",
                    "osm_key": "place"
                  }
                }
              ]
            }
            """
                .trimIndent()

        var nominatimCalled = false

        Geocoder.httpClient = MockHttpClient { req ->
            val uri = req.uri().toString()
            if (uri.contains("photon.komoot.io")) {
                if (uri.contains("Chicago%2C+USA") || uri.contains("Chicago%2C+USA".lowercase())) {
                    MockHttpResponse(photonChicagoUsJson, 200)
                } else {
                    // First query "Chicago" returns empty features in Photon
                    MockHttpResponse("""{"features": []}""", 200)
                }
            } else if (uri.contains("nominatim.openstreetmap.org")) {
                nominatimCalled = true
                MockHttpResponse("", 500)
            } else {
                MockHttpResponse("", 404)
            }
        }

        val result = Geocoder.geocode("Chicago")
        assertNotNull(result)
        assertEquals("Chicago, Illinois", result.displayName)
        assertEquals(
            false,
            nominatimCalled,
            "Nominatim should not be called when Photon fallback query succeeds",
        )
    }

    @Test
    fun `test Photon resolves displayName from city property when name is null`() {
        val photonNoNameJson =
            """
            {
              "features": [
                {
                  "geometry": { "coordinates": [-97.7431, 30.2672] },
                  "properties": {
                    "city": "Austin",
                    "state": "Texas",
                    "countrycode": "US",
                    "type": "city",
                    "osm_key": "place"
                  }
                }
              ]
            }
            """
                .trimIndent()

        Geocoder.httpClient = MockHttpClient { req ->
            val uri = req.uri().toString()
            if (uri.contains("photon.komoot.io")) {
                MockHttpResponse(photonNoNameJson, 200)
            } else {
                MockHttpResponse("", 500)
            }
        }

        val result = Geocoder.geocode("78701")
        assertNotNull(result)
        assertEquals("Austin, Texas", result.displayName)
        assertEquals(30.2672, result.coords.lat)
        assertEquals(-97.7431, result.coords.lng)
    }

    @Test
    fun `test Photon rate limiting throttle enforces delay between requests`() {
        val photonUsJson =
            """
            {
              "features": [
                {
                  "geometry": { "coordinates": [-122.4194, 37.7749] },
                  "properties": {
                    "name": "City",
                    "state": "California",
                    "countrycode": "US",
                    "type": "city",
                    "osm_key": "place"
                  }
                }
              ]
            }
            """
                .trimIndent()

        val requestTimes = mutableListOf<Long>()

        Geocoder.httpClient = MockHttpClient { req ->
            val uri = req.uri().toString()
            if (uri.contains("photon.komoot.io")) {
                requestTimes.add(System.currentTimeMillis())
                MockHttpResponse(photonUsJson, 200)
            } else {
                MockHttpResponse("", 500)
            }
        }

        Geocoder.geocode("City 1")
        Geocoder.geocode("City 2")

        assertEquals(2, requestTimes.size)
        val elapsed = requestTimes[1] - requestTimes[0]
        assert(elapsed >= 900) {
            "Expected at least 1000ms delay between Photon requests, but was ${elapsed}ms"
        }
    }

    @Test
    @Tag("network")
    fun `test city geocoding and caching`() {
        val first = Geocoder.geocode("San Jose, CA")
        assertNotNull(first)
        assertNotNull(first.displayName)

        // Second call should return instantly from cache
        val second = Geocoder.geocode("San Jose, CA")
        assertNotNull(second)
        assertEquals(first.coords.lat, second.coords.lat)
        assertEquals(first.coords.lng, second.coords.lng)
    }

    @Test
    @Tag("network")
    fun `test geocoding unresolvable location returns null`() {
        val result = Geocoder.geocode("Unknown Test Village 12345")
        assertNull(result)
    }

    @Test
    @Tag("network")
    fun `test non-California locations resolve without fastResult collision`() {
        val result = Geocoder.geocode("Port Angeles, WA")
        assertNotNull(result)
        // Ensure Port Angeles does NOT incorrectly resolve to Los Angeles, CA
        assert(!result.displayName.lowercase().contains("los angeles")) {
            "Port Angeles, WA should not resolve to Los Angeles!"
        }
    }
}

private class MockHttpResponse(private val bodyText: String, private val status: Int = 200) :
    HttpResponse<String> {
    override fun statusCode(): Int = status

    override fun body(): String = bodyText

    override fun sslSession(): Optional<SSLSession> = Optional.empty()

    override fun uri(): URI = URI.create("http://localhost")

    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_2

    override fun request(): HttpRequest =
        HttpRequest.newBuilder().uri(URI.create("http://localhost")).build()

    override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

    override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
}

private class MockHttpClient(private val handler: (HttpRequest) -> HttpResponse<String>) :
    HttpClient() {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        return handler(request) as HttpResponse<T>
    }

    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun connectTimeout(): Optional<Duration> = Optional.of(Duration.ofSeconds(5))

    override fun followRedirects(): Redirect = Redirect.NORMAL

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext = SSLContext.getDefault()

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<Authenticator> = Optional.empty()

    override fun version(): Version = Version.HTTP_2

    override fun executor(): Optional<Executor> = Optional.empty()

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException()

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException()
}
