package dev.ide.fakecompose

/**
 * Mirrors the plugin UI API's `EditorPainter(id, …, paint: DrawScope.(EditorPaintContext) -> Unit)`: a CLASS
 * whose CONSTRUCTOR takes a lambda parameter carrying a RECEIVER, behind defaulted parameters. A constructor
 * is not a function callee, so this is the shape whose lambda body used to lose its receiver — see
 * [dev.ide.lang.kotlin.KotlinConstructorLambdaReceiverTest]. Staged into a jar there so it arrives through a
 * `@kotlin.Metadata` decode exactly as the real plugin API does.
 */
/** An inline value class, so [FakePaintScope.drawRoundRect] is NAME-MANGLED in the bytecode the way
 *  `DrawScope.drawRoundRect(color: Color, …)` is — the member resolves through `@Metadata`, not the JVM name. */
@JvmInline
value class PaintColor(val argb: Long)

interface FakePaintScope {
    fun drawRoundRect(color: PaintColor, radius: Float): Int
}

interface FakePaintContext {
    val lineHeight: Float
}

class FakePainter(
    val id: String,
    val order: Int = 1000,
    val paint: FakePaintScope.(FakePaintContext) -> Unit,
)
