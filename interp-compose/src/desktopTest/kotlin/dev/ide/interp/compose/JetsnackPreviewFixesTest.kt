package dev.ide.interp.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.interp.Interpreter
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Interpreter fixes surfaced by sweeping the Jetsnack previews (see the session notes). Each reproduces a
 * concrete preview failure against REAL bundled Compose.
 */
class JetsnackPreviewFixesTest {
    private fun classpathJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator).filter { it.endsWith(".jar") }.map { Paths.get(it) }

    private fun interpret(body: String, imports: String = ""): Any? {
        val code = "package demo\n$imports\n" + body.trimIndent()
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        return Interpreter(program, ComposeDispatcher()).call(program["box/0"]!!, emptyList())
    }

    @Test
    fun overloadedValueClassExtensionPropertyResolvesByReceiverType() {
        // `readExtensionProperty` picked the first `getSp` getter by reflection order and invoked with the raw
        // receiver, so `0.5.sp` (a Double) bound to `getSp(Int)` → "argument type mismatch" (Jetsnack Type.kt
        // typography, on every preview). It now routes through the dispatcher's EXTENSION path (overload
        // selection + coercion). A TextUnit is a value class → the getter returns a non-null packed value.
        val imp = "import androidx.compose.ui.unit.sp\nimport androidx.compose.ui.unit.dp"
        assertTrue(interpret("fun box(): Any = 0.5.sp", imp) != null, "0.5.sp (Double receiver) must resolve getSp(double)")
        assertTrue(interpret("fun box(): Any = 16.sp", imp) != null, "16.sp (Int receiver) must resolve getSp(int)")
        assertTrue(interpret("fun box(): Any = 24.dp", imp) != null, "24.dp (Int receiver) must resolve getDp(int)")
    }

    @Test
    fun stringIsEmptyAndIsBlankAreModeled() {
        // `CharSequence.isEmpty()`/`isBlank()` are @InlineOnly (no JVM method on StringsKt) — the interpreter
        // modeled only the isNot*/isNullOr* forms, so `text.isEmpty()` (Jetsnack Search) failed as "inline-only".
        assertEquals(true, interpret("""fun box(): Boolean = "".isEmpty()"""))
        assertEquals(false, interpret("""fun box(): Boolean = "x".isEmpty()"""))
        assertEquals(true, interpret("""fun box(): Boolean = "   ".isBlank()"""))
        assertEquals(false, interpret("""fun box(): Boolean = "x".isBlank()"""))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun offsetBlockReturningAValueClassRendersWithoutClassCast() {
        // `Modifier.offset { IntOffset(…) }` — a non-composable `Density.() -> IntOffset` block returns the
        // interpreter's UNBOXED Long; compiled `OffsetPxNode.measure` casts it to IntOffset AFTER the proxy
        // returns (at measure, outside the guard) → "Long cannot be cast to IntOffset" (Jetsnack SnackDetail).
        // The lambda proxy now boxes a value-class return. Render it so the offset block runs at measure time.
        val code = """
            package demo
            import androidx.compose.foundation.layout.Box
            import androidx.compose.foundation.layout.offset
            import androidx.compose.foundation.layout.size
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.IntOffset
            import androidx.compose.ui.unit.dp
            @Composable fun box() {
                Box(Modifier.size(50.dp).offset { IntOffset(x = 4, y = 8) }) { Text("hi") }
            }
        """.trimIndent()
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val lowering = KotlinPreviewLowering(service)
        val program = lowering.program(parsed)
        val classes = lowering.classes(parsed)
        val entry = program["box/0"]!!
        var hard: String? = null
        val partials = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val renderer = ComposePreviewRenderer(loader = null)
        val content: @Composable () -> Unit = {
            renderer.Render(entry, program, classes, emptyList(), onError = { hard = it.message }, onPartialError = { partials.add(it?.message) })
        }
        val threw = try {
            val scene = ImageComposeScene(200, 200, Density(1f), content = content)
            try { scene.render(0L) } finally { scene.close() }
            null
        } catch (t: Throwable) {
            if (t is UnsatisfiedLinkError || t is NoClassDefFoundError || t.javaClass.simpleName.contains("LibraryLoad")) return
            "${t.javaClass.simpleName}: ${t.message}"
        }
        val castErr = { s: String? -> s != null && s.contains("cannot be cast") && s.contains("IntOffset") }
        assertTrue(threw == null || !castErr(threw), "offset block must not throw an IntOffset ClassCastException; threw $threw")
        assertTrue(!castErr(hard) && partials.none(castErr), "no IntOffset cast failure; hard=$hard partials=${partials.filterNotNull()}")
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F(); override val version = 1L
        override fun length() = text.length
    }
    private class F : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash(""); override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
