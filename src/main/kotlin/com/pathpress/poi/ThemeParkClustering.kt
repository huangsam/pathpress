package com.pathpress.poi

import com.pathpress.geo.GeoUtils
import com.pathpress.model.POI

/** Handles domain-matching and geographic clustering logic for theme park POI candidates. */
object ThemeParkClustering {

    /** Deduplicates theme park POIs within a cluster radius or matching domain. */
    fun deduplicateThemeParks(
        candidates: List<POI>,
        clusterRadiusMeters: Double = 1500.0,
    ): List<POI> {
        val (themeParkPois, otherPois) = candidates.partition { isThemeParkNode(it) }
        if (themeParkPois.size <= 1) return candidates

        val clustered = mutableListOf<POI>()
        val visited = BooleanArray(themeParkPois.size)

        for (i in themeParkPois.indices) {
            if (visited[i]) continue
            visited[i] = true
            val current = themeParkPois[i]
            val cluster = mutableListOf(current)
            val currentDomain = getThemeParkDomain(current)

            for (j in i + 1 until themeParkPois.size) {
                if (visited[j]) continue
                val candidate = themeParkPois[j]
                val dist =
                    GeoUtils.haversineMeters(current.lat, current.lng, candidate.lat, candidate.lng)
                val candidateDomain = getThemeParkDomain(candidate)
                val sameDomain = currentDomain != null && currentDomain == candidateDomain
                if (dist <= clusterRadiusMeters || sameDomain) {
                    visited[j] = true
                    cluster.add(candidate)
                }
            }

            val bestRepresentative =
                cluster.minByOrNull { it.distanceFromRouteMeters ?: Double.MAX_VALUE } ?: current
            clustered.add(bestRepresentative)
        }

        return otherPois + clustered
    }

    /** Checks if a POI represents a theme park node based on OSM tags or domain matching. */
    fun isThemeParkNode(poi: POI): Boolean {
        val attractionType = poi.tags["attraction"]
        if (
            attractionType in setOf("roller_coaster", "amusement_ride", "water_slide", "carousel")
        ) {
            return true
        }
        val tourism = poi.tags["tourism"]
        val leisure = poi.tags["leisure"]
        val amenity = poi.tags["amenity"]
        if (tourism == "theme_park" || leisure == "amusement_park" || amenity == "theme_park") {
            return true
        }
        val website = getThemeParkDomain(poi)
        return website != null
    }

    /** Extracts known theme park domain from a POI's website tag. */
    fun getThemeParkDomain(poi: POI): String? {
        val website = (poi.tags["website"] ?: "").lowercase()
        return when {
            website.contains("sixflags.com") -> "sixflags.com"
            website.contains("disney") -> "disney.com"
            website.contains("seaworld.com") -> "seaworld.com"
            website.contains("knotts.com") -> "knotts.com"
            website.contains("universalstudios.com") -> "universalstudios.com"
            else -> null
        }
    }
}
