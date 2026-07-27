package com.pathpress.export

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.openhtmltopdf.svgsupport.BatikSVGDrawer
import com.pathpress.llm.*
import com.pathpress.model.*
import com.pathpress.poi.*
import com.pathpress.routing.*

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
                "    body { font-family: 'Inter', Helvetica, Arial, sans-serif; color: #1e293b; margin: 0; padding: 0; line-height: 1.6; font-size: 12px; }"
            )
            appendLine(
                "    h1, h2, h3, .editorial-heading { font-family: 'Merriweather', Georgia, serif; }"
            )

            // Cover Page container
            appendLine("    .cover-container { margin-bottom: 20px; }")

            // Hero Header Banner
            appendLine(
                "    .hero-banner { background-color: #0f172a; color: #f8fafc; padding: 40px 32px; border-radius: 12px; margin-bottom: 24px; }"
            )
            appendLine(
                "    .hero-title { font-size: 32px; font-weight: bold; color: #f8fafc; margin: 0 0 12px 0; letter-spacing: -0.5px; line-height: 1.2; }"
            )
            appendLine(
                "    .hero-subtitle { font-size: 14px; color: #94a3b8; margin-bottom: 18px; font-weight: 500; line-height: 1.4; letter-spacing: 0.5px; text-transform: uppercase; }"
            )
            appendLine(
                "    .hero-narrative { background-color: #1e293b; border-left: 4px solid #38bdf8; padding: 16px 20px; font-style: italic; color: #f1f5f9; font-size: 13px; border-radius: 6px; line-height: 1.5; }"
            )

            // Overview Metadata Card
            appendLine(
                "    .metadata-card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 18px 22px; margin-bottom: 30px; }"
            )
            appendLine("    .metadata-grid { width: 100%; border-collapse: collapse; }")
            appendLine("    .metadata-grid td { padding: 6px 8px; vertical-align: middle; }")
            appendLine(
                "    .meta-label { color: #64748b; font-weight: 600; font-size: 11px; text-transform: uppercase; letter-spacing: 0.8px; }"
            )
            appendLine(
                "    .meta-val { color: #0f172a; font-weight: bold; font-size: 14px; margin-top: 4px; display: inline-block; }"
            )

            // Section titles
            appendLine(
                "    .section-title { font-size: 18px; color: #0f172a; border-bottom: 2px solid #0284c7; padding-bottom: 8px; margin-top: 24px; margin-bottom: 20px; font-weight: bold; line-height: 1.4; }"
            )

            // Leg Container
            appendLine(
                "    .leg { margin-bottom: 32px; padding: 24px 28px; border: 1px solid #e2e8f0; border-radius: 12px; background: #ffffff; page-break-inside: auto; }"
            )
            appendLine("    .leg + .leg { page-break-before: always; }")

            // Leg Top Header Grid
            appendLine(
                "    .leg-top-grid { width: 100%; border-collapse: collapse; margin-bottom: 18px; page-break-inside: avoid; }"
            )
            appendLine(
                "    .day-badge { color: #0284c7; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 6px; display: block; }"
            )
            appendLine(
                "    .leg-title { font-size: 20px; font-weight: bold; color: #0f172a; margin: 0 0 10px 0; line-height: 1.3; }"
            )

            // Header Meta Pills
            appendLine("    .meta-pills { margin-top: 4px; }")
            appendLine(
                "    .meta-badge { display: inline-block; background: #f1f5f9; color: #334155; padding: 5px 12px; border-radius: 14px; font-size: 11px; font-weight: 600; margin-right: 6px; border: 1px solid #e2e8f0; line-height: 1.3; }"
            )
            appendLine(
                "    .meta-badge-overnight { display: inline-block; background: #f0fdf4; color: #166534; padding: 5px 12px; border-radius: 14px; font-size: 11px; font-weight: 600; margin-right: 6px; border: 1px solid #bbf7d0; line-height: 1.3; }"
            )

            // Compact Top-Right Nav Action Box
            appendLine(
                "    .nav-action-box { background: #f8fafc; border: 1px solid #cbd5e1; border-radius: 10px; padding: 10px 12px; text-align: center; }"
            )
            appendLine(
                "    .qr-img { border: 1px solid #e2e8f0; border-radius: 6px; background: #ffffff; padding: 4px; display: block; margin: 0 auto 8px auto; }"
            )
            appendLine(
                "    a.maps-btn-sm { display: block; padding: 8px 12px; background: #0284c7; color: #ffffff; text-decoration: none; border-radius: 6px; font-size: 11px; font-weight: bold; line-height: 1.2; white-space: nowrap; letter-spacing: 0.3px; }"
            )

            // Leg Story
            appendLine(
                "    .leg-story { font-style: italic; color: #475569; margin-bottom: 20px; background: #f8fafc; padding: 16px 20px; border-radius: 10px; border-left: 4px solid #38bdf8; font-size: 13px; line-height: 1.6; }"
            )

            // POIs Section
            appendLine(
                "    .poi-section { margin-top: 20px; background: #ffffff; padding: 0; border-radius: 0; border: none; }"
            )
            appendLine(
                "    .poi-title { font-size: 15px; font-weight: bold; color: #0f172a; margin-bottom: 16px; border-bottom: 2px solid #e2e8f0; padding-bottom: 8px; line-height: 1.4; }"
            )
            appendLine(
                "    .poi-card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 14px 16px; margin-bottom: 10px; page-break-inside: avoid; }"
            )
            appendLine(
                "    .poi-card-header { font-size: 13px; font-weight: bold; color: #0284c7; margin-bottom: 6px; }"
            )
            appendLine(
                "    .poi-card-desc { color: #475569; font-size: 12px; margin-top: 6px; line-height: 1.5; }"
            )
            appendLine(
                "    .tag-badge { background: #e2e8f0; color: #475569; padding: 3px 8px; border-radius: 6px; font-size: 10px; margin-left: 8px; text-transform: capitalize; font-weight: 500; }"
            )
            appendLine(
                "    .dist-badge { background: #ffedd5; color: #9a3412; padding: 3px 8px; border-radius: 6px; font-size: 10px; margin-left: 8px; font-weight: 500; }"
            )

            // Cards (Food & Tips)
            appendLine(
                "    .info-card { margin-top: 18px; background: #fffbeb; border: 1px solid #fde68a; padding: 16px 18px; border-radius: 10px; page-break-inside: avoid; }"
            )
            appendLine(
                "    .info-card-title { font-size: 14px; font-weight: bold; color: #92400e; margin-bottom: 10px; line-height: 1.4; }"
            )
            appendLine(
                "    .info-card-item { font-size: 12px; color: #92400e; margin-bottom: 6px; line-height: 1.5; }"
            )
            appendLine("  </style>")
            appendLine("</head>")
            appendLine("<body>")

            // Hero Header Banner (Cover Page Header)
            appendLine("  <div class=\"cover-container\">")
            appendLine("  <div class=\"hero-banner\">")
            appendLine(
                "    <div class=\"hero-title editorial-heading\">PathPress Scenic Road Trip</div>"
            )
            appendLine(
                "    <div class=\"hero-subtitle\">${LucideIcon.mapPin("#94a3b8", 15)} $safeStart ${LucideIcon.arrowRight("#94a3b8", 14)} ${LucideIcon.mapPin("#94a3b8", 15)} $safeEnd</div>"
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
                "        <td><span class=\"meta-label\">Total Distance</span><br/><span class=\"meta-val\">${LucideIcon.route("#0284c7", 14)} ${formatDistance(route.totalDistanceMeters)}</span></td>"
            )
            appendLine(
                "        <td><span class=\"meta-label\">Est. Drive Time</span><br/><span class=\"meta-val\">${LucideIcon.clock("#0284c7", 14)} ${formatDuration(route.totalDurationSeconds)}</span></td>"
            )
            appendLine(
                "        <td><span class=\"meta-label\">Trip Duration</span><br/><span class=\"meta-val\">${LucideIcon.calendar("#0284c7", 14)} ${route.legs.size} ${if (route.legs.size == 1) "Day" else "Days"}</span></td>"
            )
            appendLine(
                "        <td><span class=\"meta-label\">Routing Vibe</span><br/><span class=\"meta-val\">${LucideIcon.compass("#0284c7", 14)} Scenic Corridor</span></td>"
            )
            appendLine("      </tr>")
            appendLine("    </table>")
            appendLine("  </div>")
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
                    "            <span class=\"meta-badge\">${LucideIcon.route("#334155", 12)} ${formatDistance(distance)}</span>"
                )
                appendLine(
                    "            <span class=\"meta-badge\">${LucideIcon.clock("#334155", 12)} ${formatDuration(duration)}</span>"
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
                        "      <div class=\"poi-title\">${LucideIcon.camera("#0284c7", 16)} <span style=\"vertical-align: middle;\">Corridor POIs &amp; Scenic Highlights</span></div>"
                    )
                    for (poi in leg.pois) {
                        val poiSearchUrl = escapeXml(MapUrlFormatter.formatPoiUrl(poi))
                        val poiName = escapeXml(poi.name ?: "Point of Interest")
                        val poiType =
                            escapeXml(
                                poi.type.split("_").joinToString(" ") { word ->
                                    word.replaceFirstChar { it.uppercase() }
                                }
                            )
                        val distOffRoute = formatOffRouteDistance(poi.distanceFromRouteMeters)

                        appendLine("      <div class=\"poi-card\">")
                        appendLine("        <div class=\"poi-card-header\">")
                        appendLine(
                            "          <a href=\"$poiSearchUrl\" style=\"color: #0284c7; text-decoration: none;\">$poiName</a>"
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
                        "      <div class=\"info-card-title\">${LucideIcon.coffee("#92400e", 16)} <span style=\"vertical-align: middle;\">Local Coffee &amp; Food Recommendations</span></div>"
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
