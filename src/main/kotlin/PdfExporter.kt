package com.pathpress.core

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder

/**
 * Exports trip itineraries to PDF using openhtmltopdf (JVM-native).
 */
object PdfExporter {

    /**
     * Export HTML content to a PDF file.
     *
     * @param htmlContent The HTML string to render
     * @param outputFilePath Path where the PDF will be written
     */
    fun exportToPdf(htmlContent: String, outputFilePath: String) {
        java.io.FileOutputStream(outputFilePath).use { os ->
            val builder = PdfRendererBuilder()
            builder.useFastMode()
            builder.withHtmlContent(htmlContent, null)
            builder.toStream(os)
            builder.run()
        }
    }

    /**
     * Generate HTML content for the trip itinerary.
     *
     * @param route The calculated route with all legs
     * @param startLocation Name of the starting location
     * @param endLocation Name of the destination location
     * @return HTML string ready for PDF rendering
     */
    fun generateHtml(route: Route, startLocation: String, endLocation: String): String {
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("  <meta charset=\"UTF-8\" />")
            appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />")
            appendLine("  <title>PathPress Itinerary</title>")
            appendLine("  <style>")
            appendLine("    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }")
            appendLine("    h1 { color: #2c3e50; border-bottom: 2px solid #3498db; padding-bottom: 10px; }")
            appendLine("    .metadata { background: #f8f9fa; padding: 15px; border-radius: 8px; margin-bottom: 20px; }")
            appendLine("    .leg { margin-bottom: 25px; padding: 15px; border: 1px solid #e9ecef; border-radius: 8px; }")
            appendLine("    .leg h3 { color: #495057; margin-top: 0; }")
            appendLine("    .meta { color: #6c757d; font-size: 0.9em; }")
            appendLine("    a { color: #3498db; text-decoration: none; }")
            appendLine("    a:hover { text-decoration: underline; }")
            appendLine("    .maps-link { display: inline-block; margin-top: 10px; padding: 8px 16px;")
            appendLine("                  background: #3498db; color: white; border-radius: 4px; }")
            appendLine("    .maps-link:hover { background: #2980b9; text-decoration: none; }")
            appendLine("    .poi-list { margin-top: 10px; padding-left: 20px; }")
            appendLine("    .poi-item { margin-bottom: 5px; }")
            appendLine("  </style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("  <h1>PathPress Itinerary</h1>")

            // Trip metadata
            appendLine("  <div class=\"metadata\">")
            appendLine("    <h2>Trip Details</h2>")
            appendLine("    <p><strong>From:</strong> $startLocation</p>")
            appendLine("    <p><strong>To:</strong> $endLocation</p>")
            appendLine("    <p><strong>Total Distance:</strong> ${formatDistance(route.totalDistanceMeters)}</p>")
            appendLine("    <p><strong>Estimated Duration:</strong> ${formatDuration(route.totalDurationSeconds)}</p>")
            appendLine("    <p><strong>Daily Legs:</strong> ${route.legs.size}</p>")
            appendLine("  </div>")

            // Daily legs
            appendLine("  <h2>Itinerary</h2>")
            for (leg in route.legs) {
                appendLine("  <div class=\"leg\">")
                appendLine("    <h3>Day ${leg.dayNumber} of ${leg.totalDays}</h3>")
                appendLine("    <p class=\"meta\">${leg.startLat},${leg.startLng} → ${leg.endLat},${leg.endLng}</p>")

                val distance = leg.distanceMeters ?: route.totalDistanceMeters / route.legs.size
                val duration = leg.durationSeconds ?: route.totalDurationSeconds / route.legs.size

                appendLine("    <p class=\"meta\"><strong>Distance:</strong> ${formatDistance(distance)}")
                appendLine("    <br /><strong>Estimated Time:</strong> ${formatDuration(duration)}</p>")

                // Maps links - escape & for HTML attributes
                val directionsUrl = leg.toDirectionsUrl().replace("&", "&amp;")
                appendLine("    <a href=\"$directionsUrl\" class=\"maps-link\">View on Google Maps</a>")
                appendLine("    <div style=\"margin-top: 10px;\">")
                val mapUrl = leg.toMapUrl().replace("&", "&amp;")
                appendLine("      <small><a href=\"$mapUrl\">Map View</a></small>")
                appendLine("    </div>")

                // POIs (placeholder - would be populated from actual data)
                appendLine("    <h4>Nearby Points of Interest</h4>")
                appendLine("    <ul class=\"poi-list\">")
                appendLine("      <li class=\"poi-item\"><a href=\"#\">Cafe (sample)</a></li>")
                appendLine("      <li class=\"poi-item\"><a href=\"#\">Rest Area (sample)</a></li>")
                appendLine("    </ul>")

                appendLine("  </div>")
            }

            appendLine("</body>")
            appendLine("</html>")
        }
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
}
