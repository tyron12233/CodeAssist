package dev.ide.android.layouteditor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.ide.android.layout.LayoutImage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

internal actual fun LayoutImage.toImageBitmap(): ImageBitmap? {
    argb?.let { px ->
        if (px.size >= width * height && width > 0 && height > 0) {
            // An ARGB int's bytes on a little-endian CPU are B, G, R, A: Skia's BGRA, as on the desktop.
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
            return Image.makeFromBitmap(bitmap).toComposeImageBitmap()
        }
    }
    return png?.let { runCatching { Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun decodeImageFile(path: String, maxPx: Int): ImageBitmap? = runCatching {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val bytes = ByteArray(data.length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()
