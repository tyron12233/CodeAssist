package dev.ide.ui.editor.preview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import dev.ide.ui.backend.UiDrawable

/**
 * A [Painter] that draws a [UiDrawable] — the render-ready model of a `res/drawable` XML (vector / shape /
 * layer-list / state-list) — through [drawUiDrawable]. Lets a `painterResource(R.drawable.x)` in the on-device
 * Compose preview resolve an XML drawable to a real, crisp painter (a vector scales rather than rastering),
 * instead of only bitmap drawables.
 *
 * [intrinsicSize] is the drawable's own dp size in pixels ([density]-scaled) when it declares one (a `<vector>`'s
 * `width`/`height`, a `<shape>`'s `<size>`); otherwise [Size.Unspecified], so it fills whatever bounds the
 * caller gives (e.g. a `Modifier.size(...)`). A `<bitmap>`/file drawable is NOT handled here — the resolver
 * decodes those to a `BitmapPainter` directly.
 */
class UiDrawablePainter(
    private val drawable: UiDrawable,
    private val density: Float,
) : Painter() {

    override val intrinsicSize: Size = when (drawable) {
        is UiDrawable.Vector ->
            if (drawable.widthDp > 0f && drawable.heightDp > 0f)
                Size(drawable.widthDp * density, drawable.heightDp * density) else Size.Unspecified
        is UiDrawable.Shape ->
            if (drawable.intrinsicWidthDp > 0f && drawable.intrinsicHeightDp > 0f)
                Size(drawable.intrinsicWidthDp * density, drawable.intrinsicHeightDp * density) else Size.Unspecified
        else -> Size.Unspecified
    }

    // A tint / alpha (`Icon`'s tint, `Image(alpha = …)`) is applied by the base [Painter] via a graphics layer
    // over onDraw (its default applyColorFilter/applyAlpha), which is correct for the drawn vector — so no
    // override is needed here.
    override fun DrawScope.onDraw() {
        drawUiDrawable(drawable, Offset.Zero, size)
    }
}
