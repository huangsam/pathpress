package com.pathpress.export

import com.pathpress.model.LocationCoords
import com.pathpress.model.RouteLeg
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan
import org.slf4j.LoggerFactory

/**
 * Publication-grade minimalist line-shape map tile renderer for PathPress PDF export.
 *
 * 100% Java/Kotlin native implementation: fetches CartoDB Voyager travel tiles, projects exact
 * GraphHopper road curvature polyline, and renders a clean route line (white casing + ocean blue
 * stroke + green start & red end pins) without marker clutter.
 */
object OsmTileStitcher {

    private val logger = LoggerFactory.getLogger(OsmTileStitcher::class.java)
    private const val TILE_SIZE = 256

    private fun lonToTileX(lon: Double, zoom: Int): Double {
        val n = 1 shl zoom
        return (lon + 180.0) / 360.0 * n
    }

    private fun latToTileY(lat: Double, zoom: Int): Double {
        val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val n = 1 shl zoom
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0 * n
    }

    /** Render a clean line-shape route map as a raw Data URI. */
    fun renderLegMapDataUri(
        leg: RouteLeg,
        width: Int = 260,
        height: Int = 260,
        baseDir: File = MapTileStorage.DEFAULT_BASE_DIR,
    ): String {
        val points = mutableListOf<LocationCoords>()
        points.addAll(leg.geometry)
        return renderPointsMapDataUri(
            points = points,
            startLat = leg.startLat,
            startLng = leg.startLng,
            endLat = leg.endLat,
            endLng = leg.endLng,
            width = width,
            height = height,
            baseDir = baseDir,
        )
    }

    /** Render a clean line-shape full route overview map for all legs in a Route. */
    fun renderRouteMapDataUri(
        route: com.pathpress.model.Route,
        width: Int = 560,
        height: Int = 220,
        baseDir: File = MapTileStorage.DEFAULT_BASE_DIR,
    ): String {
        if (route.legs.isEmpty()) return ""
        val points = mutableListOf<LocationCoords>()
        for (leg in route.legs) {
            points.addAll(leg.geometry)
        }
        val firstLeg = route.legs.first()
        val lastLeg = route.legs.last()
        return renderPointsMapDataUri(
            points = points,
            startLat = firstLeg.startLat,
            startLng = firstLeg.startLng,
            endLat = lastLeg.endLat,
            endLng = lastLeg.endLng,
            width = width,
            height = height,
            baseDir = baseDir,
        )
    }

    private fun renderPointsMapDataUri(
        points: List<LocationCoords>,
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        width: Int,
        height: Int,
        baseDir: File = MapTileStorage.DEFAULT_BASE_DIR,
    ): String {
        if (points.isEmpty()) return ""

        val rawMinLat = points.minOf { it.lat }
        val rawMaxLat = points.maxOf { it.lat }
        val rawMinLng = points.minOf { it.lng }
        val rawMaxLng = points.maxOf { it.lng }

        val latDiff = (rawMaxLat - rawMinLat).coerceAtLeast(0.005)
        val lngDiff = (rawMaxLng - rawMinLng).coerceAtLeast(0.005)

        // Buffer bounds tightly by 10%
        val minLat = rawMinLat - (latDiff * 0.10)
        val maxLat = rawMaxLat + (latDiff * 0.10)
        val minLng = rawMinLng - (lngDiff * 0.10)
        val maxLng = rawMaxLng + (lngDiff * 0.10)

        val targetW = width * 2
        val targetH = height * 2

        val zoomX = log2(360.0 / (maxLng - minLng) * (targetW / TILE_SIZE.toDouble()))
        val zoomY = log2(180.0 / (maxLat - minLat) * (targetH / TILE_SIZE.toDouble()))
        val zoom = max(1, min(15, min(zoomX.toInt(), zoomY.toInt())))

        val centerLng = (minLng + maxLng) / 2.0
        val centerLat = (minLat + maxLat) / 2.0

        val centerTileX = lonToTileX(centerLng, zoom) * TILE_SIZE
        val centerTileY = latToTileY(centerLat, zoom) * TILE_SIZE

        val cropMinX = (centerTileX - targetW / 2.0).toInt()
        val cropMinY = (centerTileY - targetH / 2.0).toInt()

        val startTileX = cropMinX / TILE_SIZE - 1
        val endTileX = (cropMinX + targetW) / TILE_SIZE + 1
        val startTileY = cropMinY / TILE_SIZE - 1
        val endTileY = (cropMinY + targetH) / TILE_SIZE + 1

        val tileCols = (endTileX - startTileX + 1).coerceAtLeast(1)
        val tileRows = (endTileY - startTileY + 1).coerceAtLeast(1)

        val fullW = tileCols * TILE_SIZE
        val fullH = tileRows * TILE_SIZE

        val fullCanvas = BufferedImage(fullW, fullH, BufferedImage.TYPE_INT_ARGB)
        val gFull = fullCanvas.createGraphics()
        gFull.color = Color(241, 245, 249)
        gFull.fillRect(0, 0, fullW, fullH)

        var tilesLoaded = 0
        for (ty in startTileY..endTileY) {
            for (tx in startTileX..endTileX) {
                val tile = MapTileStorage.getTile(zoom, tx, ty, baseDir)
                val drawX = (tx - startTileX) * TILE_SIZE
                val drawY = (ty - startTileY) * TILE_SIZE
                if (tile != null) {
                    gFull.drawImage(tile, drawX, drawY, null)
                    tilesLoaded++
                }
            }
        }
        gFull.dispose()

        if (tilesLoaded == 0) return ""

        val tileOriginPxX = startTileX * TILE_SIZE
        val tileOriginPxY = startTileY * TILE_SIZE
        val subX = (cropMinX - tileOriginPxX).coerceIn(0, (fullW - targetW).coerceAtLeast(0))
        val subY = (cropMinY - tileOriginPxY).coerceIn(0, (fullH - targetH).coerceAtLeast(0))

        val finalImage = fullCanvas.getSubimage(subX, subY, targetW, targetH)
        val g = finalImage.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )

        fun toPxX(lng: Double): Double = lonToTileX(lng, zoom) * TILE_SIZE - cropMinX
        fun toPxY(lat: Double): Double = latToTileY(lat, zoom) * TILE_SIZE - cropMinY

        // Draw Driving Road Polyline
        val nPoints = points.size
        val polyX = IntArray(nPoints)
        val polyY = IntArray(nPoints)
        for (i in 0 until nPoints) {
            val pt = points[i]
            polyX[i] = toPxX(pt.lng).toInt()
            polyY[i] = toPxY(pt.lat).toInt()
        }

        // Outer white casing
        g.stroke = BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = Color(255, 255, 255, 240)
        g.drawPolyline(polyX, polyY, points.size)

        // Primary Route Line (#0284c7 Ocean Blue)
        g.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = Color(2, 132, 199)
        g.drawPolyline(polyX, polyY, points.size)

        // Start Pin (Green Dot)
        val startPxX = toPxX(startLng).toInt()
        val startPxY = toPxY(startLat).toInt()
        g.color = Color(5, 150, 105)
        g.fillOval(startPxX - 10, startPxY - 10, 20, 20)
        g.color = Color.WHITE
        g.stroke = BasicStroke(3f)
        g.drawOval(startPxX - 10, startPxY - 10, 20, 20)

        // End Pin (Red Dot)
        val endPxX = toPxX(endLng).toInt()
        val endPxY = toPxY(endLat).toInt()
        g.color = Color(220, 38, 38)
        g.fillOval(endPxX - 10, endPxY - 10, 20, 20)
        g.color = Color.WHITE
        g.stroke = BasicStroke(3f)
        g.drawOval(endPxX - 10, endPxY - 10, 20, 20)

        // Attribution bar — required by CARTO and OSM tile usage policies
        val attrText = "© CARTO © OpenStreetMap"
        val attrFont = Font(Font.SANS_SERIF, Font.PLAIN, 14)
        g.font = attrFont
        val fm = g.fontMetrics
        val textW = fm.stringWidth(attrText)
        val textH = fm.height
        val padH = 4
        val padW = 6
        val barX = targetW - textW - padW * 2
        val barY = targetH - textH - padH * 2
        // Semi-transparent dark pill background
        g.color = Color(0, 0, 0, 140)
        g.fillRoundRect(barX, barY, textW + padW * 2, textH + padH * 2, 6, 6)
        // White text
        g.color = Color(255, 255, 255, 230)
        g.drawString(attrText, barX + padW, barY + padH + fm.ascent)

        g.dispose()

        val baos = ByteArrayOutputStream()
        ImageIO.write(finalImage, "png", baos)
        val base64 = Base64.getEncoder().encodeToString(baos.toByteArray())

        return "data:image/png;base64,$base64"
    }
}
