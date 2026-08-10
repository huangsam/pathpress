package com.pathpress.export

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Utility for generating scannable QR Code PNG Base64 Data URIs using ZXing. */
object QrCodeGenerator {

    /**
     * Generates a Base64-encoded PNG Data URI for a given URL with tight margins.
     *
     * @param url Destination URL to encode into the QR code
     * @param width Width in pixels (default 160)
     * @param height Height in pixels (default 160)
     * @return Data URI string suitable for direct HTML `src` attribute
     */
    fun generateQrCodeDataUri(url: String, width: Int = 160, height: Int = 160): String {
        if (url.isBlank()) return ""
        return try {
            val qrCodeWriter = QRCodeWriter()
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val bitMatrix = qrCodeWriter.encode(url, BarcodeFormat.QR_CODE, width, height, hints)
            val os = ByteArrayOutputStream()
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", os)
            val base64 = Base64.getEncoder().encodeToString(os.toByteArray())
            "data:image/png;base64,$base64"
        } catch (_: Exception) {
            ""
        }
    }
}
