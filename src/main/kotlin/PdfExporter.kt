package com.pathpress.core

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder

/**
 * Exports trip itineraries to PDF using openhtmltopdf (JVM-native).
 */
object PdfExporter {

    fun exportToPdf(htmlContent: String, outputFilePath: String) {
        java.io.FileOutputStream(outputFilePath).use { os ->
            val builder = PdfRendererBuilder()
            builder.useFastMode()
            builder.withHtmlContent(htmlContent, null)
            builder.toStream(os)
            builder.run()
        }
    }

    fun generateHtml(route: Route, startLocation: String, endLocation: String): String {
        val safeStart = escapeXml(startLocation)
        val safeEnd = escapeXml(endLocation)
        val safeNarrative = escapeXml(route.narrative)

        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("  <meta charset=\"UTF-8\" />")
            appendLine("  <title>PathPress Scenic Itinerary</title>")
            appendLine("  <style>")
            appendLine("    body { font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif; color: #2d3748; margin: 30px; line-height: 1.5; }")
            appendLine("    h1 { color: #1a202c; font-size: 26px; border-bottom: 3px solid #3182ce; padding-bottom: 8px; margin-bottom: 15px; }")
            appendLine("    .narrative { background-color: #ebf8ff; border-left: 4px solid #3182ce; padding: 12px 16px; margin-bottom: 20px; font-style: italic; color: #2b6cb0; border-radius: 4px; }")
            appendLine("    .metadata { background: #f7fafc; padding: 16px; border-radius: 8px; border: 1px solid #e2e8f0; margin-bottom: 25px; }")
            appendLine("    .metadata table { width: 100%; border-collapse: collapse; }")
            appendLine("    .metadata td { padding: 6px 0; font-size: 14px; }")
            appendLine("    .leg { margin-bottom: 30px; padding: 18px; border: 1px solid #cbd5e0; border-radius: 8px; background: #ffffff; }")
            appendLine("    .leg-title { font-size: 18px; font-weight: bold; color: #2b6cb0; margin-top: 0; margin-bottom: 6px; }")
            appendLine("    .leg-story { font-style: italic; color: #4a5568; margin-bottom: 12px; background: #f7fafc; padding: 8px 12px; border-radius: 6px; border-left: 3px solid #4299e1; }")
            appendLine("    .meta-badge { display: inline-block; background: #edf2f7; color: #2d3748; padding: 5px 12px; border-radius: 14px; font-size: 12px; font-weight: 600; margin-right: 8px; border: 1px solid #cbd5e0; }")
            appendLine("    a.maps-btn { display: inline-block; margin-top: 14px; padding: 8px 14px; background: #3182ce; color: #ffffff; text-decoration: none; border-radius: 6px; font-size: 13px; font-weight: bold; }")
            appendLine("    .poi-section { margin-top: 16px; background: #f7fafc; padding: 14px; border-radius: 6px; border: 1px solid #edf2f7; }")
            appendLine("    .poi-title { font-size: 14px; font-weight: bold; color: #2d3748; margin-bottom: 10px; border-bottom: 1px solid #e2e8f0; padding-bottom: 4px; }")
            appendLine("    .poi-item { margin-bottom: 10px; color: #4a5568; font-size: 13px; }")
            appendLine("    .poi-desc { margin-left: 14px; color: #718096; font-size: 12px; margin-top: 2px; }")
            appendLine("    .tag-badge { background: #e2e8f0; color: #4a5568; padding: 2px 6px; border-radius: 4px; font-size: 11px; margin-left: 6px; text-transform: capitalize; }")
            appendLine("    .dist-badge { background: #feebc8; color: #744210; padding: 2px 6px; border-radius: 4px; font-size: 11px; margin-left: 6px; }")
            appendLine("    .info-card { margin-top: 12px; background: #fffaf0; border: 1px solid #feebc8; padding: 10px 14px; border-radius: 6px; }")
            appendLine("    .info-card-title { font-size: 13px; font-weight: bold; color: #744210; margin-bottom: 4px; }")
            appendLine("    .info-card-item { font-size: 12px; color: #975a16; margin-bottom: 3px; }")
            appendLine("  </style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("  <h1>PathPress Road Trip Itinerary</h1>")

            if (safeNarrative.isNotBlank()) {
                appendLine("  <div class=\"narrative\">")
                appendLine("    \"$safeNarrative\"")
                appendLine("  </div>")
            }

            // Metadata card
            appendLine("  <div class=\"metadata\">")
            appendLine("    <table>")
            appendLine("      <tr><td><strong>Starting Location:</strong> $safeStart</td><td><strong>Total Distance:</strong> ${formatDistance(route.totalDistanceMeters)}</td></tr>")
            appendLine("      <tr><td><strong>Destination:</strong> $safeEnd</td><td><strong>Estimated Drive Time:</strong> ${formatDuration(route.totalDurationSeconds)}</td></tr>")
            appendLine("      <tr><td><strong>Total Trip Days:</strong> ${route.legs.size}</td><td><strong>Routing Profile:</strong> Scenic &amp; Corridor Weighted</td></tr>")
            appendLine("    </table>")
            appendLine("  </div>")

            // Daily legs
            appendLine("  <h2 style=\"color: #2d3748;\">Daily Schedule</h2>")
            for (leg in route.legs) {
                val safeTitle = escapeXml(leg.dayTitle ?: "Scenic Drive")
                val safeEndTown = leg.endTownName?.let { escapeXml(it) }

                appendLine("  <div class=\"leg\">")
                appendLine("    <div class=\"leg-title\">Day ${leg.dayNumber} of ${leg.totalDays}: $safeTitle ${if (safeEndTown != null) "(Overnight in $safeEndTown)" else ""}</div>")

                if (!leg.legStory.isNullOrBlank()) {
                    appendLine("    <div class=\"leg-story\">\"${escapeXml(leg.legStory)}\"</div>")
                }

                val distance = leg.distanceMeters ?: (route.totalDistanceMeters / route.legs.size)
                val duration = leg.durationSeconds ?: (route.totalDurationSeconds / route.legs.size)

                appendLine("    <div style=\"margin-bottom: 10px;\">")
                appendLine("      <span class=\"meta-badge\">Distance: ${formatDistance(distance)}</span>")
                appendLine("      <span class=\"meta-badge\">Est. Drive Time: ${formatDuration(duration)}</span>")
                appendLine("    </div>")

                val directionsUrl = escapeXml(leg.toDirectionsUrl())
                appendLine("    <a href=\"$directionsUrl\" class=\"maps-btn\">Open Leg Directions in Google Maps</a>")

                if (leg.pois.isNotEmpty()) {
                    appendLine("    <div class=\"poi-section\">")
                    appendLine("      <div class=\"poi-title\">Real Corridor POIs &amp; Curated Highlights</div>")
                    for (poi in leg.pois) {
                        val poiSearchUrl = escapeXml(MapUrlFormatter.formatPoiUrl(poi))
                        val poiName = escapeXml(poi.name ?: "Point of Interest")
                        val poiType = escapeXml(poi.type)
                        val distOffRoute = formatOffRouteDistance(poi.distanceFromRouteMeters)

                        appendLine("      <div class=\"poi-item\">")
                        appendLine("        • <a href=\"$poiSearchUrl\" style=\"color: #3182ce; font-weight: bold;\">$poiName</a>")
                        appendLine("        <span class=\"tag-badge\">$poiType</span>")
                        if (distOffRoute != null) {
                            appendLine("        <span class=\"dist-badge\">$distOffRoute</span>")
                        }
                        if (!poi.description.isNullOrBlank()) {
                            appendLine("        <div class=\"poi-desc\">${escapeXml(poi.description)}</div>")
                        }
                        appendLine("      </div>")
                    }
                    appendLine("    </div>")
                }

                if (leg.foodRecommendations.isNotEmpty()) {
                    appendLine("    <div class=\"info-card\">")
                    appendLine("      <div class=\"info-card-title\">☕ Local Coffee &amp; Food Recommendations</div>")
                    for (foodRec in leg.foodRecommendations) {
                        appendLine("      <div class=\"info-card-item\">• ${escapeXml(foodRec)}</div>")
                    }
                    appendLine("    </div>")
                }

                if (leg.insiderTips.isNotEmpty()) {
                    appendLine("    <div class=\"info-card\" style=\"background: #f0fff4; border-color: #c6f6d5;\">")
                    appendLine("      <div class=\"info-card-title\" style=\"color: #22543d;\">💡 Insider Driving &amp; Scenic Tips</div>")
                    for (tip in leg.insiderTips) {
                        appendLine("      <div class=\"info-card-item\" style=\"color: #276749;\">• ${escapeXml(tip)}</div>")
                    }
                    appendLine("    </div>")
                }

                appendLine("  </div>")
            }

            appendLine("</body>")
            appendLine("</html>")
        }
    }

    private fun sanitizeText(text: String): String {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace("Đ", "D").replace("đ", "d")
            .replace("Æ", "Ae").replace("æ", "ae")
            .replace("Ø", "O").replace("ø", "o")
            .replace("Å", "A").replace("å", "a")
            .replace(Regex("[^\\x20-\\x7E]"), "")
    }

    private fun escapeXml(text: String): String {
        val clean = sanitizeText(text)
        return clean.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun formatDistance(meters: Double): String {
        return if (meters >= 1000) {
            "${String.format("%.1f", meters / 1000)} km"
        } else {
            "${String.format("%.0f", meters)} m"
        }
    }

    fun formatOffRouteDistance(meters: Double?): String? {
        if (meters == null) return null
        return if (meters < 1000.0) {
            "${kotlin.math.round(meters).toInt()} m off route"
        } else {
            "${String.format("%.1f", meters / 1000.0)} km off route"
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
