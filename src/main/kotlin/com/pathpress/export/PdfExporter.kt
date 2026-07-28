package com.pathpress.export

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.openhtmltopdf.svgsupport.BatikSVGDrawer
import com.pathpress.llm.*
import com.pathpress.model.*
import com.pathpress.poi.*
import com.pathpress.routing.*
import kotlinx.html.*
import kotlinx.html.stream.appendHTML

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

    fun generateHtml(
        route: Route,
        startLocation: String,
        endLocation: String,
        unit: DistanceUnit = DistanceUnit.METRIC,
    ): String {
        val safeStart = sanitizeText(startLocation)
        val safeEnd = sanitizeText(endLocation)
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendHTML(prettyPrint = false, xhtmlCompatible = true).html {
                lang = "en"
                head {
                    meta { charset = "UTF-8" }
                    title { +"PathPress Scenic Itinerary" }
                    style { unsafe { raw(pdfStyles(safeStart, safeEnd)) } }
                }
                body {
                    coverPage(route, safeStart, safeEnd, unit)
                    dailySchedule(route, unit)
                    navigationAppendix(route)
                }
            }
        }
    }

    internal fun sanitizeText(text: String): String {
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
            .replace("“", "\"")
            .replace("”", "\"")
            .replace("‘", "'")
            .replace("’", "'")
            .replace("–", "-")
            .replace("—", " - ")
            .replace("…", "...")
            .replace("\u00A0", " ")
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

    fun formatDistance(meters: Double, unit: DistanceUnit = DistanceUnit.METRIC): String {
        return when (unit) {
            DistanceUnit.METRIC -> {
                if (meters >= 1000) {
                    "${String.format(java.util.Locale.US, "%.1f", meters / 1000.0)} km"
                } else {
                    "${String.format(java.util.Locale.US, "%.0f", meters)} m"
                }
            }
            DistanceUnit.IMPERIAL -> {
                val miles = meters / 1609.344
                val feet = meters * 3.28084
                if (miles >= 0.1) {
                    "${String.format(java.util.Locale.US, "%.1f", miles)} mi"
                } else {
                    "${String.format(java.util.Locale.US, "%.0f", feet)} ft"
                }
            }
        }
    }

    fun formatOffRouteDistance(meters: Double?, unit: DistanceUnit = DistanceUnit.METRIC): String? {
        if (meters == null) return null
        return when (unit) {
            DistanceUnit.METRIC -> {
                if (meters < 1000.0) {
                    "${kotlin.math.round(meters).toInt()} m off route"
                } else {
                    "${String.format(java.util.Locale.US, "%.1f", meters / 1000.0)} km off route"
                }
            }
            DistanceUnit.IMPERIAL -> {
                val miles = meters / 1609.344
                val feet = meters * 3.28084
                if (miles < 0.1) {
                    "${kotlin.math.round(feet).toInt()} ft off route"
                } else {
                    "${String.format(java.util.Locale.US, "%.1f", miles)} mi off route"
                }
            }
        }
    }

    fun formatDuration(seconds: Double): String {
        val hours = seconds.toInt() / 3600
        val minutes = (seconds.toInt() % 3600) / 60
        return if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
        }
    }
}
