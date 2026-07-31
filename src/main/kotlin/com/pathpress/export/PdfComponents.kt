package com.pathpress.export

import com.pathpress.export.PdfExporter.formatDistance
import com.pathpress.export.PdfExporter.formatDuration
import com.pathpress.export.PdfExporter.formatOffRouteDistance
import com.pathpress.export.PdfExporter.sanitizeText
import com.pathpress.model.DistanceUnit
import com.pathpress.model.POI
import com.pathpress.model.Route
import com.pathpress.model.RouteLeg
import com.pathpress.model.boundNarrative
import com.pathpress.model.sanitizePoiType
import com.pathpress.poi.AddressResolver
import com.pathpress.poi.MapUrlFormatter
import kotlinx.html.*

internal fun FlowContent.coverPage(
    route: Route,
    startLocation: String,
    endLocation: String,
    unit: DistanceUnit = DistanceUnit.METRIC,
) {
    div("cover-container") {
        id = "cover-page"
        heroBanner(route, startLocation, endLocation)
        metadataCard(route, unit)
        tableOfContentsCard(route)
        routeOverviewMapCard(route)
    }
}

internal fun FlowContent.tableOfContentsCard(route: Route) {
    val isMultiCol = route.legs.size >= 4
    val ulClass = if (isMultiCol) "toc-list toc-grid-2col" else "toc-list"
    div("toc-card") {
        div("toc-title editorial-heading") {
            unsafe { raw(LucideIcon.compass("#0284c7", 14)) }
            +" Table of Contents"
        }
        ul(ulClass) {
            for (leg in route.legs) {
                val rawTitle = leg.dayTitle ?: "Scenic Drive"
                val cleanTitle =
                    rawTitle
                        .replace(Regex("^Day\\s+\\d+[:\\s-]*", RegexOption.IGNORE_CASE), "")
                        .ifBlank { "Scenic Drive" }
                li("toc-item") {
                    a(href = "#leg-${leg.dayNumber}", classes = "toc-link") {
                        +"Day ${leg.dayNumber}: ${sanitizeText(cleanTitle)}"
                    }
                }
            }
            li("toc-item") {
                a(href = "#navigation-appendix", classes = "toc-link") {
                    +"Mobile Navigation & Route Map Appendix"
                }
            }
        }
    }
}

internal fun FlowContent.routeOverviewMapCard(route: Route) {
    val mapDataUri = OsmTileStitcher.renderRouteMapDataUri(route, 560, 200)
    if (mapDataUri.isBlank()) return
    div("cover-map-card") {
        div("cover-map-header editorial-heading") {
            unsafe { raw(LucideIcon.mapPin("#0284c7", 14)) }
            +" Full Route Overview Map"
        }
        div("cover-map-container") {
            img(src = mapDataUri, alt = "Full Route Overview Map", classes = "cover-map-img")
        }
    }
}

internal fun FlowContent.heroBanner(route: Route, startLocation: String, endLocation: String) {
    div("hero-banner") {
        div("hero-title editorial-heading") { +"PathPress Scenic Road Trip" }
        div("hero-subtitle") {
            unsafe {
                raw(
                    "${LucideIcon.mapPin("#94a3b8", 15)} ${PdfExporter.escapeXml(startLocation)} ${LucideIcon.arrowRight("#94a3b8", 14)} ${LucideIcon.mapPin("#94a3b8", 15)} ${PdfExporter.escapeXml(endLocation)}"
                )
            }
        }
        div("hero-narrative") {
            val narrative = route.getNarrativeOrDefault(startLocation, endLocation)
            val clean = sanitizeText(narrative).trim().trim('"', '“', '”')
            +"“$clean”"
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
    div("daily-schedule-section") {
        div("section-title editorial-heading") { +"Daily Schedule & Itinerary" }
        for (leg in route.legs) {
            legCard(leg, route, unit)
        }
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
            id = "leg-${leg.dayNumber}"
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
                    +" Scenic Overview"
                }
            }
            if (!leg.legStory.isNullOrBlank()) {
                val cleanStory =
                    sanitizeText(leg.legStory.boundNarrative(maxSentences = 1))
                        .trim()
                        .trim('"', '“', '”')
                div("leg-story-caption") { +"“$cleanStory”" }
            }
        }

        // Filter out separate Start/Finish cards and cap POIs per leg at 9 max (matching Google
        // Maps waypoints limit)
        val filteredPois =
            leg.pois
                .filterNot { poi ->
                    val typeLower = poi.type.lowercase().trim()
                    val nameLower = poi.name?.lowercase()?.trim() ?: ""
                    typeLower in setOf("start", "finish", "origin", "destination") ||
                        nameLower == "start" ||
                        nameLower == "finish" ||
                        nameLower == "origin" ||
                        nameLower == "destination"
                }
                .take(9)

        if (filteredPois.isNotEmpty()) {
            poiSection(filteredPois, unit)
        }
    }
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
            unsafe { raw(LucideIcon.map("#0284c7", 16)) }
            span { +" Along the Route" }
        }
        pois.forEachIndexed { idx, poi -> poiCard(poi, index = idx + 1, unit = unit) }
    }
}

internal fun FlowContent.poiCard(poi: POI, index: Int, unit: DistanceUnit = DistanceUnit.METRIC) {
    val poiSearchUrl = MapUrlFormatter.formatPoiUrl(poi)
    val poiNavUrl = MapUrlFormatter.formatSingleStopNavUrl(poi.lat, poi.lng)
    val poiName = poi.name ?: "Point of Interest"
    val rawType = sanitizePoiType(poi.type, poi.tags)
    val poiType =
        rawType.split("_").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    val distOffRoute = formatOffRouteDistance(poi.distanceFromRouteMeters, unit)
    val resolvedAddress = AddressResolver.resolveAddress(poi)

    div("poi-card") {
        // Line 1: Header table with title link, category tag, off-route distance badge, and
        // float-right 1-tap Nav button
        table("poi-header-table") {
            tr {
                td("poi-header-left") {
                    span("poi-number") { +"$index. " }
                    a(href = poiSearchUrl, classes = "poi-name-link") { +sanitizeText(poiName) }
                    +"  "
                    span("tag-badge") { +poiType }
                    if (distOffRoute != null) {
                        +" "
                        span("dist-badge") { +distOffRoute }
                    }
                }
                td("poi-header-right") {
                    a(href = poiNavUrl, classes = "poi-nav-btn") {
                        unsafe { raw(LucideIcon.navigation("#ffffff", 10)) }
                        +" Nav"
                    }
                }
            }
        }
        // Line 2: Muted resolved physical street address with location pin SVG icon
        div("poi-card-address") {
            unsafe { raw(LucideIcon.mapPin("#64748b", 12)) }
            +" ${sanitizeText(resolvedAddress)}"
        }
        // Line 3: Rich POI description text
        if (!poi.description.isNullOrBlank()) {
            div("poi-card-desc") { +sanitizeText(poi.description) }
        }
    }
}

internal fun FlowContent.navigationAppendix(route: Route) {
    div("appendix-page") {
        id = "navigation-appendix"
        div("section-title editorial-heading") { +"Mobile Navigation & Route Map Appendix" }
        div("appendix-intro") {
            +"Review your route overview map for each leg, and scan the matching QR code with your phone camera to launch turn-by-turn directions in Google Maps."
        }

        for (leg in route.legs) {
            val rawTitle = leg.dayTitle ?: "Scenic Drive"
            val cleanTitle =
                rawTitle
                    .replace(Regex("^Day\\s+\\d+[:\\s-]*", RegexOption.IGNORE_CASE), "")
                    .ifBlank { leg.endTownName?.let { "Drive to $it" } ?: "Scenic Drive" }
            val directionsUrl = leg.toDirectionsUrl()
            val qrDataUri = QrCodeGenerator.generateQrCodeDataUri(directionsUrl, 280, 280)
            val mapDataUri = OsmTileStitcher.renderLegMapDataUri(leg, 175, 175)

            div("appendix-leg-row") {
                div("appendix-leg-header") { +"Day ${leg.dayNumber}: ${sanitizeText(cleanTitle)}" }

                val containerClass =
                    if (mapDataUri.isNotBlank()) "appendix-card-pair"
                    else "appendix-card-pair single-card"
                div(containerClass) {
                    // Left Card: Route Overview Map
                    if (mapDataUri.isNotBlank()) {
                        div("qr-card qr-card-left") {
                            div("qr-card-header") { +"Route Overview Map" }
                            img(src = mapDataUri, alt = "Route Map", classes = "appendix-map-img")
                        }
                    }

                    // Right Card: Mobile Navigation QR Code
                    div("qr-card") {
                        div("qr-card-header") { +"Mobile Navigation" }
                        if (qrDataUri.isNotBlank()) {
                            img(
                                src = qrDataUri,
                                alt = "Leg Navigation QR Code",
                                classes = "appendix-qr-img",
                            )
                        }
                    }
                }

                // Single Unified Centered Action Button
                div("appendix-leg-action-container") {
                    a(href = directionsUrl, classes = "appendix-action-btn") {
                        unsafe { raw(LucideIcon.navigation("#0284c7", 12)) }
                        +" Open Day ${leg.dayNumber} Directions in Google Maps"
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
