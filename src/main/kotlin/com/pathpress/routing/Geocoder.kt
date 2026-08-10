package com.pathpress.routing

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PhotonResponse(val features: List<PhotonFeature> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PhotonFeature(
    val geometry: PhotonGeometry? = null,
    val properties: Map<String, Any?> = emptyMap(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PhotonGeometry(val coordinates: List<Double> = emptyList())

/**
 * Geocoding utility using OpenStreetMap's Photon (Komoot) API as primary and Nominatim API as
 * fallback to resolve location names into coordinates and clean display names, backed by an
 * in-memory query cache and rate limiting.
 */
object Geocoder {

    private val logger = LoggerFactory.getLogger(Geocoder::class.java)

    private val SETTLEMENT_TYPES =
        setOf("city", "town", "village", "hamlet", "municipality", "locality", "suburb")

    private val SECONDARY_PLACE_TYPES =
        setOf("neighbourhood", "borough", "park", "attraction", "tourism", "place")

    private val NOMINATIM_PLACE_TYPES =
        setOf("city", "town", "village", "hamlet", "municipality", "locality")

    @Volatile
    internal var httpClient: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Config.fromEnv().geocoderConnectTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    private val mapper = jacksonObjectMapper()
    private val cache = ConcurrentHashMap<String, GeocodedLocation>()
    private val lastPhotonRequestTimeMs = AtomicLong(0)
    private val lastNominatimRequestTimeMs = AtomicLong(0)

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

        val queriesToTry = listOf(trimmed, "$trimmed, USA")

        // 2. Query Photon (OSM) first for all query variants
        for (query in queriesToTry) {
            val result = queryPhoton(query)
            if (result != null) {
                cache[cacheKey] = result
                return result
            }
        }

        // 3. Fallback to Nominatim API with query caching and rate limiting
        for (query in queriesToTry) {
            val result = queryNominatim(query)
            if (result != null) {
                cache[cacheKey] = result
                return result
            }
        }

        logger.warn("Could not geocode location '$trimmed' via OSM/Photon/Nominatim")
        return null
    }

    private fun throttlePhoton() {
        synchronized(lastPhotonRequestTimeMs) {
            val now = System.currentTimeMillis()
            val lastTime = lastPhotonRequestTimeMs.get()
            val elapsed = now - lastTime
            if (elapsed < 1000) {
                Thread.sleep(1000 - elapsed)
            }
            lastPhotonRequestTimeMs.set(System.currentTimeMillis())
        }
    }

    private fun queryPhoton(queryString: String): GeocodedLocation? {
        try {
            throttlePhoton()

            val encodedQuery = URLEncoder.encode(queryString, "UTF-8")
            val uri = URI.create("https://photon.komoot.io/api/?q=$encodedQuery&limit=5")
            val timeout = Duration.ofSeconds(Config.fromEnv().geocoderTimeoutSeconds)
            val request =
                HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "PathPressRoadTripPlanner/1.0 (contact@pathpress.org)")
                    .timeout(timeout)
                    .GET()
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200 && !response.body().isNullOrBlank()) {
                val root: PhotonResponse = mapper.readValue(response.body())
                val features = root.features
                if (features.isNotEmpty()) {
                    val usSettlement = features.firstOrNull { isUsSettlement(it.properties) }
                    val selected =
                        usSettlement
                            ?: features.firstOrNull { isUsSecondaryPlace(it.properties) }
                            ?: features.firstOrNull { feat ->
                                feat.properties["countrycode"]?.toString()?.uppercase() == "US"
                            }
                            ?: return null

                    val coordsList = selected.geometry?.coordinates
                    val props = selected.properties

                    if (coordsList != null && coordsList.size >= 2) {
                        val lon = coordsList[0]
                        val lat = coordsList[1]
                        val rawName =
                            props["name"]?.toString()
                                ?: props["city"]?.toString()
                                ?: props["town"]?.toString()
                                ?: props["village"]?.toString()
                                ?: props["municipality"]?.toString()
                                ?: props["county"]?.toString()
                                ?: props["state"]?.toString()
                                ?: queryString
                        val state = props["state"]?.toString()
                        val shortName =
                            if (
                                !state.isNullOrBlank() &&
                                    !rawName.contains(state, ignoreCase = true)
                            ) {
                                "$rawName, $state"
                            } else {
                                rawName
                            }
                        return GeocodedLocation(LocationCoords(lat, lon), shortName.trim())
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Photon geocoding query failed for '$queryString': ${e.message}")
        }
        return null
    }

    private fun throttleNominatim() {
        synchronized(lastNominatimRequestTimeMs) {
            val now = System.currentTimeMillis()
            val lastTime = lastNominatimRequestTimeMs.get()
            val elapsed = now - lastTime
            if (elapsed < 1000) {
                Thread.sleep(1000 - elapsed)
            }
            lastNominatimRequestTimeMs.set(System.currentTimeMillis())
        }
    }

    private fun queryNominatim(queryString: String): GeocodedLocation? {
        try {
            // Enforce Nominatim 1 request / second policy
            throttleNominatim()

            val encodedQuery = URLEncoder.encode(queryString, "UTF-8")
            val uri =
                URI.create(
                    "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=5"
                )
            val timeout = Duration.ofSeconds(Config.fromEnv().geocoderTimeoutSeconds)
            val request =
                HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "PathPressRoadTripPlanner/1.0 (contact@pathpress.org)")
                    .timeout(timeout)
                    .GET()
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200 && !response.body().isNullOrBlank()) {
                val jsonNodes: List<Map<String, Any>> = mapper.readValue(response.body())
                if (jsonNodes.isNotEmpty()) {
                    val selected =
                        jsonNodes.firstOrNull { node ->
                            val clazz = node["class"]?.toString()?.lowercase()
                            val type = node["type"]?.toString()?.lowercase()
                            val addresstype = node["addresstype"]?.toString()?.lowercase()
                            val displayName = node["display_name"]?.toString()?.lowercase() ?: ""

                            (clazz == "place" ||
                                type in NOMINATIM_PLACE_TYPES ||
                                addresstype in NOMINATIM_PLACE_TYPES) &&
                                !displayName.startsWith("county")
                        } ?: jsonNodes[0]

                    val latStr = selected["lat"]?.toString()
                    val lonStr = selected["lon"]?.toString()
                    val displayNameStr = selected["display_name"]?.toString()

                    val lat = latStr?.toDoubleOrNull()
                    val lon = lonStr?.toDoubleOrNull()

                    if (lat != null && lon != null) {
                        val coords = LocationCoords(lat, lon)
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

    private fun isUsSettlement(props: Map<String, Any?>): Boolean {
        val countryCode = props["countrycode"]?.toString()?.uppercase()
        if (countryCode != "US") return false

        val type = props["type"]?.toString()?.lowercase()
        val osmKey = props["osm_key"]?.toString()?.lowercase()
        val osmValue = props["osm_value"]?.toString()?.lowercase()

        return !(type == "county" || osmValue == "county") &&
            (type in SETTLEMENT_TYPES ||
                (osmKey == "place" && osmValue in SETTLEMENT_TYPES) ||
                (osmKey == "place" && osmValue != "park" && osmValue != "tourism"))
    }

    private fun isUsSecondaryPlace(props: Map<String, Any?>): Boolean {
        val countryCode = props["countrycode"]?.toString()?.uppercase()
        if (countryCode != "US") return false

        val type = props["type"]?.toString()?.lowercase()
        val osmKey = props["osm_key"]?.toString()?.lowercase()
        val osmValue = props["osm_value"]?.toString()?.lowercase()

        return type in SECONDARY_PLACE_TYPES ||
            osmKey == "place" ||
            osmKey == "tourism" ||
            osmKey == "historic" ||
            osmValue in SECONDARY_PLACE_TYPES
    }
}
