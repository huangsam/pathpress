package com.pathpress.export

import com.pathpress.export.PdfExporter.formatDistance
import com.pathpress.export.PdfExporter.formatDuration
import com.pathpress.export.PdfExporter.formatOffRouteDistance
import com.pathpress.export.PdfExporter.sanitizeText
import com.pathpress.model.*
import com.pathpress.poi.*
import com.pathpress.routing.*
import kotlinx.html.*

internal fun FlowContent.coverPage(
    route: Route,
    startLocation: String,
    endLocation: String,
    unit: DistanceUnit = DistanceUnit.METRIC,
) {
    div("cover-container") {
        heroBanner(route, startLocation, endLocation)
        metadataCard(route, unit)
    }
}

internal fun FlowContent.heroBanner(route: Route, startLocation: String, endLocation: String) {
    div("hero-banner") {
        div("hero-title editorial-heading") { +"PathPress Scenic Road Trip" }
        div("hero-subtitle") {
            unsafe {
                raw(
                    "${LucideIcon.mapPin("#94a3b8", 15)} $startLocation ${LucideIcon.arrowRight("#94a3b8", 14)} ${LucideIcon.mapPin("#94a3b8", 15)} $endLocation"
                )
            }
        }
        div("hero-narrative") {
            +"\"${sanitizeText(route.getNarrativeOrDefault(startLocation, endLocation))}\""
        }
    }
}

internal fun FlowContent.metadataCard(route: Route, unit: DistanceUnit = DistanceUnit.METRIC) {
    div("metadata-card") {
        table("metadata-grid") {
            tr {
                td {
                    span("meta-label") { +"Total Distance" }
                    br()
                    span("meta-val") {
                        unsafe { raw(LucideIcon.route("#0284c7", 14)) }
                        +" ${formatDistance(route.totalDistanceMeters, unit)}"
                    }
                }
                td {
                    span("meta-label") { +"Est. Drive Time" }
                    br()
                    span("meta-val") {
                        unsafe { raw(LucideIcon.clock("#0284c7", 14)) }
                        +" ${formatDuration(route.totalDurationSeconds)}"
                    }
                }
                td {
                    span("meta-label") { +"Trip Duration" }
                    br()
                    span("meta-val") {
                        unsafe { raw(LucideIcon.calendar("#0284c7", 14)) }
                        +" ${route.legs.size} ${if (route.legs.size == 1) "Day" else "Days"}"
                    }
                }
                td {
                    span("meta-label") { +"Routing Vibe" }
                    br()
                    span("meta-val") {
                        unsafe { raw(LucideIcon.compass("#0284c7", 14)) }
                        +" Scenic Corridor"
                    }
                }
            }
        }
    }
}

internal fun FlowContent.dailySchedule(route: Route, unit: DistanceUnit = DistanceUnit.METRIC) {
    div("section-title editorial-heading") { +"Daily Schedule & Itinerary" }
    for (leg in route.legs) {
        legCard(leg, route, unit)
    }
}

internal fun FlowContent.legCard(
    leg: RouteLeg,
    route: Route,
    unit: DistanceUnit = DistanceUnit.METRIC,
) {
    val rawTitle = leg.dayTitle ?: "Scenic Drive"
    val cleanTitle =
        rawTitle.replace(Regex("^Day\\s+\\d+[:\\s-]*", RegexOption.IGNORE_CASE), "").ifBlank {
            "Scenic Drive"
        }
    val distance = leg.distanceMeters ?: (route.totalDistanceMeters / route.legs.size)
    val duration = leg.durationSeconds ?: (route.totalDurationSeconds / route.legs.size)
    val directionsUrl = leg.toDirectionsUrl()

    val legClasses = if (leg.dayNumber > 1) "leg leg-page-break" else "leg"

    div(legClasses) {
        div("leg-header") {
            div("day-badge") { +"Day ${leg.dayNumber} of ${leg.totalDays}" }
            div("leg-title editorial-heading") { +sanitizeText(cleanTitle) }
            div("meta-pills") {
                span("meta-badge") {
                    unsafe { raw(LucideIcon.route("#334155", 12)) }
                    +" ${formatDistance(distance, unit)}"
                }
                span("meta-badge") {
                    unsafe { raw(LucideIcon.clock("#334155", 12)) }
                    +" ${formatDuration(duration)}"
                }
                if (leg.endTownName != null) {
                    span("meta-badge-overnight") {
                        unsafe { raw(LucideIcon.mapPin("#276749", 12)) }
                        +" Overnight in ${sanitizeText(leg.endTownName)}"
                    }
                }
                a(href = directionsUrl, classes = "meta-badge-nav") {
                    unsafe { raw(LucideIcon.navigation("#0284c7", 12)) }
                    +" Directions"
                }
            }
        }

        if (!leg.legStory.isNullOrBlank()) {
            div("leg-story") { +"\"${sanitizeText(leg.legStory)}\"" }
        }

        div("leg-map-container") {
            style = "margin: 12px 0; text-align: center;"
            unsafe { raw(renderLegSvgMap(leg)) }
        }

        if (leg.pois.isNotEmpty()) {
            poiSection(leg.pois, unit)
        }
    }
}

internal fun renderLegSvgMap(leg: RouteLeg, width: Int = 620, height: Int = 220): String {
    val sortedPois = leg.pois

    val points = mutableListOf<LocationCoords>()
    points.add(LocationCoords(leg.startLat, leg.startLng))
    sortedPois.forEach { points.add(LocationCoords(it.lat, it.lng)) }
    points.add(LocationCoords(leg.endLat, leg.endLng))

    val minLat = points.minOf { it.lat }
    val maxLat = points.maxOf { it.lat }
    val minLng = points.minOf { it.lng }
    val maxLng = points.maxOf { it.lng }

    val latDiff = (maxLat - minLat).coerceAtLeast(0.005)
    val lngDiff = (maxLng - minLng).coerceAtLeast(0.005)

    val padX = 85.0
    val padY = 55.0

    fun toSvgX(lng: Double): Double = padX + ((lng - minLng) / lngDiff) * (width - 2 * padX)
    fun toSvgY(lat: Double): Double =
        height - padY - ((lat - minLat) / latDiff) * (height - 2 * padY)

    data class SvgPoint(
        val origX: Double,
        val origY: Double,
        var renderX: Double,
        var renderY: Double,
    )

    val svgPois = sortedPois.map { poi ->
        val px = toSvgX(poi.lng)
        val py = toSvgY(poi.lat)
        SvgPoint(px, py, px, py)
    }

    // Pass 1: Ensure minimum horizontal separation between consecutive POIs
    val minSep = 18.0
    for (i in 1 until svgPois.size) {
        val prev = svgPois[i - 1]
        val curr = svgPois[i]
        if (curr.renderX - prev.renderX < minSep) {
            curr.renderX = prev.renderX + minSep
        }
    }

    // Pass 2: Alternate vertical offsets for clustered markers to create clean staggering
    for (i in svgPois.indices) {
        val curr = svgPois[i]
        val isClusteredWithPrev = i > 0 && (curr.origX - svgPois[i - 1].origX < 24.0)
        val isClusteredWithNext = i < svgPois.size - 1 && (svgPois[i + 1].origX - curr.origX < 24.0)

        if (isClusteredWithPrev || isClusteredWithNext) {
            val yShift = if (i % 2 == 1) -10.0 else 10.0
            curr.renderY += yShift
        }
    }

    val rawStartX = toSvgX(leg.startLng)
    val rawEndX = toSvgX(leg.endLng)

    val startX = (rawStartX - 22.0).coerceAtLeast(35.0)
    val startY = toSvgY(leg.startLat)
    val endX = (rawEndX + 22.0).coerceAtMost(width - 35.0)
    val endY = toSvgY(leg.endLat)

    val pathData = buildString {
        append(
            "M ${String.format(java.util.Locale.US, "%.1f", startX)} ${String.format(java.util.Locale.US, "%.1f", startY)}"
        )
        for (poiPt in svgPois) {
            append(
                " L ${String.format(java.util.Locale.US, "%.1f", poiPt.renderX)} ${String.format(java.util.Locale.US, "%.1f", poiPt.renderY)}"
            )
        }
        append(
            " L ${String.format(java.util.Locale.US, "%.1f", endX)} ${String.format(java.util.Locale.US, "%.1f", endY)}"
        )
    }

    val poiMarkers = buildString {
        svgPois.forEachIndexed { idx, poiPt ->
            val num = idx + 1
            val pxStr = String.format(java.util.Locale.US, "%.1f", poiPt.renderX)
            val pyStr = String.format(java.util.Locale.US, "%.1f", poiPt.renderY)
            val textYStr = String.format(java.util.Locale.US, "%.1f", poiPt.renderY + 3.0)
            append(
                """
                <circle cx="$pxStr" cy="$pyStr" r="8.5" fill="#0284c7" stroke="#ffffff" stroke-width="1.5"/>
                <text x="$pxStr" y="$textYStr" font-family="Inter, sans-serif" font-size="8.5" font-weight="bold" fill="#ffffff" text-anchor="middle">$num</text>
                """
                    .trimIndent()
            )
        }
    }

    val startXStr = String.format(java.util.Locale.US, "%.1f", startX)
    val startYStr = String.format(java.util.Locale.US, "%.1f", startY)
    val startTextXStr = String.format(java.util.Locale.US, "%.1f", startX - 10)
    val startTextYStr = String.format(java.util.Locale.US, "%.1f", startY + 3.5)

    val endXStr = String.format(java.util.Locale.US, "%.1f", endX)
    val endYStr = String.format(java.util.Locale.US, "%.1f", endY)
    val endTextXStr = String.format(java.util.Locale.US, "%.1f", endX + 10)
    val endTextYStr = String.format(java.util.Locale.US, "%.1f", endY + 3.5)

    return """
    <svg width="$width" height="$height" viewBox="0 0 $width $height" xmlns="http://www.w3.org/2000/svg">
      <rect width="100%" height="100%" fill="#f8fafc" rx="8" stroke="#e2e8f0" stroke-width="1"/>
      <path d="$pathData" stroke="#0284c7" stroke-width="2.5" fill="none" stroke-linecap="round" stroke-linejoin="round" stroke-dasharray="5,3"/>

      <!-- Start Pin & Label -->
      <circle cx="$startXStr" cy="$startYStr" r="6" fill="#059669" stroke="#ffffff" stroke-width="1.5"/>
      <text x="$startTextXStr" y="$startTextYStr" font-family="Inter, sans-serif" font-size="9.5" font-weight="bold" fill="#059669" text-anchor="end">Start</text>

      <!-- POI Markers -->
      $poiMarkers

      <!-- End Pin & Label -->
      <circle cx="$endXStr" cy="$endYStr" r="6" fill="#dc2626" stroke="#ffffff" stroke-width="1.5"/>
      <text x="$endTextXStr" y="$endTextYStr" font-family="Inter, sans-serif" font-size="9.5" font-weight="bold" fill="#dc2626" text-anchor="start">End</text>
    </svg>
    """
        .trimIndent()
}

/**
 * HTML component builders for PDF generation.
 *
 * STRICT OPENHTMLTOPDF ARCHITECTURE GUIDELINES:
 * - Use pure inline text flow (or table layout) for headers, badges, and metadata cards.
 * - Avoid combining `display: inline-block` with `vertical-align: middle` on SVG replacement
 *   elements adjacent to inline text.
 */
internal fun FlowContent.poiSection(pois: List<POI>, unit: DistanceUnit = DistanceUnit.METRIC) {
    div("poi-section") {
        div("poi-title") {
            unsafe { raw(LucideIcon.camera("#0284c7", 16)) }
            span { +" Corridor POIs & Scenic Highlights" }
        }
        pois.forEachIndexed { idx, poi -> poiCard(poi, index = idx + 1, unit = unit) }
    }
}

internal fun FlowContent.poiCard(poi: POI, index: Int, unit: DistanceUnit = DistanceUnit.METRIC) {
    val poiSearchUrl = MapUrlFormatter.formatPoiUrl(poi)
    val poiName = poi.name ?: "Point of Interest"
    val poiType =
        poi.type.split("_").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    val distOffRoute = formatOffRouteDistance(poi.distanceFromRouteMeters, unit)

    div("poi-card") {
        div("poi-card-header") {
            span("poi-number") { +"$index." }
            +" "
            a(href = poiSearchUrl, classes = "poi-name-link") { +sanitizeText(poiName) }
            +"  "
            span("tag-badge") { +poiType }
            if (distOffRoute != null) {
                +" "
                span("dist-badge") { +distOffRoute }
            }
        }
        if (!poi.description.isNullOrBlank()) {
            div("poi-card-desc") { +sanitizeText(poi.description) }
        }
    }
}

internal fun FlowContent.navigationAppendix(route: Route) {
    div("appendix-page") {
        div("section-title editorial-heading") { +"Mobile Navigation Cheat Sheet" }
        div("appendix-intro") {
            +"Scan any QR code below with your phone camera to instantly launch turn-by-turn directions for that day in Google Maps."
        }
        div("qr-grid") {
            for (leg in route.legs) {
                val rawTitle = leg.dayTitle ?: "Scenic Drive"
                val cleanTitle =
                    rawTitle
                        .replace(Regex("^Day\\s+\\d+[:\\s-]*", RegexOption.IGNORE_CASE), "")
                        .ifBlank { leg.endTownName?.let { "Drive to $it" } ?: "Scenic Drive" }
                val directionsUrl = leg.toDirectionsUrl()
                val qrDataUri = QrCodeGenerator.generateQrCodeDataUri(directionsUrl, 280, 280)

                div("qr-card") {
                    div("qr-card-header") { +"Day ${leg.dayNumber}: ${sanitizeText(cleanTitle)}" }
                    if (qrDataUri.isNotBlank()) {
                        img(
                            src = qrDataUri,
                            alt = "Leg Navigation QR Code",
                            classes = "appendix-qr-img",
                        )
                    }
                    div("qr-card-footer") {
                        a(href = directionsUrl, classes = "appendix-link") {
                            unsafe { raw(LucideIcon.navigation("#0284c7", 12)) }
                            +" Open in Maps"
                        }
                    }
                }
            }
        }
    }
}

internal fun pdfStyles(startLocation: String, endLocation: String): String {
    val css = PdfExporter::class.java.getResource("/pdf-styles.css")?.readText() ?: ""
    return css.replace("{{startLocation}}", startLocation).replace("{{endLocation}}", endLocation)
}
