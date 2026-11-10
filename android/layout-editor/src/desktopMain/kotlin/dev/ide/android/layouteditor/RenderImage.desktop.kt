package dev.ide.android.layouteditor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.ide.android.layout.LayoutImage
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

internal actual fun LayoutImage.toImageBitmap(): ImageBitmap? {
    argb?.let { px ->
        if (px.size >= width * height && width > 0 && height > 0) {
            // Skia's N32 on the desktop targets is BGRA little-endian, which is exactly an ARGB int's bytes.
            val bytes = ByteArray(width * height * 4)
            for (i in 0 until width * height) {
                val c = px[i]
                bytes[i * 4] = (c and 0xFF).toByte()
                bytes[i * 4 + 1] = ((c shr 8) and 0xFF).toByte()
                bytes[i * 4 + 2] = ((c shr 16) and 0xFF).toByte()
                bytes[i * 4 + 3] = ((c ushr 24) and 0xFF).toByte()
            }
            val bitmap = Bitmap()
            bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
            bitmap.installPixels(bytes)
            return bitmap.asComposeImageBitmapCompat()
        }
    }
    return png?.let { runCatching { Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
}

private fun Bitmap.asComposeImageBitmapCompat(): ImageBitmap = Image.makeFromBitmap(this).toComposeImageBitmap()

internal actual fun decodeImageFile(path: String, maxPx: Int): ImageBitmap? =
    runCatching { Image.makeFromEncoded(java.io.File(path).readBytes()).toComposeImageBitmap() }.getOrNull()
