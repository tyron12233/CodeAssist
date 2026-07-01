package dev.ide.ui.editor.preview

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decode encoded image bytes (PNG/WebP/JPEG/…) into a Compose [ImageBitmap] for the bitmap preview.
 * Resolved per platform: Skia (`org.jetbrains.skia.Image`) on desktop, `BitmapFactory` on Android.
 * Returns null on an undecodable/empty buffer.
 */
expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?

/**
 * Wrap a live native image handle (an `android.graphics.Bitmap` from the on-device real-view render, carried
 * as `Any?`) into a Compose [ImageBitmap] with no encode/decode. Android wraps the Bitmap directly; desktop
 * never produces one and returns null (the PNG path via [decodeImageBytes] is used instead).
 */
expect fun nativeImageToBitmap(handle: Any?): ImageBitmap?
