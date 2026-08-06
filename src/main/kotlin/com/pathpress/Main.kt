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
import com.pathpress.model.DistanceUnit
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
open class PathPressCommand(
    private val orchestrator: TripPlannerOrchestrator = TripPlannerOrchestrator()
) : CliktCommand(name = "pathpress") {
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

        val request =
            TripPlannerRequest(
                startLocation = startLocation,
                endLocation = endLocation,
                days = days,
                profile = profile,
                prompt = prompt,
                llmProviderName = llmProviderName,
                llmModel = llmModel,
                llmKey = llmKey,
                llmUrl = llmUrl,
                poisPerLeg = poisPerLeg,
                distanceUnit = distanceUnit,
                pbfPath = rawPbfPath,
                graphPath = graphPath,
            )

        CliReporter.reportHeader(request, outputFile, verbose)

        val result = orchestrator.planTrip(request)

        CliReporter.reportRouteSummary(result.route, request.distanceUnit)

        if (verbose) {
            CliReporter.reportVerboseBreakdown(result.route, request.distanceUnit)
        }

        logger.info("Exporting itinerary to PDF ($outputFile)...")
        val htmlContent =
            PdfExporter.generateHtml(
                result.route,
                result.startGeo.displayName,
                result.endGeo.displayName,
                unit = request.distanceUnit,
            )
        PdfExporter.exportToPdf(htmlContent, outputFile)
        logger.info("✓ PDF exported successfully to: $outputFile")

        CliReporter.reportDailySummary(result.route, request.distanceUnit)
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
