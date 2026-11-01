package com.securecall.app

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject

/** Pairing QR: the parent shows it once, the child scans it (name + pairing code + TURN settings). */
object Qr {
    private const val PREFIX = "SCQR1:"

    fun encode(name: String, code: String, turn: String, cfId: String, cfTok: String): String {
        val j = JSONObject().put("n", name).put("c", code)
        if (turn.isNotBlank()) j.put("t", turn)
        if (cfId.isNotBlank() && cfTok.isNotBlank()) { j.put("ci", cfId); j.put("ct", cfTok) }
        return PREFIX + Base64.encodeToString(j.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun decode(s: String): JSONObject? = try {
        val t = s.trim()
        if (!t.startsWith(PREFIX)) null
        else JSONObject(String(Base64.decode(t.removePrefix(PREFIX), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
    } catch (_: Exception) { null }

    fun bitmap(text: String, size: Int): Bitmap {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 2
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
