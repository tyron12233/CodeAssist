package dev.ide.ui.editor.preview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

// Compose Multiplatform draws through Skia on iOS as it does on desktop, so the codec path is the same one.
actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    if (bytes.isEmpty()) null
    else runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

// The live native handle is an `android.graphics.Bitmap` produced by the on-device real-view render, which
// only the Android host performs. iOS takes the encoded path through [decodeImageBytes], as desktop does.
actual fun nativeImageToBitmap(handle: Any?): ImageBitmap? = null

actual fun encodeImagePng(image: ImageBitmap): ByteArray? = runCatching {
    Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
}.getOrNull()
