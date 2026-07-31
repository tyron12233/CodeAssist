package dev.ide.interp.compose

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

/**
 * Regression: calling an `Any`-override (`toString`/`hashCode`/`equals`) on a BOXED inline value-class instance
 * failed with "no method `toString`(0) on …Role". A value-class member compiles to a STATIC `name-<hash>` form,
 * and `@Metadata` authoritatively maps the Kotlin `toString` to `toString-impl` — so the boxed class's plainly-
 * named `toString()` bridge is rejected by the metadata-driven name resolver, and instance dispatch finds
 * nothing. The dispatcher now falls back (on the miss path) to the static impl, unboxing the receiver.
 *
 * `SemanticsConfiguration[SemanticsProperties.Role]` is a reliable source of a genuinely boxed `Role`: the
 * `setRole-<hash>(…, int)` setter boxes the `int` back to `Role` (`box-impl`) before storing it as an erased
 * `Object`, so reading it back hands the interpreter a boxed value-class instance — exactly the shape that
 * `State<Color>.value` / a generic `get` produces in a real preview.
 */
class BoxedValueClassMemberCallTest {

    private fun classpathJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator).filter { it.endsWith(".jar") }.map { Paths.get(it) }

    /** Parse + lower + interpret `box/0`, prepending the shared imports (joined with a newline so the last
     *  import doesn't run into `fun box`). */
    private fun run(body: String): Any? {
        val code = IMPORTS + "\n" + body.trimIndent()
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        return Interpreter(program, ComposeDispatcher()).call(program["box/0"]!!, emptyList())
    }

    @Test
    fun toStringOnABoxedValueClassRoutesToTheStaticImpl() {
        // The `as Role` fixes the erased `get<T>` result's static type so `.toString()` resolves; at runtime it
        // is a boxed `Role`, and `.toString()` must reach `Role.toString-impl` → "RadioButton".
        val result = run(
            """
            fun box(): String {
                val config = SemanticsConfiguration()
                config.apply { role = Role.RadioButton }
                return (config[SemanticsProperties.Role] as Role).toString()
            }
            """,
        )
        assertEquals("RadioButton", result, "toString() on a boxed value class must route to the static `toString-impl` and yield the right value")
    }

    @Test
    fun equalsAndHashCodeOnABoxedValueClassRouteToTheStaticImpl() {
        // Two configs give two independently-boxed `Role`s (`equals-impl(int, Object)` needs BOTH operands boxed
        // for its `other is Role` check to hold). Proves the 2-arg `equals-impl` and the 0-arg `hashCode-impl`
        // both dispatch: equal roles compare equal with matching hashes, a different role compares unequal.
        val result = run(
            """
            fun box(): Boolean {
                val c1 = SemanticsConfiguration(); c1.apply { role = Role.RadioButton }
                val c2 = SemanticsConfiguration(); c2.apply { role = Role.RadioButton }
                val c3 = SemanticsConfiguration(); c3.apply { role = Role.Button }
                val a = c1[SemanticsProperties.Role] as Role
                val aAgain = c2[SemanticsProperties.Role] as Role
                val b = c3[SemanticsProperties.Role] as Role
                return a.equals(aAgain) && !a.equals(b) && a.hashCode() == aAgain.hashCode()
            }
            """,
        )
        assertEquals(true, result, "equals/hashCode on a boxed value class must route to the static `equals-impl`/`hashCode-impl`")
    }

    private companion object {
        private val IMPORTS = """
            package demo
            import androidx.compose.ui.semantics.Role
            import androidx.compose.ui.semantics.SemanticsConfiguration
            import androidx.compose.ui.semantics.SemanticsProperties
            import androidx.compose.ui.semantics.role
        """.trimIndent()
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
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
