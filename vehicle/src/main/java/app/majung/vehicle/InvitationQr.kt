package app.majung.vehicle

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

internal object InvitationQr {
    fun create(link: String): Bitmap {
        require(link.startsWith("https://") && link.length <= 512)
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 512, 512)
        val pixels = IntArray(512 * 512) { index -> if (matrix[index % 512, index / 512]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888)
    }
}
