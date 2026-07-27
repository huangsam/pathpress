package com.pathpress.export

import com.pathpress.export.PdfExporter.formatDistance
import com.pathpress.export.PdfExporter.formatDuration
import com.pathpress.export.PdfExporter.formatOffRouteDistance
import com.pathpress.export.PdfExporter.sanitizeText
import com.pathpress.model.*
import com.pathpress.poi.*
import com.pathpress.routing.*
import kotlinx.html.*

internal fun FlowContent.coverPage(route: Route, startLocation: String, endLocation: String) {
    div("cover-container") {
        heroBanner(route, startLocation, endLocation)
        metadataCard(route)
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
        if (route.narrative.isNotBlank()) {
            div("hero-narrative") { +"\"${sanitizeText(route.narrative)}\"" }
        }
    }
}

internal fun FlowContent.metadataCard(route: Route) {
    div("metadata-card") {
        table("metadata-grid") {
            tr {
                td {
                    span("meta-label") { +"Total Distance" }
                    br()
                    span("meta-val") {
                        unsafe { raw(LucideIcon.route("#0284c7", 14)) }
                        +" ${formatDistance(route.totalDistanceMeters)}"
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

internal fun FlowContent.dailySchedule(route: Route) {
    div("section-title editorial-heading") { +"Daily Schedule & Itinerary" }
    for (leg in route.legs) {
        legCard(leg, route)
    }
}

internal fun FlowContent.legCard(leg: RouteLeg, route: Route) {
    val rawTitle = leg.dayTitle ?: "Scenic Drive"
    val cleanTitle = rawTitle.replace(Regex("^Day\\s+\\d+:\\s*", RegexOption.IGNORE_CASE), "")
    val distance = leg.distanceMeters ?: (route.totalDistanceMeters / route.legs.size)
    val duration = leg.durationSeconds ?: (route.totalDurationSeconds / route.legs.size)
    val directionsUrl = leg.toDirectionsUrl()
    val qrDataUri = QrCodeGenerator.generateQrCodeDataUri(directionsUrl, 160, 160)

    div("leg") {
        table("leg-top-grid") {
            tr {
                td {
                    style = "vertical-align: top;"
                    div("day-badge") { +"Day ${leg.dayNumber} of ${leg.totalDays}" }
                    div("leg-title editorial-heading") { +sanitizeText(cleanTitle) }
                    div("meta-pills") {
                        span("meta-badge") {
                            unsafe { raw(LucideIcon.route("#334155", 12)) }
                            +" ${formatDistance(distance)}"
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
                    }
                }
                if (qrDataUri.isNotBlank()) {
                    td {
                        style = "width: 125px; vertical-align: top; text-align: right;"
                        div("nav-action-box") {
                            img(src = qrDataUri, alt = "QR Code", classes = "qr-img") {
                                width = "70"
                                height = "70"
                            }
                            a(href = directionsUrl, classes = "maps-btn-sm") {
                                unsafe { raw(LucideIcon.navigation("#ffffff", 11)) }
                                +" Directions"
                            }
                        }
                    }
                }
            }
        }

        if (!leg.legStory.isNullOrBlank()) {
            div("leg-story") { +"\"${sanitizeText(leg.legStory)}\"" }
        }

        if (leg.pois.isNotEmpty()) {
            poiSection(leg.pois)
        }

        if (leg.foodRecommendations.isNotEmpty()) {
            infoCard(
                title = "Local Coffee & Food Recommendations",
                icon = LucideIcon.coffee("#92400e", 16),
                items = leg.foodRecommendations,
                textColor = "#92400e",
                bgColor = "#fffbeb",
                borderColor = "#fde68a",
            )
        }

        if (leg.insiderTips.isNotEmpty()) {
            infoCard(
                title = "Insider Driving & Scenic Tips",
                icon = LucideIcon.lightbulb("#22543d", 16),
                items = leg.insiderTips,
                textColor = "#276749",
                bgColor = "#f0fff4",
                borderColor = "#c6f6d5",
                titleColor = "#22543d",
            )
        }
    }
}

internal fun FlowContent.poiSection(pois: List<POI>) {
    div("poi-section") {
        div("poi-title") {
            unsafe { raw(LucideIcon.camera("#0284c7", 16)) }
            span {
                style = "vertical-align: middle;"
                +" Corridor POIs & Scenic Highlights"
            }
        }
        for (poi in pois) {
            poiCard(poi)
        }
    }
}

internal fun FlowContent.poiCard(poi: POI) {
    val poiSearchUrl = MapUrlFormatter.formatPoiUrl(poi)
    val poiName = poi.name ?: "Point of Interest"
    val poiType =
        poi.type.split("_").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    val distOffRoute = formatOffRouteDistance(poi.distanceFromRouteMeters)

    div("poi-card") {
        div("poi-card-header") {
            a(href = poiSearchUrl) {
                style = "color: #0284c7; text-decoration: none;"
                +sanitizeText(poiName)
            }
            span("tag-badge") { +poiType }
            if (distOffRoute != null) {
                span("dist-badge") { +distOffRoute }
            }
        }
        if (!poi.description.isNullOrBlank()) {
            div("poi-card-desc") { +sanitizeText(poi.description) }
        }
    }
}

internal fun FlowContent.infoCard(
    title: String,
    icon: String,
    items: List<String>,
    textColor: String,
    bgColor: String,
    borderColor: String,
    titleColor: String = textColor,
) {
    div("info-card") {
        style = "background: $bgColor; border-color: $borderColor;"
        div("info-card-title") {
            style = "color: $titleColor;"
            unsafe { raw(icon) }
            span {
                style = "vertical-align: middle;"
                +" $title"
            }
        }
        for (item in items) {
            div("info-card-item") {
                style = "color: $textColor;"
                +"\u2022 ${sanitizeText(item)}"
            }
        }
    }
}

internal fun pdfStyles(startLocation: String, endLocation: String): String {
    val css = PdfExporter::class.java.getResource("/pdf-styles.css")?.readText() ?: ""
    return css.replace("{{startLocation}}", startLocation).replace("{{endLocation}}", endLocation)
}
