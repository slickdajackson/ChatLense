package app.chatlens.vision

import android.graphics.Bitmap
import android.graphics.Rect
import app.chatlens.core.Bounds
import java.io.ByteArrayOutputStream
import java.io.File

object ImageTools {

    /** Schneidet [b] (Bildschirmkoordinaten) aus dem Screenshot, begrenzt auf dessen Flaeche. */
    fun crop(screen: Bitmap, b: Bounds): Bitmap? {
        val r = Rect(
            b.l.coerceIn(0, screen.width), b.t.coerceIn(0, screen.height),
            b.r.coerceIn(0, screen.width), b.b.coerceIn(0, screen.height),
        )
        if (r.width() < 16 || r.height() < 16) return null
        return Bitmap.createBitmap(screen, r.left, r.top, r.width(), r.height())
    }

    fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val f = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * f).toInt().coerceAtLeast(1), (src.height * f).toInt().coerceAtLeast(1), true)
    }

    fun jpeg(src: Bitmap, quality: Int = 80): ByteArray {
        val out = ByteArrayOutputStream()
        src.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    fun saveJpeg(src: Bitmap, file: File, quality: Int = 80) {
        file.parentFile?.mkdirs()
        file.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }
}
