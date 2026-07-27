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

    fun generateHtml(route: Route, startLocation: String, endLocation: String): String {
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendHTML(prettyPrint = false).html {
                lang = "en"
                head {
                    meta { charset = "UTF-8" }
                    title { +"PathPress Scenic Itinerary" }
                    style { unsafe { raw(pdfStyles(startLocation, endLocation)) } }
                }
                body {
                    coverPage(route, startLocation, endLocation)
                    dailySchedule(route)
                }
            }
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
