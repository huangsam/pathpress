package com.pathpress.routing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.pathpress.config.Config
import com.pathpress.model.LocationCoords
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

data class GeocodedLocation(val coords: LocationCoords, val displayName: String)

/**
 * Geocoding utility using OpenStreetMap's Nominatim API to resolve location names into coordinates
 * and clean display names globally, backed by an in-memory query cache and rate limiter.
 */
object Geocoder {

    private val logger = LoggerFactory.getLogger(Geocoder::class.java)

    private val httpClient: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Config.current.geocoderConnectTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    private val mapper = jacksonObjectMapper()
    private val cache = ConcurrentHashMap<String, GeocodedLocation>()
    private val lastRequestTimeMs = AtomicLong(0)

    /** Clear in-memory geocoding cache (useful for testing). */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Resolves a location string (which may be "lat,lng" coordinates or a city/landmark name).
     *
     * @param location Query location string (e.g. "San Jose", "37.33,-121.88", or "Seattle, WA")
     * @return GeocodedLocation representing the resolved latitude, longitude, and display name, or
     *   null if unresolvable
     */
    fun geocode(location: String): GeocodedLocation? {
        val trimmed = location.trim()

        // 1. Direct lat,lng coordinates
        val parts = trimmed.split(',')
        if (parts.size == 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lng = parts[1].trim().toDoubleOrNull()
            if (lat != null && lng != null) {
                return GeocodedLocation(LocationCoords(lat, lng), "$lat, $lng")
            }
        }

        val cacheKey = trimmed.lowercase()
        cache[cacheKey]?.let {
            return it
        }

        // 2. Query Nominatim API with query caching and rate limiting
        val queriesToTry = listOf(trimmed, "$trimmed, USA")

        for (query in queriesToTry) {
            val result = queryNominatim(query)
            if (result != null) {
                cache[cacheKey] = result
                return result
            }
        }

        logger.warn("Could not geocode location '$trimmed' via OSM/Nominatim")
        return null
    }

    private fun queryNominatim(queryString: String): GeocodedLocation? {
        try {
            // Enforce Nominatim 1 request / second policy
            val now = System.currentTimeMillis()
            val lastTime = lastRequestTimeMs.get()
            val elapsed = now - lastTime
            if (elapsed < 1000) {
                Thread.sleep(1000 - elapsed)
            }
            lastRequestTimeMs.set(System.currentTimeMillis())

            val encodedQuery = URLEncoder.encode(queryString, "UTF-8")
            val uri =
                URI.create(
                    "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=5"
                )

            val request =
                HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "PathPressRoadTripPlanner/1.0 (contact@pathpress.org)")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200 && !response.body().isNullOrBlank()) {
                val jsonNodes: List<Map<String, Any>> = mapper.readValue(response.body())
                if (jsonNodes.isNotEmpty()) {
                    val placeTypes =
                        setOf("city", "town", "village", "hamlet", "municipality", "locality")
                    val selected =
                        jsonNodes.firstOrNull { node ->
                            val clazz = node["class"]?.toString()?.lowercase()
                            val type = node["type"]?.toString()?.lowercase()
                            val addresstype = node["addresstype"]?.toString()?.lowercase()
                            val displayName = node["display_name"]?.toString()?.lowercase() ?: ""

                            (clazz == "place" || type in placeTypes || addresstype in placeTypes) &&
                                !displayName.startsWith("county")
                        } ?: jsonNodes[0]

                    val latStr = selected["lat"]?.toString()
                    val lonStr = selected["lon"]?.toString()
                    val displayNameStr = selected["display_name"]?.toString()

                    if (latStr != null && lonStr != null) {
                        val coords =
                            LocationCoords(
                                latStr.toDoubleOrNull() ?: 0.0,
                                lonStr.toDoubleOrNull() ?: 0.0,
                            )
                        val shortName =
                            displayNameStr?.split(',')?.take(2)?.joinToString(",") ?: queryString
                        return GeocodedLocation(coords, shortName.trim())
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Nominatim geocoding failed for query '$queryString': ${e.message}", e)
        }
        return null
    }
}
