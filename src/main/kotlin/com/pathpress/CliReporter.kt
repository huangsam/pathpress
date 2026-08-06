package com.pathpress

import com.pathpress.export.PdfExporter
import com.pathpress.export.PdfExporter.formatDuration
import com.pathpress.model.DistanceUnit
import com.pathpress.model.Route
import com.pathpress.poi.toDirectionsUrl
import org.slf4j.LoggerFactory

/** Logs human-readable terminal output for CLI execution. */
object CliReporter {
    private val logger = LoggerFactory.getLogger(CliReporter::class.java)

    fun reportHeader(request: TripPlannerRequest, outputFile: String, verbose: Boolean) {
        logger.info("PathPress v${BuildConfig.VERSION}")
        logger.info("=".repeat(50))
        logger.info("Start Input: ${request.startLocation}")
        logger.info("End Input: ${request.endLocation}")
        logger.info("Duration: ${request.days} days")
        logger.info("Profile: ${request.profile}")
        if (!request.prompt.isNullOrBlank()) {
            logger.info("Prompt: ${request.prompt}")
        }
        logger.info(
            "LLM Provider: ${request.llmProviderName} (Model: ${request.llmModel ?: "default"})"
        )
        logger.info("Distance Unit: ${request.distanceUnit.name.lowercase()}")
        logger.info("Output: $outputFile")
        logger.info("Verbose Mode: ${if (verbose) "ENABLED" else "DISABLED"}")
    }

    fun reportRouteSummary(route: Route, distanceUnit: DistanceUnit) {
        logger.info("Route calculated successfully!")
        logger.info(
            "  Total distance: ${PdfExporter.formatDistance(route.totalDistanceMeters, distanceUnit)}"
        )
        logger.info("  Estimated duration: ${formatDuration(route.totalDurationSeconds)}")
    }

    fun reportVerboseBreakdown(route: Route, distanceUnit: DistanceUnit) {
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

    fun reportDailySummary(route: Route, distanceUnit: DistanceUnit) {
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
