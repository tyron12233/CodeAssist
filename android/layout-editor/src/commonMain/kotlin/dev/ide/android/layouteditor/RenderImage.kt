package dev.ide.android.layouteditor

import androidx.compose.ui.graphics.ImageBitmap
import dev.ide.android.layout.LayoutImage

/**
 * The render as something Compose can draw, taking the most direct form the host offered: the platform bitmap
 * it drew into (no copy), packed pixels, or an encoded image. Null when none of them is usable here.
 */
internal expect fun LayoutImage.toImageBitmap(): ImageBitmap?

/** A project image file (a drawable or mipmap PNG/JPEG/WebP) decoded for a thumbnail, at most [maxPx] on a side. */
internal expect fun decodeImageFile(path: String, maxPx: Int): ImageBitmap?
