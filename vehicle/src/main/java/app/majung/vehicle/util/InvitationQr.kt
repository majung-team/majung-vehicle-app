package app.majung.vehicle.util


import app.majung.vehicle.*
import app.majung.vehicle.ui.*
import app.majung.vehicle.ui.sections.*
import app.majung.vehicle.navi.*
import app.majung.vehicle.domain.*
import app.majung.vehicle.data.api.*
import app.majung.vehicle.data.storage.*
import app.majung.vehicle.util.*
import app.majung.vehicle.service.*

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
