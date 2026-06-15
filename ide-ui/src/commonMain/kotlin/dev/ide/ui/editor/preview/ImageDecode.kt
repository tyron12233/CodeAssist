package dev.ide.ui.editor.preview

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decode encoded image bytes (PNG/WebP/JPEG/…) into a Compose [ImageBitmap] for the bitmap preview.
 * Resolved per platform: Skia (`org.jetbrains.skia.Image`) on desktop, `BitmapFactory` on Android.
 * Returns null on an undecodable/empty buffer.
 */
expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?
