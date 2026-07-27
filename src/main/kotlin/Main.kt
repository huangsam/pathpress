package com.pathpress

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.pathpress.core.*

object BuildConfig {
    const val VERSION: String = "1.2.0"
}

/** PathPress CLI command implemented using Clikt. */
class PathPressCommand :
    CliktCommand(
        name = "pathpress",
        help =
            "PathPress - A robust, hybrid AI road trip planner using OpenStreetMap & GraphHopper.",
    ) {
    val startLocation by
        option("--start", help = "Starting location name or lat,lng coordinates").required()
    val endLocation by
        option("--end", help = "Destination location name or lat,lng coordinates").required()
    val days by option("--days", help = "Number of days to spread the trip across").int().default(1)
    val outputFile by option("--output", help = "Output PDF file path").default("itinerary.pdf")
    val pbfPath by
        option("--pbf", help = "Path to OSM PBF file")
            .default(System.getenv("PATHPRESS_PBF") ?: "california-latest.osm.pbf")
    val graphPath by
        option("--graph", help = "GraphHopper graph storage directory").default(".graphhopper")
    val prompt by
        option(
            "--prompt",
            help =
                "Natural language trip themes, vibe, or preferences (e.g. 'coastal scenic bakeries')",
        )
    val profile by
        option("--profile", help = "Routing profile ('scenic' or 'car')").default("scenic")
    val llmProviderName by
        option("--llm-provider", help = "LLM provider: gemini, claude, openai, ollama, or none")
            .default("none")
    val llmModel by
        option("--llm-model", help = "Model name for LLM (e.g. 'qwen3.6:35b-mlx')")
            .default("qwen3.6:35b-mlx")
    val llmKey by option("--llm-key", help = "API Key for the chosen LLM provider")
    val llmUrl by
        option("--llm-url", help = "Endpoint URL for LLM (e.g. for Ollama or custom server)")
    val verbose by
        option(
                "-v",
                "--verbose",
                help = "Print detailed POI corridor breakdown and metrics to terminal",
            )
            .flag(default = false)

    override fun run() {
        println("PathPress v${BuildConfig.VERSION}")
        println("=".repeat(50))
        println("Start Input: $startLocation")
        println("End Input: $endLocation")
        println("Duration: $days days")
        println("Profile: $profile")
        if (!prompt.isNullOrBlank()) println("Prompt: $prompt")
        println("LLM Provider: $llmProviderName (Model: $llmModel)")
        println("Output: $outputFile")
        println("Verbose Mode: ${if (verbose) "ENABLED" else "DISABLED"}")
        println()

        // 1. Geocode start and end locations (resolving typos to clean display names & coordinates)
        println("Geocoding locations...")
        val startGeo = Geocoder.geocode(startLocation)
        val endGeo = Geocoder.geocode(endLocation)
        println(
            "  -> Start: '${startGeo.displayName}' (${startGeo.coords.lat}, ${startGeo.coords.lng})"
        )
        println("  -> End:   '${endGeo.displayName}' (${endGeo.coords.lat}, ${endGeo.coords.lng})")
        println()

        // 2. Initialize LLM Provider & Plan Trip Concept
        println("Initializing AI Trip Planner ($llmProviderName)...")
        val llm = LlmProvider.create(llmProviderName, llmKey, llmUrl, llmModel)
        val tripPlan =
            llm.planTrip(
                startName = startGeo.displayName,
                endName = endGeo.displayName,
                startCoords = startGeo.coords,
                endCoords = endGeo.coords,
                days = days,
                userPrompt = prompt,
            )

        // 3. Initialize GraphHopper Routing Engine
        println("Loading spatial routing data from $pbfPath...")
        val routeCalculator =
            try {
                RouteCalculator.create(graphPath, pbfPath)
            } catch (e: Exception) {
                error(
                    "Failed to initialize GraphHopper. Ensure PBF file exists at $pbfPath\n" +
                        "Error: ${e.message}"
                )
            }

        // 4. Calculate Driving Route & Extract Real Corridor POIs
        println("Calculating driving route & extracting real OSM corridor POIs...")
        val rawLegs =
            routeCalculator.calculateRouteWithLegs(
                startLat = startGeo.coords.lat,
                startLng = startGeo.coords.lng,
                endLat = endGeo.coords.lat,
                endLng = endGeo.coords.lng,
                days = days,
                dayTitles = tripPlan.dayThemes,
                profile = if (profile.lowercase() == "scenic") "car" else profile,
            )

        // 5. Curate POIs & Generate Leg Storytelling with LLM
        println("Curating POIs & storytelling with LLM...")
        val curatedLegs =
            rawLegs.map { leg ->
                val curation = llm.curateLegPois(leg, prompt)
                leg.copy(
                    legStory = curation.legStory,
                    pois = curation.curatedPois,
                    foodRecommendations = curation.foodRecommendations,
                    insiderTips = curation.insiderTips,
                )
            }

        val totalDistance = curatedLegs.sumOf { it.distanceMeters ?: 0.0 }
        val totalDuration = curatedLegs.sumOf { it.durationSeconds ?: 0.0 }
        val route = Route(curatedLegs, totalDistance, totalDuration, narrative = tripPlan.narrative)

        println("Route calculated successfully!")
        println("  Total distance: ${formatDistance(route.totalDistanceMeters)}")
        println("  Estimated duration: ${formatDuration(route.totalDurationSeconds)}")
        println()

        // 6. Print Verbose Detailed POI Breakdown to Terminal if requested
        if (verbose) {
            println("=".repeat(65))
            println("DETAILED POI CORRIDOR BREAKDOWN & CURATION (--verbose)")
            println("=".repeat(65))
            for (leg in route.legs) {
                val legDist = leg.distanceMeters ?: (route.totalDistanceMeters / route.legs.size)
                val legDur = leg.durationSeconds ?: (route.totalDurationSeconds / route.legs.size)
                val overnightStr = leg.endTownName?.let { " [Overnight in $it]" } ?: ""
                println("\nDay ${leg.dayNumber}: ${leg.dayTitle}$overnightStr")
                println(
                    "  Distance: ${formatDistance(legDist)} | Driving Time: ${formatDuration(legDur)}"
                )
                if (!leg.legStory.isNullOrBlank()) {
                    println("  Story: \"${leg.legStory}\"")
                }
                println("  Google Maps Leg Directions: ${leg.toDirectionsUrl()}")

                println("  Real Extracted Corridor POIs (${leg.pois.size}):")
                if (leg.pois.isEmpty()) {
                    println("    (No POIs extracted for this corridor)")
                } else {
                    for (poi in leg.pois) {
                        val poiName = poi.name ?: "Unnamed POI"
                        val distOffStr =
                            PdfExporter.formatOffRouteDistance(poi.distanceFromRouteMeters)?.let {
                                " ($it)"
                            } ?: ""
                        println("    • $poiName [${poi.type}]$distOffStr @ ${poi.lat}, ${poi.lng}")
                        if (!poi.description.isNullOrBlank()) {
                            println("      Description: ${poi.description}")
                        }
                    }
                }

                if (leg.foodRecommendations.isNotEmpty()) {
                    println("  Coffee & Local Food Recommendations:")
                    for (rec in leg.foodRecommendations) {
                        println("    ☕ $rec")
                    }
                }

                if (leg.insiderTips.isNotEmpty()) {
                    println("  Insider Tips:")
                    for (tip in leg.insiderTips) {
                        println("    💡 $tip")
                    }
                }
            }
            println("=".repeat(65))
            println()
        }

        // 7. Render HTML & Export to PDF
        println("Exporting itinerary to PDF ($outputFile)...")
        val htmlContent = PdfExporter.generateHtml(route, startGeo.displayName, endGeo.displayName)
        PdfExporter.exportToPdf(htmlContent, outputFile)

        println("✓ PDF exported successfully to: $outputFile")
        println()
        println("Daily Summary:")
        for (leg in route.legs) {
            val legDist = leg.distanceMeters ?: (route.totalDistanceMeters / route.legs.size)
            val legDur = leg.durationSeconds ?: (route.totalDurationSeconds / route.legs.size)
            val endTown = leg.endTownName?.let { " -> Overnight in $it" } ?: ""
            println(
                "  Day ${leg.dayNumber}: ${leg.dayTitle}$endTown - ${formatDistance(legDist)} (${formatDuration(legDur)})"
            )
        }
    }
}

fun main(args: Array<String>) = PathPressCommand().main(args)

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
