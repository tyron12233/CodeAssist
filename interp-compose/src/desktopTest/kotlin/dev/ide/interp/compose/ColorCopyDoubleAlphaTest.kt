package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.interp.ReflectiveDispatcher
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.SourceSpan
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A Jetsnack FilterBar preview reported `no static copy(2) on androidx.compose.ui.graphics.Color
 * [args: [java.lang.Long, java.lang.Double]; … 1 $default synthetic(s) (long,float,float,float,float,int,Object)]`
 * on device. `JetsnackSurface`'s `calculateForeground` computes
 * `val alpha = ((4.5f * ln(elevation.value + 1)) + 2f) / 100f` then `Color.White.copy(alpha = alpha)`; the value
 * is a `Float` in compiled Kotlin, but the interpreter's floating math (the `ln` overload / mixed arithmetic)
 * widens it to a `Double`, and the dispatch fit gate wouldn't narrow `Double`→`Float` for `copy`'s param — so
 * the defaulted-arg `$default` synthetic (present!) was rejected and the call failed. The fit gate now admits a
 * floating value for a floating param and converts it (the float analog of the existing Int→Long widening).
 */
class ColorCopyDoubleAlphaTest {

    private fun classpathJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator).filter { it.endsWith(".jar") }.map { Paths.get(it) }

    /** Deterministic dispatch-level guard: a MEMBER `Color.copy` whose alpha arg is a `Double` (exactly the
     *  device diagnostic's `[Long, Double]`), dispatched against REAL Compose through the reflective path that
     *  routes a value-class member's defaulted-arg call through `copy-…$default`. */
    @Test
    fun copyWithADoubleAlphaNarrowsToTheFloatDefaultSynthetic() {
        val colorKt = Class.forName("androidx.compose.ui.graphics.ColorKt")
        val make = colorKt.methods.first {
            it.name == "Color" && it.parameterCount == 1 && it.parameterTypes[0] == Long::class.javaPrimitiveType
        }
        val white = make.invoke(null, 0xFFFFFFFFL) as Long // the unboxed (packed) Color the interpreter holds
        val callee = ResolvedCallable.Library(
            displayName = "copy", ownerFqn = "androidx.compose.ui.graphics.Color", methodName = "copy",
            paramTypes = emptyList(), isStatic = false, isConstructor = false, isInline = false, descriptorPrecise = true,
        )
        val call = RNode.Call(
            callee, DispatchKind.MEMBER, receiver = null, args = emptyList(),
            callSiteKey = CallSiteKey(0), source = SourceSpan(0, 0),
        )
        val result = ReflectiveDispatcher().dispatch(call, receiver = white, args = listOf(0.5)) // 0.5 is a Double
        assertTrue(result is Long, "copy(alpha=<Double>) must narrow to the float `\$default` synthetic and yield a Color; was $result")
    }

    /** End-to-end: the exact `Surface.kt` `calculateForeground` shape — the interpreter computes a Double alpha
     *  and must still build the overlay Color via `Color.White.copy(alpha = …)`. */
    @Test
    fun surfaceCalculateForegroundComputesAndCopies() {
        val code = """
            package demo
            import androidx.compose.ui.graphics.Color
            import kotlin.math.ln
            fun box(): Any {
                val alpha = ((4.5f * ln(2f + 1)) + 2f) / 100f
                return Color.White.copy(alpha = alpha)
            }
        """.trimIndent()
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val result = Interpreter(program, ComposeDispatcher()).call(program["box/0"]!!, emptyList())
        assertTrue(result is Long, "Color.White.copy(alpha = <computed>) must build a Color; was $result")
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
