package dev.ide.ui.editor.preview

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    if (bytes.isEmpty()) null
    else runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

actual fun nativeImageToBitmap(handle: Any?): ImageBitmap? =
    (handle as? android.graphics.Bitmap)?.let { runCatching { it.asImageBitmap() }.getOrNull() }

actual fun encodeImagePng(image: ImageBitmap): ByteArray? = runCatching {
    java.io.ByteArrayOutputStream().use { out ->
        // PNG ignores the quality argument, and lossless is what an icon needs.
        image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}.getOrNull()
