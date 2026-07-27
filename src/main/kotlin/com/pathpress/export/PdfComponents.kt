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

internal fun pdfStyles(startLocation: String, endLocation: String): String =
    """
    @page {
      size: A4 portrait;
      margin: 20mm 14mm 18mm 14mm;
      @top-left {
        content: "PATHPRESS ROAD TRIP GUIDE";
        font-family: 'Inter', Helvetica, sans-serif;
        font-size: 8px;
        font-weight: 700;
        color: #a0aec0;
        letter-spacing: 0.8px;
      }
      @top-right {
        content: "$startLocation to $endLocation";
        font-family: 'Inter', Helvetica, sans-serif;
        font-size: 8px;
        color: #a0aec0;
      }
      @bottom-right {
        content: "Page " counter(page) " of " counter(pages);
        font-family: 'Inter', Helvetica, sans-serif;
        font-size: 9px;
        color: #718096;
      }
      @bottom-left {
        content: "PathPress Scenic Itinerary";
        font-family: 'Inter', Helvetica, sans-serif;
        font-size: 9px;
        color: #718096;
      }
    }
    @page:first {
      @top-left { content: none; }
      @top-right { content: none; }
    }
    body { font-family: 'Inter', Helvetica, Arial, sans-serif; color: #1e293b; margin: 0; padding: 0; line-height: 1.6; font-size: 12px; }
    h1, h2, h3, .editorial-heading { font-family: 'Merriweather', Georgia, serif; }
    .cover-container { margin-bottom: 20px; }
    .hero-banner { background-color: #0f172a; color: #f8fafc; padding: 40px 32px; border-radius: 12px; margin-bottom: 24px; }
    .hero-title { font-size: 32px; font-weight: bold; color: #f8fafc; margin: 0 0 12px 0; letter-spacing: -0.5px; line-height: 1.2; }
    .hero-subtitle { font-size: 14px; color: #94a3b8; margin-bottom: 18px; font-weight: 500; line-height: 1.4; letter-spacing: 0.5px; text-transform: uppercase; }
    .hero-narrative { background-color: #1e293b; border-left: 4px solid #38bdf8; padding: 16px 20px; font-style: italic; color: #f1f5f9; font-size: 13px; border-radius: 6px; line-height: 1.5; }
    .metadata-card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 18px 22px; margin-bottom: 30px; }
    .metadata-grid { width: 100%; border-collapse: collapse; }
    .metadata-grid td { padding: 6px 8px; vertical-align: middle; }
    .meta-label { color: #64748b; font-weight: 600; font-size: 11px; text-transform: uppercase; letter-spacing: 0.8px; }
    .meta-val { color: #0f172a; font-weight: bold; font-size: 14px; margin-top: 4px; display: inline-block; }
    .section-title { font-size: 18px; color: #0f172a; border-bottom: 2px solid #0284c7; padding-bottom: 8px; margin-top: 24px; margin-bottom: 20px; font-weight: bold; line-height: 1.4; }
    .leg { margin-bottom: 32px; padding: 24px 28px; border: 1px solid #e2e8f0; border-radius: 12px; background: #ffffff; page-break-inside: auto; }
    .leg + .leg { page-break-before: always; }
    .leg-top-grid { width: 100%; border-collapse: collapse; margin-bottom: 18px; page-break-inside: avoid; }
    .day-badge { color: #0284c7; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 6px; display: block; }
    .leg-title { font-size: 20px; font-weight: bold; color: #0f172a; margin: 0 0 10px 0; line-height: 1.3; }
    .meta-pills { margin-top: 4px; }
    .meta-badge { display: inline-block; background: #f1f5f9; color: #334155; padding: 5px 12px; border-radius: 14px; font-size: 11px; font-weight: 600; margin-right: 6px; border: 1px solid #e2e8f0; line-height: 1.3; }
    .meta-badge-overnight { display: inline-block; background: #f0fdf4; color: #166534; padding: 5px 12px; border-radius: 14px; font-size: 11px; font-weight: 600; margin-right: 6px; border: 1px solid #bbf7d0; line-height: 1.3; }
    .nav-action-box { background: #f8fafc; border: 1px solid #cbd5e1; border-radius: 10px; padding: 10px 12px; text-align: center; }
    .qr-img { border: 1px solid #e2e8f0; border-radius: 6px; background: #ffffff; padding: 4px; display: block; margin: 0 auto 8px auto; }
    a.maps-btn-sm { display: block; padding: 8px 12px; background: #0284c7; color: #ffffff; text-decoration: none; border-radius: 6px; font-size: 11px; font-weight: bold; line-height: 1.2; white-space: nowrap; letter-spacing: 0.3px; }
    .leg-story { font-style: italic; color: #475569; margin-bottom: 20px; background: #f8fafc; padding: 16px 20px; border-radius: 10px; border-left: 4px solid #38bdf8; font-size: 13px; line-height: 1.6; }
    .poi-section { margin-top: 20px; background: #ffffff; padding: 0; border-radius: 0; border: none; }
    .poi-title { font-size: 15px; font-weight: bold; color: #0f172a; margin-bottom: 16px; border-bottom: 2px solid #e2e8f0; padding-bottom: 8px; line-height: 1.4; }
    .poi-card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 14px 16px; margin-bottom: 10px; page-break-inside: avoid; }
    .poi-card-header { font-size: 13px; font-weight: bold; color: #0284c7; margin-bottom: 6px; }
    .poi-card-desc { color: #475569; font-size: 12px; margin-top: 6px; line-height: 1.5; }
    .tag-badge { background: #e2e8f0; color: #475569; padding: 3px 8px; border-radius: 6px; font-size: 10px; margin-left: 8px; text-transform: capitalize; font-weight: 500; }
    .dist-badge { background: #ffedd5; color: #9a3412; padding: 3px 8px; border-radius: 6px; font-size: 10px; margin-left: 8px; font-weight: 500; }
    .info-card { margin-top: 18px; background: #fffbeb; border: 1px solid #fde68a; padding: 16px 18px; border-radius: 10px; page-break-inside: avoid; }
    .info-card-title { font-size: 14px; font-weight: bold; color: #92400e; margin-bottom: 10px; line-height: 1.4; }
    .info-card-item { font-size: 12px; color: #92400e; margin-bottom: 6px; line-height: 1.5; }
    """
        .trimIndent()
