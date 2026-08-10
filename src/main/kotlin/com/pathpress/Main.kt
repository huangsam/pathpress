package com.pathpress

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.pathpress.config.Config
import com.pathpress.export.PdfExporter
import com.pathpress.model.DistanceUnit
import com.pathpress.pbf.PbfPathResolver
import com.pathpress.routing.GeocodingException
import com.pathpress.routing.RouteCalculationException
import com.pathpress.routing.TripPlanningException
import org.slf4j.LoggerFactory

/** Standardized application process exit codes. */
object ExitCode {
    /** Spatial route calculation or graph connectivity failure. */
    const val ROUTING_ERROR: Int = 2

    /** Location geocoding or address resolution failure. */
    const val GEOCODING_ERROR: Int = 3

    /** Internal or unhandled application error. */
    const val INTERNAL_ERROR: Int = 4
}

object BuildConfig {
    val VERSION: String by lazy {
        BuildConfig::class.java.getResourceAsStream("/version.properties")?.use { stream ->
            val props = java.util.Properties()
            props.load(stream)
            props.getProperty("version")
        } ?: "SNAPSHOT"
    }
}

/** PathPress CLI command implemented using Clikt. */
open class PathPressCommand(
    private val config: Config = Config.fromEnv(),
    private val orchestrator: TripPlannerOrchestrator = TripPlannerOrchestrator(config = config),
) : CliktCommand(name = "pathpress") {
    override fun help(context: Context): String =
        "PathPress - A robust, hybrid AI road trip planner using OpenStreetMap & GraphHopper."

    val startLocation by
        option("--start", help = "Starting location name or lat,lng coordinates").required()
    val endLocation by
        option("--end", help = "Destination location name or lat,lng coordinates").required()
    val days by option("--days", help = "Number of days to spread the trip across").int().default(1)
    val outputFile by option("--output", help = "Output PDF file path").default("itinerary.pdf")
    val rawPbfPath by
        option("--pbf", help = "Path to OSM PBF file").default(PbfPathResolver.defaultPath())
    val graphPath by
        option(
            "--graph",
            help = "GraphHopper graph storage directory (defaults to .graphhopper/<pbf-slug>)",
        )
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
            .default(config.defaultPoisPerLeg)
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

        try {
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
        } catch (e: RouteCalculationException) {
            logger.error("Route calculation failed [${e.kind}]: ${e.message}")
            System.err.println("Error [RouteCalculation - ${e.kind}]: ${e.message}")
            throw ProgramResult(ExitCode.ROUTING_ERROR)
        } catch (e: GeocodingException) {
            logger.error("Geocoding failed for '${e.locationName}': ${e.message}")
            System.err.println("Error [Geocoding]: ${e.message}")
            throw ProgramResult(ExitCode.GEOCODING_ERROR)
        } catch (e: TripPlanningException) {
            logger.error("Trip planning failure: ${e.message}", e)
            System.err.println("Error [TripPlanning]: ${e.message}")
            throw ProgramResult(ExitCode.INTERNAL_ERROR)
        } catch (e: Exception) {
            logger.error("Unexpected failure: ${e.message}", e)
            System.err.println("Error [Internal]: ${e.message}")
            throw ProgramResult(ExitCode.INTERNAL_ERROR)
        }
    }
}

fun main(args: Array<String>) = PathPressCommand().main(args)
