package dev.ide.ui.editor.preview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    if (bytes.isEmpty()) null
    else runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
