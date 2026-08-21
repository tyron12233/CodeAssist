package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Does the interpreter actually DISPATCH a `DrawScope` draw call — the layer between "the block resolves"
 * (proven headlessly in lang-kotlin's `CanvasDrawScopeLoweringTest`) and "the pixels paint" (device-only,
 * Skiko-gated). `DrawScope`'s members are value-class-heavy and name-mangled (`drawRect-n-J9OG0(long, …)`),
 * so this exercises the reflective member dispatch + value-class arg binding + defaulted-synthetic path against
 * the REAL `androidx.compose.ui.graphics.drawscope.DrawScope` interface.
 *
 * The receiver is a [Proxy] over `DrawScope` that RECORDS the method dispatched instead of rasterizing, so the
 * draw calls run with no graphics backend (a real `DrawScope.drawRect` would build a Skiko `Paint` and throw
 * `LibraryLoadException` headlessly). A recorded call name therefore proves the interpreter reached the draw
 * method; the actual paint is what still needs the emulator.
 */
class DrawScopeDispatchTest {


    private fun run(code: String, key: String, args: List<Any?>): Any? {
        val service = previewSymbolService()
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val fn = program[key] ?: error("no lowered function $key; have ${program.keys}")
        return Interpreter(program, ComposeDispatcher()).call(fn, args)
    }

    /** A `DrawScope` whose every call is recorded (never rasterized). Generic over all ~13 mangled draw methods
     *  via a [Proxy] invocation handler, so no method signature is spelled out by hand. */
    private class Recorder {
        val calls = ArrayList<String>()
        private val drawScopeClass = Class.forName("androidx.compose.ui.graphics.drawscope.DrawScope")
        private val ltr = Class.forName("androidx.compose.ui.unit.LayoutDirection")
            .enumConstants.first { it.toString() == "Ltr" }

        val scope: Any = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(drawScopeClass)) { _, method, _ ->
            val name = method.name
            if (name.startsWith("draw") || name.startsWith("record") || name.startsWith("clip")) calls += name
            when {
                name == "getLayoutDirection" -> ltr
                name.startsWith("getDensity") -> 1f
                name.startsWith("getFontScale") -> 1f
                name.startsWith("getSize") -> 0L        // packed Size (value-class, unboxed to long)
                name.startsWith("getCenter") -> 0L      // packed Offset
                name == "getDrawContext" -> null
                method.returnType == Integer.TYPE -> 0
                method.returnType == java.lang.Long.TYPE -> 0L
                method.returnType == java.lang.Float.TYPE -> 0f
                method.returnType == java.lang.Boolean.TYPE -> false
                else -> null
            }
        }
    }

    private val IMPORTS = """
        package demo
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.graphics.drawscope.DrawScope
        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.geometry.Size
    """.trimIndent()

    @Test
    fun drawRectDispatchesOnAnExplicitReceiver() {
        // s.drawRect(color = Color.Red, size = Size(...)) — named value-class arg + omitted defaulted params
        // (topLeft/alpha/style/colorFilter/blendMode), routed through the `drawRect-…$default` synthetic.
        val rec = Recorder()
        run(
            "$IMPORTS\nfun draw(s: DrawScope) { s.drawRect(color = Color.Red, size = Size(10f, 10f)) }",
            "draw/1", listOf(rec.scope),
        )
        assertTrue(rec.calls.any { it.startsWith("drawRect") }, "drawRect must dispatch on the DrawScope; recorded=${rec.calls}")
    }

    @Test
    fun drawRectDispatchesOnTheImplicitReceiver() {
        // with(s) { drawRect(...) } — the `Canvas { drawRect(...) }` shape: an implicit-receiver member call.
        val rec = Recorder()
        run(
            "$IMPORTS\nfun draw(s: DrawScope) { with(s) { drawRect(color = Color.Red, size = Size(10f, 10f)) } }",
            "draw/1", listOf(rec.scope),
        )
        assertTrue(rec.calls.any { it.startsWith("drawRect") }, "drawRect on the implicit receiver must dispatch; recorded=${rec.calls}")
    }

    @Test
    fun drawLineAndDrawCircleDispatch() {
        val rec = Recorder()
        run(
            "$IMPORTS\nfun draw(s: DrawScope) { with(s) { " +
                "drawLine(Color.Red, Offset(0f, 0f), Offset(10f, 10f))\n" +
                "drawCircle(Color.Black, radius = 4f) } }",
            "draw/1", listOf(rec.scope),
        )
        assertTrue(rec.calls.any { it.startsWith("drawLine") }, "drawLine must dispatch; recorded=${rec.calls}")
        assertTrue(rec.calls.any { it.startsWith("drawCircle") }, "drawCircle must dispatch; recorded=${rec.calls}")
    }
}

private class Doc(override val text: CharSequence) : DocumentSnapshot {
    override val file: VirtualFile = DispatchF(); override val version = 1L
    override fun length() = text.length
}
private class DispatchF : VirtualFile {
    override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
    override val exists = true; override val length = 0L
    override fun parent(): VirtualFile? = null
    override fun children(): List<VirtualFile> = emptyList()
    override fun contentHash() = ContentHash("")
    override fun readBytes() = ByteArray(0)
    override fun readText(): CharSequence = ""
}
