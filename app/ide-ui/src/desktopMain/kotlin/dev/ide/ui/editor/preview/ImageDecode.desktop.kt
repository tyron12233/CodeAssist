package dev.ide.ui.editor.preview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    if (bytes.isEmpty()) null
    else runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

// Desktop has no real-view runtime (no android.graphics.Bitmap) — the PNG path via decodeImageBytes is used.
actual fun nativeImageToBitmap(handle: Any?): ImageBitmap? = null

actual fun encodeImagePng(image: ImageBitmap): ByteArray? = runCatching {
    Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
}.getOrNull()
