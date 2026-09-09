package dev.ide.fakecompose

/**
 * Mirrors the shape of `androidx.compose.ui.graphics.drawscope.DrawScope` + `Canvas`, so the Compose-preview
 * lowering can be exercised on the CANVAS/DRAW path without the Compose toolchain. The three ways a draw block
 * differs from the layout content slots the other fakes cover:
 *   1. the receiver lambda is NON-`@Composable` (`FakeDrawScope.() -> Unit`), unlike `FakeRow`/`FakeBox`;
 *   2. the draw members take INLINE VALUE-CLASS params (`DrawColor`/`DrawOffset`/`DrawSize`, all over `Long`/
 *      `Float`), so they are name-mangled on the JVM (`drawRect-<hash>`) — the `drawRect(color, size)` shape;
 *   3. the transforms (`inset`/`withTransform`) are INLINE functions taking a nested `FakeDrawScope.() -> Unit`.
 */

@JvmInline value class DrawColor(val argb: Long) {
    companion object {
        val Red get() = DrawColor(0xFFFF0000L)
        val Black get() = DrawColor(0xFF000000L)
    }
}

@JvmInline value class DrawOffset(val packed: Long) {
    companion object { val Zero get() = DrawOffset(0L) }
}

@JvmInline value class DrawSize(val packed: Long) {
    companion object { val Zero get() = DrawSize(0L) }
}

/** The draw receiver: value-class properties (`size`/`center`) + value-class-param member draw calls with
 *  defaulted trailing params (the `drawRect(color = …, size = …)` named-omitting-defaults shape). */
interface FakeDrawScope {
    val size: DrawSize
    val center: DrawOffset

    fun drawRect(color: DrawColor, topLeft: DrawOffset = DrawOffset.Zero, size: DrawSize = this.size, alpha: Float = 1f)
    fun drawLine(color: DrawColor, start: DrawOffset, end: DrawOffset, strokeWidth: Float = 0f)
    fun drawCircle(color: DrawColor, radius: Float = 0f, center: DrawOffset = this.center)
}

/** Like Compose's `Canvas(modifier) { /* this: DrawScope */ }` — a NON-composable receiver lambda draw slot. */
@androidx.compose.runtime.Composable
fun FakeCanvas(modifier: FakeModifier = FakeModifier, onDraw: FakeDrawScope.() -> Unit) {}

/** Like `DrawScope.inset { }` / `withTransform { }` — INLINE transform helpers wrapping a nested draw block. */
inline fun FakeDrawScope.fakeInset(inset: Float, block: FakeDrawScope.() -> Unit) { block() }

inline fun FakeDrawScope.fakeWithTransform(
    transformBlock: FakeDrawScope.() -> Unit,
    drawBlock: FakeDrawScope.() -> Unit,
) { drawBlock() }
