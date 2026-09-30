package com.leejoe.artranslator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

object ImageUtils {
    fun imageProxyToUprightBitmap(image: ImageProxy): Bitmap {
        val nv21 = yuv420888ToNv21(image)
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            .compressToJpeg(Rect(0, 0, image.width, image.height), 94, out)
        val bytes = out.toByteArray()
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Unable to decode camera frame")
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return raw
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val output = ByteArray(width * height * 3 / 2)
        var p = 0
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yBase = yBuffer.position()
        for (row in 0 until height) {
            val rowBase = yBase + row * yPlane.rowStride
            for (col in 0 until width) output[p++] = yBuffer.get(rowBase + col * yPlane.pixelStride)
        }
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()
        for (row in 0 until height / 2) {
            val uRow = uBase + row * uPlane.rowStride
            val vRow = vBase + row * vPlane.rowStride
            for (col in 0 until width / 2) {
                output[p++] = vBuffer.get(vRow + col * vPlane.pixelStride)
                output[p++] = uBuffer.get(uRow + col * uPlane.pixelStride)
            }
        }
        return output
    }

    fun expanded(rect: Rect, width: Int, height: Int, ratio: Float = 0.08f): Rect {
        val dx = max(4, (rect.width() * ratio).toInt())
        val dy = max(4, (rect.height() * ratio).toInt())
        return Rect(
            max(0, rect.left - dx), max(0, rect.top - dy),
            min(width, rect.right + dx), min(height, rect.bottom + dy)
        )
    }

    fun crop(source: Bitmap, rect: Rect): Bitmap {
        val left = rect.left.coerceIn(0, source.width - 1)
        val top = rect.top.coerceIn(0, source.height - 1)
        val right = rect.right.coerceIn(left + 1, source.width)
        val bottom = rect.bottom.coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    fun softBackground(source: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(source, max(2, source.width / 14), max(2, source.height / 14), true)
        val blurred = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        if (small !== source) small.recycle()
        return blurred
    }

    fun jpegBase64(source: Bitmap, quality: Int = 86): String {
        val out = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    fun averageHash(source: Bitmap): Long {
        val tiny = Bitmap.createScaledBitmap(source, 8, 8, true)
        val values = IntArray(64)
        var total = 0L
        var i = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val c = tiny.getPixel(x, y)
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            val l = (r * 299 + g * 587 + b * 114) / 1000
            values[i++] = l
            total += l
        }
        if (tiny !== source) tiny.recycle()
        val avg = (total / 64).toInt()
        var hash = 0L
        values.forEachIndexed { index, value -> if (value >= avg) hash = hash or (1L shl index) }
        return hash
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
