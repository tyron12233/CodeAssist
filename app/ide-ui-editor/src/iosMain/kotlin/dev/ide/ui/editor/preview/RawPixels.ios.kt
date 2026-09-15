package dev.ide.ui.editor.preview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

actual fun imageFromRgba(pixels: ByteArray, width: Int, height: Int): ImageBitmap? = runCatching {
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
    Image.makeRaster(info, pixels, width * 4).toComposeImageBitmap()
}.getOrNull()

/**
 * Always null on iOS. The frames this reads are written by a *running* interpreted program in another
 * process, and the iOS host runs no programs, so nothing ever produces a frame file to read. It becomes a
 * real read (and delete) only alongside a run backend.
 */
actual fun readRunFrame(path: String): ByteArray? = null
