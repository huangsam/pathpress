package com.pathpress.poi

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.pathpress.model.POI
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Hybrid address resolver providing physical street address strings for POIs. Inspects OSM tags
 * first (`addr:housenumber`, `addr:street`, `addr:city`, etc.) and falls back to Nominatim reverse
 * geocoding backed by an in-memory query cache.
 */
object AddressResolver {

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    private val mapper = jacksonObjectMapper()
    private val cache = ConcurrentHashMap<String, String>()
    private val lastRequestTimeMs = AtomicLong(0)

    /** Clear in-memory address resolution cache (useful for testing). */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Resolve physical street address for a given POI using OSM tags or Nominatim reverse geocoding
     * fallback.
     */
    fun resolveAddress(poi: POI): String {
        // 1. First, inspect OSM tags on the POI
        val osmAddress = resolveFromOsmTags(poi.tags)
        if (!osmAddress.isNullOrBlank()) {
            return osmAddress
        }

        // 2. Query Nominatim reverse geocoding API with caching & rate limiting
        val cacheKey = String.format(java.util.Locale.US, "%.4f,%.4f", poi.lat, poi.lng)
        cache[cacheKey]?.let {
            return it
        }

        val reverseAddress = reverseGeocode(poi.lat, poi.lng)
        val finalAddress =
            if (!reverseAddress.isNullOrBlank()) {
                reverseAddress
            } else {
                // Fallback formatted coordinates or city/location fallback
                String.format(java.util.Locale.US, "%.4f, %.4f", poi.lat, poi.lng)
            }

        cache[cacheKey] = finalAddress
        return finalAddress
    }

    /** Extract structured address string from OSM tags if street/city information exists. */
    internal fun resolveFromOsmTags(tags: Map<String, String>): String? {
        val houseNumber = tags["addr:housenumber"]?.trim()
        val street = tags["addr:street"]?.trim()
        val city =
            tags["addr:city"]?.trim() ?: tags["addr:town"]?.trim() ?: tags["addr:village"]?.trim()
        val state = tags["addr:state"]?.trim()
        val postcode = tags["addr:postcode"]?.trim()

        if (!street.isNullOrBlank()) {
            val streetPart = if (!houseNumber.isNullOrBlank()) "$houseNumber $street" else street
            val cityStatePart =
                listOfNotNull(
                        city.takeIf { !it.isNullOrBlank() },
                        state.takeIf { !it.isNullOrBlank() },
                    )
                    .joinToString(" ")
            val fullCityPart =
                when {
                    cityStatePart.isNotBlank() && !postcode.isNullOrBlank() ->
                        "$cityStatePart $postcode"
                    cityStatePart.isNotBlank() -> cityStatePart
                    !postcode.isNullOrBlank() -> postcode
                    else -> ""
                }

            return listOf(streetPart, fullCityPart).filter { it.isNotBlank() }.joinToString(", ")
        }
        return null
    }

    private fun reverseGeocode(lat: Double, lng: Double): String? {
        try {
            // Enforce Nominatim 1 request / second rate limit policy
            val now = System.currentTimeMillis()
            val lastTime = lastRequestTimeMs.get()
            val elapsed = now - lastTime
            if (elapsed < 1000) {
                Thread.sleep(1000 - elapsed)
            }
            lastRequestTimeMs.set(System.currentTimeMillis())

            val uriStr =
                String.format(
                    java.util.Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?lat=%.6f&lon=%.6f&format=json&addressdetails=1",
                    lat,
                    lng,
                )
            val uri = URI.create(uriStr)

            val request =
                HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "PathPressRoadTripPlanner/1.0 (contact@pathpress.org)")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200 && !response.body().isNullOrBlank()) {
                val jsonMap: Map<String, Any> = mapper.readValue(response.body())
                @Suppress("UNCHECKED_CAST")
                val addressMap = jsonMap["address"] as? Map<String, Any> ?: emptyMap()

                val houseNumber = addressMap["house_number"]?.toString()?.trim()
                val road =
                    addressMap["road"]?.toString()?.trim()
                        ?: addressMap["street"]?.toString()?.trim()
                        ?: addressMap["pedestrian"]?.toString()?.trim()
                val city =
                    addressMap["city"]?.toString()?.trim()
                        ?: addressMap["town"]?.toString()?.trim()
                        ?: addressMap["village"]?.toString()?.trim()
                        ?: addressMap["county"]?.toString()?.trim()
                val state = addressMap["state"]?.toString()?.trim()
                val postcode = addressMap["postcode"]?.toString()?.trim()

                val streetPart =
                    if (!road.isNullOrBlank()) {
                        if (!houseNumber.isNullOrBlank()) "$houseNumber $road" else road
                    } else null

                val cityPart =
                    listOfNotNull(
                            city.takeIf { !it.isNullOrBlank() },
                            state.takeIf { !it.isNullOrBlank() },
                        )
                        .joinToString(" ")
                val fullCityPart =
                    when {
                        cityPart.isNotBlank() && !postcode.isNullOrBlank() -> "$cityPart $postcode"
                        cityPart.isNotBlank() -> cityPart
                        !postcode.isNullOrBlank() -> postcode
                        else -> null
                    }

                val structured =
                    listOfNotNull(streetPart, fullCityPart)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                if (structured.isNotBlank()) {
                    return structured
                }

                val displayNameStr = jsonMap["display_name"]?.toString()
                if (!displayNameStr.isNullOrBlank()) {
                    return displayNameStr.split(',').take(3).joinToString(",").trim()
                }
            }
        } catch (e: Exception) {
            // Silently return null on timeout or network errors
        }
        return null
    }
}
