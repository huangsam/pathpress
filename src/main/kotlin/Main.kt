package com.pathpress

import com.graphhopper.util.Helper
import com.pathpress.core.*

/**
 * Build configuration object.
 */
object BuildConfig {
    const val VERSION: String = "1.0.0"
}

/**
 * PathPress - A CLI tool for planning road trips using OpenStreetMap data.
 *
 * Usage:
 *   ./pathpress --start "San Jose" --end "San Diego" --days 4 --output itinerary.pdf
 *
 * The PBF file path can be set via the PATHPRESS_PBF environment variable,
 * or defaults to "california-latest.osm.pbf" in the current directory.
 */
fun main(args: Array<String>) {
    // Parse command line arguments manually
    val parsedArgs = parseArguments(args)

    val startLocation = parsedArgs["--start"]
        ?: error("Start location is required (--start)")

    val endLocation = parsedArgs["--end"]
        ?: error("End location is required (--end)")

    val days = parsedArgs["--days"]?.toIntOrNull() ?: 1
    val outputFile = parsedArgs["--output"] ?: "itinerary.pdf"
    val pbfPath = parsedArgs["--pbf"] ?: System.getenv("PATHPRESS_PBF") ?: "california-latest.osm.pbf"
    val graphPath = parsedArgs["--graph"] ?: ".graphhopper"

    // Parse locations (either as names or coordinates)
    val startCoords = parseLocation(startLocation)
    val endCoords = parseLocation(endLocation)

    println("PathPress v${BuildConfig.VERSION}")
    println("=" .repeat(40))
    println("Start: $startLocation (${startCoords.lat}, ${startCoords.lng})")
    println("End: $endLocation (${endCoords.lat}, ${endCoords.lng})")
    println("Duration: $days days")
    println("Output: $outputFile")
    println()

    // Initialize the route calculator
    println("Loading routing data from $pbfPath...")
    val routeCalculator = try {
        RouteCalculator.create(graphPath, pbfPath)
    } catch (e: Exception) {
        error(
            "Failed to initialize GraphHopper. Ensure PBF file exists at $pbfPath\n" +
            "Error: ${e.message}"
        )
    }

    // Calculate the route
    println("Calculating route...")
    val legs = routeCalculator.calculateRouteWithLegs(
        startLat = startCoords.lat,
        startLng = startCoords.lng,
        endLat = endCoords.lat,
        endLng = endCoords.lng,
        days = days
    )

    val totalDistance = legs.sumOf { it.distanceMeters ?: 0.0 }
    val totalDuration = legs.sumOf { it.durationSeconds ?: 0.0 }

    val route = Route(legs, totalDistance, totalDuration)

    println("Route calculated successfully!")
    println("Total distance: ${formatDistance(route.totalDistanceMeters)}")
    println("Estimated duration: ${formatDuration(route.totalDurationSeconds)}")
    println()

    // Generate HTML and export to PDF
    println("Generating PDF itinerary...")
    val htmlContent = PdfExporter.generateHtml(route, startLocation, endLocation)
    PdfExporter.exportToPdf(htmlContent, outputFile)

    println("PDF exported to: $outputFile")

    // Print daily breakdown
    println()
    println("Daily Breakdown:")
    for (leg in route.legs) {
        val legDistance = leg.distanceMeters ?: route.totalDistanceMeters / route.legs.size
        val legDuration = leg.durationSeconds ?: route.totalDurationSeconds / route.legs.size
        println("  Day ${leg.dayNumber}: ${formatDistance(legDistance)} (${formatDuration(legDuration)})")
        println("    Maps: ${leg.toDirectionsUrl()}")
    }
}

/**
 * Parse command line arguments into a map.
 */
fun parseArguments(args: Array<String>): Map<String, String?> {
    val result = mutableMapOf<String, String?>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("--") && i + 1 < args.size && !args[i + 1].startsWith("--")) {
            result[arg] = args[i + 1]
            i += 2
        } else if (arg.startsWith("--")) {
            // Flag without value
            result[arg] = null
            i += 1
        } else {
            i += 1
        }
    }
    return result
}

private fun parseLocation(location: String): LocationCoords {
    // Try to parse as coordinates first (lat,lng format)
    location.split(',').takeIf { it.size == 2 }?.let { parts ->
        val coords = try {
            LocationCoords(
                lat = parts[0].trim().toDouble(),
                lng = parts[1].trim().toDouble()
            )
        } catch (_: NumberFormatException) {
            null
        }
        if (coords != null) return coords
    }

    // For a real implementation, you would geocode the location names here.
    // For now, return placeholder coordinates based on the first letter of the location.
    // In production, use an offline geocoder or look up in OSM data.
    val hash = location.lowercase().hashCode()
    val latBase = 32.0 + (hash % 100) * 0.1
    val lngBase = -120.0 + (Math.abs(hash) % 100) * 0.1

    return LocationCoords(lat = latBase, lng = lngBase)
}

private fun formatDistance(meters: Double): String {
    return if (meters >= 1000) {
        "${String.format("%.1f", meters / 1000)} km"
    } else {
        "${String.format("%.0f", meters)} m"
    }
}

private fun formatDuration(seconds: Double): String {
    val hours = seconds.toInt() / 3600
    val minutes = (seconds.toInt() % 3600) / 60
    return if (hours > 0) {
        "${hours}h ${minutes}m"
    } else {
        "${minutes}m"
    }
}
