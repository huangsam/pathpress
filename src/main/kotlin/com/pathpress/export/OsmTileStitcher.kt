package com.pathpress.export

import com.pathpress.model.LocationCoords
import com.pathpress.model.RouteLeg
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Publication-grade minimalist line-shape map tile renderer for PathPress PDF export.
 *
 * 100% Java/Kotlin native implementation: fetches CartoDB Voyager travel tiles, projects exact
 * GraphHopper road curvature polyline, and renders a clean route line (white casing + ocean blue
 * stroke + green start & red end pins) without marker clutter.
 */
object OsmTileStitcher {

    private const val TILE_SIZE = 256
    private val cacheDir = File(".map_cache").apply { mkdirs() }

    private fun lonToTileX(lon: Double, zoom: Int): Double {
        val n = 1 shl zoom
        return (lon + 180.0) / 360.0 * n
    }

    private fun latToTileY(lat: Double, zoom: Int): Double {
        val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val n = 1 shl zoom
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0 * n
    }

    private fun fetchTile(zoom: Int, x: Int, y: Int): BufferedImage? {
        val cacheFile = File(cacheDir, "tile_v_${zoom}_${x}_${y}.png")
        if (cacheFile.exists()) {
            try {
                return ImageIO.read(cacheFile)
            } catch (_: Exception) {}
        }

        // CartoDB Voyager travel tiles
        val tileUrl =
            "https://cartodb-basemaps-a.global.ssl.fastly.net/rastertiles/voyager/$zoom/$x/$y.png"
        try {
            val conn = URI(tileUrl).toURL().openConnection() as HttpURLConnection
            conn.setRequestProperty(
                "User-Agent",
                "PathPress/0.1.0 (https://github.com/huangsam/pathpress)",
            )
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val image = ImageIO.read(conn.inputStream)
                if (image != null) {
                    try {
                        ImageIO.write(image, "png", cacheFile)
                    } catch (_: Exception) {}
                    return image
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** Render a clean line-shape route map as a raw Data URI. */
    fun renderLegMapDataUri(leg: RouteLeg, width: Int = 260, height: Int = 260): String {
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
        )
    }

    /** Render a clean line-shape full route overview map for all legs in a Route. */
    fun renderRouteMapDataUri(
        route: com.pathpress.model.Route,
        width: Int = 560,
        height: Int = 220,
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
    ): String {
        if (points.isEmpty()) return ""

        var minLat = points.minOf { it.lat }
        var maxLat = points.maxOf { it.lat }
        var minLng = points.minOf { it.lng }
        var maxLng = points.maxOf { it.lng }

        val latDiff = (maxLat - minLat).coerceAtLeast(0.005)
        val lngDiff = (maxLng - minLng).coerceAtLeast(0.005)

        // Buffer bounds tightly by 10%
        minLat -= latDiff * 0.10
        maxLat += latDiff * 0.10
        minLng -= lngDiff * 0.10
        maxLng += lngDiff * 0.10

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
                val tile = fetchTile(zoom, tx, ty)
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
        val polyX = points.map { toPxX(it.lng).toInt() }.toIntArray()
        val polyY = points.map { toPxY(it.lat).toInt() }.toIntArray()

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

        g.dispose()

        val baos = ByteArrayOutputStream()
        ImageIO.write(finalImage, "png", baos)
        val base64 = Base64.getEncoder().encodeToString(baos.toByteArray())

        return "data:image/png;base64,$base64"
    }

    /** Render a clean line-shape route map as an HTML img tag. */
    fun renderLegMapHtml(leg: RouteLeg, width: Int = 260, height: Int = 260): String {
        val dataUri = renderLegMapDataUri(leg, width, height)
        if (dataUri.isBlank()) return ""
        return "<img src=\"$dataUri\" style=\"width: ${width}px; height: auto; max-width: 100%; border-radius: 8px; border: 1px solid #cbd5e1;\" />"
    }
}
