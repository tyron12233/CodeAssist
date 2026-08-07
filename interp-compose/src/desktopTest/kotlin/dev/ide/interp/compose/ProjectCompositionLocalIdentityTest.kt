package dev.ide.interp.compose

import androidx.compose.runtime.CompositionLocal
import dev.ide.interp.Interpreter
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression: a custom-theme Compose preview (e.g. Jetsnack's `JetsnackTheme`) rendered a blank surface with
 * "No JetsnackColorPalette provided". Root cause: a project `CompositionLocal` is held in a top-level `val`
 * (`val LocalX = staticCompositionLocalOf { error(…) }`), and the interpreter re-evaluated a plain `val`'s
 * initializer on every read — so `staticCompositionLocalOf` minted a FRESH `ProvidableCompositionLocal` each
 * access. The theme's `CompositionLocalProvider(LocalX provides …)` stored under one instance and a later
 * `LocalX.current` read looked up a different instance, missed, and threw the local's default.
 *
 * The fix backs a plain-backing-field top-level `val` with a single cached instance (real Kotlin: a `<clinit>`
 * static field), so the local keeps ONE identity across reads. This exercises the REAL bundled
 * `staticCompositionLocalOf`, so it proves the object the interpreter hands back is stable — the exact identity
 * a `provides`/`.current` pair depends on. (Identity is the crux; no composition is needed to prove it.)
 */
class ProjectCompositionLocalIdentityTest {

    private fun classpathJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator).filter { it.endsWith(".jar") }.map { Paths.get(it) }

    private fun run(code: String, entry: String): Any? {
        val trimmed = code.trimIndent()
        val service = KotlinSymbolService(listOf(MemDir(listOf(MemFile("Main.kt", trimmed)))), classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(Doc(trimmed)) as KotlinParsedFile
        val program = dev.ide.lang.kotlin.interp.KotlinPreviewLowering(service).program(parsed)
        return Interpreter(program, ComposeDispatcher()).call(program[entry]!!, emptyList())
    }

    @Test
    fun projectCompositionLocalKeepsOneIdentityAcrossReads() {
        // Two reads of the same top-level `val` local must return the SAME ProvidableCompositionLocal. Before the
        // fix each read re-ran `staticCompositionLocalOf` → two distinct instances → the provide/current mismatch.
        val same = run(
            """
            package demo
            import androidx.compose.runtime.staticCompositionLocalOf
            val LocalColors = staticCompositionLocalOf<String> { error("No palette provided") }
            fun box(): Boolean = LocalColors === LocalColors
            """,
            "box/0",
        )
        assertTrue(same as Boolean, "a project CompositionLocal held in a top-level val must be one stable instance across reads")
    }

    @Test
    fun theLocalIsARealProvidableCompositionLocal() {
        // Sanity: the value really is a bundled Compose CompositionLocal (the reflectively-dispatched
        // staticCompositionLocalOf ran), not some interpreter stand-in — so the identity proven above is the
        // identity a real CompositionLocalProvider/.current pair would key on.
        val local = run(
            """
            package demo
            import androidx.compose.runtime.staticCompositionLocalOf
            val LocalColors = staticCompositionLocalOf<String> { error("No palette provided") }
            fun box(): Any = LocalColors
            """,
            "box/0",
        )
        assertNotNull(local, "the local must resolve")
        assertTrue(local is CompositionLocal<*>, "expected a real androidx CompositionLocal, got ${local::class.java.name}")
    }

    @Test
    fun distinctLocalsAreDistinctInstances() {
        // Guard against over-caching to a single slot: two DIFFERENT top-level locals must be different objects.
        val a = run(
            """
            package demo
            import androidx.compose.runtime.staticCompositionLocalOf
            val LocalA = staticCompositionLocalOf<String> { error("a") }
            val LocalB = staticCompositionLocalOf<String> { error("b") }
            fun box(): Any = LocalA
            """,
            "box/0",
        )
        val b = run(
            """
            package demo
            import androidx.compose.runtime.staticCompositionLocalOf
            val LocalA = staticCompositionLocalOf<String> { error("a") }
            val LocalB = staticCompositionLocalOf<String> { error("b") }
            fun box(): Any = LocalB
            """,
            "box/0",
        )
        assertNotNull(a); assertNotNull(b)
        assertTrue(a !== b, "two distinct top-level locals must be distinct instances (cache is keyed per property, not shared)")
    }

    // --- an in-memory source root, so the service indexes the file and the bare `LocalColors` ref resolves ---
    private class MemDir(private val kids: List<VirtualFile>) : VirtualFile {
        override val path = "src"; override val name = "src"; override val isDirectory = true
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = kids
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
    private class MemFile(override val name: String, private val content: String) : VirtualFile {
        override val path = name; override val isDirectory = false; override val exists = true
        override val length get() = content.length.toLong()
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash(content.hashCode().toString())
        override fun readBytes() = content.toByteArray()
        override fun readText(): CharSequence = content
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = MemFile("Main.kt", text.toString()); override val version = 1L
        override fun length() = text.length
    }
}
