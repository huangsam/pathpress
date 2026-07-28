package com.pathpress

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.pathpress.export.*
import com.pathpress.export.PdfExporter.formatDuration
import com.pathpress.llm.*
import com.pathpress.model.*
import com.pathpress.poi.*
import com.pathpress.routing.*
import org.slf4j.LoggerFactory

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
        option(
                "--llm-model",
                help = "Model name for LLM (e.g. '${LlmProvider.DEFAULT_OLLAMA_MODEL}')",
            )
            .default(LlmProvider.DEFAULT_OLLAMA_MODEL)
    val llmKey by option("--llm-key", help = "API Key for the chosen LLM provider")
    val llmUrl by
        option("--llm-url", help = "Endpoint URL for LLM (e.g. for Ollama or custom server)")
    val poisPerLeg by
        option("--pois-per-leg", help = "Maximum POIs to extract per day/leg")
            .int()
            .default(PoiExtractor.DEFAULT_POIS_PER_LEG)
    val includeThemeParks by
        option(
                "--include-theme-parks",
                help = "Include ticketed theme park rides (e.g. roller coasters, monorails)",
            )
            .flag(default = false)
    val verbose by
        option(
                "-v",
                "--verbose",
                help = "Print detailed POI corridor breakdown and metrics to terminal",
            )
            .flag(default = false)
    val distanceUnitStr by
        option(
                "--distance-unit",
                "--units",
                help = "Distance units for display & PDF: 'imperial' (mi/ft) or 'metric' (km/m)",
            )
            .default("metric")

    private val logger = LoggerFactory.getLogger(PathPressCommand::class.java)

    override fun run() {
        if (!prompt.isNullOrBlank() && llmProviderName.lowercase() == "none") {
            throw com.github.ajalt.clikt.core.UsageError(
                "A --prompt was provided, but no --llm-provider was specified. Please specify a provider (e.g., --llm-provider ollama) or remove the prompt."
            )
        }

        val distanceUnit =
            if (distanceUnitStr.lowercase() in listOf("imperial", "mi", "miles", "feet", "ft")) {
                DistanceUnit.IMPERIAL
            } else {
                DistanceUnit.METRIC
            }

        logger.info("PathPress v${BuildConfig.VERSION}")
        logger.info("=".repeat(50))
        logger.info("Start Input: $startLocation")
        logger.info("End Input: $endLocation")
        logger.info("Duration: $days days")
        logger.info("Profile: $profile")
        if (!prompt.isNullOrBlank()) logger.info("Prompt: $prompt")
        logger.info("LLM Provider: $llmProviderName (Model: $llmModel)")
        logger.info("Distance Unit: ${distanceUnit.name.lowercase()}")
        logger.info("Output: $outputFile")
        logger.info("Verbose Mode: ${if (verbose) "ENABLED" else "DISABLED"}")

        // 1. Geocode start and end locations (resolving typos to clean display names & coordinates)
        logger.info("Geocoding locations...")
        val startGeo = Geocoder.geocode(startLocation)
        val endGeo = Geocoder.geocode(endLocation)
        logger.info(
            "  -> Start: '${startGeo.displayName}' (${startGeo.coords.lat}, ${startGeo.coords.lng})"
        )
        logger.info(
            "  -> End:   '${endGeo.displayName}' (${endGeo.coords.lat}, ${endGeo.coords.lng})"
        )

        // 2. Initialize LLM Provider & Plan Trip Concept
        logger.info("Initializing AI Trip Planner ($llmProviderName)...")
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
        logger.info("Loading spatial routing data from $pbfPath...")
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
        logger.info("Calculating driving route & extracting real OSM corridor POIs...")
        val rawLegs =
            routeCalculator.calculateRouteWithLegs(
                startLat = startGeo.coords.lat,
                startLng = startGeo.coords.lng,
                endLat = endGeo.coords.lat,
                endLng = endGeo.coords.lng,
                days = days,
                dayTitles = tripPlan.dayThemes,
                profile = if (profile.lowercase() == "scenic") "car" else profile,
                limitPerLeg = poisPerLeg,
                userPrompt = prompt,
                includeThemeParks = includeThemeParks,
            )

        // 5. Curate POIs & Generate Leg Storytelling with LLM
        logger.info("Curating POIs & storytelling with LLM...")
        val curatedLegs =
            rawLegs.map { leg ->
                val curation = llm.curateLegPois(leg, prompt)
                leg.copy(legStory = curation.legStory, pois = curation.curatedPois)
            }

        val totalDistance = curatedLegs.sumOf { it.distanceMeters ?: 0.0 }
        val totalDuration = curatedLegs.sumOf { it.durationSeconds ?: 0.0 }
        val route = Route(curatedLegs, totalDistance, totalDuration, narrative = tripPlan.narrative)

        logger.info("Route calculated successfully!")
        logger.info(
            "  Total distance: ${PdfExporter.formatDistance(route.totalDistanceMeters, distanceUnit)}"
        )
        logger.info("  Estimated duration: ${formatDuration(route.totalDurationSeconds)}")

        // 6. Print Verbose Detailed POI Breakdown to Terminal if requested
        if (verbose) {
            logger.info("=".repeat(65))
            logger.info("DETAILED POI CORRIDOR BREAKDOWN & CURATION (--verbose)")
            logger.info("=".repeat(65))
            for (leg in route.legs) {
                val legDist = leg.distanceMeters ?: (route.totalDistanceMeters / route.legs.size)
                val legDur = leg.durationSeconds ?: (route.totalDurationSeconds / route.legs.size)
                val overnightStr = leg.endTownName?.let { " [Overnight in $it]" } ?: ""

                val legLog = buildString {
                    appendLine("Day ${leg.dayNumber}: ${leg.dayTitle}$overnightStr")
                    appendLine(
                        "  Distance: ${PdfExporter.formatDistance(legDist, distanceUnit)} | Driving Time: ${formatDuration(legDur)}"
                    )
                    if (!leg.legStory.isNullOrBlank()) {
                        appendLine("  Story: \"${leg.legStory}\"")
                    }
                    appendLine("  Google Maps Leg Directions: ${leg.toDirectionsUrl()}")

                    appendLine("  Real Extracted Corridor POIs (${leg.pois.size}):")
                    if (leg.pois.isEmpty()) {
                        appendLine("    (No POIs extracted for this corridor)")
                    } else {
                        for (poi in leg.pois) {
                            val poiName = poi.name ?: "Unnamed POI"
                            val distOffStr =
                                PdfExporter.formatOffRouteDistance(
                                        poi.distanceFromRouteMeters,
                                        distanceUnit,
                                    )
                                    ?.let { " ($it)" } ?: ""
                            appendLine(
                                "    • $poiName [${poi.type}]$distOffStr @ ${poi.lat}, ${poi.lng}"
                            )
                            if (!poi.description.isNullOrBlank()) {
                                appendLine("      Description: ${poi.description}")
                            }
                        }
                    }
                }
                logger.info("\n{}", legLog.trimEnd())
            }
            logger.info("=".repeat(65))
        }

        // 7. Render HTML & Export to PDF
        logger.info("Exporting itinerary to PDF ($outputFile)...")
        val htmlContent =
            PdfExporter.generateHtml(
                route,
                startGeo.displayName,
                endGeo.displayName,
                unit = distanceUnit,
            )
        PdfExporter.exportToPdf(htmlContent, outputFile)

        logger.info("✓ PDF exported successfully to: $outputFile")

        val summaryLog = buildString {
            appendLine("Daily Summary:")
            for (leg in route.legs) {
                val legDist = leg.distanceMeters ?: (route.totalDistanceMeters / route.legs.size)
                val legDur = leg.durationSeconds ?: (route.totalDurationSeconds / route.legs.size)
                val rawTitle = leg.dayTitle ?: "Scenic Leg"
                val cleanTitle =
                    rawTitle
                        .replace(Regex("^Day\\s+\\d+[:\\s-]*", RegexOption.IGNORE_CASE), "")
                        .ifBlank { "Scenic Leg" }
                val endTown = leg.endTownName?.let { " -> Overnight in $it" } ?: ""
                appendLine(
                    "  Day ${leg.dayNumber}: $cleanTitle$endTown - ${PdfExporter.formatDistance(legDist, distanceUnit)} (${formatDuration(legDur)})"
                )
            }
        }
        logger.info("\n{}", summaryLog.trimEnd())
    }
}

fun main(args: Array<String>) = PathPressCommand().main(args)
