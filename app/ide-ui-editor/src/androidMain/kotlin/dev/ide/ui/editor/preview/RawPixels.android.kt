package dev.ide.ui.editor.preview

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer

actual fun imageFromRgba(pixels: ByteArray, width: Int, height: Int): ImageBitmap? = runCatching {
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        .apply { copyPixelsFromBuffer(ByteBuffer.wrap(pixels)) }
        .asImageBitmap()
}.getOrNull()
