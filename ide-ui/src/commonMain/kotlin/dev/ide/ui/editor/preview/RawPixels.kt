package dev.ide.ui.editor.preview

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decode raw RGBA_8888 pixels ([width] * [height] * 4 bytes, row-major) into an image.
 *
 * The sibling of [decodeImageBytes], for pixels that were never encoded: a windowed program's frames arrive
 * as a straight pixel dump from another process, and re-encoding them to PNG on the way just to decode them
 * here would cost more than the frame is worth.
 */
expect fun imageFromRgba(pixels: ByteArray, width: Int, height: Int): ImageBitmap?

/**
 * Read a streamed frame's raw pixels and delete the file.
 *
 * The producing process writes one file per frame and never cleans up, on purpose: only the consumer knows
 * when it has the pixels. Both IDE targets are JVMs, so the one implementation lives in `jvmShared`.
 */
expect fun readRunFrame(path: String): ByteArray?
