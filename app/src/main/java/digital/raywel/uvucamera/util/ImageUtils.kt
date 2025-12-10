package digital.raywel.uvucamera.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import android.graphics.Color as GColor

// -----------------------
// Image Helper
// -----------------------

fun nv21ToJpeg(
    nv21: ByteArray,
    w: Int,
    h: Int,
    q: Int
): ByteArray {
    val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
    val out = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, w, h), q, out)
    return out.toByteArray()
}

fun yuyvToNv21(yuyv: ByteArray, width: Int, height: Int): ByteArray {
    val frameSize = width * height
    val nv21 = ByteArray(frameSize + frameSize / 2)

    var yIndex = 0
    var uvIndex = frameSize

    var i = 0
    var row = 0
    var col = 0

    while (i < yuyv.size) {
        val y1 = yuyv[i].toInt() and 0xFF
        val u  = yuyv[i + 1].toInt() and 0xFF
        val y2 = yuyv[i + 2].toInt() and 0xFF
        val v  = yuyv[i + 3].toInt() and 0xFF

        nv21[yIndex++] = y1.toByte()
        nv21[yIndex++] = y2.toByte()

        if (row % 2 == 0) {
            nv21[uvIndex++] = v.toByte()
            nv21[uvIndex++] = u.toByte()
        }

        col += 2
        if (col >= width) {
            col = 0
            row++
        }

        i += 4
    }
    return nv21
}

fun addTimestampToJpeg(jpeg: ByteArray): ByteArray {
    val original = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    val mutable = original.copy(Bitmap.Config.ARGB_8888, true)

    val timestamp = ZonedDateTime.now(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    val canvas = Canvas(mutable)
    val paint = Paint().apply {
        color = GColor.WHITE
        textSize = 24f
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, GColor.BLACK)
    }

    val margin = 20f

    val textBaseline = mutable.height - margin

    canvas.drawText(timestamp, margin, textBaseline, paint)

    val output = ByteArrayOutputStream()
    mutable.compress(Bitmap.CompressFormat.JPEG, 90, output)
    return output.toByteArray()
}

fun saveJpegToCache(context: Context, image: ByteArray): File {
    val cacheFile = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
    cacheFile.outputStream().use { it.write(image) }
    return cacheFile
}

fun loadImageFromCache(file: File): Bitmap {
    return BitmapFactory.decodeFile(file.absolutePath)
}

fun loadBase64FromCache(file: File): String {
    val bytes = file.readBytes()
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}