package dev.ide.ui.editor.core

/**
 * Foundation for a future **raw monospace glyph-draw fast path** for the editor's text layer.
 *
 * Today every visible line is shaped into a Compose [androidx.compose.ui.text.TextLayoutResult] (a full Skia
 * paragraph: BiDi, font fallback, glyph shaping) and cached per line. That cache means shaping is a per-*new*-
 * line cost, not per-frame — great for typing — but a fast fling through a huge file still shapes every line it
 * passes on the draw thread. The sora-editor-style answer is to draw the common case (left-to-right ASCII in a
 * monospace font) with a direct `nativeCanvas.drawText` per colored run at `x = column · charWidth`, skipping
 * paragraph shaping entirely, and fall back to the shaped path only for lines that actually need it.
 *
 * The *subtle* part of that — and the part a "does it look right on a device" check would silently get wrong —
 * is deciding **which lines are safe** to position by `column · charWidth`. That decision is a pure predicate
 * over the line's characters ([monospaceSafe]); this file implements and (in `MonospaceTextTest`) exhaustively
 * tests it, so the eventual platform draw code can rely on it. The platform glyph draw itself (an `expect`/
 * `actual` over skiko `nativeCanvas` vs `android.graphics.Canvas`) and any off-thread pre-shaping are the
 * remaining steps and are deliberately deferred to a change that can be verified on a real rendering surface —
 * they cannot be validated headlessly, and mis-positioned glyphs or a shared-cache data race would regress the
 * single hottest path in the editor.
 */

/**
 * True when [text] can be positioned by `column · charWidth` — i.e. every character occupies exactly one
 * monospace cell advancing left to right. Conservative on purpose: only printable ASCII (`U+0020`..`U+007E`)
 * qualifies. Everything else is treated as unsafe and must go through the shaped [androidx.compose.ui.text.
 * TextLayoutResult] path, because it can break the one-cell-per-column assumption:
 *  - a tab has a variable, tab-stop-relative advance;
 *  - CJK / fullwidth characters occupy two cells;
 *  - combining marks occupy zero cells;
 *  - RTL runs reorder visually;
 *  - emoji / ZWJ sequences and characters served by a fallback font need not share the base advance.
 *
 * An empty line is trivially safe. Note this gates only *positioning by column*; a monospace font's ligatures
 * (e.g. `->`) keep the per-character advance, so they do NOT make a line unsafe.
 */
internal fun monospaceSafe(text: CharSequence): Boolean {
    for (i in text.indices) {
        val c = text[i]
        if (c < ' ' || c > '~') return false // outside printable ASCII (also rejects '\t', control chars)
    }
    return true
}

/** The x offset (before scroll/gutter) of visual column [col] on a [monospaceSafe] line. Exact for a monospace
 *  font: equal to what `TextLayoutResult.getHorizontalPosition(col)` returns, without shaping the line. */
internal fun monospaceColumnX(col: Int, charWidth: Float): Float = col * charWidth

/** The visual column at pixel [x] (before scroll/gutter) on a [monospaceSafe] line, rounding at the half-cell
 *  boundary — the inverse of [monospaceColumnX], for tap/caret hit-testing without shaping. Clamped to >= 0. */
internal fun monospaceColumnAt(x: Float, charWidth: Float): Int {
    if (charWidth <= 0f) return 0
    val col = ((x / charWidth) + 0.5f).toInt()
    return if (col < 0) 0 else col
}
