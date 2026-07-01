package dev.ide.ui.editor.preview

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    if (bytes.isEmpty()) null
    else runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

actual fun nativeImageToBitmap(handle: Any?): ImageBitmap? =
    (handle as? android.graphics.Bitmap)?.let { runCatching { it.asImageBitmap() }.getOrNull() }
