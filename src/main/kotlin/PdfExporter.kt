package com.pathpress.core

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.openhtmltopdf.svgsupport.BatikSVGDrawer

/** Exports trip itineraries to magazine-grade PDF using openhtmltopdf (JVM-native). */
object PdfExporter {

    fun exportToPdf(htmlContent: String, outputFilePath: String) {
        java.io.FileOutputStream(outputFilePath).use { os ->
            val builder = PdfRendererBuilder()
            builder.useFastMode()
            builder.useSVGDrawer(BatikSVGDrawer())

            // Register bundled custom fonts for editorial typography
            val interStream =
                PdfExporter::class.java.getResourceAsStream("/fonts/Inter-Regular.ttf")
            if (interStream != null) {
                val interBytes = interStream.readBytes()
                builder.useFont({ interBytes.inputStream() }, "Inter")
            }

            val merriweatherStream =
                PdfExporter::class.java.getResourceAsStream("/fonts/Merriweather-Bold.ttf")
            if (merriweatherStream != null) {
                val merriweatherBytes = merriweatherStream.readBytes()
                builder.useFont({ merriweatherBytes.inputStream() }, "Merriweather")
            }

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
            appendLine("    @page {")
            appendLine("      size: A4 portrait;")
            appendLine("      margin: 20mm 14mm 18mm 14mm;")
            appendLine("      @top-left {")
            appendLine("        content: \"PATHPRESS ROAD TRIP GUIDE\";")
            appendLine("        font-family: 'Inter', Helvetica, sans-serif;")
            appendLine("        font-size: 8px;")
            appendLine("        font-weight: 700;")
            appendLine("        color: #a0aec0;")
            appendLine("        letter-spacing: 0.8px;")
            appendLine("      }")
            appendLine("      @top-right {")
            appendLine("        content: \"$safeStart to $safeEnd\";")
            appendLine("        font-family: 'Inter', Helvetica, sans-serif;")
            appendLine("        font-size: 8px;")
            appendLine("        color: #a0aec0;")
            appendLine("      }")
            appendLine("      @bottom-right {")
            appendLine("        content: \"Page \" counter(page) \" of \" counter(pages);")
            appendLine("        font-family: 'Inter', Helvetica, sans-serif;")
            appendLine("        font-size: 9px;")
            appendLine("        color: #718096;")
            appendLine("      }")
            appendLine("      @bottom-left {")
            appendLine("        content: \"PathPress Scenic Itinerary\";")
            appendLine("        font-family: 'Inter', Helvetica, sans-serif;")
            appendLine("        font-size: 9px;")
            appendLine("        color: #718096;")
            appendLine("      }")
            appendLine("    }")
            appendLine("    @page:first {")
            appendLine("      @top-left { content: none; }")
            appendLine("      @top-right { content: none; }")
            appendLine("    }")
            appendLine(
                "    body { font-family: 'Inter', Helvetica, Arial, sans-serif; color: #2d3748; margin: 0; padding: 0; line-height: 1.5; font-size: 13px; }"
            )
            appendLine(
                "    h1, h2, h3, .editorial-heading { font-family: 'Merriweather', Georgia, serif; }"
            )

            // Hero Header Banner
            appendLine(
                "    .hero-banner { background-color: #1e293b; color: #ffffff; padding: 24px 28px; border-radius: 10px; margin-bottom: 20px; }"
            )
            appendLine(
                "    .hero-title { font-size: 24px; font-weight: bold; color: #ffffff; margin: 0 0 8px 0; letter-spacing: -0.3px; line-height: 1.3; }"
            )
            appendLine(
                "    .hero-subtitle { font-size: 13px; color: #cbd5e0; margin-bottom: 12px; font-weight: 500; line-height: 1.4; }"
            )
            appendLine(
                "    .hero-narrative { background-color: #334155; border-left: 3px solid #60a5fa; padding: 10px 14px; font-style: italic; color: #f1f5f9; font-size: 12px; border-radius: 4px; line-height: 1.4; }"
            )

            // Overview Metadata Card
            appendLine(
                "    .metadata-card { background: #f7fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 14px 18px; margin-bottom: 22px; }"
            )
            appendLine("    .metadata-grid { width: 100%; border-collapse: collapse; }")
            appendLine(
                "    .metadata-grid td { padding: 4px 6px; font-size: 12px; vertical-align: middle; }"
            )
            appendLine(
                "    .meta-label { color: #718096; font-weight: 600; font-size: 10px; text-transform: uppercase; letter-spacing: 0.5px; }"
            )
            appendLine(
                "    .meta-val { color: #1a202c; font-weight: 600; font-size: 13px; margin-top: 2px; display: inline-block; }"
            )

            // Section titles
            appendLine(
                "    .section-title { font-size: 17px; color: #1a202c; border-bottom: 2px solid #3182ce; padding-bottom: 6px; margin-top: 20px; margin-bottom: 16px; font-weight: bold; line-height: 1.4; }"
            )

            // Leg Container
            appendLine(
                "    .leg { margin-bottom: 22px; padding: 20px 22px; border: 1px solid #cbd5e0; border-radius: 10px; background: #ffffff; page-break-inside: auto; }"
            )

            // Leg Top Header Grid
            appendLine(
                "    .leg-top-grid { width: 100%; border-collapse: collapse; margin-bottom: 14px; }"
            )
            appendLine(
                "    .day-badge { color: #2b6cb0; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 4px; display: block; }"
            )
            appendLine(
                "    .leg-title { font-size: 18px; font-weight: bold; color: #1a202c; margin: 0 0 8px 0; line-height: 1.3; }"
            )

            // Header Meta Pills
            appendLine("    .meta-pills { margin-top: 4px; }")
            appendLine(
                "    .meta-badge { display: inline-block; background: #edf2f7; color: #2d3748; padding: 4px 10px; border-radius: 12px; font-size: 11px; font-weight: 600; margin-right: 6px; border: 1px solid #cbd5e0; line-height: 1.3; }"
            )
            appendLine(
                "    .meta-badge-overnight { display: inline-block; background: #f0fff4; color: #22543d; padding: 4px 10px; border-radius: 12px; font-size: 11px; font-weight: 600; margin-right: 6px; border: 1px solid #c6f6d5; line-height: 1.3; }"
            )

            // Compact Top-Right Nav Action Box
            appendLine(
                "    .nav-action-box { background: #f7fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 8px 10px; text-align: center; }"
            )
            appendLine(
                "    .qr-img { border: 1px solid #cbd5e0; border-radius: 4px; background: #ffffff; padding: 2px; display: block; margin: 0 auto 6px auto; }"
            )
            appendLine(
                "    a.maps-btn-sm { display: block; padding: 6px 10px; background: #3182ce; color: #ffffff; text-decoration: none; border-radius: 5px; font-size: 11px; font-weight: bold; line-height: 1.2; white-space: nowrap; }"
            )

            // Leg Story
            appendLine(
                "    .leg-story { font-style: italic; color: #4a5568; margin-bottom: 16px; background: #f7fafc; padding: 12px 16px; border-radius: 8px; border-left: 4px solid #4299e1; font-size: 12px; line-height: 1.5; }"
            )

            // POIs Section
            appendLine(
                "    .poi-section { margin-top: 16px; background: #f8fafc; padding: 14px 16px; border-radius: 8px; border: 1px solid #e2e8f0; }"
            )
            appendLine(
                "    .poi-title { font-size: 14px; font-weight: bold; color: #2d3748; margin-bottom: 12px; border-bottom: 1px solid #e2e8f0; padding-bottom: 6px; line-height: 1.4; }"
            )
            appendLine(
                "    .poi-card { background: #ffffff; border: 1px solid #edf2f7; border-radius: 6px; padding: 10px 12px; margin-bottom: 8px; page-break-inside: avoid; }"
            )
            appendLine(
                "    .poi-card-header { font-size: 12px; font-weight: bold; color: #2b6cb0; margin-bottom: 4px; }"
            )
            appendLine(
                "    .poi-card-desc { color: #718096; font-size: 11px; margin-top: 4px; line-height: 1.4; }"
            )
            appendLine(
                "    .tag-badge { background: #e2e8f0; color: #4a5568; padding: 2px 6px; border-radius: 4px; font-size: 10px; margin-left: 6px; text-transform: capitalize; font-weight: normal; }"
            )
            appendLine(
                "    .dist-badge { background: #feebc8; color: #744210; padding: 2px 6px; border-radius: 4px; font-size: 10px; margin-left: 6px; font-weight: normal; }"
            )

            // Cards (Food & Tips)
            appendLine(
                "    .info-card { margin-top: 14px; background: #fffaf0; border: 1px solid #feebc8; padding: 12px 14px; border-radius: 8px; page-break-inside: avoid; }"
            )
            appendLine(
                "    .info-card-title { font-size: 13px; font-weight: bold; color: #744210; margin-bottom: 8px; line-height: 1.4; }"
            )
            appendLine(
                "    .info-card-item { font-size: 11px; color: #975a16; margin-bottom: 4px; line-height: 1.4; }"
            )
            appendLine("  </style>")
            appendLine("</head>")
            appendLine("<body>")

            // Hero Header Banner (Cover Page Header)
            appendLine("  <div class=\"hero-banner\">")
            appendLine(
                "    <div class=\"hero-title editorial-heading\">PathPress Scenic Road Trip</div>"
            )
            appendLine(
                "    <div class=\"hero-subtitle\">${LucideIcon.mapPin("#cbd5e0", 15)} $safeStart ${LucideIcon.arrowRight("#cbd5e0", 14)} ${LucideIcon.mapPin("#cbd5e0", 15)} $safeEnd</div>"
            )
            if (safeNarrative.isNotBlank()) {
                appendLine("    <div class=\"hero-narrative\">\"$safeNarrative\"</div>")
            }
            appendLine("  </div>")

            // Metadata Card
            appendLine("  <div class=\"metadata-card\">")
            appendLine("    <table class=\"metadata-grid\">")
            appendLine("      <tr>")
            appendLine(
                "        <td><span class=\"meta-label\">Total Distance</span><br/><span class=\"meta-val\">${LucideIcon.route("#3182ce", 14)} ${formatDistance(route.totalDistanceMeters)}</span></td>"
            )
            appendLine(
                "        <td><span class=\"meta-label\">Est. Drive Time</span><br/><span class=\"meta-val\">${LucideIcon.clock("#3182ce", 14)} ${formatDuration(route.totalDurationSeconds)}</span></td>"
            )
            appendLine(
                "        <td><span class=\"meta-label\">Trip Duration</span><br/><span class=\"meta-val\">${LucideIcon.calendar("#3182ce", 14)} ${route.legs.size} ${if (route.legs.size == 1) "Day" else "Days"}</span></td>"
            )
            appendLine(
                "        <td><span class=\"meta-label\">Routing Vibe</span><br/><span class=\"meta-val\">${LucideIcon.compass("#3182ce", 14)} Scenic Corridor</span></td>"
            )
            appendLine("      </tr>")
            appendLine("    </table>")
            appendLine("  </div>")

            // Daily Schedule
            appendLine(
                "  <div class=\"section-title editorial-heading\">Daily Schedule &amp; Itinerary</div>"
            )
            for (leg in route.legs) {
                val rawTitle = leg.dayTitle ?: "Scenic Drive"
                val cleanTitle =
                    escapeXml(
                        rawTitle.replace(Regex("^Day\\s+\\d+:\\s*", RegexOption.IGNORE_CASE), "")
                    )
                val safeEndTown = leg.endTownName?.let { escapeXml(it) }

                val distance = leg.distanceMeters ?: (route.totalDistanceMeters / route.legs.size)
                val duration = leg.durationSeconds ?: (route.totalDurationSeconds / route.legs.size)
                val directionsUrl = leg.toDirectionsUrl()
                val safeDirectionsUrl = escapeXml(directionsUrl)
                val qrDataUri = QrCodeGenerator.generateQrCodeDataUri(directionsUrl, 160, 160)

                appendLine("  <div class=\"leg\">")

                // Top Header Grid with integrated right-side QR box
                appendLine("    <table class=\"leg-top-grid\">")
                appendLine("      <tr>")
                appendLine("        <td style=\"vertical-align: top;\">")
                appendLine(
                    "          <div class=\"day-badge\">Day ${leg.dayNumber} of ${leg.totalDays}</div>"
                )
                appendLine("          <div class=\"leg-title editorial-heading\">$cleanTitle</div>")
                appendLine("          <div class=\"meta-pills\">")
                appendLine(
                    "            <span class=\"meta-badge\">${LucideIcon.route("#2b6cb0", 12)} ${formatDistance(distance)}</span>"
                )
                appendLine(
                    "            <span class=\"meta-badge\">${LucideIcon.clock("#2b6cb0", 12)} ${formatDuration(duration)}</span>"
                )
                if (safeEndTown != null) {
                    appendLine(
                        "            <span class=\"meta-badge-overnight\">${LucideIcon.mapPin("#276749", 12)} Overnight in $safeEndTown</span>"
                    )
                }
                appendLine("          </div>")
                appendLine("        </td>")
                if (qrDataUri.isNotBlank()) {
                    appendLine(
                        "        <td style=\"width: 125px; vertical-align: top; text-align: right;\">"
                    )
                    appendLine("          <div class=\"nav-action-box\">")
                    appendLine(
                        "            <img src=\"$qrDataUri\" width=\"70\" height=\"70\" alt=\"QR Code\" class=\"qr-img\" />"
                    )
                    appendLine(
                        "            <a href=\"$safeDirectionsUrl\" class=\"maps-btn-sm\">${LucideIcon.navigation("#ffffff", 11)} Directions</a>"
                    )
                    appendLine("          </div>")
                    appendLine("        </td>")
                }
                appendLine("      </tr>")
                appendLine("    </table>")

                if (!leg.legStory.isNullOrBlank()) {
                    appendLine("    <div class=\"leg-story\">\"${escapeXml(leg.legStory)}\"</div>")
                }

                if (leg.pois.isNotEmpty()) {
                    appendLine("    <div class=\"poi-section\">")
                    appendLine(
                        "      <div class=\"poi-title\">${LucideIcon.camera("#2b6cb0", 16)} <span style=\"vertical-align: middle;\">Corridor POIs &amp; Scenic Highlights</span></div>"
                    )
                    for (poi in leg.pois) {
                        val poiSearchUrl = escapeXml(MapUrlFormatter.formatPoiUrl(poi))
                        val poiName = escapeXml(poi.name ?: "Point of Interest")
                        val poiType = escapeXml(poi.type)
                        val distOffRoute = formatOffRouteDistance(poi.distanceFromRouteMeters)

                        appendLine("      <div class=\"poi-card\">")
                        appendLine("        <div class=\"poi-card-header\">")
                        appendLine(
                            "          <a href=\"$poiSearchUrl\" style=\"color: #2b6cb0; text-decoration: none;\">$poiName</a>"
                        )
                        appendLine("          <span class=\"tag-badge\">$poiType</span>")
                        if (distOffRoute != null) {
                            appendLine("          <span class=\"dist-badge\">$distOffRoute</span>")
                        }
                        appendLine("        </div>")
                        if (!poi.description.isNullOrBlank()) {
                            appendLine(
                                "        <div class=\"poi-card-desc\">${escapeXml(poi.description)}</div>"
                            )
                        }
                        appendLine("      </div>")
                    }
                    appendLine("    </div>")
                }

                if (leg.foodRecommendations.isNotEmpty()) {
                    appendLine("    <div class=\"info-card\">")
                    appendLine(
                        "      <div class=\"info-card-title\">${LucideIcon.coffee("#744210", 16)} <span style=\"vertical-align: middle;\">Local Coffee &amp; Food Recommendations</span></div>"
                    )
                    for (foodRec in leg.foodRecommendations) {
                        appendLine(
                            "      <div class=\"info-card-item\">&#8226; ${escapeXml(foodRec)}</div>"
                        )
                    }
                    appendLine("    </div>")
                }

                if (leg.insiderTips.isNotEmpty()) {
                    appendLine(
                        "    <div class=\"info-card\" style=\"background: #f0fff4; border-color: #c6f6d5;\">"
                    )
                    appendLine(
                        "      <div class=\"info-card-title\" style=\"color: #22543d;\">${LucideIcon.lightbulb("#22543d", 16)} <span style=\"vertical-align: middle;\">Insider Driving &amp; Scenic Tips</span></div>"
                    )
                    for (tip in leg.insiderTips) {
                        appendLine(
                            "      <div class=\"info-card-item\" style=\"color: #276749;\">&#8226; ${escapeXml(tip)}</div>"
                        )
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
        return normalized
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace("Đ", "D")
            .replace("đ", "d")
            .replace("Æ", "Ae")
            .replace("æ", "ae")
            .replace("Ø", "O")
            .replace("ø", "o")
            .replace("Å", "A")
            .replace("å", "a")
            .replace(Regex("[^\\x20-\\x7E]"), "")
    }

    internal fun escapeXml(text: String): String {
        val clean = sanitizeText(text)
        return clean
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    internal fun formatDistance(meters: Double): String {
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

    internal fun formatDuration(seconds: Double): String {
        val hours = seconds.toInt() / 3600
        val minutes = (seconds.toInt() % 3600) / 60
        return if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
        }
    }
}
