package dev.ide.preview

/** A measured size in pixels. */
data class Size(val width: Int, val height: Int)

/**
 * Bit-compatible reimplementation of `android.view.View.MeasureSpec` — the size+mode packing the layout
 * pass speaks. Pure integer math, no native dependency, so the engine runs identically on desktop and ART
 * (and in a headless test). Mirrors the framework constants exactly so a custom view that does its own
 * `MeasureSpec.getMode(...)` math sees the values it expects.
 */
object MeasureSpec {
    const val UNSPECIFIED = 0
    const val EXACTLY = 1 shl 30
    const val AT_MOST = 2 shl 30
    private const val MODE_MASK = 0x3 shl 30

    fun makeMeasureSpec(size: Int, mode: Int): Int = (size and MODE_MASK.inv()) or (mode and MODE_MASK)
    fun getMode(spec: Int): Int = spec and MODE_MASK
    fun getSize(spec: Int): Int = spec and MODE_MASK.inv()

    fun exactly(size: Int): Int = makeMeasureSpec(size, EXACTLY)
    fun atMost(size: Int): Int = makeMeasureSpec(size, AT_MOST)
    fun unspecified(): Int = makeMeasureSpec(0, UNSPECIFIED)
}
