package dev.ide.android.layouteditor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ide.android.layout.LayoutImage

internal actual fun LayoutImage.toImageBitmap(): ImageBitmap? {
    (bitmap as? Bitmap)?.takeIf { !it.isRecycled }?.let { return it.asImageBitmap() }
    argb?.let { px ->
        if (px.size >= width * height && width > 0 && height > 0) {
            return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    return png?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
}

internal actual fun decodeImageFile(path: String, maxPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()
