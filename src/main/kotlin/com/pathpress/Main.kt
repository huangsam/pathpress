package com.pathpress

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.pathpress.config.Config
import com.pathpress.export.PdfExporter
import com.pathpress.export.PdfExporter.formatDuration
import com.pathpress.llm.LlmProvider
import com.pathpress.model.DistanceUnit
import com.pathpress.model.LocationCoords
import com.pathpress.model.Route
import com.pathpress.routing.Geocoder
import com.pathpress.routing.RouteCalculator
import org.slf4j.LoggerFactory

object BuildConfig {
    val VERSION: String by lazy {
        BuildConfig::class.java.getResourceAsStream("/version.properties")?.use { stream ->
            val props = java.util.Properties()
            props.load(stream)
            props.getProperty("version")
        } ?: "0.1.0-SNAPSHOT"
    }
}

/** PathPress CLI command implemented using Clikt. */
open class PathPressCommand : CliktCommand(name = "pathpress") {
    override fun help(context: Context): String =
        "PathPress - A robust, hybrid AI road trip planner using OpenStreetMap & GraphHopper."

    val startLocation by
        option("--start", help = "Starting location name or lat,lng coordinates").required()
    val endLocation by
        option("--end", help = "Destination location name or lat,lng coordinates").required()
    val days by option("--days", help = "Number of days to spread the trip across").int().default(1)
    val outputFile by option("--output", help = "Output PDF file path").default("itinerary.pdf")
    val rawPbfPath by option("--pbf", help = "Path to OSM PBF file").default(defaultPbfPath())
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
        option("--llm-model", help = "Model name for LLM (defaults to provider default if omitted)")
    val llmKey by option("--llm-key", help = "API Key for the chosen LLM provider")
    val llmUrl by
        option("--llm-url", help = "Endpoint URL for LLM (e.g. for Ollama or custom server)")
    val poisPerLeg by
        option("--pois-per-leg", help = "Maximum POIs to extract per day/leg")
            .int()
            .default(Config.current.defaultPoisPerLeg)
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
        val pbfPath = resolvePbfPath(rawPbfPath)

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
        logger.info("LLM Provider: $llmProviderName (Model: ${llmModel ?: "default"})")
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

        // Resolve spatial intermediate waypoints (from LLM or fallback)
        val resolvedWaypoints =
            tripPlan.waypoints
                .mapNotNull { wp ->
                    if (wp.lat != 0.0 || wp.lng != 0.0) {
                        wp
                    } else if (!wp.name.isNullOrBlank()) {
                        try {
                            logger.info("Geocoding intermediate waypoint '${wp.name}'...")
                            val geo = Geocoder.geocode(wp.name)
                            LocationCoords(geo.coords.lat, geo.coords.lng, geo.displayName)
                        } catch (e: Exception) {
                            logger.warn("Could not geocode LLM waypoint '${wp.name}': ${e.message}")
                            null
                        }
                    } else {
                        null
                    }
                }
                .toMutableList()

        // Fallback injection if waypoints are empty and prompt specifies coastal in California
        // region
        val isCoastalPrompt =
            prompt?.let { p ->
                listOf("coastal", "coast", "beach", "ocean", "highway 1", "pacific coast").any { k
                    ->
                    p.contains(k, ignoreCase = true)
                }
            } ?: false

        if (resolvedWaypoints.isEmpty() && isCoastalPrompt) {
            val isCaRegion =
                listOf(startGeo.displayName, endGeo.displayName).any {
                    it.contains("California", ignoreCase = true) ||
                        it.contains("CA", ignoreCase = true) ||
                        it.contains("San Francisco", ignoreCase = true) ||
                        it.contains("San Jose", ignoreCase = true) ||
                        it.contains("Los Angeles", ignoreCase = true) ||
                        it.contains("San Diego", ignoreCase = true)
                }
            if (isCaRegion) {
                logger.info(
                    "Coastal prompt detected with empty LLM waypoints. Injecting default CA coastal anchor waypoints (Monterey & Pismo Beach)..."
                )
                try {
                    val mty = Geocoder.geocode("Monterey, CA")
                    val psb = Geocoder.geocode("Pismo Beach, CA")
                    resolvedWaypoints.add(
                        LocationCoords(mty.coords.lat, mty.coords.lng, mty.displayName)
                    )
                    resolvedWaypoints.add(
                        LocationCoords(psb.coords.lat, psb.coords.lng, psb.displayName)
                    )
                } catch (e: Exception) {
                    logger.warn("Fallback coastal waypoint geocoding error: ${e.message}")
                }
            }
        }

        if (resolvedWaypoints.isNotEmpty()) {
            logger.info(
                "Routing via ${resolvedWaypoints.size} intermediate waypoints: ${resolvedWaypoints.joinToString { it.name ?: "(${it.lat}, ${it.lng})" }}"
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
                profile = if (profile.lowercase() == "scenic") "car" else profile,
                limitPerLeg = poisPerLeg,
                userPrompt = prompt,
                waypoints = resolvedWaypoints,
            )

        // 5. Curate POIs & Apply Leg Storytelling
        logger.info("Curating POIs & applying leg storytelling...")
        val curatedLegs = rawLegs.mapIndexed { idx, leg ->
            val storyFromPlan = tripPlan.legStories.getOrNull(idx)
            val legWithStory =
                if (!storyFromPlan.isNullOrBlank()) leg.copy(legStory = storyFromPlan) else leg
            val curation = llm.curateLegPois(legWithStory, prompt)
            legWithStory.copy(legStory = curation.legStory, pois = curation.curatedPois)
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

fun resolvePbfPath(requestedPath: String): String {
    val file = java.io.File(requestedPath)
    if (file.exists()) return requestedPath
    val dataFile = java.io.File("data", requestedPath)
    if (dataFile.exists()) return dataFile.path
    return requestedPath
}

fun defaultPbfPath(): String {
    val envPath = System.getenv("PATHPRESS_PBF")
    if (!envPath.isNullOrBlank()) return envPath

    val defaultDataPbf = java.io.File("data", "california-latest.osm.pbf")
    if (defaultDataPbf.exists()) return defaultDataPbf.path

    val dataDir = java.io.File("data")
    if (dataDir.exists() && dataDir.isDirectory) {
        val pbfFile = dataDir.listFiles()?.firstOrNull { it.name.endsWith(".pbf") }
        if (pbfFile != null) return pbfFile.path
    }

    val rootPbf = java.io.File("california-latest.osm.pbf")
    if (rootPbf.exists()) return rootPbf.name

    return "data/california-latest.osm.pbf"
}

fun main(args: Array<String>) = PathPressCommand().main(args)
